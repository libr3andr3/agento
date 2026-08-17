package tech.yaya.agente

/** Messaging apps agente knows how to parse, keyed by Android package name. */
data class SupportedApp(
    val packageName: String,
    val displayName: String
)

object SupportedApps {
    val ALL = listOf(
        SupportedApp("com.whatsapp", "WhatsApp"),
        SupportedApp("com.whatsapp.w4b", "WhatsApp Business"),
        SupportedApp("com.instagram.android", "Instagram"),
        SupportedApp("com.facebook.orca", "Facebook Messenger"),
        SupportedApp("com.facebook.mlite", "Messenger Lite"),
        SupportedApp("com.facebook.katana", "Facebook"),
        SupportedApp("org.telegram.messenger", "Telegram"),
        SupportedApp("com.google.android.apps.messaging", "Google Messages (SMS)")
    )

    private val byPackage = ALL.associateBy { it.packageName }

    fun get(packageName: String): SupportedApp? = byPackage[packageName]
    fun isSupported(packageName: String): Boolean = byPackage.containsKey(packageName)
}

/**
 * Payment apps whose notifications we parse (never reply to) so the server
 * can verify that a customer's Yape/Plin transfer actually arrived.
 */
object PaymentApps {
    val ALL = listOf(
        SupportedApp("com.bcp.innovacxion.yapeapp", "Yape"),
        SupportedApp("pe.com.interbank.mobilebanking", "Interbank (Plin)"),
        SupportedApp("com.bbva.nxt_peru", "BBVA (Plin)"),
        SupportedApp("pe.com.scotiabank.blpm.android.client", "Scotiabank (Plin)")
    )
    private val byPackage = ALL.associateBy { it.packageName }
    fun get(packageName: String): SupportedApp? = byPackage[packageName]
}
