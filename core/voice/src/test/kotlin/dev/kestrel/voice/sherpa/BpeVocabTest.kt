package dev.kestrel.voice.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class BpeVocabTest {
    @Test
    fun everyPieceGetsTheSameScore() {
        assertEquals("<unk>\t-1.0\n▁the\t-1.0\n<blk>\t-1.0\n", BpeVocab.fromTokens("<unk> 0\n▁the 42\n<blk> 8192\n"))
    }

    @Test
    fun linesWithoutAnIdAreSkipped() {
        assertEquals("▁a\t-1.0\n", BpeVocab.fromTokens("▁a 1\n\nbroken\n"))
    }
}
