package com.mangoscreencast.tv.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.mangoscreencast.tv.R

/**
 * The gear panel's tiles, in the Hisense panel's order, and the TV's own screen each
 * one opens: Hisense's Inputs, Picture and Sound (com.vt.*), the TV's settings
 * (com.android.tv.settings, which has to be on) for the screensaver, Wi-Fi,
 * Bluetooth and Accessibility, and Google Home's smart-home screen in the
 * screensaver app. The sleep timer is the panel's own (SleepTimer).
 *
 * The tiles run down the columns, four to a column, two columns in view: as on
 * the Hisense panel, Right from the second column brings the next one in.
 */
internal object PanelTiles {
    const val TV_SETTINGS = "com.android.tv.settings"
    const val HISENSE_SETTINGS = "com.vt.customsettings"
    const val HISENSE_INPUTS = "com.vt.tv.input"
    const val GOOGLE_SCREENSAVER = "com.google.android.apps.tv.dreamx"

    /** Four tiles down a column, two columns in view. */
    const val ROWS = 4
    const val COLUMNS_SHOWN = 2

    /** A tile; `open` is the TV's screen for it, or null for one the panel handles itself. */
    data class Tile(val label: Int, val icon: Int?, val open: (() -> Intent)?)

    /** Opens the screensaver now (TvSettings' DaydreamVoiceAction, the "go to sleep" voice action). */
    val SCREENSAVER = Tile(R.string.panel_screensaver, null) {
        Intent("com.google.android.pano.action.SLEEP").setPackage(TV_SETTINGS)
    }

    /** The panel's own page of times (SleepTimer). */
    val SLEEP_TIMER = Tile(R.string.panel_sleep_timer, R.drawable.ic_panel_sleep_timer, null)

    /** In the panel's order: down the first column, then the second, then the third. */
    val all: List<Tile> = listOf(
        SCREENSAVER,
        Tile(R.string.panel_picture, R.drawable.ic_panel_picture) {
            Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage(HISENSE_SETTINGS)
        },
        Tile(R.string.panel_wifi, R.drawable.ic_panel_wifi) {
            Intent(Settings.ACTION_WIFI_SETTINGS).setPackage(TV_SETTINGS)
        },
        Tile(R.string.panel_bluetooth, R.drawable.ic_panel_bluetooth) {
            // Remotes & Accessories, the slice the TV's Bluetooth tile showed.
            Intent("android.settings.SLICE_SETTINGS")
                .setData(Uri.parse("content://com.android.tv.settings.accessories.sliceprovider/general"))
                .setPackage(TV_SETTINGS)
        },
        Tile(R.string.panel_inputs, R.drawable.ic_panel_inputs) {
            Intent("com.android.tv.action.VIEW_INPUTS").setPackage(HISENSE_INPUTS)
        },
        Tile(R.string.panel_sound, R.drawable.ic_panel_sound) {
            Intent(Settings.ACTION_SOUND_SETTINGS).setPackage(HISENSE_SETTINGS)
        },
        Tile(R.string.panel_google_home, R.drawable.ic_panel_google_home) {
            Intent("com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME").setPackage(GOOGLE_SCREENSAVER)
        },
        SLEEP_TIMER,
        Tile(R.string.panel_accessibility, R.drawable.ic_panel_accessibility) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).setPackage(TV_SETTINGS)
        },
    )

    /** The tiles column by column. */
    fun columns(tiles: List<Tile> = all): List<List<Tile>> = tiles.chunked(ROWS)

    /** All settings: the TV's settings home. */
    fun allSettings(): Intent = Intent(Settings.ACTION_SETTINGS).setPackage(TV_SETTINGS)

    /** Help: the TV's Help & feedback (HELP_SETTINGS_PERMISSION, which Mango declares and holds). */
    fun help(): Intent = Intent("com.android.settings.action.HELP_SETTINGS").setPackage(TV_SETTINGS)

    /** The account: the TV's Accounts & sign-in, to add or manage one. */
    fun account(): Intent = Intent(Settings.ACTION_ADD_ACCOUNT).setPackage(TV_SETTINGS)
}
