package com.mangoscreencast.tv.apps

import androidx.annotation.DrawableRes
import com.mangoscreencast.tv.R

/** A streaming app the home screen has a tile for. */
data class StreamingApp(
    val name: String,
    /** Its logo, from the service's own artwork (see res/drawable-xxxhdpi/app_*.png). */
    @DrawableRes val logo: Int,
    /**
     * Its packages, the one to install first: the Google TV / Android TV
     * version, then Fire TV's where it differs, then the phone version that
     * some TVs have installed instead. Each must also be under <queries> in
     * the manifest, or Android 11 and later hide it from this app.
     */
    val packages: List<String>,
    /** The Amazon Appstore's package, for installing it on a Fire TV. */
    val fireTvPackage: String = packages.first(),
)

object StreamingApps {
    val ALL = listOf(
        StreamingApp("Netflix", R.drawable.app_netflix, listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        StreamingApp("YouTube", R.drawable.app_youtube,
            listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube"),
            fireTvPackage = "com.amazon.firetv.youtube"),
        StreamingApp("HBO Max", R.drawable.app_hbomax, listOf("com.wbd.stream")),
        StreamingApp("Paramount+", R.drawable.app_paramountplus, listOf("com.cbs.ott", "com.cbs.app")),
        StreamingApp("Peacock", R.drawable.app_peacock, listOf("com.peacocktv.peacockandroid")),
        StreamingApp("Prime Video", R.drawable.app_primevideo,
            listOf("com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
            fireTvPackage = "com.amazon.firebat"),
        StreamingApp("Hulu", R.drawable.app_hulu, listOf("com.hulu.livingroomplus", "com.hulu.plus"),
            fireTvPackage = "com.hulu.plus"),
        StreamingApp("Disney+", R.drawable.app_disneyplus, listOf("com.disney.disneyplus")),
        StreamingApp("Tubi", R.drawable.app_tubi, listOf("com.tubitv")),
    )
}
