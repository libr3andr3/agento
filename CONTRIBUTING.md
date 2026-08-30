# Contributing

## Build

`./gradlew assembleDebug` — that is all. JDK 17+, Android SDK 36 (Android
Studio installs it). The agent core is prebuilt for arm64, armv7 and x86_64,
so the emulator works.

CI runs `assembleDebug` and `lintDebug` on every push and pull request.

## Ground rules

- **Read `docs/DESIGN.md` before touching a layout.** Tokens only: no
  hard-coded colors, sizes or `Widget.Material3.*` styles in layouts.
- **Strings**: Spanish in `res/values/strings_<area>.xml`, English in
  `res/values-en/strings_<area>.xml`. Every user-facing string exists in
  both. Spanish is informal Peruvian ("tú", "citas", "cobros").
- **Network**: only through `ServerClient`, on the executor its
  comment prescribes. Never on the main thread.
- **No new Gradle dependencies** without a reason in the PR. The app is
  small on purpose (budget phones, 4 GB of RAM, spotty data).
- **The listener must never crash.** Everything in
  `AgenteNotificationListener.process` is per-notification `try/catch`;
  keep it that way — a crash there costs the business every message after it.
- Keep the file map in `README.md` true when you add a screen or a class.

## Debugging

```bash
adb logcat -s AgentoCore:V AgenteListener:V AgenteServer:V AgenteSecureStore:V agente.update:V UnknownAppObserver:V DeviceAttestation:V
```

- The core's port changes on every boot; `AgentoCore.baseUrl()` is the
  truth. To poke it from your machine: `adb shell` → `curl` is not on
  most phones; use `adb forward tcp:8127 tcp:<port>` and call it from the
  host with the `X-App-Key` you read from the logs (debug builds only).
- **Settings → long-press the "Motor de IA" header** (debug builds) to
  point the core at your own OpenAI-compatible endpoint, e.g. a local model
  over `adb reverse`.
- The activity feed (Settings → Registro de actividad) shows every
  notification the listener handled and why it did or did not reply.
- Emulator: no WhatsApp, but any app that posts a `MessagingStyle`
  notification with a reply action will be answered; the manager chat on
  the dashboard talks to the agent directly.

## What lives elsewhere

The agent core (Rust), the gateway and the web console are separate,
private codebases. Changes that need a new endpoint or a new tool in the
core: open an issue describing the behaviour you need from
`docs/CORE-API.md`'s point of view.
