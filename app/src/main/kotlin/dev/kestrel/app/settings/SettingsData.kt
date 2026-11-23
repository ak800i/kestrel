package dev.kestrel.app.settings

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.agent.memory.Routine
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.app.AppGraph
import dev.kestrel.app.R
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.listen.KokoroSpeed
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.app.listen.forgetsKokoroSpeed
import dev.kestrel.app.download.DownloadChoice
import dev.kestrel.app.download.DownloadState
import dev.kestrel.app.download.ModelDownloadSetting
import dev.kestrel.app.download.VoiceSuggestion
import dev.kestrel.app.download.readHardware
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.safety.AgentFreeChanges
import dev.kestrel.safety.SafetySettings
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the settings pages read and change. [StoredSettingsData] connects them to Kestrel's stores; tests use a fake. A
 * change that a page stores is made also when the owner leaves the page at once.
 */
interface SettingsData {
    val testMode: Flow<Boolean>

    suspend fun setTestMode(on: Boolean)

    /** The owner's voice and speed (design spec, section 15). */
    val voiceOptions: Flow<VoiceOptionsSetting>

    suspend fun setVoiceOptions(options: VoiceOptionsSetting)

    /**
     * Speaks a fixed sentence with [options], and returns when it has been spoken. Throws when the voice fails or Android
     * refuses Kestrel the audio, for example during a call.
     */
    suspend fun tryVoice(options: VoiceOptionsSetting)

    /** Whether the owner chose Kokoro and Piper spoke instead the last time that Kestrel turned on (section 14). */
    val kokoroFailed: Flow<Boolean>

    /**
     * Kokoro's real-time factor on this phone when Kestrel measured it above 1, so that Piper speaks instead, or null
     * (sections 5.3 and 8.2).
     */
    val kokoroSlow: Flow<Float?>

    /** The owner's choices for listening (design spec, section 15). */
    val listening: Flow<ListeningOptions>

    suspend fun setListening(options: ListeningOptions)

    /** The options of the instant commands (design spec, sections 9.5, 9.8 and 15). */
    val commandOptions: Flow<CommandOptions>

    suspend fun setCommandOptions(options: CommandOptions)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>

    suspend fun networkLog(): List<LogEntry<NetworkEvent>>

    /** Wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory()

    /** Kestrel's version name and code, such as "0.1.0 (1)". */
    val version: String

    suspend fun licenceText(licence: Licence): String

    /** Whether the default provider profile can be used, for the checklist's step (design D18 of sub-project 4). */
    val providerReady: Flow<Boolean> get() = flowOf(false)

    /** What the page "Providers and profiles" reads and changes, or null for a fake without providers. */
    val providers: ProviderData? get() = null

    // release plan: the model download

    /** The voice and the network for the download of the models, which step 2 of the checklist shows. */
    val downloadChoice: Flow<DownloadChoice> get() = flowOf(DownloadChoice(VoiceChoice.PIPER, VoiceChoice.PIPER, mobileData = false))

    /** Stores [choice] for the next download. */
    suspend fun setDownloadChoice(choice: DownloadChoice) {}

    /** Where the download of the models stands. */
    val modelDownload: Flow<DownloadState> get() = flowOf(DownloadState.Idle)

    /** Starts a download of the models with [choice], unless one has not ended yet, and stores [choice]. */
    suspend fun downloadModels(choice: DownloadChoice) {}

    /** Stops the download of the models; its part files stay for the next one. */
    fun cancelModelDownload() {}

    // sub-project 5B: safety and memory

    /** The safety settings: test mode, the mode and test mode's names (design D13, D18 and D26 of sub-project 5). */
    val safety: Flow<SafetySettings> get() = flowOf(SafetySettings())

    /** Stores the safety settings that [change] makes of the stored ones. */
    suspend fun setSafety(change: (SafetySettings) -> SafetySettings) {}

    /** The owner's changes to the shipped list of agent-free apps (design D16 of sub-project 5). */
    val agentFree: Flow<AgentFreeChanges> get() = flowOf(AgentFreeChanges())

    /** Stores the agent-free changes that [change] makes of the stored ones, under the store's lock (as [setSafety]). */
    suspend fun setAgentFree(change: (AgentFreeChanges) -> AgentFreeChanges) {}

    /** The launcher apps of the owner's profile, for "Add an app" and the suggestions (design D16). */
    suspend fun launcherApps(): List<LauncherApp> = emptyList()

    /** Notes that the owner has reviewed the suggested agent-free apps, which completes the checklist's step (design D26). */
    suspend fun setAgentFreeReviewed() {}

    /** What Kestrel's memory holds, for the Memory page (design D22, D23 and D26 of sub-project 5). */
    suspend fun memory(): MemoryView = MemoryView()

    /** Stores [fact], replacing the fact with the same key. */
    suspend fun saveFact(fact: Fact) {}

    suspend fun deleteFact(key: String) {}

    /**
     * Replaces route note [old] of its app with [new], which keeps the path form: the stored note with [old]'s path takes
     * [new]'s path and keeps the counts that a task may have changed meanwhile; when a task removed it, [new] is stored.
     */
    suspend fun replaceNote(old: RouteNote, new: RouteNote) {}

    /** Deletes the route note with [note]'s path. */
    suspend fun deleteNote(note: RouteNote) {}

    /** Deletes every route note of the app [packageName]. */
    suspend fun deleteNotes(packageName: String) {}

    /**
     * Gives the routine called [oldName] [routine]'s name and phrasings; it keeps its steps. A routine with the new name
     * gives way to it.
     */
    suspend fun replaceRoutine(oldName: String, routine: Routine) {}

    suspend fun deleteRoutine(name: String) {}

    /** Deletes the facts, route notes, routines, signatures and found contacts (design D22; spec 5.1 goal 4). */
    suspend fun deleteAllMemory() {}

    /** Whether the owner has reviewed the suggested agent-free apps once, for the checklist (design D26 of sub-project 5). */
    val agentFreeReviewed: Flow<Boolean> get() = flowOf(false)

    // sub-project 5C: screenshot approvals

    /** The apps that may send screenshots to a provider without asking (design D20 and D26 of sub-project 5), with names. */
    val screenshotApprovals: Flow<List<dev.kestrel.app.agent.eyes.ScreenshotApprovalRow>> get() = flowOf(emptyList())

    /** Takes back one approval: the screenshot question comes again for that app and provider. */
    suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) {}
}

/**
 * "Try it" with Kokoro while Kestrel listens with Piper, because Kokoro is too slow on this phone: a second Kokoro beside
 * the session would load several hundred MB, so the page asks the owner to turn Kestrel off first (plan 2E).
 */
class KokoroTooSlowException : IllegalStateException("Kokoro is too slow on this phone while Kestrel listens")

class StoredSettingsData(private val graph: AppGraph, private val context: Context) : SettingsData {
    private val writes = Mutex()

    override val testMode: Flow<Boolean> = graph.settings.observe(SettingKeys.SAFETY).map { it.testMode }

    override suspend fun setTestMode(on: Boolean) = write {
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
    }

    override val voiceOptions: Flow<VoiceOptionsSetting> = graph.settings.observe(SettingKeys.VOICE)

    override suspend fun setVoiceOptions(options: VoiceOptionsSetting) = write {
        // Plan 2E: choosing Kokoro, also with a tap on its row while it is chosen, forgets its stored speed, so that its
        // next start measures it again. "Slower" and "Faster" change the speed and keep the measure.
        if (forgetsKokoroSpeed(graph.settings.get(SettingKeys.VOICE), options)) {
            graph.settings.set(SettingKeys.KOKORO_SPEED, KokoroSpeed())
        }
        graph.settings.set(SettingKeys.VOICE, options)
    }

    // With the voice that the open voice session speaks with, the session speaks the sample, so that no second copy of
    // the voice, several hundred MB for Kokoro, is loaded beside it. Otherwise a voice and a player of its own, which
    // are closed afterwards: the voice on the page itself, without the session's fallback to Piper, so that a Kokoro
    // that does not load shows as a failure.
    override suspend fun tryVoice(options: VoiceOptionsSetting) {
        val sample = context.getString(R.string.voice_sample)
        val spoke = graph.listening.speakWithSessionVoice(options.options(), sample) ?: run {
            // Plan 2E: where Kokoro is too slow, the open session speaks with Piper, and a second Kokoro beside it would
            // load several hundred MB. The session's own reason counts, because a tap on Kokoro forgets the stored measure.
            if (options.voice == VoiceChoice.KOKORO && graph.sessionSpeaksPiperForSlowKokoro()) {
                throw KokoroTooSlowException()
            }
            null
        } ?: withContext(Dispatchers.IO) {
            graph.voice.synthesizer(options.options()).use { voice ->
                val player = VoicePlayer(context, voice)
                try {
                    player.speak(sample).await()
                } finally {
                    // As VoiceSession.close does: a cancelled caller still waits until the player no longer uses the
                    // voice.
                    withContext(NonCancellable) { player.close() }
                }
            }
        }
        check(spoke != Spoke.REFUSED) { "Android refused the audio" }
    }

    override val kokoroFailed: Flow<Boolean> = graph.kokoroFailed

    override val providerReady: Flow<Boolean> get() = graph.providers.providerReady

    override val providers: ProviderData by lazy { StoredProviderData(graph.providers) }

    // release plan: the stored model download

    // Read once, by the flow below on Dispatchers.IO: the CPU's speed comes from files of the kernel.
    private val suggestedVoice: VoiceChoice by lazy { VoiceSuggestion.of(readHardware(context)) }

    override val downloadChoice: Flow<DownloadChoice> =
        graph.settings.observe(SettingKeys.MODEL_DOWNLOAD)
            .map { DownloadChoice(it.voice ?: suggestedVoice, suggestedVoice, it.mobileData) }
            .flowOn(Dispatchers.IO)

    override suspend fun setDownloadChoice(choice: DownloadChoice) = write {
        graph.settings.set(SettingKeys.MODEL_DOWNLOAD, ModelDownloadSetting(choice.voice, choice.mobileData))
    }

    override val modelDownload: Flow<DownloadState> get() = graph.modelDownloads.state

    // The download starts before the choice is stored, so that leaving the page at once cannot stop it.
    override suspend fun downloadModels(choice: DownloadChoice) {
        graph.modelDownloads.start(kokoro = choice.voice == VoiceChoice.KOKORO, mobileData = choice.mobileData) {
            // The chosen voice counts once its files are on the phone, from the next time that Kestrel turns on.
            setVoiceOptions(graph.settings.get(SettingKeys.VOICE).copy(voice = choice.voice))
        }
        setDownloadChoice(choice)
    }

    override fun cancelModelDownload() = graph.modelDownloads.cancel()

    // sub-project 5B: stored safety and memory

    override val agentFreeReviewed: Flow<Boolean> = graph.settings.observe(SettingKeys.AGENT_FREE_REVIEWED)

    override val safety: Flow<SafetySettings> = graph.settings.observe(SettingKeys.SAFETY)

    override suspend fun setSafety(change: (SafetySettings) -> SafetySettings) = write {
        graph.settings.set(SettingKeys.SAFETY, change(graph.settings.get(SettingKeys.SAFETY)))
    }

    override val agentFree: Flow<AgentFreeChanges> = graph.settings.observe(SettingKeys.AGENT_FREE_APPS)

    // From the stored value, so that a second tap before the page shows the first change keeps both (task-5b-16-review M1).
    override suspend fun setAgentFree(change: (AgentFreeChanges) -> AgentFreeChanges) = write {
        graph.settings.set(SettingKeys.AGENT_FREE_APPS, change(graph.settings.get(SettingKeys.AGENT_FREE_APPS)))
    }

    override suspend fun setAgentFreeReviewed() = write { graph.settings.set(SettingKeys.AGENT_FREE_REVIEWED, true) }

    // The owner's own profile only, as open_app sees it (design D11), without Kestrel itself.
    override suspend fun launcherApps(): List<LauncherApp> = withContext(Dispatchers.IO) {
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return@withContext emptyList()
        launcher.getActivityList(null, Process.myUserHandle())
            .map { LauncherApp(it.label.toString(), it.applicationInfo.packageName, null) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
    }

    override suspend fun memory(): MemoryView = withContext(Dispatchers.IO) {
        val memory = graph.memory
        val notes = memory.allNotes()
        val labels = launcherApps().associate { it.packageName to it.label }.filterKeys { it in notes }
        MemoryView(memory.facts(), notes, memory.routines(), memory.signatures(), memory.foundContacts(), labels)
    }

    override suspend fun saveFact(fact: Fact) = write { graph.memory.saveFact(fact) }

    override suspend fun deleteFact(key: String) = write { graph.memory.deleteFact(key) }

    // In one change of the memory store each, under its lock, so that a task's change between a read and a write is kept.
    override suspend fun replaceNote(old: RouteNote, new: RouteNote) = write { graph.memory.replaceNote(old, new) }

    override suspend fun deleteNote(note: RouteNote) = write { graph.memory.deleteNote(note) }

    override suspend fun deleteNotes(packageName: String) = write { graph.memory.deleteNotes(packageName) }

    override suspend fun replaceRoutine(oldName: String, routine: Routine) = write { graph.memory.replaceRoutine(oldName, routine) }

    override suspend fun deleteRoutine(name: String) = write { graph.memory.deleteRoutine(name) }

    override suspend fun deleteAllMemory() = write { graph.memory.deleteAll() }

    // sub-project 5C: stored screenshot approvals

    override val screenshotApprovals: Flow<List<dev.kestrel.app.agent.eyes.ScreenshotApprovalRow>> =
        graph.screenshotApprovals.all.map { approvals -> approvals.map(::approvalRow) }.flowOn(Dispatchers.IO)

    override suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) =
        write { graph.screenshotApprovals.revoke(row.approval) }

    // The app's label and the provider's name from the catalog, each falling back to its ID.
    private fun approvalRow(approval: dev.kestrel.app.agent.eyes.ScreenshotApproval): dev.kestrel.app.agent.eyes.ScreenshotApprovalRow {
        val packages = context.packageManager
        val app = try {
            packages.getApplicationLabel(
                packages.getApplicationInfo(approval.packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0)),
            ).toString()
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            approval.packageName
        }
        val provider = graph.catalog.provider(approval.providerId)?.label ?: approval.providerId
        return dev.kestrel.app.agent.eyes.ScreenshotApprovalRow(approval, app, provider)
    }

    // installedAt's first read is a binder call, so the page's collector does not make it on the main thread.
    override val kokoroSlow: Flow<Float?> =
        graph.settings.observe(SettingKeys.KOKORO_SPEED)
            .map { it.tooSlow(graph.voice.kokoroModel, graph.installedAt) }
            .flowOn(Dispatchers.IO)

    override val listening: Flow<ListeningOptions> = graph.settings.observe(SettingKeys.LISTENING_OPTIONS)

    override suspend fun setListening(options: ListeningOptions) = write {
        graph.settings.set(SettingKeys.LISTENING_OPTIONS, options)
    }

    override val commandOptions: Flow<CommandOptions> = graph.settings.observe(SettingKeys.COMMANDS)

    override suspend fun setCommandOptions(options: CommandOptions) = write {
        graph.settings.set(SettingKeys.COMMANDS, options)
    }

    // Section 15: each change counts, also when the owner leaves the page at once. The write runs in the graph's scope,
    // which outlives the page, behind one lock for every page, so that the writes keep their order: each one queues at
    // the lock before its caller's first suspension, and a caller's cancellation does not cancel it. A failure goes to
    // the graph's handler, which logs its classes only.
    private suspend fun write(change: suspend () -> Unit) {
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { writes.withLock { change() } }.join()
    }

    override suspend fun activityLog() = graph.logs.activity()

    override suspend fun networkLog() = graph.logs.network()

    override suspend fun deleteHistory() {
        graph.logs.deleteHistory()
    }

    override val version: String by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }

    override suspend fun licenceText(licence: Licence): String = withContext(Dispatchers.IO) {
        context.assets.open(licence.asset).bufferedReader().use { it.readText() }
    }
}
