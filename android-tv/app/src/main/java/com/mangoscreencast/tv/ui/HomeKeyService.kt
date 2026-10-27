package com.mangoscreencast.tv.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps Mango the TV's Home while Google TV's launcher is on.
 *
 * The remote's gear opens the TV's own settings panel: the Hisense gear sends
 * KEYCODE_NOTIFICATION (scancode 598), and the system opens the notification
 * handler's panel with it, the Google TV launcher's dashboard (launcherx
 * DashboardHandler). TvSystemUI only opens a system app in that one package,
 * so the launcher has to be on (and the TV's settings, com.android.tv.settings,
 * for the panel's All settings). With the launcher on, Home resolves to it:
 * its HOME filter has priority 2 and Android caps Mango's at 0, whatever the
 * HOME role says.
 *
 * An accessibility service that filters keys sees them before the system does:
 * this one takes the remote's Home button and opens Mango's Home instead, and
 * when Google TV's home comes up another way (the TV starting, an app asking
 * for Home) it opens Mango's over it. Every other key, the gear included, goes
 * on as it was.
 *
 * It's turned on once over adb, as the TV's settings have no screen for it:
 * `adb shell settings put secure enabled_accessibility_services
 * com.mangoscreencast.tv/com.mangoscreencast.tv.ui.HomeKeyService`
 */
class HomeKeyService : AccessibilityService() {
    public override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) openHome()
        return true
    }

    // Only Google TV's launcher sends events here (xml/home_key_service.xml).
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString() in GOOGLE_TV_HOMES
        ) {
            openHome()
        }
    }

    override fun onInterrupt() {}

    /**
     * Mango's Home, asked for as Android asks for a Home app, but only from Mango:
     * with no component named, Android files it as the TV's home screen, so leaving
     * an app comes back to it.
     */
    private fun openHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    private companion object {
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        val GOOGLE_TV_HOMES = setOf("$GOOGLE_TV.home.HomeActivity", "$GOOGLE_TV.home.VanillaModeHomeActivity")
    }
}
