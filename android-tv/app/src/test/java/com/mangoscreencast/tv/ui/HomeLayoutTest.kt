package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
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
import com.mangoscreencast.tv.apps.AppLauncher
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
        assertEquals("Mango TV", root.findViewById<TextView>(R.id.wordmark).text.toString())
        val menu = root.findViewById<ViewGroup>(R.id.menu)
        assertEquals(
            listOf("Home", "What to Watch", "Featured Free", "Live TV", "Sports", "Search", "Streaming Store", "Settings"),
            (0 until menu.childCount).map { (menu.getChildAt(it) as TextView).text.toString() },
        )
    }

    /**
     * The wall: News, Emulation and Live TV on Liquid Glass, then every streaming app in order,
     * four to a row, 168 x 125 dp, 18 dp apart, each logo whole inside its tile.
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
            // 168 x 125 dp, 18 dp apart across and down.
            assertEquals("$name's width", (168.0 * dp), tile.width.toDouble(), 1.0)
            assertEquals("$name's height", (125.0 * dp), tile.height.toDouble(), 1.0)
            assertEquals("$name's column", ((i % 4) * 186.0 * dp), tile.left.toDouble(), 1.0)
            assertEquals("$name's row", ((i / 4) * 143.0 * dp), tile.top.toDouble(), 1.0)
            // The fourth column ends the wall's 726 dp.
            if (i % 4 == 3) assertEquals("$name's right edge", 726.0 * dp, tile.right.toDouble(), 1.0)
            if (i < 3) continue
            val logo = tile.findViewById<ImageView>(R.id.app_logo)
            assertTrue("$name has no logo", logo.drawable != null)
            assertTrue("$name's logo is ${logo.width}x${logo.height} in a ${tile.width}x${tile.height} tile",
                logo.left >= 0 && logo.top >= 0 && logo.right <= tile.width && logo.bottom <= tile.height)
            val drawn = logo.width.toFloat() / logo.height
            val shape = logo.drawable.intrinsicWidth.toFloat() / logo.drawable.intrinsicHeight
            assertEquals("$name's logo is squeezed", shape.toDouble(), drawn.toDouble(), 0.05)
        }
        // From 30 dp right of the menu's icons, 726 dp across, the rest of the screen free on the right.
        val left = IntArray(2).also { grid!!.getLocationInWindow(it) }[0]
        assertEquals(101.8 * dp, left.toDouble(), 1.0)
        assertEquals(827.8 * dp, (left + grid!!.width).toDouble(), 1.0)
    }

    /**
     * Down through the wall: it scrolls a whole row at a time, only once the next row would run
     * past the bottom of the screen, and not at all past the last row.
     */
    @Test
    fun theWallScrollsByWholeRows() {
        var root: View? = null
        check("wall-scrolled") { home ->
            ready(home, fourthRow)
            root = home
        }
        val wall = root!!.findViewById<TileWall>(R.id.tile_wall)
        val grid = root!!.findViewById<TileGrid>(R.id.tile_grid)
        val pitch = TileGrid.ROW_PITCH_DP * wall.resources.displayMetrics.density
        // The test runs in touch mode, which the TV doesn't have.
        grid.tiles.forEach { it.isFocusableInTouchMode = true }
        grid.getChildAt(0).requestFocus()
        // Each press, then the wall's glide to its row.
        fun down() {
            wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
            frames(TileWall.GLIDE_MS + 32)
        }
        val scrolls = mutableListOf<Float>()
        repeat(grid.rows + 1) {
            down()
            scrolls += wall.scrollY / pitch
        }
        // Rows 2 and 3 are in view; row 4 runs past the bottom, so it brings the wall up exactly one row;
        // row 4 is the last, so no more.
        val rows = listOf(0f, 0f, 1f, 1f, 1f)
        assertEquals(rows.size, grid.rows + 1)
        for (i in rows.indices) assertEquals("$scrolls", rows[i].toDouble(), scrolls[i].toDouble(), 0.01)
        assertEquals(grid.childCount - TileGrid.COLUMNS, grid.indexOfChild(grid.findFocus()))
        // Back up to the first row, and once more: the focus stays on it, not the menu beside it.
        repeat(grid.rows + 1) {
            wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
            frames(TileWall.GLIDE_MS + 32)
        }
        assertEquals(0, grid.indexOfChild(grid.findFocus()))
        assertEquals(0, wall.scrollY)
    }

    /** Top right: how many tiles, or which of them has the focus, as on the Roku. */
    @Test
    fun theCount() {
        val resources = home().resources
        assertEquals("14 Items", HomeTiles.count(resources, 14, -1))
        assertEquals("1 of 14", HomeTiles.count(resources, 14, 0))
        assertEquals("14 of 14", HomeTiles.count(resources, 14, 13))
        assertEquals("1 Item", HomeTiles.count(resources, 1, -1))
    }

    @Test
    fun theWallGlidesToItsRowRatherThanJumping() {
        var root: View? = null
        check("wall-glide") { home ->
            ready(home, fourthRow)
            root = home
        }
        // From here, frames every 16 ms as the clock moves on, as on the TV.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val wall = root!!.findViewById<TileWall>(R.id.tile_wall)
        val grid = root!!.findViewById<TileGrid>(R.id.tile_grid)
        val pitch = TileGrid.ROW_PITCH_DP * wall.resources.displayMetrics.density
        grid.tiles.forEach { it.isFocusableInTouchMode = true }
        // The third row's first tile, so Down (to the fourth, which runs past the bottom) brings the wall up a row.
        grid.getChildAt(2 * TileGrid.COLUMNS).requestFocus()
        frames(TileWall.GLIDE_MS + 32)
        assertEquals(0, wall.scrollY)
        wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        wall.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("the focus moves at once", 3 * TileGrid.COLUMNS, grid.indexOfChild(grid.findFocus()))
        assertEquals("the wall hasn't jumped", 0, wall.scrollY)
        frames(TileWall.GLIDE_MS / 4)
        val early = wall.scrollY
        frames(TileWall.GLIDE_MS / 4)
        val half = wall.scrollY
        assertTrue("on its way at a quarter: $early", early > 0 && early < pitch)
        assertTrue("further on at a half: $half", half > early && half < pitch)
        // Quick at first, settling gently: more than half the way at half the time.
        assertTrue("past halfway at half the time: $half of $pitch", half > pitch / 2)
        frames(TileWall.GLIDE_MS)
        assertEquals("on the row", pitch.toDouble(), wall.scrollY.toDouble(), 1.0)
    }

    /**
     * The menu closed, as Home opens: its icons only, lined up with the wordmark's left edge, each
     * in the middle of its bar; the wall beside them with the focused tile's name over its left edge,
     * on the count's baseline.
     */
    @Test
    fun theMenuClosed() {
        val root = laidOut { HomeMenu.show(it, open = false) }
        val dp = root.resources.displayMetrics.density
        val wordmark = root.findViewById<TextView>(R.id.wordmark)
        val menu = root.findViewById<ViewGroup>(R.id.menu)
        for (i in 0 until menu.childCount) {
            val item = menu.getChildAt(i) as TextView
            val icon = item.compoundDrawablesRelative[0]
            assertTrue("${item.text} has no icon", icon != null)
            assertEquals("${item.text}'s words show", 0, item.currentTextColor)
            // The icon is drawn at the item's start padding; the words start after it and its padding.
            assertEquals("${item.text}'s icon isn't under the wordmark's left edge", wordmark.left.toDouble(),
                (menu.left + item.left + item.compoundPaddingStart - item.compoundDrawablePadding - icon.bounds.width()).toDouble(), 1.0)
            val iconTop = item.compoundPaddingTop + (item.height - item.compoundPaddingTop - item.compoundPaddingBottom - icon.bounds.height()) / 2f
            assertEquals("${item.text}'s icon is off the middle of its bar", item.height / 2.0,
                (iconTop + icon.bounds.height() / 2f).toDouble(), 0.6 * dp)
        }
        val wall = root.findViewById<View>(R.id.tile_wall)
        val name = root.findViewById<TextView>(R.id.tile_name)
        val count = root.findViewById<TextView>(R.id.item_count)
        assertEquals(101.8 * dp, wall.left.toDouble(), 1.0)
        assertTrue("the closed menu (to ${menu.right}) reaches over the wall (from ${wall.left})", menu.right <= wall.left)
        assertEquals(wall.left, name.left)
        assertEquals((count.top + count.baseline).toDouble(), (name.top + name.baseline).toDouble(), 1.0)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.home_chevron).visibility)
    }

    /** Left along every row of the wall steps one column at a time, and only the first column leads to the menu. */
    @Test
    fun leftStepsAlongTheWallColumnByColumn() {
        val root = laidOut { HomeMenu.show(it, open = false) } as ViewGroup
        val grid = root.findViewById<TileGrid>(R.id.tile_grid)
        val menu = root.findViewById<ViewGroup>(R.id.menu)
        // The test runs in touch mode, which the TV doesn't have.
        grid.tiles.forEach { it.isFocusableInTouchMode = true }
        for (i in 0 until menu.childCount) menu.getChildAt(i).isFocusableInTouchMode = true
        val finder = android.view.FocusFinder.getInstance()
        for ((i, tile) in grid.tiles.withIndex()) {
            if (i % TileGrid.COLUMNS == 0) continue
            val next = finder.findNextFocus(root, tile, View.FOCUS_LEFT)
            assertEquals("Left from tile $i (${tile.contentDescription}) went to ${next?.contentDescription ?: (next as? TextView)?.text}",
                grid.getChildAt(i - 1), next)
        }
    }

    /** The menu open: its words show, and the wall and the name move right of its bars, the arrow under them. */
    @Test
    fun theMenuOpen() {
        val root = laidOut { HomeMenu.show(it, open = true) }
        val dp = root.resources.displayMetrics.density
        val menu = root.findViewById<ViewGroup>(R.id.menu)
        for (i in 0 until menu.childCount) {
            val item = menu.getChildAt(i) as TextView
            assertTrue("${item.text}'s words don't show", item.currentTextColor != 0)
        }
        assertEquals(351.0 * dp, menu.right.toDouble(), 1.0)
        assertEquals(402.3 * dp, root.findViewById<View>(R.id.tile_wall).left.toDouble(), 1.0)
        assertTrue(menu.right < root.findViewById<View>(R.id.tile_wall).left)
        assertEquals(402.3 * dp, root.findViewById<View>(R.id.tile_name).left.toDouble(), 1.0)
        assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.home_chevron).visibility)
        // Closed again, all back.
        HomeMenu.show(root, open = false)
        root.relayout()
        assertEquals(101.8 * dp, root.findViewById<View>(R.id.tile_wall).left.toDouble(), 1.0)
    }

    @Test
    fun noSettingsButtonTheGearOpensTheMenu() {
        val root = home()
        val buttons = mutableListOf<String>()
        visit(root) { if (it is Button) buttons += it.text.toString() }
        assertTrue(buttons.toString(), root.context.getString(R.string.settings) !in buttons)
    }

    /** Home on screen, filled and laid out on the 960 x 540 dp screen, after `change`. */
    private fun laidOut(change: (View) -> Unit): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_Mango)).inflate(R.layout.activity_home, null)
        activity.setContentView(root)
        ready(root)
        change(root)
        root.relayout()
        return root
    }

    private fun View.relayout() {
        val metrics = resources.displayMetrics
        measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun home(): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_Mango)).inflate(R.layout.activity_home, null)
    }

    /** Four of the TV's other apps after the streaming apps: a fourth row, which runs past the bottom of the screen. */
    private val fourthRow = (1..4).map { HomeTiles.OtherTile(AppLauncher.OtherApp("app.$it", Intent(), 0L), "App $it", null) }

    /** The wall filled, the clock at 3:24 pm as in the Roku capture, and Home focused. */
    private fun ready(root: View, others: List<HomeTiles.OtherTile> = emptyList()) {
        val grid = root.findViewById<TileGrid>(R.id.tile_grid)
        HomeTiles.fill(grid, others, HomeTiles.Actions())
        root.text(R.id.clock, "3:24 pm")
        root.text(R.id.tile_name, grid.getChildAt(0).contentDescription.toString())
        root.text(R.id.item_count, root.resources.getQuantityString(R.plurals.home_item_count, grid.childCount, grid.childCount))
    }

    private fun check(label: String, fill: (View) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Mango)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_home, null)
        activity.setContentView(root)
        fill(root)
        // As on the TV, which has no touch mode: the wall's first tile has the focus (Home if it's empty).
        val grid = root.findViewById<TileGrid>(R.id.tile_grid)
        (grid.getChildAt(0) ?: root.findViewById(R.id.menu_home)).apply { isFocusableInTouchMode = true }.requestFocus()

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
