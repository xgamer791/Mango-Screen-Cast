package com.mangoscreencast.tv.browse

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mangoscreencast.tv.R
import kotlin.math.roundToInt

/**
 * A browse page's rows, on its glass: each a heading and a line of tiles that
 * runs off the right edge. The focused tile's row moves up to the top of the
 * glass, and along the row the focused tile stays at the 64 dp margin.
 */
class RowsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    /** One tile: its name under it, and how it fills its 192 x 108 frame. */
    class Tile(val caption: String, val fill: (FrameLayout) -> Unit, val focused: () -> Unit, val clicked: () -> Unit)

    class Row(val title: String, val tiles: List<Tile>)

    private val dp = resources.displayMetrics.density
    private val margin = (64 * dp).roundToInt()
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (20 * dp).roundToInt(), 0, 0)
    }

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** The rows' tile frames, row by row: the ones the remote moves between. */
    val frames: List<List<View>>
        get() = (0 until column.childCount).mapNotNull { column.getChildAt(it) as? LinearLayout }
            .mapNotNull { row -> (row.getChildAt(1) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout }
            .map { line -> (0 until line.childCount).map { line.getChildAt(it).findViewById(R.id.tile_frame) } }

    fun show(rows: List<Row>, message: String? = null) {
        column.removeAllViews()
        if (message != null) column.addView(text(message, 16f, R.font.geist_medium, 0xE6FFFFFF.toInt()).apply {
            maxWidth = (560 * dp).roundToInt()
            setLineSpacing(0f, 1.3f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = margin
            bottomMargin = (24 * dp).roundToInt()
        })
        val inflater = LayoutInflater.from(context)
        for (row in rows) {
            val block = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            block.addView(text(row.title, 16f, R.font.geist_medium, 0xFFFFFFFF.toInt()), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = margin })
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(margin, 0, margin, 0)
            }
            val scroller = RowScroll(context, margin).apply { addView(line) }
            block.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).roundToInt() })
            for (tile in row.tiles) {
                val view = inflater.inflate(R.layout.item_browse_tile, line, false)
                val frame = view.findViewById<FrameLayout>(R.id.tile_frame)
                val caption = view.findViewById<TextView>(R.id.tile_caption)
                caption.text = tile.caption
                frame.contentDescription = tile.caption
                tile.fill(frame)
                frame.setOnClickListener { tile.clicked() }
                frame.setOnFocusChangeListener { _, has ->
                    caption.setTextColor(if (has) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
                    if (has) {
                        smoothScrollTo(0, block.top - column.paddingTop)
                        tile.focused()
                    }
                }
                line.addView(view)
            }
            column.addView(block, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (24 * dp).roundToInt() })
        }
        scrollTo(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Room under the last row, so it too can move up to the top. Set before measuring: a padding
        // changed during layout asks for a layout that never comes, and the rows below stay undrawn.
        val room = MeasureSpec.getSize(heightMeasureSpec)
        if (column.paddingBottom != room) column.setPadding(0, column.paddingTop, 0, room)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The rows move as the focus does (above), not by the default nudge into view. */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

    /** Up on the first row or Down on the last does nothing: no scrolling into the room below. */
    override fun arrowScroll(direction: Int): Boolean {
        if (FocusFinder.getInstance().findNextFocus(this, findFocus(), direction) == null) return true
        return super.arrowScroll(direction)
    }

    private fun text(value: String, sp: Float, font: Int, colour: Int) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = ResourcesCompat.getFont(context, font)
        includeFontPadding = false
        setTextColor(colour)
        gravity = Gravity.START
    }

    /** A row's line of tiles: the focused one is brought to the left margin. */
    private class RowScroll(context: Context, private val margin: Int) : HorizontalScrollView(context) {
        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            isFocusable = false
        }

        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
            val line = getChildAt(0) ?: return 0
            val target = (rect.left - margin).coerceIn(0, (line.width - width).coerceAtLeast(0))
            return target - scrollX
        }
    }

    companion object {
        /** A logo or icon `w` x `h` px, centred in a tile frame. */
        fun centred(frame: FrameLayout, view: View, w: Int, h: Int) {
            frame.addView(view, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        }
    }
}
