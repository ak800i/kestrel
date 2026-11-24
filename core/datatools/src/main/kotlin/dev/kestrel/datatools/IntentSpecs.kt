package dev.kestrel.datatools

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.kestrel.agent.port.IntentExtra
import dev.kestrel.agent.port.IntentSpec

/**
 * Turns the agent's plain [IntentSpec] (the `open_*` tools' links and pages, and the app screens of the data tools) into
 * Android's intent, and finds the apps that would open it (design D11 and D32 item 12 of sub-project 5; plan
 * 6B-basefix), with the `<queries>` of Kestrel's manifest.
 */
object IntentSpecs {
    // Android's own chooser and resolver: a link that several apps take resolves to them, which names no app.
    private val CHOOSERS = setOf("android", "com.android.intentresolver")

    /**
     * The intent that [spec] describes, the only way an agent's intent becomes Android's: its data with its MIME type, its
     * typed extras, and the content URIs that the app that opens it may read in the clip data with a read grant.
     */
    fun toIntent(spec: IntentSpec): Intent = Intent(spec.action).apply {
        val uri = spec.data?.let(Uri::parse)
        if (spec.type != null) setDataAndType(uri, spec.type) else data = uri
        spec.packageName?.let { setPackage(it) }
        if (spec.browsable) addCategory(Intent.CATEGORY_BROWSABLE)
        for ((name, value) in spec.extras) put(name, value)
        if (spec.readable.isNotEmpty()) {
            clipData = ClipData.newRawUri("", Uri.parse(spec.readable[0])).apply { spec.readable.drop(1).forEach { addItem(ClipData.Item(Uri.parse(it))) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun Intent.put(name: String, value: IntentExtra) {
        when (value) {
            is IntentExtra.Text -> putExtra(name, value.value)
            is IntentExtra.Number -> putExtra(name, value.value)
            is IntentExtra.LongNumber -> putExtra(name, value.value)
            is IntentExtra.Flag -> putExtra(name, value.value)
            is IntentExtra.Numbers -> putIntegerArrayListExtra(name, ArrayList(value.values))
            is IntentExtra.ContentUri -> putExtra(name, Uri.parse(value.uri))
            is IntentExtra.ContentUris -> putParcelableArrayListExtra(name, value.uris.mapTo(ArrayList(), Uri::parse))
        }
    }

    /** The package of the activity that would open [spec], or null when none would or Android would ask which one. Off the main thread. */
    fun resolve(packageManager: PackageManager, spec: IntentSpec): String? {
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        return packageManager.resolveActivity(toIntent(spec), flags)?.activityInfo?.packageName?.takeIf { it !in CHOOSERS }
    }

    /** The packages of the activities that could open [spec], from one query, without Android's chooser. Off the main thread. */
    fun handlers(packageManager: PackageManager, spec: IntentSpec): Set<String> {
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        return packageManager.queryIntentActivities(toIntent(spec), flags).mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName } - CHOOSERS
    }
}
