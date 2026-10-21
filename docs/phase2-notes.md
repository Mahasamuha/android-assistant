# Phase 2 — Server notes

## Spike: web search through Bifrost (2026-10-20)

Goal: resolve plan §6, whether Anthropic's server-side web search survives Bifrost's OpenAI-compatible route, and whether Bifrost's governance covers it.

Setup: Bifrost `v2.3.1` in Docker on a workstation, bound to localhost, Anthropic key from `deploy/local/.env`. Model `claude-haiku-5-5`, tool `web_search_20250305` (`max_uses` 1–2). Question: "What is the weather in Boston right now?" Total spend for the spike: about 6¢.

### Results

| Check | Result |
|---|---|
| Haiku 5.5 supports web search | Yes. Models API: `capabilities.server_tools.web_search.supported = true`; code execution and `thinking: disabled` also supported. |
| Web search on `POST /v1/chat/completions` | Works. Pass the Anthropic tool object as-is in `tools` (`{"type": "web_search_20250305", "name": "web_search", "max_uses": N}`). The search runs; the reply is plain `message.content`. Usage reports `completion_tokens_details.num_search_queries` and `usage.tool_usage.web_search.num_requests`. Citations and search-result blocks are not returned on this route (fine for voice). |
| Streaming (SSE) | Works with web search. |
| Turning thinking off | `"reasoning_effort": "none"` (the standard OpenAI field) works; Anthropic-style `"thinking": {"type": "disabled"}` also works. Use `reasoning_effort` to stay provider-neutral. |
| Cost tracking | Bifrost's logged cost includes the $0.01 search fee (e.g. 15,524 input × $0.10/M + 158 output × $0.50/M + $0.01 = $0.0116314, exactly as logged). The breakdown labels it all `input_cost`. |
| Virtual key required | With `client.enforce_auth_on_inference: true`: no key → 401; unknown key → 401. |
| Model allowlist | A model outside `allowed_models` → 403. |
| Budget | Enforced before each request, including search fees: with a $0.015 budget, requests 1–2 ran and request 3 got 402 (`Budget exceeded: 0.0233 >= 0.0150`). Overshoot is at most one request. Usage persists across a container restart. |

### Latency (streamed, one search, workstation → Anthropic)

| Variant | First text | Total |
|---|---|---|
| Default thinking (adaptive, ~370 thinking tokens) | 4.9 s | 5.4 s |
| `reasoning_effort: "none"` | 1.8 s | 4.0 s |

With thinking off, the model usually streams a short preamble ("I'll check the current weather in Boston for you.") before the search, then the answer. The stream doesn't mark the boundary, and the two sentences arrive with no space between them ("…for you.Based on…"). The app should speak the preamble while waiting and split sentences without relying on a following space.

### Things the gateway and deploy must handle

- **`extra_fields`.** By default Bifrost adds `extra_fields` to every response, every SSE chunk, and error bodies, including `provider_response_headers` with the Anthropic organization and workspace IDs. Fixed in config: `client.extra_fields_placement: "headers"` removes it from bodies and chunks (nested fields are dropped entirely; scalars move to `x-bifrost-*` headers, which the gateway doesn't forward). Verified end to end: 0 of 35 stream chunks contained it.
- **Bifrost logs content by default** (`content_summary` with the prompt and answer). Turned off with `client.disable_content_logging: true`; body logging with retention belongs to the gateway (plan §2, Logging).
- **The "telemetry" plugin** is Bifrost's local Prometheus metrics, not phoning home. Left as is.
- **Governance needs the config store.** With `config_store.enabled: false`, the governance plugin fails to start. Use the default SQLite config store (on a persistent volume, which also keeps budget usage across restarts).
- **Virtual keys must name provider keys by ID.** Give each provider key an explicit `id` and list it in the virtual key's `provider_configs[].key_ids`. `key_ids` doesn't accept key names (fatal at startup), and omitting it leaves the virtual key with no usable key (`no_key_supports_model`).
- **Virtual key header:** `x-bf-vk`. Keys prefixed `sk-bf-` are also accepted in `Authorization`, but the gateway should send `x-bf-vk`.
- **Unverified:** whether editing `config.json` (e.g. raising the budget) resets stored usage; whether an unclean shutdown loses recent usage.

### Prompt notes for later

- Without guidance the model hedges ("conditions may vary", "check a live source"). The system prompt should ask for a single, most recent reading and no caveats.

## End-to-end test of gateway + Bifrost (2026-10-20)

`deploy/compose.yaml` run locally with test values, plus a mock identity provider container on the backend network issuing Authentik-style tokens (issuer `.../application/o/<slug>/`, RS256, JWKS via discovery). About 4¢ spent.

| Check | Result |
|---|---|
| Route allowlist | `GET /healthz` 200; `/`, `POST /v1/models`, `GET /v1/chat/completions`, `/api/logs`, `POST /anthropic/v1/messages` all 404 |
| Token validation | Missing, garbage, expired, wrong `aud`, wrong `iss`, unpublished signing key, `alg: none`, missing `sub`: all 401. Valid token passes. Identity provider unreachable: 503. |
| Rate limit (20/min, burst 5) | 6th rapid request 429 with `retry-after: 3` |
| Body checks | 300 KB body 413; non-JSON 400; JSON array 400 |
| Streamed web search | 200, `text/event-stream`; response headers are only `content-type`, `cache-control`, `x-accel-buffering` (plus Node's own); no `extra_fields` in any chunk |
| Non-streamed | Standard OpenAI body, no `extra_fields` |
| Client hangs up mid-stream | Logged as 499, upstream request cancelled |
| Debug toggle | `SIGUSR2` on (auto-off time logged), `SIGUSR2` off |
| Body log | Hourly JSONL, mode 600, request + assembled answer + usage; raw events when debug is on |
| Budget ($0.03 test cap) | Refused with 402 `budget_exceeded` at $0.0346; the phone gets the message to speak |

Test-harness note: the host firewall blocks containers from reaching services on the host, so the mock identity provider has to run as a container on the compose network.

## Dev deployment through the reverse proxy (2026-10-20)

Stack running on the development workstation (`GATEWAY_BIND` on its LAN address), Nginx Proxy Manager on the home server forwarding the dev hostname to it, real Authentik issuer and audience in `deploy/local/gateway.env`, $10/month budget.

- Discovery against the real issuer: issuer string matches exactly (trailing slash included), RS256 key in the JWKS, scopes `openid` and `offline_access`, PKCE `S256` supported.
- Authentik's token preview: `aud` is the client ID as a single string; `sub` is the hashed user ID. (The preview's `exp` doesn't reflect the access token validity setting.)
- From outside: Let's Encrypt certificate valid; HTTP redirects to HTTPS; `/healthz` 200; unauthenticated POST 401; other paths 404.
- Gotchas: Let's Encrypt fails until the hostname has its own DNS record (no wildcard record on this domain). After adding it, a machine that looked the name up earlier keeps the negative answer cached for a while (`resolvectl flush-caches` clears it).

Not yet tested: a request with a real Authentik-issued token. That comes with the app's sign-in.
