package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.kestrel.voice.KeywordHit
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.SAMPLE_RATE
import java.io.File
import com.k2fsa.sherpa.onnx.KeywordSpotter as SherpaSpotter

/** sherpa-onnx's English keyword model, listening to one continuous stream of audio (B37). */
internal class SherpaKeywordSpotter(encoder: File, decoder: File, joiner: File, tokens: File, keywords: File) : KeywordSpotter {
    private val spotter = SherpaSpotter(
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = encoder.path, decoder = decoder.path, joiner = joiner.path),
                tokens = tokens.path,
                modelType = "zipformer2",
                numThreads = 1,
            ),
            // With 4 active paths, the spotter missed "Kestrel" inside a sentence.
            maxActivePaths = 8,
            keywordsFile = keywords.path,
        ),
    )
    private val stream = spotter.createStream()
    private var samplesSeen = 0L

    override fun accept(samples: FloatArray): List<KeywordHit> {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        samplesSeen += samples.size
        val hits = mutableListOf<KeywordHit>()
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = KeywordLines.keyword(spotter.getResult(stream).keyword) ?: continue
            hits += KeywordHit(keyword, samplesSeen / SAMPLE_RATE.toFloat())
            // Listens afresh, so that one spoken keyword gives one hit.
            spotter.reset(stream)
        }
        return hits
    }

    override fun close() {
        stream.release()
        spotter.release()
    }
}
