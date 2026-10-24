package com.mangoscreencast.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GopBufferTest {
    private fun frame(pool: FramePool, size: Int, pts: Long) =
        pool.obtain(size).apply {
            length = size
            ptsUs = pts
        }

    @Test
    fun keepsFramesInOrder() {
        val pool = FramePool(1 shl 20)
        val gop = GopBuffer(maxBytes = 1 shl 20, pool = pool)
        assertTrue(gop.add(frame(pool, 5_000, 1)))
        assertTrue(gop.add(frame(pool, 300, 2)))
        assertEquals(2, gop.frames.size)
        assertEquals(2L, gop.lastPtsUs)
        assertTrue(gop.hasKeyframe)
    }

    @Test
    fun storageIsSizedToTheFrameNotAFixedBlock() {
        // Small frames must not each pin a large block: that is what used to
        // exhaust memory after about a minute of mirroring.
        val pool = FramePool(0)
        assertEquals(4096, pool.obtain(300).data.size)
        assertEquals(8192, pool.obtain(5_000).data.size)
    }

    @Test
    fun stopsAtTheLimitAndRecoversAtTheNextKeyframe() {
        val pool = FramePool(1 shl 20)
        val gop = GopBuffer(maxBytes = 3 * 4096, pool = pool)
        assertTrue(gop.add(frame(pool, 4000, 1)))
        assertTrue(gop.add(frame(pool, 4000, 2)))
        assertTrue(gop.add(frame(pool, 4000, 3)))
        assertFalse(gop.add(frame(pool, 4000, 4)))
        // A partial run can't be replayed, so it reports no keyframe.
        assertFalse(gop.hasKeyframe)
        assertFalse(gop.add(frame(pool, 100, 5)))
        gop.clear()
        assertTrue(gop.add(frame(pool, 100, 6)))
        assertTrue(gop.hasKeyframe)
    }

    @Test
    fun poolReusesRecycledFramesOfASimilarSize() {
        val pool = FramePool(1 shl 20)
        val first = pool.obtain(100_000)
        pool.recycle(first)
        assertSame(first, pool.obtain(90_000))
        // Much smaller frames get their own small buffer rather than pinning a big one.
        pool.recycle(first)
        assertEquals(4096, pool.obtain(200).data.size)
    }
}
