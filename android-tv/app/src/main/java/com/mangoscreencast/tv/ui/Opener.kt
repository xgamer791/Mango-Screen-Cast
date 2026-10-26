package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.apps.AppLauncher
import com.mangoscreencast.tv.apps.StreamingApp
import com.mangoscreencast.tv.apps.StreamingApps
import com.mangoscreencast.tv.browse.Show

/** Opens what the home screen and its pages point at, and says so when the TV can't. */
class Opener(private val activity: Activity) {
    val launcher = AppLauncher(activity.packageManager, AppLauncher.isFireTv(activity))

    /** Starts the app; one the TV doesn't have opens its app store page, and says so. */
    fun app(app: StreamingApp) {
        val entry = launcher.entry(app)
        val note = when {
            !launcher.open(activity, entry) -> activity.getString(R.string.app_open_failed, app.name)
            entry is AppLauncher.Entry.Missing -> activity.getString(R.string.app_not_installed, app.name)
            else -> return
        }
        Toast.makeText(activity, note, Toast.LENGTH_LONG).show()
    }

    /** The show in its app (straight to its page where the app takes links), or on the TV's Live TV. */
    fun show(show: Show) {
        if (show.opens == Show.LIVE_TV) return liveTv()
        val app = StreamingApps.ALL.first { it.name == show.opens }
        val entry = launcher.entry(app)
        val link = show.link
        if (entry is AppLauncher.Entry.Installed && link != null) {
            val page = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(entry.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launcher.start(activity, page)) return
        }
        app(app)
    }

    fun liveTv() = start(launcher.liveTv(), R.string.live_tv_missing)

    /** RetroArch, for the Emulation tile. */
    fun emulation() = start(launcher.emulation(), R.string.emulation_missing)

    fun store() = start(launcher.store(), R.string.store_missing)

    /** The TV's own search, with `query` already typed when there is one. */
    fun search(query: String = "") = start(launcher.search().apply {
        if (query.isNotBlank()) putExtra(android.app.SearchManager.QUERY, query)
    }, R.string.search_missing)

    private fun start(intent: Intent?, missing: Int) {
        if (intent == null || !launcher.start(activity, intent)) Toast.makeText(activity, missing, Toast.LENGTH_LONG).show()
    }
}
