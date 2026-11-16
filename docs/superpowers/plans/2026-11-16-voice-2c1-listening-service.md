# Voice 2C-1: The Listening Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire plan 2B's parts into a running listener: a process-wide controller that owns the Orchestrator, a voice session that owns the engines, the microphone, the pipeline and the player, and the foreground service `ListeningService` that keeps them alive, with its notification and the screen and lock handling, plus debug hooks and an end-to-end device test.

**Architecture:** `:core:voice` gets a `Microphone` seam that `MicCapture` implements, and two new pipeline events, `SpeechStarted` and `Dropped`. `:app` gets the package `dev.kestrel.app.listen`: `VoiceSession` opens and closes the engines, the microphone, the pipeline and the player in a fixed order; `Listening`, one per process in `AppGraph`, owns the Orchestrator, performs its effects and makes the service, the session and the microphone follow its state; `ListeningService` is the foreground service of the types microphone and media playback, with the notification and the screen receiver. The debug source set gets `ClipMicrophone`, which plays clips instead of recording, and the hooks `VOICE`, `VOICE_STATE` and `VOICE_CLIP`. Plan 2C-2 adds the accessibility button and shortcut, the real tile, more boost words and the final texts.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0, Android's `Service`, `Notification`, `PendingIntent` and `BroadcastReceiver` at compileSdk 37, plan 2A's engines and plan 2B's pipeline, player, microphone and Orchestrator, JUnit 4 with kotlinx-coroutines-test, and device tests on the `kestrel-api37` emulator through `scripts\voice-test.ps1`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; every dependency that ships in the app needs a GPL-3.0-compatible licence (D21, D28, section 17). This plan adds no dependency; kotlinx-coroutines-test stays test-only.
- `minSdk 33`, `targetSdk 37` and `compileSdk 37` (section 6).
- There is no dependency-injection framework; one `AppGraph` class wires the parts together by hand (section 17).
- `ListeningService` is a "Foreground service of the types `microphone` and `mediaPlayback`". "Android 17 requires audio playback to belong to a foreground service like this one (B5)" (section 7.2).
- Permissions granted at install: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (section 13.6). Kestrel never requests `SEND_SMS`, `CALL_PHONE`, `CAMERA`, `ACCESS_BACKGROUND_LOCATION`, `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `WRITE_SETTINGS` or `WRITE_SECURE_SETTINGS` (section 13.6).
- "A single `Orchestrator` coordinates everything." "The microphone and recognition are not effects; they follow from the state, so they cannot drift from it" (section 7.3).
- "When the screen turns off or the phone locks, Kestrel releases the microphone." "After unlock, Kestrel follows the 'on screen wake-up' setting": this plan always resumes the previous mode, the setting's default; the setting comes in plan 2D (section 8.5).
- The persistent notification offers "Pause, resume or stop" (section 8.6).
- "The feedback bar shows 'mic busy' whenever Android silences Kestrel's recording" (section 8.1).
- Section 14.6: when "Voice output fails", "Kestrel shows its text on screen only"; when "The microphone service cannot start", "Kestrel asks the owner to start it from the notification or the tile, and says why".
- "Screenshots, audio and credentials are never logged" (section 13.5). No log line, exception message or `toString` may contain audio, transcripts, replies, boost words or screen text; a class that carries the owner's words prints only lengths. Errors are logged by their classes and stack frames only.
- Test hooks exist only in debug builds and accept commands only from callers that hold `android.permission.DUMP` (section 16.1).
- Testing happens only on the `kestrel-api37` emulator (`emulator-5554`). Never test on the Galaxy S23+ or the AVD `zoran`, and never send a command to the Galaxy A71 (D20, AGENTS.md). Gradle runs only through `scripts\gradle.ps1` (stopped with `scripts\gradle.ps1 --stop`), device tests only through `scripts\voice-test.ps1`, installs only through `scripts\install.ps1`.
- The repository uses LF line endings (`.gitattributes`).

## Before you start

- Plans 2A and 2B are complete (HEAD `0544ab4`). `:core:voice` has `VoiceEngines` (`speechDetector()`, `keywordSpotter()`, `recognizer()`, `synthesizer(options)`), which throw `ModelsNotReadyException` when a model is missing; `ListeningPipeline`, `Recognition`, `VoiceEvent` and `MicCapture` in `dev.kestrel.voice.listen`; `VoicePlayer`, `Spoke` and `EchoFilter` in `dev.kestrel.voice.speak`. `:app` has `Rules`, `State`, `Event`, `Effect`, `Reply`, `Feedback`, `Mode`, `Work` and `Orchestrator` in `dev.kestrel.app.orchestrator`.
- What plan 2B's code really does, beyond its plan (`.superpowers/sdd/progress.md`): the four Sherpa engines throw `IllegalStateException` when used after `close()`, and `close()` waits for a call in progress, so an engine closes only after the thread that feeds it has stopped. `MicCapture.start(onFrame, onError: (Exception) -> Unit)` passes an exception from `onFrame` to `onError` and ends its thread; the recording stays open until `stop()`; `start()` and `stop()` throw on the microphone's own thread; `silenced` is true for any reason why Android silences Kestrel. `VoicePlayer.speak()` never blocks, completes every reply (`FINISHED`, `STOPPED`, `REFUSED`, or with the error), and returns a completed `STOPPED` after `close()`. The Orchestrator catches every `Exception` from `perform` and passes it to `onError`; for a `Say` it then handles `SpeechEnded`, for a `RunTask` `TaskEnded(n, null)`. `pipeline.pause()` drops the utterance in progress.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` when you are done.
- Run the device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`. It installs Kestrel, copies the speech models with `push-models.ps1`, runs every test in the package `dev.kestrel.app.voice`, keeps Kestrel and its test APK installed (uninstalling Kestrel deletes the models), and fails when a test was skipped. All new device tests of this plan live in that package for these reasons. Before each device step, check the emulator: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name` must print `kestrel-api37` (then `OK`). If it prints anything else, or no emulator runs, start it with `scripts\start-emulator.ps1` and use the serial that it prints instead of `emulator-5554`.
- After you change a script, run `scripts\test-scripts.ps1` (AGENTS.md).
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan (plan 2C-2)

The accessibility button and shortcut; the real Quick Settings tile (the spike tile stays); boost words beyond "Kestrel"; the final wording of the feedback texts; section 14.6's handling beyond what the shutdown needs (for example "Recognition keeps returning empty text", which must not count the empty `Heard` that this plan sends for a dropped utterance); resuming after a process restart; plan 2A's deferred rows 9, 10, 11 and 13. Plan 2C-2 also shows the reasons why Kestrel turned itself off (it could not start listening, the models are missing, the microphone failed) as a notification, because the feedback bar exists only while the accessibility service runs, and the most common reason, a start from the background without that service, happens exactly when there is no bar (section 14). `SpikeListeningService`, `SpikeTileService`, `SpikeStartup`, the `LISTEN` hooks and `scripts\spike-s1.ps1` stay as they are.

## Threads and the shutdown order

| Call | Thread | May block? |
|---|---|---|
| `Listening`'s Orchestrator loop, `perform`, the following of the state, `Session.startMicrophone`, `Session.stopMicrophone`, `Session.recognition` | The controller's thread: `Dispatchers.Default.limitedParallelism(1)` in `AppGraph.scope`'s job | `stopMicrophone()` joins the microphone's thread (at most one frame's work); nothing else blocks |
| Opening and closing a session | Coroutines on the controller's thread, behind one `Mutex`; the engines load on `Dispatchers.IO` (plan 2A) and close on `Dispatchers.IO` | They suspend for seconds, so the controller's thread stays free; a new start waits for the lock |
| `pipeline.accept` (and the keyword spotter's echo check) | The microphone's thread | Milliseconds per frame (prototype P1: 0.9-1.5 ms on the emulator) |
| Transcription | One thread per session: `Dispatchers.Default.limitedParallelism(1)` | Seconds per utterance |
| Pipeline events and `silenced` to the Orchestrator | A coroutine of the session on `Dispatchers.Default` | No; `send` is thread-safe |
| A reply's completion: `SpeechEnded`, and the reply's text on the bar when it was refused or failed | The player's thread (`Dispatchers.IO`), or the controller's thread for a reply that completed at once | No |
| `ListeningService` callbacks, the screen receiver, notification updates | The main thread; they only send events or hand over to the controller's thread | No |
| The feedback bar | One coroutine per text on the main thread (`scope.launch(Dispatchers.Main)` in `AppGraph.scope`), so that the texts keep their order | No |
| Stopping `ListeningService` | The controller posts it to the main thread, where the service stops itself with `stopSelfResult(lastStartId)` | No |

The shutdown order (final review of plan 2B, section 2) is: stop the microphone (first, also when something else fails), `pipeline.pause()`, `pipeline.close()`, `player.close()`, then close the recogniser, the spotter, the detector and the voice; only then stop `ListeningService`. Every step runs even when an earlier one throws. A start that comes during a shutdown waits for it, because two recognisers would need about 1.4 GB.

Android ends Kestrel's process when a service that was started with `startForegroundService()` is brought down before it called `startForeground()`. Every start and every notification action is such a start. So the controller never stops a service that it asked to start and that has not reported back yet; that service reports to the controller, which stops it then. And the service stops itself with `stopSelfResult(startId)` for the last start that it handled, which Android ignores while a newer start, such as a notification action that was tapped a moment before, is still on its way to the service; that start reports too, and the controller stops the service again.

## File structure

| File | Responsibility |
|---|---|
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Microphone.kt` | `Microphone`: the seam between the pipeline and where its audio comes from |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt` | Implements `Microphone`; nothing else changes |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt` | Gains `VoiceEvent.SpeechStarted` and `VoiceEvent.Dropped` |
| `app/src/main/kotlin/dev/kestrel/app/listen/Session.kt` | `Session`, `SessionListener` and `VoiceSessions`: what the controller uses of a voice session |
| `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt` | `VoiceSession`: opens the engines, the player, the echo filter and the pipeline, maps their events, and closes them in order |
| `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` | `ServiceControl`, `FeedbackBar`, `ListeningTexts` and `Listening`: the controller |
| `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt` | `ListeningService`: the foreground service, its notification and the screen and lock receiver |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` | Gains `listening` |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml` | The foreground service permissions and declaration; the notification's texts |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` | The KDoc of `Event.Speaking` names what produces it |
| `app/src/debug/kotlin/dev/kestrel/app/debug/ClipMicrophone.kt` | `ClipMicrophone`: a `Microphone` that plays queued clips in real time |
| `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt` | The hooks `VOICE`, `VOICE_STATE` and `VOICE_CLIP` |
| `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` | Routes the voice hooks; `HookError` becomes visible to them |
| `scripts/hook.ps1` | The new commands and their help |
| `scripts/voice-test.ps1` | Leaves the lock-screen test out on phones, as it does for the privacy-toggle test |
| `README.md`, `docs/superpowers/specs/2026-11-12-kestrel-design.md` | How listening starts in a debug build; section 7.2's row for `ListeningService` |

Tests: `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt` (changed), `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt` (JVM, new), and the device tests `VoiceSessionTest`, `ListeningServiceTest`, `VoiceHookTest` and `ConversationTest` in `app/src/androidTest/kotlin/dev/kestrel/app/voice/`, with new helpers in `VoiceTests.kt` there.

---

### Task 1: The microphone seam, and the start of speech and dropped utterances in the pipeline

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Microphone.kt`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt` (the class header and four members)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt` (whole file below)
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`

**Interfaces:**
- Consumes: `MicCapture`, `ListeningPipeline`, `VoiceEvent`, `Recognition`, `SpeechDetector.hearsSpeech` (plans 2A and 2B).
- Produces: in package `dev.kestrel.voice.listen`: `interface Microphone` with `val silenced: StateFlow<Boolean>`, `val echoCancelling: Boolean`, `fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit)` and `fun stop()`; `class MicCapture(context: Context) : Microphone`; `VoiceEvent.SpeechStarted` and `VoiceEvent.Dropped` (both `data object`). Task 2's `VoiceSession` maps `SpeechStarted` to `Event.Speaking` and `Dropped` to `Event.Heard("", named = false)`; Task 2's `ClipMicrophone` implements `Microphone`.

Plan 2B's final review (M-1) asks the pipeline to produce the Orchestrator's `Event.Speaking`, because only the pipeline knows `hearsSpeech` and `recognition` for each frame. `SpeechStarted` follows the contract in the KDoc of `Event.Speaking`: it comes when the detector hears speech on a frame for which recognition is not `NONE`, and on the frame before it heard none or recognition was `NONE`; it comes again on the first frame on which the detector still hears speech after the pipeline sent an event for an earlier utterance (a transcript, a failure or a drop). The worker sets a flag after each event that it sends, and the microphone's thread reads and clears it on the next frame, so the second `SpeechStarted` always comes after that event. `Dropped` is an utterance that recognition would have transcribed but that the resume rule of section 8.4 left nothing of: less than 0.25 s after the cut, or an utterance that the owner's "Kestrel" broke into. Without it, the echo of "Yes?" would start the 35 s wait and nothing would end it early.

- [ ] **Step 1: Write the failing tests**

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, make the fake detector's `hearsSpeech` settable. Replace

```kotlin
        var finished = 0
        override val hearsSpeech = false
```

with

```kotlin
        var finished = 0
        override var hearsSpeech = false
```

Then add these five tests right after the test `anInterruptionMoreThan3SecondsOldOnlyCutsAtTheResume`:

```kotlin
    @Test
    fun speechStartedComesWhenTheDetectorStartsToHearSpeech() = runTest {
        val pipeline = pipeline()
        pipeline.feedUntil(0.5f)
        detector.hearsSpeech = true
        pipeline.feedUntil(1f)
        detector.hearsSpeech = false
        pipeline.feedUntil(1.5f)
        detector.hearsSpeech = true
        pipeline.feedUntil(2f)

        assertEquals(listOf<VoiceEvent>(VoiceEvent.SpeechStarted, VoiceEvent.SpeechStarted), events)
    }

    @Test
    fun speechStartedWaitsWhileKestrelSpeaksAndComesWhenRecognitionResumesDuringSpeech() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        detector.hearsSpeech = true
        pipeline.feedUntil(1f)
        assertEquals(emptyList<VoiceEvent>(), events)

        pipeline.recognition = Recognition.ALL
        pipeline.feedUntil(1.5f)

        assertEquals(listOf<VoiceEvent>(VoiceEvent.SpeechStarted), events)
    }

    @Test
    fun speechStartedComesAgainWhenAnEventArrivesWhileTheOwnerStillSpeaks() = runTest {
        val pipeline = pipeline()
        detector.hearsSpeech = true
        pipeline.feedUntil(1f)
        // An earlier utterance ends, and its transcript arrives while the owner goes on speaking.
        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()
        pipeline.feedUntil(1.5f)

        assertEquals(listOf("SpeechStarted", "Heard", "SpeechStarted"), events.map { it::class.simpleName })
    }

    @Test
    fun anUtteranceThatTheResumeCutsToNothingIsReportedAsDropped() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL
        pipeline.feedUntil(1.6f)
        // Only 0.1 s of it comes after 1 s, when Kestrel stopped speaking: the echo of Kestrel's own voice.
        detector.next.addLast(listOf(utterance(0.3f, 0.8f)))
        pipeline.feed()

        assertEquals(listOf<VoiceEvent>(VoiceEvent.Dropped), events)
        assertEquals(0, recognizer.calls)
    }

    @Test
    fun speechStartedComesAgainAfterADropWhileTheOwnerStillSpeaks() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL
        detector.hearsSpeech = true
        pipeline.feedUntil(1.6f)
        // Kestrel's echo before 1 s ends, while the owner, who has started to answer, still speaks.
        detector.next.addLast(listOf(utterance(0.3f, 0.8f)))
        pipeline.feed()
        pipeline.feedUntil(2f)

        assertEquals(listOf<VoiceEvent>(VoiceEvent.SpeechStarted, VoiceEvent.Dropped, VoiceEvent.SpeechStarted), events)
    }
```

Last, the interrupted utterance in plan 2B's test is a drop too. In `anUtteranceThatBeganWithKestrelsVoiceIsDroppedAfterAnInterruption`, replace

```kotlin
        detector.next.addLast(listOf(utterance(0.2f, 3.8f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
```

with

```kotlin
        detector.next.addLast(listOf(utterance(0.2f, 3.8f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
        assertEquals(listOf(VoiceEvent.Spotted(Keyword.KESTREL), VoiceEvent.Dropped), events)
```

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'SpeechStarted'" and "Unresolved reference 'Dropped'".

- [ ] **Step 3: Add the microphone seam**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Microphone.kt`:

```kotlin
package dev.kestrel.voice.listen

import kotlinx.coroutines.flow.StateFlow

/**
 * Where the listening pipeline's audio comes from (design spec, section 8.1): 20 ms frames of 16 kHz samples between -1
 * and 1. [MicCapture] records the microphone; the device tests and the debug hooks play clips instead.
 *
 * Any thread may call [start] and [stop], except the microphone's own thread.
 */
interface Microphone {
    /**
     * True while Android silences the recording: when another app has priority on the microphone, when the microphone's
     * privacy toggle is on, or when Android counts Kestrel as idle. "Mic busy" on the feedback bar (section 8.1). [stop]
     * sets it back to false.
     */
    val silenced: StateFlow<Boolean>

    /** Whether an echo canceller works on the current recording. */
    val echoCancelling: Boolean

    /**
     * Starts recording. [onFrame] gets each 20 ms frame. Both callbacks run on the microphone's own thread.
     *
     * [onError] is called at most once, and then the thread ends: when reading the microphone fails, or with the
     * exception that [onFrame] threw. [onError] itself must not throw. After an error nothing reads the microphone any
     * more, but the recording stays open until [stop]. [start] and [stop] throw [IllegalStateException] when they are
     * called from the callbacks, because [stop] waits for the thread; the caller hands the error to another thread, which
     * then stops the microphone.
     *
     * Throws when the microphone does not start, for example [SecurityException] without the microphone permission; a
     * later [stop] releases whatever the start had opened.
     */
    fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit)

    /** Stops recording, and returns when the microphone's thread has finished. */
    fun stop()
}
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, make `MicCapture` implement it; nothing else changes. Replace

```kotlin
class MicCapture(private val context: Context) {
```

with

```kotlin
class MicCapture(private val context: Context) : Microphone {
```

Replace

```kotlin
    val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    /** Whether Android's echo canceller works on the current recording. */
    @Volatile
    var echoCancelling = false
        private set
```

with

```kotlin
    override val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    /** Whether Android's echo canceller works on the current recording. */
    @Volatile
    override var echoCancelling = false
        private set
```

Replace

```kotlin
    fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
```

with

```kotlin
    override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
```

Replace

```kotlin
    fun stop() {
```

with

```kotlin
    override fun stop() {
```

- [ ] **Step 4: Add the two events to the pipeline**

Replace the whole of `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt` with:

```kotlin
package dev.kestrel.voice.listen

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
     * the keyword spotter heard "Kestrel" at its start. The part of an utterance that is left after the pipeline cut away
     * Kestrel's own voice is never named, because the cut removed the part that held the name.
     */
    data class Heard(val text: String, val named: Boolean, val seconds: Float, val millis: Long) : VoiceEvent {
        override fun toString() = "Heard(${text.length} characters, named=$named, seconds=$seconds, millis=$millis)"
    }

    /** The keyword spotter heard [keyword], and it was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : VoiceEvent

    /**
     * The owner started to speak while recognition is on: the speech detector hears speech on a frame for which
     * recognition is not [Recognition.NONE], and on the frame before it heard none or recognition was [Recognition.NONE].
     * It comes again on the first frame on which the detector still hears speech after the pipeline sent an event for an
     * earlier utterance: a transcript, a failure or a drop. The Orchestrator's `Event.Speaking`.
     */
    data object SpeechStarted : VoiceEvent

    /**
     * An utterance ended that recognition would have transcribed, but nothing of it was left to transcribe: it began
     * while Kestrel spoke, and after the cut at the resume less than 0.25 s remained, or the owner's "Kestrel" broke into
     * it (section 8.4).
     */
    data object Dropped : VoiceEvent

    /** Transcribing an utterance failed; the pipeline goes on with the next one. */
    data class Failed(val error: Exception) : VoiceEvent {
        // The message could hold words from the screen or the owner, so only the class is printed.
        override fun toString() = "Failed(${error.javaClass.name})"
    }
}

/**
 * Turns the microphone's frames into transcripts and keyword events (design spec, sections 7.5 and 8.1–8.4). The speech
 * detector and the keyword spotter hear every frame; the endpointer decides when an utterance has ended, and the
 * recogniser transcribes the utterances that [recognition] asks for, one at a time in [recognitionContext], with the
 * boost words that [boostWords] returns at that moment. [isOwnVoice] tells which keyword hits came from Kestrel's own
 * voice. The pipeline does not own the engines: whoever opened them closes them, after [close] and after the last call
 * of [accept] and [pause], because the engines throw when they are used after they were closed. Call [accept] and
 * [pause] from one thread at a time: from the microphone's thread, or after it has stopped.
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

    // The worker sets it after each event that it sends; the microphone's thread reads and clears it on the next frame.
    private val sent = AtomicBoolean(false)
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
    private var hearing = false

    /**
     * What the pipeline heard, in the order in which the pipeline produces the events. A transcript can come after the
     * keyword events of later audio, because transcribing takes time. Collect it once.
     */
    val events: Flow<VoiceEvent> = output.receiveAsFlow()

    /** Which utterances to transcribe from the next frame on. Any thread may set it. */
    @Volatile
    var recognition: Recognition = Recognition.ALL

    /** Feeds one frame of 16 kHz samples. */
    fun accept(frame: FloatArray) {
        val wanted = recognition
        val before = lastRecognition
        if (before == Recognition.NONE && wanted != Recognition.NONE) resume()
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
        val hears = detector.hearsSpeech
        // An event for an earlier utterance went out since the last frame: the worker's, or a drop below.
        var answered = sent.getAndSet(false)
        for (speech in endpointer.accept(utterances, names, hears, position)) {
            val kept = afterResume(speech)
            if (kept == null) {
                if (transcribes(wanted, speech.named)) {
                    output.trySend(VoiceEvent.Dropped)
                    answered = true
                }
            } else if (transcribes(wanted, kept.named)) {
                queue.trySend(kept)
            }
        }
        // The Orchestrator's Event.Speaking: an event for an earlier utterance may have shortened the wait for a task, so
        // speech that goes on after it counts as a new start.
        if (hears && wanted != Recognition.NONE && (!hearing || before == Recognition.NONE || answered)) {
            output.trySend(VoiceEvent.SpeechStarted)
        }
        hearing = hears
    }

    /**
     * Drops the utterance that the endpointer holds and the audio in progress after the microphone stopped, so that they
     * do not run into the next audio. A transcription that was already queued or running still finishes, and its event
     * still goes out; the Orchestrator ignores transcripts while the screen is off.
     */
    fun pause() {
        detector.finish()
        detectorStart = position
        endpointer.finish()
        interruption = null
        hearing = false
    }

    /**
     * Stops transcribing, drops the utterances that wait and ends [events]. Returns when the recogniser is no longer in
     * use.
     */
    suspend fun close() {
        try {
            queue.cancel()
            worker.cancelAndJoin()
        } finally {
            output.close()
        }
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
            named = false,
        )
    }

    /** Whether recognition [wanted] transcribes an utterance that is [named], or not. */
    private fun transcribes(wanted: Recognition, named: Boolean): Boolean = when (wanted) {
        Recognition.ALL -> true
        Recognition.NAMED -> named
        Recognition.NONE -> false
    }

    private suspend fun transcribe(speech: Speech) {
        val event = try {
            val words = boostWords()
            val started = System.nanoTime()
            val transcript = recognizer.transcribe(speech.samples, words)
            VoiceEvent.Heard(transcript.text, speech.named, speech.seconds, (System.nanoTime() - started) / 1_000_000)
        } catch (e: Exception) {
            // A cancellation that is not the worker's own, such as a timeout inside boostWords, is a failure like any
            // other.
            currentCoroutineContext().ensureActive()
            VoiceEvent.Failed(e)
        }
        output.trySend(event)
        sent.set(true)
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

What changed against plan 2B's file: the import of `AtomicBoolean`, the two events, the fields `sent` and `hearing`, the part of `accept` from `val before` and from `val hears` on, `hearing = false` in `pause()`, the helper `transcribes`, and `sent.set(true)` in `transcribe`. `sent` is declared before `worker`, so the worker never sees it uninitialised.

- [ ] **Step 5: Run the tests to make sure they pass**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD SUCCESSFUL`, with 24 tests in `ListeningPipelineTest` (19 from plan 2B and the 5 new ones).

- [ ] **Step 6: Run the module's checks and compile the app's device tests**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest :core:voice:lintDebug :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: 97 JVM tests in `:core:voice`, no lint issue, and `MicCaptureTest` still compiles against `MicCapture`.

- [ ] **Step 7: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/listen/Microphone.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt
git commit -m "feat(voice): a microphone seam, and the start of speech and dropped utterances in the pipeline" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The voice session

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/Session.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/ClipMicrophone.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (the KDoc of `Event.Speaking`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionTest.kt`

**Interfaces:**
- Consumes: `Microphone`, `VoiceEvent.SpeechStarted` and `VoiceEvent.Dropped` (Task 1); `VoiceEngines.speechDetector()`, `keywordSpotter()`, `recognizer()`, `synthesizer()`, `ModelsNotReadyException` (plan 2A); `ListeningPipeline(detector, spotter, recognizer, boostWords, isOwnVoice, scope, recognitionContext)`, `VoicePlayer(context, synthesizer)` with `speak(text): Deferred<Spoke>`, `stop()`, `close()`, `recent()`, and `EchoFilter().isOwnVoice(keyword, hitMillis, spoken)` (plan 2B); `Event.Heard`, `Event.Spotted`, `Event.Speaking`, `Event.MicBusy` (plan 2B).
- Produces: in package `dev.kestrel.app.listen` (all `internal`): `interface Session` with `var recognition: Recognition`, `fun startMicrophone()`, `fun stopMicrophone()`, `fun speak(text: String): Deferred<Spoke>`, `fun stopSpeaking()`, `suspend fun close()`; `interface SessionListener` with `fun send(event: Event)`, `fun microphoneFailed(error: Exception)`, `fun transcriptionFailed(error: Exception)`; `interface VoiceSessions` with `suspend fun open(microphone: Microphone, listener: SessionListener): Session`; `class VoiceSession(context, detector, spotter, recognizer, synthesizer, microphone, listener) : Session` with `companion suspend fun open(context: Context, engines: VoiceEngines, microphone: Microphone, listener: SessionListener): VoiceSession`. In the debug source set, package `dev.kestrel.app.debug`: `class ClipMicrophone : Microphone` with `fun play(samples: FloatArray)`, `fun silence(on: Boolean)`, `fun clear()`, `val recording: Boolean` and `companion fun speech(voice: Synthesizer, text: String): FloatArray`. Task 3's controller uses `Session`, `SessionListener` and `VoiceSessions`; Tasks 4-6 use `ClipMicrophone`.

The session owns everything that listening loads: the four engines, the player, the echo filter, the pipeline and the microphone. It maps the pipeline's events to the Orchestrator's: `Heard` to `Event.Heard`, `Spotted` to `Event.Spotted`, `SpeechStarted` to `Event.Speaking`, `Dropped` and `Failed` to `Event.Heard("", named = false)`, so that an utterance without a transcript restarts the 8 s wait for a task (Rules already do that for an empty answer), and `Failed` also goes to `transcriptionFailed`. `microphone.silenced` becomes `Event.MicBusy`. The microphone's thread only calls `pipeline.accept`. `close()` stops the microphone first, then pauses and closes the pipeline, closes the player, and closes the engines on `Dispatchers.IO`, each step also when an earlier one threw, without being cancellable, and throws the first failure at the end.

`ClipMicrophone` stands in for the microphone in the device tests and the debug hooks: it delivers the clips that `play` queues in 20 ms frames, one frame every 20 ms on its own thread, and silence between them, as the microphone would.

- [ ] **Step 1: Write the failing device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionTest.kt`:

```kotlin
package dev.kestrel.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The voice session with the real engines, on Kestrel's own voice played through a stand-in microphone (section 8). */
class VoiceSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clip = ClipMicrophone()
    private val listener = RecordingListener()

    @Before
    fun models() = VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)

    @Test
    fun theSessionTurnsSpeechIntoTheOrchestratorsEvents() = runBlocking {
        val session = VoiceSession.open(context, VoiceTests.graph.voice, clip, listener)
        try {
            session.recognition = Recognition.ALL
            session.startMicrophone()
            clip.play(VoiceTests.speech("Kestrel, open the settings."))
            val heard = listener.await { it is Event.Heard } as Event.Heard
            clip.silence(true)
            listener.await { it == Event.MicBusy(true) }
            session.stopMicrophone()

            assertTrue(heard.named)
            assertTrue(heard.text.lowercase().contains("open the settings"))
            val events = listener.events.toList()
            assertTrue("No Spotted(KESTREL)", Event.Spotted(Keyword.KESTREL) in events)
            assertTrue("Speaking must come before the transcript", events.indexOf(Event.Speaking) in 0 until events.indexOf(heard))
            assertEquals(emptyList<Exception>(), listener.errors.toList())
        } finally {
            session.close()
        }
        Unit
    }

    @Test
    fun closeStopsTheMicrophoneBeforeTheEnginesAndClosesThemAll() = runBlocking {
        val voice = VoiceTests.graph.voice
        val loaded = mutableListOf<AutoCloseable>()
        var session: VoiceSession? = null
        var closed = false
        try {
            val detector = voice.speechDetector().also { loaded += it }
            val spotter = voice.keywordSpotter().also { loaded += it }
            val recognizer = voice.recognizer().also { loaded += it }
            val synthesizer = voice.synthesizer().also { loaded += it }
            val open = VoiceSession(context, detector, spotter, recognizer, synthesizer, clip, listener)
            session = open
            open.recognition = Recognition.ALL
            open.startMicrophone()
            clip.play(VoiceTests.speech("Open the settings."))
            // The microphone still delivers speech when the session closes. A frame that reached a closed engine would
            // make the pipeline throw, and the microphone would report it as an error.
            delay(1_000)

            open.close()
            closed = true

            assertFalse("The microphone still records", clip.recording)
            assertEquals(emptyList<Exception>(), listener.errors.toList())
            assertThrows(IllegalStateException::class.java) { detector.accept(FloatArray(VoiceTests.FRAME)) }
            assertThrows(IllegalStateException::class.java) { spotter.accept(FloatArray(VoiceTests.FRAME)) }
            assertThrows(IllegalStateException::class.java) { recognizer.transcribe(FloatArray(VoiceTests.FRAME)) }
            assertThrows(IllegalStateException::class.java) { synthesizer.synthesize("Hello.") }
            // The player closed too, so a reply after the close is stopped without the voice.
            assertEquals(Spoke.STOPPED, withTimeout(10_000) { open.speak("Hello.").await() })
        } finally {
            if (!closed) {
                val open = session
                if (open != null) open.close() else loaded.forEach { it.close() }
            }
        }
        Unit
    }

    /** Records what a session reports, from any thread. */
    private class RecordingListener : SessionListener {
        val events = CopyOnWriteArrayList<Event>()
        val errors = CopyOnWriteArrayList<Exception>()
        private val arrived = Channel<Event>(Channel.UNLIMITED)

        override fun send(event: Event) {
            events += event
            arrived.trySend(event)
        }

        override fun microphoneFailed(error: Exception) {
            errors += error
        }

        override fun transcriptionFailed(error: Exception) {
            errors += error
        }

        /** Waits up to 30 s for the next event that [matches]. */
        suspend fun await(matches: (Event) -> Boolean): Event = withTimeout(30_000) {
            var event = arrived.receive()
            while (!matches(event)) event = arrived.receive()
            event
        }
    }
}
```

- [ ] **Step 2: Run the compiler to make sure the test fails**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with "Unresolved reference 'ClipMicrophone'", "Unresolved reference 'SessionListener'" and "Unresolved reference 'VoiceSession'".

- [ ] **Step 3: Write what the controller needs of a session**

Create `app/src/main/kotlin/dev/kestrel/app/listen/Session.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.Deferred

/**
 * An open voice session: the speech engines, the microphone, the listening pipeline and the voice player (design spec,
 * sections 7.2 and 8.1–8.4). The listening controller sets [recognition] and calls [startMicrophone], [stopMicrophone]
 * and [close] from its own thread, one at a time; [speak] and [stopSpeaking] may be called from any thread.
 */
internal interface Session {
    /** Which utterances the pipeline transcribes from the next frame on. */
    var recognition: Recognition

    /**
     * Starts the microphone. Throws when it does not start, for example without the microphone permission; then
     * [stopMicrophone] releases whatever the start had opened.
     */
    fun startMicrophone()

    /** Stops the microphone, and then drops the audio that the pipeline still holds. */
    fun stopMicrophone()

    /** Speaks [text] in Kestrel's voice. The result completes as `VoicePlayer.speak`'s does. */
    fun speak(text: String): Deferred<Spoke>

    /** Stops the reply that plays and the replies that wait. */
    fun stopSpeaking()

    /**
     * Stops the microphone, then closes the pipeline, the player and the engines, in this order, each step also when an
     * earlier one failed, and then throws the first failure. The session cannot be used afterwards.
     */
    suspend fun close()
}

/** Where a session reports. */
internal interface SessionListener {
    /** An event for the Orchestrator. Any thread may call it. */
    fun send(event: Event)

    /** The microphone failed. It is called on the microphone's thread, so it must return at once and must not throw. */
    fun microphoneFailed(error: Exception)

    /** Transcribing an utterance failed; the session goes on. */
    fun transcriptionFailed(error: Exception)
}

/** Opens voice sessions. */
internal interface VoiceSessions {
    /**
     * Opens a session that records with [microphone] and reports to [listener]. It loads the speech models, which takes
     * several seconds. Throws `ModelsNotReadyException` when a model is missing or damaged, after closing the engines
     * that it had already loaded.
     */
    suspend fun open(microphone: Microphone, listener: SessionListener): Session
}
```

- [ ] **Step 4: Write the session**

Create `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`:

```kotlin
package dev.kestrel.app.listen

import android.content.Context
import android.os.SystemClock
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.listen.ListeningPipeline
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.listen.VoiceEvent
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kestrel's voice session (design spec, sections 7.2 and 8.1–8.4). It owns the speech engines, [microphone], the
 * listening pipeline, the echo filter and the voice player, and turns what the pipeline hears into the Orchestrator's
 * events for [listener]. The microphone's thread only feeds the pipeline; transcription runs on a thread of its own,
 * and the events go out from a coroutine of the session. The only boost word is "Kestrel" for now.
 */
internal class VoiceSession(
    context: Context,
    private val detector: SpeechDetector,
    private val spotter: KeywordSpotter,
    private val recognizer: Recognizer,
    private val synthesizer: Synthesizer,
    private val microphone: Microphone,
    private val listener: SessionListener,
) : Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = VoicePlayer(context, synthesizer)
    private val echo = EchoFilter()
    private val pipeline = ListeningPipeline(
        detector = detector,
        spotter = spotter,
        recognizer = recognizer,
        boostWords = { BOOST_WORDS },
        // The keyword spotter hears Kestrel's own voice too; the echo filter knows what Kestrel said and when (8.4).
        isOwnVoice = { keyword -> echo.isOwnVoice(keyword, SystemClock.elapsedRealtime(), player.recent()) },
        scope = scope,
        recognitionContext = Dispatchers.Default.limitedParallelism(1),
    )

    init {
        // Collected once, from the start, so that no event is lost.
        scope.launch { pipeline.events.collect { forward(it) } }
        scope.launch { microphone.silenced.collect { listener.send(Event.MicBusy(it)) } }
    }

    override var recognition: Recognition
        get() = pipeline.recognition
        set(value) {
            pipeline.recognition = value
        }

    override fun startMicrophone() {
        microphone.start(onFrame = pipeline::accept, onError = listener::microphoneFailed)
    }

    override fun stopMicrophone() {
        microphone.stop()
        // Only after the microphone's thread has ended, because the pipeline serves one thread at a time.
        pipeline.pause()
    }

    override fun speak(text: String): Deferred<Spoke> = player.speak(text)

    override fun stopSpeaking() = player.stop()

    override suspend fun close() {
        val errors = mutableListOf<Exception>()
        // A close that stopped half-way would leave the engines open, with about 1 GB of native memory.
        withContext(NonCancellable) {
            // The microphone first: a recording whose reader stopped stays open, and the engines may close only after the
            // last frame (final review of plan 2B, section 2).
            errors.attempt { microphone.stop() }
            errors.attempt { pipeline.pause() }
            errors.attempt { pipeline.close() }
            errors.attempt { player.close() }
            // An engine waits for a call in progress when it closes, so the engines close on a thread that may wait.
            withContext(Dispatchers.IO) {
                errors.attempt { recognizer.close() }
                errors.attempt { spotter.close() }
                errors.attempt { detector.close() }
                errors.attempt { synthesizer.close() }
            }
        }
        scope.cancel()
        val first = errors.firstOrNull() ?: return
        errors.drop(1).forEach(first::addSuppressed)
        throw first
    }

    private fun forward(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Heard -> listener.send(Event.Heard(event.text, event.named))
            is VoiceEvent.Spotted -> listener.send(Event.Spotted(event.keyword))
            VoiceEvent.SpeechStarted -> listener.send(Event.Speaking)
            // An utterance without a transcript gives the owner the whole wait for a task again (Rules, Event.Speaking).
            VoiceEvent.Dropped -> listener.send(Event.Heard("", named = false))
            is VoiceEvent.Failed -> {
                listener.transcriptionFailed(event.error)
                listener.send(Event.Heard("", named = false))
            }
        }
    }

    companion object {
        private val BOOST_WORDS = listOf("Kestrel")

        /**
         * Loads the engines and opens a session (section 8.2): first the speech detector, the keyword spotter and the
         * recogniser, which the pipeline needs, then the voice with its default options. Takes several seconds. Throws
         * `ModelsNotReadyException` when a model is missing or damaged, after closing the engines that it had loaded.
         */
        suspend fun open(context: Context, engines: VoiceEngines, microphone: Microphone, listener: SessionListener): VoiceSession {
            val loaded = mutableListOf<AutoCloseable>()
            try {
                val detector = engines.speechDetector().also { loaded += it }
                val spotter = engines.keywordSpotter().also { loaded += it }
                val recognizer = engines.recognizer().also { loaded += it }
                val synthesizer = engines.synthesizer().also { loaded += it }
                return VoiceSession(context, detector, spotter, recognizer, synthesizer, microphone, listener)
            } catch (e: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    for (engine in loaded.asReversed()) {
                        try {
                            engine.close()
                        } catch (closing: Exception) {
                            e.addSuppressed(closing)
                        }
                    }
                }
                throw e
            }
        }
    }
}

/** Runs [step], and keeps its exception instead of throwing it, so that the next steps still run. */
private suspend fun MutableList<Exception>.attempt(step: suspend () -> Unit) {
    try {
        step()
    } catch (e: Exception) {
        add(e)
    }
}
```

- [ ] **Step 5: Write the stand-in microphone**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/ClipMicrophone.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.listen.Microphone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * A stand-in for the microphone, for the device tests and the debug hooks (design spec, section 16.1). It delivers the
 * clips that [play] queues, in 20 ms frames of 16 kHz samples, in real time: one frame every 20 ms on its own thread,
 * and silence between the clips. A clip that is still playing when [stop] comes is dropped; the clips after it wait for
 * the next [start]. [silence] stands in for Android silencing the recording.
 */
class ClipMicrophone : Microphone {
    private val clips = ConcurrentLinkedQueue<FloatArray>()
    private val silencedState = MutableStateFlow(false)

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var running = false

    override val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    override val echoCancelling = false

    /** Whether the microphone records: from [start] until [stop]. */
    val recording: Boolean get() = reader != null

    /** Queues [samples] of 16 kHz audio, which the microphone delivers after the clips before them. Any thread. */
    fun play(samples: FloatArray) {
        clips.add(samples)
    }

    /** Silences the recording, or ends the silencing, as Android does when another app takes the microphone. */
    fun silence(on: Boolean) {
        silencedState.value = on
    }

    /** Drops the clips that wait to be played. Any thread. */
    fun clear() {
        clips.clear()
    }

    override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        check(Thread.currentThread() !== reader) { "start() must not be called from the microphone's thread" }
        synchronized(this) {
            check(reader == null) { "The microphone is already recording" }
            running = true
            val thread = Thread({ deliver(onFrame, onError) }, "Kestrel clip microphone")
            reader = thread
            thread.start()
        }
    }

    override fun stop() {
        check(Thread.currentThread() !== reader) { "stop() must not be called from the microphone's thread" }
        synchronized(this) {
            val thread = reader ?: return
            running = false
            thread.join()
            reader = null
            silencedState.value = false
        }
    }

    private fun deliver(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        var clip: FloatArray? = null
        var offset = 0
        var next = System.nanoTime()
        while (running) {
            val current = clip ?: clips.poll()
            val frame = FloatArray(FRAME)
            if (current != null) {
                val count = minOf(FRAME, current.size - offset)
                current.copyInto(frame, 0, offset, offset + count)
                offset += count
                if (offset >= current.size) {
                    clip = null
                    offset = 0
                } else {
                    clip = current
                }
            }
            try {
                onFrame(frame)
            } catch (e: Exception) {
                onError(e)
                return
            }
            // A slow frame is made up by the next ones, as a real microphone's buffer would.
            next += FRAME_NANOS
            while (running) {
                val wait = next - System.nanoTime()
                if (wait <= 0) break
                LockSupport.parkNanos(wait)
            }
        }
    }

    companion object {
        private const val FRAME = SAMPLE_RATE / 50
        private const val FRAME_NANOS = 20_000_000L

        /**
         * [text] in [voice], at 16 kHz, with 0.5 s of silence before it and 1 s after it, as the microphone hears an
         * utterance.
         */
        fun speech(voice: Synthesizer, text: String): FloatArray =
            FloatArray(SAMPLE_RATE / 2) + resample(voice.synthesize(text), voice.sampleRate) + FloatArray(SAMPLE_RATE)

        // Linear interpolation is enough for test speech.
        private fun resample(samples: FloatArray, from: Int): FloatArray {
            if (samples.isEmpty()) return samples
            val count = (samples.size.toLong() * SAMPLE_RATE / from).toInt()
            return FloatArray(count) { index ->
                val position = index.toDouble() * from / SAMPLE_RATE
                val before = position.toInt()
                val after = minOf(before + 1, samples.size - 1)
                val fraction = (position - before).toFloat()
                samples[before] + (samples[after] - samples[before]) * fraction
            }
        }
    }
}
```

- [ ] **Step 6: Say in the Orchestrator's KDoc what produces `Speaking`**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
     * `ListeningPipeline.accept`. An utterance that ends without a transcript while recognition is on should restart the
     * 8 s wait for a task; plan 2C adds the event for it.
```

with

```kotlin
     * `ListeningPipeline.accept`; the pipeline's `VoiceEvent.SpeechStarted` follows this rule. An utterance that ends
     * without a transcript while recognition is on restarts the 8 s wait for a task: `VoiceSession` sends
     * `Heard("", named = false)` for it.
```

- [ ] **Step 7: Run the compiler to make sure the test compiles**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 9: Run the device tests**

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 41 run, 0 failed, 0 errors, 0 skipped.", including the 2 tests of `VoiceSessionTest`. If `theSessionTurnsSpeechIntoTheOrchestratorsEvents` finds no `Speaking` before the transcript, `SpeechStarted` did not fire on the real detector: check that the detector's `hearsSpeech` turns true about 0.3 s into the speech (plan 2A's `SpeechDetectorTest`). If the second test finds an error from the microphone, an engine closed before the microphone's thread stopped.

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/Session.kt app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt app/src/debug/kotlin/dev/kestrel/app/debug/ClipMicrophone.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionTest.kt
git commit -m "feat(app): the voice session that owns the engines, the microphone, the pipeline and the player" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The listening controller

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `Session`, `SessionListener`, `VoiceSessions` (Task 2); `Microphone` (Task 1); `Orchestrator(scope, perform, onError, waitMillis, answerMillis)` with `state: StateFlow<State>` and `send(event)`, `State`, `Event`, `Effect`, `Reply`, `Feedback`, `Mode` (plan 2B); `Spoke` (plan 2B); `ModelsNotReadyException` (plan 2A).
- Produces: in package `dev.kestrel.app.listen`: `internal interface ServiceControl { fun start(); fun stop() }`; `internal fun interface FeedbackBar { fun show(text: String) }`; `internal object ListeningTexts` with the constants `YES`, `NO_TASKS`, `LISTENING`, `PAUSED`, `OFF`, `MIC_BUSY`, `CANNOT_START`, `MODELS_MISSING`, `MICROPHONE_FAILED`; `class Listening internal constructor(scope: CoroutineScope, thread: CoroutineDispatcher, sessions: VoiceSessions, services: ServiceControl, bar: FeedbackBar, report: (String, Throwable) -> Unit, microphones: () -> Microphone)` with `val state: StateFlow<State>`, `fun send(event: Event)`, `fun onForeground(instance: Any)`, `fun onForegroundFailed(error: Exception)`, `fun onServiceDestroyed(instance: Any)`, `internal var microphones: () -> Microphone` and `internal val sessionOpen: StateFlow<Boolean>`. Task 4 builds it in `AppGraph` and calls the three service callbacks from `ListeningService`; Task 5's hooks use `send`, `state`, `sessionOpen` and `microphones`.

`Listening` owns the Orchestrator. Its loop, `perform` and the following of the state run on `thread`, one at a time, so its own fields need no lock. When the mode leaves Off and no service runs, it starts the service through `ServiceControl`; only when the service reports `onForeground` does it open a session. While a session is open, it sets `recognition` before it starts the microphone and on every change, and it runs the microphone exactly while `State.microphone` is true. `Effect.StopService` closes the session and then stops the service, but never a service that it asked to start and that has not reported back yet: Android would end Kestrel's process (see "Threads and the shutdown order"). Such a service reports to `onForeground` later, and the controller stops it then. Opening, closing and starting the service take one `Mutex`, so that a start waits for a shutdown in progress.

The effects: `Say` speaks "Yes?" or the reply's text, and registers `invokeOnCompletion` as its last step; the completion sends `SpeechEnded`, and when the reply was refused or failed, the text goes to the feedback bar (section 14.6). `Say` throws only when no session is open, after it put the text on the bar, so the Orchestrator's compensation ends the reply. `StopSaying` stops the player. Until sub-project 5, `RunTask(n, …)` answers at once with `TaskEnded(n, "I can't run tasks yet.")`, or Kestrel would wait for the task for ever. `CancelTask` and `RunCommand` do nothing yet. `Show` puts a text on the feedback bar.

Failures that the Orchestrator has no event for become `TurnOff` with a reason: a service that cannot start or cannot enter the foreground, missing models, a session that cannot open, and a microphone that does not start or fails. The reason takes the place of "Off" on the bar, because the bar shows one text at a time and "Off" would replace the reason at once (section 14.6); when Kestrel is already off, the reason shows on its own. An `Error` while the session opens, such as an `UnsatisfiedLinkError` from sherpa-onnx, also turns Kestrel off before it goes on to AppGraph's handler, so that Kestrel never looks as if it listened. A microphone that fails reports on its own thread, so the controller stops it on its thread. When Android destroys the service on its own, the controller sends `TurnOff`; it recognises its own stop by the service's instance, so a stop that it asked for is never taken for Android's. A session's listener goes quiet when the session closes, so a late event of a closed session cannot reach the next one. `report` gets each failure with a fixed description of what failed; Task 4's implementation logs only the error's classes and stack frames.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningTest {
    private val log = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private val reports = mutableListOf<Pair<String, Throwable>>()
    private val sessions = FakeSessions()
    private val services = FakeServices()
    private val microphone = FakeMicrophone()
    private val service = Any()

    @Test
    fun theServiceStartsWhenTheModeLeavesOffAndTheSessionOpensOnlyInTheForeground() = runTest {
        val listening = listening()

        listening.send(Event.Toggle)
        runCurrent()
        assertEquals(listOf("start"), log)
        assertFalse(listening.sessionOpen.value)

        listening.onForeground(service)
        runCurrent()

        // Recognition is set before the microphone's first frame.
        assertEquals(listOf("start", "open", "recognition ALL", "microphone on"), log)
        assertSame(microphone, sessions.microphones.single())
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun theMicrophoneAndRecognitionFollowTheState() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Screen(on = false))
        runCurrent()
        listening.send(Event.Screen(on = true))
        runCurrent()
        listening.send(Event.Pause)
        runCurrent()

        assertEquals(listOf("microphone off", "microphone on", "recognition NAMED"), log)
    }

    @Test
    fun kestrelSaysYesAndTheEndOfTheReplyTurnsRecognitionOnAgain() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        log.clear()

        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        assertEquals(listOf(ListeningTexts.YES), session.said)
        assertTrue(listening.state.value.speaking)

        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()

        assertFalse(listening.state.value.speaking)
        assertEquals(Work.AWAITING_TASK, listening.state.value.work)
        assertEquals(listOf("recognition NONE", "recognition ALL"), log)
        // A reply that was spoken is not shown.
        assertEquals(listOf(ListeningTexts.LISTENING, "Kestrel"), shown)
    }

    @Test
    fun aStopWhileKestrelSpeaksStopsTheReply() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        log.clear()

        listening.send(Event.Spotted(Keyword.STOP))
        runCurrent()

        assertEquals(listOf("stop speaking"), log)
    }

    @Test
    fun aTaskGetsTheStubReplyAtOnce() = runTest {
        val listening = listeningWithSession()

        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(listOf(ListeningTexts.NO_TASKS), sessions.opened.single().said)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(1, listening.state.value.task)
    }

    @Test
    fun aReplyThatCannotBeSpokenIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        shown.clear()

        session.replies[0].complete(Spoke.REFUSED)
        runCurrent()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        session.replies[1].completeExceptionally(IllegalStateException("The voice failed"))
        runCurrent()

        assertEquals(listOf(ListeningTexts.NO_TASKS, "Kestrel", ListeningTexts.YES), shown)
        assertEquals("A reply", reports.single().first)
        assertFalse(listening.state.value.speaking)
    }

    @Test
    fun aReplyWithoutASessionIsEndedByTheOrchestrator() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        // The service has not reported the foreground yet, so no session is open.
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()

        assertEquals("An effect", reports.single().first)
        assertFalse(listening.state.value.speaking)
        assertEquals(Work.AWAITING_TASK, listening.state.value.work)
        // Section 14.6: without a voice, the reply's text goes to the bar.
        assertEquals(ListeningTexts.YES, shown.last())
    }

    @Test
    fun theFeedbackBarShowsModesTranscriptsAndMicBusy() = runTest {
        val listening = listeningWithSession()

        listening.send(Event.Heard("Open the settings.", named = false))
        listening.send(Event.MicBusy(true))
        listening.send(Event.Toggle)
        listening.send(Event.TurnOff)
        runCurrent()

        assertEquals(
            listOf(ListeningTexts.LISTENING, "Open the settings.", ListeningTexts.MIC_BUSY, ListeningTexts.PAUSED, ListeningTexts.OFF),
            shown,
        )
    }

    @Test
    fun offClosesTheSessionAndThenStopsTheService() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()

        // Closing the session stops its microphone first; the service stops only after the close.
        assertEquals(listOf("close", "closed", "stop"), log)
        assertFalse(listening.sessionOpen.value)
        assertEquals(Mode.OFF, listening.state.value.mode)
    }

    @Test
    fun aServiceThatCannotStartTurnsKestrelOffAndSaysWhy() = runTest {
        services.failure = IllegalStateException("Not allowed in the background")
        val listening = listening()

        listening.send(Event.Toggle)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("start", "stop"), log)
        // The reason takes the place of "Off", which would replace it at once.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.CANNOT_START), shown)
        assertEquals("Starting the listening service", reports.single().first)
    }

    @Test
    fun aServiceThatCannotEnterTheForegroundTurnsKestrelOffAndSaysWhy() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForegroundFailed(SecurityException("No microphone permission"))
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("start", "stop"), log)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.CANNOT_START), shown)
        assertTrue(reports.single().second is SecurityException)
    }

    @Test
    fun missingModelsTurnKestrelOffAndSaySo() = runTest {
        sessions.failure = ModelsNotReadyException(Engine.ASR, emptyList())
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("start", "open", "stop"), log)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MODELS_MISSING), shown)
        assertFalse(listening.sessionOpen.value)
    }

    @Test
    fun aNewStartWaitsForTheCloseInProgress() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        assertEquals(listOf("close"), log)

        closing.complete(Unit)
        runCurrent()
        assertEquals(listOf("close", "closed", "stop", "start"), log)

        listening.onForeground(Any())
        runCurrent()
        assertEquals(2, sessions.opened.size)
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun aSessionThatFinishesOpeningAfterOffIsClosedAtOnce() = runTest {
        val opening = CompletableDeferred<Unit>()
        sessions.opening = opening
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        listening.send(Event.TurnOff)
        runCurrent()
        opening.complete(Unit)
        runCurrent()

        assertEquals(listOf("start", "open", "close", "closed", "stop"), log)
        assertFalse(listening.sessionOpen.value)
    }

    @Test
    fun anUnexpectedEndOfTheServiceTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("close", "closed", "stop"), log)
    }

    @Test
    fun theEndOfAServiceThatKestrelStoppedChangesNothing() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()

        // Android destroys the old service only now, while the new one starts.
        listening.onServiceDestroyed(service)
        runCurrent()
        listening.onForeground(Any())
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(2, sessions.opened.size)
    }

    @Test
    fun aMicrophoneThatFailsTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        log.clear()

        sessions.opened.single().listener.microphoneFailed(IllegalStateException("The microphone failed with error -3"))
        runCurrent()

        assertEquals(listOf("microphone off", "close", "closed", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.MICROPHONE_FAILED, shown.last())
        assertEquals("The microphone", reports.single().first)
    }

    @Test
    fun aMicrophoneThatDoesNotStartIsReleasedAndTurnsKestrelOff() = runTest {
        sessions.microphoneFailure = SecurityException("Kestrel may not record audio")
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(
            listOf("start", "open", "recognition ALL", "microphone on", "microphone off", "close", "closed", "stop"),
            log,
        )
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Starting the microphone", reports.single().first)
    }

    @Test
    fun eventsFromAClosedSessionAreIgnored() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()

        old.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(0, listening.state.value.task)
    }

    @Test
    fun aServiceThatNobodyAskedForIsStopped() = runTest {
        val listening = listening()

        listening.onForeground(service)
        runCurrent()

        assertEquals(listOf("stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
    }

    @Test
    fun aServiceThatIsStillStartingIsStoppedOnlyOnceItReports() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        // Turned off before the service called startForeground(): stopping it now would end Kestrel's process.
        listening.send(Event.TurnOff)
        runCurrent()
        assertEquals(listOf("start"), log)

        listening.onForeground(service)
        runCurrent()
        assertEquals(listOf("start", "stop"), log)
    }

    private fun TestScope.listening(): Listening = Listening(
        scope = backgroundScope,
        thread = StandardTestDispatcher(testScheduler),
        sessions = sessions,
        services = services,
        bar = { shown += it },
        report = { what, error -> reports += what to error },
        microphones = { microphone },
    )

    /** Turns listening on, and lets the service report the foreground, so that a session opens. */
    private fun TestScope.listeningWithSession(): Listening {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()
        return listening
    }

    private inner class FakeSessions : VoiceSessions {
        val opened = mutableListOf<FakeSession>()
        val microphones = mutableListOf<Microphone>()
        var failure: Exception? = null
        var opening: CompletableDeferred<Unit>? = null
        var microphoneFailure: Exception? = null

        override suspend fun open(microphone: Microphone, listener: SessionListener): Session {
            log += "open"
            microphones += microphone
            opening?.await()
            failure?.let { throw it }
            return FakeSession(listener, microphoneFailure).also { opened += it }
        }
    }

    private inner class FakeSession(val listener: SessionListener, private val microphoneFailure: Exception?) : Session {
        val said = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<Spoke>>()
        var closing: CompletableDeferred<Unit>? = null
        private var current: Recognition? = null

        // Logged only when it changes, so that the log shows what the pipeline would see.
        override var recognition: Recognition
            get() = current ?: Recognition.ALL
            set(value) {
                if (value != current) log += "recognition $value"
                current = value
            }

        override fun startMicrophone() {
            log += "microphone on"
            microphoneFailure?.let { throw it }
        }

        override fun stopMicrophone() {
            log += "microphone off"
        }

        override fun speak(text: String): Deferred<Spoke> {
            said += text
            return CompletableDeferred<Spoke>().also { replies += it }
        }

        override fun stopSpeaking() {
            log += "stop speaking"
        }

        override suspend fun close() {
            log += "close"
            closing?.await()
            log += "closed"
        }
    }

    private inner class FakeServices : ServiceControl {
        var failure: Exception? = null

        override fun start() {
            log += "start"
            failure?.let { throw it }
        }

        override fun stop() {
            log += "stop"
        }
    }

    private class FakeMicrophone : Microphone {
        override val silenced: StateFlow<Boolean> = MutableStateFlow(false)
        override val echoCancelling = false

        override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) = Unit

        override fun stop() = Unit
    }
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'Listening'", "Unresolved reference 'ListeningTexts'" and "Unresolved reference 'ServiceControl'".

- [ ] **Step 3: Write the controller**

Create `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Orchestrator
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.State
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Starts and stops ListeningService. Any thread may call it. */
internal interface ServiceControl {
    /** Asks Android to start the service in the foreground. Throws when Android does not allow the start. */
    fun start()

    /**
     * Asks the service to stop itself. It stops unless a newer start, such as a notification action, is still on its way
     * to it; that start reports to [Listening.onForeground]. Nothing happens when the service does not run.
     */
    fun stop()
}

/** Shows a text on the feedback bar (design spec, section 8.9), or nothing while the accessibility service is off. */
internal fun interface FeedbackBar {
    fun show(text: String)
}

/** What Kestrel says and shows while it listens. Plan 2C-2 decides the final wording. */
internal object ListeningTexts {
    const val YES = "Yes?"
    const val NO_TASKS = "I can't run tasks yet."
    const val LISTENING = "Listening"
    const val PAUSED = "Paused"
    const val OFF = "Off"
    const val MIC_BUSY = "Microphone busy"
    const val CANNOT_START = "Kestrel could not start listening. Start it from the notification or the tile."
    const val MODELS_MISSING = "The speech models are missing; see the setup checklist."
    const val MICROPHONE_FAILED = "The microphone failed, so Kestrel stopped listening."
}

/**
 * Kestrel's listening, one for the whole process (design spec, sections 7.2, 7.3 and 8.5). It lives in AppGraph, so that
 * the accessibility service, the notification and later the tile can send it events whether or not ListeningService
 * runs. It owns the [Orchestrator] and follows its state: when the mode leaves Off, it starts ListeningService; once the
 * service runs in the foreground, it opens a voice session; while the session is open, the microphone runs exactly while
 * [State.microphone] is true, and the pipeline's recognition is [State.recognition] before the first frame and after
 * every change. [Effect.StopService] closes the session and then stops the service.
 *
 * Threads: the Orchestrator's loop, its effects, the following of the state, and the microphone's start and stop run on
 * [thread], one at a time. A session opens and closes in a coroutine of [scope] behind one lock, so that a new start
 * waits until a shutdown has finished: two recognisers would need about 1.4 GB. The public functions may be called from
 * any thread. [report] gets each failure with a fixed description of what failed, and must log no message.
 */
class Listening internal constructor(
    private val scope: CoroutineScope,
    private val thread: CoroutineDispatcher,
    private val sessions: VoiceSessions,
    private val services: ServiceControl,
    private val bar: FeedbackBar,
    private val report: (String, Throwable) -> Unit,
    microphones: () -> Microphone,
) {
    private enum class ServiceState { STOPPED, STARTING, FOREGROUND }

    /** Makes the microphone of the next session. The device tests and the debug hooks replace it. */
    @Volatile
    internal var microphones: () -> Microphone = microphones

    // Only [thread] uses these. [requested]: the service was asked to start since the last stop. [running]: the
    // service's instance that runs in the foreground and that Kestrel has not asked to stop.
    private var serviceState = ServiceState.STOPPED
    private var requested = false
    private var running: Any? = null

    // Android was asked to start the service, and the service has not reported back yet. Stopping such a service makes
    // Android end Kestrel's process.
    private var awaitingForeground = false

    // Why Kestrel turned itself off. The next "Off" on the bar shows it instead (section 14.6).
    private var offReason: String? = null
    private var session: Session? = null
    private var listener: Listener? = null
    private var microphoneOn = false
    private val lifecycle = Mutex()
    private val sessionState = MutableStateFlow(false)

    /** Whether a voice session is open: from the end of its opening until its close has returned. */
    internal val sessionOpen: StateFlow<Boolean> = sessionState.asStateFlow()

    private val orchestrator = Orchestrator(
        CoroutineScope(scope.coroutineContext + thread),
        perform = ::perform,
        onError = { report("An effect", it) },
    )

    /** The Orchestrator's state after the last event. */
    val state: StateFlow<State> get() = orchestrator.state

    init {
        scope.launch(thread) { orchestrator.state.collect { follow(it) } }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = orchestrator.send(event)

    /** ListeningService, whose instance is [instance], runs in the foreground. */
    fun onForeground(instance: Any) {
        scope.launch(thread) { foreground(instance) }
    }

    /** ListeningService could not enter the foreground; [error] says why. */
    fun onForegroundFailed(error: Exception) {
        scope.launch(thread) {
            // The service stops itself after a failed startForeground(), which Android allows.
            awaitingForeground = false
            if (serviceState == ServiceState.STARTING) {
                turnOff("Starting the listening service", error, ListeningTexts.CANNOT_START)
            }
        }
    }

    /** Android destroyed the ListeningService instance [instance], whether Kestrel asked for it or not. */
    fun onServiceDestroyed(instance: Any) {
        scope.launch(thread) { destroyed(instance) }
    }

    private fun follow(state: State) {
        if (state.mode != Mode.OFF && serviceState == ServiceState.STOPPED) start()
        val current = session ?: return
        current.recognition = state.recognition
        if (state.microphone && !microphoneOn) {
            try {
                current.startMicrophone()
                microphoneOn = true
            } catch (e: Exception) {
                // A start that failed after the microphone opened leaves it open until a stop.
                releaseMicrophone(current)
                turnOff("Starting the microphone", e, ListeningTexts.MICROPHONE_FAILED)
            }
        } else if (!state.microphone && microphoneOn) {
            microphoneOn = false
            releaseMicrophone(current)
        }
    }

    private fun start() {
        serviceState = ServiceState.STARTING
        // A reason from an earlier start must never come back.
        offReason = null
        scope.launch(thread) {
            // After a shutdown in progress, so that its stop cannot end the new service.
            lifecycle.withLock {
                if (serviceState != ServiceState.STARTING || requested) return@withLock
                requested = true
                try {
                    services.start()
                    awaitingForeground = true
                } catch (e: Exception) {
                    turnOff("Starting the listening service", e, ListeningTexts.CANNOT_START)
                }
            }
        }
    }

    private fun foreground(instance: Any) {
        awaitingForeground = false
        when (serviceState) {
            // Before the start was asked for, the instance is the one that a shutdown in progress is about to stop.
            ServiceState.STARTING -> if (requested) {
                serviceState = ServiceState.FOREGROUND
                running = instance
                open()
            }
            // A service that nobody asked for, such as one that a stale notification started while Kestrel was off.
            ServiceState.STOPPED -> scope.launch(thread) {
                lifecycle.withLock { if (serviceState == ServiceState.STOPPED) stopService() }
            }
            // The running service handled a notification action.
            ServiceState.FOREGROUND -> Unit
        }
    }

    private fun destroyed(instance: Any) {
        if (instance !== running) return
        // Android ended the service on its own, so Kestrel no longer listens, and the Orchestrator must know it.
        running = null
        send(Event.TurnOff)
    }

    private fun open() {
        scope.launch(thread) {
            lifecycle.withLock {
                if (serviceState != ServiceState.FOREGROUND || session != null) return@withLock
                val events = Listener()
                val opened = try {
                    sessions.open(microphones(), events)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelsNotReadyException) {
                    turnOff("Opening the voice session", e, ListeningTexts.MODELS_MISSING)
                    return@withLock
                } catch (e: Exception) {
                    turnOff("Opening the voice session", e, ListeningTexts.CANNOT_START)
                    return@withLock
                } catch (e: Error) {
                    // Kestrel must not look as if it listened. AppGraph's handler still logs the error's classes.
                    send(Event.TurnOff)
                    throw e
                }
                if (serviceState != ServiceState.FOREGROUND) {
                    // Kestrel was turned off while the session opened.
                    events.active = false
                    closeQuietly(opened)
                    return@withLock
                }
                session = opened
                listener = events
                sessionState.value = true
                follow(state.value)
            }
        }
    }

    private fun shutdown() {
        val closing = session
        // A service that has not called startForeground() yet reports to foreground(), which stops it then.
        val stop = !awaitingForeground
        // A late event of the closing session, such as a transcript, must not reach the state of a later session.
        listener?.active = false
        session = null
        listener = null
        microphoneOn = false
        serviceState = ServiceState.STOPPED
        requested = false
        running = null
        scope.launch(thread) {
            lifecycle.withLock {
                // The session first, so that Kestrel plays audio only while the service runs (B5).
                if (closing != null) closeQuietly(closing)
                if (stop) stopService()
            }
        }
    }

    private suspend fun closeQuietly(closing: Session) {
        try {
            closing.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Closing the voice session", e)
        } finally {
            sessionState.value = false
        }
    }

    private fun stopService() {
        try {
            services.stop()
        } catch (e: Exception) {
            report("Stopping the listening service", e)
        }
    }

    private fun releaseMicrophone(session: Session) {
        try {
            session.stopMicrophone()
        } catch (e: Exception) {
            report("Stopping the microphone", e)
        }
    }

    /** Section 14: Kestrel never fails silently. It turns off and shows why, in place of "Off". */
    private fun turnOff(what: String, error: Exception, text: String) {
        report(what, error)
        if (state.value.mode == Mode.OFF) {
            bar.show(text)
        } else {
            offReason = text
            send(Event.TurnOff)
        }
    }

    private fun perform(effect: Effect) {
        when (effect) {
            is Effect.Say -> say(effect.reply)
            Effect.StopSaying -> session?.stopSpeaking()
            // Sub-project 5 runs tasks. Until then every task ends at once, or Kestrel would wait for it for ever.
            is Effect.RunTask -> send(Event.TaskEnded(effect.task, ListeningTexts.NO_TASKS))
            // Nothing runs that could be cancelled yet, and the instant commands come with sub-project 3.
            Effect.CancelTask, is Effect.RunCommand -> Unit
            is Effect.Show -> bar.show(text(effect.feedback))
            // The Orchestrator runs the wait itself.
            is Effect.WaitForTask -> Unit
            Effect.StopService -> shutdown()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun say(reply: Reply) {
        val text = when (reply) {
            Reply.Yes -> ListeningTexts.YES
            is Reply.Text -> reply.text
        }
        val current = session
        if (current == null) {
            // Section 14.6: without a voice the text goes to the bar. Thrown before the reply starts, so that the
            // Orchestrator ends the reply in its place.
            bar.show(text)
            error("No voice session")
        }
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                // Section 14.6: when the voice fails, Kestrel shows the text instead.
                bar.show(text)
            } else if (error == null && spoken.getCompleted() == Spoke.REFUSED) {
                bar.show(text)
            }
            send(Event.SpeechEnded)
        }
    }

    private fun text(feedback: Feedback): String = when (feedback) {
        is Feedback.ModeChanged -> when (feedback.mode) {
            Mode.LISTENING -> ListeningTexts.LISTENING
            Mode.PAUSED -> ListeningTexts.PAUSED
            Mode.OFF -> (offReason ?: ListeningTexts.OFF).also { offReason = null }
        }
        is Feedback.Heard -> feedback.text
        Feedback.MicBusy -> ListeningTexts.MIC_BUSY
    }

    /** Where one session reports. It goes quiet when the session closes. */
    private inner class Listener : SessionListener {
        @Volatile
        var active = true

        override fun send(event: Event) {
            if (active) orchestrator.send(event)
        }

        override fun microphoneFailed(error: Exception) {
            // The microphone's own thread cannot stop the microphone, so the controller's thread does.
            scope.launch(thread) {
                val current = session
                if (!active || current == null) return@launch
                if (microphoneOn) {
                    microphoneOn = false
                    releaseMicrophone(current)
                }
                turnOff("The microphone", error, ListeningTexts.MICROPHONE_FAILED)
            }
        }

        override fun transcriptionFailed(error: Exception) {
            if (active) report("A transcription", error)
        }
    }
}
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 21 tests in `ListeningTest`.

- [ ] **Step 5: Run the module's JVM tests and lint**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`: 86 JVM tests in `:app` (65 before and the 21 new ones), and no lint issue.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt
git commit -m "feat(app): the listening controller that owns the Orchestrator and follows its state" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: ListeningService, its notification, and the screen and the lock

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (whole file below)
- Modify: `app/src/main/res/values/strings.xml` (the end of the file)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, and `listening` after `voice`)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt` (whole file below)
- Modify: `scripts/voice-test.ps1` (the header comment, the emulator-only tests, and putting the lock screen back)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`

**Interfaces:**
- Consumes: `Listening`, `ServiceControl`, `FeedbackBar` (Task 3); `VoiceSession.open`, `VoiceSessions`, `Session`, `SessionListener` (Task 2); `ClipMicrophone` (Task 2); `MicCapture`, `Microphone` (Task 1); `AppGraph.scope`, `AppGraph.voice`, `AppGraph.screen`, `ScreenSession.showFeedback(text)`, `describeWithoutMessagesByCause()` (plans 1A-2A); `Event.Screen`, `Event.Pause`, `Event.Resume`, `Event.TurnOff`, `Mode` (plan 2B).
- Produces: `class ListeningService : Service()` in `dev.kestrel.app.listen`, with `internal const val NOTIFICATION_ID = 7_101`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_STOP`, `internal fun actionIntent(context: Context, action: String): PendingIntent` and `internal fun control(context: Context): ServiceControl` in its companion (`control(context).stop()` posts to the main thread, where the running service calls `stopSelfResult` for the last start that it handled); `AppGraph.listening: Listening`; the strings `listening_channel`, `listening_title_listening`, `listening_title_paused`, `listening_pause`, `listening_resume`, `listening_stop`; in `VoiceTests`: `shell(command: String): String`, `waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean`, `grantListeningPermissions()`, `listeningServiceRuns(): Boolean`, `useMicrophone(microphone: Microphone)`, `startListening(microphone: ClipMicrophone)` and `stopListening()`. Tasks 5 and 6 use the `VoiceTests` helpers.

`ListeningService` keeps Kestrel listening: Android lets an app record and play audio in the background only while such a service runs (B5; prototype P1 found that without it, Kestrel loses the audio capability once the screen is off). `onStartCommand` calls `startForeground` at once with the notification and both types, after every start, because Android requires it after every `startForegroundService`. It then reports to `Listening` and turns a notification action into `Pause`, `Resume` or `TurnOff`. The notification (channel "Listening", low importance) is titled by the mode, offers Pause or Resume and Stop as `PendingIntent.getForegroundService` intents to the service, opens `MainActivity` when tapped, and follows `listening.state`. While the service runs, a receiver registered at runtime for `ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT` sends `Event.Screen(on)` with `on = isInteractive && !isKeyguardLocked`, and with `on = isInteractive` for `ACTION_USER_PRESENT`, which itself says that the owner unlocked the phone, so that a keyguard state that reaches `KeyguardManager` a moment later cannot keep the microphone off. The receiver is exported: SCREEN_ON and SCREEN_OFF come from the system, but USER_PRESENT comes from SystemUI, an ordinary app uid, and Android gives another app's broadcasts only to an exported receiver; all three are protected broadcasts, which no other app can send (review 1 checked this on the emulator). The service sends the real value once in `onCreate`, before the session opens. `onDestroy` tells `Listening`, which sends `TurnOff` when it did not ask for the stop.

The service stops itself: `control().stop()` posts to the main thread, where the running instance calls `stopSelfResult(lastStartId)` with the id of the last start that it handled. Android stops it only when no newer start is queued, because bringing down a service whose `startForegroundService()` has not reached `startForeground()` yet ends Kestrel's process; a queued start, such as a notification action that was tapped just before, reports to `Listening`, whose stop for a service that nobody asked for then stops it again. A failed `startForeground()` stops the service the same way, with its own start id. The running instance sits in a companion field that only the main thread touches and that `onDestroy` clears, so lint's `StaticFieldLeak` is suppressed there alone.

`AppGraph.listening` wires the controller: its thread is `Dispatchers.Default.limitedParallelism(1)` in `AppGraph.scope`; the feedback bar shows each text through `screen.value?.showFeedback` in its own coroutine on `Dispatchers.Main`, so that the texts reach the bar in the order in which they were asked for, and nothing shows while the accessibility service is off; `report` logs only the error's classes and stack frames with `describeWithoutMessagesByCause()`, as `AppGraph.errors` does.

The device test runs through `scripts\voice-test.ps1`, because the service opens a session with the real models. It grants the microphone and notification permissions, as `SetupStatusReaderTest` and `MicCaptureTest` do, and never revokes them, because revoking a permission ends Kestrel's process and the test with it. It finds the notification with `NotificationManager.activeNotifications` and sends its actions' intents, as a tap would, while `MainActivity` is in front, so that Android lets Kestrel start the service. It reads the service's foreground types with `dumpsys activity services dev.kestrel`, which the prototype showed prints `isForeground=true foregroundId=<id> types=0x00000082` on this image. The screen test goes through a real lock screen: kestrel-api37 has its lock screen turned off, so the test turns the swipe lock screen on (`cmd lock_settings set-disabled false`) when `cmd lock_settings get-disabled` prints `true`, switches the screen off with `input keyevent KEYCODE_SLEEP`, wakes it with `KEYCODE_WAKEUP`, checks that Kestrel does not record on the lock screen, unlocks with `wm dismiss-keyguard`, and in a `finally` dismisses the keyguard and turns the lock screen off again. Its `@After` wakes the screen in any case. `wm dismiss-keyguard` cannot unlock a PIN, pattern or password, so `voice-test.ps1` leaves this test out on phones, with the same `notClass` argument as the privacy-toggle test (`notClass` takes a comma-separated list), and fails a run in which it ran on a phone or did not run on an emulator.

- [ ] **Step 1: Add the shared helpers of the listening tests**

Replace the whole of `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt` with:

```kotlin
package dev.kestrel.app.voice

import android.Manifest
import android.app.UiAutomation
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * What the voice tests share: the app's own engines, and synthetic speech in Kestrel's voice, which stands in for the
 * owner's voice until plan 2C adds recordings (design spec, section 16.4). The tests skip themselves when the models are
 * missing; scripts\voice-test.ps1 copies the models first and fails on a skipped test.
 */
internal object VoiceTests {
    val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    /** The microphone delivers 20 ms frames (design spec, section 8.1). */
    const val FRAME = SAMPLE_RATE / 50

    private const val PACKAGE = "dev.kestrel"

    // How dumpsys names ListeningService.
    private const val LISTENING_SERVICE = "dev.kestrel/.app.listen.ListeningService"

    // Kept open for the whole test run, because loading the voice takes more than a second.
    private var synthesizer: Synthesizer? = null
    private val spoken = mutableMapOf<String, FloatArray>()

    // Kestrel's own microphone, which [stopListening] puts back after a test replaced it.
    private var realMicrophones: (() -> Microphone)? = null

    private val automation: UiAutomation
        get() = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /** Skips the test unless every model of [engines] is on the device and intact. */
    fun assumeModels(vararg engines: Engine) {
        val notReady = runBlocking { graph.models.check() }.filter { it.model.engine in engines && !it.ready }
        assumeTrue("Models missing or damaged: ${notReady.map { it.model.id }}. Run scripts\\voice-test.ps1.", notReady.isEmpty())
    }

    /** [text] in Kestrel's voice without variation, so that it is the same in every run, at 16 kHz. */
    @Synchronized
    fun voice(text: String): FloatArray = spoken.getOrPut(text) {
        val active = synthesizer ?: runBlocking { graph.voice.synthesizer(VoiceOptions(variation = 0f)) }.also { synthesizer = it }
        resample(active.synthesize(text), active.sampleRate, SAMPLE_RATE)
    }

    /** [voice] with 0.5 s of silence before it and 1 s after it, as the microphone hears an utterance. */
    fun speech(text: String): FloatArray = silence(0.5f) + voice(text) + silence(1f)

    fun silence(seconds: Float) = FloatArray((seconds * SAMPLE_RATE).toInt())

    /** Feeds [samples] to [feed] in 20 ms frames, as the microphone delivers them, and collects what it returns. */
    fun <T> frames(samples: FloatArray, feed: (FloatArray) -> List<T>): List<T> =
        (samples.indices step FRAME).flatMap { start -> feed(samples.copyOfRange(start, minOf(start + FRAME, samples.size))) }

    /** Logs a measurement; scripts\voice-test.ps1 prints these lines after the tests. Never log what was said. */
    fun log(message: String) {
        Log.i("KestrelVoiceTest", message)
    }

    /** The memory that the app's process uses (PSS), in MB, as the design spec's memory target counts it (section 5.3). */
    fun pssMegabytes(): Long = Debug.getPss() / 1024

    /** Runs [command] in the device's shell and returns its output. The command is split at spaces; quotes do not group. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /** Waits up to [timeoutMillis] until [condition] is true, and returns whether it became true. */
    fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(20)
        }
        return true
    }

    /**
     * Grants what listening needs: the microphone, without which Android refuses a foreground service of the type
     * microphone, and notifications, so that a test can read the service's notification. Like the other device tests,
     * the tests never revoke a permission, because that would end Kestrel's process and the tests with it.
     */
    fun grantListeningPermissions() {
        automation.grantRuntimePermission(PACKAGE, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(PACKAGE, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Whether Kestrel's ListeningService runs. */
    fun listeningServiceRuns(): Boolean = shell("dumpsys activity services $PACKAGE").contains(LISTENING_SERVICE)

    /** Makes [microphone] the microphone of Kestrel's next voice session, until [stopListening]. */
    fun useMicrophone(microphone: Microphone) {
        val listening = graph.listening
        if (realMicrophones == null) realMicrophones = listening.microphones
        listening.microphones = { microphone }
    }

    /**
     * Turns listening on with [microphone] as Kestrel's microphone, and waits until the voice session is open and the
     * microphone records. Kestrel must be off, and in front, so that Android lets it start a foreground service of the
     * type microphone.
     */
    fun startListening(microphone: ClipMicrophone) {
        val listening = graph.listening
        check(listening.state.value.mode == Mode.OFF) { "Listening is already on" }
        useMicrophone(microphone)
        listening.send(Event.Toggle)
        // Loading the models takes several seconds.
        check(waitUntil(60_000) { listening.sessionOpen.value }) {
            "The voice session did not open; the mode is ${listening.state.value.mode}"
        }
        check(waitUntil(5_000) { microphone.recording }) { "The microphone does not record" }
    }

    /**
     * Turns Kestrel off, and waits until its voice session has closed, with every engine, and ListeningService has
     * stopped. Puts back Kestrel's own microphone.
     */
    fun stopListening() {
        val listening = graph.listening
        listening.send(Event.TurnOff)
        try {
            check(waitUntil(30_000) { listening.state.value.mode == Mode.OFF && !listening.sessionOpen.value }) {
                "The voice session did not close"
            }
            check(waitUntil(10_000) { !listeningServiceRuns() }) { "ListeningService still runs" }
        } finally {
            realMicrophones?.let { listening.microphones = it }
            realMicrophones = null
        }
    }

    // Linear interpolation is enough for test speech; Kestrel's microphone records at 16 kHz itself.
    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        val count = (samples.size.toLong() * to / from).toInt()
        return FloatArray(count) { index ->
            val position = index.toDouble() * from / to
            val before = position.toInt()
            val after = minOf(before + 1, samples.size - 1)
            val fraction = (position - before).toFloat()
            samples[before] + (samples[after] - samples[before]) * fraction
        }
    }
}
```

What changed against plan 2B's file: the imports of `Manifest`, `UiAutomation`, `ParcelFileDescriptor`, `SystemClock`, `ClipMicrophone`, `Event`, `Mode` and `Microphone`, the constants `PACKAGE` and `LISTENING_SERVICE`, the fields `realMicrophones` and `automation`, and the functions from `shell` to `stopListening`.

- [ ] **Step 2: Write the failing device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ListeningService on the emulator (design spec, sections 7.2, 8.5 and 8.6): the foreground, the notification's actions,
 * and the screen and the lock.
 */
class ListeningServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listening = VoiceTests.graph.listening
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
    }

    @After
    fun tearDown() {
        // A test that failed while the screen was off must not leave it off for the next tests.
        VoiceTests.shell("input keyevent KEYCODE_WAKEUP")
        VoiceTests.shell("wm dismiss-keyguard")
        VoiceTests.stopListening()
    }

    @Test
    fun theServiceRunsInTheForegroundAndItsNotificationPausesResumesAndStops() {
        ActivityScenario.launch(MainActivity::class.java).use {
            VoiceTests.startListening(clip)

            val types = foregroundTypes()
            assertTrue(
                "Foreground service types 0x${types.toString(16)}",
                (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0 &&
                    (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0,
            )
            assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))

            press(R.string.listening_pause)
            awaitState { it.mode == Mode.PAUSED }
            assertEquals(texts(R.string.listening_resume, R.string.listening_stop), actions(R.string.listening_title_paused))

            press(R.string.listening_resume)
            awaitState { it.mode == Mode.LISTENING }
            assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))

            press(R.string.listening_stop)
            awaitState { it.mode == Mode.OFF }
            assertTrue("The voice session is still open", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
            assertTrue("ListeningService still runs", VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() })
        }
    }

    @Test
    fun theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock() {
        ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }
        // kestrel-api37 has no lock screen. A phone has one, and only ACTION_USER_PRESENT, which SystemUI sends, tells
        // Kestrel that the owner unlocked it. So the test turns on the swipe lock screen, and turns it off again.
        val noLockScreen = VoiceTests.shell("cmd lock_settings get-disabled").trim() == "true"
        if (noLockScreen) VoiceTests.shell("cmd lock_settings set-disabled false")
        try {
            VoiceTests.shell("input keyevent KEYCODE_SLEEP")
            awaitState { !it.screenOn && !it.microphone }
            assertTrue("The microphone still records with the screen off", VoiceTests.waitUntil(5_000) { !clip.recording })

            VoiceTests.shell("input keyevent KEYCODE_WAKEUP")
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            assertTrue("No lock screen after the wake-up", VoiceTests.waitUntil(5_000) { keyguard.isKeyguardLocked })
            Thread.sleep(1_000)
            assertFalse("Kestrel records on the lock screen", listening.state.value.screenOn || clip.recording)

            VoiceTests.shell("wm dismiss-keyguard")
            awaitState { it.screenOn && it.microphone }
            assertTrue("The microphone does not record after the unlock", VoiceTests.waitUntil(5_000) { clip.recording })
            // Section 8.5: after the unlock Kestrel resumes the mode that it had.
            assertEquals(Mode.LISTENING, listening.state.value.mode)
        } finally {
            VoiceTests.shell("wm dismiss-keyguard")
            if (noLockScreen) VoiceTests.shell("cmd lock_settings set-disabled true")
        }
    }

    private fun texts(vararg ids: Int): List<String> = ids.map { context.getString(it) }

    /** The titles of the actions of the service's notification, once its title is [title]. */
    private fun actions(title: Int): List<String> = notification(title).actions.map { it.title.toString() }

    private fun notification(title: Int): Notification {
        val expected = context.getString(title)
        var found: Notification? = null
        val shown = VoiceTests.waitUntil(5_000) {
            found = notifications.activeNotifications.firstOrNull { it.id == ListeningService.NOTIFICATION_ID }
                ?.notification
                ?.takeIf { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == expected }
            found != null
        }
        assertTrue("No notification titled '$expected'", shown)
        return found!!
    }

    /** Sends the intent of the notification's action [title], as a tap on it would. */
    private fun press(title: Int) {
        val label = context.getString(title)
        val notification = notifications.activeNotifications.single { it.id == ListeningService.NOTIFICATION_ID }.notification
        notification.actions.single { it.title.toString() == label }.actionIntent.send()
    }

    private fun awaitState(condition: (State) -> Boolean) {
        assertTrue("The state is ${listening.state.value}", VoiceTests.waitUntil(10_000) { condition(listening.state.value) })
    }

    /** ListeningService's foreground service types as Android reports them, or 0 when it is not in the foreground. */
    private fun foregroundTypes(): Int {
        val pattern = Regex("""isForeground=true foregroundId=${ListeningService.NOTIFICATION_ID} types=0x([0-9a-fA-F]+)""")
        return pattern.find(VoiceTests.shell("dumpsys activity services dev.kestrel"))?.groupValues?.get(1)?.toInt(16) ?: 0
    }
}
```

- [ ] **Step 3: Run the compiler to make sure the test fails**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with "Unresolved reference 'listening'", "Unresolved reference 'ListeningService'" and "Unresolved reference 'listening_pause'".

- [ ] **Step 4: Write the service**

Create `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`:

```kotlin
package dev.kestrel.app.listen

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Kestrel listening (design spec, sections 7.2, 8.5 and 8.6): a foreground service of the types microphone and
 * media playback, because Android lets Kestrel record and play audio in the background only while such a service runs
 * (B5). [Listening] starts and stops it, and holds the voice session while it runs. The service shows the notification
 * with Pause or Resume and Stop, and tells [Listening] whether the screen is on and the phone unlocked. It stops itself
 * when [Listening] asks, unless a newer start is still on its way to it. Android calls every callback on the main thread.
 */
class ListeningService : Service() {
    private val listening: Listening get() = (application as KestrelApplication).graph.listening
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false

    // The id of the last start that onStartCommand handled; only the main thread uses it.
    private var lastStartId = 0
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) =
            reportScreen(unlocked = intent.action == Intent.ACTION_USER_PRESENT)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.listening_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // SCREEN_ON and SCREEN_OFF come from the system, but USER_PRESENT comes from SystemUI, and Android gives another
        // app's broadcasts only to an exported receiver. All three are protected broadcasts, which other apps cannot send.
        registerReceiver(screen, filter, Context.RECEIVER_EXPORTED)
        // Before the voice session opens, and so before the microphone's first frame.
        reportScreen()
        scope.launch {
            listening.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                // Off: the service is about to stop, and its notification goes with it.
                if (foreground && mode != Mode.OFF) notifications.notify(NOTIFICATION_ID, notification(mode))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Android requires this soon after every startForegroundService(), also for a service that already runs.
        try {
            startForeground(NOTIFICATION_ID, notification(listening.state.value.mode), FOREGROUND_TYPES)
        } catch (e: Exception) {
            // For example a SecurityException without the microphone permission, or a start from the background that
            // Android does not allow (section 14.6). A newer start that is on its way keeps the service, and fails or
            // reports in turn.
            listening.onForegroundFailed(e)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        foreground = true
        listening.onForeground(this)
        when (intent?.action) {
            ACTION_PAUSE -> listening.send(Event.Pause)
            ACTION_RESUME -> listening.send(Event.Resume)
            ACTION_STOP -> listening.send(Event.TurnOff)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        scope.cancel()
        unregisterReceiver(screen)
        // After this, no update of the notification can bring it back.
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        listening.onServiceDestroyed(this)
        super.onDestroy()
    }

    /**
     * Section 8.5: Kestrel records only while the screen is on and the phone unlocked. [unlocked]: the broadcast was
     * USER_PRESENT, which says that the owner unlocked the phone, even if KeyguardManager learns it a moment later.
     */
    private fun reportScreen(unlocked: Boolean = false) {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = !unlocked && getSystemService(KeyguardManager::class.java).isKeyguardLocked
        listening.send(Event.Screen(on = interactive && !locked))
    }

    private fun notification(mode: Mode): Notification {
        val listeningNow = mode == Mode.LISTENING
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (listeningNow) R.string.listening_title_listening else R.string.listening_title_paused
        val toggle = if (listeningNow) action(R.string.listening_pause, ACTION_PAUSE) else action(R.string.listening_resume, ACTION_RESUME)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(title))
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(toggle)
            .addAction(action(R.string.listening_stop, ACTION_STOP))
            .build()
    }

    private fun action(title: Int, action: String): Notification.Action =
        Notification.Action.Builder(null, getString(title), actionIntent(this, action)).build()

    companion object {
        internal const val NOTIFICATION_ID = 7_101
        internal const val ACTION_PAUSE = "dev.kestrel.listen.PAUSE"
        internal const val ACTION_RESUME = "dev.kestrel.listen.RESUME"
        internal const val ACTION_STOP = "dev.kestrel.listen.STOP"
        private const val CHANNEL = "listening"
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        // The running service, so that it can stop itself when Listening asks. Only the main thread reads or writes it,
        // and onDestroy clears it, so it cannot keep a destroyed service alive.
        @SuppressLint("StaticFieldLeak")
        private var current: ListeningService? = null

        /** The intent of the notification's action [action]: it starts the service with that action. */
        internal fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, ListeningService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Starts and stops the service for [Listening]. */
        internal fun control(context: Context): ServiceControl = object : ServiceControl {
            private val main = Handler(Looper.getMainLooper())

            override fun start() {
                context.startForegroundService(Intent(context, ListeningService::class.java))
            }

            // stopService() would also bring down a service whose newest start has not reached startForeground() yet,
            // and Android would then end Kestrel's process. stopSelfResult() leaves such a service running, and its start
            // reports to Listening, which asks again.
            override fun stop() {
                main.post { current?.let { it.stopSelfResult(it.lastStartId) } }
            }
        }
    }
}
```

- [ ] **Step 5: Declare the service and its permissions**

Replace the whole of `app/src/main/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Asked for in the setup checklist (design spec, sections 13.6 and 15). -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <!-- Granted at install, for ListeningService (design spec, sections 7.2 and 13.6). -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <application
        android:name=".KestrelApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="Kestrel"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Reads the screen and acts on it (design spec, sections 7.2 and 9.1). -->
        <service
            android:name=".KestrelAccessibilityService"
            android:exported="true"
            android:label="Kestrel"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <!-- Keeps Kestrel listening, with the microphone and voice output (design spec, sections 7.2 and 8.5). -->
        <service
            android:name=".listen.ListeningService"
            android:exported="false"
            android:foregroundServiceType="microphone|mediaPlayback" />

        <!-- EmojiCompat would ask Google Play services for a downloadable emoji font. -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.emoji2.text.EmojiCompatInitializer"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

What changed: the three permissions with their comment, and the `ListeningService` declaration. The debug manifest's own copies of these permissions, for the spike service, stay.

- [ ] **Step 6: Add the notification's texts**

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="setup_open_app_settings">Open app settings</string>
</resources>
```

with

```xml
    <string name="setup_open_app_settings">Open app settings</string>

    <!-- The notification of the listening service (design spec, sections 8.5 and 8.6). -->
    <string name="listening_channel">Listening</string>
    <string name="listening_title_listening">Kestrel is listening</string>
    <string name="listening_title_paused">Kestrel is paused</string>
    <string name="listening_pause">Pause</string>
    <string name="listening_resume">Resume</string>
    <string name="listening_stop">Stop</string>
</resources>
```

- [ ] **Step 7: Put the controller into the app's graph**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.util.Log
import dev.kestrel.privacy.PrivacyStore
```

with

```kotlin
import android.util.Log
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.Listening
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.Session
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.privacy.PrivacyStore
```

Replace

```kotlin
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.models.ModelFiles
```

with

```kotlin
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.listen.MicCapture
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.models.ModelFiles
```

Replace

```kotlin
    /** Kestrel's speech engines, which load their models from [models] (design spec, section 8.2). */
    val voice: VoiceEngines by lazy { VoiceEngines(models, File(appContext.noBackupFilesDir, "voice")) }
```

with

```kotlin
    /** Kestrel's speech engines, which load their models from [models] (design spec, section 8.2). */
    val voice: VoiceEngines by lazy { VoiceEngines(models, File(appContext.noBackupFilesDir, "voice")) }

    /**
     * Kestrel's listening, one for the whole process (design spec, sections 7.2 and 7.3), so that the accessibility
     * service, the notification and the tile reach it whether or not ListeningService runs. Its thread runs one thing at a
     * time; failures are logged by their classes and stack frames only (section 13.5).
     */
    val listening: Listening by lazy {
        Listening(
            scope = scope,
            thread = Dispatchers.Default.limitedParallelism(1),
            sessions = object : VoiceSessions {
                override suspend fun open(microphone: Microphone, listener: SessionListener): Session =
                    VoiceSession.open(appContext, voice, microphone, listener)
            },
            services = ListeningService.control(appContext),
            // One main-thread task per text, in order, so that an earlier text never replaces a later one.
            bar = FeedbackBar { text -> scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text) } },
            report = { what, error ->
                for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
                    Log.w(TAG, if (index == 0) "$what failed: $part" else part)
                }
            },
            microphones = { MicCapture(appContext) },
        )
    }
```

- [ ] **Step 8: Leave the lock-screen test out on phones, and put the lock screen back on emulators**

In `scripts/voice-test.ps1`, replace

```powershell
# end with 'cmd sensor_privacy disable 0 microphone', also when the run failed or was interrupted. The run fails when
# that test ran on a phone or did not run on an emulator.
```

with

```powershell
# end with 'cmd sensor_privacy disable 0 microphone', also when the run failed or was interrupted.
# The test ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock turns on the swipe lock
# screen when the device has none and unlocks it with 'wm dismiss-keyguard', which cannot unlock a PIN, pattern or
# password, so on a phone the script leaves that test out too. The run fails when one of the two tests ran on a phone or
# did not run on an emulator. On an emulator the script turns the lock screen off again at the end, wakes the screen and
# dismisses the keyguard, also when the run failed or was interrupted.
```

Replace

```powershell
        $toggleTest = 'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
```

with

```powershell
        # Tests that a phone must not run: one switches the microphone's privacy toggle, the other turns on a lock screen
        # that it unlocks with adb.
        $emulatorOnly = @(
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
            'dev.kestrel.app.voice.ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock'
        )
```

Replace

```powershell
        if (-not $emulator) { $tasks += "-Pandroid.testInstrumentationRunnerArguments.notClass=$toggleTest" }
```

with

```powershell
        # notClass takes a comma-separated list.
        if (-not $emulator) { $tasks += "-Pandroid.testInstrumentationRunnerArguments.notClass=$($emulatorOnly -join ',')" }
```

Replace

```powershell
        # The JUnit XML names each test as name="method" classname="class".
        $method = ($toggleTest -split '#')[1]
        $toggleRan = [bool](Test-Path -LiteralPath $results) -and
            [bool](Get-ChildItem -LiteralPath $results -Recurse -Filter 'TEST-*.xml' | Select-String -SimpleMatch "name=`"$method`"")
        if ($code -eq 0 -and $toggleRan -ne $emulator) {
            Write-Host "The test $toggleTest should run only on an emulator, so the run fails."
            $code = 1
        }
```

with

```powershell
        # The JUnit XML names each test as name="method" classname="class".
        $reports = @(if (Test-Path -LiteralPath $results) { Get-ChildItem -LiteralPath $results -Recurse -Filter 'TEST-*.xml' })
        foreach ($test in $emulatorOnly) {
            $method = ($test -split '#')[1]
            $ran = [bool]($reports | Select-String -SimpleMatch "name=`"$method`"")
            if ($code -eq 0 -and $ran -ne $emulator) {
                Write-Host "The test $test should run only on an emulator, so the run fails."
                $code = 1
            }
        }
```

The lock-screen test turns on a swipe lock screen and puts the setting back in its own `finally`, which does not run when the test's process dies (Ctrl+C makes `connected-test.ps1` force-stop Kestrel, whose process runs the tests). So on an emulator the script reads the setting before the run and puts it back at the end. Replace

```powershell
$reached = $false
```

with

```powershell
$reached = $false
$lockScreenOff = $false
```

Replace

```powershell
    $reached = $true
```

with

```powershell
    $reached = $true
    # The lock-screen test turns on a swipe lock screen and puts the setting back itself, but a test process that died in
    # between would leave it on, so on an emulator the script reads the setting first and puts it back at the end.
    if ($emulator) { $lockScreenOff = "$(& (Get-Adb) -s $Serial shell cmd lock_settings get-disabled)".Trim() -eq 'true' }
```

In the `finally` block, replace

```powershell
            & (Get-Adb) -s $Serial shell cmd sensor_privacy disable 0 microphone
            if ($LASTEXITCODE -ne 0) {
                Write-Warning ("Could not switch off the microphone's privacy toggle on $Serial. " +
                    "Run: adb -s $Serial shell cmd sensor_privacy disable 0 microphone")
                $code = 1
            }
```

with

```powershell
            & (Get-Adb) -s $Serial shell cmd sensor_privacy disable 0 microphone
            if ($LASTEXITCODE -ne 0) {
                Write-Warning ("Could not switch off the microphone's privacy toggle on $Serial. " +
                    "Run: adb -s $Serial shell cmd sensor_privacy disable 0 microphone")
                $code = 1
            }
            # The lock-screen test leaves the screen on and unlocked and the lock screen as it found it, unless its process
            # died in between.
            if ($lockScreenOff) {
                & (Get-Adb) -s $Serial shell cmd lock_settings set-disabled true | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    Write-Warning ("Could not turn off the lock screen of $Serial again. " +
                        "Run: adb -s $Serial shell cmd lock_settings set-disabled true")
                    $code = 1
                }
            }
            & (Get-Adb) -s $Serial shell input keyevent KEYCODE_WAKEUP
            & (Get-Adb) -s $Serial shell wm dismiss-keyguard
```

The extra wake-up and `wm dismiss-keyguard` do nothing on an awake emulator without a keyguard.

- [ ] **Step 9: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: every Pester test passes in Windows PowerShell 5.1 and in PowerShell 7, PSScriptAnalyzer reports no problem in `voice-test.ps1`, and the script exits with 0.

- [ ] **Step 10: Run the compiler, lint and the audit**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: the device tests compile, all JVM tests pass (97 in `:core:voice`, 86 in `:app`), lint reports no issue in any module (`StaticFieldLeak` is suppressed on `ListeningService.current` alone), and the audit passes; it finds no test hook in the release build and no new network use.

- [ ] **Step 11: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 12: Run the device tests**

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 43 run, 0 failed, 0 errors, 0 skipped.", including the 2 tests of `ListeningServiceTest`, and no "should run only on an emulator" line. If `startListening` fails with "the mode is OFF", Android refused the service or the session: logcat has a "Kestrel" warning line that names the class of the error. If the foreground types are 0, the `dumpsys` format differs from the prototype's; print the lines of `dumpsys activity services dev.kestrel` that contain `isForeground` and fix the pattern. If the screen test stops at "No lock screen after the wake-up", `cmd lock_settings set-disabled false` did not turn the swipe lock screen on. If it stops after `wm dismiss-keyguard` with `screenOn=false`, the receiver got no USER_PRESENT: check that it is registered with `RECEIVER_EXPORTED`. Afterwards `cmd lock_settings get-disabled` on the emulator prints `true` again.

- [ ] **Step 13: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt scripts/voice-test.ps1
git commit -m "feat(app): ListeningService with its notification and the screen and lock handling" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Debug hooks for listening

**Files:**
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` (two places)
- Modify: `scripts/hook.ps1` (the help text, the parameters and the extras)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`

**Interfaces:**
- Consumes: `Listening.send`, `state`, `sessionOpen`, `microphones` (Task 3); `AppGraph.listening` (Task 4); `ClipMicrophone` with `play`, `recording`, `clear` and `speech(voice, text)` (Task 2); `TestHooks(graph, context).run(intent): HookReply`, `HookReply`, `TestHooks.ACTION_PREFIX` (plan 1B); `VoiceEngines.synthesizer(VoiceOptions(variation = 0f))` (plan 2A); the `VoiceTests` helpers of Task 4.
- Produces: in `dev.kestrel.app.debug` (debug builds only): `internal class VoiceHooks(graph: AppGraph)` with `suspend fun run(command: String, intent: Intent): String`, and in its companion `val COMMANDS = setOf("VOICE", "VOICE_STATE", "VOICE_CLIP")` and `val microphone: ClipMicrophone`; `TestHooks.HookError` becomes visible to the package. The commands for adb: `VOICE --es event toggle|pause|resume|off|screen-on|screen-off`, `VOICE --es event heard --es text <text> [--ez named true]`, `VOICE_STATE`, `VOICE_CLIP --es text <text>`; and in `scripts\hook.ps1`: `voice -Send <event> [-Text <text>] [-Named]`, `voice-state`, `voice-clip -Text <text>`.

The hooks of section 16.1 let a developer start and drive listening from adb before plan 2C-2 adds the shortcut and the tile. `VOICE` sends an event to the Orchestrator and answers with the event's name only, never the text. `VOICE_STATE` answers with `mode`, `work`, `speaking`, `microphone`, `recognition`, `screenOn`, `micBusy` and whether the voice session is open; it never contains text. `VOICE_CLIP` says a text in Kestrel's voice without variation and queues it in one `ClipMicrophone` for the whole process, which it makes the microphone of the next session; the answer says whether the clip plays now or from the next start, and that Kestrel then hears only clips until its process ends. A start from a hook needs Kestrel in front or its accessibility service on (spike S1); otherwise the controller turns Kestrel off again, which `voice-state` shows. The receiver keeps its `DUMP` permission and its 20 s limit; synthesising a clip takes about 2 s.

The device test calls `TestHooks.run` directly, as `TestHookReceiver` does: `UiAutomation.executeShellCommand` splits a command at every space, so an `am broadcast` cannot carry a text such as "Stop listening.". `TestHookTest` already covers the receiver and its permission.

- [ ] **Step 1: Write the failing device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.debug.HookReply
import dev.kestrel.app.debug.TestHooks
import dev.kestrel.app.debug.VoiceHooks
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The listening hooks of debug builds (design spec, section 16.1), called as TestHookReceiver calls them. */
class VoiceHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listening = VoiceTests.graph.listening

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun theStateHasNoTextAndATranscriptWhileOffChangesNothing() {
        assertEquals(Mode.OFF, listening.state.value.mode)

        val sent = hook("VOICE", "event" to "heard", "text" to "Kestrel, open the settings.", named = true)
        val state = hook("VOICE_STATE")

        assertEquals(HookReply(Activity.RESULT_OK, "sent heard"), sent)
        assertTrue(state.text, STATE_WHILE_OFF.matches(state.text))
    }

    @Test
    fun theHooksTurnListeningOnPauseItAndTurnItOff() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        VoiceTests.useMicrophone(ClipMicrophone())

        // Kestrel is in front, so that Android lets the hook's start begin a foreground service of the type microphone.
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals("sent toggle", hook("VOICE", "event" to "toggle").text)
            assertTrue("The voice session did not open", VoiceTests.waitUntil(60_000) { listening.sessionOpen.value })

            hook("VOICE", "event" to "heard", "text" to "Stop listening.")
            assertTrue("Not paused", VoiceTests.waitUntil(10_000) { listening.state.value.mode == Mode.PAUSED })
            val state = hook("VOICE_STATE").text
            assertTrue(state, state.startsWith("mode=paused work=idle speaking=false microphone=true recognition=named"))

            assertEquals("sent off", hook("VOICE", "event" to "off").text)
            assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
        }
    }

    @Test
    fun aClipWaitsForTheNextStartInTheHooksMicrophone() {
        VoiceTests.assumeModels(Engine.TTS)
        val previous = listening.microphones
        try {
            val reply = hook("VOICE_CLIP", "text" to "Kestrel.")

            assertEquals(reply.text, Activity.RESULT_OK, reply.code)
            assertTrue(reply.text, reply.text.contains("from its next start"))
            assertSame(VoiceHooks.microphone, listening.microphones())
        } finally {
            listening.microphones = previous
            // The clip would otherwise wait in the process-wide microphone for a later hook's start.
            VoiceHooks.microphone.clear()
        }
    }

    @Test
    fun anUnknownEventSaysWhichEventsExist() {
        val reply = hook("VOICE", "event" to "shout")

        assertEquals(HookReply.FAILED, reply.code)
        assertTrue(reply.text, reply.text.startsWith("error: add --es event with one of toggle"))
    }

    /** Runs a hook command as TestHookReceiver does, with string extras and the boolean extra `named`. */
    private fun hook(command: String, vararg extras: Pair<String, String>, named: Boolean = false): HookReply {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        if (named) intent.putExtra("named", true)
        return runBlocking { TestHooks(VoiceTests.graph, context).run(intent) }
    }

    private companion object {
        val STATE_WHILE_OFF = Regex(
            "mode=off work=idle speaking=false microphone=false recognition=named screenOn=(true|false) micBusy=false session=closed",
        )
    }
}
```

- [ ] **Step 2: Run the compiler to make sure the test fails**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with "Unresolved reference 'VoiceHooks'".

- [ ] **Step 3: Write the hooks**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.VoiceOptions
import java.util.Locale

/**
 * The listening hooks (design spec, section 16.1): they send events to Kestrel's Orchestrator, read its state, and say a
 * text into a stand-in microphone. The answers never contain what was said. `scripts/hook.ps1` lists them.
 */
internal class VoiceHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE" -> event(intent)
        "VOICE_STATE" -> state()
        else -> clip(intent)
    }

    private fun event(intent: Intent): String {
        val name = intent.getStringExtra(EXTRA_EVENT)
        val event = when (name) {
            "toggle" -> Event.Toggle
            "pause" -> Event.Pause
            "resume" -> Event.Resume
            "off" -> Event.TurnOff
            "screen-on" -> Event.Screen(on = true)
            "screen-off" -> Event.Screen(on = false)
            "heard" -> Event.Heard(text(intent), intent.getBooleanExtra(EXTRA_NAMED, false))
            else -> throw HookError("add --es event with one of ${EVENTS.joinToString()}")
        }
        graph.listening.send(event)
        return "sent $name"
    }

    private fun state(): String {
        val state = graph.listening.state.value
        val session = if (graph.listening.sessionOpen.value) "open" else "closed"
        return "mode=${state.mode.name.lowercase()} work=${state.work.name.lowercase()} speaking=${state.speaking} " +
            "microphone=${state.microphone} recognition=${state.recognition.name.lowercase()} " +
            "screenOn=${state.screenOn} micBusy=${state.micBusy} session=$session"
    }

    private suspend fun clip(intent: Intent): String {
        val text = text(intent)
        // Without variation, the voice says a text the same way every time.
        val samples = graph.voice.synthesizer(VoiceOptions(variation = 0f)).use { voice -> ClipMicrophone.speech(voice, text) }
        val clips = microphone
        graph.listening.microphones = { clips }
        clips.play(samples)
        val seconds = String.format(Locale.ROOT, "%.1f", samples.size / SAMPLE_RATE.toFloat())
        return if (clips.recording) {
            "playing $seconds s of speech through the clip microphone"
        } else {
            "queued $seconds s of speech: Kestrel hears it through the clip microphone from its next start, and only " +
                "clips until its process ends"
        }
    }

    private fun text(intent: Intent): String = intent.getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    companion object {
        val COMMANDS = setOf("VOICE", "VOICE_STATE", "VOICE_CLIP")

        /** The microphone that VOICE_CLIP says its texts into, one for the whole process. */
        val microphone: ClipMicrophone by lazy { ClipMicrophone() }

        private val EVENTS = listOf("toggle", "pause", "resume", "off", "screen-on", "screen-off", "heard")
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_NAMED = "named"
    }
}
```

- [ ] **Step 4: Route the commands**

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace

```kotlin
        if (command in SpikeHooks.COMMANDS) return SpikeHooks(context, graph).run(command, intent)
```

with

```kotlin
        if (command in SpikeHooks.COMMANDS) return SpikeHooks(context, graph).run(command, intent)
        if (command in VoiceHooks.COMMANDS) return VoiceHooks(graph).run(command, intent)
```

and replace

```kotlin
    private class HookError(override val message: String) : Exception(message)
```

with

```kotlin
    /** A mistake in a command, which the answer reports. VoiceHooks throws it too. */
    class HookError(override val message: String) : Exception(message)
```

- [ ] **Step 5: Document the commands in `hook.ps1`**

In `scripts/hook.ps1`, replace

```powershell
#   crash -Text 'text'                      ends Kestrel's process with an error whose message is the text, to check
#                                           that Android's crash records do not contain it
```

with

```powershell
#   crash -Text 'text'                      ends Kestrel's process with an error whose message is the text, to check
#                                           that Android's crash records do not contain it
# Listening (a start needs Kestrel in front or its accessibility service on, the speech models that
# scripts\push-models.ps1 copies, and the microphone permission; the notification and its actions show only with the
# notification permission):
#   voice -Send toggle|pause|resume|off|screen-on|screen-off
#                                           sends the event to Kestrel's Orchestrator; toggle starts listening, and
#                                           the notification's Pause or Resume and Stop actions work while it runs
#   voice -Send heard -Text 'text' [-Named] sends a transcript, as if Kestrel had heard it; -Named as if the keyword
#                                           spotter had heard "Kestrel" at its start
#   voice-state                             the Orchestrator's mode, work, speaking, microphone, recognition, screen and
#                                           "mic busy", and whether the voice session is open; never text
#   voice-clip -Text 'text'                 says the text in Kestrel's voice into a stand-in microphone; Kestrel hears
#                                           it from its next start, and then only such clips until its process ends
```

Replace

```powershell
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global', 'crash', 'listen',
        'listen-notification', 'listen-after-boot', 'location', 'spike-results', 'files', 'watch')][string]$Command,
```

with

```powershell
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global', 'crash', 'voice',
        'voice-state', 'voice-clip', 'listen', 'listen-notification', 'listen-after-boot', 'location', 'spike-results',
        'files', 'watch')][string]$Command,
```

Replace

```powershell
    [switch]$Clear,
    [string]$Create
)
```

with

```powershell
    [switch]$Clear,
    [string]$Create,
    [ValidateSet('toggle', 'pause', 'resume', 'off', 'screen-on', 'screen-off', 'heard')][string]$Send,
    [switch]$Named
)
```

Replace

```powershell
if ($Create) { $extras += "--es create $(ConvertTo-ShellText $Create)" }
```

with

```powershell
if ($Create) { $extras += "--es create $(ConvertTo-ShellText $Create)" }
if ($Send) { $extras += "--es event $(ConvertTo-ShellText $Send)" }
if ($Named) { $extras += '--ez named true' }
```

The hook's action name comes from the command as before: `voice-state` becomes `dev.kestrel.hook.VOICE_STATE`.

- [ ] **Step 6: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: every Pester test passes in Windows PowerShell 5.1 and in PowerShell 7, PSScriptAnalyzer reports no problem in `hook.ps1`, and the script exits with 0.

- [ ] **Step 7: Run the compiler, lint and the audit**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin check kestrelAudit`
Expected: `BUILD SUCCESSFUL`; the audit still finds no test hook in the release build, because `VoiceHooks` is in the debug source set.

- [ ] **Step 8: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 9: Run the device tests**

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 47 run, 0 failed, 0 errors, 0 skipped.", including the 4 tests of `VoiceHookTest`.

- [ ] **Step 10: Commit**

```powershell
git add app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt scripts/hook.ps1 app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt
git commit -m "feat(app): debug hooks that start, drive and read listening" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: A whole conversation, and the documentation

**Files:**
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ConversationTest.kt`
- Modify: `README.md` (the paragraph on the voice tests, and the section "Test hooks")
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 7.2, the row of `ListeningService`)

**Interfaces:**
- Consumes: `VoiceTests.startListening`, `stopListening`, `grantListeningPermissions`, `listeningServiceRuns`, `waitUntil`, `speech`, `log` (Task 4); `ClipMicrophone.play` (Task 2); `Listening.state` and `sessionOpen` (Task 3); `State`, `Mode`, `Work` (plan 2B).
- Produces: nothing that later code uses.

The end-to-end test runs one conversation through everything this plan built, with the real engines and Kestrel's own voice played through `ClipMicrophone`: "Kestrel." makes Kestrel answer "Yes?" and wait for the task; "Open the settings." becomes task 1, which ends at once with the spoken "I can't run tasks yet."; "Stop listening." pauses; "Kestrel, wake up." resumes; "Stop Kestrel." turns Kestrel off, the session closes with its engines, and the service ends. The test cannot hear Kestrel's replies, so it counts them in the state: each time `speaking` turns on, a reply started. It synthesises every clip before it starts, so that each answer comes well within the 8 s that Kestrel waits after "Yes?". It logs only the conversation's length in milliseconds. The test needs no new production code: it checks the parts together, so it passes when it is written.

The spec's section 7.2 says that `ListeningService` "Owns the microphone, the speech engines and voice output". In this plan, `Listening` in `AppGraph` holds them while the service runs, so the row changes.

- [ ] **Step 1: Write the end-to-end test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/ConversationTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A whole conversation with the real engines, on Kestrel's own voice played through a stand-in microphone (design spec,
 * sections 8.3–8.5): the name and "Yes?", a task, "Stop listening", "Kestrel, wake up" and "Stop Kestrel".
 */
class ConversationTest {
    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()
    private val states = CopyOnWriteArrayList<State>()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
    }

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun kestrelAnswersItsNameRunsATaskPausesWakesUpAndTurnsOff() {
        // Synthesised first, so that each answer comes well within the 8 s that Kestrel waits after "Yes?".
        val name = VoiceTests.speech("Kestrel.")
        val task = VoiceTests.speech("Open the settings.")
        val pause = VoiceTests.speech("Stop listening.")
        val wakeUp = VoiceTests.speech("Kestrel, wake up.")
        val off = VoiceTests.speech("Stop Kestrel.")
        val started = SystemClock.elapsedRealtime()

        runBlocking {
            val watcher = launch(Dispatchers.Default) { listening.state.collect { states += it } }
            try {
                // Kestrel is in front, so that Android lets it start a foreground service of the type microphone.
                ActivityScenario.launch(MainActivity::class.java).use {
                    VoiceTests.startListening(clip)

                    clip.play(name)
                    // "Yes?" was spoken, and Kestrel waits for the task.
                    awaitState("the answer to the name") { it.work == Work.AWAITING_TASK && !it.speaking && replies() == 1 }

                    clip.play(task)
                    // Until sub-project 5, every task ends at once with a spoken "I can't run tasks yet."
                    awaitState("the task's reply") { it.task == 1 && it.work == Work.IDLE && !it.speaking && replies() == 2 }

                    clip.play(pause)
                    awaitState("the pause") { it.mode == Mode.PAUSED }

                    clip.play(wakeUp)
                    awaitState("the wake-up") { it.mode == Mode.LISTENING }

                    clip.play(off)
                    awaitState("the end") { it.mode == Mode.OFF }
                    assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
                    assertTrue("ListeningService still runs", VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() })
                }
            } finally {
                watcher.cancel()
            }
        }
        VoiceTests.log("Conversation: 5 utterances in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /**
     * How many replies Kestrel has started: the times that speaking turned on. The state is a conflated StateFlow, so a
     * reply that ended at once (refused or stopped) could be missed; this test's replies play for about a second each.
     */
    private fun replies(): Int = states.toList().zipWithNext().count { (before, after) -> !before.speaking && after.speaking }

    private fun awaitState(step: String, condition: (State) -> Boolean) {
        assertTrue("No $step; the state is ${listening.state.value}", VoiceTests.waitUntil(30_000) { condition(listening.state.value) })
    }
}
```

- [ ] **Step 2: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 3: Run the device tests**

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 48 run, 0 failed, 0 errors, 0 skipped.", including `ConversationTest`, and the printed log has a "Conversation:" line. If the test stops at "the answer to the name" with `work=IDLE`, the transcript of "Kestrel." did not read as the name: `RequestsTest` shows which spellings count, and `ListeningTest` of plan 2B shows whether the recogniser hears the name in this voice. If it stops at "the wake-up", the keyword spotter missed "Kestrel" at the start, so the paused mode did not transcribe the utterance. Never print a transcript to find out.

- [ ] **Step 4: Document how listening starts in a debug build**

In `README.md`, in the paragraph that begins "The device tests of the speech engines need the speech models on the device.", replace

```markdown
`connected-test.ps1` runs these tests too, but they skip themselves when the models are missing, and at the end Gradle uninstalls Kestrel, which deletes the models.
```

with

```markdown
Some of these tests start Kestrel's listening service, switch the emulator's screen off and on with `input keyevent`, turn the emulator's swipe lock screen on (`cmd lock_settings set-disabled false`) and off again, and grant Kestrel the microphone and notification permissions, which stay granted. The lock-screen test unlocks with `wm dismiss-keyguard`, which cannot unlock a PIN, pattern or password, so on a phone the script leaves it out, as it does the privacy-toggle test, and on an emulator the script turns the lock screen off again at the end, in case the test's process died before it could. `connected-test.ps1` runs these tests too, but they skip themselves when the models are missing, and at the end Gradle uninstalls Kestrel, which deletes the models.
```

In the section "Test hooks", replace

```markdown
Release builds do not contain the hooks, and the privacy audit fails a release build that does.
```

with

````markdown
Until the accessibility shortcut and the Quick Settings tile come with plan 2C-2, listening starts in a debug build through the hooks. It needs the speech models (`push-models.ps1`), the microphone permission and, for the notification, the notification permission (the setup checklist asks for both), and Kestrel must be in front or its accessibility service on, because Android lets an app in the background start a microphone service only then. While Kestrel listens, its notification shows whether it listens or is paused, and its Pause or Resume and Stop actions work. Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock.

```powershell
scripts\hook.ps1 -Serial $serial voice -Send toggle                     # starts listening, or pauses it
scripts\hook.ps1 -Serial $serial voice-state                            # the Orchestrator's state, never what was said
scripts\hook.ps1 -Serial $serial voice -Send heard -Text 'Kestrel' -Named   # as if Kestrel had heard its name
scripts\hook.ps1 -Serial $serial voice-clip -Text 'Kestrel, wake up.'   # says the text into a stand-in microphone
scripts\hook.ps1 -Serial $serial voice -Send off                        # turns Kestrel off
```

`voice-clip` makes Kestrel hear only such clips from its next start until its process ends.

Release builds do not contain the hooks, and the privacy audit fails a release build that does.
````

- [ ] **Step 5: Correct section 7.2 of the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| `ListeningService` | Foreground service of the types `microphone` and `mediaPlayback` | Owns the microphone, the speech engines and voice output. Android 17 requires audio playback to belong to a foreground service like this one (B5). |
```

with

```markdown
| `ListeningService` | Foreground service of the types `microphone` and `mediaPlayback` | Keeps Kestrel listening, and shows the notification with Pause or Resume and Stop. While it runs in the foreground, Kestrel's listening controller holds the microphone, the speech engines and voice output; the controller lives in the app's process, so that the accessibility service, the notification and the tile reach it whether or not the service runs. Android 17 requires audio playback to belong to a foreground service like this one (B5). |
```

- [ ] **Step 6: Run every check of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (97 in `:core:voice`, 86 in `:app`), lint in every module and the audit pass.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add app/src/androidTest/kotlin/dev/kestrel/app/voice/ConversationTest.kt README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "test(app): a whole conversation through the listening service; docs: how listening starts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Self-review

**Spec and design coverage.**

| Design decision | Where |
|---|---|
| D1 `Microphone` with MicCapture's members; `ClipMicrophone` in the debug source set, real time, `play`, settable `silenced` (and `clear`) | Task 1 Step 3; Task 2 Step 5 |
| D2 `SpeechStarted` (edge, resume during speech, again after an event) and `Dropped`; JVM tests with the existing fakes, including the two extensions beyond the design | Task 1 |
| D3 `Listening` in `AppGraph`; Orchestrator on one single-threaded dispatcher; `state`, `send`, `microphones`; start on leaving Off; session only after `onForeground`; failed `startForeground` → `TurnOff` with the reason in place of "Off"; microphone and recognition follow the state; `StopService` closes, then stops, but never a service that has not reported; one `Mutex`; every effect's mapping; messages never logged; behind `VoiceSessions`, `ServiceControl`, `FeedbackBar` | Task 3 (logic and 21 JVM tests); Task 4 Step 7 (wiring) |
| D4 `VoiceSession`: engines from `graph.voice`, `VoicePlayer`, `EchoFilter`, pipeline with `{ listOf("Kestrel") }`, `isOwnVoice` with `elapsedRealtime` and `player.recent()`, its own recognition thread; missing models close what was loaded; the event mapping; `silenced` → `MicBusy`; microphone errors handed to the controller's thread; the close order | Task 2; Task 3 (`Listener.microphoneFailed`, missing models) |
| D5 `ListeningService`: types, permissions, declaration, `startForeground` at once, `START_NOT_STICKY`, the notification and its actions, `MainActivity` on a tap, following the state, an unexpected `onDestroy` → `TurnOff`, the exported screen receiver with USER_PRESENT read as unlocked and the first real value, resume after a real unlock; the service stops itself with `stopSelfResult` | Task 4; Task 3 (`destroyed`, `awaitingForeground`) |
| D6 `VOICE`, `VOICE_STATE`, `VOICE_CLIP`; `hook.ps1`'s help | Task 5 |
| D7 JVM and device tests; device tests through `voice-test.ps1` in `dev.kestrel.app.voice`, APKs kept installed; no text in the log; the lock-screen test only on emulators | Tasks 1-6 |
| D8 README; spec section 7.2 | Task 6 Steps 4-5 |
| Final review of 2B, "For plan 2C" 1-8: `Speaking` and dropped utterances (1); `perform` throws only when the reply did not start, after showing its text, `invokeOnCompletion` last, no `TODO()`, refused or failed replies shown (2); every `RunTask` answered (3); microphone and transcription failures become `TurnOff` with a reason or a log line, `stop()` after a failed `start()`, `onError` handed over (4); `Screen` before the first frame, recognition before the microphone, events collected once from the start, the three engines before the pipeline (5); the shutdown order outside the main thread, a new start waits, Android's own stop → `TurnOff` (6); the bar's wording stays for 2C-2, the texts keep their order (7); only classes logged (8) | Tasks 1-4 |
| Review 1: C-1 (exported receiver, USER_PRESENT as unlocked, a real swipe lock screen in the test), I-1 (`awaitingForeground`, `stopSelfResult`), I-2 (`offReason`, the bar on `Dispatchers.Main`), M-1 (`Error` while opening), M-2 (text without a voice), M-3 (two pipeline tests), M-4 (`voice-test.ps1`), M-5 ("Not in this plan"), M-6 (`ClipMicrophone.clear()`, the comment on `replies()`) | Tasks 1-6, "Not in this plan", "Threads and the shutdown order" |

**Placeholder scan.** No "TBD", "TODO", "similar to Task N" or step without its code or command. Every modified file shows the exact text it replaces; the four replaced blocks of `voice-test.ps1` match the script at 0544ab4.

**Type consistency.** `Microphone.start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit)` matches `MicCapture`, `ClipMicrophone` and the JVM fake. `VoiceSessions.open(microphone: Microphone, listener: SessionListener): Session` matches `AppGraph`'s object and the JVM fake. `Listening(scope, thread, sessions, services, bar, report, microphones)` matches `AppGraph` and the JVM test; `onForeground(instance: Any)`, `onForegroundFailed(error: Exception)`, `onServiceDestroyed(instance: Any)` match `ListeningService`. `ServiceControl.start()`/`stop()` match `ListeningService.control` and the JVM fake; `stop()` is asynchronous in the app and synchronous in the fake, which the controller does not depend on. `sessionOpen`, `microphones`, `state`, `send` match `VoiceTests`, `VoiceHooks` and the device tests. `ListeningService.NOTIFICATION_ID`, `actionIntent`, `control` match their callers; `current` and `lastStartId` are private to the service and its companion. `ClipMicrophone.clear()` is used by `VoiceHookTest`. `VoiceTests.startListening(microphone: ClipMicrophone)`, `useMicrophone(microphone: Microphone)`, `stopListening()`, `waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean` match Tasks 4-6.

**Counts.** `ListeningPipelineTest` 19 → 24, `:core:voice` 92 → 97; `ListeningTest` 21, `:app` 65 → 86; voice device tests 39 → 41 (Task 2), 43 (Task 4), 47 (Task 5), 48 (Task 6).

**Decisions this plan made beyond the design.**
- `Dropped` also covers an utterance that the owner's "Kestrel" broke into, not only one that the resume cut below 0.25 s: both end without a transcript while recognition is on, and both should restart the 8 s wait. `SpeechStarted` also comes again after a failure or a drop, not only after a transcript, for the same reason. Both have tests.
- `Listening` recognises an unexpected end of the service by the service's instance, because a stop that Kestrel asked for can be followed at once by a new start, and Android destroys the old instance only afterwards. It keeps the service state until the `TurnOff` is handled, so that no event in between restarts the service. A service that nobody asked for (a stale notification while Kestrel is off, or a start that reported after a shutdown skipped its stop) is stopped.
- A reason for turning off replaces "Off" on the bar; when Kestrel is already off, the reason shows on its own.
- `Listening` exposes `sessionOpen`, so that the tests and `VOICE_STATE` can see when the engines have been closed; `VOICE_STATE` reports it as `session=`.
- The interfaces `Session`, `SessionListener` and `VoiceSessions` live in their own file of Task 2, so that Task 2 does not depend on Task 3. `AppGraph.listening` is wired in Task 4, which creates `ListeningService`, because `ServiceControl` needs the service class.
- The device tests grant the microphone and notification permissions and leave them granted, as `SetupStatusReaderTest` does; revoking would end the test process. `VoiceHookTest` calls `TestHooks.run` directly, because `UiAutomation.executeShellCommand` cannot pass a text with spaces through `am broadcast`.
- Spec section 7.2 changes, because the controller, not the service, holds the engines.

**Not verified while writing (no Gradle run, no device).** `NotificationManager.activeNotifications` returning the foreground notification once `POST_NOTIFICATIONS` is granted; that `cmd lock_settings set-disabled false` gives a swipe keyguard that `isKeyguardLocked` reports after `KEYCODE_WAKEUP`, and that `wm dismiss-keyguard` then makes SystemUI send USER_PRESENT; that `stopSelfResult` from the posted stop ends the service within `stopListening`'s 10 s; that the recogniser reads Piper's "Kestrel." and "Kestrel, wake up." as the name; the run time of `voice-test.ps1` with four sessions more. `hook.ps1`'s and `voice-test.ps1`'s new parts are checked by `test-scripts.ps1` only, not run against a device.