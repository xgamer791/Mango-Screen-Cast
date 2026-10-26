package com.mangoscreencast.tv.ui

import android.os.Build
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.Window

/**
 * The TV's click for every move of the remote, on every screen. Android plays
 * it only when it moves the focus itself; a view that moves it on its own (the
 * home screen's wall, a ScrollView, a page's rows) takes the key and Android
 * stays silent, so Up and Down on the wall made no sound while Left and Right
 * did. This sits between the window and its screen: a direction key that some
 * view took and that moved the focus gets the click Android would have played.
 */
class FocusSounds private constructor(private val window: Window, private val screen: Window.Callback) :
    Window.Callback by screen {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = direction(event) ?: return screen.dispatchKeyEvent(event)
        val before = window.currentFocus
        val handled = screen.dispatchKeyEvent(event)
        val after = window.currentFocus
        // Not handled: Android moves the focus next and plays the click itself.
        if (handled && after != null && after !== before) after.playSoundEffect(sound(direction, event.repeatCount > 0))
        return handled
    }

    companion object {
        /** Puts the click between `window` and the screen it shows. */
        fun install(window: Window) {
            val screen = window.callback ?: return
            if (screen !is FocusSounds) window.callback = FocusSounds(window, screen)
        }

        private fun direction(event: KeyEvent): Int? {
            if (event.action != KeyEvent.ACTION_DOWN) return null
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> null
            }
        }

        /** The sound Android plays for a move that way (its quieter repeat while the key is held, where it has one). */
        private fun sound(direction: Int, repeating: Boolean): Int =
            if (Build.VERSION.SDK_INT >= 33) SoundEffectConstants.getConstantForFocusDirection(direction, repeating)
            else SoundEffectConstants.getContantForFocusDirection(direction)
    }
}
