# Vela
> Private, on-device AI assistant for Android. Powered by Gemma 4 — no data leaves your device.

## What is Vela

Vela is a fully local voice assistant: hold the mic (or use the system assist gesture), speak in any language, and Gemma 4 — running on the phone's GPU — either answers directly or invokes a tool (timer, calendar, music, contacts, flashlight, volume, app launch, web search). Tool calls run on-device against Android system APIs. The audio goes straight into the model; there is no separate cloud speech-to-text step. No telemetry. No analytics. No accounts.

## Privacy

Vela is private by design, not by promise. The reasons are technical and inspectable in this repo:

- **Inference runs locally.** The model file (`gemma-4-E2B-it.litertlm`) is downloaded once on first launch from the public HuggingFace `litert-community` repo and lives in the app's external files dir. All token generation happens inside the process via [LiteRT-LM](https://github.com/google-ai-edge/litert-lm) on the device's GPU.
- **No analytics or crash SDKs.** No Firebase, Crashlytics, Sentry, Mixpanel, Amplitude, Google Analytics, Bugsnag, or any other telemetry library. You can verify this in `android/app/build.gradle.kts` — the dependency list is short and every entry is either AndroidX, Compose, Hilt, kotlinx, Room, DataStore, Timber (local logging only), or LiteRT-LM.
- **Only one outbound network request.** The `INTERNET` permission is used solely by `data/local/ModelDownloader.kt` to fetch the model from HuggingFace on first run. No other code path opens a socket. The web-search tool fires an `Intent.ACTION_WEB_SEARCH` which is handled by your default browser — Vela itself does not contact a search engine.
- **No remote logs.** Logging uses Timber, which writes to logcat only. Nothing is uploaded anywhere.
- **Auditable source.** GPL-3.0-or-later. Read the code, build it yourself, change it.

## Hardware requirements

- **Recommended phone:** Samsung Galaxy S22 or equivalent — 8 GB RAM, modern Snapdragon/Tensor SoC, GPU with OpenCL support.
- **Android version:** 8.0+ (API 26).
- **Storage:** ~3 GB free for the Gemma 4 weights (downloaded on first launch).
- **GPU:** OpenCL is preferred; the runtime falls back to CPU if `Backend.GPU()` initialisation fails, but inference latency on CPU-only devices is significantly worse and not the supported configuration.

Lower-end / older devices may install and run, but the assistant experience (especially first-token latency and audio inference) is not guaranteed.

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

## Supported features

Tools registered in `data/tool/` and exposed to Gemma 4 via `@Tool` / `@ToolParam` annotations:

- **Timer** — set countdown timers via the system clock app (`AlarmClock.ACTION_SET_TIMER`).
- **Alarm** — set alarms via `AlarmClock.ACTION_SET_ALARM`.
- **Calendar (read)** — query events from `CalendarContract.Events` for a given time range.
- **Calendar (write)** — create new events.
- **Contacts** — look up phone numbers / names via `ContactsContract`.
- **Music** — play, pause, skip, previous, now-playing. Targets Poweramp explicitly when installed (its API documents `onPlayFromSearch` from third-party callers, unlike Spotify which gates that path to Google Assistant). Falls back to whichever app handles `MEDIA_PLAY_FROM_SEARCH`.
- **Device** — get battery status.
- **Flashlight** — toggle the camera torch via `CameraManager.setTorchMode`.
- **Volume** — adjust media stream volume.
- **App launch** — start any installed app by package via `PackageManager.getLaunchIntentForPackage`.
- **Web search** — fire `Intent.ACTION_WEB_SEARCH` so the system browser handles the actual query.

Vela also registers as a `VoiceInteractionService` so the user can set it as the device's default Assistant — long-pressing home (or using the assist gesture) brings up the Vela overlay.

## Known limitations

- **Spotify auto-play is gated.** Spotify's `onPlayFromSearch` only honours requests from Google Assistant; third-party callers (including Vela) can open Spotify but cannot make it auto-play a specific artist. If Poweramp is installed it is preferred; otherwise music control uses MediaSession transport keys (pause / next / previous) which work across all apps.
- **KV cache auto-resets every 20 turns.** Empirically Gemma 4 E2B's persistent dialogue cache starts producing 1-token nonsense after enough turns. Vela now auto-drops the conversation at turn 20 and emits a subtle Snackbar ("Conversation reset to keep responses fresh."). The "+" button in the top bar resets manually any time.
- **Lower-end devices not guaranteed.** First-token latency depends heavily on GPU support; CPU-only fallback is functional but slow enough to feel broken on phones without OpenCL.
- **Phase 6 (always-on listening) is not shipped.** Push-to-talk and the system assist gesture are the two entry points.
- **Vision input is out of scope for v1.**

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the fork-and-PR workflow, the recipe for adding a new tool, code style, and how to file a bug report.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE) for the full text.
