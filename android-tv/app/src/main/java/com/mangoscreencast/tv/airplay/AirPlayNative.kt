package com.mangoscreencast.tv.airplay

import java.nio.ByteBuffer

/**
 * Calls from the native AirPlay server (UxPlay, see src/main/cpp/airplay_jni.c).
 *
 * Every method runs on one of the server's own threads. Frame buffers are
 * direct buffers over native memory that is only valid until the method
 * returns, so implementations must copy what they need before returning.
 * Never block waiting on the main thread here.
 */
interface AirPlayCallbacks {
    /** Publish a DNS-SD service. `txt` is the wire-format TXT record. Return 0 on success. */
    fun onRegisterService(type: String, name: String, port: Int, txt: ByteArray): Int
    fun onUnregisterService(type: String)

    /** A device wants to connect. Return false to refuse it. */
    fun onClientRequest(deviceId: String, model: String, name: String): Boolean
    fun onConnectionsChanged(open: Int)

    /** Mirroring is about to start. Return 0 if the codec can be decoded, -1 to refuse. */
    /**
     * The frame is `sourceWidth` x `sourceHeight`; the sender's picture fills
     * `width` x `height` at (`x`, `y`) inside it.
     */
    fun onVideoCodec(h265: Boolean, sourceWidth: Int, sourceHeight: Int, x: Int, y: Int, width: Int, height: Int): Int

    /** One access unit, Annex B. Keyframes carry their parameter sets in front. */
    fun onVideoFrame(frame: ByteBuffer, localTimeNs: Long, h265: Boolean)
    fun onVideoPaused(paused: Boolean)
    fun onVideoFlush()
    fun onVideoReset(type: Int)

    /** `ct`: 1 = PCM, 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD. `spf`: samples per frame. */
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean, isMedia: Boolean, audioFormat: Long)
    fun onAudioFrame(frame: ByteBuffer, ct: Int, seq: Int, localTimeNs: Long)
    fun onAudioFlush()

    /** AirPlay volume in dB: -30 (quietest) to 0 (full), -144 = mute. */
    fun onVolume(db: Float)
    fun onConnectionReset(reason: Int)
    fun onLog(level: Int, message: String)
}

/** The native AirPlay server. Only one runs per process. */
object AirPlayNative {
    init {
        // OpenSSL ships inside the APK; load it before the library that needs it.
        System.loadLibrary("crypto")
        System.loadLibrary("mango_airplay")
    }

    /** Log levels understood by the native server. */
    const val LOG_ERROR = 3
    const val LOG_INFO = 6
    const val LOG_DEBUG = 7

    /**
     * Starts the server and advertises it. Returns the TCP port it listens
     * on, or a negative error code.
     *
     * @param hardwareAddress 6 bytes; identifies this TV to Apple devices.
     * @param keyFile where the server's pairing key is kept, so the TV stays
     *   the same device to clients across restarts.
     * @param width, height, refreshRate, maxFps the display Apple devices
     *   should size their stream for.
     * @param h265 advertise HEVC support, which is what lets Macs and recent
     *   iPhones/iPads send more than 1080p.
     */
    @JvmStatic
    external fun nativeStart(
        callbacks: AirPlayCallbacks,
        name: String,
        hardwareAddress: ByteArray,
        keyFile: String,
        width: Int,
        height: Int,
        refreshRate: Int,
        maxFps: Int,
        h265: Boolean,
        logLevel: Int,
    ): Int

    @JvmStatic
    external fun nativeStop()

    @JvmStatic
    external fun nativeIsRunning(): Boolean

    /** Drops every connected device. */
    @JvmStatic
    external fun nativeDisconnectClients()

    /**
     * The display offered from now on. Senders read it right after the
     * connection request, so calling this from [AirPlayCallbacks.onClientRequest]
     * sizes that device's stream. Only call it from there.
     */
    @JvmStatic
    external fun nativeOfferDisplay(width: Int, height: Int, maxFps: Int)
}
