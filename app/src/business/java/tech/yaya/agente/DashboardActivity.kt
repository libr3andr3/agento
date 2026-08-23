package tech.yaya.agente

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Business home screen: live agent status, earnings, agenda, conversations. */
class DashboardActivity : AppCompatActivity() {

    private lateinit var businessName: TextView
    private lateinit var statusChip: Chip
    private lateinit var agentSwitch: MaterialSwitch
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
        // "Habla con tu negocio": the onboarding chat lives on after setup as
        // the owner's management console — same voice, camera and text, now
        // wired to the server's manager agent.
        findViewById<TextView>(R.id.dash_chat).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
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
        refreshUpdateBanner()
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
                    Uri.parse("package:$packageName")
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

    /** Last plan/usage payload from the server; null until the first good fetch. */
    private var plan: JSONObject? = null

    /** Tint an informational chip from color tokens. */
    private fun styleChip(chip: Chip, bgRes: Int, fgRes: Int) {
        chip.chipBackgroundColor = ColorStateList.valueOf(getColor(bgRes))
        chip.setTextColor(getColor(fgRes))
    }

    /**
     * Self-hosted update banner: cached manifest renders instantly, a stale
     * one refreshes off-thread and re-renders. Below minVersionCode the
     * "Luego" button disappears — the server will stop talking to this build.
     */
    private fun refreshUpdateBanner() {
        bindUpdateBanner(UpdateCheck.available(this, allowNetwork = false))
        if (UpdateCheck.stale(this)) {
            ServerClient.IO_EXECUTOR.execute {
                val u = UpdateCheck.available(this, allowNetwork = true)
                runOnUiThread { if (!isFinishing) bindUpdateBanner(u) }
            }
        }
    }

    private fun bindUpdateBanner(u: UpdateCheck.Update?) {
        val banner = findViewById<MaterialCardView>(R.id.update_banner)
        if (u == null || (UpdateCheck.dismissed(this, u) && !u.mandatoryFor(UpdateCheck.installedVersionCode(this)))) {
            banner.visibility = View.GONE
            return
        }
        val mandatory = u.mandatoryFor(UpdateCheck.installedVersionCode(this))
        banner.visibility = View.VISIBLE
        banner.setCardBackgroundColor(getColor(
            if (mandatory) R.color.agento_error_container else R.color.agento_secondary_container))
        val tint = getColor(if (mandatory) R.color.agento_error else R.color.agento_on_secondary_container)
        val title = findViewById<TextView>(R.id.update_banner_title)
        val notes = findViewById<TextView>(R.id.update_banner_notes)
        title.setTextColor(tint); notes.setTextColor(tint)
        title.text = getString(if (mandatory) R.string.update_required_title else R.string.update_available_title, u.version)
        val size = if (u.sizeMb > 0) " · " + getString(R.string.update_size, String.format(Locale.US, "%.1f", u.sizeMb)) else ""
        notes.text = (if (mandatory) getString(R.string.update_required_body) else (u.notes ?: getString(R.string.update_tap_to_install))) + size
        val later = findViewById<View>(R.id.update_later_button)
        later.visibility = if (mandatory) View.GONE else View.VISIBLE
        later.setOnClickListener { UpdateCheck.dismiss(this, u); banner.visibility = View.GONE }
        findViewById<View>(R.id.update_button).setOnClickListener {
            if (!UpdateCheck.canInstall(this)) {
                android.widget.Toast.makeText(this, R.string.update_perm_needed, android.widget.Toast.LENGTH_LONG).show()
                UpdateCheck.openInstallPermission(this)
                return@setOnClickListener
            }
            if (UpdateCheck.download(this, u)) {
                android.widget.Toast.makeText(this, R.string.update_started, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Plan banner: always visible. Free tier shows today's usage against the
     * caps with the upgrade button; a hit cap turns it red. The single
     * button everywhere is "raise limits + web dashboard" → WhatsApp to sales.
     */
    private fun refreshPlanBanner() {
        val p = plan
        val banner = findViewById<MaterialCardView>(R.id.trial_banner)
        if (p == null) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        val text = findViewById<TextView>(R.id.trial_banner_text)
        val name = p.optString("name", "free")
        val mu = p.optInt("messagesUsed"); val mc = p.optInt("messagesCap")
        val cu = p.optInt("customersUsed"); val cc = p.optInt("customersCap")
        val limit = p.optBoolean("limitReached", false)
        val full = p.optBoolean("customersFull", false)
        when {
            limit -> {
                text.text = getString(R.string.plan_banner_limit)
                text.setTextColor(getColor(R.color.agento_error))
                banner.setCardBackgroundColor(getColor(R.color.agento_error_container))
            }
            full -> {
                text.text = getString(R.string.plan_banner_customers_full, cc)
                text.setTextColor(getColor(R.color.agento_error))
                banner.setCardBackgroundColor(getColor(R.color.agento_error_container))
            }
            name == "free" -> {
                text.text = getString(R.string.plan_banner_usage, mu, mc, cu, cc)
                text.setTextColor(getColor(R.color.agento_on_secondary_container))
                banner.setCardBackgroundColor(getColor(R.color.agento_secondary_container))
            }
            else -> {
                text.text = getString(R.string.plan_banner_paid, name.replaceFirstChar { it.uppercase() }, mu, cu)
                text.setTextColor(getColor(R.color.agento_on_secondary_container))
                banner.setCardBackgroundColor(getColor(R.color.agento_secondary_container))
            }
        }
        // Upgrading is a plan screen now (Pro / Max, paid by Yape/Plin);
        // the WhatsApp-to-sales link lives inside it.
        val button = findViewById<View>(R.id.sales_button)
        button.visibility = View.VISIBLE
        button.setOnClickListener { startActivity(Intent(this, PlanActivity::class.java)) }
    }

    /** The truth chip: is the agent actually able to answer right now? */
    private fun refreshStatus(fetchOk: Boolean) {
        val alive = hasNotificationAccess() && Prefs.isEnabled(this)
        agentSwitch.isChecked = alive
        offBanner.visibility = if (alive) View.GONE else View.VISIBLE
        refreshPlanBanner()
        val p = plan
        when {
            !alive -> {
                statusChip.text = getString(R.string.status_off_banner)
                styleChip(statusChip, R.color.agento_error_container, R.color.agento_error)
            }
            !fetchOk -> {
                statusChip.text = getString(R.string.dash_offline_chip)
                styleChip(statusChip, R.color.agento_secondary_container,
                    R.color.agento_on_secondary_container)
            }
            // The server has stopped answering customers; being "active" here
            // would be a lie the owner discovers from an angry customer.
            p != null && p.optBoolean("limitReached", false) -> {
                statusChip.text = getString(R.string.status_limit_reached)
                styleChip(statusChip, R.color.agento_error_container, R.color.agento_error)
            }
            else -> {
                val lastReply = ReplyLog.load(this).firstOrNull { it.replySent }
                val base = if (lastReply != null) getString(
                    R.string.status_active_last,
                    DateUtils.getRelativeTimeSpanString(lastReply.timestamp).toString()
                ) else getString(R.string.status_active)
                val cap = p?.optInt("messagesCap") ?: 0
                statusChip.text = if (cap > 0)
                    getString(R.string.status_plan_usage, base, p!!.optInt("messagesUsed"), cap) else base
                styleChip(statusChip, R.color.agento_primary_container,
                    R.color.agento_on_primary_container)
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
                    ServerClient.IO_EXECUTOR.execute { runCatching { Prefs.refreshLearnedSources(applicationContext) } }
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

    private fun soles(v: Double): String = Prefs.money(this, v)

    private fun inflateIn(layoutRes: Int, parent: ViewGroup): View =
        layoutInflater.inflate(layoutRes, parent, false)

    /** Teaching empty state ("what will appear here, and how to make it happen"). */
    private fun emptyState(parent: ViewGroup, msg: String): View =
        inflateIn(R.layout.item_dash_empty, parent).apply {
            findViewById<TextView>(R.id.dash_empty_text).text = msg
        }

    private fun render(d: JSONObject) {
        d.optJSONObject("locale")?.let { Prefs.setLocale(this, it) }
        businessName.text = d.optString("businessName", getString(R.string.app_name))
        plan = d.optJSONObject("plan")
        val e = d.optJSONObject("earnings") ?: JSONObject()
        earnToday.text = soles(e.optDouble("today", 0.0))
        earnWeek.text = soles(e.optDouble("week", 0.0))
        earnMonth.text = soles(e.optDouble("month", 0.0))
        renderGaps(d.optJSONArray("openGaps"))
        // Orders render before the appointments early-return below: a
        // products-only business has an empty agenda every single day.
        renderOrders(d.optJSONArray("orders"))

        agenda.removeAllViews()
        val appts = d.optJSONArray("appointments")
        if (appts == null || appts.length() == 0) {
            agenda.addView(emptyState(agenda, getString(R.string.dash_empty)))
            return
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        var lastDate = ""
        for (i in 0 until appts.length()) {
            val a = appts.getJSONObject(i)
            val date = a.optString("date")
            if (date != lastDate) {
                lastDate = date
                agenda.addView(dayChip(date, date == today))
            }
            agenda.addView(appointmentCard(a))
        }
    }

    /** Day header pill; "Hoy" gets the primary container so it pops. */
    private fun dayChip(date: String, isToday: Boolean): View {
        val chip = inflateIn(R.layout.item_dash_day, agenda) as Chip
        chip.text = if (isToday) getString(R.string.dash_today_header, prettyDate(date))
                    else prettyDate(date)
        if (isToday) styleChip(chip, R.color.agento_primary_container,
            R.color.agento_on_primary_container)
        else styleChip(chip, R.color.agento_surface_variant, R.color.agento_on_surface)
        return chip
    }

    /** Product orders (last 14 days). */
    private fun renderOrders(arr: org.json.JSONArray?) {
        val container = findViewById<LinearLayout>(R.id.orders_container)
        container.removeAllViews()
        if (arr == null || arr.length() == 0) {
            container.addView(emptyState(container, getString(R.string.dash_orders_empty)))
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val items = o.optJSONArray("items")
            val summary = (0 until (items?.length() ?: 0)).joinToString(", ") { j ->
                val it = items!!.getJSONObject(j)
                "${it.optInt("qty", 1)}× ${it.optString("product")}"
            }
            val paid = o.optBoolean("paid")
            val card = inflateIn(R.layout.item_dash_order, container)
            card.findViewById<TextView>(R.id.dash_order_customer).text = o.optString("customer")
            card.findViewById<TextView>(R.id.dash_order_items).text = summary
            card.findViewById<TextView>(R.id.dash_order_total).text =
                soles(o.optDouble("total", 0.0))
            val state = card.findViewById<Chip>(R.id.dash_order_state)
            if (paid) {
                state.text = getString(R.string.order_paid)
                styleChip(state, R.color.agento_primary_container,
                    R.color.agento_on_primary_container)
            } else {
                state.text = getString(R.string.order_pending)
                styleChip(state, R.color.agento_secondary_container,
                    R.color.agento_on_secondary_container)
            }
            container.addView(card)
        }
    }

    /**
     * Questions customers asked that the agent couldn't answer. One tap +
     * one sentence from the owner and the agent knows it forever.
     */
    private fun renderGaps(arr: org.json.JSONArray?) {
        gaps.removeAllViews()
        val n = arr?.length() ?: 0
        gapsHeader.visibility = View.VISIBLE
        if (n == 0) {
            gaps.addView(emptyState(gaps, getString(R.string.dash_gaps_empty)))
            return
        }
        for (i in 0 until n) {
            val g = arr!!.getJSONObject(i)
            val card = inflateIn(R.layout.item_dash_gap, gaps)
            card.findViewById<TextView>(R.id.dash_gap_from).text =
                getString(R.string.gap_from, g.optString("customer"))
            card.findViewById<TextView>(R.id.dash_gap_question).text = g.optString("question")
            card.findViewById<MaterialButton>(R.id.dash_gap_answer).setOnClickListener {
                askGapAnswer(g.optString("id"), g.optString("question"))
            }
            gaps.addView(card)
        }
    }

    private fun askGapAnswer(gapId: String, question: String) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.gap_answer_hint)
            minLines = 2
        }
        val pad = resources.getDimensionPixelSize(R.dimen.space_l)
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
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
            convos.addView(emptyState(convos, getString(R.string.dash_convos_empty)))
            return
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        events.forEach { e ->
            val payment = e.detail.startsWith("💰")
            val card = inflateIn(R.layout.item_dash_convo, convos) as MaterialCardView
            if (payment) {
                card.setCardBackgroundColor(getColor(R.color.agento_primary_container))
                card.strokeWidth = 0
            }
            card.findViewById<TextView>(R.id.dash_convo_meta).apply {
                text = "${e.sender} · ${e.appName} · ${time.format(e.timestamp)}"
                if (payment) setTextColor(getColor(R.color.agento_on_primary_container))
            }
            card.findViewById<TextView>(R.id.dash_convo_incoming).apply {
                text = e.incomingText
                if (payment) setTextColor(getColor(R.color.agento_on_primary_container))
            }
            card.findViewById<TextView>(R.id.dash_convo_detail).apply {
                text = when {
                    payment -> e.detail
                    e.replySent -> "↩ ${e.detail}"
                    else -> e.detail
                }
                setTextColor(getColor(when {
                    payment -> R.color.agento_on_primary_container
                    e.replySent -> R.color.agento_primary
                    else -> R.color.agento_on_surface_muted
                }))
            }
            convos.addView(card)
        }
    }

    private fun prettyDate(iso: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d)
    } catch (_: Exception) { iso }

    private fun appointmentCard(a: JSONObject): View {
        val card = inflateIn(R.layout.item_dash_appointment, agenda)
        card.findViewById<TextView>(R.id.dash_appt_time).text = a.optString("time")
        card.findViewById<TextView>(R.id.dash_appt_customer).text = a.optString("customer")
        val spec = a.optString("specialist").takeIf { it.isNotEmpty() && it != "null" }
        card.findViewById<TextView>(R.id.dash_appt_sub).text =
            spec ?: a.optString("phone").substringAfter(':')
        val remEmail = a.optString("reminderEmail").takeIf { it.isNotEmpty() && it != "null" }
        card.findViewById<TextView>(R.id.dash_appt_reminder).apply {
            if (remEmail != null) {
                visibility = View.VISIBLE
                text = getString(R.string.dash_reminder_set, a.optInt("remindMinutes", 60))
            } else visibility = View.GONE
        }
        val price = a.optDouble("price", Double.NaN)
        card.findViewById<TextView>(R.id.dash_appt_price).text =
            if (price.isNaN()) "—" else soles(price)
        val paidChip = card.findViewById<Chip>(R.id.dash_appt_paid)
        if (a.optBoolean("paid")) {
            paidChip.text = getString(R.string.dash_paid)
            styleChip(paidChip, R.color.agento_primary_container,
                R.color.agento_on_primary_container)
        } else {
            paidChip.text = getString(R.string.dash_pending)
            styleChip(paidChip, R.color.agento_secondary_container,
                R.color.agento_on_secondary_container)
        }
        return card
    }
}
