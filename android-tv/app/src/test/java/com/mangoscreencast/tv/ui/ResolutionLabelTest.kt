package com.mangoscreencast.tv.ui

import android.app.Application
import android.content.Context
import com.mangoscreencast.tv.display.DecoderInfo
import com.mangoscreencast.tv.display.DisplayCapabilities
import com.mangoscreencast.tv.display.FrameRateSetting
import com.mangoscreencast.tv.display.Panel
import com.mangoscreencast.tv.display.ResolutionSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings → Resolution's value (ResolutionLabel). Each pick by its size, and
 * Auto with the size Auto offers now: on 25 October 2026 picking Auto on a 4K
 * TV read "Auto (1080p)", the size the server was offering for the pick
 * before (1080p) until it restarted, while the TV was offering 4K.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResolutionLabelTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** A 4K TV whose HEVC decoder takes 4K. */
    private val fourKDecoders = object : DecoderInfo {
        override val hevcAvailable = true
        override fun supportsSize(mime: String, width: Int, height: Int) = height <= 2160
        override fun canDecode(mime: String, width: Int, height: Int, fps: Int) = fps <= 60
    }
    private val panel = Panel(3840, 2160, listOf(60f))

    private fun autoOffers(decoders: DecoderInfo) =
        DisplayCapabilities.choose(ResolutionSetting.AUTO, FrameRateSetting.FPS_60, panel, 60f, decoders).sizeName

    @Test
    fun eachPickReadsAsItsSize() {
        val never = { error("only Auto asks what it offers") }
        assertEquals("4K (3840 × 2160)", ResolutionLabel.of(context, ResolutionSetting.UHD_2160, never))
        assertEquals("2K / 1440p (2560 × 1440)", ResolutionLabel.of(context, ResolutionSetting.QHD_1440, never))
        assertEquals("1080p (1920 × 1080)", ResolutionLabel.of(context, ResolutionSetting.FHD_1080, never))
    }

    @Test
    fun autoReadsAsWhatItOffersNowNotWhatWasOfferedBefore() {
        // Just switched from 1080p: what Auto offers is worked out now, so it reads 4K on this TV.
        assertEquals("Auto (4K)", ResolutionLabel.of(context, ResolutionSetting.AUTO) { autoOffers(fourKDecoders) })
    }

    @Test
    fun autoOnATvWhoseDecoderTakes1440pAtMost() {
        val qhd = object : DecoderInfo by fourKDecoders {
            override fun supportsSize(mime: String, width: Int, height: Int) = height <= 1440
        }
        assertEquals("Auto (1440p)", ResolutionLabel.of(context, ResolutionSetting.AUTO) { autoOffers(qhd) })
    }

    @Test
    fun autoWithNothingKnownYetIsJustAuto() {
        assertEquals("Auto", ResolutionLabel.of(context, ResolutionSetting.AUTO) { null })
    }
}
