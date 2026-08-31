package tech.yaya.agente

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * The plan screen (D14). Three plans — Gratis (a 14-day Pro trial, then 30
 * conversations a month), Pro, Max — and one number that matters:
 * conversations this month. Nothing is bought here: "Subir a Pro" opens a
 * WhatsApp chat with agento's own sales agent, who takes the payment and
 * activates the plan on the owner's Yaya account. The app just reads the
 * result from the core (`/api/plan`).
 */
class PlanActivity : AppCompatActivity() {

    private lateinit var current: TextView
    private lateinit var usage: TextView
    private lateinit var tiers: LinearLayout
    private lateinit var note: TextView
    private var info: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plans)
        current = findViewById(R.id.plan_current)
        usage = findViewById(R.id.plan_usage)
        tiers = findViewById(R.id.plan_tiers)
        note = findViewById(R.id.plan_note)
        findViewById<TextView>(R.id.plan_account).text = getString(R.string.plan_account_line, Prefs.accountLabel(this).ifEmpty { getString(R.string.plan_no_account) })
        findViewById<MaterialButton>(R.id.plan_logout).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java).putExtra(AccountActivity.EXTRA_MANAGE, true))
        }
        findViewById<MaterialButton>(R.id.plan_sales).setOnClickListener { openSales("pro") }
        findViewById<MaterialButton>(R.id.plan_support).setOnClickListener { Support.open(this) }
        note.text = getString(R.string.plan_pay_note2)
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** WhatsApp to agento's sales agent, message prefilled: it does the rest. */
    private fun openSales(tier: String) {
        val text = Uri.encode(getString(R.string.plan_sales_text, tierName(tier)))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${BuildConfig.SALES_WHATSAPP}?text=$text")))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.plan_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun load() {
        ServerClient.IO_EXECUTOR.execute {
            val p = ServerClient.plan(this)
            runOnUiThread {
                if (p == null && info == null) Toast.makeText(this, R.string.plan_error, Toast.LENGTH_SHORT).show()
                if (p != null) { info = p; Prefs.rememberSupport(this, p) }
                render()
            }
        }
    }

    private fun tierName(t: String) = getString(when (t) {
        "pro" -> R.string.plan_pro_name
        "max", "custom", "enterprise" -> R.string.plan_max_name
        "trial" -> R.string.plan_trial_name
        else -> R.string.plan_free_name
    })

    private fun currency(): String {
        val cur = info?.optJSONObject("prices")?.optString("currency", "PEN") ?: "PEN"
        return if (cur == "PEN") "S/" else cur
    }

    /** "S/ 150" or "S/ 1 500": whole units without decimals, thin-space thousands. */
    private fun money(v: Double): String {
        val whole = v == v.toLong().toDouble()
        val n = if (whole) "%,d".format(java.util.Locale.US, v.toLong()) else "%,.2f".format(java.util.Locale.US, v)
        return "${currency()} ${n.replace(',', ' ')}"
    }

    private fun monthly(t: String) = info?.optJSONObject("prices")?.optDouble(t, 0.0) ?: 0.0
    private fun yearly(t: String): Double {
        val y = info?.optJSONObject("prices")?.optJSONObject("annual")?.optDouble(t, 0.0) ?: 0.0
        return if (y > 0.0) y else monthly(t) * 10
    }

    private fun daysLeft(): Int? {
        val exp = info?.optString("expiresAt").orEmpty()
        if (exp.length < 10) return null
        return try {
            val d = java.time.LocalDate.parse(exp.take(10))
            java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d).toInt().coerceAtLeast(0)
        } catch (_: Exception) { null }
    }

    private fun render() {
        val p = info
        val plan = p?.optString("plan", "free") ?: "free"
        val active = when (plan) { "custom", "enterprise" -> "max"; else -> plan }
        val until = p?.optString("expiresAt").orEmpty().take(10)
        current.text = when {
            plan == "trial" -> getString(R.string.plan_trial_left, daysLeft() ?: 0)
            until.isNotEmpty() && plan != "free" -> getString(R.string.plan_current_until, tierName(plan), until)
            else -> getString(R.string.plan_current, tierName(plan))
        }
        val used = p?.optInt("conversationsUsed") ?: 0
        val cap = p?.optInt("conversationsCap") ?: (p?.optJSONObject("caps")?.optInt("conversationsPerMonth") ?: 0)
        usage.text = if (cap > 0) getString(R.string.plan_conv_usage, used, cap) else getString(R.string.plan_conv_unlimited, used)

        tiers.removeAllViews()
        val inf = LayoutInflater.from(this)
        for (t in listOf("free", "pro", "max")) {
            val v = inf.inflate(R.layout.item_plan_tier, tiers, false)
            v.findViewById<TextView>(R.id.tier_name).text = tierName(t)
            val desc = when (t) {
                "pro" -> getString(R.string.plan_desc_pro)
                "max" -> getString(R.string.plan_desc_max)
                else -> getString(R.string.plan_desc_free)
            }
            val price = monthly(t)
            v.findViewById<TextView>(R.id.tier_desc).text =
                if (t == "free" || price <= 0.0) desc else desc + "\n" + getString(R.string.plan_per_year_line, money(yearly(t)))
            v.findViewById<TextView>(R.id.tier_price).text =
                if (t == "free" || price <= 0.0) "" else getString(R.string.plan_per_month, money(price))
            val btn = v.findViewById<MaterialButton>(R.id.tier_button)
            when {
                t == active || (t == "free" && active == "trial") -> { btn.text = getString(R.string.plan_active); btn.isEnabled = false }
                t == "free" -> btn.visibility = View.GONE
                else -> { btn.text = getString(R.string.plan_upgrade_to, tierName(t)); btn.setOnClickListener { openSales(t) } }
            }
            tiers.addView(v)
        }
    }
}
