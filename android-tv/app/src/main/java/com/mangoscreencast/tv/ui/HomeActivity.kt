package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ReceiverService
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.airplay.AirPlayReceiver.State
import com.mangoscreencast.tv.apps.AppLauncher
import com.mangoscreencast.tv.apps.StreamingApp
import com.mangoscreencast.tv.apps.StreamingApps
import com.mangoscreencast.tv.update.AppUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var clock: TextView
    private lateinit var itemCount: TextView
    private lateinit var grid: TileGrid

    private val receiver get() = MangoApp.receiver
    private val updater get() = MangoApp.instance.updater
    private val launcher by lazy { AppLauncher(packageManager, AppLauncher.isFireTv(this)) }
    private val handler = Handler(Looper.getMainLooper())
    private val regular by lazy { ResourcesCompat.getFont(this, R.font.montserrat_regular) }
    private val medium by lazy { ResourcesCompat.getFont(this, R.font.montserrat_medium) }

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

    /** The clock moves on every minute, and when the TV's time or time zone changes. */
    private val ticks = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showTime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drawn at 1440p, whatever size the TV draws its screens at.
        Canvas1440Layout.setContentView(this, R.layout.activity_home)
        updateArea = findViewById(R.id.update_area)
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)
        clock = findViewById(R.id.clock)
        itemCount = findViewById(R.id.item_count)
        grid = findViewById(R.id.tile_grid)
        updateButton.setOnClickListener {
            when (updater.state) {
                is AppUpdater.State.Failed -> updater.checkNow()
                else -> updater.install()
            }
        }

        // The focused item's words are a weight heavier on its white bar, as on the Roku; Left from
        // the wall comes back to the item it was entered from.
        val menu = findViewById<ViewGroup>(R.id.menu)
        for (i in 0 until menu.childCount) {
            menu.getChildAt(i).setOnFocusChangeListener { item, focused ->
                (item as TextView).typeface = if (focused) medium else regular
                if (focused) cameFrom = item.id
            }
        }
        findViewById<View>(R.id.menu_live_tv).setOnClickListener { openLiveTv() }
        findViewById<View>(R.id.menu_search).setOnClickListener { start(launcher.search(), R.string.search_missing) }
        findViewById<View>(R.id.menu_store).setOnClickListener { start(launcher.store(), R.string.store_missing) }
        findViewById<View>(R.id.menu_settings).setOnClickListener { SettingsMenuActivity.open(this) }

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
            app = ::openApp,
            other = { launcher.start(this, it.launch) },
        ))
        leadLeftTo(cameFrom)
        focused?.let { grid.getChildAt(it.coerceAtMost(grid.childCount - 1))?.requestFocus() }
        itemCount.text = resources.getQuantityString(R.plurals.home_item_count, grid.childCount, grid.childCount)
    }

    /** News and Emulation don't open anything yet; Live TV opens the TV's own. */
    private fun openShortcut(shortcut: HomeTiles.Shortcut) {
        if (shortcut == HomeTiles.Shortcut.LIVE_TV) openLiveTv()
    }

    private fun openLiveTv() = start(launcher.liveTv(), R.string.live_tv_missing)

    private fun start(intent: Intent?, missing: Int) {
        if (intent == null || !launcher.start(this, intent)) Toast.makeText(this, missing, Toast.LENGTH_LONG).show()
    }

    private fun showTime() {
        val pattern = if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm a"
        clock.text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date()).lowercase(Locale.getDefault())
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

    /** Starts the app; one the TV doesn't have opens its app store page, and says so. */
    private fun openApp(app: StreamingApp) {
        val entry = launcher.entry(app)
        val note = when {
            !launcher.open(this, entry) -> getString(R.string.app_open_failed, app.name)
            entry is AppLauncher.Entry.Missing -> getString(R.string.app_not_installed, app.name)
            else -> return
        }
        Toast.makeText(this, note, Toast.LENGTH_LONG).show()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, ticks, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        showTime()
        // An app installed while the home screen was away takes its place at the end of the wall.
        fillWall()
        receiver.acquire(this)
        receiver.addListener(this)
        updater.addListener(this)
        updater.checkIfDue()
        handler.postDelayed(recheckUpdates, AppUpdater.RECHECK_INTERVAL_MS)
    }

    override fun onStop() {
        unregisterReceiver(ticks)
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
