package dev.kestrel.datatools.location

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `get_location` (design D12 of sub-project 5; research r58): one fresh fix from the GPS provider alone, with high
 * accuracy, waiting at most [WAIT_MILLIS]; never the network or fused provider and never `Geocoder`, which can send the
 * location to Google (spec 13.1). Without a fix it gives the phone's last GPS fix with its age. [granted] tells whether
 * Kestrel holds a permission; with only coarse access the location is approximate. The coordinates are Kestrel's own
 * text, so the line has no untrusted field.
 */
class GpsLocation(private val manager: LocationManager, private val granted: (String) -> Boolean) {
    suspend fun current(): DataResult {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return DataResult.NeedsPermission(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return DataResult.Failed(LOCATION_OFF)
        val started = SystemClock.elapsedRealtime()
        val fresh = try {
            fix()
        } catch (e: SecurityException) {
            return DataResult.Failed(BACKGROUND)
        }
        if (fresh != null) return result(fresh, approximate = !fine, note = null)
        if (refusedInBackground(SystemClock.elapsedRealtime() - started)) return DataResult.Failed(BACKGROUND)
        val last = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            null
        } ?: return DataResult.Failed(NO_FIX)
        return result(last, approximate = !fine, note = LAST_FIX)
    }

    private suspend fun fix(): Location? = withTimeoutOrNull(WAIT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            val request = LocationRequest.Builder(0).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setDurationMillis(WAIT_MILLIS).build()
            try {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, request, signal, Executor { it.run() }) { continuation.resume(it) }
            } catch (e: SecurityException) {
                continuation.resumeWithException(e)
            }
        }
    }

    private fun result(location: Location, approximate: Boolean, note: String?): DataResult {
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        val line = line(location.latitude, location.longitude, location.accuracy, age.coerceAtLeast(0), approximate)
        return DataResult.Ok(listOf(DataLine(line, emptyList())), note = note)
    }

    companion object {
        const val WAIT_MILLIS = 30_000L

        /** A null answer sooner than this is Android's refusal, not a search for satellites that found none. */
        const val QUICK_NULL_MILLIS = 2_000L

        const val NO_FIX = "No satellite fix; try near a window or outdoors."
        const val LAST_FIX = "No satellite fix now; this is the phone's last satellite fix."
        const val BACKGROUND = "no location: Android did not allow it in the background"
        const val LOCATION_OFF = "the phone's location is turned off"

        fun line(latitude: Double, longitude: Double, accuracyMetres: Float, ageMillis: Long, approximate: Boolean): String =
            String.format(Locale.ROOT, "latitude %.5f, longitude %.5f, accuracy %d m, %s old, approximate: %b", latitude, longitude, accuracyMetres.toInt(), age(ageMillis), approximate)

        fun age(millis: Long): String = when {
            millis < 60_000 -> "${millis / 1_000} s"
            millis < 3_600_000 -> "${millis / 60_000} min"
            else -> "${millis / 3_600_000} h"
        }

        fun refusedInBackground(nullAfterMillis: Long): Boolean = nullAfterMillis < QUICK_NULL_MILLIS
    }
}
