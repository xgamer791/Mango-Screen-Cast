package com.mangoscreencast.tv.ui

import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.apps.StreamingApp
import com.mangoscreencast.tv.apps.StreamingApps
import kotlin.math.roundToInt

/** Fills the home screen's row with a tile per streaming app: its logo, and OK calls `open`. */
object AppTiles {
    private const val GAP_DP = 12

    fun fill(row: LinearLayout, open: (StreamingApp) -> Unit) {
        val inflater = LayoutInflater.from(row.context)
        val gap = (GAP_DP * row.resources.displayMetrics.density).roundToInt()
        for ((index, app) in StreamingApps.ALL.withIndex()) {
            val tile = inflater.inflate(R.layout.item_app_tile, row, false)
            if (index > 0) (tile.layoutParams as LinearLayout.LayoutParams).marginStart = gap
            tile.findViewById<ImageView>(R.id.app_logo).setImageResource(app.logo)
            tile.contentDescription = app.name
            tile.setOnClickListener { open(app) }
            row.addView(tile)
        }
    }
}
