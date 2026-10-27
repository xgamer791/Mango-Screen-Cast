package com.mangoscreencast.tv.guide

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.guide.PlutoTv.Channel
import com.mangoscreencast.tv.guide.PlutoTv.Show
import kotlin.math.roundToInt

/**
 * The Prevue Channel's blue grid (1993–1999): the running clock and three half
 * hours along the top, then a row per channel, its number and name in yellow
 * and its shows in white on bevelled boxes, with arrows where a show runs off
 * either side. The chosen channel is always the top row, its shows on the
 * light blue Prevue gave its featured channel; movie channels' shows are on
 * its premium channels' maroon. Rows glide up and down as the channel changes.
 *
 * Sizes are drawn from the view's height, in units of 1/508 (the grid is
 * 508 px tall on the TV's 1080p screen).
 */
class GuideView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object {
        const val ROWS = 4
        val NAVY = Color.rgb(0x16, 0x1B, 0x6E)
        val HEADER = Color.rgb(0x2B, 0x3F, 0xB8)
        val FEATURED = Color.rgb(0x33, 0x94, 0xDA)
        val PREMIUM = Color.rgb(0x7A, 0x24, 0x27)
        val YELLOW = Color.rgb(0xF6, 0xF0, 0x4A)

        private fun blend(color: Int, with: Int, amount: Float) = Color.rgb(
            (Color.red(color) + (Color.red(with) - Color.red(color)) * amount).roundToInt(),
            (Color.green(color) + (Color.green(with) - Color.green(color)) * amount).roundToInt(),
            (Color.blue(color) + (Color.blue(with) - Color.blue(color)) * amount).roundToInt(),
        )
    }

    var channels: List<Channel> = emptyList()
        set(value) {
            field = value
            layouts.clear()
            selected = if (value.isEmpty()) 0 else selected.coerceIn(0, value.size - 1)
            invalidate()
        }

    /** The grid's first half hour. */
    var from: Long = GuideGrid.slotStart(System.currentTimeMillis())
        set(value) {
            if (field == value) return
            field = value
            layouts.clear()
            invalidate()
        }

    /** The time on the clock in the corner. */
    var now: Long = System.currentTimeMillis()
        set(value) {
            field = value
            invalidate()
        }

    /** The chosen channel, in [channels]: the top row. */
    var selected = 0
        private set

    private val typeface = ResourcesCompat.getFont(context, R.font.barlow_semi_condensed_bold)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = this@GuideView.typeface }
    private val path = Path()
    private val box = RectF()

    /** Text laid out for a box, kept until the listings, the half hours or the size change. */
    private val layouts = HashMap<Any, StaticLayout>()

    /** How far the rows are from where they come to rest, in px: they glide there after a move. */
    private var offset = 0f
    private val glide = ValueAnimator().apply {
        duration = 160
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            offset = it.animatedValue as Float
            invalidate()
        }
    }

    private var u = 1f
    private val gap get() = 4 * u
    private val bevel get() = 3 * u
    private val shadow get() = 3 * u
    private val headerHeight get() = 60 * u
    private val pitch get() = (height - headerHeight) / ROWS
    private val channelWidth get() = width * 240f / 1824f
    private val slotWidth get() = (width - channelWidth) / GuideGrid.SLOTS

    /** Chooses the channel `by` rows down (up when negative), round the end of the list; the rows glide there. */
    fun move(by: Int, glide: Boolean = true) {
        if (channels.isEmpty()) return
        selected = Math.floorMod(selected + by, channels.size)
        if (glide && height > 0) {
            val start = (offset + by * pitch).coerceIn(-3 * pitch, 3 * pitch)
            this.glide.cancel()
            this.glide.setFloatValues(start, 0f)
            this.glide.start()
        } else {
            this.glide.cancel()
            offset = 0f
        }
        invalidate()
    }

    /** Chooses `index` without gliding: the guide opening on the last channel watched. */
    fun select(index: Int) {
        if (index !in channels.indices) return
        selected = index
        offset = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        u = h / 508f
        layouts.clear()
    }

    override fun onDetachedFromWindow() {
        glide.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        header(canvas)
        if (channels.isEmpty()) return
        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        val above = (offset / pitch).toInt() + 1
        val below = ROWS + 1 + (-offset / pitch).toInt()
        for (row in -above..below) {
            val top = headerHeight + row * pitch + offset
            if (top >= height || top + pitch <= headerHeight) continue
            val index = Math.floorMod(selected + row, channels.size)
            channelRow(canvas, channels[index], top, index == selected)
        }
        canvas.restore()
    }

    private fun header(canvas: Canvas) {
        cell(canvas, gap / 2, gap / 2, channelWidth - gap / 2, headerHeight - gap / 2, HEADER)
        centred(canvas, GuideGrid.seconds(now), 0f, channelWidth, 0f, headerHeight, 36 * u, YELLOW)
        for (slot in 0 until GuideGrid.SLOTS) {
            val left = channelWidth + slot * slotWidth
            cell(canvas, left + gap / 2, gap / 2, left + slotWidth - gap / 2, headerHeight - gap / 2, HEADER)
            centred(canvas, GuideGrid.clock(from + slot * GuideGrid.SLOT_MS), left, left + slotWidth, 0f, headerHeight, 36 * u, YELLOW)
        }
    }

    private fun channelRow(canvas: Canvas, channel: Channel, top: Float, chosen: Boolean) {
        val y0 = top + gap / 2
        val y1 = top + pitch - gap / 2
        cell(canvas, gap / 2, y0, channelWidth - gap / 2, y1, NAVY)
        channelName(canvas, channel, y0, y1)
        val color = when {
            chosen -> FEATURED
            channel.category == "Movies" -> PREMIUM
            else -> NAVY
        }
        for (c in GuideGrid.cells(channel.shows, from)) {
            val x0 = channelWidth + c.first * slotWidth + gap / 2
            val x1 = channelWidth + (c.last + 1) * slotWidth - gap / 2
            cell(canvas, x0, y0, x1, y1, color)
            if (c.before > 0) arrows(canvas, x0 + bevel + 6 * u, y0, y1, c.before, left = true)
            if (c.after > 0) arrows(canvas, x1 - bevel - 6 * u, y0, y1, c.after, left = false)
            val show = c.show ?: continue
            val textLeft = x0 + bevel + 8 * u + (if (c.before > 0) arrowsWidth(c.before) + 4 * u else 0f)
            val textRight = x1 - bevel - 8 * u - (if (c.after > 0) arrowsWidth(c.after) + 4 * u else 0f)
            val layout = showText(show, (textRight - textLeft).toInt().coerceAtLeast(1)) ?: continue
            shadowed(canvas, layout, textLeft, y0 + bevel + 4 * u, Color.WHITE)
        }
    }

    /** The channel's number over its name, in yellow, as Prevue's "55 / HOT CH". */
    private fun channelName(canvas: Canvas, channel: Channel, y0: Float, y1: Float) {
        val width = (channelWidth - 2 * bevel - 12 * u).toInt().coerceAtLeast(1)
        val number = layouts.getOrPut("n" + channel.id) { layout(channel.number.toString(), 36 * u, width, 1, Layout.Alignment.ALIGN_CENTER) }
        val name = layouts.getOrPut("c" + channel.id) { layout(channel.name.uppercase(), 27 * u, width, 2, Layout.Alignment.ALIGN_CENTER) }
        val top = (y0 + y1 - number.height - name.height) / 2
        val left = (channelWidth - width) / 2
        shadowed(canvas, number, left, top, YELLOW)
        shadowed(canvas, name, left, top + number.height, YELLOW)
    }

    /** A show's box text: its start time if off the half hour, its name (a movie's in quotes), rating and year. */
    private fun showText(show: Show, width: Int): StaticLayout? {
        if (show.title.isEmpty()) return null
        return layouts.getOrPut(Pair(show, width)) {
            val words = SpannableStringBuilder(GuideGrid.startNote(show)).append(GuideGrid.title(show))
            show.rating?.let {
                words.append(' ')
                val at = words.length
                words.append(it.replace("-", ""))
                words.setSpan(Badge(), at, words.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            words.append(GuideGrid.year(show))
            layout(words, 38 * u, width, 2, Layout.Alignment.ALIGN_NORMAL)
        }
    }

    private fun layout(words: CharSequence, size: Float, width: Int, lines: Int, align: Layout.Alignment): StaticLayout {
        val paint = TextPaint(text).apply { textSize = size }
        return StaticLayout.Builder.obtain(words, 0, words.length, paint, width)
            .setAlignment(align)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.92f)
            .build()
    }

    /** `layout` in `color` over a hard black shadow, as Prevue's text. */
    private fun shadowed(canvas: Canvas, layout: StaticLayout, x: Float, y: Float, color: Int) {
        canvas.save()
        canvas.translate(x + shadow, y + shadow)
        layout.paint.color = Color.BLACK
        layout.draw(canvas)
        canvas.translate(-shadow, -shadow)
        layout.paint.color = color
        layout.draw(canvas)
        canvas.restore()
    }

    private fun centred(canvas: Canvas, words: String, x0: Float, x1: Float, y0: Float, y1: Float, size: Float, color: Int) {
        text.textSize = size
        text.textAlign = Paint.Align.CENTER
        val baseline = (y0 + y1) / 2 - (text.descent() + text.ascent()) / 2
        text.color = Color.BLACK
        canvas.drawText(words, (x0 + x1) / 2 + shadow, baseline + shadow, text)
        text.color = color
        canvas.drawText(words, (x0 + x1) / 2, baseline, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** A box in `color`, lit from the top left: a light edge along its top and left, a dark one along its bottom and right. */
    private fun cell(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        fill.color = color
        canvas.drawRect(x0, y0, x1, y1, fill)
        fill.color = blend(color, Color.WHITE, 0.55f)
        canvas.drawRect(x0, y0, x1, y0 + bevel, fill)
        canvas.drawRect(x0, y0, x0 + bevel, y1, fill)
        fill.color = blend(color, Color.BLACK, 0.6f)
        canvas.drawRect(x0 + bevel, y1 - bevel, x1, y1, fill)
        canvas.drawRect(x1 - bevel, y0 + bevel, x1, y1, fill)
    }

    private val arrowWidth get() = 11 * u
    private val arrowStroke get() = 6 * u
    private fun arrowsWidth(count: Int) = arrowWidth + arrowStroke + (count - 1) * 12 * u

    /** Prevue's tall white chevrons at `edge`: one or two, pointing out of the box. */
    private fun arrows(canvas: Canvas, edge: Float, y0: Float, y1: Float, count: Int, left: Boolean) {
        val height = (y1 - y0) * 0.66f
        val top = (y0 + y1 - height) / 2
        for (i in 0 until count) {
            val tip = if (left) edge + i * 12 * u else edge - i * 12 * u
            chevron(canvas, tip + shadow, top + shadow, height, left, Color.BLACK)
            chevron(canvas, tip, top, height, left, Color.WHITE)
        }
    }

    private fun chevron(canvas: Canvas, tip: Float, top: Float, height: Float, left: Boolean, color: Int) {
        val s = if (left) 1 else -1
        val w = arrowWidth
        val t = arrowStroke
        path.rewind()
        path.moveTo(tip + s * w, top)
        path.lineTo(tip, top + height / 2)
        path.lineTo(tip + s * w, top + height)
        path.lineTo(tip + s * (w + t), top + height)
        path.lineTo(tip + s * t, top + height / 2)
        path.lineTo(tip + s * (w + t), top)
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    /** A rating in a small white box, as Prevue's "PG13". In the black of the shadow pass it draws only the shadow's box. */
    private inner class Badge : ReplacementSpan() {
        private fun small(paint: Paint) = TextPaint(paint).apply { textSize = paint.textSize * 0.62f }

        override fun getSize(paint: Paint, words: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return (small(paint).measureText(words, start, end) + 12 * u).roundToInt()
        }

        override fun draw(canvas: Canvas, words: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val small = small(paint)
            val width = small.measureText(words, start, end) + 12 * u
            val height = small.textSize * 1.1f
            val boxTop = y - paint.textSize * 0.72f + (paint.textSize * 0.72f - height) / 2
            box.set(x, boxTop, x + width, boxTop + height)
            val shadowPass = paint.color == Color.BLACK
            fill.color = if (shadowPass) Color.BLACK else Color.WHITE
            canvas.drawRoundRect(box, 3 * u, 3 * u, fill)
            if (shadowPass) return
            small.color = NAVY
            small.textAlign = Paint.Align.CENTER
            val baseline = box.centerY() - (small.descent() + small.ascent()) / 2
            canvas.drawText(words, start, end, box.centerX(), baseline, small)
        }
    }
}
