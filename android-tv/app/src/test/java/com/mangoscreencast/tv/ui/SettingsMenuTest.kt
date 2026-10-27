package com.mangoscreencast.tv.ui

import android.app.Application
import android.os.Looper
import android.view.View
import com.mangoscreencast.tv.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * The gear's panel, a copy of the Hisense's (Google TV's dashboard): 272 dp wide,
 * 24 dp from the right edge and 16 dp from the top and bottom; in 300 ms on
 * (0.12, 1, 0.4, 1) it comes in from beyond the right edge, fading in, and dims
 * what's behind to 60%; Back or the gear takes it out its own width to the right
 * in 500 ms on (0.4, 1, 0.12, 1), fading out, the dim going evenly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsMenuTest {
    private val controller by lazy { Robolectric.buildActivity(SettingsMenuActivity::class.java) }
    private val menu get() = controller.get()
    private val panel: View get() = menu.findViewById(R.id.menu_panel)
    private val scrim: View get() = menu.findViewById(R.id.menu_scrim)

    // Animation frames come as the test moves the clock on, one every 16 ms, as on the TV
    // (otherwise showing the activity runs every frame of the slide at once).
    @org.junit.Before
    fun framesWithTheClock() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }

    @After
    fun nothingInFront() = FrontSettings.clear()

    /** The clock moves on `ms`, a frame (16 ms) at a time: the test runner draws one frame per step. */
    private fun pass(ms: Long) = frames(ms)

    private fun open(): SettingsMenuActivity {
        controller.setup()
        pass(SettingsMenuActivity.OPEN_MS + 100)
        return menu
    }

    @Test
    fun its272dpWide24dpFromTheRightAnd16dpFromTopAndBottom() {
        open()
        val screen = menu.resources.displayMetrics
        assertEquals(1920, screen.widthPixels)
        assertEquals(544, panel.width)
        assertEquals("48 px in from the right edge", screen.widthPixels - 48, panel.right)
        assertEquals(32, panel.top)
        assertEquals(screen.heightPixels - 32, panel.bottom)
        assertEquals(0f, panel.translationX)
        assertEquals(1f, panel.alpha)
    }

    @Test
    fun itComesInFromBeyondTheRightEdgeFadingInAndDimsWhatsBehind() {
        controller.setup()
        assertEquals("its width and margin out", 592f, panel.translationX, 0.5f)
        assertEquals(0f, panel.alpha)
        assertEquals(0f, scrim.alpha)
        pass(SettingsMenuActivity.OPEN_MS / 3)
        assertTrue("on its way: ${panel.translationX}", panel.translationX > 0f && panel.translationX < 592f)
        assertTrue(panel.alpha > 0f && panel.alpha < 1f)
        pass(SettingsMenuActivity.OPEN_MS)
        assertEquals("in place", 0f, panel.translationX)
        assertEquals(1f, panel.alpha)
        assertEquals("what's behind at 60%", 0.6f, scrim.alpha, 0.001f)
    }

    @Test
    fun itOpensIn300msOnTheDashboardsCurve() {
        assertEquals(300L, SettingsMenuActivity.OPEN_MS)
        val curve = SettingsMenuActivity.OPEN_CURVE
        val reference = android.view.animation.PathInterpolator(0.12f, 1f, 0.4f, 1f)
        for (i in 0..20) assertEquals(reference.getInterpolation(i / 20f), curve.getInterpolation(i / 20f), 0.001f)
        // Fast out, settling in: most of the way in the first third.
        assertTrue(curve.getInterpolation(1f / 3) > 0.75f)
    }

    @Test
    fun backTakesItOutItsWidthToTheRightIn500msThenItCloses() {
        open()
        menu.onBackPressed()
        assertFalse("not at once", menu.isFinishing)
        pass(SettingsMenuActivity.CLOSE_MS / 2)
        assertFalse("still going", menu.isFinishing)
        assertTrue(panel.translationX > 0f && panel.translationX < 544f)
        assertTrue(panel.alpha < 1f)
        assertEquals("the dim goes evenly", 0.3f, scrim.alpha, 0.05f)
        pass(SettingsMenuActivity.CLOSE_MS / 2 + 100)
        assertEquals("its own width out", 544f, panel.translationX, 0.5f)
        assertEquals(0f, panel.alpha)
        assertEquals(0f, scrim.alpha)
        assertTrue("then it closes", menu.isFinishing)
    }

    @Test
    fun theGearClosesItToo() {
        open()
        menu.gear()
        pass(SettingsMenuActivity.CLOSE_MS + 100)
        assertTrue(menu.isFinishing)
    }

    @Test
    fun theGearWhileItsGoingBringsItBack() {
        open()
        menu.gear()
        pass(SettingsMenuActivity.CLOSE_MS / 3)
        menu.gear()
        pass(SettingsMenuActivity.OPEN_MS + 100)
        assertFalse("it stays", menu.isFinishing)
        assertEquals(0f, panel.translationX)
        assertEquals(1f, panel.alpha)
        assertEquals(0.6f, scrim.alpha, 0.001f)
    }

    @Test
    fun itClosesOnTheDashboardWindowsCurve() {
        assertEquals(500L, SettingsMenuActivity.CLOSE_MS)
        val reference = android.view.animation.PathInterpolator(0.4f, 1f, 0.12f, 1f)
        for (i in 0..20) {
            assertEquals(reference.getInterpolation(i / 20f), SettingsMenuActivity.CLOSE_CURVE.getInterpolation(i / 20f), 0.001f)
        }
    }

    @Test
    fun allSettingsHasTheFocusAsItOpens() {
        open()
        assertTrue("the panel's first focus", menu.findViewById<View>(R.id.panel_all_settings).isFocusedByDefault)
    }

    private fun tiles(): List<View> {
        val pages = menu.findViewById<android.view.ViewGroup>(R.id.panel_pages)
        val first = pages.getChildAt(0) as android.view.ViewGroup
        return (0 until first.childCount).map { first.getChildAt(it) }
    }

    @Test
    fun theHisensePanelsTilesDownTheColumnsTwoColumnsInView() {
        open()
        val names = tiles().map { it.contentDescription.toString() }
        assertEquals(
            listOf(
                "Screensaver", "Display & Picture", "Wi-Fi", "Bluetooth",
                "Inputs", "Sound", "Google Home", "Sleep timer",
                "Accessibility",
            ),
            names,
        )
        val all = tiles()
        assertEquals(242, all[0].width)
        assertEquals(108, all[0].height)
        assertEquals("two across, 12 px apart", all[0].right + 12, all[4].left)
        assertEquals("rows 12 px apart", all[0].bottom + 12, all[1].top)
        val dots = menu.findViewById<android.view.ViewGroup>(R.id.panel_dots)
        assertEquals("a dot for each place the tiles scroll to", 2, dots.childCount)
        assertEquals(View.VISIBLE, dots.visibility)
    }

    @Test
    fun theThirdColumnGlidesInAsItsTileGetsTheFocusAndBackAtOnce() {
        open()
        val grid = tiles()[0].parent as View
        assertEquals(0f, grid.translationX)
        tiles()[8].requestFocus() // Accessibility, in the third column
        pass(SettingsMenuActivity.SCROLL_MS + 50)
        assertEquals("a column (254 px) to the left", -254f, grid.translationX, 0.5f)
        tiles()[0].requestFocus() // Screensaver, in the first
        assertEquals("back at once", 0f, grid.translationX, 0.5f)
    }

    @Test
    fun theSleepTimerTileOpensItsPageOfTimesAndATimeStartsIt() {
        open()
        SleepTimer.now = { 1_000_000L }
        try {
            tiles()[7].performClick() // Sleep timer
            val page = menu.findViewById<View>(R.id.panel_sleep_page)
            assertEquals(View.VISIBLE, page.visibility)
            val list = menu.findViewById<android.view.ViewGroup>(R.id.panel_sleep_choices)
            assertEquals(
                listOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "1 hour 30 minutes", "2 hours", "3 hours"),
                (0 until list.childCount).map { list.getChildAt(it).contentDescription.toString() },
            )
            list.getChildAt(1).performClick()
            assertEquals(View.GONE, page.visibility)
            assertEquals(1_000_000L + 30 * 60_000L, SleepTimer.end(menu))
            tiles()[7].performClick()
            assertEquals("while it runs, Turn off timer first", "Turn off timer", list.getChildAt(0).contentDescription)
            list.getChildAt(0).performClick()
            assertEquals(null, SleepTimer.end(menu))
        } finally {
            SleepTimer.now = System::currentTimeMillis
        }
    }

    @Test
    fun backLeavesTheSleepTimerPageNotThePanel() {
        open()
        tiles()[7].performClick()
        menu.onBackPressed()
        assertEquals(View.GONE, menu.findViewById<View>(R.id.panel_sleep_page).visibility)
        pass(SettingsMenuActivity.CLOSE_MS + 100)
        assertFalse(menu.isFinishing)
    }

    @Test
    fun aTileWithTheFocusGrowsTo1025In300ms() {
        open()
        val tile = tiles()[3]
        tile.requestFocus()
        pass(SettingsMenuActivity.FOCUS_IN_MS + 50)
        assertEquals(1.025f, tile.scaleX, 0.001f)
        tiles()[2].requestFocus()
        pass(SettingsMenuActivity.FOCUS_OUT_MS + 50)
        assertEquals(1f, tile.scaleX, 0.001f)
    }

    @Test
    fun aTileOpensTheTvsOwnScreenAndThePanelGoes() {
        open()
        tiles()[1].performClick()
        val started = shadowOf(menu).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_DISPLAY_SETTINGS, started.action)
        assertEquals("Hisense's Picture", "com.vt.customsettings", started.`package`)
        assertTrue(menu.isFinishing)
    }

    @Test
    fun itsInFrontForTheGearWhileOpen() {
        open()
        assertTrue(FrontSettings.current() === menu)
    }
}

/** Moves the clock on `ms`, a frame (16 ms) at a time, as frames come on the TV. */
internal fun frames(ms: Long) {
    var left = ms
    while (left > 0) {
        val step = minOf(16L, left)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
        left -= step
    }
}
