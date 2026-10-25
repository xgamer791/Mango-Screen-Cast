package com.mangoscreencast.tv.display

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateChoiceTest {
    /**
     * HEVC up to `maxHeight` (none with `hevc` off; H.264 up to 1080p either
     * way); `fastSizes` are the heights the decoder's tables rate for 120 fps.
     */
    private class FakeDecoders(
        private val fastSizes: Set<Int>, private val maxHeight: Int = 2160, private val hevc: Boolean = true,
    ) : DecoderInfo {
        override val hevcAvailable = hevc
        override fun supportsSize(mime: String, width: Int, height: Int) =
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) hevc && height <= maxHeight else height <= 1080
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
    fun oneTwentyIsOfferedAsPickedWhenTheDecoderIsntRatedForItAtThatSize() {
        val p = choose(FrameRateSetting.FPS_120)
        assertEquals(listOf(3840, 2160, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertTrue(p.note!!, p.note!!.contains("decoder") && p.note!!.contains("3840 × 2160"))
    }

    @Test
    fun autoIsTheSharpestTheDecoderTakesWhateverTheScreen() {
        fun auto(panel: Panel, decoders: DecoderInfo): List<Any> {
            val p = DisplayCapabilities.choose(ResolutionSetting.AUTO, FrameRateSetting.FPS_60, panel, 60f, decoders)
            return listOf(p.width, p.height, p.h265)
        }
        val fourK = Panel(3840, 2160, listOf(60f))
        val fullHd = Panel(1920, 1080, listOf(60f))
        assertEquals(listOf(3840, 2160, true), auto(fourK, FakeDecoders(emptySet())))
        // A 1080p TV whose decoder takes 4K still gets 4K: an upright iPhone sends 1000 x 2160, not 500 x 1080.
        assertEquals(listOf(3840, 2160, true), auto(fullHd, FakeDecoders(emptySet())))
        assertEquals(listOf(2560, 1440, true), auto(fourK, FakeDecoders(emptySet(), maxHeight = 1440)))
        assertEquals(listOf(1920, 1080, true), auto(fourK, FakeDecoders(emptySet(), maxHeight = 1080)))
        assertEquals(listOf(1920, 1080, false), auto(fourK, FakeDecoders(emptySet(), hevc = false)))
    }

    @Test
    fun fourKCanBePickedOnATvThatReportsLess() {
        val p = DisplayCapabilities.choose(ResolutionSetting.UHD_2160, FrameRateSetting.FPS_60,
            Panel(1920, 1080, listOf(60f)), 60f, FakeDecoders(emptySet()))
        assertEquals(listOf(3840, 2160), listOf(p.width, p.height))
        assertNull(p.note)
    }

    @Test
    fun aPickedResolutionIsOfferedAsPickedEvenWhenTheDecoderDoesntListIt() {
        for ((setting, size) in listOf(ResolutionSetting.UHD_2160 to listOf(3840, 2160),
                ResolutionSetting.QHD_1440 to listOf(2560, 1440))) {
            val p = DisplayCapabilities.choose(setting, FrameRateSetting.FPS_60, panel, 60f,
                FakeDecoders(emptySet(), maxHeight = 1080))
            assertEquals(setting.name, size + listOf(true), listOf(p.width, p.height, p.h265))
            assertTrue(p.note!!, p.note!!.contains("as picked"))
        }
    }

    @Test
    fun a1080pPickIsKeptOnA4KTv() {
        val p = choose(FrameRateSetting.FPS_60, resolution = ResolutionSetting.FHD_1080)
        assertEquals(listOf(1920, 1080, 60), listOf(p.width, p.height, p.maxFps))
        assertNull(p.note)
    }

    @Test
    fun oneTwentyAtFourKWhenTheDecoderIsRatedForIt() {
        val p = choose(FrameRateSetting.FPS_120, fastSizes = setOf(2160, 1440, 1080))
        assertEquals(listOf(3840, 2160, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertNull(p.note)
    }

    @Test
    fun aScreenAndroidReportsAtSixtyHertzStillGetsOneTwentyOffered() {
        // Many Android TVs list one 60 Hz mode for a panel that can do more; the offer is the setting.
        val p = choose(FrameRateSetting.FPS_120, screenHz = 60f, fastSizes = setOf(2160, 1440, 1080))
        assertEquals(listOf(3840, 2160, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertTrue(p.note!!, p.note!!.contains("60 Hz") && p.note!!.contains("120 fps"))
    }

    @Test
    fun aSlowScreenAndAnUnratedDecoderAreBothSaid() {
        val p = choose(FrameRateSetting.FPS_120, screenHz = 60f, fastSizes = emptySet())
        assertEquals(listOf(3840, 2160, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertTrue(p.note!!, p.note!!.contains("60 Hz") && p.note!!.contains("decoder") && p.note!!.contains("pick 60 fps"))
    }

    @Test
    fun ntscRatesCount() {
        val p = choose(FrameRateSetting.FPS_120, screenHz = 119.88f, fastSizes = setOf(2160, 1440, 1080))
        assertEquals(120, p.maxFps)
        assertNull(p.note)
    }

    @Test
    fun aFixedResolutionIsKeptAtOneTwenty() {
        val p = choose(FrameRateSetting.FPS_120, resolution = ResolutionSetting.QHD_1440)
        assertEquals(listOf(2560, 1440, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertNull(p.note)
    }

    @Test
    fun aDecoderNotRatedForOneTwentyIsStillOfferedIt() {
        val p = choose(FrameRateSetting.FPS_120, fastSizes = emptySet())
        assertEquals(listOf(3840, 2160, 120, 120), listOf(p.width, p.height, p.maxFps, p.refreshRate))
        assertTrue(p.note!!, p.note!!.contains("decoder") && p.note!!.contains("pick 60 fps"))
    }

    @Test
    fun thirtyKeepsTheScreenAtSixty() {
        val p = choose(FrameRateSetting.FPS_30)
        assertEquals(listOf(3840, 2160, 30, 60), listOf(p.width, p.height, p.maxFps, p.refreshRate))
    }
}
