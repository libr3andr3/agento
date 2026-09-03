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

    internal fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    /** Blank counts as unset: an empty canned reply would make the listener
     *  "answer" customers with nothing, which reads as a snub. */
    fun replyText(ctx: Context): String =
        sp(ctx).getString(KEY_REPLY_TEXT, null)?.takeIf { it.isNotBlank() }
            ?: ctx.getString(R.string.default_reply)

    fun setReplyText(ctx: Context, text: String) =
        sp(ctx).edit().putString(KEY_REPLY_TEXT, text).apply()

    fun isAppEnabled(ctx: Context, pkg: String) =
        sp(ctx).getBoolean(KEY_APP_PREFIX + pkg, pkg in SupportedApps.DEFAULT_ENABLED)

    fun setAppEnabled(ctx: Context, pkg: String, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_APP_PREFIX + pkg, on).apply()

    /** Minimum minutes between auto-replies to the same conversation.
     *  Clamped at 0 on read: a negative value (bad import, old bug) would make
     *  the cooldown math always pass and the agent double-reply. */
    fun cooldownMinutes(ctx: Context) = sp(ctx).getInt(KEY_COOLDOWN_MIN, 30).coerceAtLeast(0)
    fun setCooldownMinutes(ctx: Context, min: Int) =
        sp(ctx).edit().putInt(KEY_COOLDOWN_MIN, min).apply()

    fun replyToGroups(ctx: Context) = sp(ctx).getBoolean(KEY_REPLY_GROUPS, false)
    fun setReplyToGroups(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_REPLY_GROUPS, on).apply()

    // ------------------------------------------------------------ money apps
    //
    // Which apps the local agent may read payment notices from. Every
    // decision is taken on the phone and stays on the phone: the toggles
    // below are the owner's word, the agent's own verdicts are the mutes
    // further down, and neither is ever uploaded.

    private const val KEY_MONEY_PREFIX = "money_enabled_"
    private const val KEY_OTHER_SOURCES = "read_other_sources"
    private const val KEY_APPS_SETUP_DONE = "apps_setup_done"

    /** A wallet/bank the owner switched off is never forwarded. Default on. */
    fun isMoneyAppEnabled(ctx: Context, pkg: String) = sp(ctx).getBoolean(KEY_MONEY_PREFIX + pkg, true)
    fun setMoneyAppEnabled(ctx: Context, pkg: String, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_MONEY_PREFIX + pkg, on).apply()

    /** Notices from apps that are neither chat apps nor listed wallets —
     *  how a wallet from any country gets recognised. Default on. */
    fun readOtherSources(ctx: Context) = sp(ctx).getBoolean(KEY_OTHER_SOURCES, true)
    fun setReadOtherSources(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_OTHER_SOURCES, on).apply()

    /** The end-of-onboarding apps screen was completed once on this phone. */
    fun appsSetupDone(ctx: Context) = sp(ctx).getBoolean(KEY_APPS_SETUP_DONE, false)
    fun setAppsSetupDone(ctx: Context) =
        sp(ctx).edit().putBoolean(KEY_APPS_SETUP_DONE, true).remove(KEY_APPS_SETUP_PENDING).apply()

    private const val KEY_APPS_SETUP_PENDING = "apps_setup_pending"

    /** The interview just ended and the apps screen has not been completed:
     *  the launcher routes there until it is. Installs that predate the
     *  screen never set this, so they land on the dashboard as before. */
    fun appsSetupPending(ctx: Context) = sp(ctx).getBoolean(KEY_APPS_SETUP_PENDING, false) && !appsSetupDone(ctx)
    fun setAppsSetupPending(ctx: Context) = sp(ctx).edit().putBoolean(KEY_APPS_SETUP_PENDING, true).apply()

    /** Last good `/api/credits` payload, rendered while offline. */
    fun creditsCache(ctx: Context): String? = sp(ctx).getString("credits_cache", null)
    fun setCreditsCache(ctx: Context, json: String) = sp(ctx).edit().putString("credits_cache", json).apply()

    // ------------------------------------------------------------ server-pushed catalogs
    //
    // The money-app catalog (`Wallets`) and the business categories
    // (`Categories`) ship bundled and are refreshed from the server at
    // launch; what the server last said is kept here, with when.

    fun walletsJson(ctx: Context): String? = sp(ctx).getString("wallets_json", null)
    fun walletsAt(ctx: Context): Long = sp(ctx).getLong("wallets_at", 0L)
    fun setWalletsJson(ctx: Context, json: String) =
        sp(ctx).edit().putString("wallets_json", json).putLong("wallets_at", System.currentTimeMillis()).apply()

    fun categoriesJson(ctx: Context): String? = sp(ctx).getString("categories_json", null)
    fun categoriesAt(ctx: Context): Long = sp(ctx).getLong("categories_at", 0L)
    fun setCategoriesJson(ctx: Context, json: String) =
        sp(ctx).edit().putString("categories_json", json).putLong("categories_at", System.currentTimeMillis()).apply()

    /** When the owner accepted the closed-loop credit terms (RFC 3339), mirrored from the account. */
    fun termsAcceptedAt(ctx: Context): String = sp(ctx).getString("terms_accepted_at", "") ?: ""
    fun setTermsAcceptedAt(ctx: Context, at: String) = sp(ctx).edit().putString("terms_accepted_at", at).apply()

    // ------------------------------------------------------------ locale

    /** Server-declared locale for this business (registration + dashboard). */
    fun setLocale(ctx: Context, locale: org.json.JSONObject?, fallbackCountry: String? = null) {
        val e = sp(ctx).edit()
        val country = locale?.optString("country").takeIf { !it.isNullOrBlank() } ?: fallbackCountry
        country?.let { e.putString("loc_country", it) }
        locale?.optString("currency")?.takeIf { it.isNotBlank() }?.let { e.putString("loc_currency", it) }
        locale?.optString("currencySymbol")?.let { e.putString("loc_symbol", it) }
        locale?.optString("language")?.takeIf { it.isNotBlank() }?.let { e.putString("loc_language", it) }
        e.apply()
    }
    fun country(ctx: Context): String = sp(ctx).getString("loc_country", "PE") ?: "PE"
    fun currencyCode(ctx: Context): String = sp(ctx).getString("loc_currency", "PEN") ?: "PEN"
    /** Symbol may legitimately be empty (unknown country): callers then show the code. */
    fun currencySymbol(ctx: Context): String =
        sp(ctx).getString("loc_symbol", null) ?: if (sp(ctx).contains("loc_currency")) "" else "S/"

    /** "S/ 50", "₹ 1,200.50", "50 USD" — money the way this business counts it. */
    fun money(ctx: Context, v: Double): String {
        val n = if (v == v.toLong().toDouble()) "${v.toLong()}"
                else String.format(java.util.Locale.US, "%.2f", v)
        val sym = currencySymbol(ctx)
        return when {
            sym.isNotEmpty() -> "$sym $n"
            currencyCode(ctx).isNotEmpty() -> "$n ${currencyCode(ctx)}"
            else -> n
        }
    }

    // ------------------------------------------------------------ learned payment sources

    private const val LEARNED_TTL_MS = 6 * 60 * 60 * 1000L

    /** Packages the server promoted as money apps (cached; refreshed by [refreshLearnedSources]). */
    fun learnedPaymentSources(ctx: Context): Set<String> =
        sp(ctx).getStringSet("learned_pay_sources", emptySet()) ?: emptySet()

    fun learnedSourcesStale(ctx: Context): Boolean =
        System.currentTimeMillis() - sp(ctx).getLong("learned_pay_sources_at", 0L) > LEARNED_TTL_MS

    fun invalidateLearnedSources(ctx: Context) =
        sp(ctx).edit().putLong("learned_pay_sources_at", 0L).apply()

    /** Pulls the list if stale. Network: call from [ServerClient.IO_EXECUTOR]. */
    fun refreshLearnedSources(ctx: Context, force: Boolean = false) {
        if (!force && !learnedSourcesStale(ctx)) return
        if (!serverConfigured(ctx)) return
        val resp = ServerClient.paymentSources(ctx) ?: return
        val arr = resp.optJSONArray("sources") ?: return
        val pkgs = HashSet<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("package")?.takeIf { it.isNotBlank() }?.let { pkgs.add(it) }
        sp(ctx).edit()
            .putStringSet("learned_pay_sources", pkgs)
            .putLong("learned_pay_sources_at", System.currentTimeMillis())
            .apply()
    }

    // ------------------------------------------------------------ notification sources
    //
    // The agent's verdicts about apps: "no money here, ask me again in a
    // week". Kept as package → epoch millis. Money apps are never muted.

    fun isSourceMuted(ctx: Context, pkg: String): Boolean =
        sp(ctx).getLong("mute_src_$pkg", 0L) > System.currentTimeMillis()

    fun muteSource(ctx: Context, pkg: String, untilMs: Long) =
        sp(ctx).edit().putLong("mute_src_$pkg", untilMs).apply()

    // ------------------------------------------------------------ server sync

    fun serverUrl(ctx: Context): String =
        sp(ctx).getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER

    private const val DEFAULT_SERVER = "https://agento.ceo"

    // ------------------------------------------------------------ AI engine
    //
    // The agent runs on the phone; only the language model is remote. Blank
    // = the yaya.tech gateway (free tier, authenticated by the agent's own
    // identity). Owners who want full sovereignty point this at any
    // OpenAI-compatible endpoint with their own key — even one on their LAN.

    fun llmBaseUrl(ctx: Context): String = sp(ctx).getString("llm_base_url", "") ?: ""
    fun llmApiKey(ctx: Context): String = SecureStore.getString(sp(ctx), "llm_api_key_enc") ?: ""
    fun llmModel(ctx: Context): String = sp(ctx).getString("llm_model", "") ?: ""
    fun setLlm(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        sp(ctx).edit()
            .putString("llm_base_url", baseUrl.trim().trimEnd('/'))
            .putString("llm_model", model.trim())
            .apply()
        SecureStore.putString(sp(ctx), "llm_api_key_enc", apiKey.trim())
    }
    fun llmIsCustom(ctx: Context) = llmBaseUrl(ctx).isNotBlank() || llmApiKey(ctx).isNotBlank()

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

    /** Mirror of the core's signed-in account (the core is the truth; this
     *  lets the launcher route without a loopback round-trip). */
    fun accountEmail(ctx: Context): String = sp(ctx).getString("account_email", "") ?: ""
    fun setAccountEmail(ctx: Context, email: String) =
        sp(ctx).edit().putString("account_email", email).apply()
    /**
     * A signed-in account. The account is the verified WhatsApp number since
     * 1.19.1 (email is optional), and since 1.22.0 there is no guest mode:
     * an install that "continued without an account" under an older build
     * is routed back to sign-in — its business and device token survive,
     * the OTP just links them to a real account.
     */
    fun hasIdentity(ctx: Context): Boolean =
        accountEmail(ctx).isNotEmpty() || accountPhone(ctx).isNotEmpty()

    /** What to call the signed-in account: the email, else the verified phone. */
    fun accountLabel(ctx: Context): String =
        accountEmail(ctx).ifEmpty { accountPhone(ctx).let { if (it.isEmpty()) "" else "+$it" } }

    /** E.164 digits of the account's verified phone, "" when unknown. */
    fun accountPhone(ctx: Context): String = sp(ctx).getString("account_phone", "") ?: ""
    fun setAccountPhone(ctx: Context, phone: String) =
        sp(ctx).edit().putString("account_phone", phone).apply()

    fun businessId(ctx: Context): String = sp(ctx).getString("business_id", "") ?: ""
    fun setBusinessId(ctx: Context, id: String) =
        sp(ctx).edit().putString("business_id", id).apply()

    /** Agent mode = registered business + reachable server; else canned replies. */
    fun serverConfigured(ctx: Context) = deviceToken(ctx).isNotEmpty()

    /**
     * Wipes everything tied to the paired business — for a future logout /
     * "cambiar de negocio" flow. Nothing calls this yet; adding it now so the
     * knowledge of WHICH keys make up an identity lives here, not in a
     * settings screen. Clears the cached dashboard and transcript too: they
     * are the previous business's data and must not greet the next login.
     * (auto_reply settings and per-app toggles are device preferences and
     * survive.)
     */
    fun clearIdentity(ctx: Context) {
        sp(ctx).edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(LEGACY_DEVICE_TOKEN)
            .remove("business_id")
            .remove("dash_cache")
            .remove("chat_transcript")
            .remove("walkthrough_seen")
            .remove("loc_country").remove("loc_currency").remove("loc_symbol").remove("loc_language")
            .remove("learned_pay_sources").remove("learned_pay_sources_at")
            .remove("credits_cache")
            .apply()
    }

    /** Last good dashboard payload, rendered while offline. */
    fun dashboardCache(ctx: Context): String? = sp(ctx).getString("dash_cache", null)
    fun setDashboardCache(ctx: Context, json: String) =
        sp(ctx).edit().putString("dash_cache", json).apply()

    /** The support WhatsApp, as last told by the server (BuildConfig default until then). */
    fun supportPhone(ctx: Context): String =
        sp(ctx).getString("support_phone", null)?.takeIf { it.length >= 8 } ?: BuildConfig.SUPPORT_WHATSAPP
    fun setSupportPhone(ctx: Context, phone: String) {
        val d = phone.filter { it.isDigit() }
        if (d.length >= 8) sp(ctx).edit().putString("support_phone", d).apply()
    }
    /** Remembers the support line from a `/api/plan` payload, if it carries one. */
    fun rememberSupport(ctx: Context, plan: org.json.JSONObject?) {
        plan?.optJSONObject("support")?.optString("phone")?.takeIf { it.isNotBlank() && it != "null" }?.let { setSupportPhone(ctx, it) }
    }

    /** D17: mirror customers / appointments into the phone's Contacts / Calendar. */
    fun syncContacts(ctx: Context): Boolean = sp(ctx).getBoolean("sync_contacts", false)
    fun setSyncContacts(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("sync_contacts", on).apply()
    fun syncCalendar(ctx: Context): Boolean = sp(ctx).getBoolean("sync_calendar", false)
    fun setSyncCalendar(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("sync_calendar", on).apply()
    fun osSyncOffered(ctx: Context): Boolean = sp(ctx).getBoolean("os_sync_offered", false)
    fun setOsSyncOffered(ctx: Context) = sp(ctx).edit().putBoolean("os_sync_offered", true).apply()

    /** D15: the first-open guide of the designed app, shown once per business. */
    fun walkthroughSeen(ctx: Context): Boolean = sp(ctx).getBoolean("walkthrough_seen", false)
    fun setWalkthroughSeen(ctx: Context, seen: Boolean) = sp(ctx).edit().putBoolean("walkthrough_seen", seen).apply()

    /** Persisted onboarding chat transcript (blocks joined by \n\n). */
    fun chatTranscript(ctx: Context): String? = sp(ctx).getString("chat_transcript", null)
    fun setChatTranscript(ctx: Context, t: String) =
        sp(ctx).edit().putString("chat_transcript", t).apply()
}
