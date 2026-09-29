# Grok Watch: talk to Grok by voice on Wear OS

Standalone Wear OS app (built for the Pixel Watch 5, runs on Wear OS 4+ / API 33+). It gives you a live
voice conversation with Grok through the xAI **Grok Voice Agent (speech-to-speech) API**.

* **Watch-face complication** ("Talk to Grok": SHORT_TEXT / SMALL_IMAGE / MONOCHROMATIC_IMAGE). Tapping it opens the app.
* **Listens right away** when opened. It asks for the microphone once, then starts listening on every launch.
* **Answers by voice**, and you can keep asking follow-ups until you leave the app. The mic and the WebSocket close in `onStop`.
* **Live captions** for both you and Grok, in a scrollable, round-screen `ScalingLazyColumn` (Wear Compose Material 3).
* **History** is stored locally in Room. You can reopen a past conversation and keep going: earlier turns are sent back to Grok as context.
* Keeps the screen on during a session. Errors like missing key, bad key, no network, or mic busy show a clear message and a Retry button.

## API choice (from docs.x.ai)

| | |
|---|---|
| Endpoint | `wss://api.x.ai/v1/realtime?model=grok-voice-think-fast-2.0` |
| Auth | `Authorization: Bearer <XAI_API_KEY>` header on the WebSocket upgrade |
| Model | `grok-voice-think-fast-2.0`: the docs call it the "Flagship voice model"; `grok-voice-latest` is an alias for it. It's pinned here, as the docs recommend for production. Override with `-PgrokModel=grok-voice-latest`. |
| Voice | `sal` (neutral: smooth, balanced — xAI built-in). Override with `-PgrokVoice=rex` / `ara` / `eve` / `leo` / … |
| Turn-taking | `turn_detection: server_vad` (the server detects the end of speech and starts the reply) |
| Audio in | `audio/pcm` PCM16 LE mono, 16 kHz (falls back to 24/48 kHz if the mic can't do 16 kHz), base64 in `input_audio_buffer.append` |
| Audio out | `audio/pcm` PCM16 LE mono, 24 kHz (API default/recommended) from `response.output_audio.delta` |
| Captions | User: `conversation.item.input_audio_transcription.updated` (live, needs `audio.input.transcription.model = "grok-transcribe"`) and `…completed`. Grok: `response.output_audio_transcript.delta/done` |
| Tools | Server-side `web_search` and `x_search`, so Grok can answer current-events questions |
| Resume | Earlier turns are replayed with `conversation.item.create` (`message` items, roles `user`/`assistant`) |

Docs: https://docs.x.ai/docs/guides/voice/agent ·
https://docs.x.ai/developers/rest-api-reference/inference/voice ·
https://docs.x.ai/developers/model-capabilities/audio/text-to-speech (voice list)

### Echo and barge-in
The mic uses the `VOICE_COMMUNICATION` source, with `AcousticEchoCanceler`, `NoiseSuppressor`, and AGC turned on where the watch supports them. The session stays in `MODE_IN_COMMUNICATION` for AEC, but playback uses `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH` on a large `MODE_STREAM` AudioTrack (prefer 48 kHz with explicit linear upsample from the API's 24 kHz PCM) so the watch speaker is not forced through the tinny telephony/SCO EQ.
By default the app is **half-duplex**: while Grok is talking it sends silence instead of the mic signal, so the watch
speaker can't trigger the server's voice detection. Tap **Stop** (pinned at the top of the screen while Grok speaks) to interrupt. That calls `response.cancel`,
clears local playback, and sends `conversation.item.truncate` so Grok's context matches what you actually heard.
**Mute**, directly above Stop at the top while Grok is speaking, pauses only the current spoken reply (AudioTrack playback). Captions keep
updating. Tap again to hear the rest of that same reply. The next assistant turn always starts unmuted. Mute is not saved
across responses or restarts. The bottom edge button stays **History** (or **Retry** on a recoverable error).
Turn on **Voice interrupt** in the list to talk over Grok instead. This relies on the watch's echo cancellation.

### Volume
On-screen **− / +** controls on the conversation screen adjust `STREAM_MUSIC` (the stream Android maps `USAGE_ASSISTANT` AudioTrack playback onto). The preferred level is stored in SharedPreferences and re-applied when a session starts. Compact bars + percent sit between the buttons so captions stay readable. Physical crown / system volume changes on that stream are also reflected in the UI.

## Build

Prerequisites: JDK 17 and the Android SDK (platform `android-37.0`, build-tools 36+).

```bash
export XAI_API_KEY=xai-...        # read at build time into BuildConfig (never commit it)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

You can put `XAI_API_KEY=...` in `local.properties` (it's git-ignored) instead of using the env var. If there's no key,
the app still builds and shows **"API key missing"**.
If `dl.google.com` is blocked on your network, set `GOOGLE_MAVEN_MIRROR=https://dl-ssl.google.com/dl/android/maven2/`.

> Security note: a key compiled into an APK can be pulled out of it. That's fine for a personal sideloaded build. For
> anything you distribute, mint short-lived **ephemeral tokens** (`POST /v1/realtime/client_secrets`) on a server instead.

## Sideload onto the watch

1. On the watch, go to **Settings → System → About → Versions** and tap **Build number** 7 times to enable Developer options.
2. Go to **Settings → Developer options** and turn on **ADB debugging**, then **Wireless debugging**. The watch and computer must be on the same Wi-Fi.
3. In **Wireless debugging**, tap **Pair new device**. Note the pairing code and the `IP:port` it shows, then run:
   ```bash
   adb pair <watch-ip>:<pairing-port>        # enter the 6-digit code
   ```
4. Back on the Wireless debugging screen, note the **IP address & port** (a different port from the pairing one):
   ```bash
   adb connect <watch-ip>:<port>
   adb devices                               # the watch should be listed
   ```
5. Install and launch:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.jsalinas.grokwatch/.ui.MainActivity
   ```
   Allow the microphone prompt on first launch.

## Add the complication to a watch face

1. Long-press the watch face, then tap **Customize** (or **Edit**).
2. Swipe to the **Complications** page and tap a slot.
3. Pick **Grok → Talk to Grok**. Short-text, small-image, and icon slots are supported.
4. Press the crown to save. Tapping the complication opens Grok and it starts listening.


## Project layout

```
app/src/main/kotlin/com/jsalinas/grokwatch/
  realtime/GrokRealtimeClient.kt   OkHttp WebSocket + xAI realtime events
  audio/MicRecorder.kt             AudioRecord (VOICE_COMMUNICATION + AEC/NS/AGC)
  audio/PcmPlayer.kt               streaming AudioTrack with instant flush (barge-in)
  audio/AudioRouting.kt            communication mode, speaker/BT routing, audio focus, playback volume
  ui/ChatViewModel.kt              session state machine, transcripts, persistence
  ui/ChatScreen.kt, HistoryScreen.kt, GrokWatchRoot.kt (SwipeDismissableNavHost)
  data/                            Room: conversations + messages
  complication/GrokComplicationService.kt
```

## Known limitations
* The app hasn't been tested on a real watch or emulator yet; it has only been built.
* Audio travels as base64 JSON (~43 KB/s up, ~64 KB/s down). That's fine on Wi-Fi/LTE, but it may stutter over the phone's Bluetooth proxy.
* Leaving the app (crown press, swipe away, screen off) ends the live session on purpose. Reopening from History resumes it with a new socket and the earlier turns replayed as text.
* If the server rejects `transcription.model = grok-transcribe`, the app automatically falls back to completed-only user captions.
