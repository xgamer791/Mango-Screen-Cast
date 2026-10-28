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
        return intents.any { start(context, it) }
    }

    /** Starts `intent`; false if nothing on the TV takes it. */
    fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** The TV's own Live TV app (its tuner and guide), if it has one. */
    fun liveTv(): Intent? = LIVE_TV_PACKAGES.firstNotNullOfOrNull { launchIntent(it) }

    /** RetroArch, for the Emulation tile, if the TV has it. */
    fun emulation(): Intent? = EMULATION_PACKAGES.firstNotNullOfOrNull { launchIntent(it) }

    /** The TV's app store: Google Play, or the Amazon Appstore on a Fire TV. */
    fun store(): Intent? =
        (if (onFireTv) listOf(AMAZON_APPSTORE, PLAY_STORE) else listOf(PLAY_STORE, AMAZON_APPSTORE))
            .firstNotNullOfOrNull { launchIntent(it) }

    /** The TV's search (Google TV's, on this one). */
    fun search(): Intent = Intent(GLOBAL_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** An app on the TV that the home screen has no tile of its own for. */
    data class OtherApp(val packageName: String, val launch: Intent, val installed: Long)

    /**
     * The TV's other apps (those on its home screen, less `except`), the
     * longest installed first: a newly installed one comes last.
     */
    fun otherApps(except: Set<String>): List<OtherApp> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in except }
            .mapNotNull { packageName ->
                val launch = launchIntent(packageName) ?: return@mapNotNull null
                val installed = try {
                    packageManager.getPackageInfo(packageName, 0).firstInstallTime
                } catch (_: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
                OtherApp(packageName, launch, installed)
            }
            .sortedWith(compareBy({ it.installed }, { it.packageName }))
    }

    private fun view(uri: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        /** Live TV apps, the TV maker's first: Hisense's, MediaTek's, Google's, Android's own. */
        /** RetroArch: the 32-bit build (all this TV's apps can be), then the others. */
        val EMULATION_PACKAGES = listOf("com.retroarch.ra32", "com.retroarch", "com.retroarch.aarch64")

        /** The TV's own settings, kept off the wall: the remote's gear and its panel open them. */
        val NOT_ON_WALL = listOf("com.android.tv.settings")

        val LIVE_TV_PACKAGES = listOf(
            "com.vt.tv.tvcenter", "com.mediatek.wwtv.tvcenter", "com.google.android.tv", "com.android.tv",
        )
        const val PLAY_STORE = "com.android.vending"
        const val AMAZON_APPSTORE = "com.amazon.venezia"
        const val GLOBAL_SEARCH = "android.search.action.GLOBAL_SEARCH"

        fun isFireTv(context: Context): Boolean =
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
                context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
