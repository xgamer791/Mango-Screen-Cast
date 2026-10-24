package com.mangoscreencast.tv.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.mangoscreencast.tv.LatencyMode
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Hardware H.264/HEVC decoding straight onto a Surface, tuned for latency:
 * low-latency decoder modes, no buffering beyond what the decoder needs, and
 * frame pacing that never shows two frames in the same refresh.
 *
 * AirPlay senders can't be asked for a keyframe, so the decoder is kept
 * alive while the screen surface comes and goes (its output is parked on an
 * off-screen surface), and the frames since the last keyframe are kept so it
 * can start, or restart after a failure, without waiting for the sender.
 *
 * [submit] is called on the AirPlay thread; everything else on any thread.
 */
class VideoDecoder {
    interface Listener {
        /** Size of the decoded picture. Called on the decoder thread. */
        fun onVideoSize(width: Int, height: Int)
    }

    data class Stats(
        val width: Int,
        val height: Int,
        val codec: String,
        val decoder: String,
        val fps: Double,
        val droppedFrames: Long,
        val decodeMs: Double,
    )

    @Volatile
    var listener: Listener? = null

    @Volatile
    var latencyMode: LatencyMode = LatencyMode.BALANCED

    private val lock = Object()
    private var h265 = false
    private var streamWidth = 1920
    private var streamHeight = 1080
    private var active = false
    private var surface: Surface? = null
    private var vsyncPeriodNs = 16_666_667L

    private var codec: MediaCodec? = null
    private var codecName = ""
    private var config: ByteArray? = null
    @Volatile
    private var needsRestart = false
    private var lastStartAttemptNs = 0L
    private var outputThread: Thread? = null
    @Volatile
    private var outputRunning = false

    /** Decoder output goes nowhere (no screen surface) but decoding continues. */
    @Volatile
    private var parked = false
    private var parkingTexture: SurfaceTexture? = null
    private var parkingSurface: Surface? = null
    private val starter = Executors.newSingleThreadExecutor { Thread(it, "VideoStart") }

    private val gop = GopBuffer(MAX_GOP_BYTES)
    private var scratch = ByteArray(512 * 1024)

    // Pacing and stats (output thread).
    @Volatile
    private var catchUpUntilUs = 0L
    private var lastTargetNs = 0L
    private var rendered = 0L
    private var dropped = 0L
    private var latencyTotalUs = 0L
    private var latencySamples = 0L
    private var outputWidth = 0
    private var outputHeight = 0
    private var statsRendered = 0L
    private var statsTimeNs = System.nanoTime()

    /** A new mirroring stream begins. */
    fun start(h265: Boolean, width: Int, height: Int) = synchronized(lock) {
        releaseCodecLocked()
        gop.clear()
        config = null
        this.h265 = h265
        if (width > 0 && height > 0) {
            streamWidth = width
            streamHeight = height
        }
        active = true
        dropped = 0
        rendered = 0
    }

    /** The stream ended. */
    fun stop() = synchronized(lock) {
        active = false
        releaseCodecLocked()
        parked = false
        gop.clear()
        config = null
    }

    /**
     * Called by the player screen with its surface (null when it goes away).
     * `refreshRate` is the display's current rate. Must return quickly: a
     * null surface is detached before this returns, a new one is started on a
     * background thread.
     */
    fun setSurface(surface: Surface?, refreshRate: Float) {
        synchronized(lock) {
            if (refreshRate > 1f) vsyncPeriodNs = (1_000_000_000.0 / refreshRate).toLong()
            if (surface === this.surface && (surface == null || codec != null)) return
            val codec = codec
            if (codec != null && Build.VERSION.SDK_INT >= 23) {
                try {
                    // Swap outputs without stopping: no keyframe needed.
                    codec.setOutputSurface(surface ?: parking())
                    this.surface = surface
                    parked = surface == null
                    lastTargetNs = 0
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "setOutputSurface failed; restarting the decoder", e)
                }
            }
            releaseCodecLocked()
            this.surface = surface
            parked = false
        }
        if (surface != null) {
            starter.execute {
                synchronized(lock) {
                    if (this.surface === surface && codec == null && active) startFromGopLocked()
                }
            }
        }
    }

    private fun parking(): Surface =
        parkingSurface ?: Surface(SurfaceTexture(0).also { parkingTexture = it }).also { parkingSurface = it }

    fun submit(frame: ByteBuffer, h265: Boolean) {
        val length = frame.remaining()
        if (length <= 0) return
        synchronized(lock) {
            if (!active) return
            if (scratch.size < length) scratch = ByteArray(length + length / 2)
            frame.get(scratch, 0, length)
            val info = NalUnits.inspect(scratch, length, h265)
            val ptsUs = System.nanoTime() / 1000
            val flags = if (info.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

            if (info.keyframe) {
                gop.clear()
                val sets = if (info.configLength > 0) scratch.copyOfRange(0, info.configLength) else null
                if (sets != null && !sets.contentEquals(config)) {
                    // New parameter sets (first frame, rotation, resolution change):
                    // the decoder is rebuilt around them.
                    config = sets
                    needsRestart = true
                }
            }
            if (info.keyframe || !gop.isEmpty) gop.add(scratch, length, ptsUs, flags)

            if (config == null) return
            if (needsRestart || codec == null) {
                // Needs a screen surface to start; until then the GOP buffer keeps frames.
                if (surface == null) return
                // The frame is already in the GOP buffer, so a restart replays it.
                // Don't retry a failing decoder on every frame.
                val now = System.nanoTime()
                if (info.keyframe || now - lastStartAttemptNs > RETRY_INTERVAL_NS) {
                    lastStartAttemptNs = now
                    startFromGopLocked()
                }
                return
            }
            if (!queueLocked(scratch, length, ptsUs, flags)) restartLocked()
        }
    }

    fun stats(): Stats = synchronized(lock) {
        val now = System.nanoTime()
        val elapsed = (now - statsTimeNs).coerceAtLeast(1)
        val fps = (rendered - statsRendered) * 1e9 / elapsed
        statsRendered = rendered
        statsTimeNs = now
        val latency = if (latencySamples > 0) latencyTotalUs.toDouble() / latencySamples / 1000 else 0.0
        latencyTotalUs = 0
        latencySamples = 0
        Stats(outputWidth, outputHeight, if (h265) "HEVC" else "H.264", codecName, fps, dropped, latency)
    }

    // --- Decoder lifecycle (call with lock held) ------------------------------

    private fun startFromGopLocked() {
        releaseCodecLocked()
        val surface = surface ?: return
        val sets = config ?: return
        if (gop.isEmpty) return
        val created = createCodec(surface, sets) ?: return
        codec = created.first
        codecName = created.second
        needsRestart = false
        parked = false
        lastTargetNs = 0
        // Replay what we have; only the newest of these frames gets shown.
        catchUpUntilUs = gop.lastPtsUs
        outputRunning = true
        val codec = created.first
        outputThread = Thread({ outputLoop(codec) }, "VideoOutput").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        for (frame in gop.frames) {
            if (!queueLocked(frame.data, frame.length, frame.ptsUs, frame.flags)) {
                Log.w(TAG, "decoder refused replayed frame")
                break
            }
        }
    }

    private fun restartLocked() {
        Log.w(TAG, "restarting decoder")
        lastStartAttemptNs = System.nanoTime()
        startFromGopLocked()
    }

    private fun releaseCodecLocked() {
        outputRunning = false
        outputThread?.let { thread ->
            if (thread !== Thread.currentThread()) thread.join(500)
        }
        outputThread = null
        codec?.let { c ->
            try {
                c.stop()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
        }
        codec = null
    }

    private fun queueLocked(data: ByteArray, length: Int, ptsUs: Long, flags: Int): Boolean {
        val codec = codec ?: return false
        return try {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) return false
            val buffer = codec.getInputBuffer(index) ?: return false
            buffer.clear()
            if (buffer.capacity() < length) {
                Log.w(TAG, "frame of $length bytes exceeds decoder input buffer ${buffer.capacity()}")
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                return false
            }
            buffer.put(data, 0, length)
            codec.queueInputBuffer(index, 0, length, ptsUs, flags)
            true
        } catch (e: Exception) {
            Log.w(TAG, "queueInputBuffer failed", e)
            false
        }
    }

    private fun createCodec(surface: Surface, sets: ByteArray): Pair<MediaCodec, String>? {
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        for (info in DecoderSelection.decodersFor(mime)) {
            // First try with every low-latency switch; some vendor decoders
            // reject keys they don't know, so retry without the extras.
            for (extras in listOf(true, false)) {
                var codec: MediaCodec? = null
                try {
                    codec = MediaCodec.createByCodecName(info.name)
                    codec.configure(buildFormat(mime, info, sets, extras), surface, null, 0)
                    codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    codec.start()
                    Log.i(TAG, "decoding $mime ${streamWidth}x$streamHeight with ${info.name} (low-latency extras: $extras)")
                    return codec to info.name
                } catch (e: Exception) {
                    Log.w(TAG, "${info.name} failed to start (extras=$extras): ${e.message}")
                    try {
                        codec?.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        Log.e(TAG, "no decoder could start for $mime")
        return null
    }

    private fun buildFormat(mime: String, info: MediaCodecInfo, sets: ByteArray, extras: Boolean): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, streamWidth, streamHeight)
        val units = NalUnits.parameterSets(sets, sets.size, h265)
        if (h265) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(units.fold(ByteArray(0)) { acc, b -> acc + b }))
        } else {
            units.firstOrNull { NalUnits.type(it[startCodeLength(it)], false) == 7 }
                ?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            units.firstOrNull { NalUnits.type(it[startCodeLength(it)], false) == 8 }
                ?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        }
        val pixels = streamWidth.toLong() * streamHeight
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, if (pixels > 2_100_000) 6 shl 20 else 3 shl 20)

        val capabilities = info.getCapabilitiesForType(mime)
        if (Build.VERSION.SDK_INT >= 30 &&
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        }
        if (extras) {
            if (Build.VERSION.SDK_INT >= 23 && DecoderSelection.isQualcomm(info.name)) {
                // Qualcomm scales clocks down to the nominal frame rate unless asked not to.
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            }
            for ((key, value) in VENDOR_LOW_LATENCY_KEYS) format.setInteger(key, value)
        }
        return format
    }

    // --- Output (own thread) ----------------------------------------------------

    private fun outputLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (outputRunning) {
            val index = try {
                codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (e: Exception) {
                if (outputRunning) {
                    Log.w(TAG, "decoder output failed", e)
                    // Not under the lock: releaseCodecLocked() may be joining this thread.
                    needsRestart = true
                }
                return
            }
            when {
                index >= 0 -> render(codec, index, info)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportSize(codec.outputFormat)
            }
        }
    }

    private fun render(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val now = System.nanoTime()
        try {
            if (parked || info.presentationTimeUs < catchUpUntilUs) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            latencyTotalUs += now / 1000 - info.presentationTimeUs
            latencySamples++
            val period = vsyncPeriodNs
            when (latencyMode) {
                LatencyMode.FASTEST -> {
                    codec.releaseOutputBuffer(index, true)
                    rendered++
                }
                LatencyMode.BALANCED, LatencyMode.SMOOTHEST -> {
                    val hold = if (latencyMode == LatencyMode.SMOOTHEST) period else 0L
                    val limit = if (latencyMode == LatencyMode.SMOOTHEST) 3 * period else 2 * period
                    // One frame per refresh: a frame that arrives in the same
                    // refresh as the previous one waits for the next refresh.
                    val target = maxOf(now + hold, lastTargetNs + period)
                    if (target - now > limit) {
                        // More frames than the screen can show (e.g. a 50 Hz TV):
                        // skip one to stay current.
                        codec.releaseOutputBuffer(index, false)
                        dropped++
                        lastTargetNs = now
                    } else {
                        codec.releaseOutputBuffer(index, target)
                        lastTargetNs = target
                        rendered++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseOutputBuffer failed", e)
        }
    }

    private fun reportSize(format: MediaFormat) {
        var width = format.getInteger(MediaFormat.KEY_WIDTH)
        var height = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        }
        if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        outputWidth = width
        outputHeight = height
        listener?.onVideoSize(width, height)
    }

    private fun startCodeLength(unit: ByteArray): Int =
        if (unit.size > 3 && unit[2].toInt() == 1) 3 else 4

    private companion object {
        const val TAG = "MangoVideo"
        const val INPUT_TIMEOUT_US = 50_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val MAX_GOP_BYTES = 32 shl 20
        const val RETRY_INTERVAL_NS = 250_000_000L

        /** Low-latency switches understood by common TV SoC decoders. */
        val VENDOR_LOW_LATENCY_KEYS = listOf(
            "vendor.low-latency.enable" to 1, // MediaTek and others
            "vendor.qti-ext-dec-low-latency.enable" to 1, // Qualcomm
            "vendor.qti-ext-dec-picture-order.enable" to 1,
            "vendor.rtc-ext-dec-low-latency.enable" to 1, // Realtek (many TVs)
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1, // HiSilicon
            "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
        )
    }
}

/** Frames since the most recent keyframe, in decode order, with pooled storage. */
internal class GopBuffer(private val maxBytes: Int) {
    class Frame(var data: ByteArray, var length: Int, var ptsUs: Long, var flags: Int)

    val frames = ArrayList<Frame>()
    private val pool = ArrayList<ByteArray>()
    private var bytes = 0
    private var overflow = false

    val isEmpty: Boolean get() = frames.isEmpty() || overflow
    val lastPtsUs: Long get() = frames.lastOrNull()?.ptsUs ?: 0L

    fun clear() {
        for (frame in frames) if (pool.size < 64) pool += frame.data
        frames.clear()
        bytes = 0
        overflow = false
    }

    fun add(data: ByteArray, length: Int, ptsUs: Long, flags: Int) {
        if (overflow) return
        if (bytes + length > maxBytes) {
            // A very long GOP: give up until the next keyframe rather than grow.
            overflow = true
            return
        }
        val storage = take(length)
        System.arraycopy(data, 0, storage, 0, length)
        frames += Frame(storage, length, ptsUs, flags)
        bytes += length
    }

    private fun take(length: Int): ByteArray {
        val i = pool.indexOfFirst { it.size >= length }
        if (i >= 0) return pool.removeAt(i)
        return ByteArray(((length + 65535) / 65536) * 65536)
    }
}
