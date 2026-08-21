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
        // Perú
        "com.bcp.innovacxion.yapeapp" to "Yape",
        "pe.com.interbank.mobilebanking" to "Interbank (Plin)",
        "com.bbva.nxt_peru" to "BBVA (Plin)",
        "pe.com.scotiabank.blpm.android.client" to "Scotiabank (Plin)",
        // LatAm
        "com.mercadopago.wallet" to "Mercado Pago",
        "com.nequi.MobileApp" to "Nequi",
        "co.com.davivienda.daviplataapp" to "Daviplata",
        "com.nu.production" to "Nubank",
        "com.picpay" to "PicPay",
        "br.com.intermedium" to "Inter",
        "com.itau" to "Itaú",
        "br.com.bb.android" to "Banco do Brasil",
        "com.bradesco" to "Bradesco",
        "br.com.gabba.Caixa" to "Caixa",
        "com.santander.app" to "Santander BR",
        "com.bancoppel.bancoppel" to "BanCoppel",
        "com.bbva.bbvacontigo" to "BBVA México",
        "mx.bancoazteca.bazdigitalmovil" to "Banco Azteca",
        "com.bancodechile.mobile" to "Banco de Chile",
        "cl.tenpo.app" to "Tenpo",
        "cl.bci.mach" to "MACH",
        "com.bancogeneral.yappy" to "Yappy",
        "com.ath.athmovil" to "ATH Móvil",
        "uy.com.prex.prexapp" to "Prex",
        // India
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
        // US / CA / EU / UK
        "com.zellepay.zelle" to "Zelle",
        "com.venmo" to "Venmo",
        "com.squareup.cash" to "Cash App",
        "com.paypal.android.p2pmobile" to "PayPal",
        "com.chase.sig.android" to "Chase",
        "com.infonow.bofa" to "Bank of America",
        "com.wf.wellsfargomobile" to "Wells Fargo",
        "com.revolut.revolut" to "Revolut",
        "com.transferwise.android" to "Wise",
        "es.bizum.app" to "Bizum",
        "com.bbva.bbvacontigo.es" to "BBVA España",
        "es.lacaixa.mobile.android.newwapicon" to "CaixaBank",
        "pt.sibs.android.mbway" to "MB WAY",
        "com.twint.payment" to "TWINT",
        "se.bankgirot.swish" to "Swish",
        "no.dnb.vipps" to "Vipps",
        "dk.danskebank.mobilepay" to "MobilePay",
        "com.blik.app" to "BLIK",
        "nl.abnamro.tikkie" to "Tikkie",
        "com.monzo.android" to "Monzo",
        "com.starlingbank.android" to "Starling",
        // Asia / Africa
        "com.eg.android.AlipayGphone" to "Alipay",
        "com.tencent.mm" to "WeChat Pay",
        "jp.ne.paypay.android.app" to "PayPay",
        "com.kakao.talk" to "KakaoPay",
        "viva.republica.toss" to "Toss",
        "com.globe.gcash.android" to "GCash",
        "com.paymaya" to "Maya",
        "com.gojek.app" to "GoPay",
        "ovo.id" to "OVO",
        "id.dana" to "DANA",
        "com.mobile.legends.tng" to "Touch 'n Go",
        "com.grabtaxi.passenger" to "GrabPay",
        "com.mservice.momotransfer" to "MoMo",
        "vn.com.vng.zalopay" to "ZaloPay",
        "com.safaricom.mpesa.lifestyle" to "M-PESA",
        "team.opay.pay" to "OPay",
        "com.transsnet.palmpay" to "PalmPay",
        "com.bkash.customerapp" to "bKash",
        "com.konasl.nagad" to "Nagad",
        "com.techlogix.mobilinkcustomer" to "JazzCash",
        "pk.com.telenor.phoenix" to "Easypaisa",
        "com.stcpay.wallet" to "STC Pay",
        "com.getmoby" to "InstaPay EG",
        "com.vodafone.cash" to "Vodafone Cash",
    )

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
