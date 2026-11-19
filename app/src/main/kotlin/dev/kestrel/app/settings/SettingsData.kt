package dev.kestrel.app.settings

import android.content.Context
import dev.kestrel.app.AppGraph
import dev.kestrel.app.R
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.listen.KokoroSpeed
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
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
        val stored = graph.settings.get(SettingKeys.VOICE)
        if (options.voice == VoiceChoice.KOKORO && (stored.voice != VoiceChoice.KOKORO || options == stored)) {
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
            // load several hundred MB.
            if (options.voice == VoiceChoice.KOKORO && graph.listening.sessionOpen.value && graph.kokoroKnownTooSlow()) {
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

    override val kokoroSlow: Flow<Float?> =
        graph.settings.observe(SettingKeys.KOKORO_SPEED).map { it.tooSlow(graph.voice.kokoroModel, graph.installedAt) }

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
