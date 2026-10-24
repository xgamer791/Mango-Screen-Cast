package com.mangoscreencast.tv.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import com.mangoscreencast.tv.LatencyMode
import java.nio.ByteBuffer

/**
 * Hardware H.264/HEVC decoding straight onto a Surface.
 *
 * Three threads, so the network never waits on the decoder:
 *  - the AirPlay thread ([submit]) copies each frame into a queue and returns
 *    at once. A receiver that reads slowly makes Apple devices lower their
 *    bitrate, so this matters for picture quality as much as for delay.
 *  - the input thread feeds the decoder, and builds or rebuilds it.
 *  - the output thread shows decoded frames, one per display refresh.
 *
 * AirPlay senders can't be asked for a keyframe and send few of them, so
 * the decoder is never torn down unless a replacement can start from the
 * last keyframe: the frames since then are kept, and when the screen
 * surface goes away the decoder keeps running against an off-screen one.
 */
class VideoDecoder internal constructor(
    factory: CodecFactory?,
    /** setOutputSurface() exists from Android 6. */
    private val canSwapSurfaces: Boolean,
) {
    constructor() : this(null, Build.VERSION.SDK_INT >= 23)

    private val factory: CodecFactory = factory ?: MediaCodecFactory { note(it) }

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
        val megabitsPerSecond: Double,
        val droppedFrames: Long,
        val decodeMs: Double,
        val restarts: Int,
    )

    @Volatile
    var listener: Listener? = null

    @Volatile
    var latencyMode: LatencyMode = LatencyMode.BALANCED

    /** Receives one line per notable event (decoder start, restart, errors). */
    @Volatile
    var log: ((String) -> Unit)? = null

    private val pool = FramePool(MAX_POOLED_BYTES)

    // Input queue: AirPlay thread -> input thread.
    private val queueLock = Object()
    private val queue = ArrayDeque<Frame>()
    private var stream: Stream? = null
    private var wake = false

    // The decoder. The input thread owns its lifecycle; the main thread only
    // swaps its output surface. Never held for more than one short call.
    private val codecLock = Object()
    private var codec: VideoCodec? = null
    private var codecStream: Stream? = null
    private var codecName = ""
    private var surface: Surface? = null
    private var parkingSurface: Surface? = null
    private var parkingTexture: SurfaceTexture? = null
    private var outputThread: Thread? = null

    @Volatile
    private var outputRunning = false

    @Volatile
    private var parked = false

    @Volatile
    private var needsRestart = false

    @Volatile
    private var vsyncPeriodNs = 16_666_667L

    // Output-thread state and counters.
    @Volatile
    private var catchUpUntilUs = 0L
    private var lastTargetNs = 0L
    @Volatile
    private var rendered = 0L
    @Volatile
    private var dropped = 0L
    @Volatile
    private var bytesReceived = 0L
    @Volatile
    private var restarts = 0
    private var latencyTotalUs = 0L
    private var latencySamples = 0L
    private var outputWidth = 0
    private var outputHeight = 0
    private var statsRendered = 0L
    private var statsBytes = 0L
    private var statsTimeNs = System.nanoTime()

    private val boosted = ThreadLocal<Boolean>()

    /** One mirroring stream, from the sender's SETUP to its TEARDOWN. */
    private inner class Stream(val h265: Boolean, val hintWidth: Int, val hintHeight: Int) {
        val gop = GopBuffer(MAX_GOP_BYTES, pool)
        val startedNs = System.nanoTime()
        var config: ByteArray? = null
        var size: SpsParser.Size? = null
        var lastStartAttemptNs = 0L
        @Volatile
        var dropUntilKeyframe = false
        val isCurrent: Boolean get() = synchronized(queueLock) { stream === this }
    }

    /** A new mirroring stream begins. */
    fun start(h265: Boolean, width: Int, height: Int) {
        stop()
        val next = Stream(h265, width.coerceAtLeast(16), height.coerceAtLeast(16))
        synchronized(queueLock) {
            stream = next
            bytesReceived = 0
            statsBytes = 0
        }
        rendered = 0
        dropped = 0
        restarts = 0
        Thread({ inputLoop(next) }, "VideoInput").start()
    }

    /** The stream ended. */
    fun stop() {
        synchronized(queueLock) {
            stream = null
            queue.forEach(pool::recycle)
            queue.clear()
            queueLock.notifyAll()
        }
        synchronized(codecLock) {
            releaseCodecLocked()
            codecStream = null
            parked = false
        }
    }

    /**
     * Called by the player screen with its surface (null when it goes away).
     * Returns quickly: the decoder keeps running and only its output moves.
     */
    fun setSurface(surface: Surface?, refreshRate: Float) {
        synchronized(codecLock) {
            if (refreshRate > 1f) vsyncPeriodNs = (1_000_000_000.0 / refreshRate).toLong()
            if (surface === this.surface) return
            this.surface = surface
            val codec = codec
            if (codec != null) {
                if (canSwapSurfaces) {
                    try {
                        codec.setOutputSurface(surface ?: parking())
                        parked = surface == null
                        lastTargetNs = 0
                        return
                    } catch (e: Exception) {
                        note("couldn't move the decoder to the new screen (${e.message}); restarting it")
                    }
                }
                // It must not keep drawing into a surface that is going away.
                releaseCodecLocked()
                needsRestart = true
            }
        }
        wakeInput()
    }

    /** The display switched modes (e.g. to 60 Hz after the player asked for it). */
    fun setRefreshRate(refreshRate: Float) {
        if (refreshRate > 1f) vsyncPeriodNs = (1_000_000_000.0 / refreshRate).toLong()
        lastTargetNs = 0
    }

    /** One access unit from the sender. Copies it and returns without waiting on the decoder. */
    fun submit(frame: ByteBuffer, h265: Boolean) {
        boostCurrentThread()
        val length = frame.remaining()
        if (length <= 0) return
        val item = pool.obtain(length)
        frame.get(item.data, 0, length)
        item.length = length
        item.ptsUs = System.nanoTime() / 1000
        val info = NalUnits.inspect(item.data, length, h265)
        item.keyframe = info.keyframe
        item.configLength = info.configLength
        synchronized(queueLock) {
            val current = stream
            if (current == null) {
                pool.recycle(item)
                return
            }
            bytesReceived += length
            if (queue.size >= MAX_QUEUED_FRAMES) {
                // The decoder has stalled for seconds. Holding more only adds
                // delay; skip ahead to the next keyframe instead.
                queue.forEach(pool::recycle)
                queue.clear()
                current.dropUntilKeyframe = true
                note("decoder fell behind; skipping to the next keyframe")
            }
            queue.addLast(item)
            queueLock.notifyAll()
        }
    }

    fun stats(): Stats {
        val now = System.nanoTime()
        val elapsed = (now - statsTimeNs).coerceAtLeast(1)
        val renderedNow = rendered
        val bytesNow = bytesReceived
        val fps = (renderedNow - statsRendered) * 1e9 / elapsed
        val mbps = (bytesNow - statsBytes) * 8.0 * 1e3 / elapsed
        statsRendered = renderedNow
        statsBytes = bytesNow
        statsTimeNs = now
        val latency = if (latencySamples > 0) latencyTotalUs.toDouble() / latencySamples / 1000 else 0.0
        latencyTotalUs = 0
        latencySamples = 0
        val h265 = synchronized(queueLock) { stream?.h265 } ?: false
        return Stats(outputWidth, outputHeight, if (h265) "HEVC" else "H.264", codecName, fps, mbps,
            dropped, latency, restarts)
    }

    // --- Input thread ------------------------------------------------------------

    private fun inputLoop(s: Stream) {
        setPriority()
        while (true) {
            var item: Frame? = null
            synchronized(queueLock) {
                while (stream === s && queue.isEmpty() && !wake) queueLock.wait()
                if (stream !== s) {
                    s.gop.clear()
                    return
                }
                wake = false
                item = queue.removeFirstOrNull()
            }
            val frame = item
            if (frame != null) {
                handle(s, frame)
            } else if (needsRestart || !hasCodec(s)) {
                // Woken by a surface change or a decoder error.
                tryStart(s, force = true)
            }
        }
    }

    private fun handle(s: Stream, frame: Frame) {
        var kept = false
        try {
            if (s.dropUntilKeyframe) {
                if (!frame.keyframe) return
                s.dropUntilKeyframe = false
                needsRestart = true
            }
            if (frame.keyframe) {
                s.gop.clear()
                if (frame.configLength > 0) {
                    val sets = frame.data.copyOfRange(0, frame.configLength)
                    if (!sets.contentEquals(s.config)) {
                        // First frame, rotation or a resolution change: build the
                        // decoder for exactly this stream.
                        s.config = sets
                        s.size = SpsParser.parseFromConfig(sets, s.h265)
                        s.size?.let { note("stream is ${if (s.h265) "HEVC" else "H.264"} ${it.width}x${it.height}") }
                        if (hasCodec(s)) needsRestart = true
                    }
                }
            }
            if (frame.keyframe || s.gop.hasKeyframe) kept = s.gop.add(frame)
            if (s.config == null) return

            if (needsRestart || !hasCodec(s)) {
                tryStart(s, force = frame.keyframe)
                return
            }
            if (!queueFrame(s, frame, STEADY_TIMEOUT_MS)) {
                note("decoder stopped taking frames; restarting it")
                needsRestart = true
                tryStart(s, force = true)
            }
        } finally {
            if (!kept) pool.recycle(frame)
        }
    }

    private fun hasCodec(s: Stream): Boolean = synchronized(codecLock) { codec != null && codecStream === s }

    /**
     * (Re)builds the decoder and replays the frames since the last keyframe,
     * keyframe first. Leaves any working decoder alone if that isn't possible.
     */
    private fun tryStart(s: Stream, force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - s.lastStartAttemptNs < RETRY_INTERVAL_NS) return
        val sets = s.config ?: return
        if (!s.gop.hasKeyframe) return // nothing to start from until the sender's next keyframe
        val target: Surface
        val parkedStart: Boolean
        synchronized(codecLock) {
            val visible = surface
            if (visible != null) {
                target = visible
                parkedStart = false
            } else if (canSwapSurfaces && now - s.startedNs > PARKED_START_DELAY_NS) {
                // No player screen (e.g. mirroring started in the background):
                // decode anyway so the picture is ready the moment it opens.
                target = parking()
                parkedStart = true
            } else {
                return
            }
            if (codec != null) restarts++
            releaseCodecLocked()
        }
        s.lastStartAttemptNs = now

        val width = s.size?.width ?: s.hintWidth
        val height = s.size?.height ?: s.hintHeight
        val created = factory.create(s.h265, width, height, sets, target) ?: return
        synchronized(codecLock) {
            if (!s.isCurrent || (surface !== target && !parkedStart)) {
                // The stream ended or the screen changed while we were building.
                created.release()
                if (s.isCurrent) needsRestart = true
                return
            }
            codec = created
            codecStream = s
            codecName = created.name
            parked = parkedStart
            needsRestart = false
            lastTargetNs = 0
            // Replayed frames are decoded but only the newest is shown.
            catchUpUntilUs = s.gop.lastPtsUs
            outputRunning = true
            outputThread = Thread({ outputLoop(created) }, "VideoOutput").apply { start() }
        }

        for ((index, frame) in s.gop.frames.withIndex()) {
            // Decoders can take a while to accept their first frame, especially at 4K.
            val timeout = if (index == 0) FIRST_FRAME_TIMEOUT_MS else STEADY_TIMEOUT_MS
            if (!queueFrame(s, frame, timeout)) {
                note("decoder didn't accept the stream start (frame $index); will retry")
                synchronized(codecLock) { releaseCodecLocked() }
                needsRestart = true
                return
            }
        }
        note("decoding ${width}x$height with ${created.name}${if (parkedStart) " (off-screen until the player opens)" else ""}")

        // The player may have appeared while we were starting off-screen.
        synchronized(codecLock) {
            val visible = surface
            if (parked && visible != null && codecStream === s && canSwapSurfaces) {
                try {
                    codec?.setOutputSurface(visible)
                    parked = false
                } catch (e: Exception) {
                    releaseCodecLocked()
                    needsRestart = true
                }
            }
        }
        if (needsRestart) wakeInput()
    }

    /** Queues one frame, waiting up to `timeoutMs` for an input buffer. */
    private fun queueFrame(s: Stream, frame: Frame, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            synchronized(codecLock) {
                val c = codec?.takeIf { codecStream === s } ?: return false
                try {
                    val index = c.dequeueInputBuffer(INPUT_POLL_US)
                    if (index >= 0) {
                        val buffer = c.inputBuffer(index) ?: return false
                        buffer.clear()
                        if (buffer.capacity() < frame.length) {
                            note("a ${frame.length}-byte frame doesn't fit the decoder's ${buffer.capacity()}-byte buffer")
                            c.queueInputBuffer(index, 0, frame.ptsUs, false)
                            return false
                        }
                        buffer.put(frame.data, 0, frame.length)
                        c.queueInputBuffer(index, frame.length, frame.ptsUs, frame.keyframe)
                        return true
                    }
                } catch (e: Exception) {
                    note("decoder input error: ${e.message}")
                    return false
                }
            }
            if (System.nanoTime() > deadline || !s.isCurrent) return false
        }
    }

    private fun releaseCodecLocked() {
        outputRunning = false
        outputThread?.let { thread -> if (thread !== Thread.currentThread()) thread.join(500) }
        outputThread = null
        codec?.release()
        codec = null
    }

    private fun parking(): Surface =
        parkingSurface ?: Surface(SurfaceTexture(0).also { parkingTexture = it }).also { parkingSurface = it }

    private fun wakeInput() {
        synchronized(queueLock) {
            wake = true
            queueLock.notifyAll()
        }
    }

    // --- Output thread ----------------------------------------------------------

    private fun outputLoop(codec: VideoCodec) {
        setPriority()
        val info = OutputInfo()
        while (outputRunning) {
            val index = try {
                codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (e: Exception) {
                if (e is MediaCodec.CodecException && e.isTransient) continue
                if (outputRunning) {
                    note("decoder error: ${e.message}")
                    needsRestart = true
                    wakeInput()
                }
                return
            }
            when {
                index >= 0 -> render(codec, index, info)
                index == VideoCodec.FORMAT_CHANGED -> reportSize(codec)
            }
        }
    }

    private fun render(codec: VideoCodec, index: Int, info: OutputInfo) {
        val now = System.nanoTime()
        try {
            if (parked || info.ptsUs < catchUpUntilUs) {
                codec.discard(index)
                return
            }
            latencyTotalUs += now / 1000 - info.ptsUs
            latencySamples++
            val period = vsyncPeriodNs
            when (latencyMode) {
                LatencyMode.FASTEST -> {
                    codec.render(index, null)
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
                        codec.discard(index)
                        dropped++
                        lastTargetNs = now
                    } else {
                        codec.render(index, target)
                        lastTargetNs = target
                        rendered++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseOutputBuffer failed", e)
        }
    }

    private fun reportSize(codec: VideoCodec) {
        val (width, height) = codec.outputSize() ?: return
        outputWidth = width
        outputHeight = height
        listener?.onVideoSize(width, height)
    }

    // --- Helpers ----------------------------------------------------------------

    private fun note(message: String) {
        Log.i(TAG, message)
        log?.invoke(message)
    }

    /** The AirPlay network thread reads the stream; keep it ahead of everything else. */
    private fun boostCurrentThread() {
        if (boosted.get() == true) return
        boosted.set(true)
        setPriority()
    }

    private fun setPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "MangoVideo"
        const val INPUT_POLL_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val FIRST_FRAME_TIMEOUT_MS = 3_000L
        const val STEADY_TIMEOUT_MS = 2_000L
        const val RETRY_INTERVAL_NS = 500_000_000L
        const val PARKED_START_DELAY_NS = 1_500_000_000L
        const val MAX_QUEUED_FRAMES = 240
        const val MAX_GOP_BYTES = 48 shl 20
        const val MAX_POOLED_BYTES = 24 shl 20
    }
}

/** One received access unit. Storage is pooled and reused. */
internal class Frame(var data: ByteArray) {
    var length = 0
    var ptsUs = 0L
    var keyframe = false
    var configLength = 0
}

/**
 * Reuses frame storage so steady streaming doesn't churn the heap (garbage
 * collection pauses show up as stutter). Thread-safe.
 */
internal class FramePool(private val maxPooledBytes: Int) {
    private val free = ArrayList<Frame>()
    private var pooledBytes = 0

    @Synchronized
    fun obtain(size: Int): Frame {
        // A buffer at most twice as big as needed, so small frames don't pin big arrays.
        val index = free.indexOfFirst { it.data.size >= size && it.data.size <= size * 2 + 16384 }
        if (index >= 0) {
            val frame = free.removeAt(index)
            pooledBytes -= frame.data.size
            return frame
        }
        return Frame(ByteArray((size + 4095) and 4095.inv()))
    }

    @Synchronized
    fun recycle(frame: Frame) {
        if (pooledBytes + frame.data.size > maxPooledBytes) return
        free += frame
        pooledBytes += frame.data.size
    }
}

/**
 * Frames since the most recent keyframe, so a decoder can (re)start without
 * the sender's help. Bounded by the memory the frames actually use.
 */
internal class GopBuffer(private val maxBytes: Int, private val pool: FramePool) {
    val frames = ArrayList<Frame>()
    private var bytes = 0
    private var overflow = false

    /** True when the buffer starts with a keyframe and has everything after it. */
    val hasKeyframe: Boolean get() = frames.isNotEmpty() && !overflow
    val lastPtsUs: Long get() = frames.lastOrNull()?.ptsUs ?: 0L

    fun clear() {
        frames.forEach(pool::recycle)
        frames.clear()
        bytes = 0
        overflow = false
    }

    /** Takes ownership of `frame` and returns true, or returns false if full. */
    fun add(frame: Frame): Boolean {
        if (overflow) return false
        if (bytes + frame.data.size > maxBytes) {
            // A very long run without a keyframe: stop keeping frames until the next one.
            frames.forEach(pool::recycle)
            frames.clear()
            bytes = 0
            overflow = true
            return false
        }
        frames += frame
        bytes += frame.data.size
        return true
    }
}
