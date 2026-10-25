package com.mangoscreencast.tv

import android.app.Application
import android.content.Context
import com.mangoscreencast.tv.display.FrameRateSetting
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsTest {
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("mango", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs.edit().clear().commit()
    }

    @Test
    fun frameRateIs120ByDefault() {
        assertEquals(FrameRateSetting.FPS_120, Settings(context).frameRate)
    }

    @Test
    fun sixtySavedBeforeTheNewDefaultBecomes120() {
        prefs.edit().putString("frame_rate", "FPS_60").commit()
        assertEquals(FrameRateSetting.FPS_120, Settings(context).frameRate)
    }

    @Test
    fun otherChoicesSavedBeforeAreKept() {
        prefs.edit().putString("frame_rate", "FPS_30").commit()
        assertEquals(FrameRateSetting.FPS_30, Settings(context).frameRate)
    }

    @Test
    fun sixtyChosenNowIsKept() {
        Settings(context).frameRate = FrameRateSetting.FPS_60
        assertEquals(FrameRateSetting.FPS_60, Settings(context).frameRate)
    }

    @Test
    fun settingsCyclesFrom120() {
        assertEquals(listOf(120, 60, 30), FrameRateSetting.entries.map { it.fps })
    }
}
