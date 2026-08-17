package tech.yaya.agente

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Blocking HTTP client for agente-server. Call only from [EXECUTOR] — a single
 * thread, so replies to the agent stay in conversation order.
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

    private fun post(
        ctx: Context,
        path: String,
        body: JSONObject,
        bearer: Boolean
    ): JSONObject? {
        return try {
            val conn = URL(Prefs.serverUrl(ctx) + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            // LLM tool loops can take a while.
            conn.readTimeout = 180_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-App-Key", APP_KEY)
            if (bearer) conn.setRequestProperty(
                "Authorization", "Bearer " + Prefs.deviceToken(ctx)
            )
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) JSONObject(text)
            else {
                Log.w(TAG, "POST $path -> $code: $text")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $path failed", e)
            null
        }
    }

    /**
     * Customer message in, agent reply out. Null = server unreachable/error.
     * The conversation history lives server-side and is not sent from here —
     * a client-supplied history was a way to put words in the agent's mouth.
     */
    fun executeAction(ctx: Context, peer: String, message: String): JSONObject? =
        post(
            ctx, "/api/execute_action",
            JSONObject()
                .put("phoneNumber", peer)
                .put("message", message),
            bearer = true
        )

    fun onboardingMessage(ctx: Context, message: String): JSONObject? =
        post(
            ctx, "/api/onboarding_message",
            JSONObject().put("message", message),
            bearer = true
        )

    /** Voice turn for onboarding: raw m4a bytes up, transcript+reply+wav down. */
    fun voiceMessage(ctx: Context, audio: ByteArray): JSONObject? {
        return try {
            val conn = URL(Prefs.serverUrl(ctx) + "/api/voice_message")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-App-Key", APP_KEY)
            conn.setRequestProperty("Authorization", "Bearer " + Prefs.deviceToken(ctx))
            conn.outputStream.use { it.write(audio) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) JSONObject(text) else {
                Log.w(TAG, "voice -> $code: $text"); null
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice failed", e); null
        }
    }

    fun dashboard(ctx: Context): JSONObject? {
        return try {
            val conn = URL(Prefs.serverUrl(ctx) + "/api/dashboard")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("X-App-Key", APP_KEY)
            conn.setRequestProperty("Authorization", "Bearer " + Prefs.deviceToken(ctx))
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) JSONObject(text) else {
                Log.w(TAG, "dashboard -> $code: $text"); null
            }
        } catch (e: Exception) {
            Log.w(TAG, "dashboard failed", e); null
        }
    }

    /**
     * Owner answers a question the agent couldn't. The server turns it into a
     * candidate the agent uses in the very next conversation.
     */
    fun answerGap(ctx: Context, gapId: String, answer: String): JSONObject? =
        post(
            ctx, "/api/answer_gap",
            JSONObject().put("gapId", gapId).put("answer", answer),
            bearer = true
        )

    /** Forward a Yape/Plin payment notification for verification/matching. */
    fun paymentEvent(ctx: Context, source: String, title: String, text: String): JSONObject? =
        post(
            ctx, "/api/payment_event",
            JSONObject().put("source", source).put("title", title).put("text", text),
            bearer = true
        )

    /**
     * Registers the business and returns its device token. No admin key: the
     * ops key has no business inside a client binary, and the server no longer
     * accepts it in place of the app key.
     */
    fun onboardBusiness(
        ctx: Context, name: String, industry: String, ownerPhone: String
    ): JSONObject? =
        post(
            ctx, "/api/onboard_business",
            JSONObject()
                .put("businessName", name)
                .put("industry", industry)
                .put("ownerPhone", ownerPhone),
            bearer = false
        )
}
