package com.mangoscreencast.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import com.mangoscreencast.tv.R
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mango Liquid Glass, as Chromium draws it on the web: what lies behind this
 * view, bent at the edges by the #lg-refract filter (red, green and blue by
 * different amounts), then blurred by 24 px and darkened to 70% (saturation
 * left at 100%), under the tint, with the app tiles' rounded corners and no
 * rim, sheen, depth or shadow. The tint and the colour without a backdrop
 * come from R.color.glass_tint and glass_fallback, which have night-mode
 * values as the web version has dark-mode ones (rgb(9 9 12 / 0.22) on a TV in
 * night mode, none in light mode). Sizes are the web version's px on the
 * 2560 x 1440 page.
 *
 * Android's own blur (RenderEffect) doesn't reach the 1440p surface the
 * home screen is drawn into (Canvas1440Layout), and it needs Android 12. So
 * what lies behind the glass on its page (the page's background and the
 * views drawn before the one holding the glass: the wallpaper) is drawn into
 * a bitmap the whole page over, and frosted once: the page's frost, so a
 * holder that moves (Home's wall slides aside for the menu) never needs it
 * made again, shared by every glass of the same kind on the page (Home's three
 * tiles share one; frosting each tile on its own, where it happened to be
 * when it was last drawn, left tiles mid-glide or scrolled off the page with
 * a stale piece, or a black frame round nothing). Each time a glass is drawn
 * somewhere new on the page it cuts its own piece from the frost there and
 * bends its rim, which is quick, so glass on a moving wall shows what's behind
 * it on every frame of the glide. A wall that scrolls doesn't redraw its
 * tiles, so the glass checks its place before each frame and redraws itself
 * when it has moved. The frost is made again only when what's behind changes
 * ([refresh]).
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    /** The corners' radius: the app tiles' by default; the home screen's square tiles set 0. */
    var cornerRadius = resources.getDimension(R.dimen.card_radius)
        set(value) {
            field = value
            invalidate()
        }

    /** How far it frosts, in px of the 1440p page: Mango Liquid Glass's 24 by default. */
    var blur1440 = BLUR_1440

    /** A shade over the tint, for glass that text sits on over bright art (transparent by default). */
    var shade = 0
        set(value) {
            field = value
            shadePaint.color = value
            invalidate()
        }

    /** The frost's pixels per page pixel: 1, or less for a heavy frost (it's smooth anyway). */
    var scale = 1f

    /**
     * Whether what's behind changes (a browse page's art, Home's wallpaper). After [refresh], a live
     * glass's page frosts it again off the main thread and the glass fades over to it, keeping the
     * old frost meanwhile; otherwise the frost is made again in place.
     */
    var live = false

    /** This glass's piece of its page's frost, rim bent; where it was cut; and from which frost. */
    private var backdrop: Bitmap? = null
    private var backdropAt: Rect? = null
    private var cutFrom: Frost? = null

    /** The piece it's fading over from, after the page's frost was made again. */
    private var previous: Bitmap? = null
    private var fade = 1f
    private var fader: ValueAnimator? = null

    /** Where it was last drawn on its page. */
    private var placed: Rect? = null

    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(filterMatrix())
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.glass_tint) }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.glass_fallback) }
    private val bounds = RectF()

    /** Before each frame: moved by a scrolling or gliding ancestor, without being redrawn? Redraw. */
    private val moved = ViewTreeObserver.OnPreDrawListener {
        val page = page()
        if (page != null && placeOn(page) != placed) invalidate()
        true
    }

    init {
        setWillNotDraw(false)
    }

    /** What's behind has changed: frost it again. */
    fun refresh() {
        val page = page() ?: return invalidate()
        frostsOf(page).stale += kind(page)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        fader?.cancel()
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        val page = page()
        val at = page?.let { placeOn(it) }
        placed = at
        val frost = if (page != null && at != null) frostFor(page) else null
        if (frost == null) {
            backdrop = null
            cutFrom = null
        } else if (frost !== cutFrom || at != backdropAt) {
            val piece = cut(frost, at!!)
            if (cutFrom != null && frost !== cutFrom && backdrop != null) fadeFrom(backdrop!!)
            backdrop = piece
            backdropAt = at
            cutFrom = frost
        }
        val radius = cornerRadius
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val image = backdrop
        if (image != null) {
            val old = previous
            if (old != null && fade < 1f) drawBackdrop(canvas, old, radius, 255)
            drawBackdrop(canvas, image, radius, if (old != null && fade < 1f) (fade * 255).roundToInt() else 255)
            canvas.drawRoundRect(bounds, radius, radius, tintPaint)
            if (shade != 0) canvas.drawRoundRect(bounds, radius, radius, shadePaint)
        } else {
            canvas.drawRoundRect(bounds, radius, radius, fallbackPaint)
        }
        super.draw(canvas)
    }

    private fun drawBackdrop(canvas: Canvas, image: Bitmap, radius: Float, alpha: Int) {
        backdropPaint.shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(width.toFloat() / image.width, height.toFloat() / image.height) })
        }
        backdropPaint.alpha = alpha
        canvas.drawRoundRect(bounds, radius, radius, backdropPaint)
    }

    /** Fades from `old` over to the newly cut piece. */
    private fun fadeFrom(old: Bitmap) {
        previous = old
        fader?.cancel()
        fade = 0f
        fader = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            addUpdateListener {
                fade = it.animatedValue as Float
                if (fade >= 1f) previous = null
                invalidate()
            }
            start()
        }
    }

    /** The page the glass is on: its topmost ancestor below the window's content (or Canvas1440Layout). */
    private fun page(): ViewGroup? {
        var view: View = this
        var page: ViewGroup? = null
        while (true) {
            val parent = view.parent as? ViewGroup ?: break
            if (parent is Canvas1440Layout || parent.id == android.R.id.content) break
            page = parent
            view = parent
        }
        return page
    }

    /** The page's child that holds this glass: what's drawn before it is what's behind. */
    private fun holder(page: ViewGroup): View {
        var holder: View = this
        while (holder.parent !== page) holder = holder.parent as View
        return holder
    }

    private fun kind(page: ViewGroup) = Kind(page.indexOfChild(holder(page)), blur1440, scale)

    /** Where the glass is on `page`, scrolling and all. */
    private fun placeOn(page: ViewGroup): Rect {
        var x = 0f
        var y = 0f
        var view: View = this
        while (view !== page) {
            x += view.left + view.translationX
            y += view.top + view.translationY
            val parent = view.parent as? ViewGroup ?: break
            x -= parent.scrollX
            y -= parent.scrollY
            view = parent
        }
        return Rect(x.roundToInt(), y.roundToInt(), x.roundToInt() + width, y.roundToInt() + height)
    }

    /**
     * The page's frost for glass like this one: made at once the first time; made again after
     * [refresh], in place, or on [worker] for live glass (which keeps the old one meanwhile).
     */
    private fun frostFor(page: ViewGroup): Frost? {
        val frosts = frostsOf(page)
        frosts.glasses += this
        val kind = kind(page)
        val current = frosts.made[kind]
        if (current == null || (kind in frosts.stale && !live) || current.over != over(page)) {
            frosts.stale -= kind
            val made = snapshot(page, kind)?.let { frost(it, kind) } ?: return current
            frosts.made[kind] = made
            return made
        }
        if (kind in frosts.stale && kind !in frosts.making) {
            frosts.stale -= kind
            val shot = snapshot(page, kind) ?: return current
            frosts.making += kind
            worker.execute {
                val made = frost(shot, kind)
                main.post {
                    frosts.making -= kind
                    frosts.made[kind] = made
                    frosts.glasses.forEach { it.invalidate() }
                }
            }
        }
        return current
    }

    /**
     * What the frost covers: the whole page, so it stays right however the glass's holder moves
     * (a frost of the holder's place alone was made again on every frame of the wall's slide).
     */
    private fun over(page: ViewGroup) = Rect(0, 0, page.width, page.height)

    /** What's behind the glass on `page`, over its holder's place, sharp: ARGB pixels at the frost's scale. */
    private fun snapshot(page: ViewGroup, kind: Kind): Snapshot? {
        val over = over(page)
        if (over.isEmpty) return null
        val w = (over.width() * kind.scale).roundToInt().coerceAtLeast(1)
        val h = (over.height() * kind.scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(w.toFloat() / over.width(), h.toFloat() / over.height())
        canvas.translate(-over.left.toFloat(), -over.top.toFloat())
        page.background?.draw(canvas)
        // The page's views up to (not including) the one that holds the glass.
        for (i in 0 until kind.holder) {
            val child = page.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            canvas.save()
            canvas.translate(child.left + child.translationX, child.top + child.translationY)
            if (child.alpha < 1f) canvas.saveLayerAlpha(null, (child.alpha * 255).roundToInt())
            child.draw(canvas)
            canvas.restore()
        }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return Snapshot(over, w, h, pixels)
    }

    /** Frosts the page's snapshot, the slow part, done once for every glass on the page. */
    private fun frost(shot: Snapshot, kind: Kind): Frost {
        gaussianBlur(shot.pixels, shot.w, shot.h, kind.blur * PX_1440_IN_DP * density * kind.scale)
        frostsMade++
        return Frost(shot.over, shot.w, shot.h, kind.scale, shot.pixels)
    }

    /**
     * This glass's piece of `frost` where it is now (`at`), its rim bent. Frosted first, the bend
     * curves light and colour at the edges like a lens; bent first (the web version's order), a sharp
     * scene behind showed a shifted copy of itself, colours split, along every edge. Past the frost's
     * edges it carries on as its edge, so a tile half scrolled away is never cut from nothing.
     */
    private fun cut(frost: Frost, at: Rect): Bitmap {
        val w = (width * frost.scale).roundToInt().coerceAtLeast(1)
        val h = (height * frost.scale).roundToInt().coerceAtLeast(1)
        val left = floor((at.left - frost.over.left) * frost.scale).toInt()
        val top = floor((at.top - frost.over.top) * frost.scale).toInt()
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = (top + y).coerceIn(0, frost.h - 1) * frost.w
            for (x in 0 until w) pixels[y * w + x] = frost.pixels[row + (left + x).coerceIn(0, frost.w - 1)]
        }
        refract(pixels, w, h, density * frost.scale * PX_1440_IN_DP)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Which glass shares a frost: the same holder on the page, frosted as far, at the same scale. */
    private data class Kind(val holder: Int, val blur: Float, val scale: Float)

    private class Snapshot(val over: Rect, val w: Int, val h: Int, val pixels: IntArray)

    /** A page's frost: what's behind its glass over `over` (page px), frosted, `w` x `h` pixels at `scale`. */
    private class Frost(val over: Rect, val w: Int, val h: Int, val scale: Float, val pixels: IntArray)

    /** A page's frosts, one per kind of glass on it, and the glass drawn from them. */
    private class PageFrosts {
        val made = HashMap<Kind, Frost>()
        val stale = HashSet<Kind>()
        val making = HashSet<Kind>()
        val glasses: MutableSet<LiquidGlassLayout> = Collections.newSetFromMap(WeakHashMap())
    }

    companion object {
        /** One px of the 2560 × 1440 page, in dp of the 960 × 540 dp window. */
        const val PX_1440_IN_DP = 960f / 2560f

        /**
         * Mango Liquid Glass's frost: 24 px on the 2560 × 1440 page. The web's 4 px left the
         * TV's illustrated wallpapers sharp enough to read through the glass, and the bent rim
         * then showed a shifted copy of the scene beside the real one; at 24 what's behind
         * reads as colour and light.
         */
        const val BLUR_1440 = 24f
        const val BLUR_DP = BLUR_1440 * PX_1440_IN_DP
        const val SATURATE = 1.0f
        const val BRIGHTNESS = 0.70f

        // #lg-refract, in px of the 1440p page: a white box blurred by 11, its slope taken
        // across 2 px each way and amplified 3.3 times, moves red, green and blue by 18, 21, 24.
        const val REFRACT_BLUR = 11f
        const val REFRACT_OFFSET = 2f
        const val REFRACT_GAIN = 3.3f
        val REFRACT_SCALES = floatArrayOf(18f, 21f, 24f)

        /** How long a live glass takes to fade over to a new frost. */
        const val FADE_MS = 180L

        /** Where live glass's pages frost again: one thread, so the newest lands last. Tests run it in place. */
        internal var worker: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        private val main = Handler(Looper.getMainLooper())

        /** Frosts made so far (they're the slow part): tests check a moving glass makes none. */
        @Volatile internal var frostsMade = 0

        /** Each page's frosts, for as long as the page is about. */
        private val pages = WeakHashMap<View, PageFrosts>()

        private fun frostsOf(page: View) = pages.getOrPut(page) { PageFrosts() }

        /** CSS saturate() then brightness(), as one colour matrix. */
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

        /**
         * The #lg-refract filter on `pixels` (ARGB, w x h), where one px of the 1440p
         * page is `px` of them. The filter floods the glass white, blurs it, and takes the
         * blur's slope across 2 px each way (times 3.3, around 0.5) as a displacement map:
         * 0.5 inside, rising to about 1 at the left and top edges and falling to about 0 at
         * the right and bottom ones. Each colour is then drawn from (x, y) + scale * (map - 0.5),
         * red with scale 18, green 21, blue 24: near the rim the backdrop comes from further in,
         * so it bends, and splits into colours. Its opacity comes along with green's, so where
         * there's nothing behind, the rim shows nothing (not black).
         */
        internal fun refract(pixels: IntArray, w: Int, h: Int, px: Float) {
            if (w < 2 || h < 2) return
            val sigma = REFRACT_BLUR * px
            val offset = REFRACT_OFFSET * px
            // The blurred white box is the same profile along each axis, zero outside the glass.
            fun profile(t: Float, n: Int) = if (t < 0f || t > n) 0f else phi(t / sigma) - phi((t - n) / sigma)
            fun slope(i: Int, n: Int) = profile(i + 0.5f + offset, n) - profile(i + 0.5f - offset, n)
            val boxX = FloatArray(w) { profile(it + 0.5f, w) }
            val boxY = FloatArray(h) { profile(it + 0.5f, h) }
            val slopeX = FloatArray(w) { slope(it, w) }
            val slopeY = FloatArray(h) { slope(it, h) }
            val scales = FloatArray(3) { REFRACT_SCALES[it] * px }
            val source = pixels.copyOf()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val mapX = (REFRACT_GAIN * boxY[y] * slopeX[x] + 0.5f).coerceIn(0f, 1f) - 0.5f
                    val mapY = (REFRACT_GAIN * boxX[x] * slopeY[y] + 0.5f).coerceIn(0f, 1f) - 0.5f
                    if (abs(mapX) < 1e-3f && abs(mapY) < 1e-3f) continue
                    var argb = sample(source, w, h, x + scales[1] * mapX, y + scales[1] * mapY, 24) shl 24
                    for (c in 0..2) {
                        val shift = 16 - 8 * c
                        val v = sample(source, w, h, x + scales[c] * mapX, y + scales[c] * mapY, shift)
                        argb = argb or (v shl shift)
                    }
                    pixels[y * w + x] = argb
                }
            }
        }

        /** One channel of `src` at (x, y) in pixel units, bilinear, edges clamped. */
        private fun sample(src: IntArray, w: Int, h: Int, x: Float, y: Float, shift: Int): Int {
            val fx = x.coerceIn(0f, w - 1f); val fy = y.coerceIn(0f, h - 1f)
            val x0 = fx.toInt().coerceAtMost(w - 2); val y0 = fy.toInt().coerceAtMost(h - 2)
            val tx = fx - x0; val ty = fy - y0
            fun at(xx: Int, yy: Int) = (src[yy * w + xx] ushr shift) and 0xFF
            val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
            val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
            return (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
        }

        /** The standard normal distribution's CDF (Abramowitz and Stegun 7.1.26, error under 1.5e-7). */
        internal fun phi(z: Float): Float {
            val x = abs(z) / sqrt(2f)
            val t = 1f / (1f + 0.3275911f * x)
            val erf = 1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t +
                0.254829592f) * t * exp(-x * x)
            return 0.5f * (1f + if (z < 0) -erf else erf)
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
