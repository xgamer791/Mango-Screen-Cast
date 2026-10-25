package com.mangoscreencast.tv.apps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLauncherTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val packages = shadowOf(context.packageManager)

    private fun launcher(onFireTv: Boolean = false) = AppLauncher(context.packageManager, onFireTv)

    /** Installs `packageName` the way its app ships: a TV app on the TV home screen, a phone app in the app list. */
    private fun install(packageName: String, tv: Boolean = true) {
        packages.installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
        })
        val activity = ComponentName(packageName, "$packageName.Main")
        packages.addActivityIfNotPresent(activity)
        packages.addIntentFilterForActivity(activity, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(if (tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        })
    }

    private fun opened(): Intent = shadowOf(context).nextStartedActivity

    @Test
    fun theNineApps() {
        assertEquals(
            listOf("Netflix", "YouTube", "HBO Max", "Paramount+", "Peacock", "Prime Video", "Hulu", "Disney+", "Tubi"),
            StreamingApps.ALL.map { it.name },
        )
    }

    @Test
    fun everyAppStartsWhenItsTvVersionIsInstalled() {
        for (app in StreamingApps.ALL) {
            val tvPackage = app.packages.first()
            install(tvPackage)
            val entry = launcher().entry(app) as AppLauncher.Entry.Installed
            assertEquals(app.name, tvPackage, entry.packageName)
            assertTrue(app.name, launcher().open(context, entry))
            val started = opened()
            assertEquals(app.name, tvPackage, started.component?.packageName)
            assertTrue(app.name, started.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        }
    }

    @Test
    fun theFireTvAndPhoneVersionsStartToo() {
        for (app in StreamingApps.ALL) {
            for (packageName in app.packages.drop(1)) {
                val fresh = app.copy(packages = listOf(app.packages.first(), packageName))
                install(packageName, tv = false)
                val entry = launcher().entry(fresh) as AppLauncher.Entry.Installed
                assertEquals(packageName, entry.packageName)
                assertTrue(launcher().open(context, entry))
                assertEquals(packageName, opened().component?.packageName)
            }
        }
    }

    @Test
    fun theTvVersionWinsOverThePhoneOne() {
        install("com.netflix.mediaclient", tv = false)
        install("com.netflix.ninja")
        val netflix = StreamingApps.ALL.first { it.name == "Netflix" }
        assertEquals("com.netflix.ninja", (launcher().entry(netflix) as AppLauncher.Entry.Installed).packageName)
    }

    @Test
    fun aMissingAppOpensItsGooglePlayPage() {
        for (app in StreamingApps.ALL) {
            val entry = launcher().entry(app) as AppLauncher.Entry.Missing
            assertTrue(app.name, launcher().open(context, entry))
            assertEquals("market://details?id=${app.packages.first()}", opened().dataString)
        }
    }

    @Test
    fun onAFireTvItOpensTheAmazonAppstore() {
        val youtube = StreamingApps.ALL.first { it.name == "YouTube" }
        val entry = launcher(onFireTv = true).entry(youtube) as AppLauncher.Entry.Missing
        assertEquals(
            listOf(
                "amzn://apps/android?p=com.amazon.firetv.youtube",
                "market://details?id=com.google.android.youtube.tv",
                "https://play.google.com/store/apps/details?id=com.google.android.youtube.tv",
            ),
            entry.store.map { it.dataString },
        )
    }

    @Test
    fun withoutAStoreItTriesTheNextWayThenGivesUp() {
        shadowOf(context).checkActivities(true)
        val tubi = launcher().entry(StreamingApps.ALL.first { it.name == "Tubi" }) as AppLauncher.Entry.Missing
        assertFalse("nothing on the TV opens a store page", launcher().open(context, tubi))

        // Only a browser: the web page.
        val browser = ComponentName("com.example.browser", "com.example.browser.Main")
        packages.addActivityIfNotPresent(browser)
        packages.addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
        })
        assertTrue(launcher().open(context, tubi))
        assertEquals("https://play.google.com/store/apps/details?id=com.tubitv", opened().dataString)
    }

    /** Android 11 and later hide every package not declared, so a missing one would never show as installed. */
    @Test
    fun everyPackageIsDeclaredInTheManifest() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries").item(0)
        val declared = (0 until queries.childNodes.length).map { queries.childNodes.item(it) }
            .filter { it.nodeName == "package" }
            .map { it.attributes.getNamedItem("android:name").nodeValue }
            .toSet()
        val needed = StreamingApps.ALL.flatMap { it.packages + it.fireTvPackage }.toSet()
        assertEquals(emptySet<String>(), needed - declared)
    }
}
