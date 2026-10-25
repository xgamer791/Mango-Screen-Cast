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
    fun oneTwentyKeepsTheTVsResolutionOnAuto() {
        val p = choose(FrameRateSetting.FPS_120)
        assertEquals(listOf(3840, 2160, 60, 60), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertTrue(p.note!!, p.note!!.contains("2560 × 1440"))
    }

    @Test
    fun autoIsTheTVsOwnResolution() {
        fun auto(width: Int, height: Int): List<Int> {
            val p = DisplayCapabilities.choose(ResolutionSetting.AUTO, FrameRateSetting.FPS_60,
                Panel(width, height, listOf(60f)), 60f, FakeDecoders(emptySet()))
            return listOf(p.width, p.height)
        }
        assertEquals(listOf(3840, 2160), auto(3840, 2160))
        assertEquals(listOf(3840, 2160), auto(4096, 2160))
        assertEquals(listOf(2560, 1440), auto(2560, 1440))
        assertEquals(listOf(1920, 1080), auto(1920, 1080))
        // Never less than 1080p.
        assertEquals(listOf(1920, 1080), auto(1366, 768))
    }

    @Test
    fun fourKCanBePickedOnATvThatReportsLess() {
        val p = DisplayCapabilities.choose(ResolutionSetting.UHD_2160, FrameRateSetting.FPS_60,
            Panel(1920, 1080, listOf(60f)), 60f, FakeDecoders(emptySet()))
        assertEquals(listOf(3840, 2160), listOf(p.width, p.height))
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
        assertEquals(120, choose(FrameRateSetting.FPS_120, screenHz = 119.88f, fastSizes = setOf(2160, 1440, 1080)).maxFps)
    }

    @Test
    fun aFixedResolutionIsKept() {
        val p = choose(FrameRateSetting.FPS_120, resolution = ResolutionSetting.UHD_2160)
        assertEquals(listOf(3840, 2160, 60), listOf(p.width, p.height, p.maxFps))
        assertTrue(p.note!!, p.note!!.contains("Resolution"))
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
}
