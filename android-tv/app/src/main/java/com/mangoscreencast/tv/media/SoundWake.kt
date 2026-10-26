package com.mangoscreencast.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Keeps the TV's sound output awake while Mango's screens are up, so the
 * remote's clicks play the moment a key is pressed, every time.
 *
 * Android puts an output to sleep 3 s after its last sound (the Hisense's
 * speaker output: "Standby Time mSec: 3000" in dumpsys media.audio_flinger),
 * and waking it takes long enough that the first click after a pause comes
 * late or not at all. A tenth of a second of silence played on a loop, on the
 * output the clicks use, keeps it from ever going to sleep. It makes no sound.
 */
class SoundWake {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        track = try {
            val frames = RATE / 10
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 4)
                .build()
                .apply {
                    write(ShortArray(frames * 2), 0, frames * 2)
                    setLoopPoints(0, frames, -1)
                    play()
                }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't keep the sound output awake", e)
            null
        }
    }

    fun stop() {
        track?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        track = null
    }

    private companion object {
        const val TAG = "SoundWake"
        const val RATE = 48000
    }
}
