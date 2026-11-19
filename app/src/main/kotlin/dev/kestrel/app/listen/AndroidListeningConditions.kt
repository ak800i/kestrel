package dev.kestrel.app.listen

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * What Kestrel reads about the phone to say why it stopped (design spec, section 14.6), through Android's services. Any
 * thread may call it: each answer is a binder call.
 */
internal class AndroidListeningConditions(private val context: Context) : ListeningConditions {
    private val activities = context.getSystemService(ActivityManager::class.java)

    override fun microphoneAllowed() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun backgroundRestricted() = activities.isBackgroundRestricted

    // The service's type is a binder call with the service's token (prototype P2).
    override fun demoted(service: Any) = demotedFrom(listedForeground(), (service as ListeningService).foregroundServiceType)

    /**
     * Whether Android runs ListeningService in the foreground, from Android's list of Kestrel's running services, or
     * null when the list does not name the service. Android still lists an app's own services, and the list's
     * foreground field is the service record's isForeground (AOSP ActiveServices, android13-release).
     */
    @Suppress("DEPRECATION")
    fun listedForeground(): Boolean? = activities.getRunningServices(Int.MAX_VALUE)
        .firstOrNull { it.service.packageName == context.packageName && it.service.className == SERVICE }
        ?.foreground

    private companion object {
        val SERVICE: String = ListeningService::class.java.name
    }
}

/**
 * Whether Android took the foreground from ListeningService (design spec, section 14.6): from [listedForeground], the
 * foreground field of Android's list of Kestrel's running services (null when the list does not name the service), and
 * [type], the service's foreground service type. Android 13 keeps the type when it takes the foreground away, so there
 * only the list tells (analysis of the A71 session of sub-project 2, items 1 and 2); Android 14 and later also set the
 * type to 0.
 */
internal fun demotedFrom(listedForeground: Boolean?, type: Int): Boolean = listedForeground != true || type == 0
