package com.mangoscreencast.tv.display

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayModeTest {
    private var nextId = 1
    private fun mode(width: Int, height: Int, hz: Float) = ScreenMode(nextId++, width, height, hz)

    // What a 4K box lists on a TV that also takes cinema 4K.
    private val uhd60 = mode(3840, 2160, 60f)
    private val uhd50 = mode(3840, 2160, 50f)
    private val dci60 = mode(4096, 2160, 60f)
    private val dci120 = mode(4096, 2160, 120f)
    private val fhd120 = mode(1920, 1080, 120f)
    private val fhd60 = mode(1920, 1080, 60f)
    private val modes = listOf(fhd60, fhd120, uhd50, uhd60, dci60, dci120)

    @Test
    fun aCinemaModeIsNeverPickedThoughItHasMorePixels() {
        // The TV squeezes 4096 x 2160 into its 16:9 screen: everything shows too narrow.
        assertEquals(uhd60, DisplayCapabilities.preferredMode(modes, fps = 60))
    }

    @Test
    fun oneTwentyFramesGetTheLargest16By9ModeAtThatRate() {
        assertEquals(fhd120, DisplayCapabilities.preferredMode(modes, fps = 120))
    }

    @Test
    fun theScreenIsItsLargest16By9Mode() {
        assertEquals(listOf(fhd60, fhd120, uhd50, uhd60), DisplayCapabilities.tvShaped(modes))
    }

    @Test
    fun anotherShapeCountsOnlyWithout16By9Modes() {
        val odd = listOf(mode(1024, 768, 60f))
        assertEquals(odd, DisplayCapabilities.tvShaped(odd))
        assertEquals(odd.single(), DisplayCapabilities.preferredMode(odd, fps = 60))
    }

    @Test
    fun a1366By768TvCountsAs16By9() {
        val hdReady = mode(1366, 768, 60f)
        assertEquals(hdReady, DisplayCapabilities.preferredMode(listOf(hdReady, mode(1024, 768, 60f)), fps = 60))
    }
}
