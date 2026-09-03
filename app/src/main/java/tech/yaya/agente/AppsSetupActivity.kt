package tech.yaya.agente

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The last step of onboarding: where the agent talks, and what it reads.
 *
 * Two lists of switches, both decided and stored on this phone only:
 *
 *  - **chat apps** the agent answers on ([SupportedApps] plus the apps this
 *    phone taught itself). WhatsApp Business is on by default; plain
 *    WhatsApp is on when there is no Business app, because since 1.21 the
 *    account *is* the WhatsApp number the owner registered with;
 *  - **money apps** the agent reads payment notices from: the wallets and
 *    banks people use in the owner's country ([Wallets], keyed by the
 *    country code of that WhatsApp number), pre-checked when installed, plus
 *    one switch for "any other app" — which is how a wallet from another
 *    country, or one we never heard of, still gets recognised.
 *
 * Reachable again from Settings ([EXTRA_EDIT]) with the same screen.
 */
class AppsSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT = "edit"
    }

    private lateinit var chatList: LinearLayout
    private lateinit var moneyList: LinearLayout
    private var edit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps_setup)
        edit = intent.getBooleanExtra(EXTRA_EDIT, false)
        chatList = findViewById(R.id.apps_chat_list)
        moneyList = findViewById(R.id.apps_money_list)
        findViewById<TextView>(R.id.apps_step).visibility = if (edit) View.GONE else View.VISIBLE
        val cta = findViewById<MaterialButton>(R.id.apps_cta)
        cta.setText(if (edit) R.string.apps_save else R.string.apps_done)
        cta.setOnClickListener { finishSetup() }

        if (!Prefs.appsSetupDone(this)) applyChatDefaults()
        buildChatRows()
        buildMoneyRows()

        val other = findViewById<MaterialSwitch>(R.id.apps_other_switch)
        other.isChecked = Prefs.readOtherSources(this)
        other.setOnCheckedChangeListener { _, on -> Prefs.setReadOtherSources(this, on) }
    }

    /** First run only: the business number's own app answers by default. */
    private fun applyChatDefaults() {
        val w4b = AppToggles.isInstalled(this, "com.whatsapp.w4b")
        val wa = AppToggles.isInstalled(this, "com.whatsapp")
        if (!w4b && wa) Prefs.setAppEnabled(this, "com.whatsapp", true)
    }

    private fun buildChatRows() {
        chatList.removeAllViews()
        val (installed, missing) = SupportedApps.ALL.partition { AppToggles.isInstalled(this, it.packageName) }
        installed.forEach { app ->
            AppToggles.addRow(
                this, chatList, app.packageName, app.displayName, installed = true, subLabel = null,
                checked = Prefs.isAppEnabled(this, app.packageName),
            ) { on -> Prefs.setAppEnabled(this, app.packageName, on) }
        }
        // Apps this phone taught itself and that earned their switch.
        ProfileStore.all(this).filter { it.eligible || it.paused }.forEach { p ->
            AppToggles.addRow(
                this, chatList, p.packageName, p.displayName, installed = AppToggles.isInstalled(this, p.packageName),
                subLabel = getString(if (p.paused) R.string.settings_app_learned_paused else R.string.settings_app_learned_ready),
                checked = Prefs.isAppEnabled(this, p.packageName),
            ) { on ->
                if (on && p.paused) ProfileStore.resume(this, p.packageName)
                Prefs.setAppEnabled(this, p.packageName, on)
            }
        }
        val note = findViewById<TextView>(R.id.apps_chat_note)
        note.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        note.text = if (installed.isEmpty()) getString(R.string.apps_chat_none)
                    else getString(R.string.apps_chat_more, missing.joinToString(", ") { it.displayName })
    }

    private fun buildMoneyRows() {
        moneyList.removeAllViews()
        val iso = Prefs.country(this)
        val country = Countries.byIso(iso)
        findViewById<TextView>(R.id.apps_money_header).text = getString(R.string.apps_money_header, country.flag, country.name(this))
        val all = Wallets.candidates(this, iso)
        val (installed, missing) = all.partition { AppToggles.isInstalled(this, it.packageName) }
        installed.forEach { w ->
            AppToggles.addRow(
                this, moneyList, w.packageName, w.displayName, installed = true, subLabel = null,
                checked = Prefs.isMoneyAppEnabled(this, w.packageName),
            ) { on -> Prefs.setMoneyAppEnabled(this, w.packageName, on) }
        }
        val note = findViewById<TextView>(R.id.apps_money_note)
        note.text = when {
            installed.isEmpty() && missing.isEmpty() -> getString(R.string.apps_money_none)
            installed.isEmpty() -> getString(R.string.apps_money_none_known, missing.joinToString(", ") { it.displayName })
            missing.isEmpty() -> getString(R.string.apps_money_all)
            else -> getString(R.string.apps_money_more, missing.joinToString(", ") { it.displayName })
        }
    }

    private fun finishSetup() {
        Prefs.setAppsSetupDone(this)
        if (edit) { finish(); return }
        startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
