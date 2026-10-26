package com.mangoscreencast.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.mangoscreencast.tv.R
import java.util.Calendar
import java.util.TimeZone

/**
 * Home's wallpaper: Halloween night through October, then the three tropical
 * sunsets, a different one every quarter of an hour (on the clock: :00, :15,
 * :30, :45), the new one fading in over the old. `back` shows the wallpaper;
 * `front` sits over it, clear, and carries the next one in. `changed` runs
 * once a new wallpaper has fully faded in (the glass tiles frost it again).
 */
class HomeWallpaper(
    private val back: ImageView,
    private val front: ImageView,
    private val changed: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val handler = Handler(Looper.getMainLooper())
    @DrawableRes private var showing = 0
    private val turn = Runnable { show(fade = true) }

    /** Shows the wallpaper for now at once (Home coming back on screen), then keeps it turning. */
    fun start() {
        show(fade = false)
    }

    fun stop() {
        handler.removeCallbacks(turn)
        front.animate().cancel()
    }

    private fun show(fade: Boolean) {
        val now = clock()
        val next = at(now)
        if (next != showing) {
            showing = next
            if (fade) fadeTo(next) else {
                front.animate().cancel()
                front.alpha = 0f
                front.setImageDrawable(null)
                back.setImageResource(next)
                changed()
            }
        }
        handler.removeCallbacks(turn)
        handler.postDelayed(turn, TURN_MS - now % TURN_MS + 50)
    }

    private fun fadeTo(@DrawableRes next: Int) {
        front.animate().cancel()
        front.setImageResource(next)
        front.alpha = 0f
        front.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    back.setImageResource(next)
                    front.alpha = 0f
                    front.setImageDrawable(null)
                    changed()
                }
            })
            .start()
    }

    companion object {
        /** A new tropical sunset every quarter of an hour. */
        const val TURN_MS = 15 * 60 * 1000L

        /** How long the new one takes to fade in over the old. */
        const val FADE_MS = 4000L

        val TROPICAL = intArrayOf(
            R.drawable.home_wallpaper_tropical_1,
            R.drawable.home_wallpaper_tropical_2,
            R.drawable.home_wallpaper_tropical_3,
        )

        /** The wallpaper at `now` (ms since 1970) on the TV's clock: Halloween's all October, else the quarter hour's sunset. */
        @DrawableRes
        fun at(now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
            val month = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.MONTH)
            if (month == Calendar.OCTOBER) return R.drawable.home_wallpaper_halloween
            return TROPICAL[((now / TURN_MS) % TROPICAL.size).toInt()]
        }
    }
}
