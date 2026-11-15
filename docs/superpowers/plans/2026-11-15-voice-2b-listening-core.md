# Voice 2B: Listening Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the parts of Kestrel's listening that work without the listening service: when an utterance ends, what a transcript asks for, voice output that the owner can interrupt, the microphone, the speech pipeline from microphone frames to transcripts, and the Orchestrator's listening rules.

**Architecture:** `:core:voice` gets two packages on top of plan 2A's engines. `dev.kestrel.voice.listen` holds the endpointer, the microphone and the pipeline, which turns 20 ms frames into transcripts and keyword events. `dev.kestrel.voice.speak` holds the sentence splitter, the echo filter and the player. `:app` gets the package `dev.kestrel.app.orchestrator`: the reading of transcripts, the Orchestrator's rules as one pure function, and a small actor that applies them. Plan 2C puts these parts together in `ListeningService` and adds the ways to start it; plan 2D adds the assistant role, calls, earbuds and the measurements on the A71.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0, sherpa-onnx 1.13.8 through plan 2A's interfaces, Android's `AudioRecord`, `AudioTrack` and `AudioManager`, JUnit 4 with kotlinx-coroutines-test, and device tests on the `kestrel-api37` emulator through `scripts\voice-test.ps1`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; every dependency that ships in the app needs a GPL-3.0-compatible licence (D21, D28, section 17). This plan adds no dependency to the app; kotlinx-coroutines-test is test-only.
- `minSdk 33`, `targetSdk 37` and `compileSdk 37` (section 6).
- There is no dependency-injection framework; one `AppGraph` class wires the parts together by hand (section 17).
- Kestrel records with `AudioRecord`, source `VOICE_RECOGNITION`, at 16 kHz in mono with 16-bit samples, in 20 ms frames (section 8.1).
- It never uses a privacy-sensitive source (`CAMCORDER`, `VOICE_COMMUNICATION` or `setPrivacySensitive(true)`), because such a source would silence other listeners such as Voice Access or a phone call (section 8.1, B2).
- Kestrel registers an `AudioRecordingCallback`, and the feedback bar shows "mic busy" whenever Android silences Kestrel's recording (section 8.1).
- Kestrel attaches Android's echo canceller (`AcousticEchoCanceler`) when the device supports it for this source (section 8.1).
- An utterance ends after 0.7 s of silence. When the keyword spotter hears "Kestrel" at the start of an utterance, the limit becomes 1.5 s, so that the owner can pause while dictating a task. An utterance can last at most 30 s (section 8.3).
- A transcript is a task when its first word matches "Kestrel", allowing near misses such as "Kestral". When the keyword spotter heard the name at the start of the utterance, more distant spellings count, and the name may also be the second word, as in "Hey Kestrel". Kestrel removes the name, and the rest is the task. "Kestrel" on its own makes Kestrel answer "Yes?" and treat the next utterance as the task (section 8.3).
- Kestrel speaks through its own voice engine, with the audio usage "assistant" (section 8.4).
- While Kestrel speaks, recognition pauses and the keyword spotter listens for "stop", "cancel" and "Kestrel". Kestrel ignores a keyword hit that matches a word it is speaking at that moment. "Stop" or "cancel" ends the speech and also cancels a running task. "Kestrel" ends the speech and listens for a new request (section 8.4).
- The listening modes: Off (the microphone is released), Listening (voice activity detection, keyword spotting and recognition), Paused (recognition runs only on utterances that start with "Kestrel"). "Kestrel, wake up" returns from Paused to Listening; "Kestrel, [task]" in Paused runs the task and then returns to Paused. When the screen turns off or the phone locks, Kestrel releases the microphone (section 8.5). Ringing and In call come in plan 2D.
- While a task runs, Kestrel accepts only "stop", "cancel" and the answer it asked for. It ignores all other speech. To start a different task, the owner first says "stop" (section 11.10).
- A single `Orchestrator` coordinates everything. It tracks the listening mode, the current activity and whether Kestrel is speaking. Each transition is defined in one place and covered by unit tests (section 7.3).
- Android 17 refuses audio playback and audio focus to an app in the background unless it runs a suitable foreground service; a refused focus request returns `AUDIOFOCUS_REQUEST_FAILED` (B5).
- Screenshots, audio and credentials are never logged (section 13.5). Nothing in this plan writes audio or transcripts to logcat or to files.
- Testing happens only on the `kestrel-api37` emulator in this plan. Never test on the Galaxy S23+ or the AVD `zoran`, and never send a command to the Galaxy A71 (D20, AGENTS.md).

## Before you start

- Plan 2A is complete: `:core:voice` has `SAMPLE_RATE`, `Utterance`, `SpeechDetector`, `Keyword`, `KeywordHit`, `KeywordSpotter`, `Transcript`, `Recognizer`, `Synthesizer`, `VoiceOptions` and `VoiceEngines`, and `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt` has the helpers for device tests (`graph`, `assumeModels`, `voice`, `speech`, `silence`, `frames`, `log`, `pssMegabytes`).
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` when you are done.
- Run the voice device tests only through `scripts\voice-test.ps1 -Serial <serial>`, which installs Kestrel, copies the models and runs the tests in `dev.kestrel.app.voice`. `<serial>` is the serial that `scripts\start-emulator.ps1` prints; check with `adb -s <serial> emu avd name` that it is `kestrel-api37`. Run it from a command that first does `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain` when you need `Get-Adb`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## File structure

| File | Responsibility |
|---|---|
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt` | `Speech` and `Endpointer`: when an utterance has ended, with the longer pause after "Kestrel" |
| `core/voice/src/main/kotlin/dev/kestrel/voice/speak/Sentences.kt` | Splits a reply into sentences for the player |
| `core/voice/src/main/kotlin/dev/kestrel/voice/speak/EchoFilter.kt` | `Spoken` and `EchoFilter`: whether a keyword hit was Kestrel's own voice |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt` | `Request` and `Requests.read`: the name, the task and Kestrel's own phrases in a transcript |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt` | `Recognition`, `VoiceEvent` and `ListeningPipeline`: frames to transcripts and keyword events |
| `core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt` | `Spoke` and `VoicePlayer`: speaks sentence by sentence with audio focus, and stops at once |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt` | `MicCapture`: the microphone in 20 ms frames, the echo canceller and the "mic busy" signal |
| `core/voice/src/main/AndroidManifest.xml` | Declares the microphone permission for `:core:voice`, which lint's `MissingPermission` check needs |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` | `Mode`, `Activity`, `State`, `Event`, `Effect`, `Reply`, `Feedback`, `Step` and `Rules.next`: every transition |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` | `Orchestrator`: applies the rules to events one at a time and runs the timer for "Yes?" |

Tests sit next to them: JVM tests in `core/voice/src/test/kotlin/dev/kestrel/voice/...` and `app/src/test/kotlin/dev/kestrel/app/orchestrator/`, device tests in `app/src/androidTest/kotlin/dev/kestrel/app/voice/`.

---

### Task 1: The end of an utterance

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/listen/EndpointerTest.kt`

**Interfaces:**
- Consumes: `Utterance(startSample: Long, samples: FloatArray)` and `SAMPLE_RATE` from plan 2A.
- Produces: in package `dev.kestrel.voice.listen`: `class Speech(startSample: Long, samples: FloatArray, named: Boolean)` with `endSample: Long` and `seconds: Float`; `class Endpointer` with `fun accept(utterances: List<Utterance>, nameHits: List<Long>, hearsSpeech: Boolean, position: Long): List<Speech>` and `fun finish(): List<Speech>`. Task 4's pipeline feeds it.

The speech detector of plan 2A ends every utterance after 0.7 s of silence. The endpointer adds the longer limit after "Kestrel": it holds an utterance that starts with the name, and joins the next utterance to it when that one starts less than 1.5 s after the held one's end. The pause between them becomes silence in the joined samples. The detector reports speech only after 0.25 s of it (sherpa-onnx's minimum speech duration), so a held utterance is let go only after 1.85 s without speech: a pause that ends just before 1.5 s still joins. `nameHits` are the positions at which the keyword spotter heard "Kestrel"; an utterance starts with the name when a hit comes at most 1.5 s after its start. All positions count samples since the audio started.

- [ ] **Step 1: Write the failing test**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/listen/EndpointerTest.kt`:

```kotlin
package dev.kestrel.voice.listen

import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class EndpointerTest {
    private val endpointer = Endpointer()

    @Test
    fun anUtteranceWithoutTheNameIsReadyAtOnce() {
        val ready = endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        val speech = ready.single()
        assertFalse(speech.named)
        assertEquals(at(0.5f), speech.startSample)
        assertEquals(at(1f).toInt(), speech.samples.size)
    }

    @Test
    fun anUtteranceThatStartsWithTheNameWaitsLongerForThePauseToEnd() {
        assertEquals(none, endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f)))
        assertEquals(none, endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f)))
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.3f)))

        val ready = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f))

        assertTrue(ready.single().named)
    }

    @Test
    fun theNextUtteranceJoinsWhenItStartsWithinTheLongerPause() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f, value = 0.5f)), emptyList(), hearsSpeech = false, position = at(2.2f))
        // The owner speaks again at 2.9 s, 1.4 s after the end, and the detector notices it only about 0.28 s later.
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.15f)))
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(3.2f)))
        assertEquals(none, endpointer.accept(listOf(utterance(2.9f, 1f, value = 0.25f)), emptyList(), hearsSpeech = false, position = at(4.6f)))

        val speech = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(5.75f)).single()

        assertTrue(speech.named)
        assertEquals(at(0.5f), speech.startSample)
        assertEquals(at(3.4f).toInt(), speech.samples.size)
        assertEquals(0.5f, speech.samples[0])
        assertEquals(0f, speech.samples[at(1f).toInt()])
        assertEquals(0.25f, speech.samples[at(2.4f).toInt()])
        assertEquals(0.25f, speech.samples.last())
    }

    @Test
    fun anUtteranceThatStartsAfterTheLongerPauseIsSeparate() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        val first = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f))
        val second = endpointer.accept(listOf(utterance(3.5f, 0.5f)), emptyList(), hearsSpeech = false, position = at(4.7f))

        assertTrue(first.single().named)
        assertFalse(second.single().named)
        assertEquals(at(3.5f), second.single().startSample)
    }

    @Test
    fun theNameMustComeAtTheStart() {
        endpointer.accept(emptyList(), listOf(at(3f)), hearsSpeech = true, position = at(3f))

        val ready = endpointer.accept(listOf(utterance(0.5f, 3.5f)), emptyList(), hearsSpeech = false, position = at(4.7f))

        assertFalse(ready.single().named)
    }

    @Test
    fun aJoinedUtteranceLastsAtMostThirtySeconds() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 20f)), emptyList(), hearsSpeech = false, position = at(21.2f))

        val ready = endpointer.accept(listOf(utterance(21.5f, 15f)), emptyList(), hearsSpeech = false, position = at(37.2f))

        assertEquals(listOf(true, false), ready.map { it.named })
    }

    @Test
    fun aHeldUtteranceIsReadyAfterThirtySecondsEvenWhileTheDetectorHearsSpeech() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(30f)))

        assertTrue(endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(30.5f)).single().named)
    }

    @Test
    fun finishReturnsTheHeldUtterance() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        assertTrue(endpointer.finish().single().named)
        assertEquals(none, endpointer.finish())
    }

    private val none = emptyList<Speech>()

    private fun at(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()

    private fun utterance(start: Float, seconds: Float, value: Float = 0.5f) =
        Utterance(at(start), FloatArray(at(seconds).toInt()) { value })
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.EndpointerTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'Endpointer'".

- [ ] **Step 3: Write the endpointer**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt`:

```kotlin
package dev.kestrel.voice.listen

import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Utterance
import kotlin.math.roundToLong

/**
 * An utterance that is ready to transcribe. [startSample] counts samples since the audio started, and [named] is true
 * when the keyword spotter heard "Kestrel" at its start.
 */
class Speech(val startSample: Long, val samples: FloatArray, val named: Boolean) {
    val endSample: Long get() = startSample + samples.size
    val seconds: Float get() = samples.size / SAMPLE_RATE.toFloat()
}

/**
 * Decides when an utterance has ended (design spec, section 8.3). The speech detector ends an utterance after 0.7 s of
 * silence. When the keyword spotter heard "Kestrel" at its start, the limit is 1.5 s instead: the endpointer holds the
 * utterance, and joins the next one to it when that one starts less than 1.5 s after its end, so that the owner can
 * pause while dictating a task. A joined utterance lasts at most 30 s. Not thread-safe: feed it from one thread.
 */
class Endpointer {
    private val names = ArrayDeque<Long>()
    private var held: Speech? = null

    /**
     * Takes the utterances that the speech detector returned for the audio up to [position], the positions at which the
     * keyword spotter heard "Kestrel" in the same audio, and whether the detector hears speech now, and returns the
     * utterances that are complete. All positions count samples since the audio started.
     */
    fun accept(utterances: List<Utterance>, nameHits: List<Long>, hearsSpeech: Boolean, position: Long): List<Speech> {
        names.addAll(nameHits)
        val ready = mutableListOf<Speech>()
        for (utterance in utterances) {
            val speech = Speech(utterance.startSample, utterance.samples, startsWithName(utterance))
            val earlier = held
            val joined = earlier?.let { join(it, speech) }
            held = when {
                joined != null -> joined
                speech.named -> speech
                else -> null
            }
            if (joined == null) {
                if (earlier != null) ready += earlier
                if (!speech.named) ready += speech
            }
        }
        val waiting = held
        if (waiting != null &&
            (!hearsSpeech && position - waiting.endSample >= RELEASE_AFTER || position - waiting.startSample >= LONGEST)
        ) {
            ready += waiting
            held = null
        }
        while (names.isNotEmpty() && names.first() < position - LONGEST - RELEASE_AFTER) names.removeFirst()
        return ready
    }

    /** Ends the audio, and returns the utterance that the endpointer still holds, if any. */
    fun finish(): List<Speech> {
        val last = listOfNotNull(held)
        held = null
        names.clear()
        return last
    }

    private fun startsWithName(utterance: Utterance): Boolean =
        names.any { it >= utterance.startSample && it <= utterance.startSample + NAME_WINDOW }

    /** [earlier], silence for the pause and [later] in one utterance, or null when the pause or the result is too long. */
    private fun join(earlier: Speech, later: Speech): Speech? {
        val pause = maxOf(0L, later.startSample - earlier.endSample)
        val size = earlier.samples.size + pause + later.samples.size
        if (pause >= NAMED_PAUSE || size > LONGEST) return null
        val samples = FloatArray(size.toInt())
        earlier.samples.copyInto(samples)
        later.samples.copyInto(samples, (earlier.samples.size + pause).toInt())
        return Speech(earlier.startSample, samples, named = true)
    }

    private companion object {
        // Design spec, section 8.3.
        val NAMED_PAUSE = samples(1.5f)
        val LONGEST = samples(30f)

        // The speech detector reports speech only after 0.25 s of it (sherpa-onnx's minimum speech duration) and up to
        // two windows of 32 ms, so a held utterance waits that much longer than the pause that still joins utterances.
        val RELEASE_AFTER = NAMED_PAUSE + samples(0.35f)

        // "Kestrel" takes about half a second, and the spotter reports it a little after the word ends.
        val NAME_WINDOW = samples(1.5f)

        fun samples(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()
    }
}
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.EndpointerTest`
Expected: `BUILD SUCCESSFUL`, with 8 tests in `EndpointerTest`.

- [ ] **Step 5: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/EndpointerTest.kt
git commit -m "feat(voice): end an utterance later when it starts with Kestrel" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Sentences and the echo filter

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/speak/Sentences.kt`
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/speak/EchoFilter.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/speak/SentencesTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/speak/EchoFilterTest.kt`

**Interfaces:**
- Consumes: `Keyword` (plan 2A): `KESTREL`, `STOP` and `CANCEL`, whose names are the spoken words.
- Produces: in package `dev.kestrel.voice.speak`: `object Sentences` with `fun split(text: String): List<String>` and `const val MAX_LENGTH = 200`; `class Spoken(text: String, startMillis: Long, endMillis: Long)`; `class EchoFilter(lateMillis: Long = 1_500, earlyMillis: Long = 1_000)` with `fun isOwnVoice(keyword: Keyword, hitMillis: Long, spoken: List<Spoken>): Boolean`. Task 5's player uses `Sentences` and records `Spoken`; plan 2C asks the filter about each keyword hit while Kestrel speaks.

Kestrel speaks a reply sentence by sentence, so that the first sentence starts while the next one is synthesized, and so that "stop" takes effect at once. The echo filter places each word of a played sentence in time, in proportion to the characters before the word's end, and calls a keyword hit Kestrel's own voice when a word that starts with the keyword ended shortly before the hit. Times are on the elapsed-time clock in milliseconds.

- [ ] **Step 1: Write the failing tests**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/speak/SentencesTest.kt`:

```kotlin
package dev.kestrel.voice.speak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {
    @Test
    fun splitsAtTheEndOfEachSentence() {
        assertEquals(
            listOf("Done.", "I sent the message!", "Anything else?"),
            Sentences.split("Done. I sent the message!  Anything else?"),
        )
    }

    @Test
    fun keepsNumbersTogether() {
        assertEquals(listOf("It costs 3.50 euros."), Sentences.split("It costs 3.50 euros."))
    }

    @Test
    fun splitsAtLineBreaks() {
        assertEquals(listOf("First line", "Second line"), Sentences.split("First line\n\nSecond line\n"))
    }

    @Test
    fun splitsALongSentenceAfterAComma() {
        val first = "a".repeat(150) + ","
        val second = "b".repeat(100)

        assertEquals(listOf(first, second), Sentences.split("$first $second"))
    }

    @Test
    fun splitsALongSentenceWithoutPunctuationAtSpaces() {
        val pieces = Sentences.split(List(60) { "word" }.joinToString(" "))

        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.length <= Sentences.MAX_LENGTH })
        assertEquals(List(60) { "word" }, pieces.flatMap { it.split(" ") })
    }

    @Test
    fun emptyTextHasNoSentences() {
        assertEquals(emptyList<String>(), Sentences.split("  \n "))
    }
}
```

Create `core/voice/src/test/kotlin/dev/kestrel/voice/speak/EchoFilterTest.kt`:

```kotlin
package dev.kestrel.voice.speak

import dev.kestrel.voice.Keyword
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {
    private val filter = EchoFilter()

    // "Stop" ends after 4 of the 15 characters, so at about 1,266 ms.
    private val question = Spoken("Stop the timer?", startMillis = 1_000, endMillis = 2_000)

    @Test
    fun aKeywordThatKestrelJustSaidIsItsOwnVoice() {
        assertTrue(filter.isOwnVoice(Keyword.STOP, hitMillis = 1_600, spoken = listOf(question)))
    }

    @Test
    fun theSameKeywordLaterIsTheOwners() {
        assertFalse(filter.isOwnVoice(Keyword.STOP, hitMillis = 4_000, spoken = listOf(question)))
    }

    @Test
    fun aKeywordThatKestrelDidNotSayIsTheOwners() {
        assertFalse(filter.isOwnVoice(Keyword.CANCEL, hitMillis = 1_600, spoken = listOf(question)))
    }

    @Test
    fun aWordThatStartsWithTheKeywordCounts() {
        // "cancelled" ends after 11 of the 22 characters, so at 1,600 ms.
        val spoken = listOf(Spoken("I cancelled the alarm.", startMillis = 1_000, endMillis = 2_200))

        assertTrue(filter.isOwnVoice(Keyword.CANCEL, hitMillis = 2_100, spoken = spoken))
    }

    @Test
    fun capitalsDoNotMatter() {
        assertTrue(filter.isOwnVoice(Keyword.KESTREL, hitMillis = 1_000, spoken = listOf(Spoken("KESTREL here.", 0, 1_000))))
    }

    @Test
    fun withNothingSpokenEveryHitIsTheOwners() {
        assertFalse(filter.isOwnVoice(Keyword.STOP, hitMillis = 1_600, spoken = emptyList()))
    }
}
```

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests 'dev.kestrel.voice.speak.*'`
Expected: `BUILD FAILED`, with "Unresolved reference 'Sentences'" and "Unresolved reference 'EchoFilter'".

- [ ] **Step 3: Write the sentence splitter and the echo filter**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/speak/Sentences.kt`:

```kotlin
package dev.kestrel.voice.speak

/**
 * Splits a reply into sentences, so that Kestrel can speak the first one while it synthesizes the next, and can stop
 * between them (design spec, section 8.4). A sentence longer than [MAX_LENGTH] characters is split after a comma, a
 * semicolon or a colon, or else at a space.
 */
object Sentences {
    const val MAX_LENGTH = 200

    private val END = Regex("""(?<=[.!?…])\s+|\s*\n\s*""")

    fun split(text: String): List<String> =
        text.split(END).map(String::trim).filter(String::isNotEmpty).flatMap(::shorten)

    private fun shorten(sentence: String): List<String> {
        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_LENGTH) {
            val head = rest.substring(0, MAX_LENGTH)
            val pause = head.indexOfLast { it == ',' || it == ';' || it == ':' }
            val space = head.lastIndexOf(' ')
            val cut = when {
                pause > 0 -> pause + 1
                space > 0 -> space
                else -> MAX_LENGTH
            }
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/speak/EchoFilter.kt`:

```kotlin
package dev.kestrel.voice.speak

import dev.kestrel.voice.Keyword

/** A sentence that Kestrel played, from [startMillis] to [endMillis] on the elapsed-time clock. */
class Spoken(val text: String, val startMillis: Long, val endMillis: Long)

/**
 * Tells whether a keyword hit came from Kestrel's own voice (design spec, section 8.4). Kestrel knows what it said and
 * roughly when it said each word: it places a word in its sentence's playing time in proportion to the characters up to
 * the word's end. A hit is Kestrel's own voice when a word that starts with the keyword ended at most [lateMillis] before
 * the hit or at most [earlyMillis] after it, because the spotter reports a keyword a little after it ends, and the
 * estimate is rough.
 */
class EchoFilter(private val lateMillis: Long = 1_500, private val earlyMillis: Long = 1_000) {
    fun isOwnVoice(keyword: Keyword, hitMillis: Long, spoken: List<Spoken>): Boolean {
        val word = keyword.name.lowercase()
        return spoken.any { sentence ->
            WORD.findAll(sentence.text.lowercase()).any { match ->
                match.value.startsWith(word) && hitMillis - wordEnd(sentence, match.range.last + 1) in -earlyMillis..lateMillis
            }
        }
    }

    private fun wordEnd(sentence: Spoken, characters: Int): Long =
        sentence.startMillis + (sentence.endMillis - sentence.startMillis) * characters / sentence.text.length

    private companion object {
        val WORD = Regex("""\p{L}+""")
    }
}
```

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests 'dev.kestrel.voice.speak.*'`
Expected: `BUILD SUCCESSFUL`, with 6 tests in `SentencesTest` and 6 in `EchoFilterTest`.

- [ ] **Step 5: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/speak core/voice/src/test/kotlin/dev/kestrel/voice/speak
git commit -m "feat(voice): split replies into sentences and recognise Kestrel's own keywords" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: What a transcript asks for

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: in package `dev.kestrel.app.orchestrator`: `sealed interface Request` with `data class Task(text: String)`, `data object Name`, `data object WakeUp`, `data class Stop(named: Boolean)`, `data object PauseListening`, `data object TurnOff` and `data class Other(text: String)`; `object Requests` with `fun read(transcript: String, spotted: Boolean): Request?`, which returns null for a transcript without words. Task 7's rules call it for every transcript.

The recogniser can misspell the name, for example as "Kestral", "Castrel" or "Kes trel". A word counts as the name when it has at least five letters, starts with "k" or "c", and is at most two letters away from "kestrel" (the Levenshtein distance, after lower-casing and removing apostrophes). Two words count when they make the name together. When the keyword spotter heard the name at the start of the utterance (`spotted`), three letters are allowed, and the name may also be the second word, as in "Hey Kestrel, …". The task keeps the owner's words as the recogniser wrote them, with their capitals and punctuation, because the agent of sub-project 5 reads it.

Kestrel's own phrases from Appendix A that this plan handles: "Kestrel" on its own, "Kestrel, wake up", "Kestrel, stop" (or "cancel"), "Stop listening", "Stop Kestrel", and plain "stop" or "cancel", which matter while a task runs (section 11.10). "Stop" and "cancel" also count with a few filler words, as in "Stop, stop!", "Stop it" or "Okay, cancel that", so that the owner can always stop a task. "Kestrel, stop listening" pauses too. Everything else goes to the instant commands of sub-project 3 as `Other`.

The requests that carry the owner's words print only the number of characters, so that a log line or an exception message can never contain what the owner said (section 13.5).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestsTest {
    @Test
    fun theNameStartsATask() {
        assertEquals(Request.Task("open the settings."), Requests.read("Kestrel, open the settings.", spotted = false))
    }

    @Test
    fun aMisspeltNameStillStartsATask() {
        assertEquals(Request.Task("open Viber"), Requests.read("Kestral open Viber", spotted = false))
        assertEquals(Request.Task("call Ana."), Requests.read("Castrel, call Ana.", spotted = false))
        assertEquals(Request.Task("call Ana"), Requests.read("Kes trel, call Ana", spotted = false))
        assertEquals(Request.Task("send it"), Requests.read("Kestrel's send it", spotted = false))
    }

    @Test
    fun aNameFurtherAwayCountsOnlyWhenTheSpotterHeardIt() {
        assertEquals(Request.Task("open the settings"), Requests.read("Castral, open the settings", spotted = true))
        assertEquals(Request.Other("Castral, open the settings"), Requests.read("Castral, open the settings", spotted = false))
    }

    @Test
    fun theNameMayComeSecondWhenTheSpotterHeardIt() {
        assertEquals(Request.Task("open the settings."), Requests.read("Hey Kestrel, open the settings.", spotted = true))
        assertEquals(Request.Name, Requests.read("Hey Kes trel?", spotted = true))
        assertEquals(Request.Other("Hey Kestrel, open the settings."), Requests.read("Hey Kestrel, open the settings.", spotted = false))
    }

    @Test
    fun wordsThatOnlyLookAlikeAreNotTheName() {
        assertEquals(Request.Other("Kettle on, please"), Requests.read("Kettle on, please", spotted = false))
        assertEquals(Request.Other("Nestle chocolate"), Requests.read("Nestle chocolate", spotted = true))
        assertEquals(Request.Other("Open the settings"), Requests.read("Open the settings", spotted = true))
    }

    @Test
    fun theNameOnItsOwn() {
        assertEquals(Request.Name, Requests.read("Kestrel.", spotted = false))
        assertEquals(Request.Name, Requests.read("Kestrel?", spotted = true))
    }

    @Test
    fun kestrelsOwnPhrasesWithTheName() {
        assertEquals(Request.WakeUp, Requests.read("Kestrel, wake up.", spotted = false))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, stop.", spotted = true))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel cancel", spotted = false))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, stop it.", spotted = false))
        assertEquals(Request.PauseListening, Requests.read("Kestrel, stop listening", spotted = false))
    }

    @Test
    fun kestrelsOwnPhrasesWithoutTheName() {
        assertEquals(Request.Stop(named = false), Requests.read("Stop.", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Cancel!", spotted = false))
        assertEquals(Request.PauseListening, Requests.read("Stop listening.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Stop Kestrel.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Stop Kestral", spotted = false))
        // The spotter also fires at the start of "Stop Kestrel".
        assertEquals(Request.TurnOff, Requests.read("Stop Kestrel.", spotted = true))
        assertEquals(Request.TurnOff, Requests.read("Stop Kes trel", spotted = true))
        assertEquals(Request.TurnOff, Requests.read("Stop Kes trel", spotted = false))
    }

    @Test
    fun stopAndCancelCountWithFillerWords() {
        assertEquals(Request.Stop(named = false), Requests.read("Stop, stop!", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Cancel that.", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Okay, stop.", spotted = false))
        assertEquals(Request.Other("Stop the timer"), Requests.read("Stop the timer", spotted = false))
    }

    @Test
    fun aTranscriptWithoutWordsIsNothing() {
        assertNull(Requests.read("", spotted = false))
        assertNull(Requests.read(" ... ", spotted = true))
    }

    @Test
    fun theOwnersWordsAreNotPrinted() {
        assertEquals("Task(17 characters)", Request.Task("open the settings").toString())
        assertEquals("Other(11 characters)", Request.Other("Scroll down").toString())
    }
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RequestsTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'Requests'".

- [ ] **Step 3: Write the reader**

Create `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`:

```kotlin
package dev.kestrel.app.orchestrator

/** What a transcript asks of Kestrel itself (design spec, sections 8.3, 8.5 and 11.10, and Appendix A). */
sealed interface Request {
    /** "Kestrel, [task]". [text] is the task as the owner said it, without the name. */
    data class Task(val text: String) : Request {
        override fun toString() = "Task(${text.length} characters)"
    }

    /** "Kestrel" on its own: Kestrel answers "Yes?" and takes the next utterance as the task. */
    data object Name : Request

    /** "Kestrel, wake up": back from the paused mode to listening. */
    data object WakeUp : Request

    /** "Stop" or "cancel"; [named] when the owner said "Kestrel" first. */
    data class Stop(val named: Boolean) : Request

    /** "Stop listening": the paused mode. */
    data object PauseListening : Request

    /** "Stop Kestrel": off. */
    data object TurnOff : Request

    /** Anything else, which the instant commands of sub-project 3 will read. */
    data class Other(val text: String) : Request {
        override fun toString() = "Other(${text.length} characters)"
    }
}

/**
 * Reads what a transcript asks of Kestrel (design spec, section 8.3). The recogniser may misspell the name, for example
 * as "Kestral", "Castrel" or "Kes trel", so a word counts as the name when it has at least five letters, starts with "k"
 * or "c" and is at most two letters away from "kestrel". When the keyword spotter heard the name at the start of the
 * utterance, three letters are allowed, and the name may also be the second word, as in "Hey Kestrel".
 */
object Requests {
    private const val NAME = "kestrel"
    private const val HEARD_LIMIT = 2
    private const val SPOTTED_LIMIT = 3
    private val WORD = Regex("""[\p{L}\p{N}']+""")
    private val STOP_WORDS = setOf("stop", "cancel")

    // Words that may come with "stop" or "cancel", as in "Okay, stop it".
    private val FILLERS = setOf("it", "that", "this", "please", "now", "ok", "okay")

    /** Returns null when [transcript] has no words. [spotted]: the keyword spotter heard "Kestrel" at its start. */
    fun read(transcript: String, spotted: Boolean): Request? {
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }.toList()
        if (words.isEmpty()) return null
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        // Before the name: the spotter also fires at the start of "Stop Kestrel".
        if (words.size in 2..3 && words[0].text == "stop" && nameEnd(words.drop(1), limit, spotted = false) == words.size - 1) {
            return Request.TurnOff
        }
        val name = nameEnd(words, limit, spotted)
        if (name > 0) return named(transcript, words.drop(name))
        return when {
            isStop(words) -> Request.Stop(named = false)
            words.joinToString(" ") { it.text } == "stop listening" -> Request.PauseListening
            else -> Request.Other(transcript.trim())
        }
    }

    private class Word(val text: String, val range: IntRange)

    /** What [rest], the words after the name, ask for. */
    private fun named(transcript: String, rest: List<Word>): Request = when {
        rest.isEmpty() -> Request.Name
        isStop(rest) -> Request.Stop(named = true)
        else -> when (rest.joinToString(" ") { it.text }) {
            "wake up" -> Request.WakeUp
            "stop listening" -> Request.PauseListening
            else -> Request.Task(transcript.substring(rest.first().range.first).trim())
        }
    }

    private fun isStop(words: List<Word>): Boolean =
        words.any { it.text in STOP_WORDS } && words.all { it.text in STOP_WORDS || it.text in FILLERS }

    /**
     * How many of the first words end with the name, or 0. The name is one word or two that make it together, and it is
     * the first word, or, when the spotter heard it, also the second.
     */
    private fun nameEnd(words: List<Word>, limit: Int, spotted: Boolean): Int {
        for (first in 0..(if (spotted) 1 else 0)) {
            if (first >= words.size) break
            if (isName(words[first].text, limit)) return first + 1
            if (first + 1 < words.size && isName(words[first].text + words[first + 1].text, limit)) return first + 2
        }
        return 0
    }

    private fun isName(word: String, limit: Int): Boolean =
        word.length >= 5 && word[0] in "kc" && editDistance(word, NAME) <= limit

    /** The Levenshtein distance: how many letters must be added, removed or replaced to turn [a] into [b]. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RequestsTest`
Expected: `BUILD SUCCESSFUL`, with 11 tests in `RequestsTest`.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt
git commit -m "feat(app): read the name, the task and Kestrel's own phrases in a transcript" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The listening pipeline

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningTest.kt`

**Interfaces:**
- Consumes: `SpeechDetector`, `KeywordSpotter`, `Recognizer`, `Transcript`, `Utterance`, `Keyword`, `KeywordHit` and `SAMPLE_RATE` (plan 2A); `Speech` and `Endpointer` (Task 1); `VoiceTests` and `VoiceEngines.speechDetector()`, `keywordSpotter()` and `recognizer()` for the device test (plan 2A).
- Produces: in package `dev.kestrel.voice.listen`: `enum class Recognition { ALL, NAMED, NONE }`; `sealed interface VoiceEvent` with `data class Heard(text: String, named: Boolean, seconds: Float, millis: Long)`, `data class Spotted(keyword: Keyword)` and `data class Failed(error: Exception)`; `class ListeningPipeline(detector, spotter, recognizer, boostWords: suspend () -> List<String>, isOwnVoice: (Keyword) -> Boolean, scope: CoroutineScope, recognitionContext: CoroutineContext)` with `val events: Flow<VoiceEvent>`, `@Volatile var recognition: Recognition`, `fun accept(frame: FloatArray)`, `fun pause()` and `suspend fun close()`. Plan 2C's `ListeningService` feeds it from the microphone and sets `recognition` from the Orchestrator's state.

The speech detector and the keyword spotter hear every frame. The endpointer (Task 1) decides when an utterance has ended, with the positions of the name hits as the pipeline counts them. The recogniser transcribes the utterances that `recognition` asks for: all of them while Kestrel listens, only the ones that start with "Kestrel" while it is paused, and none while it speaks. It works on one utterance at a time in `recognitionContext`, with the boost words that `boostWords` returns at that moment (section 8.3). Every transcript goes out, also an empty one, so that plan 2C can notice when recognition keeps returning empty text (section 14.6).

What the microphone heard while Kestrel spoke is Kestrel's own voice. When recognition resumes, the pipeline cuts it away: an utterance that began earlier keeps only its part from that moment on, and nothing of it when less than 0.25 s is left. When the owner broke in with "Kestrel" while Kestrel spoke, at most 3 s before recognition resumed, an utterance that starts at most 1.5 s before the hit starts with the owner's "Kestrel" and counts in full; that is what happens when an echo canceller or earbuds keep Kestrel's voice out of the microphone. An utterance that began earlier began with Kestrel's voice, which also runs under and after the name, so the pipeline drops it, and Kestrel waits for the request that the owner says after it stopped (section 8.4). A "Kestrel" heard while Kestrel did not speak is no interruption. Keyword hits that `isOwnVoice` calls Kestrel's own are dropped before anything else sees them. `pause()` drops the utterance in progress after the microphone stops, so that it does not run into the audio that comes when the microphone starts again; the speech detector then counts from 0, and the pipeline adds the offset. `close()` returns only when the recogniser is no longer in use, so that its owner can close it then.

`VoiceEvent.Heard` prints only the length of the transcript, so that a log line or an exception message can never contain what the owner said (section 13.5).

- [ ] **Step 1: Write the failing test**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`:

```kotlin
package dev.kestrel.voice.listen

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordHit
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Transcript
import dev.kestrel.voice.Utterance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningPipelineTest {
    private val detector = FakeDetector()
    private val spotter = FakeSpotter()
    private val recognizer = FakeRecognizer()
    private var ownVoice = emptySet<Keyword>()
    private val events = mutableListOf<VoiceEvent>()
    private var fed = 0L

    @Test
    fun transcribesEachUtteranceWithTheBoostWords() = runTest {
        val pipeline = pipeline(boostWords = listOf("Viber"))

        detector.next.addLast(listOf(utterance(0f, 1f)))
        pipeline.feed()

        val heard = events.single() as VoiceEvent.Heard
        assertEquals("heard 16000", heard.text)
        assertFalse(heard.named)
        assertEquals(1f, heard.seconds)
        assertEquals(listOf(listOf("Viber")), recognizer.boostWords)
    }

    @Test
    fun pausedTranscribesOnlyUtterancesThatStartWithTheName() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NAMED

        pipeline.feedUntil(1.4f)
        detector.next.addLast(listOf(utterance(0.2f, 0.5f)))
        pipeline.feed()
        pipeline.feedUntil(2f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2f)))
        pipeline.feed()
        pipeline.feedUntil(3.3f)
        detector.next.addLast(listOf(utterance(1.6f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(4.5f)

        assertEquals(listOf(VoiceEvent.Spotted(Keyword.KESTREL)), events.filterIsInstance<VoiceEvent.Spotted>())
        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertTrue(heard.named)
        assertEquals("heard 16000", heard.text)
    }

    @Test
    fun whileKestrelSpeaksOnlyKeywordsCome() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE

        detector.next.addLast(listOf(utterance(0f, 0.2f)))
        spotter.next.addLast(listOf(KeywordHit(Keyword.STOP, 0.02f)))
        pipeline.feed()

        assertEquals(listOf<VoiceEvent>(VoiceEvent.Spotted(Keyword.STOP)), events)
        assertEquals(0, recognizer.calls)
    }

    @Test
    fun kestrelsOwnKeywordsAreIgnored() = runTest {
        val pipeline = pipeline()
        ownVoice = setOf(Keyword.STOP)
        pipeline.recognition = Recognition.NONE

        spotter.next.addLast(listOf(KeywordHit(Keyword.STOP, 0.02f), KeywordHit(Keyword.CANCEL, 0.02f)))
        pipeline.feed()

        assertEquals(listOf<VoiceEvent>(VoiceEvent.Spotted(Keyword.CANCEL)), events)
    }

    @Test
    fun speechThatBeganWhileKestrelSpokeIsCutWhereKestrelStopped() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL

        pipeline.feedUntil(2.2f)
        detector.next.addLast(listOf(utterance(0.5f, 1f)))
        pipeline.feed()
        detector.next.addLast(listOf(utterance(1.6f, 0.8f)))
        pipeline.feed()

        // The first utterance keeps its half second after 1 s, when Kestrel stopped speaking.
        assertEquals(listOf("heard 8000", "heard 12800"), events.filterIsInstance<VoiceEvent.Heard>().map { it.text })
    }

    @Test
    fun kestrelsOwnVoiceBeforeTheResumeIsDropped() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL

        detector.next.addLast(listOf(utterance(0.1f, 0.5f)))
        pipeline.feed()
        pipeline.feedUntil(1.6f)
        // Only 0.1 s of this one comes after 1 s.
        detector.next.addLast(listOf(utterance(0.3f, 0.8f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
    }

    @Test
    fun aNameHeardBeforeKestrelSpokeDoesNotKeepKestrelsOwnVoice() = runTest {
        val pipeline = pipeline()
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 0.02f)))
        pipeline.feed()
        // Kestrel answers "Yes?" until 1 s, and the microphone hears it from 0.4 s.
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL
        pipeline.feedUntil(1.5f)
        detector.next.addLast(listOf(utterance(0.4f, 0.4f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
    }

    @Test
    fun anUtteranceThatBeganWithKestrelsVoiceIsDroppedAfterAnInterruption() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(2.6f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2.6f)))
        pipeline.feed()
        pipeline.recognition = Recognition.ALL

        // Kestrel's voice from 0.2 s, with the owner's "Kestrel, open the settings" over its end, makes one utterance.
        pipeline.feedUntil(4.7f)
        detector.next.addLast(listOf(utterance(0.2f, 3.8f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
    }

    @Test
    fun anUtteranceThatStartsWithTheInterruptingNameCounts() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(2.6f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2.6f)))
        pipeline.feed()
        pipeline.recognition = Recognition.ALL

        // An echo canceller kept Kestrel's voice out, so the utterance starts with the owner's "Kestrel" at 1.8 s.
        pipeline.feedUntil(4.7f)
        detector.next.addLast(listOf(utterance(1.8f, 2.2f)))
        pipeline.feed()
        pipeline.feedUntil(6f)

        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertTrue(heard.named)
        assertEquals("heard 35200", heard.text)
    }

    @Test
    fun aFailedTranscriptionIsReportedAndTheNextOneWorks() = runTest {
        val pipeline = pipeline()
        recognizer.failNext = true

        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()
        detector.next.addLast(listOf(utterance(0.5f, 0.25f)))
        pipeline.feed()

        assertTrue(events[0] is VoiceEvent.Failed)
        assertEquals("heard 4000", (events[1] as VoiceEvent.Heard).text)
    }

    @Test
    fun anEmptyTranscriptIsPassedOn() = runTest {
        val pipeline = pipeline()
        recognizer.blankNext = true

        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()

        assertEquals("", (events.single() as VoiceEvent.Heard).text)
    }

    @Test
    fun pauseDropsTheUtteranceInProgressAndKeepsThePositions() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NAMED
        pipeline.feedUntil(2f)

        pipeline.pause()
        // After the pause the detector counts from 0 again, so its 0.1 s is 2.1 s since the audio started.
        pipeline.feedUntil(2.4f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 0.4f)))
        pipeline.feed()
        pipeline.feedUntil(3.2f)
        detector.next.addLast(listOf(utterance(0.1f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(5f)

        assertEquals(1, detector.finished)
        assertTrue(events.filterIsInstance<VoiceEvent.Heard>().single().named)
    }

    private fun TestScope.pipeline(boostWords: List<String> = emptyList()): ListeningPipeline {
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val pipeline = ListeningPipeline(detector, spotter, recognizer, { boostWords }, { it in ownVoice }, backgroundScope, unconfined)
        backgroundScope.launch(unconfined) { pipeline.events.collect { events += it } }
        return pipeline
    }

    private fun ListeningPipeline.feed() {
        accept(FloatArray(FRAME))
        fed += FRAME
    }

    private fun ListeningPipeline.feedUntil(seconds: Float) {
        while (fed < at(seconds)) feed()
    }

    private fun at(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()

    private fun utterance(start: Float, seconds: Float) = Utterance(at(start), FloatArray(at(seconds).toInt()))

    private class FakeDetector : SpeechDetector {
        val next = ArrayDeque<List<Utterance>>()
        var finished = 0
        override val hearsSpeech = false

        override fun accept(samples: FloatArray): List<Utterance> = next.removeFirstOrNull() ?: emptyList()

        override fun finish(): List<Utterance> {
            finished++
            return emptyList()
        }

        override fun close() = Unit
    }

    private class FakeSpotter : KeywordSpotter {
        val next = ArrayDeque<List<KeywordHit>>()

        override fun accept(samples: FloatArray): List<KeywordHit> = next.removeFirstOrNull() ?: emptyList()

        override fun close() = Unit
    }

    private class FakeRecognizer : Recognizer {
        var calls = 0
        var failNext = false
        var blankNext = false
        val boostWords = mutableListOf<List<String>>()

        override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
            calls++
            this.boostWords += listOf(boostWords)
            if (failNext) {
                failNext = false
                throw IllegalStateException("The decoder failed")
            }
            if (blankNext) {
                blankNext = false
                return Transcript("", pieces = 0, lastWordEnd = 0f)
            }
            return Transcript("heard ${samples.size}", pieces = 2, lastWordEnd = samples.size / SAMPLE_RATE.toFloat())
        }

        override fun close() = Unit
    }

    private companion object {
        const val FRAME = SAMPLE_RATE / 50
    }
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'ListeningPipeline'".

- [ ] **Step 3: Write the pipeline**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`:

```kotlin
package dev.kestrel.voice.listen

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Which utterances the pipeline transcribes (design spec, sections 8.4 and 8.5). */
enum class Recognition {
    /** Every utterance, while Kestrel listens. */
    ALL,

    /** Only the utterances that start with "Kestrel", while Kestrel is paused. */
    NAMED,

    /** None, while Kestrel speaks. The keyword spotter still listens. */
    NONE,
}

/** What the pipeline heard. */
sealed interface VoiceEvent {
    /**
     * The transcript of an utterance of [seconds] that took [millis] to transcribe; it may be empty. [named] is true when
     * the keyword spotter heard "Kestrel" at its start.
     */
    data class Heard(val text: String, val named: Boolean, val seconds: Float, val millis: Long) : VoiceEvent {
        override fun toString() = "Heard(${text.length} characters, named=$named, seconds=$seconds, millis=$millis)"
    }

    /** The keyword spotter heard [keyword], and it was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : VoiceEvent

    /** Transcribing an utterance failed; the pipeline goes on with the next one. */
    data class Failed(val error: Exception) : VoiceEvent
}

/**
 * Turns the microphone's frames into transcripts and keyword events (design spec, sections 7.5 and 8.1–8.4). The speech
 * detector and the keyword spotter hear every frame; the endpointer decides when an utterance has ended, and the
 * recogniser transcribes the utterances that [recognition] asks for, one at a time in [recognitionContext], with the
 * boost words that [boostWords] returns at that moment. [isOwnVoice] tells which keyword hits came from Kestrel's own
 * voice. The pipeline does not own the engines: whoever opened them closes them, after [close]. Call [accept] and
 * [pause] from one thread only, the microphone's.
 */
class ListeningPipeline(
    private val detector: SpeechDetector,
    private val spotter: KeywordSpotter,
    private val recognizer: Recognizer,
    private val boostWords: suspend () -> List<String>,
    private val isOwnVoice: (Keyword) -> Boolean,
    scope: CoroutineScope,
    recognitionContext: CoroutineContext,
) {
    private val output = Channel<VoiceEvent>(Channel.UNLIMITED)
    private val queue = Channel<Speech>(Channel.UNLIMITED)
    private val endpointer = Endpointer()
    private val worker = scope.launch(recognitionContext) {
        for (speech in queue) transcribe(speech)
    }

    // Only the microphone's thread uses these. Positions count samples since the audio started.
    private var position = 0L
    private var detectorStart = 0L
    private var lastRecognition = Recognition.ALL
    private var resumeFrom = 0L
    private var interrupted = false
    private var interruption: Long? = null

    /** What the pipeline heard, in order. Collect it once. */
    val events: Flow<VoiceEvent> = output.receiveAsFlow()

    /** Which utterances to transcribe from the next frame on. Any thread may set it. */
    @Volatile
    var recognition: Recognition = Recognition.ALL

    /** Feeds one frame of 16 kHz samples. */
    fun accept(frame: FloatArray) {
        val wanted = recognition
        if (lastRecognition == Recognition.NONE && wanted != Recognition.NONE) resume()
        lastRecognition = wanted
        val utterances = detector.accept(frame).map { Utterance(detectorStart + it.startSample, it.samples) }
        val hits = spotter.accept(frame).filterNot { isOwnVoice(it.keyword) }
        position += frame.size
        val names = mutableListOf<Long>()
        for (hit in hits) {
            if (hit.keyword == Keyword.KESTREL) {
                names += position
                // Section 8.4: "Kestrel" while Kestrel speaks interrupts it.
                if (wanted == Recognition.NONE) interruption = position
            }
            output.trySend(VoiceEvent.Spotted(hit.keyword))
        }
        for (speech in endpointer.accept(utterances, names, detector.hearsSpeech, position)) {
            val kept = afterResume(speech) ?: continue
            val transcribe = when (wanted) {
                Recognition.ALL -> true
                Recognition.NAMED -> kept.named
                Recognition.NONE -> false
            }
            if (transcribe) queue.trySend(kept)
        }
    }

    /** Drops the utterance in progress after the microphone stopped, so that it does not run into the next audio. */
    fun pause() {
        detector.finish()
        detectorStart = position
        endpointer.finish()
        interruption = null
    }

    /** Stops transcribing and drops the utterances that wait. Returns when the recogniser is no longer in use. */
    suspend fun close() {
        queue.cancel()
        worker.cancelAndJoin()
        output.close()
    }

    /**
     * Recognition resumes after Kestrel spoke. What the microphone heard until now is Kestrel's own voice. When the owner
     * broke in with "Kestrel", an utterance that starts with the name is the owner's and counts in full; one that began
     * earlier began with Kestrel's voice, which also runs under and after the name, so it is dropped (section 8.4).
     */
    private fun resume() {
        val hit = interruption
        interruption = null
        interrupted = hit != null && position - hit <= INTERRUPTION
        resumeFrom = if (hit != null && interrupted) hit - NAME_LENGTH else position
    }

    /** [speech] without its part before [resumeFrom], or null when too little is left or the owner broke into it. */
    private fun afterResume(speech: Speech): Speech? = when {
        speech.startSample >= resumeFrom -> speech
        interrupted || speech.endSample - resumeFrom < SHORTEST -> null
        else -> Speech(
            resumeFrom,
            speech.samples.copyOfRange((resumeFrom - speech.startSample).toInt(), speech.samples.size),
            speech.named,
        )
    }

    private suspend fun transcribe(speech: Speech) {
        val started = System.nanoTime()
        val event = try {
            val transcript = recognizer.transcribe(speech.samples, boostWords())
            VoiceEvent.Heard(transcript.text, speech.named, speech.seconds, (System.nanoTime() - started) / 1_000_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VoiceEvent.Failed(e)
        }
        output.trySend(event)
    }

    private companion object {
        // A "Kestrel" that interrupts Kestrel's speech counts when recognition resumes at most 3 s later.
        const val INTERRUPTION = 3L * SAMPLE_RATE

        // An utterance that starts with the name that broke in starts at most this long before the hit: "Kestrel" takes
        // about half a second, and the spotter reports it a little after it ends.
        const val NAME_LENGTH = 3L * SAMPLE_RATE / 2

        // A rest shorter than the speech detector's own minimum of 0.25 s is dropped.
        const val SHORTEST = SAMPLE_RATE / 4L
    }
}
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD SUCCESSFUL`, with 12 tests in `ListeningPipelineTest`.

- [ ] **Step 5: Write the device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningTest.kt`:

```kotlin
package dev.kestrel.app.voice

import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.listen.ListeningPipeline
import dev.kestrel.voice.listen.VoiceEvent
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The pipeline with the real engines, on synthetic speech in Kestrel's voice (design spec, sections 8.1–8.3). */
class ListeningTest {
    @Before
    fun models() = VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)

    @Test
    fun aTaskWithTheNameIsNamed() {
        val heard = listen(VoiceTests.speech("Kestrel, open the settings.") + VoiceTests.silence(1.5f))

        assertTrue(heard.named)
        assertTrue(heard.text.lowercase().contains("open the settings"))
    }

    @Test
    fun aPauseAfterTheNameKeepsOneUtterance() {
        val audio = VoiceTests.silence(0.5f) + VoiceTests.voice("Kestrel") + VoiceTests.silence(1f) +
            VoiceTests.voice("open the settings") + VoiceTests.silence(2.5f)

        val heard = listen(audio)

        assertTrue(heard.named)
        assertTrue(heard.text.lowercase().contains("open the settings"))
    }

    @Test
    fun speechWithoutTheNameIsNotNamed() {
        val heard = listen(VoiceTests.speech("Stop listening.") + VoiceTests.silence(1f))

        assertFalse(heard.named)
        assertTrue(heard.text.lowercase().contains("stop listening"))
    }

    /** Feeds [audio] to a pipeline in 20 ms frames, as the microphone would, and returns the first transcript. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun listen(audio: FloatArray): VoiceEvent.Heard = runBlocking {
        val detector = VoiceTests.graph.voice.speechDetector()
        val spotter = VoiceTests.graph.voice.keywordSpotter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pipeline = ListeningPipeline(
                detector, spotter, recognizer(), { listOf("Kestrel") }, { false }, scope, Dispatchers.Default.limitedParallelism(1),
            )
            VoiceTests.frames(audio) { frame ->
                pipeline.accept(frame)
                emptyList<Unit>()
            }
            val event = withTimeout(30_000) { pipeline.events.first { it is VoiceEvent.Heard || it is VoiceEvent.Failed } }
            pipeline.close()
            if (event is VoiceEvent.Failed) throw AssertionError("Transcribing failed", event.error)
            val heard = event as VoiceEvent.Heard
            VoiceTests.log("Pipeline: ${heard.seconds} s of speech transcribed in ${heard.millis} ms")
            heard
        } finally {
            scope.cancel()
            detector.close()
            spotter.close()
        }
    }

    companion object {
        // Loading Parakeet takes seconds, so the tests share one recogniser.
        private var shared: Recognizer? = null

        private fun recognizer(): Recognizer = shared ?: runBlocking { VoiceTests.graph.voice.recognizer() }.also { shared = it }

        @AfterClass
        @JvmStatic
        fun closeRecognizer() {
            shared?.close()
            shared = null
        }
    }
}
```

- [ ] **Step 6: Run the device tests**

Run: `scripts\voice-test.ps1 -Serial <serial>`
Expected: every test passes and none is skipped, including the 3 tests of `ListeningTest`, and the printed log has three "Pipeline:" lines. If `aPauseAfterTheNameKeepsOneUtterance` gets "Kestrel." on its own, the endpointer let the name go before the second part began; if it gets "open the settings" without the name, the keyword spotter missed "Kestrel" in the synthetic voice, and `KeywordSpotterTest` of plan 2A shows whether the spotter hears it at all.

- [ ] **Step 7: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningTest.kt
git commit -m "feat(voice): the listening pipeline from microphone frames to transcripts and keywords" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Voice output

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoicePlayerTest.kt`

**Interfaces:**
- Consumes: `Synthesizer` (`sampleRate`, `synthesize(text): FloatArray`), `VoiceOptions(speed, variation)` and `VoiceEngines.synthesizer(options)` (plan 2A); `Sentences` and `Spoken` (Task 2); `VoiceTests` (plan 2A).
- Produces: in package `dev.kestrel.voice.speak`: `enum class Spoke { FINISHED, STOPPED, REFUSED }`; `class VoicePlayer(context: Context, synthesizer: Synthesizer)` with `fun speak(text: String): Deferred<Spoke>`, `fun stop()`, `suspend fun close()` and `fun recent(now: Long = SystemClock.elapsedRealtime()): List<Spoken>`. Plan 2C speaks the Orchestrator's replies with it, sends `SpeechEnded` when each `Deferred` completes in any way, and gives `recent()` to the echo filter.

The player speaks a reply sentence by sentence and synthesizes the next sentence while one plays. Its audio has the usage "assistant" and the content type "speech", and it asks for transient audio focus that lets other apps lower their volume. Android 17 refuses audio focus to an app in the background without a suitable foreground service (B5); the player then speaks nothing and returns `REFUSED`.

One reply plays at a time, in the order of the `speak` calls. `speak` notes the moment of the call, so `stop()` ends the reply that plays and every reply that was asked for before the stop, also one that has not begun: `stop()` counts up a generation, and a reply from an older generation ends at once. A stopped reply ends without waiting for the sentence that is being synthesized; the next reply waits for that sentence instead, because the synthesizer serves one sentence at a time. The player writes in chunks of 0.1 s, so a stop or a cancellation takes effect quickly. After the last sentence, it writes a buffer of silence, because a stream track starts only when its buffer is full and holds back its last frames until more come, and it returns when the reply has played; it never calls `AudioTrack.stop()`, which would reset the playback position to 0 at once. If synthesis fails, the reply ends with that error. `close()` returns when the synthesizer is no longer in use, so that its owner can close it then.

`recent()` keeps every played sentence with its playing time for 5 seconds, which covers the echo filter's 1.5 s. The device test opens `MainActivity` first, because Android 17 lets an app play audio only while it is visible or runs a suitable foreground service, and uses the voice without variation, so that the timing is the same in every run.

- [ ] **Step 1: Write the failing device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoicePlayerTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Kestrel's voice output on the emulator's speaker (design spec, section 8.4). */
class VoicePlayerTest {
    @Before
    fun models() = VoiceTests.assumeModels(Engine.TTS)

    @Test
    fun speaksTheWholeReplySentenceBySentenceAsTheAssistant() = withPlayer { player ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        val usages = mutableSetOf<Int>()

        val spoke = runBlocking {
            val reply = player.speak("Hello. This is Kestrel.")
            while (!reply.isCompleted) {
                audio.activePlaybackConfigurations.mapTo(usages) { it.audioAttributes.usage }
                delay(20)
            }
            reply.await()
        }
        val returned = SystemClock.elapsedRealtime()

        assertEquals(Spoke.FINISHED, spoke)
        assertTrue("Usages while speaking: $usages", AudioAttributes.USAGE_ASSISTANT in usages)
        val spoken = player.recent()
        assertEquals(listOf("Hello.", "This is Kestrel."), spoken.map { it.text })
        assertTrue(spoken[0].endMillis > spoken[0].startMillis)
        assertTrue(spoken[1].startMillis >= spoken[0].endMillis - 50)
        val late = returned - spoken.last().endMillis
        VoiceTests.log("Voice output: finished $late ms after the estimated end")
        assertTrue("speak() returned $late ms after the reply ended", late < 700)
    }

    @Test
    fun stopEndsTheReplyAtOnce() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("This is a long reply. It has several sentences. Each of them takes a while. Kestrel stops it at once.")
            delay(1_500)
            val stoppedAt = SystemClock.elapsedRealtime()
            player.stop()

            assertEquals(Spoke.STOPPED, reply.await())
            val millis = SystemClock.elapsedRealtime() - stoppedAt
            VoiceTests.log("Voice output: stopped in $millis ms")
            assertTrue("Stopping took $millis ms", millis < 500)
        }
    }

    @Test
    fun stopDuringTheSynthesisOfALongSentenceEndsTheReplyAtOnce() = withPlayer { player ->
        runBlocking {
            // One sentence of 184 characters, under the splitter's limit, which takes a while to synthesize.
            val sentence = "This sentence is long on purpose, so that Kestrel's voice needs a while to synthesize it, " +
                "and the player must not wait for that when the owner says stop before the first word is spoken"
            val reply = player.speak(sentence)
            delay(100)
            val stoppedAt = SystemClock.elapsedRealtime()
            player.stop()

            assertEquals(Spoke.STOPPED, reply.await())
            val millis = SystemClock.elapsedRealtime() - stoppedAt
            assertTrue("Stopping took $millis ms", millis < 300)
            // The next reply waits until the synthesizer is free, and then plays.
            assertEquals(Spoke.FINISHED, player.speak("Done.").await())
        }
    }

    @Test
    fun stopRightAfterSpeakEndsTheReplyBeforeItBegins() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("Hello.")
            player.stop()

            assertEquals(Spoke.STOPPED, reply.await())
        }
    }

    @Test
    fun stopAlsoEndsAReplyThatWaitsForItsTurn() = withPlayer { player ->
        runBlocking {
            val first = player.speak("The first reply is a little longer than a moment.")
            delay(500)
            val second = player.speak("The second reply waits.")
            delay(200)
            player.stop()

            assertEquals(Spoke.STOPPED, first.await())
            assertEquals(Spoke.STOPPED, second.await())
        }
    }

    @Test
    fun aCancelledReplyDoesNotHoldUpTheNextOne() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("The first sentence plays. The second one waits. The third one waits too.")
            delay(500)
            reply.cancel()

            assertEquals(Spoke.FINISHED, withTimeout(10_000) { player.speak("Done.").await() })
        }
    }

    private fun withPlayer(test: (VoicePlayer) -> Unit) {
        // Android 17 lets an app play audio only while it is visible or runs a suitable foreground service (B5).
        ActivityScenario.launch(MainActivity::class.java).use {
            // Without variation, the voice and its timing are the same in every run.
            val synthesizer = runBlocking { VoiceTests.graph.voice.synthesizer(VoiceOptions(variation = 0f)) }
            val player = VoicePlayer(InstrumentationRegistry.getInstrumentation().targetContext, synthesizer)
            try {
                test(player)
            } finally {
                runBlocking { player.close() }
                synthesizer.close()
            }
        }
    }
}
```

- [ ] **Step 2: Make sure the test does not compile yet**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with "Unresolved reference 'VoicePlayer'" and "Unresolved reference 'Spoke'".

- [ ] **Step 3: Write the player**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt`:

```kotlin
package dev.kestrel.voice.speak

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import dev.kestrel.voice.Synthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger

/** How a reply ended. */
enum class Spoke {
    /** Kestrel spoke the whole reply. */
    FINISHED,

    /** [VoicePlayer.stop] ended it early, or before it began. */
    STOPPED,

    /** Android refused audio focus, so Kestrel spoke nothing (B5). */
    REFUSED,
}

/**
 * Speaks replies in Kestrel's voice (design spec, section 8.4). It speaks sentence by sentence and synthesizes the next
 * sentence while one plays, so that it starts soon and [stop] takes effect at once. The audio has the usage "assistant",
 * and other apps lower their volume while Kestrel speaks. Android 17 refuses audio focus to an app in the background
 * without a suitable foreground service (B5); then the player speaks nothing and returns [Spoke.REFUSED]. One reply
 * plays at a time, in the order of the [speak] calls.
 */
class VoicePlayer(context: Context, private val synthesizer: Synthesizer) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val turn = Mutex()
    private val generation = AtomicInteger()
    private val history = ArrayDeque<Spoken>()
    private val lock = Any()
    private var track: AudioTrack? = null
    private var clips: Channel<Clip>? = null

    // The synthesis of the reply that played last. Only the reply that holds the turn reads or sets it.
    private var synthesis: Job? = null

    /** The sentences that Kestrel played in the last few seconds, for the echo filter. Any thread may call it. */
    fun recent(now: Long = SystemClock.elapsedRealtime()): List<Spoken> = synchronized(history) {
        while (history.isNotEmpty() && history.first().endMillis < now - KEEP_MILLIS) history.removeFirst()
        history.toList()
    }

    /**
     * Speaks [text] after the replies that came before it, and completes when it has been spoken, stopped or refused, or
     * with the error when synthesis failed. A [stop] after this call ends the reply, also when it has not begun yet.
     */
    fun speak(text: String): Deferred<Spoke> {
        val mine = generation.get()
        // Undispatched, the caller's thread takes the turn or joins the queue for it, so replies keep the order of the calls.
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            turn.withLock {
                yield()
                if (generation.get() != mine) return@withLock Spoke.STOPPED
                // The synthesizer serves one sentence at a time, and a stopped reply's last sentence may still be in it.
                synthesis?.join()
                val sentences = Sentences.split(text)
                when {
                    generation.get() != mine -> Spoke.STOPPED
                    sentences.isEmpty() -> Spoke.FINISHED
                    else -> withFocus { withTrack { output -> play(output, sentences, mine) } }
                }
            }
        }
    }

    /** Stops the reply that plays and the replies that were asked for before. Any thread may call it. */
    fun stop() {
        generation.incrementAndGet()
        synchronized(lock) {
            clips?.close()
            track?.run {
                pause()
                flush()
            }
        }
    }

    /** Stops speaking, and returns when the synthesizer is no longer in use, so that its owner can close it. */
    suspend fun close() {
        stop()
        scope.coroutineContext.job.cancelAndJoin()
    }

    private class Clip(val text: String, val samples: FloatArray)

    private inline fun withFocus(speak: () -> Spoke): Spoke {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return Spoke.REFUSED
        try {
            return speak()
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private suspend fun withTrack(speak: suspend (AudioTrack) -> Spoke): Spoke {
        val rate = synthesizer.sampleRate
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val output = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, rate / 5 * Float.SIZE_BYTES))
            .build()
        synchronized(lock) { track = output }
        try {
            return speak(output)
        } finally {
            synchronized(lock) { track = null }
            output.pause()
            output.flush()
            output.release()
        }
    }

    private suspend fun play(output: AudioTrack, sentences: List<String>, mine: Int): Spoke {
        val rate = synthesizer.sampleRate
        // One sentence is synthesized ahead of the one that plays. stop() closes the channel, so that the reply ends
        // without waiting for the sentence in synthesis.
        val ready = Channel<Clip>(capacity = 1)
        synchronized(lock) { clips = ready }
        if (generation.get() != mine) ready.close()
        synthesis = scope.launch {
            var failure: Exception? = null
            try {
                for (sentence in sentences) {
                    if (generation.get() != mine) break
                    ready.send(Clip(sentence, synthesizer.synthesize(sentence)))
                }
            } catch (e: ClosedSendChannelException) {
                // stop() ended the reply.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            } finally {
                ready.close(failure)
            }
        }
        try {
            output.play()
            var written = 0L
            for (clip in ready) {
                if (generation.get() != mine) break
                val start = SystemClock.elapsedRealtime() + (written - output.playbackHeadPosition) * 1_000 / rate
                synchronized(history) { history.addLast(Spoken(clip.text, start, start + clip.samples.size * 1_000L / rate)) }
                written += write(output, clip.samples, mine)
            }
            if (generation.get() == mine) {
                // A stream track starts only when its buffer is full, and holds back its last frames until more come, so a
                // buffer of silence follows the reply. AudioTrack.stop() would reset playbackHeadPosition to 0 at once.
                write(output, FloatArray(output.bufferSizeInFrames), mine)
                val deadline = SystemClock.elapsedRealtime() + (written - output.playbackHeadPosition) * 1_000 / rate + DRAIN_MARGIN_MILLIS
                while (generation.get() == mine && output.playbackHeadPosition < written && SystemClock.elapsedRealtime() < deadline) {
                    delay(20)
                }
            }
        } finally {
            synchronized(lock) { clips = null }
            // A synthesis job that waits to hand over a sentence must not wait for ever when the reply ends early.
            ready.cancel()
        }
        return if (generation.get() == mine) Spoke.FINISHED else Spoke.STOPPED
    }

    /** Writes [samples] in chunks of 0.1 s, so that a stop or a cancellation takes effect quickly, and returns how many it wrote. */
    private suspend fun write(output: AudioTrack, samples: FloatArray, mine: Int): Int {
        val chunk = synthesizer.sampleRate / 10
        var offset = 0
        while (offset < samples.size && generation.get() == mine) {
            currentCoroutineContext().ensureActive()
            val count = output.write(samples, offset, minOf(chunk, samples.size - offset), AudioTrack.WRITE_BLOCKING)
            if (count <= 0) break
            offset += count
        }
        return offset
    }

    private companion object {
        const val KEEP_MILLIS = 5_000L
        const val DRAIN_MARGIN_MILLIS = 2_000L
    }
}
```

- [ ] **Step 4: Run the device tests**

Run: `scripts\voice-test.ps1 -Serial <serial>`
Expected: every test passes and none is skipped, including the 6 tests of `VoicePlayerTest`, and the log has a "Voice output: finished" line with less than 700 ms and a "Voice output: stopped in" line with less than 500 ms.

- [ ] **Step 5: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoicePlayerTest.kt
git commit -m "feat(voice): speak replies sentence by sentence and stop them at once" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The microphone

**Files:**
- Create: `core/voice/src/main/AndroidManifest.xml`
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/MicCaptureTest.kt`

**Interfaces:**
- Consumes: `SAMPLE_RATE` (plan 2A); `VoiceTests.log` (plan 2A).
- Produces: in package `dev.kestrel.voice.listen`: `class MicCapture(context: Context)` with `fun start(onFrame: (FloatArray) -> Unit, onError: (Int) -> Unit)`, `fun stop()`, `val silenced: StateFlow<Boolean>` and `val echoCancelling: Boolean`. Plan 2C's `ListeningService` starts it when the Orchestrator's state asks for the microphone, feeds `onFrame` to `ListeningPipeline.accept`, and turns `silenced` into the event `MicBusy`.

The microphone records with the source `VOICE_RECOGNITION`, at 16 kHz in mono with 16-bit samples, and delivers whole 20 ms frames of floats between -1 and 1 on its own thread at audio priority. It is never privacy-sensitive, so that other apps that listen, such as Voice Access or a call, keep their audio (B2). It attaches Android's echo canceller when the device has one, and it registers an `AudioRecordingCallback`, whose report that Android silences Kestrel's recording becomes `silenced`. `start` checks the microphone permission itself and throws `SecurityException` without it; when the microphone does not start, it releases everything and throws `IllegalStateException`, so that a later `start` can try again. `stop` waits until the reading thread has finished, so it must not be called from `onFrame` or `onError`.

Lint's `MissingPermission` check looks for the permission in the merged manifest of the module that it checks, so `:core:voice` declares `RECORD_AUDIO` in a manifest of its own. The app declares it already, so the app's merged manifest does not change.

The device test grants the permission the way `SetupStatusReaderTest` does and never revokes it, because revoking a permission ends the app's process and the tests with it. It opens `MainActivity` first, because Android lets an app record only while it is visible or runs a foreground service of the type microphone. The emulator's microphone delivers silence, so the test checks the frames' rate and size, the source and that Android does not silence the recording, not the sound.

- [ ] **Step 1: Write the failing device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/MicCaptureTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.Manifest
import android.app.UiAutomation
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.voice.listen.MicCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Kestrel's microphone on the emulator (design spec, section 8.1). */
class MicCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun recordsTwentyMillisecondFramesFromTheVoiceRecognitionSource() {
        // Revoking a permission would stop the app's process, and the tests with it, so this test only grants it.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val frames = CopyOnWriteArrayList<FloatArray>()
        val errors = CopyOnWriteArrayList<Int>()

        // Android lets an app record only while it is visible or runs a foreground service of the type microphone.
        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(onFrame = { frames += it }, onError = { errors += it })
            try {
                Thread.sleep(1_000)
                // Another app may record too, such as a hotword service, so this looks only at the source Kestrel uses.
                val recordings = context.getSystemService(AudioManager::class.java).activeRecordingConfigurations
                    .filter { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION }
                assertTrue("No recording from the voice recognition source", recordings.isNotEmpty())
                assertFalse(recordings.any { it.isClientSilenced })
                assertFalse(capture.silenced.value)
                VoiceTests.log("Microphone: ${frames.size} frames in 1 s, echo canceller ${capture.echoCancelling}")
            } finally {
                capture.stop()
            }
        }

        assertEquals(emptyList<Int>(), errors)
        assertTrue("${frames.size} frames in 1 s", frames.size in 30..70)
        assertTrue(frames.all { it.size == 320 })
    }

    @Test
    fun theMicrophoneStartsAgainAfterAStop() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val frames = CopyOnWriteArrayList<FloatArray>()

        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(onFrame = {}, onError = {})
            capture.stop()
            capture.start(onFrame = { frames += it }, onError = {})
            try {
                Thread.sleep(300)
            } finally {
                capture.stop()
            }
        }

        assertTrue("${frames.size} frames in 0.3 s", frames.isNotEmpty())
    }
}
```

- [ ] **Step 2: Make sure the test does not compile yet**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with "Unresolved reference 'MicCapture'".

- [ ] **Step 3: Write the microphone**

Create `core/voice/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- MicCapture records the microphone (design spec, section 8.1). The app declares the permission too. -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
</manifest>
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`:

```kotlin
package dev.kestrel.voice.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.kestrel.voice.SAMPLE_RATE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records the microphone for listening (design spec, section 8.1): source VOICE_RECOGNITION, 16 kHz, mono, 16-bit, in
 * 20 ms frames, and never privacy-sensitive, so that other apps that listen, such as Voice Access or a call, keep their
 * audio (B2). It attaches Android's echo canceller where the device has one. [silenced] is true while Android gives
 * Kestrel silence because another app has priority on the microphone.
 */
class MicCapture(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val silencedState = MutableStateFlow(false)
    private var canceller: AcousticEchoCanceler? = null
    private var reader: Thread? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    /** True while Android silences Kestrel's recording: "mic busy" on the feedback bar (section 8.1). */
    val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    /** Whether Android's echo canceller works on the current recording. */
    val echoCancelling: Boolean get() = canceller?.enabled == true

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            silencedState.value = isSilenced(configs)
        }
    }

    /**
     * Starts recording. [onFrame] gets each 20 ms frame, and [onError] gets the error code when reading fails; both run
     * on the microphone's own thread. Throws [SecurityException] without the microphone permission and
     * [IllegalStateException] when the microphone does not start.
     */
    fun start(onFrame: (FloatArray) -> Unit, onError: (Int) -> Unit) {
        check(record == null) { "The microphone is already recording" }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Kestrel may not record audio")
        }
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, BUFFER_BYTES))
                .setPrivacySensitive(false)
                .build()
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("The microphone could not be opened", e)
        }
        var effect: AcousticEchoCanceler? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
            if (AcousticEchoCanceler.isAvailable()) {
                effect = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.setEnabled(true) }
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone did not start" }
        } catch (e: Exception) {
            effect?.release()
            recorder.release()
            throw e
        }
        canceller = effect
        record = recorder
        audio.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        // The callback reports only changes, so the state at the start comes from the current recordings.
        silencedState.value = isSilenced(audio.activeRecordingConfigurations)
        running = true
        reader = Thread({ read(recorder, onFrame, onError) }, "Kestrel microphone").apply { start() }
    }

    /** Stops recording, and waits until the microphone's thread has finished. Never call it from [start]'s callbacks. */
    fun stop() {
        val recorder = record ?: return
        running = false
        recorder.stop()
        reader?.join()
        reader = null
        audio.unregisterAudioRecordingCallback(callback)
        canceller?.release()
        canceller = null
        record = null
        recorder.release()
        silencedState.value = false
    }

    private fun isSilenced(configs: List<AudioRecordingConfiguration>): Boolean {
        val session = record?.audioSessionId ?: return false
        return configs.any { it.clientAudioSessionId == session && it.isClientSilenced }
    }

    private fun read(recorder: AudioRecord, onFrame: (FloatArray) -> Unit, onError: (Int) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(FRAME)
        while (running) {
            val count = recorder.read(buffer, 0, FRAME, AudioRecord.READ_BLOCKING)
            // A blocking read returns fewer samples only when stop() interrupts it, and that rest is dropped.
            if (count == FRAME) {
                onFrame(FloatArray(count) { buffer[it] / 32_768f })
            } else if (count < 0 && running) {
                onError(count)
                return
            }
        }
    }

    private companion object {
        const val FRAME = SAMPLE_RATE / 50

        // 200 ms of 16-bit samples, so that a short stall of the microphone's thread loses nothing.
        const val BUFFER_BYTES = SAMPLE_RATE / 5 * 2
    }
}
```

- [ ] **Step 4: Run lint on the module**

Run: `scripts\gradle.ps1 :core:voice:lintDebug`
Expected: `BUILD SUCCESSFUL`, with no `MissingPermission` error for `MicCapture.kt`.

- [ ] **Step 5: Run the device tests**

Run: `scripts\voice-test.ps1 -Serial <serial>`
Expected: every test passes and none is skipped, including the 2 tests of `MicCaptureTest`, and the log has a "Microphone:" line with about 50 frames. Write in your report whether the echo canceller was on; on the emulator it is usually not available.

- [ ] **Step 6: Commit**

```powershell
git add core/voice/src/main/AndroidManifest.xml core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/MicCaptureTest.kt
git commit -m "feat(voice): record the microphone in 20 ms frames without a privacy-sensitive source" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The Orchestrator's listening rules

**Files:**
- Modify: `app/build.gradle.kts` (kotlinx-coroutines-test for the JVM tests)
- Create: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`

**Interfaces:**
- Consumes: `Request` and `Requests.read` (Task 3); `Recognition` (Task 4); `Keyword` (plan 2A).
- Produces: in package `dev.kestrel.app.orchestrator`: `enum class Mode { OFF, LISTENING, PAUSED }`; `enum class Work { IDLE, AWAITING_TASK, RUNNING_TASK }`; `data class State(mode, work, speeches, task, screenOn, micBusy)` with `speaking`, `microphone` and `recognition`; `sealed interface Event` (`Toggle`, `Pause`, `Resume`, `TurnOff`, `Heard(text, named)`, `Spotted(keyword)`, `SpeechEnded`, `TaskEnded(task, reply)`, `MicBusy(busy)`, `Screen(on)`, `WaitEnded`); `sealed interface Reply` (`Yes`, `Text(text)`); `sealed interface Feedback` (`ModeChanged(mode)`, `Heard(text)`, `MicBusy`); `sealed interface Effect` (`Say(reply)`, `StopSaying`, `RunTask(task, text)`, `CancelTask`, `RunCommand(text)`, `Show(feedback)`, `WaitForTask`, `StopService`); `data class Step(state, effects)`; `object Rules` with `fun next(state: State, event: Event): Step`; `class Orchestrator(scope: CoroutineScope, perform: (Effect) -> Unit, onError: (Exception) -> Unit, waitMillis: Long = 8_000)` with `val state: StateFlow<State>` and `fun send(event: Event)`. Plan 2C's `ListeningService` sends the events, performs the effects, sends `SpeechEnded` once for each reply however it ends, logs what `onError` gets, and makes the microphone and the pipeline follow `state.microphone` and `state.recognition`.

The Orchestrator tracks the listening mode, what Kestrel is doing (`Work`, the spec's "current activity") and whether it is speaking (section 7.3). Every transition is in `Rules.next`, a pure function, so that the JVM tests cover each one. The microphone and recognition are not effects: they follow from the state (`microphone` and `recognition`), so they cannot drift from it.

The rules:
- **Modes (section 8.5):** the toggle (accessibility button or shortcut, or the tile) starts listening when Kestrel is off or paused, and pauses it while it listens. The notification pauses, resumes and turns off. "Stop listening" pauses, "Kestrel, wake up" resumes, and "Stop Kestrel" turns off. Turning off stops the speech and the task, and stops the service.
- **The name (section 8.3):** "Kestrel, [task]" runs the task, in either mode; the mode stays, so a task that starts while Kestrel is paused returns to the paused mode. "Kestrel" on its own answers "Yes?", and the next utterance is the task; after 8 seconds without one, Kestrel stops waiting.
- **Recognition:** none while Kestrel speaks; all utterances while it listens, while it waits for a task and while a task runs; otherwise only the ones that start with "Kestrel".
- **While a task runs (section 11.10):** only "stop" and "cancel" count, plus "stop listening" and "stop Kestrel", which also end the task. Everything else is ignored. Each task has a number, so that the end of an older task changes nothing.
- **While Kestrel speaks (section 8.4):** the keyword spotter's "stop" or "cancel" ends the speech and cancels a running task; "Kestrel" ends the speech and waits for a request, unless a task runs.
- **Instant commands:** other speech goes to the instant commands of sub-project 3 (`RunCommand`) only while Kestrel listens. Plain "stop" and "cancel" go there too when nothing runs, because they may name a button.
- **Screen (section 8.5):** while the screen is off or the phone is locked, the microphone is off and the mode stays; Kestrel stops waiting for a task.
- **Feedback bar (section 8.9):** the bar shows each mode change, each transcript that Kestrel acts on, and "mic busy" when Android starts to silence the recording.
- **Off:** turning off also forgets the replies in progress and "mic busy", so that a reply whose end never came cannot keep recognition off after Kestrel starts again.

The Orchestrator applies the rules to one event at a time. An effect that throws does not stop it: `onError` gets the exception, and the next effects and events still count. The events, effects and replies that carry the owner's words print only the number of characters, so that a log line or an exception message can never contain what the owner said (section 13.5).
- [ ] **Step 1: Add the coroutine test library to the app's JVM tests**

In `app/build.gradle.kts`, add this line right after `testImplementation(libs.junit)`:

```kotlin
    testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val listening = State(mode = Mode.LISTENING)
    private val paused = State(mode = Mode.PAUSED)

    @Test
    fun theToggleStartsListeningThenPausesAndListensAgain() {
        val started = Rules.next(State(), Event.Toggle)

        assertEquals(Mode.LISTENING, started.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING))), started.effects)
        assertEquals(Mode.PAUSED, Rules.next(started.state, Event.Toggle).state.mode)
        assertEquals(Mode.LISTENING, Rules.next(paused, Event.Toggle).state.mode)
    }

    @Test
    fun theNotificationPausesResumesAndTurnsOff() {
        assertEquals(Mode.PAUSED, Rules.next(listening, Event.Pause).state.mode)
        assertEquals(Step(paused), Rules.next(paused, Event.Pause))
        assertEquals(Mode.LISTENING, Rules.next(paused, Event.Resume).state.mode)

        val off = Rules.next(listening, Event.TurnOff)

        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService), off.effects)
    }

    @Test
    fun turningOffStopsTheSpeechAndTheTask() {
        val busy = listening.copy(work = Work.RUNNING_TASK, speeches = 1, task = 3, micBusy = true)

        val off = Rules.next(busy, Event.TurnOff)

        assertEquals(
            listOf(Effect.StopSaying, Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
        assertEquals(0, off.state.speeches)
        assertFalse(off.state.micBusy)
        assertEquals(Recognition.ALL, Rules.next(off.state, Event.Toggle).state.recognition)
    }

    @Test
    fun aTaskWithTheNameRuns() {
        val step = Rules.next(listening, Event.Heard("Kestrel, open the settings.", named = true))

        assertEquals(Work.RUNNING_TASK, step.state.work)
        assertEquals(1, step.state.task)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open the settings.")), Effect.RunTask(1, "open the settings.")),
            step.effects,
        )
        assertEquals(Recognition.ALL, step.state.recognition)
    }

    @Test
    fun aTaskWhilePausedRunsAndKestrelStaysPaused() {
        assertEquals(Recognition.NAMED, paused.recognition)

        val running = Rules.next(paused, Event.Heard("Kestrel, open Viber", named = true)).state
        assertEquals(Mode.PAUSED, running.mode)
        assertEquals(Recognition.ALL, running.recognition)

        val done = Rules.next(running, Event.TaskEnded(task = 1, reply = null))
        assertEquals(Work.IDLE, done.state.work)
        assertEquals(Recognition.NAMED, done.state.recognition)
    }

    @Test
    fun theNameAloneAsksAndTheNextUtteranceIsTheTask() {
        val asked = Rules.next(paused, Event.Heard("Kestrel?", named = true))

        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel?")), Effect.WaitForTask, Effect.Say(Reply.Yes)), asked.effects)
        assertEquals(Work.AWAITING_TASK, asked.state.work)
        assertEquals(Recognition.NONE, asked.state.recognition)

        val ready = Rules.next(asked.state, Event.SpeechEnded).state
        assertEquals(Recognition.ALL, ready.recognition)

        val task = Rules.next(ready, Event.Heard("Open the settings.", named = false))
        assertEquals(Effect.RunTask(1, "Open the settings."), task.effects.last())
        assertEquals(Work.RUNNING_TASK, task.state.work)
    }

    @Test
    fun theWaitForATaskEnds() {
        val waiting = listening.copy(work = Work.AWAITING_TASK)
        assertEquals(Work.IDLE, Rules.next(waiting, Event.WaitEnded).state.work)

        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)
        assertEquals(Step(running), Rules.next(running, Event.WaitEnded))
    }

    @Test
    fun whileATaskRunsOnlyStopAndCancelCount() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        assertEquals(Step(running), Rules.next(running, Event.Heard("Open Viber", named = false)))
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel, open Viber", named = true)))

        val stopped = Rules.next(running, Event.Heard("Stop.", named = false))
        assertEquals(Work.IDLE, stopped.state.work)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.CancelTask), stopped.effects)
    }

    @Test
    fun stopListeningWhileATaskRunsEndsTheTaskAndPauses() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        val step = Rules.next(running, Event.Heard("Stop listening.", named = false))

        assertEquals(Mode.PAUSED, step.state.mode)
        assertEquals(Work.IDLE, step.state.work)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening.")), Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.PAUSED))),
            step.effects,
        )
    }

    @Test
    fun wakeUpResumesListeningFromThePause() {
        val step = Rules.next(paused, Event.Heard("Kestrel, wake up.", named = true))

        assertEquals(Mode.LISTENING, step.state.mode)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, wake up.")), Effect.Show(Feedback.ModeChanged(Mode.LISTENING))),
            step.effects,
        )
    }

    @Test
    fun stopListeningPausesAndStopKestrelTurnsOff() {
        assertEquals(Mode.PAUSED, Rules.next(listening, Event.Heard("Stop listening.", named = false)).state.mode)

        val off = Rules.next(listening, Event.Heard("Stop Kestrel.", named = false))

        assertEquals(Mode.OFF, off.state.mode)
        assertTrue(Effect.StopService in off.effects)
    }

    @Test
    fun otherSpeechGoesToTheCommandsOnlyWhileListening() {
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.RunCommand("Scroll down")),
            Rules.next(listening, Event.Heard("Scroll down", named = false)).effects,
        )
        assertEquals(Step(paused), Rules.next(paused, Event.Heard("Scroll down", named = true)))
        assertEquals(Effect.RunCommand("Cancel!"), Rules.next(listening, Event.Heard("Cancel!", named = false)).effects.last())
    }

    @Test
    fun stopWhileKestrelSpeaksEndsTheSpeechAndCancelsTheTask() {
        val speaking = listening.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)

        val step = Rules.next(speaking, Event.Spotted(Keyword.STOP))

        assertEquals(listOf(Effect.StopSaying, Effect.CancelTask), step.effects)
        assertEquals(Work.IDLE, step.state.work)
    }

    @Test
    fun theNameWhileKestrelSpeaksEndsTheSpeechAndWaitsForARequest() {
        val step = Rules.next(listening.copy(speeches = 1), Event.Spotted(Keyword.KESTREL))

        assertEquals(listOf(Effect.StopSaying, Effect.WaitForTask), step.effects)
        assertEquals(Work.AWAITING_TASK, step.state.work)
    }

    @Test
    fun keywordsCountOnlyWhileKestrelSpeaks() {
        assertEquals(Step(listening), Rules.next(listening, Event.Spotted(Keyword.STOP)))
    }

    @Test
    fun aTaskReplyIsSpokenAndTheEndOfAnOlderTaskChangesNothing() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 2)

        val done = Rules.next(running, Event.TaskEnded(task = 2, reply = "Done."))

        assertEquals(listOf(Effect.Say(Reply.Text("Done."))), done.effects)
        assertEquals(1, done.state.speeches)
        assertEquals(Step(running), Rules.next(running, Event.TaskEnded(task = 1, reply = "Late.")))
        assertEquals(Step(done.state), Rules.next(done.state, Event.TaskEnded(task = 2, reply = "Again.")))
    }

    @Test
    fun theMicrophoneFollowsTheModeAndTheScreen() {
        assertFalse(State().microphone)
        assertTrue(listening.microphone)

        val dark = Rules.next(listening.copy(work = Work.AWAITING_TASK), Event.Screen(on = false)).state

        assertFalse(dark.microphone)
        assertEquals(Mode.LISTENING, dark.mode)
        assertEquals(Work.IDLE, dark.work)
        assertTrue(Rules.next(dark, Event.Screen(on = true)).state.microphone)
    }

    @Test
    fun aBusyMicrophoneIsShownOnce() {
        val busy = Rules.next(listening, Event.MicBusy(busy = true))

        assertEquals(listOf(Effect.Show(Feedback.MicBusy)), busy.effects)
        assertEquals(Step(busy.state), Rules.next(busy.state, Event.MicBusy(busy = true)))
        assertFalse(Rules.next(busy.state, Event.MicBusy(busy = false)).state.micBusy)
    }

    @Test
    fun transcriptsAreIgnoredWhileOff() {
        assertEquals(Step(State()), Rules.next(State(), Event.Heard("Kestrel, open the settings.", named = true)))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {
    private val performed = mutableListOf<Effect>()
    private val errors = mutableListOf<Exception>()

    @Test
    fun appliesTheRulesToEachEventInTurn() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(Work.RUNNING_TASK, orchestrator.state.value.work)
        assertEquals(
            listOf(
                Effect.Show(Feedback.ModeChanged(Mode.LISTENING)),
                Effect.Show(Feedback.Heard("Kestrel, open the settings.")),
                Effect.RunTask(1, "open the settings."),
            ),
            performed,
        )
        assertEquals(emptyList<Exception>(), errors)
    }

    @Test
    fun theWaitAfterYesEndsAfterEightSeconds() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)
        // The Orchestrator runs the timer itself.
        assertFalse(Effect.WaitForTask in performed)

        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
    }

    @Test
    fun aTaskInTimeEndsTheWait() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        advanceTimeBy(5_000)
        orchestrator.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(Work.RUNNING_TASK, orchestrator.state.value.work)
    }

    @Test
    fun theNameAgainRestartsTheWait() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()

        // The first wait would have ended at 8 s; the second one ends at 13 s.
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
    }

    @Test
    fun anEffectThatFailsIsReportedAndTheNextOnesStillCount() = runTest {
        val listening = Effect.Show(Feedback.ModeChanged(Mode.LISTENING))
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect == listening) throw IllegalStateException("The feedback bar failed")
                performed += effect
            },
            { errors += it },
        )

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(listOf("The feedback bar failed"), errors.map { it.message })
        assertEquals(Effect.RunTask(1, "open the settings."), performed.last())
    }
}
```

- [ ] **Step 3: Run the tests to make sure they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests 'dev.kestrel.app.orchestrator.*'`
Expected: `BUILD FAILED`, with "Unresolved reference 'Rules'" and "Unresolved reference 'Orchestrator'".

- [ ] **Step 4: Write the rules**

Create `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition

/** The listening modes (design spec, section 8.5). Ringing and In call come with calls in plan 2D. */
enum class Mode { OFF, LISTENING, PAUSED }

/** What Kestrel is doing besides listening: the spec's "current activity" (section 7.3). */
enum class Work {
    IDLE,

    /** After "Kestrel" on its own: the next utterance is the task. */
    AWAITING_TASK,

    /** A task runs, and only "stop" and "cancel" count (section 11.10). */
    RUNNING_TASK,
}

/**
 * The Orchestrator's state (design spec, section 7.3). [speeches] counts the replies that Kestrel has started and not
 * finished, and [task] is the number of the latest task, so that the end of an older task changes nothing.
 */
data class State(
    val mode: Mode = Mode.OFF,
    val work: Work = Work.IDLE,
    val speeches: Int = 0,
    val task: Int = 0,
    val screenOn: Boolean = true,
    val micBusy: Boolean = false,
) {
    val speaking: Boolean get() = speeches > 0

    /** Whether Kestrel records: in every mode but off, while the screen is on and the phone unlocked (section 8.5). */
    val microphone: Boolean get() = mode != Mode.OFF && screenOn

    /** Which utterances the pipeline transcribes (sections 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
            work != Work.IDLE || mode == Mode.LISTENING -> Recognition.ALL
            else -> Recognition.NAMED
        }
}

/** What happens to Kestrel (design spec, section 7.3). */
sealed interface Event {
    /** The accessibility button or shortcut, or the Quick Settings tile (section 8.6). */
    data object Toggle : Event

    /** The notification's Pause action. */
    data object Pause : Event

    /** The notification's Resume action. */
    data object Resume : Event

    /** The notification's Stop action. */
    data object TurnOff : Event

    /** A transcript. [named] is true when the keyword spotter heard "Kestrel" at its start. */
    data class Heard(val text: String, val named: Boolean) : Event {
        override fun toString() = "Heard(${text.length} characters, named=$named)"
    }

    /** A keyword from the spotter that was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : Event

    /** A reply has been spoken, stopped or refused, or speaking it failed. Send it once for each reply, or recognition stays off. */
    data object SpeechEnded : Event

    /** Task number [task] ended, with a [reply] for the owner or none. */
    data class TaskEnded(val task: Int, val reply: String?) : Event {
        override fun toString() = "TaskEnded($task, reply=${reply?.let { "${it.length} characters" }})"
    }

    /** Android started or stopped silencing Kestrel's recording. */
    data class MicBusy(val busy: Boolean) : Event

    /** The screen is on and the phone unlocked ([on]), or the screen turned off or the phone locked. */
    data class Screen(val on: Boolean) : Event

    /** Kestrel has waited long enough for a task after "Yes?". */
    data object WaitEnded : Event
}

/** What Kestrel says. */
sealed interface Reply {
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply

    data class Text(val text: String) : Reply {
        override fun toString() = "Text(${text.length} characters)"
    }
}

/** What the feedback bar shows (design spec, section 8.9). */
sealed interface Feedback {
    data class ModeChanged(val mode: Mode) : Feedback

    data class Heard(val text: String) : Feedback {
        override fun toString() = "Heard(${text.length} characters)"
    }

    data object MicBusy : Feedback
}

/** What the Orchestrator asks of the rest of Kestrel. The microphone and recognition follow [State] instead. */
sealed interface Effect {
    data class Say(val reply: Reply) : Effect

    data object StopSaying : Effect

    data class RunTask(val task: Int, val text: String) : Effect {
        override fun toString() = "RunTask($task, ${text.length} characters)"
    }

    data object CancelTask : Effect

    /** Speech for the instant commands of sub-project 3. */
    data class RunCommand(val text: String) : Effect {
        override fun toString() = "RunCommand(${text.length} characters)"
    }

    data class Show(val feedback: Feedback) : Effect

    /** Starts the timer that ends the wait for a task after "Yes?". The Orchestrator runs it itself. */
    data object WaitForTask : Effect

    /** Stops the listening service. */
    data object StopService : Effect
}

/** A transition: the new state, and what to do, in order. */
data class Step(val state: State, val effects: List<Effect> = emptyList())

/** Every transition of the Orchestrator, in one place (design spec, section 7.3). */
object Rules {
    fun next(state: State, event: Event): Step = when (event) {
        Event.Toggle -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        Event.Pause -> if (state.mode == Mode.LISTENING) switch(state, Mode.PAUSED) else Step(state)
        Event.Resume -> if (state.mode == Mode.LISTENING) Step(state) else switch(state, Mode.LISTENING)
        Event.TurnOff -> turnOff(state)
        is Event.Heard -> heard(state, event)
        is Event.Spotted -> spotted(state, event.keyword)
        Event.SpeechEnded -> Step(state.copy(speeches = maxOf(0, state.speeches - 1)))
        is Event.TaskEnded -> taskEnded(state, event)
        is Event.MicBusy -> Step(
            state.copy(micBusy = event.busy),
            if (event.busy && !state.micBusy) listOf(Effect.Show(Feedback.MicBusy)) else emptyList(),
        )
        is Event.Screen -> Step(
            state.copy(screenOn = event.on, work = if (!event.on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work),
        )
        Event.WaitEnded -> Step(if (state.work == Work.AWAITING_TASK) state.copy(work = Work.IDLE) else state)
    }

    private fun heard(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.OFF) return Step(state)
        val request = Requests.read(event.text, event.named) ?: return Step(state)
        val shown = Effect.Show(Feedback.Heard(event.text))
        return when (state.work) {
            Work.RUNNING_TASK -> whileTaskRuns(state, request, shown)
            Work.AWAITING_TASK -> whileAwaitingTask(state, request, shown)
            Work.IDLE -> whileIdle(state, request, event.text, shown)
        }
    }

    // Section 11.10: while a task runs, only "stop" and "cancel" count, and the ways to pause or turn Kestrel off.
    private fun whileTaskRuns(state: State, request: Request, shown: Effect): Step = when (request) {
        is Request.Stop -> cancelTask(state, shown)
        Request.PauseListening -> {
            val cancelled = cancelTask(state, shown)
            val paused = switch(cancelled.state, Mode.PAUSED)
            Step(paused.state, cancelled.effects + paused.effects)
        }
        Request.TurnOff -> turnOff(state)
        else -> Step(state)
    }

    private fun whileAwaitingTask(state: State, request: Request, shown: Effect): Step = when (request) {
        is Request.Task -> runTask(state, request.text, shown)
        is Request.Other -> runTask(state, request.text, shown)
        Request.Name -> say(state, Reply.Yes, shown, Effect.WaitForTask)
        Request.WakeUp -> switch(state.copy(work = Work.IDLE), Mode.LISTENING, shown)
        is Request.Stop -> Step(state.copy(work = Work.IDLE), listOf(shown))
        Request.PauseListening -> switch(state.copy(work = Work.IDLE), Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state)
    }

    private fun whileIdle(state: State, request: Request, text: String, shown: Effect): Step = when (request) {
        is Request.Task -> runTask(state, request.text, shown)
        Request.Name -> say(state.copy(work = Work.AWAITING_TASK), Reply.Yes, shown, Effect.WaitForTask)
        Request.WakeUp -> if (state.mode == Mode.PAUSED) switch(state, Mode.LISTENING, shown) else Step(state, listOf(shown))
        Request.PauseListening -> if (state.mode == Mode.LISTENING) switch(state, Mode.PAUSED, shown) else Step(state, listOf(shown))
        Request.TurnOff -> turnOff(state)
        // "Kestrel, stop" with nothing running only ends the speech; plain "stop" may name a button.
        is Request.Stop -> if (request.named) Step(state, listOfNotNull(shown, Effect.StopSaying.takeIf { state.speaking })) else command(state, text.trim(), shown)
        is Request.Other -> command(state, request.text, shown)
    }

    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5).
    private fun command(state: State, text: String, shown: Effect): Step =
        if (state.mode == Mode.LISTENING) Step(state, listOf(shown, Effect.RunCommand(text))) else Step(state)

    private fun spotted(state: State, keyword: Keyword): Step {
        if (!state.speaking || state.mode == Mode.OFF) return Step(state)
        return when (keyword) {
            Keyword.STOP, Keyword.CANCEL ->
                if (state.work == Work.RUNNING_TASK) cancelTask(state) else Step(state.copy(work = Work.IDLE), listOf(Effect.StopSaying))
            Keyword.KESTREL ->
                if (state.work == Work.RUNNING_TASK) {
                    Step(state, listOf(Effect.StopSaying))
                } else {
                    Step(state.copy(work = Work.AWAITING_TASK), listOf(Effect.StopSaying, Effect.WaitForTask))
                }
        }
    }

    private fun taskEnded(state: State, event: Event.TaskEnded): Step {
        if (state.work != Work.RUNNING_TASK || event.task != state.task) return Step(state)
        val ended = state.copy(work = Work.IDLE)
        return if (event.reply == null) Step(ended) else say(ended, Reply.Text(event.reply))
    }

    private fun runTask(state: State, text: String, shown: Effect): Step {
        val task = state.task + 1
        return Step(state.copy(work = Work.RUNNING_TASK, task = task), listOf(shown, Effect.RunTask(task, text)))
    }

    private fun cancelTask(state: State, vararg first: Effect): Step = Step(
        state.copy(work = Work.IDLE),
        first.toList() + listOfNotNull(Effect.StopSaying.takeIf { state.speaking }) + Effect.CancelTask,
    )

    private fun say(state: State, reply: Reply, vararg first: Effect): Step =
        Step(state.copy(speeches = state.speeches + 1), first.toList() + Effect.Say(reply))

    private fun switch(state: State, mode: Mode, vararg first: Effect): Step =
        Step(state.copy(mode = mode), first.toList() + Effect.Show(Feedback.ModeChanged(mode)))

    // Off also forgets the replies in progress and "mic busy", so that a lost SpeechEnded cannot keep recognition off.
    private fun turnOff(state: State): Step = Step(
        state.copy(mode = Mode.OFF, work = Work.IDLE, speeches = 0, micBusy = false),
        listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.CancelTask.takeIf { state.work == Work.RUNNING_TASK },
            Effect.Show(Feedback.ModeChanged(Mode.OFF)),
            Effect.StopService,
        ),
    )
}
```

- [ ] **Step 5: Write the Orchestrator**

Create `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies [Rules] to Kestrel's events one at a time, so that two parts of Kestrel never act at once (design spec,
 * section 7.3). [perform] runs each effect in order on the Orchestrator's coroutine, so it must return quickly; an effect
 * that throws goes to [onError], and the next ones still run. The timer that ends the wait for a task after "Yes?" runs
 * here.
 */
class Orchestrator(
    private val scope: CoroutineScope,
    private val perform: (Effect) -> Unit,
    private val onError: (Exception) -> Unit,
    private val waitMillis: Long = WAIT_MILLIS,
) {
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val current = MutableStateFlow(State())
    private var wait: Job? = null

    /** The state after the last event. */
    val state: StateFlow<State> = current.asStateFlow()

    init {
        scope.launch {
            for (event in events) handle(event)
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) {
        events.trySend(event)
    }

    private fun handle(event: Event) {
        val step = Rules.next(current.value, event)
        current.value = step.state
        if (step.state.work != Work.AWAITING_TASK) {
            wait?.cancel()
            wait = null
        }
        for (effect in step.effects) {
            if (effect == Effect.WaitForTask) {
                wait?.cancel()
                wait = scope.launch {
                    delay(waitMillis)
                    send(Event.WaitEnded)
                }
            } else {
                try {
                    perform(effect)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    private companion object {
        // How long Kestrel waits for the task after "Yes?".
        const val WAIT_MILLIS = 8_000L
    }
}
```

- [ ] **Step 6: Run the tests to make sure they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests 'dev.kestrel.app.orchestrator.*'`
Expected: `BUILD SUCCESSFUL`, with 19 tests in `RulesTest`, 5 in `OrchestratorTest` and 11 in `RequestsTest`.

- [ ] **Step 7: Run every check of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests, lint and the audit pass, and the audit reports no new network use.

- [ ] **Step 8: Commit**

```powershell
git add app/build.gradle.kts app/src/main/kotlin/dev/kestrel/app/orchestrator app/src/test/kotlin/dev/kestrel/app/orchestrator
git commit -m "feat(app): the Orchestrator's listening rules and its event loop" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```
