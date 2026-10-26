package com.mangoscreencast.tv.games

import android.app.Application
import com.mangoscreencast.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GameLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val core = "/data/user/0/com.retroarch.ra32/cores/mesen_libretro_android.so"

    private fun playlist(vararg items: String) =
        """{"version": "1.5", "default_core_path": "$core", "items": [${items.joinToString(",")}]}"""

    private fun item(path: String, label: String, core: String = this.core) =
        """{"path": "$path", "label": "$label", "core_path": "$core", "core_name": "Mesen", "crc32": "DETECT", "db_name": "x.lpl"}"""

    @Test
    fun aGamelistsDetailsWithItsBareAmpersands() {
        val games = GameLibrary.gamelist("""<?xml version="1.0"?>
            <gameList>
              <game>
                <path>./blade.nes</path>
                <name>Blade Buster</name>
                <desc>Shoot & dodge, then shoot again.</desc>
                <developer>High Level Challenge</developer>
                <releasedate>20110101T000000</releasedate>
                <players>2</players>
              </game>
            </gameList>""")
        val blade = games.getValue("blade.nes")
        assertEquals("Blade Buster", blade["name"])
        assertEquals("Shoot & dodge, then shoot again.", blade["desc"])
        assertEquals("High Level Challenge", blade["developer"])
        assertEquals("2", blade["players"])
    }

    @Test
    fun aPlaylistsGamesWithWhatTheirGamelistSays() {
        val roms = tmp.newFolder("roms")
        File(roms, "gamelist.xml").writeText("""<gameList><game><path>./b.nes</path><name>Bravo</name>
            <developer>Mojon Twins</developer><releasedate>20160801T000000</releasedate><players>1</players>
            <desc>About Bravo.</desc></game></gameList>""")
        val snaps = tmp.newFolder("snaps")
        File(snaps, "Alpha_ Two.png").writeBytes(byteArrayOf(1))
        File(snaps, "b.png").writeBytes(byteArrayOf(1))
        val games = GameLibrary.games(playlist(
            item("${roms.path}/b.nes", "Bee"),
            item("${roms.path}/a.nes", "Alpha: Two", core = ""),
            item("", "No file"),
        ), snaps)
        // Sorted by name; the one with no file left out; the one with no core takes the playlist's.
        assertEquals(listOf("Alpha: Two", "Bravo"), games.map { it.title })
        val (alpha, bravo) = games
        assertEquals(core, alpha.core)
        assertEquals("Alpha_ Two.png", alpha.image?.name)
        assertNull(alpha.developer)
        assertEquals("Mojon Twins", bravo.developer)
        assertEquals("2016", bravo.year)
        assertEquals("1", bravo.players)
        assertEquals("About Bravo.", bravo.about)
        // Its screenshot by its file's name, when there's none by its label.
        assertEquals("b.png", bravo.image?.name)
    }

    @Test
    fun theSystemsInTheTopRowsOrderAndOnlyThoseWithGames() {
        val root = tmp.newFolder("RetroArch")
        File(root, "playlists").mkdirs()
        fun write(key: String, vararg items: String) = File(root, "playlists/$key.lpl").writeText(playlist(*items))
        write("Nintendo - Nintendo 64", item("/r/z.z64", "Z"))
        write("Sega - Mega Drive - Genesis", item("/r/g.bin", "G"))
        write("Nintendo - Nintendo Entertainment System")
        val systems = GameLibrary.read(root)
        assertEquals(listOf("Sega Genesis", "Nintendo 64"), systems.map { it.title })
        assertEquals(R.drawable.ic_system_genesis, systems.first().icon)
    }
}
