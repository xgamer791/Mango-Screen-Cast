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
    fun aResolutionSavedBeforeIsNeverReset() {
        for (saved in ResolutionSetting.entries) {
            // Whichever one-time resets earlier versions had done or not.
            for (flag in listOf(null, "resolution_1080_default", "resolution_auto_default")) {
                val editor = prefs.edit().clear().putString("resolution", saved.name)
                flag?.let { editor.putBoolean(it, true) }
                editor.commit()
                assertEquals("$saved after $flag", saved, Settings(context).resolution)
                assertEquals("$saved after $flag, opened again", saved, Settings(context).resolution)
            }
        }
    }

    @Test
    fun aResolutionThatNoLongerExistsIsAuto() {
        prefs.edit().putString("resolution", "HD_720").commit()
        assertEquals(ResolutionSetting.AUTO, Settings(context).resolution)
    }

    @Test
    fun aLandscapeIPhoneFillsTheScreenUnlessChanged() {
        assertEquals(WidePictures.FILL, Settings(context).widePictures)
        for (chosen in WidePictures.entries) {
            Settings(context).widePictures = chosen
            assertEquals(chosen, Settings(context).widePictures)
        }
    }

    @Test
    fun fillTurnedOffBeforeStretchCameIsFit() {
        prefs.edit().putBoolean("fill_wide", false).commit()
        assertEquals(WidePictures.FIT, Settings(context).widePictures)
    }

    @Test
    fun upGoesFromFitToFillToStretch() {
        assertEquals(listOf("FIT", "FILL", "STRETCH"), WidePictures.entries.map { it.name })
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
