package com.mangoscreencast.tv.ui

import android.app.Application
import android.view.View
import com.mangoscreencast.tv.WidePictures
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
    private fun measure(videoWidth: Int, videoHeight: Int, mode: WidePictures): Pair<Int, Int> {
        val frame = AspectFrameLayout(RuntimeEnvironment.getApplication())
        frame.setAspect(videoWidth, videoHeight)
        frame.widePictures = mode
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(3840, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2160, View.MeasureSpec.EXACTLY),
        )
        return frame.measuredWidth to frame.measuredHeight
    }

    @Test
    fun anIPhoneInLandscapeFitsWithBarsAboveAndBelow() {
        // iPhone 15 Pro: 2556 × 1179.
        assertEquals(3840 to 1771, measure(2556, 1179, WidePictures.FIT))
    }

    @Test
    fun fillCoversTheScreenAndOverhangsTheSides() {
        assertEquals(4683 to 2160, measure(2556, 1179, WidePictures.FILL))
    }

    @Test
    fun fillNeverCropsAMac() {
        // MacBook Air 13": 2560 × 1664, narrower than 16:9.
        assertEquals(3323 to 2160, measure(2560, 1664, WidePictures.FILL))
    }

    @Test
    fun sixteenByNineFillsEitherWay() {
        assertEquals(3840 to 2160, measure(3840, 2160, WidePictures.FIT))
        assertEquals(3840 to 2160, measure(1920, 1080, WidePictures.FILL))
    }

    @Test
    fun stretchTakesTheWholeScreen() {
        assertEquals(3840 to 2160, measure(2556, 1179, WidePictures.STRETCH))
    }

    @Test
    fun stretchNeverChangesAMacOrAnUprightIPhone() {
        assertEquals(3323 to 2160, measure(2560, 1664, WidePictures.STRETCH))
        assertEquals(996 to 2160, measure(1179, 2556, WidePictures.STRETCH))
    }
}
