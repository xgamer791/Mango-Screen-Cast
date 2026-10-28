package com.mangoscreencast.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mangoscreencast.tv.R
import kotlin.math.roundToInt

/**
 * Home's menu, as on the Roku: only its icons beside the wall until Left from
 * the wall's first column opens it. Opening, the wall, with the focused tile's
 * name over it, glides right to make room, running on past the right edge of
 * the screen, and the menu's bar grows into the room it leaves, its words
 * coming with it, never nearer the wall than when it's open, so the two never
 * meet; closing, the bar shrinks ahead of the wall coming back. One unhurried
 * glide ([MOVE_MS]) does both. The arrow beside the wall's first row hides
 * under it.
 */
object HomeMenu {
    /** Where the wall starts, in dp from the page's left: 30 dp right of the icons (which end at 71.8 dp), and beside the open menu's bars. */
    const val WALL_CLOSED_DP = 101.8f
    const val WALL_OPEN_DP = 402.3f

    /**
     * The menu's width: its bars open (to 351 dp, as Roku's); closed, just its icons (12 dp in, 20 dp
     * wide, 12 dp after), so nothing unseen of it reaches over the wall. A closed menu as wide as its
     * bars lay under the wall's first column and reached past it, so Left from the second column
     * went straight to the menu.
     */
    const val MENU_OPEN_DP = 311.2f
    const val MENU_CLOSED_DP = 44f

    /**
     * The menu opening or closing: the wall's glide and the bar's growth (or shrinking), easing in and
     * out. The bar used to open whole at once while the wall was still on its way, so the wall ran over
     * it for the first frames, and the glide was quick enough to look rushed.
     */
    const val MOVE_MS = 360L
    val MOVE: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun show(page: View, open: Boolean) {
        val menu = page.findViewById<ViewGroup>(R.id.menu)
        val wall = page.findViewById<View>(R.id.tile_wall)
        val name = page.findViewById<View>(R.id.tile_name)
        val density = page.resources.displayMetrics.density
        val start = ((if (open) WALL_OPEN_DP else WALL_CLOSED_DP) * density).roundToInt()
        val params = wall.layoutParams as ViewGroup.MarginLayoutParams
        val gliding = menu.getTag(R.id.menu) as? ValueAnimator
        if (params.marginStart == start) {
            // Already there, or on its way.
            if (gliding == null) settle(menu, open, density)
            return
        }
        // While it moves the menu is as wide as open, its words showing as far as the bar is shown.
        words(menu, true)
        width(menu, MENU_OPEN_DP, density)
        gliding?.cancel()
        // The wall takes its new place at once, so the remote finds it there, but is drawn gliding over
        // from where it is now (mid-glide too, if the menu turns back).
        val from = params.marginStart + wall.translationX
        for (view in arrayOf(wall, name)) {
            val p = view.layoutParams as ViewGroup.MarginLayoutParams
            p.marginStart = start
            view.layoutParams = p
            view.translationX = from - start
        }
        reveal(menu, from, density)
        val glide = ValueAnimator.ofFloat(from - start, 0f).apply {
            duration = MOVE_MS
            interpolator = MOVE
            addUpdateListener {
                val x = it.animatedValue as Float
                wall.translationX = x
                name.translationX = x
                reveal(menu, start + x, density)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var turned = false

                override fun onAnimationCancel(animation: Animator) {
                    turned = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (turned) return
                    menu.setTag(R.id.menu, null)
                    settle(menu, open, density)
                }
            })
        }
        menu.setTag(R.id.menu, glide)
        glide.start()
    }

    /** Whether `view` is one of the menu's items. */
    fun has(page: View, view: View?): Boolean = view != null && view.parent === page.findViewById<View>(R.id.menu)

    /** The menu at rest: open, all of it; closed, its icons only, its words gone. */
    private fun settle(menu: ViewGroup, open: Boolean, density: Float) {
        words(menu, open)
        width(menu, if (open) MENU_OPEN_DP else MENU_CLOSED_DP, density)
        menu.clipBounds = null
    }

    /**
     * Shows the menu's bar as far as the wall, drawn at `wallAt` (px from the page's left), leaves room
     * for: as far short of it as the open bar is of the open wall, and never less than the icons.
     */
    private fun reveal(menu: ViewGroup, wallAt: Float, density: Float) {
        val left = (menu.layoutParams as ViewGroup.MarginLayoutParams).marginStart
        val clearance = WALL_OPEN_DP * density - (left + MENU_OPEN_DP * density)
        val shown = (wallAt - clearance - left).coerceIn(MENU_CLOSED_DP * density, MENU_OPEN_DP * density)
        menu.clipBounds = Rect(0, 0, shown.roundToInt(), Int.MAX_VALUE / 2)
    }

    private fun words(menu: ViewGroup, show: Boolean) {
        val colour = if (show) ContextCompat.getColorStateList(menu.context, R.color.home_menu_text)
        else ColorStateList.valueOf(Color.TRANSPARENT)
        for (i in 0 until menu.childCount) (menu.getChildAt(i) as TextView).setTextColor(colour)
    }

    private fun width(menu: ViewGroup, dp: Float, density: Float) {
        val width = (dp * density).roundToInt()
        if (menu.layoutParams.width != width) menu.layoutParams = menu.layoutParams.apply { this.width = width }
    }
}
