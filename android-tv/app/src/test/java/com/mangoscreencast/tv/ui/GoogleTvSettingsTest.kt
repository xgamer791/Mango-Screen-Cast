package com.mangoscreencast.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleTvSettingsTest {
    private fun candidate(packageName: String, className: String = "$packageName.Main", system: Boolean = false) =
        GoogleTvSettings.Candidate(packageName, className, system)

    @Test
    fun prefersTheAndroidTvSettingsAppAndSkipsMango() {
        val chosen = GoogleTvSettings.choose(
            "com.mangoscreencast.tv",
            listOf(
                candidate("com.mangoscreencast.tv", "com.mangoscreencast.tv.ui.SettingsActivity"),
                candidate("com.example.other", system = true),
                candidate(GoogleTvSettings.TV_SETTINGS_PACKAGE, GoogleTvSettings.TV_SETTINGS_ACTIVITY, system = true),
            ),
        )
        assertEquals(GoogleTvSettings.TV_SETTINGS_PACKAGE, chosen?.packageName)
        assertEquals(GoogleTvSettings.TV_SETTINGS_ACTIVITY, chosen?.className)
    }

    @Test
    fun prefersASystemHandlerWhenTheTvSettingsAppIsNotThere() {
        val chosen = GoogleTvSettings.choose(
            "com.mangoscreencast.tv",
            listOf(
                candidate("com.mangoscreencast.tv"),
                candidate("com.oem.settings"),
                candidate("com.hisense.settings", system = true),
            ),
        )
        assertEquals("com.hisense.settings", chosen?.packageName)
    }

    @Test
    fun usesAnotherAppWhenNothingIsASystemApp() {
        val chosen = GoogleTvSettings.choose(
            "com.mangoscreencast.tv",
            listOf(candidate("com.mangoscreencast.tv"), candidate("com.oem.settings")),
        )
        assertEquals("com.oem.settings", chosen?.packageName)
    }

    @Test
    fun returnsNullWhenMangoIsTheOnlyHandler() {
        assertNull(
            GoogleTvSettings.choose(
                "com.mangoscreencast.tv",
                listOf(candidate("com.mangoscreencast.tv", "com.mangoscreencast.tv.ui.SettingsActivity")),
            ),
        )
    }
}
