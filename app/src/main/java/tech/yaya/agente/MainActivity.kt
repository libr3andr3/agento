package tech.yaya.agente

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var permissionBanner: View
    private lateinit var masterSwitch: SwitchMaterial
    private lateinit var groupSwitch: SwitchMaterial
    private lateinit var replyPreview: TextView
    private lateinit var cooldownPreview: TextView
    private lateinit var appTogglesContainer: LinearLayout
    private lateinit var logAdapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // This screen is Settings; WelcomeActivity owns first-run routing.
        if (Prefs.serverConfigured(this)) {
            findViewById<TextView>(R.id.main_title).text = getString(R.string.settings_title)
            findViewById<TextView>(R.id.main_subtitle).visibility = View.GONE
        }
        // Server URL is a dev tool: reveal with a long-press on the section header.
        findViewById<TextView>(R.id.server_header).setOnLongClickListener {
            val b = findViewById<Button>(R.id.server_config_button)
            b.visibility = if (b.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        permissionBanner = findViewById(R.id.permission_banner)
        masterSwitch = findViewById(R.id.master_switch)
        groupSwitch = findViewById(R.id.group_switch)
        replyPreview = findViewById(R.id.reply_preview)
        cooldownPreview = findViewById(R.id.cooldown_preview)
        appTogglesContainer = findViewById(R.id.app_toggles)

        findViewById<Button>(R.id.grant_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        masterSwitch.setOnCheckedChangeListener { _, on ->
            if (on && !hasNotificationAccess()) {
                masterSwitch.isChecked = false
                promptForAccess()
            } else {
                Prefs.setEnabled(this, on)
            }
        }

        groupSwitch.setOnCheckedChangeListener { _, on -> Prefs.setReplyToGroups(this, on) }

        findViewById<Button>(R.id.server_config_button).setOnClickListener { editServerUrl() }
        findViewById<Button>(R.id.onboarding_button).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        findViewById<View>(R.id.reply_row).setOnClickListener { editReplyText() }
        findViewById<View>(R.id.cooldown_row).setOnClickListener { editCooldown() }
        findViewById<Button>(R.id.clear_log_button).setOnClickListener {
            ReplyLog.clear(this)
        }

        val logList = findViewById<RecyclerView>(R.id.log_list)
        logList.layoutManager = LinearLayoutManager(this)
        logAdapter = LogAdapter()
        logList.adapter = logAdapter

        buildAppToggles()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ReplyLog.listener = { runOnUiThread { logAdapter.reload() } }
    }

    override fun onPause() {
        super.onPause()
        ReplyLog.listener = null
    }

    private fun refresh() {
        val granted = hasNotificationAccess()
        permissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        masterSwitch.isChecked = granted && Prefs.isEnabled(this)
        groupSwitch.isChecked = Prefs.replyToGroups(this)
        replyPreview.text = Prefs.replyText(this)
        cooldownPreview.text = getString(R.string.cooldown_value, Prefs.cooldownMinutes(this))
        findViewById<TextView>(R.id.server_status).text =
            if (Prefs.serverConfigured(this)) getString(R.string.server_status_connected)
            else getString(R.string.server_status_off)
        findViewById<Button>(R.id.onboarding_button).text =
            if (Prefs.serverConfigured(this)) getString(R.string.server_setup_chat)
            else getString(R.string.server_setup_chat_new)
        logAdapter.reload()
        findViewById<View>(R.id.log_empty).visibility =
            if (logAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun hasNotificationAccess(): Boolean {
        val cn = ComponentName(this, AgenteNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.split(":")?.any {
            ComponentName.unflattenFromString(it) == cn
        } == true
    }

    private fun promptForAccess() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_explainer)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editServerUrl() {
        val input = EditText(this).apply { setText(Prefs.serverUrl(this@MainActivity)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_url_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (Prefs.setServerUrl(this, input.text.toString())) {
                    refresh()
                } else {
                    Toast.makeText(this, R.string.server_url_must_be_https, Toast.LENGTH_LONG)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editReplyText() {
        val input = EditText(this).apply {
            setText(Prefs.replyText(this@MainActivity))
            minLines = 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reply_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) Prefs.setReplyText(this, t)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editCooldown() {
        val input = EditText(this).apply {
            setText(Prefs.cooldownMinutes(this@MainActivity).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cooldown_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let {
                    Prefs.setCooldownMinutes(this, it.coerceIn(0, 24 * 60))
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildAppToggles() {
        appTogglesContainer.removeAllViews()
        // Installed apps first — those are the ones the user came to configure.
        // Uninstalled ones stay visible (so the catalog is discoverable) but
        // inert: a toggle for an app that can't produce notifications is a lie.
        val (installed, missing) = SupportedApps.ALL.partition { isInstalled(it.packageName) }
        (installed + missing).forEach { app ->
            val here = isInstalled(app.packageName)
            val sw = SwitchMaterial(this).apply {
                text = if (here) app.displayName
                       else getString(R.string.app_not_installed, app.displayName)
                isEnabled = here
                isChecked = here && Prefs.isAppEnabled(this@MainActivity, app.packageName)
                alpha = if (here) 1f else 0.5f
                setOnCheckedChangeListener { _, on ->
                    Prefs.setAppEnabled(this@MainActivity, app.packageName, on)
                }
                setPadding(0, 8, 0, 8)
            }
            appTogglesContainer.addView(sw)
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    // ------------------------------------------------------------ log adapter

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
        private var events: List<ReplyEvent> = emptyList()

        fun reload() {
            events = ReplyLog.load(this@MainActivity)
            notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val line1: TextView = v.findViewById(R.id.log_line1)
            val line2: TextView = v.findViewById(R.id.log_line2)
            val line3: TextView = v.findViewById(R.id.log_line3)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = events.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = events[position]
            val time = DateFormat.format("MMM d, HH:mm", e.timestamp)
            val status = when {
                e.detail.startsWith("💰") -> e.detail
                e.replySent -> getString(R.string.log_replied)
                else -> "– ${e.detail}"
            }
            holder.line1.text = "${e.appName} · ${e.sender} · $time"
            holder.line2.text = e.incomingText
            holder.line3.text = status
        }
    }
}
