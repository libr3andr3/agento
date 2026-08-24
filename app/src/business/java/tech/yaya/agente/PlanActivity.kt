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
 * Business plan screen: Free / Pro / Max for the whole Yaya account. The
 * owner reads their standing here and buys on the web (Yape/Plin through
 * their account page) — one place to pay, every device follows.
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
        findViewById<View>(R.id.plan_pay_card).visibility = View.GONE
        findViewById<TextView>(R.id.plan_account).text = getString(R.string.plan_account_line, Prefs.accountEmail(this))
        findViewById<MaterialButton>(R.id.plan_logout).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java).putExtra(AccountActivity.EXTRA_MANAGE, true))
        }
        findViewById<MaterialButton>(R.id.plan_sales).apply {
            setText(R.string.account_manage_web)
            setOnClickListener { openWeb() }
        }
        note.text = getString(R.string.plan_web_only) + "\n" + getString(R.string.plan_credits_hint)
        render()
        load()
    }

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
        val prices = p?.optJSONObject("prices")
        val cur = prices?.optString("currency", "PEN") ?: "PEN"
        val sym = if (cur == "PEN") "S/" else cur
        tiers.removeAllViews()
        val inf = LayoutInflater.from(this)
        for (t in listOf("free", "pro", "max", "enterprise")) {
            val v = inf.inflate(R.layout.item_plan_tier, tiers, false)
            v.findViewById<TextView>(R.id.tier_name).text = tierName(t)
            val tj = tier(t)
            v.findViewById<TextView>(R.id.tier_desc).text = when (t) {
                "pro" -> getString(R.string.plan_biz_pro_desc, tj?.optInt("messagesPerDay") ?: 1000, tj?.optInt("customersPerDay") ?: 250)
                "max" -> getString(R.string.plan_biz_max_desc)
                "enterprise" -> getString(R.string.plan_biz_enterprise_desc)
                else -> getString(R.string.plan_biz_free_desc, tj?.optInt("messagesPerDay") ?: 100, tj?.optInt("customersPerDay") ?: 25)
            }
            val price = prices?.optDouble(t, 0.0) ?: 0.0
            v.findViewById<TextView>(R.id.tier_price).text =
                if (t == "free" || price <= 0.0) "" else getString(R.string.plan_per_month, "$sym ${if (price == price.toLong().toDouble()) price.toLong() else price}")
            val btn = v.findViewById<MaterialButton>(R.id.tier_button)
            when {
                t == plan -> { btn.text = getString(R.string.plan_active); btn.isEnabled = false }
                t == "free" -> btn.visibility = View.GONE
                else -> { btn.text = getString(R.string.plan_choose, tierName(t)); btn.setOnClickListener { openWeb() } }
            }
            tiers.addView(v)
        }
    }
}
