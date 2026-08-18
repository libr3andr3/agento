package tech.yaya.agente

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Listens to incoming notifications, parses the ones from supported messaging
 * apps, and answers them through the notification's own inline-reply
 * (RemoteInput) action — the same mechanism Android Wear / Auto use.
 * Nothing is scraped from the apps themselves; only what they publish in the
 * notification shade is read, and only after the user grants Notification
 * Access to agente in system settings.
 */
class AgenteNotificationListener : NotificationListenerService() {

    /** conversationKey -> last auto-reply epoch millis (cooldown). */
    private val lastReplied = HashMap<String, Long>()

    /** Recently handled notification identities, to ignore reposts/updates. */
    private val handled = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            process(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "error processing notification from ${sbn.packageName}", t)
        }
    }

    private fun process(sbn: StatusBarNotification) {
        val ctx = applicationContext
        if (!Prefs.isEnabled(ctx)) return

        PaymentApps.get(sbn.packageName)?.let { payApp ->
            handlePaymentNotification(payApp, sbn)
            return
        }

        val app = SupportedApps.get(sbn.packageName) ?: return
        if (!Prefs.isAppEnabled(ctx, app.packageName)) return

        val n = sbn.notification ?: return

        // Skip summaries, ongoing/foreground-service notifications, and anything
        // that isn't a fresh message.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val parsed = parseMessage(n) ?: return

        // Ignore our own outgoing messages echoed back into the conversation
        // notification (MessagingStyle marks them as coming from the device user).
        if (parsed.fromSelf) return

        // Group chats are opt-in for businesses.
        if (parsed.isGroup && !Prefs.replyToGroups(ctx)) {
            log(app, parsed, sent = false, detail = ctx.getString(R.string.log_skipped_group))
            return
        }

        // Ignore reposts of a notification we already saw (apps re-issue the
        // same notification when their unread count changes).
        val identity = "${sbn.key}|${parsed.sender}|${parsed.text.hashCode()}"
        val now = System.currentTimeMillis()
        synchronized(handled) {
            handled.entries.removeAll { now - it.value > IDENTITY_WINDOW_MS }
            if (handled.containsKey(identity)) return
            handled[identity] = now
        }

        val replyAction = findReplyAction(n)
        if (replyAction == null) {
            log(app, parsed, sent = false, detail = ctx.getString(R.string.log_no_reply_action))
            return
        }

        if (Prefs.serverConfigured(ctx)) {
            // Agent mode: every message goes to the server; the LLM agent holds
            // the conversation, so no cooldown — the identity dedupe above
            // already blocks notification reposts.
            ServerClient.EXECUTOR.execute { agentReply(app, parsed, replyAction) }
            return
        }

        // Canned mode: per-conversation cooldown so a customer gets one
        // auto-reply, not one per message.
        val convKey = "${app.packageName}|${parsed.sender}"
        val cooldownMs = Prefs.cooldownMinutes(ctx) * 60_000L
        synchronized(lastReplied) {
            val last = lastReplied[convKey] ?: 0L
            if (now - last < cooldownMs) {
                log(app, parsed, sent = false, detail = ctx.getString(R.string.log_skipped_cooldown))
                return
            }
            lastReplied[convKey] = now
        }

        val replyText = Prefs.replyText(ctx)
        val ok = sendReply(replyAction, replyText)
        log(app, parsed, sent = ok, detail = if (ok) replyText else ctx.getString(R.string.log_send_failed))
    }

    /** Yape/Plin: parse and forward to the server for payment matching. */
    private fun handlePaymentNotification(app: SupportedApp, sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: return
        if (text.isBlank()) return

        val identity = "${sbn.key}|$title|${text.hashCode()}"
        val now = System.currentTimeMillis()
        synchronized(handled) {
            handled.entries.removeAll { now - it.value > IDENTITY_WINDOW_MS }
            if (handled.containsKey(identity)) return
            handled[identity] = now
        }

        val parsed = ParsedMessage(sender = title, text = text, isGroup = false, fromSelf = false)
        val ctx = applicationContext
        if (!Prefs.serverConfigured(ctx)) {
            log(app, parsed, sent = false, detail = ctx.getString(R.string.log_payment_no_server))
            return
        }
        ServerClient.IO_EXECUTOR.execute {
            val resp = ServerClient.paymentEvent(ctx, app.displayName, title, text)
            val detail = when {
                resp == null -> ctx.getString(R.string.log_payment_unreachable)
                !resp.isNull("matchedAppointment") ->
                    ctx.getString(R.string.log_payment_matched, resp.optJSONObject("matchedAppointment")?.optString("customer") ?: "")
                else -> ctx.getString(R.string.log_payment_recorded)
            }
            log(app, parsed, sent = false, detail = "💰 $detail")
        }
    }

    /** Runs on ServerClient.EXECUTOR: ask the server agent, then reply inline. */
    private fun agentReply(
        app: SupportedApp,
        parsed: ParsedMessage,
        replyAction: Notification.Action
    ) {
        val ctx = applicationContext
        val peer = "${app.packageName}:${parsed.sender}"
        val resp = ServerClient.executeAction(ctx, peer, parsed.text)
        // The server flags questions that need the owner (out-of-scope, or
        // the customer asked for a human) — raise a local heads-up for each.
        resp?.optJSONArray("attention")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                OwnerAlerts.notify(
                    ctx,
                    urgent = g.optBoolean("urgent"),
                    sender = parsed.sender,
                    question = g.optString("question"),
                    gapId = g.optString("gapId", "gap$i")
                )
            }
        }
        // Trial over: the server answered (so this is not an outage — no canned
        // fallback, which would be answering customers for free) but the agent
        // is off. Tell the owner, throttled so a busy inbox doesn't become a
        // notification storm.
        if (resp?.optString("action") == "trial_expired") {
            log(app, parsed, sent = false, detail = ctx.getString(R.string.log_trial_expired))
            val now = System.currentTimeMillis()
            if (now - lastTrialAlert > TRIAL_ALERT_INTERVAL_MS) {
                lastTrialAlert = now
                OwnerAlerts.notify(
                    ctx,
                    urgent = true,
                    sender = ctx.getString(R.string.app_name),
                    question = ctx.getString(R.string.trial_expired_alert),
                    gapId = "trial_expired"
                )
            }
            return
        }

        val text = resp?.optString("agentResponse")?.takeIf { it.isNotBlank() }
        if (text != null) {
            val ok = sendReply(replyAction, text)
            val action = resp.optString("action").takeIf { it.isNotEmpty() && it != "null" }
            val suffix = action?.let { " [$it]" } ?: ""
            log(app, parsed, sent = ok,
                detail = if (ok) "$text$suffix" else ctx.getString(R.string.log_send_failed))
        } else {
            // Server unreachable — fall back to the canned reply so the
            // customer still hears back.
            val ok = sendReply(replyAction, Prefs.replyText(ctx))
            log(app, parsed, sent = ok, detail = ctx.getString(R.string.log_fallback_sent))
        }
    }

    // ---------------------------------------------------------------- parsing

    private data class ParsedMessage(
        val sender: String,
        val text: String,
        val isGroup: Boolean,
        val fromSelf: Boolean
    )

    private fun parseMessage(n: Notification): ParsedMessage? {
        val extras = n.extras ?: return null

        // Prefer MessagingStyle — WhatsApp, Messenger, Telegram and Google
        // Messages all use it and it cleanly separates sender / self / group.
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            val senderPerson = last.person
            val fromSelf = senderPerson == null ||
                senderPerson.name.isNullOrEmpty() ||
                senderPerson.name.toString() == style.user.name?.toString()
            val conversation = style.conversationTitle?.toString()
            val sender = conversation
                ?: senderPerson?.name?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: return null
            return ParsedMessage(
                sender = sender,
                text = last.text?.toString() ?: "",
                isGroup = style.isGroupConversation,
                fromSelf = fromSelf
            )
        }

        // Fallback: plain title/text notification.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        // "Checking for new messages", "X new messages" style placeholders.
        if (text.isBlank() || looksLikePlaceholder(text)) return null
        return ParsedMessage(
            sender = title,
            text = text,
            isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            fromSelf = false
        )
    }

    private fun looksLikePlaceholder(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("new message") || t.contains("checking for") ||
            t.matches(Regex("^\\d+ (messages|chats).*"))
    }

    // ---------------------------------------------------------- reply sending

    private fun findReplyAction(n: Notification): Notification.Action? {
        val direct = n.actions.orEmpty().filter { !it.remoteInputs.isNullOrEmpty() }
        // Prefer an action explicitly marked/labelled as a reply; otherwise any
        // action carrying a RemoteInput is almost always the reply on messaging
        // notifications ("Mark as read" etc. carry no RemoteInput).
        direct.firstOrNull { isReplyLike(it) }?.let { return it }
        direct.firstOrNull()?.let { return it }
        // Some apps only expose reply through the wearable extender.
        return Notification.WearableExtender(n).actions
            .firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }

    private fun isReplyLike(action: Notification.Action): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 28 &&
            action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        ) return true
        val title = action.title?.toString()?.lowercase() ?: return false
        // Covers "Reply", "Responder", "Répondre"…
        return title.contains("reply") || title.contains("respon") || title.contains("répond")
    }

    private fun sendReply(action: Notification.Action, text: String): Boolean {
        val remoteInputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        remoteInputs.forEach { ri -> results.putCharSequence(ri.resultKey, text) }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        return try {
            action.actionIntent.send(this, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "reply intent canceled", e)
            false
        }
    }

    // ----------------------------------------------------------------- logging

    private fun log(app: SupportedApp, msg: ParsedMessage, sent: Boolean, detail: String) {
        ReplyLog.add(
            applicationContext,
            ReplyEvent(
                timestamp = System.currentTimeMillis(),
                appPackage = app.packageName,
                appName = app.displayName,
                sender = msg.sender,
                incomingText = msg.text,
                replySent = sent,
                detail = detail
            )
        )
    }

    companion object {
        private const val TAG = "AgenteListener"
        private const val IDENTITY_WINDOW_MS = 10 * 60_000L
        private const val TRIAL_ALERT_INTERVAL_MS = 6 * 60 * 60_000L
        @Volatile private var lastTrialAlert = 0L
    }
}
