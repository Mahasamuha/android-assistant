# Phone Assistant — Plan (draft)

Status: phase 1 done (see `docs/phase1-notes.md`); phase 2 server half in progress (see `docs/phase2-notes.md`).

## 1. Goal

A personal voice assistant on Android, roughly what Google Assistant used to be, built for one user and sideloaded:

- Ask questions by voice and get spoken answers.
- Take actions on the phone: calls, texts, launch apps, timers, settings.
- Optionally understand what's on screen ("reply to this", "add this to my calendar").
- Use a hosted LLM API with spend that is tracked and capped.

Non-goals for now: self-hosted model, Play Store distribution, multi-user, iOS.

## 2. Decisions made

| Decision | Rationale |
|---|---|
| Platform: Pixel 8 on Android 17 (build CP3A.260905.009) | iOS doesn't allow third-party always-on mic or cross-app control. Target and test against Android 17 (API 37). Pixel 8 has Google's on-device speech recognition and a configurable power-button assistant gesture; it's within Google's 7-year update window. |
| App shell: native app, not HA Companion | Avoid being bound to another project's design choices; HA Assist is built around home automation, not a general phone assistant. HA Companion stays a possible fallback if the native build stalls. |
| Invocation: gesture and manual first | Phone battery is aging. A CPU-based wake word costs a lot of battery (HA measured roughly 1% → 15% per day). |
| Wake word path kept open, not built | A trigger seam in the design so it can be added later (Porcupine on CPU, or an official DSP API if one ever reaches US builds). |
| Model: hosted API, not self-hosted | Self-hosting isn't worth it for this; spend tracking is the requirement. |
| Network path: public HTTPS with OAuth from day one; no tunnel | Proton VPN is almost always on, and Android allows only one active VPN at a time, so a WireGuard/Tailscale tunnel would conflict. Public HTTPS + OAuth also matches what a VPS deployment would need. |
| LLM gateway: Bifrost (OSS, Apache 2.0), home server initially, configurable host | Virtual keys with budgets, rate limits, and cost tracking are in the free tier. Lighter than LiteLLM. Stays private behind the auth gateway. |
| Token validation: small custom auth gateway in front of Bifrost | Bifrost's OIDC/JWT support is Enterprise-only (unpublished pricing); LiteLLM's is too (reportedly ~$250/mo+). A separate gateway keeps the LLM gateway off the internet and is independent of which LLM gateway sits behind it. |
| Gateway pair is an isolated, replaceable unit | The app only sees an OpenAI-compatible base URL plus an OAuth issuer. Swapping Bifrost for LiteLLM (or anything else) touches only the gateway's upstream config. |
| v1 requires the phone to be unlocked before calling the LLM | One chunk at a time. Lock-screen use may be added later. |
| LLM provider: Anthropic | Existing account. Start with a small, fast model; latency matters more than capability for v1. Confirm Bifrost's Anthropic tool-calling translation in phase 2, including the server-side web search tool (§6). Model: Haiku 5.5 (`claude-haiku-5-5`); verified 2026-10-20 that it runs the basic web search tool (`web_search_20250305`) directly. |
| Unreachable server: fail cleanly in v1 | Spoken error, no provider key on the phone. App config holds endpoints as a list so a fallback URL/host can be added in v.next without code changes. |
| STT: on-device, behind a replaceable interface | Free, private, no extra round trip. An API implementation can be swapped in if accuracy (especially on contact names) disappoints. |
| Confirmation policy for side effects | **Texts:** always read back recipient and body; send only after confirmation. **Calls:** always announce "Calling <name>, <number type>"; if the contact match is high-confidence, proceed without waiting; if ambiguous (multiple matches or numbers), ask first. **Confirming:** voice or tap, always both available. |
| Memory: per invocation in v1 | Context lives while the session panel is open (enough for "which Sam?" follow-ups), then is discarded. Persistent notes (e.g., aliases, preferred numbers) are a later feature. |
| Logging: configurable on the server | Cost/usage data always kept. Request/response bodies: configurable retention (start with short retention). Plus a server-side debug toggle that turns on full logging and automatically turns itself off after ~30 minutes, for reproducing issues. Controlled on the server, not the app. Likely implemented in the auth gateway (it sees every request) so it doesn't depend on Bifrost's logging features; Bifrost keeps cost data. |
| Phone stack: Kotlin + Jetpack Compose | The assistant role, session, and call/SMS/contacts APIs are all native; no cross-platform layer since iOS is permanently out of scope. Compose for the panel UI. |
| UI: the Mahasamuha design system | Paper and ink palette, Geist and Geist Mono (bundled variable fonts, OFL, so no downloadable-fonts dependency), ink primary and ghost buttons, indigo/tint chat bubbles, mono uppercase labels, the node mark drawn from its own geometry. Light follows the paper theme; system dark mode uses the flat-ink variant. Tokens and components live in `app/.../ui/`. |
| Token lifetimes | Access token ~10 minutes; refresh token ~60 days, rotated on use (sliding). Refresh token encrypted with a hardware-backed Android Keystore key. Configured in Authentik's provider settings. |
| Auth gateway implementation | TypeScript on Node (strict mode, pnpm); Bun may be explored later since the code is the same. Streams responses through without buffering (SSE pass-through), forwarding only `content-type` from upstream; Bifrost runs with `extra_fields_placement: "headers"` so its metadata (including provider org/workspace IDs) never enters response bodies. Stateless apart from the debug-logging toggle and an in-memory rate limit (both reset on restart, which is acceptable). Minimal public surface: an explicit route allowlist (today `POST /v1/chat/completions` and `GET /healthz`), every route except health requiring a valid token, everything else 404. New routes are added deliberately and recorded here. |
| Web search: Anthropic's server tool on the chat-completions route | Verified in the phase 2 spike: Bifrost passes `web_search_20250305` through `/v1/chat/completions` (streaming too), counts the $0.01 search fee in cost and budget, and enforces the virtual key and model allowlist. Keeps the gateway's single OpenAI-compatible route. Thinking off via `reasoning_effort: "none"` cuts time to first text from ~4.9 s to ~1.8 s. |
| Approximate location (coarse permission, city level) | The phone resolves a coarse fix to city/region/country while the user is speaking and passes it to web search (`user_location`) and the prompt. Without it, location-dependent questions need the city spoken. Coordinates leave the phone only rounded, for the weather tool. |
| Weather via a tool, not web search | Verified 2026-10-20: web search returns indexed snapshots of weather pages, often days old, and prompting can't fix stale data (a retry instruction made answers confidently wrong). A `get_weather` tool runs on the phone against Open-Meteo (no key). The swap point is the tool, not a provider interface: a keyed provider such as Pirate Weather becomes a remote `get_weather` that overrides the local one (§4b), with its key server-side. This pulls the phase 3 tool registry forward. |
| Web search: the provider's own tool, no provider-neutral search tool | Most major providers ship their own built-in search, each in an incompatible form, so a bespoke search tool isn't worth building now. Search is the one provider-coupled piece: on a provider switch, either use that provider's search or add a remote search tool (§4b) backed by a search API (Brave, Tavily, SearXNG). |
| Tools and skills (§4b) | Tools shaped like MCP tool definitions (JSON Schema input, side-effect annotations); skills in the Agent Skills `SKILL.md` format with our `delivery` and `tools` fields. Both are existing standards, so nothing bespoke to maintain or explain. Confirmation is enforced by per-tool policy in phone code, never by skill text. Skills always loaded for now; progressive disclosure when they grow. |
| Confirmation mechanics (§4b) | Action tools prepare, the registry confirms, then performs, so no tool can skip the step. Ask (read back, wait for yes) or Announce (say it, go ahead unless cancelled). Yes/no is decided on the phone by a strict whole-utterance list, not by the model; anything else goes back to the model as `not_confirmed` with what the user said, which lets "no, change it to…" revise the action. Read-back text comes from the resolved arguments, not the model. |
| Hybrid delivery via an MCP server behind the gateway (§4b) | Some things must run on the phone (SMS), some must run on the server (keyed APIs like Pirate Weather). An MCP server on the private network serves remote tools and draft skills; the phone stays the single owner of the tool loop. Chosen over a bespoke gateway route (non-standard) and Bifrost's MCP integration (splits the loop, ties tools to Bifrost). Adds `/mcp` to the gateway allowlist when step 4 is built. |
| v1 capabilities (see §5a) | General Q&A with short spoken answers, including live web lookups; weather; contact lookup + phone call; SMS. |

## 3. Platform constraints (from research, Oct 2026)

- **Default assistant role is still available.** Researched on Android 16; Android 17's published behavior changes don't touch the assistant role or `VoiceInteractionService`. Third-party apps (ChatGPT, Perplexity) can hold it. **Phase 1 finding:** the headset button does not route through the role; it fires `ACTION_WEB_SEARCH`/`VOICE_SEARCH_HANDS_FREE` activity intents, so headset support needs a separate trampoline activity (see `docs/phase1-notes.md`). Holding it gives power-button long-press, corner swipe, lock-screen invocation, and optional screen context (`AssistStructure` + screenshot). Phase 1 confirms this on the actual phone.
- **The DSP (low-power hotword chip) path is closed to third parties.** The permissions are privileged-only and `adb` can't grant them. Root or a custom ROM gets the permissions, but loading a custom model into the DSP is vendor-specific and largely undocumented.
- **EU DMA decision (16 Jul 2026)** requires Google to open hotword detection, screen automation, App Functions, etc. to rival assistants. Deadlines: Android 18 by Aug 2027, concurrent hotwords in Android 19 by Aug 2028. It is EU-scoped, so whether US builds get it is unknown. Plan doesn't depend on it.
- **Sideloading removes Play policy limits.** `READ_CONTACTS`, `CALL_PHONE`, and `SEND_SMS` are normal runtime permissions for a personally installed app.
- **Android 17 changes that touch this app:**
  - Background audio hardening: a lifecycle condition, not a permission. Audio playback/focus/volume calls fail silently when the app isn't visible or running a foreground service; apps targeting API 37 need that foreground service to have while-in-use capabilities, which it gets if started while the app is visible. **Verified in phase 1 (Pixel 6a, Android 17):** platform `TextToSpeech` keeps speaking after the panel closes with no foreground service. The TTS engine plays the audio in its own process, and the assistant's process stays bound at foreground-service level with full capabilities while it holds the role. No foreground service is planned for on-device TTS. Recheck if audio is ever played in our own process (e.g. a cloud TTS through `AudioTrack`).
  - Contacts provider: some account columns removed from `ContactsContract.Data` (read from `RawContacts` instead); stricter SQL on unpermissioned queries. Minor for name/number lookup.
  - Background activity launch restrictions extended to `IntentSender`; launching the dialer or Signal must happen while the session is visible.
  - `ACCESS_LOCAL_NETWORK` permission required for LAN access when targeting API 37. Not needed while the gateway is reached over public HTTPS.
  - SMS OTP delay applies to reading SMS, not sending. Not relevant.
  - **Observed (Pixel 6a, Android 17):** the token key (`setUnlockedDeviceRequired`) can refuse to decrypt while the screen shows unlocked. The phone had started to lock and was woken within the grace period without a fingerprint or PIN; the Keystore still treated it as locked. A real unlock fixes it. The app says so instead of "unlock your phone".
- **Developer verification (sideloading).** Global enforcement on certified devices in 2027 (four countries from 30 Sep 2026). **ADB installs are exempt**, so installing/updating from a computer (Android Studio uses ADB) needs no registration. Installing without a computer would need either the one-time "advanced flow" (developer mode, 24-hour wait, then unverified installs allowed with a warning) or a free limited distribution account (no ID, up to 20 devices). Keep the signing key backed up: registration ties package names to signing keys.
- **LLM gateway auth is paywalled across the board.** LiteLLM: native JWT is Enterprise; a free custom-auth Python hook exists but mapping to virtual keys is Enterprise. Bifrost: OSS inbound auth is username/password or none; OIDC/SAML and upstream-proxy JWT are Enterprise; custom auth would mean a Go plugin compiled into a custom build. Hence the separate auth gateway.
- **Signal has no public API for other apps to send messages.** Considered for v1 and dropped. If revisited: one-tap handoff to Signal's chat, or signal-cli (unofficial) as a linked device on the server.

## 4. Architecture

```
Phone
  Trigger (gesture / power / headset / tile)
    → Session (panel UI)
    → STT (speech-to-text)
    → HTTPS + OAuth bearer token
                │
                ▼
Server (home server to start; isolated, replaceable unit)
  Reverse proxy (TLS)
    → Auth gateway (TypeScript)
        - validate Authentik JWT (JWKS signature, iss, aud, exp)
        - attach the phone's Bifrost virtual key
        - rate-limit
    → Bifrost (private network only)
        - virtual key → budget, rate limits, model allowlist
        - cost tracking / logs
    → LLM provider
                │
                ▼
Phone
  ←→ Tool executors (tool calls returned by the LLM run on the phone)
  → TTS (text-to-speech)
```

### Auth flow

- **Identity provider:** Authentik.
- **Phone client:** a statically registered public client in Authentik (no DCR needed for a single known app). Authorization code + PKCE (Proof Key for Code Exchange) via a browser custom tab, likely using the AppAuth-Android library.
- **Tokens on the phone:** access token short-lived; refresh token stored encrypted with an Android Keystore key, so a gesture invocation can silently refresh without a login prompt. v1 only uses them while the phone is unlocked.
- **Validation:** the auth gateway checks the JWT signature against Authentik's JWKS, plus issuer, audience, and expiry. The audience is the assistant provider's client ID, checked as a single exact string (array audiences rejected, as in the asterism-proxy design); with Authentik's per-provider issuer, a dedicated provider gives the same isolation as a per-resource audience. On success it forwards to Bifrost with the virtual key attached. Bifrost is never reachable from outside.
- **What the app is configured with:** issuer URL, client ID, gateway base URL. No secrets.
- **What the gateway is configured with:** issuer/JWKS URL, expected audience, upstream (Bifrost) URL, virtual key (env var). Upstream URL is the only thing that changes if Bifrost is swapped.

Components and options:

| Component | Choice | Alternatives | Status |
|---|---|---|---|
| App shell | Native app holding the assistant role | — | Decided |
| LLM gateway / spend tracking | Bifrost OSS; virtual key with budget for the phone | LiteLLM (custom-auth hook), others | Decided (replaceable) |
| Token validation | Custom TypeScript auth gateway | oauth2-proxy; MCP auth proxy gateway mode | Decided |
| Network path | Public HTTPS + OAuth (Authentik) | — | Decided |
| LLM provider | Anthropic (existing account), small fast model by default | OpenAI; OpenRouter as upstream | Decided (replaceable via Bifrost) |
| STT | On-device `SpeechRecognizer` behind an STT interface | Whisper / Deepgram API (via the gateway) | Decided (replaceable) |
| TTS | Android `TextToSpeech` | OpenAI / ElevenLabs API | Default to on-device |
| Actions | Intents + direct APIs (see §5a) | AccessibilityService for UI driving (fragile) | Intents first; accessibility only if needed |
| Screen context | `onHandleAssist` → send text summary to LLM | — | Later phase |

### 4b. Tools and skills

Two kinds of capability, borrowed from existing standards rather than invented: **tools** (code, shaped like MCP tool definitions) and **skills** (instructions, in the Agent Skills `SKILL.md` format). The model sees one tool list and one set of skill instructions, wherever each piece runs or comes from.

**Tools** are functions the model can call.

- Spec follows the MCP tool shape: `name`, `description`, `inputSchema` (JSON Schema), and annotations `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`. Unset annotations take MCP's defaults (not read-only, destructive, not idempotent, open-world), so an unannotated tool is treated as having side effects. Annotations stay on the phone; the chat request carries only name, description, and schema.
- Local tools are registered per session, only when their permission is granted (contacts, SMS), so a skill that needs a missing one drops out with them.
- Two kinds of local tool. **Read-only tools** (`readOnlyHint: true`, e.g. `get_weather`) just run. **Action tools** split into *prepare* (validate and resolve the arguments, return a pending action) and *perform*. The registry rejects a tool whose kind contradicts its `readOnlyHint`.
- **Local tools** run on the phone (contacts, calls, SMS, weather today). **Remote tools** run on the server, for anything that needs a key or server resources (e.g. Pirate Weather). The phone owns the tool loop for both, so the model can't tell them apart.
- **Confirmation is enforced in phone code, never by skill or prompt text.** Each local tool declares its policy: SMS always confirms; a call is announced and confirms only when the contact match is ambiguous (§2); read-only tools never confirm. Remote tools with `destructiveHint` always confirm. Nothing a skill says can weaken this.
- **Confirmation flow.** A pending action carries one of two confirmations, and the registry, not the tool, carries it out before performing:
  - *Ask* (SMS, ambiguous calls): the panel shows the prompt with Confirm and Cancel, speaks it, then listens. Voice or tap, whichever comes first. A spoken reply confirms only if the whole utterance is a yes ("yes", "send it", "go ahead"…), judged on the phone by a fixed list, not by the model.
  - *Announce* (high-confidence calls): the panel shows the text with Cancel, speaks it in full, then performs.
  - The prompt text is built by phone code from the resolved arguments, never from the model's text, so the user confirms what will actually happen.
  - Anything other than a yes (no, silence, "no, say ten minutes") returns `{"status": "not_confirmed", "user_said": …}` to the model, which can then revise and call again (confirmed again). The base prompt tells the model the phone confirms, so it calls send/call tools directly instead of asking first.
  - Before performing, the phone checks it is still unlocked (§2).
  - Several tool calls in one response are confirmed one at a time.
- **Discovery (step 4):** at session start, while the user is still speaking, the phone fetches the remote tool list (`tools/list`) and draft skills from the MCP server, in parallel like location, so it adds no delay. That session's registry is the local tools plus the remote ones; **a remote tool overrides a local tool with the same name**, the same rule as draft skills. The model learns of tools only through the definitions sent with each request, so a newly deployed remote tool appears in the next session without an app update, and skills that reference it by name don't change. If the server is slow or down, the last fetched list (cached on the phone) or the local tools alone are used. If a remote tool fails mid-session and a local tool has the same name, the phone falls back to it (e.g. remote Pirate Weather fails → local Open-Meteo answers).
- Anthropic's server-side web search stays a provider tool, enabled in the chat client, not a registry entry.

**Skills** are instruction bundles.

- A folder with a `SKILL.md`: YAML frontmatter, then plain instructions. Frontmatter: `name`, `description` (what it's for and when to use it), plus two fields of ours: `delivery` (`app` or `server`) and `tools` (tool names it relies on; omit for instruction-only skills).
- Example: *weather* bundles `get_weather` with how to phrase answers ("one current reading, 'a low around…'"); *texting* carries how to read back an SMS; a *kitchen conversions* skill would be instructions only.
- Domain rules live in skills, so the base system prompt stays generic: voice style, brevity, no reasoning narration, and how to behave around tools and actions (nothing said before an action, the phone confirms, no claiming success before the result, declined actions, fresh lookups). Rules that first appear in one skill move to the base prompt once they apply to every tool.
- **Loading: always loaded for now.** Every active skill's full text is appended to the system prompt, sorted by name so the prompt stays byte-stable for caching. A skill whose tools aren't available this session (permission off, server unreachable) is left out. Move to progressive disclosure (the prompt lists each skill's name and description, and a `load_skill` tool returns the body) when there are roughly ten or more skills or their text gets long; the `SKILL.md` format doesn't change.

**Where skills come from: one source, two delivery paths.**

- Source of truth is `skills/<name>/SKILL.md` in the repo.
- `delivery: app` skills are bundled into the APK at build time. `delivery: server` skills are drafts, served live by the server (below), so they can change without an app rebuild.
- Promoting a draft is changing `delivery: server` to `app` and rebuilding; the text doesn't move.
- On the phone: bundled skills load first, then server skills; a server skill with the same name overrides the bundled one, so an existing skill can be revised as a draft. Server skills and the remote tool list are cached on the phone, used immediately, and refreshed in the background for the next session. Fetching never delays a turn; with the server down, bundled skills and the cache still work.

**Server side: an MCP server behind the gateway.**

- A small MCP server (TypeScript, Streamable HTTP, stateless) on the private network next to Bifrost, holding the keys its tools need in env. It serves remote tools (`tools/list`, `tools/call`) and draft skills as resources (`skill://<name>`), read from the deployed `skills/` directory.
- The gateway adds `/mcp` to its route allowlist (§2), with the same token check and rate limit, forwarding without any key from the phone. It's a standard protocol, so the same server also works from other MCP clients.
- Not Bifrost's MCP integration: that would run part of the tool loop inside the LLM gateway and tie tools to Bifrost (against the replaceable-unit design).

**Rollout**, each step usable on its own:

1. Bundled skills: `skills/` in the repo, the app build copies `delivery: app` skills into assets, prompt assembly, and weather's rules move from the base prompt into `skills/weather`. No server change.
2. Tool spec: annotations and per-tool confirmation policy in the registry, plus the confirmation flow in the panel (voice or tap). Needed before anything sends or calls. Built; first exercised on the phone by step 3's SMS tool.
3. Phase 3's local tools on top: `find_contact`, call, SMS, with their skills. `find_contact` returns only the matched contacts' names and numbers; `send_sms(phone_number, message)` takes a number from it, and its read-back names the recipient from the phone's own reverse lookup of that number. `place_call(phone_number, contact_name)` is announced only when the phone's contacts agree: the name matches exactly one contact, the number is one of its numbers, and no other number of that contact has the same type; otherwise it asks first. The model still does the "which Sam?" question; this is the backstop. Calls go through `TelecomManager.placeCall`, not an `ACTION_CALL` activity: on the test phone Zoom also handles `ACTION_CALL`, so the intent resolved to a chooser that opened behind the panel. The panel closes once the call is placed. Emergency numbers open the default dialer prefilled (`ACTION_DIAL`), since apps can't place them. Built: `find_contact`, `send_sms` with the `texting` skill (tested on device), and `place_call` with the `calling` skill (awaiting its on-device test).
4. Server half: the MCP server, the gateway's `/mcp` route, and an MCP client on the phone. Then remote tools (e.g. a Pirate Weather `get_weather`) and draft skills.

## 5. Phases

1. **Prove invocation.** App holds the assistant role on the Android 17 phone; gesture opens a panel.
2. **Auth + voice round trip.** Authentik client; auth gateway + Bifrost on the home server with a budgeted virtual key. App signs in once, then STT → LLM → TTS with no tools. Verify budget enforcement and cost logging. Covers v1 capability 1.
3. **Actions.** Tools and skills per §4b, rollout steps 1–3: bundled skills, the tool spec with confirmation, then the v1 tools (§5a). Step 4 (server-side tools and draft skills via MCP) follows when a server tool is needed. (The tool loop and the weather tool were pulled forward into phase 2.)
4. **Screen context.** Feed on-screen text to the LLM when invoked.
5. **Gaps only.** AccessibilityService UI driving, wake word, HA integration, lock-screen use — each only if earlier phases leave a real need.

Phases 1 and the server half of 2 are independent and can be done in either order.

Exit criterion per phase: used daily for a week without wanting to go back to the previous phase's workaround.

### 5a. v1 capabilities

| Capability | Example | Mechanism | Permission |
|---|---|---|---|
| General Q&A, spoken, with live web lookups | "How many grams in a cup of sugar?" / "What's the weather in Boston?" | LLM, plus Anthropic's server-side web search tool when the answer depends on current information (~$0.01 per search plus result tokens; adds a few seconds). System prompt requires short, plain-text, speakable answers (no markdown, lists, or URLs; numbers and units written to be read aloud); citations are not read aloud. | — |
| Weather | "What's the weather tonight?" | `get_weather` tool on the phone: approximate location (or a named place, geocoded on the phone) → Open-Meteo forecast. Coordinates sent rounded to about 10 km. | `ACCESS_COARSE_LOCATION` (optional; otherwise the user names the place) |
| Contact lookup | "Call Mom" → resolve "Mom" | `ContactsContract` query on the phone, exposed to the LLM as a `find_contact` tool. High confidence = exactly one contact match with one number of the requested type; anything else is ambiguous and the assistant asks back. | `READ_CONTACTS` |
| Phone call | "Call Mom on her mobile" | `ACTION_CALL` intent with the resolved number, launched while the session is visible. | `CALL_PHONE` |
| SMS | "Text Sam I'm running late" | `SmsManager` send, after spoken/visual confirmation of recipient and body. | `SEND_SMS` |

Privacy note: contact lookup happens on the phone; only the matched name and number (not the address book) go to the LLM, and only when a tool call asks for them.

**Future expansion (not planned):** additional tools and skills such as home control via Home Assistant, playing music, and adding calendar events. §4b makes these additive: a skill, plus a local or remote tool if it needs one.

## 6. Open questions

- **MCP client on the phone** (§4b step 4): the official Kotlin MCP SDK, or a minimal client for `tools/list`, `tools/call`, and `resources/read`. Decide by the SDK's size and maturity when step 4 starts.
- **Weather long term:** keep the local Open-Meteo `get_weather`, or deploy a keyed provider (Pirate Weather) as a remote `get_weather` once step 4 exists; the local one then remains as the fallback.

(Web search through Bifrost was resolved by the phase 2 spike; see the decisions table and `docs/phase2-notes.md`.) New questions get added here as they come up.

## 7. Risks

- **Battery** if a wake word is ever added. Mitigation: keep it optional and off by default.
- **Endpoint availability.** If the server, gateway, or Authentik is down, the assistant is down. v1 fails cleanly; fallback endpoint is v.next.
- **Public exposure.** The auth gateway is internet-facing. Keep it minimal, expose only the needed routes, rate-limit; Bifrost budget caps bound the worst case.
- **Misfired actions.** Wrong contact or a misheard message body. Mitigated by the confirmation policy (§2) and asking back on ambiguous matches.
- **Bifrost maturity / vendor.** Single-vendor open-core project; most documentation and comparisons come from the vendor. Mitigated by the replaceable-unit design.
- **Latency.** Two hops add round-trip time; likely small next to LLM latency, but measure in phase 2.
- **AccessibilityService fragility** — breaks when apps change UI. Keep it out of the core path.
- **Policy drift** — Google could change role behavior or Gemini integration in future Android releases. The DMA ruling cuts the other way in the EU; US is unknown.

## 8. References

- EU DMA developer portal, Alphabet AI interoperability: https://digital-markets-act.ec.europa.eu/developer-portal/interoperability/alphabet-specification-proceedings-interoperability-ai-services_en
- Open Home Foundation on the HA wake-word fight: https://www.openhomefoundation.org/blog/a-big-win-for-android-interoperability/
- Developers Digest summary: https://www.developersdigest.tech/blog/eu-dma-android-ai-assistant-interoperability
- Android 17 behavior changes (all apps): https://developer.android.com/about/versions/17/behavior-changes-all
- Android 17 behavior changes (targeting 17): https://developer.android.com/about/versions/17/behavior-changes-17
- Android developer verification: https://developer.android.com/developer-verification
- Developer verification FAQ: https://developer.android.com/developer-verification/guides/faq
- Bifrost pricing / OSS vs Enterprise: https://www.getmaxim.ai/bifrost/pricing
- Bifrost auth setup: https://docs.getbifrost.ai/quickstart/gateway/setting-up-auth
- Bifrost plugins: https://www.getmaxim.ai/docs/bifrost/contributing/plugin.md
- LiteLLM custom auth (considered alternative): https://docs.litellm.ai/docs/proxy/custom_auth
- LiteLLM JWT auth (Enterprise): https://docs.litellm.ai/docs/proxy/jwt_auth_arch
