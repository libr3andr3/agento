package tech.yaya.agente

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher. Registered businesses go straight to the dashboard; new users get
 * a single-purpose welcome with one call to action.
 */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.serverConfigured(this)) {
            startActivity(Intent(this, DashboardActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }
        setContentView(R.layout.activity_welcome)
        findViewById<Button>(R.id.welcome_cta).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onResume() {
        super.onResume()
        // Registration finished while we were behind the onboarding screen.
        if (Prefs.serverConfigured(this) && !isFinishing) {
            // Stay put: OnboardingActivity handles its own forward navigation.
        }
    }
}
