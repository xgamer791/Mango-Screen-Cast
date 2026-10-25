package com.mangoscreencast.tv.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.annotation.RequiresApi
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

/**
 * The most frames per second Apple devices are told they may send. 120 is
 * the default; a TV that can't show or decode it offers 60 instead.
 */
enum class FrameRateSetting(val fps: Int) {
    FPS_120(120),
    FPS_60(60),
    FPS_30(30),
}

/** The physical screen: Android TVs often run their UI at 1080p on a 4K panel. */
data class Panel(val width: Int, val height: Int, val refreshRates: List<Float>)

/** A display mode the TV offers, sideways (width the larger side). */
internal data class ScreenMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float) {
    val pixels: Long get() = width.toLong() * height
}

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

    /** Either way round. */
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
            val modes = tvShaped(screenModes(display))
            val best = modes.maxBy { it.pixels }
            val rates = modes
                .filter { it.width == best.width && it.height == best.height }
                .map { it.refreshRate }
                .distinctBy { (it * 100).toInt() }
                .sortedDescending()
            return Panel(best.width, best.height, rates)
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
        context: Context, setting: ResolutionSetting, frameRate: FrameRateSetting = FrameRateSetting.FPS_120,
    ): MirrorProfile = choose(setting, frameRate, panel(context), maxRefreshRate(context), SystemDecoders)

    /**
     * The size every Mac, iPhone and iPad is offered. A size picked under
     * Resolution is offered exactly as picked, whatever the screen or the
     * decoder's tables report: nothing overrides it.
     *
     * Auto (the default) is the sharpest picture the TV's hardware decoder
     * takes: 4K, else 1440p, else 1080p, whatever the TV's menus run at.
     * Apple devices scale their screen to fit inside the size offered,
     * keeping its shape (up as well as down): a 4K offer gets about
     * 1000 x 2160 from an iPhone held upright (996 x 2160 from a 15 Pro Max)
     * and 1620 x 2160 from an iPad, where 1080p gets them about 500 x 1080
     * and 810 x 1080. The TV's own size is the most it
     * can show, so that is the most worth asking for: a bigger offer only
     * has the TV scale the picture down instead of the phone, and apps that
     * play video on a TV screen of their own (YouTube does) then draw it
     * into a shape that isn't the TV's. Above 1080p Apple devices only send
     * HEVC, so those sizes need an HEVC decoder. Every TV HEVC decoder that
     * takes a 4K picture is built for 4K60 streaming apps, so up to 60 fps
     * the size check is enough; the frame-rate tables some vendors publish
     * are often too conservative.
     *
     * Above 60 fps nothing is assumed: the screen needs a mode that fast and
     * the decoder's own tables must list the rate. The resolution always
     * comes first: when the decoder can't keep up at that size (4K120 is
     * rare), the TV offers 60 fps and says which smaller resolution gets
     * the faster rate.
     */
    internal fun choose(
        resolution: ResolutionSetting, frameRate: FrameRateSetting, panel: Panel, screenMaxHz: Float,
        decoders: DecoderInfo,
    ): MirrorProfile {
        val taken = CANDIDATES.filter { (width, height) -> takes(decoders, width, height) }
        // Nothing reported even 1080p on Auto; ask for it anyway and let the decoder try.
        val best = if (resolution == ResolutionSetting.AUTO) taken.firstOrNull() ?: (1920 to 1080)
            else CANDIDATES.first { it.second == resolution.maxHeight }
        val unlisted = best !in taken
        val refresh = refreshFor(panel)
        fun offer(fps: Int, refreshRate: Int, note: String? = null): MirrorProfile {
            val (width, height) = best
            // A size the decoder doesn't list goes out with HEVC whenever there's an HEVC decoder.
            val h265 = decoders.supportsSize(HEVC, width, height) || (unlisted && decoders.hevcAvailable)
            val notes = listOfNotNull(
                if (unlisted && resolution != ResolutionSetting.AUTO)
                    "The TV's video decoder doesn't list $width × $height; it's offered as picked under Resolution."
                else null,
                note,
            )
            return MirrorProfile(width, height, refreshRate, fps, h265, panel, notes.joinToString(" ").ifEmpty { null })
        }

        val wanted = frameRate.fps
        if (wanted <= 60) return offer(wanted, refresh)
        if (screenMaxHz < wanted - 1.5f) {
            return offer(60, refresh, "This TV's screen goes up to ${screenMaxHz.roundToInt()} Hz, so it offers 60 fps.")
        }
        val fast = taken.firstOrNull { (width, height) -> height <= best.second && decodesAt(decoders, width, height, wanted) }
        return when {
            fast == null -> offer(60, refresh, "The TV's video decoder isn't rated for $wanted fps, so it offers 60 fps.")
            fast != best -> offer(60, refresh,
                "$wanted fps needs ${fast.first} × ${fast.second} or smaller on this TV: pick it under Resolution to get it.")
            else -> offer(wanted, wanted)
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
     * to 60 (or 120). Always the TV's own 16:9 shape.
     */
    fun preferredMode(display: Display, fps: Int = 60): Display.Mode? {
        if (Build.VERSION.SDK_INT < 23) return null
        val id = preferredMode(screenModes(display), fps)?.id ?: return null
        return display.supportedModes.firstOrNull { it.modeId == id }
    }

    internal fun preferredMode(all: List<ScreenMode>, fps: Int): ScreenMode? {
        val modes = tvShaped(all)
        if (fps > 60) {
            modes.filter { abs(it.refreshRate - fps) < 1.5f }.maxByOrNull { it.pixels }?.let { return it }
        }
        val maxPixels = modes.maxOfOrNull { it.pixels } ?: return null
        return modes
            .filter { it.pixels == maxPixels }
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

    /**
     * The modes in the TV's own 16:9 shape. Boxes also list 4096 × 2160
     * cinema modes when the TV accepts them, and the TV squeezes those into
     * its 16:9 panel, so everything (the mirrored picture too) shows too
     * narrow. Any other shape only counts when there's no 16:9 mode at all.
     */
    internal fun tvShaped(modes: List<ScreenMode>): List<ScreenMode> =
        modes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f }.ifEmpty { modes }

    @RequiresApi(23)
    private fun screenModes(display: Display): List<ScreenMode> =
        (display.supportedModes.toList() + display.mode).distinctBy { it.modeId }.map {
            ScreenMode(it.modeId, max(it.physicalWidth, it.physicalHeight), min(it.physicalWidth, it.physicalHeight),
                it.refreshRate)
        }
}
