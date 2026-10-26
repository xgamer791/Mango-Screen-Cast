package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent

/**
 * Backup path when a remote delivers the gear as [KeyEvent.KEYCODE_SETTINGS].
 * Starts Settings by explicit component. The Hisense gear never arrives here.
 */
internal fun Activity.consumeGearKey(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_SETTINGS) return false
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
    return true
}
