# Feature proposal: on-device Gemini Nano post-processing

**Status:** proposal for maintainer discussion; no implementation is included.

## Motivation and scope

Boice performs speech recognition locally and offers optional AI post-processing through an OpenAI-compatible endpoint. That endpoint can be local or remote, but using a remote service sends the finished text off the device. An additional **On-device Gemini Nano** provider could offer AI-assisted cleanup without a cloud API, API key, or a separately bundled LLM. Android AICore manages and shares the underlying Gemini Nano model across supported features. This proposal does not change speech recognition or remove the existing provider.

Google lists the Samsung Galaxy S25 Ultra among supported devices, but model, API, language, Android version, AICore configuration, and runtime availability must all be checked; a device name alone is not a guarantee. Inference is local, but initial setup or later updates can require network access and storage: the system may need to download the base model or a feature-specific adapter. Do not promise zero downloads or fully offline setup.

## Expected user experience

- Keep AI post-processing opt-in and off by default. Offer an explicit provider selection between the current OpenAI-compatible provider and **On-device Gemini Nano**, without changing stored server credentials or existing defaults.
- For Nano, show availability and an actionable explanation for unsupported, downloadable, downloading, ready, and temporarily unavailable states. No URL or API key is required. If assets must be downloaded, disclose this and request user consent before initiating a download; show progress and errors. Avoid implicit inference-triggered downloads before consent.
- Reuse the existing Dictate + AI flow and prompt conventions, including `${output}`. Offer cleanup of grammar and punctuation, filler-word removal, formatting, and custom prompts where supported. Keep the regular microphone unchanged. Consider the existing selection-edit workflow separately and preserve its current behavior until explicitly designed.
- Preserve meaning and language wherever possible; model output is probabilistic and can alter facts. Return the original transcription on timeout, cancellation, unavailable model, blocked background execution, invalid/empty output, or other failure, with a clear status. Never silently switch to a cloud endpoint. Allow users to retry or explicitly choose the existing provider.
- Explain that inference and text stay on-device with this provider, while system-managed asset provisioning or updates may use a network. Do not log transcript, prompts, or output by default; avoid sensitive diagnostic payloads.

## Suggested technical direction

1. Define a small asynchronous post-processing provider interface around the existing request/result and failure behavior. Adapt the existing HTTP client without changing its request format, preferences, prompt semantics, timeouts, or default selection. Dispatch to the selected provider after on-device transcription completes; retain the original text until a valid result is ready. Run inference off the UI thread and handle cancellation and the IME lifecycle.
2. Investigate the ML Kit GenAI Prompt API (direct `GenerativeModel` access) for custom instructions and general cleanup. Evaluate the Proofreading API for short grammar/spelling tasks, with voice input type and an explicitly supported language. Proofreading is not a replacement for arbitrary prompts, filler removal, or formatting. Select an approach only after testing API compatibility, output quality, latency, dependencies, distribution requirements, and licensing/terms; this proposal does not prescribe exact SDK versions.
3. Check the selected API's runtime status, including API-specific language/options, using its availability methods. Handle `UNAVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`, and `AVAILABLE` distinctly; refresh status after configuration or model changes. Treat status as advisory rather than a guarantee that the next inference succeeds. Gate downloads explicitly and do not ship a separate model.
4. Respect model token limits and supported languages. For long text, investigate bounded chunking that preserves context and ordering; otherwise explain the limit and return the original text rather than silently truncate it. Constrain prompts to return only the transformed text, validate output, and avoid treating dictated text as trusted instructions. Compare results against representative multilingual dictation, names, numbers, punctuation, and custom prompts.
5. Surface actionable errors for incompatible or missing AICore, unsupported configuration, failed downloads, resource pressure, quota/busy, and foreground restrictions. Use bounded retries only where appropriate; do not block dictation indefinitely. Release clients/resources according to SDK lifecycle guidance.

### Critical feasibility question: voice keyboard foreground status

Google documents that GenAI inference is allowed only when the app is the top foreground application; a foreground service does not bypass this restriction. Boice's IME typically runs while another app owns the foreground. It is **not yet established** whether the system regards Boice's IME as eligible during inference. Test the actual IME invocation on supported devices and OS/AICore versions, including focus changes and app transitions. Handle `BACKGROUND_USE_BLOCKED` and possible busy responses without losing dictated text. If IME use is blocked, discuss an explicit foreground activity or another documented, user-consented flow with maintainers; do not assume an overlay or foreground service bypasses the restriction, and do not claim seamless keyboard support until verified.

## Limitations and open questions

- Device lists change; capability, configuration, language and downloaded assets determine actual availability. Play services/AICore distribution and behavior on devices without Google services need investigation.
- Proofreading is a task-specific API for short text with a limited language set; check current documentation for limits and support rather than assuming parity with prompts. Quality and behavior may vary by model version.
- Inference may be slow, battery-intensive, quota-limited, unavailable offline before provisioning, or interrupted by lifecycle changes. Establish realistic timeouts and assess battery, memory and thermal impact on actual devices.
- Determine whether Java integration and dependency sizes fit the current build, minimum Android version, release channels, and open-source distribution. Review Google's API terms and any restrictions before adoption.
- Resolve whether/how existing custom prompts and selection editing should map to Nano; assess prompt injection, hallucination, sensitive text handling, and safe output validation. No network fallback should occur without a deliberate user choice.

## Proposed acceptance criteria for a future implementation

- [ ] AI remains off by default; existing OpenAI-compatible settings, requests, credentials, dictation, and selection editing continue to work as before.
- [ ] A user can explicitly select Nano and run supported grammar, punctuation, filler-word, formatting and custom-prompt transformations without an API key or cloud inference; raw dictation remains available.
- [ ] Availability is checked at runtime for the relevant device, API and language; unsupported and temporary states are explained. Model/adapter downloads are disclosed and require consent; progress and failure are visible.
- [ ] After required assets are provisioned, on-device transcription and Nano inference work with networking disabled on a supported test device. Instrumentation verifies that no transcript or prompt is sent to the configured HTTP provider in Nano mode.
- [ ] Failures, cancellation, invalid or oversized responses, quota/resource exhaustion, and unsupported/blocked IME execution preserve the original transcription without silent cloud fallback or data loss.
- [ ] An actual IME test records whether inference succeeds while another app is foreground, including focus/lifecycle transitions. If blocked, the chosen foreground alternative is explicitly approved and tested; a foreground service is not treated as a workaround.
- [ ] Tests cover provider selection/migration, availability and download states, error paths, consent, privacy, prompt handling, token limits and regression of the existing HTTP provider. Document supported configurations, provisioning requirements, quality limitations and measured latency on representative hardware.

## References

- [ML Kit GenAI overview, device support, quotas and foreground restrictions](https://developers.google.com/ml-kit/genai)
- [Direct model access / Prompt API](https://developers.google.com/ml-kit/genai/prompt/android)
- [Direct model access setup and availability](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [ML Kit GenAI Proofreading API](https://developers.google.com/ml-kit/genai/proofreading/android)
- [GenAI error codes](https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode)
