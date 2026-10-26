package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import com.mangoscreencast.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The menu's glass (LiquidGlassPanel, with LiquidGlass's recipe) shows what's
 * behind it as the web's Mango Liquid Glass does, and as Home's strip did:
 * bent at the edges, blurred 4 px, darkened to 70%, under the tint.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class LiquidGlassTest {
    private val activity: Activity get() = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A 600 x 200 px glass at (100, 100) over `behind` (800 x 400), drawn; returns the screen. */
    private fun render(behind: View): Bitmap {
        behind.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        behind.layout(0, 0, 800, 400)
        val screen = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).also { behind.draw(Canvas(it)) }
        // What the panel has behind it: that part of the screen, as the gear's screenshot gives it.
        val glass = LiquidGlassPanel(behind.context).apply { frostNow = true }
        glass.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        glass.layout(0, 0, 600, 200)
        glass.showBehind(Bitmap.createBitmap(screen, 100, 100, 600, 200))
        val canvas = Canvas(screen)
        canvas.translate(100f, 100f)
        glass.draw(canvas)
        return screen
    }

    private fun flat(context: android.content.Context, colour: Int) = View(context).apply { background = ColorDrawable(colour) }

    /** CSS: saturate(100%) brightness(70%) on `c`, then the glass tint over it. */
    private fun expected(c: Int, tint: Int): IntArray {
        val a = Color.alpha(tint) / 255.0
        return intArrayOf(
            (a * Color.red(tint) + (1 - a) * 0.7 * Color.red(c)).roundToInt(),
            (a * Color.green(tint) + (1 - a) * 0.7 * Color.green(c)).roundToInt(),
            (a * Color.blue(tint) + (1 - a) * 0.7 * Color.blue(c)).roundToInt(),
        )
    }

    private fun assertColour(want: IntArray, got: Int, slack: Int = 3) {
        val rgb = intArrayOf(Color.red(got), Color.green(got), Color.blue(got))
        for (i in 0..2) assertTrue("got ${rgb.toList()}, want ${want.toList()}", abs(rgb[i] - want[i]) <= slack)
    }

    @Test
    fun aFlatBackdropIsTintedOverItsDarkenedColour() {
        val colour = Color.rgb(150, 110, 70)
        val context = activity
        assertEquals(0x00000000, context.getColor(R.color.glass_tint)) // rgb(0 0 0 / 0)
        val screen = render(flat(context, colour))
        assertColour(expected(colour, context.getColor(R.color.glass_tint)), screen.getPixel(400, 200))
        // Away from the glass the backdrop is untouched.
        assertEquals(colour, screen.getPixel(30, 30))
    }

    @Test
    @Config(qualifiers = "+night")
    fun inNightModeTheDarkTintIsUsed() {
        val colour = Color.rgb(150, 110, 70)
        val context = activity
        assertEquals(0x3809090C, context.getColor(R.color.glass_tint)) // rgb(9 9 12 / 0.22)
        val screen = render(flat(context, colour))
        assertColour(expected(colour, 0x3809090C), screen.getPixel(400, 200))
    }

    @Test
    fun theCornersAreSquareItsFlushWithTheScreen() {
        val colour = Color.rgb(200, 200, 200)
        val context = activity
        val screen = render(flat(context, colour))
        assertColour(expected(colour, context.getColor(R.color.glass_tint)), screen.getPixel(101, 101))
    }

    @Test
    fun beforeItsGlassIsReadyItsTheFallbackColour() {
        val context = activity
        val glass = LiquidGlassPanel(context)
        glass.measure(View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
        glass.layout(0, 0, 60, 40)
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).also { glass.draw(Canvas(it)) }
        val fallback = context.getColor(R.color.glass_fallback)
        assertColour(intArrayOf(Color.red(fallback), Color.green(fallback), Color.blue(fallback)), bitmap.getPixel(30, 20), 20)
    }

    @Test
    fun nothingIsDrawnOutsideIt() {
        // No shadow: right next to the glass, on every side, the backdrop is untouched.
        val colour = Color.rgb(200, 200, 200)
        val screen = render(flat(activity, colour))
        for ((x, y) in listOf(400 to 98, 400 to 302, 98 to 200, 702 to 200)) {
            assertEquals("at ($x, $y)", colour, screen.getPixel(x, y))
        }
    }

    @Test
    fun aSharpEdgeBehindTheGlassIsBlurredAcrossIt() {
        // White on the left half, black on the right, meeting at x = 400 (glass x 300).
        val behind = object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.BLACK)
                canvas.clipRect(0, 0, 400, 400)
                canvas.drawColor(Color.WHITE)
            }
        }.apply { setWillNotDraw(false) }
        val screen = render(behind)
        val row = (380..420).map { Color.red(screen.getPixel(it, 200)) }
        assertTrue("not darkening: $row", row.first() > row.last() + 40)
        assertTrue("not monotonic: $row", row.zipWithNext().all { (a, b) -> b <= a + 1 })
        val biggestStep = row.zipWithNext().maxOf { (a, b) -> a - b }
        assertTrue("edge still sharp (step $biggestStep): $row", biggestStep < (row.first() - row.last()) / 3)
        val tint = behind.context.getColor(R.color.glass_tint)
        assertEquals(expected(Color.WHITE, tint)[0].toDouble(), Color.red(screen.getPixel(140, 200)).toDouble(), 3.0)
        assertEquals(expected(Color.BLACK, tint)[0].toDouble(), Color.red(screen.getPixel(660, 200)).toDouble(), 3.0)
    }

    /** A 200 x 100 image whose red, green and blue all say where they are: 2 * x + y. */
    private fun ramp(w: Int = 200, h: Int = 100) = IntArray(w * h) { i ->
        val v = (2 * (i % w) + (i / w)).coerceAtMost(255); Color.rgb(v, v, v)
    }

    private fun channels(c: Int) = listOf(Color.red(c), Color.green(c), Color.blue(c))

    @Test
    fun atTheRimEachColourComesFromFurtherInByHalfItsScale() {
        // One px of the page per pixel. At the left edge the map is at its most, 1: red, green and
        // blue come from 18, 21 and 24 times (1 - 0.5) further right, x + 9, 10.5 and 12.
        val pixels = ramp(); LiquidGlass.refract(pixels, 200, 100, 1f)
        val y = 50
        assertEquals(listOf(18 + y, 21 + y, 24 + y), channels(pixels[y * 200 + 0]))
        // At the right edge they come from as far to the left: x = 199 reads 190, 188.5, 187.
        val right = pixels[y * 200 + 199]
        assertEquals(listOf(2 * 190 + y, (2 * 188.5 + y).toInt(), 2 * 187 + y).map { it.coerceAtMost(255) }, channels(right))
    }

    @Test
    fun theTopEdgeBendsDownwardTheSameWay() {
        // Vertical ramp: value = 2 * y. At the top edge, red/green/blue read rows 9, 10.5, 12.
        val w = 200; val h = 100
        val pixels = IntArray(w * h) { i -> val v = 2 * (i / w); Color.rgb(v, v, v) }
        LiquidGlass.refract(pixels, w, h, 1f)
        assertEquals(listOf(18, 21, 24), channels(pixels[0 * w + 100]))
    }

    @Test
    fun inTheMiddleNothingMovesAndAFlatBackdropStaysFlat() {
        val pixels = ramp(); val before = pixels.copyOf()
        LiquidGlass.refract(pixels, 200, 100, 1f)
        // 11 px blur: the bend fades within a few times that of the rim.
        for (x in 60..140) assertEquals("x $x", before[50 * 200 + x], pixels[50 * 200 + x])
        val flat = IntArray(200 * 100) { Color.rgb(150, 110, 70) }
        LiquidGlass.refract(flat, 200, 100, 1f)
        assertTrue(flat.all { it == Color.rgb(150, 110, 70) })
    }

    @Test
    fun theBendFadesInward() {
        val pixels = ramp(); LiquidGlass.refract(pixels, 200, 100, 1f)
        val shifts = listOf(0, 5, 10, 20, 30).map { x -> (Color.blue(pixels[50 * 200 + x]) - 50) / 2.0 - x }
        assertTrue("blue's shift should shrink going in: $shifts", shifts.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(12.0, shifts.first(), 0.01)
        assertTrue("still bent at 30 px: $shifts", shifts.last() < 1.0)
    }

    @Test
    fun phiIsTheNormalCdf() {
        assertEquals(0.5f, LiquidGlass.phi(0f), 1e-6f)
        assertEquals(0.841345f, LiquidGlass.phi(1f), 1e-5f)
        assertEquals(0.022750f, LiquidGlass.phi(-2f), 1e-5f)
    }

    @Test
    fun boxesApproximateTheGaussian() {
        for (sigma in listOf(2f, 3.66f, 7.3f, 14.6f)) {
            val sizes = LiquidGlass.boxSizes(sigma)
            val variance = sizes.sumOf { (it * it - 1) / 12.0 }
            assertEquals("sigma $sigma: $sizes", sigma * sigma.toDouble(), variance, sigma.toDouble())
        }
    }
}
