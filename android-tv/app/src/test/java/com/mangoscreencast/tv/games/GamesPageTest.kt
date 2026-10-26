package com.mangoscreencast.tv.games

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.mangoscreencast.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class GamesPageTest {
    /** A 256 x 224 picture of 1-pixel stripes, so every pixel of it can be told apart. */
    private fun stripes(w: Int = 256, h: Int = 224) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) for (x in 0 until w) setPixel(x, y, if ((x + y) % 2 == 0) Color.WHITE else Color.RED)
    }

    @Test
    fun theHeroDrawsPixelArtWithEveryPixelTheSameWholeSize() {
        val hero = HeroView(RuntimeEnvironment.getApplication())
        hero.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        hero.layout(0, 0, 1920, 1080)
        hero.show(stripes())
        val shot = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        hero.draw(Canvas(shot))
        // 224 rows into 1080: 4 a row (the largest even number), right-aligned, centred on even pixels.
        val k = 4
        val left = 1920 - 256 * k
        val top = (1080 - 224 * k) / 2 / 2 * 2
        // Along a row near the right (past the fade), each picture pixel is k screen pixels of one colour.
        val y = top + 100 * k + 1
        for (px in 200 until 256) {
            val colours = (0 until k).map { shot.getPixel(left + px * k + it, y) }.toSet()
            assertEquals("pixel $px is one colour across its $k columns", 1, colours.size)
        }
    }

    @Test
    fun aCardIsExactlyItsSizeWithItsPixelsSharp() {
        val card = GameArt.card(stripes(), 341, 256)
        assertEquals(341, card.width)
        assertEquals(256, card.height)
    }

    @Test
    fun aGameStartsInRetroArchWithItsCoreAndSettings() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.retroarch.ra32"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.retroarch.ra32"
                dataDir = "/data/user/0/com.retroarch.ra32"
                sourceDir = "/data/app/ra32/base.apk"
            }
        })
        val game = GameLibrary.Game("Alter Ego", "/storage/emulated/0/ROMs/nes/alterego.nes",
            "/data/user/0/com.retroarch.ra32/cores/mesen_libretro_android.so", null)
        val intent = RetroArchLaunch.intent(app, game)!!
        assertEquals(ComponentName("com.retroarch.ra32", RetroArchLaunch.ACTIVITY), intent.component)
        assertEquals(game.path, intent.getStringExtra("ROM"))
        assertEquals(game.core, intent.getStringExtra("LIBRETRO"))
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.ra32/files/retroarch.cfg", intent.getStringExtra("CONFIGFILE"))
        assertEquals("/data/user/0/com.retroarch.ra32", intent.getStringExtra("DATADIR"))
        assertEquals("/data/app/ra32/base.apk", intent.getStringExtra("APK"))
        assertEquals("/storage/emulated/0", intent.getStringExtra("SDCARD"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    @Test
    fun thePageLaidOut() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_Mango)).inflate(R.layout.activity_games, null)
        activity.setContentView(root)
        val systems = GameLibrary.SYSTEMS.map { (key, title, icon) ->
            GameLibrary.System(key, title, icon, (1..8).map { GameLibrary.Game("Game $it", "/r/$it", "/c", null) })
        }
        root.findViewById<SystemRow>(R.id.games_systems).show(systems, select = 0)
        root.findViewById<GameRow>(R.id.games_row).apply {
            show(8)
            (0 until 8).forEach { picture(it, stripes()) }
            active = true
        }
        root.findViewById<SystemRow>(R.id.games_systems).active = false
        root.findViewById<HeroView>(R.id.games_hero).show(stripes())
        root.findViewById<TextView>(R.id.games_title).text = "Skipp and Friends: Unexpected Journey"
        root.findViewById<TextView>(R.id.games_meta).text = "Mukunda Johnson · 2012 · 1 player"
        root.findViewById<TextView>(R.id.games_about).text = "A platformer on the Super Nintendo, made by a small team long after the console's day, " +
            "with music written for its sound chip."
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        val shot = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(shot))
        File("build/layout-previews").apply { mkdirs() }.resolve("games.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The words end above the games' heading, which ends above the cards.
        val info = root.findViewById<View>(R.id.games_info)
        val label = root.findViewById<View>(R.id.games_row_label)
        val row = root.findViewById<View>(R.id.games_row)
        assertTrue("info ${info.bottom} over the heading ${label.top}", info.bottom <= label.top)
        assertTrue(label.bottom <= row.top)
        assertTrue("the cards end ${row.bottom} at least 48 dp off the bottom", row.bottom <= 1080 - 48 * 2)
    }
}
