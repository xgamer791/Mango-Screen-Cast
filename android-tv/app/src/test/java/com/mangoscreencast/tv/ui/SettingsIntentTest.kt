package com.mangoscreencast.tv.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Hisense gear sends android.settings.SETTINGS. Mango Settings has to be
 * an exported match for that intent, at the same priority as stock TvSettings,
 * or the remote keeps opening the system settings app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsIntentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun settingsIntentResolvesToMangoSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val ours = matches.filter { it.activityInfo.name == SettingsActivity::class.java.name }
        assertEquals(ours.toString(), 1, ours.size)
        assertTrue(ours[0].activityInfo.exported)
        assertEquals(1, ours[0].priority)
    }

    @Test
    fun leanbackSettingsCategoryAlsoMatches() {
        // Not a public SDK constant; TvSettings still declares this category.
        val intent = Intent(Settings.ACTION_SETTINGS)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addCategory("android.intent.category.LEANBACK_SETTINGS")
        val names = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }
        assertTrue(names.toString(), SettingsActivity::class.java.name in names)
    }

    @Test
    fun settingsActivityIsExported() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, SettingsActivity::class.java),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(info.exported)
    }
}
