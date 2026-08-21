package tech.yaya.agente

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import java.text.Normalizer
import java.util.Locale

/**
 * Decides whether a notification from ANY app looks like "money arrived",
 * so the server can match it against what a customer owes. Nothing here is
 * tied to one country: Yape in Lima, PhonePe in Pune, Pix in São Paulo and
 * Zelle in Miami all reduce to a currency-qualified amount plus an incoming
 * verb in the app's language.
 *
 * Two tiers keep false positives down:
 *  - KNOWN wallets/banks: any notification carrying an amount is forwarded
 *    (the server still classifies direction and currency).
 *  - Any OTHER app: forwarded only with amount + credit verb + no debit verb,
 *    and never from messaging apps, the system, or ourselves.
 * The server's parser is the authority; this is only the gate that decides
 * what leaves the phone.
 */
object PaymentDetector {

    /** Package → human label, for the log and the server's `source`. */
    val KNOWN: Map<String, String> = mapOf(
        // ---- Tier 1: launch markets (Perú, then Panamá #2, Colombia, Chile)
        // Perú — Yape/Plin
        "com.bcp.innovacxion.yapeapp" to "Yape",
        "pe.com.interbank.mobilebanking" to "Interbank (Plin)",
        "com.bbva.nxt_peru" to "BBVA (Plin)",
        "pe.com.scotiabank.blpm.android.client" to "Scotiabank (Plin)",
        // Panamá — Yappy (Banco General) is the rail; Nequi PA, Banistmo, BAC
        "com.yappy" to "Yappy",
        "com.bgeneral" to "Banco General",
        "pa.com.nequi.MobileApp" to "Nequi Panamá",
        "com.banistmo.transaccional.personas" to "Banistmo",
        "net.bac.sbe.android" to "BAC Credomatic",
        // Colombia — Nequi, Daviplata, Bre-B inside every bank app
        "com.nequi.MobileApp" to "Nequi",
        "com.davivienda.daviplataapp" to "Daviplata",
        "com.davivienda.daviviendaapp" to "Davivienda",
        "co.com.bancolombia.personas.superapp" to "Mi Bancolombia",
        "com.todo1.mobile" to "Bancolombia",
        "com.bbva.bbvacolombia" to "BBVA Colombia",
        "com.lulobank.lulo" to "Lulo Bank",
        "com.nu.production" to "Nu",
        // Chile — transferencia is king; BancoEstado, MACH, Tenpo, Mercado Pago
        "net.veritran.becl.prod" to "BancoEstado",
        "cl.bci.sismo.mach" to "MACH",
        "cl.tenpo.app" to "Tenpo",
        "cl.santander.smartphone" to "Santander Chile",
        "cl.bancochile.mi_banco" to "Banco de Chile",
        "com.mercadopago.wallet" to "Mercado Pago",

        // ---- Tier 2: highest WhatsApp penetration worldwide (BR, IN, IT, ES, MX, ZA, AR, ID, NG…)
        // Brasil — Pix
        "com.picpay" to "PicPay",
        "br.com.intermedium" to "Inter",
        "com.itau" to "Itaú",
        "br.com.bb.android" to "Banco do Brasil",
        "com.bradesco" to "Bradesco",
        "br.com.gabba.Caixa" to "Caixa",
        "com.santander.app" to "Santander BR",
        // India — UPI
        "com.phonepe.app" to "PhonePe",
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "in.amazon.mShop.android.shopping" to "Amazon Pay",
        "com.dreamplug.androidapp" to "CRED",
        "com.sbi.lotusintouch" to "SBI YONO",
        "com.csam.icici.bank.imobile" to "iMobile (ICICI)",
        "com.snapwork.hdfc" to "HDFC",
        "com.axis.mobile" to "Axis Mobile",
        "com.msf.kbank.mobile" to "Kotak",
        // Italia / España — Bizum, Satispay, PayPal
        "es.bizum.app" to "Bizum",
        "com.bbva.bbvacontigo.es" to "BBVA España",
        "es.lacaixa.mobile.android.newwapicon" to "CaixaBank",
        "com.satispay.customer" to "Satispay",
        "com.paypal.android.p2pmobile" to "PayPal",
        "com.revolut.revolut" to "Revolut",
        // México — SPEI / CoDi
        "com.bancoppel.bancoppel" to "BanCoppel",
        "com.bbva.bbvacontigo" to "BBVA México",
        "mx.bancoazteca.bazdigitalmovil" to "Banco Azteca",
        "com.banorte.mobile" to "Banorte",
        // Argentina / Uruguay / Paraguay / Bolivia / Ecuador
        "ar.com.santander.rio.mbanking" to "Santander Río",
        "com.uala" to "Ualá",
        "uy.com.prex.prexapp" to "Prex",
        "py.com.tigo.money" to "Tigo Money",
        "com.bancogeneral.yappy" to "Yappy (legacy)",
        // US / CA / UK / DE
        "com.zellepay.zelle" to "Zelle",
        "com.venmo" to "Venmo",
        "com.squareup.cash" to "Cash App",
        "com.chase.sig.android" to "Chase",
        "com.infonow.bofa" to "Bank of America",
        "com.wf.wellsfargomobile" to "Wells Fargo",
        "com.transferwise.android" to "Wise",
        "com.monzo.android" to "Monzo",
        "com.starlingbank.android" to "Starling",
        // Europe instant rails
        "pt.sibs.android.mbway" to "MB WAY",
        "com.twint.payment" to "TWINT",
        "se.bankgirot.swish" to "Swish",
        "no.dnb.vipps" to "Vipps",
        "dk.danskebank.mobilepay" to "MobilePay",
        "com.blik.app" to "BLIK",
        "nl.abnamro.tikkie" to "Tikkie",
        // Africa / Middle East
        "com.safaricom.mpesa.lifestyle" to "M-PESA",
        "team.opay.pay" to "OPay",
        "com.transsnet.palmpay" to "PalmPay",
        "com.stcpay.wallet" to "STC Pay",
        "com.vodafone.cash" to "Vodafone Cash",
        // Asia-Pacific
        "id.dana" to "DANA",
        "ovo.id" to "OVO",
        "com.gojek.app" to "GoPay",
        "com.globe.gcash.android" to "GCash",
        "com.paymaya" to "Maya",
        "com.grabtaxi.passenger" to "GrabPay",
        "com.mservice.momotransfer" to "MoMo",
        "vn.com.vng.zalopay" to "ZaloPay",
        "com.bkash.customerapp" to "bKash",
        "com.konasl.nagad" to "Nagad",
        "com.techlogix.mobilinkcustomer" to "JazzCash",
        "pk.com.telenor.phoenix" to "Easypaisa",
        "com.eg.android.AlipayGphone" to "Alipay",
        "com.tencent.mm" to "WeChat Pay",
        "jp.ne.paypay.android.app" to "PayPay",
        "com.kakao.talk" to "KakaoPay",
        "viva.republica.toss" to "Toss",
    )
    // Any app NOT listed still gets through on the amount + credit-verb
    // heuristic below — the list only lowers the bar, it never closes the door.

    private val SYSTEM_PREFIXES = listOf("android", "com.android.", "com.google.android.gms", "com.sec.android", "com.miui", "com.samsung")

    // Currency-qualified number: a symbol/ISO/word before or after digits.
    private val SYMBOL = Regex(
        """(?i)(?:s/\.?|r\$|rd\$|mx\$|us\$|nt\$|hk\$|s\$|rs\.?|bs|ksh|gh₵|e£|zł|kč|ft|lei|rp|rm|dh|[$€£¥₹₩₱฿₫₦₺₪₴₲₡৳])\s*\d[\d.,' ]*|\b[A-Z]{3}\s*\d[\d.,' ]*|\d[\d.,' ]*\s*(?:[$€£¥₹₩₱฿₫₦₺₪₴₲₡৳]|kr|zł|kč|lei|soles|reais|rupees|rupias|euros|dollars|d[oó]lares|pesos|bolivianos|shillings|naira|cedis|taka|yen|won|baht|dong|ringgit|rupiah|[A-Z]{3}\b)"""
    )

    private val CREDIT = listOf(
        "recibiste", "te envio", "te yapeo", "te plineo", "te plinearon", "abono", "te transfirio", "has recibido",
        "te depositaron", "pago recibido", "confirmacion de pago", "te pago", "te enviaron",
        "voce recebeu", "recebeu", "pix recebido", "recebida",
        "you received", "received", "credited", "sent you", "paid you", "payment from", "deposited", "money received",
        "vous avez recu", "recu de", "virement recu", "erhalten", "gutschrift", "eingegangen", "hai ricevuto", "accredito", "ontvangen",
        "प्राप्त", "मिले", "जमा", "credit hua", "paisa aaya", "diterima", "masuk", "menerima", "natanggap", "umepokea",
        "aldiniz", "hesabiniza", "تم استلام", "استلمت", "وصلك", "إيداع",
    )
    private val DEBIT = listOf(
        "enviaste", "pagaste", "yapeaste", "plineaste", "transferiste", "cargo", "compra", "consumo", "retiro", "debito",
        "voce enviou", "enviou", "pagou", "pix enviado", "you sent", "you paid", "debited", "sent to", "paid to", "payment to",
        "withdrawal", "purchase", "spent", "gesendet", "abgebucht", "hai inviato", "addebito", "verzonden",
        "भेजे", "भुगतान किया", "bheje", "debit hua", "terkirim", "dikirim", "gonderdiniz", "تم إرسال", "أرسلت",
    )

    data class Hit(val label: String, val packageName: String, val title: String, val text: String)

    fun inspect(ctx: Context, sbn: StatusBarNotification): Hit? {
        val pkg = sbn.packageName ?: return null
        if (pkg == ctx.packageName) return null
        if (SupportedApps.isSupported(pkg)) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        if (text.isBlank()) return null

        val full = "$title $text"
        val known = KNOWN[pkg]
        if (!SYMBOL.containsMatchIn(full)) return null
        if (known == null) {
            if (SYSTEM_PREFIXES.any { pkg.startsWith(it) }) return null
            val f = fold(full)
            if (DEBIT.any { f.contains(fold(it)) }) return null
            if (CREDIT.none { f.contains(fold(it)) }) return null
        }
        return Hit(known ?: appLabel(ctx, pkg), pkg, title, text)
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
