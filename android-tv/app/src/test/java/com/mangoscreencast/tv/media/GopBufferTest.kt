package com.mangoscreencast.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GopBufferTest {
    @Test
    fun keepsFramesInOrderAndCopiesThem() {
        val gop = GopBuffer(maxBytes = 1_000)
        val data = ByteArray(10) { it.toByte() }
        gop.add(data, 10, ptsUs = 1, flags = 1)
        data[0] = 99
        gop.add(data, 4, ptsUs = 2, flags = 0)
        assertEquals(2, gop.frames.size)
        assertEquals(0.toByte(), gop.frames[0].data[0])
        assertEquals(99.toByte(), gop.frames[1].data[0])
        assertEquals(2L, gop.lastPtsUs)
    }

    @Test
    fun stopsAtTheSizeLimitUntilCleared() {
        val gop = GopBuffer(maxBytes = 100)
        gop.add(ByteArray(60), 60, 1, 1)
        assertFalse(gop.isEmpty)
        gop.add(ByteArray(60), 60, 2, 0)
        // Can't hold the whole group any more, so it can't be replayed either.
        assertTrue(gop.isEmpty)
        gop.clear()
        gop.add(ByteArray(10), 10, 3, 1)
        assertFalse(gop.isEmpty)
        assertEquals(1, gop.frames.size)
    }
}
