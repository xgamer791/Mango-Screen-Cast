package com.mangoscreencast.tv.apps

import androidx.annotation.DrawableRes
import com.mangoscreencast.tv.R

/** A streaming app the home screen has a tile for. */
data class StreamingApp(
    val name: String,
    /**
     * Its logo in its tile's colours, at exactly the pixels it covers on Home's 1440p page: drawn from the
     * service's own vector artwork at 4x, brought down with Lanczos and sharpened, so its edges stay crisp
     * after the TV scales the page up to its 4K panel (res/drawable-nodpi/app_*.png).
     */
    @DrawableRes val logo: Int,
    /** Its tile, as Roku draws the service's: its own colours (res/drawable/tile_bg_*.xml). */
    @DrawableRes val tile: Int,
    /**
     * Its packages, the one to install first: the Google TV / Android TV
     * version, then Fire TV's where it differs, then the phone version that
     * some TVs have installed instead. Each must also be under <queries> in
     * the manifest, or Android 11 and later hide it from this app.
     */
    val packages: List<String>,
    /** The Amazon Appstore's package, for installing it on a Fire TV. */
    val fireTvPackage: String = packages.first(),
    /** The most of the tile's height the logo takes (its width is at most 78% of the tile's). */
    val logoHeight: Float = 0.5f,
)

object StreamingApps {
    val ALL = listOf(
        StreamingApp("Netflix", R.drawable.app_netflix, R.drawable.tile_bg_white,
            listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        StreamingApp("YouTube", R.drawable.app_youtube, R.drawable.tile_bg_white,
            listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube"),
            fireTvPackage = "com.amazon.firetv.youtube"),
        StreamingApp("HBO Max", R.drawable.app_hbomax, R.drawable.tile_bg_dark, listOf("com.wbd.stream"),
            logoHeight = 0.42f),
        StreamingApp("Paramount+", R.drawable.app_paramountplus, R.drawable.tile_bg_paramount,
            listOf("com.cbs.ott", "com.cbs.app"), logoHeight = 0.48f),
        StreamingApp("Peacock", R.drawable.app_peacock, R.drawable.tile_bg_peacock, listOf("com.peacocktv.peacockandroid")),
        StreamingApp("Prime Video", R.drawable.app_primevideo, R.drawable.tile_bg_prime,
            listOf("com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
            fireTvPackage = "com.amazon.firebat"),
        StreamingApp("Hulu", R.drawable.app_hulu, R.drawable.tile_bg_dark, listOf("com.hulu.livingroomplus", "com.hulu.plus"),
            fireTvPackage = "com.hulu.plus"),
        StreamingApp("Disney+", R.drawable.app_disneyplus, R.drawable.tile_bg_disney, listOf("com.disney.disneyplus"),
            logoHeight = 0.62f),
        StreamingApp("Tubi", R.drawable.app_tubi, R.drawable.tile_bg_white, listOf("com.tubitv")),
    )
}
