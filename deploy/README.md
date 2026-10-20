# Deploy: auth gateway + Bifrost

Two containers on one private network. Only the gateway publishes a port, and only to the address in `GATEWAY_BIND` (loopback by default), so the reverse proxy is its only way in. Bifrost publishes nothing.

```
phone ──HTTPS──▶ reverse proxy ──▶ gateway (:8080 in container) ──▶ bifrost:8080 ──▶ Anthropic
```

## Files

| Tracked | Real copy (gitignored) | Contents |
|---|---|---|
| `.env.example` | `.env` | `GATEWAY_BIND` (host address and port for the reverse proxy) |
| `gateway.env.example` | `local/gateway.env` | Issuer, audience, virtual key, rate limits, body-log retention |
| `bifrost.env.example` | `local/bifrost.env` | Anthropic API key, virtual key |
| `bifrost/config.example.json` | `local/bifrost-config.json` | Bifrost config: provider key, virtual key name, model allowlist, budget |

Secrets stay in the env files. The Bifrost config refers to them as `env.ANTHROPIC_API_KEY` and `env.BIFROST_VIRTUAL_KEY`.

## Setup

1. Copy each example to its real location and fill in the placeholders. Generate the virtual key once and use it in both env files:
   ```sh
   echo "sk-bf-$(openssl rand -hex 24)"
   ```
2. In `local/bifrost-config.json`, set the virtual key name and the monthly budget (`max_limit`, in dollars).
3. Set a spend limit on the Anthropic Console workspace too, as a backstop.
4. Start the stack:
   ```sh
   docker compose up -d --build
   ```
5. Point the reverse proxy at `GATEWAY_BIND` (see below).

The Bifrost config keeps these settings on purpose:
- `extra_fields_placement: "headers"`: removes Bifrost's metadata, including the provider's organization and workspace IDs, from response bodies and stream chunks. The gateway forwards only `content-type`, so none of it reaches the phone.
- `disable_content_logging: true`: Bifrost keeps cost and usage, not prompts and answers. Bodies are logged by the gateway, with retention.
- `enforce_auth_on_inference: true`: every request needs the virtual key.

The config store and budget usage live in the `bifrost-data` volume. `docker compose down -v` deletes them, which resets spend tracking.

## Reverse proxy requirements

Whatever proxy fronts the gateway:
- Terminate TLS and forward to `GATEWAY_BIND`. Forwarding every path is fine: the gateway itself returns 404 for anything but `POST /v1/chat/completions` and `GET /healthz`.
- Don't buffer responses (the gateway sends `X-Accel-Buffering: no` for streams; nginx also needs `proxy_buffering off`).
- Allow long reads: a streamed answer with a web search takes a few seconds, but give it at least 120 s.
- Apply per-IP rate limiting here if wanted; the gateway limits per token subject.

## Operations

- **Debug logging** (raw upstream events in the body log; turns itself off after `DEBUG_DURATION`):
  ```sh
  docker compose kill -s SIGUSR2 gateway   # on; run again to turn it off early
  ```
- **Body logs:** hourly JSONL files in the `gateway-logs` volume (`/app/logs`), deleted after `LOG_BODY_RETENTION`.
- **Costs and budget:** the Bifrost dashboard. It has no login in this setup, so it's never published by default. See `compose.admin.yaml` for temporary loopback access over an SSH tunnel.
- **Upgrades:** both images are pinned. Bump the tag in `compose.yaml` (Bifrost) or the Dockerfile (gateway) deliberately.
