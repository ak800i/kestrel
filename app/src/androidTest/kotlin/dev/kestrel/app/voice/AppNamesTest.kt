package dev.kestrel.app.voice

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.LauncherAppLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names of the apps that Kestrel boosts (design spec, section 8.3), read from the device's launcher. It needs no
 * models. Its messages and its log line give only counts, positions, times and classes, never a name (section 13.5).
 */
class AppNamesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theBoostWordsNameTheAppsThatTheLauncherShows() = runBlocking {
        val failures = mutableListOf<Throwable>()

        val started = SystemClock.elapsedRealtime()
        val labels = LauncherAppLabels(context).read()
        val millis = SystemClock.elapsedRealtime() - started
        val words = BoostWordSource(LauncherAppLabels(context)) { _, error -> failures += error }.words()
        VoiceTests.log(
            "App names: ${labels.size} launcher apps (${labels.count { it.system }} system apps) read in $millis ms; " +
                "${words.size} boost words",
        )

        assertTrue("Reading the names failed: ${failures.map { it.javaClass.name }}", failures.isEmpty())
        assertTrue("The first of ${words.size} boost words is not Kestrel", words.first() == BoostWordSource.NAME)
        // Gradle installs the fixture app (:testapp) before :app's device tests. Kestrel sees it only through the
        // <queries> element of its manifest. Android shows the Settings app to every app, so Settings cannot show it.
        val fixtures = words.indexOf(FIXTURES)
        // The Settings app's label as this device shows it, so that the test also holds in another language.
        val packages = context.packageManager
        val settingsLabel = checkNotNull(
            packages.getLaunchIntentForPackage(SETTINGS_PACKAGE)
                ?.let { packages.resolveActivity(it, PackageManager.ResolveInfoFlags.of(0)) }
                ?.loadLabel(packages)
                ?.toString(),
        ) { "The Settings app has no launcher activity" }
        val settings = words.indexOf(settingsLabel)
        assertTrue("The ${words.size} boost words do not name the fixture app", fixtures > 0)
        assertTrue("The ${words.size} boost words do not name the Settings app", settings > 0)
        // The owner's apps come before the system apps.
        assertTrue("The fixture app is boost word $fixtures, after Settings at $settings", fixtures < settings)
        assertTrue(
            "Of ${labels.size} launcher apps, ${labels.count { it.system }} are system apps, and Settings is not one of them",
            labels.any { it.label == settingsLabel && it.system },
        )
    }

    private companion object {
        // The label in testapp/src/main/AndroidManifest.xml, which no translation changes.
        const val FIXTURES = "Kestrel fixtures"
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
