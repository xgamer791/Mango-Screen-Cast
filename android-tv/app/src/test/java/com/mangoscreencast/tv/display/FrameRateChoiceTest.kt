package com.mangoscreencast.tv.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateChoiceTest {
    /** HEVC up to 4K; `fastSizes` are the heights the decoder's tables rate for 120 fps. */
    private class FakeDecoders(private val fastSizes: Set<Int>) : DecoderInfo {
        override val hevcAvailable = true
        override fun supportsSize(mime: String, width: Int, height: Int) = height <= 2160
        override fun canDecode(mime: String, width: Int, height: Int, fps: Int) = fps <= 60 || height in fastSizes
    }

    private val panel = Panel(3840, 2160, listOf(120f, 60f))

    private fun choose(
        frameRate: FrameRateSetting, screenHz: Float = 120f, fastSizes: Set<Int> = setOf(1440, 1080),
        resolution: ResolutionSetting = ResolutionSetting.AUTO,
    ) = DisplayCapabilities.choose(resolution, frameRate, panel, screenHz, FakeDecoders(fastSizes))

    @Test
    fun sixtyIsUnchanged() {
        val p = choose(FrameRateSetting.FPS_60)
        assertEquals(listOf(3840, 2160, 60, 60), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertNull(p.note)
    }

    @Test
    fun oneTwentyTradesSizeWhenResolutionIsAuto() {
        val p = choose(FrameRateSetting.FPS_120)
        assertEquals(listOf(2560, 1440, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertTrue(p.note!!, p.note!!.contains("3840 × 2160"))
    }

    @Test
    fun oneTwentyAtFourKWhenTheDecoderIsRatedForIt() {
        val p = choose(FrameRateSetting.FPS_120, fastSizes = setOf(2160, 1440, 1080))
        assertEquals(listOf(3840, 2160, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertNull(p.note)
    }

    @Test
    fun aSixtyHertzScreenStaysAtSixty() {
        val p = choose(FrameRateSetting.FPS_120, screenHz = 60f)
        assertEquals(listOf(3840, 2160, 60, 60), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertTrue(p.note!!, p.note!!.contains("60 Hz"))
    }

    @Test
    fun ntscRatesCount() {
        assertEquals(120, choose(FrameRateSetting.FPS_120, screenHz = 119.88f).maxFps)
    }

    @Test
    fun aFixedResolutionIsKept() {
        val p = choose(FrameRateSetting.FPS_120, resolution = ResolutionSetting.UHD_2160)
        assertEquals(listOf(3840, 2160, 60), listOf(p.width, p.height, p.maxFps))
        assertTrue(p.note!!, p.note!!.contains("Auto"))
    }

    @Test
    fun aDecoderNotRatedForOneTwentyStaysAtSixty() {
        val p = choose(FrameRateSetting.FPS_120, fastSizes = emptySet())
        assertEquals(listOf(3840, 2160, 60), listOf(p.width, p.height, p.maxFps))
        assertTrue(p.note!!, p.note!!.contains("decoder"))
    }

    @Test
    fun thirtyKeepsTheScreenAtSixty() {
        val p = choose(FrameRateSetting.FPS_30)
        assertEquals(listOf(3840, 2160, 30, 60), listOf(p.width, p.height, p.maxFps, p.refreshRate))
    }

    @Test
    fun sevenTwentyIsOfferedAtEveryFrameRate() {
        val hd = ResolutionSetting.HD_720
        val p30 = choose(FrameRateSetting.FPS_30, resolution = hd)
        assertEquals(listOf(1280, 720, 30, 60), listOf(p30.width, p30.height, p30.maxFps, p30.refreshRate))
        val p60 = choose(FrameRateSetting.FPS_60, resolution = hd)
        assertEquals(listOf(1280, 720, 60, 60), listOf(p60.width, p60.height, p60.maxFps, p60.refreshRate))
        assertEquals("720p", p60.sizeName)
        val p120 = choose(FrameRateSetting.FPS_120, resolution = hd, fastSizes = setOf(720))
        assertEquals(listOf(1280, 720, 120, 120), listOf(p120.width, p120.height, p120.maxFps, p120.refreshRate))
        assertNull(p120.note)
        // A decoder not rated for 720p120: 60, and says why.
        val slow = choose(FrameRateSetting.FPS_120, resolution = hd, fastSizes = emptySet())
        assertEquals(listOf(1280, 720, 60), listOf(slow.width, slow.height, slow.maxFps))
    }

    @Test
    fun autoNeverGoesBelow1080p() {
        val p = choose(FrameRateSetting.FPS_120, fastSizes = setOf(720))
        assertEquals(listOf(3840, 2160, 60), listOf(p.width, p.height, p.maxFps))
    }
}
