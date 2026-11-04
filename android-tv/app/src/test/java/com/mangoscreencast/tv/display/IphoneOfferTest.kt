package com.mangoscreencast.tv.display

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IphoneOfferTest {
    /** HEVC up to `maxWidth` x `maxHeight`, either way round. */
    private class FakeDecoders(private val maxWidth: Int = 4096, private val maxHeight: Int = 2304) : DecoderInfo {
        override val hevcAvailable = true
        override fun supportsSize(mime: String, width: Int, height: Int) =
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                (width <= maxWidth && height <= maxHeight || height <= maxWidth && width <= maxHeight)
        override fun canDecode(mime: String, width: Int, height: Int, fps: Int) = true
    }

    private val panel = Panel(3840, 2160, listOf(60f))
    private val uhd = MirrorProfile(3840, 2160, 60, 60, true, panel)

    private fun offer(model: String, profile: MirrorProfile = uhd, decoders: DecoderInfo = FakeDecoders()) =
        DisplayCapabilities.iphoneOffer(model, profile, decoders)

    /** The picture an iPhone with a `short` x `long` screen sends held sideways: fitted inside the offer. */
    private fun sidewaysRows(offer: IphoneOffer, short: Int, long: Int) =
        minOf(offer.width.toDouble() / long, offer.height.toDouble() / short) * short

    /** And held upright. */
    private fun uprightRows(offer: IphoneOffer, short: Int, long: Int) =
        minOf(offer.width.toDouble() / short, offer.height.toDouble() / long) * long

    @Test
    fun aSidewaysIphoneSends1440Rows() {
        // 15 Pro Max, 1290 x 2796: 4K's 3840 wide got 3840 x 1772.
        val proMax = offer("iPhone16,2")!!
        assertEquals(IphoneOffer(3122, 2160), proMax)
        assertEquals(1440.0, sidewaysRows(proMax, 1290, 2796), 0.5)
        // 16 Pro Max, 1320 x 2868; 15 Pro, 1179 x 2556; 17 Pro, 1206 x 2622.
        assertEquals(1440.0, sidewaysRows(offer("iPhone17,2")!!, 1320, 2868), 0.5)
        assertEquals(1440.0, sidewaysRows(offer("iPhone16,1")!!, 1179, 2556), 0.5)
        assertEquals(1440.0, sidewaysRows(offer("iPhone18,1")!!, 1206, 2622), 0.5)
    }

    @Test
    fun uprightItStillSendsEveryRowTheTvHas() {
        assertEquals(2160.0, uprightRows(offer("iPhone16,2")!!, 1290, 2796), 0.01)
        assertEquals(2160.0, uprightRows(offer("iPhone17,2")!!, 1320, 2868), 0.01)
    }

    @Test
    fun aTvOffering1440pGivesItsHeightUpright() {
        val qhd = MirrorProfile(2560, 1440, 60, 60, true, panel)
        assertEquals(IphoneOffer(3122, 1440), offer("iPhone16,2", qhd))
    }

    @Test
    fun anIphoneNewerThanTheListIsStillOffered1440Sideways() {
        assertEquals(IphoneOffer(3128, 2160), offer("iPhone19,1"))
    }

    @Test
    fun macsIpadsAndOldIphonesKeepTheTvsSize() {
        assertNull(offer("MacBookPro18,3"))
        assertNull(offer("Mac16,13"))
        assertNull(offer("iPad13,8"))
        assertNull(offer("iPhone9,1")) // iPhone 7
        assertNull(offer("iPhone"))
    }

    @Test
    fun belowA1440OfferOrWithoutHevcTheTvsSizeStays() {
        assertNull(offer("iPhone16,2", MirrorProfile(1920, 1080, 60, 60, true, panel)))
        assertNull(offer("iPhone16,2", uhd.copy(h265 = false)))
    }

    @Test
    fun aDecoderThatCantTakeThePictureKeepsTheTvsSize() {
        assertNull(offer("iPhone16,2", decoders = FakeDecoders(maxWidth = 3000)))
    }
}
