package com.mangoscreencast.tv.ui

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.WidePictures
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.display.DisplayCapabilities
import com.mangoscreencast.tv.media.VideoDecoder
import kotlin.math.abs

/** Full-screen mirrored picture. Back twice disconnects; OK shows stats; gear opens Settings. */
class MirrorActivity : Activity(), SurfaceHolder.Callback, AirPlayReceiver.Listener, VideoDecoder.Listener {
    private lateinit var frame: AspectFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statsView: TextView
    private lateinit var bannerView: TextView
    private lateinit var pausedView: TextView
    private lateinit var coverView: View

    private val receiver get() = MangoApp.receiver
    private val settings get() = MangoApp.settings
    private val handler = Handler(Looper.getMainLooper())
    private var lastBackPress = 0L
    private var showStats = false

    private val statsTick = object : Runnable {
        override fun run() {
            renderStats()
            handler.postDelayed(this, 1000)
        }
    }
    private val hideBanner = Runnable { bannerView.animate().alpha(0f).setDuration(400).start() }
    private val uncover = Runnable { coverView.visibility = View.GONE }
    /** The shape of the picture being shown (0 before the first). */
    private var pictureAspect = 0f
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == currentDisplay().displayId) receiver.video.setRefreshRate(currentDisplay().refreshRate)
        }

        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror)
        frame = findViewById(R.id.video_frame)
        surfaceView = findViewById(R.id.video_surface)
        statsView = findViewById(R.id.stats)
        bannerView = findViewById(R.id.banner)
        pausedView = findViewById(R.id.paused)
        coverView = findViewById(R.id.shape_cover)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        frame.widePictures = settings.widePictures
        configureDisplay()
        surfaceView.holder.addCallback(this)
    }

    /** The frame rate the TV offered Apple devices: the Frame rate setting, as picked. */
    private val streamFps get() = receiver.status.profile?.maxFps ?: 60

    /**
     * Asks the TV for its game mode (no picture processing, which can add
     * 50-100 ms) and for a native-resolution 16:9 mode at 60 Hz (or 120 Hz
     * for a 120 fps stream), so frames play evenly instead of juddering on a
     * 50 Hz or 24 Hz setting, and never in a 4096-wide cinema mode, which
     * the TV squeezes into its screen.
     */
    private fun configureDisplay() {
        val attributes = window.attributes
        if (Build.VERSION.SDK_INT >= 30) attributes.preferMinimalPostProcessing = true
        if (Build.VERSION.SDK_INT >= 23) {
            val display = currentDisplay()
            val current = display.mode
            val fps = streamFps
            val best = DisplayCapabilities.preferredMode(display, fps)
            val currentIsEven = if (fps > 60) abs(current.refreshRate - fps) < 1.5f
                else abs(current.refreshRate - 60f) < 1f || abs(current.refreshRate - 120f) < 1f
            val currentIsNative = best == null ||
                (current.physicalWidth == best.physicalWidth && current.physicalHeight == best.physicalHeight)
            // Switching modes blanks HDMI for a second or two, so only do it when it helps.
            if (best != null && best.modeId != current.modeId && (!currentIsEven || !currentIsNative)) {
                attributes.preferredDisplayModeId = best.modeId
            }
        }
        window.attributes = attributes
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display =
        if (Build.VERSION.SDK_INT >= 30) display!! else windowManager.defaultDisplay

    override fun onStart() {
        super.onStart()
        receiver.acquire(this)
        receiver.video.listener = this
        receiver.addListener(this)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        showStats = settings.showStats
        updateStatsVisibility()
    }

    override fun onStop() {
        handler.removeCallbacks(statsTick)
        handler.removeCallbacks(hideBanner)
        handler.removeCallbacks(uncover)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        receiver.removeListener(this)
        receiver.video.listener = null
        receiver.release(this)
        super.onStop()
    }

    // --- Surface ----------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        receiver.video.setLayoutAspect(frame.pictureAspect)
        if (coverView.visibility == View.VISIBLE) {
            // Laid out for the new shape: show it once a frame or two has been drawn at it.
            handler.removeCallbacks(uncover)
            handler.postDelayed(uncover, UNCOVER_DELAY_MS)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                // A stream faster than the screen is shown at the screen's rate.
                val shown = minOf(streamFps.toFloat(), DisplayCapabilities.maxRefreshRate(this))
                holder.surface.setFrameRate(shown, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            } catch (_: Exception) {
            }
        }
        receiver.video.setSurface(holder.surface, currentDisplay().refreshRate)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        receiver.video.setSurface(null, 0f)
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread {
            if (width <= 0 || height <= 0) return@runOnUiThread
            val root = frame.parent as View
            val before = frame.sizeFor(root.width, root.height)
            val aspect = width.toFloat() / height
            val reshaped = pictureAspect != 0f && abs(aspect - pictureAspect) > pictureAspect * 0.02f
            pictureAspect = aspect
            frame.setAspect(width, height)
            if (root.width > 0 && frame.sizeFor(root.width, root.height) == before) {
                // Laid out the same either way (stretched both ways, say): nothing to wait for.
                receiver.video.setLayoutAspect(frame.pictureAspect)
            } else if (reshaped) {
                // A new shape (the iPhone was turned): black for a moment, as the
                // last frame would otherwise show stretched while the player resizes.
                coverView.visibility = View.VISIBLE
                handler.removeCallbacks(uncover)
                handler.postDelayed(uncover, MAX_COVER_MS)
            }
            logLayout(width, height)
            // An iPhone in landscape is wider than the TV: say once how to fill the screen.
            if (!fillHintShown && frame.isWiderThan(root.width, root.height)) {
                fillHintShown = true
                showBanner(getString(wideBanner(frame.widePictures, hint = true)))
            }
        }
    }

    private var fillHintShown = false

    private fun wideBanner(mode: WidePictures, hint: Boolean = false): Int = when (mode) {
        WidePictures.FIT -> if (hint) R.string.fill_hint else R.string.fill_off
        WidePictures.FILL -> R.string.fill_on
        WidePictures.STRETCH -> R.string.stretch_on
    }

    /** For Diagnostics: how the picture sits on the screen, and whether that leaves bars. */
    private fun logLayout(width: Int, height: Int) {
        val root = frame.parent as View
        if (root.width == 0 || root.height == 0) return
        val wide = frame.isWiderThan(root.width, root.height)
        val how = when {
            wide && frame.widePictures == WidePictures.FILL -> "filling the screen, sides cut off"
            wide && frame.widePictures == WidePictures.STRETCH -> "stretched to the screen"
            wide -> "fit, bars above and below"
            width.toLong() * root.height == height.toLong() * root.width -> "fills the screen"
            else -> "fit, bars at the sides"
        }
        receiver.log("player: ${width}x$height picture on a ${root.width}x${root.height} screen, $how")
    }

    private fun showBanner(text: String) {
        bannerView.text = text
        bannerView.alpha = 1f
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 3500)
    }

    // --- Status -----------------------------------------------------------------

    override fun onStatus(status: AirPlayReceiver.Status) {
        if (status.state != AirPlayReceiver.State.MIRRORING) {
            if (!isFinishing) finish()
            return
        }
        pausedView.visibility = if (status.paused) View.VISIBLE else View.GONE
        val from = status.clientName ?: getString(R.string.a_device)
        val text = getString(R.string.mirroring_banner, from)
        if (bannerView.text != text && !fillHintShown) showBanner(text)
    }

    private fun updateStatsVisibility() {
        statsView.visibility = if (showStats) View.VISIBLE else View.GONE
        handler.removeCallbacks(statsTick)
        if (showStats) handler.post(statsTick)
    }

    private fun renderStats() {
        val s = receiver.video.stats()
        val profile = receiver.status.profile
        statsView.text = getString(
            R.string.stats_format,
            s.width, s.height, s.codec, s.fps, s.megabitsPerSecond, s.decodeMs, s.droppedFrames, s.restarts,
            profile?.width ?: 0, profile?.height ?: 0, settings.latencyMode.name.lowercase(), s.shownFps,
            profile?.maxFps ?: 0, s.skippedFrames,
        )
    }

    // --- Remote -----------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (consumeGearKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackPress < 2500) {
                    receiver.disconnect()
                    finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(this, R.string.back_again, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                showStats = !showStats
                updateStatsVisibility()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Up: Fit, Fill, Stretch. Down: back the other way.
                val modes = WidePictures.entries
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else modes.size - 1
                settings.widePictures = modes[(modes.indexOf(settings.widePictures) + step) % modes.size]
                frame.widePictures = settings.widePictures
                fillHintShown = true
                showBanner(getString(wideBanner(frame.widePictures)))
                receiver.video.stats().let { logLayout(it.width, it.height) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        /** After the player is laid out for a new shape, before it's shown. */
        const val UNCOVER_DELAY_MS = 80L
        /** The longest the picture stays covered. */
        const val MAX_COVER_MS = 700L
    }
}
