package com.mangoscreencast.tv.browse

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mangoscreencast.tv.R

/**
 * One show or film on a browse page. `on` is where it plays as people say it
 * ("Netflix", or a network such as "NBC" on the Live TV page); `opens` is the
 * app OK starts: one of StreamingApps by name, or "Live TV" for the TV's own
 * tuner. `link`, when there is one, is the title's page on that service, which
 * its TV app opens straight to the show.
 */
data class Show(
    val key: String,
    val title: String,
    val kind: String,
    val years: String,
    val genres: String,
    val on: String,
    val opens: String,
    val about: String,
    @DrawableRes val art: Int,
    @DrawableRes val tile: Int,
    val link: String?,
) {
    /** "Series", "Since 2023" and "Drama, Action", spaced apart rather than joined with dots. */
    val meta: String get() = listOf(kind, years, genres).filter { it.isNotBlank() }.joinToString(META_GAP)

    companion object {
        const val LIVE_TV = "Live TV"
        const val META_GAP = " "
    }
}

/** A row of a browse page: its heading, and its shows in order. */
data class Shelf(val title: String, val keys: List<String>) {
    val shows: List<Show> get() = keys.map(Catalog::show)
}

/** The pages the home screen's menu opens, in the menu's order. */
enum class Page(@StringRes val title: Int) {
    WHAT_TO_WATCH(R.string.menu_what_to_watch),
    FEATURED_FREE(R.string.menu_featured_free),
    LIVE_TV(R.string.menu_live_tv),
    SPORTS(R.string.menu_sports),
    SEARCH(R.string.menu_search),
    STORE(R.string.menu_store),
}
