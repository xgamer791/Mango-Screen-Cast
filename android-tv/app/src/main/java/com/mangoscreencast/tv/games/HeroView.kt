package com.mangoscreencast.tv.games

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.mangoscreencast.tv.ui.TileWall
import kotlin.math.roundToInt

/**
 * The Games page's art, as the PS5 home's hero but retro: the focused game's
 * picture drawn large on the right, pixel art scaled by a whole, even number
 * so each of its pixels is square and hard-edged, a CRT's scanlines laid on its
 * own rows, and fading out to the left and the
 * bottom into the same picture blurred right out across the whole screen,
 * like a lens focused on the screen and not the room behind it. A new picture
 * fades in over the last and settles in from a touch larger.
 */
class HeroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Art(val sharp: Bitmap, val blur: Bitmap)

    private var art: Art? = null
    private var previous: Art? = null
    private var fade = 1f
    private var fader: ValueAnimator? = null

    private val pixels = Paint().apply { isFilterBitmap = false }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = Color.argb(DIM, 0, 0, 0) }
    private val lines = Paint().apply { color = Color.argb(SCANLINE, 0, 0, 0) }
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val box = RectF()
    private val whole = Rect()

    /** Shows `picture` (or nothing), fading over from what was there. */
    fun show(picture: Bitmap?) {
        if (picture === art?.sharp) return
        fader?.cancel()
        previous = art
        art = picture?.let { Art(it, blurred(it)) }
        if (previous == null || !isAttachedToWindow) {
            previous = null
            fade = 1f
            invalidate()
            return
        }
        fade = 0f
        fader = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            interpolator = TileWall.GLIDE
            addUpdateListener {
                fade = it.animatedValue as Float
                if (fade >= 1f) previous = null
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        fader?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        whole.set(0, 0, width, height)
        canvas.drawColor(Color.BLACK)
        previous?.let { draw(canvas, it, 255, 1f) }
        art?.let { draw(canvas, it, (fade * 255).roundToInt(), 1f + SETTLE * (1f - fade)) }
    }

    private fun draw(canvas: Canvas, art: Art, alpha: Int, zoom: Float) {
        if (alpha <= 0) return
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        // Behind: the picture blurred across the whole screen, and darkened.
        canvas.drawBitmap(art.blur, null, cover(art.blur), smooth)
        canvas.drawRect(whole.left.toFloat(), whole.top.toFloat(), whole.right.toFloat(), whole.bottom.toFloat(), dim)
        // In front: the picture itself on the right. Pixel art goes up by a whole, even number, so
        // every pixel is the same size here and again on the 4K panel (which scales the page 1.5x),
        // right-aligned and centred on even pixels; anything sharper is smoothed to most of the height.
        val src = art.sharp
        val pixelArt = src.height <= PIXEL_ART_ROWS
        val k = if (pixelArt) even(height * OVERFLOW / src.height) else 0
        val w: Float
        val h: Float
        if (pixelArt) {
            w = (src.width * k).toFloat()
            h = (src.height * k).toFloat()
        } else {
            h = height * HEIGHT
            w = h * src.width / src.height
        }
        val left = width - w
        val top = if (pixelArt) evenPx((height - h) / 2f).coerceAtLeast(0f) else height * TOP
        val cx = left + w / 2f
        val cy = top + h / 2f
        box.set(cx - w * zoom / 2f, cy - h * zoom / 2f, cx + w * zoom / 2f, cy + h * zoom / 2f)
        val front = canvas.saveLayer(box, null)
        canvas.drawBitmap(src, null, box, if (pixelArt) pixels else smooth)
        // A CRT's scanline across the lower third of each of the game's rows, whole pixels tall.
        if (pixelArt && zoom == 1f) {
            val line = (k / 3).coerceAtLeast(1)
            var y = box.top + k - line
            while (y < box.bottom) {
                canvas.drawRect(box.left, y, box.right, y + line, lines)
                y += k
            }
        }
        // Fading out to the left and towards the bottom, into the blur.
        mask.shader = LinearGradient(box.left, 0f, box.left + box.width() * FADE_LEFT, 0f,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(box, mask)
        mask.shader = LinearGradient(0f, box.top + box.height() * FADE_FROM, 0f, box.bottom,
            Color.BLACK, Color.argb(BOTTOM, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(box, mask)
        canvas.restoreToCount(front)
        canvas.restoreToCount(layer)
    }

    /** The largest even whole number not over `x`, and at least 2. */
    private fun even(x: Float) = ((x / 2f).toInt() * 2).coerceAtLeast(2)

    /** `x` to the even pixel below it. */
    private fun evenPx(x: Float) = ((x / 2f).toInt() * 2).toFloat()

    /** Where a picture fills the whole view, cropped to its shape. */
    private fun cover(image: Bitmap): RectF {
        val scale = maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    companion object {
        /** The picture's height on the page, from this far down: most of it, a little above centre. */
        const val HEIGHT = 0.92f
        const val TOP = 0.02f

        /** How much of its width the picture fades in over from the left, and from where it fades at the bottom. */
        const val FADE_LEFT = 0.5f
        const val FADE_FROM = 0.5f

        /** What's left of the picture at its bottom edge, under the games. */
        const val BOTTOM = 90

        /** The blur behind, darkened by this much. */
        const val DIM = 115

        /** A scanline at 24% black. */
        const val SCANLINE = 61

        /** Pictures this many rows tall or fewer are pixel art, drawn with square pixels and scanlines. */
        const val PIXEL_ART_ROWS = 300

        /** Pixel art may run off the top and bottom by this much, so a 241-row picture scales like a 240. */
        const val OVERFLOW = 1.015f

        /** How long a new picture takes to come in, and how much larger it starts. */
        const val FADE_MS = 360L
        const val SETTLE = 0.035f

        /** A picture blurred by shrinking it to a few dozen pixels and smoothing it back up. */
        fun blurred(picture: Bitmap): Bitmap {
            val tiny = Bitmap.createScaledBitmap(picture, 24, (24f * picture.height / picture.width).roundToInt().coerceAtLeast(1), true)
            return Bitmap.createScaledBitmap(tiny, 96, (96f * picture.height / picture.width).roundToInt().coerceAtLeast(1), true)
        }
    }
}
