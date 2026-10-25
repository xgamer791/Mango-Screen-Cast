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
import android.widget.TextView
import com.mangoscreencast.tv.R
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
 * off. Also writes what it laid out to build/layout-previews.
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

    @Test
    fun readyNextToTheTvsOwnAirPlay() = check("two-receivers") { root ->
        ready(root)
        root.text(R.id.status_detail, root.context.getString(R.string.status_ready_detail_other, name, "Master Bedroom TV"))
        root.text(R.id.update_status, root.context.getString(R.string.version_label, "1.2.0"))
    }

    @Test
    fun failedUpdateCheckAndTwoLineStatus() = check("errors") { root ->
        ready(root)
        root.text(R.id.status, root.context.getString(R.string.status_failed))
        root.text(R.id.status_detail,
            "Couldn't announce the TV on the network. Check that the TV is on Wi-Fi or Ethernet.")
        root.text(R.id.update_status,
            "Couldn't check for updates (Unable to resolve host \"github.com\": No address associated with hostname)")
        root.findViewById<Button>(R.id.update_button).apply {
            text = context.getString(R.string.update_retry)
            visibility = View.VISIBLE
        }
    }

    private fun ready(root: View) {
        val c = root.context
        root.text(R.id.status, c.getString(R.string.status_ready))
        root.text(R.id.status_detail, c.getString(R.string.status_ready_detail, name))
        root.text(R.id.mac_steps, c.getString(R.string.mac_steps, name))
        root.text(R.id.ios_steps, c.getString(R.string.ios_steps, name))
        root.text(R.id.capability, c.getString(R.string.capability, "4K", 3840, 2160, 60, "HEVC + H.264", 3840, 2160))
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
            // Also not hidden by a parent that is too small.
            var bottom = view.bottom
            var parent = view.parent as? ViewGroup
            var child: View = view
            while (parent != null) {
                val limit = parent.height - if (parent.clipToPadding) parent.paddingBottom else 0
                if (bottom > limit) problems += "$id is cut off by ${parent.javaClass.simpleName} (${bottom - limit}px)"
                bottom += parent.top
                child = parent
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
