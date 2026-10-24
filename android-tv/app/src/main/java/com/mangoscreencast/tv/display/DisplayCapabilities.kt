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
import kotlin.math.roundToInt

enum class ResolutionSetting(val maxHeight: Int) {
    AUTO(Int.MAX_VALUE),
    UHD_2160(2160),
    QHD_1440(1440),
    FHD_1080(1080),
}

/** The most frames per second Apple devices are told they may send. */
enum class FrameRateSetting(val fps: Int) {
    FPS_60(60),
    FPS_120(120),
    FPS_30(30),
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
    /** Why the TV offers less than the settings ask for, if it does. */
    val note: String? = null,
) {
    val sizeName: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            else -> "${height}p"
        }

    val codecs: String get() = if (h265) "HEVC + H.264" else "H.264"
}

/** What the TV's hardware decoders take; an interface so [DisplayCapabilities.choose] can be tested. */
internal interface DecoderInfo {
    val hevcAvailable: Boolean
    fun supportsSize(mime: String, width: Int, height: Int): Boolean
    fun canDecode(mime: String, width: Int, height: Int, fps: Int): Boolean
}

private object SystemDecoders : DecoderInfo {
    override val hevcAvailable get() = DecoderSelection.hardwareDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC) != null
    override fun supportsSize(mime: String, width: Int, height: Int) = DecoderSelection.supportsSize(mime, width, height)
    override fun canDecode(mime: String, width: Int, height: Int, fps: Int) =
        DecoderSelection.canDecode(mime, width, height, fps)
}

object DisplayCapabilities {
    private val CANDIDATES = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080)
    private const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC

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

    /** The fastest refresh rate any of the screen's modes offers. */
    fun maxRefreshRate(context: Context): Float {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (Build.VERSION.SDK_INT < 23) return display.refreshRate
        return (display.supportedModes.map { it.refreshRate } + display.refreshRate).max()
    }

    fun profile(
        context: Context, setting: ResolutionSetting, frameRate: FrameRateSetting = FrameRateSetting.FPS_60,
    ): MirrorProfile = choose(setting, frameRate, panel(context), maxRefreshRate(context), SystemDecoders)

    /**
     * The largest size the TV's hardware decoder takes, up to 4K. Above
     * 1080p Apple devices only send HEVC, so those sizes need an HEVC
     * decoder.
     *
     * Auto doesn't cap this at the screen mode Android reports: many 4K TVs
     * run their menus at 1080p and report that, while video still plays on
     * the full panel. The decoder is the reliable signal. Every TV HEVC
     * decoder that takes a 4K picture is built for 4K60 streaming apps, so
     * up to 60 fps the size check is enough; the frame-rate tables some
     * vendors publish are often too conservative.
     *
     * Above 60 fps nothing is assumed: the screen needs a mode that fast and
     * the decoder's own tables must list the rate. With Resolution on Auto
     * the picture gets smaller until the decoder can keep up (4K120 is rare,
     * 1440p120 and 1080p120 are common); with a fixed resolution it stays
     * at 60 fps instead.
     */
    internal fun choose(
        resolution: ResolutionSetting, frameRate: FrameRateSetting, panel: Panel, screenMaxHz: Float,
        decoders: DecoderInfo,
    ): MirrorProfile {
        val sizes = CANDIDATES.filter { (width, height) -> height <= resolution.maxHeight && takes(decoders, width, height) }
        val best = sizes.firstOrNull()
        val refresh = refreshFor(panel)
        fun offer(size: Pair<Int, Int>?, fps: Int, refreshRate: Int, note: String? = null): MirrorProfile {
            // Nothing reported 1080p; ask for it anyway and let the decoder try.
            val (width, height) = size ?: (1920 to 1080)
            val h265 = if (size == null) decoders.hevcAvailable else decoders.supportsSize(HEVC, width, height)
            return MirrorProfile(width, height, refreshRate, fps, h265, panel, note)
        }

        val wanted = frameRate.fps
        if (wanted <= 60) return offer(best, wanted, refresh)
        if (screenMaxHz < wanted - 1.5f) {
            return offer(best, 60, refresh,
                "This TV's screen goes up to ${screenMaxHz.roundToInt()} Hz, so it offers 60 fps.")
        }
        val fast = sizes.firstOrNull { (width, height) -> decodesAt(decoders, width, height, wanted) }
        return when {
            fast == null -> offer(best, 60, refresh,
                "The TV's video decoder isn't rated for $wanted fps, so it offers 60 fps.")
            fast != best && resolution != ResolutionSetting.AUTO -> offer(best, 60, refresh,
                "$wanted fps needs a smaller picture on this TV. Set Resolution to Auto to get it.")
            fast != best -> offer(fast, wanted, wanted,
                "$wanted fps at ${fast.first} × ${fast.second}: the decoder isn't rated for $wanted fps at " +
                    "${best!!.first} × ${best.second}.")
            else -> offer(fast, wanted, wanted)
        }
    }

    private fun takes(decoders: DecoderInfo, width: Int, height: Int): Boolean {
        if (decoders.supportsSize(HEVC, width, height)) return true
        return height <= 1080 && decoders.supportsSize(AVC, width, height)
    }

    /** At this size Apple devices send HEVC when offered it, else H.264. */
    private fun decodesAt(decoders: DecoderInfo, width: Int, height: Int, fps: Int): Boolean =
        if (decoders.supportsSize(HEVC, width, height)) decoders.canDecode(HEVC, width, height, fps)
        else decoders.canDecode(AVC, width, height, fps)

    /** 60 Hz when the panel has it (as 59.94 or 60), else its fastest rate up to 60. */
    private fun refreshFor(panel: Panel): Int {
        if (panel.refreshRates.any { abs(it - 60f) < 1f || abs(it - 120f) < 1f }) return 60
        return panel.refreshRates.filter { it <= 61f }.maxOrNull()?.toInt()?.takeIf { it > 0 } ?: 60
    }

    /**
     * Best display mode for a stream of `fps` frames per second. Above 60:
     * the largest mode at that rate. Otherwise native size, refresh closest
     * to 60 (or 120).
     */
    fun preferredMode(display: Display, fps: Int = 60): Display.Mode? {
        if (Build.VERSION.SDK_INT < 23) return null
        val modes = display.supportedModes
        if (fps > 60) {
            modes.filter { abs(it.refreshRate - fps) < 1.5f }
                .maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
                ?.let { return it }
        }
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
