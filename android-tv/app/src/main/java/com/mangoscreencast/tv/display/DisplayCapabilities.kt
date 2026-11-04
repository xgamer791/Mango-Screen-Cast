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
 * The most frames per second Apple devices are told they may send, offered
 * exactly as picked. 120 is the default; a screen Android reports at 60 Hz
 * shows 60 of them.
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
    /** What the TV can't fully show or decode of the offer, or why it offers less than picked, if anything. */
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

/** What an iPhone is offered in place of [MirrorProfile]'s size: see [DisplayCapabilities.iphoneOffer]. */
data class IphoneOffer(val width: Int, val height: Int)

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
    const val IPHONE_SIDEWAYS_ROWS = 1440

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
     * 1000 x 2160 from an iPhone held upright (998 x 2160 from a 15 Pro Max)
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
     * The frame rate is offered exactly as picked too, 120 included, whatever
     * the screen's mode list or the decoder's tables say: Android TVs often
     * list a single 60 Hz mode for a panel that can do more, and the rate
     * tables are as conservative as the size tables. The note says what the
     * TV can show of it (a screen Android reports at 60 Hz shows 60 of 120)
     * and when the decoder isn't rated for it, so the person can pick 60 if
     * the picture stutters. Apple devices treat the rate as an upper limit.
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
        // Above 60: the display goes out at the same rate, so the sender sees a screen that fast.
        val (width, height) = best
        val limits = listOfNotNull(
            if (screenMaxHz < wanted - 1.5f)
                "Android reports this TV's screen at ${screenMaxHz.roundToInt()} Hz, so it shows up to " +
                    "${screenMaxHz.roundToInt()} of them."
            else null,
            if (!decodesAt(decoders, width, height, wanted))
                "The TV's video decoder isn't rated for $wanted fps at $width × $height: if the picture stutters, " +
                    "pick 60 fps or a smaller Resolution."
            else null,
        )
        if (limits.isEmpty()) return offer(wanted, wanted)
        return offer(wanted, wanted, "Offering $wanted fps as picked. ${limits.joinToString(" ")} " +
            "What Apple devices send is up to them (Show stats shows it).")
    }

    /**
     * What `model` is offered when it's an iPhone (the 8 or later), so that
     * held sideways it sends 1440 rows; null for anything else (Macs and
     * iPads get the [MirrorProfile]), and when the TV offers fewer than 1440
     * rows or no HEVC.
     *
     * Apple devices fit their screen inside the size offered, keeping its
     * shape. Sideways an iPhone is wider than the TV, so the offer's width
     * sets its size: 4K's 3840 gets 3840 × 1772 from a 15 Pro Max, scaled up
     * past its own 1290 rows. Here the width is 1440 rows of the iPhone's
     * shape (3122 for a 15 Pro Max, which then sends 3122 × 1440) and the
     * height is the TV's, which sets its size upright: still every row the
     * TV has (998 × 2160 at 4K).
     */
    fun iphoneOffer(model: String, profile: MirrorProfile): IphoneOffer? = iphoneOffer(model, profile, SystemDecoders)

    internal fun iphoneOffer(model: String, profile: MirrorProfile, decoders: DecoderInfo): IphoneOffer? {
        val (short, long) = IphoneScreens.nativeSize(model) ?: return null
        if (!profile.h265 || profile.height < IPHONE_SIDEWAYS_ROWS) return null
        // The even width closest to 1440 rows of its shape.
        val width = (2 * ((IPHONE_SIDEWAYS_ROWS.toLong() * long + short) / (2 * short))).toInt()
        if (!decoders.supportsSize(HEVC, width, IPHONE_SIDEWAYS_ROWS)) return null
        return IphoneOffer(width, profile.height)
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
     * Best display mode for a stream of `fps` frames per second: the screen's
     * largest 16:9 size, at that rate above 60 when the screen has it there,
     * else at the refresh closest to 60 (or 120). Never a smaller size for
     * the sake of the rate: a 1080p120 mode would turn a 4K panel into a
     * 1080p one.
     */
    fun preferredMode(display: Display, fps: Int = 60): Display.Mode? {
        if (Build.VERSION.SDK_INT < 23) return null
        val id = preferredMode(screenModes(display), fps)?.id ?: return null
        return display.supportedModes.firstOrNull { it.modeId == id }
    }

    internal fun preferredMode(all: List<ScreenMode>, fps: Int): ScreenMode? {
        val modes = tvShaped(all)
        val maxPixels = modes.maxOfOrNull { it.pixels } ?: return null
        val largest = modes.filter { it.pixels == maxPixels }
        if (fps > 60) largest.firstOrNull { abs(it.refreshRate - fps) < 1.5f }?.let { return it }
        return largest
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
