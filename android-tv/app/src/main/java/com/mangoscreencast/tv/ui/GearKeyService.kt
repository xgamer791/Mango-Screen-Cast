package com.mangoscreencast.tv.ui

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * The remote's gear opens Mango Settings, from whatever is on screen.
 *
 * The Hisense gear sends KEYCODE_NOTIFICATION (scancode 598). The system takes
 * it before any app sees it and opens the notification handler's panel, here
 * the Google TV launcher's dashboard (launcherx DashboardHandler). TvSystemUI
 * (TvNotificationPanel.startNotificationHandlerActivity) only opens a system
 * app in that one package, behind STATUS_BAR_SERVICE, so no intent filter of
 * Mango's can be it; and with Mango as Home the launcher is off, so the gear
 * did nothing. An accessibility service that filters keys sees them before
 * the system does: this one takes the gear, and a remote's KEYCODE_SETTINGS,
 * and on letting go opens the menu (SettingsMenuActivity), once it's taken a
 * screenshot for the menu's Liquid Glass, or slides it closed
 * when it's in front, as the dashboard did; Settings opened another way
 * (by voice) it closes. Every other key goes on as it was.
 *
 * It's turned on once over adb, as the TV's own settings are off:
 * `adb shell settings put secure enabled_accessibility_services
 * com.mangoscreencast.tv/com.mangoscreencast.tv.ui.GearKeyService`
 */
class GearKeyService : AccessibilityService() {
    companion object {
        /** How long the menu waits for the screenshot its glass is made from. */
        const val SCREENSHOT_WAIT_MS = 400L
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_NOTIFICATION && event.keyCode != KeyEvent.KEYCODE_SETTINGS) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            when (val shown = FrontSettings.current()) {
                is SettingsMenuActivity -> shown.gear()
                null -> openMenu()
                else -> shown.finish()
            }
        }
        return true
    }

    private val main = Handler(Looper.getMainLooper())
    private var opening = false

    /** A picture of the screen, for the menu's glass: `done` gets it, or null. Replaced in tests. */
    internal var screenshot: ((Bitmap?) -> Unit) -> Unit = ::takeScreen

    /** The menu, once what's on screen is taken for its glass (or that's given up on). */
    private fun openMenu() {
        if (opening) return
        opening = true
        screenshot { image ->
            ScreenBehind.image = image
            opening = false
            SettingsMenuActivity.open(this)
        }
    }

    /** The screen as it is (Android 11 and later), as a bitmap the glass can read; null after SCREENSHOT_WAIT_MS. */
    private fun takeScreen(done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return done(null)
        var answered = false
        val answer = { image: Bitmap? ->
            if (!answered) {
                answered = true
                done(image)
            }
        }
        main.postDelayed({ answer(null) }, SCREENSHOT_WAIT_MS)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val readable = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    buffer.close()
                    answer(readable)
                }

                override fun onFailure(errorCode: Int) = answer(null)
            })
        } catch (_: RuntimeException) {
            answer(null)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}

/**
 * The Settings screen in front, if one is: SettingsActivity says so as it's
 * resumed and paused, and the gear closes it (GearKeyService). Both run on
 * the main thread.
 */
internal object FrontSettings {
    private var screen: WeakReference<Activity>? = null

    fun shown(activity: Activity) {
        screen = WeakReference(activity)
    }

    /** `activity` is no longer in front; a newer screen in front stays. */
    fun hidden(activity: Activity) {
        if (screen?.get() === activity) screen = null
    }

    fun current(): Activity? = screen?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun clear() {
        screen = null
    }
}
