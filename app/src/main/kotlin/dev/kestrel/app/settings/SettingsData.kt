package dev.kestrel.app.settings

import android.content.Context
import dev.kestrel.app.AppGraph
import dev.kestrel.app.R
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** What the settings pages read and change. [StoredSettingsData] connects them to Kestrel's stores; tests use a fake. */
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

    /** The owner's choices for listening (design spec, section 15). */
    val listening: Flow<ListeningOptions>

    suspend fun setListening(options: ListeningOptions)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>

    suspend fun networkLog(): List<LogEntry<NetworkEvent>>

    /** Wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory()

    /** Kestrel's version name and code, such as "0.1.0 (1)". */
    val version: String

    suspend fun licenceText(licence: Licence): String
}

class StoredSettingsData(private val graph: AppGraph, private val context: Context) : SettingsData {
    override val testMode: Flow<Boolean> = graph.settings.observe(SettingKeys.SAFETY).map { it.testMode }

    override suspend fun setTestMode(on: Boolean) {
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
    }

    override val voiceOptions: Flow<VoiceOptionsSetting> = graph.settings.observe(SettingKeys.VOICE)

    override suspend fun setVoiceOptions(options: VoiceOptionsSetting) {
        graph.settings.set(SettingKeys.VOICE, options)
    }

    // A voice and a player of its own, beside a voice session that may be open; both are closed afterwards. The voice on
    // the page itself, without the session's fallback to Piper, so that a Kokoro that does not load shows as a failure.
    override suspend fun tryVoice(options: VoiceOptionsSetting) = withContext(Dispatchers.IO) {
        val spoke = graph.voice.synthesizer(options.options()).use { voice ->
            val player = VoicePlayer(context, voice)
            try {
                player.speak(context.getString(R.string.voice_sample)).await()
            } finally {
                // As VoiceSession.close does: a cancelled caller still waits until the player no longer uses the voice.
                withContext(NonCancellable) { player.close() }
            }
        }
        check(spoke != Spoke.REFUSED) { "Android refused the audio" }
    }

    override val listening: Flow<ListeningOptions> = graph.settings.observe(SettingKeys.LISTENING_OPTIONS)

    override suspend fun setListening(options: ListeningOptions) {
        graph.settings.set(SettingKeys.LISTENING_OPTIONS, options)
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
