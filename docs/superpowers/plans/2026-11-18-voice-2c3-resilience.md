# Voice 2C-3: Resilience and Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make listening survive the failures that need no new facts from a device (a model folder that comes back, a model check that fails or is cancelled, a microphone that fails once, a start while a session closes, an event of a closing session), hold the answer to "Yes?" like a named utterance, and boost the names of the installed apps.

**Architecture:** `ModelFiles` finds its folder again at each check and load, creates it before each check, and stops a cancelled check between files; the settings pages show a failed check through a new `ModelCheck` state. `:core:voice` gains `Recognition.ANSWER`, for which the `Endpointer` holds every utterance as it holds a named one, and the Orchestrator's state asks for it while it waits for a task. A `BoostWordSource` in `:app` reads the launcher's app labels once when a voice session opens, behind a `<queries>` element. The Orchestrator handles an event of a voice session only if the session is still active when the event's turn comes. `Listening` asks Android for the service at once while only the session's opening waits for a close, and restarts a failed microphone after 1 s, at most once a minute.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0 (with kotlinx-coroutines-test's virtual time for the restart's waits), Jetpack Compose for the settings pages, sherpa-onnx 1.13.8 behind `:core:voice`'s interfaces, Android's `PackageManager` at compileSdk 37, JUnit 4, device tests on the `kestrel-api37` emulator through `scripts\voice-test.ps1` and, for the settings app's tests, `scripts\connected-test.ps1`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app.
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`.
- No dependency-injection framework; `AppGraph` wires by hand.
- Never log audio, transcripts, replies, boost words, app labels, screen text or exception messages (section 13.5); `toString` of anything that carries the owner's words or the phone's app names prints only lengths or counts.
- Test only on `kestrel-api37` (emulator-5554); never on the S23+ or the AVD `zoran`; never send commands to the A71. Gradle only through `scripts\gradle.ps1` (stopped with `--stop`), device tests only through `scripts\voice-test.ps1` or `scripts\connected-test.ps1`, installs only through `scripts\install.ps1`. Device tests restore every setting they change and keep the APKs installed.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan files are frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

## Before you start

- Plan 2C-2 is complete, including its Task 5 (`b5b9581`, which adds `scripts\listen-check.ps1`) and the fix commit that follows it. The current totals: `:app` JVM 129 tests (126, and the 3 that `78c99c1` added to `ListeningTest`), `:core:voice` JVM 99, voice device tests 60, Pester 90 per shell (82, and the 8 that `3f77ddb` added). This plan changes no script, so Pester stays at 90 and `scripts\test-scripts.ps1` need not run.
- What the listening classes do now (`.superpowers/sdd/progress.md`, plans 2C-1 and 2C-2): `Listening` (in `AppGraph.listening`) owns the Orchestrator on one single-threaded dispatcher (`thread`). When the mode leaves Off, `follow()` calls `start()`, which today waits for the `lifecycle` lock before it asks Android for `ListeningService`; once the service reports, `open()` opens a `VoiceSession` under the same lock. `Effect.StopService` runs `shutdown()`, which silences the session's `Listener`, closes the session under the lock, and then stops the service unless a newer start was asked for (`requested`) or a start has not reported yet (`awaitingForeground`). A microphone error turns Kestrel off with `ListeningTexts.MICROPHONE_FAILED`. `VoiceSession` boosts only "Kestrel". `State.recognition` is `ALL` while Kestrel waits for a task.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` when you are done.
- Run the voice device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`; it installs Kestrel, copies the models and keeps both installed. Task 1 also runs the settings app's device test classes `SettingsAppTest` and `MainActivityTest` through `scripts\connected-test.ps1` with the arguments that its step gives, because they are not in the package `dev.kestrel.app.voice`, which is the only package that `voice-test.ps1` runs. Every new voice device test of this plan lives in `dev.kestrel.app.voice`.
- Every device command of this plan starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. `voice-test.ps1` and `connected-test.ps1` also refuse other devices themselves (`Assert-TestDevice`).
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too. Plan 2C-2's Task 5 and its fix change the spec and the README; by the time this plan runs, they are committed.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 2C-4 (feedback and failures), after prototype P2 (`.superpowers/sdd/prototype-2c3-brief.md`):
- The final texts of the feedback bar: "Waiting for your task", mic-busy wording that fits the privacy toggle and an idle app, and `CANNOT_START` by its cause.
- Failure reasons as a notification with a "Start listening" action (`Event.Start`); Pause, Resume and Stop as broadcasts to a receiver; spike S1's start from a notification action, checked again.
- The "Kestrel is off" notification when the accessibility service disconnects; the empty-transcript hint of section 14.6, which counts only real empty transcripts and not `Dropped` or `Failed`.
- Battery restrictions and a `startForeground()` that Android ignored; the notification that the owner swiped away (final review of plan 2C-1, item 9).
- The restore and `getHistoricalProcessExitReasons()`; a device test of a refused reply (`REFUSED`).

Plan 2D: the player's focus listener and the focus between queued replies; the A71 measurements (the waits, the misses of the spotter and the speech detector, the effect of the boost words with the owner's voice, and how long reading the app labels takes with the A71's apps).

Sub-projects 3 and 5: the other boost-word sources of section 8.3 (the labels on the screen and the command vocabulary; the owner's aliases and the contact names).

Item 16 of the final review of plan 2C-1 (a test that calls `VOICE_CLIP` must put the microphone factory back itself) needs no change: the two tests that call `VOICE_CLIP`, `VoiceHookTest.aClipWaitsForTheNextStartInTheHooksMicrophone` and `clearDropsTheClipsAndPutsBackTheMicrophoneThatTheClipReplaced`, already put `listening.microphones` back and clear the clips in their `finally` blocks (since `b43ff9e` and `89e6047`), and no other device test replaces the factory through the hooks.

A device test of the microphone's restart (design D4, optional) is not in this plan: the emulator cannot restart its audio server, and the restart reuses the stop and start of the screen's off and on, which `ListeningServiceTest` already covers on the device.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `ModelFiles.check()` | `Dispatchers.IO` (it switches itself) | Finds the folder once per check (`getExternalFilesDir` touches the disk) and creates it |
| `ModelFiles.root` and `ModelFiles.file(path)` | The caller's thread, which is never the main thread: `KestrelApplication`'s `mkdirs` and the engines' loads run on `Dispatchers.IO`, and the device tests on the instrumentation's thread | Each use finds the folder again |
| `MainActivity.checkModels()` | Launched from the main thread in `lifecycleScope`; the check runs on `Dispatchers.IO`; `status` is set on the main thread | A failed check becomes `ModelCheck.Failed`, and `graph.errors` logs the error's classes |
| `BoostWordSource.words()` | An `async` child of the coroutine that opens the voice session, which `Listening.open()` launches on `thread`; only the read itself runs in `withContext(Dispatchers.IO)` | Runs while the engines load; `VoiceSession.open` waits for it after the recogniser has loaded, and the session keeps the list. A failed load cancels it, but the package manager's calls do not stop for a cancellation, so a failure such as missing models shows up to one read of the labels later |
| The pipeline's `boostWords` | The recognition worker (`Dispatchers.Default.limitedParallelism(1)`) | Returns the session's list |
| `Listener.send` | Any session thread; it puts the event into the Orchestrator's channel at once, with the check `{ active }`, which the Orchestrator runs on `thread` when the event's turn comes | Every shutdown that came before the event has run by then, so no event of a closing session acts on the next state; the events keep their order |
| `ServiceControl.start()` | The controller's thread, inside `follow()`, at once | Also while a session closes; only `open()` waits for the `lifecycle` lock |
| The microphone's restart | A coroutine on the controller's thread waits 1 s, and a second one marks the minute after a restart | `shutdown()` cancels both; `follow()` does not start the microphone while the first one waits |

## File structure

| File | Responsibility |
|---|---|
| `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt` | Finds its folder again at each use, creates it before each check, and stops a cancelled check between files |
| `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt` | `ModelCheck` (checking, failed, done) and `checkModelFiles`, which turns a failed check into `ModelCheck.Failed` |
| `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, `AboutPage.kt`, `app/src/main/res/values/strings.xml` | Show a failed check |
| `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` | Checks the model files through `checkModelFiles` |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` | Finds the models' folder at each use (Task 1); wires `BoostWordSource` and one failure reporter (Task 3) |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt` | Holds every utterance when asked to, and keeps each utterance's own `named` |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt` | `Recognition.ANSWER`, which transcribes every utterance and asks the endpointer to hold it |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` | `State.recognition` is `ANSWER` while Kestrel waits for a task and does not speak |
| `app/src/main/kotlin/dev/kestrel/app/listen/BoostWordSource.kt` | `AppLabel`, `AppLabels`, `LauncherAppLabels` and `BoostWordSource` (new): the owner's apps before the system apps |
| `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt` | Reads the boost words once when it opens, and boosts them for the whole session |
| `app/src/main/AndroidManifest.xml` | `<queries>` for the launcher's intent |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` | `send(event, valid)`: an event that the Orchestrator handles only if its check still holds when its turn comes |
| `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` | The session's events checked when the Orchestrator handles them; the service asked for at once; the microphone's restart |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md` | Sections 8.3, 8.10 and 14.6 |

Tests: `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/listen/EndpointerTest.kt` and `ListeningPipelineTest.kt` (JVM, changed); `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `OrchestratorTest.kt` and `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt` (JVM, changed); `app/src/test/kotlin/dev/kestrel/app/listen/BoostWordSourceTest.kt` (JVM, new); `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` and `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt` (device, changed); `app/src/androidTest/kotlin/dev/kestrel/app/voice/AppNamesTest.kt` (device, new).

---

### Task 1: The model files

**Files:**
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt` (whole file below)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (`models`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt` (whole file below)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt` (`ChecklistPage`, `ModelProblems`, an import)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt` (`AboutPage`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` (`refresh`, `checkModels`, imports)
- Modify: `app/src/main/res/values/strings.xml` (two strings)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.10)
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`

**Interfaces:**
- Consumes: `ModelFiles(root: File, manifest: ModelManifest, hashCache: File, hash: (File) -> String = ::sha256)`, `ModelFiles.check(): List<ModelStatus>`, `ModelStatus(model, missing, damaged)`; `SetupStatus`, `SetupChecklist.states(status)`, `SettingsApp(status, data, onAction, onCheckModels, onExportLogs)`; `AppGraph.errors: CoroutineExceptionHandler`.
- Produces: `class ModelFiles(findRoot: () -> File, manifest, hashCache, hash = ::sha256)` with the secondary constructor `ModelFiles(root: File, manifest, hashCache, hash = ::sha256)`, `val root: File` (a getter that finds the folder again), and a `check()` that creates the folder and stops between files when cancelled; `sealed interface ModelCheck` with `data object Checking`, `data object Failed` and `data class Done(val statuses: List<ModelStatus>)` (package `dev.kestrel.app.settings`); `SetupStatus.models: ModelCheck = ModelCheck.Checking`; `internal suspend fun checkModelFiles(check: suspend () -> List<ModelStatus>, report: (Exception) -> Unit): ModelCheck`; the strings `setup_models_check_failed` and `about_models_check_failed`. Task 3 edits other parts of `AppGraph`.

Plan 2A's deferred rows 9, 10, 11 and 13 (`.superpowers/sdd/final-review-2a-report.md`). Row 9: `AppGraph.models` builds its root once, so when shared storage was missing at first use, the fallback folder in Kestrel's private storage stays until the process ends, and the models count as missing for days. Now `ModelFiles` asks for the folder at each use, and the fallback lasts for one check or one load. Row 10: Kestrel created the models folder only when its process started, so `push-models.ps1`, which starts `MainActivity` and waits for the folder, failed after someone deleted the folder while Kestrel ran; now every check creates it, and `MainActivity` checks when it comes to the front. Row 11: a cancelled check went on hashing and wrote the cache; now it stops before the next file. Row 13: a check that throws left "Checking the model files…" on both pages for ever; now both pages say that Kestrel could not check the files, `graph.errors` logs the error by its classes, and the next page that opens checks again, as every opening of the checklist or the About page already does (`SettingsApp`'s `LaunchedEffect(page)`).

- [ ] **Step 1: Write the failing JVM tests**

In `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt`, replace the imports

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
```

with

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
```

and replace

```kotlin
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
```

with

```kotlin
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
```

Then add these three tests right before the test `theHashIsLowercaseHex`:

```kotlin
    @Test
    fun aCancelledCheckStopsBeforeTheNextFileAndWritesNoCache() = runTest {
        write("m/a.onnx", "abc")
        write("m/b.onnx", "de")
        val hashing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val files = ModelFiles(root, manifest("m/a.onnx" to "abc", "m/b.onnx" to "de"), cache) { file ->
            hashed += file.name
            hashing.complete(Unit)
            // Hashing a large file takes seconds; the check is cancelled meanwhile.
            release.await(10, TimeUnit.SECONDS)
            sha256(file)
        }

        val checking = launch { files.check() }
        hashing.await()
        checking.cancel()
        release.countDown()
        checking.join()

        assertEquals(listOf("a.onnx"), hashed)
        assertFalse(cache.exists())
    }

    @Test
    fun theFolderIsFoundAgainAtEachCheck() = runTest {
        write("m/a.onnx", "abc")
        // While shared storage is missing, Kestrel falls back to a folder in its private storage.
        var current = File(folder.root, "fallback")
        val files = ModelFiles({ current }, manifest("m/a.onnx" to "abc"), cache)

        assertEquals(listOf("m/a.onnx"), files.check().single().missing)

        current = root
        assertTrue(files.check().single().ready)
        assertEquals(File(root, "m/a.onnx"), files.file("m/a.onnx"))
    }

    @Test
    fun aCheckCreatesTheFolder() = runTest {
        // Someone deleted the folder while Kestrel ran; scripts\push-models.ps1 waits for Kestrel to create it.
        root.deleteRecursively()

        modelFiles(manifest("m/a.onnx" to "abc")).check()

        assertTrue(root.isDirectory)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, replace the imports

```kotlin
import dev.kestrel.voice.models.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Test
```

with

```kotlin
import dev.kestrel.voice.models.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
```

In the test `theModelStepIsDoneOnlyWhenEveryModelIsReady`, replace

```kotlin
        // Kestrel has not checked the files yet.
        assertEquals(StepState.TO_DO, state(nothingSetUp))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(models = listOf(ready, ModelStatus(speech, listOf("a"), emptyList())))))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(models = listOf(ready, ModelStatus(speech, emptyList(), listOf("a"))))))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(models = listOf(ready, ModelStatus(speech, emptyList(), emptyList())))))
    }
```

with

```kotlin
        // Kestrel has not checked the files yet, or could not check them.
        assertEquals(StepState.TO_DO, state(nothingSetUp))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(models = ModelCheck.Failed)))
        assertEquals(
            StepState.TO_DO,
            state(nothingSetUp.copy(models = ModelCheck.Done(listOf(ready, ModelStatus(speech, listOf("a"), emptyList()))))),
        )
        assertEquals(
            StepState.TO_DO,
            state(nothingSetUp.copy(models = ModelCheck.Done(listOf(ready, ModelStatus(speech, emptyList(), listOf("a")))))),
        )
        assertEquals(
            StepState.DONE,
            state(nothingSetUp.copy(models = ModelCheck.Done(listOf(ready, ModelStatus(speech, emptyList(), emptyList()))))),
        )
    }

    @Test
    fun aModelCheckThatFailsIsReportedAndSaysSo() = runTest {
        val failure = IllegalStateException("The manifest cannot be read")
        val reported = mutableListOf<Exception>()

        val result = checkModelFiles({ throw failure }) { reported += it }

        assertEquals(ModelCheck.Failed, result)
        assertEquals(listOf<Exception>(failure), reported)
    }

    @Test
    fun aCancelledModelCheckIsNotAFailure() = runTest {
        val reported = mutableListOf<Exception>()

        // As when the owner leaves the page while the files are hashed.
        val thrown = runCatching { checkModelFiles({ throw CancellationException("The page closed") }) { reported += it } }

        assertTrue(thrown.exceptionOrNull() is CancellationException)
        assertTrue(reported.isEmpty())
    }
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.models.ModelFilesTest`
Expected: `BUILD FAILED`, with a type mismatch in `theFolderIsFoundAgainAtEachCheck`, where the test passes a function, `{ current }`, in place of the `File` that the constructor takes.

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'ModelCheck'" and "Unresolved reference 'checkModelFiles'".

- [ ] **Step 3: Find the folder at each use, create it, and stop a cancelled check**

Replace the whole of `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt` with:

```kotlin
package dev.kestrel.voice.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Whether a model's files are on the phone and intact. [missing] and [damaged] hold paths from the manifest. */
data class ModelStatus(val model: Model, val missing: List<String>, val damaged: List<String>) {
    val ready: Boolean get() = missing.isEmpty() && damaged.isEmpty()
}

/**
 * Checks the model files under [root] against [manifest] (design spec, section 8.10). A file of the wrong size is
 * damaged without being read; otherwise its SHA-256 hash decides. [hashCache] remembers each file's hash together with
 * its size and modification time, so that a file is hashed again only after it changes. The cache lives in Kestrel's
 * private storage, because anyone with adb can change the files under [root]. [findRoot] finds the folder again at each
 * use, because the folder can change while Kestrel runs, for example when shared storage comes back after a while.
 */
class ModelFiles(
    private val findRoot: () -> File,
    val manifest: ModelManifest,
    private val hashCache: File,
    private val hash: (File) -> String = ::sha256,
) {
    /** Checks the files under [root], a folder that never changes. */
    constructor(root: File, manifest: ModelManifest, hashCache: File, hash: (File) -> String = ::sha256) :
        this({ root }, manifest, hashCache, hash)

    private val lock = Mutex()

    /** The folder that holds the model files, found again at each use. */
    val root: File get() = findRoot()

    /** Where a path from the manifest lives on this phone. */
    fun file(path: String): File = File(root, path)

    /**
     * Checks every model, in the manifest's order. Hashing a large file takes seconds, so this runs on [Dispatchers.IO].
     * It uses one folder for the whole check, and creates it first, so that scripts\push-models.ps1 finds it also after
     * someone deleted it while Kestrel ran. A cancelled check stops before the next file and leaves the cache as it was.
     */
    suspend fun check(): List<ModelStatus> = withContext(Dispatchers.IO) {
        lock.withLock {
            val folder = root.apply { mkdirs() }
            val cached = readCache()
            val known = mutableMapOf<String, CachedHash>()
            val statuses = manifest.models.map { model ->
                val missing = mutableListOf<String>()
                val damaged = mutableListOf<String>()
                for (entry in model.files) {
                    ensureActive()
                    val file = File(folder, entry.path)
                    when {
                        !file.isFile -> missing += entry.path
                        file.length() != entry.size -> damaged += entry.path
                        else -> {
                            val actual = try {
                                hashOf(file, entry.path, cached, known)
                            } catch (e: IOException) {
                                null
                            }
                            if (actual != entry.sha256) damaged += entry.path
                        }
                    }
                }
                ModelStatus(model, missing, damaged)
            }
            // Also after the last file, so that a cancelled check never writes the cache.
            ensureActive()
            if (known != cached) writeCache(known)
            statuses
        }
    }

    private fun hashOf(file: File, path: String, cached: Map<String, CachedHash>, known: MutableMap<String, CachedHash>): String {
        val size = file.length()
        val modified = file.lastModified()
        val fileHash = cached[path]?.takeIf { it.size == size && it.modified == modified }?.sha256 ?: hash(file)
        known[path] = CachedHash(size, modified, fileHash)
        return fileHash
    }

    private fun readCache(): Map<String, CachedHash> = try {
        if (hashCache.isFile) json.decodeFromString(CACHE, hashCache.readText()) else emptyMap()
    } catch (e: IOException) {
        emptyMap()
    } catch (e: IllegalArgumentException) {
        // A cache that cannot be read costs only one more hashing.
        emptyMap()
    }

    private fun writeCache(entries: Map<String, CachedHash>) {
        try {
            hashCache.parentFile?.mkdirs()
            val temporary = File(hashCache.path + ".tmp")
            temporary.writeText(json.encodeToString(CACHE, entries))
            Files.move(temporary.toPath(), hashCache.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // The next check hashes the files again.
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val CACHE = MapSerializer(String.serializer(), CachedHash.serializer())
    }
}

@Serializable
private data class CachedHash(val size: Long, val modified: Long, val sha256: String)

/** The SHA-256 hash of [file], in lowercase hex. */
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
    /**
     * Kestrel's speech model files, which scripts\push-models.ps1 copies into Kestrel's external files folder (design
     * spec, section 8.10). Built on first use, because finding that folder touches the disk. If shared storage is not
     * available, the models count as missing.
     */
    val models: ModelFiles by lazy {
        ModelFiles(
            root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models"),
```

with

```kotlin
    /**
     * Kestrel's speech model files, which scripts\push-models.ps1 copies into Kestrel's external files folder (design
     * spec, section 8.10). Built on first use, because it reads the manifest from the assets. The folder is found again
     * at each check and each load of the engines: while shared storage is not available, a check or a load uses a folder
     * in Kestrel's private storage, where the models count as missing, and the next one finds them once shared storage
     * is back. Every caller uses it off the main thread, because finding the folder touches the disk.
     */
    val models: ModelFiles by lazy {
        ModelFiles(
            findRoot = { File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models") },
```

- [ ] **Step 4: Say on both pages that the check failed**

Replace the whole of `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt` with:

```kotlin
package dev.kestrel.app.settings

import dev.kestrel.voice.models.ModelStatus
import kotlinx.coroutines.CancellationException

/** The steps of the setup checklist, in order (design spec, section 15). */
enum class SetupStep {
    INSTALL,
    COPY_MODELS,
    ACCESSIBILITY_SERVICE,
    MICROPHONE_AND_NOTIFICATIONS,
    BATTERY,
    DEFAULT_ASSISTANT,
    PROVIDER,
    VOICE_ACCESS,
}

/** Where a step of the setup checklist stands. */
enum class StepState {
    DONE,

    /** The owner still has to do the step. */
    TO_DO,

    /** A later sub-project adds what the step sets up. */
    LATER,

    /** The step is optional and not done. */
    OPTIONAL,

    /** Something needs the owner's attention, such as Voice Access running beside Kestrel. */
    WARNING,
}

/** What the settings pages know about the model files (design spec, section 8.10). */
sealed interface ModelCheck {
    /** Kestrel is checking them and has no result to show. */
    data object Checking : ModelCheck

    /** The last check failed. The pages say so, and the next page that opens checks again. */
    data object Failed : ModelCheck

    /** The result of the last check: one status per model, in the manifest's order. */
    data class Done(val statuses: List<ModelStatus>) : ModelCheck
}

/** What Kestrel can find out about its own setup on the phone. [models] is what Kestrel knows about the model files. */
data class SetupStatus(
    val serviceOn: Boolean,
    val microphoneAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val voiceAccessOn: Boolean,
    val models: ModelCheck = ModelCheck.Checking,
)

/** Decides the state of each step of the setup checklist (design spec, section 15). */
object SetupChecklist {
    fun states(status: SetupStatus): Map<SetupStep, StepState> = SetupStep.entries.associateWith { step ->
        when (step) {
            // Kestrel is running, so it is installed.
            SetupStep.INSTALL -> StepState.DONE
            SetupStep.COPY_MODELS -> doneIf((status.models as? ModelCheck.Done)?.statuses?.all { it.ready } == true)
            SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
            SetupStep.ACCESSIBILITY_SERVICE -> doneIf(status.serviceOn)
            SetupStep.MICROPHONE_AND_NOTIFICATIONS -> doneIf(status.microphoneAllowed && status.notificationsAllowed)
            SetupStep.BATTERY -> doneIf(status.batteryUnrestricted)
            // Both apps would react to the same words.
            SetupStep.VOICE_ACCESS -> when {
                !status.voiceAccessOn -> StepState.DONE
                status.serviceOn -> StepState.WARNING
                else -> StepState.OPTIONAL
            }
        }
    }

    private fun doneIf(done: Boolean) = if (done) StepState.DONE else StepState.TO_DO
}

/**
 * Checks the model files with [check] for the settings pages. A check that throws gives [ModelCheck.Failed], so that
 * the pages do not say "Checking…" for ever, and [report] gets the error, which it must log by its classes only (design
 * spec, section 13.5). A cancellation, such as the end of the page's scope, is thrown on.
 */
internal suspend fun checkModelFiles(check: suspend () -> List<ModelStatus>, report: (Exception) -> Unit): ModelCheck =
    try {
        ModelCheck.Done(check())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
        ModelCheck.Failed
    }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, remove the import line

```kotlin
import dev.kestrel.voice.models.ModelStatus
```

then replace

```kotlin
/** The setup checklist (design spec, section 15). [models] is null until Kestrel has checked the model files. */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: List<ModelStatus>?,
```

with

```kotlin
/** The setup checklist (design spec, section 15). [models] is what Kestrel knows about the model files. */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: ModelCheck,
```

and replace

```kotlin
/** Names each model file that is missing or damaged (design spec, section 8.10). */
@Composable
private fun ModelProblems(models: List<ModelStatus>?) {
    if (models == null) {
        Text(stringResource(R.string.setup_models_checking), style = MaterialTheme.typography.bodySmall)
        return
    }
    for (status in models) {
        for (path in status.missing) {
            Text(stringResource(R.string.setup_models_missing, path), style = MaterialTheme.typography.bodySmall)
        }
        for (path in status.damaged) {
            Text(stringResource(R.string.setup_models_damaged, path), style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

with

```kotlin
/** Names each missing or damaged model file, or says that Kestrel is checking them or could not (section 8.10). */
@Composable
private fun ModelProblems(models: ModelCheck) {
    val small = MaterialTheme.typography.bodySmall
    when (models) {
        ModelCheck.Checking -> Text(stringResource(R.string.setup_models_checking), style = small)
        ModelCheck.Failed -> Text(stringResource(R.string.setup_models_check_failed), style = small)
        is ModelCheck.Done -> for (status in models.statuses) {
            for (path in status.missing) Text(stringResource(R.string.setup_models_missing, path), style = small)
            for (path in status.damaged) Text(stringResource(R.string.setup_models_damaged, path), style = small)
        }
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt`, replace

```kotlin
internal fun AboutPage(data: SettingsData, models: List<ModelStatus>?, onOpen: (Licence) -> Unit, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.settings_about), onBack)
    Text(stringResource(R.string.about_version, data.version), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
    Text(stringResource(R.string.about_models), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    if (models == null) {
        Text(stringResource(R.string.about_models_checking))
    } else {
        for (status in models) {
            for (line in modelLines(status)) Text(line)
        }
    }
```

with

```kotlin
internal fun AboutPage(data: SettingsData, models: ModelCheck, onOpen: (Licence) -> Unit, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.settings_about), onBack)
    Text(stringResource(R.string.about_version, data.version), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
    Text(stringResource(R.string.about_models), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    when (models) {
        ModelCheck.Checking -> Text(stringResource(R.string.about_models_checking))
        ModelCheck.Failed -> Text(stringResource(R.string.about_models_check_failed))
        is ModelCheck.Done -> for (status in models.statuses) {
            for (line in modelLines(status)) Text(line)
        }
    }
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="about_models_checking">Checking the model files…</string>
```

with

```xml
    <string name="about_models_checking">Checking the model files…</string>
    <string name="about_models_check_failed">Kestrel could not check the model files. It checks them again when this page opens again.</string>
```

and replace

```xml
    <string name="setup_models_checking">Checking the model files…</string>
```

with

```xml
    <string name="setup_models_checking">Checking the model files…</string>
    <string name="setup_models_check_failed">Kestrel could not check the model files. It checks them again when this page opens again.</string>
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace

```kotlin
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
import dev.kestrel.app.settings.writeLogExport
```

with

```kotlin
import dev.kestrel.app.settings.ModelCheck
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
import dev.kestrel.app.settings.checkModelFiles
import dev.kestrel.app.settings.writeLogExport
```

and replace

```kotlin
    private fun refresh() {
        // Checking the model files can take seconds the first time, so the other steps show at once, and the model
        // files keep their last known status until the check ends.
        status = SetupStatusReader(this).read().copy(models = status?.models)
        checkModels()
    }

    /** The hash cache makes a check of unchanged files cost only a few stat calls, so pages may check often. */
    private fun checkModels() {
        lifecycleScope.launch(graph.errors) {
            val models = graph.models.check()
            status = status?.copy(models = models)
        }
    }
```

with

```kotlin
    private fun refresh() {
        // Checking the model files can take seconds the first time, so the other steps show at once, and the model
        // files keep their last known status until the check ends.
        status = SetupStatusReader(this).read().copy(models = status?.models ?: ModelCheck.Checking)
        checkModels()
    }

    /** The hash cache makes a check of unchanged files cost only a few stat calls, so pages may check often. */
    private fun checkModels() {
        // After a failed check, the pages say "Checking…" again until this one ends.
        status = status?.let { if (it.models == ModelCheck.Failed) it.copy(models = ModelCheck.Checking) else it }
        lifecycleScope.launch(graph.errors) {
            val coroutine = coroutineContext
            // A failure is logged by its classes only (section 13.5), and the pages say that the check failed.
            val models = checkModelFiles({ graph.models.check() }) { error -> graph.errors.handleException(coroutine, error) }
            status = status?.copy(models = models)
        }
    }
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.models.ModelFilesTest`
Expected: `BUILD SUCCESSFUL`, with 14 tests in `ModelFilesTest` (11 and the 3 new ones).

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD SUCCESSFUL`, with 7 tests in `SetupChecklistTest` (5 and the 2 new ones).

- [ ] **Step 6: Update the settings app's device test, and add a test of the failed check**

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, in the tests `theModelStepNamesTheFilesThatAreMissingOrDamaged` and `theAboutPageShowsEachModel`, replace in each

```kotlin
        show(NOTHING_SET_UP.copy(models = listOf(READY_VOICE, speech)))
```

with

```kotlin
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, speech))))
```

In `EVERYTHING_SET_UP`, replace

```kotlin
            models = listOf(READY_VOICE),
```

with

```kotlin
            models = ModelCheck.Done(listOf(READY_VOICE)),
```

Then add this test right after the test `theModelStepSaysWhileKestrelChecks`:

```kotlin
    @Test
    fun bothPagesSayWhenTheModelCheckFailed() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Failed))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_models_check_failed)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.about_models_check_failed)).performScrollTo().assertIsDisplayed()
    }
```

- [ ] **Step 7: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.10, replace

```markdown
- Kestrel creates its `files/models` folder when it starts. The script creates the folders below it with the mode 2775,
```

with

```markdown
- Kestrel creates its `files/models` folder when it starts and before each check of the model files, so that the script also finds it after someone deleted it while Kestrel ran. The script creates the folders below it with the mode 2775,
```

(The rest of that line, "because Kestrel cannot read a folder that adb creates on Android 17 otherwise (spike S7), and it copies only the files whose size or hash on the phone differs.", stays.)

In the same section, replace

```markdown
A missing or corrupt file disables the engine that needs it, and the setup checklist names the file.
```

with

```markdown
A missing or corrupt file disables the engine that needs it, and the setup checklist names the file. While shared storage is not available, the models count as missing, and Kestrel finds them again as soon as it is back. When a check fails, the setup checklist and the About page say so, and they check again when they open again.
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (131 in `:app`, 102 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 9: Run the voice device tests, which also install the current debug build**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 60 run, 0 failed, 0 errors, 0 skipped." `ModelFolderTest` reads `graph.models.root` through the new getter and still finds `/Android/data/dev.kestrel/files/models`.

- [ ] **Step 10: Run the device tests of the settings app**

`SettingsAppTest` shows the pages with a fixed status, and `MainActivityTest` runs `MainActivity`'s real check of the model files, which now goes through `checkModelFiles`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.settings.SettingsAppTest,dev.kestrel.app.MainActivityTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result file in `app\build\outputs\androidTest-results\connected\debug\` shows 18 tests and 0 failures: `SettingsAppTest` with 14 (13 and `bothPagesSayWhenTheModelCheckFailed`) and `MainActivityTest` with 4. Kestrel stays installed with its models.

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "fix: find the models' folder at each check and create it, stop a cancelled check, and say on the pages when a check failed" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The answer to "Yes?" is held like a named utterance

**Files:**
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt` (whole file below)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt` (`Recognition`, the KDoc of `VoiceEvent.Dropped` and of the class, `accept`, `transcribes`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`State.recognition`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.3)
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/listen/EndpointerTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `Endpointer.accept(utterances: List<Utterance>, nameHits: List<Long>, hearsSpeech: Boolean, position: Long): List<Speech>`, `Speech(startSample, samples, named)`; `ListeningPipeline.recognition`; `State`, `Work`, `Mode` (package `dev.kestrel.app.orchestrator`).
- Produces: `Recognition.ANSWER`; `Endpointer.accept(utterances, nameHits, hearsSpeech, position, holdAll: Boolean = false)`; `State.recognition` is `Recognition.ANSWER` while `work == Work.AWAITING_TASK` and Kestrel does not speak. `VoiceHooks`' `VOICE_STATE` prints `recognition=answer` then, without a change of its own. Task 4 does not depend on this task.

Section 8.3 and the review of plan 2C-2's Task 1 (M-4): after "Yes?", the owner dictates the task and may pause in it. The speech detector ends an utterance after 0.7 s of silence, so a pause splits the task, and its first part runs alone. A named utterance avoids this with the 1.5 s hold; the answer to "Yes?" gets the same. While recognition is `ANSWER`, the pipeline transcribes every utterance, as with `ALL`, and the endpointer holds each one and joins the next one to it, as it does for an utterance that starts with the name. An answer is not named, so `named` keeps its own value: a joined utterance is named only when its first part was. The cost is about 1.15 s more before an answer is transcribed (1.85 s after it ends instead of 0.7 s); plan 2D measures it with the owner. The 8 s wait does not run out meanwhile: `Event.Speaking` has already switched it to the long wait of 35 s when the owner started to answer.

- [ ] **Step 1: Write the failing tests**

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/EndpointerTest.kt`, add these four tests right after the test `finishReturnsTheHeldUtterance`:

```kotlin
    @Test
    fun withTheHoldAnUtteranceWithoutTheNameWaitsAndJoinsTheNextOne() {
        // The answer to "Yes?" ends at 1.5 s, and the owner goes on at 2.9 s, 1.4 s after the end.
        assertEquals(
            none,
            endpointer.accept(listOf(utterance(0.5f, 1f, value = 0.5f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true),
        )
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(3.4f), holdAll = true))
        assertEquals(
            none,
            endpointer.accept(listOf(utterance(2.9f, 1f, value = 0.25f)), emptyList(), hearsSpeech = false, position = at(4.6f), holdAll = true),
        )

        val speech = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(5.75f), holdAll = true).single()

        assertFalse(speech.named)
        assertEquals(at(0.5f), speech.startSample)
        assertEquals(at(3.4f).toInt(), speech.samples.size)
        assertEquals(0.5f, speech.samples[0])
        assertEquals(0.25f, speech.samples.last())
    }

    @Test
    fun withTheHoldAnUtteranceWithoutTheNameGoesOutAfterTheLongerPause() {
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true)
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.3f), holdAll = true))

        val ready = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f), holdAll = true)

        assertFalse(ready.single().named)
        assertEquals(at(0.5f), ready.single().startSample)
    }

    @Test
    fun aJoinedUtteranceIsNamedOnlyWhenItsFirstPartWas() {
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true)
        // The second part starts with "Kestrel" at 2.9 s, and the spotter reports the name at 3.4 s.
        endpointer.accept(emptyList(), listOf(at(3.4f)), hearsSpeech = true, position = at(3.4f), holdAll = true)
        endpointer.accept(listOf(utterance(2.9f, 1f)), emptyList(), hearsSpeech = false, position = at(4.6f), holdAll = true)

        val speech = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(5.75f), holdAll = true).single()

        assertFalse(speech.named)
        assertEquals(at(3.4f).toInt(), speech.samples.size)
    }

    @Test
    fun aHeldAnswerStillWaitsForItsPauseWhenTheHoldEnds() {
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true)

        // Kestrel no longer waits for the task, so recognition is no longer ANSWER.
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.3f)))
        val ready = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f))

        assertFalse(ready.single().named)
    }
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, add this test right before the test `aFailedTranscriptionIsReportedAndTheNextOneWorks`:

```kotlin
    @Test
    fun anAnswerToYesIsHeldAndJoinedToTheNextUtterance() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.ANSWER

        // "Open the ..." from 0.5 s to 1.5 s, and "... settings" from 2.9 s, after a pause of 1.4 s.
        pipeline.feedUntil(2.2f)
        detector.next.addLast(listOf(utterance(0.5f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(3.2f)
        detector.hearsSpeech = true
        pipeline.feedUntil(4.6f)
        detector.hearsSpeech = false
        detector.next.addLast(listOf(utterance(2.9f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(6.5f)

        // One transcript of both parts and the pause between them: 1 s, 1.4 s and 1 s.
        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertEquals("heard 54400", heard.text)
        assertFalse(heard.named)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, in the test `theNameAloneAsksAndTheNextUtteranceIsTheTask`, replace

```kotlin
        val ready = spoken.state
        assertEquals(Recognition.ALL, ready.recognition)
```

with

```kotlin
        val ready = spoken.state
        assertEquals(Recognition.ANSWER, ready.recognition)
```

and add this test right before the test `theWaitForATaskEnds`:

```kotlin
    @Test
    fun theAnswerToYesIsHeldLikeANamedUtterance() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)

        assertEquals(Recognition.ANSWER, waiting.recognition)
        assertEquals(Recognition.ANSWER, paused.copy(work = Work.AWAITING_TASK, wait = 1).recognition)
        // While "Yes?" is spoken, nothing is transcribed, and while a task runs, every utterance goes out at once.
        assertEquals(Recognition.NONE, waiting.copy(speeches = 1).recognition)
        assertEquals(Recognition.ALL, listening.copy(work = Work.RUNNING_TASK, task = 1).recognition)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, in the test `aReplyOrTaskThatCannotStartDoesNotLeaveKestrelWaitingForIt`, replace

```kotlin
        assertEquals(Recognition.ALL, orchestrator.state.value.recognition)
```

with

```kotlin
        // "Yes?" could not be spoken, so Kestrel waits for the task and holds the answer.
        assertEquals(Recognition.ANSWER, orchestrator.state.value.recognition)
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, in the test `kestrelSaysYesAndTheEndOfTheReplyTurnsRecognitionOnAgain`, replace

```kotlin
        assertEquals(listOf("recognition NONE", "recognition ALL"), log)
```

with

```kotlin
        assertEquals(listOf("recognition NONE", "recognition ANSWER"), log)
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.EndpointerTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD FAILED`, with "No parameter with name 'holdAll' found" and "Unresolved reference 'ANSWER'".

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'ANSWER'".

- [ ] **Step 3: Hold every utterance when asked to**

Replace the whole of `core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt` with:

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
 * silence. When the keyword spotter heard "Kestrel" at its start, or while the caller asks to hold every utterance, as
 * it does for the answer to "Yes?", the limit is 1.5 s instead: the endpointer holds the utterance, and joins the next
 * one to it when that one starts less than 1.5 s after its end, so that the owner can pause while dictating a task. A
 * joined utterance is named when its first part was. A held utterance goes out at the latest 30 s after its start, also
 * while the owner still speaks, and a joined utterance lasts at most 30 s. What comes after that, such as the next part
 * of long speech that the detector cut at 30 s, is a separate utterance. Not thread-safe: feed it from one thread.
 */
class Endpointer {
    private val names = ArrayDeque<Long>()
    private var held: Speech? = null

    /**
     * Takes the utterances that the speech detector returned for the audio up to [position], the positions at which the
     * keyword spotter heard "Kestrel" in the same audio, and whether the detector hears speech now, and returns the
     * utterances that are complete. All positions count samples since the audio started. With [holdAll], each new
     * utterance is held as one that starts with the name is; an utterance that is already held waits for its pause
     * either way.
     */
    fun accept(
        utterances: List<Utterance>,
        nameHits: List<Long>,
        hearsSpeech: Boolean,
        position: Long,
        holdAll: Boolean = false,
    ): List<Speech> {
        names.addAll(nameHits)
        val ready = mutableListOf<Speech>()
        for (utterance in utterances) {
            val speech = Speech(utterance.startSample, utterance.samples, startsWithName(utterance))
            val joined = held?.let { join(it, speech) }
            if (joined != null) {
                held = joined
            } else {
                held?.let { ready += it }
                val hold = speech.named || holdAll
                held = if (hold) speech else null
                if (!hold) ready += speech
            }
        }
        val waiting = held
        if (waiting != null) {
            val pauseIsOver = !hearsSpeech && position - waiting.endSample >= RELEASE_AFTER
            val tooLong = position - waiting.startSample >= LONGEST
            if (pauseIsOver || tooLong) {
                ready += waiting
                held = null
            }
        }
        while (names.isNotEmpty() && names.first() < position - KEEP_NAMES) names.removeFirst()
        return ready
    }

    /**
     * Ends the audio: returns the utterance that the endpointer still holds, if any, and forgets the name hits, so that
     * positions may start again from 0.
     */
    fun finish(): List<Speech> {
        val last = listOfNotNull(held)
        held = null
        names.clear()
        return last
    }

    private fun startsWithName(utterance: Utterance): Boolean =
        names.any { it >= utterance.startSample && it <= utterance.startSample + NAME_WINDOW }

    /**
     * [earlier], silence for the pause and [later] in one utterance, named when [earlier] is, or null when the pause or
     * the result is too long.
     */
    private fun join(earlier: Speech, later: Speech): Speech? {
        val pause = maxOf(0L, later.startSample - earlier.endSample)
        val size = earlier.samples.size + pause + later.samples.size
        if (pause >= NAMED_PAUSE || size > LONGEST) return null
        val samples = FloatArray(size.toInt())
        earlier.samples.copyInto(samples)
        later.samples.copyInto(samples, (earlier.samples.size + pause).toInt())
        return Speech(earlier.startSample, samples, named = earlier.named)
    }

    private companion object {
        // Design spec, section 8.3.
        val NAMED_PAUSE = samples(1.5f)
        val LONGEST = samples(30f)

        // The speech detector dates an utterance's start two windows of 512 samples and its minimum speech duration of
        // 0.25 s (0.314 s in all) before the window in which it reports speech. So speech that starts less than 1.5 s
        // after a held utterance's end shows in hearsSpeech less than 1.814 s after that end.
        val RELEASE_AFTER = NAMED_PAUSE + samples(0.35f)

        // The name may follow a word and a short pause ("Hey, Kestrel, ..."). "Kestrel" takes about half a second, and
        // the spotter reports it 0.3-0.55 s after the word ends, so its hit comes up to about 1.8 s after the start.
        val NAME_WINDOW = samples(2f)

        // An utterance arrives at most 30 s and the detector's 0.7 s of silence (plus the window that ends it) after its
        // start, so a hit more than 31 s old can no longer name one.
        val KEEP_NAMES = samples(31f)

        fun samples(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()
    }
}
```

- [ ] **Step 4: Add `Recognition.ANSWER` to the pipeline**

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, replace

```kotlin
/** Which utterances the pipeline transcribes (design spec, sections 8.4 and 8.5). */
enum class Recognition {
    /** Every utterance, while Kestrel listens. */
    ALL,

```

with

```kotlin
/** Which utterances the pipeline transcribes (design spec, sections 8.3, 8.4 and 8.5). */
enum class Recognition {
    /** Every utterance, while Kestrel listens. */
    ALL,

    /**
     * Every utterance, while Kestrel waits for the answer to "Yes?". The endpointer holds each one as it holds an
     * utterance that starts with "Kestrel", and joins the next one to it when that one starts within 1.5 s, so that the
     * owner can pause while dictating the task (section 8.3).
     */
    ANSWER,

```

replace

```kotlin
     * An utterance ended that recognition would have transcribed (while recognition is [Recognition.ALL], or
     * [Recognition.NAMED] and the utterance began with the name), but nothing of it was left to transcribe: it began
     * while Kestrel spoke, and after the cut at the resume less than 0.25 s remained, or the owner's "Kestrel" broke into
     * it (section 8.4).
```

with

```kotlin
     * An utterance ended that recognition would have transcribed (while recognition is [Recognition.ALL] or
     * [Recognition.ANSWER], or [Recognition.NAMED] and the utterance began with the name), but nothing of it was left
     * to transcribe: it began while Kestrel spoke, and after the cut at the resume less than 0.25 s remained, or the
     * owner's "Kestrel" broke into it (section 8.4).
```

replace

```kotlin
 * detector and the keyword spotter hear every frame; the endpointer decides when an utterance has ended, and the
 * recogniser transcribes the utterances that [recognition] asks for, one at a time in [recognitionContext], with the
```

with

```kotlin
 * detector and the keyword spotter hear every frame; the endpointer decides when an utterance has ended, and while
 * [recognition] is [Recognition.ANSWER] it holds every utterance as it holds one that starts with the name. The
 * recogniser transcribes the utterances that [recognition] asks for, one at a time in [recognitionContext], with the
```

replace

```kotlin
        for (speech in endpointer.accept(utterances, names, hears, position)) {
```

with

```kotlin
        for (speech in endpointer.accept(utterances, names, hears, position, holdAll = wanted == Recognition.ANSWER)) {
```

and replace

```kotlin
        Recognition.ALL -> true
```

with

```kotlin
        Recognition.ALL, Recognition.ANSWER -> true
```

- [ ] **Step 5: Ask for `ANSWER` while Kestrel waits for a task**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    /** Which utterances the pipeline transcribes (sections 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
            work != Work.IDLE || mode == Mode.LISTENING -> Recognition.ALL
```

with

```kotlin
    /** Which utterances the pipeline transcribes, and whether it holds them (sections 8.3, 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
            // The owner dictates the task in the answer to "Yes?", and may pause in it.
            work == Work.AWAITING_TASK -> Recognition.ANSWER
            work != Work.IDLE || mode == Mode.LISTENING -> Recognition.ALL
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.EndpointerTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD SUCCESSFUL`, with 14 tests in `EndpointerTest` (10 and 4) and 27 in `ListeningPipelineTest` (26 and 1).

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 38 tests in `RulesTest` (37 and 1), 8 in `OrchestratorTest` and 52 in `ListeningTest` (one line changed in each of the last two).

- [ ] **Step 7: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.3, replace

```markdown
When the keyword spotter hears "Kestrel" at the start of an utterance, the limit becomes 1.5 s, so that the owner can pause while dictating a task:
```

with

```markdown
When the keyword spotter hears "Kestrel" at the start of an utterance, and for the answer to "Yes?", the limit becomes 1.5 s, so that the owner can pause while dictating a task:
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (132 in `:app`, 107 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 9: Run the voice device tests**

`ConversationTest` answers "Yes?" with "Open the settings.", which now goes out about 1.15 s later; its wait of 30 s covers that.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 60 run, 0 failed, 0 errors, 0 skipped."

- [ ] **Step 10: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 11: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/listen/Endpointer.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/EndpointerTest.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(voice): hold the answer to 'Yes?' for 1.5 s like a named utterance, so that a pause does not split the task" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Boost words from the installed apps

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/BoostWordSource.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt` (the KDoc, the constructor, `pipeline`, the companion object, `open`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, a failure reporter, `listening`, whose sessions read the app names in parallel with the engine loads)
- Modify: `app/src/main/AndroidManifest.xml` (`<queries>`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.3)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/BoostWordSourceTest.kt` (JVM, new), `app/src/androidTest/kotlin/dev/kestrel/app/voice/AppNamesTest.kt` (device, new), `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt` (device, one more test)

**Interfaces:**
- Consumes: `ListeningPipeline(detector, spotter, recognizer, boostWords: suspend () -> List<String>, isOwnVoice, scope, recognitionContext)`; `VoiceSession(context, detector, spotter, recognizer, synthesizer, microphone, listener)` and `VoiceSession.open(context, engines, microphone, listener, loadSpeechDetector, loadKeywordSpotter, loadSynthesizer, loadRecognizer)`; `AppGraph.models` from Task 1 (unchanged here).
- Produces: `internal data class AppLabel(val label: String, val system: Boolean)`, whose `toString` prints only the label's length; `internal fun interface AppLabels { fun read(): List<AppLabel> }`; `internal class LauncherAppLabels(context: Context) : AppLabels`, which marks an app as a system app when its `ApplicationInfo.flags` has `FLAG_SYSTEM` or `FLAG_UPDATED_SYSTEM_APP`; `internal class BoostWordSource(labels: AppLabels, report: (String, Throwable) -> Unit)` with `suspend fun words(): List<String>` and the constants `BoostWordSource.NAME = "Kestrel"` and `BoostWordSource.SHORTEST = 3`; `VoiceSession`'s constructor gains `words: List<String> = listOf(BoostWordSource.NAME)` as its last parameter, and `VoiceSession.open` gains `boostWords: suspend () -> List<String> = { listOf(BoostWordSource.NAME) }` after `listener`, which it asks once, after the recogniser has loaded, so that the device tests' calls stay as they are. `AppGraph` gains `private val reportFailure: (String, Throwable) -> Unit`, which Task 4 does not change.

Section 8.3 lists the sources of boost words: the labels on the screen, the names of the installed apps, the command vocabulary, "Kestrel", the owner's aliases and contact names. Sub-project 2 has two of them: "Kestrel" and the names of the installed apps. The labels on the screen and the command vocabulary come with sub-project 3, and the aliases and contacts with sub-project 5; Kestrel's own phrases ("wake up", "stop listening") are common words that need no boost. `BoostWordSource` returns "Kestrel" first, then the labels of the apps that the owner installed, and then the labels of the system apps, each group in the order in which the package manager lists the apps, without repeats (ignoring case) and without names that have fewer than 3 letters or digits, because a one-letter name such as "X" would pull many words towards it. An app counts as a system app when its `ApplicationInfo` has `FLAG_SYSTEM`; an updated system app (`FLAG_UPDATED_SYSTEM_APP`) counts as one too. The owner's apps come first because sherpa-onnx gets at most 100 phrases (`BoostWords.MAX_PHRASES` in `:core:voice`), and the system apps have common names ("Phone", "Camera", "Settings") that need no boost, while the owner's apps have the rare names that the recogniser misses ("Revolut", "Glovo"); so the cap drops system apps first. `AppGraph` starts reading them, on `Dispatchers.IO`, as the voice session starts to open, and `VoiceSession.open` waits for them after the recogniser has loaded, so the read costs no time unless it takes longer than the engine loads. The session boosts the same list before each utterance, so a change of the installed apps counts from the next time that Kestrel turns on. When the labels cannot be read, the failure is reported by its classes only and the session boosts "Kestrel" alone. "Kestrel" comes first, so the cap never drops it. Since Android 11 an app sees the other apps' launcher activities only when its manifest declares the launcher's intent in `<queries>` (section 13.6 already says so); no permission is needed, and nothing leaves the phone. Android shows a few packages to every app, among them the Settings app (`config_forceQueryablePackages` in the framework's configuration), so the device test checks the `<queries>` element with the fixture app "Kestrel fixtures" (`:testapp`), which Gradle installs before `:app`'s device tests and which Kestrel sees only through `<queries>`; Step 6 runs it once before Step 7 adds the element, to see it fail. Nothing logs a boost word or a label, and no class that holds them prints them; the device test logs only the counts and the time of the read.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/listen/BoostWordSourceTest.kt`:

```kotlin
package dev.kestrel.app.listen

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostWordSourceTest {
    private val reports = mutableListOf<Pair<String, Throwable>>()

    private fun source(labels: AppLabels) = BoostWordSource(labels) { what, error -> reports += what to error }

    private fun installed(label: String) = AppLabel(label, system = false)

    private fun system(label: String) = AppLabel(label, system = true)

    @Test
    fun kestrelComesFirstAndThenTheNamesOfTheApps() = runTest {
        val labels = listOf("Settings", " Maps ", "Kestrel", "settings", "X", "Mi", "TV 2", "A.I.", "Viber").map { installed(it) }

        val words = source { labels }.words()

        // "X", "Mi" and "A.I." have fewer than three letters or digits; Kestrel's own app and "settings" are repeats.
        assertEquals(listOf("Kestrel", "Settings", "Maps", "TV 2", "Viber"), words)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun theOwnersAppsComeBeforeTheSystemAppsSoThatTheCapDropsSystemAppsFirst() = runTest {
        val words = source { listOf(system("Phone"), installed("Revolut"), system("Camera"), installed("Glovo")) }.words()

        // Each group keeps the order in which the package manager lists the apps.
        assertEquals(listOf("Kestrel", "Revolut", "Glovo", "Phone", "Camera"), words)

        // sherpa-onnx keeps the first 100 phrases (BoostWords.MAX_PHRASES in :core:voice): "Kestrel" and 99 of the owner's
        // apps leave no room for a system app, wherever the package manager lists it.
        val owners = (1..99).map { installed("App $it") }
        val kept = source { listOf(system("Phone")) + owners + system("Camera") }.words().take(100)

        assertEquals(listOf("Kestrel") + owners.map { it.label }, kept)
    }

    @Test
    fun namesThatCannotBeReadLeaveKestrelAlone() = runTest {
        val words = source { throw SecurityException("Not allowed") }.words()

        assertEquals(listOf("Kestrel"), words)
        assertEquals(listOf("Reading the names of the apps"), reports.map { it.first })
        // Not the same instance: in Gradle's tests, kotlinx.coroutines hands on a copy of the error across withContext.
        assertTrue(reports.single().second is SecurityException)
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/AppNamesTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.LauncherAppLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names of the apps that Kestrel boosts (design spec, section 8.3), read from the device's launcher. It needs no
 * models. Its messages and its log line give only counts, positions, times and classes, never a name (section 13.5).
 */
class AppNamesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theBoostWordsNameTheAppsThatTheLauncherShows() = runBlocking {
        val failures = mutableListOf<Throwable>()

        val started = SystemClock.elapsedRealtime()
        val labels = LauncherAppLabels(context).read()
        val millis = SystemClock.elapsedRealtime() - started
        val words = BoostWordSource(LauncherAppLabels(context)) { _, error -> failures += error }.words()
        VoiceTests.log(
            "App names: ${labels.size} launcher apps (${labels.count { it.system }} system apps) read in $millis ms; " +
                "${words.size} boost words",
        )

        assertTrue("Reading the names failed: ${failures.map { it.javaClass.name }}", failures.isEmpty())
        assertTrue("The first of ${words.size} boost words is not Kestrel", words.first() == BoostWordSource.NAME)
        // Gradle installs the fixture app (:testapp) before :app's device tests. Kestrel sees it only through the
        // <queries> element of its manifest. Android shows the Settings app to every app, so Settings cannot show it.
        val fixtures = words.indexOf(FIXTURES)
        // The Settings app's label as this device shows it, so that the test also holds in another language.
        val packages = context.packageManager
        val settingsLabel = checkNotNull(
            packages.getLaunchIntentForPackage(SETTINGS_PACKAGE)
                ?.let { packages.resolveActivity(it, PackageManager.ResolveInfoFlags.of(0)) }
                ?.loadLabel(packages)
                ?.toString(),
        ) { "The Settings app has no launcher activity" }
        val settings = words.indexOf(settingsLabel)
        assertTrue("The ${words.size} boost words do not name the fixture app", fixtures > 0)
        assertTrue("The ${words.size} boost words do not name the Settings app", settings > 0)
        // The owner's apps come before the system apps.
        assertTrue("The fixture app is boost word $fixtures, after Settings at $settings", fixtures < settings)
        assertTrue(
            "Of ${labels.size} launcher apps, ${labels.count { it.system }} are system apps, and Settings is not one of them",
            labels.any { it.label == settingsLabel && it.system },
        )
    }

    private companion object {
        // The label in testapp/src/main/AndroidManifest.xml, which no translation changes.
        const val FIXTURES = "Kestrel fixtures"
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt`, add this test right before the test `openClosesTheLoadedEnginesWhenALaterLoadFails`:

```kotlin
    @Test
    fun theSessionsBoostWordsReachTheRecognizer() = runBlocking {
        val session = VoiceSession.open(
            context, VoiceTests.graph.voice, microphone, listener,
            boostWords = { listOf("Kestrel", "Viber") },
            loadSpeechDetector = { detector },
            loadKeywordSpotter = { spotter },
            loadSynthesizer = { synthesizer },
            loadRecognizer = { recognizer },
        )
        try {
            session.recognition = Recognition.ALL
            session.startMicrophone()
            detector.next.add(Utterance(0, FloatArray(seconds(0.5f).toInt())))
            microphone.feed(0.02f)
            listener.await { it is Event.Heard }

            assertEquals(listOf(listOf("Kestrel", "Viber")), recognizer.boostWords.toList())
        } finally {
            session.close()
        }
    }
```

and in the same file, in `FakeRecognizer`, replace

```kotlin
        @Volatile
        var fails = false

        override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
            checkOpen()
            if (fails) throw FakeFailure()
```

with

```kotlin
        @Volatile
        var fails = false

        /** The boost words of each call of [transcribe]. */
        val boostWords = CopyOnWriteArrayList<List<String>>()

        override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
            checkOpen()
            this.boostWords += boostWords
            if (fails) throw FakeFailure()
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.BoostWordSourceTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'AppLabels'", "Unresolved reference 'AppLabel'" and "Unresolved reference 'BoostWordSource'".

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with "Unresolved reference 'BoostWordSource'", "Unresolved reference 'LauncherAppLabels'" and "No parameter with name 'boostWords' found".

- [ ] **Step 3: Write the source of boost words**

Create `app/src/main/kotlin/dev/kestrel/app/listen/BoostWordSource.kt`:

```kotlin
package dev.kestrel.app.listen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app's name as the launcher shows it, and whether the app came with the phone ([system]). */
internal data class AppLabel(val label: String, val system: Boolean) {
    // An app's name says what the owner uses (design spec, section 13.5).
    override fun toString() = "AppLabel(${label.length} characters, system=$system)"
}

/** Reads the names of the apps on the phone. */
internal fun interface AppLabels {
    fun read(): List<AppLabel>
}

/**
 * The labels of the apps that the launcher shows, in the order in which the package manager lists them. An app is a
 * system app when its [ApplicationInfo] has [ApplicationInfo.FLAG_SYSTEM], or [ApplicationInfo.FLAG_UPDATED_SYSTEM_APP]
 * for a system app that an update replaced. Since Android 11, Kestrel sees the other apps' launcher activities only
 * because its manifest declares the launcher's intent in `<queries>` (design spec, section 13.6); Android shows a few
 * packages, such as the Settings app, to every app. It asks the package manager, so call it off the main thread.
 */
internal class LauncherAppLabels(private val context: Context) : AppLabels {
    override fun read(): List<AppLabel> {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0)).map {
            AppLabel(it.loadLabel(packages).toString(), system = it.activityInfo.applicationInfo.flags and SYSTEM != 0)
        }
    }

    private companion object {
        const val SYSTEM = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}

/**
 * The boost words of a voice session (design spec, section 8.3): "Kestrel" first, then the names of the apps that the
 * owner installed, and then those of the system apps, each group in the order in which [labels] reads them. sherpa-onnx
 * gets at most 100 phrases, so the cap drops system apps first: their common names, such as "Phone" or "Camera", need
 * no boost, while the owner's apps have the rare names that the recogniser misses, such as "Revolut". Sub-project 3
 * adds the labels on the screen and the command vocabulary, and sub-project 5 the owner's aliases and contact names. A
 * name with fewer than [SHORTEST] letters or digits, such as "X", is left out, because so short a boost would pull many
 * words towards it, and a repeated name counts once, ignoring case. When the names cannot be read, [report] gets the
 * error, which it must log by its classes only (section 13.5), and the words are "Kestrel" alone. Never log a boost
 * word or an app's name.
 */
internal class BoostWordSource(private val labels: AppLabels, private val report: (String, Throwable) -> Unit) {
    /** Reads the names of the apps on [Dispatchers.IO]. A voice session asks once, when it opens. */
    suspend fun words(): List<String> {
        val apps = try {
            withContext(Dispatchers.IO) { labels.read() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Reading the names of the apps", e)
            emptyList()
        }
        // The owner's apps first; filter and filterNot keep the package manager's order within each group.
        val names = (apps.filterNot { it.system } + apps.filter { it.system })
            .map { it.label.trim() }
            .filter { name -> name.count { it.isLetterOrDigit() } >= SHORTEST }
        return (listOf(NAME) + names).distinctBy { it.lowercase() }
    }

    companion object {
        const val NAME = "Kestrel"
        const val SHORTEST = 3
    }
}
```

- [ ] **Step 4: Boost the words in each voice session**

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, replace

```kotlin
 * transcription runs one utterance at a time on [Dispatchers.Default], and the events go out from coroutines of the
 * session. The only boost word is "Kestrel" for now.
 */
```

with

```kotlin
 * transcription runs one utterance at a time on [Dispatchers.Default], and the events go out from coroutines of the
 * session. [words] are the boost words of every utterance of the session (section 8.3).
 */
```

replace

```kotlin
    private val microphone: Microphone,
    private val listener: SessionListener,
) : Session {
```

with

```kotlin
    private val microphone: Microphone,
    private val listener: SessionListener,
    private val words: List<String> = listOf(BoostWordSource.NAME),
) : Session {
```

replace

```kotlin
        boostWords = { BOOST_WORDS },
```

with

```kotlin
        boostWords = { words },
```

replace

```kotlin
    companion object {
        private val BOOST_WORDS = listOf("Kestrel")

        /**
```

with

```kotlin
    companion object {
        /**
```

replace

```kotlin
         * otherwise or is cancelled. The loaders are parameters only for the tests.
         */
        suspend fun open(
            context: Context,
            engines: VoiceEngines,
            microphone: Microphone,
            listener: SessionListener,
            loadSpeechDetector: suspend () -> SpeechDetector = { engines.speechDetector() },
```

with

```kotlin
         * otherwise or is cancelled. It asks [boostWords] once, after the recogniser has loaded and inside the same clean-up,
         * so that the caller can read the words while the engines load, and a cancellation while it waits still closes the
         * engines. The session keeps the words, so that a change of the installed apps counts from the next time that
         * Kestrel turns on. The loaders are parameters only for the tests.
         */
        suspend fun open(
            context: Context,
            engines: VoiceEngines,
            microphone: Microphone,
            listener: SessionListener,
            boostWords: suspend () -> List<String> = { listOf(BoostWordSource.NAME) },
            loadSpeechDetector: suspend () -> SpeechDetector = { engines.speechDetector() },
```

and replace

```kotlin
                val recognizer = loadRecognizer().also { loaded += it }
                return VoiceSession(context, detector, spotter, recognizer, synthesizer, microphone, listener)
```

with

```kotlin
                val recognizer = loadRecognizer().also { loaded += it }
                val words = boostWords()
                return VoiceSession(context, detector, spotter, recognizer, synthesizer, microphone, listener, words)
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.Listening
```

with

```kotlin
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.listen.Listening
```

replace

```kotlin
import kotlinx.coroutines.SupervisorJob
```

with

```kotlin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
```

replace

```kotlin
    /** Background work, whose failures go to [errors]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
```

with

```kotlin
    /** Background work, whose failures go to [errors]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)

    /**
     * Logs a failure that Kestrel handled, after a fixed description of what failed: only the error's classes and stack
     * frames, one entry for each error of the chain of causes, as [errors] does (section 13.5).
     */
    private val reportFailure: (String, Throwable) -> Unit = { what, error ->
        for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
            Log.w(TAG, if (index == 0) "$what failed: $part" else part)
        }
    }
```

replace

```kotlin
            sessions = object : VoiceSessions {
                override suspend fun open(microphone: Microphone, listener: SessionListener): Session =
                    VoiceSession.open(appContext, voice, microphone, listener)
            },
```

with

```kotlin
            sessions = object : VoiceSessions {
                override suspend fun open(microphone: Microphone, listener: SessionListener): Session = coroutineScope {
                    // "Kestrel" and the names of the apps that the launcher shows, read once while the engines load.
                    // words() throws only a cancellation, so it cannot fail this scope; a failed load cancels it.
                    val apps = BoostWordSource(LauncherAppLabels(appContext), reportFailure)
                    val words = async { apps.words() }
                    VoiceSession.open(appContext, voice, microphone, listener, boostWords = { words.await() })
                }
            },
```

and replace

```kotlin
            report = { what, error ->
                for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
                    Log.w(TAG, if (index == 0) "$what failed: $part" else part)
                }
            },
```

with

```kotlin
            report = reportFailure,
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.BoostWordSourceTest :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`, with 3 tests in `BoostWordSourceTest`, and the device tests compile.

- [ ] **Step 6: Run the device test of the app names before the manifest has `<queries>`**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.voice.AppNamesTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD FAILED`, with 1 failed test, `AppNamesTest > theBoostWordsNameTheAppsThatTheLauncherShows`, and the message "The N boost words do not name the fixture app", where N is a small number: without `<queries>`, Kestrel sees only itself and the few packages that Android shows to every app. If the test passes instead, an app that started or bound one of Kestrel's components since the emulator booted has become visible without `<queries>`; reboot the emulator and repeat this step.

- [ ] **Step 7: Let Kestrel see the launcher's apps**

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <application
```

with

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <!-- Kestrel boosts the names of the apps that the launcher shows (design spec, sections 8.3 and 13.6). Since Android
         11 an app sees the other apps' launcher activities only when it declares their intent here. -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>

    <application
```

- [ ] **Step 8: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.3, in the bullet on boost words, replace

```markdown
the owner's aliases, and relevant contact names (names on screen, favourites and recent contacts). sherpa-onnx applies
```

with

```markdown
the owner's aliases, and relevant contact names (names on screen, favourites and recent contacts). Sub-project 2 boosts "Kestrel" and the names of the apps that the launcher shows. Kestrel reads these names when its voice session opens, that is when it turns on, so an app that was installed later counts from the next start. Kestrel leaves out names with fewer than three letters or digits, because so short a boost pulls many words towards it. It passes at most 100 phrases to sherpa-onnx, so it lists the apps that the owner installed before the system apps, whose common names, such as "Phone" or "Camera", need no boost. The labels on screen and the command vocabulary come with sub-project 3, and the aliases and the contact names with sub-project 5. sherpa-onnx applies
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (135 in `:app`, 107 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile. The audit does not look at the manifest's `<queries>`.

- [ ] **Step 10: Run the voice device tests**

From now on, every voice session boosts the emulator's app names, among them "Kestrel fixtures" and "Settings". If `ConversationTest` misses the name or the task after this task, while it passed after Task 2, the boost words are a candidate cause.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 62 run, 0 failed, 0 errors, 0 skipped.", including `AppNamesTest` and `VoiceSessionFailuresTest.theSessionsBoostWordsReachTheRecognizer`, and a line "App names: N launcher apps (M system apps) read in T ms; W boost words" in the printed log. If `AppNamesTest` fails with "do not name the fixture app", check that `app\src\main\AndroidManifest.xml` has the `<queries>` element of Step 7, outside `<application>`. Never print the list of names to find out.

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/BoostWordSource.kt app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/AndroidManifest.xml app/src/test/kotlin/dev/kestrel/app/listen/BoostWordSourceTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/AppNamesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): boost the names of the apps that the launcher shows, read while the engines load" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The listening controller

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` (`events`, the loop, `send`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (imports, `ListeningTexts`' KDoc, the class's KDoc, two fields, `follow`, `start`, `foreground`, `shutdown` and its comment, a new `restartMicrophone`, `Listener`, a companion object)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 14.6)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `Listening`'s internal constructor (unchanged), `ServiceControl.start()` and `stop()`, `SessionListener.send(event)`, `microphoneFailed(error)` and `transcriptionFailed(error)`, `Session.startMicrophone()` and `stopMicrophone()`, `ListeningTexts.MICROPHONE_FAILED`; in the tests `FakeSessions`, `FakeSession` (with its `listener`, `closing` and `microphoneFailure`), `FakeServices`, `listening()`, `listeningWithSession()` and `service`.
- Produces: `Orchestrator.send(event: Event, valid: () -> Boolean)`, which the Orchestrator handles only if `valid()` still returns true when the event's turn comes; `Orchestrator.send(event: Event)` stays and sends with a check that is always true. No other new name. `Listening` asks Android for the service inside `follow()`; its `Listener` sends every event with the check `{ active }`; a microphone error waits `MICROPHONE_RESTART_MILLIS` (1 000 ms) and starts the microphone again, and turns Kestrel off when it comes within `MICROPHONE_FAILURES_MILLIS` (60 000 ms) of such a restart. Both constants are private to `Listening`.

The final review of plan 2C-1 (`.superpowers/sdd/final-review-2c1-report.md`, "For plan 2C-2"), items 11, 12 and 15. Item 16 needs no change ("Not in this plan").

- **Item 15, the events of a closing session.** `Listener.send` checked `active` on the session's thread, so an event that passed the check just as Kestrel turned off could act on the next state. Now the Orchestrator makes the check: `Orchestrator.send(event, valid)` puts the event into the Orchestrator's channel at once, with a check that the Orchestrator runs on `thread` when the event's turn comes, after every event that was sent before it. `Listener.send` passes `{ active }`. `shutdown()` runs while the Orchestrator handles the `TurnOff` before such an event, and sets `active` to false, so no event of a closing session acts on the next state, and the events keep the order in which they were sent. Handing the event to `thread` first, and checking there, would not be enough: an owner's TurnOff and a new Toggle that `Listening.send` puts into the channel while `thread` runs a blocking task could overtake the session's event, which would then act on the new state (the plan's review, M2).
- **Item 11, a start behind a closing session.** `start()` asked Android for the service only after it had the `lifecycle` lock, so a start while a session closed (seconds, if a transcription ran) waited for the close, and the short exemption of a tap on the tile (15 s) or of a notification action could run out before Android was asked. Now `start()` asks at once, on `thread`, with `requested = true`, and only `open()` waits for the lock, as it already did. The paths still hold: the shutdown's stop is decided under the lock and sees `requested`, so it does not stop the service that the new start asked for; a stop that `ServiceControl.stop()` has already posted is undone by the newer start, because `stopSelfResult(lastStartId)` fails once Android has given the service a newer start; and a service that a shutdown stopped before the start reached Android is created again, while the old instance's `onDestroy` is ignored because it is no longer `running`. Since `start()` sets `requested` together with `STARTING`, `foreground()` takes every report in `STARTING` as the new start's report: while a session still closes, the running instance gets the new start, so the report may come from it, and it keeps running.
- **Item 12, a read error of the microphone.** A read error, such as `ERROR_DEAD_OBJECT` after Android's audio server restarted, turned Kestrel off with `MICROPHONE_FAILED`. Now the controller stops the microphone as before, reports the error by its classes, waits 1 s on its own thread, and starts the microphone again if the state still wants it. While it waits, `follow()` leaves the microphone off, so that a change of the state cannot start it early. A turn-off or a new session cancels the wait in `shutdown()`. A restart that fails, or a microphone that fails again within 60 s of a restart, turns Kestrel off with `MICROPHONE_FAILED` as before.

Three tests of `ListeningTest` pinned the old order of a start behind a close, and change with it: `aNewStartWaitsForTheCloseInProgress` becomes `aNewStartAsksForTheServiceAtOnceAndOpensItsSessionAfterTheClose`, `aStopThatWaitedBehindANewStartLeavesTheNewServiceRunning` becomes `offAndOnTwiceWhileASessionClosesLeaveTheNewServiceRunning` and no longer expects a stop and a second start after the close, and `aReportFromTheClosingServiceIsNotTakenForTheNewStart`, whose old service no longer gets stopped and destroyed, becomes `aReportOfTheClosingServiceBeforeANewStartDoesNotStopTheNewService`. `offAndOnInOneRunOfTheOrchestratorStartTheServiceAgain` and the other tests of stops and starts pass unchanged. `aMicrophoneThatFailsTurnsKestrelOff` gives way to seven tests of the restart.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
import kotlinx.coroutines.test.TestScope
```

with

```kotlin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
```

Replace the whole test

```kotlin
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
```

with

```kotlin
    @Test
    fun aNewStartAsksForTheServiceAtOnceAndOpensItsSessionAfterTheClose() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        // At once, so that the short exemption of a tap on the tile cannot run out while a transcription ends the close.
        assertEquals(listOf("close", "start"), log)

        // The running service, which the shutdown has not stopped yet, takes the new start and reports.
        listening.onForeground(service)
        runCurrent()
        assertEquals(1, sessions.opened.size)

        closing.complete(Unit)
        runCurrent()
        // The close does not stop the service that the new start asked for, and the new session opens after the close.
        assertEquals(listOf("close", "start", "closed", "open", "recognition ALL", "microphone on"), log)
        assertTrue(listening.sessionOpen.value)
    }
```

Replace the whole test

```kotlin
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
```

with

```kotlin
    @Test
    fun aMicrophoneThatFailsStartsAgainAfterASecond() = runTest {
        val listening = listeningWithSession()
        log.clear()

        sessions.opened.single().listener.microphoneFailed(readError())
        runCurrent()
        assertEquals(listOf("microphone off"), log)

        // A change of the state during the wait does not start the microphone early.
        listening.send(Event.Pause)
        runCurrent()
        advanceTimeBy(999)
        assertEquals(listOf("microphone off", "recognition NAMED"), log)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("microphone off", "recognition NAMED", "microphone on"), log)
        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertEquals("The microphone", reports.single().first)
    }

    @Test
    fun aMicrophoneThatFailsAgainWithinAMinuteOfItsRestartTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener
        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        log.clear()

        // 59 s after the restart.
        advanceTimeBy(59_000)
        events.microphoneFailed(readError())
        runCurrent()

        assertEquals(listOf("microphone off", "close", "closed", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.MICROPHONE_FAILED, shown.last())
        assertEquals(listOf("The microphone", "The microphone"), reports.map { it.first })
    }

    @Test
    fun aMicrophoneThatFailsAMinuteAfterItsRestartStartsAgain() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener
        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        log.clear()

        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("microphone off", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aRestartThatFailsTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        session.listener.microphoneFailed(readError())
        runCurrent()
        session.microphoneFailure = readError()
        log.clear()

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("microphone on", "microphone off", "close", "closed", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.MICROPHONE_FAILED, shown.last())
        assertEquals(listOf("The microphone", "Starting the microphone"), reports.map { it.first })
    }

    @Test
    fun aTurnOffDuringTheWaitCancelsTheRestart() = runTest {
        val listening = listeningWithSession()
        sessions.opened.single().listener.microphoneFailed(readError())
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        log.clear()

        listening.onForeground(Any())
        runCurrent()

        // The new session starts its microphone at once, not when the old session's wait would have ended.
        assertEquals(listOf("open", "recognition ALL", "microphone on"), log)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("open", "recognition ALL", "microphone on"), log)

        // The old wait did not start the minute after a restart for the new session, so this first failure restarts it.
        sessions.opened.last().listener.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aRestartInAnEarlierSessionDoesNotCountForTheNextOne() = runTest {
        val listening = listeningWithSession()
        sessions.opened.single().listener.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(Any())
        runCurrent()
        log.clear()

        // Within a minute of the first session's restart, the new session's microphone fails for the first time.
        sessions.opened.last().listener.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("microphone off", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aMicrophoneThatFailsAsTheScreenGoesOffStartsAgainWithTheScreen() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener
        events.microphoneFailed(readError())
        runCurrent()
        listening.send(Event.Screen(on = false))
        advanceTimeBy(1_000)
        runCurrent()
        log.clear()

        listening.send(Event.Screen(on = true))
        runCurrent()
        assertEquals(listOf("microphone on"), log)

        // The controller did not restart the microphone itself, so this failure is not one within a minute of a restart.
        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }
```

Add this test right before the test `aServiceThatNobodyAskedForIsStopped`:

```kotlin
    @Test
    fun anEventThatAClosingSessionSendsAsKestrelTurnsOffNeverActsOnTheNextState() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener

        // The owner turns Kestrel off and on again, and the old session's transcript arrives before the controller has
        // handled either: it must not run as a task in the new state.
        listening.send(Event.TurnOff)
        listening.send(Event.Toggle)
        old.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(0, listening.state.value.task)
    }
```

In the test `aStopThatWaitedBehindANewStartLeavesTheNewServiceRunning`, replace

```kotlin
    fun aStopThatWaitedBehindANewStartLeavesTheNewServiceRunning() = runTest {
```

with

```kotlin
    fun offAndOnTwiceWhileASessionClosesLeaveTheNewServiceRunning() = runTest {
```

and replace

```kotlin
        assertEquals(listOf("closed", "stop", "start"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }
```

with

```kotlin
        // Both starts went to Android before the close ended, so neither stop ends the service that they asked for.
        assertEquals(listOf("closed"), log)
        listening.onForeground(service)
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
    }
```

Replace the whole test

```kotlin
    @Test
    fun aReportFromTheClosingServiceIsNotTakenForTheNewStart() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()

        // A notification action reaches the old service while it closes; the shutdown is about to stop that service.
        listening.onForeground(service)
        runCurrent()
        closing.complete(Unit)
        runCurrent()
        listening.onServiceDestroyed(service)
        runCurrent()
        listening.onForeground(Any())
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(2, sessions.opened.size)
        assertTrue(listening.sessionOpen.value)
    }
```

with

```kotlin
    @Test
    fun aReportOfTheClosingServiceBeforeANewStartDoesNotStopTheNewService() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()

        // A notification action reaches the old service while it closes, and then the owner turns Kestrel on again.
        listening.onForeground(service)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        log.clear()
        closing.complete(Unit)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        assertEquals(listOf("closed", "open", "recognition ALL", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
    }
```

Then add this helper right before the KDoc line `/** A scope whose failures go to [errors] instead of failing the test, for the errors that the controller rethrows. */`:

```kotlin
    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")

```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, with 11 of the 59 tests failing: `aNewStartAsksForTheServiceAtOnceAndOpensItsSessionAfterTheClose`, `offAndOnTwiceWhileASessionClosesLeaveTheNewServiceRunning` and `aReportOfTheClosingServiceBeforeANewStartDoesNotStopTheNewService` (the start still waits for the close, and the close stops the service), `anEventThatAClosingSessionSendsAsKestrelTurnsOffNeverActsOnTheNextState` (the task runs: `task` is 1, because the session's transcript was checked before the shutdown), and `aMicrophoneThatFailsStartsAgainAfterASecond`, `aMicrophoneThatFailsAgainWithinAMinuteOfItsRestartTurnsKestrelOff`, `aMicrophoneThatFailsAMinuteAfterItsRestartStartsAgain`, `aRestartThatFailsTurnsKestrelOff`, `aTurnOffDuringTheWaitCancelsTheRestart`, `aRestartInAnEarlierSessionDoesNotCountForTheNextOne` and `aMicrophoneThatFailsAsTheScreenGoesOffStartsAgainWithTheScreen` (the first failure turns Kestrel off).

- [ ] **Step 3: Change the Orchestrator and the controller**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace

```kotlin
    private val events = Channel<Event>(Channel.UNLIMITED)
```

with

```kotlin
    // Each event with the check that decides, when its turn comes, whether the Orchestrator still handles it.
    private val events = Channel<Pair<Event, () -> Boolean>>(Channel.UNLIMITED)
```

replace

```kotlin
    init {
        scope.launch {
            for (event in events) handle(event)
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) {
        events.trySend(event)
    }
```

with

```kotlin
    init {
        scope.launch {
            for ((event, valid) in events) if (valid()) handle(event)
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = send(event) { true }

    /**
     * Sends [event], which the Orchestrator handles only if [valid] still returns true when the event's turn comes. Any
     * thread may call it; [valid] runs on the Orchestrator's coroutine, after every event that was sent before this one,
     * and must not throw.
     */
    fun send(event: Event, valid: () -> Boolean) {
        events.trySend(event to valid)
    }
```

and replace

```kotlin
        notStarted.forEach(::send)
```

with

```kotlin
        notStarted.forEach { send(it) }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
```

with

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
```

replace

```kotlin
/** What Kestrel says and shows while it listens. Plan 2C-3 decides the final wording. */
```

with

```kotlin
/** What Kestrel says and shows while it listens. Plan 2C-4 decides the final wording. */
```

replace

```kotlin
 * every change. [Effect.StopService] closes the session and then stops the service. It stores the mode in [memory]:
```

with

```kotlin
 * every change. [Effect.StopService] closes the session and then stops the service. A read error of the microphone
 * restarts it after a second, at most once a minute (section 14.6). It stores the mode in [memory]:
```

replace

```kotlin
 * Threads: the Orchestrator's loop, its effects, the following of the state, and the microphone's start and stop run on
 * [thread], one at a time. A session opens and closes in a coroutine of [scope] behind one lock, so that a new start
 * waits until a shutdown has finished: two recognisers would need about 1.4 GB. The public functions may be called from
 * any thread. [report] gets each failure with a fixed description of what failed, and must log no message.
```

with

```kotlin
 * Threads: the Orchestrator's loop, its effects, the following of the state, the microphone's start and stop, and the
 * open session's events run on [thread], one at a time. A session opens and closes in a coroutine of [scope] behind
 * one lock, so that a new session opens only after a shutdown has finished: two recognisers would need about 1.4 GB.
 * Android is asked for the service at once, also while a session still closes, because a tap on the tile or a
 * notification action lets Kestrel start a foreground service from the background only for a few seconds. The public
 * functions may be called from any thread. [report] gets each failure with a fixed description of what failed, and
 * must log no message.
```

replace

```kotlin
    // Android was asked to start the service, and the service has not reported back yet. Stopping such a service makes
    // Android end Kestrel's process.
    private var awaitingForeground = false
```

with

```kotlin
    // Android was asked to start the service, and the service has not reported back yet. Stopping such a service makes
    // Android end Kestrel's process.
    private var awaitingForeground = false

    // Only [thread] uses these. [microphoneWait]: the microphone failed, and starts again when this wait ends; until then
    // follow() leaves it off. [restartedLately]: runs for a minute after such a restart (section 14.6).
    private var microphoneWait: Job? = null
    private var restartedLately: Job? = null
```

replace

```kotlin
        if (state.microphone && !microphoneOn) {
```

with

```kotlin
        // Not while the microphone waits to start again after a failure.
        if (state.microphone && !microphoneOn && microphoneWait == null) {
```

replace

```kotlin
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
```

with

```kotlin
    private fun start() {
        serviceState = ServiceState.STARTING
        // A reason from an earlier start must never come back.
        offReason = null
        // At once, also while a shutdown still closes the session: a tap on the tile or a notification action lets Kestrel
        // start a foreground service from the background only for a few seconds. The shutdown's stop sees [requested]
        // and leaves the service running, and open() waits for the close.
        requested = true
        try {
            services.start()
            awaitingForeground = true
        } catch (e: Exception) {
            turnOff("Starting the listening service", e, ListeningTexts.CANNOT_START)
        }
    }
```

replace

```kotlin
            // Before the start was asked for, the instance is the one that a shutdown in progress is about to stop.
            ServiceState.STARTING -> if (requested) {
                serviceState = ServiceState.FOREGROUND
                running = instance
                open()
            }
```

with

```kotlin
            // The start's report. While a shutdown still closes the session, Android gives the new start to the instance
            // that runs, so the report may come from it, and the shutdown leaves it running.
            ServiceState.STARTING -> {
                serviceState = ServiceState.FOREGROUND
                running = instance
                open()
            }
```

replace

```kotlin
        requested = false
        running = null
        scope.launch(thread) {
            lifecycle.withLock {
```

with

```kotlin
        requested = false
        running = null
        // A restart of the microphone that waits, and the minute after a restart, belong to the closing session.
        microphoneWait?.cancel()
        microphoneWait = null
        restartedLately?.cancel()
        restartedLately = null
        scope.launch(thread) {
            lifecycle.withLock {
```

replace

```kotlin
                    // Also after an Error from close(), so that the service does not run on while the mode is Off.
                    // Decided here, under the lock: a start that waited for the lock may have been asked for since
                    // shutdown() ran. A service that has not called startForeground() yet reports to foreground(),
                    // which stops it then.
```

with

```kotlin
                    // Also after an Error from close(), so that the service does not run on while the mode is Off.
                    // Decided here, under the lock and after the close: a start may have asked Android for the service
                    // while the session closed, and then the service keeps running. A service that has not called
                    // startForeground() yet reports to foreground(), which stops it then.
```

replace

```kotlin
    private fun releaseMicrophone(session: Session) {
        try {
            session.stopMicrophone()
        } catch (e: Exception) {
            report("Stopping the microphone", e)
        }
    }
```

with

```kotlin
    private fun releaseMicrophone(session: Session) {
        try {
            session.stopMicrophone()
        } catch (e: Exception) {
            report("Stopping the microphone", e)
        }
    }

    /**
     * Section 14.6: a read error of the microphone, such as ERROR_DEAD_OBJECT after Android's audio server restarted,
     * need not end listening. The microphone, which the caller has stopped, starts again after [MICROPHONE_RESTART_MILLIS]
     * if the state still wants it then; a turn-off or a new session cancels the wait in shutdown(). A restart that fails,
     * or a failure within [MICROPHONE_FAILURES_MILLIS] of a restart, turns Kestrel off.
     */
    private fun restartMicrophone(error: Exception) {
        if (restartedLately?.isActive == true) {
            turnOff("The microphone", error, ListeningTexts.MICROPHONE_FAILED)
            return
        }
        report("The microphone", error)
        microphoneWait?.cancel()
        microphoneWait = scope.launch(thread) {
            // Android's audio server needs a moment to come back.
            delay(MICROPHONE_RESTART_MILLIS)
            microphoneWait = null
            if (state.value.microphone) restartedLately = scope.launch(thread) { delay(MICROPHONE_FAILURES_MILLIS) }
            // Starts the microphone if the state wants it; a start that fails turns Kestrel off.
            followSafely(state.value)
        }
    }
```

Finally, replace everything from the KDoc of `Listener` to the end of the file, that is

```kotlin
    /**
     * Where one session reports. It goes quiet when the session starts to close. An event that a session thread sends
     * just as Kestrel turns off can still slip through, within the width of an instruction, and act on the next state;
     * it still comes before the next session opens, because close() waits for calls that are on their way.
     */
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

with

```kotlin
    /**
     * Where one session reports. It goes quiet when the session starts to close. [send] returns at once, and the
     * Orchestrator checks [active] when the event's turn comes, on [thread], after every event that was sent before it:
     * an event that a session thread sends just as Kestrel turns off is dropped once the shutdown has run, and never acts
     * on the next state. The events keep the order in which they were sent.
     */
    private inner class Listener : SessionListener {
        // Written on [thread]; read there by the Orchestrator's check and by microphoneFailed, and on a session thread by
        // transcriptionFailed.
        @Volatile
        var active = true

        override fun send(event: Event) = orchestrator.send(event) { active }

        override fun microphoneFailed(error: Exception) {
            // The microphone's own thread cannot stop the microphone, so the controller's thread does.
            scope.launch(thread) {
                val current = session
                if (!active || current == null) return@launch
                if (microphoneOn) {
                    microphoneOn = false
                    releaseMicrophone(current)
                }
                restartMicrophone(error)
            }
        }

        override fun transcriptionFailed(error: Exception) {
            if (active) report("A transcription", error)
        }
    }

    private companion object {
        // Android's audio server needs about this long to come back after it restarted (section 14.6).
        const val MICROPHONE_RESTART_MILLIS = 1_000L

        // A failure within this time after a restart turns Kestrel off.
        const val MICROPHONE_FAILURES_MILLIS = 60_000L
    }
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 59 tests in `ListeningTest`: 52, less `aMicrophoneThatFailsTurnsKestrelOff`, and the seven tests of the restart and the test of item 15; three more tests changed. Run `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.OrchestratorTest` too: 8 tests pass, because `send(event)` keeps its behaviour.

- [ ] **Step 5: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 14.6, replace

```markdown
| Voice output fails | Kestrel shows its text on screen only |
```

with

```markdown
| Voice output fails | Kestrel shows its text on screen only |
| The microphone fails, for example after Android's audio server restarted | Kestrel starts it again after 1 s. If that fails, or the microphone fails again within a minute, Kestrel turns off and says why |
```

- [ ] **Step 6: Run every check of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (142 in `:app`, 107 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 7: Run the voice device tests**

`ListeningServiceTest`, `ActivationTest`, `VoiceHookTest` and `ConversationTest` start and stop the real service through the changed controller, and every event of their sessions now passes the Orchestrator's check. `ConversationTest` also decodes with the emulator's app names as boost words since Task 3; if its name or task step fails here but passed after Task 3, look at this task's changes first.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 62 run, 0 failed, 0 errors, 0 skipped." If a test stops at "ListeningService still runs", print the lines of `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 logcat -d | Select-String 'Kestrel' | Select-String 'failed'`: a stop that went to a service with a newer start shows no such line, while a start that Android refused shows "Starting the listening service failed".

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "fix(app): check a session's events on the controller's thread, ask for the service at once during a close, and restart a failed microphone after a second" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Self-review

**Spec and design coverage.**

| Design decision | Where |
|---|---|
| D1 row 9: the models' folder found again at each check and load, the fallback for one check or load only | Task 1, Steps 3 (`ModelFiles(findRoot, ...)`, `root` as a getter; `AppGraph.models`) and 1 (`theFolderIsFoundAgainAtEachCheck`) |
| D1 row 10: the folder created before each check, so that `push-models.ps1` finds it | Task 1, Steps 3 (`check()` creates it) and 1 (`aCheckCreatesTheFolder`) |
| D1 row 11: `ensureActive()` before each file; a cancelled check writes no cache | Task 1, Steps 3 and 1 (`aCancelledCheckStopsBeforeTheNextFileAndWritesNoCache`) |
| D1 row 13: both pages say that the check failed, the error logged by its classes, a new check when a page opens again; JVM tests as far as they reach | Task 1, Step 4 (`ModelCheck`, `checkModelFiles`, both pages, `MainActivity`), Step 1 (`SetupChecklistTest`), Step 6 (`SettingsAppTest.bothPagesSayWhenTheModelCheckFailed`); the new check on opening is `SettingsApp`'s existing `LaunchedEffect(page)` |
| D2: `Recognition.ANSWER`, transcribed like `ALL`; the endpointer holds every utterance with `holdAll`, and a held one waits for its pause also after the hold ends; `named` kept (a joined utterance named only when its first part was); the KDocs; `State.recognition` is `ANSWER` while waiting for a task and `NONE` while Kestrel speaks (also in `OrchestratorTest`); JVM tests; spec 8.3 | Task 2 |
| D3: `BoostWordSource` with `suspend fun words()`, "Kestrel" and the launcher's labels, the owner's apps before the system apps (`FLAG_SYSTEM` or `FLAG_UPDATED_SYSTEM_APP`) with the package manager's order in each group (the controller's change after the first draft), no repeats, no labels with fewer than 3 letters or digits, read once per session on `Dispatchers.IO` while the engines load, a failure reported by classes with "Kestrel" alone; the manifest's `<queries>`; nothing logged; JVM tests with a fake reader, one of them for the order and the cap; a device test that finds the fixture app (only through `<queries>`, seen failing without it) before Settings, Settings marked as a system app, and logs the read's counts and time; a device test that the session's words reach the recogniser; spec 8.3 | Task 3 |
| D4 item 15: the check of `active` on the controller's thread, made by the Orchestrator when the event's turn comes (`Orchestrator.send(event, valid)`, the complete fix of the review's M2, in place of the design's hand-over to `thread`); a JVM test that fails without it | Task 4 (`anEventThatAClosingSessionSendsAsKestrelTurnsOffNeverActsOnTheNextState`) |
| D4 item 11: `services.start()` at once on `thread` with `requested = true`; only the opening waits for the close; the paths of the stop checked; JVM tests | Task 4 (`start()`, `foreground()`; the three changed tests; the paths in the task's introduction) |
| D4 item 12: stop, wait 1 s on `thread`, start again if the same session is open and the state wants the microphone; off after a failed restart or a failure within 60 s; a turn-off or a new session cancels the wait; JVM tests for each case, also a screen that goes off during the wait | Task 4 (`restartMicrophone`, the seven tests) |
| D4 item 16 | Already done in the code; "Not in this plan" |
| The expected counts after each task | Steps 5, 8, 9 and 10 of Task 1; Steps 6, 8 and 9 of Task 2; Steps 5, 9 and 10 of Task 3; Steps 4, 6 and 7 of Task 4 |
| Review 1 of this plan (`.superpowers/sdd/plan-2c3-review.md`): C1 (`OrchestratorTest`), I1 (the fixture app, Step 6 of Task 3), M1 (the read in parallel with the loads, and its log line), M2 (`Orchestrator.send(event, valid)`), M3 (the emulator check inside each device command), M4 (the comment in `shutdown()`, the renamed test), M5 (spec 8.3), M6 (three tests: `EndpointerTest`, `ListeningTest`, `VoiceSessionFailuresTest`), M7 (Gradle stopped in each task), M8 (`git status --short` before each task), N1 to N8 | Tasks 1 to 4, "Before you start", "Threads" |
| Review 2 of this plan (`.superpowers/sdd/plan-2c3-review-2.md`): I1 (`namesThatCannotBeReadLeaveKestrelAlone` compares the description and the class, because kotlinx.coroutines' stack-trace recovery copies the error across `withContext` in Gradle's tests), M1 (the header, Task 4's Interfaces and its commit message), M2 (`aTurnOffDuringTheWaitCancelsTheRestart` ends with a first failure of the new session; 11 tests fail in Step 2), N1 (`ensureActive()` before the cache is written), N2 (`AppNamesTest` takes the Settings app's label from the package manager), N3 (the delay of a failed load in "Threads") | Tasks 1, 3 and 4, the header, "Threads" |

**Placeholder scan.** No "TBD", "TODO", "similar to Task N" or step without its code or command. Every changed file shows the exact text that it replaces, or the whole new file. Each replaced text was checked by a script against the files at the time of writing (`78c99c1`, and `b5b9581` for the files of plan 2C-2's Task 5, which this plan does not touch), also the old texts that the review's fixes added (`OrchestratorTest`, `Orchestrator`, the comment in `shutdown()`, the name of the renamed test, `VoiceSession`'s last two lines of the load, `AppGraph`'s `SupervisorJob` import and `VoiceSessionFailuresTest`'s `FakeRecognizer`): every old text exists, and exists once, except the two lines `show(NOTHING_SET_UP.copy(models = listOf(READY_VOICE, speech)))` in `SettingsAppTest`, which Task 1 Step 6 names with their tests and changes both. Task 1 changes `AppGraph.models`, and Task 3 changes other parts of `AppGraph`, so their old texts do not overlap.

**Type consistency.** `ModelFiles(findRoot: () -> File, manifest, hashCache, hash)` and the secondary `ModelFiles(root: File, ...)` match `AppGraph` (named `findRoot`), `ModelFilesTest` and `VoiceEnginesTest` (positional `File`, unchanged); `ModelFiles.root` stays a `File` for `KestrelApplication` and `ModelFolderTest`. `ModelCheck.Checking`, `ModelCheck.Failed` and `ModelCheck.Done(statuses)` are used by `SetupStatus.models`, `SetupChecklist.states`, `checkModelFiles`, `ChecklistPage`, `AboutPage`, `MainActivity`, `SetupChecklistTest` and `SettingsAppTest`; `SettingsApp` passes `status.models` unchanged. `checkModelFiles(check: suspend () -> List<ModelStatus>, report: (Exception) -> Unit): ModelCheck` matches `MainActivity` and the tests. `Endpointer.accept(..., holdAll: Boolean = false)` matches `ListeningPipeline.accept` and `EndpointerTest`. `Recognition.ANSWER` is used by `ListeningPipeline`, `Rules`, `RulesTest`, `ListeningPipelineTest` and the log line `recognition ANSWER` of `ListeningTest`'s `FakeSession`. `AppLabel(label, system)`, `AppLabels.read(): List<AppLabel>`, `LauncherAppLabels(context)`, `BoostWordSource(labels, report)`, `words()`, `BoostWordSource.NAME` match `AppGraph`, `VoiceSession`, `BoostWordSourceTest` and `AppNamesTest`. `VoiceSession(..., listener, words: List<String> = listOf(BoostWordSource.NAME))` and `VoiceSession.open(context, engines, microphone, listener, boostWords = ..., loaders)` keep the calls of `VoiceSessionTest` and `VoiceSessionFailuresTest` (positional four arguments, named loaders), and the new test of `VoiceSessionFailuresTest` passes `boostWords` by name; `AppGraph` passes `{ words.await() }` for the `async` that it started in its `coroutineScope`. `Orchestrator.send(event, valid: () -> Boolean)` is called by `Listening.Listener.send` with `{ active }`; `send(event)` keeps every other caller, and the Orchestrator's own `notStarted.forEach { send(it) }` names the one-argument overload. `AppGraph.reportFailure: (String, Throwable) -> Unit` matches `Listening`'s `report` and `BoostWordSource`'s `report`. In `Listening`, `microphoneWait` and `restartedLately` are `Job?`, set in `restartMicrophone`, read in `follow()` and cancelled in `shutdown()`; `MICROPHONE_RESTART_MILLIS` and `MICROPHONE_FAILURES_MILLIS` are used only there, and the tests use the same numbers (1 000 and 60 000 ms) through `advanceTimeBy`.

**Counts.**

| After | `:app` JVM | `:core:voice` JVM | Voice device tests | Pester (per shell) | Other device tests |
|---|---|---|---|---|---|
| Start (plan 2C-2 complete) | 129 | 99 | 60 | 90 | `SettingsAppTest` 13, `MainActivityTest` 4 |
| Task 1 | 131 (`SetupChecklistTest` 5 → 7) | 102 (`ModelFilesTest` 11 → 14) | 60 | 90 | `SettingsAppTest` 14, `MainActivityTest` 4 (run in Task 1 only) |
| Task 2 | 132 (`RulesTest` 37 → 38; one line each of `OrchestratorTest` and `ListeningTest` changed) | 107 (`EndpointerTest` 10 → 14, `ListeningPipelineTest` 26 → 27) | 60 | 90 | 14 |
| Task 3 | 135 (`BoostWordSourceTest` 3) | 107 | 62 (`AppNamesTest` 1, `VoiceSessionFailuresTest` 5 → 6) | 90 | 14 |
| Task 4 | 142 (`ListeningTest` 52 → 59) | 107 | 62 | 90 | 14 |

**Decisions this plan made beyond the design, and where it departs from it.**
- D1, row 13: the pages' knowledge of the model files is a sealed `ModelCheck` (checking, failed, done) in place of `List<ModelStatus>?` with a second flag, so that no page can show "failed" and a result at once. The check's error handling is the top-level `checkModelFiles`, so that JVM tests reach it; `MainActivity` only calls it, and logs through `graph.errors.handleException`, which writes the error's classes and stack frames. After a failed check, the next check shows "Checking…" again until it ends. One device test in `SettingsAppTest` shows the failed text on both pages; it runs, with `MainActivityTest` and its real check, through `connected-test.ps1`, because `voice-test.ps1` runs only the package `dev.kestrel.app.voice`.
- D1: spec 8.10 gains two sentences (the folder before each check, shared storage and a failed check), which the design did not list.
- D2: `holdAll` has the default `false`, so the endpointer's existing tests show its old behaviour without the hold.
- D3: `BoostWordSource` keeps nothing. `AppGraph` starts the read in an `async` as the session starts to open, and `VoiceSession.open` asks for the words once, after the recogniser has loaded and inside its clean-up, and passes the list to the session, which the pipeline's `boostWords` returns for each utterance (the review's M1), so the read costs no time unless it outlasts the engine loads. `AppNamesTest` logs the number of names and the read's time, so that `voice-test.ps1` prints them. The only type that holds a label, `AppLabel`, prints only the label's length in its `toString`. The failure case is a JVM test only; the device test checks the real reader, the system flag of the Settings app and the `<queries>` element, because a device failure would test the same code with the same fake. Android shows the Settings app to every app (`config_forceQueryablePackages`), so the device test checks `<queries>` with the fixture app "Kestrel fixtures", and Task 3 runs it once before the manifest change to see it fail (the review's I1). `AppGraph`'s two copies of the failure reporter become one `reportFailure`.
- D3: sherpa-onnx gets at most 100 phrases (`BoostWords.MAX_PHRASES`), so on a phone with more than 99 launcher apps some names are not boosted. "Kestrel" comes first, so it is never dropped, and the owner's apps come before the system apps, so the cap drops system apps first (the controller's decision). `AppLabel` carries the system flag, and its `toString` prints only the label's length. The test of the cap uses the number 100 itself, because `BoostWords` is internal to `:core:voice`. Plan 2D measures whether the A71 needs more.
- D4, item 11: three existing tests pinned the old order and change (named in the task); the design expected the existing tests of stop and start to keep passing, which `offAndOnInOneRunOfTheOrchestratorStartTheServiceAgain` and the others do. `foreground()` no longer checks `requested` in `STARTING`, because `start()` now sets both together.
- D4, item 12 (`aMicrophoneThatFailsAsTheScreenGoesOffStartsAgainWithTheScreen` covers a screen that goes off during the wait): while the restart waits, `follow()` leaves the microphone off; without this, any change of the state, such as a transcript or "Yes?", would start the microphone at once and skip both the wait and the minute after it. The "same session" check of the design is the cancellation in `shutdown()`, which every turn-off and every new session passes through. The first failure is reported by its classes, as before; the bar shows nothing during the wait. No device test (optional in the design). Spec 14.6 gains a row for it.
- D4, item 15: the design handed each event to `thread` before the check; the plan has the Orchestrator check `active` when the event's turn comes (`Orchestrator.send(event, valid)`), because the hand-over would let the owner's TurnOff and Toggle overtake a session's event that waits behind a blocking task on `thread` (the review's M2).
- D4, item 16: no change, because the code already does what the item asks.
- No test compares an exception instance that crossed a coroutine boundary: `BoostWordSourceTest` compares the description and the class (review 2, I1). `SetupChecklistTest.aModelCheckThatFailsIsReportedAndSaysSo` still compares the instance, because `checkModelFiles` calls the check directly in the same coroutine, with no `withContext` or other resumption in between, so the error is never copied.
- Every device command checks the emulator in the same command, and each task stops Gradle before its commit (the review's M3 and M7). "Before you start" asks for `git status --short` before each task, so that no commit takes changes that are not its own (M8).
- `ListeningTexts`' KDoc names plan 2C-4 instead of 2C-3, after the split.

