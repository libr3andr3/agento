package tech.yaya.agente

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher: a pure router, no UI (see BOUNDARIES.md flow contract).
 *  - not registered            → RegistrationActivity (full-screen step flow)
 *  - registered, no interview  → OnboardingActivity (chat)
 *  - registered + interviewed  → DashboardActivity
 *
 * "Interviewed" is inferred from the persisted chat transcript until Prefs
 * grows an explicit onboarded flag; DashboardActivity keeps the chat reachable
 * either way, so the heuristic can never strand anyone.
 */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = when {
            !Prefs.serverConfigured(this) -> RegistrationActivity::class.java
            Prefs.chatTranscript(this).isNullOrBlank() -> OnboardingActivity::class.java
            else -> DashboardActivity::class.java
        }
        startActivity(Intent(this, next))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
