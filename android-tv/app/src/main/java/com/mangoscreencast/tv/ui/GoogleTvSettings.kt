package com.mangoscreencast.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The TV's own settings, the Google dashboard the gear used to open.
 *
 * Once Mango is chosen for [Settings.ACTION_SETTINGS], starting that action
 * would open Mango again. This builds an explicit intent for the other app
 * that handles it, so the dashboard still opens from the row in Settings.
 */
internal object GoogleTvSettings {
    const val TV_SETTINGS_PACKAGE = "com.android.tv.settings"
    const val TV_SETTINGS_ACTIVITY = "com.android.tv.settings.MainSettings"

    data class Candidate(val packageName: String, val className: String, val system: Boolean)

    /**
     * Another app's handler. The Android TV settings app wins, then any system
     * app, then whatever else is left. Mango itself is never chosen.
     */
    fun choose(selfPackage: String, candidates: List<Candidate>): Candidate? {
        val others = candidates.filter { it.packageName != selfPackage }
        return others.firstOrNull { it.packageName == TV_SETTINGS_PACKAGE }
            ?: others.firstOrNull { it.system }
            ?: others.firstOrNull()
    }

    fun intent(context: Context): Intent {
        val probe = Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT)
        val found = context.packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map {
                Candidate(
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                    (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
        val chosen = choose(context.packageName, found)
        val component = if (chosen != null) {
            ComponentName(chosen.packageName, chosen.className)
        } else {
            // Package visibility can hide the match. This is the activity the gear opens on Google TV.
            ComponentName(TV_SETTINGS_PACKAGE, TV_SETTINGS_ACTIVITY)
        }
        // The component is set, so resolution cannot send this back to Mango.
        return Intent(Settings.ACTION_SETTINGS).setComponent(component)
    }
}
