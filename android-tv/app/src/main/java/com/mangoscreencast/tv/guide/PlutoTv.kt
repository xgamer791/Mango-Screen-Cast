package com.mangoscreencast.tv.guide

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pluto TV's free live channels and what's on them, from the guide feed its
 * web player reads (api.pluto.tv/v2/channels). No account or key: for the
 * hours asked for, the feed lists every channel's number, name and shows, and
 * a stream link that plays once it names a device and a session.
 *
 * The feed is about 2.5 MB an hour of listings for 441 channels, so it's read
 * as it arrives, keeping only what the guide shows.
 */
object PlutoTv {
    const val GUIDE = "https://api.pluto.tv/v2/channels"

    /** A show in the listings. Times are milliseconds since 1970. */
    data class Show(
        val title: String,
        val start: Long,
        val stop: Long,
        /** A movie: the guide puts its title in quotes and its year after it, as Prevue did. */
        val film: Boolean = false,
        val year: Int? = null,
        /** "TV-14", "PG-13" and so on; null when Pluto has none, or "Not Rated". */
        val rating: String? = null,
    )

    data class Channel(
        val id: String,
        val number: Int,
        val name: String,
        val category: String,
        /** Pluto's stream link, before [stream] fills in the device and session. */
        val link: String,
        val shows: List<Show>,
    )

    /** The feed for the shows on between `from` and `to`, including any already on at `from`. */
    fun guideUrl(from: Long, to: Long) = "$GUIDE?start=${iso(from)}&stop=${iso(to)}"

    /** Every channel and its shows between `from` and `to`, by number. It waits on the network: not on the main thread. */
    fun channels(from: Long, to: Long): List<Channel> {
        val connection = URL(guideUrl(from, to)).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) throw java.io.IOException("Pluto TV's guide answered ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use(::read)
        } finally {
            connection.disconnect()
        }
    }

    /** The channels in the feed, by number, leaving out Pluto's unlisted and test channels and any without a stream. */
    fun read(source: Reader): List<Channel> {
        val channels = mutableListOf<Channel>()
        JsonReader(source).use { json ->
            json.beginArray()
            while (json.hasNext()) channel(json)?.let(channels::add)
            json.endArray()
        }
        return channels.sortedBy { it.number }
    }

    /**
     * `link` with this TV named in it. Pluto turns the link away (400) while
     * its deviceModel is empty, as the feed sends it; `device` stays the same
     * from day to day, `session` is new each time the guide opens.
     */
    fun stream(link: String, device: String, session: String) = link.substringBefore('?') +
        "?advertisingId=&appName=web&appVersion=unknown&architecture=&buildVersion=&clientTime=0" +
        "&deviceDNT=0&deviceId=$device&deviceMake=Chrome&deviceModel=web&deviceType=web&deviceVersion=unknown" +
        "&includeExtendedEvents=false&serverSideAds=true&sid=$session&userId="

    private fun channel(json: JsonReader): Channel? {
        var id = ""
        var number = 0
        var name = ""
        var category = ""
        var link: String? = null
        var shows = emptyList<Show>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "_id" -> id = string(json)
                "number" -> number = if (json.peek() == JsonToken.NUMBER) json.nextInt() else { json.skipValue(); 0 }
                "name" -> name = string(json)
                "category" -> category = string(json)
                "stitched" -> link = hls(json)
                "timelines" -> shows = shows(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        // Channel 0 is Pluto's: duplicates of listed channels, and test feeds ("Degrassi VIDAA TEST").
        if (link == null || number <= 0 || category.startsWith("Test")) return null
        return Channel(id, number, name.trim(), category, link!!, shows.sortedBy { it.start })
    }

    /** The HLS link in a channel's "stitched": {"urls": [{"type": "hls", "url": …}]}. */
    private fun hls(json: JsonReader): String? {
        var found: String? = null
        if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); return null }
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() != "urls" || json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); continue }
            json.beginArray()
            while (json.hasNext()) {
                var type = ""
                var url = ""
                json.beginObject()
                while (json.hasNext()) when (json.nextName()) {
                    "type" -> type = string(json)
                    "url" -> url = string(json)
                    else -> json.skipValue()
                }
                json.endObject()
                if (found == null && type == "hls" && url.isNotEmpty()) found = url
            }
            json.endArray()
        }
        json.endObject()
        return found
    }

    private fun shows(json: JsonReader): List<Show> {
        if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); return emptyList() }
        val shows = mutableListOf<Show>()
        json.beginArray()
        while (json.hasNext()) {
            var title = ""
            var start: Long? = null
            var stop: Long? = null
            var episode = Episode()
            json.beginObject()
            while (json.hasNext()) when (json.nextName()) {
                "title" -> title = string(json)
                "start" -> start = time(string(json))
                "stop" -> stop = time(string(json))
                "episode" -> episode = episode(json)
                else -> json.skipValue()
            }
            json.endObject()
            if (start != null && stop != null && stop > start) {
                shows += Show(title.trim(), start, stop, episode.film, episode.year.takeIf { episode.film }, episode.rating)
            }
        }
        json.endArray()
        return shows
    }

    private class Episode(val film: Boolean = false, val year: Int? = null, val rating: String? = null)

    /** From a show's "episode": whether it's a film ("series": {"type": "film"}), its year, its rating. */
    private fun episode(json: JsonReader): Episode {
        if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); return Episode() }
        var film = false
        var year: Int? = null
        var rating: String? = null
        json.beginObject()
        while (json.hasNext()) when (json.nextName()) {
            "rating" -> rating = string(json).trim().takeIf { it.isNotEmpty() && it.lowercase() !in UNRATED }
            "series" -> film = inside(json, "type") == "film"
            "clip" -> year = inside(json, "originalReleaseDate")?.take(4)?.toIntOrNull()?.takeIf { it > 1880 }
            else -> json.skipValue()
        }
        json.endObject()
        return Episode(film, year, rating)
    }

    /** The string `key` in the object next in `json`, skipping the rest of it. */
    private fun inside(json: JsonReader, key: String): String? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); return null }
        var value: String? = null
        json.beginObject()
        while (json.hasNext()) if (json.nextName() == key) value = string(json) else json.skipValue()
        json.endObject()
        return value
    }

    private fun string(json: JsonReader): String = when (json.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> json.nextString()
        else -> { json.skipValue(); "" }
    }

    /** Ratings Pluto gives shows that have none ("no rating" on the placeholder listings it sends for past hours). */
    private val UNRATED = setOf("not rated", "nr", "n/a", "unrated", "no rating")

    private fun format() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** "2026-10-27T02:00:00.000Z" in milliseconds; the feed's times are all UTC. */
    fun time(text: String): Long? = try {
        format().parse(text.take(19))?.time
    } catch (_: Exception) {
        null
    }

    private fun iso(time: Long): String = format().format(time) + "Z"
}
