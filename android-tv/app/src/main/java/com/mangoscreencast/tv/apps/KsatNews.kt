package com.mangoscreencast.tv.apps

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/**
 * The News tile's video: KSAT 12's latest newscast on its YouTube channel
 * (@KSATnews) that runs over 20 minutes. The channel's Videos page lists each
 * video's title and length, newest first, so no API key is needed.
 *
 * The channel also posts clips, church services, sports shows, interviews,
 * press conferences and weather streams, some over 20 minutes. Only a
 * newscast's title is one of its shows' names followed by the day it aired:
 * "Good Morning San Antonio 6 a.m. : Oct 25, 2026", "KSAT News at Noon : …",
 * "KSAT 12 News at 5:30 p.m. Sunday : …", "The Nightbeat @ 9 : …",
 * "KSAT 12 Nightbeat : …". Three weeks of the channel (930 videos) held 166
 * such newscasts over 20 minutes and nothing else that matched.
 */
object KsatNews {
    const val CHANNEL = "UCETE4rKzRRjqM0JaB7TlPpQ"
    const val VIDEOS = "https://www.youtube.com/channel/$CHANNEL/videos"

    /** Longer than this, in seconds: 20 minutes. */
    const val LONGER_THAN_S = 20 * 60

    /**
     * Pages of the channel's videos read before giving up, 30 a page: up to 22
     * videos in a row came between two newscasts in those three weeks.
     */
    const val PAGES = 4

    /** A newscast's title: its show's name, then " : " and the day it aired. */
    val NEWSCAST = Regex(
        """^(Good Morning San Antonio|KSAT (12 )?News|(The |KSAT 12 )?Nightbeat)\b.*\s:\s""" +
            """(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.? \d{1,2}, \d{4}$""",
        RegexOption.IGNORE_CASE,
    )

    /** YouTube sends the full page, titles and lengths and all, to a desktop browser. */
    private const val BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** Where the page's "show more" asks for the next 30 videos. */
    private const val MORE = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

    data class Video(val id: String, val title: String, val seconds: Int)

    /** A page of videos, newest first, and the key to the page after it. */
    data class Page(val videos: List<Video>, val next: String?)

    fun watch(video: String) = "https://www.youtube.com/watch?v=$video"

    /** The newscast's video id, or null if YouTube can't be reached or lists none. It waits on the network: not on the main thread. */
    fun latest(): String? = try {
        val html = fetch(VIDEOS)
        val version = CLIENT_VERSION.find(html)?.groupValues?.get(1)
        var page = firstPage(html)
        var read = 1
        var found: String? = null
        while (page != null) {
            found = newscast(page.videos)
            val next = page.next
            if (found != null || next == null || version == null || read == PAGES) break
            page = nextPage(more(version, next))
            read++
        }
        found
    } catch (_: Exception) {
        null
    }

    /** From the channel's Videos page: its latest newscast over 20 minutes. */
    fun latest(html: String): String? = firstPage(html)?.let { newscast(it.videos) }

    /** The first, so newest, of `videos` that is a newscast over [LONGER_THAN_S]. */
    fun newscast(videos: List<Video>): String? =
        videos.firstOrNull { it.seconds > LONGER_THAN_S && NEWSCAST.matches(it.title.trim()) }?.id

    /** The Videos page's videos, if the page is KSAT 12's and opened on its Videos tab. */
    fun firstPage(html: String): Page? {
        val start = html.indexOf(INITIAL_DATA)
        if (start < 0) return null
        val data = JSONTokener(html.substring(start + INITIAL_DATA.length)).nextValue() as? JSONObject ?: return null
        val channel = data.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")?.optString("externalId")
        if (channel != CHANNEL) return null
        val tabs = data.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return null
        val videos = (0 until tabs.length()).mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
            .firstOrNull { it.optBoolean("selected") } ?: return null
        return page(videos)
    }

    /** The videos "show more" brought: the reply to [more]. */
    fun nextPage(json: String): Page? = (JSONTokener(json).nextValue() as? JSONObject)?.let(::page)

    private fun page(node: Any): Page {
        val videos = mutableListOf<Video>()
        var next: String? = null
        fun collect(node: Any?) {
            when (node) {
                is JSONArray -> {
                    val before = videos.size
                    for (i in 0 until node.length()) collect(node.opt(i))
                    // The key to the next page ends the list the videos are in.
                    if (videos.size > before && next == null) {
                        next = (0 until node.length()).firstNotNullOfOrNull { continuation(node.optJSONObject(it)) }
                    }
                }
                is JSONObject -> {
                    val lockup = node.optJSONObject("lockupViewModel")
                    val renderer = node.optJSONObject("videoRenderer")
                    when {
                        lockup != null -> video(
                            lockup.optString("contentId"),
                            lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                                ?.optJSONObject("title")?.optString("content"),
                            badge(lockup.opt("contentImage")),
                        )?.let(videos::add)
                        renderer != null -> video(
                            renderer.optString("videoId"),
                            renderer.optJSONObject("title")?.let { title ->
                                title.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: title.optString("simpleText")
                            },
                            renderer.optJSONObject("lengthText")?.optString("simpleText"),
                        )?.let(videos::add)
                        else -> node.keys().forEach { collect(node.opt(it)) }
                    }
                }
            }
        }
        collect(node)
        return Page(videos, next)
    }

    /** A video with its title and length; live and upcoming ones have no length, and are left out. */
    private fun video(id: String, title: String?, length: String?): Video? {
        val seconds = length?.let(::seconds) ?: return null
        if (id.isEmpty() || title.isNullOrEmpty()) return null
        return Video(id, title, seconds)
    }

    private fun continuation(item: JSONObject?): String? =
        item?.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotEmpty() }

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

    private fun fetch(url: String): String = read(connect(url))

    /** The 30 videos after `token`, as the page's own "show more" asks for them. */
    private fun more(version: String, token: String): String {
        val client = JSONObject().put("clientName", "WEB").put("clientVersion", version).put("hl", "en").put("gl", "US")
        val body = JSONObject().put("context", JSONObject().put("client", client)).put("continuation", token)
        val connection = connect(MORE).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(connection)
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", BROWSER)
        setRequestProperty("Accept-Language", "en-US")
    }

    private fun read(connection: HttpURLConnection): String {
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val INITIAL_DATA = "var ytInitialData = "
    private val CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION":"([^"]+)"""")
    private val LENGTH = Regex("""\d{1,2}(:\d{2}){1,2}""")
}
