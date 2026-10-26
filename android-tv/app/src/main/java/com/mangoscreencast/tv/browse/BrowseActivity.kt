package com.mangoscreencast.tv.browse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.apps.AppLauncher
import com.mangoscreencast.tv.apps.StreamingApp
import com.mangoscreencast.tv.apps.StreamingApps
import com.mangoscreencast.tv.ui.Canvas1440Layout
import com.mangoscreencast.tv.ui.HomeWallpaper
import com.mangoscreencast.tv.ui.LiquidGlassLayout
import com.mangoscreencast.tv.ui.Opener
import com.mangoscreencast.tv.ui.SettingsMenuActivity
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A page from the home screen's menu: What to Watch, Featured Free, Live TV,
 * Sports, Search or Streaming Store. The focused title's art fills the screen
 * and its rows sit on Mango Liquid Glass; OK opens the title in its app.
 */
class BrowseActivity : Activity() {
    private lateinit var page: Page
    private lateinit var opener: Opener
    private lateinit var artBack: ImageView
    private lateinit var artFront: ImageView
    private lateinit var shelf: LiquidGlassLayout
    private lateinit var searchBox: LiquidGlassLayout
    private lateinit var rows: RowsView
    private val handler = Handler(Looper.getMainLooper())
    private var artShown = 0
    private var pendingArt: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Canvas1440Layout.setContentView(this, R.layout.activity_browse)
        page = Page.valueOf(intent.getStringExtra(EXTRA_PAGE) ?: Page.WHAT_TO_WATCH.name)
        opener = Opener(this)
        artBack = findViewById(R.id.art_back)
        artFront = findViewById(R.id.art_front)
        rows = findViewById(R.id.rows)
        findViewById<TextView>(R.id.browse_page).setText(page.title)
        shelf = findViewById<LiquidGlassLayout>(R.id.shelf).apply { frosted() }
        searchBox = findViewById<LiquidGlassLayout>(R.id.search_box).apply { frosted(); blur1440 = 24f; shade = SEARCH_SHADE }

        when (page) {
            Page.SEARCH -> setUpSearch()
            Page.STORE -> showStore()
            else -> rows.show(Catalog.shelves.getValue(page).map { shelf -> row(shelf.title, shelf.shows) })
        }
        // The first tile has the focus, and its art is up before anything moves.
        val first = rows.frames.firstOrNull()?.firstOrNull()
        if (page == Page.SEARCH) findViewById<View>(R.id.search_field).requestFocus() else first?.requestFocus()
    }

    private fun LiquidGlassLayout.frosted() {
        live = true
        cornerRadius = 0f
        blur1440 = SHELF_BLUR
        shade = SHELF_SHADE
        scale = 0.5f
    }

    private fun row(title: String, shows: List<Show>) = RowsView.Row(title, shows.map { show ->
        RowsView.Tile(show.title, fill = { it.findViewById<ImageView>(R.id.tile_image).setImageResource(show.tile) },
            focused = { describe(show) }, clicked = { opener.show(show) })
    })

    /** The info at the top left, and the art behind, for `show`. */
    private fun describe(show: Show) {
        val app = StreamingApps.ALL.firstOrNull { it.name == show.opens }
        val missing = app != null && opener.launcher.entry(app) is AppLauncher.Entry.Missing
        info(
            on = when {
                show.opens == Show.LIVE_TV -> getString(R.string.browse_on, show.on)
                show.opens == "Tubi" -> getString(R.string.browse_free_on, show.on)
                else -> getString(R.string.browse_on, show.on)
            },
            title = show.title,
            meta = show.meta,
            about = show.about,
            action = when {
                show.opens == Show.LIVE_TV -> getString(R.string.browse_action_live)
                missing -> getString(R.string.browse_action_get, show.opens)
                else -> getString(R.string.browse_action_watch, show.opens)
            },
            icon = if (missing) R.drawable.ic_lucide_download else R.drawable.ic_lucide_play,
        )
        art(show.art)
    }

    private fun info(on: String, title: String, meta: String, about: String, action: String, icon: Int) {
        findViewById<TextView>(R.id.info_on).text = on
        findViewById<TextView>(R.id.info_title).text = title
        findViewById<TextView>(R.id.info_meta).apply { text = meta; visibility = if (meta.isBlank()) View.GONE else View.VISIBLE }
        findViewById<TextView>(R.id.info_about).text = about
        findViewById<TextView>(R.id.info_action).text = action
        findViewById<ImageView>(R.id.info_action_icon).setImageResource(icon)
    }

    /**
     * The art fades in over the last, a beat after the focus settles (moving
     * along a row doesn't flash every title), and the glass frosts it once it's up.
     */
    private fun art(res: Int) {
        if (res == artShown) return
        artShown = res
        pendingArt?.let(handler::removeCallbacks)
        val load = Runnable {
            decoder.execute {
                val bitmap = cached(res)
                handler.post { if (artShown == res && !isDestroyed) crossfade(bitmap) }
            }
        }
        pendingArt = load
        if (artBack.drawable == null) load.run() else handler.postDelayed(load, SETTLE_MS)
    }

    private fun crossfade(bitmap: Bitmap) {
        if (artBack.drawable == null) {
            artBack.setImageBitmap(bitmap)
            shelf.refresh(); searchBox.refresh()
            return
        }
        artFront.animate().cancel()
        artFront.setImageBitmap(bitmap)
        artFront.alpha = 0f
        artFront.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            artBack.setImageBitmap(bitmap)
            artFront.alpha = 0f
            artFront.setImageDrawable(null)
            shelf.refresh(); searchBox.refresh()
        }.start()
    }

    private fun cached(res: Int): Bitmap = synchronized(art) {
        art.get(res) ?: BitmapFactory.decodeResource(resources, res).also { art.put(res, it) }
    }

    // Search: a field labelled above, the matches as a row, and the TV's own search for everything else.
    private fun setUpSearch() {
        findViewById<View>(R.id.info).visibility = View.GONE
        findViewById<View>(R.id.search_area).visibility = View.VISIBLE
        val field = findViewById<EditText>(R.id.search_field)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = searchFor(s?.toString().orEmpty())
        })
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) rows.frames.firstOrNull()?.firstOrNull()?.requestFocus()
            action == EditorInfo.IME_ACTION_SEARCH
        }
        findViewById<View>(R.id.search_everywhere).setOnClickListener { opener.search(field.text.toString()) }
        searchFor("")
    }

    private fun searchFor(query: String) {
        val found = Catalog.search(query)
        val suggestions = Catalog.shelves.getValue(Page.WHAT_TO_WATCH).first().shows
        when {
            query.isBlank() -> rows.show(listOf(row(getString(R.string.search_try), suggestions)))
            found.isEmpty() -> rows.show(listOf(row(getString(R.string.search_try), suggestions)),
                message = getString(R.string.search_none, query.trim()))
            else -> rows.show(listOf(row(resources.getQuantityString(R.plurals.search_results, found.size, found.size), found)))
        }
        art((found.firstOrNull() ?: suggestions.first()).art)
    }

    // Streaming Store: every streaming app as its brand tile, and Google Play for the rest.
    private fun showStore() {
        val apps = StreamingApps.ALL.map { app ->
            RowsView.Tile(app.name, fill = { frame -> brandTile(frame, app) }, focused = { describeApp(app) },
                clicked = { opener.app(app) })
        }
        val play = RowsView.Tile(getString(R.string.store_play), fill = { frame ->
            frame.setBackgroundResource(R.drawable.tile_bg_dark)
            val icon = ImageView(this).apply { setImageResource(R.drawable.ic_lucide_store) }
            val size = (40 * resources.displayMetrics.density).roundToInt()
            RowsView.centred(frame, icon, size, size)
        }, focused = {
            info(getString(R.string.store_all_apps), getString(R.string.store_play), "", getString(R.string.store_about_play),
                getString(R.string.store_action_play), R.drawable.ic_lucide_play)
            art(HomeWallpaper.at(System.currentTimeMillis()))
        }, clicked = { opener.store() })
        rows.show(listOf(RowsView.Row(getString(R.string.store_your_apps), apps), RowsView.Row(getString(R.string.store_more), listOf(play))))
    }

    private fun describeApp(app: StreamingApp) {
        val installed = opener.launcher.entry(app) is AppLauncher.Entry.Installed
        info(
            on = getString(if (installed) R.string.store_installed else R.string.store_not_installed),
            title = app.name, meta = "", about = StoreApps.about.getValue(app.name),
            action = getString(if (installed) R.string.store_action_open else R.string.browse_action_get, app.name),
            icon = if (installed) R.drawable.ic_lucide_play else R.drawable.ic_lucide_download,
        )
        art(StoreApps.flagship[app.name]?.let { Catalog.show(it).art } ?: HomeWallpaper.at(System.currentTimeMillis()))
    }

    /** An app's brand tile, as on the home screen's wall, at the browse tiles' size. */
    private fun brandTile(frame: FrameLayout, app: StreamingApp) {
        frame.setBackgroundResource(app.tile)
        val logo = ImageView(this).apply { setImageResource(app.logo); scaleType = ImageView.ScaleType.FIT_CENTER }
        val d = logo.drawable
        val w = frame.layoutParams.width * 0.78f
        val h = frame.layoutParams.height * app.logoHeight
        val s = min(w / d.intrinsicWidth, h / d.intrinsicHeight)
        RowsView.centred(frame, logo, (d.intrinsicWidth * s).roundToInt(), (d.intrinsicHeight * s).roundToInt())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS && event.repeatCount == 0) {
            SettingsMenuActivity.open(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        pendingArt?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAGE = "page"

        /** How long the focus rests on a tile before its art comes up, and how long it takes to fade in. */
        const val SETTLE_MS = 140L
        const val FADE_MS = 220L

        /** The rows' glass frosts hard and is shaded, so headings stay clear over any art. */
        const val SHELF_BLUR = 48f
        const val SHELF_SHADE = 0x66000000
        const val SEARCH_SHADE = 0x40000000

        /** Where the art is decoded, off the main thread. Tests decode in place. */
        internal var decoder: java.util.concurrent.Executor = Executors.newSingleThreadExecutor()
        private val art = LruCache<Int, Bitmap>(4)

        fun open(context: Context, page: Page) {
            context.startActivity(Intent(context, BrowseActivity::class.java).putExtra(EXTRA_PAGE, page.name))
        }
    }
}

/** What each app is for, in a line, and the show whose art stands behind it on the Streaming Store page. */
object StoreApps {
    val about = mapOf(
        "Netflix" to "Netflix series, films and games.",
        "YouTube" to "Videos, music, podcasts and live streams.",
        "HBO Max" to "HBO series, Warner Bros. films and more.",
        "Paramount+" to "CBS shows, Paramount films and live sports.",
        "Peacock" to "NBC shows, Premier League soccer and live sports.",
        "Prime Video" to "Amazon originals, films and Thursday Night Football.",
        "Hulu" to "Next-day TV, FX series and Hulu originals.",
        "Disney+" to "Disney, Pixar, Marvel, Star Wars and The Simpsons.",
        "Tubi" to "Free films and series, with no subscription.",
    )
    val flagship = mapOf(
        "Netflix" to "stranger_things", "HBO Max" to "last_of_us", "Paramount+" to "landman", "Peacock" to "the_office",
        "Prime Video" to "the_boys", "Hulu" to "the_bear", "Disney+" to "andor", "Tubi" to "charade",
    )
}
