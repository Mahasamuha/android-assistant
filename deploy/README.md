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

## Reverse proxy (Nginx Proxy Manager)

How the proxy reaches the gateway depends on where it runs:
- **Proxy in Docker on the same host (recommended):** set `PROXY_NETWORK` in `deploy/.env` to the proxy's Docker network and start with `docker compose -f compose.yaml -f compose.proxy-network.yaml up -d`. The gateway publishes no host port; the proxy forwards to `gateway:8080`.
- **Proxy elsewhere, or not in Docker:** set `GATEWAY_BIND` to an address the proxy can reach (loopback only works if the proxy runs directly on the host), and keep that port closed to the internet.

Proxy host in Nginx Proxy Manager:

| Setting | Value |
|---|---|
| Domain name | `<gateway-host>` |
| Scheme / forward host / port | `http` / `gateway` (shared network) or the `GATEWAY_BIND` address / `8080` or the `GATEWAY_BIND` port |
| Websockets support | Off (not used) |
| Block common exploits | On |
| SSL | Request a certificate; Force SSL on; HTTP/2 on |
| Advanced | Nothing needed. The gateway sends `X-Accel-Buffering: no` on streams so nginx doesn't buffer them, and nginx's defaults (60 s read timeout, 1 MB body limit) fit. |

Forwarding every path is fine: the gateway returns 404 for anything but `POST /v1/chat/completions` and `GET /healthz`. Per-IP rate limiting, if wanted, belongs at the proxy; the gateway limits per token subject.

Check it from outside once it's up: `curl https://<gateway-host>/healthz` returns `{"status":"ok"}`, and `curl -X POST https://<gateway-host>/v1/chat/completions` returns 401.

## Operations

- **Debug logging** (raw upstream events in the body log; turns itself off after `DEBUG_DURATION`):
  ```sh
  docker compose kill -s SIGUSR2 gateway   # on; run again to turn it off early
  ```
- **Body logs:** hourly JSONL files in the `gateway-logs` volume (`/app/logs`), deleted after `LOG_BODY_RETENTION`.
- **Costs and budget:** the Bifrost dashboard. It has no login in this setup, so it's never published by default. See `compose.admin.yaml` for temporary loopback access over an SSH tunnel.
- **Upgrades:** both images are pinned. Bump the tag in `compose.yaml` (Bifrost) or the Dockerfile (gateway) deliberately.
