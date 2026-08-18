package tech.yaya.agente

import android.content.Context
import android.content.SharedPreferences

/** Simple settings store. Server sync replaces/augments this later. */
object Prefs {
    private const val FILE = "agente_prefs"
    private const val KEY_ENABLED = "auto_reply_enabled"
    private const val KEY_REPLY_TEXT = "reply_text"
    private const val KEY_APP_PREFIX = "app_enabled_"
    private const val KEY_COOLDOWN_MIN = "cooldown_minutes"
    private const val KEY_REPLY_GROUPS = "reply_to_groups"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun replyText(ctx: Context): String =
        sp(ctx).getString(KEY_REPLY_TEXT, null) ?: ctx.getString(R.string.default_reply)

    fun setReplyText(ctx: Context, text: String) =
        sp(ctx).edit().putString(KEY_REPLY_TEXT, text).apply()

    fun isAppEnabled(ctx: Context, pkg: String) =
        sp(ctx).getBoolean(KEY_APP_PREFIX + pkg, pkg in SupportedApps.DEFAULT_ENABLED)

    fun setAppEnabled(ctx: Context, pkg: String, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_APP_PREFIX + pkg, on).apply()

    /** Minimum minutes between auto-replies to the same conversation. */
    fun cooldownMinutes(ctx: Context) = sp(ctx).getInt(KEY_COOLDOWN_MIN, 30)
    fun setCooldownMinutes(ctx: Context, min: Int) =
        sp(ctx).edit().putInt(KEY_COOLDOWN_MIN, min).apply()

    fun replyToGroups(ctx: Context) = sp(ctx).getBoolean(KEY_REPLY_GROUPS, false)
    fun setReplyToGroups(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_REPLY_GROUPS, on).apply()

    // ------------------------------------------------------------ server sync

    fun serverUrl(ctx: Context): String =
        sp(ctx).getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER

    private const val DEFAULT_SERVER = "https://agento.ceo"

    /**
     * Rejects anything that isn't HTTPS and reports whether it took the value.
     * The device token, every customer conversation, and the payment feed all
     * travel this URL; a plain-HTTP endpoint puts them on the wire in the clear
     * and lets a network attacker rewrite the replies the business sends.
     */
    fun setServerUrl(ctx: Context, url: String): Boolean {
        val clean = url.trim().trimEnd('/')
        if (!clean.startsWith("https://") || clean.length <= "https://".length) return false
        sp(ctx).edit().putString("server_url", clean).apply()
        return true
    }

    private const val KEY_DEVICE_TOKEN = "device_token_enc"
    private const val LEGACY_DEVICE_TOKEN = "device_token"

    /**
     * The device bearer token, encrypted at rest under the Android Keystore.
     * Older installs wrote it in plaintext, so the legacy key is migrated on
     * first read and then deleted — an upgrade must not un-pair anyone.
     */
    fun deviceToken(ctx: Context): String {
        val store = sp(ctx)
        SecureStore.migratePlaintext(store, LEGACY_DEVICE_TOKEN, KEY_DEVICE_TOKEN)
        return SecureStore.getString(store, KEY_DEVICE_TOKEN) ?: ""
    }

    fun setDeviceToken(ctx: Context, t: String) =
        SecureStore.putString(sp(ctx), KEY_DEVICE_TOKEN, t)

    fun businessId(ctx: Context): String = sp(ctx).getString("business_id", "") ?: ""
    fun setBusinessId(ctx: Context, id: String) =
        sp(ctx).edit().putString("business_id", id).apply()

    /** Agent mode = registered business + reachable server; else canned replies. */
    fun serverConfigured(ctx: Context) = deviceToken(ctx).isNotEmpty()

    /** Last good dashboard payload, rendered while offline. */
    fun dashboardCache(ctx: Context): String? = sp(ctx).getString("dash_cache", null)
    fun setDashboardCache(ctx: Context, json: String) =
        sp(ctx).edit().putString("dash_cache", json).apply()

    /** Persisted onboarding chat transcript (blocks joined by \n\n). */
    fun chatTranscript(ctx: Context): String? = sp(ctx).getString("chat_transcript", null)
    fun setChatTranscript(ctx: Context, t: String) =
        sp(ctx).edit().putString("chat_transcript", t).apply()
}
