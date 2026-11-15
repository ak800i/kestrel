package dev.kestrel.app

import android.content.Context
import android.util.Log
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessagesByCause
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.ScreenSession
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelManifest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}

/** Wires Kestrel's parts together by hand; there is no dependency-injection framework (design spec, section 17). */
class AppGraph(context: Context) {
    private val appContext: Context = context.applicationContext

    /**
     * Handles the failures of background work: a failed job is written to logcat instead of ending the app's process.
     * Only the error's classes and stack frames are written, because a message can contain screen text, and each error
     * of the chain of causes gets its own entry, because Android's log cuts an entry after about 4 KB.
     */
    val errors = CoroutineExceptionHandler { _, error ->
        for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
            Log.e(TAG, if (index == 0) "A background task failed: $part" else part)
        }
    }

    /** Background work, whose failures go to [errors]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()

    // Built on first use: building the HTTP client on the main thread made Kestrel miss Android's start-up deadline
    // on a busy emulator just after it booted.
    val network: NetworkGateway by lazy { NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } } }

    /**
     * Kestrel's speech model files, which scripts\push-models.ps1 copies into Kestrel's external files folder (design
     * spec, section 8.10). Built on first use, because finding that folder touches the disk. If shared storage is not
     * available, the models count as missing.
     */
    val models: ModelFiles by lazy {
        ModelFiles(
            root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models"),
            manifest = ModelManifest.fromAssets(appContext.assets),
            hashCache = File(appContext.noBackupFilesDir, "model-hashes.json"),
        )
    }

    /** Kestrel's speech engines, which load their models from [models] (design spec, section 8.2). */
    val voice: VoiceEngines by lazy { VoiceEngines(models, File(appContext.noBackupFilesDir, "voice")) }

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
