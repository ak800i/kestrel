package dev.kestrel.app

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.Listening
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.app.listen.ModeMemory
import dev.kestrel.app.listen.Session
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessagesByCause
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.ScreenSession
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.listen.MicCapture
import dev.kestrel.voice.listen.Microphone
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

    /** The listening mode that Kestrel turns on in again after its process restarts (design spec, sections 8.5 and 14.4). */
    val LISTENING = SettingKey("listening", ListeningSetting.serializer(), ListeningSetting())
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
     * spec, section 8.10). Built on first use, because it reads the manifest from the assets. The folder is found again
     * at each check and each load of the engines: while shared storage is not available, a check or a load uses a folder
     * in Kestrel's private storage, where the models count as missing, and the next one finds them once shared storage
     * is back. Every caller uses it off the main thread, because finding the folder touches the disk.
     */
    val models: ModelFiles by lazy {
        ModelFiles(
            findRoot = { File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models") },
            manifest = ModelManifest.fromAssets(appContext.assets),
            hashCache = File(appContext.noBackupFilesDir, "model-hashes.json"),
        )
    }

    /** Kestrel's speech engines, which load their models from [models] (design spec, section 8.2). */
    val voice: VoiceEngines by lazy { VoiceEngines(models, File(appContext.noBackupFilesDir, "voice")) }

    /**
     * Kestrel's listening, one for the whole process (design spec, sections 7.2 and 7.3), so that the accessibility
     * service, the notification and the tile reach it whether or not ListeningService runs. Its thread runs one thing at a
     * time; failures are logged by their classes and stack frames only (section 13.5).
     */
    val listening: Listening by lazy {
        Listening(
            scope = scope,
            thread = Dispatchers.Default.limitedParallelism(1),
            sessions = object : VoiceSessions {
                override suspend fun open(microphone: Microphone, listener: SessionListener): Session =
                    VoiceSession.open(appContext, voice, microphone, listener)
            },
            services = ListeningService.control(appContext),
            // One main-thread task per text, in order, so that an earlier text never replaces a later one.
            bar = FeedbackBar { text -> scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text) } },
            report = { what, error ->
                for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
                    Log.w(TAG, if (index == 0) "$what failed: $part" else part)
                }
            },
            // The launcher's own intent, so that Kestrel's task comes to the front instead of a second MainActivity. The
            // controller's thread calls it; startActivity may be called from any thread.
            openSettings = {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            // Encrypted like every setting (section 13.4). SettingsStore reads and writes on Dispatchers.IO.
            memory = object : ModeMemory {
                override suspend fun read(): Mode = settings.get(SettingKeys.LISTENING).mode

                override suspend fun write(mode: Mode) = settings.set(SettingKeys.LISTENING, ListeningSetting(mode))
            },
            microphones = { MicCapture(appContext) },
        )
    }

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
