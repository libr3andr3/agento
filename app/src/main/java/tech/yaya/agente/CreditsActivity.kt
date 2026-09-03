package tech.yaya.agente

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONObject
import java.util.Locale

/**
 * Credits — the whole revenue model on one screen (docs/CREDITS.md). No
 * plans: every confirmed booking or sale costs credits, nothing else does.
 *
 *   confirmed outcome with a customer already known to this business  USD 1
 *   confirmed outcome with a new customer                              USD 2
 *   welcome gift on sign-up                                            USD 12
 *
 * The gateway keeps the ledger and charges at confirmation time; this
 * screen only reads it (`GET /api/credits`, cached for offline). The
 * balance never gates who gets served: below zero the agent keeps booking
 * down to the grace floor, and only past it hands conversations to the
 * owner ("modo manual"). Topping up opens a card checkout (Dodo, merchant
 * of record) in a Custom Tab, or the Yape/Plin recarga in Perú.
 */
class CreditsActivity : AppCompatActivity() {

    private lateinit var balance: TextView
    private lateinit var state: TextView
    private lateinit var ledger: LinearLayout
    private lateinit var ledgerEmpty: TextView
    private var info: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credits)
        balance = findViewById(R.id.credits_balance)
        state = findViewById(R.id.credits_state)
        ledger = findViewById(R.id.credits_ledger)
        ledgerEmpty = findViewById(R.id.credits_ledger_empty)
        findViewById<TextView>(R.id.credits_account).text =
            getString(R.string.plan_account_line, Prefs.accountLabel(this))
        findViewById<MaterialButton>(R.id.credits_manage).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java).putExtra(AccountActivity.EXTRA_MANAGE, true))
        }
        findViewById<MaterialButton>(R.id.credits_topup).setOnClickListener { TopUp.sheet(this, info) }
        findViewById<MaterialButton>(R.id.credits_support).setOnClickListener { Support.open(this) }
        Prefs.creditsCache(this)?.let { info = runCatching { JSONObject(it) }.getOrNull() }
        render()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        ServerClient.IO_EXECUTOR.execute {
            val c = ServerClient.credits(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (c == null && info == null) Toast.makeText(this, R.string.credits_error, Toast.LENGTH_SHORT).show()
                if (c != null) { info = c; Prefs.setCreditsCache(this, c.toString()); Prefs.rememberSupport(this, c) }
                render()
            }
        }
    }

    private fun render() {
        val c = info
        val bal = Credits.balance(c)
        balance.text = Credits.money(c, bal)
        val known = Credits.priceKnown(c)
        val new = Credits.priceNew(c)
        findViewById<TextView>(R.id.credits_rule_known).text = getString(R.string.credits_rule_known, Credits.money(c, known))
        findViewById<TextView>(R.id.credits_rule_new).text = getString(R.string.credits_rule_new, Credits.money(c, new))
        findViewById<TextView>(R.id.credits_rule_welcome).text =
            getString(R.string.credits_rule_welcome, Credits.money(c, c?.optDouble("welcome", Credits.WELCOME) ?: Credits.WELCOME))
        findViewById<TextView>(R.id.credits_rule_volume).text = getString(
            R.string.credits_rule_volume,
            Credits.volumeAfter(c), Credits.money(c, Credits.priceKnownVolume(c)), Credits.money(c, Credits.priceNewVolume(c)), Credits.money(c, Credits.monthlyCap(c))
        )
        val month = c?.optJSONObject("month")
        findViewById<TextView>(R.id.credits_month).text = if (month == null) "" else getString(
            R.string.credits_month_line, month.optInt("outcomes"), Credits.money(c, month.optDouble("charged", 0.0))
        ) + if (month.optBoolean("capReached")) " · " + getString(R.string.credits_cap_reached) else ""
        state.text = when (Credits.state(c)) {
            Credits.State.UNKNOWN -> getString(R.string.credits_state_unknown)
            Credits.State.OK -> getString(R.string.credits_state_ok, (bal / new).toInt())
            Credits.State.LOW -> getString(R.string.credits_state_low)
            Credits.State.GRACE -> getString(R.string.credits_state_grace, Credits.money(c, kotlin.math.abs(Credits.grace(c))))
            Credits.State.MANUAL -> getString(R.string.credits_state_manual)
        }
        val red = Credits.state(c) == Credits.State.GRACE || Credits.state(c) == Credits.State.MANUAL
        state.setTextColor(getColor(if (red) R.color.agento_error else R.color.agento_on_primary_container))
        val deposits = findViewById<TextView>(R.id.credits_deposits)
        deposits.visibility = if (c != null && !Credits.depositsEnabled(c)) View.VISIBLE else View.GONE

        ledger.removeAllViews()
        val rows = c?.optJSONArray("ledger")
        ledgerEmpty.visibility = if (rows == null || rows.length() == 0) View.VISIBLE else View.GONE
        if (rows == null) return
        val inf = LayoutInflater.from(this)
        for (i in 0 until minOf(rows.length(), 50)) {
            val e = rows.optJSONObject(i) ?: continue
            val v = inf.inflate(R.layout.item_credit_entry, ledger, false)
            val amount = e.optDouble("amount", 0.0)
            v.findViewById<TextView>(R.id.entry_title).text = Credits.entryLabel(this, e)
            v.findViewById<TextView>(R.id.entry_when).text = e.optLong("ts").takeIf { it > 0 }?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            } ?: ""
            val amt = v.findViewById<TextView>(R.id.entry_amount)
            amt.text = (if (amount > 0) "+" else "−") + Credits.money(c, kotlin.math.abs(amount))
            amt.setTextColor(getColor(if (amount > 0) R.color.agento_secondary else R.color.agento_on_surface))
            ledger.addView(v)
        }
    }
}

/** Reading a `/api/credits` (or dashboard `credits`) payload, shared with the dashboard banner and the listener. */
object Credits {
    const val PRICE_KNOWN = 1.0
    const val PRICE_NEW = 2.0
    const val WELCOME = 12.0
    /** The account may run this far below zero before the agent hands off. */
    const val GRACE = -4.0
    const val VOLUME_AFTER = 100
    const val MONTHLY_CAP = 199.0
    /** Closed-loop terms the owner accepts at registration (docs/CREDITS.md § 3). */
    const val TERMS_VERSION = "2026-09"

    enum class State { UNKNOWN, OK, LOW, GRACE, MANUAL }

    fun balance(c: JSONObject?): Double = c?.optDouble("balance", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
    fun grace(c: JSONObject?): Double = c?.optDouble("grace", GRACE)?.takeIf { !it.isNaN() } ?: GRACE
    private fun prices(c: JSONObject?) = c?.optJSONObject("prices")
    fun priceKnown(c: JSONObject?): Double = prices(c)?.optDouble("known", PRICE_KNOWN) ?: PRICE_KNOWN
    fun priceNew(c: JSONObject?): Double = prices(c)?.optDouble("new", PRICE_NEW) ?: PRICE_NEW
    fun priceKnownVolume(c: JSONObject?): Double = prices(c)?.optDouble("volumeKnown", PRICE_KNOWN / 2) ?: PRICE_KNOWN / 2
    fun priceNewVolume(c: JSONObject?): Double = prices(c)?.optDouble("volumeNew", PRICE_NEW / 2) ?: PRICE_NEW / 2
    fun volumeAfter(c: JSONObject?): Int = prices(c)?.optInt("volumeAfter", VOLUME_AFTER) ?: VOLUME_AFTER
    fun monthlyCap(c: JSONObject?): Double = prices(c)?.optDouble("monthlyCap", MONTHLY_CAP) ?: MONTHLY_CAP
    fun depositsEnabled(c: JSONObject?): Boolean = c?.optBoolean("depositsEnabled", true) ?: true

    /**
     * The gateway's word when it gave one; otherwise derived from the
     * balance. The state never gates bookings — it only decides the banner
     * and, past the grace floor, the hand-off to the owner.
     */
    fun state(c: JSONObject?): State {
        if (c == null) return State.UNKNOWN
        when (c.optString("state")) {
            "ok" -> return State.OK
            "low" -> return State.LOW
            "grace" -> return State.GRACE
            "manual" -> return State.MANUAL
        }
        val b = balance(c)
        return when {
            b < grace(c) -> State.MANUAL
            b < 0.0 -> State.GRACE
            b < priceNew(c) -> State.LOW
            else -> State.OK
        }
    }

    fun manual(c: JSONObject?): Boolean = state(c) == State.MANUAL

    /** "$ 8.00" — credits are kept in USD everywhere; the symbol follows the code. */
    fun money(c: JSONObject?, v: Double): String {
        val code = c?.optString("currency", "USD")?.takeIf { it.isNotBlank() && it != "null" } ?: "USD"
        val sym = when (code) { "USD" -> "$"; "EUR" -> "€"; "BRL" -> "R$"; "PEN" -> "S/"; else -> code }
        return "$sym " + String.format(Locale.US, "%.2f", v)
    }

    fun entryLabel(ctx: Context, e: JSONObject): String {
        val who = e.optString("customer").takeIf { it.isNotBlank() && it != "null" }
        return when (e.optString("kind")) {
            "debit" -> if (e.optBoolean("isNewClient")) ctx.getString(R.string.credits_entry_new, who ?: ctx.getString(R.string.crm_unknown_name))
                       else ctx.getString(R.string.credits_entry_known, who ?: ctx.getString(R.string.crm_unknown_name))
            "booking_known" -> ctx.getString(R.string.credits_entry_known, who ?: ctx.getString(R.string.crm_unknown_name))
            "booking_new" -> ctx.getString(R.string.credits_entry_new, who ?: ctx.getString(R.string.crm_unknown_name))
            "grant", "welcome" -> ctx.getString(R.string.credits_entry_welcome)
            "topup" -> ctx.getString(R.string.credits_entry_topup)
            "reversal", "refund" -> ctx.getString(R.string.credits_entry_refund, who ?: "")
            else -> e.optString("note").takeIf { it.isNotBlank() && it != "null" } ?: e.optString("kind")
        }
    }
}

/**
 * Top-up: three presets (the middle one preselected), card everywhere
 * (Dodo Checkout in a Custom Tab), Yape/Plin as well in Perú. The gateway
 * says which methods apply (`topup.methods`); the app never carries the
 * country list.
 */
object TopUp {
    fun sheet(activity: AppCompatActivity, info: JSONObject?) {
        val topup = info?.optJSONObject("topup")
        val presets = topup?.optJSONArray("presets")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it > 0 } }
            ?.takeIf { it.isNotEmpty() } ?: listOf(10, 25, 50)
        val selected = topup?.optInt("selected", presets[presets.size / 2]) ?: presets[presets.size / 2]
        val methods = topup?.optJSONArray("methods")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("card")
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_topup, null)
        dialog.setContentView(view)
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.topup_presets)
        group.removeAllViews()
        val ids = HashMap<Int, Int>()
        presets.forEach { amount ->
            val b = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.id = View.generateViewId()
            b.text = Credits.money(info, amount.toDouble())
            group.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            ids[b.id] = amount
            if (amount == selected) group.check(b.id)
        }
        if (group.checkedButtonId == View.NO_ID) group.check(group.getChildAt(presets.size / 2).id)
        val amount = { ids[group.checkedButtonId] ?: selected }
        val card = view.findViewById<MaterialButton>(R.id.topup_card)
        val yape = view.findViewById<MaterialButton>(R.id.topup_yape)
        yape.visibility = if ("yape" in methods) View.VISIBLE else View.GONE
        card.visibility = if ("card" in methods || methods.isEmpty()) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.topup_terms).text = activity.getString(R.string.credits_terms)
        card.setOnClickListener { dialog.dismiss(); open(activity, amount(), "card") }
        yape.setOnClickListener { dialog.dismiss(); open(activity, amount(), "yape") }
        dialog.show()
    }

    private fun open(activity: AppCompatActivity, amount: Int, method: String) {
        Toast.makeText(activity, R.string.credits_topup_opening, Toast.LENGTH_SHORT).show()
        ServerClient.IO_EXECUTOR.execute {
            val r = ServerClient.topupSession(activity, amount, method)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val j = r.json
                val url = j?.optString("url").orEmpty()
                when {
                    r.code in 200..299 && url.startsWith("https://") -> customTab(activity, url)
                    r.code in 200..299 && j != null && method == "yape" -> yapeDialog(activity, j)
                    else -> Toast.makeText(activity, activity.getString(R.string.credits_topup_failed, j?.optString("error").orEmpty().ifEmpty { r.code.toString() }), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** The Yape/Plin recarga: amount, number and reference to write in the transfer. */
    private fun yapeDialog(activity: AppCompatActivity, j: JSONObject) {
        val pay = j.optJSONObject("pay")
        val lines = mutableListOf(activity.getString(R.string.credits_yape_body, "${j.optString("currency", "S/")} ${j.optDouble("amount")}", j.optString("ref")))
        pay?.optString("yape")?.takeIf { it.isNotBlank() && it != "null" }?.let { lines.add("Yape: $it") }
        pay?.optString("plin")?.takeIf { it.isNotBlank() && it != "null" }?.let { lines.add("Plin: $it") }
        pay?.optString("payee")?.takeIf { it.isNotBlank() && it != "null" }?.let { lines.add(it) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.credits_yape_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun customTab(ctx: Context, url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url))
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { Toast.makeText(ctx, url, Toast.LENGTH_LONG).show() }
        }
    }
}
