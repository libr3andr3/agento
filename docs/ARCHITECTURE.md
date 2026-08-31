# Architecture

## One idea

The business phone is the server. Everything the agent knows — conversations,
appointments, orders, payments, the learned configuration of the business,
the agent's own cryptographic identity — lives in one SQLite file in the
app's private storage. The only things that leave the phone are the
language-model calls and the account plumbing. There is no "backend that
holds the business's data"; a breach of our servers is not a breach of the
business.

## Three parts

```
┌────────────────────────── the phone ───────────────────────────┐
│                                                                │
│  Kotlin shell (this repo)          agent core (libagento_core) │
│  ───────────────────────           ─────────────────────────── │
│  NotificationListenerService  ──►  HTTP 127.0.0.1:<port>       │
│  screens, settings, alerts    ◄──  JSON replies                │
│  SharedPreferences            │    SQLite  filesDir/agento.db  │
│  Android Keystore             │    schemas filesDir/schemas/   │
│                               │    Ed25519 identity (sealed)   │
└───────────────────────────────┼────────────────────────────────┘
                                │ HTTPS, requests signed by the identity
                       ┌────────▼────────┐
                       │  yaya.tech      │  metered LLM / speech / vision proxy
                       │  gateway        │  Yaya ID accounts, plans, backups
                       └─────────────────┘  a relay that cannot read the mail
```

**Kotlin shell** — everything Android: the notification listener, the
screens, preferences, the update channel. It has no business logic of its
own; when in doubt it asks the core.

**Agent core** — a Rust library loaded with `System.loadLibrary("agento_core")`
(`AgentoCore.kt`). `start(configJson)` boots an HTTP server on a random
loopback port and returns the port; the Kotlin side keeps talking plain
HTTP to it. The core runs the agent loop (prompt, tools, memory), keeps the
database, signs outbound requests, and talks to the gateway.

**Gateway** — `https://agento.ceo` / `llm.yaya.tech`. From the app's point
of view it is (a) where the core sends LLM calls, (b) where the Yaya account
lives, (c) where `/dl/latest.json` is published. The app itself only ever
calls the core; the core calls the gateway.

## Boot

1. `AgenteApp.onCreate` → `AgentoCore.ensureStarted()` on a background thread.
2. `ensureStarted` copies `assets/schemas/` into `filesDir/schemas/` once per
   `versionCode`, builds the config (database path, schemas dir, two
   per-install random secrets from `SecureStore`, optional custom LLM
   endpoint) and calls the JNI `start`.
3. The core returns its port. `AgentoCore.baseUrl()` is
   `http://127.0.0.1:<port>` from then on; every `ServerClient` call goes
   there with `X-App-Key: <per-install secret>`.
4. `DeviceAttestation.ensure()` runs once per agent id: a Keystore-attested
   P-256 key whose certificate chain the core attaches to the agent's public
   card (best effort; most phones say "TEE", some say nothing).

The listener service (`onListenerConnected`) also calls `ensureStarted`, so
the core is up even when the app UI was never opened after a reboot.

## A customer message, end to end

`AgenteNotificationListener.process()`:

1. Master switch off (`Prefs.isEnabled`) → ignore everything.
2. Is the package a known chat app (`SupportedApps`) or one this phone has
   learned (`ProfileStore`)? If neither: hand it to `UnknownAppObserver`
   (might be a chat app we can learn) and then to `PaymentDetector` (might
   be money). Done.
3. Skip group summaries and ongoing notifications. Parse the message —
   `MessagingStyle` first (sender, text, group flag, "from self"), plain
   title/text as a fallback.
4. Drop our own echoed messages, group chats unless enabled, and reposts of
   a notification already handled (10-minute identity window).
5. Find the inline-reply action (`RemoteInput`). No reply action → log it,
   done.
6. **Agent mode** (a business is registered): `POST /api/execute_action`
   on the single-threaded `ServerClient.EXECUTOR`, so replies to one
   conversation stay in order. The core answers with `agentResponse` plus
   an optional `action` and `attention[]` (questions it could not answer,
   or a customer asking for a human → `OwnerAlerts`). The reply is sent
   through the notification's own `PendingIntent`.
   **Canned mode** (no business yet): send the fixed reply text with a
   per-conversation cooldown.
7. Every outcome lands in `ReplyLog` — the owner's proof of what was said
   in their name.

If the core is unreachable the canned reply goes out, so the customer
always hears something.

## A payment

Wallets and banks are never listed in the app. `PaymentDetector` forwards
the *raw* notification (title, text, channel, template, installer…) of any
non-chat app to `POST /api/payment_event`; the core decides "money / not
money", matches it to a pending booking or order, and can answer `mute` for
a package that never carries money. Packages the network already knows as
money apps in this country (`GET /api/payment_sources`) are never muted.
Support for a new wallet is one phone seeing it first.

## Learning a new chat app

Any notification with an inline reply is a conversation. For an unknown
package, `UnknownAppObserver` keeps the *shape* of three distinct
notifications (MessagingStyle or not, title+text or not, did the shared
parser read it) — never the content. Three consistent, parseable shapes →
a `NotificationProfile` is mounted in **shadow** mode: the listener reads
that app and logs it but does not answer. After ten clean reads the owner
is offered a switch in Settings; if reads start failing the profile pauses
itself; two pauses and it is dropped for a week. All of this is data in
`ProfileStore`; unmounting leaves nothing behind.

## First run

```
WelcomeActivity (router, no UI)
  no Yaya identity  ──► AccountActivity      name/email/phone → one-time code
  no business       ──► RegistrationActivity business → owner phone → code → /api/onboard_business
  no interview yet  ──► OnboardingActivity   voice/text interview; catalog photo; "¡Todo listo!"
                                             gates on Notification access before it lets you leave
  otherwise         ──► DashboardActivity
```

`Prefs.serverConfigured()` (a device token exists) is what "a business is
registered" means everywhere in the app.

## The owner's app is data (D15)

`DashboardActivity` draws nothing of its own below the header. The core
composes a **UI spec** for the business — the vertical bundle's `ui:`
template (`assets/schemas/bundles/<vertical>/bundle.yml`) ⊕ the design the
onboarding agent saved with its `design_ui` tool — and the app renders it:
one bottom-bar tab per entry, each tab a column of blocks from the fixed
**block catalog** in `Blocks.kt` (`earnings`, `attention`, `orders_board`,
`agenda_day`, `agenda_week`, `catalog`, `conversations`, `contacts`). A
restaurant opens on its pedidos board, a salon on today's citas, a Gamarra
stall on its catalog; the owner can tell the agent "quiero ver la agenda
primero" and the next refresh redraws. `Walkthrough.kt` shows the tabs once,
in the agent's own words (`intro`). The spec is validated in the core
(`ui.rs`): unknown blocks are dropped, blocks the business cannot use are
dropped, at most four tabs, the template is the fallback — the app never
sees a spec it cannot draw.

The vertical also ships a `skill.md`: how that kind of business runs and how
to interview its owner (question order, how one answer steers the next). The
core mounts it into the onboarding prompt and its customer section into the
customer prompt.

Photos live on the phone (`media` table); customers see them through a
private link the gateway serves for five minutes (`privado.yaya.tech/…`).

## The audit chain

`audit_log` is the phone's own tamper-evident record of what the agent did:
every customer and owner turn (customer words as digests, the agent's reply
verbatim), every tool call, every command that came in over the owner relay
from the web console, every payment read, every swipe, every private link,
every restore and boot. Each row is hash-chained to the previous one and
signed with the installation's Ed25519 identity; UPDATE and DELETE are
refused by trigger; timestamps never go backwards; and every 25 entries or
15 minutes the head is **anchored** — the gateway countersigns
`(agent, seq, head, its own clock)` and both sides keep the anchor — so a
row provably sits between two trusted instants no matter what the phone's
clock says. Ajustes → Auditoría verifies the whole chain on the phone.

## Your data, on your phone (D17)

With the owner's consent (Ajustes → "Tus datos, en tu teléfono"), `OsSync.kt`
mirrors the CRM into the OS after every dashboard refresh: customers with a
phone number into Contacts (tagged with a custom MIME row holding the CRM id,
so it never duplicates), upcoming appointments into a local "agento" calendar
(keyed by `CUSTOM_APP_URI`, reminder an hour before, deleted on cancel). The
same data leaves as files: `.vcf` / `.csv` from Clientes, `.ics` from the week
view. One-way, idempotent, never sent anywhere.

## Threads

`ServerClient` documents the contract; keep it:

- `EXECUTOR` — one thread. Conversation turns (`execute_action`,
  `onboarding_message`, `voice_message`, `catalog_photo`, registration).
  Order matters; a turn can take up to three minutes.
- `IO_EXECUTOR` — cached pool. Dashboard, payments, gap answers, account,
  CRM. These must never queue behind a slow LLM turn: a Yape notification
  waiting behind a chat turn looks like a missing payment.
- Nothing in `ServerClient` may run on the main thread.

Retries are opt-in per endpoint (`Retry.NEVER / IDEMPOTENT /
NETWORK_FAILURE_ONLY`) because most endpoints have side effects.

## Storage

| where | what |
|---|---|
| `filesDir/agento.db` | the core's SQLite: conversations, bookings, orders, payments, contacts, the business's layered schema, the sealed agent identity, the account session |
| `filesDir/schemas/` | copy of `assets/schemas` (core fields + vertical bundles), refreshed per app version |
| SharedPreferences `agente_prefs` (`Prefs`) | master switch, per-app toggles, canned reply, cooldown, locale, cached dashboard, chat transcript, update-channel state; **encrypted** entries: device token, custom LLM key, the core's app key and identity KEK |
| `agente_log` (`ReplyLog`) | last 100 events of the activity feed |
| `agente_observer`, `ProfileStore` | shapes being observed, learned profiles |
| Android Keystore | the AES key `SecureStore` wraps secrets with; the attestation key |

Backup is disabled (`allowBackup=false`, `data_extraction_rules`) because
the token and customer conversations must not ride along with a device
backup. Paid plans get an *encrypted* backup through the core instead
(`POST /api/backup`, `POST /api/restore`).

## Trust boundaries

- **App ↔ core**: `X-App-Key`, a per-install random secret, so another app
  on the phone cannot talk to the core; `Authorization: Bearer <device
  token>` names the business for business routes.
- **Core ↔ gateway**: every request signed with the agent's Ed25519 key
  (`X-Agent-Auth`); the seed is sealed at rest under a Keystore-wrapped key.
- **Gateway**: sees who talks to whom and when (metadata), never a customer
  message. The relay carries sealed boxes only.

## Update channel

Sideloaded apps get no Play updates. `UpdateCheck` fetches
`<server>/dl/latest.json` (see `docs/RELEASE.md`) when the dashboard opens
and when the listener reconnects; newer `versionCode` → banner (blocking
below `minVersionCode`); download with `DownloadManager`; verify the
`sha256`; hand the file to the package installer.

## Design system

Every screen is built from the tokens in `res/values/{colors,design,themes}.xml`
and the Plus Jakarta Sans font. Read `docs/DESIGN.md` before touching a
layout.
