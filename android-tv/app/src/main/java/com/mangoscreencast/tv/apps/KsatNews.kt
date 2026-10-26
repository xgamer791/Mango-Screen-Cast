package com.mangoscreencast.tv.apps

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/**
 * The News tile's video: the newest on KSAT 12's YouTube channel (@KSATnews)
 * that runs over 20 minutes. The channel's Videos page lists each video's
 * length, newest first, so no API key is needed.
 */
object KsatNews {
    const val CHANNEL = "UCETE4rKzRRjqM0JaB7TlPpQ"
    const val VIDEOS = "https://www.youtube.com/channel/$CHANNEL/videos"

    /** Longer than this, in seconds: 20 minutes. */
    const val LONGER_THAN_S = 20 * 60

    /** YouTube sends the full page, lengths and all, to a desktop browser. */
    private const val BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    fun watch(video: String) = "https://www.youtube.com/watch?v=$video"

    /** The video's id, or null if YouTube can't be reached or lists none. It waits on the network: not on the main thread. */
    fun latest(): String? = try {
        latest(fetch(VIDEOS))
    } catch (_: Exception) {
        null
    }

    /** From the channel's Videos page: the first, so newest, video longer than [LONGER_THAN_S]. */
    fun latest(page: String): String? {
        val start = page.indexOf(INITIAL_DATA)
        if (start < 0) return null
        val data = JSONTokener(page.substring(start + INITIAL_DATA.length)).nextValue() as? JSONObject ?: return null
        val tabs = data.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return null
        val videos = (0 until tabs.length()).mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
            .firstOrNull { it.optBoolean("selected") } ?: return null
        val found = mutableListOf<Pair<String, Int>>()
        collect(videos, found)
        return found.firstOrNull { (_, seconds) -> seconds > LONGER_THAN_S }?.first
    }

    /** Each video in page order with its length; live and upcoming ones have none and are left out. */
    private fun collect(node: Any?, found: MutableList<Pair<String, Int>>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), found)
            is JSONObject -> {
                val lockup = node.optJSONObject("lockupViewModel")
                val renderer = node.optJSONObject("videoRenderer")
                when {
                    lockup != null -> {
                        val seconds = badge(lockup.opt("contentImage"))?.let(::seconds)
                        if (seconds != null) found += lockup.optString("contentId") to seconds
                    }
                    renderer != null -> {
                        val seconds = renderer.optJSONObject("lengthText")?.optString("simpleText")?.let(::seconds)
                        if (seconds != null) found += renderer.optString("videoId") to seconds
                    }
                    else -> node.keys().forEach { collect(node.opt(it), found) }
                }
            }
        }
    }

    /** The length on a video's thumbnail, "22:02". */
    private fun badge(node: Any?): String? = when (node) {
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { badge(node.opt(it)) }
        is JSONObject -> node.optJSONObject("thumbnailBadgeViewModel")?.optString("text")?.takeIf { LENGTH.matches(it) }
            ?: node.keys().asSequence().firstNotNullOfOrNull { badge(node.opt(it)) }
        else -> null
    }

    /** "1:45:54" in seconds; null for anything that isn't a length. */
    private fun seconds(length: String): Int? =
        if (LENGTH.matches(length)) length.split(':').fold(0) { total, part -> total * 60 + part.toInt() } else null

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", BROWSER)
            setRequestProperty("Accept-Language", "en-US")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val INITIAL_DATA = "var ytInitialData = "
    private val LENGTH = Regex("""\d{1,2}(:\d{2}){1,2}""")
}
