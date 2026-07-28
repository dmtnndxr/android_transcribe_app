# Containerised verification

Type-checks the app and runs the post-processing tests without installing a JDK
or the Android SDK on the host.

```sh
tools/verify/run.sh          # both tiers
tools/verify/run.sh tier1    # logic tests only (~3 s after the first run)
tools/verify/run.sh tier2    # resources + type-check only (~1 min)
tools/verify/run.sh shell    # interactive shell in the image
tools/verify/run.sh clean    # delete the image and the Gradle cache volume
```

The first run builds the image (~1 GB: JDK 17, Android SDK platform 35,
build-tools 35) and downloads Gradle 8.9 plus the AARs into a named volume.
Later runs are incremental.

## What each tier covers

| | Tier 1 | Tier 2 |
|---|---|---|
| Runs | `PostProcessClientTest` on a plain JVM | `:app:compilePlusDebugJavaWithJavac` |
| Checks | request building, prompt templating, response parsing, `<think>` stripping, quote unwrapping, HTTP status → message mapping, timeouts, oversized responses | AAPT2 resource validation, R.java generation, javac against `android.jar` and the resolved Material/AppCompat AARs |
| Needs | JDK only | Android SDK, no NDK |

Tier 1 talks to `StubServer`, an OpenAI-compatible endpoint built on the JDK's
own `com.sun.net.httpserver` and bound to an ephemeral loopback port. No
network, no subprocess, no emulator — every case including failures is
deterministic.

## Optional: live OpenRouter test

```sh
export OPENROUTER_API_KEY=sk-or-v1-...
export OPENROUTER_MODEL=openai/gpt-4o-mini   # optional
tools/verify/run.sh tier1
```

`OpenRouterLiveTest` then runs against the real API: a full round-trip, a
custom-prompt case, a rejected key, and an unknown model. Skipped entirely when
the variable is unset, so the default run stays offline, free and
deterministic.

What it adds over the stub: the stub encodes what the OpenAI-compatible spec
*says*; this checks what a provider *does* — the real response envelope, real
auth handling, real error bodies. The rejected-key case in particular is the
real-provider counterpart to the `setFixedLengthStreamingMode` regression test.

`run.sh` forwards the variable with a bare `-e NAME`, so the value is inherited
from your shell rather than appearing in the container's command line where
`ps` would expose it. Costs a fraction of a cent per run.

## What it deliberately does not cover

- **The Rust core and the JNI boundary.** Post-processing hooks in after Rust
  hands the text to Java via `onTextTranscribed`, so none of it is reachable
  from these tests. Changes under `src/` are not verified here.
- **APK assembly.** Needs the NDK, the aarch64 Rust target, a C++ build of
  transcribe.cpp and a 600 MB model — several GB for an artifact that still
  can't be exercised without a phone. Build that on a developer machine:
  `./gradlew assemblePlusDebug`.
- **On-device behaviour.** Keyboard layout, tinting, and the commit path need a
  real IME session.

## Design constraint

`PostProcessClient` must stay free of Android imports — that is the only reason
Tier 1 can run at all. Tier 1 compiles it against nothing but `org.json`; if
that compile ever starts needing `android.jar`, Android has crept into the class
and the request path has quietly become untestable off-device. Treat it as a
design regression, not a build error to paper over. The Android-facing pieces
(`Context`, `Log`, threading) belong in `PostProcessor`.

`org.json` comes from Maven rather than `android.jar` because the SDK ships
throwing stubs (`"Stub!"`) of the platform classes.

## Notes

- The repo's `gradle.properties` pins `org.gradle.java.home` to an Android
  Studio JBR path. The container overrides it via
  `GRADLE_USER_HOME/gradle.properties`, which Gradle reads at higher
  precedence, so the working tree is never modified.
- `cargoNdkBuild` and `downloadModels` are excluded by name. The image points
  `ANDROID_NDK_HOME` at a nonexistent path so Gradle doesn't try to provision
  an NDK while configuring the excluded task.
