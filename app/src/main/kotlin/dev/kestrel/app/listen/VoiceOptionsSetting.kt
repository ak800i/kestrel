package dev.kestrel.app.listen

import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * The owner's choice of voice and speed (design spec, sections 8.2 and 15), stored encrypted like every setting. The
 * speed lies between [SLOWEST] and [FASTEST] in steps of a tenth; a stored value outside them counts as the nearest one.
 */
@Serializable
data class VoiceOptionsSetting(val voice: VoiceChoice = VoiceChoice.PIPER, val speed: Float = 1.0f) {
    /** The options for Kestrel's voice engines. */
    fun options(): VoiceOptions = VoiceOptions(speed = speed.coerceIn(SLOWEST, FASTEST), voice = voice)

    /** A tenth slower, but not below [SLOWEST]. */
    fun slower(): VoiceOptionsSetting = copy(speed = stepped(-1))

    /** A tenth faster, but not above [FASTEST]. */
    fun faster(): VoiceOptionsSetting = copy(speed = stepped(1))

    // In whole tenths, so that steps add up exactly.
    private fun stepped(tenths: Int): Float =
        ((options().speed * 10).roundToInt() + tenths).coerceIn((SLOWEST * 10).roundToInt(), (FASTEST * 10).roundToInt()) / 10f

    companion object {
        const val SLOWEST = 0.8f
        const val FASTEST = 1.4f
    }
}

/**
 * Loads the voice that the owner chose, at the chosen speed, with [load] (design spec, sections 8.2 and 15). When the
 * Kokoro voice does not load, because its files are missing or damaged or for any other failure, Piper speaks instead,
 * so that an optional voice never keeps Kestrel from listening, and [report] gets the failure, because Kestrel never
 * fails silently (section 14). A failure of the Piper voice still throws, such as [ModelsNotReadyException].
 */
internal suspend fun loadChosenVoice(
    chosen: VoiceOptionsSetting,
    report: (String, Throwable) -> Unit = { _, _ -> },
    load: suspend (VoiceOptions) -> Synthesizer,
): Synthesizer {
    val options = chosen.options()
    if (options.voice == VoiceChoice.PIPER) return load(options)
    return try {
        load(options)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Logged by its class and stack frames only (section 13.5).
        report("Loading the Kokoro voice", e)
        load(options.copy(voice = VoiceChoice.PIPER))
    }
}
