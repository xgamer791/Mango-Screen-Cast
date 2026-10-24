package com.mangoscreencast.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParseTest {
    private val base = "https://github.com/owner/repo/releases/latest/download/"
    private val sha = "a".repeat(64)

    @Test
    fun readsWhatCiPublishes() {
        val release = AppUpdater.parse(
            """{"versionCode":1042,"versionName":"1.2.42","apk":"MangoScreenCast.apk","sha256":"$sha","size":12345678}""",
            base,
        )!!
        assertEquals(1042, release.versionCode)
        assertEquals("1.2.42", release.versionName)
        assertEquals(base + "MangoScreenCast.apk", release.apkUrl)
        assertEquals(sha, release.sha256)
        assertEquals(12345678L, release.size)
    }

    @Test
    fun keepsAbsoluteApkUrls() {
        val release = AppUpdater.parse(
            """{"versionCode":5,"apk":"https://example.com/a.apk","sha256":"$sha"}""", base,
        )!!
        assertEquals("https://example.com/a.apk", release.apkUrl)
        assertEquals("5", release.versionName)
        assertEquals(0L, release.size)
    }

    @Test
    fun rejectsIncompleteManifests() {
        assertNull(AppUpdater.parse("""{"versionName":"1.0","sha256":"$sha"}""", base))
        assertNull(AppUpdater.parse("""{"versionCode":7,"sha256":"abc"}""", base))
    }
}
