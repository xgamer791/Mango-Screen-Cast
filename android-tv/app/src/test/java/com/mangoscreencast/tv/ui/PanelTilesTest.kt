package com.mangoscreencast.tv.ui

import android.app.Application
import android.app.Notification
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Where each of the gear panel's tiles goes, and which notifications it lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelTilesTest {
    private fun target(label: String) = PanelTiles.all.single {
        RuntimeEnvironment.getApplication().getString(it.label) == label
    }.open!!()

    @Test
    fun eachTileOpensTheTvsOwnScreenForIt() {
        fun check(label: String, action: String, pkg: String) {
            val intent = target(label)
            assertEquals(label, action, intent.action)
            assertEquals(label, pkg, intent.`package`)
        }
        check("Screensaver", "com.google.android.pano.action.SLEEP", "com.android.tv.settings")
        check("Inputs", "com.android.tv.action.VIEW_INPUTS", "com.vt.tv.input")
        check("Display & Picture", Settings.ACTION_DISPLAY_SETTINGS, "com.vt.customsettings")
        check("Sound", Settings.ACTION_SOUND_SETTINGS, "com.vt.customsettings")
        check("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "com.android.tv.settings")
        check("Google Home", "com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME", "com.google.android.apps.tv.dreamx")
        check("Bluetooth", "android.settings.SLICE_SETTINGS", "com.android.tv.settings")
        check("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "com.android.tv.settings")
        assertEquals(
            "content://com.android.tv.settings.accessories.sliceprovider/general",
            target("Bluetooth").data.toString(),
        )
    }

    @Test
    fun fourDownAColumnAndTheSleepTimerIsThePanelsOwn() {
        assertEquals(listOf(4, 4, 1), PanelTiles.columns().map { it.size })
        assertEquals(PanelTiles.all, PanelTiles.columns().flatten())
        assertEquals(null, PanelTiles.SLEEP_TIMER.open)
    }

    @Test
    fun theSleepTimersTimesByName() {
        val res = RuntimeEnvironment.getApplication().resources
        assertEquals(
            listOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "1 hour 30 minutes", "2 hours", "3 hours"),
            SleepTimer.CHOICES.map { SleepTimer.name(res, it) },
        )
    }

    @Test
    fun helpAndAllSettingsOpenTheTvsOwn() {
        assertEquals("com.android.settings.action.HELP_SETTINGS", PanelTiles.help().action)
        assertEquals("com.android.tv.settings", PanelTiles.help().`package`)
        assertEquals(Settings.ACTION_SETTINGS, PanelTiles.allSettings().action)
    }

    private fun sbn(id: Int, time: Long, title: String?, text: String?, summary: Boolean = false): StatusBarNotification {
        val context = RuntimeEnvironment.getApplication()
        val notification = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info).apply {
            title?.let(::setContentTitle)
            text?.let(::setContentText)
            if (summary) setGroup("g").setGroupSummary(true)
        }.build()
        @Suppress("DEPRECATION")
        return StatusBarNotification("p", "p", id, null, 0, 0, 0, notification, UserHandle.getUserHandleForUid(0), time)
    }

    @Test
    fun notificationsNewestFirstWithoutEmptyOnesOrGroupSummaries() {
        val items = PanelNotifications.items(
            listOf(
                sbn(1, 100, "Older", "a"),
                sbn(2, 300, "Newest", "b"),
                sbn(3, 200, null, null),
                sbn(4, 400, "Summary", "s", summary = true),
            ),
        )
        assertEquals(listOf("Newest", "Older"), items.map { it.title })
        assertEquals("b", items.first().text)
    }
}
