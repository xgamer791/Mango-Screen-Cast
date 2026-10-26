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
        val root = FrameLayout(behind.context)
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
    fun theCornersAreRoundedLikeTheAppTiles() {
        val colour = Color.rgb(200, 200, 200)
        val context = activity
        val radius = context.resources.getDimension(R.dimen.card_radius)
        assertEquals(8 * context.resources.displayMetrics.density, radius)
        val screen = render(flat(context, colour))
        val glass = expected(colour, context.getColor(R.color.glass_tint))
        // Just inside the top-left corner's curve: the backdrop.
        val corner = screen.getPixel(101, 101)
        assertTrue("corner is glass: ${Integer.toHexString(corner)}", Color.red(corner) > glass[0] + 30)
        // A radius in, it's glass.
        assertColour(glass, screen.getPixel(100 + radius.toInt(), 100 + radius.toInt()))
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

    /**
     * A detailed scene behind reads as colour and light, not things: black and white stripes 8 px
     * apart come through the glass with under a tenth of their contrast, and its rim shows no copy
     * of them. (The web's 4 px frost let a scene through sharp, and the bent rim doubled it.)
     */
    @Test
    fun aDetailedSceneBehindComesThroughAsColourNotShapes() {
        val behind = object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.BLACK)
                val white = android.graphics.Paint().apply { color = Color.WHITE }
                var x = 0f
                while (x < width) { canvas.drawRect(x, 0f, x + 8f, height.toFloat(), white); x += 16f }
            }
        }.apply { setWillNotDraw(false) }
        val screen = render(behind)
        // Outside the glass the stripes are full contrast; inside, across its middle and along its rim.
        fun swing(xs: IntRange, y: Int) = xs.map { Color.red(screen.getPixel(it, y)) }.let { it.max() - it.min() }
        assertEquals(255, swing(10..60, 50))
        for ((xs, y) in listOf(300..400 to 200, 101..140 to 200, 300..400 to 102, 660..699 to 200)) {
            assertTrue("stripes show through at x $xs, y $y: swing ${swing(xs, y)}", swing(xs, y) < 26)
        }
    }

    /** White left of x = 400, black right of it. */
    private fun split(context: android.content.Context) = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            canvas.clipRect(0, 0, 400, 400)
            canvas.drawColor(Color.WHITE)
        }
    }.apply { setWillNotDraw(false) }

    /**
     * Home's tiles move with their wall. The frame a glass is drawn somewhere new, it shows what's
     * behind it there: not the place it was frosted at before, faded over later.
     */
    @Test
    fun aGlassThatMovesShowsWhatIsBehindItsNewPlaceAtOnce() {
        val context = activity
        val root = FrameLayout(context)
        root.addView(split(context), FrameLayout.LayoutParams(800, 400))
        val wall = FrameLayout(context)
        root.addView(wall, FrameLayout.LayoutParams(800, 400))
        val glass = LiquidGlassLayout(context).apply { live = true }
        wall.addView(glass, FrameLayout.LayoutParams(200, 200).apply { leftMargin = 100; topMargin = 100 })
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 400)
        val screen = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(screen))
        val tint = context.getColor(R.color.glass_tint)
        assertColour(expected(Color.WHITE, tint), screen.getPixel(200, 200))
        // The wall moves the glass 400 px right, over the black.
        wall.scrollTo(-400, 0)
        screen.eraseColor(0)
        root.draw(Canvas(screen))
        assertColour(expected(Color.BLACK, tint), screen.getPixel(600, 200))
    }

    /**
     * Home's wall slides aside as the menu opens. Its glass shows what's behind at every step, cut
     * from the frost made once, not a frost made again each frame (the slow part: a stutter).
     */
    @Test
    fun aSlidingWallsGlassMakesNoNewFrost() {
        val context = activity
        val root = FrameLayout(context)
        root.addView(split(context), FrameLayout.LayoutParams(800, 400))
        val wall = FrameLayout(context)
        root.addView(wall, FrameLayout.LayoutParams(400, 400))
        val glass = LiquidGlassLayout(context).apply { live = true }
        wall.addView(glass, FrameLayout.LayoutParams(200, 200).apply { leftMargin = 100; topMargin = 100 })
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 400)
        val screen = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(screen))
        val made = LiquidGlassLayout.frostsMade
        for (step in 1..8) {
            wall.translationX = step * 50f
            screen.eraseColor(0)
            root.draw(Canvas(screen))
        }
        assertEquals("frosts made while the wall slid", made, LiquidGlassLayout.frostsMade)
        // Slid 400 px right, the glass is over the black.
        assertColour(expected(Color.BLACK, context.getColor(R.color.glass_tint)), screen.getPixel(600, 200))
    }

    /** A wall that scrolls doesn't redraw its tiles; the glass sees it has moved, and redraws. */
    @Test
    fun aGlassRedrawsWhenItsWallScrolls() {
        val context = activity
        val root = FrameLayout(context)
        root.addView(split(context), FrameLayout.LayoutParams(800, 400))
        val wall = FrameLayout(context)
        root.addView(wall, FrameLayout.LayoutParams(800, 400))
        val glass = LiquidGlassLayout(context)
        wall.addView(glass, FrameLayout.LayoutParams(200, 200).apply { leftMargin = 100; topMargin = 100 })
        context.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 400)
        root.draw(Canvas(Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)))
        assertTrue(!glass.isDirty)
        wall.scrollTo(0, 120)
        glass.viewTreeObserver.dispatchOnPreDraw()
        assertTrue("the moved glass wasn't asked to redraw", glass.isDirty)
    }

    /** Where there's nothing behind, the bent rim shows nothing too: not black (the frame round a scrolled tile). */
    @Test
    fun nothingBehindTheRimStaysClear() {
        val pixels = IntArray(200 * 100)
        LiquidGlassLayout.refract(pixels, 200, 100, 1f)
        assertTrue("the rim turned opaque", pixels.all { Color.alpha(it) == 0 })
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
        val pixels = ramp(); LiquidGlassLayout.refract(pixels, 200, 100, 1f)
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
        LiquidGlassLayout.refract(pixels, w, h, 1f)
        assertEquals(listOf(18, 21, 24), channels(pixels[0 * w + 100]))
    }

    @Test
    fun inTheMiddleNothingMovesAndAFlatBackdropStaysFlat() {
        val pixels = ramp(); val before = pixels.copyOf()
        LiquidGlassLayout.refract(pixels, 200, 100, 1f)
        // 11 px blur: the bend fades within a few times that of the rim.
        for (x in 60..140) assertEquals("x $x", before[50 * 200 + x], pixels[50 * 200 + x])
        val flat = IntArray(200 * 100) { Color.rgb(150, 110, 70) }
        LiquidGlassLayout.refract(flat, 200, 100, 1f)
        assertTrue(flat.all { it == Color.rgb(150, 110, 70) })
    }

    @Test
    fun theBendFadesInward() {
        val pixels = ramp(); LiquidGlassLayout.refract(pixels, 200, 100, 1f)
        val shifts = listOf(0, 5, 10, 20, 30).map { x -> (Color.blue(pixels[50 * 200 + x]) - 50) / 2.0 - x }
        assertTrue("blue's shift should shrink going in: $shifts", shifts.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(12.0, shifts.first(), 0.01)
        assertTrue("still bent at 30 px: $shifts", shifts.last() < 1.0)
    }

    @Test
    fun phiIsTheNormalCdf() {
        assertEquals(0.5f, LiquidGlassLayout.phi(0f), 1e-6f)
        assertEquals(0.841345f, LiquidGlassLayout.phi(1f), 1e-5f)
        assertEquals(0.022750f, LiquidGlassLayout.phi(-2f), 1e-5f)
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
