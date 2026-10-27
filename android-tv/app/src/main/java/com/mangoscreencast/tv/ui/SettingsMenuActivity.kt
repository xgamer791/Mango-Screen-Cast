package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.mangoscreencast.tv.R
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The menu's Settings panel: a panel on the right, 540 px of a 1920 px wide
 * screen, over whatever is on screen. It slides in from the right edge as it
 * opens, and back out to it, in the same time and on the same curve, as Back
 * closes it. Closed, the screen behind it is as it was. Empty for now.
 */
class SettingsMenuActivity : Activity() {
    private lateinit var panel: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_menu)
        panel = findViewById(R.id.menu_panel)
        val width = resources.displayMetrics.widthPixels * WIDTH / SCREEN
        panel.layoutParams.width = width
        panel.translationX = width.toFloat()
        slide(open = true)
    }

    @Deprecated("Back slides the menu out")
    override fun onBackPressed() = slide(open = false)

    private fun slide(open: Boolean) {
        val width = panel.layoutParams.width.toFloat()
        val target = if (open) 0f else width
        // The whole way takes SLIDE_MS; turned around part way, the rest at the same speed.
        val share = if (width > 0f) abs(panel.translationX - target) / width else 0f
        panel.animate().cancel()
        panel.animate()
            .translationX(target)
            .setInterpolator(EASE)
            .setDuration((SLIDE_MS * share).roundToLong())
            .withEndAction { if (!open) finish() }
            .start()
    }

    companion object {
        /** The panel's width: 540 px of a 1920 px wide screen, the same share on any TV. */
        const val WIDTH = 540
        const val SCREEN = 1920

        /** How long the panel takes to slide all the way in, and all the way out. */
        const val SLIDE_MS = 320L

        /** Ease in and out, the same curve each way: f(t) + f(1 - t) = 1. */
        val EASE: Interpolator = PathInterpolator(0.45f, 0f, 0.55f, 1f)

        /**
         * Opens the menu, over whatever is on screen. NO_ANIMATION, or Android fades
         * the menu's window in as the panel slides: the panel only slides.
         */
        fun open(context: Context) {
            context.startActivity(
                Intent(context, SettingsMenuActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
