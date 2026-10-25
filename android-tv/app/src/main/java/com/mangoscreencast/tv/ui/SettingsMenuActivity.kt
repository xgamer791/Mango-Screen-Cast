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
 * The gear's menu: a panel a third of the screen wide on the right, over
 * whatever is on screen. It slides in from the right edge as the gear opens
 * it, and back out to it, in the same time and on the same curve, as the gear
 * or Back closes it; the gear while it's sliding turns it around at the same
 * speed. Closed, the app behind it is as it was. Empty for now.
 */
class SettingsMenuActivity : Activity() {
    private lateinit var panel: View
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_menu)
        panel = findViewById(R.id.menu_panel)
        val width = resources.displayMetrics.widthPixels / 3
        panel.layoutParams.width = width
        panel.translationX = width.toFloat()
        slide(open = true)
    }

    override fun onResume() {
        super.onResume()
        FrontSettings.shown(this)
    }

    override fun onPause() {
        FrontSettings.hidden(this)
        super.onPause()
    }

    /** The gear (GearKeyService): closes the menu, or keeps it if it's on its way out. */
    fun gear() = slide(open = closing)

    @Deprecated("Back slides the menu out, as the gear does")
    override fun onBackPressed() = slide(open = false)

    private fun slide(open: Boolean) {
        closing = !open
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
        /** How long the panel takes to slide all the way in, and all the way out. */
        const val SLIDE_MS = 320L

        /** Ease in and out, the same curve each way: f(t) + f(1 - t) = 1. */
        val EASE: Interpolator = PathInterpolator(0.45f, 0f, 0.55f, 1f)

        /** Opens the menu, over whatever is on screen. */
        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsMenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
