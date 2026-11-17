package dev.kestrel.app

import android.Manifest
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.kestrel.app.listen.AndroidCallApi
import dev.kestrel.app.listen.AndroidCallMonitor
import dev.kestrel.app.listen.AndroidExitRecords
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.CallActions
import dev.kestrel.app.listen.CallScreen
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.listen.Listening
import dev.kestrel.app.listen.ListeningConditions
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.app.listen.ModeMemory
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.listen.Session
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.StoredMode
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.app.listen.loadChosenVoice
import dev.kestrel.app.listen.openWithBoostWords
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
import kotlinx.coroutines.CancellationException
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

    /** The owner's voice and speed (design spec, sections 8.2 and 15). */
    val VOICE = SettingKey("voice", VoiceOptionsSetting.serializer(), VoiceOptionsSetting())

    /** The listening mode that Kestrel turns on in again after its process restarts (design spec, sections 8.5 and 14.4). */
    val LISTENING = SettingKey("listening", ListeningSetting.serializer(), ListeningSetting())

    /** The owner's choices for listening (design spec, sections 8.5, 8.7, 8.8 and 15). */
    val LISTENING_OPTIONS = SettingKey("listening-options", ListeningOptions.serializer(), ListeningOptions())
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

    /**
     * Logs a failure that Kestrel handled, after a fixed description of what failed: only the error's classes and stack
     * frames, one entry for each error of the chain of causes, as [errors] does (section 13.5).
     */
    private val reportFailure: (String, Throwable) -> Unit = { what, error ->
        for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
            Log.w(TAG, if (index == 0) "$what failed: $part" else part)
        }
    }

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
     * is back. Every read of root and file() runs off the main thread, because finding the folder touches the disk;
     * check() switches to Dispatchers.IO itself.
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

    /** Kestrel's notifications about problems (design spec, section 14.6). */
    internal val problems: ProblemNotifications by lazy { ProblemNotifications(appContext) }

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
                    // "Kestrel" and the names of the apps that the launcher shows, read once while the engines load.
                    openWithBoostWords(BoostWordSource(LauncherAppLabels(appContext), reportFailure)) { boostWords ->
                        VoiceSession.open(
                            appContext,
                            voice,
                            microphone,
                            listener,
                            boostWords,
                            // The owner's voice and speed, read at each start, so that a change counts from the next one.
                            // A setting that cannot be read counts as the default, Piper, as the listening options do.
                            loadSynthesizer = {
                                val chosen = try {
                                    settings.get(SettingKeys.VOICE)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    reportFailure("Reading the voice setting", e)
                                    VoiceOptionsSetting()
                                }
                                loadChosenVoice(chosen, reportFailure) { voice.synthesizer(it) }
                            },
                        )
                    }
            },
            services = ListeningService.control(appContext),
            // One main-thread task per text, in order, so that an earlier text never replaces a later one.
            bar = FeedbackBar { text -> scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text) } },
            report = reportFailure,
            notices = problems,
            // The launcher's own intent, so that Kestrel's task comes to the front instead of a second MainActivity.
            // The controller's thread calls it; startActivity may be called from any thread. Without Kestrel's
            // accessibility service, Android blocks the start from the background without an exception (prototype P1),
            // so Kestrel does not try, and says why instead. Android lets the holder of the assistant role start
            // activities from the background too, because it binds the role's VoiceInteractionService (section 8.6).
            openSettings = {
                if (screen.value == null &&
                    !appContext.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    false
                } else {
                    appContext.startActivity(
                        Intent(appContext, MainActivity::class.java)
                            .setAction(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }
            },
            // Encrypted like every setting (section 13.4). SettingsStore reads and writes on Dispatchers.IO.
            memory = object : ModeMemory {
                override suspend fun read(): StoredMode =
                    settings.get(SettingKeys.LISTENING).let { StoredMode(it.mode, it.writtenAt, it.restoredAt) }

                override suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?) =
                    settings.set(SettingKeys.LISTENING, ListeningSetting(mode, writtenAt, restoredAt))
            },
            exits = AndroidExitRecords(appContext),
            // The wall clock, as in Android's exit records.
            clock = System::currentTimeMillis,
            // Section 8.7: the call state, while a voice session is open.
            calls = AndroidCallMonitor(appContext),
            // The call commands: Android's call API with ANSWER_PHONE_CALLS, otherwise the phone app's controls through
            // the accessibility service while it runs. Listening runs them on Dispatchers.IO.
            callActions = CallActions(AndroidCallApi(appContext)) {
                screen.value?.let { session -> CallScreen { label, ifOff -> session.actions.clickLabelled(label, ifOff) } }
            },
            // Section 15: encrypted like every setting. Listening passes a change on at once.
            options = settings.observe(SettingKeys.LISTENING_OPTIONS),
            conditions = object : ListeningConditions {
                override fun microphoneAllowed() =
                    appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                override fun backgroundRestricted() =
                    appContext.getSystemService(ActivityManager::class.java).isBackgroundRestricted

                // A binder call with the service's token, which any thread may make (prototype P2). The type is 0 while
                // the service is not in the foreground.
                override fun demoted(service: Any) = (service as ListeningService).foregroundServiceType == 0
            },
            // The owner's choice as Listening read it last, on the controller's thread, where the microphone starts.
            microphones = { MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }) },
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
