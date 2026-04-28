# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Vela is a private, on-device Google Assistant alternative for Android. The user holds a button (or invokes via the assist gesture once Phase 5 lands), speaks, and Gemma 4 — running locally — either answers directly or calls a tool (calendar, music control, timer, web search, etc.). No cloud, no telemetry.

The repo is mid-pivot from an earlier Phi-3 ONNX chat app. **Phase 0 (demolition) has been completed**: the entire ONNX/TFLite/Phi-3 stack is gone, the inference repository is stubbed to return an "not implemented" error, and Clean Architecture / DI / Compose UI / Room scaffolding has been preserved for reuse.

The phased rollout plan lives at `/Users/nade/.claude/plans/sorted-sprouting-glacier.md`. Read it before making non-trivial changes — it has the per-phase scope and acceptance criteria.

## Repository layout

- `android/` — the Android app. Single Gradle module (`:app`), package `com.vela.assistant`, root project name `Vela`. All app work lives here.

There is intentionally no `ml/` directory or top-level design doc anymore; those were Phi-3 quantization research and have been deleted.

## Common commands

From `android/`:

```bash
./gradlew assembleDebug                 # build debug APK
./gradlew assembleRelease               # build release APK (R8/ProGuard)
./gradlew test                          # JVM unit tests
./gradlew :app:testDebugUnitTest --tests "com.vela.assistant.domain.usecase.SendMessageUseCaseTest"  # single test
./gradlew connectedAndroidTest          # instrumentation tests (needs device/emulator)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -i "vela\|gemma\|mediapipe"
```

Toolchain: Kotlin 1.9.20, JVM target 17, Hilt with `kapt` (keep `kapt` for Hilt + Room).

## Architecture

Clean Architecture in three layers under `app/src/main/kotlin/com/vela/assistant/`:

- `domain/` — pure Kotlin: `model/` (Conversation, Message, InferenceRequest, InferenceResult), `repository/` (interfaces), `usecase/` (SendMessageUseCase, CreateConversationUseCase, GetConversationsUseCase). No Android imports.
- `data/` — `local/` (Room DAOs/entities; will host Gemma4ModelWrapper + AudioRecorder + AndroidTtsEngine), `repository/` (impls; `ModelRepositoryImpl` is currently a Phase 0 stub), `mapper/`. Hilt-injected.
- `presentation/` — Compose + MVVM (`ChatViewModel` exposes `StateFlow`, `ChatScreen` collects).
- `di/` — Hilt modules (`DatabaseModule`, `RepositoryModule`).

`InferenceResult` is a sealed class (`Loading`/`Streaming`/`Success`/`Error`) — keep it; it fits both the streaming-token UX and the upcoming tool-call loop.

## Phase 0 stub: what's compiling but not running

`ModelRepositoryImpl.runInference()` currently emits a single `InferenceResult.Error("Inference not implemented yet — Phase 1 will wire Gemma 4 via MediaPipe.")`. The DI graph, ChatViewModel, ChatScreen, Room persistence, and SendMessageUseCase all wire through it correctly — so the app launches, you can type a message, and you'll see the stub error in the UI. **Do not** add fake/mock generation here; replace it with the real Gemma 4 wrapper when Phase 1 starts.

Removed in Phase 0 and not coming back in the same form: `IntentRouterUseCase` (single-model architecture, no routing), `ModelType` enum (one model), the entire ONNX/TFLite/Phi3BPETokenizer/SimpleTokenizer/ImagePreprocessor/TFLiteModelWrapper set.

## What to add in upcoming phases (high level)

- **Phase 1**: `data/local/Gemma4ModelWrapper.kt` (MediaPipe `LlmInference` + `LlmInferenceSession`, streaming via `callbackFlow`), `data/local/ModelDownloader.kt` (HuggingFace `.litertlm` download to external files dir on first run), gradle dep `com.google.mediapipe:tasks-genai:<verify-version>`.
- **Phase 2**: `data/local/AudioRecorder.kt` (16 kHz mono PCM, AudioRecord, ≤30 s segments) and `data/local/AndroidTtsEngine.kt` (Android `TextToSpeech` wrapper). Push-to-talk UI.
- **Phase 3**: `domain/tool/{Tool, ToolRegistry}` interfaces + `data/llm/{ToolPromptBuilder, ToolCallParser}` + `domain/usecase/AssistantTurnUseCase` (loop: stream → detect tool call → execute → continue). First tools: `TimerTool`, `CalendarReadTool`, `WebSearchTool`.
- **Phase 4**: `data/system/AssistantNotificationListener.kt` (NotificationListenerService) + `MusicControlTool`, plus calendar-write/contacts/app-launch/flashlight/volume tools.
- **Phase 5**: `presentation/voice/VelaVoiceInteractionService(+Session+SessionService).kt` and `res/xml/voice_interaction_service.xml` to register as the device's default assistant via `RoleManager.ROLE_ASSISTANT`.
- **Phase 6** (optional): `data/audio/SileroVad.kt` + `AssistantListeningService.kt` for hands-free continuous listening behind a settings toggle.

Out of scope for v1: vision input, `CALL_PHONE`/`SEND_SMS` (Play Store policy), Wi-Fi/Bluetooth toggles, wake-word.

## Non-obvious things to know

- **Model is not bundled.** The Gemma 4 `.litertlm` weights (~2.5–3 GB) will be downloaded into the app's external files dir on first run. Don't try to ship them in the APK.
- **Two Gemma 4 caveats need verification at coding time** (research found them; not yet confirmed against live docs): the exact `tasks-genai` artifact version that ships Gemma 4 audio support, and the `.litertlm` model file name on HuggingFace `litert-community`. If Gemma 4 audio isn't available yet on the day Phase 1 starts, fall back to Gemma 3n E4B — same runtime, same API surface.
- **`Message.imageUri`** is a vestigial field from the old vision plan; safe to leave for now, can drop later if no tool ever passes it.
- The single-module Gradle setup, Hilt + Room + Compose stack, and Material 3 theme are all reusable. Don't restructure them without a reason.
