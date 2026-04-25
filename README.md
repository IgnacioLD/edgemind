# EdgeMind

**Private, on-device assistant for Android.** Open-source alternative to Google Assistant — voice in, voice out, tool calls, no cloud.

## Status

Repo is mid-pivot from a Phi-3 chat app to a Gemma 4 voice assistant. The old inference stack has been removed; the new one is being wired up in phases. See `/Users/nade/.claude/plans/sorted-sprouting-glacier.md` for the phased rollout.

## Architecture (target)

- **Model:** Gemma 4 E4B (or E2B) with native audio input and tool calling.
- **Runtime:** MediaPipe GenAI / LiteRT-LM, on-device.
- **Voice:** Push-to-talk → Gemma 4 (audio-in) → tool dispatch loop → Android `TextToSpeech` reply.
- **Tools:** Calendar, music control, timers, contacts, app launch, web search, flashlight, volume — each is a `Tool` registered into a Hilt-bound registry and exposed to the model via JSON schema.
- **Assistant role:** Registered `VoiceInteractionService` so the user can set EdgeMind as the device's default digital assistant (Settings → Default apps).

## Building

```bash
cd android
./gradlew assembleDebug
```

The Gemma 4 model is downloaded on first run, not bundled in the APK.

## Docs

- `CLAUDE.md` — guidance for Claude Code agents working in this repo.
