package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ReceiverService
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.airplay.AirPlayReceiver.State
import com.mangoscreencast.tv.apps.AppLauncher
import com.mangoscreencast.tv.apps.StreamingApps
import com.mangoscreencast.tv.browse.BrowseActivity
import com.mangoscreencast.tv.browse.Page
import com.mangoscreencast.tv.update.AppUpdater

/**
 * The home screen, laid out as the Roku TV home screen: the menu on the
 * left, the wall of tiles on the right (News, Emulation and Live TV on
 * Liquid Glass, then the streaming apps, then the TV's other apps), the
 * clock and the number of tiles top right.
 */
class HomeActivity : Activity(), AirPlayReceiver.Listener, AppUpdater.Listener {
    private lateinit var updateArea: View
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var itemCount: TextView
    private lateinit var grid: TileGrid

    private val receiver get() = MangoApp.receiver
    private val updater get() = MangoApp.instance.updater
    private val opener by lazy { Opener(this) }
    private val launcher get() = opener.launcher
    private val handler = Handler(Looper.getMainLooper())
    private val medium by lazy { ResourcesCompat.getFont(this, R.font.geist_medium) }
    private val medium550 by lazy { ResourcesCompat.getFont(this, R.font.geist_550) }

    /** The menu item focused last: Left from the wall's first column goes back to it. */
    private var cameFrom = R.id.menu_home
        set(value) {
            field = value
            leadLeftTo(value)
        }

    private fun leadLeftTo(item: Int) =
        grid.tiles.forEachIndexed { i, tile -> if (i % TileGrid.COLUMNS == 0) tile.nextFocusLeftId = item }

    /** The TV's other apps on the wall now, so it's filled again only when they change. */
    private var othersShown: List<String>? = null

    /** A TV left on this screen still picks up a new release within minutes. */
    private val recheckUpdates = object : Runnable {
        override fun run() {
            updater.checkIfDue()
            handler.postDelayed(this, AppUpdater.RECHECK_INTERVAL_MS)
        }
    }

    /** Apps installed or removed while the home screen is up take or leave their place on the wall. */
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = fillWall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drawn at 1440p, whatever size the TV draws its screens at.
        Canvas1440Layout.setContentView(this, R.layout.activity_home)
        updateArea = findViewById(R.id.update_area)
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)
        itemCount = findViewById(R.id.item_count)
        grid = findViewById(R.id.tile_grid)
        updateButton.setOnClickListener {
            when (updater.state) {
                is AppUpdater.State.Failed -> updater.checkNow()
                else -> updater.install()
            }
        }

        // The focused item's words go from 500 to 550 on its white bar; Left from the wall comes back
        // to the item it was entered from.
        val menu = findViewById<ViewGroup>(R.id.menu)
        for (i in 0 until menu.childCount) {
            menu.getChildAt(i).setOnFocusChangeListener { item, focused ->
                (item as TextView).typeface = if (focused) medium550 else medium
                if (focused) cameFrom = item.id
            }
        }
        // Each item but Home and Settings opens its page.
        for ((item, page) in listOf(
            R.id.menu_what_to_watch to Page.WHAT_TO_WATCH, R.id.menu_featured_free to Page.FEATURED_FREE,
            R.id.menu_live_tv to Page.LIVE_TV, R.id.menu_sports to Page.SPORTS, R.id.menu_search to Page.SEARCH,
            R.id.menu_store to Page.STORE,
        )) findViewById<View>(item).setOnClickListener { BrowseActivity.open(this, page) }
        findViewById<View>(R.id.menu_settings).setOnClickListener { SettingsMenuActivity.open(this) }

        // Top right: how many tiles there are, or which of them has the focus.
        findViewById<View>(android.R.id.content).viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> count() }

        fillWall()
        findViewById<View>(R.id.menu_home).requestFocus()
        ReceiverService.sync(this)
    }

    /** The wall's tiles, and how many there are; again only if the TV's other apps have changed. */
    private fun fillWall() {
        val own = StreamingApps.ALL.flatMap { it.packages }.toSet() + AppLauncher.LIVE_TV_PACKAGES + packageName
        val others = launcher.otherApps(own)
        val names = others.map { it.packageName }
        if (names == othersShown) return
        othersShown = names
        val tiles = others.mapNotNull { other ->
            try {
                val app = packageManager.getApplicationInfo(other.packageName, 0)
                val activity = packageManager.resolveActivity(other.launch, 0)?.activityInfo
                HomeTiles.OtherTile(other, app.loadLabel(packageManager),
                    activity?.loadBanner(packageManager) ?: app.loadBanner(packageManager))
            } catch (_: Exception) {
                null
            }
        }
        val focused = grid.findFocus()?.let { grid.indexOfChild(it) }?.takeIf { it >= 0 }
        HomeTiles.fill(grid, tiles, HomeTiles.Actions(
            shortcut = ::openShortcut,
            app = opener::app,
            other = { launcher.start(this, it.launch) },
        ))
        leadLeftTo(cameFrom)
        focused?.let { grid.getChildAt(it.coerceAtMost(grid.childCount - 1))?.requestFocus() }
        count()
    }

    /** "14 Items", or "5 of 14" while the fifth tile has the focus, as on the Roku. */
    private fun count() {
        val total = grid.childCount
        val at = grid.findFocus()?.let { focused -> grid.tiles.indexOfFirst { it === focused } } ?: -1
        itemCount.text = HomeTiles.count(resources, total, at)
    }

    /** News and Emulation don't open anything yet; Live TV opens the TV's own. */
    private fun openShortcut(shortcut: HomeTiles.Shortcut) {
        if (shortcut == HomeTiles.Shortcut.LIVE_TV) opener.liveTv()
    }

    /**
     * The remote's gear opens the menu from anywhere (GearKeyService). Without
     * that service, a remote that sends KEYCODE_SETTINGS opens it from here.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS && event.repeatCount == 0) {
            SettingsMenuActivity.open(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // As the TV's Home app there is nowhere to go back to: Back must not close this screen.
    override fun onBackPressed() {}

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // An app installed while the home screen was away takes its place at the end of the wall.
        fillWall()
        receiver.acquire(this)
        receiver.addListener(this)
        updater.addListener(this)
        updater.checkIfDue()
        handler.postDelayed(recheckUpdates, AppUpdater.RECHECK_INTERVAL_MS)
    }

    override fun onStop() {
        unregisterReceiver(packages)
        handler.removeCallbacks(recheckUpdates)
        updater.removeListener(this)
        receiver.removeListener(this)
        receiver.release(this)
        super.onStop()
    }

    override fun onStatus(status: AirPlayReceiver.Status) {
        // Something that waited for the TV to be free can go ahead now.
        if (status.state == State.READY) updater.checkIfDue()
    }

    override fun onUpdateState(state: AppUpdater.State) {
        val (text, button) = when (state) {
            AppUpdater.State.Idle, AppUpdater.State.Checking ->
                getString(R.string.version_label, com.mangoscreencast.tv.BuildConfig.VERSION_NAME) to null
            AppUpdater.State.UpToDate ->
                getString(R.string.version_up_to_date, com.mangoscreencast.tv.BuildConfig.VERSION_NAME) to null
            is AppUpdater.State.Available -> getString(R.string.update_available, state.release.versionName) to
                getString(R.string.update_now)
            is AppUpdater.State.NeedsPermission -> getString(R.string.update_needs_permission) to
                getString(R.string.update_allow)
            is AppUpdater.State.Downloading -> getString(R.string.update_downloading, state.percent) to null
            is AppUpdater.State.Installing -> getString(R.string.update_installing) to null
            is AppUpdater.State.Failed -> state.message to getString(R.string.update_retry)
        }
        // The home screen says nothing about versions unless there's an update to tell about.
        val quiet = state is AppUpdater.State.Idle || state is AppUpdater.State.Checking || state is AppUpdater.State.UpToDate
        updateArea.visibility = if (quiet) View.GONE else View.VISIBLE
        updateStatus.text = text
        updateButton.visibility = if (button == null) View.GONE else View.VISIBLE
        button?.let { updateButton.text = it }
    }
}
