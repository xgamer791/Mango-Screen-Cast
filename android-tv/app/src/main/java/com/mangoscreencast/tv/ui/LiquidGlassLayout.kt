package com.mangoscreencast.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mango Liquid Glass, as on the web: what lies behind this view, blurred,
 * saturated to 125% and darkened to 70%, under a tint of rgb(10 10 16) at
 * 63%. Square corners, no rim, sheen or shadow. Without a backdrop it is a
 * solid rgb(22 22 28) at 90%, the web version's fallback. The web version's
 * edge refraction only runs in Chromium; elsewhere it stays frosted, as here.
 *
 * Android's own blur (RenderEffect) doesn't reach the 1440p surface the
 * home screen is drawn into (Canvas1440Layout), and it needs Android 12. So
 * the views behind the glass (the wallpaper, its overlay, the badge's glow)
 * are drawn once into a small bitmap and blurred here, inside the glass's
 * own bounds with the edges clamped, as a backdrop filter does. They don't
 * change; the backdrop is made again when the glass moves or changes size.
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var backdrop: Bitmap? = null
    private var backdropFor: Rect? = null
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(filterMatrix())
    }
    private val dst = Rect()
    private val tintPaint = Paint().apply { color = TINT }
    private val fallbackPaint = Paint().apply { color = FALLBACK }

    init {
        setWillNotDraw(false)
    }

    override fun draw(canvas: Canvas) {
        val bounds = Rect(left, top, right, bottom)
        if (bounds != backdropFor) {
            backdrop?.recycle()
            backdrop = makeBackdrop()
            backdropFor = bounds
        }
        val image = backdrop
        if (image != null) {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(image, null, dst, backdropPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackPaint)
        }
        super.draw(canvas)
    }

    /** The views behind this one, inside its bounds, small and blurred; null if there's nothing to draw. */
    private fun makeBackdrop(): Bitmap? {
        val parent = parent as? ViewGroup ?: return null
        if (width <= 0 || height <= 0) return null
        val sigma = BLUR_DP * resources.displayMetrics.density * SCALE
        val w = (width * SCALE).roundToInt().coerceAtLeast(1)
        val h = (height * SCALE).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(w.toFloat() / width, h.toFloat() / height)
        canvas.translate(-left.toFloat(), -top.toFloat())
        parent.background?.draw(canvas)
        for (i in 0 until parent.indexOfChild(this)) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            canvas.save()
            canvas.translate(child.left + child.translationX, child.top + child.translationY)
            if (child.alpha < 1f) canvas.saveLayerAlpha(null, (child.alpha * 255).roundToInt())
            child.draw(canvas)
            canvas.restore()
        }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        gaussianBlur(pixels, w, h, sigma)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    companion object {
        /** 19.5 px on the 2560 × 1440 page, in dp of the 960 × 540 dp window. */
        const val BLUR_DP = 19.5f * 960f / 2560f
        const val SATURATE = 1.25f
        const val BRIGHTNESS = 0.70f

        /** rgb(10 10 16 / 0.63). */
        const val TINT = 0xA10A0A10.toInt()

        /** rgb(22 22 28 / 0.9), when there's no backdrop. */
        const val FALLBACK = 0xE616161C.toInt()

        /** The backdrop is made at a quarter of the view's pixels; blurred, it loses nothing. */
        private const val SCALE = 0.25f

        /** CSS saturate(125%) then brightness(70%), as one colour matrix. */
        fun filterMatrix(): ColorMatrix {
            val s = SATURATE
            val saturate = floatArrayOf(
                0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0f, 0f,
                0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0f, 0f,
                0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            return ColorMatrix(saturate).apply { postConcat(ColorMatrix().apply { setScale(BRIGHTNESS, BRIGHTNESS, BRIGHTNESS, 1f) }) }
        }

        /** A Gaussian blur of standard deviation `sigma` px, as three box blurs each way, edges clamped. */
        internal fun gaussianBlur(pixels: IntArray, w: Int, h: Int, sigma: Float) {
            if (sigma < 0.5f) return
            val scratch = IntArray(pixels.size)
            for (size in boxSizes(sigma)) {
                val radius = (size - 1) / 2
                boxBlur(pixels, scratch, w, h, radius, horizontal = true)
                boxBlur(scratch, pixels, w, h, radius, horizontal = false)
            }
        }

        /** Widths of three boxes whose repeated blur matches a Gaussian of `sigma`. */
        internal fun boxSizes(sigma: Float, n: Int = 3): List<Int> {
            val ideal = sqrt(12.0 * sigma * sigma / n + 1)
            var lower = floor(ideal).toInt()
            if (lower % 2 == 0) lower--
            val upper = lower + 2
            val m = ((12.0 * sigma * sigma - n * lower * lower - 4.0 * n * lower - 3 * n) / (-4.0 * lower - 4)).roundToInt()
            return List(n) { if (it < m) lower else upper }
        }

        private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val length = if (horizontal) w else h
            val step = if (horizontal) 1 else w
            val span = (2 * r + 1).toFloat()
            for (line in 0 until lines) {
                val start = if (horizontal) line * w else line
                var a = 0f; var red = 0f; var green = 0f; var blue = 0f
                fun at(i: Int) = src[start + i.coerceIn(0, length - 1) * step]
                for (i in -r..r) {
                    val c = at(i)
                    a += c ushr 24; red += (c shr 16) and 0xFF; green += (c shr 8) and 0xFF; blue += c and 0xFF
                }
                for (i in 0 until length) {
                    dst[start + i * step] = ((a / span).roundToInt() shl 24) or ((red / span).roundToInt() shl 16) or
                        ((green / span).roundToInt() shl 8) or (blue / span).roundToInt()
                    val out = at(i - r)
                    val inn = at(i + r + 1)
                    a += (inn ushr 24) - (out ushr 24)
                    red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    green += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    blue += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
    }
}
