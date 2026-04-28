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

### Java version

This project requires **JDK 21**. The Kotlin toolchain is pinned to 21 via `kotlin { jvmToolchain(21) }` in `app/build.gradle.kts`, and the Foojay resolver in `settings.gradle.kts` will auto-download a JDK 21 if one isn't installed. If you have JDK 25 installed system-wide and Gradle still picks it up, point `JAVA_HOME` at an installed JDK 21 — for example, the one bundled with Android Studio:

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
```

JDK 25 is currently incompatible with the Kotlin/Gradle versions used here.

### Model download

The APK does **not** bundle the model weights. On first launch the app downloads the Gemma 4 E2B `.litertlm` file into the app's external files directory and verifies completion via a sentinel marker:

- **Model:** `gemma-4-E2B-it.litertlm`
- **Source:** `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`
- **Size:** ~2.5 GB (Wi-Fi recommended)

`ModelDownloader.kt` reports per-byte progress to a `Flow` consumed by `ChatScreen` (which renders a download UI), supports HTTP byte-range resume on partial downloads, and surfaces errors back to the UI.

## Docs

- `CLAUDE.md` — guidance for Claude Code agents working in this repo.
