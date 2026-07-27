# Building and running on a Samsung phone

Start-to-finish for getting this build onto a device and exercising the AI
cleanup feature.

**Do this on a laptop, not the VPS.** The APK needs the Android NDK, the
`aarch64` Rust target, a C++ build of transcribe.cpp and a 485 MB model
download — and once built, it has to be installed on a phone over USB. The VPS
can only run the checks in [`tools/verify/`](../tools/verify/README.md).

## Which laptop: use the MacBook

Both work, but macOS is materially less friction for this project:

| | macOS | Windows |
|---|---|---|
| `adb` sees a Samsung phone | works out of the box | usually needs Samsung's USB driver installed first |
| Build scripts | `./gradlew`, Unix shell, same as CI | `gradlew.bat`; `tools/verify/*.sh` needs WSL or Git Bash |
| Paths in `.properties` files | plain | backslashes are escape characters — a wrong path fails confusingly |
| Rust + `cargo-ndk` cross-compile | well-trodden | works, but the host toolchain also needs MSVC build tools |

If you only ever build on one machine, make it the Mac. The Windows steps are
included below because they do work, not because they are equally pleasant.

Apple Silicon is fine — the NDK ships a native arm64 toolchain and the Rust
`aarch64-linux-android` target cross-compiles from it without special setup.

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

Same five pieces on every OS; only the install commands differ. The NDK version
matters — transcribe.cpp is built through CMake against it.

### macOS

```sh
brew install --cask android-studio      # brings the SDK and a JDK 17 (JBR)
brew install cmake ninja
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup target add aarch64-linux-android
cargo install cargo-ndk

# From Android Studio: More Actions -> SDK Manager -> SDK Tools -> NDK,
# or on the command line:
sdkmanager "ndk;28.0.13004108" "platforms;android-35" "build-tools;35.0.0"
```

Default SDK location: `~/Library/Android/sdk`.

### Windows

Install [Android Studio](https://developer.android.com/studio) (SDK + JDK),
then [rustup](https://rustup.rs) — it will prompt for the MSVC build tools if
they're missing; accept, the host toolchain needs them. Then in PowerShell:

```powershell
rustup target add aarch64-linux-android
cargo install cargo-ndk
sdkmanager "ndk;28.0.13004108" "platforms;android-35" "build-tools;35.0.0" "cmake;3.22.1"
```

Default SDK location: `%LOCALAPPDATA%\Android\Sdk`.

You also need **Samsung's USB driver** for `adb` to see the phone —
[developer.samsung.com/android-usb-driver](https://developer.samsung.com/android-usb-driver).
Install it before plugging the phone in.

---

## 3. Point the build at your setup

Two files. Get these right and most build failures never happen.

**`local.properties`** in the project root (gitignored — create it):

```properties
# macOS
sdk.dir=/Users/you/Library/Android/sdk

# Windows — use forward slashes. Backslashes are escape characters in a
# .properties file, so C:\Users\... silently parses wrong.
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

**`gradle.properties`** currently pins a Linux path:

```properties
org.gradle.java.home=/opt/android-studio/jbr
```

**If that path doesn't exist, the build fails immediately** with `Java home
supplied is invalid`. Either comment it out to fall back to `JAVA_HOME`, or set
it for your machine:

```properties
# macOS (Android Studio's bundled JBR)
org.gradle.java.home=/Applications/Android Studio.app/Contents/jbr/Contents/Home

# Windows — forward slashes again
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

If Gradle can't find the NDK, point at it explicitly:

```sh
# macOS
export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/28.0.13004108
```

```powershell
# Windows
$env:ANDROID_NDK_HOME="$env:LOCALAPPDATA\Android\Sdk\ndk\28.0.13004108"
```

---

## 4. Build

```sh
./gradlew assembleDebug          # macOS
```

```powershell
.\gradlew.bat assembleDebug      # Windows
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

**On Windows,** if `adb devices` shows nothing at all, the Samsung USB driver
from step 2 is missing or the phone is in *Charging* USB mode. macOS needs no
driver.

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

### Option B — OpenRouter (or any hosted provider)

Pick the **OpenRouter** preset, paste your key, set a model:

- **Base URL:** `https://openrouter.ai/api/v1`
- **API key:** your `sk-or-v1-…` key
- **Model:** `openai/gpt-4o-mini` is a good default — cheap, fast, and reliable
  at instruction-following, which is what cleanup needs

Encrypted, works off Wi-Fi, no laptop involved — so this is the better choice
for everyday use, while Option A is better for iterating without spending
tokens. Both can be swapped at any time from the settings screen.

The same key can drive the automated live test:

```sh
export OPENROUTER_API_KEY=sk-or-v1-...
tools/verify/run.sh tier1
```

That runs `OpenRouterLiveTest` — a real round-trip, a custom-prompt case, and
two error cases — against the actual API instead of the local stub. It's
skipped when the variable is unset, so the default run stays offline and free.
Set `OPENROUTER_MODEL` to override the model. Costs a fraction of a cent.

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
