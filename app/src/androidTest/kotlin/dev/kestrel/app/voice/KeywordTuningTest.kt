package dev.kestrel.app.voice

import dev.kestrel.app.voice.VoiceTests.FRAME
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.silence
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.KeywordTuning
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The keyword spotter on the device's own processor with generated speech, with each of the three settings that the A71
 * session tries (plan 2E; A71 session of sub-project 2, item 8). The same audio for every setting: "Kestrel", "Hey
 * Kestrel" and "Kestrel, wake up", 10 clips of each in Piper's voice with its normal variation, each with another lead of
 * silence and another level, and 10 sentences without a keyword, fed in 20 ms frames to the speech detector and the
 * keyword spotter, as the listening pipeline feeds them. It logs, for each setting, how many clips of each phrase gave a
 * hit for "Kestrel" and how long after the start of its utterance each hit came, which must be at most 2 s for the hit to
 * name the utterance (the endpointer's window); the false hits in the sentences without a keyword, by keyword; and how
 * many milliseconds the detector and the spotter needed for each second of audio. Counts and times only. It fails only
 * when a phrase gave no hit at all with Kestrel's own setting, after it has logged everything. It puts Kestrel's setting
 * back and changes nothing on the device, so it runs on a phone too.
 */
class KeywordTuningTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.TTS)

    @Test
    fun eachSettingIsMeasuredWithTheSameGeneratedSpeech() = runBlocking {
        val clips = graph.voice.synthesizer(VoiceOptions()).use { voice ->
            fun clip(text: String, index: Int): FloatArray {
                val speech = VoiceTests.resample(voice.synthesize(text), voice.sampleRate, SAMPLE_RATE)
                val gain = 0.6f + 0.04f * index
                return silence(0.3f + 0.04f * index) + FloatArray(speech.size) { speech[it] * gain } + silence(1.5f)
            }
            Clips(
                PHRASES.associateWith { phrase -> List(CLIPS) { clip(phrase, it) } },
                OTHER_SPEECH.mapIndexed { index, text -> clip(text, index) },
            )
        }
        val before = graph.voice.keywordTuning
        val withKestrelsSetting = mutableMapOf<String, Int>()
        try {
            for (tuning in TUNINGS) {
                graph.voice.keywordTuning = tuning
                measure(tuning, clips, if (tuning == KeywordTuning()) withKestrelsSetting else mutableMapOf())
            }
        } finally {
            graph.voice.keywordTuning = before
        }
        assertTrue(
            "A phrase gave no hit for Kestrel with Kestrel's setting: $withKestrelsSetting",
            withKestrelsSetting.values.all { it > 0 },
        )
    }

    /** Feeds [clips] to a new detector and a new spotter, which loads with [tuning], logs, and fills [hits] by phrase. */
    private suspend fun measure(tuning: KeywordTuning, clips: Clips, hits: MutableMap<String, Int>) {
        val name = "boost ${tuning.boost} threshold ${tuning.threshold}"
        graph.voice.speechDetector().use { detector ->
            graph.voice.keywordSpotter().use { spotter ->
                val stream = Stream(detector, spotter)
                for ((phrase, audios) in clips.phrases) {
                    val distances = audios.mapNotNull { stream.feed(it).distance }
                        .map { String.format(Locale.ROOT, "%.2f", it) }
                    hits[phrase] = distances.size
                    log(
                        "keyword spotter, $name, \"$phrase\": a hit for Kestrel in ${distances.size} of $CLIPS clips; " +
                            "seconds from the utterance's start: ${distances.joinToString()}",
                    )
                }
                val falseHits = mutableMapOf<Keyword, Int>()
                var sentences = 0
                for (audio in clips.other) {
                    val heard = stream.feed(audio).keywords
                    if (heard.isNotEmpty()) sentences++
                    heard.forEach { falseHits.merge(it, 1, Int::plus) }
                }
                log(
                    "keyword spotter, $name, speech without a keyword: false hits in $sentences of ${clips.other.size} " +
                        "sentences (kestrel ${falseHits[Keyword.KESTREL] ?: 0}, stop ${falseHits[Keyword.STOP] ?: 0}, " +
                        "cancel ${falseHits[Keyword.CANCEL] ?: 0}); ${stream.millisPerSecond()} ms of processing per " +
                        "second of audio",
                )
            }
        }
    }

    private class Clips(val phrases: Map<String, List<FloatArray>>, val other: List<FloatArray>)

    /** One stream for a detector and a spotter, as the microphone gives them one; the spotter's hits count from its start. */
    private class Stream(private val detector: SpeechDetector, private val spotter: KeywordSpotter) {
        private var seconds = 0f
        private var nanos = 0L

        /**
         * [distance]: how long after the start of the detector's utterance the spotter's first "Kestrel" came, or null
         * without one. [keywords]: every keyword that the spotter heard.
         */
        class Heard(val distance: Float?, val keywords: List<Keyword>)

        /** Feeds [audio] in 20 ms frames. The detector counts from 0 again after the audio. */
        fun feed(audio: FloatArray): Heard {
            var start: Float? = null
            var hit: Float? = null
            val keywords = mutableListOf<Keyword>()
            val began = System.nanoTime()
            for (offset in audio.indices step FRAME) {
                val frame = audio.copyOfRange(offset, minOf(offset + FRAME, audio.size))
                val utterance = detector.accept(frame).firstOrNull()
                if (start == null && utterance != null) start = utterance.startSample / SECOND
                for (found in spotter.accept(frame)) {
                    keywords += found.keyword
                    if (hit == null && found.keyword == Keyword.KESTREL) hit = found.seconds - seconds
                }
            }
            val rest = detector.finish().firstOrNull()
            nanos += System.nanoTime() - began
            if (start == null && rest != null) start = rest.startSample / SECOND
            seconds += audio.size / SECOND
            return Heard(hit?.let { it - (start ?: 0f) }, keywords)
        }

        /** The milliseconds of processing for each second of audio so far. */
        fun millisPerSecond(): Long = if (seconds > 0f) (nanos / 1_000_000f / seconds).roundToLong() else 0L
    }

    private companion object {
        val PHRASES = listOf("Kestrel.", "Hey Kestrel.", "Kestrel, wake up.")
        const val CLIPS = 10

        // Kestrel's setting since plan 2A, and the two that the A71 session's step spotter tries.
        val TUNINGS = listOf(KeywordTuning(), KeywordTuning(3f, 0.1f), KeywordTuning(4f, 0.05f))

        // Without "Kestrel", "stop" or "cancel", some with sounds near them.
        val OTHER_SPEECH = listOf(
            "Chest roll and a pastry, please.",
            "The best trail starts near the kettle.",
            "Next Tuesday we rest well.",
            "Let's test the shell first.",
            "Can I sell the old desk?",
            "Pass the vessel to Esther.",
            "The weather tomorrow will be mild.",
            "I left the keys on the table.",
            "Check the schedule for Friday.",
            "Turn the volume down a little.",
        )
        val SECOND = SAMPLE_RATE.toFloat()
    }
}
