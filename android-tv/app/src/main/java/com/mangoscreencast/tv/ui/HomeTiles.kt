package com.mangoscreencast.tv.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.apps.AppLauncher
import com.mangoscreencast.tv.apps.StreamingApp
import com.mangoscreencast.tv.apps.StreamingApps
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fills the home screen's wall, in order: the three Liquid Glass shortcuts
 * (Roku's Cable Box, Game Console and Live TV), a tile per streaming app, then
 * the TV's other apps, a new one last. OK on a tile calls its action.
 */
object HomeTiles {
    /** A glass shortcut: its name, its icon, and the words on it (its name, unless it says what it opens). */
    enum class Shortcut(@StringRes val label: Int, @DrawableRes val icon: Int, @StringRes val words: Int = label) {
        NEWS(R.string.shortcut_news, R.drawable.ic_tile_news),
        // RetroArch's invader, and its name in the app's own type, as the other tiles' names are.
        EMULATION(R.string.shortcut_emulation, R.drawable.ic_tile_retroarch, R.string.shortcut_retroarch),
        LIVE_TV(R.string.shortcut_live_tv, R.drawable.ic_tile_live_tv),
    }

    /** What OK on each kind of tile does. */
    class Actions(
        val shortcut: (Shortcut) -> Unit = {},
        val app: (StreamingApp) -> Unit = {},
        val other: (AppLauncher.OtherApp) -> Unit = {},
    )

    /** One of the TV's other apps: its name and its TV banner (if it has one). */
    class OtherTile(val app: AppLauncher.OtherApp, val label: CharSequence, val banner: Drawable?)

    /** "14 Items", or "5 of 14" while the tile at `focused` (from 0) has the focus; -1 for none. */
    fun count(resources: Resources, total: Int, focused: Int): String =
        if (focused in 0 until total) resources.getString(R.string.home_item_position, focused + 1, total)
        else resources.getQuantityString(R.plurals.home_item_count, total, total)

    /** The most of a tile's width a logo takes. */
    private const val LOGO_WIDTH = 0.78f

    fun fill(grid: TileGrid, others: List<OtherTile>, actions: Actions) {
        grid.removeAllViews()
        val inflater = LayoutInflater.from(grid.context)
        for (shortcut in Shortcut.entries) {
            val tile = inflater.inflate(R.layout.item_shortcut_tile, grid, false) as LiquidGlassLayout
            tile.cornerRadius = 0f
            // The wallpaper turns, so the glass frosts it again off the main thread and fades over; at
            // Mango Liquid Glass's 24 px frost, half the pixels do.
            tile.live = true
            tile.scale = 0.5f
            tile.findViewById<ImageView>(R.id.shortcut_icon).setImageResource(shortcut.icon)
            tile.findViewById<TextView>(R.id.shortcut_label).setText(shortcut.words)
            tile.contentDescription = grid.context.getString(shortcut.label)
            tile.setOnClickListener { actions.shortcut(shortcut) }
            grid.addView(tile)
        }
        for (app in StreamingApps.ALL) {
            val tile = appTile(inflater, grid, app.name)
            tile.setBackgroundResource(app.tile)
            tile.findViewById<ImageView>(R.id.app_logo).apply {
                setImageResource(app.logo)
                fit(this, LOGO_WIDTH, app.logoHeight)
            }
            tile.setOnClickListener { actions.app(app) }
            grid.addView(tile)
        }
        for (other in others) {
            val tile = appTile(inflater, grid, other.label.toString())
            val banner = other.banner
            if (banner != null) {
                // The app's own TV banner across the tile, on the colour of its edge.
                tile.background = ColorDrawable(edgeColour(banner))
                tile.findViewById<ImageView>(R.id.app_logo).apply {
                    setImageDrawable(banner)
                    fit(this, 1f, 1f)
                }
            } else {
                tile.setBackgroundResource(R.drawable.tile_bg_dark)
            }
            tile.setOnClickListener { actions.other(other.app) }
            grid.addView(tile)
        }
    }

    private fun appTile(inflater: LayoutInflater, grid: TileGrid, name: String): FrameLayout =
        (inflater.inflate(R.layout.item_app_tile, grid, false) as FrameLayout).also { it.contentDescription = name }

    /** Sizes the logo to fit `width` and `height` of the tile (fractions), keeping its shape. */
    private fun fit(logo: ImageView, width: Float, height: Float) {
        val drawable = logo.drawable ?: return
        val density = logo.resources.displayMetrics.density
        val boxW = TileGrid.TILE_WIDTH_DP * density * width
        val boxH = TileGrid.TILE_HEIGHT_DP * density * height
        val w = drawable.intrinsicWidth.toFloat().takeIf { it > 0 } ?: return
        val h = drawable.intrinsicHeight.toFloat().takeIf { it > 0 } ?: return
        val scale = min(boxW / w, boxH / h)
        logo.layoutParams = (logo.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = (w * scale).roundToInt()
            this.height = (h * scale).roundToInt()
        }
    }

    /** The colour round a banner's edge (its corners, averaged), to fill the tile above and below it. */
    internal fun edgeColour(banner: Drawable): Int {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        banner.setBounds(0, 0, 32, 18)
        banner.draw(Canvas(bitmap))
        val corners = listOf(bitmap.getPixel(0, 0), bitmap.getPixel(31, 0), bitmap.getPixel(0, 17), bitmap.getPixel(31, 17))
        bitmap.recycle()
        return Color.rgb(corners.sumOf { Color.red(it) } / 4, corners.sumOf { Color.green(it) } / 4, corners.sumOf { Color.blue(it) } / 4)
    }
}
