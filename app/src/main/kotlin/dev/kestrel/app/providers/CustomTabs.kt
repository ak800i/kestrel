package dev.kestrel.app.providers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.net.toUri

/**
 * Opens a sign-in page in a Custom Tab, built by hand so that no library ships (design D10): `ACTION_VIEW` with the
 * session extra set to a null binder, Chrome when Chrome offers the Custom Tabs service, else the default browser. It is
 * started from Kestrel's settings activity without FLAG_ACTIVITY_NEW_TASK, so that the tab is in Kestrel's task: closing
 * the tab returns to the page, and SignInService may bring Kestrel back to the front after the callback (prototype P5).
 */
internal object CustomTabs {
    const val SERVICE_ACTION = "android.support.customtabs.action.CustomTabsService"
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
    private const val CHROME = "com.android.chrome"

    /** Opens [url]; false when the phone has no browser. */
    fun open(activity: Activity, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        chrome(activity)?.let { intent.setPackage(it) }
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Chrome's package when it offers the Custom Tabs service, found through the manifest's `<queries>` entry. */
    fun chrome(context: Context): String? =
        context.packageManager.queryIntentServices(Intent(SERVICE_ACTION), PackageManager.ResolveInfoFlags.of(0))
            .map { it.serviceInfo.packageName }
            .firstOrNull { it == CHROME }
}
