package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.apps.StreamingApps
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each wall logo is a bitmap the size it's drawn on Home's 1440p page (the layout's pixels, 4/3
 * larger), so it's drawn pixel for pixel, not stretched or squeezed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class HomeLogosTest {
    @Test
    fun eachLogoIsDrawnPixelForPixelAt1440p() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val grid = TileGrid(ContextThemeWrapper(activity, R.style.Theme_Mango))
        HomeTiles.fill(grid, emptyList(), HomeTiles.Actions())
        grid.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
        val logos = grid.tiles.mapNotNull { it.findViewById<ImageView>(R.id.app_logo) }
        assertEquals(StreamingApps.ALL.size, logos.size)
        for (logo in logos) {
            val name = (logo.parent as View).contentDescription
            assertEquals("$name's width at 1440p", logo.drawable.intrinsicWidth.toDouble(), logo.width * 4.0 / 3, 1.4)
            assertEquals("$name's height at 1440p", logo.drawable.intrinsicHeight.toDouble(), logo.height * 4.0 / 3, 1.4)
        }
    }
}
