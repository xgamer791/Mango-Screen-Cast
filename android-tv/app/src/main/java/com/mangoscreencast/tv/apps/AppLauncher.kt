package com.mangoscreencast.tv.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Finds which of the [StreamingApps] this TV has, and opens them: an
 * installed app starts, one that isn't opens its page in the TV's app store.
 */
class AppLauncher(private val packageManager: PackageManager, private val onFireTv: Boolean) {
    sealed interface Entry {
        val app: StreamingApp

        data class Installed(override val app: StreamingApp, val packageName: String, val launch: Intent) : Entry

        /** Not on this TV: `store` are the ways to its install page, best first. */
        data class Missing(override val app: StreamingApp, val store: List<Intent>) : Entry
    }

    fun entry(app: StreamingApp): Entry {
        for (packageName in app.packages) {
            val launch = launchIntent(packageName) ?: continue
            return Entry.Installed(app, packageName, launch)
        }
        return Entry.Missing(app, storeIntents(app))
    }

    /** The TV home screen's way in (Netflix and others have no other), else the phone one. */
    fun launchIntent(packageName: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)

    fun storeIntents(app: StreamingApp): List<Intent> = buildList {
        val id = app.packages.first()
        if (onFireTv) add(view("amzn://apps/android?p=${app.fireTvPackage}"))
        add(view("market://details?id=$id"))
        add(view("https://play.google.com/store/apps/details?id=$id"))
    }

    /** Starts the app, or its store page; false if nothing on the TV could. */
    fun open(context: Context, entry: Entry): Boolean {
        val intents = when (entry) {
            is Entry.Installed -> listOf(entry.launch)
            is Entry.Missing -> entry.store
        }
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    private fun view(uri: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        fun isFireTv(context: Context): Boolean =
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
                context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
