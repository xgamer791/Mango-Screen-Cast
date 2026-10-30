package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * The home screen, laid out as the Roku TV home screen: the menu's icons on
 * the left (Left from the wall opens it, see HomeMenu), the wall of tiles
 * four across (News, Emulation and Live TV on Liquid Glass, then the
 * streaming apps, then the TV's other apps) with the focused tile's name over
 * it, the clock and the number of tiles top right.
 */
class HomeActivity : Activity(), AirPlayReceiver.Listener, AppUpdater.Listener {
    private lateinit var updateArea: View
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var itemCount: TextView
    private lateinit var tileName: TextView
    private lateinit var wallpaper: HomeWallpaper
    private lateinit var page: View
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

    /** The wall's tile focused last: Right from the menu goes back to it. */
    private var lastTile = 0

    /** Whether there's an update to tell about under the menu (shown while the menu is open). */
    private var updateToTell = false

    /** The TV's other apps on the wall now, so it's filled again only when they change. */
    private var othersShown: List<String>? = null

    /** Everything on the page but the wallpaper, which Back twice hides. */
    private lateinit var overWallpaper: List<View>

    /** When Back was last pressed: pressed again within [BACK_TWICE_MS], only the wallpaper shows. */
    private var lastBack = 0L

    /** Whether only the wallpaper shows; the next key brings the rest back and does nothing else. */
    private var wallpaperOnly = false

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
        tileName = findViewById(R.id.tile_name)
        // A new wallpaper fades in; the glass tiles then frost it in turn.
        wallpaper = HomeWallpaper(findViewById(R.id.wallpaper), findViewById(R.id.wallpaper_next), changed = {
            grid.tiles.forEach { (it as? LiquidGlassLayout)?.refresh() }
        })
        page = window.decorView
        grid = findViewById(R.id.tile_grid)
        val layout = findViewById<View>(R.id.wallpaper).parent as ViewGroup
        overWallpaper = (0 until layout.childCount).map(layout::getChildAt)
            .filter { it.id != R.id.wallpaper && it.id != R.id.wallpaper_next }
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

        // Top right: how many tiles there are, or which of them has the focus. The menu is open while it
        // has the focus; over the wall, the name of the tile focused last.
        findViewById<View>(android.R.id.content).viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            count()
            focusMoved(now)
        }

        fillWall()
        // As on the Roku, Home opens on the wall's first tile with the menu closed. The page isn't in its
        // window yet, so Android doesn't tell the focus listener: set what follows the focus here.
        val first = grid.getChildAt(0) ?: findViewById(R.id.menu_home)
        first.requestFocus()
        focusMoved(first)
        ReceiverService.sync(this)
    }

    /** The wall's tiles, and how many there are; again only if the TV's other apps have changed. */
    private fun fillWall() {
        val own = StreamingApps.ALL.flatMap { it.packages }.toSet() + AppLauncher.LIVE_TV_PACKAGES + AppLauncher.EMULATION_PACKAGES +
            AppLauncher.NOT_ON_WALL + packageName
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

    private fun focusMoved(now: View?) {
        val open = HomeMenu.has(page, now)
        HomeMenu.show(page, open)
        updateArea.visibility = if (updateToTell && open) View.VISIBLE else View.GONE
        val at = now?.let { grid.indexOfChild(it) } ?: -1
        if (at >= 0) {
            lastTile = at
            tileName.text = now?.contentDescription
        }
    }

    /** Right from the menu goes back to the tile the wall was left from, as on the Roku. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // With only the wallpaper showing, a key (its press and its release) just brings the rest back.
        if (wallpaperOnly) {
            if (event.action == KeyEvent.ACTION_UP) showWallpaperOnly(false)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) lastBack = 0L
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && HomeMenu.has(page, currentFocus)) {
            val tile = grid.getChildAt(lastTile.coerceAtMost(grid.childCount - 1))
            if (tile != null) {
                if (event.action == KeyEvent.ACTION_DOWN) tile.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** "14 Items", or "5 of 14" while the fifth tile has the focus, as on the Roku. */
    private fun count() {
        val total = grid.childCount
        val at = grid.findFocus()?.let { focused -> grid.tiles.indexOfFirst { it === focused } } ?: -1
        itemCount.text = HomeTiles.count(resources, total, at)
    }

    /** News plays KSAT 12's latest newscast on YouTube; Emulation opens RetroArch, and Live TV the TV's own. */
    private fun openShortcut(shortcut: HomeTiles.Shortcut) {
        when (shortcut) {
            HomeTiles.Shortcut.EMULATION -> opener.emulation()
            HomeTiles.Shortcut.LIVE_TV -> opener.liveTv()
            HomeTiles.Shortcut.NEWS -> opener.news()
        }
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

    // As the TV's Home app there is nowhere to go back to: Back must not close this screen. Twice in a
    // row, it hides everything but the wallpaper.
    override fun onBackPressed() {
        val now = SystemClock.uptimeMillis()
        if (lastBack != 0L && now - lastBack <= BACK_TWICE_MS) {
            lastBack = 0L
            showWallpaperOnly(true)
        } else {
            lastBack = now
        }
    }

    /** The Home button, while only the wallpaper shows, brings the rest back too. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (wallpaperOnly) showWallpaperOnly(false)
    }

    /** Fades everything but the wallpaper out (`only`) or back in; at once if not `fade`. */
    private fun showWallpaperOnly(only: Boolean, fade: Boolean = true) {
        wallpaperOnly = only
        val alpha = if (only) 0f else 1f
        for (view in overWallpaper) {
            view.animate().cancel()
            if (fade) view.animate().alpha(alpha).setDuration(WALLPAPER_ONLY_FADE_MS) else view.alpha = alpha
        }
    }

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
        wallpaper.start()
    }

    override fun onStop() {
        // An app opened from the remote's own buttons comes back to the whole page.
        if (wallpaperOnly) showWallpaperOnly(false, fade = false)
        unregisterReceiver(packages)
        handler.removeCallbacks(recheckUpdates)
        wallpaper.stop()
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
        // The home screen says nothing about versions unless there's an update to tell about, and then
        // only under the open menu, as Roku shows its offers there.
        val quiet = state is AppUpdater.State.Idle || state is AppUpdater.State.Checking || state is AppUpdater.State.UpToDate
        updateToTell = !quiet
        updateArea.visibility = if (updateToTell && HomeMenu.has(page, currentFocus)) View.VISIBLE else View.GONE
        updateStatus.text = text
        updateButton.visibility = if (button == null) View.GONE else View.VISIBLE
        button?.let { updateButton.text = it }
    }

    private companion object {
        /** How soon the second Back must follow the first to show the wallpaper alone. */
        const val BACK_TWICE_MS = 1500L
        const val WALLPAPER_ONLY_FADE_MS = 300L
    }
}
