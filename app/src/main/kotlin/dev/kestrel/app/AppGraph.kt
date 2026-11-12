package dev.kestrel.app

import android.content.Context
import android.util.Log
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}

/** Wires Kestrel's parts together by hand; there is no dependency-injection framework (design spec, section 17). */
class AppGraph(context: Context) {
    /** Background work. A failed job is written to logcat instead of ending the app's process. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> Log.e(TAG, "A background task failed", error) },
    )

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()
    val network = NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } }

    private companion object {
        const val TAG = "Kestrel"
    }
}
