package dev.kestrel.app.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.kestrel.app.KestrelAccessibilityService
import dev.kestrel.datatools.notifications.KestrelNotificationListener

/** Reads what Kestrel can find out about its own setup, for the setup checklist (design spec, section 15). */
class SetupStatusReader(private val context: Context) {
    fun read(): SetupStatus {
        val kestrel = ComponentName(context, KestrelAccessibilityService::class.java)
        val service = KestrelAccessibilityService.state(context)
        // Only the services that Android runs.
        val services = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }
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
            shortcutTarget = names(SHORTCUT_TARGET, kestrel),
            buttonTarget = names(BUTTON_TARGETS, kestrel) || names(GESTURE_TARGETS, kestrel),
            notificationAccess = KestrelNotificationListener.hasAccess(context),
        )
    }

    // Whether the secure setting [name], a list of components joined by ':', names [service]. Android may refuse an app
    // some of its settings; then the way to start Kestrel shows as not set.
    private fun names(name: String, service: ComponentName): Boolean = try {
        Settings.Secure.getString(context.contentResolver, name).orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .contains(service)
    } catch (e: SecurityException) {
        false
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
