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

    /** H.264 rated up to `avcHeight` at up to `avcFps`. */
    private class AvcDecoders(private val avcHeight: Int, private val avcFps: Int = 60) : DecoderInfo {
        override val hevcAvailable = true
        override fun supportsSize(mime: String, width: Int, height: Int) = height <= 2160
        override fun canDecode(mime: String, width: Int, height: Int, fps: Int) =
            mime != "video/avc" || (height <= avcHeight && fps <= avcFps)
    }

    @Test
    fun h264LimitIsWhatTheDecoderIsRatedFor() {
        assertEquals(3840 to 2160, DisplayCapabilities.h264Limit(3840, 2160, 60, AvcDecoders(2160)))
        assertEquals(2560 to 1440, DisplayCapabilities.h264Limit(3840, 2160, 60, AvcDecoders(1440)))
        assertEquals(1920 to 1080, DisplayCapabilities.h264Limit(3840, 2160, 60, AvcDecoders(1080)))
        // 4K at 30 fps only: not offered for a 60 fps stream.
        assertEquals(1920 to 1080, DisplayCapabilities.h264Limit(3840, 2160, 60, AvcDecoders(2160, avcFps = 30)))
        // Never more than the TV takes, and never less than 1080p.
        assertEquals(2560 to 1440, DisplayCapabilities.h264Limit(2560, 1440, 60, AvcDecoders(2160)))
        assertEquals(1920 to 1080, DisplayCapabilities.h264Limit(3840, 2160, 60, AvcDecoders(720)))
    }
}
