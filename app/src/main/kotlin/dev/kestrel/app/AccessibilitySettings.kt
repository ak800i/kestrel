package dev.kestrel.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.kestrel.app.listen.AccessibilityServiceState

/** What Android's accessibility settings and its accessibility manager say about accessibility services. */
internal object AccessibilitySettings {
    /** The accessibility services that Android runs: not those that it holds as crashed or is still binding. */
    fun running(context: Context): List<ComponentName> =
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }

    /**
     * Whether the secure setting [name], a list of components joined by ':', names [component]. Android may refuse an app
     * some of its settings; then the setting counts as not naming it.
     */
    fun names(context: Context, name: String, component: ComponentName): Boolean = try {
        Settings.Secure.getString(context.contentResolver, name).orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .contains(component)
    } catch (e: SecurityException) {
        false
    }

    /**
     * Whether Android's setting names Kestrel's accessibility service and whether Android runs it, from [running]. They
     * differ after "Stop app" or Stop in "Active apps" (prototype P2), which the setup checklist and the notice after the
     * owner's stop both read.
     */
    fun kestrel(context: Context, running: List<ComponentName> = running(context)): AccessibilityServiceState {
        val service = ComponentName(context, KestrelAccessibilityService::class.java)
        return AccessibilityServiceState(
            enabled = names(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, service),
            running = service in running,
        )
    }
}
