package com.mangoscreencast.tv.browse

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ui.LiquidGlassLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

/**
 * Opens each page of the home screen's menu on a 1080p TV, draws it with real
 * fonts and art to build/layout-previews/browse-<page>.png, and checks that no
 * text is cut off and the first title is described at the top left.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class BrowseLayoutTest {
    private lateinit var worker: Executor
    private lateinit var decoder: Executor

    @Before
    fun inPlace() {
        worker = LiquidGlassLayout.worker
        decoder = BrowseActivity.decoder
        LiquidGlassLayout.worker = Executor { it.run() }
        BrowseActivity.decoder = Executor { it.run() }
    }

    @After
    fun restore() {
        LiquidGlassLayout.worker = worker
        BrowseActivity.decoder = decoder
    }

    @Test fun whatToWatch() { page(Page.WHAT_TO_WATCH, first = "The Last of Us") }
    @Test fun featuredFree() { page(Page.FEATURED_FREE, first = "Night of the Living Dead") }
    @Test fun liveTv() { page(Page.LIVE_TV, first = "NBC Nightly News") }
    @Test fun sports() { page(Page.SPORTS, first = "Sunday Night Football") }
    @Test fun store() { page(Page.STORE, first = "Netflix") }

    /** Down through What to Watch: each row comes to the top of the glass, and Down on the last does nothing. */
    @Test
    fun downThroughTheRows() {
        val intent = Intent().putExtra(BrowseActivity.EXTRA_PAGE, Page.WHAT_TO_WATCH.name)
        val activity = Robolectric.buildActivity(BrowseActivity::class.java, intent).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        val rows = root.findViewById<RowsView>(R.id.rows)
        rows.frames.flatten().forEach { it.isFocusableInTouchMode = true }
        rows.frames.first().first().requestFocus()
        val tops = mutableListOf<Int>()
        repeat(rows.frames.size + 1) {
            rows.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            rows.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            // The rows glide (a draw moves them on): step the glide as frames would.
            repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); rows.computeScroll() }
            tops += rows.scrollY
        }
        // Each row is 380 px (190 dp) below the last; the last one stays put.
        assertEquals(listOf(380, 760, 1140, 1140, 1140), tops)
        assertEquals(rows.frames.last().first(), rows.findFocus())
    }

    @Test
    fun search() {
        val root = page(Page.SEARCH, first = null)
        assertEquals(View.GONE, root.findViewById<View>(R.id.info).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.search_area).visibility)
    }

    private fun page(page: Page, first: String?): View {
        val intent = Intent().putExtra(BrowseActivity.EXTRA_PAGE, page.name)
        val activity = Robolectric.buildActivity(BrowseActivity::class.java, intent).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        // As on the TV, which has no touch mode: the first tile takes the focus and is described.
        root.findViewById<RowsView>(R.id.rows).frames.flatten().forEach { it.isFocusableInTouchMode = true }
        root.findViewById<View>(R.id.search_field).isFocusableInTouchMode = true
        if (page == Page.SEARCH) root.findViewById<View>(R.id.search_field).requestFocus()
        else root.findViewById<RowsView>(R.id.rows).frames.first().first().requestFocus()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)) }
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        repeat(2) {
            val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            if (it == 1) File("build/layout-previews").apply { mkdirs() }
                .resolve("browse-${page.name.lowercase()}.png").outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        if (first != null) assertEquals(first, root.findViewById<TextView>(R.id.info_title).text.toString())

        val problems = mutableListOf<String>()
        visit(root) { view ->
            if (view !is TextView || view.visibility != View.VISIBLE || view.text.isNullOrEmpty() || view.layout == null) return@visit
            if (view.width == 0) return@visit
            val name = if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else view.text.toString()
            val needed = view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom
            if (needed > view.height) problems += "$name needs ${needed}px but has ${view.height}px"
            if (view.layout.lineCount > view.maxLines) problems += "$name wraps to ${view.layout.lineCount} lines"
        }
        assertTrue("$page: ${problems.joinToString("; ")}", problems.isEmpty())
        return root
    }

    private fun visit(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), action)
    }
}
