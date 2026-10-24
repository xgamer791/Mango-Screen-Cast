package com.mangoscreencast.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Sizes itself to the largest box of the video's aspect ratio that fits its
 * parent, or, with [fillWide], covers the parent with a picture wider than
 * it (an iPhone in landscape), letting the parent cut off the sides.
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var aspect = 16f / 9f

    var fillWide = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** True if the picture is wider than the space for it, so [fillWide] would crop it. */
    fun isWiderThan(width: Int, height: Int): Boolean = height > 0 && aspect > width.toFloat() / height * 1.01f

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val next = width.toFloat() / height
        if (next != aspect) {
            aspect = next
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        var width = maxWidth
        var height = (maxWidth / aspect).roundToInt()
        if (fillWide && isWiderThan(maxWidth, maxHeight)) {
            // Full height; the parent centres the extra width and clips it.
            height = maxHeight
            width = (maxHeight * aspect).roundToInt()
        } else if (height > maxHeight) {
            height = maxHeight
            width = (maxHeight * aspect).roundToInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
