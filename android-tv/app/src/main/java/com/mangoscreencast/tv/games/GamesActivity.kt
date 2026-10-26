package com.mangoscreencast.tv.games

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ui.Canvas1440Layout
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Games page, opened by Home's Emulation tile: the TV's games as the PS5
 * home lays out its own. The game systems along the top, the selected one's
 * games along the bottom, and the focused one's picture filling the screen
 * behind its name. Left and Right move along a row, Down goes to the games and
 * Up back to the systems, OK plays the game in RetroArch, Back goes up a row
 * and then home.
 */
class GamesActivity : Activity() {
    private lateinit var hero: HeroView
    private lateinit var systems: SystemRow
    private lateinit var games: GameRow
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var about: TextView
    private lateinit var message: TextView

    private var library: List<GameLibrary.System> = emptyList()
    private var onGames = false
    /** The game each system was left on, so coming back to it finds it again. */
    private val left = mutableMapOf<String, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pictures = object : LruCache<String, Bitmap>(PICTURES_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    /** Bumped on every new selection, so pictures for an old one are dropped when they arrive. */
    private var shown = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Canvas1440Layout.setContentView(this, R.layout.activity_games)
        hero = findViewById(R.id.games_hero)
        systems = findViewById(R.id.games_systems)
        games = findViewById(R.id.games_row)
        title = findViewById(R.id.games_title)
        meta = findViewById(R.id.games_meta)
        about = findViewById(R.id.games_about)
        message = findViewById(R.id.games_message)
        if (!canRead()) return say(getString(R.string.games_no_access))
        worker.execute {
            val found = GameLibrary.read(File(RetroArchLaunch.SDCARD, "RetroArch"))
            handler.post { if (!isDestroyed) show(found) }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Android 11 and later let an app read RetroArch's folder only with all files access. */
    private fun canRead() = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun say(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
        for (id in intArrayOf(R.id.games_info, R.id.games_row_label)) findViewById<View>(id).visibility = View.INVISIBLE
    }

    private fun show(found: List<GameLibrary.System>) {
        library = found
        if (found.isEmpty()) return say(getString(R.string.games_none))
        systems.show(found)
        system(0)
    }

    /** The selected system: its games along the bottom, and it as the focus. */
    private fun system(index: Int) {
        val system = library[index]
        val at = left[system.key] ?: 0
        games.show(system.games.size, at)
        val w = (GameRow.CARD_W_DP * resources.displayMetrics.density).roundToInt()
        val h = (GameRow.CARD_H_DP * resources.displayMetrics.density).roundToInt()
        val token = ++shown
        system.games.forEachIndexed { i, game ->
            cached(game, "card:$w:$h")?.let { games.picture(i, it) } ?: worker.execute {
                val card = picture(game, "card:$w:$h") { GameArt.card(it, w, h) }
                handler.post { if (token == shown || systems.selected == index) games.picture(i, card) }
            }
        }
        describe(system)
    }

    private fun describe(system: GameLibrary.System) {
        title.text = system.title
        meta.text = resources.getQuantityString(R.plurals.games_count, system.games.size, system.games.size)
        about.text = ""
        art(system.games.firstOrNull())
    }

    private fun describe(game: GameLibrary.Game) {
        title.text = game.title
        val players = game.players?.let { if (it == "1") getString(R.string.games_one_player) else getString(R.string.games_players, it) }
        meta.text = listOfNotNull(game.developer, game.year, players).joinToString(" · ")
            .ifEmpty { library[systems.selected].title }
        about.text = game.about.orEmpty()
        art(game)
    }

    /** The hero: `game`'s picture, as it is (HeroView scales it). */
    private fun art(game: GameLibrary.Game?) {
        if (game == null) return hero.show(null)
        val token = ++shown
        cached(game, "hero")?.let { return hero.show(it) }
        worker.execute {
            val picture = picture(game, "hero") { it }
            handler.post { if (token == shown) hero.show(picture) }
        }
    }

    /** `game`'s picture as `kind`, if it's been made already. */
    private fun cached(game: GameLibrary.Game, kind: String): Bitmap? = pictures.get("$kind:${game.path}")

    /** `game`'s picture as `kind` (read from its file and made by `make`), cached. On the worker. */
    private fun picture(game: GameLibrary.Game, kind: String, make: (Bitmap) -> Bitmap): Bitmap? {
        val key = "$kind:${game.path}"
        pictures.get(key)?.let { return it }
        val made = GameArt.read(game.image)?.let(make) ?: return null
        pictures.put(key, made)
        return made
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (library.isEmpty()) return super.onKeyDown(keyCode, event)
        val moved = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!onGames) toGames() else false
            KeyEvent.KEYCODE_DPAD_UP -> if (onGames) toSystems() else false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) { if (onGames) play() else toGames() }
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (moved) click(keyCode, event.repeatCount > 0)
        return true
    }

    @Deprecated("Back goes up from the games to the systems first.")
    override fun onBackPressed() {
        if (onGames && library.isNotEmpty()) toSystems() else super.onBackPressed()
    }

    private fun step(by: Int): Boolean {
        if (!onGames) {
            if (!systems.move(by)) return false
            system(systems.selected)
        } else {
            if (!games.move(by)) return false
            left[library[systems.selected].key] = games.selected
            describe(library[systems.selected].games[games.selected])
        }
        return true
    }

    private fun toGames(): Boolean {
        val system = library[systems.selected]
        if (system.games.isEmpty()) return false
        onGames = true
        systems.active = false
        games.active = true
        describe(system.games[games.selected])
        return true
    }

    private fun toSystems(): Boolean {
        onGames = false
        games.active = false
        systems.active = true
        describe(library[systems.selected])
        return true
    }

    private fun play() {
        val game = library[systems.selected].games[games.selected]
        val intent = RetroArchLaunch.intent(this, game)
        if (intent == null) {
            Toast.makeText(this, R.string.emulation_missing, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.emulation_missing, Toast.LENGTH_LONG).show()
        }
    }

    /** The TV's click for a move, as everywhere else (the rows move their own selection, so Android doesn't). */
    private fun click(keyCode: Int, repeating: Boolean) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            else -> View.FOCUS_DOWN
        }
        val sound = if (Build.VERSION.SDK_INT >= 33) SoundEffectConstants.getConstantForFocusDirection(direction, repeating)
            else SoundEffectConstants.getContantForFocusDirection(direction)
        window.decorView.playSoundEffect(sound)
    }

    private companion object {
        /** Pictures kept in memory: the cards and heroes of a few systems. */
        const val PICTURES_KB = 48 * 1024
    }
}
