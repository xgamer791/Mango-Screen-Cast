package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.mangoscreencast.tv.R
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The gear's menu: a panel on the right, 540 px of a 1920 px wide screen, over
 * whatever is on screen, in Mango Liquid Glass (LiquidGlassPanel) made from
 * what was on screen as the gear was pressed. It slides in from the right edge as the gear opens
 * it, and back out to it, in the same time and on the same curve, as the gear
 * or Back closes it; the gear while it's sliding turns it around at the same
 * speed. Closed, the app behind it is as it was. Empty for now.
 */
class SettingsMenuActivity : Activity() {
    private lateinit var panel: LiquidGlassPanel
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_menu)
        panel = findViewById(R.id.menu_panel)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels * WIDTH / SCREEN
        panel.layoutParams.width = width
        panel.translationX = width.toFloat()
        // Liquid Glass over what was on screen as the gear was pressed.
        ScreenBehind.take()?.let {
            panel.showBehind(ScreenBehind.region(it, WIDTH.toFloat() / SCREEN, width, metrics.heightPixels))
        }
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
        /** The panel's width: 540 px of a 1920 px wide screen, the same share on any TV. */
        const val WIDTH = 540
        const val SCREEN = 1920

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
