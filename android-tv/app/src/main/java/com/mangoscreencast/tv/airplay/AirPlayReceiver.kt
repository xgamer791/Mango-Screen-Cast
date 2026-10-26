package com.mangoscreencast.tv.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.mangoscreencast.tv.Settings
import com.mangoscreencast.tv.display.DisplayCapabilities
import com.mangoscreencast.tv.display.FrameRateSetting
import com.mangoscreencast.tv.display.MirrorProfile
import com.mangoscreencast.tv.display.ResolutionSetting
import com.mangoscreencast.tv.media.AudioPlayer
import com.mangoscreencast.tv.media.VideoDecoder
import com.mangoscreencast.tv.media.DecoderSelection
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the AirPlay server while something needs it (the app's screens, or
 * the background service), and routes what it receives to the decoders.
 */
class AirPlayReceiver(private val context: Context, private val settings: Settings) : AirPlayCallbacks {
    enum class State { STOPPED, STARTING, READY, CONNECTED, MIRRORING, FAILED }

    data class Status(
        val state: State = State.STOPPED,
        val tvName: String = "",
        val profile: MirrorProfile? = null,
        val clientName: String? = null,
        val clientModel: String? = null,
        val paused: Boolean = false,
        val message: String? = null,
        /** The TV's own AirPlay, when it is on next to this app. */
        val otherReceiver: OtherReceiver? = null,
    )

    fun interface Listener {
        fun onStatus(status: Status)
    }

    /** Called on the main thread when mirroring starts and the player should come up. */
    var onMirroringStarted: (() -> Unit)? = null

    val video = VideoDecoder()
    val audio = AudioPlayer()

    @Volatile
    var status = Status()
        private set

    private val advertiser = ServiceAdvertiser(context)
    private val others = OtherReceivers(context)
    private val main = Handler(Looper.getMainLooper())
    private val control = Handler(HandlerThread("AirPlayControl").apply { start() }.looper)
    private val owners = mutableSetOf<Any>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var wifiLock: WifiManager.WifiLock? = null
    private val recentLog = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        advertiser.onFailure = { reason ->
            log("advertising failed: $reason")
            update { it.copy(state = State.FAILED, message = "Couldn't announce the TV on the network ($reason).") }
        }
        advertiser.onRegistered = { type, name ->
            log("advertising $type as \"$name\"")
            if (type == OtherReceivers.SERVICE_TYPE) others.ownNames = others.ownNames + name
        }
        others.onChanged = { other ->
            if (other != null) {
                log("this TV's own AirPlay is on too: \"${other.name}\" on port ${other.port}" +
                    (other.model?.let { " ($it)" } ?: ""))
            }
            update { it.copy(otherReceiver = other) }
        }
        video.log = ::log
    }

    /**
     * Adds a line to the log shown under Settings > Diagnostics, and to
     * logcat (tag MangoAirPlay) so it can be followed with adb.
     */
    fun log(line: String) {
        Log.i(TAG, line)
        synchronized(recentLog) {
            recentLog.addLast("${timeFormat.format(Date())} $line")
            while (recentLog.size > MAX_LOG_LINES) recentLog.removeFirst()
        }
    }

    /** Plain-text report for troubleshooting. */
    fun diagnostics(): String = buildString {
        val s = status
        appendLine("State: ${s.state}${s.message?.let { " ($it)" } ?: ""}")
        appendLine("TV name: ${s.tvName}")
        s.otherReceiver?.let {
            appendLine("Also on this TV: \"${it.name}\" on port ${it.port}${it.model?.let { m -> " ($m)" } ?: ""}, " +
                "the TV's own AirPlay; it mirrors at 1080p")
        }
        s.profile?.let { p ->
            appendLine("Offering: ${p.width}x${p.height} @ ${p.maxFps} fps, ${p.codecs} (refresh ${p.refreshRate} Hz)")
            appendLine("Panel: ${p.panel.width}x${p.panel.height}, modes ${p.panel.refreshRates.joinToString { "%.2f".format(it) }} Hz")
            appendLine("Settings: resolution ${settings.resolution.name}, frame rate ${settings.frameRate.fps} fps")
            p.note?.let { appendLine("Note: $it") }
        }
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App: ${com.mangoscreencast.tv.BuildConfig.VERSION_NAME} (${com.mangoscreencast.tv.BuildConfig.VERSION_CODE})")
        appendLine("Stay visible when closed: ${if (settings.receiveInBackground) "on" else "off"}, " +
            "background receiver ${if (com.mangoscreencast.tv.ReceiverService.running) "running" else "not running"}, " +
            "player can open by itself: ${if (com.mangoscreencast.tv.MangoApp.instance.canOpenFromBackground()) "yes" else "no (needs Display over other apps)"}")
        appendLine("Decoders:")
        DecoderSelection.describe().forEach { appendLine("  $it") }
        if (s.state == State.MIRRORING) {
            val v = video.stats()
            appendLine("Video: ${v.width}x${v.height} ${v.codec} via ${v.decoder}, " +
                "${"%.1f".format(v.fps)} fps in, ${"%.1f".format(v.shownFps)} shown, " +
                "${"%.1f".format(v.megabitsPerSecond)} Mbit/s, ${v.droppedFrames} dropped, " +
                "${v.skippedFrames} skipped, ${v.restarts} restarts")
        }
        appendLine()
        appendLine("Recent log:")
        synchronized(recentLog) { recentLog.forEach { appendLine(it) } }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStatus(status)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Keeps the server running while `owner` holds it. Main thread. */
    fun acquire(owner: Any) {
        if (owners.add(owner) && owners.size == 1) control.post { startServer() }
    }

    fun release(owner: Any) {
        if (owners.remove(owner) && owners.isEmpty()) control.post { stopServer() }
    }

    /** Applies changed settings (name, resolution). */
    fun restart() {
        if (owners.isEmpty()) return
        control.post {
            stopServer()
            startServer()
        }
    }

    /** Drops the connected device and returns to the ready screen. */
    fun disconnect() {
        control.post {
            AirPlayNative.nativeDisconnectClients()
            endMirroring()
            update { it.copy(state = State.READY, clientName = null, clientModel = null, paused = false) }
        }
    }

    // --- Server lifecycle (control thread) --------------------------------------

    private fun startServer() {
        val name = settings.tvName
        update { Status(state = State.STARTING, tvName = name) }
        others.ownNames = setOf(name)
        // Forced to 4K at 60 fps, whatever Resolution and Frame rate say, until the gear's menu has them.
        val profile = DisplayCapabilities.profile(context, ResolutionSetting.UHD_2160, FrameRateSetting.FPS_60)
        profile.note?.let { log(it) }
        val result = try {
            AirPlayNative.nativeStart(
                this, name, hardwareAddress(), File(context.filesDir, "airplay.key").absolutePath,
                profile.width, profile.height, profile.refreshRate, profile.maxFps, profile.h265,
                AirPlayNative.LOG_INFO,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "native AirPlay server unavailable", e)
            ERROR_NO_LIBRARY
        }
        if (result > 0) {
            log("server ready on port $result: ${profile.width}x${profile.height} @ ${profile.maxFps} fps, ${profile.codecs}")
            update { it.copy(state = State.READY, profile = profile, message = null) }
            others.start()
        } else {
            log("server failed to start: $result")
            update { it.copy(state = State.FAILED, profile = profile, message = describeError(result)) }
        }
    }

    private fun stopServer() {
        others.stop()
        try {
            AirPlayNative.nativeStop()
        } catch (_: Throwable) {
        }
        advertiser.unregisterAll()
        endMirroring()
        update { Status(state = State.STOPPED, tvName = settings.tvName) }
    }

    private fun endMirroring() {
        video.stop()
        audio.release()
        releaseWifiLock()
    }

    /**
     * AirPlay identifies receivers by a MAC-style id. Android doesn't expose
     * the real one, so derive a stable, locally administered one.
     */
    private fun hardwareAddress(): ByteArray {
        val uuid = UUID.fromString(settings.deviceId)
        val bytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        val address = bytes.copyOf(6)
        address[0] = ((address[0].toInt() and 0xFC) or 0x02).toByte()
        return address
    }

    private fun describeError(code: Int): String = when (code) {
        ERROR_NO_LIBRARY -> "This build is missing its AirPlay component. Reinstall the app."
        -5 -> "Couldn't open a network port for AirPlay. Restart the TV and try again."
        -6 -> "Couldn't announce the TV on the network. Check that the TV is on Wi-Fi or Ethernet."
        else -> "AirPlay couldn't start (error $code)."
    }

    // --- Status -----------------------------------------------------------------

    private fun update(change: (Status) -> Status) {
        val next = synchronized(this) {
            status = change(status)
            status
        }
        main.post { listeners.forEach { it.onStatus(next) } }
    }

    // --- Native callbacks (AirPlay threads) ------------------------------------------

    override fun onRegisterService(type: String, name: String, port: Int, txt: ByteArray): Int =
        advertiser.register(type, name, port, txt)

    override fun onUnregisterService(type: String) = advertiser.unregister(type)

    override fun onClientRequest(deviceId: String, model: String, name: String): Boolean {
        log("connection from $name ($model)")
        update {
            it.copy(
                state = if (it.state == State.MIRRORING) State.MIRRORING else State.CONNECTED,
                clientName = name.ifBlank { null } ?: model,
                clientModel = model,
                message = null,
            )
        }
        return true
    }

    override fun onConnectionsChanged(open: Int) {
        if (open == 0) {
            endMirroring()
            update { it.copy(state = State.READY, clientName = null, clientModel = null, paused = false) }
        }
    }

    override fun onVideoCodec(
        h265: Boolean, sourceWidth: Int, sourceHeight: Int, x: Int, y: Int, width: Int, height: Int,
    ): Int {
        // What the device chose to send for the size the TV offered: it fits
        // its screen inside that size, keeping its shape, so a 4K offer gets
        // about 1000x2160 from an iPhone held upright.
        val offered = status.profile?.let { " for the TV's ${it.width}x${it.height} offer" } ?: ""
        log("mirroring starts: ${if (h265) "HEVC" else "H.264"} ${sourceWidth}x$sourceHeight$offered, " +
            "picture ${width}x$height at ($x, $y)")
        if (h265 && status.profile?.h265 == false) return -1
        video.latencyMode = settings.latencyMode
        video.start(h265, sourceWidth, sourceHeight)
        control.removeCallbacks(statsTicker)
        control.postDelayed(statsTicker, STATS_INTERVAL_MS)
        acquireWifiLock()
        update { it.copy(state = State.MIRRORING, paused = false) }
        main.post { onMirroringStarted?.invoke() }
        return 0
    }

    override fun onVideoFrame(frame: ByteBuffer, localTimeNs: Long, h265: Boolean) = video.submit(frame, h265)

    override fun onVideoPaused(paused: Boolean) = update { it.copy(paused = paused) }

    override fun onVideoFlush() {}

    override fun onVideoReset(type: Int) {
        // The sender stopped mirroring (or another device took over).
        log("mirroring stopped (reset type $type)")
        video.stop()
        releaseWifiLock()
        update {
            if (it.state == State.MIRRORING) it.copy(state = State.CONNECTED, paused = false) else it
        }
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean, isMedia: Boolean, audioFormat: Long) {
        log("audio format ct=$ct spf=$spf")
        audio.enabled = settings.soundEnabled
        audio.configure(ct, spf)
    }

    override fun onAudioFrame(frame: ByteBuffer, ct: Int, seq: Int, localTimeNs: Long) = audio.submit(frame, ct)

    override fun onAudioFlush() = audio.flush()

    override fun onVolume(db: Float) = audio.setVolumeDb(db)

    override fun onConnectionReset(reason: Int) {
        val who = status.clientName
        log("connection reset (reason $reason)")
        endMirroring()
        update {
            it.copy(
                state = State.READY, clientName = null, clientModel = null, paused = false,
                message = if (reason == 1 && who != null) "Lost the connection to $who." else null,
            )
        }
    }

    override fun onLog(level: Int, message: String) {
        // Info and above from the AirPlay server, which the JNI layer has
        // already written to logcat whole. Its multi-line dumps (the sender's
        // once-a-second report on its own stream, as plist XML) would only
        // fill Diagnostics with "<?xml" lines: read those with adb.
        if (message.contains('\n')) return
        message.trim().takeIf { it.isNotBlank() }?.let { log(it) }
    }

    /** While mirroring, logs what the decoder is doing every few seconds. */
    private val statsTicker = object : Runnable {
        override fun run() {
            if (status.state != State.MIRRORING) return
            val v = video.stats()
            log("stats: ${v.width}x${v.height} ${v.codec}, ${"%.1f".format(v.fps)} fps in, " +
                "${"%.1f".format(v.shownFps)} shown, ${"%.1f".format(v.megabitsPerSecond)} Mbit/s, " +
                "${"%.1f".format(v.decodeMs)} ms decode, ${v.droppedFrames} dropped, ${v.skippedFrames} skipped, " +
                "${v.restarts} restarts")
            control.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    // --- Wi-Fi ---------------------------------------------------------------------

    /** Keeps the TV's Wi-Fi out of power save while mirroring, which otherwise adds latency spikes. */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = try {
            wifi.createWifiLock(mode, "MangoMirroring").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Wi-Fi lock unavailable", e)
            null
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
        }
        wifiLock = null
    }

    private companion object {
        const val TAG = "MangoAirPlay"
        const val ERROR_NO_LIBRARY = -100
        const val MAX_LOG_LINES = 150
        const val STATS_INTERVAL_MS = 10_000L
    }
}
