package com.mangoscreencast.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
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
 * The home screen's wall of tiles, as on a Roku: three columns of equal
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
        const val COLUMNS = 3
        // The Roku home screen's grid, measured: 149 x 111 dp tiles, 11.5 dp apart both ways.
        const val TILE_WIDTH_DP = 149f
        const val TILE_HEIGHT_DP = 111f
        const val GAP_DP = 11.5f
        const val ROW_PITCH_DP = TILE_HEIGHT_DP + GAP_DP
    }
}

/**
 * Scrolls the [TileGrid] a whole row at a time as the remote moves through
 * it. As on the Roku, the row below the last whole one shows only its top,
 * dimmed and fading out at the bottom of the screen: from [FADE_TOP_DP]
 * down, the tiles start at 45% and fade to nothing.
 */
class TileWall @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val rowPitch = TileGrid.ROW_PITCH_DP * density
    private val fadeTop = FADE_TOP_DP * density
    private val gap = TileGrid.GAP_DP * density
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val maskShift = Matrix()
    private var maskHeight = -1

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
        // Room under the last row, so it can scroll a whole row up, clear of the fade.
        getChildAt(0)?.let { grid ->
            val room = (MeasureSpec.getSize(heightMeasureSpec) - fadeTop + gap).roundToInt().coerceAtLeast(0)
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
     * Whole rows: the focused tile's row moves just clear of the fade, or to the top. The wall
     * glides there rather than jumping, so this asks ScrollView to move it by nothing.
     */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        // Judged from where the wall is headed: another press mid-glide goes a row further.
        val from = if (glide != null) destination else scrollY
        var target = from
        if (rect.bottom > from + fadeTop) {
            target = (ceil((rect.bottom - fadeTop) / rowPitch) * rowPitch).roundToInt()
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

    override fun draw(canvas: Canvas) {
        val w = width.toFloat()
        val top = scrollY + fadeTop
        val bottom = (scrollY + height).toFloat()
        // Above the fade, as it is.
        var save = canvas.save()
        canvas.clipRect(0f, scrollY.toFloat(), w, top)
        super.draw(canvas)
        canvas.restoreToCount(save)
        if (bottom <= top) return
        // Below it, dimmed to 45% and fading to nothing at the bottom: one gradient, moved
        // with the wall, so a glide doesn't make a new one every frame.
        if (maskHeight != height) {
            mask.shader = LinearGradient(0f, fadeTop, 0f, height.toFloat(), FADE_START, 0, Shader.TileMode.CLAMP)
            maskHeight = height
        }
        maskShift.setTranslate(0f, scrollY.toFloat())
        mask.shader.setLocalMatrix(maskShift)
        save = canvas.saveLayer(0f, top, w, bottom, null)
        super.draw(canvas)
        canvas.drawRect(0f, top, w, bottom, mask)
        canvas.restoreToCount(save)
    }

    companion object {
        /** Where the fade starts, from the wall's top: in the gap above the fourth row. */
        const val FADE_TOP_DP = 3 * TileGrid.ROW_PITCH_DP - TileGrid.GAP_DP / 2

        /** 45% opaque. */
        private const val FADE_START = 0x73000000

        /** How long the wall takes to glide a row (or more) up or down. */
        const val GLIDE_MS = 280L

        /** Off quickly, then a long, gentle settle onto the row. */
        val GLIDE: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/** Tiles of the grid in order, for code that walks them. */
val TileGrid.tiles: List<View> get() = (0 until childCount).map { getChildAt(it) }
