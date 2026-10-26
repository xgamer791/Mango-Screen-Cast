package com.mangoscreencast.tv.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KsatNewsTest {
    /** A video as the channel's Videos page has it, with only the parts read here. */
    private fun lockup(id: String, title: String, length: String) = """
        {"richItemRenderer":{"content":{"lockupViewModel":{
          "contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":
            {"badges":[{"thumbnailBadgeViewModel":{"text":"$length","animationActivationTargetId":"$id"}}]}}]}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"}}},
          "contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}}}}
    """

    private fun more(token: String) =
        """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""

    private fun page(vararg videos: String, channel: String = KsatNews.CHANNEL) = """
        <html><script>var ytInitialData = {
          "metadata":{"channelMetadataRenderer":{"externalId":"$channel"}},
          "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
            {"tabRenderer":{"title":"Home","content":{"richGridRenderer":{"contents":[
              ${lockup("home", "KSAT News at 6 p.m. : Oct 20, 2026", "40:00")}, ${more("home-more")}]}}}},
            {"tabRenderer":{"title":"Videos","selected":true,"content":{"richGridRenderer":{"contents":[
              ${(videos.toList() + more("videos-more")).joinToString(",")}
            ]}}}}
          ]}}};</script></html>
    """

    @Test
    fun `KSAT 12's newscasts, as their titles read`() {
        listOf(
            "Good Morning San Antonio 5 a.m. : Oct 20, 2026",
            "Good Morning San Antonio 9 a.m. Saturday : Oct 24, 2026",
            "KSAT News at Noon : Oct 20, 2026",
            "KSAT News at 6 p.m. : Oct 20, 2026",
            "KSAT 12 News at 5:30 p.m. Sunday : Oct 25, 2026",
            "The Nightbeat @ 9 : Oct 16, 2026",
            "KSAT 12 Nightbeat : Oct 09, 2026",
            "KSAT 12 Nightbeat Sunday : Oct 18, 2026",
            "KSAT 12 News Nightbeat Saturday : Oct 24, 2026",
            "Good Morning San Antonio 6 a.m. : Sept 30, 2026",
        ).forEach { assertTrue(it, KsatNews.NEWSCAST.matches(it)) }
    }

    @Test
    fun `the channel's other long videos are not newscasts`() {
        listOf(
            "First Baptist Church Service : Oct 25, 2026",
            "KSAT Sports Now: BGC highlight show : Oct 23, 2026",
            "WATCH LIVE: Watching for new storms to develop : Oct 01, 2026",
            "WATCH: Spurs media day 2026 full press conference",
            "Harrison Barnes of the San Antonio Spurs Talks Trick-Or-Treat for UNICEF",
            "Pickup Lines: How San Antonio’s ’210 La Chismosa’ turned gossip into a platform for good",
            "San Antonio City Council discusses proposed SAWS rate increases",
            "KSAT Q&A: Mayor Jones urges storm prep, backs SAWS rate increase",
            "KSAT 12 Game of the Week: Steele, Judson clash in District 27-6A rivalry",
            "Good Morning San Antonio 6 a.m.",
        ).forEach { assertFalse(it, KsatNews.NEWSCAST.matches(it)) }
    }

    @Test
    fun `the latest newscast, past the clips and the longer videos that aren't news`() {
        val page = page(
            lockup("clip", "Parents of victim in Metzger Middle School stabbing speak out", "2:29"),
            lockup("church", "First Baptist Church Service : Oct 25, 2026", "1:45:54"),
            lockup("sports", "KSAT Sports Now: BGC highlight show : Oct 23, 2026", "23:04"),
            lockup("live", "KSAT News at 6 p.m. : Oct 25, 2026", "LIVE"),
            lockup("newscast", "Good Morning San Antonio 8 a.m. Sunday : Oct 25, 2026", "39:02"),
            lockup("older", "KSAT 12 Nightbeat : Oct 24, 2026", "49:18"),
        )
        assertEquals("newscast", KsatNews.latest(page))
    }

    @Test
    fun `a newscast of 20 minutes or less is passed over`() {
        val page = page(
            lockup("short", "KSAT 12 Nightbeat : Oct 16, 2026", "19:25"),
            lockup("twenty", "KSAT News at Noon : Oct 16, 2026", "20:00"),
            lockup("long", "KSAT News at 6 p.m. : Oct 16, 2026", "1:02:10"),
        )
        assertEquals("long", KsatNews.latest(page))
    }

    @Test
    fun `the next page's key is the one that ends the Videos list`() {
        val page = KsatNews.firstPage(page(lockup("clip", "Clip", "0:44")))!!
        assertEquals(listOf("clip"), page.videos.map { it.id })
        assertEquals("videos-more", page.next)
    }

    @Test
    fun `show more's reply reads the same, older layout too`() {
        val reply = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
            ${lockup("clip", "Clip", "1:58")},
            {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"old",
              "title":{"runs":[{"text":"KSAT News at Noon : Oct 08, 2026"}]},"lengthText":{"simpleText":"31:01"}}}}},
            ${more("page-3")}]}}]}"""
        val page = KsatNews.nextPage(reply)!!
        assertEquals("old", KsatNews.newscast(page.videos))
        assertEquals("page-3", page.next)
    }

    @Test
    fun `another channel's page, or no page, finds nothing`() {
        val newscast = lockup("newscast", "KSAT News at Noon : Oct 20, 2026", "40:00")
        assertNull(KsatNews.latest(page(newscast, channel = "UCsomeoneelse")))
        assertNull(KsatNews.latest(page(lockup("clip", "Clip", "3:40"))))
        assertNull(KsatNews.latest("<html>Sorry</html>"))
    }
}
