package com.mangoscreencast.tv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mangoscreencast.tv.BuildConfig
import com.mangoscreencast.tv.Settings
import com.mangoscreencast.tv.UpdateMode
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Keeps the app up to date from this repository's GitHub Releases. Every
 * release carries `MangoScreenCast.apk` and a `version.json` describing it;
 * the app compares version codes, downloads the APK, checks its SHA-256 and
 * hands it to Android's package installer.
 *
 * The first update asks for confirmation on the TV. From Android 12 on, once
 * the app has installed itself, later updates usually go through silently.
 *
 * It looks whenever the app comes to the front or a device stops mirroring,
 * and every few minutes while the home screen is up, so a new release
 * reaches the TV within minutes of the next idle moment, not hours later.
 */
class AppUpdater(
    private val context: Context,
    private val settings: Settings,
    private val log: (String) -> Unit,
) {
    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        /** Android needs "Install unknown apps" allowed for this app first. */
        data class NeedsPermission(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    fun interface Listener {
        fun onUpdateState(state: State)
    }

    @Volatile
    var state: State = State.Idle
        private set

    /** Asked before an automatic install: false while something is mirroring. */
    var isIdle: () -> Boolean = { true }

    @Volatile
    private var lastAttemptMs = 0L
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Updater") }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onUpdateState(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Checks unless one was made a moment ago (and updates are on). Called on
     * every screen start and whenever the TV becomes free, so this only
     * keeps a burst of those from asking GitHub several times over.
     */
    fun checkIfDue() {
        if (settings.updateMode == UpdateMode.OFF) return
        val now = System.currentTimeMillis()
        if (state is State.Failed) {
            // Offline or GitHub unreachable: try again, but not on every screen change.
            if (now - lastAttemptMs < RETRY_INTERVAL_MS) return
        } else if (now - settings.lastUpdateCheckMs < CHECK_INTERVAL_MS) {
            // Still offer an update found earlier.
            (state as? State.Available)?.let { maybeInstallAutomatically(it.release) }
            return
        }
        checkNow()
    }

    fun checkNow() {
        if (state is State.Checking || state is State.Downloading || state is State.Installing) return
        lastAttemptMs = System.currentTimeMillis()
        set(State.Checking)
        worker.execute {
            val result = try {
                val release = fetchLatest()
                settings.lastUpdateCheckMs = System.currentTimeMillis()
                if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
                    log("update available: ${release.versionName}")
                    State.Available(release)
                } else {
                    State.UpToDate
                }
            } catch (e: NotPublished) {
                log("update check: no release visible at ${BuildConfig.UPDATE_BASE_URL}")
                State.Failed(NOT_PUBLISHED_MESSAGE)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                log("update check failed: ${e.message ?: e.javaClass.simpleName}")
                State.Failed("Couldn't check for updates (${e.message ?: e.javaClass.simpleName})")
            }
            set(result)
            if (result is State.Available) maybeInstallAutomatically(result.release)
        }
    }

    /** The user chose "Update". */
    fun install() {
        val release = when (val s = state) {
            is State.Available -> s.release
            is State.NeedsPermission -> s.release
            else -> return
        }
        if (!canInstall()) {
            set(State.NeedsPermission(release))
            openInstallPermissionSettings()
            return
        }
        worker.execute { downloadAndInstall(release, automatic = false) }
    }

    private fun maybeInstallAutomatically(release: Release) {
        if (settings.updateMode != UpdateMode.AUTOMATIC) return
        main.post {
            if (!isIdle()) return@post
            if (!canInstall()) {
                set(State.NeedsPermission(release))
                return@post
            }
            worker.execute { downloadAndInstall(release, automatic = true) }
        }
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Some TV builds hide this page; it's under Apps > Security & restrictions.
            Log.w(TAG, "can't open the unknown-sources settings", e)
        }
    }

    // --- Worker thread -----------------------------------------------------------

    private class NotPublished : Exception()

    private fun fetchLatest(): Release? {
        val base = BuildConfig.UPDATE_BASE_URL
        val connection = open(base + VERSION_FILE)
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                // No release yet, or one the TV can't see: GitHub answers the
                // same for a private repository's releases.
                HttpURLConnection.HTTP_NOT_FOUND -> throw NotPublished()
                else -> throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(body, base)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndInstall(release: Release, automatic: Boolean) {
        try {
            val file = File(context.cacheDir, "update.apk")
            download(release, file)
            if (automatic && !isIdle()) {
                // Mirroring started (or the app closed) during the download; try again later.
                set(State.Available(release))
                return
            }
            set(State.Installing(release))
            log("installing ${release.versionName}")
            install(file)
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            log("update failed: ${e.message}")
            set(State.Failed("Update failed (${e.message ?: e.javaClass.simpleName})"))
        }
    }

    private fun download(release: Release, file: File) {
        set(State.Downloading(release, 0))
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("download returned HTTP ${connection.responseCode}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        received += n
                        val percent = if (release.size > 0) (received * 100 / release.size).toInt().coerceIn(0, 100) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            set(State.Downloading(release, percent))
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hash.equals(release.sha256, ignoreCase = true)) {
                file.delete()
                throw IllegalStateException("downloaded file doesn't match its checksum")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= 31) {
            // Lets Android 12+ update without a prompt once this app installed itself.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            // Installed from the app's own screen, so bring it back afterwards.
            settings.reopenAfterUpdate = true
            session.commit(pending.intentSender)
        }
    }

    /** Called by [InstallResultReceiver]. */
    internal fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> log("waiting for the update to be confirmed on the TV")
            PackageInstaller.STATUS_SUCCESS -> log("update installed")
            else -> {
                log("update not installed: ${message ?: status}")
                set(State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled"
                    else "Update not installed (${message ?: "error $status"})"
                ))
            }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MangoScreenCast/${BuildConfig.VERSION_NAME}")
        }

    private fun set(next: State) {
        state = next
        main.post { listeners.forEach { it.onUpdateState(next) } }
    }

    companion object {
        private const val TAG = "MangoUpdater"
        const val VERSION_FILE = "version.json"
        /** How close together two checks may be: the home screen asks again every [RECHECK_INTERVAL_MS]. */
        const val CHECK_INTERVAL_MS = 60 * 1000L
        /** While the home screen stays up, how often it looks for a new release. */
        const val RECHECK_INTERVAL_MS = 10 * 60 * 1000L
        const val RETRY_INTERVAL_MS = 10 * 60 * 1000L
        const val NOT_PUBLISHED_MESSAGE =
            "GitHub shows this TV no releases. If the repository is private, the TV can't see them."

        /** Reads a release's version.json. Relative APK names resolve against `baseUrl`. */
        fun parse(json: String, baseUrl: String): Release? {
            val o = JSONObject(json)
            val code = o.optInt("versionCode", -1)
            val sha = o.optString("sha256", "")
            if (code <= 0 || sha.length != 64) return null
            val apk = o.optString("apk", "MangoScreenCast.apk")
            return Release(
                versionCode = code,
                versionName = o.optString("versionName", code.toString()),
                apkUrl = if (apk.startsWith("https://")) apk else baseUrl + apk,
                sha256 = sha,
                size = o.optLong("size", 0L),
            )
        }
    }
}
