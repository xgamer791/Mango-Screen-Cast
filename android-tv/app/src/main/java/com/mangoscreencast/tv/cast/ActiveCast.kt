package com.mangoscreencast.tv.cast

import com.mangoscreencast.tv.media.MediaPipeline

enum class CastKind(val label: String) {
    MANGO("Mango"),
    AIRPLAY("AirPlay"),
    DLNA("DLNA"),
}

enum class StopReason {
    /** Someone pressed Back / Stop on the TV. */
    USER,

    /** A new cast took over the screen. */
    REPLACED,

    /** The sending device ended it. */
    REMOTE,

    ERROR,
}

/**
 * Whatever is on the TV screen right now. The [ReceiverHub] holds at most one.
 *
 * Screen mirroring (Mango, AirPlay mirroring) hands encoded frames to a
 * [MediaPipeline] that [com.mangoscreencast.tv.ui.CastActivity] renders.
 * Media URLs (DLNA, AirPlay video) use [UrlCast] and play in
 * [com.mangoscreencast.tv.ui.MediaPlayerActivity].
 */
interface ActiveCast {
    val kind: CastKind
    val sourceName: String

    /** Non-null for frame-based casts. */
    val pipeline: MediaPipeline?

    /**
     * Ends the cast. Implementations close their connection and release
     * their pipeline, then call [ReceiverHub.end].
     */
    fun stop(reason: StopReason)
}

enum class PlaybackState { BUFFERING, PLAYING, PAUSED, STOPPED, ENDED }

/** What a URL cast's player exposes to the protocol that started it. */
interface PlaybackController {
    val state: PlaybackState
    val positionMs: Long
    /** Zero or negative if unknown (live streams). */
    val durationMs: Long
    val volume: Float
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    /** Stops playback and closes the player screen. */
    fun close()
}

/**
 * Plays a media URL full screen. The player screen sets [controller] once it
 * is ready; protocol code reads state through it and should treat a null
 * controller as "still starting".
 */
class UrlCast(
    override val kind: CastKind,
    override val sourceName: String,
    val url: String,
    val title: String?,
    val startPositionMs: Long = 0,
    /** Called once when the cast ends, for whatever reason. */
    private val onStopped: (StopReason) -> Unit = {},
) : ActiveCast {
    override val pipeline: MediaPipeline? = null

    @Volatile
    var controller: PlaybackController? = null

    @Volatile
    private var stopped = false

    override fun stop(reason: StopReason) {
        if (stopped) return
        stopped = true
        controller?.close()
        controller = null
        onStopped(reason)
        com.mangoscreencast.tv.MangoApp.hub.end(this)
    }
}
