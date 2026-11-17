package dev.kestrel.voice.sherpa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroTextTest {
    @Test
    fun onlyTextWithoutAChineseCharacterGoesToKokoro() {
        assertTrue(kokoroCanSay("Kestrel, open Viber."))
        assertTrue(kokoroCanSay("Đorđe, 06:45"))
        // sherpa-onnx would log each word of it that its lexicon lacks (design spec, section 13.5).
        assertFalse(kokoroCanSay("Open 微信."))
    }
}
