# Building and running on a Samsung phone

Start-to-finish for getting this build onto a device and exercising the AI
cleanup feature.

**Do this on your laptop, not the VPS.** The APK needs the Android NDK, the
`aarch64` Rust target, a C++ build of transcribe.cpp and a 485 MB model
download — and once built, it has to be installed on a phone over USB. The VPS
can only run the checks in [`tools/verify/`](../tools/verify/README.md).

---

## 0. Device requirements

- **arm64 (`arm64-v8a`)** — every Samsung phone from the last decade. The build
  targets this ABI only.
- **Android 8.0 (API 26) or newer.**
- **A CPU with `armv8.2-a` dot-product and fp16** — roughly 2018 flagships
  onward. The engine checks this at model load and shows a clear error rather
  than crashing, so if your phone is too old you will find out immediately and
  unambiguously.

---

## 1. Get the code onto the laptop

The work is on a branch. Push it from the VPS and pull it down, or clone
directly:

```sh
# on the VPS
git push origin feat/ai-cleanup-post-processing

# on the laptop
git clone <your-fork-url>
cd android_transcribe_app
git checkout feat/ai-cleanup-post-processing
```

---

## 2. Install the toolchain

| Dependency | Install |
|---|---|
| JDK 17 | Bundled with Android Studio, or your distro's `jdk17-openjdk` |
| Android SDK | Android Studio, or `sdkmanager` |
| Android NDK | `sdkmanager "ndk;28.0.13004108"` |
| CMake + Ninja | `sdkmanager "cmake;3.22.1"`, or your distro's packages |
| Rust | [rustup.rs](https://rustup.rs), then `rustup target add aarch64-linux-android` |
| cargo-ndk | `cargo install cargo-ndk` |

The NDK version matters — transcribe.cpp is built through CMake against it.

---

## 3. Point the build at your setup

Create `local.properties` in the project root (gitignored):

```properties
sdk.dir=/home/you/Android/Sdk
```

Then check `gradle.properties`. It currently pins:

```properties
org.gradle.java.home=/opt/android-studio/jbr
```

**If that path doesn't exist on your laptop the build fails immediately** with
`Java home supplied is invalid`. Set it to your JDK 17, or comment it out to
fall back to `JAVA_HOME`.

If the NDK isn't where Gradle expects, export it:

```sh
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/28.0.13004108
```

---

## 4. Build

```sh
./gradlew assembleDebug
```

The first build is slow: it downloads the 485 MB GGUF model (SHA-256 verified),
compiles the Rust core, and builds transcribe.cpp's C++ through CMake. Expect
tens of minutes. Later builds are incremental.

Output: `app/build/outputs/apk/debug/app-debug.apk`

If it fails in `cargoNdkBuild`, the cause is almost always a missing
`aarch64-linux-android` Rust target, missing `cargo-ndk`, or an NDK path Gradle
can't resolve.

---

## 5. Put the phone in developer mode

On One UI:

1. **Settings → About phone → Software information**
2. Tap **Build number** seven times, enter your PIN.
3. **Settings → Developer options → USB debugging** → on.
4. Connect USB. The phone shows an RSA fingerprint prompt — **Allow**. If it
   never appears, change the USB mode from *Charging* to *File transfer (MTP)*
   and re-plug.

Some Samsung firmware also has **Install via USB** in Developer options; turn
it on if `adb install` is refused.

Confirm the phone is visible:

```sh
adb devices        # should list your device as "device", not "unauthorized"
```

---

## 6. Install

```sh
./gradlew installDebug
# or: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Offline Voice Input** and grant the microphone permission when asked.

---

## 7. Enable the voice keyboard

The AI cleanup mic lives on this app's own keyboard, so it has to be enabled:

1. **Settings → General management → Keyboard list and default**
2. Toggle **Offline Voice Input** on, accept the warning Android shows for any
   third-party keyboard.

To switch to it while typing: tap the keyboard icon in the navigation bar, or
long-press the space bar on Samsung Keyboard, and pick **Offline Voice Input**.
Its keyboard-switch key takes you back.

> Samsung Keyboard's own mic key is not in this repo's tested-keyboards list, so
> whether it hands speech to this app is unknown. It doesn't matter for AI
> cleanup: that feature is on this app's keyboard, which you reach through the
> keyboard switcher regardless.

Sanity-check the plain path first — switch to the keyboard, tap the big mic,
speak, tap to stop. Text should appear. **Get this working before touching AI
cleanup**, so that if something breaks later you know which half it was.

---

## 8. Set up AI cleanup

In the app: **AI cleanup → Set up AI cleanup**.

### Option A — local model on your laptop (recommended for testing)

Nothing leaves your machine, and there is no API key or billing involved.

```sh
ollama serve
ollama pull llama3.2
```

Then forward the port over USB, so the phone reaches your laptop without any
network or firewall configuration:

```sh
adb reverse tcp:11434 tcp:11434
```

In the app:

- **Base URL:** `http://localhost:11434/v1`
- **API key:** *(empty)*
- **Model:** `llama3.2`

`adb reverse` is worth preferring over the LAN-IP route: it survives the phone
changing Wi-Fi networks, needs no `OLLAMA_HOST=0.0.0.0`, and isn't blocked by
your laptop's firewall. It does need the USB cable connected, and it resets
when the cable is unplugged — re-run it after re-plugging.

Alternatively, without USB: set `OLLAMA_HOST=0.0.0.0` on the laptop, open the
port in its firewall, and use `http://<laptop-LAN-IP>:11434/v1`. The app will
warn that the address is unencrypted, which is correct — fine on a trusted
network.

### Option B — a hosted provider

Pick the preset, paste an API key, set a model. Anything OpenAI-compatible
works.

### Then

Tap **Send test sentence**. It round-trips a deliberately messy sentence and
shows what came back. **Do not skip this** — it isolates configuration problems
from keyboard problems, and it is much easier to debug here than mid-typing.

Models below ~3B parameters tend to ignore the instruction and answer or
paraphrase your text instead of cleaning it up. If output looks wrong rather
than absent, try a larger model before suspecting the app.

---

## 9. Use it

Switch to the Offline Voice Input keyboard. There are now two mics: the large
one (offline, as before) and a smaller one with a sparkle to its right.

Tap the small one, speak, tap to stop. The status line shows *Cleaning up…*
during the round-trip, then the processed text is inserted.

To confirm the fallback works, turn off Wi-Fi (or stop Ollama) and use the
second mic: you should get your raw transcription plus an explanation in the
status line — never nothing.

---

## 10. When something misbehaves

```sh
adb logcat -s OfflineVoiceInput PostProcessor MainActivity
```

The keyboard runs in a `:ime` process; the tag filter above catches both.

| Symptom | Likely cause |
|---|---|
| No second mic on the keyboard | Not enabled in the app. It is hidden entirely until then. If you enabled it while the keyboard was open, close and reopen the keyboard. |
| "AI cleanup isn't set up" | Base URL or model empty. |
| "can't reach the server" | `adb reverse` not run, or run before the cable was connected. Re-run it. |
| "the API key was rejected" | Wrong or expired key. The provider's own reason is appended when it sends one. |
| "endpoint or model not found" | Usually a model name typo, or a base URL missing `/v1`. |
| "the server took too long" | Model too large for the machine serving it, or cold-loading. Try again once it is warm. |
| Text is answered rather than cleaned up | Model too small, or the prompt lost `${output}`. Reset the prompt to default. |
| Reasoning text inserted | Should be stripped; if a model uses different tags, report it — the pattern covers `<think>`, `<thinking>`, `<reasoning>`. |

---

## 11. What has and hasn't been checked

The logic and the type-checking are verified — see
[`tools/verify/`](../tools/verify/README.md) and the
[implementation report](ai-cleanup-report.md). Never verified on hardware:

- keyboard layout and spacing (the second mic sits close to the mic-level glow
  on narrow screens),
- colour and contrast of the new button in light/dark and with Material You,
- how the round-trip latency actually feels mid-sentence,
- the commit path into real editors after the delay.

Those are the things to look at first on the device.
