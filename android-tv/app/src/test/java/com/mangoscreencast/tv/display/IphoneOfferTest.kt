package com.mangoscreencast.tv.display

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneOfferTest {
    /** HEVC up to `maxWidth` x `maxHeight`, exactly that way round; `fps` the most it's rated for. */
    private class FakeDecoders(
        private val maxWidth: Int = 4096, private val maxHeight: Int = 4096, private val fps: Int = 120,
    ) : DecoderInfo {
        override val hevcAvailable = true
        override fun supportsSize(mime: String, width: Int, height: Int) =
            supportsUpright(mime, width, height) || supportsUpright(mime, height, width)
        override fun supportsUpright(mime: String, width: Int, height: Int) =
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC && width <= maxWidth && height <= maxHeight
        override fun canDecode(mime: String, width: Int, height: Int, fps: Int) = fps <= this.fps
    }

    private val panel = Panel(3840, 2160, listOf(60f))

    private fun profile(fps: Int = 60, h265: Boolean = true) = MirrorProfile(3840, 2160, 60, fps, h265, panel)

    private fun offer(model: String, decoders: DecoderInfo = FakeDecoders(), profile: MirrorProfile = profile()) =
        DisplayCapabilities.iphoneOffer(model, profile, decoders)

    @Test
    fun anIphoneIsOfferedASquareAsBigAsItsScreen() {
        // Apple devices fit their screen to the offer: a square that size fits it exactly both ways round.
        assertEquals(IphoneOffer(2868, 2868, 60), offer("iPhone17,2")) // 16 Pro Max: 1320 x 2868
        assertEquals(IphoneOffer(2556, 2556, 60), offer("iPhone16,1")) // 15 Pro: 1179 x 2556
        assertEquals(IphoneOffer(2622, 2622, 60), offer("iPhone18,1")) // 17 Pro: 1206 x 2622
    }

    @Test
    fun itIsOfferedWhateverResolutionTheTvOffersOthers() {
        val fullHd = MirrorProfile(1920, 1080, 60, 60, true, panel)
        assertEquals(IphoneOffer(2868, 2868, 60), offer("iPhone17,2", profile = fullHd))
    }

    @Test
    fun anIphoneNewerThanTheListGetsTheLargestScreen() {
        assertEquals(IphoneOffer(2868, 2868, 60), offer("iPhone19,1"))
        assertEquals(IphoneOffer(2868, 2868, 60), offer("iPhone18,5"))
    }

    @Test
    fun macsIpadsAndOldIphonesKeepTheTvsSize() {
        assertNull(offer("MacBookPro18,3"))
        assertNull(offer("iPad13,8"))
        assertNull(offer("iPhone9,1")) // iPhone 7
        assertNull(offer("iPhone"))
    }

    @Test
    fun withoutHevcTheTvsSizeStays() {
        assertNull(offer("iPhone17,2", profile = profile(h265 = false)))
    }

    @Test
    fun aDecoderThatCantTakeItUprightCutsOnlyTheUprightPicture() {
        // Takes 4096 x 2304 but not 1320 x 2868: sideways stays native, upright fits 2304.
        val o = offer("iPhone17,2", FakeDecoders(maxWidth = 4096, maxHeight = 2304))!!
        assertEquals(2868, o.width)
        assertEquals(2304, o.height)
        assertTrue(o.note!!, o.note!!.contains("scaled to fit"))
    }

    @Test
    fun aSmallDecoderGetsTheMostItTakesBothWays() {
        val o = offer("iPhone17,2", FakeDecoders(maxWidth = 1920, maxHeight = 1920))!!
        assertEquals(1920, o.width)
        assertEquals(1920, o.height)
        // Not even 1080 upright: the TV's own size.
        assertNull(offer("iPhone17,2", FakeDecoders(maxWidth = 1920, maxHeight = 1000)))
    }

    @Test
    fun theFrameRateDropsTo60OnlyWhenTheDecoderIsntRatedAtTheIphonesSize() {
        assertEquals(120, offer("iPhone17,2", FakeDecoders(fps = 120), profile(fps = 120))!!.maxFps)
        val slow = offer("iPhone17,2", FakeDecoders(fps = 60), profile(fps = 120))!!
        assertEquals(60, slow.maxFps)
        assertTrue(slow.note!!, slow.note!!.contains("120 fps"))
    }

    @Test
    fun screens() {
        assertEquals(1320 to 2868, IphoneScreens.nativeSize("iPhone17,2"))
        assertEquals(1179 to 2556, IphoneScreens.nativeSize("iPhone15,4"))
        assertEquals(750 to 1334, IphoneScreens.nativeSize("iPhone14,6"))
        assertNull(IphoneScreens.nativeSize("iPhone8,1"))
        assertNull(IphoneScreens.nativeSize("iPad16,3"))
    }
}
