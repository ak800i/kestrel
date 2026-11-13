package dev.kestrel.app

import android.content.Context
import android.util.Log
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessages
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}

/** Wires Kestrel's parts together by hand; there is no dependency-injection framework (design spec, section 17). */
class AppGraph(context: Context) {
    /**
     * Background work. A failed job is written to logcat instead of ending the app's process. Only the error's
     * classes and stack frames are written, because a message can contain screen text.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "A background task failed: ${error.describeWithoutMessages()}") },
    )

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()
    val network = NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } }

    private val screens = MutableStateFlow<ScreenSession?>(null)

    /** Kestrel's screen layer while its accessibility service is connected, otherwise null. */
    val screen: StateFlow<ScreenSession?> = screens.asStateFlow()

    internal fun attachScreen(session: ScreenSession) {
        screens.value = session
    }

    internal fun detachScreen(session: ScreenSession) {
        screens.compareAndSet(session, null)
    }

    private companion object {
        const val TAG = "Kestrel"
    }
}
