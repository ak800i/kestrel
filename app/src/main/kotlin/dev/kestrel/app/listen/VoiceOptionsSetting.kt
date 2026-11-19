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
 * Kokoro's speed on this phone (design spec, sections 5.3 and 8.2): the real-time factor that Kestrel measured when
 * [model], the file of the Kokoro model, first loaded for a voice session after Kestrel was installed or updated at
 * [installed] (PackageInfo.lastUpdateTime). Above 1, the phone needs longer to say a sentence than the sentence lasts, so
 * Piper speaks instead. Another model or another install is measured again, and so is a Kokoro that the owner chooses
 * again. Stored encrypted like every setting.
 */
@Serializable
data class KokoroSpeed(val model: String = "", val installed: Long = 0L, val factor: Float = 0f) {
    /** Whether this is the measure of [model] in the install of [installed]. */
    fun measures(model: String, installed: Long): Boolean = this.model == model && this.installed == installed

    /** The measured factor when it [measures] [model] and [installed] and is above 1, else null. */
    fun tooSlow(model: String, installed: Long): Float? = factor.takeIf { measures(model, installed) && it > 1f }
}

/** About 5 s of speech without a keyword or a command, for the measure of Kokoro's speed. */
internal const val SPEED_SAMPLE = "The weather tomorrow will be mild, with a light wind from the west in the afternoon."

/** A short sentence that Kokoro says before the measure, so that the measure does not time its first run. */
internal const val SPEED_WARM_UP = "Hello."

/** Kokoro said nothing for the sentence of its measure, which counts as a failure to load. */
internal class KokoroSilentException : IllegalStateException("Kokoro said nothing")

/**
 * The real-time factor of [voice] for [SPEED_SAMPLE], after it has said [SPEED_WARM_UP]: the milliseconds that [clock]
 * counts while the voice synthesizes the sample, divided by the milliseconds that the sample lasts. Throws
 * [KokoroSilentException] when the voice says nothing.
 */
internal fun realTimeFactor(voice: Synthesizer, clock: () -> Long): Float {
    voice.synthesize(SPEED_WARM_UP)
    val started = clock()
    val samples = voice.synthesize(SPEED_SAMPLE)
    val took = clock() - started
    if (samples.isEmpty()) throw KokoroSilentException()
    return took / (samples.size * 1_000f / voice.sampleRate)
}

/**
 * A voice that Kestrel loaded for its voice session: [synthesizer], which speaks with [options]. [fellBack]: the owner
 * chose Kokoro, and Piper speaks instead, because Kokoro did not load. [slow]: the owner chose Kokoro, and Piper speaks
 * instead, because Kokoro speaks more slowly than real time on this phone.
 */
internal class LoadedVoice(
    val synthesizer: Synthesizer,
    val options: VoiceOptions,
    val fellBack: Boolean = false,
    val slow: Boolean = false,
)

/**
 * Loads the voice that the owner chose, at the chosen speed, with [load] (design spec, sections 8.2 and 15). When the
 * Kokoro voice does not load, because its files are missing or damaged or for any other failure, Piper speaks instead,
 * so that an optional voice never keeps Kestrel from listening, and [report] gets the failure, because Kestrel never
 * fails silently (section 14). Kokoro speaks more slowly than real time on some phones (plan 2E): when [knownTooSlow]
 * says that this phone's stored measure found that, Piper speaks without loading Kokoro at all; otherwise, when
 * [tooSlow] measures the loaded Kokoro voice as too slow, Kestrel closes it, and Piper speaks too. A Kokoro that says
 * nothing in the measure counts as not loaded; any other failure of the measure is reported, and Kokoro speaks. A failure
 * of the Piper voice still throws, such as [ModelsNotReadyException].
 */
internal suspend fun loadChosenVoice(
    chosen: VoiceOptionsSetting,
    report: (String, Throwable) -> Unit = { _, _ -> },
    knownTooSlow: suspend () -> Boolean = { false },
    tooSlow: suspend (Synthesizer) -> Boolean = { false },
    load: suspend (VoiceOptions) -> Synthesizer,
): LoadedVoice {
    val options = chosen.options()
    if (options.voice == VoiceChoice.PIPER) return LoadedVoice(load(options), options)
    val piper = options.copy(voice = VoiceChoice.PIPER)
    val known = try {
        knownTooSlow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("Reading Kokoro's speed", e)
        false
    }
    if (known) return LoadedVoice(load(piper), piper, slow = true)
    val kokoro = try {
        load(options)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Logged by its class and stack frames only (section 13.5).
        report("Loading the Kokoro voice", e)
        return LoadedVoice(load(piper), piper, fellBack = true)
    }
    val slow = try {
        tooSlow(kokoro)
    } catch (e: CancellationException) {
        kokoro.close()
        throw e
    } catch (e: KokoroSilentException) {
        report("Measuring the Kokoro voice", e)
        kokoro.close()
        return LoadedVoice(load(piper), piper, fellBack = true)
    } catch (e: Exception) {
        report("Measuring the Kokoro voice", e)
        false
    }
    if (!slow) return LoadedVoice(kokoro, options)
    kokoro.close()
    return LoadedVoice(load(piper), piper, slow = true)
}

/**
 * Loads the voice that the owner stored, which [read] reads, as [loadChosenVoice] does. A setting that cannot be read
 * counts as the default, Piper, as the listening options do, and [report] gets the failure.
 */
internal suspend fun loadStoredVoice(
    read: suspend () -> VoiceOptionsSetting,
    report: (String, Throwable) -> Unit,
    knownTooSlow: suspend () -> Boolean = { false },
    tooSlow: suspend (Synthesizer) -> Boolean = { false },
    load: suspend (VoiceOptions) -> Synthesizer,
): LoadedVoice {
    val chosen = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("Reading the voice setting", e)
        VoiceOptionsSetting()
    }
    return loadChosenVoice(chosen, report, knownTooSlow, tooSlow, load)
}
