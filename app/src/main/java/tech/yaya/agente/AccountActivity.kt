package tech.yaya.agente

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Yaya ID — the door to both editions. Sign in ("Continuar con Yaya") or
 * create an account; the core links this phone's agent to the account and
 * everything the app does on the network is then done by a real person who
 * signed up. Opened with [EXTRA_MANAGE] it shows who is signed in and lets
 * them sign out.
 */
class AccountActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MANAGE = "manage"
    }

    private var registering = false
    private lateinit var title: TextView
    private lateinit var form: View
    private lateinit var nameTil: TextInputLayout
    private lateinit var name: TextInputEditText
    private lateinit var emailTil: TextInputLayout
    private lateinit var email: TextInputEditText
    private lateinit var passwordTil: TextInputLayout
    private lateinit var password: TextInputEditText
    private lateinit var error: TextView
    private lateinit var primary: MaterialButton
    private lateinit var switchMode: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var restoreCard: View
    private lateinit var signedCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)
        title = findViewById(R.id.account_title)
        form = findViewById(R.id.account_form)
        nameTil = findViewById(R.id.account_name_til)
        name = findViewById(R.id.account_name)
        emailTil = findViewById(R.id.account_email_til)
        email = findViewById(R.id.account_email)
        passwordTil = findViewById(R.id.account_password_til)
        password = findViewById(R.id.account_password)
        error = findViewById(R.id.account_error)
        primary = findViewById(R.id.account_primary)
        switchMode = findViewById(R.id.account_switch)
        progress = findViewById(R.id.account_progress)
        restoreCard = findViewById(R.id.account_restore_card)
        signedCard = findViewById(R.id.account_signed_card)

        primary.setOnClickListener { submit() }
        switchMode.setOnClickListener { setMode(!registering) }
        findViewById<View>(R.id.account_restore).setOnClickListener { restore() }
        findViewById<View>(R.id.account_fresh).setOnClickListener { goHome() }
        findViewById<View>(R.id.account_continue).setOnClickListener { goHome() }
        findViewById<View>(R.id.account_logout).setOnClickListener { logout() }

        if (intent.getBooleanExtra(EXTRA_MANAGE, false) && Prefs.accountEmail(this).isNotEmpty()) showSigned()
        else setMode(false)
    }

    private fun setMode(register: Boolean) {
        registering = register
        title.setText(if (register) R.string.account_title_register else R.string.account_title_signin)
        primary.setText(if (register) R.string.account_primary_register else R.string.account_primary_signin)
        switchMode.setText(if (register) R.string.account_switch_to_signin else R.string.account_switch_to_register)
        nameTil.visibility = if (register) View.VISIBLE else View.GONE
        error.visibility = View.GONE
    }

    private fun showSigned() {
        form.visibility = View.GONE
        restoreCard.visibility = View.GONE
        signedCard.visibility = View.VISIBLE
        title.setText(R.string.account_title_signin)
        findViewById<TextView>(R.id.account_signed_as).text = getString(R.string.account_signed_as, Prefs.accountEmail(this))
    }

    private fun setBusy(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        primary.isEnabled = !b
        switchMode.isEnabled = !b
    }

    private fun showError(msg: String) {
        error.text = msg
        error.visibility = View.VISIBLE
    }

    private fun submit() {
        val e = email.text?.toString()?.trim().orEmpty()
        val p = password.text?.toString().orEmpty()
        emailTil.error = null; passwordTil.error = null; error.visibility = View.GONE
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) { emailTil.error = getString(R.string.account_error_email); return }
        if (p.length < 8) { passwordTil.error = getString(R.string.account_error_short_password); return }
        setBusy(true)
        ServerClient.IO_EXECUTOR.execute {
            val r = if (registering) ServerClient.accountRegister(this, e, p, name.text?.toString()?.trim().orEmpty().ifEmpty { null })
                    else ServerClient.accountLogin(this, e, p)
            runOnUiThread {
                setBusy(false)
                if (r.code in 200..299 && r.json?.optBoolean("signedIn") == true) {
                    Prefs.setAccountEmail(this, r.json.optString("email", e))
                    afterSignIn()
                } else showError(when (ServerClient.classify(r)) {
                    ServerClient.Kind.OFFLINE -> getString(R.string.account_error_offline)
                    ServerClient.Kind.AUTH -> getString(R.string.account_error_wrong)
                    ServerClient.Kind.RATE_LIMITED -> getString(R.string.account_error_rate)
                    else -> if (r.code == 409) getString(R.string.account_error_conflict)
                            else getString(R.string.account_error_generic, r.json?.optString("error").orEmpty().ifEmpty { r.code.toString() })
                })
            }
        }
    }

    /** A business phone with nothing on it yet may come from a backup. */
    private fun afterSignIn() {
        if (Edition.CLIENT || Prefs.serverConfigured(this)) { goHome(); return }
        form.visibility = View.GONE
        restoreCard.visibility = View.VISIBLE
    }

    private fun restore() {
        setBusy(true)
        ServerClient.IO_EXECUTOR.execute {
            val r = ServerClient.restore(this, force = false)
            runOnUiThread {
                setBusy(false)
                val j = r.json
                if (r.code in 200..299 && j != null && j.optString("deviceToken").isNotEmpty()) {
                    Prefs.setDeviceToken(this, j.optString("deviceToken"))
                    Prefs.setBusinessId(this, j.optString("businessId"))
                    Prefs.setLocale(this, j.optJSONObject("locale"))
                    Prefs.setChatTranscript(this, getString(R.string.account_restored))
                    Toast.makeText(this, R.string.account_restored, Toast.LENGTH_LONG).show()
                    goHome()
                } else if (r.code == 404) {
                    Toast.makeText(this, R.string.account_restore_none, Toast.LENGTH_LONG).show()
                    goHome()
                } else showError(getString(R.string.account_error_generic, j?.optString("error").orEmpty().ifEmpty { r.code.toString() }))
            }
        }
    }

    private fun logout() {
        setBusy(true)
        ServerClient.IO_EXECUTOR.execute {
            ServerClient.accountLogout(this)
            runOnUiThread {
                Prefs.setAccountEmail(this, "")
                signedCard.visibility = View.GONE
                form.visibility = View.VISIBLE
                setBusy(false)
                setMode(false)
            }
        }
    }

    /** Where the app goes once a person is signed in: the edition's flow. */
    private fun goHome() {
        val next = if (Edition.CLIENT || Prefs.serverConfigured(this)) Edition.HOME else Edition.FIRST_RUN
        startActivity(Intent(this, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
