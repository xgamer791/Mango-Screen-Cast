package com.mangoscreencast.tv.ui

import android.app.Application
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AspectFrameLayoutTest {
    /** The size the player's frame takes on a 3840 × 2160 screen. */
    private fun measure(videoWidth: Int, videoHeight: Int, fillWide: Boolean): Pair<Int, Int> {
        val frame = AspectFrameLayout(RuntimeEnvironment.getApplication())
        frame.setAspect(videoWidth, videoHeight)
        frame.fillWide = fillWide
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(3840, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2160, View.MeasureSpec.EXACTLY),
        )
        return frame.measuredWidth to frame.measuredHeight
    }

    @Test
    fun anIPhoneInLandscapeFitsWithBarsAboveAndBelow() {
        // iPhone 15 Pro: 2556 × 1179.
        assertEquals(3840 to 1771, measure(2556, 1179, fillWide = false))
    }

    @Test
    fun fillCoversTheScreenAndOverhangsTheSides() {
        assertEquals(4683 to 2160, measure(2556, 1179, fillWide = true))
    }

    @Test
    fun fillNeverCropsAMac() {
        // MacBook Air 13": 2560 × 1664, narrower than 16:9.
        assertEquals(3323 to 2160, measure(2560, 1664, fillWide = true))
    }

    @Test
    fun sixteenByNineFillsEitherWay() {
        assertEquals(3840 to 2160, measure(3840, 2160, fillWide = false))
        assertEquals(3840 to 2160, measure(1920, 1080, fillWide = true))
    }
}
