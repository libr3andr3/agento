package tech.yaya.agente

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class AgenteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Colors are tuned for light mode; budget phones often default to dark,
        // which made green-on-dark unreadable. Force light until a proper
        // dark palette exists.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        OwnerAlerts.ensureChannel(this)
    }
}
