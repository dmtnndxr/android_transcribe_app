# Build and run on a Mac + Samsung phone

Everything below is macOS only. Do it on the MacBook, not the VPS.

---

## 1. Install

```sh
brew install --cask android-studio
brew install --cask android-platform-tools     # adb
brew install cmake ninja
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

Open Android Studio once, let it finish first-run setup, then:
**More Actions → SDK Manager → SDK Tools →** tick **NDK (Side by side)** and
install version **30.0.15729638**.

---

## 2. Two files to fix (do this first, or the build fails)

**`gradle.properties`** — the line `org.gradle.java.home=/opt/android-studio/jbr`
is a Linux path. Replace it with:

```properties
org.gradle.java.home=/Applications/Android Studio.app/Contents/jbr/Contents/Home
```

**`local.properties`** — create it in the project root:

```properties
sdk.dir=/Users/YOURNAME/Library/Android/sdk
```

---

## 3. Build

Open the project in Android Studio and press **Run** (▶).

Or from a terminal:

```sh
./gradlew installPlusDebug
```

First build takes 20–30 minutes: it downloads a 485 MB speech model and
compiles the Rust and C++ cores. After that, changes rebuild in seconds.

---

## 4. Phone setup (one time)

1. **Settings → About phone → Software information →** tap **Build number** 7×.
2. **Settings → Developer options → USB debugging** → on.
3. Plug in USB, tap **Allow** on the fingerprint prompt.
4. **Settings → Security and privacy → Auto Blocker** → **off** (it blocks
   sideloading).

Check it worked: `adb devices` should list your phone.

---

## 5. Enable the keyboard

**Settings → General management → Keyboard list and default →**
turn on **Offline Voice Input**.

To use it: tap the keyboard icon in the navigation bar (or long-press space)
and pick it.

**Test the big mic first.** Speak, tap to stop, text appears. Get this working
before touching AI cleanup — otherwise you won't know which half is broken.

---

## 6. Set up AI cleanup

In the app: **AI cleanup → Set up AI cleanup**.

- Enable the switch
- Preset: **OpenRouter**
- Base URL: `https://openrouter.ai/api/v1`
- API key: your `sk-or-v1-…`
- Model: `openai/gpt-4o-mini`

Tap **Send test sentence**. If that works, the feature works.

Then on the keyboard there are two mics. The small one with the sparkle is AI
cleanup.

---

## 7. When something breaks

```sh
adb logcat -s OfflineVoiceInput PostProcessor
```

| Message | Fix |
|---|---|
| No second mic | Not enabled in the app; close and reopen the keyboard |
| "AI cleanup isn't set up" | Base URL or model is empty |
| "the API key was rejected" | Wrong key |
| "endpoint or model not found" | Model name typo, or URL missing `/v1` |
| "can't reach the server" | No internet |

Failures always insert your raw transcription anyway — you never lose words.

---

## 8. Getting an APK without building

Push a tag and GitHub builds it for you:

```sh
git tag v0.1.19 && git push fork v0.1.19
```

Then download the `.apk` from the repo's Releases page, on the phone, and tap
it. Grant "Install unknown apps" when asked.

Slower than building locally (~30 min), but needs nothing installed.
