package com.mangoscreencast.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The home screen's wall of tiles, as on a Roku: four columns of equal
 * tiles, filled left to right and top to bottom, so a tile added at the end
 * takes the next place along. Sizes are dp on the 960 x 540 dp page.
 */
class TileGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val density = resources.displayMetrics.density
    private val tileWidth = TILE_WIDTH_DP * density
    private val tileHeight = TILE_HEIGHT_DP * density
    private val gap = GAP_DP * density

    /** Rows of tiles there are. */
    val rows get() = (childCount + COLUMNS - 1) / COLUMNS

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = tileWidth.roundToInt()
        val h = tileHeight.roundToInt()
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        val width = COLUMNS * tileWidth + (COLUMNS - 1) * gap
        val height = if (rows == 0) 0f else rows * tileHeight + (rows - 1) * gap
        setMeasuredDimension(
            (width + paddingLeft + paddingRight).roundToInt(),
            (height + paddingTop + paddingBottom).roundToInt(),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = (paddingLeft + (i % COLUMNS) * (tileWidth + gap)).roundToInt()
            val y = (paddingTop + (i / COLUMNS) * (tileHeight + gap)).roundToInt()
            val child = getChildAt(i)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    companion object {
        const val COLUMNS = 4
        /** The wall's width: from 36 dp right of the menu's icons to the clock's right edge. */
        const val WIDTH_DP = 764.3f
        // The Roku home screen's grid, measured (149 x 111 dp tiles, 11.5 dp apart both ways), scaled
        // up so that four across fill the wall's width.
        private const val SCALE = WIDTH_DP / (COLUMNS * 149f + (COLUMNS - 1) * 11.5f)
        const val TILE_WIDTH_DP = 149f * SCALE
        const val TILE_HEIGHT_DP = 111f * SCALE
        const val GAP_DP = 11.5f * SCALE
        const val ROW_PITCH_DP = TILE_HEIGHT_DP + GAP_DP
    }
}

/**
 * Scrolls the [TileGrid] a whole row at a time as the remote moves through
 * it. As on the Roku, the rows run on past the bottom of the screen, cut off
 * by its edge.
 */
class TileWall @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    private val rowPitch = TileGrid.ROW_PITCH_DP * resources.displayMetrics.density

    /** The wall's glide to a row, while it's moving. */
    private var glide: ValueAnimator? = null

    /** Where the wall is headed: the end of the glide, else where it is. */
    private var destination = 0

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = false
        // The wall glides on its own curve (glideTo), not ScrollView's.
        isSmoothScrollingEnabled = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A row's room under the last row, so it can scroll up a whole row like the others.
        getChildAt(0)?.let { grid ->
            val room = ceil(rowPitch).toInt()
            if (grid.paddingBottom != room) grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, room)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Up or Down with no tile that way (the first or last row) does nothing, as on the Roku: no
     * scrolling into empty space, and no jumping out of the wall to the menu.
     */
    override fun arrowScroll(direction: Int): Boolean {
        if (FocusFinder.getInstance().findNextFocus(this, findFocus(), direction) == null) return true
        return super.arrowScroll(direction)
    }

    /**
     * Whole rows: the focused tile's row moves up just far enough to be whole on screen, or to the top. The wall
     * glides there rather than jumping, so this asks ScrollView to move it by nothing.
     */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        // Judged from where the wall is headed: another press mid-glide goes a row further.
        val from = if (glide != null) destination else scrollY
        var target = from
        if (rect.bottom > from + height) {
            target = (ceil((rect.bottom - height) / rowPitch) * rowPitch).roundToInt()
        } else if (rect.top < from) {
            target = (floor(rect.top / rowPitch) * rowPitch).roundToInt()
        }
        val max = (getChildAt(0).height - height).coerceAtLeast(0)
        glideTo(target.coerceIn(0, max))
        return 0
    }

    /**
     * Glides the wall to `target` in [GLIDE_MS], quick at first and settling gently. A glide
     * already on its way turns there from where it is. With the TV's animations off it lands at once.
     */
    private fun glideTo(target: Int) {
        if (target == (if (glide != null) destination else scrollY)) return
        destination = target
        glide?.cancel()
        glide = ValueAnimator.ofInt(scrollY, target).apply {
            duration = GLIDE_MS
            interpolator = GLIDE
            addUpdateListener { scrollTo(scrollX, it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (glide === animation) glide = null
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** How long the wall takes to glide a row (or more) up or down. */
        const val GLIDE_MS = 280L

        /** Off quickly, then a long, gentle settle onto the row. */
        val GLIDE: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/** Tiles of the grid in order, for code that walks them. */
val TileGrid.tiles: List<View> get() = (0 until childCount).map { getChildAt(it) }
