package tech.yaya.agente

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Voice-first chat with the setup agent. Registers the business on first run,
 * then interviews the owner; ends with the "¡Todo listo!" sheet which also
 * gates on Notification Access so the agent can never finish setup dead.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var chatLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var micButton: Button
    private lateinit var replayButton: Button

    private val blocks = mutableListOf<String>()
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recordStart = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private var pendingDone = false
    private val voiceFile: File by lazy { File(cacheDir, "voice.m4a") }
    private val replyWav: File by lazy { File(cacheDir, "reply.wav") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        chatLog = findViewById(R.id.chat_log)
        scroll = findViewById(R.id.chat_scroll)
        input = findViewById(R.id.chat_input)
        sendButton = findViewById(R.id.chat_send)
        micButton = findViewById(R.id.chat_mic)
        replayButton = findViewById(R.id.chat_replay)

        sendButton.setOnClickListener { send() }
        micButton.setOnClickListener { toggleRecording() }
        replayButton.setOnClickListener { replayLast() }

        // Restore the transcript so an interrupted owner never sees a blank chat.
        Prefs.chatTranscript(this)?.let {
            blocks.addAll(it.split("\n\n"))
            renderBlocks()
        }

        if (!Prefs.serverConfigured(this)) {
            if (blocks.isEmpty()) showRegistrationDialog()
        } else if (blocks.isEmpty()) {
            append(getString(R.string.onboarding_resume_hint))
        }
    }

    // ------------------------------------------------------------ registration

    private fun showRegistrationDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val name = EditText(this).apply { hint = getString(R.string.reg_business_name) }
        val industry = EditText(this).apply { hint = getString(R.string.reg_industry) }
        val phone = EditText(this).apply {
            hint = getString(R.string.reg_owner_phone)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText("+51 ")
        }
        container.addView(name); container.addView(industry); container.addView(phone)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reg_title)
            .setMessage(R.string.reg_message)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.reg_start, null) // validated below, no dismiss
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .create()
        dialog.show()
        // Validate in place so typed values survive a validation error.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var ok = true
            if (name.text.isBlank()) { name.error = getString(R.string.reg_error_empty); ok = false }
            if (industry.text.isBlank()) { industry.error = getString(R.string.reg_error_empty); ok = false }
            if (!ok) return@setOnClickListener
            dialog.dismiss()
            register(name.text.toString().trim(), industry.text.toString().trim(),
                     phone.text.toString().trim())
        }
    }

    private fun register(name: String, industry: String, phone: String) {
        setBusy(true)
        append("⏳ ${getString(R.string.reg_registering)}")
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.onboardBusiness(this, name, industry, phone)
            runOnUiThread {
                setBusy(false)
                if (resp == null) {
                    replaceLast("⚠️ ${getString(R.string.server_error)}")
                    return@runOnUiThread
                }
                Prefs.setDeviceToken(this, resp.optString("deviceToken"))
                Prefs.setBusinessId(this, resp.optString("businessId"))
                replaceLast("🟢 " + resp.optString("conversationStarterMessage"))
                playIfPresent(resp)
            }
        }
    }

    // ------------------------------------------------------------------- text

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        append("🧑 $text")
        append(getString(R.string.typing_indicator))
        setBusy(true)
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.onboardingMessage(this, text)
            runOnUiThread {
                // Only clear the box once the message actually made it through.
                if (resp != null) input.setText("")
                handleAgentResponse(resp)
            }
        }
    }

    // ------------------------------------------------------------- voice mode

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecordingAndSend()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 71)
            return
        }
        try {
            player?.release(); player = null
            @Suppress("DEPRECATION")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(voiceFile.absolutePath)
                prepare()
                start()
            }
            recordStart = System.currentTimeMillis()
            tickTimer()
            sendButton.isEnabled = false
        } catch (e: Exception) {
            recorder = null
            Toast.makeText(this, getString(R.string.mic_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun tickTimer() {
        val r = recorder ?: return
        val secs = (System.currentTimeMillis() - recordStart) / 1000
        micButton.text = getString(
            R.string.voice_stop, String.format("%d:%02d", secs / 60, secs % 60)
        )
        timerHandler.postDelayed({ tickTimer() }, 500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 71) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toggleRecording()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mic_perm_title)
                .setMessage(R.string.mic_perm_msg)
                .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                               Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun stopRecordingAndSend() {
        timerHandler.removeCallbacksAndMessages(null)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        micButton.text = getString(R.string.voice_mic)
        sendButton.isEnabled = true
        if (!voiceFile.exists() || voiceFile.length() < 1000) {
            Toast.makeText(this, getString(R.string.voice_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        append("🧑 🎤 …")
        append(getString(R.string.typing_indicator))
        setBusy(true)
        micButton.text = getString(R.string.voice_sending)
        val bytes = voiceFile.readBytes()
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.voiceMessage(this, bytes)
            runOnUiThread {
                micButton.text = getString(R.string.voice_mic)
                resp?.optString("transcript")?.takeIf { it.isNotEmpty() }?.let {
                    // blocks: [.., "🧑 🎤 …", typing]; fix the transcript line.
                    if (blocks.size >= 2) blocks[blocks.size - 2] = "🧑 🎤 $it"
                }
                handleAgentResponse(resp)
            }
        }
    }

    private fun replayLast() {
        if (!replyWav.exists()) return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(replyWav.absolutePath); prepare(); start()
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.audio_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun playIfPresent(resp: org.json.JSONObject) {
        val b64 = resp.optString("audioBase64").takeIf { it.isNotEmpty() && it != "null" } ?: return
        try {
            replyWav.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            replayButton.visibility = View.VISIBLE
            replayLast()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.audio_error), Toast.LENGTH_SHORT).show()
        }
    }

    // ----------------------------------------------------------------- shared

    private fun handleAgentResponse(resp: org.json.JSONObject?) {
        setBusy(false)
        if (resp == null) {
            replaceLast("⚠️ ${getString(R.string.server_error)}")
            return
        }
        replaceLast("🟢 " + resp.optString("agentResponse"))
        playIfPresent(resp)
        if (resp.optString("action") == "finish_onboarding") {
            append("✅ ${getString(R.string.onboarding_saved)}")
            showDoneSheet()
        }
    }

    // ------------------------------------------------- done sheet + permission

    private fun hasNotificationAccess(): Boolean {
        val cn = ComponentName(this, AgenteNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.split(":")?.any { ComponentName.unflattenFromString(it) == cn } == true
    }

    private fun isBatteryExempt(): Boolean =
        (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

    private var batteryAsked = false

    /** System dialog: "Allow agente to always run in background?" */
    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        batteryAsked = true
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                       Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // Some OEM ROMs hide this screen; nothing more we can do here.
        }
    }

    /** Celebration sheet: voice keeps reading; the tap gates on permission. */
    private fun showDoneSheet() {
        val sheet = findViewById<View>(R.id.done_sheet)
        val arrow = findViewById<TextView>(R.id.done_arrow)
        sheet.visibility = View.VISIBLE
        sheet.translationY = 500f
        sheet.animate().translationY(0f).setDuration(380)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        val pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            arrow,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f)
        ).apply {
            duration = 550
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            start()
        }
        sheet.setOnClickListener {
            when {
                !hasNotificationAccess() -> {
                    pendingDone = true
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.permission_title)
                        .setMessage(R.string.permission_explainer)
                        .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                !isBatteryExempt() && !batteryAsked -> {
                    pendingDone = true
                    requestBatteryExemption()
                }
                else -> {
                    pulse.cancel()
                    goToDashboard()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a permission screen: chain the next step or finish setup.
        if (!pendingDone) return
        if (!hasNotificationAccess()) return // still waiting on the user
        Prefs.setEnabled(this, true)
        if (!isBatteryExempt() && !batteryAsked) {
            requestBatteryExemption()
            return
        }
        // Battery exemption is best-effort: proceed even if declined.
        goToDashboard()
    }

    private fun goToDashboard() {
        // Setup is complete: the agent must actually be on.
        if (hasNotificationAccess()) Prefs.setEnabled(this, true)
        player?.release(); player = null
        startActivity(Intent(this, DashboardActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    // ------------------------------------------------------------------ chat UI

    private fun append(line: String) {
        blocks.add(line)
        renderBlocks()
    }

    private fun replaceLast(newLine: String) {
        // The last block is the typing indicator / placeholder being resolved.
        if (blocks.isNotEmpty()) blocks[blocks.size - 1] = newLine else blocks.add(newLine)
        renderBlocks()
    }

    private fun renderBlocks() {
        chatLog.text = blocks.joinToString("\n\n")
        Prefs.setChatTranscript(this, chatLog.text.toString())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        input.isEnabled = !busy
        micButton.isEnabled = !busy || recorder != null
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacksAndMessages(null)
        recorder?.release()
        player?.release()
    }
}
