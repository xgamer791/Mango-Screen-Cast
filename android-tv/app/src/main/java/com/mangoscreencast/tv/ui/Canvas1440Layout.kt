package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * A page drawn at 2560 × 1440 (1440p), whatever size the TV runs its screens at.
 *
 * Android TV lays apps out on a 960 × 540 dp window, and most TVs draw that
 * window at 1920 × 1080 even on a 4K panel (this one does), so every frame is
 * stretched 2×. This frame lays its page out as before, on that window, but
 * draws it 4/3 larger into a 2560 × 1440 surface (a SurfaceView above the
 * window), which the TV scales once onto its panel: the page's text, icons and
 * logos are drawn with 1440p's pixels. The page stays in the window, so focus
 * and the remote's keys work as they always have; only its picture goes to the
 * surface, and the window leaves it out while the surface shows it. Without
 * the surface (before it's there, after it's gone, or if it can't be drawn
 * into), the page draws in the window as it did before.
 *
 * Dialogs and toasts are windows of their own, drawn at the TV's own size.
 */
class Canvas1440Layout(context: Context) : FrameLayout(context), SurfaceHolder.Callback, ViewTreeObserver.OnDrawListener {
    companion object {
        private const val TAG = "Canvas1440"

        /** The surface's width: 1440p's. */
        const val WIDTH = 2560

        /** The surface for a window `width` × `height`: 2560 wide, keeping the window's shape (2560 × 1440 at 16:9). */
        fun bufferSize(width: Int, height: Int): Size =
            if (width <= 0 || height <= 0) Size(WIDTH, 1440) else Size(WIDTH, Math.round(WIDTH.toFloat() * height / width))

        /** Makes `layout` the activity's page, drawn at 1440p. */
        fun setContentView(activity: Activity, layout: Int): Canvas1440Layout {
            val frame = Canvas1440Layout(activity)
            frame.setPage(LayoutInflater.from(activity).inflate(layout, frame, false))
            activity.setContentView(frame)
            return frame
        }
    }

    private val surfaceView = SurfaceView(context).apply {
        // Above the window, which is left with only the window's background under it.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this@Canvas1440Layout)
    }

    /** The page: laid out in the window, drawn in the surface. */
    var page: View? = null
        private set

    /** The surface size asked for. */
    var requestedSize: Size = bufferSize(0, 0)
        private set

    /** Whether the surface holds the page's latest picture, so the window leaves the page out. */
    var showing = false
        private set

    // How a frame of the surface is started (null: the surface isn't ready, so no frame) and finished:
    // on a hardware canvas (tests draw into a bitmap).
    internal var lockCanvas: (Surface) -> Canvas? = { if (it.isValid) it.lockHardwareCanvas() else null }
    internal var postCanvas: (Surface, Canvas) -> Unit = { surface, canvas -> surface.unlockCanvasAndPost(canvas) }

    private var surface: Surface? = null
    private var surfaceSize = requestedSize
    /** Drawing into the surface failed once: the page stays in the window from then on. */
    private var broken = false
    private var drawingSurface = false
    private var observer: ViewTreeObserver? = null

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun setPage(view: View) {
        page?.let(::removeView)
        page = view
        addView(view, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = viewTreeObserver.also { it.addOnDrawListener(this) }
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnDrawListener(this)
        observer = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestedSize = bufferSize(w, h)
        surfaceView.holder.setFixedSize(requestedSize.width, requestedSize.height)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child === page && showing && !drawingSurface) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    /** Every time the window draws (the page changed, the focus moved), the surface does too. */
    override fun onDraw() = drawSurface()

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        onSurface(holder.surface, Size(width, height))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceGone()

    internal fun onSurface(surface: Surface, size: Size) {
        this.surface = surface
        surfaceSize = size
        drawSurface()
    }

    internal fun onSurfaceGone() {
        surface = null
        if (showing) {
            showing = false
            invalidate()
        }
    }

    private fun drawSurface() {
        val page = page ?: return
        val surface = surface ?: return
        if (broken || width == 0 || height == 0) return
        try {
            val canvas = lockCanvas(surface) ?: return
            try {
                canvas.drawColor(Color.BLACK)
                canvas.scale(surfaceSize.width.toFloat() / width, surfaceSize.height.toFloat() / height)
                drawingSurface = true
                drawChild(canvas, page, drawingTime)
            } finally {
                drawingSurface = false
                postCanvas(surface, canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't draw the page at 1440p; it draws in the window instead", e)
            broken = true
            showing = false
            invalidate()
            post { surfaceView.visibility = GONE }
            return
        }
        if (!showing) {
            showing = true
            // The window leaves the page out from its next frame.
            invalidate()
        }
    }
}
