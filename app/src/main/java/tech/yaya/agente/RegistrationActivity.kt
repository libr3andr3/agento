package tech.yaya.agente

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Full-screen, step-by-step registration. Runs BEFORE any chat exists, so a
 * cancelled verification can never strand the user in an unauthorized chat.
 *
 * Step machine (portrait-locked; state also survives recreation):
 *   1 business (name + industry)
 *   2 owner phone (country picker, E.164 composed here)
 *   3 code (only if /api/verify/start returns 200; 503 skips it)
 *   → onboard_business → Prefs token → OnboardingActivity.
 *
 * Back (system or top arrow) always moves ONE step back; from step 1 it exits.
 * Every network hop shows a spinner in the CTA and disables inputs; every
 * failure re-enables with an inline message — no dead ends.
 */
class RegistrationActivity : AppCompatActivity() {

    private companion object {
        const val RESEND_SECONDS = 60L
        const val LOCAL_MIN = 8
        const val LOCAL_MAX = 12
    }

    // ------------------------------------------------------------------ state
    private var step = 1
    private var country: Country = Countries.DEFAULT
    private var fullPhone = ""          // E.164, composed when step 2 submits
    private var resendDeadline = 0L     // wall-clock millis; 0 = no countdown
    private var busy = false
    private var seq = 0                 // bumps on every step change/submit; stale responses are dropped
    private var timer: CountDownTimer? = null

    // ------------------------------------------------------------------ views
    private lateinit var backBtn: ImageButton
    private lateinit var progress: View
    private lateinit var segments: List<View>
    private lateinit var steps: List<View>

    private lateinit var nameTil: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var industryTil: TextInputLayout
    private lateinit var industryInput: TextInputEditText
    private lateinit var businessCta: MaterialButton
    private lateinit var businessSpin: CircularProgressIndicator

    private lateinit var countryCard: MaterialCardView
    private lateinit var countryFlag: TextView
    private lateinit var countryDial: TextView
    private lateinit var phoneTil: TextInputLayout
    private lateinit var phoneInput: TextInputEditText
    private lateinit var phoneCta: MaterialButton
    private lateinit var phoneSpin: CircularProgressIndicator

    private lateinit var otpSub: TextView
    private lateinit var codeTil: TextInputLayout
    private lateinit var codeInput: TextInputEditText
    private lateinit var resendBtn: MaterialButton
    private lateinit var changeNumberBtn: MaterialButton
    private lateinit var otpCta: MaterialButton
    private lateinit var otpSpin: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)
        bindViews()
        wireStep1()
        wireStep2()
        wireStep3()

        backBtn.setOnClickListener { stepBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = stepBack()
        })

        savedInstanceState?.let {
            country = Countries.byIso(it.getString("iso"))
            fullPhone = it.getString("phone", "")
            resendDeadline = it.getLong("deadline", 0L)
            step = it.getInt("step", 1).coerceIn(1, 3)
        }
        updateCountryViews()
        showStep(step)
        if (step == 3 && resendDeadline > 0L) startCountdown(resendDeadline)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
        outState.putString("iso", country.iso)
        outState.putString("phone", fullPhone)
        outState.putLong("deadline", resendDeadline)
        // EditText contents ride the default view state (they all have ids).
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ view wiring

    private fun bindViews() {
        backBtn = findViewById(R.id.reg_back)
        progress = findViewById(R.id.reg_progress)
        segments = listOf(
            findViewById(R.id.reg_seg_1), findViewById(R.id.reg_seg_2), findViewById(R.id.reg_seg_3)
        )
        steps = listOf(
            findViewById(R.id.reg_step_business),
            findViewById(R.id.reg_step_phone),
            findViewById(R.id.reg_step_otp)
        )

        nameTil = findViewById(R.id.reg_name_til)
        nameInput = findViewById(R.id.reg_name_input)
        industryTil = findViewById(R.id.reg_industry_til)
        industryInput = findViewById(R.id.reg_industry_input)
        businessCta = findViewById(R.id.reg_business_cta)
        businessSpin = findViewById(R.id.reg_business_spin)

        countryCard = findViewById(R.id.reg_country_card)
        countryFlag = findViewById(R.id.reg_country_flag)
        countryDial = findViewById(R.id.reg_country_dial)
        phoneTil = findViewById(R.id.reg_phone_til)
        phoneInput = findViewById(R.id.reg_phone_input)
        phoneCta = findViewById(R.id.reg_phone_cta)
        phoneSpin = findViewById(R.id.reg_phone_spin)

        otpSub = findViewById(R.id.reg_otp_sub)
        codeTil = findViewById(R.id.reg_code_til)
        codeInput = findViewById(R.id.reg_code_input)
        resendBtn = findViewById(R.id.reg_resend)
        changeNumberBtn = findViewById(R.id.reg_change_number)
        otpCta = findViewById(R.id.reg_otp_cta)
        otpSpin = findViewById(R.id.reg_otp_spin)
    }

    private fun wireStep1() {
        nameInput.clearErrorOnType(nameTil)
        industryInput.clearErrorOnType(industryTil)
        industryInput.setOnEditorActionListener { _, _, _ -> businessCta.performClick(); true }
        businessCta.setOnClickListener { submitBusiness() }
    }

    private fun wireStep2() {
        countryCard.setOnClickListener { openCountryPicker() }
        phoneInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                phoneTil.error = null
                updatePhonePreview()
            }
        })
        phoneInput.setOnEditorActionListener { _, _, _ -> phoneCta.performClick(); true }
        phoneCta.setOnClickListener { submitPhone() }
    }

    private fun wireStep3() {
        codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                codeTil.error = null
                // Auto-advance feel: 6th digit submits without a tap.
                if (s?.length == 6 && !busy && step == 3) submitCode()
            }
        })
        codeInput.setOnEditorActionListener { _, _, _ -> otpCta.performClick(); true }
        otpCta.setOnClickListener { submitCode() }
        resendBtn.setOnClickListener { startVerify(fromResend = true) }
        changeNumberBtn.setOnClickListener { showStep(2) }
    }

    private fun TextInputEditText.clearErrorOnType(til: TextInputLayout) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { til.error = null }
        })
    }

    // ------------------------------------------------------------ step machine

    /** One step back everywhere; from step 1 this exits the app. */
    private fun stepBack() {
        if (step > 1) showStep(step - 1) else finish()
    }

    private fun showStep(n: Int) {
        setBusy(false)          // restore the step we are leaving
        seq++                   // any in-flight response is now stale
        if (n != 3) { timer?.cancel(); timer = null }
        step = n
        steps.forEachIndexed { i, v -> v.visibility = if (i == n - 1) View.VISIBLE else View.GONE }
        segments.forEachIndexed { i, v ->
            v.setBackgroundResource(
                if (i < n) R.drawable.reg_progress_active else R.drawable.reg_progress_inactive
            )
        }
        progress.contentDescription = getString(R.string.reg_progress_cd, n)
        when (n) {
            1 -> nameInput.requestFocus()
            2 -> phoneInput.requestFocus()
            3 -> {
                otpSub.text = getString(R.string.reg_otp_sub, prettyPhone(fullPhone))
                codeInput.requestFocus()
                updateResendButton()
            }
        }
    }

    /** Spinner in the CTA + everything disabled while a request runs. */
    private fun setBusy(b: Boolean) {
        busy = b
        val (cta, spin, label) = when (step) {
            1 -> Triple(businessCta, businessSpin, R.string.reg_continue)
            2 -> Triple(phoneCta, phoneSpin, R.string.reg_send_code)
            else -> Triple(otpCta, otpSpin, R.string.reg_verify)
        }
        cta.isEnabled = !b
        cta.text = if (b) "" else getString(label)
        spin.visibility = if (b) View.VISIBLE else View.GONE
        // backBtn stays enabled on purpose: back during a request cancels it (seq bump).
        when (step) {
            1 -> { nameInput.isEnabled = !b; industryInput.isEnabled = !b }
            2 -> { phoneInput.isEnabled = !b; countryCard.isEnabled = !b }
            3 -> {
                codeInput.isEnabled = !b
                changeNumberBtn.isEnabled = !b
                updateResendButton()
            }
        }
    }

    /** Inline error on whichever step is visible — failures never dead-end. */
    private fun showStepError(msg: String) {
        when (step) {
            2 -> phoneTil.error = msg
            3 -> codeTil.error = msg
            else -> nameTil.error = msg
        }
    }

    // ------------------------------------------------------------ step 1

    private fun submitBusiness() {
        val name = nameInput.text?.toString()?.trim().orEmpty()
        val industry = industryInput.text?.toString()?.trim().orEmpty()
        var ok = true
        if (name.isEmpty()) { nameTil.error = getString(R.string.reg_business_name_error); ok = false }
        if (industry.isEmpty()) { industryTil.error = getString(R.string.reg_business_industry_error); ok = false }
        if (ok) showStep(2)
    }

    // ------------------------------------------------------------ step 2

    private fun localDigits(): String =
        phoneInput.text?.toString().orEmpty().filter(Char::isDigit).trimStart('0')

    private fun updatePhonePreview() {
        val local = localDigits()
        phoneTil.helperText = if (local.isEmpty()) null else prettyPhone("+" + country.dial + local)
    }

    private fun updateCountryViews() {
        countryFlag.text = country.flag
        countryDial.text = "+" + country.dial
        countryCard.contentDescription =
            getString(R.string.reg_country_cd, country.nameEs, "+" + country.dial)
    }

    private fun submitPhone() {
        val local = localDigits()
        if (local.length < LOCAL_MIN || local.length > LOCAL_MAX) {
            phoneTil.error = getString(R.string.reg_phone_len_error)
            return
        }
        fullPhone = "+" + country.dial + local
        startVerify(fromResend = false)
    }

    /**
     * /api/verify/start — the status code is the contract:
     * 200 code sent → step 3; 503 verification off → onboard right away;
     * 400 bad phone → inline on the phone field; 429 throttled → wait message.
     */
    private fun startVerify(fromResend: Boolean) {
        setBusy(true)
        val my = ++seq
        ServerClient.IO_EXECUTOR.execute {
            val code = ServerClient.verifyStart(this, fullPhone)
            runOnUiThread {
                if (my != seq || isFinishing) return@runOnUiThread
                when (code) {
                    200 -> {
                        setBusy(false)
                        if (fromResend) {
                            Toast.makeText(this, R.string.reg_resent, Toast.LENGTH_SHORT).show()
                        } else {
                            codeInput.setText("")
                            showStep(3)
                        }
                        startCountdown(System.currentTimeMillis() + RESEND_SECONDS * 1000)
                    }
                    503 -> doOnboard(null)  // server not enforcing: skip OTP entirely
                    400 -> {
                        setBusy(false)
                        if (step != 2) showStep(2)
                        phoneTil.error = getString(R.string.reg_phone_invalid_error)
                    }
                    429 -> { setBusy(false); showStepError(getString(R.string.reg_throttle_error)) }
                    else -> { setBusy(false); showStepError(getString(R.string.reg_error_network)) }
                }
            }
        }
    }

    // ------------------------------------------------------------ step 3

    private fun submitCode() {
        val code = codeInput.text?.toString()?.trim().orEmpty()
        if (code.length != 6) {
            codeTil.error = getString(R.string.reg_code_len_error)
            return
        }
        setBusy(true)
        val my = ++seq
        ServerClient.IO_EXECUTOR.execute {
            val resp = ServerClient.verifyCheck(this, fullPhone, code)
            val token = resp?.optString("verificationToken")?.takeIf { it.isNotEmpty() }
            runOnUiThread {
                if (my != seq || isFinishing) return@runOnUiThread
                if (token != null) {
                    doOnboard(token)  // spinner stays on through the final hop
                } else {
                    setBusy(false)
                    codeTil.error = getString(R.string.reg_code_wrong_error)
                }
            }
        }
    }

    private fun startCountdown(deadline: Long) {
        resendDeadline = deadline
        timer?.cancel()
        updateResendButton()
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return
        timer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(ms: Long) = updateResendButton()
            override fun onFinish() { resendDeadline = 0L; updateResendButton() }
        }.start()
    }

    private fun updateResendButton() {
        val remain = ((resendDeadline - System.currentTimeMillis()) + 999) / 1000
        if (remain > 0) {
            resendBtn.isEnabled = false
            resendBtn.text = getString(R.string.reg_resend_wait, remain)
        } else {
            resendBtn.text = getString(R.string.reg_resend)
            resendBtn.isEnabled = !busy
        }
    }

    // ------------------------------------------------------------ finish

    /**
     * /api/onboard_business with whatever proof we have (token or none when the
     * server said 503). Success stores the device credential and hands off to
     * the onboarding chat — the first moment a chat can exist at all.
     */
    private fun doOnboard(verificationToken: String?) {
        setBusy(true)
        val my = ++seq
        val name = nameInput.text?.toString()?.trim().orEmpty()
        val industry = industryInput.text?.toString()?.trim().orEmpty()
        ServerClient.IO_EXECUTOR.execute {
            val resp = ServerClient.onboardBusiness(this, name, industry, fullPhone, verificationToken)
            runOnUiThread {
                if (my != seq || isFinishing) return@runOnUiThread
                val deviceToken = resp?.optString("deviceToken").orEmpty()
                if (deviceToken.isNotEmpty()) {
                    Prefs.setDeviceToken(this, deviceToken)
                    Prefs.setBusinessId(this, resp!!.optString("businessId"))
                    startActivity(Intent(this, OnboardingActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                } else {
                    setBusy(false)
                    showStepError(getString(R.string.reg_onboard_error))
                }
            }
        }
    }

    // ------------------------------------------------------------ country picker

    private fun openCountryPicker() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_country_picker, null)
        val sheetHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        dialog.setContentView(
            view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight)
        )
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val list = view.findViewById<RecyclerView>(R.id.country_list)
        val empty = view.findViewById<TextView>(R.id.country_empty)
        val search = view.findViewById<TextInputEditText>(R.id.country_search_input)

        val adapter = CountryAdapter(Countries.ALL) { picked ->
            country = picked
            updateCountryViews()
            updatePhonePreview()
            phoneTil.error = null
            dialog.dismiss()
            phoneInput.requestFocus()
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val results = Countries.search(s?.toString().orEmpty())
                adapter.submit(results)
                empty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                list.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        dialog.show()
    }

    private class CountryAdapter(
        initial: List<Country>,
        private val onPick: (Country) -> Unit
    ) : RecyclerView.Adapter<CountryAdapter.Holder>() {

        private var items: List<Country> = initial

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val flag: TextView = v.findViewById(R.id.country_flag)
            val name: TextView = v.findViewById(R.id.country_name)
            val dial: TextView = v.findViewById(R.id.country_dial)
        }

        fun submit(list: List<Country>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_country, parent, false)
            )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = items[position]
            holder.flag.text = c.flag
            holder.name.text = c.nameEs
            holder.dial.text = "+" + c.dial
            holder.itemView.setOnClickListener { onPick(c) }
        }
    }

    // ------------------------------------------------------------ helpers

    /** "+51987654321" → "+51 987 654 321" for subtitles and helper text. */
    private fun prettyPhone(e164: String): String {
        val dial = country.dial
        val local = e164.removePrefix("+").removePrefix(dial)
        return "+" + dial + " " + local.chunked(3).joinToString(" ")
    }
}
