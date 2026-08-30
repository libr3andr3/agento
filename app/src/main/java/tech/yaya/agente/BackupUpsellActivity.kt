package tech.yaya.agente

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/**
 * Shown once, right after the interview: the owner has just trained their
 * agent and would hate to lose it. The promise is recovery — sign in with
 * the Yaya account on any phone and it comes back — and the plans that
 * carry it. Bought on the web, never here (Spotify-style).
 */
class BackupUpsellActivity : AppCompatActivity() {

    companion object {
        const val PREF_SHOWN = "upsell_shown"
        fun shouldShow(ctx: android.content.Context) = !Prefs.sp(ctx).getBoolean(PREF_SHOWN, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upsell)
        Prefs.sp(this).edit().putBoolean(PREF_SHOWN, true).apply()
        val name = Prefs.sp(this).getString("business_name", "").orEmpty()
        findViewById<TextView>(R.id.upsell_body).text =
            if (name.isBlank()) getString(R.string.upsell_body_generic) else getString(R.string.upsell_body, name)
        findViewById<TextView>(R.id.upsell_web_note).text = getString(R.string.upsell_web_note, Prefs.accountEmail(this))
        val guest = Prefs.isGuest(this)
        if (guest) findViewById<com.google.android.material.button.MaterialButton>(R.id.upsell_cta).setText(R.string.upsell_cta_guest)
        findViewById<View>(R.id.upsell_cta).setOnClickListener {
            if (guest) startActivity(Intent(this, AccountActivity::class.java))
            else startActivity(Intent(this, PlanActivity::class.java))
        }
        findViewById<View>(R.id.upsell_later).setOnClickListener { done() }
        loadTiers()
    }

    private fun loadTiers() {
        ServerClient.IO_EXECUTOR.execute {
            val p = ServerClient.plan(this) ?: return@execute
            runOnUiThread { renderTiers(p) }
        }
    }

    private fun renderTiers(p: JSONObject) {
        val box = findViewById<LinearLayout>(R.id.upsell_tiers)
        box.removeAllViews()
        val tiers = p.optJSONArray("tiers") ?: return
        val cur = p.optJSONObject("prices")?.optString("currency", "PEN") ?: "PEN"
        val sym = if (cur == "PEN") "S/" else cur
        for (i in 0 until tiers.length()) {
            val t = tiers.getJSONObject(i)
            if (t.optString("name") == "free") continue
            val card = MaterialCardView(this).apply {
                radius = resources.getDimension(R.dimen.corner_card)
                cardElevation = resources.getDimension(R.dimen.card_elevation)
                setContentPadding(resources.getDimensionPixelSize(R.dimen.space_m), resources.getDimensionPixelSize(R.dimen.space_s),
                    resources.getDimensionPixelSize(R.dimen.space_m), resources.getDimensionPixelSize(R.dimen.space_s))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = resources.getDimensionPixelSize(R.dimen.space_s)
                layoutParams = lp
            }
            val tv = TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_Agento_Title)
                setTextColor(getColor(R.color.agento_on_surface))
                val tier = t.optString("name").replaceFirstChar { it.uppercase() }
                text = getString(R.string.upsell_tier_line2, tier, "$sym ${t.optDouble("price").toLong()}")
            }
            card.addView(tv)
            box.addView(card)
        }
    }

    private fun done() {
        startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
