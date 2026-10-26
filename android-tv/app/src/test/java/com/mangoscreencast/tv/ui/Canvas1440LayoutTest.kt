package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import com.mangoscreencast.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The home screen and Settings drawn at 2560 × 1440 (Canvas1440Layout): the
 * page is laid out as before, on the TV's 960 × 540 dp window (1920 × 1080
 * px), and drawn 4/3 larger into a 2560 × 1440 surface; the window leaves it
 * out while the surface shows it, and draws it again when the surface goes or
 * can't be drawn into.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class Canvas1440LayoutTest {
    @Test
    fun theSurfaceIs1440pWhateverTheWindow() {
        assertEquals(Size(2560, 1440), Canvas1440Layout.bufferSize(1920, 1080))
        assertEquals(Size(2560, 1440), Canvas1440Layout.bufferSize(3840, 2160))
        assertEquals(Size(2560, 1440), Canvas1440Layout.bufferSize(1280, 720))
        // Not 16:9: 2560 wide, keeping the window's shape.
        assertEquals(Size(2560, 1600), Canvas1440Layout.bufferSize(1920, 1200))
        assertEquals(Size(2560, 1440), Canvas1440Layout.bufferSize(0, 0))
    }

    @Test
    fun thePageIsLaidOutAsBeforeAndAsksFor1440p() {
        val (activity, frame) = open(R.layout.activity_home)
        assertEquals(1920, frame.width)
        assertEquals(1080, frame.height)
        val page = frame.page!!
        assertEquals(1920, page.width)
        assertEquals(1080, page.height)
        // The wall where the layout puts it: 402.3 dp in, 470 dp wide (xhdpi: × 2).
        val wall = activity.findViewById<View>(R.id.tile_wall)
        assertEquals(805, wall.left)
        assertEquals(805 + 940, wall.right)
        assertEquals(Size(2560, 1440), frame.requestedSize)
        assertNotNull(frame.findSurfaceView())
    }

    @Test
    fun thePageGoesToTheSurfaceAt1440pAndTheWindowLeavesItOut() {
        val (_, frame) = open(R.layout.activity_home)
        val page = frame.page!!
        val reference = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { page.draw(Canvas(it)) }
        val surfaceFrame = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
        frame.lockCanvas = { Canvas(surfaceFrame) }
        frame.postCanvas = { _, _ -> }
        frame.onSurface(fakeSurface(), Size(2560, 1440))
        assertTrue(frame.showing)

        // The surface holds the page 4/3 larger: each of its points is the page's point at 3/4.
        val row = page.findViewById<View>(R.id.tile_wall)
        val y = row.top + row.height / 2
        for ((x, wy) in listOf(30 to 30, 60 to y, row.left + 40 to y, 900 to 400)) {
            assertClose("at $x, $wy", reference.getPixel(x, wy), surfaceFrame.getPixel(x * 4 / 3, wy * 4 / 3))
        }
        // The wall's left edge, 805 px in on the window, is 1073 px in on the surface.
        assertClose("left of the apps", reference.getPixel(row.left - 10, y), surfaceFrame.getPixel((row.left - 10) * 4 / 3, y * 4 / 3))
        assertClose("in the first app", reference.getPixel(row.left + 10, y), surfaceFrame.getPixel((row.left + 10) * 4 / 3, y * 4 / 3))

        // The window draws only the surface's place, not the page.
        assertEquals(Color.MAGENTA, drawWindow(frame).getPixel(60, y))
    }

    @Test
    fun withoutTheSurfaceThePageDrawsInTheWindowAgain() {
        val (_, frame) = open(R.layout.activity_home)
        val reference = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { frame.page!!.draw(Canvas(it)) }
        frame.lockCanvas = { Canvas(Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)) }
        frame.postCanvas = { _, _ -> }
        frame.onSurface(fakeSurface(), Size(2560, 1440))
        assertEquals(Color.MAGENTA, drawWindow(frame).getPixel(60, 400))
        frame.onSurfaceGone()
        assertFalse(frame.showing)
        assertEquals("the page is back in the window", reference.getPixel(60, 400), drawWindow(frame).getPixel(60, 400))
    }

    @Test
    fun aSurfaceThatCantBeDrawnIntoLeavesThePageInTheWindow() {
        val (_, frame) = open(R.layout.activity_settings)
        val before = drawWindow(frame)
        frame.lockCanvas = { throw IllegalStateException("no hardware canvas") }
        frame.onSurface(fakeSurface(), Size(2560, 1440))
        ShadowLooper.idleMainLooper()
        assertFalse(frame.showing)
        assertEquals(View.GONE, frame.findSurfaceView()!!.visibility)
        assertTrue("still drawn in the window", drawWindow(frame).sameAs(before))
    }

    @Test
    fun settingsIsDrawnTheSameWay() {
        val (activity, frame) = open(R.layout.activity_settings)
        assertTrue(frame.page is android.widget.ScrollView)
        assertNotNull(activity.findViewById<View>(R.id.settings_list))
        assertEquals(1920, frame.page!!.width)
        assertEquals(Size(2560, 1440), frame.requestedSize)
    }

    private fun assertClose(what: String, expected: Int, actual: Int) {
        val near = listOf(Color::red, Color::green, Color::blue).all { kotlin.math.abs(it(expected) - it(actual)) <= 12 }
        assertTrue("$what: expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", near)
    }

    private fun open(layout: Int): Pair<Activity, Canvas1440Layout> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Mango)
        val frame = Canvas1440Layout.setContentView(activity, layout)
        frame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, 1920, 1080)
        return activity to frame
    }

    private fun fakeSurface() = Surface(SurfaceTexture(0))

    /** What the window draws of the frame, as a 1920 × 1080 picture. */
    private fun drawWindow(frame: Canvas1440Layout): Bitmap {
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        frame.draw(Canvas(bitmap))
        return bitmap
    }

    private fun Canvas1440Layout.findSurfaceView(): SurfaceView? =
        (0 until childCount).map(::getChildAt).filterIsInstance<SurfaceView>().firstOrNull()
}
