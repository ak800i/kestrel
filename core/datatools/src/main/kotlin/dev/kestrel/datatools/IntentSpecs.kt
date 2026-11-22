package dev.kestrel.datatools

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.kestrel.agent.port.IntentSpec

/**
 * Turns plan 5A's plain [IntentSpec] (the `open_url`, `open_deep_link` and `open_settings` tools, and `navigate`) into
 * Android's intent, and finds the app that would open it (design D11 and D32 item 12 of sub-project 5), with the
 * `<queries>` of Kestrel's manifest.
 */
object IntentSpecs {
    // Android's own chooser and resolver: a link that several apps take resolves to them, which names no app.
    private val CHOOSERS = setOf("android", "com.android.intentresolver")

    fun toIntent(spec: IntentSpec): Intent = Intent(spec.action).apply {
        spec.data?.let { data = Uri.parse(it) }
        spec.packageName?.let { setPackage(it) }
        if (spec.browsable) addCategory(Intent.CATEGORY_BROWSABLE)
        for ((name, value) in spec.extras) putExtra(name, value)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The package of the activity that would open [spec], or null when none would or Android would ask which one. Off the main thread. */
    fun resolve(packageManager: PackageManager, spec: IntentSpec): String? = resolve(packageManager, toIntent(spec))

    /** The package of the activity that would open [intent], or null when none would or Android would ask which one. Off the main thread. */
    fun resolve(packageManager: PackageManager, intent: Intent): String? {
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        return packageManager.resolveActivity(intent, flags)?.activityInfo?.packageName?.takeIf { it !in CHOOSERS }
    }
}
