# Andee local-voice customization design

Date: 2026-11-23
Repo: `Suydev/andee` (fork of `kuafuai/andee`), working clone `~/andee`

## Goal and success criteria

Produce one final ARM64-only debug APK of Andee that is fully English, uses the best bundled offline voice models, and ships prebuilt app shortcuts so the agent works fast on a phone.

Success:
- Hearing and speaking work with no network call.
- APK contains only `arm64-v8a` artifacts; one push, one GitHub Actions build.
- All user-visible strings and hardcoded literals are English.
- Voice loop works after adb install, verified by logcat and on-screen state.
- Thinking remains ON with effort `high` (explicit user choice).

## Decisions

- ASR: **SenseVoice Small int8 (2025-09-09)**, ~226 MB, ~0.5 GB RAM, ~35x realtime, multilingual. Non-streaming; transcript arrives after end-of-speech.
- TTS: **Kokoro v1.1 int8**, 140 MB archive, 24 kHz, 103 voices, Apache-2.0, UTMOS ~4.44.
- Delivery: **bundled in APK** (user choice). Extract to `filesDir/voice/` on first run.
- Backend: sherpa-onnx Android AAR (JNI). The exact Maven coordinate and version are pinned from the sherpa-onnx release verified during implementation; this spec intentionally does not fix a number.
- Fallback: Gemini Live / Google TTS stay configured as fallback when local assets are missing or fail to load.
- License note: SenseVoice is under FunASR terms restricting commercial use; acceptable for personal use, not for redistribution.

## Architecture

Interfaces stay in `core`:
- `core/src/main/kotlin/net/kuafuai/andee/asr/AsrEngine.kt` (`start`, `sendAudio(pcm, isLast)`, `waitFinished`, `endOfTurn`, `close`, listener with `onReady/onPartial/onFinal/onError/onClosed`)
- `core/src/main/kotlin/net/kuafuai/andee/tts/TtsEngine.kt` (`open`, `push`, `finish`, `cancel`, listener with `onOpen(rate)/onAudio/onDone/onError/onClose`)

New provider implementations live in `app` (they need `Context` for assets/filesDir):
- `app/src/main/java/net/kuafuai/andee/asr/LocalAsrEngine.kt` — implements `AsrEngine` using sherpa-onnx SenseVoice.
- `app/src/main/java/net/kuafuai/andee/tts/LocalTtsEngine.kt` — implements `TtsEngine` using sherpa-onnx Kokoro.
- `app/src/main/java/net/kuafuai/andee/voice/VoiceAssets.kt` — copies bundled model files from `assets/voice/` into `filesDir/voice/`, verifies size/hash, and returns absolute paths for native `newFromFile` APIs.

New provider value: `local` (constant in both `core/.../config/VoiceSettings.kt` and `app/.../config/VoiceConfig.kt`).

## Data flow

1. `AudioIO.startStreaming(16000, 100)` feeds 16 kHz mono PCM to `LocalAsrEngine.sendAudio`. Pre-ready audio is buffered (cap 600 KB).
2. Local endpointing (silence detection) decides end-of-turn, then calls `endOfTurn(bySilence)` and `sendAudio(empty, true)`; `waitFinished` returns final transcript.
3. The brain receives the transcript as if from any other ASR engine.
4. `LocalTtsEngine.open` emits `onOpen(24000)`; `push` generates PCM chunks via Kokoro; `onAudio` feeds `AudioIO` stream playback; `finish` emits `onDone`.
5. `cancel()` must be prompt and must not emit a late error (otherwise the ball stays SPEAKING until the 20 s watchdog).
6. On any local model load failure: `onError` for ASR; `onUnavailable` for TTS so the controller can fall back.

## Wiring checklist (from code exploration)

- `app/.../asr/AsrController.kt:219` — add `VOICE_LOCAL -> LocalAsrEngine(context, cfg, listener)`.
- `app/.../tts/TtsController.kt:457` and fallback `:380` — add `VOICE_LOCAL -> LocalTtsEngine(context, listener)`.
- `app/.../config/VoiceConfig.kt` — provider constants `:73-81`, `voiceProvider()` `:514-528`, `speakProvider()` `:543-554`, `load()` `:567-622`, `save()` whitelist `ALLOWED_KEYS` `:1063-1095`, reset keys `OVERRIDE_KEYS` `:46-55`.
- `core/.../config/VoiceSettings.kt:53-54` — add `VOICE_LOCAL`.
- `app/.../ui/SettingsUi.kt:756-894` — add `local` to both segmented pickers and gate the local rows.
- `app/.../ui/OnboardingUi.kt:873-914` — add `local` to both pickers; no key required for local.
- `app/.../device/SelfCheck.kt:916-943,962-986` — skip the cloud host/key checks when provider is `local`.
- `app/.../meeting/MeetingRecorder.kt:484` — handle the new provider branch.
- `app/src/main/res/values/strings.xml` + `values-en/strings.xml` — add local provider labels (English in both).
- `app/build.gradle` — add sherpa-onnx AAR dependency in `:241-275`; keep ABI split arm64-only `:211-218`; add `androidResources.noCompress` for `.onnx/.bin/.json/.txt` assets.
- `settings.gradle:13-24` — ensure the AAR repository (e.g. Maven Central / JitPack) is present.
- `app/src/main/assets/voice/` — bundled SenseVoice + Kokoro model files.

## Prebuilt speed assets

- Existing 6 app-triggered scenes in `app/.../config/ScenePresets.kt` (WhatsApp, Termux, YouTube, Brave, Notes, Chrome) remain; registry `all` at `:460`.
- No new preset-flow registry in this change: the app has no flow preset file, and adding one would alter persistence. Fast app launching uses the existing voice shortcuts and scene `triggerApps` instead.
- Keep voice shortcuts (`open <app>`, `show notifications`, `screenshot`) documented in `docs/reference.md`.
- English-only UI: apply the 399-entry translation map (already committed on branch `translate-kotlin-strings`, 421 literals across 49 files).

## App Playbooks (added scope)

A per-package learning store for fast app control:

- `AppPlaybook(pkg, label, habits, shortcuts, anchors, uses, updatedAt)` in `NotebookTypes.kt`.
- Stored in the existing encrypted notebook blob under `playbooks`; CRUD, seeding, and a prompt index in `NotebookBook.kt`; façade in `Notebook.kt`.
- Tools `list_app_playbooks`, `get_app_playbook`, `save_app_playbook`, `delete_app_playbook` (`app_playbook.*`, localOnly) in `ToolSchemas.kt` and `CommandDispatcher.kt`.
- Six playbooks seeded on first run for WhatsApp, Termux, YouTube, Brave, Notes, Chrome.
- Prompt index is appended to `scenesForPrompt()`, so the model reads learned app shortcuts on every turn.
- UI: a third "App Playbooks" tab in `ScenesUi.kt` with list, delete, and an add form.
- Learning mode (user decision): demonstrate once or complete a task successfully, then auto-save/refine; no passive screen observation.

## Build and verification

- One commit on top of the current work, then a single push to `Suydev/andee` `main`.
- Trigger `.github/workflows/build.yml` once via `workflow_dispatch`.
- Confirm artifact contains a single `app-arm64-v8a-debug.apk`.
- Install with `adb install -r`; re-grant permissions if Android resets them.
- Smoke test: say a phrase, confirm transcript appears, confirm spoken reply, check logcat for `LocalAsrEngine`/`LocalTtsEngine` and no fallback errors.

## Risks

- APK size ~370 MB; GitHub artifact and phone storage are sufficient.
- sherpa-onnx AAR exact version/API must be confirmed from the recipe research before coding.
- SenseVoice int8 accuracy figures are vendor-reported; verify on device.
- Non-streaming ASR means a pause before the transcript appears; this is the accepted accuracy trade-off.
