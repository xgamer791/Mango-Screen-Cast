package com.mangoscreencast.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SPS units produced by x264/x265 (ffmpeg) at real iPhone, Mac and TV sizes. */
class SpsParserTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun avc(s: String) = SpsParser.parse(hex(s), h265 = false)
    private fun hevc(s: String) = SpsParser.parse(hex(s), h265 = true)

    @Test
    fun h264PortraitPhoneWithCropping() {
        assertEquals(SpsParser.Size(1170, 2532), avc("67640033acd9404a013fe227c044000003000400000301e03c60c658"))
    }

    @Test
    fun h264LandscapeMainProfile() {
        assertEquals(SpsParser.Size(2532, 1170), avc("674d4033eca013e04af3c46022000003000200000300f01e30632c"))
    }

    @Test
    fun h264FullHd() {
        assertEquals(SpsParser.Size(1920, 1080), avc("6764002aacd940780227e5c044000003000400000301e03c60c658"))
    }

    @Test
    fun hevc4k() {
        assertEquals(
            SpsParser.Size(3840, 2160),
            hevc("420101016000000300900000030000030099a001e020021c596566924caf016808000003000800000301e040"),
        )
    }

    @Test
    fun hevcMacSizedDisplay() {
        assertEquals(
            SpsParser.Size(3456, 2160),
            hevc("420101016000000300900000030000030099a001b020021c596566924caf016808000003000800000301e040"),
        )
    }

    @Test
    fun hevcPortraitPhone() {
        assertEquals(
            SpsParser.Size(1290, 2796),
            hevc("420101016000000300900000030000030096a00288800af1c9796566924caf016808000003000800000301e040"),
        )
    }

    @Test
    fun findsSpsAmongParameterSets() {
        val config = hex("0000000140010c" + "00000001" + "420101016000000300900000030000030099a001e020021c596566924caf016808000003000800000301e040" + "0000000144010c")
        assertEquals(SpsParser.Size(3840, 2160), SpsParser.parseFromConfig(config, h265 = true))
    }

    @Test
    fun truncatedSpsReturnsNull() {
        assertNull(avc("6764002a"))
    }
}
