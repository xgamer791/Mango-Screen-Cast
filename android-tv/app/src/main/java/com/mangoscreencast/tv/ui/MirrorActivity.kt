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
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.display.DisplayCapabilities
import com.mangoscreencast.tv.media.VideoDecoder
import kotlin.math.abs

/** Full-screen mirrored picture. Back twice disconnects; OK shows stats. */
class MirrorActivity : Activity(), SurfaceHolder.Callback, AirPlayReceiver.Listener, VideoDecoder.Listener {
    private lateinit var frame: AspectFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statsView: TextView
    private lateinit var bannerView: TextView
    private lateinit var pausedView: TextView

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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        configureDisplay()
        surfaceView.holder.addCallback(this)
    }

    /**
     * Asks the TV for its game mode (no picture processing, which can add
     * 50-100 ms) and for a 60 Hz native-resolution mode, so 60 fps plays
     * evenly instead of juddering on a 50 Hz or 24 Hz setting.
     */
    private fun configureDisplay() {
        val attributes = window.attributes
        if (Build.VERSION.SDK_INT >= 30) attributes.preferMinimalPostProcessing = true
        if (Build.VERSION.SDK_INT >= 23) {
            val display = currentDisplay()
            val current = display.mode
            val best = DisplayCapabilities.preferredMode(display)
            val currentIsEven = abs(current.refreshRate - 60f) < 1f || abs(current.refreshRate - 120f) < 1f
            val currentIsNative = best == null ||
                current.physicalWidth.toLong() * current.physicalHeight >=
                best.physicalWidth.toLong() * best.physicalHeight
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
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        receiver.removeListener(this)
        receiver.video.listener = null
        receiver.release(this)
        super.onStop()
    }

    // --- Surface ----------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            } catch (_: Exception) {
            }
        }
        receiver.video.setSurface(holder.surface, currentDisplay().refreshRate)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        receiver.video.setSurface(null, 0f)
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread { frame.setAspect(width, height) }
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
        if (bannerView.text != text) {
            bannerView.text = text
            bannerView.alpha = 1f
            handler.removeCallbacks(hideBanner)
            handler.postDelayed(hideBanner, 3500)
        }
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
            s.width, s.height, s.codec, s.fps, s.decodeMs, s.droppedFrames,
            profile?.width ?: 0, profile?.height ?: 0, settings.latencyMode.name.lowercase(),
        )
    }

    // --- Remote -----------------------------------------------------------------

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
        }
        return super.onKeyDown(keyCode, event)
    }
}
