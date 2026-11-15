package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Transcript
import java.io.File

/** Parakeet-TDT-0.6B-v3 through sherpa-onnx, with boost words (design spec, sections 8.2 and 8.3, B39). */
internal class SherpaRecognizer(encoder: File, decoder: File, joiner: File, tokens: File, vocab: File) : Recognizer {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = encoder.path, decoder = decoder.path, joiner = joiner.path),
                tokens = tokens.path,
                modelType = "nemo_transducer",
                numThreads = THREADS,
                // Without these two, sherpa-onnx ends the process when it gets boost words.
                modelingUnit = "bpe",
                bpeVocab = vocab.path,
            ),
            // Only modified beam search applies boost words.
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            hotwordsScore = BOOST,
        ),
    )

    override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
        val boost = BoostWords.format(boostWords)
        val first = decode(samples, boost)
        if (boost.isEmpty()) return first
        return TranscriptChoice.choose(samples.size / SAMPLE_RATE.toFloat(), first) { decode(samples, "") }
    }

    private fun decode(samples: FloatArray, boost: String): Transcript {
        val stream = if (boost.isEmpty()) recognizer.createStream() else recognizer.createStream(boost)
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val end = if (result.timestamps.isEmpty()) 0f else result.timestamps.last() + (result.durations.lastOrNull() ?: 0f)
            return Transcript(result.text.trim(), result.tokens.size, end)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    private companion object {
        // Plan 2C measures whether more threads are faster on the A71 (spike S5).
        const val THREADS = 2
        // A moderate boost: strong ones cut transcripts short in tests (design spec, section 8.3).
        const val BOOST = 2.0f
    }
}
