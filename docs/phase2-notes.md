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

- **Strip `extra_fields`.** Bifrost adds `extra_fields` to every response, every SSE chunk, and error bodies. It includes routing info and `provider_response_headers`, which carry the Anthropic organization and workspace IDs and rate-limit headers. The gateway must remove it before responding, so SSE pass-through is line-by-line rewriting (still unbuffered), not a byte copy. Check whether a Bifrost setting turns it off.
- **Bifrost logs content by default** (`content_summary` with the prompt and answer). Turn content logging off in Bifrost; body logging with retention belongs to the gateway (plan §2, Logging).
- **A telemetry plugin is active by default.** Find out what it sends and disable it.
- **Governance needs the config store.** With `config_store.enabled: false`, the governance plugin fails to start. Use the default SQLite config store (on a persistent volume, which also keeps budget usage across restarts).
- **Virtual keys must name provider keys by ID.** Give each provider key an explicit `id` and list it in the virtual key's `provider_configs[].key_ids`. `key_ids` doesn't accept key names (fatal at startup), and omitting it leaves the virtual key with no usable key (`no_key_supports_model`).
- **Virtual key header:** `x-bf-vk`. Keys prefixed `sk-bf-` are also accepted in `Authorization`, but the gateway should send `x-bf-vk`.
- **Unverified:** whether editing `config.json` (e.g. raising the budget) resets stored usage; whether an unclean shutdown loses recent usage.

### Prompt notes for later

- Without guidance the model hedges ("conditions may vary", "check a live source"). The system prompt should ask for a single, most recent reading and no caveats.
