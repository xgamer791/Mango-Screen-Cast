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
import com.mangoscreencast.tv.display.MirrorProfile
import com.mangoscreencast.tv.media.AudioPlayer
import com.mangoscreencast.tv.media.VideoDecoder
import com.mangoscreencast.tv.media.DecoderSelection
import com.mangoscreencast.tv.mangocast.Decision
import com.mangoscreencast.tv.mangocast.Hello
import com.mangoscreencast.tv.mangocast.MangoCast
import com.mangoscreencast.tv.mangocast.MangoCastServer
import com.mangoscreencast.tv.mangocast.Offer
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
 *
 * Next to it, Windows computers (Mango Cast, protocol/mango-cast.md) can show
 * their screen through the same decoders. One device has the screen at a
 * time: a Windows computer is refused while an Apple device mirrors, and
 * gives way when one starts. While no Windows computer is connected,
 * everything here works exactly as it did without them.
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

    /** Windows computers: their own listener and announcement, so neither can upset AirPlay's. */
    private val windows = MangoCastServer(WindowsSink(), ::log)
    private val windowsAdvertiser = ServiceAdvertiser(context)
    private val windowsLock = Any()

    /** A Windows computer has the screen. Changes only under [windowsLock]. */
    @Volatile
    private var windowsActive = false

    /** The Apple device connected over AirPlay (name, model), for when a Windows computer is done. */
    @Volatile
    private var appleClient: Pair<String, String>? = null
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
        advertiser.onRegistered = { type, name -> log("advertising $type as \"$name\"") }
        // Only Windows computers depend on this one: a failure is noted, AirPlay carries on.
        windowsAdvertiser.onFailure = { reason -> log("Windows: announcing failed: $reason") }
        windowsAdvertiser.onRegistered = { type, name -> log("advertising $type as \"$name\"") }
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
        s.profile?.let { p ->
            appendLine("Offering: ${p.width}x${p.height} @ ${p.maxFps} fps, ${p.codecs} (refresh ${p.refreshRate} Hz)")
            appendLine("Panel: ${p.panel.width}x${p.panel.height}, modes ${p.panel.refreshRates.joinToString { "%.2f".format(it) }} Hz")
            appendLine("Frame rate setting: ${settings.frameRate.fps} fps${p.note?.let { ", $it" } ?: ""}")
        }
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App: ${com.mangoscreencast.tv.BuildConfig.VERSION_NAME} (${com.mangoscreencast.tv.BuildConfig.VERSION_CODE})")
        appendLine("Stay visible when closed: ${if (settings.receiveInBackground) "on" else "off"}, " +
            "background receiver ${if (com.mangoscreencast.tv.ReceiverService.running) "running" else "not running"}, " +
            "player can open by itself: ${if (com.mangoscreencast.tv.MangoApp.instance.canOpenFromBackground()) "yes" else "no (needs Display over other apps)"}")
        appendLine("Decoders:")
        DecoderSelection.describe().forEach { appendLine("  $it") }
        appendLine("Windows computers: " + when {
            !settings.windowsEnabled -> "off"
            windows.port < 0 -> "not listening"
            else -> "listening on port ${windows.port}" + (windows.session?.let { ", ${it.hello.name} (${it.address}) connected" } ?: "")
        })
        if (s.state == State.MIRRORING) {
            val v = video.stats()
            appendLine("Video: ${v.width}x${v.height} ${v.codec} via ${v.decoder}, " +
                "${"%.1f".format(v.fps)} fps, ${"%.1f".format(v.megabitsPerSecond)} Mbit/s, ${v.restarts} restarts")
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

    /** Applies the Windows computers setting, leaving AirPlay as it is. */
    fun applyWindowsSetting() {
        if (owners.isEmpty()) return
        control.post {
            windowsAdvertiser.unregisterAll()
            windows.stop("Windows computers were turned off on ${settings.tvName}.")
            status.profile?.let { startWindows(settings.tvName, it) }
        }
    }

    /** Drops the connected device and returns to the ready screen. */
    fun disconnect() {
        control.post {
            windows.disconnect("Disconnected on the TV.")
            AirPlayNative.nativeDisconnectClients()
            endMirroring()
            update { it.copy(state = State.READY, clientName = null, clientModel = null, paused = false) }
        }
    }

    // --- Server lifecycle (control thread) --------------------------------------

    private fun startServer() {
        val name = settings.tvName
        update { Status(state = State.STARTING, tvName = name) }
        val profile = DisplayCapabilities.profile(context, settings.resolution, settings.frameRate)
        profile.note?.let { log("frame rate: $it") }
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
        } else {
            log("server failed to start: $result")
            update { it.copy(state = State.FAILED, profile = profile, message = describeError(result)) }
        }
        startWindows(name, profile)
    }

    /** Listens for Windows computers and announces the TV to them. Never affects AirPlay. */
    private fun startWindows(name: String, profile: MirrorProfile) {
        if (!settings.windowsEnabled) return
        val port = windows.start()
        if (port < 0) {
            log("Windows: couldn't listen for Windows computers")
            return
        }
        val txt = offer(profile).txtRecord(settings.deviceId, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        windowsAdvertiser.register(MangoCast.SERVICE_TYPE, name, port, txt)
        log("Windows: listening on port $port")
    }

    /** What the TV takes from a Windows computer: what it offers Apple devices. */
    private fun offer(profile: MirrorProfile) = Offer(
        name = settings.tvName,
        maxWidth = profile.width,
        maxHeight = profile.height,
        fps = profile.maxFps,
        h265 = profile.h265,
        audio = settings.soundEnabled,
    )

    private fun stopServer() {
        windowsAdvertiser.unregisterAll()
        windows.stop()
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
        appleClient = (name.ifBlank { null } ?: model) to model
        // A Windows computer has the screen until this device starts mirroring.
        if (windowsActive) return true
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
            appleClient = null
            if (windowsActive) return
            endMirroring()
            update { it.copy(state = State.READY, clientName = null, clientModel = null, paused = false) }
        }
    }

    override fun onVideoCodec(
        h265: Boolean, sourceWidth: Int, sourceHeight: Int, x: Int, y: Int, width: Int, height: Int,
    ): Int {
        log("mirroring starts: ${if (h265) "HEVC" else "H.264"} ${sourceWidth}x$sourceHeight, " +
            "picture ${width}x$height at ($x, $y)")
        if (h265 && status.profile?.h265 == false) return -1
        // The Apple device takes the screen from a Windows computer.
        if (windowsActive) windows.disconnect("An Apple device started mirroring to ${settings.tvName}.")
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
        if (windowsActive) return
        video.stop()
        releaseWifiLock()
        update {
            if (it.state == State.MIRRORING) it.copy(state = State.CONNECTED, paused = false) else it
        }
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean, isMedia: Boolean, audioFormat: Long) {
        log("audio format ct=$ct spf=$spf")
        // While a Windows computer has the screen, its sound plays, not an Apple device's.
        if (windowsActive) return
        audio.enabled = settings.soundEnabled
        audio.configure(ct, spf)
    }

    override fun onAudioFrame(frame: ByteBuffer, ct: Int, seq: Int, localTimeNs: Long) {
        if (!windowsActive) audio.submit(frame, ct)
    }

    override fun onAudioFlush() {
        if (!windowsActive) audio.flush()
    }

    override fun onVolume(db: Float) {
        if (!windowsActive) audio.setVolumeDb(db)
    }

    override fun onConnectionReset(reason: Int) {
        val who = status.clientName
        log("connection reset (reason $reason)")
        appleClient = null
        if (windowsActive) return
        endMirroring()
        update {
            it.copy(
                state = State.READY, clientName = null, clientModel = null, paused = false,
                message = if (reason == 1 && who != null) "Lost the connection to $who." else null,
            )
        }
    }

    override fun onLog(level: Int, message: String) {
        // Info and above from the AirPlay server; skip its multi-line dumps.
        message.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { log(it.trim()) }
    }

    // --- Windows computers (Mango Cast threads) ----------------------------------------

    private inner class WindowsSink : MangoCastServer.Sink {
        override fun onHello(hello: Hello): Decision {
            val s = status
            val profile = s.profile
            return when {
                !settings.windowsEnabled -> Decision.Refuse("off",
                    "Windows computers are turned off in Mango Screen Cast's settings on ${s.tvName}.")
                profile == null -> Decision.Refuse("off", "${s.tvName} is starting. Try again in a moment.")
                s.state == State.MIRRORING -> Decision.Refuse("busy",
                    "${s.clientName ?: "An Apple device"} is mirroring to ${s.tvName} right now.")
                else -> Decision.Accept(offer(profile))
            }
        }

        override fun onStart(session: MangoCastServer.Session) {
            synchronized(windowsLock) { windowsActive = true }
            log("Windows: ${session.hello.name} is showing its screen: ${if (session.h265) "HEVC" else "H.264"} " +
                "${session.width}x${session.height}, sound ${if (session.audio) "on" else "off"}")
            video.latencyMode = settings.latencyMode
            video.onKeyframeNeeded = session::requestKeyframe
            video.start(session.h265, session.width, session.height)
            audio.release()
            if (session.audio) {
                audio.enabled = settings.soundEnabled
                audio.configure(AudioPlayer.CODEC_PCM, 0, MangoCast.AUDIO_RATE)
                // An Apple device's volume doesn't carry over.
                audio.setVolumeDb(0f)
            }
            control.removeCallbacks(statsTicker)
            control.postDelayed(statsTicker, STATS_INTERVAL_MS)
            acquireWifiLock()
            update {
                it.copy(state = State.MIRRORING, clientName = session.hello.name, clientModel = session.hello.os,
                    paused = false, message = null)
            }
            main.post { onMirroringStarted?.invoke() }
        }

        override fun onVideo(session: MangoCastServer.Session, frame: ByteBuffer) {
            // Under the lock, so no Windows picture lands in an Apple device's stream as it takes over.
            synchronized(windowsLock) {
                if (windowsActive && windows.session === session) video.submit(frame, session.h265)
            }
        }

        override fun onAudio(session: MangoCastServer.Session, pcm: ByteBuffer) {
            synchronized(windowsLock) {
                if (windowsActive && windows.session === session) audio.submit(pcm, AudioPlayer.CODEC_PCM)
            }
        }

        override fun onEnd(session: MangoCastServer.Session, reason: String, lost: Boolean) {
            val was = synchronized(windowsLock) {
                val active = windowsActive
                windowsActive = false
                active
            }
            if (!was) return
            video.onKeyframeNeeded = null
            endMirroring()
            val apple = appleClient
            update {
                if (apple != null) {
                    it.copy(state = State.CONNECTED, clientName = apple.first, clientModel = apple.second,
                        paused = false, message = null)
                } else {
                    it.copy(state = State.READY, clientName = null, clientModel = null, paused = false,
                        message = if (lost) reason else null)
                }
            }
        }
    }

    /** While mirroring, logs what the decoder is doing every few seconds. */
    private val statsTicker = object : Runnable {
        override fun run() {
            if (status.state != State.MIRRORING) return
            val v = video.stats()
            log("stats: ${v.width}x${v.height} ${v.codec}, ${"%.1f".format(v.fps)} fps, " +
                "${"%.1f".format(v.megabitsPerSecond)} Mbit/s, ${v.droppedFrames} dropped, ${v.restarts} restarts")
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
