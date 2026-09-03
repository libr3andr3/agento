package tech.yaya.agente

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher: a pure router, no UI (see BOUNDARIES.md flow contract).
 *  - no Yaya account            → AccountActivity (sign in / create; no guest mode)
 *  - not registered             → RegistrationActivity (full-screen step flow)
 *  - registered, no interview   → OnboardingActivity (chat)
 *  - interviewed, apps not set  → AppsSetupActivity (which apps the agent talks on / reads)
 *  - otherwise                  → DashboardActivity
 *
 * "Interviewed" is inferred from the persisted chat transcript until Prefs
 * grows an explicit onboarded flag; DashboardActivity keeps the chat reachable
 * either way, so the heuristic can never strand anyone. The apps step is
 * skipped for installs that predate it (they configured apps in Settings).
 */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = when {
            !Prefs.hasIdentity(this) -> AccountActivity::class.java
            !Prefs.serverConfigured(this) -> RegistrationActivity::class.java
            Prefs.chatTranscript(this).isNullOrBlank() -> OnboardingActivity::class.java
            Prefs.appsSetupPending(this) -> AppsSetupActivity::class.java
            else -> DashboardActivity::class.java
        }
        startActivity(Intent(this, next))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
