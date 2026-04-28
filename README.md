# Vela

**Private, on-device AI assistant for Android.** A fully local, open-source alternative to Google Assistant: hold a button or use the assist gesture, speak in any language, and Gemma 4 — running on the phone's GPU via LiteRT-LM — answers directly or calls a tool (calendar, music, timers, web search, flashlight, volume, contacts, app launch). Audio goes straight into the model. No cloud, no telemetry.

## Architecture

- **Model:** Gemma 4 E2B with native audio input and tool calling, downloaded into the app's external files dir on first run (~2.5 GB, not bundled).
- **Runtime:** LiteRT-LM (`com.google.ai.edge.litertlm`), GPU backend (OpenCL) with CPU fallback.
- **Voice loop:** Push-to-talk → audio → Gemma 4 → optional tool dispatch → Android `TextToSpeech` reply. Silence-based VAD auto-stops the mic.
- **Tools:** Hilt-registered `ToolSet`s (`SystemTools`, `CalendarTools`, `ContactsTools`, `MusicTools`, `DeviceTools`) annotated with `@Tool` / `@ToolParam` and dispatched natively by LiteRT-LM.
- **Assistant role:** Registered `VoiceInteractionService` so Vela can be set as the device's default digital assistant.
- **Persistent KV cache:** The Gemma 4 conversation is reused across turns so only the first turn pays the full system-prompt prefill.

## Building

```bash
cd android
./gradlew assembleDebug
```

The Gemma 4 `.litertlm` model is downloaded on first launch.

## Docs

- `CLAUDE.md` — guidance for Claude Code agents working in this repo.
