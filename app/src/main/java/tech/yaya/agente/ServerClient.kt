package tech.yaya.agente

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Blocking HTTP client for agente-server. Call only from [EXECUTOR] — a single
 * thread, so replies to the agent stay in conversation order.
 *
 * Threading contract (matches current call sites — keep it that way):
 *  - [executeAction], [onboardingMessage], [voiceMessage], [catalogPhoto],
 *    [verifyStart], [verifyCheck], [onboardBusiness] → [EXECUTOR] (the
 *    conversation lane; ordering matters).
 *  - [dashboard], [answerGap], [paymentEvent] → [IO_EXECUTOR] (must not queue
 *    behind a 3-minute LLM turn).
 *  Nothing here may run on the main thread — every method blocks on network.
 */
object ServerClient {
    private const val TAG = "AgenteServer"

    /** Client credential: the server rejects /api requests without it.
     *  Injected at build time (AGENTO_APP_KEY env/property) — never committed. */
    private val APP_KEY = BuildConfig.APP_KEY

    /** Conversation lane: replies must stay in order, so one thread. */
    val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Fast lane for payment events, dashboard, and gap answers. Payment
     * notifications MUST NOT queue behind a slow LLM turn: a customer saying
     * "ya pagué" while their Yape waits in line looks like a missing payment.
     */
    val IO_EXECUTOR: ExecutorService = Executors.newCachedThreadPool()

    /** HTTP status + parsed body (null body = network failure or bad JSON). */
    data class Response(val code: Int, val json: JSONObject?)

    // ------------------------------------------------------------- taxonomy

    /**
     * What a [Response] means for the user, so callers stop switch-casing raw
     * status ints (and so new callers don't forget that code 0 exists).
     * Additive: every method still returns exactly what it returned before —
     * feed its Response (or bare code) through [classify] when you want words.
     */
    enum class Kind {
        /** 2xx — body (if any) is in [Response.json]. */
        OK,
        /** Code 0: never reached the server. Airplane mode, DNS, timeout. */
        OFFLINE,
        /** 401/403 — token rejected. Re-pair, don't retry. */
        AUTH,
        /** 429 — throttled. Back off, tell the user to wait. */
        RATE_LIMITED,
        /** 503 — feature intentionally off or server redeploying. The verify
         *  flow treats this as "skip the step", not as an error. */
        UNAVAILABLE,
        /** Other 5xx — the server is having a bad day; ours to fix, not the user's. */
        SERVER_DOWN,
        /** Remaining 4xx — the request itself was wrong (bad phone, bad code,
         *  unreadable photo). Retrying the same input won't help. */
        BAD,
    }

    fun classify(code: Int): Kind = when {
        code in 200..299 -> Kind.OK
        code == 0 -> Kind.OFFLINE
        code == 401 || code == 403 -> Kind.AUTH
        code == 429 -> Kind.RATE_LIMITED
        code == 503 -> Kind.UNAVAILABLE
        code in 500..599 -> Kind.SERVER_DOWN
        else -> Kind.BAD
    }

    fun classify(r: Response): Kind = classify(r.code)

    // ---------------------------------------------------------- connectivity

    /**
     * Best-effort "is there a network at all" — for callers that want to skip
     * a doomed request or word an error as "sin conexión" instead of "error".
     * NOT a gate inside this client: transport failures still come back as
     * code 0 whatever this says, and it can be stale the moment it returns.
     *
     * Fails OPEN (returns true) when the answer is unknowable — including the
     * current manifest, which does not declare ACCESS_NETWORK_STATE (only the
     * manifest owner may add it). Never let this helper be the reason a
     * request that would have succeeded was never sent.
     */
    fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true // unknowable → fail open
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // includes SecurityException while the permission is undeclared
        }
    }

    // ------------------------------------------------------------- transport

    /** When a failed exchange may be sent again. Retrying is opt-in per call
     *  because most of our endpoints have side effects (send a WhatsApp
     *  message, register a business, record a payment): the server dedupes
     *  some of those, but we do not rely on it. */
    private enum class Retry {
        /** Side-effectful POSTs: one shot, ever. */
        NEVER,
        /** Idempotent GET: one extra attempt on code 0 or any 5xx. */
        IDEMPOTENT,
        /** verifyStart/verifyCheck: one extra attempt ONLY on code 0 — the
         *  request died before the server saw it, so nothing was sent twice.
         *  A real status (429, 400…) is an answer, not a failure. */
        NETWORK_FAILURE_ONLY,
    }

    private const val RETRY_BACKOFF_MS = 500L

    /**
     * The one place a socket is opened. Everything public funnels through
     * here so timeouts, headers, and logging cannot drift apart again (they
     * had, four ways).
     *
     * body == null → GET; otherwise POST with [contentType].
     * Note: Android's HttpURLConnection may itself silently re-send a POST on
     * a stale kept-alive connection (sun.net.http.retryPost) — one more reason
     * the server-side dedupe on onboard/payment endpoints must stay.
     */
    private fun exchange(
        ctx: Context,
        path: String,
        body: ByteArray?,
        contentType: String?,
        bearer: Boolean,
        readTimeoutMs: Int,
        retry: Retry,
    ): Response {
        val first = exchangeOnce(ctx, path, body, contentType, bearer, readTimeoutMs)
        val again = when (retry) {
            Retry.NEVER -> false
            Retry.IDEMPOTENT -> first.code == 0 || first.code in 500..599
            Retry.NETWORK_FAILURE_ONLY -> first.code == 0
        }
        if (!again) return first
        try {
            Thread.sleep(RETRY_BACKOFF_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return first
        }
        Log.i(TAG, "retrying $path after ${first.code}")
        return exchangeOnce(ctx, path, body, contentType, bearer, readTimeoutMs)
    }

    private fun exchangeOnce(
        ctx: Context,
        path: String,
        body: ByteArray?,
        contentType: String?,
        bearer: Boolean,
        readTimeoutMs: Int,
    ): Response {
        return try {
            val conn = URL(AgentoCore.baseUrl(ctx) + path).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("X-App-Key", AgentoCore.appKey(ctx))
                if (bearer) conn.setRequestProperty(
                    "Authorization", "Bearer " + Prefs.deviceToken(ctx)
                )
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    contentType?.let { conn.setRequestProperty("Content-Type", it) }
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) Log.w(TAG, "$path -> $code: $text")
                Response(code, runCatching { JSONObject(text) }.getOrNull())
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path failed", e)
            Response(0, null)
        }
    }

    /** JSON POST, raw response back. LLM tool loops can take a while, hence
     *  the 3-minute read timeout on the conversation endpoints. */
    private fun postRaw(
        ctx: Context,
        path: String,
        body: JSONObject,
        bearer: Boolean,
        retry: Retry = Retry.NEVER,
    ): Response = exchange(
        ctx, path,
        body = body.toString().toByteArray(),
        contentType = "application/json",
        bearer = bearer,
        readTimeoutMs = 180_000,
        retry = retry,
    )

    private fun post(
        ctx: Context,
        path: String,
        body: JSONObject,
        bearer: Boolean,
        retry: Retry = Retry.NEVER,
    ): JSONObject? {
        val r = postRaw(ctx, path, body, bearer, retry)
        return if (r.code in 200..299) r.json else null
    }

    // -------------------------------------------------------------- endpoints

    // Plans (both editions). Bearer when a business is paired; the core
    // talks to yaya.tech with its identity either way. [IO_EXECUTOR].

    fun plan(ctx: Context): JSONObject? {
        val r = exchange(ctx, "/api/plan", body = null, contentType = null,
            bearer = Prefs.serverConfigured(ctx), readTimeoutMs = 30_000, retry = Retry.IDEMPOTENT)
        return if (r.code in 200..299) r.json else null
    }

    // Yaya ID (both editions). No device token: the account comes first.
    // [IO_EXECUTOR].

    fun account(ctx: Context): JSONObject? {
        val r = exchange(ctx, "/api/account", body = null, contentType = null, bearer = false,
            readTimeoutMs = 15_000, retry = Retry.IDEMPOTENT)
        return if (r.code in 200..299) r.json else null
    }

    fun accountRegister(ctx: Context, email: String, password: String, name: String?): Response =
        postRaw(ctx, "/api/account/register",
            JSONObject().put("email", email).put("password", password).apply { name?.let { put("name", it) } },
            bearer = false)

    fun accountLogin(ctx: Context, email: String, password: String): Response =
        postRaw(ctx, "/api/account/login", JSONObject().put("email", email).put("password", password), bearer = false)

    fun accountLogout(ctx: Context): Boolean =
        postRaw(ctx, "/api/account/logout", JSONObject(), bearer = false).code in 200..299

    /** Upload an encrypted snapshot now (paid plans). */
    fun backupNow(ctx: Context): Response = postRaw(ctx, "/api/backup", JSONObject(), bearer = false)

    /** Bring the account's latest snapshot onto this phone. Slow: it downloads
     *  and rewrites every table. Side-effectful: never retried. */
    fun restore(ctx: Context, force: Boolean): Response = exchange(
        ctx, "/api/restore",
        body = JSONObject().put("force", force).toString().toByteArray(),
        contentType = "application/json", bearer = false,
        readTimeoutMs = 180_000, retry = Retry.NEVER,
    )

    /** Coarse location → the core (public offer for a business, private
     *  profile for a person). Idempotent. [IO_EXECUTOR]. */
    fun postLocation(ctx: Context, body: JSONObject, bearer: Boolean): Boolean =
        postRaw(ctx, "/api/location", body, bearer = bearer, retry = Retry.IDEMPOTENT).code in 200..299

    // Personal assistant (client edition). The phone owner is the tenant: no
    // device token, the loopback app key is the whole authorization.

    /** One assistant turn. Side-effectful (persists + may `remember`):
     *  never auto-retried. [EXECUTOR]. */
    fun assistantMessage(ctx: Context, message: String): Response =
        postRaw(ctx, "/api/assistant/message", JSONObject().put("message", message), bearer = false)

    /** Stored transcript + profile, oldest first. Idempotent. [IO_EXECUTOR]. */
    fun assistantHistory(ctx: Context, limit: Int = 200): JSONObject? {
        val r = exchange(
            ctx, "/api/assistant/history?limit=$limit",
            body = null, contentType = null, bearer = false,
            readTimeoutMs = 15_000, retry = Retry.IDEMPOTENT,
        )
        return if (r.code in 200..299) r.json else null
    }

    /** Wipes the transcript (and the profile when [forgetProfile]). [EXECUTOR]. */
    fun assistantReset(ctx: Context, forgetProfile: Boolean): Boolean =
        postRaw(
            ctx, "/api/assistant/reset",
            JSONObject().put("forget_profile", forgetProfile), bearer = false,
        ).code in 200..299

    /**
     * Customer message in, agent reply out. Null = server unreachable/error.
     * The conversation history lives server-side and is not sent from here —
     * a client-supplied history was a way to put words in the agent's mouth.
     * Side-effectful (sends a reply): never auto-retried. Runs on [EXECUTOR].
     */
    fun executeAction(ctx: Context, peer: String, message: String): JSONObject? =
        post(
            ctx, "/api/execute_action",
            JSONObject()
                .put("phoneNumber", peer)
                .put("message", message),
            bearer = true
        )

    /** Onboarding chat turn. Side-effectful: never auto-retried. [EXECUTOR]. */
    fun onboardingMessage(ctx: Context, message: String): JSONObject? =
        post(
            ctx, "/api/onboarding_message",
            JSONObject().put("message", message),
            bearer = true
        )

    /** Voice turn for onboarding: raw m4a bytes up, transcript+reply+wav down.
     *  Side-effectful (a turn in the interview): never auto-retried. [EXECUTOR]. */
    fun voiceMessage(ctx: Context, audio: ByteArray): JSONObject? {
        val r = exchange(
            ctx, "/api/voice_message",
            body = audio,
            contentType = "application/octet-stream",
            bearer = true,
            readTimeoutMs = 180_000,
            retry = Retry.NEVER,
        )
        return if (r.code in 200..299) r.json else null
    }

    /**
     * Catalog/menu photo up (JPEG bytes), extracted items+prices down. The
     * status code carries meaning (422 unreadable photo, 503 feature off,
     * 429 limited), so the raw [Response] is returned instead of a nullable
     * body — mirror of [voiceMessage] otherwise, same timeouts. Never
     * auto-retried (each attempt costs an LLM vision call). [EXECUTOR].
     */
    fun catalogPhoto(ctx: Context, image: ByteArray): Response = exchange(
        ctx, "/api/catalog_photo",
        body = image,
        contentType = "application/octet-stream",
        bearer = true,
        readTimeoutMs = 180_000,
        retry = Retry.NEVER,
    )

    /** Idempotent GET — the one endpoint that auto-retries freely (once,
     *  500ms) on network failure or 5xx. Callers cache the last good payload
     *  ([Prefs.dashboardCache]), so a final null must degrade, not blank the
     *  screen. Runs on [IO_EXECUTOR]. */
    fun dashboard(ctx: Context): JSONObject? {
        val r = exchange(
            ctx, "/api/dashboard",
            body = null,
            contentType = null,
            bearer = true,
            readTimeoutMs = 30_000,
            retry = Retry.IDEMPOTENT,
        )
        return if (r.code in 200..299) r.json else null
    }

    /** Fleet-learned money apps for this business's country. [IO_EXECUTOR]. */
    fun paymentSources(ctx: Context): JSONObject? {
        val r = exchange(
            ctx, "/api/payment_sources",
            body = null, contentType = null, bearer = true,
            readTimeoutMs = 20_000, retry = Retry.IDEMPOTENT,
        )
        return if (r.code in 200..299) r.json else null
    }

    /**
     * Owner answers a question the agent couldn't. The server turns it into a
     * candidate the agent uses in the very next conversation. Side-effectful:
     * never auto-retried. Runs on [IO_EXECUTOR].
     */
    fun answerGap(ctx: Context, gapId: String, answer: String): JSONObject? =
        post(
            ctx, "/api/answer_gap",
            JSONObject().put("gapId", gapId).put("answer", answer),
            bearer = true
        )

    /** Forward a Yape/Plin payment notification for verification/matching.
     *  Side-effectful (marks money as seen): never auto-retried — a double
     *  send could double-confirm a payment. Runs on [IO_EXECUTOR]. */
    fun paymentEvent(
        ctx: Context, source: String, sourcePackage: String, title: String, text: String,
        notification: JSONObject,
    ): JSONObject? =
        post(
            ctx, "/api/payment_event",
            JSONObject().put("source", source).put("sourcePackage", sourcePackage)
                .put("title", title).put("text", text).put("notification", notification),
            bearer = true
        )

    /**
     * Asks the server to WhatsApp a 6-digit code to the owner's phone.
     * The status code is the answer: 200 sent, 503 verification not deployed
     * (register without it), 429 throttled, 400 bad phone, 0 unreachable.
     * Auto-retries once ONLY on code 0 (request never left the device) —
     * a 429/400/503 is an answer and is returned as-is. [EXECUTOR].
     */
    fun verifyStart(ctx: Context, phone: String): Int =
        postRaw(
            ctx, "/api/verify/start", JSONObject().put("phone", phone),
            bearer = false, retry = Retry.NETWORK_FAILURE_ONLY
        ).code

    /** Null unless the code was right; the proof rides in "verificationToken".
     *  Auto-retries once ONLY on code 0 — a wrong code must not be re-tested
     *  (it burns the owner's limited attempts). [EXECUTOR]. */
    fun verifyCheck(ctx: Context, phone: String, code: String): JSONObject? =
        post(
            ctx, "/api/verify/check",
            JSONObject().put("phone", phone).put("code", code),
            bearer = false, retry = Retry.NETWORK_FAILURE_ONLY
        )

    /**
     * Registers the business and returns its device token. No admin key: the
     * ops key has no business inside a client binary, and the server no longer
     * accepts it in place of the app key. Side-effectful (creates the
     * business): NEVER auto-retried — the server dedupes by phone today, but
     * a client must not lean on that. [EXECUTOR].
     */
    fun onboardBusiness(
        ctx: Context, name: String, industry: String, ownerPhone: String,
        country: String, verificationToken: String? = null
    ): JSONObject? =
        post(
            ctx, "/api/onboard_business",
            JSONObject()
                .put("businessName", name)
                .put("industry", industry)
                .put("ownerPhone", ownerPhone)
                .put("country", country)
                .apply { verificationToken?.let { put("verificationToken", it) } },
            bearer = false
        )
}
