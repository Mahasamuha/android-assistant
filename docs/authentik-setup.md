# Identity provider setup (Authentik)

Manual setup, done once in the Authentik admin UI. The result is a public OAuth client the phone signs in with, and the issuer and audience values the gateway checks.

Items marked **(verify)** are from general Authentik knowledge, not checked against the running version. Confirm them while doing the setup and fix this page.

## 1. Provider

Applications → Providers → Create → **OAuth2/OpenID Provider**:

| Setting | Value |
|---|---|
| Name | e.g. `assistant` |
| Authorization flow | The default explicit-consent or implicit-consent flow |
| Client type | **Public** (the phone can't keep a secret; PKCE protects the code exchange) |
| Client ID | Generated; this goes in the app config as `<client-id>` |
| Redirect URIs | Strict: `com.mahasamuha.assistant:/oauth2redirect` (must match the AppAuth redirect scheme in the app) |
| Signing key | An RSA certificate (tokens must be asymmetrically signed; the gateway rejects HS256) |
| Access token validity | `minutes=10` |
| Refresh token validity | `days=60` |
| Scopes | `openid`, plus `offline_access` so a refresh token is issued **(verify: newer Authentik versions only issue refresh tokens with `offline_access`)** |
| Issuer mode | Per provider (based on the application slug) |
| Subject mode | Based on the user's hashed ID (the default). The gateway only needs `sub` to be stable, for rate limiting and logs; hashed keeps usernames and emails out of the logs. |
| Grant types | Authorization code (with PKCE) and refresh token only, if the version lets you restrict them. Not the implicit grant, client credentials, device code, or password. (The "implicit consent" *authorization flow* is fine: it only skips the consent screen.) |

Refresh token rotation: the plan wants refresh tokens rotated on use (sliding 60 days). **(verify)** whether Authentik rotates refresh tokens on each use and whether that's configurable.

## 2. Application

Applications → Applications → Create:
- Name and slug, e.g. slug `assistant`. The slug appears in the issuer URL.
- Provider: the one from step 1.
- Bind a policy so only your user can authorize it.

## 3. Values for the gateway and the app

| Value | Where it comes from | Used in |
|---|---|---|
| Issuer | The provider's "OpenID Configuration Issuer", e.g. `https://<issuer-host>/application/o/<slug>/` (with the trailing slash) | `ISSUER_URL` in `deploy/local/gateway.env`; app config |
| Audience | The provider's client ID. Authentik sets the access token's `aud` to it **(verify: as a single string; the gateway rejects array audiences)**. Authentik can't mint per-resource audiences (RFC 8707), so isolation comes from the dedicated provider instead: its own issuer (per-provider issuer mode) and its own client ID. Tokens from other Authentik applications fail the issuer check. | `AUDIENCE` in `deploy/local/gateway.env` |
| Client ID | Step 1 | App config only |

Check a real token before going live: sign in once, decode the access token (e.g. at a local JWT decoder, never a website), and confirm `iss`, `aud`, `exp` (≈10 min) and `sub` match what the gateway expects.
