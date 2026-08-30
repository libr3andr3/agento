# The core's loopback API

The Kotlin shell talks to the agent core over plain HTTP on
`http://127.0.0.1:<port>`; `AgentoCore.baseUrl()` gives the base URL and
boots the core if needed. All calls go through `ServerClient.kt` — add new
endpoints there, never open a socket elsewhere.

## Headers

| header | when | what |
|---|---|---|
| `X-App-Key: <secret>` | every request | per-install random secret (`AgentoCore.appKey`), so no other app on the phone can drive the core |
| `Authorization: Bearer <device token>` | business routes | issued by `/api/onboard_business`, stored encrypted (`Prefs.deviceToken`) |

Bodies are JSON unless noted. A 2xx carries the response; the app maps
other codes with `ServerClient.classify()`:

| code | meaning for the user |
|---|---|
| 0 | never reached the core (it is down or booting) |
| 401 / 403 | token rejected — re-pair, don't retry |
| 429 | throttled (daily caps, code attempts) |
| 503 | feature intentionally off (e.g. phone verification not deployed) |
| other 5xx | the core's problem, not the user's |
| other 4xx | bad input (unreadable photo, wrong code) |

## Endpoints the app uses

### Account (Yaya ID) — no bearer

| method + path | body | notes |
|---|---|---|
| `GET /api/account` | | who is signed in, plan, `shareTraining` |
| `POST /api/account/otp/start` | `{email, phone, name?}` | sends a 6-digit code by WhatsApp and email |
| `POST /api/account/otp/check` | `{email, phone, code, name?}` | creates the account on first sign-in; links this phone |
| `POST /api/account/guest` | `{share}` | "Continuar sin cuenta" |
| `POST /api/account/share` | `{share}` | opt in/out of sharing redacted turns |
| `POST /api/account/logout` | `{}` | |
| `POST /api/account/plan/request` | `{plan, months}` | opens a purchase; returns amount + reference |
| `GET /api/account/plan/request/{ref}` | | `pending` / `paid` |
| `GET /api/plan` | | current plan and usage (bearer when a business exists) |
| `POST /api/backup` | `{}` | encrypted snapshot to the account (paid plans) |
| `POST /api/restore` | `{force}` | bring the account's latest snapshot onto this phone (slow) |

### Registration — no bearer

| method + path | body | notes |
|---|---|---|
| `POST /api/verify/start` | `{phone}` | 200 code sent · 503 verification off (skip the step) · 429 · 400 |
| `POST /api/verify/check` | `{phone, code}` | → `{verificationToken}` |
| `POST /api/onboard_business` | `{businessName, industry, ownerPhone, country, verificationToken?}` | creates the business, returns the **device token** and locale |

### The business — bearer

| method + path | body | notes |
|---|---|---|
| `POST /api/execute_action` | `{phoneNumber, message}` | **a customer message in, the agent's reply out**: `{agentResponse, action?, actionData?, attention?[]}`. `action == "limit_reached"` = daily cap; `attention[]` = `{question, urgent, gapId}` for the owner |
| `POST /api/onboarding_message` | `{message}` | one turn of the setup interview / manager chat |
| `POST /api/voice_message` | raw `audio/m4a` bytes (`application/octet-stream`) | → transcript + reply + `wav` |
| `POST /api/catalog_photo` | raw JPEG bytes | → items and prices; 422 unreadable, 503 off |
| `GET /api/dashboard` | | today/week/month, agenda, conversations, open questions |
| `POST /api/answer_gap` | `{gapId, answer}` | the owner answers a question the agent could not; mounts immediately |
| `POST /api/payment_event` | the raw notification envelope (`PaymentDetector`) | → `{handled, matchedAppointment?, matchedOrder?, mute?, untilMs?}` |
| `GET /api/payment_sources` | | packages the network knows as money apps for this country |
| `GET /api/conversations` · `GET /api/conversations/{peer}` | | CRM views |
| `GET /api/contacts?q=` · `POST /api/contacts/{id}` | patch | |
| `GET /api/payout` · `POST /api/payout` | `{holder, cashOnly, destinations[]}` | Cobros — where customers send money |
| `GET /api/rails?country=` | | wallet names people use in this country (no bearer) |
| `POST /api/location` | `{…}` | coarse location → the business's public location |

### Identity (used by `DeviceAttestation`) — no bearer

| method + path | notes |
|---|---|
| `GET /api/agent` | the agent's public card, incl. `agent:<hex>` id |
| `POST /api/agent/device` | `{chain[], level}` — Android key-attestation chain for the card |

Peers are keyed as `"<package>:<sender name>"` (`AgenteNotificationListener.agentReply`),
which is why the CRM shows a WhatsApp contact and an Instagram contact with
the same name as two customers.

## Timeouts and retries

Conversation endpoints have a 180 s read timeout (an LLM tool loop can be
slow); dashboard-type GETs 15–30 s. Only idempotent GETs auto-retry
(`Retry.IDEMPOTENT`: once, after 500 ms, on code 0 or 5xx).
`verify/*` retry only on code 0 (`NETWORK_FAILURE_ONLY`) — a wrong code must
not be re-tested. Everything with a side effect is one shot.

## JNI surface (`AgentoCore.kt`)

```kotlin
external fun start(configJson: String): Int   // boots the core, returns the loopback port (≤0 = failed)
external fun stop()
external fun port(): Int                       // 0 when not running
external fun version(): String                 // e.g. "0.2.0"
```

`configJson` keys: `DATABASE_URL` (sqlite path), `SCHEMAS_DIR`, `BIND_ADDR`
(`127.0.0.1:0`), `APP_KEY`, `ADMIN_KEY`, `IDENTITY_KEK_HEX` (32 bytes hex,
Keystore-wrapped at rest), `DEVICE_LABEL`, and optionally `LLM_BASE_URL`,
`LLM_API_KEY`, `LLM_MODEL` for a custom model endpoint.
