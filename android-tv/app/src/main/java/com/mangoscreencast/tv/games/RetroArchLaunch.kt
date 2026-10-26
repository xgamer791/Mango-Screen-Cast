package com.mangoscreencast.tv.games

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.mangoscreencast.tv.apps.AppLauncher

/**
 * Starts a game in RetroArch, straight into it: RetroArch's game activity with
 * the game, its core and RetroArch's own settings, as front ends such as
 * Daijisho start it. Leaving the game (Quit in RetroArch's menu) comes back here.
 */
object RetroArchLaunch {
    const val ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"

    /** The intent for `game`, or null if the TV has no RetroArch. */
    fun intent(context: Context, game: GameLibrary.Game): Intent? {
        val pm = context.packageManager
        val pkg = AppLauncher.EMULATION_PACKAGES.firstOrNull { installed(pm, it) } ?: return null
        val app = pm.getApplicationInfo(pkg, 0)
        val files = "$SDCARD/Android/data/$pkg/files"
        return Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(pkg, ACTIVITY))
            .putExtra("ROM", game.path)
            .putExtra("LIBRETRO", game.core)
            .putExtra("CONFIGFILE", "$files/retroarch.cfg")
            .putExtra("IME", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            .putExtra("DATADIR", app.dataDir)
            .putExtra("APK", app.sourceDir)
            .putExtra("SDCARD", SDCARD)
            .putExtra("EXTERNAL", files)
            // A game already running gives way to this one.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun installed(pm: PackageManager, pkg: String) = try {
        pm.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** The TV's shared storage, where RetroArch keeps its settings, games and playlists. */
    const val SDCARD = "/storage/emulated/0"
}
