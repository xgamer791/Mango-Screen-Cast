package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
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
    private val name = "Mango Screen Cast For macOS/iOS"

    @Test
    fun ready() = check("ready") { root ->
        ready(root)
        root.text(R.id.update_status, root.context.getString(R.string.version_label, "1.2.0"))
    }

    @Test
    fun readyWithUpdateButton() = check("update-available") { root ->
        ready(root)
        root.text(R.id.update_status, root.context.getString(R.string.update_available, "1.2.10"))
        root.findViewById<Button>(R.id.update_button).apply {
            text = context.getString(R.string.update_now)
            visibility = View.VISIBLE
        }
    }

    /** Every app has a tile on the screen, in order, with its whole logo inside it. */
    @Test
    fun appTiles() {
        var row: ViewGroup? = null
        check("apps") { root ->
            ready(root)
            row = root.findViewById(R.id.apps_row)
        }
        val tiles = (0 until row!!.childCount).map { row!!.getChildAt(it) }
        assertEquals(StreamingApps.ALL.map { it.name }, tiles.map { it.contentDescription })
        val screen = row!!.rootView.width
        var left = 0
        for (tile in tiles) {
            val name = tile.contentDescription
            assertTrue("$name overlaps the tile before it", tile.left >= left)
            left = tile.right
            val logo = tile.findViewById<ImageView>(R.id.app_logo)
            assertTrue("$name has no logo", logo.drawable != null)
            assertTrue("$name's logo is ${logo.width}x${logo.height} in a ${tile.width}x${tile.height} tile",
                logo.left >= 0 && logo.top >= 0 && logo.right <= tile.width && logo.bottom <= tile.height)
            assertTrue("$name's logo is squeezed", logo.width == logo.drawable.intrinsicWidth &&
                logo.height == logo.drawable.intrinsicHeight)
        }
        val rowRight = IntArray(2).also { row!!.getLocationInWindow(it) }[0] + row!!.width
        assertTrue("the row runs past the screen", rowRight <= screen)
    }

    @Test
    fun failedUpdateCheck() = check("errors") { root ->
        ready(root)
        root.text(R.id.update_status,
            "Couldn't check for updates (Unable to resolve host \"github.com\": No address associated with hostname)")
        root.findViewById<Button>(R.id.update_button).apply {
            text = context.getString(R.string.update_retry)
            visibility = View.VISIBLE
        }
    }

    /** Under the wallpaper, only the apps, the version and an update's button: no headline,
     * no lines under it, no glass strip of steps. */
    @Test
    fun noTextOnTopAndNoGlassStrip() {
        val root = home()
        val ids = mutableListOf<String>()
        val kinds = mutableListOf<String>()
        visit(root) { view ->
            if (view.id != View.NO_ID) ids += view.resources.getResourceEntryName(view.id)
            kinds += view.javaClass.simpleName
        }
        for (gone in listOf("status", "subtitle", "status_detail", "steps")) assertTrue("$gone: $ids", gone !in ids)
        assertTrue(kinds.toString(), "LiquidGlassLayout" !in kinds)
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

    private fun ready(root: View) {
        AppTiles.fill(root.findViewById(R.id.apps_row)) {}
    }

    private fun check(label: String, fill: (View) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, R.style.Theme_Mango)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_home, null)
        fill(root)

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
