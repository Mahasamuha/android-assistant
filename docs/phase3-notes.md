# Phase 3 notes

Findings from building and testing tools and skills (plan §4b) on the test phone (Pixel 6a, Android 17).

## Confirmation flow and SMS (2026-10-21)

- The flow works end to end: an ask is spoken and shown with Confirm/Cancel, the reply is judged on the phone, and a `not_confirmed` result lets the model revise ("no, say I'm ten minutes late" leads to a new read-back; "no, the other Sam" to a new lookup).
- `SmsManager` sends report `RESULT_OK` from the radio for fictional 555-01xx numbers too: "sent" means the carrier accepted it, not that it was delivered.
- With several declined sends in one session, the model (thinking off) started copying the pattern: answering a fresh request with "Okay, I won't send it", or asking for confirmation itself. Declines are now scoped in the tool result ("only this action") and the base prompt.
- The model reused an earlier `find_contact` result instead of looking up again, so a contact added mid-session was never offered. The base prompt now asks for a fresh lookup per request.
- A number saved under two contacts was read back as whichever contact the reverse lookup returned first. The read-back now names all of them, phrased as a statement ("the number saved as A and B"), because "A or B?" inside a yes/no question invited a name as the answer.
- A lone spoken "no" sometimes comes back from the on-device recognizer as no match (error 7). Confirmation listening now passes `EXTRA_BIASING_STRINGS` with yes/no words; in the first test the "no" was recognized. An unrecognized reply is treated as not confirmed and shown as a "no reply heard · cancelled" note.

## Calls (2026-10-22)

- An `ACTION_CALL` activity from the session was allowed (`BAL_ALLOW_NON_APP_VISIBLE_WINDOW`) but resolved to the system chooser, because Zoom also handles `ACTION_CALL`; the chooser opened behind the panel and no call happened. Calls now go through `TelecomManager.placeCall`, which needs `CALL_PHONE` and no activity resolution. The session closes itself once the call is placed.
- Announce-and-dial for a certain match and "which one?" for different numbers both worked.

## Keystore while "unlocked"

- The token key (`setUnlockedDeviceRequired`) refused to decrypt (`UserNotAuthenticatedException`) while the screen showed unlocked: the phone had started to lock and was woken within the grace period without a fingerprint or PIN. A real unlock fixes it; the app now says so.

## Thinking: `reasoning_effort` "none" vs "low" (2026-10-22)

Even with prompt rules against it, Haiku 5.5 with thinking off kept narrating its reasoning aloud in some answers ("The user wants to call 555, which isn't a complete number…", "The 2025 World Series is a current event relative to my training, so I'll check."), and once invented a contact option before looking anything up. Logged requests from the session were replayed through Bifrost, two runs per case (time to the first text or tool call / total):

| Case | none | low |
|---|---|---|
| Contact lookup turn | 0.7–1.3 s | 0.5–2.3 s |
| Weather (tool call) | 0.7–0.9 s | 0.6–0.7 s |
| Web search question | 0.6–1.2 s first text (a narrated preamble), 2.6–3.3 s total | 2.9–5.4 s, answer in one piece |
| Plain question | 1.1 s | 1.9 s |

`low` produced no narration in any case and dropped the "Let me find…" preambles. It costs roughly 0.5–2 s before the first word on plain and search answers; tool-call turns are unchanged. `low` works within a 400-token cap (99 thinking tokens on a plain question); the cap is now 1000 for headroom. Switched the app to `low`.

On the phone with `low` (2026-10-22): no narration in a session of weather, a call, two texts with corrections, and a search question. Turns with thinking took about 1–4 s to the first word, the search question 5.8 s; the user judged the delay normal for an assistant and kept `low`. One search answer ended with raw figures ("…about 360 points. 52,856.09 +360.21 (+0.69%)"), which would be read aloud; three replays of the same request gave clean sentences (one named its source, against the prompt). Watch for it before adding a speech-side filter.
