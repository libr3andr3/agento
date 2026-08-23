package tech.yaya.agente

import android.content.ClipData
import android.content.ClipboardManager
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
 * Business plans: Free / Pro / Max, paid monthly by Yape/Plin. The owner
 * picks a tier, gets a reference + the number to pay, and the plan turns on
 * when yaya.tech confirms the transfer. Everything the agent may do today
 * (messages, new customers, speech, vision) follows the plan.
 */
class PlanActivity : AppCompatActivity() {

    private lateinit var current: TextView
    private lateinit var usage: TextView
    private lateinit var tiers: LinearLayout
    private lateinit var payCard: View
    private lateinit var note: TextView
    private var info: JSONObject? = null
    private var request: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plans)
        current = findViewById(R.id.plan_current)
        usage = findViewById(R.id.plan_usage)
        tiers = findViewById(R.id.plan_tiers)
        payCard = findViewById(R.id.plan_pay_card)
        note = findViewById(R.id.plan_note)
        findViewById<View>(R.id.plan_pay_copy).setOnClickListener {
            val ref = request?.optString("ref").orEmpty()
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ref", ref))
            Toast.makeText(this, R.string.plan_pay_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.plan_pay_check).setOnClickListener { check() }
        Prefs.sp(this).getString("plan_request", null)?.let { request = runCatching { JSONObject(it) }.getOrNull() }
        render()
        load()
    }

    private fun load() {
        ServerClient.IO_EXECUTOR.execute {
            val p = ServerClient.plan(this)
            runOnUiThread {
                if (p == null) Toast.makeText(this, R.string.plan_error, Toast.LENGTH_SHORT).show()
                info = p; render()
            }
        }
    }

    private fun tierName(t: String) = getString(when (t) { "pro" -> R.string.plan_pro_name; "max" -> R.string.plan_max_name; else -> R.string.plan_free_name })

    private fun render() {
        val p = info
        val plan = p?.optString("plan", "free") ?: "free"
        val until = p?.optString("expiresAt").orEmpty().take(10)
        current.text = if (until.isNotEmpty() && plan != "free") getString(R.string.plan_current_until, tierName(plan), until)
                       else getString(R.string.plan_current, tierName(plan))
        val used = p?.optInt("used") ?: 0; val cap = p?.optInt("cap") ?: 0
        usage.text = if (cap > 0) getString(R.string.plan_usage_today, used, cap) else getString(R.string.plan_usage_unlimited, used)
        val tiersJson = p?.optJSONArray("tiers")
        fun tier(t: String): JSONObject? {
            if (tiersJson == null) return null
            for (i in 0 until tiersJson.length()) { val o = tiersJson.getJSONObject(i); if (o.optString("name") == t) return o }
            return null
        }
        val prices = p?.optJSONObject("prices")
        val cur = prices?.optString("currency", "PEN") ?: "PEN"
        val sym = if (cur == "PEN") "S/" else cur
        tiers.removeAllViews()
        val inf = LayoutInflater.from(this)
        for (t in listOf("free", "pro", "max")) {
            val v = inf.inflate(R.layout.item_plan_tier, tiers, false)
            v.findViewById<TextView>(R.id.tier_name).text = tierName(t)
            val tj = tier(t)
            v.findViewById<TextView>(R.id.tier_desc).text = when (t) {
                "pro" -> getString(R.string.plan_biz_pro_desc, tj?.optInt("messagesPerDay") ?: 1000, tj?.optInt("customersPerDay") ?: 250)
                "max" -> getString(R.string.plan_biz_max_desc)
                else -> getString(R.string.plan_biz_free_desc, tj?.optInt("messagesPerDay") ?: 100, tj?.optInt("customersPerDay") ?: 25)
            }
            val price = prices?.optDouble(t, 0.0) ?: 0.0
            v.findViewById<TextView>(R.id.tier_price).text =
                if (t == "free" || price <= 0.0) "" else getString(R.string.plan_per_month, "$sym ${if (price == price.toLong().toDouble()) price.toLong() else price}")
            val btn = v.findViewById<MaterialButton>(R.id.tier_button)
            when {
                t == plan -> { btn.text = getString(R.string.plan_active); btn.isEnabled = false }
                t == "free" -> btn.visibility = View.GONE
                else -> { btn.text = getString(R.string.plan_choose, tierName(t)); btn.setOnClickListener { choose(t) } }
            }
            tiers.addView(v)
        }
        renderPay()
        val sales = p?.optJSONObject("pay")?.optString("salesWhatsapp").orEmpty().filter { it.isDigit() }
            .ifEmpty { request?.optJSONObject("pay")?.optString("salesWhatsapp").orEmpty().filter { it.isDigit() } }
        findViewById<MaterialButton>(R.id.plan_sales).apply {
            visibility = if (sales.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener {
                val msg = Uri.encode(getString(R.string.plan_sales_message, Prefs.sp(this@PlanActivity).getString("business_name", "") ?: ""))
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$sales?text=$msg")))
            }
        }
    }

    private fun choose(t: String) {
        ServerClient.IO_EXECUTOR.execute {
            val r = ServerClient.planRequest(this, t, 1)
            runOnUiThread {
                if (r.code in 200..299 && r.json != null) {
                    request = r.json
                    Prefs.sp(this).edit().putString("plan_request", r.json.toString()).apply()
                    renderPay()
                } else Toast.makeText(this, R.string.plan_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderPay() {
        val r = request
        if (r == null || r.optString("status") == "paid") { payCard.visibility = View.GONE; return }
        payCard.visibility = View.VISIBLE
        val cur = r.optString("currency", "PEN"); val sym = if (cur == "PEN") "S/" else cur
        val amount = r.optDouble("amount", 0.0)
        val amt = "$sym ${if (amount == amount.toLong().toDouble()) amount.toLong() else amount}"
        val pay = r.optJSONObject("pay")
        val payee = pay?.optString("payee").orEmpty().ifEmpty { "Yaya Tech" }
        findViewById<TextView>(R.id.plan_pay_body).text = getString(R.string.plan_pay_body, amt, payee)
        findViewById<TextView>(R.id.plan_pay_ref).text = r.optString("ref")
        val yape = pay?.optString("yape").orEmpty(); val plin = pay?.optString("plin").orEmpty()
        findViewById<TextView>(R.id.plan_pay_channels).text = listOfNotNull(
            yape.takeIf { it.isNotEmpty() && it != "null" }?.let { getString(R.string.plan_pay_yape, it) },
            plin.takeIf { it.isNotEmpty() && it != "null" }?.let { getString(R.string.plan_pay_plin, it) },
        ).joinToString("\n").ifEmpty { getString(R.string.plan_pay_unconfigured) }
        findViewById<TextView>(R.id.plan_pay_status).text = ""
    }

    private fun check() {
        val ref = request?.optString("ref").orEmpty()
        if (ref.isEmpty()) return
        ServerClient.IO_EXECUTOR.execute {
            val s = ServerClient.planRequestStatus(this, ref)
            runOnUiThread {
                val status = findViewById<TextView>(R.id.plan_pay_status)
                if (s?.optString("status") == "paid") {
                    request = s
                    Prefs.sp(this).edit().remove("plan_request").apply()
                    Toast.makeText(this, getString(R.string.plan_pay_paid, tierName(s.optString("plan"))), Toast.LENGTH_LONG).show()
                    load()
                } else status.text = getString(R.string.plan_pay_pending)
            }
        }
    }
}
