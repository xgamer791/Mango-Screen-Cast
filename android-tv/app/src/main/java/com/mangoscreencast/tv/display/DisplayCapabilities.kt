package com.mangoscreencast.tv.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.mangoscreencast.tv.media.DecoderSelection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ResolutionSetting(val maxHeight: Int) {
    AUTO(Int.MAX_VALUE),
    UHD_2160(2160),
    QHD_1440(1440),
    FHD_1080(1080),
}

/** The physical screen: Android TVs often run their UI at 1080p on a 4K panel. */
data class Panel(val width: Int, val height: Int, val refreshRates: List<Float>)

/** What the TV tells Macs, iPhones and iPads it can show. They size their stream to it. */
data class MirrorProfile(
    val width: Int,
    val height: Int,
    val refreshRate: Int,
    val maxFps: Int,
    val h265: Boolean,
    val panel: Panel,
) {
    val sizeName: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            else -> "${height}p"
        }

    val codecs: String get() = if (h265) "HEVC + H.264" else "H.264"
}

object DisplayCapabilities {
    private val CANDIDATES = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080)
    private const val FPS = 60

    fun panel(context: Context): Panel {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (Build.VERSION.SDK_INT >= 23) {
            val modes = (display.supportedModes.toList() + display.mode)
            val best = modes.maxBy { it.physicalWidth.toLong() * it.physicalHeight }
            val width = max(best.physicalWidth, best.physicalHeight)
            val height = min(best.physicalWidth, best.physicalHeight)
            val rates = modes
                .filter { max(it.physicalWidth, it.physicalHeight) == width && min(it.physicalWidth, it.physicalHeight) == height }
                .map { it.refreshRate }
                .distinctBy { (it * 100).toInt() }
                .sortedDescending()
            return Panel(width, height, rates)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Panel(
            max(metrics.widthPixels, metrics.heightPixels),
            min(metrics.widthPixels, metrics.heightPixels),
            listOf(display.refreshRate),
        )
    }

    /**
     * The largest size the TV's hardware decoder takes, up to 4K, at 60 fps.
     * Above 1080p Apple devices only send HEVC, so those sizes need an HEVC
     * decoder.
     *
     * Auto doesn't cap this at the screen mode Android reports: many 4K TVs
     * run their menus at 1080p and report that, while video still plays on
     * the full panel. The decoder is the reliable signal. Every TV HEVC
     * decoder that takes a 4K picture is built for 4K60 streaming apps, so
     * the size check is enough; the frame-rate tables some vendors publish
     * are often too conservative.
     */
    fun profile(context: Context, setting: ResolutionSetting): MirrorProfile {
        val panel = panel(context)
        val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
        val avc = MediaFormat.MIMETYPE_VIDEO_AVC
        val hevcAvailable = DecoderSelection.hardwareDecoder(hevc) != null
        for ((width, height) in CANDIDATES) {
            if (height > setting.maxHeight) continue
            val hevcOk = DecoderSelection.supportsSize(hevc, width, height)
            if (height > 1080 && !hevcOk) continue
            if (height <= 1080 && !hevcOk && !DecoderSelection.supportsSize(avc, width, height)) continue
            return MirrorProfile(width, height, refreshFor(panel), FPS, hevcOk, panel)
        }
        // Nothing reported 1080p; ask for it anyway and let the decoder try.
        return MirrorProfile(1920, 1080, refreshFor(panel), FPS, hevcAvailable, panel)
    }

    /** 60 Hz when the panel has it (as 59.94 or 60), else its fastest rate up to 60. */
    private fun refreshFor(panel: Panel): Int {
        if (panel.refreshRates.any { abs(it - 60f) < 1f || abs(it - 120f) < 1f }) return 60
        return panel.refreshRates.filter { it <= 61f }.maxOrNull()?.toInt()?.takeIf { it > 0 } ?: 60
    }

    /** Best display mode for showing a 60 fps stream: native size, refresh closest to 60 (or 120). */
    fun preferredMode(display: Display): Display.Mode? {
        if (Build.VERSION.SDK_INT < 23) return null
        val modes = display.supportedModes
        val maxArea = modes.maxOfOrNull { it.physicalWidth.toLong() * it.physicalHeight } ?: return null
        return modes
            .filter { it.physicalWidth.toLong() * it.physicalHeight == maxArea }
            .minByOrNull { mode ->
                val rate = mode.refreshRate
                // Multiples of 60 show 60 fps evenly; prefer exactly 60 over 120.
                when {
                    abs(rate - 60f) < 1f -> 0f
                    abs(rate - 120f) < 1f -> 1f
                    else -> 10f + abs(rate - 60f)
                }
            }
    }
}
