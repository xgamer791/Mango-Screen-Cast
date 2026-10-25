package com.mangoscreencast.tv.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

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
 * and opens Mango Settings, on letting go. Every other key goes on as it was.
 *
 * It's turned on once over adb, as the TV's own settings are off:
 * `adb shell settings put secure enabled_accessibility_services
 * com.mangoscreencast.tv/com.mangoscreencast.tv.ui.GearKeyService`
 */
class GearKeyService : AccessibilityService() {
    public override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_NOTIFICATION && event.keyCode != KeyEvent.KEYCODE_SETTINGS) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
