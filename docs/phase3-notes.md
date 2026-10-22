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

## Web search answers (2026-10-22)

- **Quoted source text read aloud.** Search answers often ended with a verbatim snippet ("7,712.57 +21.84 (+0.28%) At close: October 20 at 4:45:45 PM EDT"). On Bifrost's native Anthropic route the cause is visible: Claude answers in several text blocks, and the cited block's text is the source sentence nearly word for word (equal to its `cited_text`); citations are always on for web search, and the chat-completions route concatenates all text blocks. Not a Bifrost injection. A base-prompt rule ("answer in your own words in one or two sentences and stop; never quote or repeat source text; never add a second version") cut it from 4 of 5 replays to 0 of 6.
- **Retracting its own correct answers.** The chat-completions route doesn't return search results, so the phone's history holds only the spoken sentence. On a follow-up the model saw an unsupported figure and announced it had made it up, then searched and found the same figure. A base-prompt rule (earlier answers came from searches whose results it can no longer see; don't retract unless asked) stopped it in 5 of 5 replays. If this recurs, the next step is marking searched answers in history.

## Responses API spike (2026-10-22)

The chat-completions route flattens Claude's answer blocks into one string and drops search results, which caused both problems above. Bifrost also serves the OpenAI Responses API (`POST /v1/responses`); probed with the app's real system prompt and tools:

- **Typed output.** A search comes back as a `web_search_call` item (query and sources), the answer as `output_text` with citations as `url_citation` annotations, reasoning as its own item. Phone tools round-trip as `function_call` / `function_call_output` items (a tool with no parameters arrives with empty `arguments`). Streaming uses typed events (`response.output_text.delta`, `response.function_call_arguments.delta`, `response.completed`).
- **Searches survive in history.** Sending the output items back as input lets follow-ups see earlier searches: asked "where did you get that number?", the model named the sites. Reasoning items can't be sent back (Anthropic: "thinking blocks … cannot be modified"; Bifrost doesn't keep the signatures), so history drops them.
- **Quoted source text.** In the probes, answers on this route were clean even without the no-quoting rule; the rule stays as a backstop, since a cited block can still be source-like `output_text`.
- **Cost.** Each search kept in history adds about 15,000 input tokens to every later turn of the session (three-turn conversation: 18,215 / 33,454 / 20,133 input tokens on Responses versus 18,277 / 18,239 / 5,154 on chat completions). Bifrost's logged cost: about $0.0016 more per later turn per kept search.
- **Tracking.** Bifrost logs `responses` and `responses_stream` requests under the phone's virtual key with cost (search fee included) and attributes them to the same budget (`budget-phone`). Budget enforcement itself was verified on chat completions in phase 2, not re-tested here.
- **Latency.** Comparable; the larger history adds a little (2.4 s versus 1.7 s on a no-search follow-up).

Switched (2026-10-22): the app's `GatewayChatClient` now speaks Responses; assistant turns keep the returned items (minus reasoning) and send them back verbatim. The gateway allowlist has `POST /v1/responses`; chat completions stays until the phone test passes. Checked: Responses bodies, streamed and not, contain no `extra_fields` or provider account IDs (Bifrost's metadata stays in `x-bifrost-*` headers, which the gateway doesn't forward). The "don't retract earlier search answers" prompt rule is gone, since the searches are now in history; the no-quoting rule stays.

Thinking off on Responses (2026-10-22), 3 runs each with the app's prompt: narration came back on search questions in 3 of 3 ("The 2025 World Series is last year relative to 2026, so I'll look it up."), plus muddled visible reasoning on the S&P question; `low` was clean. So narration is a property of thinking off, not of the endpoint; `low` stays. With `low`, one of three S&P answers still ended with a source fragment ("At close: October 20 at 4:00:00 PM EDT"): rarer on Responses with the no-quoting rule, not gone.
