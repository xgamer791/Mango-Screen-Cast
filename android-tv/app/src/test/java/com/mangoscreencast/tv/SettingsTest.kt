package com.mangoscreencast.tv

import android.app.Application
import android.content.Context
import com.mangoscreencast.tv.display.FrameRateSetting
import com.mangoscreencast.tv.display.ResolutionSetting
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

    @Test
    fun resolutionIsAutoByDefault() {
        assertEquals(ResolutionSetting.AUTO, Settings(context).resolution)
    }

    @Test
    fun anyResolutionSavedBeforeGoesBackToAutoOnce() {
        for (old in listOf("HD_720", "UHD_2160", "QHD_1440", "FHD_1080")) {
            prefs.edit().clear().putString("resolution", old).commit()
            assertEquals(old, ResolutionSetting.AUTO, Settings(context).resolution)
        }
    }

    @Test
    fun aResolutionChosenAfterIsKept() {
        for (chosen in ResolutionSetting.entries) {
            Settings(context).resolution = chosen
            assertEquals(chosen, Settings(context).resolution)
        }
    }

    @Test
    fun settingsCyclesResolutionsFromAuto() {
        assertEquals(listOf("AUTO", "UHD_2160", "QHD_1440", "FHD_1080"), ResolutionSetting.entries.map { it.name })
    }
}
