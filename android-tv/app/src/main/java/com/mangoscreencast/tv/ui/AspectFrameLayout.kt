package com.mangoscreencast.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Sizes itself to the largest box of the video's aspect ratio that fits its parent. */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var aspect = 16f / 9f

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
        var height = (maxWidth / aspect).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (maxHeight * aspect).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
