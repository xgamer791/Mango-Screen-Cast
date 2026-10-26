package com.mangoscreencast.tv.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mango Liquid Glass, as Chromium draws it on the web and as Home's strip of
 * steps had it: what lies behind the glass, bent at the edges by the
 * #lg-refract filter (red, green and blue by different amounts), then blurred
 * by 4 px and darkened to 70% (saturation left at 100%), under the tint
 * (R.color.glass_tint: rgb(9 9 12 / 0.22) in night mode, none in light mode),
 * with no rim, sheen, depth or shadow. Sizes are the web version's px on the
 * 2560 x 1440 page. [frost] does the bending and the blur; the darkening and
 * the tint are drawn over it (LiquidGlassPanel).
 */
internal object LiquidGlass {
    /** One px of the 2560 × 1440 page, in dp of the 960 × 540 dp window. */
    const val PX_1440_IN_DP = 960f / 2560f

    /** 4 px on the 2560 × 1440 page. */
    const val BLUR_DP = 4f * PX_1440_IN_DP
    const val SATURATE = 1.0f
    const val BRIGHTNESS = 0.70f

    // #lg-refract, in px of the 1440p page: a white box blurred by 11, its slope taken
    // across 2 px each way and amplified 3.3 times, moves red, green and blue by 18, 21, 24.
    const val REFRACT_BLUR = 11f
    const val REFRACT_OFFSET = 2f
    const val REFRACT_GAIN = 3.3f
    val REFRACT_SCALES = floatArrayOf(18f, 21f, 24f)

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
     * The #lg-refract filter on `pixels` (opaque ARGB, w x h), where one px of the 1440p
     * page is `px` of them. The filter floods the glass white, blurs it, and takes the
     * blur's slope across 2 px each way (times 3.3, around 0.5) as a displacement map:
     * 0.5 inside, rising to about 1 at the left and top edges and falling to about 0 at
     * the right and bottom ones. Each colour is then drawn from (x, y) + scale * (map - 0.5),
     * red with scale 18, green 21, blue 24: near the rim the backdrop comes from further in,
     * so it bends, and splits into colours.
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
                var argb = 0xFF shl 24
                for (c in 0..2) {
                    val shift = 16 - 8 * c
                    val v = sample(source, w, h, x + scales[c] * mapX, y + scales[c] * mapY, shift)
                    argb = argb or (v shl shift)
                }
                pixels[y * w + x] = argb
            }
        }
    }

    /** One colour channel of `src` at (x, y) in pixel units, bilinear, edges clamped. */
    private fun sample(src: IntArray, w: Int, h: Int, x: Float, y: Float, shift: Int): Int {
        val fx = x.coerceIn(0f, w - 1f); val fy = y.coerceIn(0f, h - 1f)
        val x0 = fx.toInt().coerceAtMost(w - 2); val y0 = fy.toInt().coerceAtMost(h - 2)
        val tx = fx - x0; val ty = fy - y0
        fun at(xx: Int, yy: Int) = (src[yy * w + xx] shr shift) and 0xFF
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

    /** `behind` (what's behind the glass, at the glass's size in pixels, `density` per dp) bent at the edges and blurred. */
    fun frost(behind: Bitmap, density: Float): Bitmap {
        val w = behind.width
        val h = behind.height
        val pixels = IntArray(w * h)
        behind.getPixels(pixels, 0, w, 0, 0, w, h)
        // The ::before layer bends the sharp backdrop; the ::after layer frosts what it shows.
        refract(pixels, w, h, density * PX_1440_IN_DP)
        gaussianBlur(pixels, w, h, BLUR_DP * density)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
