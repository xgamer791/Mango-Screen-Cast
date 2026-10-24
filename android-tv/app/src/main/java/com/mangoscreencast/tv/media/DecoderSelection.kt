package com.mangoscreencast.tv.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Finds hardware video decoders and what they can do. */
object DecoderSelection {
    private val decoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    }

    fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.")
    }

    fun isQualcomm(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.qcom.") || n.startsWith("c2.qti.")
    }

    /** Decoders for `mime`, hardware first. Secure-only variants are skipped. */
    fun decodersFor(mime: String): List<MediaCodecInfo> =
        decoders
            .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .filter { !it.name.endsWith(".secure") }
            .sortedBy { if (isSoftware(it)) 1 else 0 }

    fun hardwareDecoder(mime: String): MediaCodecInfo? = decodersFor(mime).firstOrNull { !isSoftware(it) }

    /** True if a hardware decoder can do `width`x`height` at `fps` (either orientation). */
    fun canDecode(mime: String, width: Int, height: Int, fps: Int): Boolean {
        val info = hardwareDecoder(mime) ?: return false
        val video = info.getCapabilitiesForType(mime).videoCapabilities ?: return false
        return supports(video, width, height, fps) || supports(video, height, width, fps)
    }

    private fun supports(video: MediaCodecInfo.VideoCapabilities, width: Int, height: Int, fps: Int): Boolean {
        if (!video.isSizeSupported(width, height)) return false
        if (Build.VERSION.SDK_INT >= 29) {
            val points = video.supportedPerformancePoints
            if (!points.isNullOrEmpty()) {
                val wanted = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps)
                if (points.any { it.covers(wanted) }) return true
            }
        }
        return try {
            video.getSupportedFrameRatesFor(width, height).upper >= fps - 1
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
