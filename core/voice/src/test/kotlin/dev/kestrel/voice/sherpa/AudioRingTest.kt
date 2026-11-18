package dev.kestrel.voice.sherpa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The audio around an utterance's speech (design spec, section 8.3), which the speech detector takes from its ring. */
class AudioRingTest {
    // Audio whose sample at each position is that position, so that every sample shows where it came from.
    private fun audio(from: Long, until: Long) = FloatArray((until - from).toInt()) { (from + it).toFloat() }

    private fun ring(until: Long, capacity: Int = 16_000) = AudioRing(capacity).also { it.add(audio(0, until)) }

    @Test
    fun theRingHoldsTheNewestSamplesByTheirPosition() {
        val ring = AudioRing(1_000)
        ring.add(audio(0, 700))
        ring.add(audio(700, 2_500))

        assertEquals(1_500L, ring.start)
        assertEquals(2_500L, ring.end)
        assertArrayEquals(audio(1_900, 2_100), ring.copy(1_900, 2_100), 0f)
        val copy = ring.copy()
        ring.add(audio(2_500, 3_000))
        assertArrayEquals(audio(1_500, 2_500), copy.copy(1_500, 2_500), 0f)
    }

    @Test
    fun anUtteranceGetsUpToAThirdOfASecondOfTheAudioBeforeAndAfterItsSpeech() {
        // The speech lasts from 15,000 to 25,000; the ring at its first window held the audio until 20,000.
        val utterance = AudioRing.padded(15_000, audio(15_000, 25_000), ring(20_000), ring(36_200), from = 0, pad = 4_800, longest = 480_000)

        assertEquals(15_000L, utterance.startSample)
        assertEquals(25_000L, utterance.endSample)
        assertEquals(4_800, utterance.lead)
        assertEquals(4_800, utterance.trail)
        assertArrayEquals(audio(10_200, 29_800), utterance.samples, 0f)
    }

    @Test
    fun theAudioBeforeNeverReachesBackPastTheLastUtteranceTheAudiosStartOrTheRing() {
        val speech = audio(15_000, 25_000)
        val after = ring(36_200)

        // The audio of the utterance before this one ended at 13,000.
        assertEquals(2_000, AudioRing.padded(15_000, speech, ring(20_000), after, from = 13_000, pad = 4_800, longest = 480_000).lead)
        // A cut at 30 s: the next utterance starts where the one before ended, with no audio before it.
        assertEquals(0, AudioRing.padded(15_000, speech, ring(20_000), after, from = 15_000, pad = 4_800, longest = 480_000).lead)
        // The audio started 1,000 samples before the speech.
        val early = AudioRing.padded(1_000, audio(1_000, 5_000), ring(5_500), ring(20_000), from = 0, pad = 4_800, longest = 480_000)
        assertEquals(1_000, early.lead)
        assertArrayEquals(audio(0, 9_800), early.samples, 0f)
        // The ring at the first window held only its last 6,000 samples.
        assertEquals(1_000, AudioRing.padded(15_000, speech, ring(20_000, capacity = 6_000), after, 0, 4_800, 480_000).lead)
    }

    @Test
    fun theAudioAfterIsWhatTheRingHasAndAllStaysWithinTheLongest() {
        // A cut: the speech ends where the audio does, so nothing comes after it.
        val cut = AudioRing.padded(15_000, audio(15_000, 25_000), ring(20_000), ring(25_000), from = 0, pad = 4_800, longest = 480_000)
        assertEquals(0, cut.trail)
        assertEquals(25_000L, cut.endSample)
        assertEquals(1_000, AudioRing.padded(15_000, audio(15_000, 25_000), ring(20_000), ring(26_000), 0, 4_800, 480_000).trail)
        // 29.9 s of speech leaves room for 0.1 s of the audio around it, which goes before it first.
        val long = AudioRing.padded(15_000, audio(15_000, 15_000 + 478_400), ring(20_000), ring(500_000), 0, 4_800, 480_000)
        assertEquals(1_600, long.lead)
        assertEquals(0, long.trail)
        assertEquals(480_000, long.samples.size)
    }
}
