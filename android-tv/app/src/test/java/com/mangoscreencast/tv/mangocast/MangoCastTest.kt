package com.mangoscreencast.tv.mangocast

import com.mangoscreencast.tv.airplay.TxtRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangoCastTest {
    private val offer = Offer(name = "Living Room", maxWidth = 3840, maxHeight = 2160, fps = 60, h265 = true, audio = true)

    private fun hello(json: String) = Hello.parse(json.toByteArray())

    @Test
    fun readsHello() {
        val h = hello("""{"protocol": 1, "name": "DESKTOP-4F2K", "os": "Windows 11", "codecs": ["H265", "h264"],
            "width": 2560, "height": 1440, "fps": 60, "audio": true, "extra": "ignored"}""")
        assertEquals(Hello(1, "DESKTOP-4F2K", "Windows 11", listOf("h265", "h264"), 2560, 1440, 60, true), h)
    }

    @Test
    fun helloFillsInWhatsMissing() {
        val h = hello("""{"protocol": 1}""")
        assertEquals("Windows", h.name)
        assertEquals(emptyList<String>(), h.codecs)
        assertFalse(h.audio)
    }

    @Test(expected = IllegalArgumentException::class)
    fun helloMustBeJson() {
        hello("not json")
    }

    @Test
    fun picksTheSendersFirstCodecTheTvDecodes() {
        assertEquals("h265", offer.codecFor(hello("""{"codecs": ["h265", "h264"]}""")))
        assertEquals("h264", offer.codecFor(hello("""{"codecs": ["vp9", "h264", "h265"]}""")))
        assertEquals("h264", offer.copy(h265 = false).codecFor(hello("""{"codecs": ["h265", "h264"]}""")))
        assertNull(offer.copy(h265 = false).codecFor(hello("""{"codecs": ["h265"]}""")))
        assertNull(offer.codecFor(hello("""{"codecs": []}""")))
    }

    @Test
    fun h264IsOfferedNoBiggerThanTheDecoderPlays() {
        assertEquals(offer, offer.forCodec("h265"))
        val h264 = offer.forCodec("h264")
        assertEquals(1920 to 1080, h264.maxWidth to h264.maxHeight)
        // Another shape stays that shape.
        val square = offer.copy(maxWidth = 2752, maxHeight = 2064).forCodec("h264")
        assertEquals(1440 to 1080, square.maxWidth to square.maxHeight)
        val small = offer.copy(maxWidth = 1280, maxHeight = 720)
        assertEquals(small, small.forCodec("h264"))
        // A TV whose H.264 decoder plays 1440p, or 4K.
        val qhd = offer.copy(h264Width = 2560, h264Height = 1440).forCodec("h264")
        assertEquals(2560 to 1440, qhd.maxWidth to qhd.maxHeight)
        val uhd = offer.copy(h264Width = 3840, h264Height = 2160)
        assertEquals(uhd, uhd.forCodec("h264"))
    }

    @Test
    fun scalesThePictureToFitKeepingItsShape() {
        val tv1080 = offer.copy(maxWidth = 1920, maxHeight = 1080)
        assertEquals(1920 to 1080, tv1080.pictureFor(hello("""{"width": 2560, "height": 1440}""")))
        // An ultrawide screen: as wide as the TV, less tall, both sides even.
        assertEquals(1920 to 802, tv1080.pictureFor(hello("""{"width": 3440, "height": 1440}""")))
        // Smaller than the TV: sent as it is.
        assertEquals(1366 to 768, tv1080.pictureFor(hello("""{"width": 1366, "height": 768}""")))
        // Odd sizes are rounded down to even.
        assertEquals(1280 to 1022, offer.pictureFor(hello("""{"width": 1281, "height": 1023}""")))
        // No size given: the most the TV takes.
        assertEquals(3840 to 2160, offer.pictureFor(hello("""{}""")))
    }

    @Test
    fun welcomeSaysWhatToSend() {
        val json = JSONObject(String(offer.welcome("h265", hello("""{"audio": true}"""))))
        assertEquals(1, json.getInt("protocol"))
        assertEquals("Living Room", json.getString("name"))
        assertEquals("h265", json.getString("codec"))
        assertEquals(3840, json.getInt("maxWidth"))
        assertEquals(2160, json.getInt("maxHeight"))
        assertEquals(60, json.getInt("fps"))
        val audio = json.getJSONObject("audio")
        assertEquals("pcm_s16le", audio.getString("format"))
        assertEquals(48_000, audio.getInt("rate"))
        assertEquals(2, audio.getInt("channels"))
    }

    @Test
    fun noSoundWhenTheTvsIsOffOrTheSenderHasNone() {
        assertTrue(JSONObject(String(offer.copy(audio = false).welcome("h264", hello("""{"audio": true}""")))).isNull("audio"))
        assertTrue(JSONObject(String(offer.welcome("h264", hello("""{"audio": false}""")))).isNull("audio"))
    }

    @Test
    fun announcesWhatItTakes() {
        val txt = TxtRecord.parse(offer.txtRecord("1234-abcd", "SHIELD Android TV")).toMap()
        assertEquals(
            mapOf("v" to "1", "id" to "1234-abcd", "model" to "SHIELD Android TV", "w" to "3840", "h" to "2160",
                "fps" to "60", "codecs" to "h265,h264", "audio" to "1"),
            txt,
        )
        assertEquals("h264", TxtRecord.parse(offer.copy(h265 = false, audio = false).txtRecord("x", "y")).toMap()["codecs"])
    }

    @Test
    fun refusalPayload() {
        val json = JSONObject(String(Decision.Refuse("busy", "An iPhone is mirroring.").payload()))
        assertEquals("busy", json.getString("reason"))
        assertEquals("An iPhone is mirroring.", json.getString("message"))
    }
}
