package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
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

/** The glass shows what's behind it as the web's Mango Liquid Glass does. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class LiquidGlassTest {
    /** A 600 x 200 px glass at (100, 100) over `behind`, drawn; returns the screen. */
    private fun render(behind: View): Bitmap {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.addView(behind, FrameLayout.LayoutParams(800, 400))
        val glass = LiquidGlassLayout(activity)
        root.addView(glass, FrameLayout.LayoutParams(600, 200).apply { leftMargin = 100; topMargin = 100 })
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 400)
        return Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    }

    /** CSS: saturate(125%) brightness(70%) on `c`, clamped per step, then rgb(10 10 16 / 0.63) over it. */
    private fun expected(c: Int): IntArray {
        val r = Color.red(c).toDouble(); val g = Color.green(c).toDouble(); val b = Color.blue(c).toDouble()
        val s = 1.25
        fun clamp(x: Double) = x.coerceIn(0.0, 255.0)
        val sr = clamp((0.213 + 0.787 * s) * r + (0.715 - 0.715 * s) * g + (0.072 - 0.072 * s) * b)
        val sg = clamp((0.213 - 0.213 * s) * r + (0.715 + 0.285 * s) * g + (0.072 - 0.072 * s) * b)
        val sb = clamp((0.213 - 0.213 * s) * r + (0.715 - 0.715 * s) * g + (0.072 + 0.928 * s) * b)
        val a = 0xA1 / 255.0
        return doubleArrayOf(10.0, 10.0, 16.0).zip(listOf(sr, sg, sb)) { t, f -> (a * t + (1 - a) * 0.7 * f).roundToInt() }
            .toIntArray()
    }

    @Test
    fun aFlatBackdropIsTintedOverItsFilteredColour() {
        val colour = Color.rgb(150, 110, 70)
        val screen = render(View(Robolectric.buildActivity(Activity::class.java).get()).apply {
            background = ColorDrawable(colour)
        })
        val inside = screen.getPixel(400, 200)
        val want = expected(colour)
        val got = intArrayOf(Color.red(inside), Color.green(inside), Color.blue(inside))
        for (i in 0..2) assertTrue("channel $i: got ${got.toList()}, want ${want.toList()}", abs(got[i] - want[i]) <= 3)
        // Outside the glass the backdrop is untouched.
        assertEquals(colour, screen.getPixel(50, 50))
    }

    @Test
    fun aSharpEdgeBehindTheGlassIsBlurredAcrossIt() {
        // White on the left half, black on the right, meeting at x = 400 (glass x 300).
        val behind = object : View(Robolectric.buildActivity(Activity::class.java).get()) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.BLACK)
                canvas.clipRect(0, 0, 400, 400)
                canvas.drawColor(Color.WHITE)
            }
        }.apply { setWillNotDraw(false) }
        val screen = render(behind)
        val row = (360..440).map { Color.red(screen.getPixel(it, 200)) }
        // Light to dark, gradually: no single step does more than a fraction of the change.
        assertTrue("not darkening: $row", row.first() > row.last() + 40)
        assertTrue("not monotonic: $row", row.zipWithNext().all { (a, b) -> b <= a + 1 })
        val biggestStep = row.zipWithNext().maxOf { (a, b) -> a - b }
        assertTrue("edge still sharp (step $biggestStep): $row", biggestStep < (row.first() - row.last()) / 4)
        // Far from the edge, each side is its own colour filtered and tinted.
        assertEquals(expected(Color.WHITE)[0].toDouble(), Color.red(screen.getPixel(140, 200)).toDouble(), 3.0)
        assertEquals(expected(Color.BLACK)[0].toDouble(), Color.red(screen.getPixel(660, 200)).toDouble(), 3.0)
    }

    @Test
    fun boxesApproximateTheGaussian() {
        // Three boxes whose combined variance matches sigma^2 within a pixel's worth.
        for (sigma in listOf(2f, 3.66f, 7.3f, 14.6f)) {
            val sizes = LiquidGlassLayout.boxSizes(sigma)
            val variance = sizes.sumOf { (it * it - 1) / 12.0 }
            assertEquals("sigma $sigma: $sizes", sigma * sigma.toDouble(), variance, sigma.toDouble())
        }
    }
}
