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
        check("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "com.android.tv.settings")
        val bluetooth = target("Bluetooth")
        assertEquals("android.settings.SLICE_SETTINGS", bluetooth.action)
        assertEquals("com.android.tv.settings.slice.SliceActivity", bluetooth.component?.className)
        assertEquals(
            "Remotes & Accessories, in SliceActivity's slice_uri extra",
            "content://com.android.tv.settings.accessories.sliceprovider/general",
            bluetooth.getStringExtra("slice_uri"),
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

    private fun sbn(
        id: Int,
        time: Long,
        title: String?,
        text: String?,
        pkg: String = "p",
        uid: Int = 10_100,
        summary: Boolean = false,
        foreground: Boolean = false,
    ): StatusBarNotification {
        val context = RuntimeEnvironment.getApplication()
        val notification = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info).apply {
            title?.let(::setContentTitle)
            text?.let(::setContentText)
            if (summary) setGroup("g").setGroupSummary(true)
        }.build()
        if (foreground) notification.flags = notification.flags or Notification.FLAG_FOREGROUND_SERVICE
        @Suppress("DEPRECATION")
        return StatusBarNotification(pkg, pkg, id, null, uid, 0, 0, notification, UserHandle.getUserHandleForUid(uid), time)
    }

    @Test
    fun theSystemsNoticesOnCardsTheRestThatMatterCounted() {
        val low = sbn(9, 900, "Keyboard configured", "x", pkg = "android", uid = 1000)
        val listed = PanelNotifications.list(
            listOf(
                sbn(1, 100, "Developer options enabled", "a", pkg = "android", uid = 1000),
                sbn(2, 300, "Wireless debugging connected", "b", pkg = "android", uid = 1000),
                low,
                sbn(3, 200, "Security alert", "s"),
                sbn(4, 250, "New sign-in", "t"),
                sbn(5, 400, "Foreground Service", "f", foreground = true),
                sbn(6, 500, "CustomSettings is running", "c", pkg = "com.vt.customsettings", uid = 1000),
                sbn(7, 600, null, null),
                sbn(8, 700, "Summary", "s", summary = true),
            ),
        ) { if (it === low) android.app.NotificationManager.IMPORTANCE_LOW else android.app.NotificationManager.IMPORTANCE_DEFAULT }
        assertEquals(listOf("Wireless debugging connected", "Developer options enabled"), listed.cards.map { it.title })
        assertEquals(listOf("New sign-in", "Security alert"), listed.rest.map { it.title })
    }
}
