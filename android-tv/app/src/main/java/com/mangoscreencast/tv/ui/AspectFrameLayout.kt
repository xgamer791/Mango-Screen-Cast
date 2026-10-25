package com.mangoscreencast.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.mangoscreencast.tv.WidePictures
import kotlin.math.roundToInt

/**
 * Sizes itself to the largest box of the video's aspect ratio that fits its
 * parent. A picture wider than the parent (an iPhone in landscape) can
 * instead cover it ([WidePictures.FILL]), letting the parent cut off the
 * sides, or take exactly the parent's size ([WidePictures.STRETCH]).
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var aspect = 16f / 9f

    var widePictures = WidePictures.FIT
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** The shape of the picture this is laid out for. */
    val pictureAspect: Float get() = aspect

    /** True if the picture is wider than the space for it, so [widePictures] applies. */
    fun isWiderThan(width: Int, height: Int): Boolean = height > 0 && aspect > width.toFloat() / height * 1.01f

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val next = width.toFloat() / height
        if (next != aspect) {
            aspect = next
            requestLayout()
        }
    }

    /** The size this takes in a parent of `maxWidth` x `maxHeight`. */
    fun sizeFor(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (isWiderThan(maxWidth, maxHeight)) {
            when (widePictures) {
                // Full height; the parent centres the extra width and clips it.
                WidePictures.FILL -> return (maxHeight * aspect).roundToInt() to maxHeight
                WidePictures.STRETCH -> return maxWidth to maxHeight
                WidePictures.FIT -> {}
            }
        }
        val height = (maxWidth / aspect).roundToInt()
        return if (height > maxHeight) (maxHeight * aspect).roundToInt() to maxHeight else maxWidth to height
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (width, height) = sizeFor(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
