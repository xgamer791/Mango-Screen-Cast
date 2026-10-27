package com.mangoscreencast.tv.ui

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import com.mangoscreencast.tv.R
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
import org.xmlpull.v1.XmlPullParser

/**
 * The Hisense remote's gear sends KEYCODE_NOTIFICATION, which the system
 * takes before any app sees it and gives only to a system app's panel. The
 * gear service sees keys first, as an accessibility service filtering them,
 * and opens the menu instead, or closes it when it's in front, as the
 * dashboard did; every other key goes on as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class GearKeyServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val service = Robolectric.setupService(GearKeyService::class.java)

    @org.junit.Before
    fun framesWithTheClock() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }

    @org.junit.After
    fun nothingInFront() = FrontSettings.clear()

    private fun key(action: Int, code: Int, repeat: Int = 0, flags: Int = 0) =
        KeyEvent(0, 0, action, code, repeat, 0, 9, 0, flags)

    private fun started(): Intent? = shadowOf(context).nextStartedActivity

    private fun press(code: Int): Pair<Boolean, Boolean> =
        service.onKeyEvent(key(KeyEvent.ACTION_DOWN, code)) to service.onKeyEvent(key(KeyEvent.ACTION_UP, code))

    @Test
    fun theHisenseGearOpensMangoSettings() {
        assertTrue("the press is the gear's", service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NOTIFICATION)))
        assertNull("nothing until it's let go", started())
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NOTIFICATION)))
        val intent = started()
        assertNotNull("the menu opens", intent)
        assertEquals(SettingsMenuActivity::class.java.name, intent!!.component?.className)
        assertTrue("from the service, a task of its own", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(started())
    }

    /** A Settings screen in front, as SettingsActivity reports itself while it's resumed. */
    private fun settingsInFront(): Activity =
        Robolectric.buildActivity(Activity::class.java).create().start().resume().get().also { FrontSettings.shown(it) }

    @Test
    fun pressingTheGearAgainClosesMangoSettings() {
        val shown = settingsInFront()
        assertEquals(true to true, press(KeyEvent.KEYCODE_NOTIFICATION))
        assertTrue("it closes", shown.isFinishing)
        assertNull("nothing opens", started())
    }

    @Test
    fun theGearOpensSettingsAgainOnceItsClosed() {
        val shown = settingsInFront()
        press(KeyEvent.KEYCODE_NOTIFICATION)
        assertTrue(shown.isFinishing)
        FrontSettings.hidden(shown) // its onPause
        press(KeyEvent.KEYCODE_NOTIFICATION)
        assertEquals(SettingsMenuActivity::class.java.name, started()?.component?.className)
    }

    @Test
    fun settingsBehindAnotherAppIsOpenedNotClosed() {
        val behind = settingsInFront()
        FrontSettings.hidden(behind) // paused: another app came in front of it
        press(KeyEvent.KEYCODE_NOTIFICATION)
        assertFalse(behind.isFinishing)
        assertEquals(SettingsMenuActivity::class.java.name, started()?.component?.className)
    }

    @Test
    fun anOlderScreenPausingLateDoesntHideTheNewOne() {
        val old = settingsInFront()
        val new = settingsInFront()
        FrontSettings.hidden(old)
        press(KeyEvent.KEYCODE_NOTIFICATION)
        assertTrue("the one in front closes", new.isFinishing)
        assertFalse(old.isFinishing)
    }

    @Test
    fun theGearSlidesTheMenuClosedWhenItsOpen() {
        val menu = Robolectric.buildActivity(SettingsMenuActivity::class.java).setup().get()
        frames(SettingsMenuActivity.OPEN_MS + 100)
        assertEquals(true to true, press(KeyEvent.KEYCODE_NOTIFICATION))
        assertFalse("it slides out first", menu.isFinishing)
        assertNull("nothing new opens", started())
        frames(SettingsMenuActivity.CLOSE_MS + 100)
        assertTrue("then it closes", menu.isFinishing)
    }

    @Test
    fun aRemotesSettingsKeyOpensMangoSettingsToo() {
        assertEquals(true to true, press(KeyEvent.KEYCODE_SETTINGS))
        assertEquals(SettingsMenuActivity::class.java.name, started()?.component?.className)
    }

    @Test
    fun holdingTheGearOpensSettingsOnce() {
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NOTIFICATION)))
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NOTIFICATION, repeat = 1)))
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NOTIFICATION, repeat = 2)))
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NOTIFICATION)))
        assertNotNull(started())
        assertNull("once", started())
    }

    @Test
    fun aCanceledPressOpensNothing() {
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NOTIFICATION)))
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NOTIFICATION, flags = KeyEvent.FLAG_CANCELED)))
        assertNull(started())
    }

    @Test
    fun everyOtherKeyGoesOnAsItWas() {
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_SEARCH,
        )) {
            assertEquals(KeyEvent.keyCodeToString(code), false to false, press(code))
        }
        assertNull(started())
    }

    @Test
    fun whenThePanelsSleepTimerEndsTheTvGoesToSleep() {
        val start = 5_000_000L
        var clock = start
        SleepTimer.now = { clock }
        try {
            SleepTimer.set(context, 15)
            val shadow = shadowOf(service)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(14))
            assertTrue("not yet", shadow.globalActionsPerformed.isEmpty())
            clock = start + 15 * 60_000L
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(2))
            assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), shadow.globalActionsPerformed)
            assertEquals("the timer's done", null, SleepTimer.end(context))
        } finally {
            SleepTimer.now = System::currentTimeMillis
        }
    }

    @Test
    fun aTimerTurnedOffPutsNothingToSleep() {
        SleepTimer.set(context, 15)
        SleepTimer.cancel(context)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(20))
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test
    fun itsAnAccessibilityServiceThatFiltersKeys() {
        val services = context.packageManager.queryIntentServices(Intent(AccessibilityService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
        val ours = services.single { it.serviceInfo.name == GearKeyService::class.java.name }
        assertEquals("only the system binds it", android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE, ours.serviceInfo.permission)
        val attrs = mutableMapOf<String, String>()
        context.resources.getXml(R.xml.gear_key_service).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "accessibility-service") {
                    for (i in 0 until xml.attributeCount) attrs[xml.getAttributeName(i)] = xml.getAttributeValue(i)
                }
            }
        }
        assertEquals(attrs.toString(), "true", attrs["canRequestFilterKeyEvents"])
        val flags = attrs["accessibilityFlags"]?.removePrefix("0x")?.toInt(16) ?: 0
        assertTrue("it asks for the keys: $attrs", flags and 0x20 != 0) // flagRequestFilterKeyEvents
    }
}
