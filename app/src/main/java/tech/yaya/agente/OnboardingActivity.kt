package tech.yaya.agente

import android.Manifest
import android.animation.ObjectAnimator
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The owner's chat with their business — first the setup interview, later the
 * management console (the server decides; the surface is the same). Real
 * bubbles in a RecyclerView, a typing indicator while the server thinks, and
 * the "¡Todo listo!" sheet which gates on Notification Access so the agent can
 * never finish setup dead. Registration/OTP live in RegistrationActivity.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val RC_CATALOG_CAMERA = 72
        private const val RC_CATALOG_GALLERY = 73
        private const val RC_VOICE_LISTEN = 74
        /** Longest edge for uploaded catalog photos. */
        private const val MAX_PHOTO_EDGE = 1600
        /** Transcript role markers — also the on-disk serialization prefixes. */
        private const val OWNER_PREFIX = "🧑 "
        private const val AGENT_PREFIX = "🟢 "
        /** Chat-UI prefs — OUR file, never Prefs.kt (see BOUNDARIES.md). */
        private const val UI_PREFS = "agente_chat_ui"
        private const val KEY_VOICE_MODE = "voice_mode_on"
        /** Recognizer locales: Peruvian Spanish first, LatAm Spanish fallback. */
        private const val LOCALE_PRIMARY = "es-PE"
        private const val LOCALE_FALLBACK = "es-419"
        /** Beat before auto-listening when a reply arrived without TTS audio. */
        private const val LISTEN_AFTER_TEXT_MS = 1100L
        /** Longer beat on open — the owner is still reading the seeded greeting. */
        private const val LISTEN_ON_OPEN_MS = 1600L
    }

    // ------------------------------------------------------------- chat model

    private enum class Role { OWNER, AGENT, SYSTEM }

    /** [pending] = live speech partial: muted bubble, never persisted. */
    private class Msg(
        var role: Role,
        var text: String,
        var failed: Boolean = false,
        var pending: Boolean = false
    )

    /**
     * Hands-free loop states. SPEAKING (agent TTS playing) → on completion →
     * LISTENING (SpeechRecognizer, partials in a muted owner bubble) → final
     * result → THINKING (message in flight, typing dots) → reply → SPEAKING.
     * Any recognizer error or user action drops to IDLE (strip hidden); the
     * loop then waits for the next agent reply or an explicit tap.
     */
    private enum class VoiceState { IDLE, SPEAKING, LISTENING, THINKING }

    private val msgs = mutableListOf<Msg>()
    private var typing = false

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var micButton: MaterialButton
    private lateinit var cameraButton: MaterialButton
    private lateinit var replayButton: MaterialButton
    private lateinit var voiceToggle: MaterialButton
    private lateinit var recordBar: View
    private lateinit var recordDot: TextView
    private lateinit var recordTimer: TextView
    private lateinit var voiceStrip: View
    private lateinit var voiceDot: TextView
    private lateinit var voiceLabel: TextView

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recordStart = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val voiceHandler = Handler(Looper.getMainLooper())
    private var recordPulse: ObjectAnimator? = null
    private var statusPulse: ObjectAnimator? = null
    private var pendingDone = false

    // --- voice-mode state machine ---
    private var voiceState = VoiceState.IDLE
    private var voiceModeOn = false
    private var voiceCapable = false
    private var interviewDone = false
    private var speech: SpeechRecognizer? = null
    private var speechOnDevice = false
    private var forceCloudRecognizer = false
    private var triedCloudForLocale = false
    private var busyRetryUsed = false
    private var voiceLocale = LOCALE_PRIMARY
    private var pendingVoiceMsg: Msg? = null
    private val uiPrefs by lazy { getSharedPreferences(UI_PREFS, MODE_PRIVATE) }

    private val voiceFile: File by lazy { File(cacheDir, "voice.m4a") }
    private val replyWav: File by lazy { File(cacheDir, "reply.wav") }
    private val photoFile: File by lazy { File(cacheDir, "catalog.jpg") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Chat only exists for a registered business: registration (and OTP)
        // live in RegistrationActivity, never here.
        if (!Prefs.serverConfigured(this)) {
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)
        recycler = findViewById(R.id.chat_recycler)
        input = findViewById(R.id.chat_input)
        sendButton = findViewById(R.id.chat_send)
        micButton = findViewById(R.id.chat_mic)
        cameraButton = findViewById(R.id.chat_camera)
        replayButton = findViewById(R.id.chat_replay)
        voiceToggle = findViewById(R.id.chat_voice_toggle)
        recordBar = findViewById(R.id.record_bar)
        recordDot = findViewById(R.id.record_dot)
        recordTimer = findViewById(R.id.record_timer)
        voiceStrip = findViewById(R.id.voice_strip)
        voiceDot = findViewById(R.id.voice_dot)
        voiceLabel = findViewById(R.id.voice_label)

        adapter = ChatAdapter()
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        sendButton.setOnClickListener { send() }
        micButton.setOnClickListener { toggleRecording() }
        cameraButton.setOnClickListener { showPhotoSourceDialog() }
        replayButton.setOnClickListener { replayLast() }
        voiceToggle.setOnClickListener { setVoiceMode(!voiceModeOn) }
        // Barge-in lite: tap the strip while the agent talks → answer now.
        voiceStrip.setOnClickListener {
            if (voiceState == VoiceState.SPEAKING) startVoiceListening()
        }

        // Voice mode: default ON while the recognizer exists; a persisted user
        // choice wins. No recognizer → silently text-only, never a crash.
        voiceCapable = SpeechRecognizer.isRecognitionAvailable(this)
        voiceModeOn = voiceCapable && uiPrefs.getBoolean(KEY_VOICE_MODE, true)
        updateVoiceToggle()

        // Restore the transcript so an interrupted owner never sees a blank chat.
        Prefs.chatTranscript(this)?.let { stored ->
            stored.split("\n\n").forEach { line -> parseLine(line)?.let { msgs.add(it) } }
            adapter.notifyDataSetChanged()
            scrollToBottom(smooth = false)
        }
        val transcriptBlank = msgs.isEmpty()
        if (!voiceCapable) {
            val line = getString(R.string.voice_unavailable)
            if (msgs.none { it.text == line }) addMsg(Role.SYSTEM, line)
        }
        if (transcriptBlank) {
            // Safety net: never open onto a silent agent — kick the interview
            // ourselves, invisibly (no owner bubble for the "hola").
            kickInterview()
        } else if (voiceModeOn && msgs.lastOrNull()?.role == Role.AGENT && !replyWav.exists()) {
            // Seeded greeting with no TTS: give the owner a beat to read it,
            // then open the mic — the loop must begin without a tap.
            voiceHandler.postDelayed({
                if (voiceState == VoiceState.IDLE && !typing) startVoiceListening()
            }, LISTEN_ON_OPEN_MS)
        }
    }

    // -------------------------------------------------- transcript persistence

    /**
     * Best-effort parse of one stored block back into a bubble role. The old
     * one-TextView build persisted blocks joined by \n\n with "🧑 "/"🟢 "
     * markers and bare status lines — the same format we keep writing, so old
     * transcripts load unchanged. Unknown lines become system lines; stale
     * typing-indicator blocks are dropped.
     */
    private fun parseLine(line: String): Msg? {
        val t = line.trim()
        if (t.isEmpty()) return null
        if (t == getString(R.string.typing_indicator)) return null
        return when {
            t.startsWith(OWNER_PREFIX) -> Msg(Role.OWNER, t.removePrefix(OWNER_PREFIX))
            t.startsWith(AGENT_PREFIX) -> Msg(Role.AGENT, t.removePrefix(AGENT_PREFIX))
            else -> Msg(Role.SYSTEM, t)
        }
    }

    private fun persist() {
        // Pending speech partials are ephemeral — never written to disk.
        val serialized = msgs.filter { !it.pending }.joinToString("\n\n") {
            when (it.role) {
                Role.OWNER -> OWNER_PREFIX + it.text
                Role.AGENT -> AGENT_PREFIX + it.text
                Role.SYSTEM -> it.text
            }
        }
        Prefs.setChatTranscript(this, serialized)
    }

    // ------------------------------------------------------------ chat surface

    private fun addMsg(role: Role, text: String): Msg {
        val m = Msg(role, text)
        msgs.add(m)
        adapter.notifyItemInserted(msgs.size - 1)
        persist()
        scrollToBottom()
        return m
    }

    /** Add a line that may carry legacy 🧑/🟢 markers (e.g. resume hint). */
    private fun addParsed(line: String) {
        parseLine(line)?.let {
            msgs.add(it)
            adapter.notifyItemInserted(msgs.size - 1)
            persist()
            scrollToBottom()
        }
    }

    private fun changeMsg(m: Msg, block: (Msg) -> Unit) {
        block(m)
        val i = msgs.indexOf(m)
        if (i >= 0) adapter.notifyItemChanged(i)
        persist()
    }

    private fun showTyping() {
        if (typing) return
        typing = true
        adapter.notifyItemInserted(msgs.size)
        scrollToBottom()
        if (voiceModeOn && !interviewDone) setVoiceStatus(VoiceState.THINKING)
    }

    private fun hideTyping() {
        if (!typing) return
        typing = false
        adapter.notifyItemRemoved(msgs.size)
    }

    private fun scrollToBottom(smooth: Boolean = true) {
        val last = adapter.itemCount - 1
        if (last < 0) return
        recycler.post {
            if (smooth) recycler.smoothScrollToPosition(last)
            else recycler.scrollToPosition(last)
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        input.isEnabled = !busy
        micButton.isEnabled = !busy || recorder != null
        cameraButton.isEnabled = !busy
    }

    // ------------------------------------------------------------------- text

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        interruptVoiceLoop() // typing wins over any TTS/recognizer in progress
        val mine = addMsg(Role.OWNER, text)
        showTyping()
        setBusy(true)
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.onboardingMessage(this, text)
            runOnUiThread {
                // Only clear the box once the message actually made it through.
                if (resp != null) input.setText("")
                handleAgentResponse(resp, retryTarget = mine)
            }
        }
    }

    /** "Reintentar" on a failed bubble — resends that exact text. */
    private fun resend(m: Msg) {
        if (!sendButton.isEnabled) return // a send is already in flight
        interruptVoiceLoop()
        changeMsg(m) { it.failed = false }
        showTyping()
        setBusy(true)
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.onboardingMessage(this, m.text)
            runOnUiThread { handleAgentResponse(resp, retryTarget = m) }
        }
    }

    // ------------------------------------------------------------- voice mode

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecordingAndSend()
            return
        }
        interruptVoiceLoop() // push-to-talk takes the mic from the loop
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
            showRecordingUi(true)
            tickTimer()
            sendButton.isEnabled = false
        } catch (e: Exception) {
            recorder = null
            showRecordingUi(false)
            Toast.makeText(this, getString(R.string.mic_error), Toast.LENGTH_LONG).show()
        }
    }

    /** Timer strip + red pulse + mic becomes a stop button while capturing. */
    private fun showRecordingUi(on: Boolean) {
        recordBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            micButton.text = getString(R.string.chat_stop_glyph)
            micButton.contentDescription = getString(R.string.chat_a11y_stop_recording)
            micButton.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.agento_error_container)
            micButton.setTextColor(ContextCompat.getColor(this, R.color.agento_error))
            recordTimer.text = getString(R.string.chat_timer_zero)
            recordPulse = ObjectAnimator.ofFloat(recordDot, View.ALPHA, 1f, 0.2f).apply {
                duration = 550
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        } else {
            recordPulse?.cancel(); recordPulse = null
            recordDot.alpha = 1f
            micButton.text = getString(R.string.chat_mic_glyph)
            micButton.contentDescription = getString(R.string.chat_a11y_mic)
            micButton.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.agento_primary_container)
            micButton.setTextColor(ContextCompat.getColor(this, R.color.agento_on_primary_container))
        }
    }

    private fun tickTimer() {
        if (recorder == null) return
        val secs = (System.currentTimeMillis() - recordStart) / 1000
        recordTimer.text = String.format("%d:%02d", secs / 60, secs % 60)
        timerHandler.postDelayed({ tickTimer() }, 500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_VOICE_LISTEN) {
            // First hands-free listen. Grant → open the mic; denial → text
            // mode, no nagging dialog (session-only, so a later grant can
            // bring voice back without digging through settings).
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startVoiceListening()
            } else {
                setVoiceMode(false, persistChoice = false)
                addSystemLineOnce(getString(R.string.voice_perm_denied))
            }
            return
        }
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
        showRecordingUi(false)
        sendButton.isEnabled = true
        if (!voiceFile.exists() || voiceFile.length() < 1000) {
            Toast.makeText(this, getString(R.string.voice_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        val voiceMsg = addMsg(Role.OWNER, "🎤 …")
        showTyping()
        setBusy(true)
        val bytes = voiceFile.readBytes()
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.voiceMessage(this, bytes)
            runOnUiThread {
                resp?.optString("transcript")?.takeIf { it.isNotEmpty() }?.let { t ->
                    // Swap the placeholder for what the owner actually said.
                    changeMsg(voiceMsg) { it.text = "🎤 $t" }
                }
                handleAgentResponse(resp, retryTarget = null)
            }
        }
    }

    /**
     * Play the last reply wav. In voice mode this is the SPEAKING leg of the
     * hands-free loop: playback completion opens the mic automatically.
     * @return true if playback actually started.
     */
    private fun replayLast(): Boolean {
        if (!replyWav.exists()) return false
        if (voiceState == VoiceState.LISTENING) cancelVoiceListening()
        return try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(replyWav.absolutePath)
                setOnCompletionListener {
                    if (voiceState == VoiceState.SPEAKING) {
                        if (voiceModeOn && !interviewDone) startVoiceListening()
                        else setVoiceStatus(VoiceState.IDLE)
                    }
                }
                prepare(); start()
            }
            if (voiceModeOn) setVoiceStatus(VoiceState.SPEAKING)
            true
        } catch (_: Exception) {
            setVoiceStatus(VoiceState.IDLE)
            Toast.makeText(this, getString(R.string.audio_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** @return true if agent TTS audio started playing (loop continues there). */
    private fun playIfPresent(resp: org.json.JSONObject): Boolean {
        val b64 = resp.optString("audioBase64").takeIf { it.isNotEmpty() && it != "null" }
            ?: return false
        return try {
            replyWav.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            replayButton.visibility = View.VISIBLE
            replayLast()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.audio_error), Toast.LENGTH_SHORT).show()
            false
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
        val status = addMsg(Role.SYSTEM, "📷 ⏳ ${getString(R.string.catalog_photo_reading)}")
        setBusy(true)
        ServerClient.EXECUTOR.execute {
            val jpeg = prepareCatalogJpeg(raw)
            val resp = jpeg?.let { ServerClient.catalogPhoto(this, it) }
            runOnUiThread {
                setBusy(false)
                photoFile.delete()
                if (resp == null) { // undecodable image, never left the phone
                    changeMsg(status) {
                        it.text = "⚠️ ${getString(R.string.catalog_photo_unreadable)}"
                    }
                    return@runOnUiThread
                }
                handleCatalogResponse(resp, status)
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

    private fun handleCatalogResponse(resp: ServerClient.Response, status: Msg) {
        val text = when {
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
                "✅ $note" + if (preview.isNotEmpty()) " — $preview" else ""
            }
            resp.code == 422 -> "⚠️ ${getString(R.string.catalog_photo_unreadable)}"
            resp.code == 503 -> "⚠️ ${getString(R.string.catalog_photo_unavailable)}"
            resp.code == 429 -> "⚠️ ${getString(R.string.verify_throttled)}"
            else -> "⚠️ ${getString(R.string.server_error)}"
        }
        changeMsg(status) { it.text = text }
        scrollToBottom()
    }

    // ----------------------------------------------------------------- shared

    /**
     * [retryTarget] is the owner bubble a null response should mark as failed
     * (tap → resend). Voice sends pass null: their bytes are gone once the
     * recorder file is rewritten, so failure becomes a readable system line.
     */
    private fun handleAgentResponse(resp: org.json.JSONObject?, retryTarget: Msg?) {
        setBusy(false)
        hideTyping()
        if (resp == null) {
            setVoiceStatus(VoiceState.IDLE) // loop stops; the user acts next
            if (retryTarget != null) {
                changeMsg(retryTarget) { it.failed = true }
            } else {
                addMsg(Role.SYSTEM, "⚠️ ${getString(R.string.server_error)}")
            }
            return
        }
        addMsg(Role.AGENT, resp.optString("agentResponse"))
        val finishing = resp.optString("action") == "finish_onboarding"
        if (finishing) interviewDone = true // audio still plays; loop stops
        val audioStarted = playIfPresent(resp)
        if (finishing) {
            setVoiceStatus(VoiceState.IDLE)
            addMsg(Role.SYSTEM, "✅ ${getString(R.string.onboarding_saved)}")
            showDoneSheet()
        } else if (!audioStarted) {
            setVoiceStatus(VoiceState.IDLE)
            if (voiceModeOn) {
                // Reply without TTS: a beat to read it, then open the mic —
                // the loop must not die just because audio was missing.
                voiceHandler.postDelayed({
                    if (voiceState == VoiceState.IDLE && recorder == null && !typing) {
                        startVoiceListening()
                    }
                }, LISTEN_AFTER_TEXT_MS)
            }
        }
    }

    // ------------------------------------------------- hands-free voice loop

    /** Flip voice mode. [persistChoice] false = session-only (perm denials). */
    private fun setVoiceMode(on: Boolean, persistChoice: Boolean = true) {
        voiceModeOn = on && voiceCapable
        if (persistChoice) uiPrefs.edit().putBoolean(KEY_VOICE_MODE, voiceModeOn).apply()
        if (!voiceModeOn) interruptVoiceLoop() // silence everything, instantly
        updateVoiceToggle()
    }

    private fun updateVoiceToggle() {
        voiceToggle.visibility = if (voiceCapable) View.VISIBLE else View.GONE
        voiceToggle.text = getString(
            if (voiceModeOn) R.string.chat_voice_on_glyph else R.string.chat_voice_off_glyph
        )
        voiceToggle.contentDescription = getString(
            if (voiceModeOn) R.string.chat_a11y_voice_mute else R.string.chat_a11y_voice_unmute
        )
    }

    /** Stop TTS playback + recognizer, drop any partial bubble → IDLE. */
    private fun interruptVoiceLoop() {
        voiceHandler.removeCallbacksAndMessages(null)
        player?.release(); player = null
        cancelVoiceListening()
        if (voiceState != VoiceState.IDLE) setVoiceStatus(VoiceState.IDLE)
    }

    private fun cancelVoiceListening() {
        if (voiceState == VoiceState.LISTENING) {
            try { speech?.cancel() } catch (_: Exception) {}
            setVoiceStatus(VoiceState.IDLE)
        }
        discardPendingVoice()
    }

    /**
     * Lazily build the recognizer: on-device engine when the OS offers one
     * (API 31+), else the default service asked to EXTRA_PREFER_OFFLINE.
     */
    private fun ensureRecognizer(): SpeechRecognizer? {
        speech?.let { return it }
        val onDevice = !forceCloudRecognizer &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val r = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) {
            return null
        }
        speechOnDevice = onDevice
        r.setRecognitionListener(recognitionListener)
        speech = r
        return r
    }

    /** LISTENING leg: free-form Spanish, offline-preferred, partials on. */
    private fun startVoiceListening() {
        if (!voiceModeOn || !voiceCapable || interviewDone) return
        if (recorder != null || voiceState == VoiceState.LISTENING) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_VOICE_LISTEN
            )
            return
        }
        val rec = ensureRecognizer()
        if (rec == null) {
            voiceCapable = false
            setVoiceMode(false, persistChoice = false)
            addSystemLineOnce(getString(R.string.voice_unavailable))
            return
        }
        player?.release(); player = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLocale)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLocale)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        setVoiceStatus(VoiceState.LISTENING)
        try {
            rec.startListening(intent)
        } catch (_: Exception) {
            setVoiceStatus(VoiceState.IDLE)
            addSystemLineOnce(getString(R.string.voice_not_heard))
        }
    }

    /** Live partial → muted owner bubble that solidifies on the final result. */
    private fun showVoicePartial(text: String) {
        val m = pendingVoiceMsg
        if (m == null) {
            val nm = Msg(Role.OWNER, text, pending = true)
            pendingVoiceMsg = nm
            msgs.add(nm)
            adapter.notifyItemInserted(msgs.size - 1)
        } else {
            m.text = text
            val i = msgs.indexOf(m)
            if (i >= 0) adapter.notifyItemChanged(i)
        }
        scrollToBottom()
    }

    private fun discardPendingVoice() {
        val m = pendingVoiceMsg ?: return
        pendingVoiceMsg = null
        val i = msgs.indexOf(m)
        if (i >= 0) {
            msgs.removeAt(i)
            adapter.notifyItemRemoved(i)
        }
    }

    /** Final transcript: solidify the bubble, send through onboardingMessage. */
    private fun sendVoiceFinal(text: String) {
        val m = pendingVoiceMsg
        pendingVoiceMsg = null
        val target: Msg = if (m != null) {
            changeMsg(m) { it.pending = false; it.text = text }
            m
        } else {
            addMsg(Role.OWNER, text)
        }
        showTyping()
        setBusy(true)
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.onboardingMessage(this, text)
            runOnUiThread { handleAgentResponse(resp, retryTarget = target) }
        }
    }

    /** Silence/no-match: gentle status, loop stops until the user acts. */
    private fun stopLoopGently() {
        discardPendingVoice()
        setVoiceStatus(VoiceState.IDLE)
        addSystemLineOnce(getString(R.string.voice_not_heard))
    }

    /** es-PE → es-419 → (on-device only) default service → give up politely. */
    private fun retryWithFallbackLocale() {
        discardPendingVoice()
        setVoiceStatus(VoiceState.IDLE)
        when {
            voiceLocale == LOCALE_PRIMARY -> {
                voiceLocale = LOCALE_FALLBACK
                startVoiceListening()
            }
            speechOnDevice && !triedCloudForLocale -> {
                triedCloudForLocale = true
                forceCloudRecognizer = true
                speech?.destroy(); speech = null
                voiceLocale = LOCALE_PRIMARY
                startVoiceListening()
            }
            else -> {
                setVoiceMode(false, persistChoice = false)
                addSystemLineOnce(getString(R.string.voice_lang_unavailable))
            }
        }
    }

    /** One quiet status line, never repeated back-to-back. */
    private fun addSystemLineOnce(text: String) {
        if (msgs.lastOrNull()?.text == text) return
        addMsg(Role.SYSTEM, text)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { busyRetryUsed = false }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (voiceState != VoiceState.LISTENING) return
            val stable = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            val unstable = partialResults
                ?.getStringArrayList("android.speech.extra.UNSTABLE_TEXT")
                ?.firstOrNull().orEmpty()
            val text = (stable + unstable).trim()
            if (text.isNotEmpty()) showVoicePartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (voiceState != VoiceState.LISTENING) return // stale after cancel
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) stopLoopGently() else sendVoiceFinal(text)
        }

        override fun onError(error: Int) {
            if (voiceState != VoiceState.LISTENING) return // stale after cancel
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> stopLoopGently()
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> retryWithFallbackLocale()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    discardPendingVoice()
                    setVoiceMode(false, persistChoice = false)
                    addSystemLineOnce(getString(R.string.voice_perm_denied))
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Wedged service: rebuild once per listen, else stop gently.
                    discardPendingVoice()
                    setVoiceStatus(VoiceState.IDLE)
                    speech?.destroy(); speech = null
                    if (!busyRetryUsed) {
                        busyRetryUsed = true
                        startVoiceListening()
                    } else {
                        stopLoopGently()
                    }
                }
                else -> stopLoopGently() // network/client/etc — never error-loop
            }
        }
    }

    // ------------------------------------------------------ voice status strip

    /**
     * Slim strip above the composer — the whole voice UI. One dot + one label,
     * recolored and pulsed per state; hidden when idle or muted. During
     * SPEAKING the strip itself is the barge-in tap target.
     */
    private fun setVoiceStatus(state: VoiceState) {
        voiceState = state
        statusPulse?.cancel(); statusPulse = null
        voiceDot.alpha = 1f
        voiceLabel.alpha = 1f
        val visible = state != VoiceState.IDLE && voiceModeOn && !interviewDone
        voiceStrip.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        fun color(res: Int) = ContextCompat.getColor(this, res)
        when (state) {
            VoiceState.SPEAKING -> {
                voiceLabel.text = getString(R.string.voice_status_speaking)
                voiceLabel.setTextColor(color(R.color.agento_primary))
                voiceDot.setTextColor(color(R.color.agento_primary))
                voiceStrip.isClickable = true
                voiceStrip.contentDescription = getString(R.string.chat_a11y_voice_interrupt)
                statusPulse = ObjectAnimator.ofFloat(voiceLabel, View.ALPHA, 1f, 0.55f).apply {
                    duration = 700
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
            VoiceState.LISTENING -> {
                voiceLabel.text = getString(R.string.voice_status_listening)
                voiceLabel.setTextColor(color(R.color.agento_on_surface))
                voiceDot.setTextColor(color(R.color.agento_error))
                voiceStrip.isClickable = false
                voiceStrip.contentDescription = getString(R.string.voice_status_listening)
                statusPulse = ObjectAnimator.ofFloat(voiceDot, View.ALPHA, 1f, 0.2f).apply {
                    duration = 550
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
            VoiceState.THINKING -> {
                voiceLabel.text = getString(R.string.voice_status_thinking)
                voiceLabel.setTextColor(color(R.color.agento_on_surface_muted))
                voiceDot.setTextColor(color(R.color.agento_on_surface_muted))
                voiceStrip.isClickable = false
                voiceStrip.contentDescription = getString(R.string.voice_status_thinking)
                statusPulse = ObjectAnimator.ofFloat(voiceDot, View.ALPHA, 0.3f, 1f).apply {
                    duration = 450
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
            VoiceState.IDLE -> Unit
        }
    }

    // --------------------------------------------------- interview safety net

    /**
     * Blank transcript on a configured device: the agent must speak first.
     * Send a silent "hola" through the normal onboarding path — typing dots
     * only, no owner bubble — and render (and speak) just the reply.
     */
    private fun kickInterview() {
        showTyping()
        setBusy(true)
        ServerClient.EXECUTOR.execute {
            val resp = ServerClient.onboardingMessage(this, "hola")
            runOnUiThread {
                if (resp == null) {
                    setBusy(false)
                    hideTyping()
                    setVoiceStatus(VoiceState.IDLE)
                    // Keep the chat alive even offline: canned hint + next step.
                    addParsed(getString(R.string.onboarding_resume_hint))
                    addMsg(Role.SYSTEM, "⚠️ ${getString(R.string.server_error)}")
                } else {
                    handleAgentResponse(resp, retryTarget = null)
                }
            }
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

    override fun onPause() {
        super.onPause()
        // Never keep the mic open in the background; the loop resumes on the
        // next agent reply (or an explicit tap) when the owner comes back.
        cancelVoiceListening()
        voiceHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacksAndMessages(null)
        voiceHandler.removeCallbacksAndMessages(null)
        recordPulse?.cancel()
        statusPulse?.cancel()
        speech?.destroy()
        recorder?.release()
        player?.release()
    }

    // ---------------------------------------------------------------- adapter

    private inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_OWNER = 0
        private val TYPE_AGENT = 1
        private val TYPE_SYSTEM = 2
        private val TYPE_TYPING = 3

        override fun getItemCount() = msgs.size + if (typing) 1 else 0

        override fun getItemViewType(position: Int): Int {
            if (typing && position == msgs.size) return TYPE_TYPING
            return when (msgs[position].role) {
                Role.OWNER -> TYPE_OWNER
                Role.AGENT -> TYPE_AGENT
                Role.SYSTEM -> TYPE_SYSTEM
            }
        }

        /** WhatsApp-style tail: full 18dp radius, one sharp 4dp corner on the sender's side. */
        private fun bubbleBg(fillColor: Int, sharpBottomRight: Boolean): MaterialShapeDrawable {
            val r = resources.getDimension(R.dimen.corner_bubble)
            val sharp = resources.getDimension(R.dimen.space_xs)
            val shape = ShapeAppearanceModel.builder()
                .setAllCornerSizes(r)
                .apply {
                    if (sharpBottomRight) setBottomRightCornerSize(sharp)
                    else setBottomLeftCornerSize(sharp)
                }
                .build()
            return MaterialShapeDrawable(shape).apply {
                setTint(ContextCompat.getColor(this@OnboardingActivity, fillColor))
            }
        }

        private val bubbleMaxWidth: Int
            get() = (resources.displayMetrics.widthPixels * 0.78f).toInt()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_OWNER -> OwnerVH(inf.inflate(R.layout.item_chat_owner, parent, false))
                TYPE_AGENT -> AgentVH(inf.inflate(R.layout.item_chat_agent, parent, false))
                TYPE_TYPING -> TypingVH(inf.inflate(R.layout.item_chat_typing, parent, false))
                else -> SystemVH(inf.inflate(R.layout.item_chat_system, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is OwnerVH -> holder.bind(msgs[position])
                is AgentVH -> holder.bind(msgs[position])
                is SystemVH -> holder.bind(msgs[position])
                is TypingVH -> Unit // animation runs on attach
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            if (holder is TypingVH) holder.startDots()
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            if (holder is TypingVH) holder.stopDots()
        }

        inner class OwnerVH(v: View) : RecyclerView.ViewHolder(v) {
            private val text: TextView = v.findViewById(R.id.bubble_text)
            private val retry: MaterialButton = v.findViewById(R.id.bubble_retry)

            init {
                text.maxWidth = bubbleMaxWidth
                retry.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < msgs.size) resend(msgs[pos])
                }
            }

            fun bind(m: Msg) {
                // Pending = live speech partial: muted fill + muted text until
                // the recognizer's final result solidifies the bubble.
                text.background = bubbleBg(
                    if (m.pending) R.color.agento_surface_variant
                    else R.color.agento_primary_container,
                    sharpBottomRight = true
                )
                text.setTextColor(
                    ContextCompat.getColor(
                        this@OnboardingActivity,
                        if (m.pending) R.color.agento_on_surface_muted
                        else R.color.agento_on_primary_container
                    )
                )
                text.text = m.text
                retry.visibility = if (m.failed && !m.pending) View.VISIBLE else View.GONE
            }
        }

        inner class AgentVH(v: View) : RecyclerView.ViewHolder(v) {
            private val text: TextView = v.findViewById(R.id.bubble_text)

            init {
                text.background = bubbleBg(R.color.agento_surface_variant, sharpBottomRight = false)
                text.maxWidth = bubbleMaxWidth
            }

            fun bind(m: Msg) { text.text = m.text }
        }

        inner class SystemVH(v: View) : RecyclerView.ViewHolder(v) {
            private val text: TextView = v.findViewById(R.id.system_text)
            fun bind(m: Msg) { text.text = m.text }
        }

        inner class TypingVH(v: View) : RecyclerView.ViewHolder(v) {
            private val dots = listOf<TextView>(
                v.findViewById(R.id.typing_dot1),
                v.findViewById(R.id.typing_dot2),
                v.findViewById(R.id.typing_dot3)
            )
            private val animators = mutableListOf<ObjectAnimator>()

            init {
                v.findViewById<LinearLayout>(R.id.typing_bubble).background =
                    bubbleBg(R.color.agento_surface_variant, sharpBottomRight = false)
            }

            fun startDots() {
                stopDots()
                dots.forEachIndexed { i, dot ->
                    animators += ObjectAnimator.ofFloat(dot, View.ALPHA, 0.25f, 1f).apply {
                        duration = 450
                        startDelay = i * 160L
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        start()
                    }
                }
            }

            fun stopDots() {
                animators.forEach { it.cancel() }
                animators.clear()
                dots.forEach { it.alpha = 0.25f }
            }
        }
    }
}
