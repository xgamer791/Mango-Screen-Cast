package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every move of the remote clicks once, whoever moves the focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FocusSoundsTest {
    /** A button that notes the sounds played for it. */
    private class Heard(context: Context) : Button(context) {
        val sounds = mutableListOf<Int>()
        override fun playSoundEffect(soundConstant: Int) {
            sounds += soundConstant
        }
    }

    private fun screen(scrolls: Boolean): Pair<Activity, List<Heard>> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val buttons = List(2) { Heard(activity).apply { isFocusableInTouchMode = true; text = "$it" } }
        val column = LinearLayout(activity).apply {
            orientation = if (scrolls) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(400, 300)) }
        }
        activity.setContentView(if (scrolls) ScrollView(activity).apply { addView(column) } else column)
        FocusSounds.install(activity.window)
        shadowOf(Looper.getMainLooper()).idle()
        buttons[0].requestFocus()
        return activity to buttons
    }

    private fun Activity.press(key: Int) {
        window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    /** A ScrollView moves the focus itself and Android stays silent: the move gets its click here. */
    @Test
    fun aMoveAViewMakesClicks() {
        val (activity, buttons) = screen(scrolls = true)
        activity.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(true, buttons[1].isFocused)
        assertEquals(listOf(SoundEffectConstants.NAVIGATION_DOWN), buttons[1].sounds)
    }

    /** A key no view takes is Android's to move and click for: no second click from here. */
    @Test
    fun aMoveAndroidMakesIsLeftToAndroid() {
        val (activity, buttons) = screen(scrolls = false)
        activity.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(emptyList<Int>(), buttons[0].sounds + buttons[1].sounds)
    }

    /** No move, no click: Up from the top of a list. */
    @Test
    fun noMoveNoClick() {
        val (activity, buttons) = screen(scrolls = true)
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(true, buttons[0].isFocused)
        assertEquals(emptyList<Int>(), buttons[0].sounds + buttons[1].sounds)
    }

    @Test
    fun installsOnce() {
        val (activity, _) = screen(scrolls = true)
        val callback = activity.window.callback
        FocusSounds.install(activity.window)
        assertEquals(callback, activity.window.callback)
    }
}
