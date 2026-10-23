<p align="center">
  <img src="docs/assets/samu-mark.svg" width="96" height="96" alt="The Mahasamuha mark: a network of colored nodes in a hexagon">
</p>

<h1 align="center">Samu</h1>

<p align="center"><em>Samu by Mahasamuha</em><br>A personal voice assistant for Android</p>

Samu replaces Google Assistant on one Pixel phone. The app holds the default assistant role, so a long-press of the power button opens its panel. It listens on the phone, sends the question to a hosted LLM (Anthropic's Claude) through a small self-hosted gateway, and speaks the answer on the phone.

It's built for one user and one device. The design and the reasoning behind each decision are in [`docs/plan.md`](docs/plan.md).

## What it does

| Ask | What happens |
|---|---|
| "How many grams in a cup of sugar?" | Answered by the model. |
| "What did the Dow do today?" | Web search (Brave Search API), answered in a sentence or two, with source links in the panel. |
| "Will it rain this weekend?" / "Any weather alerts?" | Weather from Pirate Weather, with Open-Meteo on the phone as a fallback. |
| "Call Sam Lee" | Looks up the contact on the phone and places the call: announced when the match is certain, confirmed first when it isn't. |
| "Text Sam I'm running late" | Reads back the recipient and message and sends only after a yes. |
| "Navigate to Costco" / "Navigate home with Google Maps" / "Navigate to Mom's" | Opens Waze (default) or Google Maps with turn-by-turn directions. |

Misheard contact names get a "did you mean…" from a sound-alike search on the phone. If location is off when navigating, a one-tap dialog turns it on first.

## How it works

```
phone ──HTTPS + token──▶ auth gateway ──┬─ /v1/responses ─▶ Bifrost ──▶ Anthropic
  │                     (public)        └─ /mcp ──────────▶ MCP server ──▶ Brave Search, Pirate Weather
  │                                        (private network: Bifrost and the MCP server aren't reachable from outside)
  └─ on the phone: speech recognition, text-to-speech, the tool loop, contacts, calls, texts, navigation
```

- **Phone app** (`app/`, Kotlin and Jetpack Compose, Android 17). A `VoiceInteractionService` holding the assistant role. It signs in once with OAuth (authorization code + PKCE) and keeps the refresh token encrypted under a hardware-backed Keystore key. It runs the tool loop: tool calls from the model run on the phone, or on the MCP server for tools that need a server-side key.
- **Auth gateway** (`gateway/`, TypeScript). The only public piece. It validates the identity provider's JWT, rate-limits per user, adds the Bifrost virtual key, and streams responses straight through. Anything outside its three routes (`POST /v1/responses`, `POST /mcp`, `GET /healthz`) is a 404.
- **Bifrost** ([getbifrost.ai](https://www.getbifrost.ai)). An LLM gateway on the private network that holds the provider key and enforces a monthly budget and a model allowlist.
- **MCP server** (`mcp/`, TypeScript). Stateless remote tools, reached only through the gateway: `web_search` and `get_weather`.
- **Skills** (`skills/`). Short instruction bundles in the `SKILL.md` format that teach the model each task, such as calling or navigation. They're bundled into the app.

## Privacy and safety

- **No provider keys on the phone.** It holds only the identity provider's URL, a client ID and the gateway's URL. Keys live on the server.
- **Speech stays on the phone.** Recognition must be on-device: if it isn't available, Samu says so rather than falling back to a cloud recognizer. The voice picker lists only on-device voices.
- **Contacts stay on the phone.** Only the matched names and numbers go to the model. Home, work and contact addresses never go to the model: it says "home" or a contact's name, and the phone fills in the address for the maps app.
- **Location** is shared only at city level, for local answers.
- **Side effects are enforced in code, not by the prompt.** Texts are always read back and need a yes. Calls are announced when the contact is certain and confirmed otherwise. After a session has read outside content (web search, weather), calls and navigation ask first, so text from a web page can't start an action on its own. Numbers must be plain digits, so star codes like call forwarding are refused.
- **Samu talks to the LLM only while the phone is unlocked.**
- **Logs:** the gateway can keep request and response bodies for debugging, with a retention limit, and the deployed configuration turns it off. Bifrost keeps cost and usage, not content.

## Repository layout

```
app/       Android app (Gradle, Kotlin DSL)
gateway/   Auth gateway (Node, pnpm)
mcp/       MCP server for remote tools (Node, pnpm)
deploy/    Docker Compose for the gateway, Bifrost and the MCP server; env templates
skills/    SKILL.md instruction bundles
docs/      The plan, identity provider setup, and findings from each phase
```

Each directory builds on its own; there's no monorepo tooling.

## Setting it up

1. **Identity provider.** Create a public OAuth client with PKCE and its own audience. The steps for Authentik are in [`docs/authentik-setup.md`](docs/authentik-setup.md).
2. **Server.** The gateway and MCP server images are published to Docker Hub (`mahasamuha/samu-gateway`, `mahasamuha/samu-mcp`) by GitHub Actions. On the server you need only `deploy/` and its env files. Then run `docker compose pull && docker compose up -d` behind a reverse proxy. The details, including Bifrost's configuration and budget, are in [`deploy/README.md`](deploy/README.md).
3. **App.** Copy `app/config.example.properties` to `app/config.local.properties`, fill in the issuer, client ID and gateway URL, then build with `./gradlew assembleDebug` in `app/` and install with `adb install`. Open Samu, sign in, set it as the digital assistant, and grant the permissions on its setup screen. The setup screen is also where you set home and work addresses and pick a voice.

Real hosts, keys and IDs live only in gitignored files (`.env`, `deploy/local/`, `app/config.local.properties`); the repository holds templates with placeholders.

## Status

Phases 1–3 of the plan are done: invocation, the voice round trip, and tools and skills, including the MCP server and navigation. The server side is deployed, and Samu is in daily use. Phase 4, sending on-screen context to the model, waits until there's a real need for it. Findings from each phase are in `docs/phase*-notes.md`.
