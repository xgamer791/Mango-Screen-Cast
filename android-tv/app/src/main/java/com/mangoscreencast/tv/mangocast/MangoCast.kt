package com.mangoscreencast.tv.mangocast

import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Mango Cast, version 1 (protocol/mango-cast.md): how a Windows computer, which
 * can't send AirPlay, puts its screen and sound on the TV. One TCP connection;
 * every message is a type byte, a 4-byte big-endian length and its payload.
 */
object MangoCast {
    const val VERSION = 1
    const val SERVICE_TYPE = "_mangocast._tcp"
    const val DEFAULT_PORT = 7270
    const val MAX_PAYLOAD = 16 shl 20

    const val HELLO = 0x01
    const val WELCOME = 0x02
    const val REFUSE = 0x03
    const val VIDEO = 0x10
    const val KEYFRAME = 0x11
    const val AUDIO = 0x20
    const val PING = 0x30
    const val PONG = 0x31
    const val BYE = 0x3F

    /** Sound comes as signed 16-bit little-endian PCM, 48 kHz stereo. */
    const val AUDIO_RATE = 48_000
    const val AUDIO_CHANNELS = 2

    /** VIDEO and AUDIO payloads start with the sender's 8-byte timestamp. */
    const val TIMESTAMP_BYTES = 8

    const val HELLO_TIMEOUT_MS = 5_000
    const val SILENCE_TIMEOUT_MS = 8_000
    const val KEEPALIVE_MS = 2_000L

    const val H265 = "h265"
    const val H264 = "h264"
}

/** What a sender says about itself (HELLO). */
data class Hello(
    val protocol: Int,
    val name: String,
    val os: String,
    /** What it can encode, in its order of preference. */
    val codecs: List<String>,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audio: Boolean,
) {
    companion object {
        /** Reads a HELLO payload; throws [IllegalArgumentException] when it isn't one. */
        fun parse(payload: ByteArray): Hello {
            val json = try {
                JSONObject(String(payload, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException("HELLO isn't JSON")
            }
            val codecs = json.optJSONArray("codecs")
                ?.let { array -> (0 until array.length()).map { array.optString(it).lowercase() } }
                .orEmpty()
            return Hello(
                protocol = json.optInt("protocol", 0),
                name = json.optString("name").trim().take(MAX_NAME).ifBlank { "Windows" },
                os = json.optString("os").trim().take(MAX_NAME).ifBlank { "Windows" },
                codecs = codecs,
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                fps = json.optInt("fps", 60),
                audio = json.optBoolean("audio", false),
            )
        }

        private const val MAX_NAME = 80
    }
}

/**
 * What the TV takes. [h264Width] × [h264Height] is the largest H.264 picture
 * its decoder plays (1080p on many TVs, whose 4K decoding is for HEVC).
 */
data class Offer(
    val name: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val fps: Int,
    val h265: Boolean,
    val audio: Boolean,
    val h264Width: Int = 1920,
    val h264Height: Int = 1080,
) {
    /** What the TV takes in `codec`: H.264 no bigger than its decoder plays (the same shape, smaller). */
    fun forCodec(codec: String): Offer {
        if (codec != MangoCast.H264 || (maxWidth <= h264Width && maxHeight <= h264Height)) return this
        val (w, h) = if (maxWidth.toLong() * h264Height >= maxHeight.toLong() * h264Width) {
            h264Width to (maxHeight.toLong() * h264Width / maxWidth).toInt()
        } else {
            (maxWidth.toLong() * h264Height / maxHeight).toInt() to h264Height
        }
        return copy(maxWidth = w and 1.inv(), maxHeight = h and 1.inv())
    }

    /** The first of the sender's codecs the TV decodes, or null. */
    fun codecFor(hello: Hello): String? = hello.codecs.firstOrNull { it == MangoCast.H264 || (it == MangoCast.H265 && h265) }

    /** The WELCOME payload for a sender that will send [codec]. */
    fun welcome(codec: String, hello: Hello): ByteArray {
        val json = JSONObject()
            .put("protocol", MangoCast.VERSION)
            .put("name", name)
            .put("codec", codec)
            .put("maxWidth", maxWidth)
            .put("maxHeight", maxHeight)
            .put("fps", fps)
        if (audio && hello.audio) {
            json.put("audio", JSONObject()
                .put("format", "pcm_s16le")
                .put("rate", MangoCast.AUDIO_RATE)
                .put("channels", MangoCast.AUDIO_CHANNELS))
        } else {
            json.put("audio", JSONObject.NULL)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** The picture the sender will send: its screen scaled to fit, keeping its shape, even sides. */
    fun pictureFor(hello: Hello): Pair<Int, Int> {
        val w = hello.width.takeIf { it > 0 } ?: maxWidth
        val h = hello.height.takeIf { it > 0 } ?: maxHeight
        val (fitW, fitH) = when {
            w <= maxWidth && h <= maxHeight -> w to h
            // Wider than the TV's shape: as wide as it takes.
            w.toLong() * maxHeight >= h.toLong() * maxWidth -> maxWidth to (h.toLong() * maxWidth / w).toInt()
            else -> (w.toLong() * maxHeight / h).toInt() to maxHeight
        }
        return (fitW and 1.inv()).coerceAtLeast(2) to (fitH and 1.inv()).coerceAtLeast(2)
    }

    /** The TXT record announced with `_mangocast._tcp`, in DNS wire format. */
    fun txtRecord(id: String, model: String): ByteArray = txt(
        "v" to MangoCast.VERSION.toString(),
        "id" to id,
        "model" to model,
        "w" to maxWidth.toString(),
        "h" to maxHeight.toString(),
        "fps" to fps.toString(),
        "codecs" to if (h265) "h265,h264" else "h264",
        "audio" to if (audio) "1" else "0",
    )
}

/** The TV's answer to a HELLO. */
sealed class Decision {
    data class Accept(val offer: Offer) : Decision()

    /** `reason` is `busy`, `protocol` or `off` (protocol/mango-cast.md); `message` is for people. */
    data class Refuse(val reason: String, val message: String) : Decision() {
        fun payload(): ByteArray = JSONObject().put("reason", reason).put("message", message)
            .toString().toByteArray(Charsets.UTF_8)
    }
}

/** Length-prefixed `key=value` strings, as DNS TXT records are sent. */
internal fun txt(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((key, value) in entries) {
        val bytes = "$key=$value".toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        out.write(bytes.size)
        out.write(bytes)
    }
    return out.toByteArray()
}
