# agente — notification auto-reply for businesses

Android app that auto-replies to incoming messages from WhatsApp, WhatsApp
Business, Instagram, Facebook Messenger, Telegram and SMS — entirely through
Android's notification system.

## How it works

1. The user grants **Notification Access** (special permission, granted in
   system settings — the app deep-links there and explains why).
2. `AgenteNotificationListener` (a `NotificationListenerService`) receives every
   posted notification and keeps only those from supported messaging apps that
   the user has toggled on.
3. Each notification is parsed — preferring `MessagingStyle` extras (sender,
   text, group flag, self-detection), falling back to title/text.
4. The reply is sent through the notification's own inline-reply
   (`RemoteInput`) action, i.e. the same "Reply" button you see in the shade.
   No Accessibility hacks, no app scraping, nothing leaves the phone.

Safeguards: per-conversation cooldown (default 30 min), group chats off by
default, self-message and notification-repost detection, skip notifications
with no reply action. All handled events land in an on-screen activity log.

## Build

```bash
export JAVA_HOME=~/tools/jdk-21.0.12+8
export ANDROID_HOME=~/Android/Sdk
export PATH="$JAVA_HOME/bin:$HOME/tools/gradle-8.14.2/bin:$PATH"
cd ~/agente && gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Next: server

The app is deliberately self-contained. Server integration points:

- `Prefs` — replace the static reply text with rules fetched from the server
  (per-app, per-keyword, business-hours templates).
- `ReplyLog.add()` — mirror events to the server so the business dashboard
  shows conversations and can take over from the auto-reply.
- `AgenteNotificationListener.process()` — before replying, optionally ask the
  server (LLM) for a contextual reply instead of the canned template, with the
  canned template as offline fallback.
