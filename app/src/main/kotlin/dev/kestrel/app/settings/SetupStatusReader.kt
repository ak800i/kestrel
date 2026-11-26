package dev.kestrel.app.settings

import android.Manifest
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import dev.kestrel.app.AccessibilitySettings
import dev.kestrel.datatools.notifications.KestrelNotificationListener

/** Reads what Kestrel can find out about its own setup, for the setup checklist (design spec, section 15). */
class SetupStatusReader(private val context: Context) {
    fun read(): SetupStatus {
        val kestrel = AccessibilitySettings.kestrelComponent(context)
        // Only the services that Android runs.
        val services = AccessibilitySettings.running(context)
        val service = AccessibilitySettings.kestrel(context, services)
        return SetupStatus(
            serviceOn = service.running,
            microphoneAllowed = granted(Manifest.permission.RECORD_AUDIO),
            notificationsAllowed = granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            voiceAccessOn = services.any { it.packageName == VOICE_ACCESS },
            serviceEnabled = service.enabled,
            batteryRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            callsAllowed = granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS),
            assistantRole = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT),
            shortcutTarget = AccessibilitySettings.names(context, SHORTCUT_TARGET, kestrel),
            buttonTarget = AccessibilitySettings.names(context, BUTTON_TARGETS, kestrel) ||
                AccessibilitySettings.names(context, GESTURE_TARGETS, kestrel),
            notificationAccess = KestrelNotificationListener.hasAccess(context),
        )
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val VOICE_ACCESS = "com.google.android.apps.accessibility.voiceaccess"

        // The targets of the accessibility shortcut, and of the accessibility button with the navigation bar's buttons or
        // with gesture navigation (research R14).
        const val SHORTCUT_TARGET = "accessibility_shortcut_target_service"
        const val BUTTON_TARGETS = "accessibility_button_targets"
        const val GESTURE_TARGETS = "accessibility_gesture_targets"
    }
}
