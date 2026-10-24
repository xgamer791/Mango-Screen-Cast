package com.mangoscreencast.tv.mangocast

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A real server on a real socket, with a scripted Windows computer on the other end. */
class MangoCastServerTest {
    private val offer = Offer(name = "Living Room", maxWidth = 1920, maxHeight = 1080, fps = 60, h265 = true, audio = true)

    private class Recorder(var decide: (Hello) -> Decision) : MangoCastServer.Sink {
        val hellos = CopyOnWriteArrayList<Hello>()
        val started = CopyOnWriteArrayList<MangoCastServer.Session>()
        val video = CopyOnWriteArrayList<ByteArray>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val ended = CopyOnWriteArrayList<Triple<MangoCastServer.Session, String, Boolean>>()
        val startLatch = CountDownLatch(1)
        @Volatile var endLatch = CountDownLatch(1)
        @Volatile var videoLatch = CountDownLatch(1)
        @Volatile var audioLatch = CountDownLatch(1)

        override fun onHello(hello: Hello): Decision {
            hellos += hello
            return decide(hello)
        }

        override fun onStart(session: MangoCastServer.Session) {
            started += session
            startLatch.countDown()
        }

        override fun onVideo(session: MangoCastServer.Session, frame: ByteBuffer) {
            video += ByteArray(frame.remaining()).also { frame.get(it) }
            videoLatch.countDown()
        }

        override fun onAudio(session: MangoCastServer.Session, pcm: ByteBuffer) {
            audio += ByteArray(pcm.remaining()).also { pcm.get(it) }
            audioLatch.countDown()
        }

        override fun onEnd(session: MangoCastServer.Session, reason: String, lost: Boolean) {
            ended += Triple(session, reason, lost)
            endLatch.countDown()
        }
    }

    /** A Windows computer, as far as the protocol goes. */
    private class Sender(port: Int) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())

        fun send(type: Int, payload: ByteArray) {
            output.writeByte(type)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }

        fun hello(json: String = """{"protocol": 1, "name": "DESKTOP-4F2K", "os": "Windows 11",
            "codecs": ["h265", "h264"], "width": 2560, "height": 1440, "fps": 60, "audio": true}""") =
            send(MangoCast.HELLO, json.toByteArray())

        fun stamped(type: Int, data: ByteArray) =
            send(type, ByteBuffer.allocate(8 + data.size).putLong(123_456L).put(data).array())

        /** The next message: type and payload. */
        fun read(): Pair<Int, ByteArray> {
            val type = input.readUnsignedByte()
            val payload = ByteArray(input.readInt())
            input.readFully(payload)
            return type to payload
        }

        /** The next message that isn't a PING. */
        fun readSkippingPings(): Pair<Int, ByteArray> {
            while (true) {
                val message = read()
                if (message.first != MangoCast.PING) return message
            }
        }

        /** True when the TV closed the connection. */
        fun closedByTv(): Boolean = try {
            while (true) read()
            @Suppress("UNREACHABLE_CODE") false
        } catch (e: EOFException) {
            true
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        }

        fun close() = socket.close()
    }

    private lateinit var sink: Recorder
    private lateinit var server: MangoCastServer
    private var port = -1
    private val logs = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        sink = Recorder { Decision.Accept(offer) }
        server = MangoCastServer(sink) { logs += it }
        port = server.start(0)
        assertTrue(port > 0)
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun json(payload: ByteArray) = JSONObject(String(payload))

    private fun welcomed(): Sender {
        val sender = Sender(port)
        sender.hello()
        val (type, payload) = sender.readSkippingPings()
        assertEquals(MangoCast.WELCOME, type)
        assertEquals("h265", json(payload).getString("codec"))
        assertTrue(sink.startLatch.await(2, TimeUnit.SECONDS))
        return sender
    }

    @Test
    fun welcomesASenderAndPassesOnItsPicturesAndSound() {
        val sender = welcomed()
        val session = sink.started.single()
        assertEquals("DESKTOP-4F2K", session.hello.name)
        assertTrue(session.h265)
        assertEquals(1920 to 1080, session.width to session.height)
        assertTrue(session.audio)
        assertEquals(server.session, session)

        val picture = byteArrayOf(0, 0, 0, 1, 0x40, 1, 2, 3)
        sender.stamped(MangoCast.VIDEO, picture)
        assertTrue(sink.videoLatch.await(2, TimeUnit.SECONDS))
        assertArrayEquals("the timestamp is taken off", picture, sink.video.single())

        val pcm = ByteArray(1920) { it.toByte() }
        sender.stamped(MangoCast.AUDIO, pcm)
        assertTrue(sink.audioLatch.await(2, TimeUnit.SECONDS))
        assertArrayEquals(pcm, sink.audio.single())

        sender.send(MangoCast.PING, ByteBuffer.allocate(8).putLong(42).array())
        val (type, payload) = sender.readSkippingPings()
        assertEquals(MangoCast.PONG, type)
        assertEquals(42L, ByteBuffer.wrap(payload).long)

        // A message type from a newer version is skipped, and the session goes on.
        sender.send(0x55, byteArrayOf(9, 9, 9))
        sink.videoLatch = CountDownLatch(1)
        sender.stamped(MangoCast.VIDEO, picture)
        assertTrue(sink.videoLatch.await(2, TimeUnit.SECONDS))

        sender.send(MangoCast.BYE, "Stopped".toByteArray())
        assertTrue(sink.endLatch.await(2, TimeUnit.SECONDS))
        val (_, reason, lost) = sink.ended.single()
        assertEquals("DESKTOP-4F2K stopped: Stopped", reason)
        assertFalse(lost)
        assertNull(server.session)
        assertTrue(sender.closedByTv())
    }

    @Test
    fun refusesWhenTheTvSaysSo() {
        sink.decide = { Decision.Refuse("busy", "An iPhone is mirroring to Living Room right now.") }
        val sender = Sender(port)
        sender.hello()
        val (type, payload) = sender.read()
        assertEquals(MangoCast.REFUSE, type)
        assertEquals("busy", json(payload).getString("reason"))
        assertEquals("An iPhone is mirroring to Living Room right now.", json(payload).getString("message"))
        assertTrue(sender.closedByTv())
        assertTrue(sink.started.isEmpty())
        assertTrue(sink.ended.isEmpty())
    }

    @Test
    fun oneSenderAtATime() {
        val first = welcomed()
        val second = Sender(port)
        second.hello("""{"protocol": 1, "name": "LAPTOP", "codecs": ["h264"]}""")
        val (type, payload) = second.read()
        assertEquals(MangoCast.REFUSE, type)
        assertEquals("busy", json(payload).getString("reason"))
        assertTrue(json(payload).getString("message").contains("DESKTOP-4F2K"))
        assertEquals("the TV wasn't asked about the second", 1, sink.hellos.size)
        assertTrue(second.closedByTv())
        // The first carries on.
        first.stamped(MangoCast.VIDEO, byteArrayOf(0, 0, 0, 1, 0x26, 1))
        assertTrue(sink.videoLatch.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun refusesAnotherVersionOrNoCodecInCommon() {
        val newer = Sender(port)
        newer.hello("""{"protocol": 2, "name": "FUTURE", "codecs": ["h264"]}""")
        assertEquals("protocol", json(newer.read().second).getString("reason"))

        sink.decide = { Decision.Accept(offer.copy(h265 = false)) }
        val hevcOnly = Sender(port)
        hevcOnly.hello("""{"protocol": 1, "name": "HEVC-ONLY", "codecs": ["h265"]}""")
        val (type, payload) = hevcOnly.read()
        assertEquals(MangoCast.REFUSE, type)
        assertEquals("protocol", json(payload).getString("reason"))
        assertTrue(sink.started.isEmpty())
    }

    @Test
    fun noSoundUnlessItWasAskedFor() {
        sink.decide = { Decision.Accept(offer.copy(audio = false)) }
        val sender = Sender(port)
        sender.hello()
        val welcome = json(sender.readSkippingPings().second)
        assertTrue(welcome.isNull("audio"))
        assertTrue(sink.startLatch.await(2, TimeUnit.SECONDS))
        assertFalse(sink.started.single().audio)
        sender.stamped(MangoCast.AUDIO, ByteArray(64))
        sender.stamped(MangoCast.VIDEO, byteArrayOf(0, 0, 0, 1, 0x26, 1))
        assertTrue(sink.videoLatch.await(2, TimeUnit.SECONDS))
        assertTrue("sound it didn't ask for is dropped", sink.audio.isEmpty())
    }

    @Test
    fun asksForKeyframesAtMostTwiceASecond() {
        val sender = welcomed()
        val session = sink.started.single()
        session.requestKeyframe()
        session.requestKeyframe()
        assertEquals(MangoCast.KEYFRAME, sender.readSkippingPings().first)
        Thread.sleep(600)
        session.requestKeyframe()
        assertEquals(MangoCast.KEYFRAME, sender.readSkippingPings().first)
        // Nothing else came in between: the second request was dropped.
        sender.socket.soTimeout = 300
        val extra = try {
            sender.readSkippingPings().first
        } catch (e: SocketTimeoutException) {
            null
        }
        assertNull(extra)
    }

    @Test
    fun theTvCanEndIt() {
        val sender = welcomed()
        server.disconnect("Disconnected on the TV.")
        val (type, payload) = sender.readSkippingPings()
        assertEquals(MangoCast.BYE, type)
        assertEquals("Disconnected on the TV.", String(payload))
        assertTrue(sender.closedByTv())
        assertTrue(sink.endLatch.await(2, TimeUnit.SECONDS))
        assertFalse(sink.ended.single().third)
    }

    @Test
    fun aSenderThatDropsOffIsLost() {
        val sender = welcomed()
        sender.close()
        assertTrue(sink.endLatch.await(2, TimeUnit.SECONDS))
        val (_, reason, lost) = sink.ended.single()
        assertTrue(lost)
        assertEquals("Lost the connection to DESKTOP-4F2K.", reason)
    }

    @Test
    fun anOversizedMessageEndsTheSession() {
        val sender = welcomed()
        DataOutputStream(sender.socket.getOutputStream()).apply {
            writeByte(MangoCast.VIDEO)
            writeInt(MangoCast.MAX_PAYLOAD + 1)
            flush()
        }
        assertTrue(sink.endLatch.await(2, TimeUnit.SECONDS))
        assertTrue(sink.ended.single().third)
        assertTrue(logs.any { it.contains("broke the protocol") })
    }

    @Test
    fun aNewSenderCanComeOnceTheLastHasGone() {
        val first = welcomed()
        first.send(MangoCast.BYE, ByteArray(0))
        assertTrue(sink.endLatch.await(2, TimeUnit.SECONDS))
        val second = Sender(port)
        second.hello("""{"protocol": 1, "name": "LAPTOP", "codecs": ["h264"]}""")
        val (type, payload) = second.readSkippingPings()
        assertEquals(MangoCast.WELCOME, type)
        assertEquals("h264", json(payload).getString("codec"))
    }

    @Test
    fun anythingButHelloFirstIsRefused() {
        val sender = Sender(port)
        sender.stamped(MangoCast.VIDEO, byteArrayOf(0, 0, 0, 1, 0x65))
        assertTrue(sender.closedByTv())
        assertTrue(sink.hellos.isEmpty())
        assertTrue(sink.started.isEmpty())
    }

    @Test
    fun keepsTheConnectionAliveWhenIdle() {
        val sender = welcomed()
        // The TV mostly listens; it still says something every couple of seconds.
        val (type, payload) = sender.read()
        assertEquals(MangoCast.PING, type)
        assertEquals(8, payload.size)
        assertNotNull(server.session)
    }

    @Test
    fun stoppingTheServerEndsTheSession() {
        val sender = welcomed()
        server.stop("The TV stopped receiving.")
        assertEquals(MangoCast.BYE, sender.readSkippingPings().first)
        assertTrue(sink.endLatch.await(2, TimeUnit.SECONDS))
        assertEquals(-1, server.port)
    }
}
