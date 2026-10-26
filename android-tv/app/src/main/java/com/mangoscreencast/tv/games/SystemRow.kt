package com.mangoscreencast.tv.games

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ui.TileWall
import kotlin.math.roundToInt

/**
 * The Games page's top row, as the PS5 home's: a square tile per game system,
 * its controller on it. The selected one is larger and stays at the left
 * margin; the rest follow it at the small size, top edges in line, and slide
 * along as the selection moves. The selected system's name sits beside it,
 * under the small tiles. Sizes are dp on the 960 x 540 dp page.
 */
class SystemRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val small = SMALL_DP * dp
    private val large = LARGE_DP * dp
    private val gap = GAP_DP * dp
    private val margin = MARGIN_DP * dp

    private val tiles = mutableListOf<FrameLayout>()
    private val name = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = ResourcesCompat.getFont(context, R.font.geist_medium)
        includeFontPadding = false
        setTextColor(0xFFFFFFFF.toInt())
        maxLines = 1
    }
    private var names: List<String> = emptyList()

    /** The selected tile, and where the row is on its way from. */
    var selected = 0
        private set
    private var from = 0
    private var progress = 1f
    private var glide: ValueAnimator? = null

    /** Whether the remote is on this row (a ring round the selected tile), or on the games below. */
    var active = true
        set(value) {
            field = value
            tiles.forEachIndexed { i, tile -> tile.isActivated = value && i == selected }
        }

    init {
        clipChildren = false
        addView(name)
    }

    fun show(systems: List<GameLibrary.System>, select: Int = 0) {
        tiles.forEach { removeView(it) }
        tiles.clear()
        names = systems.map { it.title }
        for (system in systems) {
            val tile = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = RADIUS_DP * dp
                    setColor(TILE)
                }
                foreground = context.getDrawable(R.drawable.games_tile_ring)
                addView(ImageView(context).apply {
                    // Drawn 1:1 on the selected tile; smoothed down with mipmaps on the small ones.
                    setImageDrawable(context.getDrawable(system.icon)?.also { (it as? BitmapDrawable)?.bitmap?.setHasMipMap(true) })
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = ICON_ALPHA
                }, FrameLayout.LayoutParams((large * ICON).roundToInt(), (large * ICON).roundToInt(), Gravity.CENTER))
                pivotX = 0f
                pivotY = 0f
                contentDescription = system.title
            }
            tiles += tile
            addView(tile)
        }
        selected = select.coerceIn(0, (systems.size - 1).coerceAtLeast(0))
        from = selected
        progress = 1f
        name.text = names.getOrNull(selected)
        active = active
        requestLayout()
    }

    /** Moves the selection by `by` tiles, if there's one there; true if it moved. */
    fun move(by: Int): Boolean {
        val to = selected + by
        if (to !in tiles.indices) return false
        from = selected
        selected = to
        tiles.forEachIndexed { i, tile -> tile.isActivated = active && i == selected }
        glide?.cancel()
        progress = 0f
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TileWall.GLIDE_MS
            interpolator = TileWall.GLIDE
            addUpdateListener { progress = it.animatedValue as Float; place() }
            start()
        }
        name.animate().cancel()
        name.alpha = 0f
        name.text = names[selected]
        name.animate().alpha(1f).setDuration(TileWall.GLIDE_MS).setInterpolator(TileWall.GLIDE).start()
        place()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.makeMeasureSpec(large.roundToInt(), MeasureSpec.EXACTLY)
        tiles.forEach { it.measure(size, size) }
        name.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), large.roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        tiles.forEach { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        name.layout(0, 0, name.measuredWidth, name.measuredHeight)
        place()
    }

    /** Puts each tile where it is on the way from the last selection to this one. */
    private fun place() {
        val old = positions(from)
        val new = positions(selected)
        tiles.forEachIndexed { i, tile ->
            val x = old[i].first + (new[i].first - old[i].first) * progress
            val s = old[i].second + (new[i].second - old[i].second) * progress
            tile.translationX = x
            tile.scaleX = s / large
            tile.scaleY = s / large
        }
        name.translationX = margin + large + gap
        name.translationY = small + NAME_GAP_DP * dp
    }

    /** Each tile's left edge and size with `focus` selected: it at the margin, larger, the rest in a line from it. */
    private fun positions(focus: Int): List<Pair<Float, Float>> {
        val sizes = tiles.indices.map { if (it == focus) large else small }
        var x = margin
        for (i in focus - 1 downTo 0) x -= sizes[i] + gap
        return sizes.map { s -> (x to s).also { x += s + gap } }
    }

    /** The tile for system `index`, for tests. */
    internal fun tile(index: Int): View = tiles[index]

    companion object {
        /** A tile, the selected one, the space between them, and the left margin. */
        const val SMALL_DP = 56f
        const val LARGE_DP = 88f
        const val GAP_DP = 8f
        const val MARGIN_DP = 64f

        /** Corners as round as the PS5's, and the controller across most of the tile. */
        const val RADIUS_DP = 12f
        const val ICON = 0.62f
        const val ICON_ALPHA = 0.92f

        /** The selected system's name, this far under the small tiles. */
        const val NAME_GAP_DP = 12f

        /** A tile: near-black, letting a little of the art through. */
        const val TILE = 0xB8141418.toInt()
    }
}
