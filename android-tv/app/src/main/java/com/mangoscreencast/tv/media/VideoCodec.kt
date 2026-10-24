package com.mangoscreencast.tv.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/** The few decoder operations [VideoDecoder] uses, so its logic can be tested without hardware. */
internal interface VideoCodec {
    val name: String

    /** An input buffer index, or a negative value if none freed up within the timeout. */
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun inputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, size: Int, ptsUs: Long, keyframe: Boolean)

    /**
     * An output buffer index (and its timestamp in `out`), [FORMAT_CHANGED],
     * or another negative value when nothing is ready.
     */
    fun dequeueOutputBuffer(out: OutputInfo, timeoutUs: Long): Int
    fun outputSize(): Pair<Int, Int>?

    /** Shows the buffer at (or after) `timestampNs`, or as soon as possible when null. */
    fun render(index: Int, timestampNs: Long?)
    fun discard(index: Int)
    fun setOutputSurface(surface: Surface)
    fun release()

    companion object {
        const val FORMAT_CHANGED = MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
    }
}

internal class OutputInfo {
    var ptsUs = 0L
}

internal fun interface CodecFactory {
    /** A started decoder for `config` (Annex B parameter sets), drawing into `target`, or null. */
    fun create(h265: Boolean, width: Int, height: Int, config: ByteArray, target: Surface): VideoCodec?
}

/** Hardware decoders through MediaCodec, with each vendor's low-latency switches. */
internal class MediaCodecFactory(private val note: (String) -> Unit) : CodecFactory {
    override fun create(h265: Boolean, width: Int, height: Int, config: ByteArray, target: Surface): VideoCodec? {
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        for (info in DecoderSelection.decodersFor(mime)) {
            // First with every low-latency switch; some vendor decoders reject
            // keys they don't know, so retry without the extras.
            for (extras in listOf(true, false)) {
                var codec: MediaCodec? = null
                try {
                    codec = MediaCodec.createByCodecName(info.name)
                    codec.configure(buildFormat(mime, info, config, width, height, h265, extras), target, null, 0)
                    codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    codec.start()
                    Log.i(TAG, "decoding $mime ${width}x$height with ${info.name} (low-latency extras: $extras)")
                    return AndroidVideoCodec(codec, info.name)
                } catch (e: Exception) {
                    Log.w(TAG, "${info.name} failed to start (extras=$extras): ${e.message}")
                    codec?.let { AndroidVideoCodec(it, info.name).release() }
                }
            }
        }
        note("no decoder could start for $mime ${width}x$height")
        return null
    }

    private fun buildFormat(
        mime: String, info: MediaCodecInfo, config: ByteArray, width: Int, height: Int, h265: Boolean, extras: Boolean,
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        val units = NalUnits.parameterSets(config, config.size, h265)
        if (h265) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(units.fold(ByteArray(0)) { acc, b -> acc + b }))
        } else {
            units.firstOrNull { NalUnits.type(it[startCodeLength(it)], false) == 7 }
                ?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            units.firstOrNull { NalUnits.type(it[startCodeLength(it)], false) == 8 }
                ?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        }
        // Room for the largest keyframes a 4K stream at full quality produces.
        val pixels = width.toLong() * height
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, if (pixels > 2_100_000) 8 shl 20 else 4 shl 20)

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

    private fun startCodeLength(unit: ByteArray): Int =
        if (unit.size > 3 && unit[2].toInt() == 1) 3 else 4

    private companion object {
        const val TAG = "MangoVideo"

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

private class AndroidVideoCodec(private val codec: MediaCodec, override val name: String) : VideoCodec {
    private val info = MediaCodec.BufferInfo()

    override fun dequeueInputBuffer(timeoutUs: Long) = codec.dequeueInputBuffer(timeoutUs)

    override fun inputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun queueInputBuffer(index: Int, size: Int, ptsUs: Long, keyframe: Boolean) =
        codec.queueInputBuffer(index, 0, size, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)

    override fun dequeueOutputBuffer(out: OutputInfo, timeoutUs: Long): Int {
        val index = codec.dequeueOutputBuffer(info, timeoutUs)
        if (index >= 0) out.ptsUs = info.presentationTimeUs
        return index
    }

    override fun outputSize(): Pair<Int, Int>? {
        val format = codec.outputFormat
        var width = format.getInteger(MediaFormat.KEY_WIDTH)
        var height = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        }
        if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        return width to height
    }

    override fun render(index: Int, timestampNs: Long?) {
        if (timestampNs == null) codec.releaseOutputBuffer(index, true) else codec.releaseOutputBuffer(index, timestampNs)
    }

    override fun discard(index: Int) = codec.releaseOutputBuffer(index, false)

    override fun setOutputSurface(surface: Surface) {
        if (Build.VERSION.SDK_INT < 23) throw UnsupportedOperationException("needs Android 6")
        codec.setOutputSurface(surface)
    }

    override fun release() {
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }
}
