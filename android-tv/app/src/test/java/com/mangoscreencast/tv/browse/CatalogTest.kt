package com.mangoscreencast.tv.browse

import android.app.Application
import android.graphics.BitmapFactory
import com.mangoscreencast.tv.apps.StreamingApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogTest {
    private val apps = StreamingApps.ALL.map { it.name }.toSet()

    @Test
    fun everyPageButSearchAndTheStoreHasRowsOfRealShows() {
        for (page in Page.entries - Page.SEARCH - Page.STORE) {
            val shelves = Catalog.shelves.getValue(page)
            assertTrue("$page has no rows", shelves.isNotEmpty())
            for (shelf in shelves) assertTrue("${shelf.title} is empty", shelf.shows.isNotEmpty())
        }
        // Every show is on a page, once.
        val placed = Catalog.shelves.values.flatten().flatMap { it.keys }
        assertEquals(placed.size, placed.toSet().size)
        assertEquals(Catalog.shows.map { it.key }.toSet(), placed.toSet())
    }

    @Test
    fun everyShowOpensOneOfTheTvsAppsOrLiveTv() {
        for (show in Catalog.shows) {
            assertTrue("${show.title} opens ${show.opens}", show.opens in apps || show.opens == Show.LIVE_TV)
            show.link?.let { assertTrue("${show.title}: $it", it.startsWith("https://")) }
        }
        // The free films are all on Tubi, each straight to its page there.
        for (show in Catalog.shelves.getValue(Page.FEATURED_FREE).flatMap { it.shows }) {
            assertEquals("Tubi", show.opens)
            assertTrue(show.link!!.startsWith("https://tubitv.com/movies/"))
        }
        assertTrue(Catalog.shelves.getValue(Page.LIVE_TV).flatMap { it.shows }.all { it.opens == Show.LIVE_TV })
    }

    @Test
    fun everyShowHasItsArtAndAFewWordsAboutIt() {
        val resources = RuntimeEnvironment.getApplication().resources
        for (show in Catalog.shows) {
            for (res in listOf(show.art, show.tile)) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(resources, res, bounds)
                assertTrue("${show.title} has no art", bounds.outWidth > 0)
            }
            assertTrue("${show.title}: ${show.about}", show.about.length in 40..140)
            assertTrue(show.meta, "·" !in show.meta)
        }
    }

    @Test
    fun searchFindsTitlesByEveryWordInAnyCase() {
        assertEquals(listOf("The Bear"), Catalog.search("bear").map { it.title })
        assertEquals(listOf("Night of the Living Dead"), Catalog.search("LIVING night").map { it.title })
        assertTrue(Catalog.search("   ").isEmpty())
        assertTrue(Catalog.search("zzz").isEmpty())
    }
}
