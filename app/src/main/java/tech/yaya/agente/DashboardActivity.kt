package tech.yaya.agente

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Business home screen: live agent status, earnings, agenda, conversations. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var businessName: TextView
    private lateinit var statusChip: TextView
    private lateinit var agentSwitch: SwitchMaterial
    private lateinit var offBanner: View
    private lateinit var earnToday: TextView
    private lateinit var earnWeek: TextView
    private lateinit var earnMonth: TextView
    private lateinit var agenda: LinearLayout
    private lateinit var convos: LinearLayout
    private lateinit var gapsHeader: TextView
    private lateinit var gaps: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        businessName = findViewById(R.id.dash_business_name)
        statusChip = findViewById(R.id.dash_status)
        agentSwitch = findViewById(R.id.dash_agent_switch)
        offBanner = findViewById(R.id.off_banner)
        earnToday = findViewById(R.id.earn_today)
        earnWeek = findViewById(R.id.earn_week)
        earnMonth = findViewById(R.id.earn_month)
        agenda = findViewById(R.id.agenda_container)
        convos = findViewById(R.id.convos_container)
        gapsHeader = findViewById(R.id.gaps_header)
        gaps = findViewById(R.id.gaps_container)
        swipe = findViewById(R.id.dash_swipe)

        findViewById<ImageButton>(R.id.dash_settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        findViewById<View>(R.id.off_banner_button).setOnClickListener { activateAgent() }
        agentSwitch.setOnCheckedChangeListener { btn, on ->
            if (!btn.isPressed) return@setOnCheckedChangeListener
            if (on && !hasNotificationAccess()) {
                agentSwitch.isChecked = false
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } else {
                Prefs.setEnabled(this, on)
                refreshStatus(lastFetchOk)
            }
        }
        swipe.setOnRefreshListener { load() }

        // Render cache instantly so the screen never opens blank.
        Prefs.dashboardCache(this)?.let { runCatching { render(JSONObject(it)) } }
    }

    private var lastFetchOk = true

    override fun onResume() {
        super.onResume()
        refreshStatus(lastFetchOk)
        load()
        maybeAskBatteryExemption()
        maybeAskNotifPermission()
    }

    /** Android 13+: owner alerts (customer needs you) require this grant. */
    private fun maybeAskNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33 || notifAskedThisSession) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        notifAskedThisSession = true
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    /**
     * Xiaomi/Huawei-class ROMs kill background listeners under battery
     * optimization; ask once per session for the exemption so the agent
     * survives overnight.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun maybeAskBatteryExemption() {
        if (batteryAskedThisSession) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (!hasNotificationAccess() || !Prefs.isEnabled(this)) return
        batteryAskedThisSession = true
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        private var batteryAskedThisSession = false
        private var notifAskedThisSession = false
    }

    private fun hasNotificationAccess(): Boolean {
        val cn = ComponentName(this, AgenteNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.split(":")?.any { ComponentName.unflattenFromString(it) == cn } == true
    }

    private fun activateAgent() {
        if (!hasNotificationAccess()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        Prefs.setEnabled(this, true)
        refreshStatus(lastFetchOk)
    }

    /** The truth row: is the agent actually able to answer right now? */
    private fun refreshStatus(fetchOk: Boolean) {
        val alive = hasNotificationAccess() && Prefs.isEnabled(this)
        agentSwitch.isChecked = alive
        offBanner.visibility = if (alive) View.GONE else View.VISIBLE
        when {
            !alive -> {
                statusChip.text = getString(R.string.status_off_banner)
                statusChip.setTextColor(0xFFB3261E.toInt())
            }
            !fetchOk -> {
                statusChip.text = getString(R.string.status_offline)
                statusChip.setTextColor(0xFFB26A00.toInt())
            }
            else -> {
                val lastReply = ReplyLog.load(this).firstOrNull { it.replySent }
                statusChip.text = if (lastReply != null) getString(
                    R.string.status_active_last,
                    DateUtils.getRelativeTimeSpanString(lastReply.timestamp).toString()
                ) else getString(R.string.status_active)
                statusChip.setTextColor(0xFF1B5E20.toInt())
            }
        }
    }

    private fun load() {
        swipe.isRefreshing = true
        ServerClient.IO_EXECUTOR.execute {
            val data = ServerClient.dashboard(this)
            runOnUiThread {
                swipe.isRefreshing = false
                lastFetchOk = data != null
                if (data != null) {
                    Prefs.setDashboardCache(this, data.toString())
                    render(data)
                } else {
                    // Keep identity + cached content; the chip explains.
                    Prefs.dashboardCache(this)?.let { runCatching { render(JSONObject(it)) } }
                }
                refreshStatus(lastFetchOk)
                renderConversations()
            }
        }
    }

    private fun soles(v: Double): String =
        if (v == v.toLong().toDouble()) "S/ ${v.toLong()}"
        else String.format(Locale.US, "S/ %.2f", v)

    private fun render(d: JSONObject) {
        businessName.text = d.optString("businessName", getString(R.string.app_name))
        val e = d.optJSONObject("earnings") ?: JSONObject()
        earnToday.text = soles(e.optDouble("today", 0.0))
        earnWeek.text = soles(e.optDouble("week", 0.0))
        earnMonth.text = soles(e.optDouble("month", 0.0))
        renderGaps(d.optJSONArray("openGaps"))

        agenda.removeAllViews()
        val appts = d.optJSONArray("appointments")
        if (appts == null || appts.length() == 0) {
            agenda.addView(emptyText(getString(R.string.dash_empty)))
            return
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        var lastDate = ""
        for (i in 0 until appts.length()) {
            val a = appts.getJSONObject(i)
            val date = a.optString("date")
            if (date != lastDate) {
                lastDate = date
                agenda.addView(TextView(this).apply {
                    text = if (date == today)
                        getString(R.string.dash_today_header, prettyDate(date))
                    else prettyDate(date)
                    setTypeface(null, Typeface.BOLD)
                    textSize = 15f
                    setPadding(4, 28, 0, 8)
                })
            }
            agenda.addView(appointmentRow(a))
        }
    }

    /**
     * Questions customers asked that the agent couldn't answer. One tap +
     * one sentence from the owner and the agent knows it forever.
     */
    private fun renderGaps(arr: org.json.JSONArray?) {
        gaps.removeAllViews()
        val n = arr?.length() ?: 0
        gapsHeader.visibility = if (n == 0) View.GONE else View.VISIBLE
        if (arr == null) return
        for (i in 0 until n) {
            val g = arr.getJSONObject(i)
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 28f
                cardElevation = 0f
                setCardBackgroundColor(0x1AFF9800)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 14 }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 24, 36, 24)
            }
            col.addView(TextView(this).apply {
                text = getString(R.string.gap_from, g.optString("customer"))
                textSize = 12f
                alpha = 0.7f
            })
            col.addView(TextView(this).apply {
                text = g.optString("question")
                setTypeface(null, Typeface.BOLD)
                textSize = 15f
            })
            col.addView(
                com.google.android.material.button.MaterialButton(
                    this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = getString(R.string.gap_answer_button)
                    setTextColor(0xFFB26A00.toInt())
                    strokeColor = android.content.res.ColorStateList.valueOf(0xFFB26A00.toInt())
                    setOnClickListener { askGapAnswer(g.optString("id"), g.optString("question")) }
                }
            )
            card.addView(col)
            gaps.addView(card)
        }
    }

    private fun askGapAnswer(gapId: String, question: String) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.gap_answer_hint)
            minLines = 2
        }
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(56, 24, 56, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(question)
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.gap_answer_send) { _, _ ->
                val answer = input.text.toString().trim()
                if (answer.isEmpty()) return@setPositiveButton
                ServerClient.IO_EXECUTOR.execute {
                    val res = ServerClient.answerGap(this, gapId, answer)
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this,
                            if (res != null) getString(R.string.gap_answered)
                            else getString(R.string.gap_answer_failed),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        if (res != null) load()
                    }
                }
            }
            .show()
    }

    private fun renderConversations() {
        convos.removeAllViews()
        val events = ReplyLog.load(this).take(8)
        if (events.isEmpty()) {
            convos.addView(emptyText(getString(R.string.dash_convos_empty)))
            return
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        events.forEach { e ->
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 28f
                cardElevation = 0f
                setCardBackgroundColor(
                    if (e.detail.startsWith("💰")) 0x146A1B9A else 0x0D000000
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 14 }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 24, 36, 24)
            }
            col.addView(TextView(this).apply {
                text = "${e.sender} · ${e.appName} · ${time.format(e.timestamp)}"
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
            })
            col.addView(TextView(this).apply {
                text = e.incomingText
                textSize = 14f
                maxLines = 2
            })
            col.addView(TextView(this).apply {
                text = when {
                    e.detail.startsWith("💰") -> e.detail
                    e.replySent -> "↩ ${e.detail}"
                    else -> e.detail
                }
                textSize = 13f
                maxLines = 2
                setTextColor(
                    when {
                        e.detail.startsWith("💰") -> 0xFF6A1B9A.toInt()
                        e.replySent -> 0xFF1B5E20.toInt()
                        else -> 0xFF5F6368.toInt()
                    }
                )
            })
            card.addView(col)
            convos.addView(card)
        }
    }

    private fun emptyText(msg: String) = TextView(this).apply {
        text = msg
        textSize = 14f
        setPadding(4, 20, 4, 8)
        gravity = Gravity.CENTER_HORIZONTAL
        alpha = 0.75f
    }

    private fun prettyDate(iso: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d)
    } catch (_: Exception) { iso }

    private fun appointmentRow(a: JSONObject): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 28f
            cardElevation = 0f
            setCardBackgroundColor(0x14208030)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16 }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(36, 28, 36, 28)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = a.optString("time")
            setTypeface(null, Typeface.BOLD)
            textSize = 17f
        })
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginStart = 36 }
        }
        mid.addView(TextView(this).apply {
            text = a.optString("customer")
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        })
        val spec = a.optString("specialist").takeIf { it.isNotEmpty() && it != "null" }
        mid.addView(TextView(this).apply {
            text = spec ?: a.optString("phone").substringAfter(':')
            textSize = 14f
            alpha = 0.7f
        })
        val remEmail = a.optString("reminderEmail").takeIf { it.isNotEmpty() && it != "null" }
        if (remEmail != null) {
            mid.addView(TextView(this).apply {
                text = getString(R.string.dash_reminder_set, a.optInt("remindMinutes", 60))
                textSize = 12f
                alpha = 0.7f
            })
        }
        row.addView(mid)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val price = a.optDouble("price", Double.NaN)
        right.addView(TextView(this).apply {
            text = if (price.isNaN()) "—" else soles(price)
            setTypeface(null, Typeface.BOLD)
            textSize = 15f
        })
        right.addView(TextView(this).apply {
            text = if (a.optBoolean("paid")) getString(R.string.dash_paid)
            else getString(R.string.dash_pending)
            textSize = 12f
            setTextColor(if (a.optBoolean("paid")) 0xFF1B5E20.toInt() else 0xFFB26A00.toInt())
        })
        row.addView(right)
        card.addView(row)
        return card
    }
}
