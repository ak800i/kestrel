package dev.kestrel.voice.sherpa

/**
 * sherpa-onnx needs Parakeet's word pieces as a `bpe.vocab` file before it applies boost words, and Parakeet's package
 * has none (B39). This turns each line `<piece> <id>` of the model's tokens.txt into a line `<piece>\t-1.0`. Pieces that
 * were scored by their position cut a transcript short in tests; the same score for every piece did not.
 */
internal object BpeVocab {
    fun fromTokens(tokens: String): String = buildString {
        for (line in tokens.lineSequence()) {
            val piece = line.substringBeforeLast(' ', missingDelimiterValue = "")
            if (piece.isNotEmpty()) append(piece).append("\t-1.0\n")
        }
    }
}
