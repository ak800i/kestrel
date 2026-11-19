package dev.kestrel.app

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import dev.kestrel.app.assist.KestrelAssistantService
import dev.kestrel.app.commands.CommandEnvironment
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.commands.CommandRunner
import dev.kestrel.app.commands.InstantCommands
import dev.kestrel.app.commands.ScreenTargets
import dev.kestrel.app.listen.AndroidCallApi
import dev.kestrel.app.listen.AndroidCallMonitor
import dev.kestrel.app.listen.AndroidCallScreen
import dev.kestrel.app.listen.AndroidExitRecords
import dev.kestrel.app.listen.AndroidListeningConditions
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.CallActions
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.KokoroSpeed
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.listen.Listening
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.app.listen.ModeMemory
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.listen.ScreenBoostWords
import dev.kestrel.app.listen.Session
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.SettingsOpener
import dev.kestrel.app.listen.StoredMode
import dev.kestrel.app.listen.Timeline
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.app.listen.loadStoredVoice
import dev.kestrel.app.listen.measuredTooSlow
import dev.kestrel.app.listen.openWithBoostWords
import dev.kestrel.app.listen.realTimeFactor
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.providers.ProviderAllowlist
import dev.kestrel.app.providers.StoredCredentials
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessagesByCause
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.network.LogPathTag
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.network.TaskTag
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.discovery.ModelList
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.ScreenSession
import dev.kestrel.voice.Synthesizer
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())

    /** The owner's voice and speed (design spec, sections 8.2 and 15). */
    val VOICE = SettingKey("voice", VoiceOptionsSetting.serializer(), VoiceOptionsSetting())

    /** Kokoro's speed on this phone, measured at its first load for each model and install (sections 5.3 and 8.2). */
    val KOKORO_SPEED = SettingKey("kokoro-speed", KokoroSpeed.serializer(), KokoroSpeed())

    /** The listening mode that Kestrel turns on in again after its process restarts (design spec, sections 8.5 and 14.4). */
    val LISTENING = SettingKey("listening", ListeningSetting.serializer(), ListeningSetting())

    /** The owner's choices for listening (design spec, sections 8.5, 8.7, 8.8 and 15). */
    val LISTENING_OPTIONS = SettingKey("listening-options", ListeningOptions.serializer(), ListeningOptions())

    /** The options of the instant commands: "Require verbs" and where the numbers go (design spec, sections 9.8 and 15). */
    val COMMANDS = SettingKey("commands", CommandOptions.serializer(), CommandOptions())

    /**
     * The provider profiles, the default profile, the switches of the borrowed sign-ins and the ChatGPT plan's registration
     * (design spec, section 10.6). Credentials are not in it.
     */
    val PROVIDERS = SettingKey("providers", ProvidersSetting.serializer(), ProvidersSetting())

    /** Each profile's credits and tokens of this month and the one before (design spec, section 10.6). */
    val SPENDING = SettingKey("spending", Spending.serializer(), Spending())
}

/**
 * The last model list of the profile [profileId], in a setting of its own, because lists of hundreds of rows should not be
 * rewritten at every change of a profile. Deleting a profile writes an empty list (design D13 of sub-project 4).
 */
fun SettingKeys.providerModels(profileId: String): SettingKey<ModelList> =
    SettingKey("provider-models-$profileId", ModelList.serializer(), ModelList())

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

    /** Logs a failure that Kestrel handled, as [reportFailure] does, for the parts that AppGraph does not build. */
    internal fun report(what: String, error: Throwable) = reportFailure(what, error)

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()

    // Built on first use: building the HTTP client on the main thread made Kestrel miss Android's start-up deadline
    // on a busy emulator just after it booted.
    val network: NetworkGateway by lazy { NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } } }

    /**
     * The providers' requests (design spec, sections 7.4 and 13.1): each one goes through the network gateway, carries its
     * task and the path for the network log as tags and Kestrel's User-Agent when it names no other, and a request that the
     * allowlist refuses becomes ProviderError.NotAllowed. Built on first use, like the gateway.
     */
    val providerHttp: ProviderHttp by lazy {
        val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
        ProviderHttp(
            calls = network.callFactory,
            decorator = { builder, meta ->
                meta.taskId?.let { builder.tag(TaskTag::class.java, TaskTag(it)) }
                meta.logPath?.let { builder.tag(LogPathTag::class.java, LogPathTag(it)) }
                if (builder.build().header("User-Agent") == null) builder.header("User-Agent", "Kestrel/$version")
            },
            classify = { error -> if (error is BlockedRequestException) ProviderError.NotAllowed else null },
        )
    }

    /** The provider catalog that the app bundles (design spec, section 10.2), read from the assets on first use. */
    val catalog: Catalog by lazy {
        fun asset(name: String) = appContext.assets.open("catalog/$name").use { it.readBytes().decodeToString() }
        CatalogLoader.load(asset("providers.json"), asset("overrides.json"))
    }

    /** The providers' credentials, encrypted under their own key (design spec, section 10.7). */
    val credentialVault: CredentialVault by lazy { StoredCredentials(store.credentials) }

    private val appliedSetting = MutableStateFlow<ProvidersSetting?>(null)

    /**
     * The provider setting whose hosts the network allowlist holds, null until the first update after the start. Plan 4C's
     * ProfileClient waits for it before a request, so that a new profile's hosts are allowed first.
     */
    val appliedProviders: StateFlow<ProvidersSetting?> = appliedSetting.asStateFlow()

    init {
        keepProviderHosts()
    }

    /**
     * Keeps the allowlist in step with the stored profiles (design spec, section 13.1): at start and after each change, the
     * hosts of every profile whose borrowed sign-in is on go to the allowlist and the local servers to its list of local
     * servers, and then the applied setting is published. A failed update is logged by its classes only, and the next
     * change tries again. If reading the setting fails, the flow ends: the allowlist keeps the hosts of the last update
     * until the process restarts.
     */
    private fun keepProviderHosts() {
        scope.launch {
            settings.observe(SettingKeys.PROVIDERS)
                .catch { reportFailure("Reading the provider setting", it) }
                .collect { setting ->
                    try {
                        networkPolicy.setProfileHosts(ProviderAllowlist.profileHosts(setting, catalog))
                        networkPolicy.setLocalServers(ProviderAllowlist.localServers(setting))
                        appliedSetting.value = setting
                    } catch (e: IllegalArgumentException) {
                        reportFailure("Updating the allowlist", e)
                    } catch (e: IOException) {
                        reportFailure("Updating the allowlist", e)
                    }
                }
        }
    }

    // Plan 4C: provider graph

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

    private val kokoroFallback = MutableStateFlow(false)

    /**
     * Whether the owner chose Kokoro and Piper spoke instead the last time that a voice session opened in this process,
     * because Kokoro did not load (design spec, sections 8.2 and 14).
     */
    val kokoroFailed: StateFlow<Boolean> = kokoroFallback.asStateFlow()

    /**
     * Whether the voice session that opened last speaks with Piper because Kokoro is too slow on this phone (plan 2E):
     * each open sets it, and it counts only while that session is open ([Listening.sessionOpen]), so that "Try it" with
     * Kokoro loads no second Kokoro beside it, also after a tap on Kokoro forgot the stored measure.
     */
    private val kokoroSlowSession = MutableStateFlow(false)

    /** Whether a voice session is open that speaks with Piper because Kokoro is too slow on this phone (plan 2E). */
    internal fun sessionSpeaksPiperForSlowKokoro(): Boolean = listening.sessionOpen.value && kokoroSlowSession.value

    /** Kestrel's notifications about problems (design spec, section 14.6). */
    internal val problems: ProblemNotifications by lazy { ProblemNotifications(appContext) }

    /** The phone's own times of Kestrel's starts, sessions, reactions, turn-offs and settings (plan 2E). */
    internal val timeline = Timeline(SystemClock::elapsedRealtime)

    /**
     * The owner's options for the instant commands (design spec, sections 9.5 and 9.8), stored encrypted, which the
     * executor reads at each command: the defaults until the store has answered, and after a read that failed, which is
     * logged by its classes only.
     */
    internal val commandOptions: StateFlow<CommandOptions> by lazy {
        settings.observe(SettingKeys.COMMANDS)
            .catch { reportFailure("Reading the command options", it) }
            .stateIn(scope, SharingStarted.Eagerly, CommandOptions())
    }

    /**
     * The executor of the instant commands (design spec, section 9.5), which Listening runs on Dispatchers.IO. It works on
     * the screen through Kestrel's accessibility service while that is connected.
     */
    internal val commands: CommandRunner by lazy {
        InstantCommands(
            CommandEnvironment(
                context = appContext,
                screen = { screen.value },
                options = commandOptions,
                targets = ScreenTargets { screen.value },
                scope = scope,
                // From the accessibility service, which Android lets start activities from the background (prototype P1),
                // with an intent that reaches the settings app also when something covers it in Kestrel's task.
                openSettings = { page, permission ->
                    val intent = MainActivity.freshIntent(appContext)
                    page?.let { intent.putExtra(MainActivity.EXTRA_PAGE, it.name) }
                    permission?.let { intent.putExtra(MainActivity.EXTRA_ASK_PERMISSION, it) }
                    appContext.startActivity(intent)
                },
                report = reportFailure,
            ),
        )
    }

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
                            // The labels on the screen before each utterance, from the newest snapshot (section 8.3).
                            screenWords = { screenLabels.words() },
                            // The owner's voice and speed, read at each start, so that a change counts from the next one.
                            // The page "Voice and speech" says when Kokoro did not load or was too slow.
                            loadVoice = {
                                loadStoredVoice(
                                    { settings.get(SettingKeys.VOICE) },
                                    reportFailure,
                                    ::kokoroKnownTooSlow,
                                    ::kokoroTooSlow,
                                ) { voice.synthesizer(it) }.also {
                                    kokoroFallback.value = it.fellBack
                                    kokoroSlowSession.value = it.slow
                                }
                            },
                        )
                    }
            },
            services = ListeningService.control(appContext),
            // One main-thread task per call, in order, so that an earlier text never replaces a later one.
            bar = object : FeedbackBar {
                override fun show(text: String, keep: Boolean) {
                    scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text, keep) }
                }

                override fun release() {
                    scope.launch(Dispatchers.Main) { screen.value?.releaseFeedback() }
                }
            },
            report = reportFailure,
            notices = problems,
            openSettings = { settingsOpener.open() },
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
                screen.value?.let { session -> AndroidCallScreen(appContext, session.actions) }
            },
            commands = commands,
            // Section 15: encrypted like every setting. Listening passes a change on at once.
            options = settings.observe(SettingKeys.LISTENING_OPTIONS),
            conditions = AndroidListeningConditions(appContext),
            // The owner's choice as Listening read it last, on the controller's thread, where the microphone starts.
            microphones = {
                MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }).also { latestMicrophone = it }
            },
            timeline = timeline,
        ).also(::keepScreenFresh)
    }

    /** The microphone of the newest voice session, for the debug hook voice-route (plan 2E). */
    @Volatile
    internal var latestMicrophone: MicCapture? = null
        private set

    private val screens = MutableStateFlow<ScreenSession?>(null)

    /** Kestrel's screen layer while its accessibility service is connected, otherwise null. */
    val screen: StateFlow<ScreenSession?> = screens.asStateFlow()

    /** The labels on the screen as boost words (section 8.3). */
    private val screenLabels = ScreenBoostWords { screen.value }

    /**
     * While a voice session is open and Kestrel listens with the screen on and the phone unlocked, the screen session
     * builds a snapshot after each settled change of the screen, so that each utterance's boost words read a current one
     * without waiting (section 8.3). A screen session that connects while Kestrel listens is kept fresh too. When the mode
     * leaves Listening, for a pause, a call or off, the numbers, the "Which one?" numbers, the labels and the grid go,
     * because only voice removes them and no command works then (section 9.4); a screen that turns off leaves them, as
     * they come back with it.
     */
    private fun keepScreenFresh(listening: Listening) {
        scope.launch {
            combine(screens, listening.state, listening.sessionOpen) { session, state, open ->
                session to (open && state.mode == Mode.LISTENING && state.screenOn)
            }.collect { (session, fresh) -> session?.keepFresh = fresh }
        }
        scope.launch {
            listening.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                // One task per change, as the bar's, so that a failure ends only that call.
                if (mode != Mode.LISTENING) screens.value?.let { session -> scope.launch { session.hideOverlays() } }
            }
        }
    }

    // Built at the first "Kestrel, open settings", after every part of the graph that it uses.
    private val settingsOpener by lazy {
        SettingsOpener(
            timeline,
            serviceOn = { screen.value != null },
            roleHeld = { appContext.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) },
            startActivity = { appContext.startActivity(MainActivity.freshIntent(appContext)) },
            showSession = { KestrelAssistantService.openSettings() },
            offer = { problems.offerSettings() },
            report = reportFailure,
        )
    }

    /**
     * When Kestrel was installed or last updated: a measure of Kokoro's speed belongs to one install (plan 2E). Its first
     * read is a binder call, so the callers read it on Dispatchers.IO, never on the main thread or Listening's thread.
     */
    internal val installedAt: Long by lazy {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
            .lastUpdateTime
    }

    /**
     * Whether this phone's stored measure says that Kokoro is too slow, so that Piper speaks without loading Kokoro. It
     * reads [installedAt] on Dispatchers.IO.
     */
    private suspend fun kokoroKnownTooSlow(): Boolean {
        val installed = withContext(Dispatchers.IO) { installedAt }
        return settings.get(SettingKeys.KOKORO_SPEED).tooSlow(voice.kokoroModel, installed) != null
    }

    /**
     * Whether Kokoro speaks more slowly than real time on this phone (design spec, sections 5.3 and 8.2): measured once for
     * each Kokoro model and install of Kestrel, when Kokoro first loads for a voice session, and stored. The measure
     * synthesizes a short sentence and then one of about 5 s, which takes about 10 s on the A71 with the fp32 model, so it
     * delays that session's start once. It reads [installedAt] on Dispatchers.IO and measures on Dispatchers.Default,
     * because the session opens on Listening's thread, which must stay free for the Orchestrator's events (plan 2E). A
     * cancellation of the open still reaches loadChosenVoice, which closes Kokoro.
     */
    private suspend fun kokoroTooSlow(kokoro: Synthesizer): Boolean {
        val installed = withContext(Dispatchers.IO) { installedAt }
        return measuredTooSlow(
            kokoro,
            voice.kokoroModel,
            installed,
            stored = { settings.get(SettingKeys.KOKORO_SPEED) },
            store = { settings.set(SettingKeys.KOKORO_SPEED, it) },
            report = reportFailure,
        ) { withContext(Dispatchers.Default) { realTimeFactor(it, SystemClock::elapsedRealtime) } }
    }

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
