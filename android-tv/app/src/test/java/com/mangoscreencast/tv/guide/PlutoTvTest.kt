package com.mangoscreencast.tv.guide

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlutoTvTest {
    private val link = "https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv/stitch/hls/channel/545943f1c9f133a519bbac92/master.m3u8" +
        "?advertisingId=&appName=&appVersion=unknown&deviceId=unknown&deviceModel=&sid=&userId="

    /** A channel as the feed has it (2026-10-27), with a few of the parts the guide leaves out. */
    private fun channel(number: Int, name: String, category: String = "Comedy", timelines: String = "", stitched: String? = null) = """
        {"_id": "id$number", "slug": "s$number", "name": "$name", "hash": "#x", "number": $number,
         "summary": "…", "visibility": "everyone", "category": "$category", "isStitched": true,
         "logo": {"path": "https://images.pluto.tv/channels/x/logo.png"},
         "stitched": ${stitched ?: """{"urls": [{"type": "hls", "url": "$link"}], "sessionURL": "https://x/session/undefined.json"}"""},
         "timelines": [$timelines]}
    """

    private fun show(start: String, stop: String, title: String, type: String = "tv", rating: String = "TV-14", released: String = "1990-01-01T00:00:00.000Z") = """
        {"_id": "t", "start": "$start", "stop": "$stop", "title": "$title",
         "episode": {"_id": "e", "number": 11, "description": "…", "duration": 7200000, "genre": "Comedy",
           "clip": {"directors": ["Jim Mallon"], "originalReleaseDate": "$released"},
           "rating": "$rating", "name": "n", "liveBroadcast": false,
           "series": {"_id": "s", "name": "n", "type": "$type", "tile": {"path": "x"}}}}
    """

    @Test
    fun `channels come by number, with their shows and stream link`() {
        val feed = "[" + listOf(
            channel(2533, "CNN HEADLINES", "News + Opinion"),
            channel(430, "MST3K", timelines = listOf(
                show("2026-10-27T04:00:00.000Z", "2026-10-27T06:00:00.000Z", "MST3K: The Beatniks"),
                show("2026-10-27T02:00:00.000Z", "2026-10-27T04:00:00.000Z", "Mystery Science Theater 3000: Moon Zero Two"),
            ).joinToString(",")),
        ).joinToString(",") + "]"

        val channels = PlutoTv.read(StringReader(feed))

        assertEquals(listOf(430, 2533), channels.map { it.number })
        val mst3k = channels[0]
        assertEquals("MST3K", mst3k.name)
        assertEquals("Comedy", mst3k.category)
        assertEquals(link, mst3k.link)
        assertEquals(listOf("Mystery Science Theater 3000: Moon Zero Two", "MST3K: The Beatniks"), mst3k.shows.map { it.title })
        val first = mst3k.shows[0]
        assertEquals(PlutoTv.time("2026-10-27T02:00:00.000Z"), first.start)
        assertEquals(2 * 3600_000L, first.stop - first.start)
        assertEquals("TV-14", first.rating)
        assertFalse(first.film)
        assertNull("only a movie's year shows", first.year)
    }

    @Test
    fun `a movie is marked as one, with its year`() {
        val feed = "[" + channel(70, "Pluto TV Action", "Movies", show(
            "2026-10-27T02:00:00.000Z", "2026-10-27T03:45:00.000Z", "Posse", type = "film", rating = "R",
            released = "1993-05-14T00:00:00.000Z",
        )) + "]"

        val posse = PlutoTv.read(StringReader(feed)).single().shows.single()

        assertTrue(posse.film)
        assertEquals(1993, posse.year)
        assertEquals("R", posse.rating)
    }

    @Test
    fun `Pluto's unlisted and test channels and channels without a stream are left out`() {
        val feed = "[" + listOf(
            channel(0, "Degrassi VIDAA TEST", "Entertainment"),
            channel(0, "Pluto TV Cars", "Reality"),
            channel(1, "Test", "Test Test Test"),
            channel(2, "Testing", "Testing"),
            channel(3, "No stream", stitched = """{"urls": []}"""),
            channel(4, "Kept"),
        ).joinToString(",") + "]"

        assertEquals(listOf("Kept"), PlutoTv.read(StringReader(feed)).map { it.name })
    }

    @Test
    fun `"Not Rated" is no rating`() {
        val feed = "[" + channel(5, "x", timelines = show("2026-10-27T02:00:00.000Z", "2026-10-27T03:00:00.000Z", "x", rating = "Not Rated")) + "]"

        assertNull(PlutoTv.read(StringReader(feed)).single().shows.single().rating)
    }

    @Test
    fun `the stream link names the TV, which Pluto needs before it plays`() {
        val stream = PlutoTv.stream(link, "device-1", "session-2")

        assertTrue(stream.startsWith(link.substringBefore('?') + "?"))
        assertTrue(stream.contains("&deviceId=device-1&"))
        assertTrue(stream.contains("&sid=session-2&"))
        assertTrue(stream.contains("&deviceModel=web&"))
        assertFalse("the feed's own empty values are dropped", stream.contains("deviceId=unknown"))
    }

    @Test
    fun `the guide asks for the hours it shows, in UTC`() {
        val from = PlutoTv.time("2026-10-27T02:00:00.000Z")!!

        assertEquals(
            "https://api.pluto.tv/v2/channels?start=2026-10-27T02:00:00Z&stop=2026-10-27T03:30:00Z",
            PlutoTv.guideUrl(from, from + 90 * 60_000L),
        )
    }
}
