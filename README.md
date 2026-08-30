# agento

**An AI receptionist that lives on the business owner's Android phone.**

agento reads the messages a business phone already receives — WhatsApp,
WhatsApp Business, Instagram, Messenger, Telegram, SMS — through Android's
notification system, and answers them as the business: it quotes prices,
books appointments, takes orders and confirms payments (Yape, Plin, any
wallet or bank that posts a notification). No platform APIs, no scraping, no
server that holds the conversations. The phone *is* the server.

[![ci](https://github.com/libr3andr3/agento/actions/workflows/ci.yml/badge.svg)](https://github.com/libr3andr3/agento/actions/workflows/ci.yml)

```
  customer writes on WhatsApp
          │
          ▼  Android posts a notification
  ┌──────────────────────────────┐
  │ AgenteNotificationListener   │  reads sender + text (MessagingStyle)
  └──────────────┬───────────────┘
                 │ HTTP on 127.0.0.1  (ServerClient)
  ┌──────────────▼───────────────┐
  │ agent core  libagento_core.so│  the agent: SQLite, tools, memory
  │ (Rust, runs inside the app)  │  ← only the language-model call leaves
  └──────────────┬───────────────┘     the phone (yaya.tech or your own key)
                 │ reply text
  ┌──────────────▼───────────────┐
  │ notification inline reply    │  the same "Reply" button you see in
  │ (RemoteInput)                │  the notification shade
  └──────────────────────────────┘
```

This repository is the Android app: the Kotlin shell, its screens, and a
prebuilt copy of the agent core it drives. It is built for people who want to
understand, modify and extend a real, shipping notification-first agent.

## Quick start

You need a JDK 17+ and Android Studio (or just the Android SDK). Nothing
else: no Rust, no NDK, no API keys.

```bash
git clone https://github.com/libr3andr3/agento.git
cd agento
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on the phone:

1. Open **agento** and sign in with a Yaya ID (name, email, phone → one-time code).
2. Register the business (name, kind of business, owner phone).
3. Let the setup interview ask its questions — by voice or by typing. You can
   also photograph a menu or price list and the core extracts the items.
4. Grant **Notification access** when asked. This is the permission that makes
   everything work; without it the agent is deaf.
5. Message the business phone from another phone. Watch the reply arrive.

Everything the agent does shows up in the dashboard and in
**Settings → Registro de actividad**.

The app works on the emulator too (an `x86_64` core is included), but the
emulator has no WhatsApp — use the manager chat on the dashboard to talk to
the agent directly.

## Where things are

```
app/src/main/
  AndroidManifest.xml          permissions (each one explained), screens, the listener service
  java/tech/yaya/agente/
    AgenteApp.kt               Application: boots the core early, creates notification channels
    AgentoCore.kt              JNI bridge to libagento_core.so — start/stop, config, port
    ServerClient.kt            the ONE HTTP client for the core on the loopback (threading rules inside)
    AgenteNotificationListener.kt  the heart: notification in → core → inline reply out
    PaymentDetector.kt         forwards bank/wallet notifications to the core (money detection)
    UnknownAppObserver.kt      learns new chat apps from the shape of their notifications
    NotificationProfile.kt, ProfileStore.kt   the trial state of a learned app
    Prefs.kt                   settings + on-device state (SharedPreferences)
    SecureStore.kt             AES-GCM under the Android Keystore for secrets in Prefs
    ReplyLog.kt                the activity feed (last 100 events)
    OwnerAlerts.kt             local notifications when the agent needs the owner
    UpdateCheck.kt             self-hosted update channel (/dl/latest.json)
    DeviceAttestation.kt       hardware key attestation handed to the core (best effort)
    LocationHelper.kt          coarse location → the business's public location
    Screens.kt                 which screen is "home"
    ── screens ──
    WelcomeActivity.kt         launcher: a router with no UI
    AccountActivity.kt         Yaya ID sign-in (one-time code by email/WhatsApp)
    RegistrationActivity.kt    business → owner phone → code, step by step
    OnboardingActivity.kt      the setup interview and, later, the manager chat
    DashboardActivity.kt       home: status, earnings, agenda, conversations, open questions
    MainActivity.kt            Settings: master switch, connected apps, canned reply, log
    CrmListActivity.kt, ConversationActivity.kt   conversations and customers
    PayoutActivity.kt          Cobros: where customers send money
    PlanActivity.kt            plans for the Yaya account
    BackupUpsellActivity.kt    shown once after the interview
    Countries.kt, CountryPicker.kt, SupportedApps.kt   small catalogs
  res/                         layouts, strings (values = Spanish, values-en = English), design tokens
  assets/schemas/              what the agent knows to ask: core fields + per-vertical bundles
  jniLibs/<abi>/libagento_core.so   the agent core (see jniLibs/CORE.md)
docs/                          ARCHITECTURE, CORE-API, DESIGN, SECURITY, RELEASE
scripts/                       verify-apk.sh (check a download), publish-apk.sh (update channel)
```

Start with `AgenteNotificationListener.kt` — read it top to bottom (430
lines) and you understand the product. Then `ServerClient.kt` for how the
app talks to the core, then `docs/ARCHITECTURE.md`.

## The agent core

`libagento_core.so` is the agent itself: a Rust library that runs inside the
app's process, keeps every conversation, booking, payment and learned fact in
`filesDir/agento.db`, and exposes a small HTTP API on `127.0.0.1:<random
port>`. The Kotlin side never touches the database; it only calls that API
(`docs/CORE-API.md`).

The core is prebuilt here for `arm64-v8a`, `armeabi-v7a` and `x86_64`. Its
source depends on a private kernel crate and is not part of this repo; the
JNI surface and every endpoint the app uses are documented so you can build
on top of it. `app/src/main/jniLibs/CORE.md` records which build is checked
in.

## What leaves the phone

- The language-model call, to `yaya.tech` by default (metered, signed by the
  agent's own key) or to any OpenAI-compatible endpoint with your own key
  (Settings → long-press the *Motor de IA* header in a debug build).
- Voice and catalog photos, for transcription and extraction, the same way.
- Your Yaya account (sign-in, plan) and, on paid plans, an encrypted backup.

Customer messages are never uploaded anywhere as such; the model sees the
turn it is asked to answer. See `docs/SECURITY.md`.

## Building a release

```bash
export AGENTO_KEYSTORE=~/.agento/upload-keystore.jks \
       AGENTO_KEYSTORE_PASS=… AGENTO_KEY_ALIAS=agento-upload AGENTO_KEY_PASS=…
./gradlew assembleRelease           # → app/build/outputs/apk/release/app-release.apk
scripts/verify-apk.sh               # checks a published download end to end
```

Full procedure, version numbers and the update channel: `docs/RELEASE.md`.

## Language

The product is Spanish-first (Peru), with English as the second locale.
Code and docs are in English; user-facing strings live in `res/values`
(es) and `res/values-en`.

## Status

This is the app that is in production at [agento.ceo](https://agento.ceo).
The Kotlin package is still `tech.yaya.agente` (the app's first name) — it
cannot be renamed without rebuilding the core, whose JNI entry points carry
that name.
