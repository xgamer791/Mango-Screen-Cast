package com.mangoscreencast.tv.ui

import android.app.Application
import android.os.Looper
import android.view.View
import com.mangoscreencast.tv.R
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
 * The menu's Settings panel: 540 px of a 1920 px wide screen on the right, that
 * slides in from the right edge as it opens and back out the same way as Back
 * closes it: the same time and the same curve each way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsMenuTest {
    private val controller by lazy { Robolectric.buildActivity(SettingsMenuActivity::class.java) }
    private val menu get() = controller.get()
    private val panel: View get() = menu.findViewById(R.id.menu_panel)

    // Animation frames come as the test moves the clock on, one every 16 ms, as on the TV
    // (otherwise showing the activity runs every frame of the slide at once).
    @org.junit.Before
    fun framesWithTheClock() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }

    /** The clock moves on `ms`, a frame (16 ms) at a time: the test runner draws one frame per step. */
    private fun pass(ms: Long) = frames(ms)

    private fun open(): SettingsMenuActivity {
        controller.setup()
        pass(SettingsMenuActivity.SLIDE_MS + 100)
        return menu
    }

    @Test
    fun its540PixelsOfThe1920WideScreenOnTheRight() {
        open()
        val screen = menu.resources.displayMetrics.widthPixels
        assertEquals(1920, screen)
        assertEquals(540, panel.width)
        assertEquals("flush with the right edge", screen, panel.right)
        assertEquals(0f, panel.translationX)
    }

    @Test
    fun itSlidesInFromTheRightEdge() {
        controller.setup()
        val width = panel.layoutParams.width.toFloat()
        assertEquals("it starts just off the right edge", width, panel.translationX)
        pass(SettingsMenuActivity.SLIDE_MS / 2)
        assertTrue("on its way: ${panel.translationX}", panel.translationX > 0f && panel.translationX < width)
        pass(SettingsMenuActivity.SLIDE_MS / 2 + 100)
        assertEquals("in place", 0f, panel.translationX)
    }

    @Test
    fun backSlidesItBackOutThenItCloses() {
        open()
        menu.onBackPressed()
        assertFalse("not at once", menu.isFinishing)
        pass(SettingsMenuActivity.SLIDE_MS / 2)
        assertFalse("still sliding out", menu.isFinishing)
        assertTrue(panel.translationX > 0f)
        pass(SettingsMenuActivity.SLIDE_MS / 2 + 100)
        assertEquals("all the way out", panel.layoutParams.width.toFloat(), panel.translationX)
        assertTrue("then it closes", menu.isFinishing)
    }

    @Test
    fun outTakesAsLongAsIn() {
        controller.setup()
        val width = panel.layoutParams.width.toFloat()
        var opened = 0L
        while (panel.translationX > 0f && opened < 2000) { pass(10); opened += 10 }
        menu.onBackPressed()
        var closed = 0L
        while (panel.translationX < width && closed < 2000) { pass(10); closed += 10 }
        assertEquals("in: $opened ms, out: $closed ms", opened.toDouble(), closed.toDouble(), 40.0)
        assertEquals(SettingsMenuActivity.SLIDE_MS.toDouble(), opened.toDouble(), 60.0)
    }

    @Test
    fun theCurveIsTheSameBothWays() {
        // Played backwards, closing looks exactly like opening: f(t) + f(1 - t) = 1.
        val ease = SettingsMenuActivity.EASE
        for (i in 0..20) {
            val t = i / 20f
            assertEquals("t=$t", 1f, ease.getInterpolation(t) + ease.getInterpolation(1 - t), 0.01f)
        }
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
