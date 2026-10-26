package com.mangoscreencast.tv.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KsatNewsTest {
    /** A video as the channel's Videos page has it, with only the parts read here. */
    private fun lockup(id: String, length: String) = """
        {"richItemRenderer":{"content":{"lockupViewModel":{
          "contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":
            {"badges":[{"thumbnailBadgeViewModel":{"text":"$length","animationActivationTargetId":"$id"}}]}}]}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"Video $id"}}},
          "contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}}}}
    """

    private fun page(vararg videos: String) = """
        <html><script>var ytInitialData = {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
          {"tabRenderer":{"title":"Home","content":{"richGridRenderer":{"contents":[${lockup("home", "59:00")}]}}}},
          {"tabRenderer":{"title":"Videos","selected":true,"content":{"richGridRenderer":{"contents":[
            ${videos.joinToString(",")}
          ]}}}}
        ]}}};</script></html>
    """

    @Test
    fun `the newest video over 20 minutes, past the clips before it`() {
        val page = page(lockup("clip", "0:44"), lockup("church", "LIVE"), lockup("newscast", "22:02"), lockup("older", "39:02"))
        assertEquals("newscast", KsatNews.latest(page))
    }

    @Test
    fun `hours count, and exactly 20 minutes is not over`() {
        assertEquals("long", KsatNews.latest(page(lockup("twenty", "20:00"), lockup("long", "1:45:54"))))
    }

    @Test
    fun `the older video layout reads the same`() {
        val renderer = """{"richItemRenderer":{"content":{"videoRenderer":{"videoId":"old","lengthText":{"simpleText":"31:01"}}}}}"""
        assertEquals("old", KsatNews.latest(page(lockup("clip", "2:29"), renderer)))
    }

    @Test
    fun `none over 20 minutes, or no page, finds nothing`() {
        assertNull(KsatNews.latest(page(lockup("clip", "3:40"))))
        assertNull(KsatNews.latest("<html>Sorry</html>"))
    }
}
