# AI cleanup — implementation report

Adds optional LLM post-processing of transcriptions, modelled on
[Handy](https://github.com/cjpais/handy)'s
[post-processing](https://handy.computer/docs/post-processing) feature.

Status: written and verified as far as a machine without a phone can verify it
(see [Verification](#verification)). Not yet run on a device.

---

## 1. What was built

A second microphone on the voice keyboard. It records identically to the main
one, then sends the finished transcription to an OpenAI-compatible
chat-completions endpoint before inserting the result. Fixing grammar,
reformatting, translating — whatever the user's prompt asks for.

Off by default. The main microphone is untouched and stays fully offline.

| Piece | File |
|---|---|
| Request path (no Android deps) | `app/.../PostProcessClient.java` |
| Android wrapper (Context, Log, threading) | `app/.../PostProcessor.java` |
| Settings storage | `app/.../PostProcessPrefs.java` |
| Settings screen | `app/.../PostProcessActivity.java`, `res/layout/activity_post_process.xml` |
| Second mic on the keyboard | `res/layout/ime_layout.xml`, `RustInputMethodService.java` |
| Icon and button surface | `res/drawable/ic_mic_ai.xml`, `res/drawable/bg_ime_mini_mic.xml` |
| Entry point on the home screen | `res/layout/activity_main.xml`, `MainActivity.java` |
| Cleartext policy for LAN servers | `res/xml/network_security_config.xml` |
| Test harness | `tools/verify/` |

**No Rust was modified.** The Rust core (`src/`, ~2,250 lines) ends its work
when it calls `onTextTranscribed(...)` across JNI into Java. Post-processing
hooks in strictly after that boundary, so the entire feature lives on the Java
side. This is also why the feature can be verified without the NDK, the
`aarch64` Rust target, a C++ build of transcribe.cpp, or the 485 MB model.

---

## 2. Decisions, and why

### Java rather than Rust for the HTTP call

The natural instinct is to put networking in the Rust core alongside everything
else. Rejected: it would mean adding a TLS stack (`rustls` + `ring`, or
OpenSSL) and cross-compiling it for the NDK, for a single HTTP POST. Using
`HttpURLConnection` on the Java side means TLS comes from the platform trust
store, there is no new native dependency, and — as it turned out — the whole
request path can be tested on a plain JVM.

### One code path, not a provider abstraction

"OpenAI-compatible `/chat/completions`" is the de-facto interface for Ollama,
LM Studio, llama.cpp's server, vLLM, OpenAI, Groq, OpenRouter, Together, and
Anthropic's compatibility endpoint. A single client covers local and cloud
alike. Presets in the UI only prefill a URL and a plausible model name; nothing
is hardcoded to a vendor.

### Marker/config files, not SharedPreferences

Matches every existing setting in this app (`auto_record`, `theme_mode`,
`model_threads`). The reason is not style: the IME runs in a separate process
(`:ime`), where SharedPreferences are not reliably shared.

### A separate trigger, not a global toggle

Following Handy. Post-processing is chosen per recording, at the moment of
recording, rather than being a mode that silently changes what the main mic
does. The user always knows whether the text they are about to get went through
a model.

### Failure falls back to the raw transcription

Post-processing is a bonus, never a gate. If the server is unreachable, the key
is rejected, the request times out, or the feature is enabled but not
configured, the raw transcription is inserted anyway and the keyboard's status
line explains what went wrong. Losing dictated words to a network error would
be a far worse outcome than inserting them unpolished.

### `PostProcessClient` must stay free of Android imports

Deliberate, and load-bearing: it is the only reason the request path can be
tested without a device. The Android-facing pieces live in `PostProcessor`. If
`PostProcessClient` ever needs `android.jar` to compile, the feature has
quietly become untestable off-device — treat that as a design regression, not a
build error to work around.

---

## 3. Behaviour details worth knowing

- **Reasoning-model output is stripped.** DeepSeek-R1 and similar emit their
  chain of thought inline in the message content. Typing that into the user's
  text field would be worse than not post-processing at all, so `<think>`,
  `<thinking>` and `<reasoning>` blocks are removed.
- **Added quotes are unwrapped**, because models wrap the answer when told
  "reply with the text only" — but only when the quotes enclose the whole
  string and none of the same kind appear inside, so dictated speech containing
  quotations is left alone.
- **The transcript is substituted literally.** `String.replace`, not
  `replaceAll` — a dictated "$1" would otherwise be eaten as a regex
  replacement.
- **`max_tokens` is always sent.** Anthropic's compatibility layer requires it,
  and it is a useful ceiling everywhere else.
- **Mutual exclusion between the mics.** While a recording runs, the mic that
  did not start it is dimmed and disabled, so the mode cannot change
  mid-session.
- **Audio focus is released before the network call**, not after, so the user's
  music doesn't stay ducked for the round-trip.
- **Deferred commit still applies.** If no field is focused when the LLM
  replies (more likely now that a round-trip is involved), the text is held and
  committed when a field is focused again, reusing the existing
  `pendingCommitText` path.

---

## 4. Security and privacy

| Concern | Position |
|---|---|
| Audio | Never leaves the device. Unchanged. |
| Text | Leaves the device **only** when the second mic is used, to the endpoint the user configured. |
| Default | Off. The second mic is not even visible until enabled. |
| API key | Plain text in the app's private storage — the same protection the other settings get, and the same SharedPreferences would give. Not hardware-backed. |
| Cleartext HTTP | Permitted app-wide via `network_security_config.xml`. |

The cleartext decision deserves its own note. LAN servers cannot obtain a
certificate for an IP address, and Android blocks `http://` by default at
targetSdk 35. A per-domain allowlist is impossible for an address the user
types at runtime. The exposure is narrow — the app makes no network requests of
its own, so the only outbound traffic is the user's configured endpoint — and
the settings screen warns when that endpoint is non-loopback `http://`. The
platform default trust anchors are left untouched for `https://`.

The README's "no network is required" claim was qualified rather than left to
rot.

---

## 5. Verification

`tools/verify/` runs both tiers in Docker; nothing is installed on the host.

```sh
tools/verify/run.sh
```

**Tier 1 — 29 tests, plain JVM.** Request building, prompt templating, response
parsing, `<think>` stripping, quote unwrapping, HTTP status mapping, timeouts,
oversized responses, unresolvable hosts. Runs against a stub OpenAI-compatible
server built on the JDK's own `com.sun.net.httpserver`, bound to an ephemeral
loopback port — no network, no subprocess, no emulator, every case
deterministic.

**Tier 2 — `:app:compileDebugJavaWithJavac`.** AAPT2 over every resource,
R.java generation, and javac against `android.jar` plus the resolved
Material/AppCompat AARs.

Both pass.

### The bug the tests caught

The first run failed one test, and it was production code, not the test.

`HttpURLConnection` begins an internal auth-retry on HTTP 401. In
`setFixedLengthStreamingMode` the request body cannot be replayed, so it
abandons that path and leaves `getErrorStream()` returning null. Measured:

| Status | with streaming mode | without |
|---|---|---|
| **401** | **error body lost** | body present |
| 403 / 404 / 429 / 500 | body present | body present |

Only 401 — which is the wrong-API-key case, by far the most common failure, and
exactly where the provider's explanation ("expired key", "insufficient quota")
is most useful. Users would have seen a generic message and nothing more.

The mechanism was confirmed with a probe varying one factor at a time rather
than guessed. Streaming mode was removed; request bodies here are a few KB, so
buffering costs nothing. The failing test is now the regression pin and the
code carries a comment explaining why not to reintroduce it.

### Not verified

- **The Rust core and the JNI boundary.** Untouched by this change and
  unreachable from these tests.
- **APK assembly.** Needs the NDK, the `aarch64` Rust target, a C++ build of
  transcribe.cpp and the 485 MB model.
- **On-device behaviour.** Keyboard layout and spacing, tinting, the commit
  path into real editors, and the feel of the round-trip latency.

Next step is [docs/build-and-run.md](build-and-run.md).

---

## 6. Known rough edges

- On a 360 dp-wide screen the mic-level glow (200 dp, centred) comes within
  ~8 dp of the second mic. It draws underneath so nothing breaks, but the
  spacing was chosen without a device to look at.
- `tintAiMic` uses `setColorFilter(int)`, which is deprecated. It follows the
  existing `tintRecordButton` for consistency; worth a separate cleanup pass
  across both.
- Timeouts are fixed constants (10 s connect, 45 s read). A slow local model on
  older hardware could exceed the read timeout; if that shows up in practice it
  should become a setting.
- Only the keyboard has the second mic. The voice popup panel
  (`RecognizeActivity`) and the background `RecognitionService` do not, by
  choice — the service has no UI, so it would need an always-on/never toggle
  rather than a button, which is a different interaction model.
