package com.mangoscreencast.tv.games

import android.util.Xml
import androidx.annotation.DrawableRes
import com.mangoscreencast.tv.R
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/**
 * The games on the TV, as RetroArch has them: a playlist per system in
 * RetroArch/playlists (each game's file, its name and the core that plays it),
 * screenshots under RetroArch/thumbnails/<system>/Named_Snaps, and what each
 * game is from the gamelist.xml next to its files (EmulationStation's format).
 */
object GameLibrary {
    /** A game system, as the Games page's top row shows it. */
    data class System(
        /** RetroArch's name for it: its playlist's and its thumbnails' folder. */
        val key: String,
        val title: String,
        @DrawableRes val icon: Int,
        val games: List<Game>,
    )

    data class Game(
        val title: String,
        /** The game's file, for RetroArch's ROM. */
        val path: String,
        /** The core that plays it, for RetroArch's LIBRETRO. */
        val core: String,
        /** Its screenshot, if there is one. */
        val image: File?,
        val developer: String? = null,
        val year: String? = null,
        val players: String? = null,
        val about: String? = null,
    )

    /** The systems, in the order the top row has them, with RetroArch's name for each. */
    val SYSTEMS = listOf(
        Triple("Sega - Mega Drive - Genesis", "Sega Genesis", R.drawable.ic_system_genesis),
        Triple("Sega - Master System - Mark III", "Sega Master System", R.drawable.ic_system_mastersystem),
        Triple("Nintendo - Nintendo Entertainment System", "Nintendo Entertainment System", R.drawable.ic_system_nes),
        Triple("Nintendo - Super Nintendo Entertainment System", "Super Nintendo", R.drawable.ic_system_snes),
        Triple("Nintendo - Nintendo 64", "Nintendo 64", R.drawable.ic_system_n64),
    )

    /** Reads the library under `retroArch` (the TV's RetroArch folder). Systems with no games are left out. */
    fun read(retroArch: File): List<System> = SYSTEMS.mapNotNull { (key, title, icon) ->
        val playlist = File(retroArch, "playlists/$key.lpl")
        if (!playlist.isFile) return@mapNotNull null
        val snaps = File(retroArch, "thumbnails/$key/Named_Snaps")
        val games = runCatching { games(playlist.readText(), snaps) }.getOrDefault(emptyList())
        if (games.isEmpty()) null else System(key, title, icon, games)
    }

    /** A playlist's games, each with what its folder's gamelist.xml says about it. */
    internal fun games(playlist: String, snaps: File): List<Game> {
        val items = JSONObject(playlist).optJSONArray("items") ?: return emptyList()
        val defaultCore = JSONObject(playlist).optString("default_core_path")
        val about = mutableMapOf<String, Map<String, Map<String, String>>>()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val path = item.optString("path").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val label = item.optString("label").ifEmpty { File(path).nameWithoutExtension }
            val core = item.optString("core_path").takeIf { it.isNotEmpty() && it != "DETECT" } ?: defaultCore
            if (core.isEmpty() || core == "DETECT") return@mapNotNull null
            val folder = File(path).parentFile
            val known = folder?.let { dir -> about.getOrPut(dir.path) { gamelist(File(dir, "gamelist.xml")) } }
                ?.get(File(path).name).orEmpty()
            Game(
                title = known["name"] ?: label,
                path = path,
                core = core,
                image = snap(snaps, label, File(path).nameWithoutExtension),
                developer = known["developer"]?.takeIf { it.isNotBlank() },
                year = known["releasedate"]?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                players = known["players"]?.takeIf { it.isNotBlank() && it != "0" },
                about = known["desc"]?.trim()?.takeIf { it.isNotBlank() },
            )
        }.sortedBy { it.title.lowercase() }
    }

    /** RetroArch's screenshot for a game: by its name (as RetroArch writes it), else by its file's. */
    private fun snap(snaps: File, label: String, file: String): File? =
        sequenceOf(label.replace(Regex("[&*/:`<>?\\\\|]"), "_"), file)
            .map { File(snaps, "$it.png") }
            .firstOrNull { it.isFile }

    /** A gamelist.xml's games, by file name: each one's name, developer, players, releasedate and desc. */
    internal fun gamelist(file: File): Map<String, Map<String, String>> {
        if (!file.isFile) return emptyMap()
        return runCatching { gamelist(file.readText()) }.getOrDefault(emptyMap())
    }

    internal fun gamelist(xml: String): Map<String, Map<String, String>> {
        val games = mutableMapOf<String, Map<String, String>>()
        val parser = Xml.newPullParser()
        // Gamelists in the wild have bare ampersands; read them as the text they mean.
        parser.setInput(StringReader(xml.replace(Regex("&(?!amp;|lt;|gt;|quot;|apos;|#)"), "&amp;")))
        var fields: MutableMap<String, String>? = null
        var field: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "game" -> fields = mutableMapOf()
                    fields != null -> field = parser.name
                }
                XmlPullParser.TEXT -> if (fields != null && field != null) {
                    fields[field] = (fields[field] ?: "") + parser.text
                }
                XmlPullParser.END_TAG -> when {
                    parser.name == "game" -> {
                        fields?.get("path")?.let { games[File(it.trim()).name] = fields.mapValues { (_, v) -> v.trim() } }
                        fields = null
                    }
                    else -> field = null
                }
                XmlPullParser.END_DOCUMENT -> return games
            }
        }
    }
}
