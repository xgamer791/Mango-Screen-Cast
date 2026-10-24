package com.mangoscreencast.tv.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NalUnitsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // H.264: SPS (0x67), PPS (0x68), SEI (0x06), IDR slice (0x65).
    private val h264Keyframe = bytes(
        0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28,
        0, 0, 0, 1, 0x68, 0xEE, 0x3C, 0x80,
        0, 0, 1, 0x06, 0x05, 0x01,
        0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x33,
    )

    @Test
    fun h264KeyframeWithParameterSets() {
        val info = NalUnits.inspect(h264Keyframe, h264Keyframe.size, h265 = false)
        assertTrue(info.keyframe)
        assertTrue(info.hasPicture)
        assertEquals(16, info.configLength)
        val sets = NalUnits.parameterSets(h264Keyframe, h264Keyframe.size, h265 = false)
        assertEquals(2, sets.size)
        assertArrayEquals(bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28), sets[0])
        assertArrayEquals(bytes(0, 0, 0, 1, 0x68, 0xEE, 0x3C, 0x80), sets[1])
    }

    @Test
    fun h264DeltaFrame() {
        val frame = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x00, 0x10)
        val info = NalUnits.inspect(frame, frame.size, h265 = false)
        assertFalse(info.keyframe)
        assertTrue(info.hasPicture)
        assertEquals(0, info.configLength)
    }

    @Test
    fun hevcKeyframeWithParameterSets() {
        // VPS (32), SPS (33), PPS (34), prefix SEI (39), IDR_W_RADL (19).
        val frame = bytes(
            0, 0, 0, 1, 0x40, 0x01, 0x0C,
            0, 0, 0, 1, 0x42, 0x01, 0x01,
            0, 0, 0, 1, 0x44, 0x01, 0xC1,
            0, 0, 0, 1, 0x4E, 0x01, 0x05,
            0, 0, 0, 1, 0x26, 0x01, 0xAF, 0x09,
        )
        val info = NalUnits.inspect(frame, frame.size, h265 = true)
        assertTrue(info.keyframe)
        assertEquals(21, info.configLength)
        assertEquals(3, NalUnits.parameterSets(frame, frame.size, h265 = true).size)
    }

    @Test
    fun hevcTrailingPictureIsNotAKeyframe() {
        val frame = bytes(0, 0, 0, 1, 0x02, 0x01, 0xD0, 0x11)
        val info = NalUnits.inspect(frame, frame.size, h265 = true)
        assertFalse(info.keyframe)
        assertTrue(info.hasPicture)
    }

    @Test
    fun respectsLengthArgument() {
        val padded = h264Keyframe + ByteArray(64)
        assertEquals(4, NalUnits.split(padded, h264Keyframe.size, h265 = false).size)
    }
}
