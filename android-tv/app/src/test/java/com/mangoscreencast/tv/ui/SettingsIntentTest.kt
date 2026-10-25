package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Hisense sends com.hisense.KEY_EVENT. Katniss then lists activities that
 * declare MAIN and android.intent.category.LEANBACK_SETTINGS. That pair, plus
 * android.settings.SETTINGS and DEFAULT, has to live on one SettingsActivity
 * filter. A filter missing MAIN (1.2.70) or LEANBACK_SETTINGS (1.2.71) is not
 * listed, and the gear does nothing once stock TvSettings is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsIntentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun katnissLeanbackQueryHitsOneFilterWithEveryRequiredPiece() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory("android.intent.category.LEANBACK_SETTINGS")
        val match = context.packageManager
            .queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
            .single { it.activityInfo.name == SettingsActivity::class.java.name }
        val filter = match.filter
        assertNotNull(filter)
        assertTrue(filter.hasAction(Intent.ACTION_MAIN))
        assertTrue(filter.hasAction(Settings.ACTION_SETTINGS))
        assertTrue(filter.hasCategory(Intent.CATEGORY_DEFAULT))
        assertTrue(filter.hasCategory("android.intent.category.LEANBACK_SETTINGS"))
        assertFalse(filter.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        assertFalse(filter.hasCategory(Intent.CATEGORY_HOME))
        assertEquals(999, filter.priority)
        assertEquals(999, match.priority)
        assertTrue(match.activityInfo.exported)
    }

    @Test
    fun settingsActionResolvesThroughThatSameFilter() {
        val intent = Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT)
        val match = context.packageManager
            .queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER or PackageManager.MATCH_DEFAULT_ONLY)
            .single { it.activityInfo.name == SettingsActivity::class.java.name }
        val filter = match.filter
        assertNotNull(filter)
        assertTrue(filter.hasAction(Intent.ACTION_MAIN))
        assertTrue(filter.hasCategory("android.intent.category.LEANBACK_SETTINGS"))
        assertEquals(999, match.priority)
    }

    @Test
    fun settingsActivityIsNotASecondLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val names = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertFalse(names.toString(), SettingsActivity::class.java.name in names)
        assertTrue(names.toString(), HomeActivity::class.java.name in names)
    }

    @Test
    fun settingsActivityIsExported() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, SettingsActivity::class.java),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(info.exported)
    }

    @Test
    fun gearKeyBackupStartsSettingsByExplicitComponent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SETTINGS)
        assertTrue(activity.consumeGearKey(down))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)
        assertEquals(ComponentName(activity, SettingsActivity::class.java), started.intent.component)
        assertNull(started.intent.action)

        val repeat = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SETTINGS, 1)
        assertTrue(activity.consumeGearKey(repeat))
        assertTrue(activity.consumeGearKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SETTINGS)))
        assertNull(shadowOf(activity).nextStartedActivityForResult)

        assertFalse(activity.consumeGearKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
    }
}
