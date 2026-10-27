package com.mangoscreencast.tv.guide

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.guide.PlutoTv.Channel
import com.mangoscreencast.tv.ui.AspectFrameLayout
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Live TV as the Prevue Channel showed it in the 1990s, with Pluto TV's free
 * channels: the chosen channel's show in the blue box and its picture along
 * the top, the grid below. Up and Down change the channel (it tunes once the
 * grid stops moving), OK fills the screen with it, and Back comes back to the
 * grid, then Home. In full screen, Up, Down and the channel keys change the
 * channel straight away.
 */
class GuideActivity : Activity() {
    private lateinit var grid: GuideView
    private lateinit var video: FrameLayout
    private lateinit var videoSpot: View
    private lateinit var frame: AspectFrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var note: TextView
    private lateinit var infoChannel: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoWhen: TextView
    private lateinit var infoUntil: TextView
    private lateinit var infoNumber: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null

    private var channels: List<Channel> = emptyList()
    private var loading = false
    /** When the listings were last asked for: a reread that fails is tried again after [RETRY_MS]. */
    private var askedAt = 0L
    private var failed = false
    private var fullScreen = false
    /** The channel playing, so a reload or a move back to it doesn't tune it again. */
    private var tuned: String? = null
    /** Pluto's session: new each time the guide opens. */
    private val session = UUID.randomUUID().toString()

    private val prefs by lazy { getSharedPreferences("guide", Context.MODE_PRIVATE) }

    /** This TV to Pluto, the same from day to day. */
    private val device by lazy {
        prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            grid.now = now
            val from = GuideGrid.slotStart(now)
            // On the half hour, the grid moves on and the listings for its new half hours are read again.
            if (from != grid.from && !loading && now - askedAt > RETRY_MS) load(from)
            describe()
            handler.postDelayed(this, 1000 - now % 1000)
        }
    }

    private val tune = Runnable { tuneSelected() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_guide)
        grid = findViewById(R.id.guide_grid)
        video = findViewById(R.id.guide_video)
        videoSpot = findViewById(R.id.guide_video_spot)
        frame = findViewById(R.id.guide_video_frame)
        surface = findViewById(R.id.guide_surface)
        note = findViewById(R.id.guide_video_note)
        infoChannel = findViewById(R.id.guide_info_channel)
        infoTitle = findViewById(R.id.guide_info_title)
        infoWhen = findViewById(R.id.guide_info_when)
        infoUntil = findViewById(R.id.guide_info_until)
        infoNumber = findViewById(R.id.guide_info_number)
        // The picture sits over its spot above the grid until it fills the screen. Placed after the layout that
        // moved the spot, since a size given during a layout pass is only measured on the next one.
        videoSpot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> video.post(::placeVideo) }
        load(GuideGrid.slotStart(System.currentTimeMillis()))
    }

    override fun onStart() {
        super.onStart()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            setVideoSurfaceView(surface)
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) frame.setAspect((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
                }

                override fun onRenderedFirstFrame() {
                    note.visibility = View.GONE
                }

                override fun onPlayerError(error: PlaybackException) = played(error)
            })
            playWhenReady = true
        }
        tuned = null
        tuneSelected()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(tune)
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Reads the listings for the three half hours from `from`, keeping the chosen channel. */
    private fun load(from: Long) {
        loading = true
        askedAt = System.currentTimeMillis()
        if (channels.isEmpty()) say(getString(R.string.guide_loading), "")
        worker.execute {
            val read = try {
                PlutoTv.channels(from, from + GuideGrid.SLOTS * GuideGrid.SLOT_MS)
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (isDestroyed) return@post
                loading = false
                if (read.isNullOrEmpty()) {
                    // A failed reread keeps the listings already up, and the tick tries again.
                    if (channels.isEmpty()) {
                        failed = true
                        say(getString(R.string.guide_unreachable), getString(R.string.guide_retry))
                    }
                    return@post
                }
                failed = false
                show(read, from)
            }
        }
    }

    private fun show(read: List<Channel>, from: Long) {
        val keep = channels.getOrNull(grid.selected)?.id ?: prefs.getString("channel", null)
        channels = read
        grid.from = from
        grid.channels = read
        read.indexOfFirst { it.id == keep }.takeIf { it >= 0 }?.let(grid::select)
        describe()
        tuneSelected()
    }

    /** The blue box: the chosen channel's name, its show, until when, and its number. */
    private fun describe() {
        val channel = channels.getOrNull(grid.selected) ?: return
        val (now, _) = GuideGrid.onAt(channel.shows, System.currentTimeMillis())
        infoChannel.text = channel.name
        infoTitle.text = now?.let { GuideGrid.title(it) + GuideGrid.year(it) } ?: ""
        infoWhen.text = if (now != null) getString(R.string.guide_now_showing) else ""
        infoUntil.text = now?.let { getString(R.string.guide_until, GuideGrid.clock(it.stop)) } ?: ""
        infoNumber.text = getString(R.string.guide_channel, channel.number)
    }

    /** A note in the blue box while there are no listings. */
    private fun say(line: String, second: String) {
        infoChannel.text = getString(R.string.guide_pluto)
        infoTitle.text = ""
        infoWhen.text = line
        infoUntil.text = second
        infoNumber.text = ""
    }

    private fun tuneSelected() {
        val channel = channels.getOrNull(grid.selected) ?: return
        val player = player ?: return
        if (channel.id == tuned) return
        tuned = channel.id
        prefs.edit().putString("channel", channel.id).apply()
        note.visibility = View.GONE
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(PlutoTv.stream(channel.link, device, session))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
        )
        player.prepare()
    }

    /** A live stream that fell behind starts again at the live edge; anything else says the channel isn't playing. */
    private fun played(error: PlaybackException) {
        val player = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        note.text = getString(R.string.guide_not_playing)
        note.visibility = View.VISIBLE
    }

    private fun placeVideo() {
        val params = video.layoutParams as FrameLayout.LayoutParams
        if (fullScreen) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.leftMargin = 0
            params.topMargin = 0
        } else {
            val spot = IntArray(2).also(videoSpot::getLocationInWindow)
            val root = IntArray(2).also { findViewById<View>(R.id.guide_root).getLocationInWindow(it) }
            params.width = videoSpot.width
            params.height = videoSpot.height
            params.leftMargin = spot[0] - root[0]
            params.topMargin = spot[1] - root[1]
        }
        video.layoutParams = params
    }

    private fun setFullScreen(on: Boolean) {
        fullScreen = on
        findViewById<View>(R.id.guide_page).visibility = if (on) View.INVISIBLE else View.VISIBLE
        placeVideo()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val by = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> 1
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) when {
                    failed && !loading -> load(GuideGrid.slotStart(System.currentTimeMillis()))
                    !fullScreen && channels.isNotEmpty() -> {
                        handler.removeCallbacks(tune)
                        tuneSelected()
                        setFullScreen(true)
                    }
                }
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (channels.isEmpty()) return true
        grid.move(by, glide = !fullScreen)
        describe()
        handler.removeCallbacks(tune)
        if (fullScreen) tuneSelected() else handler.postDelayed(tune, TUNE_AFTER_MS)
        click(keyCode, event.repeatCount > 0)
        return true
    }

    @Deprecated("Back leaves full screen first.")
    override fun onBackPressed() {
        if (fullScreen) setFullScreen(false) else super.onBackPressed()
    }

    /** The TV's click for a move, as on the other pages (the grid moves its own choice, so Android doesn't). */
    private fun click(keyCode: Int, repeating: Boolean) {
        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) View.FOCUS_UP else View.FOCUS_DOWN
        val sound = if (Build.VERSION.SDK_INT >= 33) SoundEffectConstants.getConstantForFocusDirection(direction, repeating)
            else SoundEffectConstants.getContantForFocusDirection(direction)
        window.decorView.playSoundEffect(sound)
    }

    private companion object {
        /** How long the grid rests on a channel before it's tuned, so running through the list doesn't start each one. */
        const val TUNE_AFTER_MS = 600L
        const val RETRY_MS = 30_000L
    }
}
