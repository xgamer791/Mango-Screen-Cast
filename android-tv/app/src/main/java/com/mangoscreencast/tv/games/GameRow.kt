package com.mangoscreencast.tv.games

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ui.TileWall
import kotlin.math.roundToInt

/**
 * The Games page's bottom row: a card per game, its picture 4:3 as on the
 * TVs these games were made for, pixels kept square. As along the PS5 home's
 * rows, the selected card stays at the left margin and the row slides past
 * it; the selected card is ringed and a touch larger while the remote is on
 * the row. Sizes are dp on the 960 x 540 dp page.
 */
class GameRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val cardW = CARD_W_DP * dp
    private val cardH = CARD_H_DP * dp
    private val gap = GAP_DP * dp
    private val margin = SystemRow.MARGIN_DP * dp

    private val cards = mutableListOf<FrameLayout>()

    var selected = 0
        private set
    private var offset = 0f
    private var glide: ValueAnimator? = null

    /** Whether the remote is on this row. */
    var active = false
        set(value) {
            field = value
            mark()
        }

    init {
        clipChildren = false
    }

    fun show(count: Int, select: Int = 0) {
        glide?.cancel()
        cards.forEach { removeView(it) }
        cards.clear()
        repeat(count) {
            val card = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = RADIUS_DP * dp
                    setColor(EMPTY)
                }
                foreground = context.getDrawable(R.drawable.games_card_ring)
                clipToOutline = true
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            cards += card
            addView(card)
        }
        selected = select.coerceIn(0, (count - 1).coerceAtLeast(0))
        offset = target(selected)
        mark()
        requestLayout()
    }

    /** Card `index`'s picture, drawn with its pixels square. */
    fun picture(index: Int, bitmap: Bitmap?) {
        val image = cards.getOrNull(index)?.getChildAt(0) as? ImageView ?: return
        image.setImageDrawable(bitmap?.let { BitmapDrawable(resources, it).apply { isFilterBitmap = false } })
    }

    /** Moves the selection by `by` cards, if there's one there; true if it moved. */
    fun move(by: Int): Boolean {
        val to = selected + by
        if (to !in cards.indices) return false
        selected = to
        mark()
        glide?.cancel()
        glide = ValueAnimator.ofFloat(offset, target(to)).apply {
            duration = TileWall.GLIDE_MS
            interpolator = TileWall.GLIDE
            addUpdateListener { offset = it.animatedValue as Float; place() }
            start()
        }
        return true
    }

    private fun mark() {
        cards.forEachIndexed { i, card ->
            val on = active && i == selected
            card.isActivated = on
            card.animate().scaleX(if (on) FOCUS_SCALE else 1f).scaleY(if (on) FOCUS_SCALE else 1f)
                .setDuration(TileWall.GLIDE_MS).setInterpolator(TileWall.GLIDE).start()
        }
    }

    /** How far the row has slid for card `index` to sit at the margin. */
    private fun target(index: Int) = index * (cardW + gap)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.makeMeasureSpec(cardW.roundToInt(), MeasureSpec.EXACTLY)
        val h = MeasureSpec.makeMeasureSpec(cardH.roundToInt(), MeasureSpec.EXACTLY)
        cards.forEach { it.measure(w, h) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cardH.roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        cards.forEach {
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
            // Grows from its left edge's middle, so it stays at the margin.
            it.pivotX = 0f
            it.pivotY = it.measuredHeight / 2f
        }
        place()
    }

    private fun place() {
        cards.forEachIndexed { i, card -> card.translationX = margin + i * (cardW + gap) - offset }
    }

    internal fun card(index: Int): View = cards[index]

    companion object {
        /** A card, 4:3, and the space between cards. */
        const val CARD_W_DP = 128f
        const val CARD_H_DP = 96f
        const val GAP_DP = 16f
        const val RADIUS_DP = 8f

        /** The selected card, this much larger. */
        const val FOCUS_SCALE = 1.08f

        /** A card before its picture is in. */
        const val EMPTY = 0xFF1A1A1E.toInt()
    }
}
