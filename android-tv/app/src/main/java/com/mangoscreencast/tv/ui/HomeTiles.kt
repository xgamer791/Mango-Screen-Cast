package com.mangoscreencast.tv.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.style.ReplacementSpan
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
    /** A glass shortcut: its name, its icon, and a wordmark shown in place of the name (0: none). */
    enum class Shortcut(@StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val wordmark: Int = 0) {
        NEWS(R.string.shortcut_news, R.drawable.ic_tile_news),
        // RetroArch's invader and its RETROARCH lettering, as on its own TV banner.
        EMULATION(R.string.shortcut_emulation, R.drawable.ic_tile_retroarch, R.drawable.retroarch_wordmark),
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
            // The wall glides, so the glass frosts off the main thread and fades over.
            tile.live = true
            tile.findViewById<ImageView>(R.id.shortcut_icon).setImageResource(shortcut.icon)
            tile.findViewById<TextView>(R.id.shortcut_label).apply {
                if (shortcut.wordmark != 0) text = wordmark(this, shortcut.wordmark) else setText(shortcut.label)
            }
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

    /** How tall a wordmark is against the name's capitals. */
    private const val WORDMARK_CAPS = 1.35f

    /**
     * A wordmark where a tile's name goes: on the name's baseline, a little taller than its
     * capitals, in the name's colour.
     */
    private fun wordmark(label: TextView, @DrawableRes art: Int): CharSequence {
        val mark = label.context.getDrawable(art)!!.mutate()
        // Drawn well below its size: smooth it with mipmaps, so the thin letters don't break up.
        (mark as? BitmapDrawable)?.bitmap?.setHasMipMap(true)
        val caps = Rect().also { label.paint.getTextBounds("H", 0, 1, it) }.height()
        val height = (caps * WORDMARK_CAPS).roundToInt()
        mark.setBounds(0, 0, (mark.intrinsicWidth.toFloat() * height / mark.intrinsicHeight).roundToInt(), height)
        mark.alpha = Color.alpha(label.currentTextColor)
        return SpannableString(" ").apply { setSpan(OnBaseline(mark), 0, 1, 0) }
    }

    /**
     * Draws `mark` standing on the text's baseline. Unlike ImageSpan it leaves the line as tall
     * as the text's, so the mark sits where the name's capitals would.
     */
    private class OnBaseline(private val mark: Drawable) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return mark.bounds.right
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val save = canvas.save()
            canvas.translate(x, (y - mark.bounds.bottom).toFloat())
            mark.draw(canvas)
            canvas.restoreToCount(save)
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
