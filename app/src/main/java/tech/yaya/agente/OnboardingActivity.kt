package tech.yaya.agente

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Voice-first chat with the setup agent. Registers the business on first run,
 * then interviews the owner; ends with the "¡Todo listo!" sheet which also
 * gates on Notification Access so the agent can never finish setup dead.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val RC_CATALOG_CAMERA = 72
        private const val RC_CATALOG_GALLERY = 73
        /** Longest edge for uploaded catalog photos. */
        private const val MAX_PHOTO_EDGE = 1600
    }

    private lateinit var chatLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var micButton: Button
    private lateinit var cameraButton: Button
    private lateinit var replayButton: Button

    private val blocks = mutableListOf<String>()
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recordStart = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private var pendingDone = false
    private val voiceFile: File by lazy { File(cacheDir, "voice.m4a") }
    private val replyWav: File by lazy { File(cacheDir, "reply.wav") }
    private val photoFile: File by lazy { File(cacheDir, "catalog.jpg") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        chatLog = findViewById(R.id.chat_log)
        scroll = findViewById(R.id.chat_scroll)
        input = findViewById(R.id.chat_input)
        sendButton = findViewById(R.id.chat_send)
        micButton = findViewById(R.id.chat_mic)
        cameraButton = findViewById(R.id.chat_camera)
        replayButton = findViewById(R.id.chat_replay)

        sendButton.setOnClickListener { send() }
        micButton.setOnClickListener { toggleRecording() }
        cameraButton.setOnClickListener { showPhotoSourceDialog() }
        replayButton.setOnClickListener { replayLast() }

        // Photos need a registered business to land on: server-gated like
        // the rest of the chat.
        cameraButton.isEnabled = Prefs.serverConfigured(this)

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
            startVerification(name.text.toString().trim(), industry.text.toString().trim(),
                              phone.text.toString().trim())
        }
    }

    // ------------------------------------------------- phone verification (OTP)

    /**
     * WhatsApps a 6-digit code to the owner's phone before registering. The
     * status code decides the path: 503 means the server isn't enforcing
     * verification, so registration proceeds without it — the flow degrades
     * instead of bricking onboarding when the OTP bridge is down.
     */
    private fun startVerification(name: String, industry: String, phone: String) {
        setBusy(true)
        append("⏳ ${getString(R.string.verify_sending)}")
        ServerClient.EXECUTOR.execute {
            val code = ServerClient.verifyStart(this, phone)
            runOnUiThread {
                setBusy(false)
                when (code) {
                    200 -> {
                        replaceLast("📲 ${getString(R.string.verify_sent, phone)}")
                        showCodeDialog(name, industry, phone)
                    }
                    503 -> {
                        replaceLast("ℹ️ ${getString(R.string.verify_skipped)}")
                        register(name, industry, phone, token = null)
                    }
                    400 -> {
                        replaceLast("⚠️ ${getString(R.string.verify_bad_phone)}")
                        showRegistrationDialog()
                    }
                    429 -> replaceLast("⚠️ ${getString(R.string.verify_throttled)}")
                    else -> replaceLast("⚠️ ${getString(R.string.server_error)}")
                }
            }
        }
    }

    private fun showCodeDialog(name: String, industry: String, phone: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.verify_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.verify_title)
            .setMessage(getString(R.string.verify_message, phone))
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(input)
            })
            .setCancelable(false)
            .setPositiveButton(R.string.verify_confirm, null) // validated below
            .setNeutralButton(R.string.verify_resend) { _, _ ->
                startVerification(name, industry, phone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = input.text.toString().trim()
            if (typed.length != 6) {
                input.error = getString(R.string.verify_code_hint)
                return@setOnClickListener
            }
            setBusy(true)
            ServerClient.EXECUTOR.execute {
                val resp = ServerClient.verifyCheck(this, phone, typed)
                val token = resp?.optString("verificationToken")?.takeIf { it.isNotEmpty() }
                runOnUiThread {
                    setBusy(false)
                    if (token != null) {
                        dialog.dismiss()
                        append("✅ ${getString(R.string.verify_ok)}")
                        register(name, industry, phone, token)
                    } else {
                        input.error = getString(R.string.verify_wrong_code)
                    }
                }
            }
        }
    }

    private fun register(name: String, industry: String, phone: String, token: String?) {
        setBusy(true)
        append("⏳ ${getString(R.string.reg_registering)}")
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.onboardBusiness(this, name, industry, phone, token)
            runOnUiThread {
                setBusy(false)
                if (resp == null) {
                    replaceLast("⚠️ ${getString(R.string.server_error)}")
                    return@runOnUiThread
                }
                Prefs.setDeviceToken(this, resp.optString("deviceToken"))
                Prefs.setBusinessId(this, resp.optString("businessId"))
                // setBusy(false) ran before the token landed — unlock now.
                cameraButton.isEnabled = Prefs.serverConfigured(this)
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

    // -------------------------------------------------------- catalog photo

    /**
     * Owner sends a photo of their catalog/menu; the server extracts items and
     * prices. Camera goes through ACTION_IMAGE_CAPTURE + FileProvider (full
     * size, no CAMERA permission), gallery through ACTION_GET_CONTENT.
     */
    private fun showPhotoSourceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.catalog_photo_title)
            .setItems(
                arrayOf(
                    getString(R.string.catalog_photo_take),
                    getString(R.string.catalog_photo_gallery)
                )
            ) { _, which ->
                if (which == 0) launchCamera() else launchGallery()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchCamera() {
        photoFile.delete()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        // Grant flags only cover data/clipData, not extras — mirror the uri
        // there so every camera app can actually write to it.
        intent.clipData = ClipData.newRawUri("output", uri)
        try {
            startActivityForResult(intent, RC_CATALOG_CAMERA)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.catalog_photo_no_app), Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(intent, RC_CATALOG_GALLERY)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.catalog_photo_no_app), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val raw: ByteArray? = when (requestCode) {
            RC_CATALOG_CAMERA ->
                photoFile.takeIf { it.exists() && it.length() > 0 }?.readBytes()
            RC_CATALOG_GALLERY ->
                data?.data?.let { uri ->
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
            else -> return
        }
        if (raw == null || raw.isEmpty()) {
            Toast.makeText(this, getString(R.string.catalog_photo_unreadable), Toast.LENGTH_LONG).show()
            return
        }
        sendCatalogPhoto(raw)
    }

    private fun sendCatalogPhoto(raw: ByteArray) {
        append("📷 ⏳ ${getString(R.string.catalog_photo_reading)}")
        setBusy(true)
        ServerClient.EXECUTOR.execute {
            val jpeg = prepareCatalogJpeg(raw)
            val resp = jpeg?.let { ServerClient.catalogPhoto(this, it) }
            runOnUiThread {
                setBusy(false)
                photoFile.delete()
                if (resp == null) { // undecodable image, never left the phone
                    replaceLast("⚠️ ${getString(R.string.catalog_photo_unreadable)}")
                    return@runOnUiThread
                }
                handleCatalogResponse(resp)
            }
        }
    }

    /**
     * Downscale so the longest edge is ≤ [MAX_PHOTO_EDGE] px and re-encode as
     * JPEG q85 — menu photos are all the server needs, not 12MP originals.
     * inSampleSize does the cheap power-of-two step, an exact scale finishes,
     * and the EXIF orientation is baked in so the server never sees a
     * sideways menu. Null = not a decodable image.
     */
    private fun prepareCatalogJpeg(raw: ByteArray): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_EDGE) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > MAX_PHOTO_EDGE) {
                val scale = MAX_PHOTO_EDGE.toFloat() / longest
                bmp = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            }
            val rotation = try {
                when (ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        } catch (e: Exception) {
            android.util.Log.w("Onboarding", "catalog photo decode failed", e)
            null
        }
    }

    private fun handleCatalogResponse(resp: ServerClient.Response) {
        when {
            resp.code in 200..299 && resp.json != null -> {
                val items = resp.json.optJSONArray("items")
                val total = items?.length() ?: 0
                val preview = StringBuilder()
                for (i in 0 until minOf(total, 3)) {
                    val item = items?.optJSONObject(i) ?: continue
                    if (preview.isNotEmpty()) preview.append(", ")
                    preview.append(item.optString("name"))
                        .append(" S/").append(item.optString("price"))
                }
                if (total > 3) preview.append(", …")
                val note = resp.json.optString("note").takeIf { it.isNotEmpty() }
                    ?: getString(R.string.catalog_photo_saved, resp.json.optInt("count", total))
                replaceLast(
                    "✅ $note" + if (preview.isNotEmpty()) " — $preview" else ""
                )
            }
            resp.code == 422 -> replaceLast("⚠️ ${getString(R.string.catalog_photo_unreadable)}")
            resp.code == 503 -> replaceLast("⚠️ ${getString(R.string.catalog_photo_unavailable)}")
            resp.code == 429 -> replaceLast("⚠️ ${getString(R.string.verify_throttled)}")
            else -> replaceLast("⚠️ ${getString(R.string.server_error)}")
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
        cameraButton.isEnabled = !busy && Prefs.serverConfigured(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacksAndMessages(null)
        recorder?.release()
        player?.release()
    }
}
