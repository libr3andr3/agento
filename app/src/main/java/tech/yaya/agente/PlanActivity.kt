package tech.yaya.agente

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/**
 * Business plan screen: Free / Pro / Max / Enterprise for the whole Yaya
 * account, bought right here. Choosing a tier opens "Opciones de pago" —
 * monthly, or a year for the price of ten months — and paying is a Yape or
 * Plin transfer against a reference; the gateway sees the money and the
 * plan turns on for every device of the account.
 */
class PlanActivity : AppCompatActivity() {

    private lateinit var current: TextView
    private lateinit var usage: TextView
    private lateinit var tiers: LinearLayout
    private lateinit var note: TextView
    private lateinit var optionsCard: View
    private lateinit var payCard: View
    private var info: JSONObject? = null

    /** Tier the owner is deciding on, and the period picked in the options card. */
    private var choosing: String? = null
    private var annual = false
    /** The open purchase (reference), polled while this screen is visible. */
    private var pendingRef: String? = null
    private var pendingPlan: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { pendingRef?.let { check(it, quiet = true) }; ui.postDelayed(this, POLL_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plans)
        current = findViewById(R.id.plan_current)
        usage = findViewById(R.id.plan_usage)
        tiers = findViewById(R.id.plan_tiers)
        note = findViewById(R.id.plan_note)
        optionsCard = findViewById(R.id.plan_options_card)
        payCard = findViewById(R.id.plan_pay_card)
        findViewById<TextView>(R.id.plan_account).text = getString(R.string.plan_account_line, Prefs.accountEmail(this))
        findViewById<MaterialButton>(R.id.plan_logout).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java).putExtra(AccountActivity.EXTRA_MANAGE, true))
        }
        findViewById<MaterialButton>(R.id.plan_sales).apply {
            visibility = View.VISIBLE
            setText(R.string.account_manage_web)
            setOnClickListener { openWeb() }
        }
        note.text = getString(R.string.plan_pay_hint) + "\n" + getString(R.string.plan_credits_hint)
        findViewById<View>(R.id.plan_option_month).setOnClickListener { annual = false; renderOptions() }
        findViewById<View>(R.id.plan_option_year).setOnClickListener { annual = true; renderOptions() }
        findViewById<View>(R.id.plan_options_cancel).setOnClickListener { choosing = null; renderOptions() }
        findViewById<View>(R.id.plan_options_pay).setOnClickListener { buy() }
        findViewById<View>(R.id.plan_pay_copy).setOnClickListener {
            val r = pendingRef ?: return@setOnClickListener
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("yaya", r))
            Toast.makeText(this, R.string.plan_pay_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.plan_pay_check).setOnClickListener { pendingRef?.let { check(it, quiet = false) } }
        pendingRef = Prefs.sp(this).getString(PREF_PENDING_REF, null)
        pendingPlan = Prefs.sp(this).getString(PREF_PENDING_PLAN, null)
        render()
        load()
        pendingRef?.let { check(it, quiet = true) }
    }

    override fun onResume() { super.onResume(); ui.postDelayed(poll, POLL_MS) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }

    private fun openWeb() = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.WEB_APP_URL)))

    private fun load() {
        ServerClient.IO_EXECUTOR.execute {
            val p = ServerClient.plan(this)
            runOnUiThread {
                if (p == null) Toast.makeText(this, R.string.plan_error, Toast.LENGTH_SHORT).show()
                info = p; render()
            }
        }
    }

    private fun tierName(t: String) = getString(when (t) { "pro" -> R.string.plan_pro_name; "max" -> R.string.plan_max_name; "enterprise" -> R.string.plan_enterprise_name; else -> R.string.plan_free_name })

    // ------------------------------------------------------------- prices

    private fun currency(): String {
        val cur = info?.optJSONObject("prices")?.optString("currency", "PEN") ?: "PEN"
        return if (cur == "PEN") "S/" else cur
    }

    /** "S/ 100" or "S/ 1 000.50": whole soles without decimals, thin-space thousands. */
    private fun money(v: Double, cur: String = currency()): String {
        val whole = v == v.toLong().toDouble()
        val n = if (whole) "%,d".format(java.util.Locale.US, v.toLong()) else "%,.2f".format(java.util.Locale.US, v)
        return "$cur ${n.replace(',', ' ')}"
    }

    private fun monthly(t: String) = info?.optJSONObject("prices")?.optDouble(t, 0.0) ?: 0.0
    private fun yearly(t: String): Double {
        val y = info?.optJSONObject("prices")?.optJSONObject("annual")?.optDouble(t, 0.0) ?: 0.0
        return if (y > 0.0) y else monthly(t) * 10
    }

    // ------------------------------------------------------------- render

    private fun render() {
        val p = info
        val plan = p?.optString("plan", "free") ?: "free"
        val until = p?.optString("expiresAt").orEmpty().take(10)
        current.text = if (until.isNotEmpty() && plan != "free") getString(R.string.plan_current_until, tierName(plan), until)
                       else getString(R.string.plan_current, tierName(plan))
        val used = p?.optInt("used") ?: 0; val cap = p?.optInt("cap") ?: 0
        val credits = p?.optJSONObject("credits")
        val usageLine = if (cap > 0) getString(R.string.plan_usage_today, used, cap) else getString(R.string.plan_usage_unlimited, used)
        usage.text = if (credits == null) usageLine else usageLine + "\n" + getString(
            R.string.plan_credits_line, Prefs.money(this, credits.optLong("balance") / 100.0), Prefs.money(this, credits.optLong("leadPrice") / 100.0))
        val tiersJson = p?.optJSONArray("tiers")
        fun tier(t: String): JSONObject? {
            if (tiersJson == null) return null
            for (i in 0 until tiersJson.length()) { val o = tiersJson.getJSONObject(i); if (o.optString("name") == t) return o }
            return null
        }
        tiers.removeAllViews()
        val inf = LayoutInflater.from(this)
        for (t in listOf("free", "pro", "max", "enterprise")) {
            val v = inf.inflate(R.layout.item_plan_tier, tiers, false)
            v.findViewById<TextView>(R.id.tier_name).text = tierName(t)
            val tj = tier(t)
            val desc = when (t) {
                "pro" -> getString(R.string.plan_biz_pro_desc, tj?.optInt("messagesPerDay") ?: 1000, tj?.optInt("customersPerDay") ?: 250)
                "max" -> getString(R.string.plan_biz_max_desc)
                "enterprise" -> getString(R.string.plan_biz_enterprise_desc)
                else -> getString(R.string.plan_biz_free_desc, tj?.optInt("messagesPerDay") ?: 100, tj?.optInt("customersPerDay") ?: 25)
            }
            val price = monthly(t)
            v.findViewById<TextView>(R.id.tier_desc).text =
                if (t == "free" || price <= 0.0) desc else desc + "\n" + getString(R.string.plan_per_year_line, money(yearly(t)))
            v.findViewById<TextView>(R.id.tier_price).text =
                if (t == "free" || price <= 0.0) "" else getString(R.string.plan_per_month, money(price))
            val btn = v.findViewById<MaterialButton>(R.id.tier_button)
            when {
                t == plan -> { btn.text = getString(R.string.plan_active); btn.isEnabled = false }
                t == "free" -> btn.visibility = View.GONE
                else -> { btn.text = getString(R.string.plan_choose, tierName(t)); btn.setOnClickListener { choose(t) } }
            }
            tiers.addView(v)
        }
        renderOptions()
    }

    private fun choose(t: String) {
        if (Prefs.accountEmail(this).isEmpty() || Prefs.isGuest(this)) {
            Toast.makeText(this, R.string.plan_sign_in_first, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, AccountActivity::class.java))
            return
        }
        choosing = t
        renderOptions()
        optionsCard.post { optionsCard.parent.requestChildFocus(optionsCard, optionsCard) }
    }

    private fun renderOptions() {
        val t = choosing
        optionsCard.visibility = if (t == null) View.GONE else View.VISIBLE
        if (t == null) return
        val m = monthly(t); val y = yearly(t)
        findViewById<TextView>(R.id.plan_options_sub).text = getString(R.string.plan_options_sub, tierName(t))
        findViewById<TextView>(R.id.plan_option_month_title).text = getString(R.string.plan_option_monthly, money(m))
        findViewById<TextView>(R.id.plan_option_year_title).text = getString(R.string.plan_option_annual, money(y))
        findViewById<TextView>(R.id.plan_option_year_note).text = getString(R.string.plan_option_annual_save, money(m * 12 - y))
        val on = getColor(R.color.agento_primary); val off = getColor(R.color.agento_outline)
        findViewById<MaterialCardView>(R.id.plan_option_month).strokeColor = if (annual) off else on
        findViewById<MaterialCardView>(R.id.plan_option_year).strokeColor = if (annual) on else off
        findViewById<MaterialButton>(R.id.plan_options_pay).text = getString(R.string.plan_options_pay, money(if (annual) y else m))
    }

    // ---------------------------------------------------------------- buy

    private fun buy() {
        val t = choosing ?: return
        val btn = findViewById<MaterialButton>(R.id.plan_options_pay)
        btn.isEnabled = false; btn.setText(R.string.plan_pay_opening)
        val months = if (annual) 12 else 1
        ServerClient.IO_EXECUTOR.execute {
            val r = ServerClient.planRequest(this, t, months)
            runOnUiThread {
                btn.isEnabled = true; renderOptions()
                val j = r.json
                if (r.code !in 200..299 || j == null || j.optString("ref").isEmpty()) {
                    val why = j?.optString("error").orEmpty().ifEmpty { if (r.code == 0) getString(R.string.plan_error) else r.code.toString() }
                    Toast.makeText(this, getString(R.string.plan_pay_error, why), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                choosing = null; renderOptions()
                showPay(j)
            }
        }
    }

    private fun showPay(j: JSONObject) {
        pendingRef = j.optString("ref"); pendingPlan = j.optString("plan")
        Prefs.sp(this).edit().putString(PREF_PENDING_REF, pendingRef).putString(PREF_PENDING_PLAN, pendingPlan).apply()
        val pay = j.optJSONObject("pay")
        // JSON null must read as "not configured", not the word "null".
        fun channel(k: String): String = if (pay == null || pay.isNull(k)) "" else pay.optString(k).trim()
        val cur = j.optString("currency", "PEN").let { if (it == "PEN") "S/" else it }
        val payee = channel("payee").ifEmpty { "Yaya Tech" }
        findViewById<TextView>(R.id.plan_pay_body).text = getString(R.string.plan_pay_body, money(j.optDouble("amount", 0.0), cur), payee)
        findViewById<TextView>(R.id.plan_pay_ref).text = pendingRef
        val ch = mutableListOf<String>()
        channel("yape").ifEmpty { null }?.let { ch += getString(R.string.plan_pay_yape, it) }
        channel("plin").ifEmpty { null }?.let { ch += getString(R.string.plan_pay_plin, it) }
        val channels = findViewById<TextView>(R.id.plan_pay_channels)
        channels.text = if (ch.isEmpty()) getString(R.string.plan_pay_unconfigured) else ch.joinToString("\n")
        // No numbers configured yet: the sales WhatsApp is the way to pay, reference in hand.
        val sales = channel("salesWhatsapp").filter { it.isDigit() }
        channels.setOnClickListener(if (ch.isEmpty() && sales.isNotEmpty()) View.OnClickListener {
            val text = Uri.encode("Hola, quiero pagar el plan ${tierName(pendingPlan ?: "pro")} · ref $pendingRef")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$sales?text=$text")))
        } else null)
        val period = if (j.optInt("months", 1) >= 12) R.string.plan_pay_period_year else R.string.plan_pay_period_month
        findViewById<TextView>(R.id.plan_pay_status).text =
            getString(period, tierName(pendingPlan ?: "pro")) + if (j.optBoolean("reused")) "\n" + getString(R.string.plan_pay_reused) else ""
        payCard.visibility = View.VISIBLE
        payCard.post { payCard.parent.requestChildFocus(payCard, payCard) }
    }

    private fun clearPending() {
        pendingRef = null; pendingPlan = null
        Prefs.sp(this).edit().remove(PREF_PENDING_REF).remove(PREF_PENDING_PLAN).apply()
        payCard.visibility = View.GONE
    }

    /** Asks whether the transfer was seen. Quiet checks (timer, first open) only speak when it was. */
    private fun check(ref: String, quiet: Boolean) {
        ServerClient.IO_EXECUTOR.execute {
            val r = ServerClient.planRequestStatus(this, ref)
            runOnUiThread {
                val j = r.json
                when {
                    r.code in 200..299 && j?.optString("status") == "paid" -> {
                        Toast.makeText(this, getString(R.string.plan_pay_paid, tierName(j.optString("plan", pendingPlan ?: "pro"))), Toast.LENGTH_LONG).show()
                        clearPending(); load()
                    }
                    r.code in 200..299 && j != null -> {
                        if (payCard.visibility != View.VISIBLE) showPay(j.also { it.put("reused", true) })
                        if (!quiet) findViewById<TextView>(R.id.plan_pay_status).text = getString(R.string.plan_pay_pending)
                    }
                    r.code == 404 || r.code == 401 -> clearPending()
                    !quiet -> Toast.makeText(this, R.string.plan_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val PREF_PENDING_REF = "plan_pending_ref"
        private const val PREF_PENDING_PLAN = "plan_pending_plan"
        private const val POLL_MS = 30_000L
    }
}
