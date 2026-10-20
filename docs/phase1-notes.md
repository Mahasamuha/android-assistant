# Phase 1 — Invocation results

Device: Pixel 8, Android 17 (build CP3A.260905.009). Fill in each result after testing on the device.

Results are marked by where they were observed: *(emu)* = emulator (API 37 Google Play image, 6 GB RAM), *(6a)* = Pixel 6a on Android 17 (same platform version, Pixel gestures). Confirm on the Pixel 8 when convenient.

## How to test

```sh
cd app
./gradlew installDebug
adb logcat -s Assistant
```

Every session callback logs under the `Assistant` tag: `onShow` show-flags and args, `onHandleAssist`, `onHandleScreenshot`, TTS init/speak/progress, and the role request result.

## Checklist

| # | Check | Result | Notes |
|---|---|---|---|
| 1 | App appears in Settings → Apps → Default apps → Digital assistant app | Yes *(emu)* | `dumpsys role` lists it as `ROLE_ASSISTANT` holder |
| 2 | Picker still lists the app with the stub `RecognitionService` removed (is it required?) | Required *(6a)* | Removing it and updating the app made the system log `AssistantRoleBehavior: ... has an unqualified voice interaction service`, revoke our role, and fall back to the Google app. Keep the stub. |
| 3 | `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` ("Test: request assistant role" button) | Refused *(emu, 6a)* | `isRoleAvailable=true`, but the request returns `RESULT_CANCELED` immediately even while the app holds the role. Settings is the only way to select it. |
| 4 | Which settings intent lands on the digital assistant picker: `ACTION_VOICE_INPUT_SETTINGS` vs `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` | `ACTION_VOICE_INPUT_SETTINGS` *(6a)* | Opens the digital assistant picker directly. `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` opens the Default apps list. Setup screen keeps only the first. |
| 5 | Power-button long-press opens the panel (Settings → System → Gestures → Press & hold power button → Digital assistant) | Yes *(emu, 6a)* | `onShow` flags: `SOURCE_ASSIST_GESTURE`; args include `invocation_type=6` |
| 6 | Corner swipe opens the panel | Yes *(6a)* | Gesture navigation → "Swipe to invoke assistant". `onShow` flags: `SOURCE_ASSIST_GESTURE`; args `invocation_type=1`, `triggered_by=83` (power long-press is `invocation_type=6`, so the two are distinguishable) |
| 7 | Headset button opens the panel | No *(6a, simulated)* | No headset with an assistant gesture available; simulated with `adb shell input keyevent --longpress KEYCODE_HEADSETHOOK`. The role is not involved: `MediaSessionService` launches `ACTION_WEB_SEARCH` (screen unlocked), which only the Google app handles, and the Google app redirects to the digital assistant settings because it isn't the assistant. See Findings. |
| 8 | TTS test line plays while the panel is up (panel counts as "visible" for audio) | Yes *(emu, 6a)* | Audible on both. Clean on the 6a; the popping on the emulator was emulator audio. |
| 9 | Closing the panel mid-sentence: does audio stop, and what do the TTS callbacks log? | Keeps playing *(6a)* | With TTS shutdown deferred until the utterance ends, speech continued ~4 s after `onHide`/`onDestroy` and finished normally (`onDone`). No foreground service needed; see Findings. |
| 10 | What `onHandleAssist` receives (structure / content / screenshot), with and without "Use text from screen" | Partial *(emu)* | Without it: called once (`index=0/1`), structure, content and data all null; no screenshot callback. |

## Findings

- **TTS can fail on the first try.** On a fresh emulator the Google TTS engine was frozen; our bind woke it, it restarted for a Play Services module update, and `TextToSpeech` init returned `ERROR` (-1). The second invocation worked. The real TTS implementation must handle init failure (retry once, then show an error) rather than staying silent.
- **TTS keeps playing after the panel closes, without a foreground service.** The engine (`com.google.android.tts`) plays the audio in its own process, so Android 17's background-audio rule for our app doesn't apply. Our process also stays at process state 5 (bound foreground service) with full capabilities after the panel hides, because the system keeps the assistant bound while it holds the role. Plan §3 updated; the foreground service is dropped for on-device TTS.
- **TTS init costs ~0.9–1.1 s on the 6a** (1.7 s on the emulator). Keep creating it per session at `onCreate`: in the real flow, TTS isn't needed until after STT and the LLM reply, so init overlaps with those and stays hidden. Keeping it alive in `AssistantService` would hold the engine process bound (memory, and it can't be frozen) for no visible gain.
- **No audio focus yet.** Other audio keeps playing over the assistant. Phase 2 should request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` while speaking.
- **Emulator setup:** Studio's Pixel 8 profile defaults to 2 GB RAM, which is too little for the API 37 Google Play image: the low-memory killer loops on the launcher and the home screen keeps reloading. 6 GB is fine. The UWB and NFC HALs crash-loop on the emulator (no hardware); ignore them.
- **The headset button bypasses the assistant role.** A headset long-press goes through `MediaSessionService` as a plain activity intent (`ACTION_WEB_SEARCH` when unlocked; `VOICE_SEARCH_HANDS_FREE` is the locked counterpart), not through `VoiceInteractionService`. To support it, the app would need a trampoline activity handling those intents (and probably `ACTION_VOICE_COMMAND` for Bluetooth headsets) that asks the running `AssistantService` to `showSession()` and finishes; the first use would show a chooser against the Google app. Bluetooth (HFP voice-recognition) routing is untested.
- **The role can be assigned over adb**, which saves a trip to Settings after a test install: `adb shell cmd role add-role-holder android.app.role.ASSISTANT com.mahasamuha.assistant`. Check with `adb shell cmd role get-role-holders android.app.role.ASSISTANT`.

(Write up anything else surprising here, and update `docs/plan.md` if a decision changes.)
