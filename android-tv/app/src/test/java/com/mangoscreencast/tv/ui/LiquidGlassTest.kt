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

/** The glass shows what's behind it as the web's Mango Liquid Glass does. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class LiquidGlassTest {
    private val activity: Activity get() = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A 600 x 200 px glass at (100, 100) over `behind`, drawn; returns the screen. */
    private fun render(behind: View): Bitmap {
        val root = FrameLayout(behind.context).apply { clipChildren = false }
        root.addView(behind, FrameLayout.LayoutParams(800, 400))
        val glass = LiquidGlassLayout(behind.context)
        root.addView(glass, FrameLayout.LayoutParams(600, 200).apply { leftMargin = 100; topMargin = 100 })
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 400)
        return Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
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
        assertEquals(0x4A000000, context.getColor(R.color.glass_tint))
        val screen = render(flat(context, colour))
        assertColour(expected(colour, context.getColor(R.color.glass_tint)), screen.getPixel(400, 200))
        // Away from the glass and its shadow the backdrop is untouched.
        assertEquals(colour, screen.getPixel(30, 30))
    }

    @Test
    @Config(qualifiers = "+night")
    fun inNightModeTheDarkTintIsUsed() {
        val colour = Color.rgb(150, 110, 70)
        val context = activity
        assertEquals(0x8C09090C.toInt(), context.getColor(R.color.glass_tint))
        val screen = render(flat(context, colour))
        assertColour(expected(colour, 0x8C09090C.toInt()), screen.getPixel(400, 200))
    }

    @Test
    fun theCornersAreRoundedLikeTheAppTiles() {
        val colour = Color.rgb(200, 200, 200)
        val context = activity
        val radius = context.resources.getDimension(R.dimen.card_radius)
        assertEquals(8 * context.resources.displayMetrics.density, radius)
        val screen = render(flat(context, colour))
        val glass = expected(colour, context.getColor(R.color.glass_tint))
        // Just inside the top-left corner's curve: the backdrop (the shadow falls below, not here).
        val corner = screen.getPixel(101, 101)
        assertTrue("corner is glass: ${Integer.toHexString(corner)}", Color.red(corner) > glass[0] + 30)
        // A radius in, it's glass.
        assertColour(glass, screen.getPixel(100 + radius.toInt(), 100 + radius.toInt()))
    }

    @Test
    fun itCastsASoftShadowBelowIt() {
        val colour = Color.rgb(200, 200, 200)
        val screen = render(flat(activity, colour))
        val below = Color.red(screen.getPixel(400, 304)) // 4 px under the glass
        val above = Color.red(screen.getPixel(400, 96))  // 4 px over it
        assertTrue("no shadow below: $below", below < 200)
        assertTrue("shadow should fall below more than above: above $above, below $below", below < above)
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

    @Test
    fun boxesApproximateTheGaussian() {
        for (sigma in listOf(2f, 3.66f, 7.3f, 14.6f)) {
            val sizes = LiquidGlassLayout.boxSizes(sigma)
            val variance = sizes.sumOf { (it * it - 1) / 12.0 }
            assertEquals("sigma $sigma: $sizes", sigma * sigma.toDouble(), variance, sigma.toDouble())
        }
    }
}
