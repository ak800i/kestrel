package dev.kestrel.app.agent

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.DataResult
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.datatools.AndroidDataPort
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * get_location while another app is in front (design D12 and D32 item 45 of sub-project 5; spike S1): the test makes
 * Kestrel's package the mock-location app through its shell, adds a test provider for `gps` with a fictional public place,
 * opens Android's Settings, checks through Kestrel's own snapshot that Settings is the active window, and reads the
 * location through the data tool, which uses only `GPS_PROVIDER`. It puts back the app-op, the provider and the location
 * switch, also after a failure. It grants Kestrel's location permission itself; Task 19's step takes it back after the
 * run, because a test cannot take a permission back without ending Kestrel's process.
 */
@RunWith(AndroidJUnit4::class)
class AgentLocationTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun getLocationAnswersWithSettingsInFront() = runBlocking {
        screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.ACCESS_FINE_LOCATION)
        screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        val locationWasOn = manager.isLocationEnabled
        val provider = LocationManager.GPS_PROVIDER
        var fixes: Job? = null
        // Every change below is put back in the finally block, also when one of them fails.
        try {
            if (!locationWasOn) screen.shell("cmd location set-location-enabled true")
            screen.shell("appops set $KESTREL android:mock_location allow")
            manager.addTestProvider(provider, ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE).build())
            manager.setTestProviderEnabled(provider, true)
            // Off the test's own thread, which the waits below block.
            fixes = launch(Dispatchers.Default) {
                while (isActive) {
                    manager.setTestProviderLocation(provider, Location(provider).apply {
                        latitude = TEST_LATITUDE
                        longitude = TEST_LONGITUDE
                        accuracy = 5f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    })
                    delay(500)
                }
            }
            screen.shell("am start -W -a android.settings.SETTINGS")
            // In front by Kestrel's own snapshot's active window, not by the script (D32 item 45).
            check(VoiceTests.waitUntil(10_000) {
                runBlocking { ScreenTree.frontWindow(screen.session.snapshots.current().snapshot)?.packageName } == SETTINGS
            }) { "Settings did not come to the front" }

            val result = AndroidDataPort(context, open = { false }, isAgentFree = { false }).getLocation()

            assertTrue(result is DataResult.Ok)
        } finally {
            fixes?.cancelAndJoin()
            runCatching { manager.removeTestProvider(provider) }
            screen.shell("appops set $KESTREL android:mock_location default")
            if (!locationWasOn) screen.shell("cmd location set-location-enabled false")
            screen.shell("am force-stop $SETTINGS")
        }
    }

    private companion object {
        const val SETTINGS = "com.android.settings"

        // A public place in Belgrade (Slavija), fictional for the test's purpose (the owner's answer to Q4).
        const val TEST_LATITUDE = 44.8027
        const val TEST_LONGITUDE = 20.4664
    }
}
