package com.mangoscreencast.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Plays AirPlay audio as it arrives, with only a few frames of buffering.
 *
 * Mirroring sends AAC-ELD; AirPlay "audio only" may send AAC-LC or PCM.
 * ALAC (Apple Lossless, used for music-only AirPlay) is not supported yet.
 * A Windows computer (Mango Cast) sends 48 kHz PCM.
 * Everything is called on the AirPlay audio thread, or the Windows one.
 */
class AudioPlayer {
    private var codecType = 0
    /** The PCM rate the track plays at: AirPlay's 44.1 kHz unless [configure] said otherwise. */
    private var sampleRate = SAMPLE_RATE
    private var decoder: MediaCodec? = null
    private var track: AudioTrack? = null
    private var framesWritten = 0
    private var volume = 1f
    private var warnedUnsupported = false
    private val info = MediaCodec.BufferInfo()
    private var pcm = ByteArray(8192)

    @Volatile
    var enabled = true

    /** `sampleRate` applies to PCM ([CODEC_PCM]); AAC always decodes to 44.1 kHz. */
    @Synchronized
    fun configure(ct: Int, samplesPerFrame: Int, sampleRate: Int = SAMPLE_RATE) {
        release()
        codecType = ct
        this.sampleRate = if (ct == CT_PCM) sampleRate else SAMPLE_RATE
        warnedUnsupported = false
        val config = when (ct) {
            CT_AAC_ELD -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00) // ELD, 44.1 kHz, stereo, 480 spf
            CT_AAC_LC -> byteArrayOf(0x12, 0x10) // LC, 44.1 kHz, stereo
            else -> null
        }
        if (config != null) {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
                format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    if (ct == CT_AAC_ELD) MediaCodecInfo.CodecProfileLevel.AACObjectELD
                    else MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(format, null, null, 0)
                codec.start()
                decoder = codec
            } catch (e: Exception) {
                Log.e(TAG, "AAC decoder failed to start", e)
                decoder = null
            }
        }
        Log.i(TAG, "audio format ct=$ct spf=$samplesPerFrame")
    }

    @Synchronized
    fun submit(frame: ByteBuffer, ct: Int) {
        if (!enabled) return
        when (ct) {
            CT_PCM -> {
                // UxPlay hands PCM over as 16-bit little-endian, which is what AudioTrack takes.
                val n = frame.remaining() and 1.inv()
                ensurePcm(n)
                frame.duplicate().get(pcm, 0, n)
                write(pcm, n)
            }
            CT_AAC_ELD, CT_AAC_LC -> decode(frame)
            else -> if (!warnedUnsupported) {
                warnedUnsupported = true
                Log.w(TAG, "audio codec $ct is not supported; playing video without sound")
            }
        }
    }

    private fun decode(frame: ByteBuffer) {
        val codec = decoder ?: return
        try {
            val index = codec.dequeueInputBuffer(5_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                buffer.clear()
                val length = minOf(frame.remaining(), buffer.capacity())
                val slice = frame.duplicate()
                slice.limit(slice.position() + length)
                buffer.put(slice)
                codec.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0)
            }
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                val buffer = codec.getOutputBuffer(out)
                if (buffer != null && info.size > 0) {
                    ensurePcm(info.size)
                    buffer.position(info.offset)
                    buffer.get(pcm, 0, info.size)
                    write(pcm, info.size)
                }
                codec.releaseOutputBuffer(out, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio decode failed", e)
        }
    }

    private fun write(data: ByteArray, length: Int) {
        val track = track ?: createTrack() ?: return
        // Never block the network thread: if the track is full the sender is
        // running slightly fast, and dropping a few samples keeps latency flat.
        val written = if (Build.VERSION.SDK_INT >= 23) {
            track.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING)
        } else {
            track.write(data, 0, length)
        }
        if (written > 0) framesWritten++
        // Start once a couple of packets are queued so the first ones don't underrun.
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING && framesWritten >= START_THRESHOLD_FRAMES) {
            track.play()
        }
    }

    private fun createTrack(): AudioTrack? {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minimum <= 0) return null
        val size = maxOf(minimum * 2, sampleRate * 4 * TRACK_BUFFER_MS / 1000)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()
        val track = try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(attributes, format, size, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack failed", e)
            return null
        }
        track.setVolume(volume)
        this.track = track
        framesWritten = 0
        return track
    }

    /** AirPlay volume: -30 dB (quietest) to 0 dB, -144 = mute. */
    @Synchronized
    fun setVolumeDb(db: Float) {
        volume = when {
            db <= -144f -> 0f
            db >= 0f -> 1f
            else -> 10f.pow(db.coerceAtLeast(-30f) / 20f)
        }
        track?.setVolume(volume)
    }

    @Synchronized
    fun flush() {
        try {
            decoder?.flush()
        } catch (_: Exception) {
        }
        track?.let {
            it.pause()
            it.flush()
        }
        framesWritten = 0
    }

    @Synchronized
    fun release() {
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        decoder = null
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
        framesWritten = 0
    }

    private fun ensurePcm(size: Int) {
        if (pcm.size < size) pcm = ByteArray(size)
    }

    companion object {
        /** Signed 16-bit little-endian PCM, as UxPlay and Mango Cast hand it over. */
        const val CODEC_PCM = 1

        private const val TAG = "MangoAudio"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val CT_PCM = CODEC_PCM
        private const val CT_AAC_LC = 4
        private const val CT_AAC_ELD = 8
        private const val TRACK_BUFFER_MS = 60
        private const val START_THRESHOLD_FRAMES = 3
    }
}
