package com.mangoscreencast.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.mangoscreencast.tv.R
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The gear's panel, a copy of the one the Hisense's gear opened (Google TV's
 * dashboard, launcherx DashboardActivity, read from the TV's own launcher and
 * measured on its screen): a rounded panel 272 dp wide, 24 dp from the right
 * edge and 16 dp from the top and bottom, over whatever is on screen.
 *
 * It opens as the dashboard does: in 300 ms on PathInterpolator(0.12, 1, 0.4, 1)
 * it comes in from beyond the right edge (its width and margin, 296 dp) while
 * its opacity goes from 0 to 1 and what's behind dims to 60%. It closes as the
 * dashboard's window leaves: in 500 ms on PathInterpolator(0.4, 1, 0.12, 1) it
 * moves its own width right while fading out, and the dim goes evenly over the
 * same time. The gear while it's closing brings it back.
 *
 * In it: the date and time, All settings (which has the focus as it opens) and
 * the account, the setting tiles (PanelTiles) four down a column and two
 * columns in view, a dot for each place the tiles can scroll to, the TV's
 * notifications (PanelNotifications), and Help at the foot.
 * A tile with the focus grows to 1.025 and turns light in 300 ms; each opens
 * the TV's own screen for its setting, and the panel goes as that opens. The
 * Sleep timer opens a page of times in the panel (SleepTimer).
 */
class SettingsMenuActivity : Activity() {
    private lateinit var panel: View
    private lateinit var scrim: View
    private lateinit var gridHolder: FrameLayout
    private lateinit var grid: FrameLayout
    private lateinit var dotsView: LinearLayout
    private lateinit var sleepPage: View
    private var closing = false
    private var motion: ValueAnimator? = null
    private var sleepTile: View? = null
    private var sleepLeft: TextView? = null

    /** The first column in view. */
    private var scroll = 0
    private var columns = 0

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_menu)
        panel = findViewById(R.id.menu_panel)
        scrim = findViewById(R.id.menu_scrim)
        gridHolder = findViewById(R.id.panel_pages)
        dotsView = findViewById(R.id.panel_dots)
        sleepPage = findViewById(R.id.panel_sleep_page)
        panel.clipToOutline = true
        header()
        tiles()
        notifications()
        footer()
        panel.alpha = 0f
        panel.translationX = openTravel()
        scrim.alpha = 0f
        findViewById<View>(R.id.panel_all_settings).requestFocus()
        open()
    }

    override fun onResume() {
        super.onResume()
        FrontSettings.shown(this)
        showSleepLeft()
    }

    override fun onPause() {
        FrontSettings.hidden(this)
        super.onPause()
    }

    override fun onDestroy() {
        motion?.cancel()
        super.onDestroy()
    }

    /** The gear (GearKeyService): closes the panel, or brings it back if it's on its way out. */
    fun gear() = if (closing) open() else close()

    @Deprecated("Back leaves the Sleep timer page, or closes the panel, as the gear does")
    override fun onBackPressed() {
        if (sleepPage.visibility == View.VISIBLE) leaveSleepPage() else close()
    }

    /** How far the panel starts beyond its place: its width and its margin, off the right edge. */
    private fun openTravel() = resources.getDimension(R.dimen.panel_width) + resources.getDimension(R.dimen.panel_margin_end)

    /** How far it moves as it closes: its own width (dashboard_slide_out_translation_x, 100%). */
    private fun closeTravel() = resources.getDimension(R.dimen.panel_width)

    private fun open() {
        closing = false
        val fromX = panel.translationX
        val fromAlpha = panel.alpha
        val fromDim = scrim.alpha
        // From part way (turned round while closing), the rest of the way in the rest of the time.
        val share = 1f - fromAlpha
        animate((OPEN_MS * share).roundToLong(), OPEN_CURVE) { f ->
            panel.translationX = fromX * (1f - f)
            panel.alpha = fromAlpha + (1f - fromAlpha) * f
            scrim.alpha = fromDim + (DIM - fromDim) * f
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        val fromX = panel.translationX
        val toX = closeTravel()
        val fromAlpha = panel.alpha
        val fromDim = scrim.alpha
        animate(CLOSE_MS, LinearInterpolator(), done = ::finishNow) { t ->
            val f = CLOSE_CURVE.getInterpolation(t)
            panel.translationX = fromX + (toX - fromX) * f
            panel.alpha = fromAlpha * (1f - f)
            scrim.alpha = fromDim * (1f - t)
        }
    }

    private fun animate(ms: Long, curve: Interpolator, done: (() -> Unit)? = null, step: (Float) -> Unit) {
        motion?.cancel()
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { step(it.animatedValue as Float) }
            if (done != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) done()
                    }
                })
            }
            start()
        }
    }

    private fun finishNow() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Opens one of the TV's screens over what's on screen, and the panel goes. */
    private fun launch(intent: Intent, name: CharSequence) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            motion?.cancel()
            finishNow()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.panel_open_failed, name), Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, getString(R.string.panel_open_failed, name), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- The head and foot ----

    private fun header() {
        val allSettings = findViewById<View>(R.id.panel_all_settings)
        val icon = findViewById<ImageView>(R.id.panel_all_settings_icon)
        val label = findViewById<View>(R.id.panel_all_settings_label)
        tintButton(icon, false)
        allSettings.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            tintButton(icon, focused)
            label.animate().alpha(if (focused) 1f else 0f).setDuration(LABEL_MS).start()
        }
        allSettings.setOnClickListener { launch(PanelTiles.allSettings(), getString(R.string.panel_all_settings)) }

        findViewById<View>(R.id.panel_account).setOnClickListener {
            launch(PanelTiles.account(), getString(R.string.panel_account))
        }
    }

    private fun footer() {
        val help = findViewById<View>(R.id.panel_help)
        val icon = findViewById<ImageView>(R.id.panel_help_icon)
        tintButton(icon, false)
        help.onFocusChangeListener = View.OnFocusChangeListener { _, focused -> tintButton(icon, focused) }
        help.setOnClickListener { launch(PanelTiles.help(), getString(R.string.panel_help)) }
    }

    private fun tintButton(icon: ImageView, focused: Boolean) {
        icon.imageTintList = ColorStateList.valueOf(getColor(if (focused) R.color.panel_button_icon_focused else R.color.panel_content))
    }

    // ---- The tiles ----

    private val step get() = px(R.dimen.panel_tile_width) + px(R.dimen.panel_tile_gap)

    private fun tiles() {
        grid = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val all = PanelTiles.columns()
        columns = all.size
        all.forEachIndexed { column, tiles ->
            tiles.forEachIndexed { row, tile ->
                val view = tileView(tile)
                val params = FrameLayout.LayoutParams(px(R.dimen.panel_tile_width), px(R.dimen.panel_tile_height))
                params.leftMargin = column * step
                params.topMargin = row * (px(R.dimen.panel_tile_height) + px(R.dimen.panel_tile_gap))
                grid.addView(view, params)
                view.setOnFocusChangeListener(chain(view.onFocusChangeListener) { focused -> if (focused) bringIntoView(column) })
            }
        }
        gridHolder.addView(grid, FrameLayout.LayoutParams(columns * step, ViewGroup.LayoutParams.MATCH_PARENT))
        // Columns out of view are cut off at the box's edge (clipChildren only keeps each child in its own bounds).
        gridHolder.outlineProvider = ViewOutlineProvider.BOUNDS
        gridHolder.clipToOutline = true
        dots()
    }

    private fun chain(first: View.OnFocusChangeListener?, then: (Boolean) -> Unit) =
        View.OnFocusChangeListener { v, focused ->
            first?.onFocusChange(v, focused)
            then(focused)
        }

    /**
     * Scrolls a column into view as a tile in it gets the focus, as the dashboard's grid
     * does: in to the right with a quick glide (85 ms, slowing), back to the left at once.
     */
    private fun bringIntoView(column: Int) {
        val to = when {
            column >= scroll + PanelTiles.COLUMNS_SHOWN -> column - PanelTiles.COLUMNS_SHOWN + 1
            column < scroll -> column
            else -> return
        }
        val forward = to > scroll
        scroll = to
        val x = (-to * step).toFloat()
        grid.animate().cancel()
        if (forward) {
            grid.animate().translationX(x).setDuration(SCROLL_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            grid.translationX = x
        }
        dots()
    }

    /** A dot for each place the tiles can scroll to; the one for where they are is grey. */
    private fun dots() {
        dotsView.removeAllViews()
        val places = (columns - PanelTiles.COLUMNS_SHOWN + 1).coerceAtLeast(1)
        dotsView.visibility = if (places < 2) View.INVISIBLE else View.VISIBLE
        if (places < 2) return
        val size = (DOT_DP * dp).roundToInt()
        for (i in 0 until places) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(if (i == scroll) R.color.panel_dot_current else R.color.panel_dot))
                }
            }
            dotsView.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginStart = (DOT_GAP_DP * dp / 2).roundToInt()
                marginEnd = (DOT_GAP_DP * dp / 2).roundToInt()
            })
        }
    }

    private fun tileView(tile: PanelTiles.Tile): View {
        val name = getString(tile.label)
        val view = if (tile === PanelTiles.SCREENSAVER) screensaverTile(name) else plainTile(tile, name)
        view.isFocusable = true
        view.contentDescription = name
        val open = tile.open
        view.setOnClickListener { if (open != null) launch(open(), name) else showSleepPage() }
        return view
    }

    private fun plainTile(tile: PanelTiles.Tile, name: String): View {
        val background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.panel_tile_radius)
            setColor(getColor(R.color.panel_tile))
        }
        val icon = ImageView(this).apply {
            tile.icon?.let(::setImageResource)
            val pad = (2 * dp).roundToInt()
            setPadding(pad, pad, pad, pad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = label(name)
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
        }
        // The Sleep timer tile says how long is left while it runs.
        val left = if (tile === PanelTiles.SLEEP_TIMER) {
            label(text = "").apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                visibility = View.GONE
            }.also { words.addView(it) }
        } else {
            null
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
            this.background = background
            addView(icon, LinearLayout.LayoutParams((20 * dp).roundToInt(), (20 * dp).roundToInt()))
            addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * dp).roundToInt()
            })
        }
        if (left != null) {
            sleepTile = view
            sleepLeft = left
        }
        val evaluator = ArgbEvaluator()
        val rest = getColor(R.color.panel_tile)
        val lit = getColor(R.color.panel_tile_focused)
        val content = getColor(R.color.panel_content)
        val textLit = getColor(R.color.panel_content_focused)
        val iconLit = getColor(R.color.panel_icon_focused)
        focusMotion(view) { f ->
            background.setColor(evaluator.evaluate(f, rest, lit) as Int)
            val text = evaluator.evaluate(f, content, textLit) as Int
            label.setTextColor(text)
            left?.setTextColor(text)
            icon.imageTintList = ColorStateList.valueOf(evaluator.evaluate(f, content, iconLit) as Int)
        }
        return view
    }

    /**
     * The Screensaver tile: its name over a moonlit range of hills, a sunset with the
     * focus (the dashboard's screensaver_unfocused and screensaver_focused), with a
     * white rim while it has the focus.
     */
    private fun screensaverTile(name: String): View {
        val rest = ImageView(this).apply {
            setImageResource(R.drawable.panel_screensaver)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val lit = ImageView(this).apply {
            setImageResource(R.drawable.panel_screensaver_focused)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        val radius = resources.getDimension(R.dimen.panel_tile_radius)
        val rim = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setStroke(dp.roundToInt().coerceAtLeast(1), getColor(android.R.color.white))
            }
            alpha = 0f
        }
        val label = label(name).apply { gravity = Gravity.CENTER }
        val view = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            addView(rest, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(lit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(rim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val evaluator = ArgbEvaluator()
        val content = getColor(R.color.panel_content)
        val white = getColor(android.R.color.white)
        focusMotion(view) { f ->
            lit.alpha = f
            rim.alpha = f
            label.setTextColor(evaluator.evaluate(f, content, white) as Int)
        }
        return view
    }

    private fun label(text: String, size: Float = 12f) = TextView(this).apply {
        this.text = text
        typeface = ResourcesCompat.getFont(this@SettingsMenuActivity, R.font.geist_medium)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(getColor(R.color.panel_content))
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Something with the focus grows to 1.025 and lights up in 300 ms (decelerating), and
     * goes back in 200 ms (accelerating), as the dashboard's tiles do. `paint` gets how lit it is.
     */
    private fun focusMotion(view: View, paint: (Float) -> Unit) {
        var lit = 0f
        var animator: ValueAnimator? = null
        paint(0f)
        view.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            animator?.cancel()
            animator = ValueAnimator.ofFloat(lit, if (focused) 1f else 0f).apply {
                duration = if (focused) FOCUS_IN_MS else FOCUS_OUT_MS
                interpolator = if (focused) DecelerateInterpolator() else AccelerateInterpolator()
                addUpdateListener {
                    lit = it.animatedValue as Float
                    val scale = 1f + (TILE_SCALE - 1f) * lit
                    view.scaleX = scale
                    view.scaleY = scale
                    paint(lit)
                }
                start()
            }
        }
    }

    // ---- The sleep timer ----

    private fun showSleepLeft() {
        val left = sleepLeft ?: return
        val text = SleepTimer.left(this)
        left.text = text.orEmpty()
        left.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** The Sleep timer page: the times, and Turn off timer first while one runs. */
    private fun showSleepPage() {
        val list = findViewById<LinearLayout>(R.id.panel_sleep_choices)
        list.removeAllViews()
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        if (SleepTimer.end(this) != null) rows += getString(R.string.panel_sleep_off) to { SleepTimer.cancel(this) }
        for (minutes in SleepTimer.CHOICES) rows += SleepTimer.name(resources, minutes) to { SleepTimer.set(this, minutes) }
        for ((name, choose) in rows) {
            list.addView(sleepRow(name, choose), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * dp).roundToInt()).apply {
                bottomMargin = (8 * dp).roundToInt()
            })
        }
        findViewById<View>(R.id.panel_scroll).visibility = View.INVISIBLE
        findViewById<View>(R.id.panel_footer).visibility = View.INVISIBLE
        sleepPage.visibility = View.VISIBLE
        list.getChildAt(0)?.requestFocus()
    }

    private fun leaveSleepPage() {
        sleepPage.visibility = View.GONE
        findViewById<View>(R.id.panel_scroll).visibility = View.VISIBLE
        findViewById<View>(R.id.panel_footer).visibility = View.VISIBLE
        showSleepLeft()
        sleepTile?.requestFocus()
    }

    private fun sleepRow(name: String, choose: () -> Unit): View {
        val background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.panel_tile_radius)
            setColor(getColor(R.color.panel_row))
        }
        val text = label(name, size = 14f)
        val view = FrameLayout(this).apply {
            this.background = background
            isFocusable = true
            contentDescription = name
            setPadding((12 * dp).roundToInt(), 0, (12 * dp).roundToInt(), 0)
            addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            setOnClickListener {
                choose()
                leaveSleepPage()
            }
        }
        val evaluator = ArgbEvaluator()
        val rest = getColor(R.color.panel_row)
        val lit = getColor(R.color.panel_tile_focused)
        val content = getColor(R.color.panel_row_text)
        val contentLit = getColor(R.color.panel_content_focused)
        focusMotion(view) { f ->
            background.setColor(evaluator.evaluate(f, rest, lit) as Int)
            text.setTextColor(evaluator.evaluate(f, content, contentLit) as Int)
        }
        return view
    }

    // ---- The notifications ----

    /** The notifications, dimmed to 60% while the focus is elsewhere in the panel, as there. */
    private fun notifications() {
        val list = findViewById<LinearLayout>(R.id.panel_notifications)
        list.alpha = NOTIFICATIONS_DIMMED
        list.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val inside = now != null && generateSequence(now.parent) { it.parent }.any { it === list }
            list.animate().alpha(if (inside) 1f else NOTIFICATIONS_DIMMED).setDuration(FOCUS_OUT_MS).start()
        }
        list.removeAllViews()
        val listed = PanelNotifications.current()
        listed.cards.forEach { addNotification(list, notificationView(it)) }
        if (listed.rest.isNotEmpty()) {
            // All notifications, with how many: it opens out into their cards.
            lateinit var row: View
            row = allNotificationsRow(listed.rest.size) {
                val at = list.indexOfChild(row)
                list.removeView(row)
                listed.rest.forEachIndexed { i, item -> addNotification(list, notificationView(item), at + i) }
                list.getChildAt(at)?.requestFocus()
            }
            addNotification(list, row)
        }
    }

    private fun addNotification(list: LinearLayout, view: View, at: Int = -1) {
        list.addView(view, at, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (NOTIFICATION_GAP_DP * dp).roundToInt()
        })
    }

    private fun allNotificationsRow(count: Int, open: () -> Unit): View {
        val view = notificationView(PanelNotifications.Item(getString(R.string.panel_all_notifications), "", null))
        val number = label(count.toString(), size = 13f).apply { gravity = Gravity.CENTER }
        (view as LinearLayout).addView(number, LinearLayout.LayoutParams((20 * dp).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        view.contentDescription = getString(R.string.panel_all_notifications) + ", " + count
        view.setOnClickListener { open() }
        return view
    }

    private fun notificationView(item: PanelNotifications.Item): View {
        val background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.panel_tile_radius)
            setColor(getColor(R.color.panel_notification))
        }
        val icon = ImageView(this).apply {
            // Lucide's bell for every notification: the app's icons are Lucide only.
            setImageResource(R.drawable.ic_panel_notification)
            val pad = (8 * dp).roundToInt()
            setPadding(pad, pad, pad, pad)
            this.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.panel_tile))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = label(item.title, size = 13f)
        val text = label(item.text, size = 11f).apply { alpha = 0.72f }
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            if (item.text.isNotEmpty()) {
                addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (4 * dp).roundToInt()
                })
            }
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 54 dp for a line, 56 for two, as the dashboard's cards.
            minimumHeight = (54 * dp).roundToInt()
            setPadding((11 * dp).roundToInt(), (10 * dp).roundToInt(), (11 * dp).roundToInt(), (10 * dp).roundToInt())
            this.background = background
            isFocusable = true
            contentDescription = listOf(item.title, item.text).filter { it.isNotEmpty() }.joinToString(". ")
            addView(icon, LinearLayout.LayoutParams((32 * dp).roundToInt(), (32 * dp).roundToInt()))
            addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * dp).roundToInt()
            })
        }
        val evaluator = ArgbEvaluator()
        val rest = getColor(R.color.panel_notification)
        val lit = getColor(R.color.panel_tile_focused)
        val content = getColor(R.color.panel_content)
        val textLit = getColor(R.color.panel_content_focused)
        val iconLit = getColor(R.color.panel_icon_focused)
        focusMotion(view) { f ->
            background.setColor(evaluator.evaluate(f, rest, lit) as Int)
            val c = evaluator.evaluate(f, content, textLit) as Int
            title.setTextColor(c)
            text.setTextColor(c)
            icon.imageTintList = ColorStateList.valueOf(evaluator.evaluate(f, content, iconLit) as Int)
        }
        view.setOnClickListener {
            if (item.open()) {
                motion?.cancel()
                finishNow()
            }
        }
        return view
    }

    private fun px(dimen: Int) = resources.getDimensionPixelSize(dimen)

    companion object {
        /** The dashboard's slide in: 300 ms (its integer 0x7f0c0026) on its curve (anim 0x7f01006f). */
        const val OPEN_MS = 300L
        val OPEN_CURVE: Interpolator = PathInterpolator(0.12f, 1f, 0.4f, 1f)

        /** Its window's exit: 500 ms (config_longAnimTime) on the reverse curve (anim 0x7f010070). */
        const val CLOSE_MS = 500L
        val CLOSE_CURVE: Interpolator = PathInterpolator(0.4f, 1f, 0.12f, 1f)

        /** What's behind is dimmed to 60%. */
        const val DIM = 0.6f

        /** dashboard_tile_scale and the tiles' focus timing. */
        const val TILE_SCALE = 1.025f
        const val FOCUS_IN_MS = 300L
        const val FOCUS_OUT_MS = 200L

        /** The tiles' glide a column to the right, as measured on the TV. */
        const val SCROLL_MS = 85L

        const val LABEL_MS = 150L
        const val DOT_DP = 6f
        const val DOT_GAP_DP = 4f
        const val NOTIFICATION_GAP_DP = 8f
        const val NOTIFICATIONS_DIMMED = 0.6f

        /** Opens the panel over whatever is on screen. NO_ANIMATION: the panel moves itself. */
        fun open(context: Context) {
            context.startActivity(
                Intent(context, SettingsMenuActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
