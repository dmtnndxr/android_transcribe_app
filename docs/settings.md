# Settings reference

Every setting in Offline Voice Input+, what it does, its default, and what it
costs in privacy, battery or speed. Names match the English interface.

Settings are stored in the app's private storage. Nothing is synced, and the
history is excluded from cloud backup and device-to-device transfer.

- [Home screen switches](#home-screen-switches)
- [Voice keyboard](#voice-keyboard)
- [Speech models](#speech-models)
- [AI post-processing](#ai-post-processing)
- [Selected-text editing](#selected-text-editing)
- [History](#history)
- [Live subtitles](#live-subtitles)
- [Appearance and benchmark](#appearance-and-benchmark)

## Home screen switches

| Setting | Default | What it does |
|---|---|---|
| **Auto-start recording** | Off | The voice keyboard starts recording as soon as it opens. Handy when another keyboard's mic key switches to this one. |
| **Pause audio** | Off | Pauses music or a podcast while you record and resumes it afterwards. |
| **Select transcription** | Off | Leaves the inserted text highlighted, so one Backspace deletes it and typing replaces it. |
| **Copy to clipboard if there's no text field** | On | See [When the text has nowhere to go](#when-the-text-has-nowhere-to-go). |
| **Record in background** | On | Recording continues when the voice keyboard is hidden or you switch apps. Turn it off to stop recording whenever the keyboard closes. |
| **Auto-stop after silence** | Off | The voice-input popup (the panel other keyboards open) stops by itself about 2 seconds after you stop speaking. Doesn't affect the voice keyboard. |
| **Subtitle length** | 2 lines | How many lines live subtitles show: 2, 4 or all. |

### When the text has nowhere to go

Long dictations take a moment to transcribe. If you leave the app or move to
another field in the meantime, the text is not typed into whatever field
happens to be open.

- It is inserted only into the field where you started recording.
- With **Copy to clipboard** on, it is copied instead and a short message says
  so. The clipboard entry is marked sensitive, so Android doesn't show it in
  the clipboard preview.
- With the switch off, nothing is copied. The next time the voice keyboard
  opens it shows a **Not inserted: …** bar with **Insert** and **Dismiss**.
- If you return to the same field within a minute, the text is inserted
  automatically.
- Either way the text is in [History](#history), unless you turned history off.

## Voice keyboard

Enable it under **Open Keyboard Settings**, then pick it from the keyboard
switcher.

| Control | What it does |
|---|---|
| **Main microphone** | Records and transcribes on the phone. Nothing is sent anywhere. |
| **Dictate + AI** (small microphone) | Transcribes on the phone, then sends the text through [AI post-processing](#ai-post-processing). Shown only when AI post-processing is enabled. |
| **Edit** (wand) | Applies a spoken instruction to the selected text. See [Selected-text editing](#selected-text-editing). |
| **Editing keys** | Punctuation keys for quick manual fixes. Choose from `. , ? ! : ;` under **Editing keys**; the default is `. , ?`. Space and Backspace are always there. |
| **Keyboard switch** | Returns to your previous keyboard. Pressed while recording, it stops, inserts the text and then switches back. |
| **Cancel** | Available while recording, transcribing or waiting for AI. Nothing is inserted. |
| **Undo** | Offered for 12 seconds after a voice edit replaces your text. It runs only if the replacement is still unchanged. Plain dictation has no Undo — use Backspace, or turn on **Select transcription**. |

With a streaming model (see below) the text appears while you speak.

## Speech models

Under **Manage speech models**.

| Setting | Default | What it does |
|---|---|---|
| **Active model** | Parakeet TDT 0.6B v3 | The built-in model: 25 European languages, about 485 MB, included in the APK. |
| **Import model file (.gguf)** | — | Copies a [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) GGUF model into the app. Download the file in your browser first; **Where to get models** lists direct links. |
| **Language** | Automatic | The language passed to the model. Set it explicitly if speech comes out in the wrong language or script. Languages the model doesn't support are ignored. |
| **Translate to English** | Off | For models that support it (Whisper): speech in any language becomes English text. Other models ignore it. |
| **CPU threads** | Automatic | Threads used for transcription. More is not always faster; check with the benchmark. |

Which model to pick:

| Model | Languages | Notes |
|---|---|---|
| Parakeet TDT 0.6B v3 (built in) | 25 European | Good default. Transcribes after you stop. |
| Parakeet 110M | English | Small and fast, for older phones. |
| Nemotron streaming | 40, incl. Chinese, Japanese, Korean, Arabic, Hindi | Streaming: text appears while you speak. Adds punctuation. |
| Whisper small | 99 | Compact; can translate to English. |
| Whisper large-v3-turbo | 99 | Most accurate in the list, slowest; can't translate. |
| SenseVoice | English, Chinese, Cantonese, Japanese, Korean | Fast. |

The voice keyboard picks up a model change after it restarts. Models run on
the CPU; larger ones need more memory and drain the battery faster during
transcription.

## AI post-processing

Under **Set up AI post-processing**. Off by default.

| Setting | Default | What it does |
|---|---|---|
| **Enable AI post-processing** | Off | Shows **Dictate + AI** and the **Edit** wand on the voice keyboard. The main microphone is unaffected. |
| **Preset** | — | Fills in the address of a known server. |
| **Base URL** | empty | Any OpenAI-compatible `/chat/completions` endpoint. |
| **API key** | empty | Leave empty for local servers. Stored in the app's private storage in plain text. |
| **Model** | empty | The model name as the server knows it. |
| **Prompt** | Dictation cleanup | `${output}` is replaced with what you dictated. Without the placeholder, the prompt is sent as an instruction and the transcription follows as a separate message. |
| **Send test sentence** | — | Sends one sample sentence through the current settings. |

Server addresses:

| Server | Base URL |
|---|---|
| Ollama | `http://<host>:11434/v1` |
| LM Studio | `http://<host>:1234/v1` |
| llama.cpp server | `http://<host>:8080/v1` |
| OpenAI | `https://api.openai.com/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| Anthropic | `https://api.anthropic.com/v1` |

For a server on your own computer, use that computer's LAN address:
`localhost` on a phone means the phone. Ollama also needs
`OLLAMA_HOST=0.0.0.0` to accept connections from other devices.

Privacy and limits:

- Only the finished text is sent, never audio, and only when you use
  **Dictate + AI** or the wand.
- A plain `http://` address sends the text and the API key unencrypted. That is
  fine on a trusted home network and risky anywhere else; the settings screen
  warns about it.
- The app waits up to 10 seconds to connect and 45 seconds for the reply.
- If the request fails, the text is inserted as heard and the keyboard says
  what went wrong.
- Models under about 3B parameters often ignore the instruction and answer or
  paraphrase the text instead.
- Reasoning blocks (`<think>…</think>`) are removed from the reply.

## Selected-text editing

Under **Configure selected-text editing**. Uses the server and API key from AI
post-processing.

| Setting | Default | What it does |
|---|---|---|
| **Model override** | empty | Leave blank to use the AI post-processing model. Set it when edits should use a different one. |
| **System prompt** | Precise text editor | Defines how the model edits. Your spoken instruction and the selected text are sent separately, and the selection is treated as data, not as instructions. |
| **Test selected-text editing** | — | Runs a sample edit with the current model and prompt. |

How it behaves:

- Select text, tap the wand, say what to change ("make this shorter",
  "translate to German"), tap again to stop.
- The replacement is applied only if the same text is still selected. If the
  selection changed, the result goes to the clipboard or the **Not inserted**
  bar instead.
- Spaces at the edges of the selection and quotation marks around it are
  preserved.
- Very long selections can exceed the model's reply limit. The edit then fails
  with "the model's reply was cut off" and your text is left untouched.
- Not available in password fields.

## History

**Open history** on the home screen. Settings are behind the gear icon.

| Setting | Default | What it does |
|---|---|---|
| **Keep history** | On | Saves each dictation: recognized text, the final text after AI or a voice edit, and where it went. |
| **Keep recordings** | On | Also saves the audio, about 2 MB per minute. |
| **Keep recordings for** | Always | Always, 1, 7 or 30 days. After that the audio is deleted and the text stays. |
| **Clear history** | — | In the menu. Deletes all texts and recordings. |

Each entry can be played, copied, shared or deleted; expand it to select part
of the text. Search covers the recognized, final and original text.

History never leaves the phone. Recordings sit in the app's private storage,
so music players and galleries don't see them. Dictations made through another
keyboard's microphone are saved as text only.

## Live subtitles

**Start Live Subtitles**, then choose *Share entire screen*. Captions for any
audio playing on the device are produced on the phone.

- **Subtitle length** on the home screen sets how many lines are shown.
- **Skip the permission dialog (advanced)** explains how to pre-approve
  Android's screen-capture dialog once via adb.
- **Translate to English** (speech models) also applies to subtitles.

## Appearance and benchmark

- **Appearance**: System default, Light or Dark.
- **Run benchmark**: transcribes a built-in 11-second English recording with
  the active model and reports the speed as a multiple of real time. Use it to
  compare models and thread counts on your phone.
