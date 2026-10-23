# Phone Assistant — Plan (draft)

Status: phase 1 done (`docs/phase1-notes.md`); phase 2 working end to end (`docs/phase2-notes.md`); phase 3 in progress (`docs/phase3-notes.md`).

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
| Name: Samu | The assistant's name (2026-10-22): the app label, how it refers to itself (base system prompt), and the panel's status line ("SAMU · LISTENING…"). Bylines and any about text use "Samu by Mahasamuha"; Mahasamuha is the brand and design system. |
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
| STT: on-device, behind a replaceable interface | Free, private, no extra round trip. An API implementation can be swapped in if accuracy (especially on contact names) disappoints. No silent fallback to the platform's default recognizer (which may send audio off the phone): without on-device recognition the assistant says so. |
| Confirmation policy for side effects | **Texts:** always read back recipient and body; send only after confirmation. **Calls:** always announce "Calling <name>, <number type>"; if the contact match is high-confidence, proceed without waiting; if ambiguous (multiple matches or numbers), ask first. Once the session has read outside content (web search, weather), every call asks first, since that content could carry injected instructions. An announced call dials the contact's saved number, never the model's text. Numbers must be plain (digits, `+`, separators); star codes, pauses and letters are rejected. **Confirming:** voice or tap, always both available. |
| Memory: per invocation in v1 | Context lives while the session panel is open (enough for "which Sam?" follow-ups), then is discarded. Persistent notes (e.g., aliases, preferred numbers) are a later feature. |
| Logging: configurable on the server | Cost/usage data always kept. Request/response bodies: configurable retention (start with short retention). Plus a server-side debug toggle that turns on full logging and automatically turns itself off after ~30 minutes, for reproducing issues. Controlled on the server, not the app. Likely implemented in the auth gateway (it sees every request) so it doesn't depend on Bifrost's logging features; Bifrost keeps cost data. |
| Phone stack: Kotlin + Jetpack Compose | The assistant role, session, and call/SMS/contacts APIs are all native; no cross-platform layer since iOS is permanently out of scope. Compose for the panel UI. |
| UI: the Mahasamuha design system | Paper and ink palette, Geist and Geist Mono (bundled variable fonts, OFL, so no downloadable-fonts dependency), ink primary and ghost buttons, indigo/tint chat bubbles, mono uppercase labels, the node mark drawn from its own geometry. Light follows the paper theme; system dark mode uses the flat-ink variant. Tokens and components live in `app/.../ui/`. |
| Token lifetimes | Access token ~10 minutes; refresh token ~60 days, rotated on use (sliding). Refresh token encrypted with a hardware-backed Android Keystore key. Configured in Authentik's provider settings. |
| Auth gateway implementation | TypeScript on Node (strict mode, pnpm); Bun may be explored later since the code is the same. Streams responses through without buffering (SSE pass-through), forwarding only `content-type` from upstream; Bifrost runs with `extra_fields_placement: "headers"` so its metadata (including provider org/workspace IDs) never enters response bodies. Stateless apart from the debug-logging toggle and an in-memory rate limit (both reset on restart, which is acceptable). Minimal public surface: an explicit route allowlist (today `POST /v1/responses`, `POST /mcp`, and `GET /healthz`; `POST /v1/chat/completions` was removed once the app moved to Responses), every route except health requiring a valid token, everything else 404. New routes are added deliberately and recorded here. |
| LLM endpoint: OpenAI Responses API (`/v1/responses`) | Replaces chat completions (phase 3). Chat completions flattens Claude's answer blocks into one string and drops search results, so quoted source text was read aloud and follow-ups "retracted" correct figures they could no longer see. Responses returns typed items: searches (`web_search_call`) go back in history so follow-ups see them, citations are annotations, phone tools are `function_call` / `function_call_output`. Still an OpenAI-standard format, so the gateway pair stays replaceable. Reasoning items are dropped from history (Bifrost loses the thinking signatures). Cost: about $0.0016 per later turn per kept search; full history is kept (sessions are 5–7 turns). Bifrost logs cost and budget on this route, and its bodies carry no `extra_fields`. Details: `docs/phase3-notes.md`. |
| Search sources in the panel | A search answer's bubble gets a small "↗ N" pill on its tail corner, like a chat reaction; tapping it lists the pages the search found, and tapping one opens it in the browser. Spoken answers stay free of sources. Sources are the pages our `web_search` returned for that turn, shown on the answer bubble that follows the search. (With Anthropic's search they came from citation events; Anthropic requires citations alongside search output shown to end users.) |
| Web search: our own `web_search` tool, Brave Search API behind the MCP server (§4b step 4) | Replaces Anthropic's server-side search (phases 2–3), which read verbatim source fragments aloud (Claude's cited answer blocks) and was the one provider-specific piece of the request. Brave: own index, sold for AI answers, $5 per 1,000 searches with $5 monthly credit; Bing's API is retired, Google's Custom Search closed to new customers, and self-hosted meta-search (SearXNG) scrapes engines against their terms. The `llm/context` endpoint returns extracted page text; the server sends the top 8 pages in a 24,000-character budget. In a spike it answered market closes, sports and news correctly with no quoted fragments, as fast as Anthropic's search despite the extra model round, at half the per-search price. No fallback: if the MCP server or Brave is down, there is no search. Key only in `deploy/.env`. Details: `docs/phase3-notes.md`. |
| Thinking: `reasoning_effort: "low"` | Thinking off (the phase 2 choice, for speed) let the model narrate its reasoning aloud and invent options despite prompt rules. Low effort keeps reasoning in an unspoken step; measured cost about 0.5–2 s before the first word on plain and search answers, none on tool-call turns (`docs/phase3-notes.md`). Supersedes "thinking off" in the web search row. |
| Approximate location (coarse permission, city level) | The phone resolves a coarse fix to city/region/country while the user is speaking and passes it to the prompt, which the model uses for weather and puts in search queries when location matters. Without it, location-dependent questions need the city spoken. Coordinates leave the phone only rounded, for the weather tool. |
| Weather: Pirate Weather on the server, Open-Meteo on the phone as fallback | The user already pays for Pirate Weather; it adds official alerts (NWS in the US) and minute-by-minute rain for the next hour, which Open-Meteo lacks. A remote `get_weather` on the MCP server (registered only when its key is set) replaces the phone's by name, with the same inputs (`place`, `day`, `end_day`) and output shape plus `alerts` and `next_hour`; `place` is required, since the server doesn't know where the phone is, so city-level location travels as a name and coordinates no longer leave the phone. The phone's Open-Meteo tool stays behind it and answers on any remote failure. Key in `deploy/.env`, sent in a header so it never appears in a URL. |
| Weather via a tool, not web search | Verified 2026-10-20: web search returns indexed snapshots of weather pages, often days old, and prompting can't fix stale data (a retry instruction made answers confidently wrong). A `get_weather` tool runs on the phone against Open-Meteo (no key). The swap point is the tool, not a provider interface: a keyed provider such as Pirate Weather becomes a remote `get_weather` that overrides the local one (§4b), with its key server-side. This pulls the phase 3 tool registry forward. |
| MCP client on the phone: our own, not the Kotlin SDK | The official Kotlin MCP SDK (0.15.0) roughly doubles the release APK (3.1 MB → 5.3 MB with R8 shrinking it, 10.0 MB without): it implements the whole spec for every platform (about 600 schema classes with generated serializers, Ktor, kotlinx-io, every transport). Our server is stateless with plain-JSON responses, so the phone needs only `tools/list` and `tools/call` as one POST each (no `initialize`: a stateless server keeps nothing between requests; the protocol version travels in the `mcp-protocol-version` header). About 150 lines with `HttpURLConnection` and `org.json`, like the chat client. Revisit if the phone needs more of MCP. |
| Tools and skills (§4b) | Tools shaped like MCP tool definitions (JSON Schema input, side-effect annotations); skills in the Agent Skills `SKILL.md` format with our `delivery` and `tools` fields. Both are existing standards, so nothing bespoke to maintain or explain. Confirmation is enforced by per-tool policy in phone code, never by skill text. Skills always loaded for now; progressive disclosure when they grow. |
| Confirmation mechanics (§4b) | Action tools prepare, the registry confirms, then performs, so no tool can skip the step. Ask (read back, wait for yes) or Announce (say it, go ahead unless cancelled). Yes/no is decided on the phone by a strict whole-utterance list, not by the model; anything else goes back to the model as `not_confirmed` with what the user said, which lets "no, change it to…" revise the action. Read-back text comes from the resolved arguments, not the model. |
| Hybrid delivery via an MCP server behind the gateway (§4b) | Some things must run on the phone (SMS), some must run on the server (keyed APIs like Pirate Weather). An MCP server on the private network serves remote tools and draft skills; the phone stays the single owner of the tool loop. Chosen over a bespoke gateway route (non-standard) and Bifrost's MCP integration (splits the loop, ties tools to Bifrost). Adds `/mcp` to the gateway allowlist when step 4 is built. |
| Server images: gateway and MCP published to Docker Hub by GitHub Actions | The home server pulls `mahasamuha/samu-gateway` and `mahasamuha/samu-mcp` and needs only `deploy/`, not a checkout or a build toolchain. Images hold no configuration (all env), are smoke-tested before each push, and are tagged `latest` and `sha-<commit>` for pinning and rollback. `compose.build.yaml` builds from source for development. |
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
- **Confirmation is enforced in phone code, never by skill or prompt text.** Each local tool declares its policy: SMS always confirms; a call is announced and confirms only when the contact match is ambiguous or the session has read outside content (§2); read-only tools never confirm. A remote tool confirms unless the server marks it `readOnlyHint` (so search doesn't ask every time). Nothing a skill says can weaken this.
- **Why the phone trusts a remote tool's `readOnlyHint`.** A remote tool runs on the server; confirming on the phone only decides whether the phone sends the request. Everything with a side effect on the phone (contacts, SMS, calls) is a local tool that a remote one can't replace (`allowRemote`, see Discovery), so a wrong hint can't cause a phone-side action. A compromised server can act on its own end whatever the phone confirms. What's left is a server bug that marks an effectful tool read-only, which then runs unasked; accepted.
- **Read-back for remote action tools (planned; build with the first one).** Today the phone asks a generic "Run <title> with <argument> <value>…?". The plan: the server sends a confirmation template with the tool definition, in the MCP `_meta` field (e.g. `"Add {item} to the shopping list?"`), and the phone fills it from the actual arguments, so the wording comes from the server and the values from what will really be sent. It is never written by the model; steering the model's own phrasing through the tool description would break the read-back rule above. Every remote action's confirmation goes through one phone-side preprocessor: arguments the template doesn't use are appended so nothing is hidden, and with no template the phone still asks, listing every argument and opening with a spoken warning that the tool has no confirmation text set up. The warning is meant to be noticeable enough that a misconfigured tool gets fixed rather than tolerated.
- **Confirmation flow.** A pending action carries one of two confirmations, and the registry, not the tool, carries it out before performing:
  - *Ask* (SMS, ambiguous calls): the panel shows the prompt with Confirm and Cancel, speaks it, then listens. Voice or tap, whichever comes first. A spoken reply confirms only if the whole utterance is a yes ("yes", "send it", "go ahead"…), judged on the phone by a fixed list, not by the model.
  - *Announce* (high-confidence calls): the panel shows the text with Cancel, speaks it in full, then performs. Once a read-only tool with `openWorldHint` has run in the session (web search, weather), the registry turns every Announce into an Ask with the tool's question form ("Call Mom, mobile?") for the rest of the session.
  - The prompt text is built by phone code from the resolved arguments, never from the model's text, so the user confirms what will actually happen.
  - Anything other than a yes (no, silence, "no, say ten minutes") returns `{"status": "not_confirmed", "user_said": …}` to the model, which can then revise and call again (confirmed again). The base prompt tells the model the phone confirms, so it calls send/call tools directly instead of asking first.
  - Before performing, the phone checks it is still unlocked (§2).
  - Several tool calls in one response are confirmed one at a time.
- **Discovery (step 4):** at session start, while the user is still speaking, the phone fetches the remote tool list (`tools/list`) and draft skills from the MCP server, in parallel like location, so it adds no delay. That session's registry is the local tools plus the remote ones; **a remote tool overrides a local tool with the same name only if the local tool opts in** (`allowRemote`, today only `get_weather`); otherwise the remote one is dropped, so the server can't replace a tool that reads contacts or acts on the phone. A local tool's name stays reserved even in a session where its permission is off. The model learns of tools only through the definitions sent with each request, so a newly deployed remote tool appears in the next session without an app update, and skills that reference it by name don't change. If the server is slow or down, the last fetched list (cached in memory on the phone) or the local tools alone are used. If a remote tool fails mid-session and a read-only local tool has the same name, the phone falls back to it (e.g. remote Pirate Weather fails → local Open-Meteo answers).
- Web search is a remote tool (`web_search` on the MCP server, backed by Brave), not the provider's server-side search.

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
3. Phase 3's local tools on top: `find_contact`, call, SMS, with their skills. `find_contact` returns only the matched contacts' names and numbers; `send_sms(phone_number, message)` takes a number from it, and its read-back names the recipient from the phone's own reverse lookup of that number. When the lookup finds a contact, the text goes to (or the call dials) that contact's saved number, not the model's text, so what the user hears is what happens; on the test phone the lookup did not match a number with a different area code or only the last 7 digits (the digits were read back instead), and matches with different saved numbers fall back to reading the digits. A text's read-back drops control and invisible characters and is capped at 320 characters. `place_call(phone_number, contact_name)` is announced only when the phone's contacts agree: the name matches exactly one contact, the number is one of its numbers, and no other number of that contact has the same type; otherwise it asks first. The model still does the "which Sam?" question; this is the backstop. Calls go through `TelecomManager.placeCall`, not an `ACTION_CALL` activity: on the test phone Zoom also handles `ACTION_CALL`, so the intent resolved to a chooser that opened behind the panel. The panel closes once the call is placed. Emergency numbers open the default dialer prefilled (`ACTION_DIAL`), since apps can't place them. Built: `find_contact`, `send_sms` with the `texting` skill (tested on device), and `place_call` with the `calling` skill (tested on device: announce-and-dial for a certain match, "which one?" for different numbers, panel closes on the call screen).
4. Server half: the MCP server, the gateway's `/mcp` route, and an MCP client on the phone. Then remote tools (e.g. a Pirate Weather `get_weather`) and draft skills. Built: the server (`mcp/`) with `web_search`, the `/mcp` route, and the phone's client and remote tool loading. Draft skills come when there is a draft to serve.

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
| General Q&A, spoken, with live web lookups | "How many grams in a cup of sugar?" / "What's the weather in Boston?" | LLM, plus our `web_search` tool (Brave, through the MCP server) when the answer depends on current information ($0.005 per search plus result tokens; adds a few seconds). System prompt requires short, plain-text, speakable answers (no markdown, lists, or URLs; numbers and units written to be read aloud); citations are not read aloud. | — |
| Weather | "What's the weather tonight?" / "Any weather alerts?" | `get_weather` on the MCP server, backed by Pirate Weather (adds official alerts and next-hour rain): the model passes the place (named, or the user's city from the context), the server geocodes it. If the server or Pirate Weather fails, the phone's Open-Meteo `get_weather` answers instead (coordinates rounded to about 10 km). | `ACCESS_COARSE_LOCATION` (optional; otherwise the user names the place) |
| Contact lookup | "Call Mom" → resolve "Mom" | `ContactsContract` query on the phone, exposed to the LLM as a `find_contact` tool. When nothing matches (speech recognition often misspells uncommon names), a sound-alike search over the contact names on the phone (a rough phonetic key plus Jaro-Winkler) offers up to three candidates marked approximate, and the model asks "did you mean …" before acting; only those candidates leave the phone. A nickname spelled the way the recognizer writes a name is the reliable fix for one that keeps failing. High confidence = exactly one contact match with one number of the requested type; anything else is ambiguous and the assistant asks back. | `READ_CONTACTS` |
| Phone call | "Call Mom on her mobile" | `ACTION_CALL` intent with the resolved number, launched while the session is visible. | `CALL_PHONE` |
| SMS | "Text Sam I'm running late" | `SmsManager` send, after spoken/visual confirmation of recipient and body. | `SEND_SMS` |
| Navigation | "Navigate to Costco" / "Navigate home with Google Maps" / "Navigate to Mom's" | Local `navigate` action tool, announced: a Waze (default) or Google Maps navigation intent, launched while the session is visible. The maps app resolves a named place itself (usually the nearest match); Samu doesn't ask "which one" (that would need a places API). "home" and "work" are addresses saved on the setup screen, and a contact's address comes from Contacts by exact name; the phone substitutes both, so the model never sees an address. With device location off, Samu says so and a transparent activity shows Play services' one-tap "turn on location" dialog, then opens the maps app (neither maps app handles location being off well; `play-services-location` is used only for this). | `READ_CONTACTS` (contact addresses only) |

Privacy note: contact lookup happens on the phone; only the matched name and number (not the address book) go to the LLM, and only when a tool call asks for them.

**Future expansion (not planned):** additional tools and skills such as home control via Home Assistant, playing music, and adding calendar events. §4b makes these additive: a skill, plus a local or remote tool if it needs one.

### 5b. Setup and options screen

The setup screen in `MainActivity` doubles as the options screen. Settings live in app-private `SharedPreferences` on the phone (backup is off).

- **Places (built).** Home and work addresses for `navigate`. They never go to the model: it passes "home" or "work", and the phone substitutes the address for the maps app.
- **TTS voice picker (built).** `Speaker.voices()` lists the engine's voices for the current locale that run on the phone: network voices are left out (they'd send everything Samu says, read-backs included, to the engine's servers), as are voices whose data isn't installed and the engine's `-language` alias for its default. Tapping a voice plays a sample and saves its name; each session's `PlatformSpeaker` applies it when the engine is ready, falling back to the engine default with a log line if an engine update renamed or removed it. `Voice` has no gender field and the names are opaque, so rows show "Voice N" with the raw name beneath. Alternative engines (e.g. a Piper-based one) could be selected by passing the package to the `TextToSpeech` constructor; untested.

## 6. Open questions


- **Car use (out of scope for now; phase 5 at the earliest):** would Android Auto's voice button (mic icon or steering-wheel button) reach Samu? Expected no: Android Auto routes it to Google's assistant regardless of the default assistant role, and the Car App Library has no assistant category. Plain Bluetooth without Android Auto is more promising: the steering-wheel button likely sends the same voice-search intent as a headset button, which a trampoline activity can catch (`docs/phase1-notes.md`). Either way, car use also needs lock-screen use (deferred, §2) and a UI that doesn't depend on the phone's screen. Cheap check when it matters: the Android Auto Desktop Head Unit emulator, then the real car with and without Android Auto.

(Resolved: web search, now our own tool, and the MCP client on the phone; see the decisions table.) New questions get added here as they come up.

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
