package com.mangoscreencast.tv.ui

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.mangoscreencast.tv.R
import org.junit.Assert.assertEquals
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
 * With Google TV's launcher on, for the gear's settings panel, Home would open
 * Google TV's home. The Home key service sees keys first, as an accessibility
 * service filtering them, and opens Mango's Home instead, and opens it over
 * Google TV's home when that comes up another way; every other key, the gear
 * included, goes on to the system as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeKeyServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val service = Robolectric.setupService(HomeKeyService::class.java)

    private fun key(action: Int, code: Int, repeat: Int = 0, flags: Int = 0) =
        KeyEvent(0, 0, action, code, repeat, 0, 9, 0, flags)

    private fun started(): Intent? = shadowOf(context).nextStartedActivity

    private fun press(code: Int): Pair<Boolean, Boolean> =
        service.onKeyEvent(key(KeyEvent.ACTION_DOWN, code)) to service.onKeyEvent(key(KeyEvent.ACTION_UP, code))

    private fun assertMangoHome(intent: Intent?) {
        assertNotNull("Home opens", intent)
        assertEquals(Intent.ACTION_MAIN, intent!!.action)
        assertEquals("asked for as a Home app", setOf(Intent.CATEGORY_HOME), intent.categories)
        assertEquals("Mango's", context.packageName, intent.`package`)
        assertNull("no component, so Android files it as the home screen", intent.component)
        assertTrue("from the service, a task of its own", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun theHomeButtonOpensMangosHome() {
        assertTrue("the press is Mango's", service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertNull("nothing until it's let go", started())
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        assertMangoHome(started())
        assertNull(started())
    }

    @Test
    fun holdingHomeOpensItOnce() {
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, repeat = 1)))
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        assertNotNull(started())
        assertNull("once", started())
    }

    @Test
    fun aCanceledPressOpensNothing() {
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, flags = KeyEvent.FLAG_CANCELED)))
        assertNull(started())
    }

    @Test
    fun theGearGoesOnToTheTvsOwnSettingsPanel() {
        assertEquals("the Hisense gear", false to false, press(KeyEvent.KEYCODE_NOTIFICATION))
        assertEquals("a remote's settings key", false to false, press(KeyEvent.KEYCODE_SETTINGS))
        assertNull(started())
    }

    @Test
    fun everyOtherKeyGoesOnAsItWas() {
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_SEARCH,
        )) {
            assertEquals(KeyEvent.keyCodeToString(code), false to false, press(code))
        }
        assertNull(started())
    }

    private fun shown(className: String, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) =
        service.onAccessibilityEvent(
            AccessibilityEvent(type).apply {
                packageName = "com.google.android.apps.tv.launcherx"
                this.className = className
            },
        )

    @Test
    fun googleTvsHomeComingUpOpensMangosOverIt() {
        shown("com.google.android.apps.tv.launcherx.home.HomeActivity")
        assertMangoHome(started())
        shown("com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity")
        assertMangoHome(started())
    }

    @Test
    fun theGearsPanelAndTheLaunchersOtherScreensStay() {
        shown("com.google.android.apps.tv.launcherx.dashboard.DashboardActivity")
        shown("com.google.android.apps.tv.launcherx.dashboard.DashboardHandler")
        shown("com.google.android.apps.tv.launcherx.profile.chooser.ProfileChooserActivity")
        shown("com.google.android.apps.tv.launcherx.home.HomeActivity", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertNull(started())
    }

    @Test
    fun itsAnAccessibilityServiceThatFiltersKeysAndHearsOnlyGoogleTv() {
        val services = context.packageManager.queryIntentServices(Intent(AccessibilityService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
        val ours = services.single { it.serviceInfo.name == HomeKeyService::class.java.name }
        assertEquals("only the system binds it", android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE, ours.serviceInfo.permission)
        val attrs = mutableMapOf<String, String>()
        context.resources.getXml(R.xml.home_key_service).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "accessibility-service") {
                    for (i in 0 until xml.attributeCount) attrs[xml.getAttributeName(i)] = xml.getAttributeValue(i)
                }
            }
        }
        assertEquals(attrs.toString(), "true", attrs["canRequestFilterKeyEvents"])
        val flags = attrs["accessibilityFlags"]?.removePrefix("0x")?.toInt(16) ?: 0
        assertTrue("it asks for the keys: $attrs", flags and 0x20 != 0) // flagRequestFilterKeyEvents
        val events = attrs["accessibilityEventTypes"]?.removePrefix("0x")?.toInt(16) ?: 0
        assertEquals("only screens coming up: $attrs", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, events)
        assertEquals("com.google.android.apps.tv.launcherx", attrs["packageNames"])
    }
}
