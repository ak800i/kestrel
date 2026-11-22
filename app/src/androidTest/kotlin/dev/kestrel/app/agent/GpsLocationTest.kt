package dev.kestrel.app.agent

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.DataResult
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.datatools.location.GpsLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GpsLocation itself in Kestrel's process, whose accessibility service keeps it in the foreground (design D12 of
 * sub-project 5; spike S1), against a test provider for `gps` with a fictional public place: with coarse access only it
 * gives Android's approximate fix, with fine access the fix itself, and without a fresh fix within its wait the phone's
 * last fix with its age. It grants coarse access first, so Task 19's step takes both location permissions back before its
 * run, which it gives on its own, because a test cannot take a permission back without ending Kestrel's process; the test
 * skips itself when they are granted. It puts back the app-op, the provider and the location switch, also after a failure.
 * `:core:datatools`' DataToolsTest checks the quick refusal in the background.
 */
@RunWith(AndroidJUnit4::class)
class GpsLocationTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun held(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Test
    fun aCoarseFixAFineFixAndThePhonesLastFix() = runBlocking {
        assumeTrue(
            "Revoke Kestrel's location permissions before this test, as plan 5B's Task 19 does",
            !held(Manifest.permission.ACCESS_FINE_LOCATION) && !held(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        val manager = context.getSystemService(LocationManager::class.java)
        val locationWasOn = manager.isLocationEnabled
        val gps = GpsLocation(manager, ::held)
        var fixes: Job? = null
        try {
            if (!locationWasOn) screen.shell("cmd location set-location-enabled true")
            screen.shell("appops set $KESTREL android:mock_location allow")
            manager.addTestProvider(GPS, ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE).build())
            manager.setTestProviderEnabled(GPS, true)
            // Off the test's own thread, which GpsLocation's wait blocks.
            fixes = launch(Dispatchers.Default) {
                while (isActive) {
                    manager.setTestProviderLocation(GPS, fix(ageMillis = 0))
                    delay(500)
                }
            }

            screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.ACCESS_COARSE_LOCATION)
            val coarse = gps.current() as DataResult.Ok
            assertTrue(coarse.lines.single().fixed.endsWith("approximate: true"))
            assertNull(coarse.note)

            screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.ACCESS_FINE_LOCATION)
            val fine = gps.current() as DataResult.Ok
            assertTrue(fine.lines.single().fixed.startsWith(PLACE))
            assertTrue(fine.lines.single().fixed.endsWith("approximate: false"))
            assertNull(fine.note)

            // No fresh fix: a new test provider, whose only fix is two minutes old, older than what getCurrentLocation gives
            // at once; Android keeps the newest fix of a provider, so the old one needs a provider of its own.
            fixes.cancelAndJoin()
            manager.removeTestProvider(GPS)
            manager.addTestProvider(GPS, ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE).build())
            manager.setTestProviderEnabled(GPS, true)
            manager.setTestProviderLocation(GPS, fix(ageMillis = 120_000))
            val waited = SystemClock.elapsedRealtime()
            val last = gps.current() as DataResult.Ok
            assertTrue(SystemClock.elapsedRealtime() - waited >= GpsLocation.QUICK_NULL_MILLIS)
            assertEquals(GpsLocation.LAST_FIX, last.note)
            assertTrue(last.lines.single().fixed.startsWith("$PLACE 2 min old"))
        } finally {
            fixes?.cancelAndJoin()
            runCatching { manager.removeTestProvider(GPS) }
            screen.shell("appops set $KESTREL android:mock_location default")
            if (!locationWasOn) screen.shell("cmd location set-location-enabled false")
        }
    }

    private fun fix(ageMillis: Long) = Location(GPS).apply {
        latitude = TEST_LATITUDE
        longitude = TEST_LONGITUDE
        accuracy = 5f
        time = System.currentTimeMillis() - ageMillis
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMillis * 1_000_000
    }

    private companion object {
        const val GPS = LocationManager.GPS_PROVIDER

        // The public place of AgentLocationTest.
        const val TEST_LATITUDE = 44.8027
        const val TEST_LONGITUDE = 20.4664
        const val PLACE = "latitude 44.80270, longitude 20.46640, accuracy 5 m,"
    }
}
