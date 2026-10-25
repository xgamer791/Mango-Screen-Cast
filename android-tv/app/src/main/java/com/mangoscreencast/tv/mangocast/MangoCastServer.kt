package com.mangoscreencast.tv.mangocast

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for Mango Cast senders (Windows computers) on the local network and
 * hands what they send to a [Sink]. One sender at a time; others are refused
 * as busy. Plain Java sockets, so it runs, and is tested, without Android.
 *
 * Each connection has a reading thread, which calls the sink, and a writing
 * thread for WELCOME, PINGs, KEYFRAME requests and BYE, so a slow network
 * never holds up the decoders.
 */
class MangoCastServer(private val sink: Sink, private val log: (String) -> Unit) {
    interface Sink {
        /** A sender said HELLO: welcome it with an offer, or refuse it. Its reading thread. */
        fun onHello(hello: Hello): Decision

        /** WELCOME went out; pictures and sound follow. */
        fun onStart(session: Session)

        /** One access unit (Annex B), without the timestamp. Valid only during the call. */
        fun onVideo(session: Session, frame: ByteBuffer)

        /** PCM, without the timestamp. Valid only during the call. */
        fun onAudio(session: Session, pcm: ByteBuffer)

        /**
         * The session that [onStart] began is over, for `reason`; `lost` when the
         * connection dropped rather than either side ending it. Once per session, any thread.
         */
        fun onEnd(session: Session, reason: String, lost: Boolean)
    }

    /** The port it listens on, or -1. */
    @Volatile
    var port = -1
        private set

    /** The sender whose pictures are showing, if one is. */
    val session: Session? get() = current

    @Volatile
    private var current: Session? = null
    private var server: ServerSocket? = null
    private val lock = Any()

    /** Listens on [preferredPort] if it's free, or any port. Returns the port, or -1. */
    fun start(preferredPort: Int = MangoCast.DEFAULT_PORT): Int {
        synchronized(lock) {
            if (server != null) return port
            val socket = listen(preferredPort) ?: listen(0) ?: return -1
            server = socket
            port = socket.localPort
            Thread({ acceptLoop(socket) }, "MangoCastAccept").apply { isDaemon = true }.start()
            return port
        }
    }

    /** Stops listening and ends the connected sender's session. */
    fun stop(reason: String = "The TV stopped receiving.") {
        val socket = synchronized(lock) {
            val s = server
            server = null
            port = -1
            s
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        disconnect(reason)
    }

    /** Ends the connected sender's session, if there is one, telling it why. */
    fun disconnect(reason: String) {
        current?.end(reason, sendBye = true)
    }

    private fun listen(port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            // Large enough for a burst of 4K keyframes without the sender stalling.
            receiveBufferSize = RECEIVE_BUFFER
            bind(InetSocketAddress(port))
        }
    } catch (e: IOException) {
        if (port != 0) log("Windows: port $port is taken (${e.message}); using another")
        null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!server.isClosed) log("Windows: accept failed: ${e.message}")
                continue
            }
            Thread({ Session(socket).run() }, "MangoCastSession").apply { isDaemon = true }.start()
        }
    }

    /** One sender's connection. */
    inner class Session internal constructor(private val socket: Socket) {
        /** What it said about itself. Set before [Sink.onStart]. */
        lateinit var hello: Hello
            private set

        /** `h265` or `h264`, as WELCOME told it. */
        var codec = ""
            private set

        /** The size it will send, from its screen and the offer (the stream says for sure). */
        var width = 0
            private set
        var height = 0
            private set

        /** Whether it was asked to send sound. */
        var audio = false
            private set

        val h265: Boolean get() = codec == MangoCast.H265
        val address: String = socket.inetAddress?.hostAddress ?: "?"

        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 shl 10))
        private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "MangoCastWriter").apply { isDaemon = true }
        }
        private val ended = AtomicBoolean(false)

        @Volatile
        private var started = false

        @Volatile
        private var lastSentNs = System.nanoTime()

        @Volatile
        private var lastKeyframeRequestNs = 0L

        private var buffer = ByteArray(256 shl 10)

        /** Asks the sender for a keyframe (the decoder restarted). At most twice a second. */
        fun requestKeyframe() {
            val now = System.nanoTime()
            if (ended.get() || now - lastKeyframeRequestNs < KEYFRAME_REQUEST_INTERVAL_NS) return
            lastKeyframeRequestNs = now
            send(MangoCast.KEYFRAME, EMPTY)
        }

        /** Ends this session: BYE with `reason` (if asked), then the connection closes. */
        fun end(reason: String, sendBye: Boolean, lost: Boolean = false) {
            if (!ended.compareAndSet(false, true)) return
            if (sendBye) {
                try {
                    writer.execute { write(MangoCast.BYE, reason.toByteArray(Charsets.UTF_8)) }
                } catch (_: Exception) {
                }
            }
            writer.shutdown()
            try {
                writer.awaitTermination(BYE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            try {
                socket.close()
            } catch (_: IOException) {
            }
            val wasCurrent = synchronized(lock) {
                if (current === this) {
                    current = null
                    true
                } else {
                    false
                }
            }
            if (started && wasCurrent) {
                log("Windows: ${hello.name} ended: $reason")
                sink.onEnd(this, reason, lost)
            }
        }

        internal fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = MangoCast.HELLO_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 shl 10))
                if (!handshake(input)) return
                socket.soTimeout = MangoCast.SILENCE_TIMEOUT_MS
                writer.scheduleWithFixedDelay(::keepAlive, KEEPALIVE_CHECK_MS, KEEPALIVE_CHECK_MS, TimeUnit.MILLISECONDS)
                readLoop(input)
            } catch (e: SocketTimeoutException) {
                end(if (started) "No word from ${hello.name} for ${MangoCast.SILENCE_TIMEOUT_MS / 1000} seconds." else "No HELLO.",
                    sendBye = true, lost = true)
            } catch (e: ProtocolError) {
                if (!ended.get()) log("Windows: $address broke the protocol: ${e.message}")
                end("Mango Cast protocol error: ${e.message}", sendBye = true, lost = true)
            } catch (e: IOException) {
                end(if (started) "Lost the connection to ${hello.name}." else "The connection closed.", sendBye = false, lost = true)
            } catch (e: RuntimeException) {
                log("Windows: session failed: $e")
                end("Something went wrong on the TV.", sendBye = true, lost = true)
            } finally {
                end("The connection closed.", sendBye = false)
            }
        }

        /** HELLO in, WELCOME or REFUSE out. True when the session goes on. */
        private fun handshake(input: DataInputStream): Boolean {
            val type = input.readUnsignedByte()
            val length = readLength(input)
            if (type != MangoCast.HELLO) throw ProtocolError("expected HELLO, got type $type")
            val payload = ByteArray(length)
            input.readFully(payload)
            val hello = try {
                Hello.parse(payload)
            } catch (e: IllegalArgumentException) {
                throw ProtocolError(e.message ?: "bad HELLO")
            }
            this.hello = hello
            log("Windows: HELLO from ${hello.name} ($address, ${hello.os}): ${hello.width}x${hello.height} " +
                "@ ${hello.fps} fps, codecs ${hello.codecs}, audio ${hello.audio}")

            val busy = current
            val decision = when {
                hello.protocol != MangoCast.VERSION -> Decision.Refuse("protocol",
                    "This TV speaks Mango Cast ${MangoCast.VERSION}; update Mango Cast for Windows or the TV app.")
                busy != null -> Decision.Refuse("busy", "${busy.hello.name} is showing on this TV right now.")
                else -> sink.onHello(hello)
            }
            val offered = when (decision) {
                is Decision.Refuse -> return refuse(decision)
                is Decision.Accept -> decision.offer
            }
            val codec = offered.codecFor(hello)
                ?: return refuse(Decision.Refuse("protocol", "This TV can't decode ${hello.codecs.joinToString(" or ")}."))
            val offer = offered.forCodec(codec)
            val claimed = synchronized(lock) {
                if (current == null) {
                    current = this
                    true
                } else {
                    false
                }
            }
            if (!claimed) return refuse(Decision.Refuse("busy", "Another computer is showing on this TV right now."))

            this.codec = codec
            val (w, h) = offer.pictureFor(hello)
            width = w
            height = h
            audio = offer.audio && hello.audio
            send(MangoCast.WELCOME, offer.welcome(codec, hello))
            started = true
            log("Windows: welcomed ${hello.name}: $codec up to ${offer.maxWidth}x${offer.maxHeight} @ ${offer.fps} fps, " +
                "sound ${if (audio) "on" else "off"}")
            sink.onStart(this)
            return true
        }

        private fun refuse(refusal: Decision.Refuse): Boolean {
            log("Windows: refused ${hello.name} (${refusal.reason}): ${refusal.message}")
            send(MangoCast.REFUSE, refusal.payload())
            end(refusal.message, sendBye = false)
            return false
        }

        private fun readLoop(input: DataInputStream) {
            while (!ended.get()) {
                val type = input.readUnsignedByte()
                val length = readLength(input)
                if (buffer.size < length) buffer = ByteArray(maxOf(length, buffer.size * 2).coerceAtMost(MangoCast.MAX_PAYLOAD))
                input.readFully(buffer, 0, length)
                when (type) {
                    MangoCast.VIDEO -> if (length > MangoCast.TIMESTAMP_BYTES) {
                        sink.onVideo(this, ByteBuffer.wrap(buffer, MangoCast.TIMESTAMP_BYTES, length - MangoCast.TIMESTAMP_BYTES))
                    }
                    MangoCast.AUDIO -> if (audio && length > MangoCast.TIMESTAMP_BYTES) {
                        sink.onAudio(this, ByteBuffer.wrap(buffer, MangoCast.TIMESTAMP_BYTES, length - MangoCast.TIMESTAMP_BYTES))
                    }
                    MangoCast.PING -> send(MangoCast.PONG, buffer.copyOf(length))
                    MangoCast.BYE -> {
                        val why = String(buffer, 0, length, Charsets.UTF_8).trim()
                        end(if (why.isEmpty()) "${hello.name} stopped." else "${hello.name} stopped: $why", sendBye = false)
                    }
                    // PONG only shows it's there; anything else is from a newer version.
                    else -> Unit
                }
            }
        }

        private fun readLength(input: DataInputStream): Int {
            val length = input.readInt()
            if (length < 0 || length > MangoCast.MAX_PAYLOAD) throw ProtocolError("a $length-byte message")
            return length
        }

        private fun keepAlive() {
            if (System.nanoTime() - lastSentNs >= MangoCast.KEEPALIVE_MS * 1_000_000) {
                write(MangoCast.PING, ByteBuffer.allocate(8).putLong(System.nanoTime() / 1000).array())
            }
        }

        private fun send(type: Int, payload: ByteArray) {
            try {
                writer.execute { write(type, payload) }
            } catch (_: Exception) {
                // Ending: nothing more goes out.
            }
        }

        /** Writer thread only. */
        private fun write(type: Int, payload: ByteArray) {
            try {
                output.writeByte(type)
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
                lastSentNs = System.nanoTime()
            } catch (_: IOException) {
                // The reading thread finds out and ends the session.
            }
        }
    }

    private class ProtocolError(message: String) : IOException(message)

    private companion object {
        val EMPTY = ByteArray(0)
        const val RECEIVE_BUFFER = 4 shl 20
        const val KEEPALIVE_CHECK_MS = 1_000L
        const val BYE_WAIT_MS = 500L
        const val KEYFRAME_REQUEST_INTERVAL_NS = 500_000_000L
    }
}
