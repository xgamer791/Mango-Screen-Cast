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
 * The gear key starts android.settings.SETTINGS. With this app installed and
 * nothing else claiming that action, resolution lands on Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsIntentFilterTest {
    private val settingsActivity = "com.mangoscreencast.tv.ui.SettingsActivity"

    @Test
    fun settingsActionResolvesToMangoSettings() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(context.packageName, resolved?.activityInfo?.packageName)
        assertEquals(settingsActivity, resolved?.activityInfo?.name)
    }

    @Test
    fun settingsActionWithoutAnExplicitCategoryStillResolves() {
        val context = RuntimeEnvironment.getApplication()
        // startActivity adds CATEGORY_DEFAULT. MATCH_DEFAULT_ONLY is the same rule.
        val resolved = context.packageManager.resolveActivity(
            Intent(Settings.ACTION_SETTINGS),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertEquals(settingsActivity, resolved?.activityInfo?.name)
    }

    @Test
    fun settingsActivityIsExported() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context.packageName, settingsActivity),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(info.exported)
    }
}
