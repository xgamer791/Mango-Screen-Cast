package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.apps.StreamingApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Lays the home screen out on a 1080p TV (960 × 540 dp, how Android TV sizes
 * its UI even on 4K panels) with real fonts, and checks that no text is cut
 * off and nothing runs past the screen. Also writes what it laid out to
 * build/layout-previews.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class HomeLayoutTest {
    @Test
    fun ready() = check("ready") { root ->
        ready(root)
    }

    @Test
    fun readyWithUpdateButton() = check("update-available") { root ->
        ready(root)
        root.findViewById<View>(R.id.update_area).visibility = View.VISIBLE
        root.text(R.id.update_status, root.context.getString(R.string.update_available, "1.2.10"))
        root.findViewById<Button>(R.id.update_button).apply {
            text = context.getString(R.string.update_now)
            visibility = View.VISIBLE
        }
    }

    @Test
    fun failedUpdateCheck() = check("errors") { root ->
        ready(root)
        root.findViewById<View>(R.id.update_area).visibility = View.VISIBLE
        root.text(R.id.update_status,
            "Couldn't check for updates (Unable to resolve host \"github.com\": No address associated with hostname)")
        root.findViewById<Button>(R.id.update_button).apply {
            text = context.getString(R.string.update_retry)
            visibility = View.VISIBLE
        }
    }

    /** Roku's menu, in Roku's order. */
    @Test
    fun theMenu() {
        val root = home()
        val menu = root.findViewById<ViewGroup>(R.id.menu)
        assertEquals(
            listOf("Home", "What to Watch", "Featured Free", "Live TV", "Sports", "Search", "Streaming Store", "Settings"),
            (0 until menu.childCount).map { (menu.getChildAt(it) as TextView).text.toString() },
        )
    }

    /**
     * The wall: News, Emulation and Live TV on Liquid Glass, then every streaming app in order,
     * three to a row, 149 x 111 dp, 11.5 dp apart, each logo whole inside its tile.
     */
    @Test
    fun theWall() {
        var grid: TileGrid? = null
        check("wall") { root ->
            ready(root)
            grid = root.findViewById(R.id.tile_grid)
        }
        val tiles = grid!!.tiles
        assertEquals(listOf("News", "Emulation", "Live TV") + StreamingApps.ALL.map { it.name },
            tiles.map { it.contentDescription })
        assertTrue(tiles.take(3).all { it is LiquidGlassLayout })
        val dp = grid!!.resources.displayMetrics.density
        for ((i, tile) in tiles.withIndex()) {
            val name = tile.contentDescription
            assertEquals("$name's width", (149 * dp).toDouble(), tile.width.toDouble(), 1.0)
            assertEquals("$name's height", (111 * dp).toDouble(), tile.height.toDouble(), 1.0)
            assertEquals("$name's column", ((i % 3) * 160.5 * dp).toDouble(), tile.left.toDouble(), 1.0)
            assertEquals("$name's row", ((i / 3) * 122.5 * dp).toDouble(), tile.top.toDouble(), 1.0)
            if (i < 3) continue
            val logo = tile.findViewById<ImageView>(R.id.app_logo)
            assertTrue("$name has no logo", logo.drawable != null)
            assertTrue("$name's logo is ${logo.width}x${logo.height} in a ${tile.width}x${tile.height} tile",
                logo.left >= 0 && logo.top >= 0 && logo.right <= tile.width && logo.bottom <= tile.height)
            val drawn = logo.width.toFloat() / logo.height
            val shape = logo.drawable.intrinsicWidth.toFloat() / logo.drawable.intrinsicHeight
            assertEquals("$name's logo is squeezed", shape.toDouble(), drawn.toDouble(), 0.05)
        }
        val right = IntArray(2).also { grid!!.getLocationInWindow(it) }[0] + grid!!.width
        assertTrue("the wall runs past the screen", right <= grid!!.rootView.width)
    }

    /**
     * Down through the wall: it scrolls a whole row at a time, only once the next row would be
     * under the fade, and not at all past the last row.
     */
    @Test
    fun theWallScrollsByWholeRows() {
        var root: View? = null
        check("wall-scrolled") { home ->
            ready(home)
            root = home
        }
        val wall = root!!.findViewById<TileWall>(R.id.tile_wall)
        val grid = root!!.findViewById<TileGrid>(R.id.tile_grid)
        val pitch = 122.5f * wall.resources.displayMetrics.density
        // The test runs in touch mode, which the TV doesn't have.
        grid.tiles.forEach { it.isFocusableInTouchMode = true }
        grid.getChildAt(0).requestFocus()
        fun down() {
            wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
        val scrolls = mutableListOf<Float>()
        repeat(grid.rows + 1) {
            down()
            scrolls += wall.scrollY / pitch
        }
        // Rows 2 and 3 are in view; row 4 brings the wall up exactly one row; row 4 is the last, so no more.
        val rows = listOf(0f, 0f, 1f, 1f, 1f)
        for (i in rows.indices) assertEquals("$scrolls", rows[i].toDouble(), scrolls[i].toDouble(), 0.01)
        assertEquals(grid.childCount - 3, grid.indexOfChild(grid.findFocus()))
    }

    @Test
    fun noSettingsButtonTheGearOpensTheMenu() {
        val root = home()
        val buttons = mutableListOf<String>()
        visit(root) { if (it is Button) buttons += it.text.toString() }
        assertTrue(buttons.toString(), root.context.getString(R.string.settings) !in buttons)
    }

    private fun home(): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_Mango)).inflate(R.layout.activity_home, null)
    }

    /** The wall filled, the clock at 3:24 pm as in the Roku capture, and Home focused. */
    private fun ready(root: View) {
        val grid = root.findViewById<TileGrid>(R.id.tile_grid)
        HomeTiles.fill(grid, emptyList(), HomeTiles.Actions())
        root.text(R.id.clock, "3:24 pm")
        root.text(R.id.item_count, root.resources.getQuantityString(R.plurals.home_item_count, grid.childCount, grid.childCount))
    }

    private fun check(label: String, fill: (View) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Mango)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_home, null)
        activity.setContentView(root)
        fill(root)
        // As on the TV, which has no touch mode: Home has the focus.
        root.findViewById<View>(R.id.menu_home).apply { isFocusableInTouchMode = true }.requestFocus()

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/layout-previews").apply { mkdirs() }.resolve("home-$label.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val problems = mutableListOf<String>()
        visit(root) { view ->
            if (view !is TextView || view.visibility != View.VISIBLE || view.text.isNullOrEmpty()) return@visit
            val id = view.resources.getResourceEntryName(view.id.takeIf { it != View.NO_ID } ?: return@visit)
            val needed = view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom
            if (needed > view.height) problems += "$id needs ${needed}px but has ${view.height}px"
            if (view.layout.lineCount > view.maxLines) problems += "$id wraps to ${view.layout.lineCount} lines"
            // Also not hidden by a parent that is too small, or off the screen.
            var bottom = view.bottom
            var right = view.right
            var parent = view.parent as? ViewGroup
            while (parent != null) {
                val limit = parent.height - if (parent.clipToPadding) parent.paddingBottom else 0
                if (bottom > limit) problems += "$id is cut off by ${parent.javaClass.simpleName} (${bottom - limit}px)"
                if (right > parent.width) problems += "$id runs past the right edge (${right - parent.width}px)"
                bottom += parent.top
                right += parent.left
                parent = parent.parent as? ViewGroup
            }
        }
        assertTrue("$label: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    private fun visit(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), action)
    }

    private fun View.text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }
}
