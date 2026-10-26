package com.mangoscreencast.tv.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mangoscreencast.tv.R
import kotlin.math.roundToInt

/**
 * Home's menu, as on the Roku: only its icons beside the wall until Left from
 * the wall's first column opens it. Open, its words show and the wall, with
 * the focused tile's name over it, moves right to make room, running on past
 * the right edge of the screen; the arrow beside the wall's first row hides
 * under it.
 */
object HomeMenu {
    /** Where the wall starts, in dp from the page's left: beside the icons, and beside the open menu's bars. */
    const val WALL_CLOSED_DP = 108f
    const val WALL_OPEN_DP = 402.3f

    /**
     * The menu's width: its bars open (to 351 dp, as Roku's); closed, just its icons (12 dp in, 20 dp
     * wide, 12 dp after), so nothing unseen of it reaches over the wall. A closed menu as wide as its
     * bars lay under the wall's first column and reached past it, so Left from the second column
     * went straight to the menu.
     */
    const val MENU_OPEN_DP = 311.2f
    const val MENU_CLOSED_DP = 44f

    fun show(page: View, open: Boolean) {
        val menu = page.findViewById<ViewGroup>(R.id.menu)
        val words = if (open) ContextCompat.getColorStateList(page.context, R.color.home_menu_text)
        else ColorStateList.valueOf(Color.TRANSPARENT)
        for (i in 0 until menu.childCount) (menu.getChildAt(i) as TextView).setTextColor(words)
        val width = ((if (open) MENU_OPEN_DP else MENU_CLOSED_DP) * page.resources.displayMetrics.density).roundToInt()
        if (menu.layoutParams.width != width) menu.layoutParams = menu.layoutParams.apply { this.width = width }
        val start = ((if (open) WALL_OPEN_DP else WALL_CLOSED_DP) * page.resources.displayMetrics.density).roundToInt()
        for (id in intArrayOf(R.id.tile_wall, R.id.tile_name)) {
            val view = page.findViewById<View>(id)
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.marginStart != start) {
                params.marginStart = start
                view.layoutParams = params
            }
        }
        page.findViewById<View>(R.id.home_chevron).visibility = if (open) View.INVISIBLE else View.VISIBLE
    }

    /** Whether `view` is one of the menu's items. */
    fun has(page: View, view: View?): Boolean = view != null && view.parent === page.findViewById<View>(R.id.menu)
}
