package dev.kestrel.app.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import dev.kestrel.app.KestrelAccessibilityService

/** Reads what Kestrel can find out about its own setup, for the setup checklist (design spec, section 15). */
class SetupStatusReader(private val context: Context) {
    fun read(): SetupStatus {
        val services = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }
        return SetupStatus(
            serviceOn = ComponentName(context, KestrelAccessibilityService::class.java) in services,
            microphoneAllowed = granted(Manifest.permission.RECORD_AUDIO),
            notificationsAllowed = granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            voiceAccessOn = services.any { it.packageName == VOICE_ACCESS },
        )
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val VOICE_ACCESS = "com.google.android.apps.accessibility.voiceaccess"
    }
}
