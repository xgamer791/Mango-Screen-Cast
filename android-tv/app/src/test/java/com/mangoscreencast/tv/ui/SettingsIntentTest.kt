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
 * How the gear finds a settings screen. On the Hisense (and Google TV), the
 * gear looks up activities for MAIN + LEANBACK_SETTINGS, as TvSettings'
 * MainSettings declares them, and opens what it finds by component;
 * android.settings.SETTINGS is what `am start` and other apps use. Mango
 * Settings has to be an exported match for both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsIntentTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val settingsActivity = SettingsActivity::class.java.name

    @Test
    fun theGearsLookupFindsMangoSettings() {
        // Not a public SDK constant; TvSettings declares it with MAIN and no DEFAULT.
        val intent = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
        val names = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertTrue(names.toString(), settingsActivity in names)
    }

    @Test
    fun theGearsLookupStartedAsAnIntentOpensMangoSettings() {
        // startActivity adds DEFAULT: a TV that starts the lookup's intent instead of querying it.
        val intent = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(settingsActivity, resolved?.activityInfo?.name)
    }

    @Test
    fun settingsIntentResolvesToMangoSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val ours = matches.filter { it.activityInfo.name == settingsActivity }
        assertEquals(ours.toString(), 1, ours.size)
        assertTrue(ours[0].activityInfo.exported)
    }

    @Test
    fun settingsActionWithoutAnExplicitCategoryStillResolves() {
        // startActivity adds CATEGORY_DEFAULT. MATCH_DEFAULT_ONLY is the same rule.
        val resolved = context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(settingsActivity, resolved?.activityInfo?.name)
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
