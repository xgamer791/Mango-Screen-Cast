package com.mangoscreencast.tv.media

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the decoder pipeline with a fake decoder through the situations
 * that broke it on a real TV: a decoder slow to take its first frame, the
 * player screen arriving late, a decoder crash and a rotation.
 */
class VideoDecoderTest {
    /**
     * Records what it was fed. Accepts input only after `warmUpMs`, can be
     * told to crash, and reports `size` as its picture's size once it starts.
     */
    private class FakeCodec(val id: Int, private val warmUpMs: Long, private val size: Pair<Int, Int>? = null) :
        VideoCodec {
        override val name = "fake-$id"
        val fed = CopyOnWriteArrayList<String>()
        @Volatile var crash = false
        @Volatile var released = false
        @Volatile private var sizeReported = false
        private val createdAt = System.nanoTime()
        private val input = ByteBuffer.allocate(1 shl 20)

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            if (released) throw IllegalStateException("released")
            if ((System.nanoTime() - createdAt) / 1_000_000 < warmUpMs) {
                Thread.sleep(timeoutUs / 1000)
                return -1
            }
            return 0
        }

        override fun inputBuffer(index: Int): ByteBuffer = input

        override fun queueInputBuffer(index: Int, size: Int, ptsUs: Long, keyframe: Boolean) {
            val bytes = ByteArray(size)
            input.flip()
            input.get(bytes)
            fed += String(bytes.copyOfRange(bytes.size - 3, bytes.size))
        }

        override fun dequeueOutputBuffer(out: OutputInfo, timeoutUs: Long): Int {
            if (crash) throw IllegalStateException("decoder died")
            if (size != null && !sizeReported) {
                sizeReported = true
                return VideoCodec.FORMAT_CHANGED
            }
            Thread.sleep(timeoutUs / 1000)
            return -1
        }

        override fun outputSize(): Pair<Int, Int>? = size
        override fun render(index: Int, timestampNs: Long?) {}
        override fun discard(index: Int) {}
        override fun setOutputSurface(surface: Surface) {}
        override fun release() {
            released = true
        }
    }

    /** A distinct Surface object; nothing draws into it in these tests. */
    private fun screen() = Surface(SurfaceTexture(0))

    private val codecs = CopyOnWriteArrayList<FakeCodec>()
    private var warmUpMs = 0L
    private var pictureSize: Pair<Int, Int>? = null

    private fun decoder(canSwapSurfaces: Boolean = true) = VideoDecoder(
        { _, _, _, _, _ -> FakeCodec(codecs.size + 1, warmUpMs, pictureSize).also { codecs += it } },
        canSwapSurfaces,
    )

    // H.264 access units tagged with a 3-character label at the end.
    private fun keyframe(label: String, sps: Int = 0x28) = frame(
        byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, sps.toByte(), 0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte(),
            0, 0, 0, 1, 0x65, 0x88.toByte()), label)

    private fun delta(label: String) = frame(byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte()), label)

    private fun frame(prefix: ByteArray, label: String): ByteBuffer =
        ByteBuffer.allocateDirect(prefix.size + 3).put(prefix).put(label.toByteArray()).flip() as ByteBuffer

    private fun waitFor(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun slowDecoderGetsTheKeyframeFirstAndTheNetworkNeverWaits() {
        warmUpMs = 1_500 // TV decoders can take this long to warm up at 4K
        val video = decoder()
        video.setSurface(screen(), 60f)
        video.start(h265 = false, width = 1920, height = 1080)
        val started = System.nanoTime()
        video.submit(keyframe("K01"), false)
        for (i in 2..30) video.submit(delta("P%02d".format(i)), false)
        val submitMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("submit blocked for $submitMs ms", submitMs < 200)

        waitFor { codecs.firstOrNull()?.fed?.size == 30 }
        val fed = codecs.single().fed
        assertEquals("K01", fed.first())
        assertEquals((2..30).map { "P%02d".format(it) }, fed.drop(1))
        video.stop()
    }

    @Test
    fun startsWhenThePlayerScreenArrivesLater() {
        val video = decoder()
        video.start(h265 = false, width = 1920, height = 1080)
        video.submit(keyframe("K01"), false)
        video.submit(delta("P02"), false)
        video.submit(delta("P03"), false)
        Thread.sleep(100)
        assertTrue(codecs.isEmpty())

        video.setSurface(screen(), 60f)
        waitFor { codecs.firstOrNull()?.fed?.size == 3 }
        video.submit(delta("P04"), false)
        waitFor { codecs.single().fed.size == 4 }
        assertEquals(listOf("K01", "P02", "P03", "P04"), codecs.single().fed)
        video.stop()
    }

    @Test
    fun decoderCrashRestartsFromTheLastKeyframe() {
        val video = decoder()
        video.setSurface(screen(), 60f)
        video.start(h265 = false, width = 1920, height = 1080)
        video.submit(keyframe("K01"), false)
        video.submit(delta("P02"), false)
        waitFor { codecs.firstOrNull()?.fed?.size == 2 }

        codecs.single().crash = true
        waitFor { codecs.size == 2 && codecs[1].fed.size == 2 }
        video.submit(delta("P03"), false)
        waitFor { codecs[1].fed.size == 3 }
        assertEquals(listOf("K01", "P02", "P03"), codecs[1].fed)
        assertTrue(codecs[0].released)
        video.stop()
    }

    @Test
    fun rotationBuildsANewDecoderStartingAtTheNewKeyframe() {
        val video = decoder()
        video.setSurface(screen(), 60f)
        video.start(h265 = false, width = 1920, height = 1080)
        video.submit(keyframe("K01"), false)
        video.submit(delta("P02"), false)
        waitFor { codecs.firstOrNull()?.fed?.size == 2 }

        // New parameter sets: the sender rotated or changed resolution.
        video.submit(keyframe("K03", sps = 0x33), false)
        video.submit(delta("P04"), false)
        waitFor { codecs.size == 2 && codecs[1].fed.size == 2 }
        assertEquals(listOf("K03", "P04"), codecs[1].fed)
        video.stop()
    }

    @Test
    fun sameParameterSetsDoNotRestartTheDecoder() {
        val video = decoder()
        video.setSurface(screen(), 60f)
        video.start(h265 = false, width = 1920, height = 1080)
        video.submit(keyframe("K01"), false)
        video.submit(delta("P02"), false)
        video.submit(keyframe("K03"), false)
        waitFor { codecs.firstOrNull()?.fed?.size == 3 }
        Thread.sleep(100)
        assertEquals(1, codecs.size)
        video.stop()
    }

    @Test
    fun surfaceGoingAwayKeepsTheDecoderRunning() {
        val video = decoder()
        val screen = screen()
        video.setSurface(screen, 60f)
        video.start(h265 = false, width = 1920, height = 1080)
        video.submit(keyframe("K01"), false)
        waitFor { codecs.firstOrNull()?.fed?.size == 1 }

        video.setSurface(null, 0f)
        video.submit(delta("P02"), false)
        video.setSurface(screen(), 60f)
        video.submit(delta("P03"), false)
        waitFor { codecs.single().fed.size == 3 }
        assertEquals(1, codecs.size)
        video.stop()
    }

    @Test
    fun aPlayerThatOpensAfterThePictureStartedIsToldItsShape() {
        // An iPhone held upright; the player opens once it's already decoding (from the background, say).
        pictureSize = 500 to 1080
        val video = decoder()
        val firstPlayer = CopyOnWriteArrayList<Pair<Int, Int>>()
        video.listener = object : VideoDecoder.Listener {
            override fun onVideoSize(width: Int, height: Int) {
                firstPlayer += width to height
            }
        }
        video.setSurface(screen(), 60f)
        video.start(h265 = false, width = 500, height = 1080)
        video.submit(keyframe("K01"), false)
        waitFor { firstPlayer.isNotEmpty() }

        val laterPlayer = CopyOnWriteArrayList<Pair<Int, Int>>()
        video.listener = object : VideoDecoder.Listener {
            override fun onVideoSize(width: Int, height: Int) {
                laterPlayer += width to height
            }
        }
        assertEquals(listOf(500 to 1080), laterPlayer)

        // A new stream's player isn't told the last stream's shape.
        video.start(h265 = false, width = 1920, height = 888)
        val nextStream = CopyOnWriteArrayList<Pair<Int, Int>>()
        video.listener = object : VideoDecoder.Listener {
            override fun onVideoSize(width: Int, height: Int) {
                nextStream += width to height
            }
        }
        assertTrue(nextStream.isEmpty())
        video.stop()
    }
}
