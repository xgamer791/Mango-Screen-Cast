package com.mangoscreencast.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.AttributeSet
import android.widget.FrameLayout
import com.mangoscreencast.tv.R
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The gear's menu panel in Mango Liquid Glass (LiquidGlass), over what was on
 * screen as the gear was pressed ([showBehind]: that part of the gear
 * service's screenshot). The glass is made on a thread of its own and fades in
 * over [GLASS_FADE_MS]; until then the panel is the glass's colour without a
 * backdrop (R.color.glass_fallback). Square, flush with the screen's edges.
 */
class LiquidGlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val glassPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(LiquidGlass.filterMatrix())
    }
    private val tintPaint = Paint().apply { color = context.getColor(R.color.glass_tint) }
    private val fallbackPaint = Paint().apply { color = context.getColor(R.color.glass_fallback) }
    private val whole = Rect()

    /** What's behind the panel, at its size, as the screenshot showed it. */
    var behind: Bitmap? = null
        private set
    private var glass: Bitmap? = null
    private var glassShown = 0f

    /** Makes the glass on this thread, at once (tests). */
    internal var frostNow = false

    init {
        setWillNotDraw(false)
    }

    fun showBehind(image: Bitmap) {
        behind = image
        if (frostNow) {
            glass = LiquidGlass.frost(image, density)
            glassShown = 1f
            invalidate()
            return
        }
        thread(name = "liquid-glass") {
            val started = SystemClock.elapsedRealtime()
            val made = LiquidGlass.frost(image, density)
            Log.i(TAG, "glass ${made.width}x${made.height} made in ${SystemClock.elapsedRealtime() - started} ms")
            post {
                glass = made
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = GLASS_FADE_MS
                    addUpdateListener { glassShown = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        whole.set(0, 0, width, height)
        if (glassShown < 1f) canvas.drawRect(whole, fallbackPaint)
        val image = glass ?: return
        glassPaint.alpha = (glassShown * 255).roundToInt()
        canvas.drawBitmap(image, null, whole, glassPaint)
        tintPaint.alpha = (glassShown * android.graphics.Color.alpha(context.getColor(R.color.glass_tint))).roundToInt()
        canvas.drawRect(whole, tintPaint)
    }

    companion object {
        /** How long the glass takes to come in once it's made. */
        const val GLASS_FADE_MS = 160L
        private const val TAG = "MangoGlass"
    }
}

/**
 * What was on screen as the gear was pressed, for the menu's glass: the gear
 * service's screenshot (GearKeyService), taken by the menu as it opens.
 */
internal object ScreenBehind {
    @Volatile
    var image: Bitmap? = null

    fun take(): Bitmap? = image.also { image = null }

    /** The part of `screen` a panel `share` of its width on the right covers, at `width` x `height`. */
    fun region(screen: Bitmap, share: Float, width: Int, height: Int): Bitmap {
        val w = (screen.width * share).roundToInt().coerceIn(1, screen.width)
        val part = Bitmap.createBitmap(screen, screen.width - w, 0, w, screen.height)
        return if (part.width == width && part.height == height) part else Bitmap.createScaledBitmap(part, width, height, true)
    }
}
