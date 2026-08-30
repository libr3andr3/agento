package tech.yaya.agente

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-hosted update channel. The landing publishes `/dl/latest.json`
 * (written by scripts/publish-apk.sh) with the newest build's versionCode,
 * the minimum versionCode still allowed to talk to the server, a sha256 and
 * release notes. We check it when the dashboard opens and when the listener
 * reconnects, show a banner (blocking below minVersionCode), download with
 * DownloadManager, verify the hash and hand the file to the package
 * installer. Sideloaded apps get no Play updates; this is ours.
 */
object UpdateCheck {
    private const val TAG = "agente.update"
    private const val CHANNEL = "agente_updates"
    private const val NOTIF_ID = 7001
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    data class Update(
        val version: String,
        val versionCode: Long,
        val minVersionCode: Long,
        val url: String,
        val sha256: String?,
        val notes: String?,
        val sizeMb: Double,
    ) {
        fun mandatoryFor(installed: Long) = installed < minVersionCode
    }

    fun installedVersionCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    /** latest.json lives next to the APK, on the same origin as the API. */
    private fun manifestUrl(ctx: Context): String =
        Prefs.serverUrl(ctx).trimEnd('/') + "/dl/latest.json"

    /** Network. Returns the parsed manifest or null; caches on success. */
    fun fetch(ctx: Context): Update? {
        return try {
            val conn = URL(manifestUrl(ctx)).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val u = parse(ctx, JSONObject(body)) ?: return null
            Prefs.sp(ctx).edit()
                .putString("update_manifest", body)
                .putLong("update_checked_at", System.currentTimeMillis())
                .apply()
            u
        } catch (t: Throwable) {
            Log.w(TAG, "manifest fetch failed: ${t.message}")
            null
        }
    }

    private fun parse(ctx: Context, j: JSONObject): Update? {
        val code = j.optLong("versionCode", -1L)
        if (code < 0) return null
        val file = j.optString("file")
        val url = j.optString("url").ifBlank {
            if (file.isBlank()) return null else Prefs.serverUrl(ctx).trimEnd('/') + "/dl/" + file
        }
        return Update(
            version = j.optString("version"),
            versionCode = code,
            minVersionCode = j.optLong("minVersionCode", 0L),
            url = url,
            sha256 = j.optString("sha256").takeIf { it.length == 64 },
            notes = j.optString("notes").takeIf { it.isNotBlank() },
            sizeMb = j.optDouble("size_mb", 0.0),
        )
    }

    fun cached(ctx: Context): Update? =
        Prefs.sp(ctx).getString("update_manifest", null)?.let { runCatching { parse(ctx, JSONObject(it)) }.getOrNull() }

    fun stale(ctx: Context): Boolean =
        System.currentTimeMillis() - Prefs.sp(ctx).getLong("update_checked_at", 0L) > TTL_MS

    /** Cached-or-fresh manifest, only when it is newer than what's installed. */
    fun available(ctx: Context, allowNetwork: Boolean): Update? {
        if (BuildConfig.PLAY) return null // Play delivers updates
        val u = (if (allowNetwork && stale(ctx)) fetch(ctx) else null) ?: cached(ctx) ?: return null
        return if (u.versionCode > installedVersionCode(ctx)) u else null
    }

    fun dismissed(ctx: Context, u: Update) =
        Prefs.sp(ctx).getLong("update_dismissed_code", 0L) >= u.versionCode
    fun dismiss(ctx: Context, u: Update) =
        Prefs.sp(ctx).edit().putLong("update_dismissed_code", u.versionCode).apply()

    // ------------------------------------------------------------ background

    /** From the listener: fetch if stale, post one notification per version. */
    fun checkInBackground(ctx: Context) {
        if (BuildConfig.PLAY) return
        val u = available(ctx, allowNetwork = true) ?: return
        if (Prefs.sp(ctx).getLong("update_notified_code", 0L) >= u.versionCode) return
        // Marked only once actually posted: without POST_NOTIFICATIONS yet
        // (fresh install) we must retry on the next reconnect, not give up.
        if (notifyAvailable(ctx, u)) {
            Prefs.sp(ctx).edit().putLong("update_notified_code", u.versionCode).apply()
        }
    }

    private fun notifyAvailable(ctx: Context, u: Update): Boolean {
        if (!OwnerAlerts.canPost(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = Intent(ctx, Screens.HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mandatory = u.mandatoryFor(installedVersionCode(ctx))
        val title = ctx.getString(if (mandatory) R.string.update_required_title else R.string.update_available_title, u.version)
        val body = u.notes ?: ctx.getString(R.string.update_tap_to_install)
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build())
        return true
    }

    // ------------------------------------------------------------ download + install

    private fun updatesDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates").apply { mkdirs() }

    /** Starts the download (system notification shows progress). Returns false if it couldn't. */
    fun download(ctx: Context, u: Update): Boolean = runCatching {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val dir = updatesDir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "agento-${u.version}.apk")
        val req = DownloadManager.Request(Uri.parse(u.url))
            .setTitle(ctx.getString(R.string.update_downloading, u.version))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = dm.enqueue(req)
        Prefs.sp(ctx).edit()
            .putLong("update_download_id", id)
            .putString("update_download_path", target.absolutePath)
            .putString("update_download_sha", u.sha256 ?: "")
            .apply()
        true
    }.onFailure { Log.e(TAG, "download enqueue failed", it) }.getOrDefault(false)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Called by [DownloadReceiver]; verifies the hash and opens the installer. */
    fun onDownloadComplete(ctx: Context, id: Long) {
        val sp = Prefs.sp(ctx)
        if (id != sp.getLong("update_download_id", -1L)) return
        val path = sp.getString("update_download_path", null) ?: return
        val want = sp.getString("update_download_sha", "") ?: ""
        val f = File(path)
        if (!f.exists() || f.length() < 1_000_000) { Log.w(TAG, "download missing/short"); return }
        if (want.isNotEmpty()) {
            val got = runCatching { sha256(f) }.getOrDefault("")
            if (!got.equals(want, ignoreCase = true)) {
                Log.e(TAG, "sha256 mismatch: want $want got $got — refusing to install")
                f.delete()
                return
            }
        }
        install(ctx, f)
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(i) }.onFailure { Log.e(TAG, "installer launch failed", it) }
    }

    /** Android 8+: installs from an app need the per-app "unknown sources" grant. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** DownloadManager tells us the APK landed; verify and install. */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        UpdateCheck.onDownloadComplete(ctx.applicationContext, id)
    }
}
