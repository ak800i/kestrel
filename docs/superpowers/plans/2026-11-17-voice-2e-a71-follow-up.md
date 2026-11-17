# Voice 2E: The Follow-up of Sub-project 2's A71 Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix what the A71 session of 2026-11-17 found (the controller's analysis in `.superpowers/sdd/a71-session-2-analysis.md`, which git does not track): Kestrel notices on Android 13 that Android took the foreground from its listening service, "Kestrel, open settings" works or says why with only the assistant role, the scripts' command helper and checks no longer fail on One UI, the earbuds' recording waits for the Bluetooth link, the keyword spotter can be measured with three settings and tuned on the phone, Kokoro gives way to Piper on a phone where it is too slow and its int8 model can be measured beside the fp32 one, and the A71 session repeats only the checks that failed or were not valid, timed on the phone and never from the owner's Enter; the section "After the session" says how the A71's results become Kestrel's settings.

**Architecture:** `AndroidListeningConditions` decides "demoted" from Android's own list of Kestrel's running services, which Android 13 keeps correct while it keeps the service's type. A `Timeline` and `VoiceCounts`, one of each for the process, record on the phone's clock when Kestrel was started, opened a session, heard the end of speech (from the pipeline's new `VoiceEvent.Heard.endedAt`), reacted, turned itself off and brought its settings to the front, and count the spotter's hits by keyword, the transcribed utterances and how each one was read; the debug hooks `voice-times` and `voice-counts` answer them, and `a71-session.ps1` reads them instead of timing the owner's Enter. "Kestrel, open settings" waits up to 2 s for `MainActivity` to resume (`SettingsOpener`); with only the assistant role it starts the settings through the assistant's own session (`startAssistantActivity`), and when the settings do not come, Kestrel says so, records its verdict on the phone and posts a notification that opens them. `HeadsetRoute.open` waits up to 3 s until Android names the headset as the communication device before the recording opens. The keyword spotter's boost and threshold become a run-time setting of `VoiceEngines`, which a debug hook sets, and a device test measures three settings with the same generated speech. Kokoro keeps its fp32 model; its real-time factor is measured at its first load on each phone and stored, and above 1, Piper speaks, later sessions load Piper without Kokoro, and the page "Voice and speech" says why. Kokoro's int8 model becomes a second optional model, which only a device test loads, so that the A71 compares the two.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0 (kotlinx-coroutines-test for the JVM tests), kotlinx.serialization for the stored settings, Android's `ActivityManager.getRunningServices`, `VoiceInteractionService.showSession` and `VoiceInteractionSession.startAssistantActivity`, `AudioManager.getCommunicationDevice` and `AudioRecord.getRoutedDevice` at compileSdk 37, sherpa-onnx 1.13.8, Jetpack Compose for the settings page, JUnit 4, Pester 6.2.0, device tests on the `kestrel-api37` emulator through `scripts\voice-test.ps1`, `scripts\connected-test.ps1` and `scripts\listen-check.ps1`, and `scripts\a71-session.ps1 -DryRun`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app. A new model file is listed in `models/manifest.json` with its licence, size and SHA-256 hash.
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`.
- No dependency-injection framework; `AppGraph` wires by hand.
- Never log audio, transcripts, replies, boost words, app labels, screen text, phone numbers, exception messages or an exit record's description (section 13.5); `toString` of anything that carries the owner's words prints only lengths or counts. The debug hooks answer only counts, times on the phone's clock, the names of kinds, device types and levels, never words.
- Test only on `kestrel-api37`, and check it in the same command as each device command; never on the S23+ or the AVD `zoran`; the A71 only in the session with the owner, which the controller runs (`scripts\a71-session.ps1` together with `scripts\a71-commands.ps1`). Every device step holds the emulator lock (`.superpowers/sdd/progress.md`, the controller's rule 8a): wait until `.superpowers/sdd/emulator.lock` does not exist (every 2 minutes, up to 60 minutes, then report BLOCKED), create it with your name on one line, and delete it as soon as the device steps are done, also when they fail. One Gradle build at a time on this computer (rule 8b).
- Gradle only through `scripts\gradle.ps1` (stopped with `--stop` at the end of each task); device tests only through `scripts\voice-test.ps1`, `scripts\connected-test.ps1` (with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) or `scripts\listen-check.ps1`, as each task says; installs only through `scripts\install.ps1`. Device tests and scripts restore every setting, app op, role, permission and file that they change, and keep the APKs installed. A reboot of the emulator is a cold boot.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change. `scripts\a71-session.ps1`, `listen-check.ps1` and `hook.ps1` need PowerShell 7.3 (`pwsh`).
- No measure of the A71 session is timed from the owner's Enter: Kestrel records the times on the phone, and the script reads them through the hooks. Every prompt says exactly what the owner does and what the phone shows or does not show, such as "Say "Kestrel" once. Then press Enter at once, whether or not the phone reacts."
- The repository uses LF line endings.
- The plan file is named with the day it is written (`Get-Date -Format yyyy-MM-dd`), and it is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

## Before you start

- Sub-project 3 (plans 3A, 3B and 3C) is complete: plan 2E changes files that sub-project 3 changes, and its "replace" texts are written against the code as sub-project 3 leaves it. Its starting totals are those at the end of sub-project 3. The controller gives their values in each task's brief, because later fix commits change them: `:app` JVM A₀ (among them `ListeningTest` L₀ and `RulesTest` R₀), `:core:voice` JVM V₀, voice device tests (`scripts\voice-test.ps1` on the emulator) D₀, the other device tests of `connected-test.ps1` O₀ (with the command tests skipped there), Pester P₀ per shell. `:core:commands`, `:core:screen`, `:core:privacy` and `:core:safety` do not change in this plan.
- The expected counts after each task, as A₀ + k and so on: after Task 2, `:app` A₀ + 2, voice device tests D₀ + 1, Pester P₀ + 3; after Task 3, `:core:voice` V₀ + 1, `:app` A₀ + 6 (`ListeningTest` L₀ + 2), voice device tests D₀ + 2, Pester P₀ + 4; after Task 4, `:app` A₀ + 13 (`ListeningTest` L₀ + 3, `RulesTest` R₀ + 1, `SettingsOpenerTest` 5); after Task 5, Pester P₀ + 6; after Task 6, `:core:voice` V₀ + 5, voice device tests D₀ + 3; after Task 7, `:core:voice` V₀ + 6, voice device tests D₀ + 4; after Task 8, `:app` A₀ + 19, voice device tests D₀ + 5, other device tests O₀ + 1; after Task 9, Pester P₀ + 15. Tasks that the list does not name leave a count as the task before it left it.
- Before each task, check that every text that the task replaces exists, and exists once, in its file, and compare the counts with the ones that the earlier tasks left. If a text is missing or a count differs, stop and ask the controller.
- Every device command of this plan starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. `voice-test.ps1`, `connected-test.ps1`, `listen-check.ps1` and `a71-session.ps1` also refuse other devices themselves (`Assert-TestDevice`).
- Run the voice device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`; it installs Kestrel, copies the models with Kokoro and keeps both installed. It runs only the package `dev.kestrel.app.voice`. The other device test classes run through `scripts\connected-test.ps1`, always with `'-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

A change of which speech Kestrel transcribes while it listens and is idle (recognition `ALL`, `Rules.kt`): the battery cost of transcribing room talk waits for the numbers of Task 9's three battery measures (analysis, item 10).

The Galaxy Buds' assistant gesture, the assistant gesture, the unlock paths, the tile on the lock screen, a start during a close, the three restarts of the old step reboots and the notification's actions: they passed or were skipped by the owner on 2026-11-17, and the new session does not repeat them. The owner's recordings of the voice corpus are recorded by `scripts\a71-commands.ps1`'s step `recordings` in the same sitting; Task 9 fixes its prompt and its estimate.

The keyword spotter on the owner's own recordings on the phone (analysis, item 8): the recordings come only in the sitting, and `scripts\voice-eval.ps1 -Corpus owner` measures them later on the emulator; a run on the A71 waits for a later plan. A change of the owner's "require verbs" for the battery measure of room talk: the session counts the instant commands that ran instead.

A variant of `ListeningServiceTest` that sets the battery restriction on the emulator and checks `BATTERY_RESTRICTED` end to end (review of plan 2E, part A, N7): it would need a new entry in `voice-test.ps1`'s list of emulator-only tests, and the session's step battery-rules checks the same on the A71.

A prototype of the assistant's session start on the A71 outside the session: Task 4 builds the start through the session, checks it on the emulator, and the session's step `assistant` checks it on Android 13 with the phone's own times.

A switch of Kestrel's Kokoro model to int8, or of Kokoro's threads: `KokoroModelsTest` measures both models with 2 and 4 threads on the A71 in the session, and "After the session" applies the result by a stated rule.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `Timeline`'s marks and `VoiceCounts` | Any thread | `@Volatile` fields and atomic counters; the hooks read them in a coroutine of `AppGraph.scope` (`Dispatchers.IO`), after `TestHookReceiver`'s `goAsync()` |
| `Listening`'s `openSettings` (`SettingsOpener.open`) | A coroutine of `Listening`'s `scope` (`Dispatchers.IO` in `AppGraph`) | Waits up to 2 s for `Timeline.settingsResumes`; it no longer runs on the controller's thread |
| `KestrelAssistantService.openSettings()` | The coroutine above | `showSession` is a binder call; the session's `onShow` then runs on the main thread and calls `startAssistantActivity` |
| `HeadsetRoute.open` | The controller's thread, inside `MicCapture.start`, which holds `MicCapture`'s monitor | Waits up to 3 s for Android's report, which comes on the main thread, so it must never run there; it holds the route's lock only while it asks, and it blocks the controller's thread for up to 3 s at each start of the microphone with earbuds |
| `MicCapture.routeReport()` | The hook's thread | Reads `@Volatile` fields and Android's audio service; the loudest level is an atomic that the microphone's thread raises |
| `VoiceEngines.keywordTuning` | Any thread | `@Volatile`; `keywordSpotter()` reads it when a voice session opens |
| `kokoroKnownTooSlow` and `kokoroTooSlow` (`AppGraph`) | The coroutine that opens a voice session (`Dispatchers.IO`); `SettingsData.tryVoice` | The first reads the stored measure; the second synthesizes a warm-up and one sentence at Kokoro's first load for each model and install, and stores the factor |

## File structure

| File | Responsibility |
|---|---|
| `docs/superpowers/specs/2026-11-12-kestrel-design.md`, `README.md` | Task 1: the results of the session of 2026-11-17 in section 19.1 and the README's table; Tasks 2, 4, 6, 8 and 9: the sections and paragraphs whose behaviour they change |
| `app/src/main/kotlin/dev/kestrel/app/listen/AndroidListeningConditions.kt` | New (Task 2): `AndroidListeningConditions` and `demotedFrom` |
| `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt` | Task 2: `running()` for the device tests and the hooks; Task 3: the mark of "Start listening" |
| `app/src/main/kotlin/dev/kestrel/app/listen/Timeline.kt` | New (Task 3): `Timeline` and `VoiceCounts` |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` | Task 3: the end of speech on the phone's clock (`VoiceEvent.Heard.endedAt`, `Event.Heard.seconds` and `endedAt`); Task 4: `Event.SettingsBlocked`, `Reply.SettingsBlocked` |
| `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, `SettingsOpener.kt` (new, Task 4) | Task 3: the marks and counts; Task 4: `SettingsOpening`, the open off the controller's thread and the verdict `settings-blocked` |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` | Task 2: the conditions; Task 3: the timeline; Task 4: `SettingsOpener`'s wiring; Task 6: the latest microphone; Task 8: `SettingKeys.KOKORO_SPEED`, `installedAt`, `kokoroKnownTooSlow` and `kokoroTooSlow` |
| `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, `AssistActivity.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt`, `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` | Task 3: the marks of each start and of the settings in front; Task 4: the settings through the assistant's session |
| `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt`, `app/src/main/res/values/strings.xml` | Task 4: the notification that opens the settings; Task 8: Kokoro's text |
| `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, `VoiceHooks.kt`, `scripts/hook.ps1` | Task 3: `voice-times`, `voice-counts`; Task 6: `voice-route`; Task 7: `voice-spotter`; Task 8: `voice-choice`'s `kokoroSlow` and `kokoroSpeed` |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, `AndroidCommunicationDevices.kt`, `MicCapture.kt` | Task 6: the wait for the headset and the route's report |
| `core/voice/src/main/kotlin/dev/kestrel/voice/KeywordSpotter.kt`, `sherpa/KeywordLines.kt`, `VoiceEngines.kt` | Task 7: `KeywordTuning`; Task 8: `kokoroModel`, `kokoro(model, threads)` and the int8 model's constants; `sherpa/SherpaKokoroSynthesizer.kt`: Task 8's threads |
| `models/manifest.json`, `scripts/push-models.ps1`, `THIRD_PARTY_NOTICES.md`, `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, `VoicePage.kt` | Task 8: Kokoro's int8 model as a second optional model, Kokoro's speed, "Try it" and the page's text |
| `scripts/lib/Kestrel.ps1`, `scripts/tests/fake-adb.ps1`, `fake-adb-files.ps1`, `scripts/listen-check.ps1` | Task 5: the end marker on its own line, one line for each check, the settings check through the hook |
| `scripts/lib/Session.ps1`, `scripts/a71-session.ps1`, `scripts/a71-commands.ps1` | Tasks 2, 3, 5 and 9: the session's helpers; Task 9: the session of the re-checks (the whole script) and the recordings' prompt |

Tests: `app/src/test/kotlin/dev/kestrel/app/listen/DemotionTest.kt` (JVM, new, Task 2), `TimelineTest.kt` (JVM, new, Task 3), `ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt` (JVM, changed); `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, `HeadsetRouteTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt` (JVM, changed); `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, `SessionHookTest.kt`, `AssistTest.kt` (voice device tests, changed), `KeywordTuningTest.kt` (voice device test, new, Task 7), `KokoroModelsTest.kt` (voice device test, new, Task 8), `VoiceSessionFailuresTest.kt` (voice device test, changed, Task 3); `app/src/test/kotlin/dev/kestrel/app/listen/SettingsOpenerTest.kt` (JVM, new, Task 4); `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` (device, changed, Task 8); `scripts/tests/Session.Tests.ps1`, `Kestrel.Tests.ps1`, `Models.Tests.ps1` (Pester, changed).

---

### Task 1: The results of the A71 session of 2026-11-17 in the documents

The session of sub-project 2 ran on 2026-11-17 from 13:12 to 15:40 (`corpus\owner\session-20261117-1312.txt`, `.superpowers/sdd/progress.md`, "The A71 session of sub-project 2"). Its results go into spec 19.1 and the README's table, with the owner's notes on what is not valid: every time that the voice step gave as seconds after Enter is no latency, because the owner pressed Enter after he saw the phone react; the owner's recordings failed, because Kestrel was on; and the battery number is a worst case. The re-checks of Task 9 add their results after the next session.

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 19.1)
- Modify: `README.md` (the table of the A71 session's measurements)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing that code uses.

- [ ] **Step 1: Add the session's results to spec 19.1**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, after the bullet that starts with "**The A71 session (plan 2D).**", add this bullet:

```markdown
- **The A71 session of sub-project 2 (2026-11-17, commit 59b86ae).** The session ran from 13:12 to 15:40 on the A71 (Android 13, One UI 5.1). The controller analysed it after the session. Four kinds of result are not valid: every time that the voice step gave as seconds after the owner's Enter, because the owner pressed Enter after he saw the phone react, so such a time is close to 0 and no latency (the counts of tries that worked stay valid, because the script read Kestrel's counters before each prompt); the owner's recordings and the corpus measures with them, because Kestrel was on while the owner recorded; the shortcut's 53.6 s, which most likely includes what the owner did on the phone after Enter; and the times after the three restarts of the phone (8.6, 38.6 and 0.5 s), which count from the owner's Enter after the unlock and not from the boot. What passed: the install, 73 of the 74 voice tests that may run on a phone, among them spike S8 (`OnnxRuntimeTogetherTest`) and the keyword spotter's hit 0.41 s after a generated keyword; three restarts of the phone, after each of which the restore at boot turned Kestrel on again; a start during a close; the tile on the lock screen, which listened without an unlock; the unlock by PIN (the fingerprint, the face and Smart Lock were skipped); the accessibility shortcut, the accessibility button, the tile and the notification's Pause, Resume and Stop with Kestrel in the background; with only the assistant role, a start in the background, the restore after Android ended Kestrel's process (listening again after 12.4 s) and the assistant gesture (the Galaxy Buds' gesture was skipped); the phone's microphone after the earbuds went away; "a quick answer to "Yes?"" in 3 of 3 tries, a dictated task of 23 words whole in 3 of 3, an answer of 61 words whole in 2 of 2, a notification sound during a reply, "stop" while a task ran in 3 of 3, "Stop listening" in 5 of 5 and "Kestrel, turn off" in 3 of 3; and "Try it" with Kokoro while Kestrel listened with Kokoro. The battery step found that the setup checklist names Samsung's "Never sleeping apps" correctly. The interruptions after pauses of 1, 1.5 and 2 s were skipped. The measures of section 5.3 on the A71: the recogniser loaded in 3791 ms, its PSS grew from 250 to 994 MB, and it transcribed 2.0 s of audio in about 415 ms; with the generated corpus, Piper's clips had a word error rate of 6.5 % and 101 of 104 read as intended, with a median transcription of 272 ms (90th percentile 463 ms, longest 817 ms) and a PSS of at most 1704 MB while the eval hook transcribed them with Kestrel off, and Kokoro's clips 4.0 %, 101 of 104, 257 ms (longest 732 ms) and 1659 MB; Piper spoke 1.8 s in 314 ms (a real-time factor of 0.17), and Kokoro's fp32 model loaded in 2355 ms and spoke 1.6 s in 2902 ms (1.81, too slow for replies without gaps), with a PSS of 773 MB, although the owner found Kokoro's speed good enough; voice sessions with Piper opened after about 7.2 s, with a PSS of about 1150 MB while Kestrel listened; Kokoro's first start reached 2049 MB, just over the 2 GB target, because 0.4 to 1 GB of the earlier sessions stayed in Kestrel's process, and its later starts 1276 to 1474 MB; in 30 minutes of room talk with the screen on, Kestrel used 81.4 mAh, 3.69 % of the battery per hour, which is a worst case, because Kestrel transcribed all the talk, and no typical case was measured; and in an hour of room audio, the keyword spotter had 127 hits of all three keywords, and Kestrel started 0 tasks, began 0 waits and 0 replies and changed its mode 0 times. So the transcription time and the memory with Piper met the targets, Kokoro's first start missed the memory target by 1 MB, and the battery target is still open. What failed: `ListeningServiceTest` on Android 13, because Android 13's `dumpsys` prints no service types; listen-check, which stopped at its shortcut check, most likely because One UI's `dumpsys accessibility` ends without a line break and the command helper needed its end marker on a line of its own, which the next session checks; the one logged failure, which was listen-check's own start in the background that Android refuses on purpose; the battery rules, where Kestrel, with the battery restriction and without its accessibility service, stayed "listening" with a silenced microphone and no notice, because on Android 13 a service whose foreground Android took away keeps its type, and Kestrel read the type; "Kestrel, open settings" with only the assistant role, which most likely hit Android 13's block on activity starts from the background, while Kestrel said nothing; the earbuds, whose microphone did not hear the name, most likely because the recording opened before the Bluetooth link was up; a cold start through the tile, because the script could not tell a kill that Android refused from a new process; and every measure that needs the keyword spotter (0 of 8): "Hey Kestrel" in 0 of 5, "Kestrel, wake up" while paused in 0 of 1, and an interruption after a pause of 0.5 s in 0 of 2, which cannot work, because the speech detector joins speech that follows within 0.7 s. A lone "Kestrel", which the recogniser's transcript can name without the spotter, worked in 2 of 5 tries here and in 7 of 10 in the whole session, and "Stop Kestrel" in 2 of 3. On the A71 even `ConversationTest` needed 4 plays of a generated "Kestrel, wake up" to wake Kestrel. The echo filter took every hit of Kestrel's own voice for its own. The recordings step and the corpus measures with the owner's clips failed, as said. Plan 2E fixes these failures and adds the measures that were missing; its session repeats only those checks, timed on the phone.
```

- [ ] **Step 2: Put the measures into the README's table**

In `README.md`, replace

```markdown
| Transcription time of a command | at most 4 s | not measured yet |
| Kestrel's memory while it listens (PSS) | at most 2 GB | not measured yet |
| Battery use while listening | at most 2 % per hour | not measured yet |
| Word error rate and requests read as intended, with the owner's recordings | none yet | not measured yet |
| The keyword spotter's hits and Kestrel's reactions in an hour of ordinary room audio | none yet | not measured yet |
| Piper's and Kokoro's speed (real-time factor) | below 1, so that Kestrel speaks without gaps | not measured yet |
```

with

```markdown
| Transcription time of a command | at most 4 s | met on 2026-11-17: a median of 272 ms with Piper's generated clips (90th percentile 463 ms, longest 817 ms) and 257 ms with Kokoro's (longest 732 ms) |
| Kestrel's memory while it listens (PSS) | at most 2 GB | met with Piper on 2026-11-17: about 1150 MB while Kestrel listened, at most 1704 MB while the eval hook transcribed the corpus, with Kestrel off; Kokoro's fp32 model reached 2049 MB at its first start, because memory of the earlier sessions stayed in the process |
| Battery use while listening | at most 2 % per hour | not measured for a typical case yet: 3.69 % per hour on 2026-11-17 is a worst case, with the screen on and 30 minutes of room talk that Kestrel transcribed |
| Word error rate and requests read as intended, with the owner's recordings | none yet | not measured yet: the owner's recordings of 2026-11-17 failed; with the generated clips on the A71, Piper's word error rate was 6.5 % and Kokoro's 4.0 %, and 101 of 104 clips of each were read as intended |
| The keyword spotter's hits and Kestrel's reactions in an hour of ordinary room audio | none yet | on 2026-11-17: 127 hits of the three keywords, and 0 tasks, 0 waits, 0 replies and 0 changes of mode |
| Piper's and Kokoro's speed (real-time factor) | below 1, so that Kestrel speaks without gaps | on 2026-11-17: Piper 0.17; Kokoro's fp32 model 1.81, too slow |
```

- [ ] **Step 3: Check the documents**

Run: `git diff --stat`
Expected: two files changed, `README.md` (6 lines changed) and `docs/superpowers/specs/2026-11-12-kestrel-design.md` (1 line added).

- [ ] **Step 4: Commit**

```powershell
git add README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs: record the results of the A71 session of sub-project 2 in spec 19.1 and the README" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: The loss of the foreground on Android 13

Analysis items 1 and 2. On Android 13, a service whose foreground Android takes away keeps its foreground service type (AOSP `ActiveServices.java`, android13-release), so `demoted()`, which read the type, was never true there: with the battery restriction, Kestrel stayed "listening" with a silenced microphone and no notice. `AndroidListeningConditions` decides it from Android's own list of Kestrel's running services, whose `foreground` field is the service record's `isForeground` on every version, and keeps "type 0" as a second sign for Android 14 and later. The existing rule of `Listening` then acts on it: the first `MicBusy` of a demoted service under the restriction turns Kestrel off with `BATTERY_RESTRICTED`, which the bar and the problems notification say. `ListeningServiceTest` and the battery step read Android's record as every version prints it: the line `isForeground=true foregroundId=7101`, whose `types=` Android 13 does not print.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/AndroidListeningConditions.kt`
- Create: `app/src/test/kotlin/dev/kestrel/app/listen/DemotionTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `conditions`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (the KDoc of `ListeningConditions.demoted`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt` (`running()`)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`
- Modify: `scripts/lib/Session.ps1` (`Get-ForegroundRecord`), `scripts/tests/Session.Tests.ps1`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 14.6)

**Interfaces:**
- Consumes: `ListeningConditions` and `ListeningService` as sub-project 3 left them.
- Produces: `internal class AndroidListeningConditions(context: Context) : ListeningConditions` with `fun listedForeground(): Boolean?`; `internal fun demotedFrom(listedForeground: Boolean?, type: Int): Boolean`; `ListeningService.running(): ListeningService?` (companion, main thread only); the PowerShell function `Get-ForegroundRecord -Dump <string[]>`, which returns `Listed` (bool), `Foreground` (`'true'`, `'false'` or `'not printed'`) and `Types` (`'0x82'` or `'not printed'`). Task 3's hook `voice-times` and Task 9's step `battery-rules` use them.

- [ ] **Step 1: Write the failing JVM test**

Create `app/src/test/kotlin/dev/kestrel/app/listen/DemotionTest.kt`:

```kotlin
package dev.kestrel.app.listen

import android.content.pm.ServiceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When ListeningService counts as demoted (design spec, section 14.6; analysis of the A71 session of sub-project 2). */
class DemotionTest {
    private val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    @Test
    fun onAndroid13OnlyAndroidsListTellsBecauseTheTypeStays() {
        assertFalse(demotedFrom(listedForeground = true, type = types))
        assertTrue(demotedFrom(listedForeground = false, type = types))
        // A service that Android's list no longer names does not run in the foreground either.
        assertTrue(demotedFrom(listedForeground = null, type = types))
    }

    @Test
    fun aTypeOfZeroCountsAsDemotedAsOnAndroid14AndLater() {
        assertTrue(demotedFrom(listedForeground = true, type = 0))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.DemotionTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference 'demotedFrom'`.

- [ ] **Step 3: Write the conditions**

Create `app/src/main/kotlin/dev/kestrel/app/listen/AndroidListeningConditions.kt`:

```kotlin
package dev.kestrel.app.listen

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * What Kestrel reads about the phone to say why it stopped (design spec, section 14.6), through Android's services. Any
 * thread may call it: each answer is a binder call.
 */
internal class AndroidListeningConditions(private val context: Context) : ListeningConditions {
    private val activities = context.getSystemService(ActivityManager::class.java)

    override fun microphoneAllowed() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun backgroundRestricted() = activities.isBackgroundRestricted

    // The service's type is a binder call with the service's token (prototype P2).
    override fun demoted(service: Any) = demotedFrom(listedForeground(), (service as ListeningService).foregroundServiceType)

    /**
     * Whether Android runs ListeningService in the foreground, from Android's list of Kestrel's running services, or
     * null when the list does not name the service. Android still lists an app's own services, and the list's
     * foreground field is the service record's isForeground (AOSP ActiveServices, android13-release).
     */
    @Suppress("DEPRECATION")
    fun listedForeground(): Boolean? = activities.getRunningServices(Int.MAX_VALUE)
        .firstOrNull { it.service.packageName == context.packageName && it.service.className == SERVICE }
        ?.foreground

    private companion object {
        val SERVICE: String = ListeningService::class.java.name
    }
}

/**
 * Whether Android took the foreground from ListeningService (design spec, section 14.6): from [listedForeground], the
 * foreground field of Android's list of Kestrel's running services (null when the list does not name the service), and
 * [type], the service's foreground service type. Android 13 keeps the type when it takes the foreground away, so there
 * only the list tells (analysis of the A71 session of sub-project 2, items 1 and 2); Android 14 and later also set the
 * type to 0.
 */
internal fun demotedFrom(listedForeground: Boolean?, type: Int): Boolean = listedForeground != true || type == 0
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.Manifest
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
```

with

```kotlin
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.util.Log
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.AndroidExitRecords
```

with

```kotlin
import dev.kestrel.app.listen.AndroidExitRecords
import dev.kestrel.app.listen.AndroidListeningConditions
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.Listening
import dev.kestrel.app.listen.ListeningConditions
import dev.kestrel.app.listen.ListeningOptions
```

with

```kotlin
import dev.kestrel.app.listen.Listening
import dev.kestrel.app.listen.ListeningOptions
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
            conditions = object : ListeningConditions {
                override fun microphoneAllowed() =
                    appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                override fun backgroundRestricted() =
                    appContext.getSystemService(ActivityManager::class.java).isBackgroundRestricted

                // A binder call with the service's token, which any thread may make (prototype P2). The type is 0 while
                // the service is not in the foreground.
                override fun demoted(service: Any) = (service as ListeningService).foregroundServiceType == 0
            },
```

with

```kotlin
            conditions = AndroidListeningConditions(appContext),
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    /**
     * Whether Android took the foreground from ListeningService's instance [service]: its foreground service type is 0.
     * Android does this without an exception or a callback (prototype P2).
     */
    fun demoted(service: Any): Boolean
```

with

```kotlin
    /**
     * Whether Android took the foreground from ListeningService's instance [service], which Android does without an
     * exception or a callback (prototype P2). Android 13 keeps the service's type when it does so, so the type alone does
     * not tell (AndroidListeningConditions).
     */
    fun demoted(service: Any): Boolean
```

- [ ] **Step 4: Run the JVM tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:app` has A₀ + 2 tests (`DemotionTest` 2), all passing.

- [ ] **Step 5: Let the device test read the foreground as Android 13 prints it, and add a test of a lost foreground**

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace

```kotlin
        @SuppressLint("StaticFieldLeak")
        private var current: ListeningService? = null
```

with

```kotlin
        @SuppressLint("StaticFieldLeak")
        private var current: ListeningService? = null

        /**
         * The running service, for the device tests and the debug hook voice-times, or null while none runs. Only the main
         * thread may call it.
         */
        internal fun running(): ListeningService? = current
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, replace

```kotlin
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
```

with

```kotlin
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, replace

```kotlin
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.ListeningService
```

with

```kotlin
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.AndroidListeningConditions
import dev.kestrel.app.listen.ListeningService
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, replace

```kotlin
            VoiceTests.startListening(clip)

            val types = foregroundTypes()
            assertTrue(
                "Foreground service types 0x${types.toString(16)}",
                (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0 &&
                    (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0,
            )
```

with

```kotlin
            VoiceTests.startListening(clip)

            // Android 13 prints the foreground but no types (analysis of the A71 session of sub-project 2, item 1), so the
            // types come from the running service, which has them while it is in the foreground.
            assertTrue("Android's record of the service shows no foreground", dumpedForeground())
            val types = foregroundTypes()
            assertTrue(
                "Foreground service types 0x${types.toString(16)}",
                (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0 &&
                    (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0,
            )
            // Where Android prints the types, as Android 14 ("types=%08X") and later ("types=0x%08X") do, they agree.
            dumpedTypes()?.let { assertEquals(types, it) }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, replace

```kotlin
    private fun texts(vararg ids: Int): List<String> = ids.map { context.getString(it) }
```

with

```kotlin
    @Test
    fun aServiceThatLostItsForegroundCountsAsDemoted() {
        val conditions = AndroidListeningConditions(context)
        ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }
        val service = runningService()
        assertFalse("A service in the foreground counts as demoted", conditions.demoted(service))
        try {
            // As Android does to a service whose app's battery use is Restricted (prototype P2): without flags, so that
            // the record keeps its notification's ID; Android 13 keeps the service's type then (analysis of the A71
            // session of sub-project 2, item 1).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service.stopForeground(Service.STOP_FOREGROUND_LEGACY)
            }
            assertTrue(
                "A service without the foreground does not count as demoted",
                VoiceTests.waitUntil(5_000) { conditions.demoted(service) },
            )
            assertEquals(false, conditions.listedForeground())
        } finally {
            VoiceTests.stopListening()
            // The service's notification of the turn-off no longer belongs to a foreground, so nothing else removes it.
            notifications.cancel(ListeningService.NOTIFICATION_ID)
        }
    }

    private fun texts(vararg ids: Int): List<String> = ids.map { context.getString(it) }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, replace

```kotlin
    /** ListeningService's foreground service types as Android reports them, or 0 when it is not in the foreground. */
    private fun foregroundTypes(): Int {
        val pattern = Regex("""isForeground=true foregroundId=${ListeningService.NOTIFICATION_ID} types=0x([0-9a-fA-F]+)""")
        return pattern.find(VoiceTests.shell("dumpsys activity services dev.kestrel"))?.groupValues?.get(1)?.toInt(16) ?: 0
    }
}
```

with

```kotlin
    /** The running ListeningService. */
    private fun runningService(): ListeningService {
        var found: ListeningService? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { found = ListeningService.running() }
        return checkNotNull(found) { "ListeningService does not run" }
    }

    /** ListeningService's foreground service types, from the running service itself. */
    private fun foregroundTypes(): Int {
        val service = runningService()
        var types = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { types = service.foregroundServiceType }
        return types
    }

    /** Whether Android's record of ListeningService says that it runs in the foreground, which every version prints. */
    private fun dumpedForeground(): Boolean = FOREGROUND.containsMatchIn(services())

    /** The types that Android's record prints after the foreground, or null where it prints none, as Android 13 does. */
    private fun dumpedTypes(): Int? = TYPES.find(services())?.groupValues?.get(1)?.toInt(16)

    private fun services(): String = VoiceTests.shell("dumpsys activity services dev.kestrel")

    private companion object {
        val RECORD = """isForeground=true foregroundId=${ListeningService.NOTIFICATION_ID}\b"""
        val FOREGROUND = Regex(RECORD)
        val TYPES = Regex(RECORD + """.*?\btypes=(?:0x)?([0-9a-fA-F]+)""")
    }
}
```

- [ ] **Step 6: Run the voice device tests on the emulator**

Take the emulator lock first, and delete it after the run, also when it fails.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: `Voice tests: D₀ + 1 run, 0 failed, 0 errors, 0 skipped.`, among them `ListeningServiceTest#aServiceThatLostItsForegroundCountsAsDemoted` and `#theServiceRunsInTheForegroundAndItsNotificationPausesResumesAndStops`.

- [ ] **Step 7: Write the failing Pester tests of Android's record**

In `scripts/tests/Session.Tests.ps1`, add at the end of the file:

```powershell

Describe 'Android''s record of the listening service' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'reads the foreground from Android 13''s record, which prints no types' {
        $dump = @(
            'ACTIVITY MANAGER SERVICES (dumpsys activity services)'
            '  User 0 active services:'
            '  * ServiceRecord{b2c1d3e u0 dev.kestrel/.app.listen.ListeningService}'
            '    intent={cmp=dev.kestrel/.app.listen.ListeningService}'
            '    isForeground=true foregroundId=7101 foregroundNoti=Notification(channel=listening flags=0x6a actions=2 vis=PRIVATE)'
            '    createTime=-3m2s1ms startingBgTimeout=--'
        )

        $record = Get-ForegroundRecord -Dump $dump

        $record.Listed | Should -BeTrue
        $record.Foreground | Should -BeExactly 'true'
        $record.Types | Should -BeExactly 'not printed'
    }

    It 'reads the record as Android 14 and Android 15 to 17 print it' {
        $fourteen = Get-ForegroundRecord -Dump @(
            '  * ServiceRecord{1 u0 dev.kestrel/.app.listen.ListeningService}'
            '    isForeground=false foregroundId=7101 types=00000082 foregroundNoti=null'
        )
        # From Android 15 on, the first line names the package that last started the service before its '}'.
        $later = Get-ForegroundRecord -Dump @(
            '  * ServiceRecord{2 u0 dev.kestrel/.app.listen.ListeningService c:dev.kestrel}'
            '    intent={cmp=dev.kestrel/.app.listen.ListeningService}'
            '    isForeground=true foregroundId=7101 types=0x00000082 foregroundNoti=Notification(channel=listening flags=0x6a actions=2 vis=PRIVATE)'
        )

        $fourteen.Foreground | Should -BeExactly 'false'
        $fourteen.Types | Should -BeExactly '0x82'
        $later.Listed | Should -BeTrue
        $later.Foreground | Should -BeExactly 'true'
        $later.Types | Should -BeExactly '0x82'
    }

    It 'says when the record is missing, and reads no other service''s record' {
        $record = Get-ForegroundRecord -Dump @(
            '  * ServiceRecord{3 u0 dev.kestrel/.app.KestrelAccessibilityService}'
            '    isForeground=true foregroundId=1 foregroundNoti=null types=0x00000001'
            '  Connection bindings to services:'
        )

        $record.Listed | Should -BeFalse
        $record.Foreground | Should -BeExactly 'not printed'
        $record.Types | Should -BeExactly 'not printed'
    }
}
```

Run: `pwsh -NoProfile -Command "Import-Module Pester -RequiredVersion 6.2.0; Invoke-Pester scripts\tests\Session.Tests.ps1"`
Expected: the three new tests fail with `The term 'Get-ForegroundRecord' is not recognized`.

- [ ] **Step 8: Write the reader of Android's record**

In `scripts/lib/Session.ps1`, add at the end of the file:

```powershell

# Android's record of Kestrel's ListeningService in 'dumpsys activity services dev.kestrel' (analysis of the A71 session
# of sub-project 2, items 1 and 2): Listed, whether the dump has the record; Foreground, 'true' or 'false' from its line
# 'isForeground=... foregroundId=...', which Android prints only while the service is in the foreground or keeps its
# notification's ID, and 'not printed' otherwise; and Types, the foreground service types where Android prints them,
# Android 14 as 'types=00000082' and later versions as 'types=0x00000082', always as '0x82', or 'not printed', as on
# Android 13. A record's lines are indented more than its first line, which starts with '* ServiceRecord{'; from
# Android 15 on, that line ends with ' c:' and the package that last started the service, before its '}'.
function Get-ForegroundRecord([string[]]$Dump) {
    $listed = $false
    $inRecord = $false
    $foreground = 'not printed'
    $types = 'not printed'
    foreach ($line in $Dump) {
        if ($line -match '^\s*\* ServiceRecord\{') {
            $inRecord = $line -match '\sdev\.kestrel/\.app\.listen\.ListeningService[\s}]'
            if ($inRecord) { $listed = $true }
            continue
        }
        if ($line -match '^\s{0,2}\S') { $inRecord = $false }
        if (-not $inRecord) { continue }
        if ($foreground -eq 'not printed' -and $line -match '\bisForeground=(true|false)\b') { $foreground = $Matches[1] }
        if ($types -eq 'not printed' -and $line -match '\btypes=(?:0x)?([0-9a-fA-F]+)\b') {
            $types = '0x{0:X}' -f [Convert]::ToInt64($Matches[1], 16)
        }
    }
    [pscustomobject]@{ Listed = $listed; Foreground = $foreground; Types = $types }
}
```

- [ ] **Step 9: Run the scripts' tests in both shells**

Run: `scripts\test-scripts.ps1`
Expected: Pester passes P₀ + 3 tests in Windows PowerShell 5.1 and in PowerShell 7, and PSScriptAnalyzer reports no problem.

- [ ] **Step 10: Say how Kestrel sees the loss in spec 14.6**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
(prototype P2); Kestrel then turns off at once and says why, and it says the same when Android stops the service |
```

with

```markdown
(prototype P2); Kestrel sees it in Android's list of its running services, because Android 13 keeps the service's type when it takes the foreground away (A71 session of sub-project 2), then turns off at once and says why, and it says the same when Android stops the service |
```

- [ ] **Step 11: Check the build and stop Gradle**

Run: `scripts\gradle.ps1 lint kestrelAudit; scripts\gradle.ps1 --stop`
Expected: `BUILD SUCCESSFUL`, no lint issues.

- [ ] **Step 12: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/AndroidListeningConditions.kt app/src/test/kotlin/dev/kestrel/app/listen/DemotionTest.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt scripts/lib/Session.ps1 scripts/tests/Session.Tests.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "fix(listen): see on Android 13 that Android took the foreground from the listening service, and read its record as Android 13 prints it" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 3: The phone's own counts and times

Analysis items 8, 10 and 11, and the owner's lesson: no measure of the A71 session may be timed from the owner's Enter. Kestrel records on the phone's clock (`SystemClock.elapsedRealtime`, which the shell's `/proc/uptime` also counts) when it was started and by what, when its voice session opened, when the latest transcribed speech ended (as the speech detector found it, carried by the pipeline's transcript), when it reacted and how long after that speech, when and why it turned itself off, and when its settings were asked for and came to the front. It also counts the keyword spotter's hits by keyword, the transcribed utterances, those that began with the name, their seconds of audio and how Requests read each one. The hooks `voice-times` and `voice-counts` answer them, with times, counts and the names of kinds only.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/Timeline.kt`
- Create: `app/src/test/kotlin/dev/kestrel/app/listen/TimelineTest.kt`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`Event.Heard`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, `Listening.kt`, `ListeningService.kt`, `KestrelTileService.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `MainActivity.kt`, `KestrelAccessibilityService.kt`, `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, `AssistActivity.kt`
- Modify: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, `VoiceSessionFailuresTest.kt`
- Modify: `scripts/hook.ps1`, `scripts/lib/Session.ps1`, `scripts/tests/Session.Tests.ps1`, `scripts/a71-session.ps1` (the old session's pattern of voice-counts, until Task 9)

**Interfaces:**
- Consumes: `AndroidListeningConditions.listedForeground()`, `demoted(service)` and `ListeningService.running()` from Task 2.
- Produces: `VoiceEvent.Heard.endedAt: Long` (0 without a clock) and `ListeningPipeline(..., now: (() -> Long)? = null)`; `Event.Heard(text, named, seconds: Float = 0f, endedAt: Long = 0L)`; `internal class Timeline(now: () -> Long)` with `start(source)`, `opened()`, `speechEnded(at)`, `reacted(kind)`, `stopped(reason)`, `settingsAsked()`, `settingsResumed()`, `settingsPaused()`, the properties `started`, `opened`, `speechEnded`, `reacted`, `stopped` (each a `Timeline.Mark(kind, at, sinceSpeech)` or a time), `settingsAsked`, `settingsResumes: StateFlow<Long>`, `settingsInFront: Boolean`, and `describe()`; `internal class VoiceCounts` with `spotted(keyword)`, `heard(event)` and `describe()`; `AppGraph.timeline`; `Listening(..., timeline: Timeline = Timeline { 0L })` and `Listening.voiceCounts`; the hook answers `voice-times` (`now=… started=<source>@<ms> opened=<ms> speechEnded=<ms> reacted=<kind>@<ms>+<ms after speech> stopped=<reason>@<ms> settingsAsked=<ms> settingsResumed=<ms> settingsInFront=<bool> foreground=<true|false|none> demoted=<true|false|none>`) and `voice-counts` (the four counts as before, then `audioMillis=`, `hit-kestrel=`, `hit-stop=`, `hit-cancel=`, `named=`, `read-<kind>=` and `utterances=` where they are above 0, sorted by name); the PowerShell functions `ConvertFrom-HookPairs -Text` (an ordered table) and `ConvertFrom-TimeMark -Text` (`Kind`, `At`, `SinceSpeech`, or `$null` for `none`). Task 4 uses `settingsAsked`, `settingsResumes` and `settingsInFront`; Task 9 uses the hooks.

- [ ] **Step 1: Write the failing test of the end of speech in the pipeline**

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, replace

```kotlin
    private val events = mutableListOf<VoiceEvent>()
    private var fed = 0L
```

with

```kotlin
    private val events = mutableListOf<VoiceEvent>()
    private var fed = 0L

    // The phone's clock in milliseconds, which the tests move by hand.
    private var clock = 0L
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, replace

```kotlin
    @Test
    fun pausedTranscribesOnlyUtterancesThatStartWithTheName() = runTest {
```

with

```kotlin
    @Test
    fun aTranscriptCarriesThePhonesTimeOfTheEndOfItsSpeech() = runTest {
        val pipeline = pipeline()
        pipeline.feedUntil(1.98f)
        clock = 50_000L

        // The frame that ends at 2 s of audio arrives while the phone's clock reads 50 s, and the utterance ended at 1.5 s.
        detector.next.addLast(listOf(utterance(0.5f, 1f)))
        pipeline.feed()

        assertEquals(49_500L, (events.single() as VoiceEvent.Heard).endedAt)
    }

    @Test
    fun pausedTranscribesOnlyUtterancesThatStartWithTheName() = runTest {
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, replace

```kotlin
        val pipeline = ListeningPipeline(
            detector, spotter, recognizer, boostWords, { it in ownVoice }, backgroundScope, context ?: unconfined,
        )
```

with

```kotlin
        val pipeline = ListeningPipeline(
            detector, spotter, recognizer, boostWords, { it in ownVoice }, backgroundScope, context ?: unconfined,
            now = { clock },
        )
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD FAILED` in `:core:voice:compileDebugUnitTestKotlin`, with `No parameter with name 'now' found` and `Unresolved reference 'endedAt'`.

- [ ] **Step 2: Let the pipeline say when the speech of each transcript ended**

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, replace

```kotlin
    /**
     * The transcript of an utterance of [seconds] that took [millis] to transcribe; it may be empty. [named] is true when
     * the keyword spotter heard "Kestrel" at its start. The part of an utterance that is left after the pipeline cut away
     * Kestrel's own voice is never named, because the cut removed the part that held the name.
     */
    data class Heard(val text: String, val named: Boolean, val seconds: Float, val millis: Long) : VoiceEvent {
```

with

```kotlin
    /**
     * The transcript of an utterance of [seconds] that took [millis] to transcribe; it may be empty. [named] is true when
     * the keyword spotter heard "Kestrel" at its start. The part of an utterance that is left after the pipeline cut away
     * Kestrel's own voice is never named, because the cut removed the part that held the name. [endedAt] is the end of
     * the utterance's speech on the clock of the pipeline's `now`, in milliseconds, or 0 without such a clock. It is read
     * when the microphone's thread handles the newest frame, so when that thread falls behind the recording, the end
     * looks later than it was, by as much; on the A71 the pipeline runs faster than real time.
     */
    data class Heard(
        val text: String,
        val named: Boolean,
        val seconds: Float,
        val millis: Long,
        val endedAt: Long = 0L,
    ) : VoiceEvent {
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, replace

```kotlin
 * [pause] from one thread at a time: from the microphone's thread, or after it has stopped.
 */
class ListeningPipeline(
```

with

```kotlin
 * [pause] from one thread at a time: from the microphone's thread, or after it has stopped. [now] reads the phone's clock
 * in milliseconds when an utterance is queued, so that its transcript says when its speech ended, for the A71 session's
 * times (plan 2E); the newest frame's last sample counts as the present.
 */
class ListeningPipeline(
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, replace

```kotlin
    scope: CoroutineScope,
    recognitionContext: CoroutineContext,
) {
    private val output = Channel<VoiceEvent>(Channel.UNLIMITED)
    private val queue = Channel<Speech>(Channel.UNLIMITED)
```

with

```kotlin
    scope: CoroutineScope,
    recognitionContext: CoroutineContext,
    private val now: (() -> Long)? = null,
) {
    private val output = Channel<VoiceEvent>(Channel.UNLIMITED)
    private val queue = Channel<Queued>(Channel.UNLIMITED)
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, replace

```kotlin
        for (speech in queue) transcribe(speech)
```

with

```kotlin
        for (queued in queue) transcribe(queued.speech, queued.endedAt)
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, replace

```kotlin
            } else if (transcribes(wanted, kept.named)) {
                queue.trySend(kept)
            }
```

with

```kotlin
            } else if (transcribes(wanted, kept.named)) {
                queue.trySend(Queued(kept, endedAt(kept)))
            }
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt`, replace

```kotlin
    private suspend fun transcribe(speech: Speech) {
        val event = try {
            val words = boostWords()
            val started = System.nanoTime()
            val transcript = recognizer.transcribe(speech.samples, words)
            VoiceEvent.Heard(transcript.text, speech.named, speech.seconds, (System.nanoTime() - started) / 1_000_000)
```

with

```kotlin
    /** The end of [speech] on the clock of [now], or 0 without one; the newest frame ends at [position]. */
    private fun endedAt(speech: Speech): Long =
        now?.let { it() - (position - speech.endSample) * 1_000 / SAMPLE_RATE } ?: 0L

    /** An utterance that waits for the recogniser, with the end of its speech on the clock of [now]. */
    private class Queued(val speech: Speech, val endedAt: Long)

    private suspend fun transcribe(speech: Speech, endedAt: Long) {
        val event = try {
            val words = boostWords()
            val started = System.nanoTime()
            val transcript = recognizer.transcribe(speech.samples, words)
            val millis = (System.nanoTime() - started) / 1_000_000
            VoiceEvent.Heard(transcript.text, speech.named, speech.seconds, millis, endedAt)
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:core:voice` has V₀ + 1 tests, all passing.

- [ ] **Step 3: Write the failing JVM tests of the timeline and the counts**

Create `app/src/test/kotlin/dev/kestrel/app/listen/TimelineTest.kt`:

```kotlin
package dev.kestrel.app.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private var now = 10_000L
    private val timeline = Timeline { now }

    @Test
    fun aReactionCountsFromTheEndOfTheLatestSpeech() {
        timeline.reacted("mode-paused")
        assertEquals(Timeline.Mark("mode-paused", 10_000L), timeline.reacted)

        timeline.speechEnded(9_400L)
        // An unknown end, as from a pipeline without a clock, changes nothing.
        timeline.speechEnded(0L)
        now = 10_250L
        timeline.reacted("wait")

        assertEquals(Timeline.Mark("wait", 10_250L, sinceSpeech = 850L), timeline.reacted)
    }

    @Test
    fun theHooksAnswerHoldsOnlyTimesAndKinds() {
        assertEquals(
            "now=10000 started=none opened=0 speechEnded=0 reacted=none stopped=none settingsAsked=0 settingsResumed=0 " +
                "settingsInFront=false",
            timeline.describe(),
        )
        timeline.start("tile")
        now = 16_000L
        timeline.opened()
        timeline.speechEnded(17_000L)
        now = 17_600L
        timeline.reacted("wait")
        timeline.stopped("BATTERY_RESTRICTED")
        timeline.settingsAsked()
        now = 18_000L
        timeline.settingsResumed()
        assertTrue(timeline.settingsInFront)

        assertEquals(
            "now=18000 started=tile@10000 opened=16000 speechEnded=17000 reacted=wait@17600+600 " +
                "stopped=BATTERY_RESTRICTED@17600 settingsAsked=17600 settingsResumed=18000 settingsInFront=true",
            timeline.describe(),
        )
        timeline.settingsPaused()
        assertFalse(timeline.settingsInFront)
        assertEquals(18_000L, timeline.settingsResumes.value)
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    // The wall clock, in milliseconds.
    private var now = 1_000_000L
```

with

```kotlin
    // The wall clock, in milliseconds.
    private var now = 1_000_000L

    // The phone's clock of the timeline, in milliseconds, which the tests move by hand.
    private var elapsed = 50_000L
    private val timeline = Timeline { elapsed }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
        options = options,
        microphones = { microphone },
    )

    // Android gives every start of the service the next start ID, and onStartCommand reports it with the instance.
```

with

```kotlin
        options = options,
        microphones = { microphone },
        timeline = timeline,
    )

    // Android gives every start of the service the next start ID, and onStartCommand reports it with the instance.
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    @Test
    fun aStopByAndroidUnderTheBatteryRestrictionSaysSo() = runTest {
```

with

```kotlin
    @Test
    fun theTimelineMarksTheSessionTheReactionToSpeechAndAStop() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        elapsed += 6_000
        listening.onForeground(service)
        runCurrent()
        assertEquals(56_000L, timeline.opened)

        // The owner's "Kestrel" ended 700 ms before Kestrel began to wait for the task.
        elapsed += 700
        sessions.opened.single().listener.send(Event.Heard("Kestrel", named = true, seconds = 0.6f, endedAt = 56_000L))
        runCurrent()
        assertEquals(Timeline.Mark("wait", 56_700L, sinceSpeech = 700L), timeline.reacted)

        conditions.restricted = true
        conditions.demoted = true
        elapsed += 1_000
        listening.send(Event.MicBusy(true))
        runCurrent()
        assertEquals(Timeline.Mark("BATTERY_RESTRICTED", 57_700L), timeline.stopped)
    }

    @Test
    fun theCountsCountHitsUtterancesAndReadsButKeepNoWords() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener

        events.send(Event.Spotted(Keyword.KESTREL))
        events.send(Event.Spotted(Keyword.STOP))
        events.send(Event.Heard("Kestrel, wake up.", named = true, seconds = 1.25f))
        events.send(Event.Heard("", named = false, seconds = 0.5f))
        runCurrent()

        assertEquals(
            "audioMillis=1750 hit-kestrel=1 hit-stop=1 named=1 read-empty=1 read-wakeup=1 utterances=2",
            listening.voiceCounts.describe(),
        )
    }

    @Test
    fun aStopByAndroidUnderTheBatteryRestrictionSaysSo() = runTest {
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.TimelineTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference 'Timeline'`, `No parameter with name 'timeline' found` and `No parameter with name 'seconds' found`.

- [ ] **Step 4: Write the timeline and the counts**

Create `app/src/main/kotlin/dev/kestrel/app/listen/Timeline.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.voice.Keyword
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * The times on the phone's clock, [now] (SystemClock.elapsedRealtime in milliseconds, which the shell's /proc/uptime
 * also counts), of the events that the A71 session measures, so that no measure counts from the owner's Enter (plan 2E;
 * analysis of the A71 session of sub-project 2). The debug hook voice-times reads them through [describe]; plan 2E's
 * "Kestrel, open settings" also reads [settingsResumes] and [settingsInFront]. It holds times and the names of kinds, never words. Any
 * thread may use it.
 */
internal class Timeline(private val now: () -> Long) {
    /** An event of [kind] at [at]; [sinceSpeech] is how long after the end of the latest speech it came, or null. */
    data class Mark(val kind: String, val at: Long, val sinceSpeech: Long? = null)

    /** The latest event that turned Kestrel on or toggled it, by its source, such as "tile". */
    @Volatile
    var started: Mark? = null
        private set

    /** When the latest voice session opened, or 0. */
    @Volatile
    var opened = 0L
        private set

    /** The end of the latest transcribed utterance, as the speech detector found it, or 0. */
    @Volatile
    var speechEnded = 0L
        private set

    /**
     * Kestrel's latest reaction: the start of a wait for a task ("wait"), a mode ("mode-paused"), a task or a command. Its
     * [Mark.sinceSpeech] counts from the end of the latest transcribed speech, whatever caused the reaction, so a reaction
     * that no transcript caused, such as a spotter's hit while Kestrel speaks, counts from older speech; a reader compares
     * `at - sinceSpeech` with the start of its try.
     */
    @Volatile
    var reacted: Mark? = null
        private set

    /**
     * Why and when Kestrel last turned itself off, by the name of its StopReason: only a reason that turned a listening
     * or paused Kestrel off, not one that came while Kestrel was off or while an earlier turn-off was on its way.
     */
    @Volatile
    var stopped: Mark? = null
        private set

    /** When Kestrel last asked Android for its settings, or 0. */
    @Volatile
    var settingsAsked = 0L
        private set

    /** Whether Kestrel's settings are in front now: from MainActivity's onResume until its onPause. */
    @Volatile
    var settingsInFront = false
        private set

    private val resumes = MutableStateFlow(0L)

    /** When Kestrel's settings last came to the front, or 0. */
    val settingsResumes: StateFlow<Long> = resumes.asStateFlow()

    fun start(source: String) {
        started = Mark(source, now())
    }

    fun opened() {
        opened = now()
    }

    /** [at] is on the same clock; 0 means that the end is not known, which changes nothing. */
    fun speechEnded(at: Long) {
        if (at > 0) speechEnded = at
    }

    fun reacted(kind: String) {
        val at = now()
        val speech = speechEnded
        reacted = Mark(kind, at, if (speech > 0) at - speech else null)
    }

    fun stopped(reason: String) {
        stopped = Mark(reason, now())
    }

    fun settingsAsked() {
        settingsAsked = now()
    }

    fun settingsResumed() {
        settingsInFront = true
        resumes.value = now()
    }

    fun settingsPaused() {
        settingsInFront = false
    }

    /**
     * The answer of the hook voice-times: each time in milliseconds on the phone's clock, and 0 or none for what has not
     * happened in this process, such as "now=81234 started=tile@80012 opened=86010 speechEnded=0 reacted=none ...".
     */
    fun describe(): String =
        "now=${now()} started=${text(started)} opened=$opened speechEnded=$speechEnded reacted=${text(reacted)} " +
            "stopped=${text(stopped)} settingsAsked=$settingsAsked settingsResumed=${resumes.value} " +
            "settingsInFront=$settingsInFront"

    private fun text(mark: Mark?): String =
        mark?.let { "${it.kind}@${it.at}" + (it.sinceSpeech?.let { since -> "+$since" } ?: "") } ?: "none"
}

/**
 * What the voice sessions of this process heard, for the A71 session (plan 2E): the keyword spotter's hits by keyword,
 * the transcribed utterances, those at whose start the spotter heard the name, their audio in milliseconds, and how
 * Requests read each transcript outside a wait for a task, by the kind of request. Counts only, never words. Any thread
 * may use it.
 */
internal class VoiceCounts {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun spotted(keyword: Keyword) = add("hit-${keyword.name.lowercase()}", 1)

    fun heard(event: Event.Heard) {
        add("utterances", 1)
        if (event.named) add("named", 1)
        add("audioMillis", (event.seconds * 1_000).roundToLong())
        val read = Requests.read(event.text, event.named)
        add("read-${read?.javaClass?.simpleName?.lowercase() ?: "empty"}", 1)
    }

    /** The counts above 0, sorted by name, such as "audioMillis=1750 hit-kestrel=1 named=1 ... utterances=2". */
    fun describe(): String = counts.entries
        .filter { it.value.get() > 0 }
        .sortedBy { it.key }
        .joinToString(" ") { "${it.key}=${it.value.get()}" }

    private fun add(key: String, amount: Long) {
        counts.getOrPut(key) { AtomicLong() }.addAndGet(amount)
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    /** A transcript. [named] is true when the keyword spotter heard "Kestrel" at its start. */
    data class Heard(val text: String, val named: Boolean) : Event {
```

with

```kotlin
    /**
     * A transcript. [named] is true when the keyword spotter heard "Kestrel" at its start. [seconds] is the length of its
     * audio, and [endedAt] the end of its speech on the phone's clock (SystemClock.elapsedRealtime), or 0 when it is not
     * known; only the A71 session's counts and times read them (plan 2E).
     */
    data class Heard(val text: String, val named: Boolean, val seconds: Float = 0f, val endedAt: Long = 0L) : Event {
```

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, replace

```kotlin
        scope = scope,
        recognitionContext = Dispatchers.Default.limitedParallelism(1),
    )
```

with

```kotlin
        scope = scope,
        recognitionContext = Dispatchers.Default.limitedParallelism(1),
        now = SystemClock::elapsedRealtime,
    )
```

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, replace

```kotlin
            is VoiceEvent.Heard -> listener.send(Event.Heard(event.text, event.named))
```

with

```kotlin
            is VoiceEvent.Heard -> listener.send(Event.Heard(event.text, event.named, event.seconds, event.endedAt))
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
 * of Kestrel never drive the screen at once. Every command that started gets one report in [commandReports].
 */
class Listening internal constructor(
```

with

```kotlin
 * of Kestrel never drive the screen at once. Every command that started gets one report in [commandReports].
 * [timeline] records on the phone's clock when a session opened, when Kestrel reacted to speech and why it turned itself
 * off, and [voiceCounts] counts what the sessions heard, as times and counts only, for the A71 session (plan 2E).
 */
class Listening internal constructor(
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    options: Flow<ListeningOptions>,
    microphones: () -> Microphone,
) {
    private enum class ServiceState { STOPPED, STARTING, FOREGROUND }
```

with

```kotlin
    options: Flow<ListeningOptions>,
    microphones: () -> Microphone,
    private val timeline: Timeline = Timeline { 0L },
) {
    private enum class ServiceState { STOPPED, STARTING, FOREGROUND }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    internal val keywordHits: Int get() = spotted.get()
```

with

```kotlin
    internal val keywordHits: Int get() = spotted.get()

    /** What the voice sessions of this process heard, as counts only, for the debug hooks (plan 2E). */
    internal val voiceCounts = VoiceCounts()
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
                session = opened
                listener = events
                sessionState.value = true
```

with

```kotlin
                session = opened
                listener = events
                sessionState.value = true
                timeline.opened()
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
            offReason != null -> return
            else -> {
                offReason = reason.text
                send(Event.TurnOff)
```

with

```kotlin
            offReason != null -> return
            else -> {
                offReason = reason.text
                // Only the reason that Kestrel acts on: not one while Kestrel is off, nor a later one during a turn-off.
                timeline.stopped(reason.name)
                send(Event.TurnOff)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
            is Effect.RunTask ->
                afterStopping({ send(Event.TaskEnded(effect.task, reply = null)) }) { runTask(effect.task, effect.text) }
```

with

```kotlin
            is Effect.RunTask -> {
                timeline.reacted("task")
                afterStopping({ send(Event.TaskEnded(effect.task, reply = null)) }) { runTask(effect.task, effect.text) }
            }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
            is Effect.RunCommand -> {
                newestCommand = effect.command
```

with

```kotlin
            is Effect.RunCommand -> {
                newestCommand = effect.command
                timeline.reacted("command")
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
            is Effect.Show -> {
                (effect.feedback as? Feedback.ModeChanged)?.let { noteMode(it.mode) }
```

with

```kotlin
            is Effect.Show -> {
                (effect.feedback as? Feedback.ModeChanged)?.let {
                    noteMode(it.mode)
                    timeline.reacted("mode-${it.mode.name.lowercase()}")
                }
                if (effect.feedback == Feedback.AwaitingTask) timeline.reacted("wait")
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
        override fun send(event: Event) {
            if (event is Event.Spotted && active) spotted.incrementAndGet()
            orchestrator.send(event) { active }
        }
```

with

```kotlin
        override fun send(event: Event) {
            if (active) {
                if (event is Event.Spotted) {
                    spotted.incrementAndGet()
                    voiceCounts.spotted(event.keyword)
                }
                if (event is Event.Heard) {
                    voiceCounts.heard(event)
                    timeline.speechEnded(event.endedAt)
                }
            }
            orchestrator.send(event) { active }
        }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt`, replace

```kotlin
        assertEquals(listOf<Event>(Event.Heard(TEXT, named = false)), listener.events.filterIsInstance<Event.Heard>())
```

with

```kotlin
        // The transcript also carries its length and the end of its speech, which the A71 session's counts read.
        val heard = listener.events.filterIsInstance<Event.Heard>().map { it.text to it.named }
        assertEquals(listOf(TEXT to false), heard)
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:app` has A₀ + 6 tests (`TimelineTest` 2, `ListeningTest` L₀ + 2), all passing.

- [ ] **Step 5: Mark each start, and the settings in front**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.content.Intent
import android.util.Log
```

with

```kotlin
import android.content.Intent
import android.os.SystemClock
import android.util.Log
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.StoredMode
```

with

```kotlin
import dev.kestrel.app.listen.StoredMode
import dev.kestrel.app.listen.Timeline
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
    /** Kestrel's notifications about problems (design spec, section 14.6). */
    internal val problems: ProblemNotifications by lazy { ProblemNotifications(appContext) }
```

with

```kotlin
    /** Kestrel's notifications about problems (design spec, section 14.6). */
    internal val problems: ProblemNotifications by lazy { ProblemNotifications(appContext) }

    /** The phone's own times of Kestrel's starts, sessions, reactions, turn-offs and settings (plan 2E). */
    internal val timeline = Timeline(SystemClock::elapsedRealtime)
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
            microphones = { MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }) },
        ).also(::keepScreenFresh)
```

with

```kotlin
            microphones = { MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }) },
            timeline = timeline,
        ).also(::keepScreenFresh)
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace

```kotlin
    override fun onResume() {
        super.onResume()
        refresh()
    }
```

with

```kotlin
    override fun onResume() {
        super.onResume()
        // The A71 session reads the time of the settings in front (plan 2E).
        graph.timeline.settingsResumed()
        refresh()
    }

    override fun onPause() {
        graph.timeline.settingsPaused()
        super.onPause()
    }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt`, replace

```kotlin
    override fun onClick() {
        super.onClick()
        listening.send(Event.Toggle)
    }
```

with

```kotlin
    override fun onClick() {
        super.onClick()
        // The A71 session times a start from here (plan 2E).
        (application as KestrelApplication).graph.timeline.start("tile")
        listening.send(Event.Toggle)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt`, replace

```kotlin
            override fun onClicked(controller: AccessibilityButtonController) {
                graph.listening.send(Event.Toggle)
            }
```

with

```kotlin
            override fun onClicked(controller: AccessibilityButtonController) {
                // The A71 session times a start from here; the button and the shortcut both arrive here (plan 2E).
                graph.timeline.start("accessibility")
                graph.listening.send(Event.Toggle)
            }
```

In `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, replace

```kotlin
        super.onShow(args, showFlags)
        (context.applicationContext as KestrelApplication).graph.listening.assist()
        hide()
```

with

```kotlin
        super.onShow(args, showFlags)
        val graph = (context.applicationContext as KestrelApplication).graph
        graph.timeline.start("assistant")
        graph.listening.assist()
        hide()
```

In `app/src/main/kotlin/dev/kestrel/app/assist/AssistActivity.kt`, replace

```kotlin
        (application as KestrelApplication).graph.listening.assist()
```

with

```kotlin
        val graph = (application as KestrelApplication).graph
        graph.timeline.start("headset")
        graph.listening.assist()
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
```

with

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The A71 session times a start through the problems notification's "Start listening" from here (plan 2E).
        if (intent?.action == ACTION_START) (application as KestrelApplication).graph.timeline.start("notification")
```

- [ ] **Step 6: Answer the times and the counts through the hooks**

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
```

with

```kotlin
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.AndroidListeningConditions
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.orchestrator.Event
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
```

with

```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
 * that opens during the check shares the microphone, and Android silences a recording from the background, which the
 * answer shows. Their answers hold only counts, lengths and levels.
 */
```

with

```kotlin
 * that opens during the check shares the microphone, and Android silences a recording from the background, which the
 * answer shows. VOICE_COUNTS also counts the keyword hits by keyword, the transcribed utterances, those that began with
 * the name, their audio in milliseconds and how Requests read each one, and VOICE_TIMES answers the phone's own times of
 * Kestrel's starts, sessions, ends of speech, reactions, turn-offs and settings, and whether Android runs the listening
 * service in the foreground (plan 2E). Their answers hold only counts, lengths, levels, times and the names of kinds.
 */
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
        "VOICE_COUNTS" -> graph.listening.state.value.let {
            "waits=${it.wait} tasks=${it.task} lastTaskWords=$lastTaskWords hits=${graph.listening.keywordHits}"
        }
        "VOICE_TASK" -> if (intent.getBooleanExtra(EXTRA_CLEAR, false)) clearTask() else task(intent)
```

with

```kotlin
        "VOICE_COUNTS" -> graph.listening.state.value.let {
            val counts = graph.listening.voiceCounts.describe()
            "waits=${it.wait} tasks=${it.task} lastTaskWords=$lastTaskWords hits=${graph.listening.keywordHits}" +
                if (counts.isEmpty()) "" else " $counts"
        }
        "VOICE_TIMES" -> times()
        "VOICE_TASK" -> if (intent.getBooleanExtra(EXTRA_CLEAR, false)) clearTask() else task(intent)
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
    private fun task(intent: Intent): String {
```

with

```kotlin
    // The phone's own times (plan 2E), and whether Android runs ListeningService in the foreground: foreground= from
    // Android's list of Kestrel's running services, demoted= Kestrel's decision from it, and none while no service runs.
    private suspend fun times(): String {
        val conditions = AndroidListeningConditions(context)
        val service = withContext(Dispatchers.Main) { ListeningService.running() }
        val foreground = conditions.listedForeground()?.toString() ?: "none"
        val demoted = service?.let { conditions.demoted(it).toString() } ?: "none"
        return "${graph.timeline.describe()} foreground=$foreground demoted=$demoted"
    }

    private fun task(intent: Intent): String {
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TASK", "VOICE_ECHO")
```

with

```kotlin
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TIMES", "VOICE_TASK", "VOICE_ECHO")
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace

```kotlin
            assertTrue(counts, Regex("waits=\\d+ tasks=${tasks + 1} lastTaskWords=3 hits=\\d+").matches(counts))
```

with

```kotlin
            // From plan 2E on, the counts that are above 0 follow, by name.
            val expected = Regex("waits=\\d+ tasks=${tasks + 1} lastTaskWords=3 hits=\\d+( [A-Za-z-]+=\\d+)*")
            assertTrue(counts, expected.matches(counts))
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace

```kotlin
    /** Runs a hook command as TestHookReceiver does, with string extras, int extras and boolean extras that are true. */
```

with

```kotlin
    @Test
    fun theTimesHookAnswersOnlyTimesAndKinds() {
        VoiceTests.graph.timeline.start("tile")

        val reply = hook("VOICE_TIMES")

        assertEquals(reply.text, Activity.RESULT_OK, reply.code)
        val answer = Regex(
            "now=\\d+ started=tile@\\d+ opened=\\d+ speechEnded=\\d+ reacted=\\S+ stopped=\\S+ settingsAsked=\\d+ " +
                "settingsResumed=\\d+ settingsInFront=(true|false) foreground=(true|false|none) demoted=(true|false|none)",
        )
        assertTrue(reply.text, answer.matches(reply.text))
    }

    /** Runs a hook command as TestHookReceiver does, with string extras, int extras and boolean extras that are true. */
```

In `scripts/hook.ps1`, replace

```powershell
#                                           how many words the last slow task had, and how many keyword hits its voice
#                                           sessions reported, without those of its own voice
```

with

```powershell
#                                           how many words the last slow task had, and how many keyword hits its voice
#                                           sessions reported, without those of its own voice; then, where they are above
#                                           0, the hits by keyword (hit-kestrel, hit-stop, hit-cancel), the transcribed
#                                           utterances, those that began with the name, their audio in milliseconds and
#                                           how Requests read each one (read-name, read-wakeup, read-other, read-empty
#                                           and so on); never words
#   voice-times                             the phone's own times (SystemClock.elapsedRealtime, in milliseconds) of
#                                           Kestrel's latest start and its source, the latest voice session's opening, the
#                                           end of the latest transcribed speech, Kestrel's latest reaction and how long
#                                           after that speech it came, its latest turn-off and why, the settings asked
#                                           for and in front, and whether Android runs the listening service in the
#                                           foreground (foreground= from Android's list, demoted= Kestrel's decision)
```

In `scripts/hook.ps1`, replace

```powershell
        'voice-counts', 'voice-task', 'voice-echo', 'location', 'spike-results', 'files', 'watch', 'profiles',
```

with

```powershell
        'voice-counts', 'voice-times', 'voice-task', 'voice-echo', 'location', 'spike-results', 'files', 'watch', 'profiles',
```

- [ ] **Step 7: Write the failing Pester test of the hooks' answers**

In `scripts/tests/Session.Tests.ps1`, add at the end of the file:

```powershell

Describe 'The answers of the hooks voice-times and voice-counts' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'reads names and values, and the marks of the times' {
        $times = ConvertFrom-HookPairs -Text 'now=81234 started=tile@80012 opened=86010 reacted=wait@86500+640 stopped=none'

        $times['now'] | Should -BeExactly '81234'
        (ConvertFrom-TimeMark -Text $times['started']).Kind | Should -BeExactly 'tile'
        (ConvertFrom-TimeMark -Text $times['started']).At | Should -Be 80012
        (ConvertFrom-TimeMark -Text $times['started']).SinceSpeech | Should -BeNullOrEmpty
        (ConvertFrom-TimeMark -Text $times['reacted']).SinceSpeech | Should -Be 640
        ConvertFrom-TimeMark -Text $times['stopped'] | Should -BeNullOrEmpty
        { ConvertFrom-HookPairs -Text 'waits=1 broken' } | Should -Throw
    }
}
```

Run: `pwsh -NoProfile -Command "Import-Module Pester -RequiredVersion 6.2.0; Invoke-Pester scripts\tests\Session.Tests.ps1"`
Expected: the new test fails with `The term 'ConvertFrom-HookPairs' is not recognized`.

- [ ] **Step 8: Read the hooks' answers**

In `scripts/lib/Session.ps1`, add at the end of the file:

```powershell

# Reads an answer of the hooks voice-times, voice-counts or voice-route, such as 'now=81234 started=tile@80012', into an
# ordered table of names and values. The answers hold no words. Throws when a part is not 'name=value'.
function ConvertFrom-HookPairs([string]$Text) {
    $pairs = [ordered]@{}
    foreach ($pair in @("$Text".Trim() -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw "Not an answer of the hooks: '$Text'" }
        $pairs[$name] = $value
    }
    $pairs
}

# A mark of voice-times, such as 'wait@86500+640': Kind, At (milliseconds on the phone's clock) and SinceSpeech
# (milliseconds after the end of the latest transcribed speech, or $null), or $null for 'none'.
function ConvertFrom-TimeMark([string]$Text) {
    if ($Text -notmatch '^([\w-]+)@(\d+)(?:\+(\d+))?$') { return $null }
    $since = if ($Matches[3]) { [long]$Matches[3] } else { $null }
    [pscustomobject]@{ Kind = $Matches[1]; At = [long]$Matches[2]; SinceSpeech = $since }
}
```

The old session script reads the answer of voice-counts with a pattern that ends after `hits=`. Task 9 replaces the whole script; until then, it must still read the longer answer.

In `scripts/a71-session.ps1`, replace

```powershell
    if ($text -notmatch '^waits=(\d+) tasks=(\d+) lastTaskWords=(\d+) hits=(\d+)$') { throw 'Unexpected answer of voice-counts.' }
```

with

```powershell
    if ($text -notmatch '^waits=(\d+) tasks=(\d+) lastTaskWords=(\d+) hits=(\d+)\b') { throw 'Unexpected answer of voice-counts.' }
```

- [ ] **Step 9: Run every test that this task touches**

Run: `scripts\test-scripts.ps1`
Expected: Pester passes P₀ + 4 tests in both shells, and PSScriptAnalyzer reports no problem.

Take the emulator lock first, keep it until the hook command below has run, and delete it then, also when a command fails.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: `Voice tests: D₀ + 2 run, 0 failed, 0 errors, 0 skipped.`, among them `SessionHookTest#theTimesHookAnswersOnlyTimesAndKinds` and `VoiceSessionFailuresTest#noEventReachesTheListenerAfterCloseReturns`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -File scripts\hook.ps1 -Serial emulator-5554 voice-times`
Expected: one line that starts with `now=` and ends with `foreground=none demoted=none` while Kestrel is off.

- [ ] **Step 10: Check the build and stop Gradle**

Run: `scripts\gradle.ps1 lint kestrelAudit; scripts\gradle.ps1 --stop`
Expected: `BUILD SUCCESSFUL`, no lint issues.

- [ ] **Step 11: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/Timeline.kt app/src/test/kotlin/dev/kestrel/app/listen/TimelineTest.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/ListeningPipeline.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt app/src/main/kotlin/dev/kestrel/app/assist/AssistActivity.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt scripts/hook.ps1 scripts/lib/Session.ps1 scripts/tests/Session.Tests.ps1 scripts/a71-session.ps1
git commit -m "feat(listen): record the phone's own times and counts of Kestrel's starts, speech and reactions for the A71 session" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 4: "Kestrel, open settings" with only the assistant role

Analysis item 5. With only the assistant role, Android 13 most likely blocked the start of the settings from the background, because the role's binding exempts Kestrel only while the binding client has a visible window, and Kestrel said nothing, because `startActivity()` blocks without an exception. Now Kestrel waits up to 2 s until `MainActivity` has resumed (`Timeline.settingsResumes` from Task 3). With only the role, it starts the settings through its own assistant session, which Android lets start the assistant's activity also over another app (`VoiceInteractionSession.startAssistantActivity`); with the accessibility service it starts them as before (prototype P1). When the settings do not come to the front, Kestrel says "Android did not let me open my settings. Tap my notification, or open Kestrel from the launcher.", posts a notification that opens them with a tap, because a notification's tap may always start an activity, and records its verdict on the phone as the reaction `settings-blocked`. The open runs off the controller's thread, because it waits; its decisions are in `SettingsOpener`, which JVM tests check with virtual time. The emulator checks the session's path with the role alone (`AssistTest`), and the A71 session checks it on Android 13 with the phone's own times (Task 9).

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`SettingsOpening`, `ListeningTexts.SETTINGS_BLOCKED`, `openSettings`, `openSettingsOrSayWhy`, `say`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`Event.SettingsBlocked`, `Reply.SettingsBlocked`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (`openSettings`, `report`, imports)
- Modify: `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt` (`settingsIntent`, `KestrelAssistantService.openSettings`, the session's settings)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt` (`offerSettings`, `settingsOpened`), `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/SettingsOpener.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/SettingsOpenerTest.kt`
- Modify: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.6 and 14.6), `README.md`

**Interfaces:**
- Consumes: `AppGraph.timeline` with `settingsInFront`, `settingsResumes`, `settingsAsked()` and `settingsResumed()` (MainActivity) from Task 3.
- Produces: `internal enum class SettingsOpening { OPENED, NEED_SERVICE, BLOCKED }`; `internal class SettingsOpener(timeline, serviceOn, roleHeld, startActivity, showSession, offer, report, waitMillis = 2_000L)` with `suspend fun open(): SettingsOpening`; the reaction mark `settings-blocked` (`Timeline.reacted`) when Kestrel gives up; `Listening(..., openSettings: suspend () -> SettingsOpening, ...)`; `Event.SettingsBlocked`, `Reply.SettingsBlocked`, `ListeningTexts.SETTINGS_BLOCKED`; `internal fun settingsIntent(context: Context): Intent` and `KestrelAssistantService.openSettings(): Boolean` with `EXTRA_OPEN_SETTINGS` (package `dev.kestrel.app.assist`); `ProblemNotifications.offerSettings()`, `settingsOpened()` and `SETTINGS_ID = 7_104`; `AppGraph.report(what, error)`.

- [ ] **Step 1: Write the failing JVM tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    private var settingsCanOpen = true
```

with

```kotlin
    private var settingsOpening = SettingsOpening.OPENED
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
            settingsCanOpen
        },
```

with

```kotlin
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
            settingsOpening
        },
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
            openSettings = { true },
```

with

```kotlin
            openSettings = { SettingsOpening.OPENED },
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
        // Android blocks the start from the background without an exception, and the bar does not exist.
        settingsCanOpen = false
```

with

```kotlin
        // Android blocks the start from the background without an exception, and the bar does not exist.
        settingsOpening = SettingsOpening.NEED_SERVICE
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    @Test
    fun aServiceThatCannotEnterTheForegroundWithoutTheMicrophonePermissionAsksForIt() = runTest {
```

with

```kotlin
    @Test
    fun settingsThatAndroidKeptBackAreSaidAndOffered() = runTest {
        // With only the assistant role, Android 13 may keep the settings back without an exception (A71 session of
        // sub-project 2, item 5); AppGraph then has posted the notification that opens them.
        settingsOpening = SettingsOpening.BLOCKED
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()

        assertEquals(listOf("open settings", "recognition NONE"), log)
        assertEquals(listOf(ListeningTexts.SETTINGS_BLOCKED), sessions.opened.single().said)
        assertEquals("settings-blocked", timeline.reacted?.kind)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aServiceThatCannotEnterTheForegroundWithoutTheMicrophonePermissionAsksForIt() = runTest {
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace

```kotlin
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsUnavailable))
    }
```

with

```kotlin
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsUnavailable))
    }

    @Test
    fun kestrelSaysThatAndroidKeptItsSettingsBack() {
        val step = Rules.next(listening, Event.SettingsBlocked)

        assertEquals(listOf(Effect.Say(Reply.SettingsBlocked)), step.effects)
        assertEquals(1, step.state.speeches)
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsBlocked))
    }
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.orchestrator.RulesTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference 'SettingsOpening'`, `'SettingsBlocked'` and `'SETTINGS_BLOCKED'`.

- [ ] **Step 2: Let Listening wait for the settings off its thread and say why they did not come**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
/** What Kestrel says and shows while it listens, and why it stopped (design spec, sections 8.9 and 14.6). */
internal object ListeningTexts {
```

with

```kotlin
/** How "Kestrel, open settings" ended (design spec, sections 8.6 and 14). */
internal enum class SettingsOpening {
    /** Kestrel's settings came to the front, or were in front already. */
    OPENED,

    /** Kestrel's accessibility service is off and Kestrel does not hold the assistant role, so Kestrel did not try. */
    NEED_SERVICE,

    /** Android did not bring the settings to the front within 2 s; a notification that opens them was posted. */
    BLOCKED,
}

/** What Kestrel says and shows while it listens, and why it stopped (design spec, sections 8.9 and 14.6). */
internal object ListeningTexts {
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    const val SETTINGS_NEED_SERVICE = "I can open my settings only while my accessibility service is on or I am your " +
        "assistant app. Tap my listening notification, or open Kestrel from the launcher."
}
```

with

```kotlin
    const val SETTINGS_NEED_SERVICE = "I can open my settings only while my accessibility service is on or I am your " +
        "assistant app. Tap my listening notification, or open Kestrel from the launcher."
    const val SETTINGS_BLOCKED = "Android did not let me open my settings. Tap my notification, or open Kestrel from " +
        "the launcher."
}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
 * [openSettings] opens Kestrel's settings app and returns true, or returns false while Kestrel's accessibility service
 * is off and Kestrel does not hold the assistant role, because Android then blocks the start without an exception and
 * Kestrel says why instead; the controller's thread calls it, and it throws when Android refuses.
```

with

```kotlin
 * [openSettings] opens Kestrel's settings app and says how it went ([SettingsOpening]); it waits until the settings are
 * in front, so a coroutine of [scope] calls it, and it throws when Android refuses the start.
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    private val openSettings: () -> Boolean,
```

with

```kotlin
    private val openSettings: suspend () -> SettingsOpening,
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    // Section 14: Kestrel never fails silently. Android blocks an activity start from the background without an
    // exception unless Kestrel's accessibility service is on (prototype P1) or Kestrel holds the assistant role; without
    // the service the bar does not exist either, so Kestrel says why.
    private fun openSettingsOrSayWhy() {
        val opened = try {
            openSettings()
        } catch (e: Exception) {
            report("Opening the settings", e)
            bar.show(ListeningTexts.CANNOT_OPEN_SETTINGS)
            return
        }
        if (!opened) send(Event.SettingsUnavailable)
    }
```

with

```kotlin
    // Section 14: Kestrel never fails silently. Android blocks an activity start from the background without an
    // exception unless Kestrel's accessibility service is on (prototype P1) or Kestrel holds the assistant role, and with
    // only the role Android 13 may block it too (A71 session of sub-project 2, item 5); without the service the bar does
    // not exist either, so Kestrel says why. openSettings waits up to 2 s for the settings, so it runs off the
    // controller's thread.
    private fun openSettingsOrSayWhy() {
        scope.launch {
            val opened = try {
                openSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Opening the settings", e)
                bar.show(ListeningTexts.CANNOT_OPEN_SETTINGS)
                return@launch
            }
            when (opened) {
                SettingsOpening.OPENED -> Unit
                SettingsOpening.NEED_SERVICE -> send(Event.SettingsUnavailable)
                SettingsOpening.BLOCKED -> {
                    // The A71 session and the device test read Kestrel's verdict on the phone.
                    timeline.reacted("settings-blocked")
                    send(Event.SettingsBlocked)
                }
            }
        }
    }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
            Reply.SettingsNeedService -> ListeningTexts.SETTINGS_NEED_SERVICE
```

with

```kotlin
            Reply.SettingsNeedService -> ListeningTexts.SETTINGS_NEED_SERVICE
            Reply.SettingsBlocked -> ListeningTexts.SETTINGS_BLOCKED
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    data object SettingsUnavailable : Event

```

with

```kotlin
    data object SettingsUnavailable : Event

    /**
     * Android did not bring Kestrel's settings to the front, although Kestrel's accessibility service is on or Kestrel
     * holds the assistant role, as Android 13 may do with only the role. Kestrel posted a notification that opens them,
     * and says so (section 14).
     */
    data object SettingsBlocked : Event

```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    data object SettingsNeedService : Reply

```

with

```kotlin
    data object SettingsNeedService : Reply

    /** Android kept Kestrel's settings back, and a notification opens them (section 14). */
    data object SettingsBlocked : Reply

```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
```

with

```kotlin
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
        // Not over a ringing call: the verdict comes up to 2 s after the request.
        Event.SettingsBlocked -> if (state.mode == Mode.OFF || state.inCall) Step(state) else say(state, Reply.SettingsBlocked)
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.orchestrator.RulesTest`
Expected: `BUILD FAILED` in `:app:compileDebugKotlin`, because `AppGraph` still passes `openSettings` as `() -> Boolean`; the next step changes it.

- [ ] **Step 3: Open the settings through the assistant's session, wait for them, and offer them in a notification**

In `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, replace

```kotlin
import android.content.Context
import android.content.Intent
```

with

```kotlin
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
```

In `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, replace

```kotlin
import dev.kestrel.app.KestrelApplication

/**
 * Makes Kestrel eligible for Android's assistant role (design spec, section 8.6; research r6, prototype P3). Android
 * binds it while Kestrel holds the role, also after a restart, so it runs the restore of the listening mode (section
 * 8.5). The assistant gesture opens [KestrelSessionService].
 */
class KestrelAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
```

with

```kotlin
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity

/**
 * Kestrel's settings app with the launcher's own intent, so that a plain start brings Kestrel's task to the front instead
 * of a second MainActivity (design spec, sections 8.6 and 15). Started by the assistant's session, the settings open as
 * the assistant's activity, in a task of their own, beside a settings screen that the launcher opened, because Android
 * reuses only a task of the same activity type.
 */
internal fun settingsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    .setAction(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_LAUNCHER)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Makes Kestrel eligible for Android's assistant role (design spec, section 8.6; research r6, prototype P3). Android
 * binds it while Kestrel holds the role, also after a restart, so it runs the restore of the listening mode (section
 * 8.5). The assistant gesture opens [KestrelSessionService], and so does [openSettings].
 */
class KestrelAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        ready = this
```

In `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, replace

```kotlin
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}
```

with

```kotlin
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    override fun onShutdown() {
        if (ready === this) ready = null
        super.onShutdown()
    }

    companion object {
        /** The argument of a session that opens Kestrel's settings instead of starting to listen. */
        internal const val EXTRA_OPEN_SETTINGS = "dev.kestrel.app.extra.OPEN_SETTINGS"

        // The service that Android has bound for the role, from onReady until onShutdown, which clears it, so it cannot
        // keep a service that Android has let go.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var ready: KestrelAssistantService? = null

        /**
         * "Kestrel, open settings" with only the assistant role (design spec, section 8.6): asks Android for a session of
         * Kestrel's assistant, which starts the settings, and returns whether a service was there to ask. Android lets
         * the assistant's shown session start an activity also over another app, while Android 13 most likely blocks a
         * plain start from the background then (A71 session of sub-project 2, item 5). Any thread may call it; it throws when Android
         * refuses the session.
         */
        internal fun openSettings(): Boolean {
            val service = ready ?: return false
            service.showSession(Bundle().apply { putBoolean(EXTRA_OPEN_SETTINGS, true) }, 0)
            return true
        }
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, replace

```kotlin
/**
 * The assistant gesture, such as a long press of Home or the side key (prototype P3): Kestrel listens and waits for a
 * task. The session's window would cover the screen and take every touch and key, so the session hides it at once.
 */
private class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val graph = (context.applicationContext as KestrelApplication).graph
        graph.timeline.start("assistant")
```

with

```kotlin
/**
 * The assistant gesture, such as a long press of Home or the side key (prototype P3): Kestrel listens and waits for a
 * task. A session that [KestrelAssistantService.openSettings] asked for starts Kestrel's settings instead. The session's
 * window would cover the screen and take every touch and key, so the session hides it at once.
 */
private class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val graph = (context.applicationContext as KestrelApplication).graph
        if (args?.getBoolean(KestrelAssistantService.EXTRA_OPEN_SETTINGS) == true) {
            // A shown session may start the assistant's activity, also over another app. AppGraph waits for the settings
            // and says so when they do not come.
            try {
                startAssistantActivity(settingsIntent(context))
            } catch (e: RuntimeException) {
                graph.report("Opening the settings from the assistant's session", e)
            }
            hide()
            return
        }
        graph.timeline.start("assistant")
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt`, replace

```kotlin
    /** Kestrel's accessibility service connected, so the notification of [accessibilityServiceOff] goes. */
    fun accessibilityServiceOn() = notifications.cancel(ACCESSIBILITY_ID)
```

with

```kotlin
    /** Kestrel's accessibility service connected, so the notification of [accessibilityServiceOff] goes. */
    fun accessibilityServiceOn() = notifications.cancel(ACCESSIBILITY_ID)

    /**
     * Android did not bring Kestrel's settings to the front after "Kestrel, open settings" (design spec, section 14): a
     * tap on this notification opens them, because Android lets the tap on a notification start an activity.
     */
    fun offerSettings() {
        if (!allowed()) return
        notifications.createNotificationChannel(
            NotificationChannel(PROBLEMS, context.getString(R.string.problems_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = context.getString(R.string.problem_settings_text)
        val notification = Notification.Builder(context, PROBLEMS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.problem_settings_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(ListeningService.openKestrel(context))
            .setAutoCancel(true)
            .build()
        notifications.notify(SETTINGS_ID, notification)
    }

    /** Kestrel's settings came to the front, so the notification of [offerSettings] goes. */
    fun settingsOpened() = notifications.cancel(SETTINGS_ID)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt`, replace

```kotlin
        internal const val ACCESSIBILITY_ID = 7_103
```

with

```kotlin
        internal const val ACCESSIBILITY_ID = 7_103

        /** The notification that opens Kestrel's settings after Android kept them back. */
        internal const val SETTINGS_ID = 7_104
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="problem_service_off_text">Kestrel cannot use the screen, the accessibility button or the shortcut until you turn the service on again. If Kestrel is listening, it goes on listening. Tap to open the accessibility settings.</string>
```

with

```xml
    <string name="problem_service_off_text">Kestrel cannot use the screen, the accessibility button or the shortcut until you turn the service on again. If Kestrel is listening, it goes on listening. Tap to open the accessibility settings.</string>
    <string name="problem_settings_title">Open Kestrel\'s settings</string>
    <string name="problem_settings_text">Android did not let Kestrel open its settings from the background. Tap here to open them.</string>
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace

```kotlin
        graph.timeline.settingsResumed()
        refresh()
```

with

```kotlin
        graph.timeline.settingsResumed()
        graph.problems.settingsOpened()
        refresh()
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.commands.CommandEnvironment
```

with

```kotlin
import dev.kestrel.app.assist.KestrelAssistantService
import dev.kestrel.app.assist.settingsIntent
import dev.kestrel.app.commands.CommandEnvironment
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.SessionListener
```

with

```kotlin
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.SettingsOpener
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
    private val store = PrivacyStore(context)
```

with

```kotlin
    /** Logs a failure that Kestrel handled, as [reportFailure] does, for the parts that AppGraph does not build. */
    internal fun report(what: String, error: Throwable) = reportFailure(what, error)

    private val store = PrivacyStore(context)
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
            // The launcher's own intent, so that Kestrel's task comes to the front instead of a second MainActivity.
            // The controller's thread calls it; startActivity may be called from any thread. Without Kestrel's
            // accessibility service, Android blocks the start from the background without an exception (prototype P1),
            // so Kestrel does not try, and says why instead. Android lets the holder of the assistant role start
            // activities from the background too, because it binds the role's VoiceInteractionService (section 8.6).
            openSettings = {
                if (screen.value == null &&
                    !appContext.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    false
                } else {
                    appContext.startActivity(
                        Intent(appContext, MainActivity::class.java)
                            .setAction(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }
            },
```

with

```kotlin
            openSettings = { settingsOpener.open() },
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
    internal fun attachScreen(session: ScreenSession) {
```

with

```kotlin
    // Built at the first "Kestrel, open settings", after every part of the graph that it uses.
    private val settingsOpener by lazy {
        SettingsOpener(
            timeline,
            serviceOn = { screen.value != null },
            roleHeld = { appContext.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) },
            startActivity = { appContext.startActivity(settingsIntent(appContext)) },
            showSession = { KestrelAssistantService.openSettings() },
            offer = { problems.offerSettings() },
            report = reportFailure,
        )
    }

    internal fun attachScreen(session: ScreenSession) {
```

Create `app/src/main/kotlin/dev/kestrel/app/listen/SettingsOpener.kt`:

```kotlin
package dev.kestrel.app.listen

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Kestrel, open settings" (design spec, sections 8.6 and 14): opens Kestrel's settings app and waits up to [waitMillis]
 * until it is in front. With Kestrel's accessibility service on ([serviceOn]), Android lets Kestrel start it from the
 * background (prototype P1), and [startActivity] does. With only the assistant role ([roleHeld]), Android 13 most likely
 * blocks such a start while another app is in front (A71 session of sub-project 2, item 5), so [showSession] asks
 * Kestrel's assistant service for a session, which starts the settings as the assistant's activity; it returns whether a
 * service was there to ask. When the settings do not come to the front, [offer] posts a notification that opens them,
 * and [open] says BLOCKED; without the service and the role it does not try. [report] gets the failures that it handles;
 * a failure of [startActivity] goes to the caller. A coroutine of Listening's scope calls [open].
 */
internal class SettingsOpener(
    private val timeline: Timeline,
    private val serviceOn: () -> Boolean,
    private val roleHeld: () -> Boolean,
    private val startActivity: () -> Unit,
    private val showSession: () -> Boolean,
    private val offer: () -> Unit,
    private val report: (String, Throwable) -> Unit,
    private val waitMillis: Long = 2_000L,
) {
    suspend fun open(): SettingsOpening {
        if (timeline.settingsInFront) return SettingsOpening.OPENED
        val service = serviceOn()
        if (!service && !roleHeld()) return SettingsOpening.NEED_SERVICE
        val before = timeline.settingsResumes.value
        timeline.settingsAsked()
        val asked = if (service) {
            startActivity()
            true
        } else {
            try {
                showSession()
            } catch (e: RuntimeException) {
                report("Opening the settings through the assistant", e)
                false
            }
        }
        val resumed = asked && withTimeoutOrNull(waitMillis) { timeline.settingsResumes.first { it > before } } != null
        if (resumed) return SettingsOpening.OPENED
        try {
            offer()
        } catch (e: RuntimeException) {
            report("Posting the notification of the settings", e)
        }
        return SettingsOpening.BLOCKED
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/listen/SettingsOpenerTest.kt`:

```kotlin
package dev.kestrel.app.listen

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsOpenerTest {
    private val log = mutableListOf<String>()
    private var service = false
    private var role = false
    private var sessionReady = true

    private fun TestScope.timeline() = Timeline { testScheduler.currentTime }

    private fun opener(timeline: Timeline) = SettingsOpener(
        timeline,
        serviceOn = { service },
        roleHeld = { role },
        startActivity = { log += "start" },
        showSession = {
            log += "session"
            sessionReady
        },
        offer = { log += "offer" },
        report = { what, _ -> log += "report $what" },
    )

    @Test
    fun settingsInFrontCountAsOpenedAndNothingStarts() = runTest {
        val timeline = timeline()
        timeline.settingsResumed()

        assertEquals(SettingsOpening.OPENED, opener(timeline).open())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun withoutTheServiceAndTheRoleKestrelDoesNotTry() = runTest {
        assertEquals(SettingsOpening.NEED_SERVICE, opener(timeline()).open())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun withOnlyTheRoleSettingsThatComeWithinTwoSecondsAreOpened() = runTest {
        role = true
        val timeline = timeline()
        val opening = async { opener(timeline).open() }
        runCurrent()
        advanceTimeBy(1_500)
        timeline.settingsResumed()
        runCurrent()

        assertEquals(SettingsOpening.OPENED, opening.await())
        assertEquals(listOf("session"), log)
    }

    @Test
    fun withOnlyTheRoleSettingsThatDoNotComeAreOfferedAfterTwoSeconds() = runTest {
        role = true
        val opening = async { opener(timeline()).open() }
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(opening.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(SettingsOpening.BLOCKED, opening.await())
        assertEquals(listOf("session", "offer"), log)
    }

    @Test
    fun withoutAReadyAssistantServiceTheSettingsAreOfferedAtOnce() = runTest {
        role = true
        sessionReady = false

        assertEquals(SettingsOpening.BLOCKED, opener(timeline()).open())
        assertEquals(listOf("session", "offer"), log)
        assertEquals(0L, testScheduler.currentTime)
    }
}
```

- [ ] **Step 4: Run the JVM tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:app` has A₀ + 13 tests (`ListeningTest` L₀ + 3, `RulesTest` R₀ + 1, `SettingsOpenerTest` 5), all passing.

- [ ] **Step 5: Let the device test of the role check the settings through the session**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt`, replace

```kotlin
                listening.send(Event.Heard("Kestrel, open settings", named = true))

                // Without the role and the accessibility service, Android would block the start without an exception.
                assertTrue(
                    "Kestrel's settings did not come to the front",
                    VoiceTests.waitUntil(10_000) {
                        SETTINGS_IN_FRONT.containsMatchIn(VoiceTests.shell("dumpsys activity activities"))
                    },
                )
```

with

```kotlin
                val resumes = VoiceTests.graph.timeline.settingsResumes.value
                listening.send(Event.Heard("Kestrel, open settings", named = true))

                // Without the role and the accessibility service, Android would block the start without an exception.
                // With only the role, Kestrel starts the settings through its assistant's session (A71 session of
                // sub-project 2, item 5), and MainActivity's own resume tells Kestrel and the A71 session that they came.
                assertTrue(
                    "Kestrel's settings did not come to the front",
                    VoiceTests.waitUntil(10_000) {
                        VoiceTests.graph.timeline.settingsResumes.value > resumes &&
                            SETTINGS_IN_FRONT.containsMatchIn(VoiceTests.shell("dumpsys activity activities"))
                    },
                )
                // Kestrel gives up after 2 s and says so; settings that came later must not hide that verdict.
                assertTrue(
                    "Kestrel said that Android kept its settings back",
                    VoiceTests.graph.timeline.reacted?.kind != "settings-blocked",
                )
```

Android 13 prints the activity in front as `ResumedActivity: ActivityRecord{…}` and later versions as `topResumedActivity=ActivityRecord{…}`, so the checks of the activity in front would never fail on the A71 (analysis, item 5).

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt`, replace

```kotlin
        val KESTREL_IN_FRONT = Regex("""topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/""")
        val SETTINGS_IN_FRONT = Regex("""topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity""")
```

with

```kotlin
        // Android 13 prints "  ResumedActivity: ActivityRecord{…}", later versions "topResumedActivity=ActivityRecord{…}".
        val KESTREL_IN_FRONT = Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/""")
        val SETTINGS_IN_FRONT =
            Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity""")
```

- [ ] **Step 6: Run the voice device tests and listen-check on the emulator**

Take the emulator lock first, and delete it after the runs, also when they fail.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: `Voice tests: D₀ + 2 run, 0 failed, 0 errors, 0 skipped.`, with `AssistTest#withTheAssistantRoleOpenSettingsBringsTheSettingsToTheFront` among the passed tests. If that test fails, stop and report to the controller with the output of `dumpsys activity activities` and `logcat -d -s ActivityTaskManager:W VoiceInteractionManager:W VoiceInteractionServiceManager:W Kestrel:W` (the lines about dev.kestrel only): the start through the assistant's session is the plan's design, and a failure on API 37 changes it.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -File scripts\listen-check.ps1 -Serial emulator-5554`
Expected: the line `settings     ok: ...` (the start with the accessibility service, whose start did not change), every other check `ok`, and exit code 0.

- [ ] **Step 7: Say it in the documents**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
with the role alone, without the accessibility service; the A71 session checks them on the A71 together with "Kestrel, open settings".
```

with

```markdown
with the role alone, without the accessibility service; the A71 session checks them on the A71 together with "Kestrel, open settings". With only the role, Android 13 most likely blocks a plain start of an activity from the background while another app is in front (A71 session of sub-project 2), so for "Kestrel, open settings" Kestrel's assistant service shows a session, which starts the settings as the assistant's activity (`VoiceInteractionSession.startAssistantActivity`) and hides itself; the settings then open in a task of their own, beside a settings screen that the launcher opened. Kestrel waits up to 2 s for its settings to come to the front, with either start; when they do not, it says that Android did not let it open them and posts a notification that opens them with a tap.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| "Kestrel, open settings" while Kestrel's accessibility service is off and Kestrel does not hold the assistant role | Android does not let Kestrel open an activity from the background then, so Kestrel says so and asks the owner to tap its listening notification or to open Kestrel from the launcher |
```

with

```markdown
| "Kestrel, open settings" while Kestrel's accessibility service is off and Kestrel does not hold the assistant role | Android does not let Kestrel open an activity from the background then, so Kestrel says so and asks the owner to tap its listening notification or to open Kestrel from the launcher |
| "Kestrel, open settings", and the settings do not come to the front within 2 s | Kestrel says that Android did not let it open them, and asks the owner to tap its notification, which opens them, or to open Kestrel from the launcher |
```

In `README.md`, replace

```markdown
"Kestrel, open settings" opens this app; without either, Android does not let Kestrel open it from the background, so Kestrel says so; a tap on its listening notification, or on Kestrel in the launcher, opens the app.
```

with

```markdown
"Kestrel, open settings" opens this app; without either, Android does not let Kestrel open it from the background, so Kestrel says so; a tap on its listening notification, or on Kestrel in the launcher, opens the app. When Android does not bring the app to the front within 2 s, as Android 13 may do while Kestrel is only the default digital assistant, Kestrel says so too, and posts a notification that opens the app with a tap; Kestrel in the launcher opens it too.
```

- [ ] **Step 8: Check the build and stop Gradle**

Run: `scripts\gradle.ps1 lint kestrelAudit; scripts\gradle.ps1 --stop`
Expected: `BUILD SUCCESSFUL`, no lint issues.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/SettingsOpener.kt app/src/test/kotlin/dev/kestrel/app/listen/SettingsOpenerTest.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "fix(assist): open the settings through the assistant's session with only the role, wait for them, and say why and offer a notification when they do not come" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 5: The command helper's end marker, one line for each check, and the failures' classes

Analysis items 3, 4 and 5. The command helper `Invoke-SettingsCommand` and the `Invoke-Once` of `listen-check.ps1` need their end marker alone on the last line, but One UI's `dumpsys accessibility` most likely ends without a line break, so the marker landed at the end of the dump's last line, and listen-check stopped at its shortcut check. The device now prints a line break before the marker (`&& echo && echo kestrel-end`), and the computer drops the marker and the one empty line that the extra line break leaves after output that ends with one. listen-check turns an error of one check into that check's line, so that the later checks still run and print their lines. Its settings check reads Kestrel's own times on the device through `voice-times` (Task 3) instead of `dumpsys activity activities`, whose `topResumedActivity` line Android 13 may not print. The failures' reader keeps the short name of each failure's class, which is on the same line of the log.

**Files:**
- Modify: `scripts/lib/Kestrel.ps1` (`Invoke-SettingsCommand`)
- Modify: `scripts/tests/fake-adb.ps1`, `scripts/tests/fake-adb-files.ps1`, `scripts/tests/Kestrel.Tests.ps1`, `scripts/tests/Models.Tests.ps1`
- Modify: `scripts/listen-check.ps1` (header, `Invoke-Once`, `Invoke-Check`, `Invoke-SettingsCheck`, the reboot check's time on the phone's clock with `Wait-Reboot` and `Get-RestoreTime`, the run of the checks)
- Modify: `scripts/lib/Session.ps1` (`Get-FailureDescriptions`), `scripts/tests/Session.Tests.ps1`

**Interfaces:**
- Consumes: the hook `voice-times` and `ConvertFrom-HookPairs` from Task 3.
- Produces: every device command of `Invoke-SettingsCommand` ends with ` && echo && echo kestrel-end`; `Get-FailureDescriptions` returns `What`, `Class` and `Count` for each pair of a description and a class. Task 9's `a71-session.ps1` uses the same marker in its own `Invoke-Once`, and the failures' classes.

- [ ] **Step 1: Write the failing Pester tests**

In `scripts/tests/Kestrel.Tests.ps1`, replace

```powershell
            (Get-FakeDevice).calls | Should -BeExactly (@('settings get secure x && echo kestrel-end') * 3)
```

with

```powershell
            (Get-FakeDevice).calls | Should -BeExactly (@('settings get secure x && echo && echo kestrel-end') * 3)
```

In `scripts/tests/Kestrel.Tests.ps1`, replace

```powershell
        It 'returns the value when only the first 2 tries fail' {
            Set-FakeDevice -Settings @{ x = 'value' } -Failures 2
            Get-SecureSetting -Serial emulator-5554 -Name x | Should -BeExactly 'value'
            (Get-FakeDevice).calls | Should -HaveCount 3
        }
```

with

```powershell
        It 'returns the value when only the first 2 tries fail' {
            Set-FakeDevice -Settings @{ x = 'value' } -Failures 2
            Get-SecureSetting -Serial emulator-5554 -Name x | Should -BeExactly 'value'
            (Get-FakeDevice).calls | Should -HaveCount 3
        }

        It 'keeps the last line of an answer that ends without a line break, as One UI''s dumpsys accessibility does' {
            Invoke-SettingsCommand -Serial emulator-5554 -Command 'printf %s last-line' | Should -BeExactly @('last-line')
            (Get-FakeDevice).calls | Should -BeExactly @('printf %s last-line && echo && echo kestrel-end')
        }
```

In `scripts/tests/Kestrel.Tests.ps1`, replace

```powershell
            $device.refused | Should -BeExactly @('settings delete secure x || true && echo kestrel-end')
```

with

```powershell
            $device.refused | Should -BeExactly @('settings delete secure x || true && echo && echo kestrel-end')
```

In `scripts/tests/Kestrel.Tests.ps1`, replace

```powershell
            (Get-FakeDevice).calls | Should -BeExactly (@('settings list secure && echo kestrel-end') * 3)
```

with

```powershell
            (Get-FakeDevice).calls | Should -BeExactly (@('settings list secure && echo && echo kestrel-end') * 3)
```

In `scripts/tests/Models.Tests.ps1`, replace

```powershell
        (Get-FakeDevice).calls | Should -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo kestrel-end'
```

with

```powershell
        (Get-FakeDevice).calls | Should -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo && echo kestrel-end'
```

In `scripts/tests/Models.Tests.ps1`, replace

```powershell
        $device.calls | Should -Not -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo kestrel-end'
```

with

```powershell
        $device.calls | Should -Not -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo && echo kestrel-end'
```

In `scripts/tests/Session.Tests.ps1`, replace

```powershell
        $failures[1].What | Should -BeExactly 'The microphone'
        $failures[1].Count | Should -Be 2
    }
```

with

```powershell
        $failures[1].What | Should -BeExactly 'The microphone'
        $failures[1].Count | Should -Be 2
    }

    It 'keeps the class of each logged failure, as listen-check''s refused start shows it' {
        $log = @(
            '11-17 13:40:31.112  4321  4321 W Kestrel : Starting the listening service failed: android.app.ForegroundServiceStartNotAllowedException'
            '11-17 13:40:31.113  4321  4321 W Kestrel :     at android.app.Service.startForeground(Service.java:775)'
            '11-17 13:41:02.000  4321  4400 W Kestrel : Starting the listening service failed: java.lang.SecurityException'
        )

        $failures = @(Get-FailureDescriptions -Log $log)

        $failures.Count | Should -Be 2
        $failures[0].What | Should -BeExactly 'Starting the listening service'
        $failures[0].Class | Should -BeExactly 'ForegroundServiceStartNotAllowedException'
        $failures[1].Class | Should -BeExactly 'SecurityException'
        $failures[1].Count | Should -Be 1
    }
```

Run: `pwsh -NoProfile -Command "Import-Module Pester -RequiredVersion 6.2.0; Invoke-Pester scripts\tests\Kestrel.Tests.ps1, scripts\tests\Models.Tests.ps1, scripts\tests\Session.Tests.ps1"`
Expected: the changed and new tests fail, except the changed `Should -Not -Contain` check, which passes before and after the change: the calls still end with `&& echo kestrel-end`, the fake adb does not know `printf %s`, and the failures have no `Class`.

- [ ] **Step 2: Put the marker on a line of its own**

In `scripts/lib/Kestrel.ps1`, replace

```powershell
# Runs a settings command on a device and returns the lines that it printed. The device prints a marker only after a
# command that worked, which tells an empty answer from a failed command. A busy device, or one whose system is still
# starting, sometimes fails the command, so it is repeated before it fails for good with the device's last complaint.
# The command runs only on the device that Assert-TestDevice accepted for the serial, and throws at once on another one.
function Invoke-SettingsCommand([string]$Serial, [string]$Command) {
    $adb = Get-Adb
    $guarded = Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo kestrel-end"
```

with

```powershell
# Runs a settings command on a device and returns the lines that it printed. The device prints a marker only after a
# command that worked, which tells an empty answer from a failed command. It prints a line break before the marker, so
# that the marker has a line of its own also after an answer that ends without one, as One UI's 'dumpsys accessibility'
# does (A71 session of sub-project 2, item 3); after an answer that ends with a line break, that line break leaves an
# empty line, which is dropped. A busy device, or one whose system is still starting, sometimes fails the command, so it
# is repeated before it fails for good with the device's last complaint. The command runs only on the device that
# Assert-TestDevice accepted for the serial, and throws at once on another one.
function Invoke-SettingsCommand([string]$Serial, [string]$Command) {
    $adb = Get-Adb
    $guarded = Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo && echo kestrel-end"
```

In `scripts/lib/Kestrel.ps1`, replace

```powershell
        if ($lines.Count -gt 0 -and $lines[-1].Trim() -eq 'kestrel-end') { return $lines | Select-Object -First ($lines.Count - 1) }
```

with

```powershell
        if ($lines.Count -gt 0 -and $lines[-1].Trim() -eq 'kestrel-end') {
            $count = $lines.Count - 1
            if ($count -gt 0 -and $lines[$count - 1] -eq '') { $count-- }
            return $lines | Select-Object -First $count
        }
```

In `scripts/tests/fake-adb.ps1`, replace

```powershell
# The helpers end every command with this marker; the commands in their warnings, which the owner pastes, have none.
$marker = ' && echo kestrel-end'
$withMarker = $command.EndsWith($marker)
$deviceCommand = if ($withMarker) { $command.Substring(0, $command.Length - $marker.Length) } else { $command }
$lines = @()
if ($deviceCommand -match '^settings get secure (\S+)$') {
```

with

```powershell
# The helpers end every command with this marker; the commands in their warnings, which the owner pastes, have none.
$marker = ' && echo && echo kestrel-end'
$withMarker = $command.EndsWith($marker)
$deviceCommand = if ($withMarker) { $command.Substring(0, $command.Length - $marker.Length) } else { $command }
$lines = @()
# 'printf %s <text>' prints the text without a final line break, as One UI's 'dumpsys accessibility' ends its answer.
$partial = $null
if ($deviceCommand -match '^printf %s (\S+)$') {
    $partial = $Matches[1]
} elseif ($deviceCommand -match '^settings get secure (\S+)$') {
```

In `scripts/tests/fake-adb.ps1`, replace

```powershell
foreach ($line in $lines) { [Console]::Out.WriteLine($line) }
if ($withMarker) { [Console]::Out.WriteLine('kestrel-end') }
exit 0
```

with

```powershell
foreach ($line in $lines) { [Console]::Out.WriteLine($line) }
if ($null -ne $partial) { [Console]::Out.Write($partial) }
# As on the device, the first echo ends a last line that has no line break, or else prints an empty line.
if ($withMarker) {
    [Console]::Out.WriteLine('')
    [Console]::Out.WriteLine('kestrel-end')
}
exit 0
```

In `scripts/tests/fake-adb-files.ps1`, replace

```powershell
# followed by ' && echo kestrel-end' and inside the check of Format-TestDeviceCommand, for which it is kestrel-api37:
```

with

```powershell
# followed by ' && echo && echo kestrel-end' and inside the check of Format-TestDeviceCommand, for which it is kestrel-api37:
```

In `scripts/tests/fake-adb-files.ps1`, replace

```powershell
$marker = ' && echo kestrel-end'
```

with

```powershell
$marker = ' && echo && echo kestrel-end'
```

In `scripts/tests/fake-adb-files.ps1`, replace

```powershell
if ($withMarker) { 'kestrel-end' }
exit 0
```

with

```powershell
# As on the device, the first echo prints an empty line after an answer that ends with a line break.
if ($withMarker) {
    ''
    'kestrel-end'
}
exit 0
```

- [ ] **Step 3: Keep the class of each failure**

In `scripts/lib/Session.ps1`, replace

```powershell
# Kestrel's logged failures from 'logcat -s Kestrel:W': each fixed description of what failed, with how often it
# appeared, in the order of the descriptions. Kestrel logs '<what> failed: <classes and frames>' and never a message
# (design spec, section 13.5); the lines of the frames and causes after it do not count.
function Get-FailureDescriptions([string[]]$Log) {
    $Log |
        ForEach-Object { if ($_ -match '\bKestrel\s*:\s*(.+?) failed\b') { $Matches[1] } } |
        Group-Object |
        Sort-Object Name |
        ForEach-Object { [pscustomobject]@{ What = $_.Name; Count = $_.Count } }
}
```

with

```powershell
# Kestrel's logged failures from 'logcat -s Kestrel:W': each fixed description of what failed with the short name of its
# error's class, and how often the two appeared together, in the order of the descriptions and classes. Kestrel logs
# '<what> failed: <class>' with the frames on the next lines, and never a message (design spec, section 13.5); the class
# is on the same line, and the lines of the frames and causes after it do not count.
function Get-FailureDescriptions([string[]]$Log) {
    $Log |
        ForEach-Object {
            if ($_ -match '\bKestrel\s*:\s*(.+?) failed:\s*([\w.$]+)') {
                [pscustomobject]@{ What = $Matches[1]; Class = ($Matches[2] -split '\.')[-1] }
            }
        } |
        Group-Object What, Class |
        Sort-Object Name |
        ForEach-Object { [pscustomobject]@{ What = $_.Group[0].What; Class = $_.Group[0].Class; Count = $_.Count } }
}
```

- [ ] **Step 4: Run the scripts' tests in both shells**

Run: `scripts\test-scripts.ps1`
Expected: Pester passes P₀ + 6 tests in Windows PowerShell 5.1 and in PowerShell 7, and PSScriptAnalyzer reports no problem.

- [ ] **Step 5: Let listen-check run every check and time the settings on the device**

In `scripts/listen-check.ps1`, replace

```powershell
#   settings  with the accessibility service on, 'Kestrel, open settings' (a transcript that the hook sends) brings
#             Kestrel's settings app to the front
```

with

```powershell
#   settings  with the accessibility service on, 'Kestrel, open settings' (a transcript that the hook sends) brings
#             Kestrel's settings app to the front, which the check reads from Kestrel's own times on the device (the
#             hook voice-times), because Android 13 may not print the activity in front as later versions do
```

In `scripts/listen-check.ps1`, replace

```powershell
# with 'scripts\hook.ps1 voice-state', which never contains what Kestrel heard, and prints one line for each check that
# ran, also when a later check fails with an error.
```

with

```powershell
# with 'scripts\hook.ps1 voice-state', which never contains what Kestrel heard, and prints one line for each check that
# ran: an error of a check becomes that check's line, 'failed: <message>', and the later checks still run.
```

In `scripts/listen-check.ps1`, replace

```powershell
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
```

with

```powershell
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
Use-KestrelToolchain
```

In `scripts/listen-check.ps1`, replace

```powershell
    $output = @(& $adb -s $Serial shell (Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo kestrel-end") 2>&1)
```

with

```powershell
    # The first echo gives the marker a line of its own, also after output without a final line break (Invoke-SettingsCommand).
    $output = @(& $adb -s $Serial shell (Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo && echo kestrel-end") 2>&1)
```

In `scripts/listen-check.ps1`, replace

```powershell
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }
```

with

```powershell
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

# Runs one check and returns its line. An error of the check becomes its line, 'failed: <message>', so that one failure
# does not hide the checks after it (A71 session of sub-project 2, item 3); the messages name only commands, files and
# serials. A stop such as Ctrl+C still ends the run.
function Invoke-Check([scriptblock]$Check) {
    try {
        & $Check
    } catch {
        "failed: $($_.Exception.Message)"
    }
}
```

In `scripts/listen-check.ps1`, replace

```powershell
    Invoke-Hook voice -Send heard -Text 'Kestrel, open settings.' -Named | Out-Null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 10) {
        $activities = (Invoke-Checked 'dumpsys activity activities') -join "`n"
        if ($activities -match 'topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity ') {
            $opened = [math]::Round($watch.Elapsed.TotalSeconds, 1)
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            return "ok: Kestrel's settings came to the front $opened s after 'Kestrel, open settings'"
        }
        Start-Sleep -Milliseconds 500
    }
    "failed: Kestrel's settings did not come to the front within 10 s of 'Kestrel, open settings'"
```

with

```powershell
    $before = [long](ConvertFrom-HookPairs (Invoke-Hook voice-times))['settingsAsked']
    Invoke-Hook voice -Send heard -Text 'Kestrel, open settings.' -Named | Out-Null
    # Kestrel's own times on the device: when it asked Android for its settings, and when MainActivity resumed.
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 10) {
        $times = ConvertFrom-HookPairs (Invoke-Hook voice-times)
        $asked = [long]$times['settingsAsked']
        $resumed = [long]$times['settingsResumed']
        # Kestrel gives up after 2 s, says that Android blocked its settings, and records that as its reaction; settings
        # that came later must not count as a pass (plan 2E, Task 4).
        $reaction = ConvertFrom-TimeMark -Text $times['reacted']
        $blocked = $asked -gt $before -and $reaction -and $reaction.Kind -eq 'settings-blocked' -and $reaction.At -ge $asked
        if ($asked -gt $before -and $resumed -ge $asked) {
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            if ($blocked) { return "failed: Kestrel's settings came to the front $($resumed - $asked) ms after Kestrel asked for them, although Kestrel said that Android blocked them" }
            return "ok: Kestrel's settings came to the front $($resumed - $asked) ms after Kestrel asked for them, timed on the device"
        }
        Start-Sleep -Milliseconds 500
    }
    if ($blocked) { return "failed: Kestrel's settings did not come to the front within 10 s of 'Kestrel, open settings', and Kestrel said that Android blocked them" }
    "failed: Kestrel's settings did not come to the front within 10 s of 'Kestrel, open settings'"
```

In `scripts/listen-check.ps1`, replace

```powershell
    # The accessibility service is on since the shortcut check. Without it, Android does not let Kestrel open an activity
    # from the background, and nothing tells Kestrel so.
```

with

```powershell
    # The accessibility service is on since the shortcut check. Without it, Android does not let Kestrel open an activity
    # from the background, and Kestrel says why instead.
```

On a phone with a PIN, `Wait-Reboot` waited in `Read-Host` for the owner's Enter after the unlock, and the reboot check's stopwatch ran across that wait, so its time held the owner's Enter. Now `Wait-Reboot` asks for the unlock without waiting for Enter and checks the phone every second; at the first check that sees the user's storage unlocked, it keeps the phone's uptime. The reboot check then times the restore on the phone's clock: from that uptime to Kestrel's open voice session, as the hook voice-times gives it (`SystemClock.elapsedRealtime` and `/proc/uptime` count the same). The time can be up to about 1.5 s short: the time between the unlock and the check that saw it, a sleep of 1 s and a few adb calls. When the phone gives no uptime, or Kestrel listened before the script saw the unlock, the line says only that Kestrel listened again, without a time. The emulator, which nobody unlocks, keeps the time from the reboot.

In `scripts/listen-check.ps1`, replace

```powershell
# Waits until the phone has started again after a reboot, which a new boot ID shows, and until Android has unlocked the
# user's storage, without which Kestrel cannot run. On a phone with a PIN, the owner has to unlock it first. It waits for
# at most 5 minutes, and returns whether the phone came back.
function Wait-Reboot([string]$BootId) {
    $deadline = (Get-Date).AddMinutes(5)
    $lockedChecks = 0
    do {
        Start-Sleep -Seconds 3
        $id = Get-ShellAnswer 'cat /proc/sys/kernel/random/boot_id'
        $booted = $id -and $id -ne $BootId -and (Get-ShellAnswer 'getprop sys.boot_completed') -eq '1'
        $unlocked = $booted -and (Get-ShellAnswer 'am get-started-user-state 0') -match 'RUNNING_UNLOCKED'
        # Without a PIN, Android unlocks the storage a few seconds after the start.
        if ($booted -and -not $unlocked -and ++$lockedChecks -eq 2) {
            Read-Host 'Unlock the phone, then press Enter' | Out-Null
        }
    } until ($unlocked -or (Get-Date) -gt $deadline)
    $unlocked
}
```

with

```powershell
# Waits until the phone has started again after a reboot, which a new boot ID shows, and until Android has unlocked the
# user's storage, without which Kestrel cannot run. On a phone with a PIN, the owner has to unlock it first; the script
# does not wait for an Enter, but checks every second. At the first check that sees the storage unlocked, it keeps the
# phone's uptime in milliseconds in $script:unlockedAt, or $null when the phone gives none, so that the reboot check can
# time Kestrel's restore on the phone's clock (A71 session of sub-project 2: no time counts from the owner's Enter). It
# waits for at most 5 minutes, and returns whether the phone came back.
function Wait-Reboot([string]$BootId) {
    $deadline = (Get-Date).AddMinutes(5)
    $lockedChecks = 0
    $script:unlockedAt = $null
    do {
        Start-Sleep -Seconds 1
        $id = Get-ShellAnswer 'cat /proc/sys/kernel/random/boot_id'
        $booted = $id -and $id -ne $BootId -and (Get-ShellAnswer 'getprop sys.boot_completed') -eq '1'
        $unlocked = $booted -and (Get-ShellAnswer 'am get-started-user-state 0') -match 'RUNNING_UNLOCKED'
        if ($unlocked) {
            # Such as '123.45 678.90': the seconds since the boot, on the clock of SystemClock.elapsedRealtime.
            $uptime = "$(Get-ShellAnswer 'cat /proc/uptime')".Trim() -split '\s+' | Select-Object -First 1
            if ($uptime -match '^\d+(\.\d+)?$') {
                $script:unlockedAt = [long]([double]::Parse($uptime, [Globalization.CultureInfo]::InvariantCulture) * 1000)
            }
        } elseif ($booted -and ++$lockedChecks -eq 5) {
            # Without a PIN, Android unlocks the storage a few seconds after the start, so the script asks only later.
            Write-Host 'Unlock the phone now. The script goes on by itself; do not press Enter. Kestrel shows only its notification when it listens again.'
        }
    } until ($unlocked -or (Get-Date) -gt $deadline)
    $unlocked
}

# The reboot check's time on a phone: from the unlock that Wait-Reboot saw to Kestrel's open voice session, both on the
# phone's clock, as the end of the check's line; without a time when the phone gave none or Kestrel listened before the
# script saw the unlock.
function Get-RestoreTime {
    $opened = [long](ConvertFrom-HookPairs (Invoke-Hook voice-times))['opened']
    if ($null -eq $script:unlockedAt) { return 'after the reboot, without a time, because the phone gave no time of the unlock' }
    if ($opened -le 0) { return 'after the reboot, without a time, because Kestrel gave no time of its open session' }
    if ($opened -lt $script:unlockedAt) { return 'after the reboot, without a time, because Kestrel listened before the script saw the unlock' }
    "$([math]::Round(($opened - $script:unlockedAt) / 1000, 1)) s after the phone was unlocked, timed on the phone"
}
```

In `scripts/listen-check.ps1`, replace

```powershell
    if ((Wait-Mode listening 240) -lt 0) { return 'failed: Kestrel did not listen again within 240 s of the device''s start' }
    $again = [math]::Round($watch.Elapsed.TotalSeconds, 1)
```

with

```powershell
    if ((Wait-Mode listening 240) -lt 0) { return 'failed: Kestrel did not listen again within 240 s of the device''s start' }
    $again = if ($isEmulator) { "$([math]::Round($watch.Elapsed.TotalSeconds, 1)) s after the reboot" } else { Get-RestoreTime }
```

In `scripts/listen-check.ps1`, replace

```powershell
    if ($problem) { return "failed: listening again $again s after the reboot, but $problem" }
    "ok: listening again $again s after the reboot"
```

with

```powershell
    if ($problem) { return "failed: listening again $again, but $problem" }
    "ok: listening again $again"
```

In `scripts/listen-check.ps1`, replace

```powershell
    $results['notification'] = Invoke-NotificationCheck
    $results['tile'] = Invoke-TileCheck
    $results['shortcut'] = Invoke-ShortcutCheck
    $results['settings'] = Invoke-SettingsCheck
    $results['call'] = Invoke-CallCheck
    $results['restart'] = Invoke-RestartCheck
    if ($Reboot) { $results['reboot'] = Invoke-RebootCheck }
    if (-not $deviceLost) { $results['role'] = Invoke-RoleRestartCheck }
```

with

```powershell
    $results['notification'] = Invoke-Check { Invoke-NotificationCheck }
    $results['tile'] = Invoke-Check { Invoke-TileCheck }
    $results['shortcut'] = Invoke-Check { Invoke-ShortcutCheck }
    $results['settings'] = Invoke-Check { Invoke-SettingsCheck }
    $results['call'] = Invoke-Check { Invoke-CallCheck }
    $results['restart'] = Invoke-Check { Invoke-RestartCheck }
    if ($Reboot) { $results['reboot'] = Invoke-Check { Invoke-RebootCheck } }
    if (-not $deviceLost) { $results['role'] = Invoke-Check { Invoke-RoleRestartCheck } }
```

- [ ] **Step 6: Run the scripts' tests, and listen-check on the emulator**

Run: `scripts\test-scripts.ps1`
Expected: Pester passes P₀ + 6 tests in both shells, and PSScriptAnalyzer reports no problem.

Take the emulator lock first, and delete it after the run, also when it fails.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -File scripts\listen-check.ps1 -Serial emulator-5554`
Expected: seven lines, `notification`, `tile`, `shortcut`, `settings`, `call`, `restart` and `role`, each `ok`; the settings line says `ok: Kestrel's settings came to the front <n> ms after Kestrel asked for them, timed on the device`; exit code 0.

- [ ] **Step 7: Commit**

```powershell
git add scripts/lib/Kestrel.ps1 scripts/tests/fake-adb.ps1 scripts/tests/fake-adb-files.ps1 scripts/tests/Kestrel.Tests.ps1 scripts/tests/Models.Tests.ps1 scripts/listen-check.ps1 scripts/lib/Session.ps1 scripts/tests/Session.Tests.ps1
git commit -m "fix(scripts): give the command marker a line of its own, print a line for every check of listen-check, time its settings and a phone's reboot on the device, and keep the failures' classes" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 6: The earbuds' recording waits for the Bluetooth link

Analysis item 6. With the owner's Galaxy Buds, Kestrel did not hear its name through the earbuds. The leading hypothesis: on Android 13, while the SCO link is asked for but not yet up, Android names no communication device (`AudioDeviceBroker`, android13-release), and the recording, which opened right after the request, stayed on the phone's microphone. `HeadsetRoute.open` now waits up to 3 s until Android names the headset as its communication device, through the watcher's report or Android's current device, before it returns and the recording opens; a report of another device ends the wait at once, a failure during the wait withdraws the request, and after the limit the request stays, while a link that comes later counts only from the next start. The wait blocks the controller's thread, never the main thread, which delivers Android's reports. The debug hook `voice-route` reports the earbud option and the route of the newest recording, as types and levels only, so that Task 9's step `earbuds` can repeat the check and see where it breaks.

**Files:**
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, `AndroidCommunicationDevices.kt`, `MicCapture.kt`
- Modify: `core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (`latestMicrophone`)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt` (`VOICE_ROUTE`), `scripts/hook.ps1`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.8)

**Interfaces:**
- Consumes: nothing from earlier tasks but `SessionHooks` as Task 3 left it.
- Produces: `CommunicationDevices.current(): Int?`; `HeadsetRoute(devices, linkMillis: Long = 3_000)` with `linkedMillis: Long?`; `MicCapture.routeReport(): String` (`microphone=<open|closed> holding=<bool> headset=<sco|le-audio|none> linkedMillis=<n|none> communication=<type> routed=<type> loudest=<percent>`); `AppGraph.latestMicrophone: MicCapture?`; the hook `voice-route`, whose answer starts with `earbud=<on|off>` and then gives the report, or `microphone=none`. Task 9's step `earbuds` reads it.

- [ ] **Step 1: Write the failing JVM tests of the wait**

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt`, replace

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
```

with

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt`, replace

```kotlin
    private class FakeDevices(var headsets: List<Headset> = emptyList(), var accepts: Boolean = true) : CommunicationDevices {
        val chosen = mutableListOf<Headset>()
```

with

```kotlin
    private class FakeDevices(
        var headsets: List<Headset> = emptyList(),
        var accepts: Boolean = true,
        // Android names the chosen headset its communication device at once, or only when the test reports it.
        var linksAtOnce: Boolean = true,
    ) : CommunicationDevices {
        val chosen = mutableListOf<Headset>()

        @Volatile
        var currentId: Int? = null
        var currentFails = false
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt`, replace

```kotlin
        override fun choose(headset: Headset): Boolean {
            chosen += headset
            events += "choose"
            return accepts
        }
```

with

```kotlin
        override fun choose(headset: Headset): Boolean {
            chosen += headset
            events += "choose"
            if (accepts && linksAtOnce) currentId = headset.id
            return accepts
        }

        override fun current() = if (currentFails) throw RuntimeException("The audio service failed") else currentId
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt`, replace

```kotlin
    @Test
    fun aWatcherThatAndroidRefusesGivesTheRequestBack() {
```

with

```kotlin
    @Test
    fun openWaitsUntilAndroidNamesTheHeadset() {
        val devices = FakeDevices(headsets = listOf(classic), linksAtOnce = false)
        val route = HeadsetRoute(devices, linkMillis = 5_000)
        // Android reports the headset once its SCO link is up, a moment after the request (A71 session of sub-project 2).
        val link = thread {
            while (devices.watchers.isEmpty()) Thread.sleep(1)
            Thread.sleep(100)
            devices.change(classic.id)
        }

        assertSame(classic, route.open {})
        link.join(5_000)

        assertTrue("${route.linkedMillis}", (route.linkedMillis ?: -1) >= 50)
        assertTrue(route.holding)
    }

    @Test
    fun openGivesUpWaitingAfterItsLimitAndKeepsTheRequest() {
        val devices = FakeDevices(headsets = listOf(classic), linksAtOnce = false)
        val route = HeadsetRoute(devices, linkMillis = 50)

        assertSame(classic, route.open {})

        assertNull(route.linkedMillis)
        assertTrue(route.holding)
        assertEquals(0, devices.cleared)
    }

    @Test
    fun aLossDuringTheWaitEndsItAtOnce() {
        val devices = FakeDevices(headsets = listOf(classic), linksAtOnce = false)
        val route = HeadsetRoute(devices, linkMillis = 5_000)
        var lost = 0
        // Android reports another device, such as the phone's earpiece, while the start waits for the link.
        val other = thread {
            while (devices.watchers.isEmpty()) Thread.sleep(1)
            devices.change(null)
        }
        val started = System.nanoTime()

        route.open { lost++ }
        other.join(5_000)

        assertTrue("The wait did not end at once", (System.nanoTime() - started) / 1_000_000 < 4_000)
        assertEquals(1, lost)
        assertFalse(route.holding)
        assertNull(route.linkedMillis)
        assertEquals(1, devices.cleared)
    }

    @Test
    fun aFailureWhileWaitingWithdrawsTheRequest() {
        val devices = FakeDevices(headsets = listOf(classic), linksAtOnce = false).apply { currentFails = true }
        val route = HeadsetRoute(devices)

        assertThrows(RuntimeException::class.java) { route.open {} }

        assertFalse(route.holding)
        assertEquals(1, devices.cleared)
        assertEquals(0, devices.watchers.size)
    }

    @Test
    fun aWatcherThatAndroidRefusesGivesTheRequestBack() {
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.HeadsetRouteTest`
Expected: `BUILD FAILED` in `:core:voice:compileDebugUnitTestKotlin`, with `'current' overrides nothing`, `No parameter with name 'linkMillis' found` and `Unresolved reference 'linkedMillis'`.

- [ ] **Step 2: Wait for the headset's link before the recording opens**

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, replace

```kotlin
package dev.kestrel.voice.listen

/**
```

with

```kotlin
package dev.kestrel.voice.listen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, replace

```kotlin
    /** Withdraws Kestrel's request, so that Android's own choice counts again. */
    fun clear()
```

with

```kotlin
    /** Withdraws Kestrel's request, so that Android's own choice counts again. */
    fun clear()

    /** The ID of Android's communication device now, or null for none. */
    fun current(): Int?
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, replace

```kotlin
 * [open], so that the recording goes back to the phone's microphone; it asks again only at the next [open]. It never
 * sets Android's audio mode, so a call keeps its own route (r12, item 7). Any thread may call [open] and [close], one at
 * a time.
 */
class HeadsetRoute(private val devices: CommunicationDevices) {
```

with

```kotlin
 * [open], so that the recording goes back to the phone's microphone; it asks again only at the next [open]. It never
 * sets Android's audio mode, so a call keeps its own route (r12, item 7). [open] waits up to [linkMillis] until Android
 * names the headset as its communication device, because Android 13 does so for a classic headset only once its SCO
 * link is up, and a recording that opens before that may stay on the phone's microphone (A71 session of sub-project 2,
 * item 6). After the limit the request stays, and a link that comes later counts only from the next [open]. [open]
 * blocks its caller for up to [linkMillis], so it must not run on the main thread, which delivers Android's reports;
 * any thread may call [close]; one call at a time.
 */
class HeadsetRoute(private val devices: CommunicationDevices, private val linkMillis: Long = LINK_MILLIS) {
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, replace

```kotlin
    fun open(onLost: () -> Unit): Headset? {
        synchronized(lock) {
            check(held == null) { "A headset is already asked for" }
            val headsets = devices.headsets()
            // LE Audio keeps the quality of the sound, while a classic headset switches to its call profile (r12, item 5).
            val headset = headsets.firstOrNull { it.leAudio } ?: headsets.firstOrNull() ?: return null
            if (!devices.choose(headset)) return null
            val token = Any()
            held = token
            // Android delivers its reports asynchronously, so a report of an earlier change, such as the clear of the last
            // close, could still arrive and withdraw this request; the callers leave time between a stop and the next
            // start, except when the owner's choice changes, and then only one of the two opens a route. Android also
            // reports the headset itself once its link is up, which changes nothing.
            watching = try {
                devices.watch { id -> if (id != headset.id) lose(token, onLost) }
            } catch (e: Exception) {
                // Without a watcher, nothing would ever withdraw the request. A failure of the withdrawal too is kept with
                // the watcher's.
                held = null
                runCatching { devices.clear() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
            return headset
        }
    }
```

with

```kotlin
    fun open(onLost: () -> Unit): Headset? {
        // Ends the wait below: the headset's own report, which names it, or a report of another device, after which the
        // request is withdrawn.
        val reported = CountDownLatch(1)
        val named = AtomicBoolean(false)
        val headset = synchronized(lock) {
            check(held == null) { "A headset is already asked for" }
            linkedMillis = null
            val headsets = devices.headsets()
            // LE Audio keeps the quality of the sound, while a classic headset switches to its call profile (r12, item 5).
            val headset = headsets.firstOrNull { it.leAudio } ?: headsets.firstOrNull() ?: return null
            if (!devices.choose(headset)) return null
            val token = Any()
            held = token
            // Android delivers its reports asynchronously, so a report of an earlier change, such as the clear of the last
            // close, could still arrive and withdraw this request; the callers leave time between a stop and the next
            // start, except when the owner's choice changes, and then only one of the two opens a route. Android reports
            // the headset itself once its link is up, which ends the wait below.
            watching = try {
                devices.watch { id ->
                    if (id == headset.id) named.set(true) else lose(token, onLost)
                    reported.countDown()
                }
            } catch (e: Exception) {
                // Without a watcher, nothing would ever withdraw the request. A failure of the withdrawal too is kept with
                // the watcher's.
                held = null
                runCatching { devices.clear() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
            headset
        }
        // Without the lock, so that a loss can still come. The current device covers a report that came before the
        // watcher was there.
        val started = System.nanoTime()
        try {
            if (devices.current() == headset.id) named.set(true) else reported.await(linkMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // The headset counts as not named, and the caller keeps its interrupt.
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // A failure of Android's audio service: the request is withdrawn, as after a failure of the watcher.
            runCatching { close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        if (named.get()) linkedMillis = (System.nanoTime() - started) / 1_000_000
        return headset
    }

    /**
     * How long the latest [open] waited until Android named the headset as its communication device, in milliseconds, or
     * null when it did not within [linkMillis]; for the debug hook voice-route (plan 2E).
     */
    @Volatile
    var linkedMillis: Long? = null
        private set
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, replace

```kotlin
    // Runs whole under the lock, so that a close that comes meanwhile waits until the withdrawal and onLost are done.
```

with

```kotlin
    private companion object {
        // How long a start waits for the headset's link. The A71 session's linkedMillis shows whether it must grow.
        const val LINK_MILLIS = 3_000L
    }

    // Runs whole under the lock, so that a close that comes meanwhile waits until the withdrawal and onLost are done.
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/AndroidCommunicationDevices.kt`, replace

```kotlin
    override fun clear() {
        audio.clearCommunicationDevice()
    }
```

with

```kotlin
    override fun clear() {
        audio.clearCommunicationDevice()
    }

    override fun current(): Int? = audio.communicationDevice?.id
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:core:voice` has V₀ + 5 tests (`HeadsetRouteTest` 15), all passing.

- [ ] **Step 3: Report the route of the recording**

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
import android.content.pm.PackageManager
import android.media.AudioFormat
```

with

```kotlin
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
import kotlinx.coroutines.flow.asStateFlow
```

with

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
 * [HeadsetRoute] asks Android for the earbuds before the recording exists, so that Android routes the recording there
 * from its first frame, and for an LE Audio headset the recording also names the headset's microphone as its preferred
 * device, because Android gives that microphone to VOICE_RECOGNITION only then (research r12). When the earbuds go away,
```

with

```kotlin
 * [HeadsetRoute] asks Android for the earbuds before the recording exists, and waits up to 3 s until Android names them
 * its communication device, so that Android routes the recording there from its first frame (on Android 13 a classic
 * headset is named only once its link is up), and for an LE Audio headset the recording also names the headset's
 * microphone as its preferred device, because Android gives that microphone to VOICE_RECOGNITION only then (research
 * r12). When the earbuds go away,
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
    private var canceller: AcousticEchoCanceler? = null
    private var route: HeadsetRoute? = null
```

with

```kotlin
    private var canceller: AcousticEchoCanceler? = null

    @Volatile
    private var route: HeadsetRoute? = null

    // For routeReport: the kind of the headset that the latest start asked for, and the loudest sample since its last
    // call, in percent.
    @Volatile
    private var headsetKind = "none"
    private val loudest = AtomicInteger()
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
    private val callback = object : AudioManager.AudioRecordingCallback() {
```

with

```kotlin
    /**
     * The route of the current recording, for the debug hook voice-route (plan 2E; A71 session of sub-project 2, item 6):
     * whether a recording is open, whether the request for the earbuds holds, the headset's kind (sco, le-audio or none),
     * how long the start waited until Android named the headset, the types of Android's communication device and of the
     * recording's input, and the loudest sample since the last call, in percent. Types and levels only, never audio. Any
     * thread may call it.
     */
    fun routeReport(): String {
        val recorder = record
        val current = route
        val routed = runCatching { recorder?.routedDevice?.type }.getOrNull()
        return "microphone=${if (recorder != null) "open" else "closed"} holding=${current?.holding == true} " +
            "headset=$headsetKind linkedMillis=${current?.linkedMillis ?: "none"} " +
            "communication=${typeName(audio.communicationDevice?.type)} routed=${typeName(routed)} " +
            "loudest=${loudest.getAndSet(0)}"
    }

    private val callback = object : AudioManager.AudioRecordingCallback() {
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
        val headset = earbuds?.open(onLost = ::usePhoneMicrophone)
```

with

```kotlin
        val headset = earbuds?.open(onLost = ::usePhoneMicrophone)
        headsetKind = when {
            headset == null -> "none"
            headset.leAudio -> "le-audio"
            else -> "sco"
        }
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
                val frame = FloatArray(count) { buffer[it] / 32_768f }
```

with

```kotlin
                var peak = 0
                val frame = FloatArray(count) { index ->
                    val sample = buffer[index].toInt()
                    if (sample > peak) peak = sample else if (-sample > peak) peak = -sample
                    sample / 32_768f
                }
                loudest.accumulateAndGet(peak * 100 / 32_768) { a, b -> maxOf(a, b) }
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
    private companion object {
        const val FRAME = SAMPLE_RATE / 50
```

with

```kotlin
    private companion object {
        const val FRAME = SAMPLE_RATE / 50

        /** The name of an audio device's type for routeReport, such as "bluetooth-sco", or "none". */
        fun typeName(type: Int?): String = when (type) {
            null -> "none"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin-mic"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
            else -> "type-$type"
        }
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt`, replace

```kotlin
     * throw with the microphone still open; then [stop] releases it.
     */
    override fun start(
```

with

```kotlin
     * throw with the microphone still open; then [stop] releases it.
     *
     * With the earbud microphone allowed and earbuds connected, [start] waits up to 3 s for the earbuds' link
     * ([HeadsetRoute.open]), so it must not run on the main thread, which delivers Android's reports.
     */
    override fun start(
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
            microphones = { MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }) },
            timeline = timeline,
        ).also(::keepScreenFresh)
    }
```

with

```kotlin
            microphones = {
                MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }).also { latestMicrophone = it }
            },
            timeline = timeline,
        ).also(::keepScreenFresh)
    }

    /** The microphone of the newest voice session, for the debug hook voice-route (plan 2E). */
    @Volatile
    internal var latestMicrophone: MicCapture? = null
        private set
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
 * service in the foreground (plan 2E). Their answers hold only counts, lengths, levels, times and the names of kinds.
```

with

```kotlin
 * service in the foreground (plan 2E). VOICE_ROUTE answers the earbud option and the route of the newest recording
 * (MicCapture.routeReport), so that the A71 session sees where the earbuds' recording goes. Their answers hold only
 * counts, lengths, levels, times, device types and the names of kinds.
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
        "VOICE_TIMES" -> times()
```

with

```kotlin
        "VOICE_TIMES" -> times()
        "VOICE_ROUTE" -> "earbud=${if (graph.listening.currentOptions.value.earbudMicrophone) "on" else "off"} " +
            (graph.latestMicrophone?.routeReport() ?: "microphone=none")
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TIMES", "VOICE_TASK", "VOICE_ECHO")
```

with

```kotlin
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TIMES", "VOICE_ROUTE", "VOICE_TASK", "VOICE_ECHO")
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace

```kotlin
    @Test
    fun theTimesHookAnswersOnlyTimesAndKinds() {
```

with

```kotlin
    @Test
    fun theRouteHookAnswersTheRecordingsRouteAsTypesAndLevelsOnly() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        val answer = Regex(
            "earbud=(on|off) microphone=open holding=false headset=none linkedMillis=none " +
                "communication=[a-z0-9-]+ routed=[a-z0-9-]+ loudest=\\d+",
        )

        // With Kestrel's own microphone, which records from the emulator's microphone; the earbud option is off.
        ActivityScenario.launch(MainActivity::class.java).use {
            listening.send(Event.Toggle)
            assertTrue(
                "The voice session did not open",
                VoiceTests.waitUntil(60_000) { listening.sessionOpen.value && VoiceTests.graph.latestMicrophone != null },
            )
            // The recording reports its route a moment after it starts.
            var reply = hook("VOICE_ROUTE")
            VoiceTests.waitUntil(5_000) {
                reply = hook("VOICE_ROUTE")
                answer.matches(reply.text) && !reply.text.contains(" routed=none ")
            }

            assertEquals(reply.text, Activity.RESULT_OK, reply.code)
            assertTrue(reply.text, answer.matches(reply.text))
            assertTrue(reply.text, !reply.text.contains(" routed=none "))
        }
    }

    @Test
    fun theTimesHookAnswersOnlyTimesAndKinds() {
```

In `scripts/hook.ps1`, replace

```powershell
#                                           foreground (foreground= from Android's list, demoted= Kestrel's decision)
```

with

```powershell
#                                           foreground (foreground= from Android's list, demoted= Kestrel's decision)
#   voice-route                             the earbud option and the route of Kestrel's newest recording: whether it is
#                                           open, whether the request for the earbuds holds, the headset's kind, how
#                                           long the start waited for the headset's link, the types of Android's
#                                           communication device and of the recording's input, and the loudest sample
#                                           since the last voice-route, in percent; never audio
```

In `scripts/hook.ps1`, replace

```powershell
        'voice-counts', 'voice-times', 'voice-task', 'voice-echo', 'location', 'spike-results', 'files', 'watch', 'profiles',
```

with

```powershell
        'voice-counts', 'voice-times', 'voice-route', 'voice-task', 'voice-echo', 'location', 'spike-results', 'files',
        'watch', 'profiles',
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
an LE Audio headset before a classic one, before the recording exists.
```

with

```markdown
an LE Audio headset before a classic one, before the recording exists, and waits up to 3 s until Android names the earbuds as its communication device: Android 13 does so for classic earbuds only once their SCO link is up, and a recording that opens before that may stay on the phone's microphone (A71 session of sub-project 2). After 3 s the recording starts anyway and the request stays; a link that comes later counts only from the next start of the microphone. A report of another device during the wait withdraws the request at once.
```

- [ ] **Step 4: Run the tests that this task touches**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:core:voice` V₀ + 5 and `:app` A₀ + 13 tests, all passing.

Take the emulator lock first, keep it until the hook command below has run, and delete it then, also when a command fails.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: `Voice tests: D₀ + 3 run, 0 failed, 0 errors, 0 skipped.`, among them `SessionHookTest#theRouteHookAnswersTheRecordingsRouteAsTypesAndLevelsOnly` and `MicCaptureTest#withTheEarbudMicrophoneAllowedAndNoEarbudsThePhoneRecordsAndNoRequestStays`, which starts without earbuds and so does not wait.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -File scripts\hook.ps1 -Serial emulator-5554 voice-route`
Expected: `earbud=off microphone=none` while Kestrel has not listened in its process, or `earbud=off microphone=closed holding=false headset=none linkedMillis=none communication=<type> routed=none loudest=<n>` after it has.

- [ ] **Step 5: Check the build and stop Gradle**

Run: `scripts\test-scripts.ps1; scripts\gradle.ps1 lint kestrelAudit; scripts\gradle.ps1 --stop`
Expected: Pester P₀ + 6 in both shells, no analyzer problem; `BUILD SUCCESSFUL`, no lint issues.

- [ ] **Step 6: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/AndroidCommunicationDevices.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt scripts/hook.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt
git commit -m "fix(voice): open the earbuds' recording only after Android names the headset, and report the recording's route through a debug hook" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 7: The keyword spotter's hits on the phone, and its tuning at run time

Analysis item 8. Every measure that needs the keyword spotter failed on the A71 (0 of 8), and even a generated "Kestrel, wake up" needed 4 plays there. The spotter's boost and threshold become a run-time setting of `VoiceEngines`, which a debug hook sets, so that the session can try three settings without a new build (Task 9's step `spotter`). A voice device test, which runs in the session's voice-tests without the owner, feeds the same generated speech to the speech detector and the spotter with each of the three settings, as the pipeline feeds them: "Kestrel", "Hey Kestrel" and "Kestrel, wake up", 10 clips each, and 10 sentences without a keyword. It logs for each setting the hits for "Kestrel" by phrase, each hit's distance from the utterance's start, the false hits by keyword, and the processing time per second of audio (counts and times only). Kestrel's default setting does not change in this plan: the section "After the session" at the end of the plan says how the setting that the A71 measured best becomes the default.

**Files:**
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/KeywordSpotter.kt` (`KeywordTuning`), `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/KeywordLines.kt`, `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`
- Modify: `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/voice/KeywordTuningTest.kt`
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt` (`resample` for the tests)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt` (`VOICE_SPOTTER`), `scripts/hook.ps1`

**Interfaces:**
- Consumes: `SessionHooks` and `hook.ps1` as Task 6 left them.
- Produces: `data class KeywordTuning(boost: Float = 2.0f, threshold: Float = 0.15f)` in `dev.kestrel.voice`, which accepts a boost from 0.5 to 10 and a threshold from 0.01 to 0.5; `KeywordLines.text(tuning: KeywordTuning = KeywordTuning())`; `VoiceEngines.keywordTuning` (`@Volatile var`); the hook `voice-spotter [-Boost b] [-Threshold t]`, which answers `boost=<b> threshold=<t>`; the log lines `keyword spotter, boost <b> threshold <t>, "<phrase>": a hit for Kestrel in <n> of 10 clips; seconds from the utterance's start: <list>` and `keyword spotter, boost <b> threshold <t>, speech without a keyword: false hits in <n> of 10 sentences (kestrel <n>, stop <n>, cancel <n>); <n> ms of processing per second of audio`, for the settings 2.0/0.15, 3.0/0.1 and 4.0/0.05. Task 9 uses the hook and the lines.

- [ ] **Step 1: Write the failing JVM test of the tuning**

In `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt`, replace

```kotlin
import dev.kestrel.voice.Keyword
import org.junit.Assert.assertEquals
import org.junit.Test
```

with

```kotlin
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt`, replace

```kotlin
    @Test
    fun anUnknownNameIsNoKeyword() {
```

with

```kotlin
    @Test
    fun theTuningGoesOnEveryLine() {
        val tuned = KeywordLines.text(KeywordTuning(boost = 3.5f, threshold = 0.05f)).lines().filter { it.isNotEmpty() }

        assertEquals(lines.size, tuned.size)
        assertTrue(tuned.all { " :3.5 #0.05 @" in it })
        // Kestrel's values since plan 2A.
        assertTrue(lines.all { " :2.0 #0.15 @" in it })
    }

    @Test
    fun anUnknownNameIsNoKeyword() {
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.sherpa.KeywordLinesTest`
Expected: `BUILD FAILED` in `:core:voice:compileDebugUnitTestKotlin`, with `Unresolved reference 'KeywordTuning'`.

- [ ] **Step 2: Make the boost and the threshold a setting**

In `core/voice/src/main/kotlin/dev/kestrel/voice/KeywordSpotter.kt`, replace

```kotlin
data class KeywordHit(val keyword: Keyword, val seconds: Float)
```

with

```kotlin
data class KeywordHit(val keyword: Keyword, val seconds: Float)

/**
 * How eagerly the keyword spotter takes a keyword (design spec, section 8.2): sherpa-onnx's [boost] of each keyword's
 * score and its [threshold] for a keyword's probability. A higher boost and a lower threshold hear more keywords, true and
 * false ones. The defaults are Kestrel's since plan 2A; the A71 session tries others through a debug hook (plan 2E), and
 * only a setting that the A71 measured may replace them.
 */
data class KeywordTuning(val boost: Float = 2.0f, val threshold: Float = 0.15f) {
    init {
        require(boost in 0.5f..10f) { "The boost must lie between 0.5 and 10" }
        require(threshold in 0.01f..0.5f) { "The threshold must lie between 0.01 and 0.5" }
    }
}
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/KeywordLines.kt`, replace

```kotlin
import dev.kestrel.voice.Keyword
```

with

```kotlin
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordTuning
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/KeywordLines.kt`, replace

```kotlin
    private const val CANCEL = "▁CAN CE L"
    private const val TUNING = ":2.0 #0.15"

    /** The text of the spotter's keywords file. */
    fun text(): String = buildString {
        for (spelling in KESTREL) append("$spelling $TUNING @kestrel\n")
        append("$STOP $TUNING @stop\n")
        append("$CANCEL $TUNING @cancel\n")
    }
```

with

```kotlin
    private const val CANCEL = "▁CAN CE L"

    /** The text of the spotter's keywords file, with [tuning]'s boost and threshold on every line. */
    fun text(tuning: KeywordTuning = KeywordTuning()): String = buildString {
        val suffix = ":${tuning.boost} #${tuning.threshold}"
        for (spelling in KESTREL) append("$spelling $suffix @kestrel\n")
        append("$STOP $suffix @stop\n")
        append("$CANCEL $suffix @cancel\n")
    }
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, replace

```kotlin
    suspend fun speechDetector(): SpeechDetector = load(Engine.VAD) {
```

with

```kotlin
    /**
     * The keyword spotter's boost and threshold for the spotters that load from now on (design spec, section 8.2). Only
     * the debug hook voice-spotter changes it, so that the A71 session can try other values without a new build (plan 2E);
     * a change lasts until the end of the process. Any thread may set it.
     */
    @Volatile
    var keywordTuning = KeywordTuning()

    suspend fun speechDetector(): SpeechDetector = load(Engine.VAD) {
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, replace

```kotlin
        val keywords = writeWorkFile("keywords.txt", KeywordLines.text())
```

with

```kotlin
        val keywords = writeWorkFile("keywords.txt", KeywordLines.text(keywordTuning))
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:core:voice` has V₀ + 6 tests (`KeywordLinesTest` 3), all passing.

- [ ] **Step 3: Measure the spotter with generated speech on the device**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt`, replace

```kotlin
    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
```

with

```kotlin
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/KeywordTuningTest.kt`:

```kotlin
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
```

- [ ] **Step 4: Let a debug hook set the tuning**

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
import dev.kestrel.voice.ModelsNotReadyException
```

with

```kotlin
import dev.kestrel.voice.KeywordTuning
import dev.kestrel.voice.ModelsNotReadyException
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
 * counts, lengths, levels, times, device types and the names of kinds.
```

with

```kotlin
 * counts, lengths, levels, times, device types and the names of kinds. VOICE_SPOTTER sets the keyword spotter's boost and
 * threshold for the spotters that load from Kestrel's next start, until the end of its process, so that the session can
 * try other values without a new build.
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
            (graph.latestMicrophone?.routeReport() ?: "microphone=none")
```

with

```kotlin
            (graph.latestMicrophone?.routeReport() ?: "microphone=none")
        "VOICE_SPOTTER" -> spotter(intent)
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
    private fun task(intent: Intent): String {
```

with

```kotlin
    // The spotter's boost and threshold, after storing --ef boost and --ef threshold (plan 2E).
    private fun spotter(intent: Intent): String {
        if (intent.hasExtra(EXTRA_BOOST) || intent.hasExtra(EXTRA_THRESHOLD)) {
            val now = graph.voice.keywordTuning
            graph.voice.keywordTuning = try {
                val boost = intent.getFloatExtra(EXTRA_BOOST, now.boost)
                KeywordTuning(boost, intent.getFloatExtra(EXTRA_THRESHOLD, now.threshold))
            } catch (e: IllegalArgumentException) {
                throw HookError("add --ef boost from 0.5 to 10 and --ef threshold from 0.01 to 0.5")
            }
        }
        val tuning = graph.voice.keywordTuning
        return "boost=${tuning.boost} threshold=${tuning.threshold}"
    }

    private fun task(intent: Intent): String {
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TIMES", "VOICE_ROUTE", "VOICE_TASK", "VOICE_ECHO")
```

with

```kotlin
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TIMES", "VOICE_ROUTE", "VOICE_SPOTTER", "VOICE_TASK", "VOICE_ECHO")
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace

```kotlin
        private const val EXTRA_RESULT = "result"
```

with

```kotlin
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_BOOST = "boost"
        private const val EXTRA_THRESHOLD = "threshold"
```

In `scripts/hook.ps1`, replace

```powershell
#                                           since the last voice-route, in percent; never audio
```

with

```powershell
#                                           since the last voice-route, in percent; never audio
#   voice-spotter [-Boost b] [-Threshold t] answers the keyword spotter's boost and threshold, after storing -Boost (0.5
#                                           to 10) and -Threshold (0.01 to 0.5); a change counts from Kestrel's next
#                                           start until the end of its process
```

In `scripts/hook.ps1`, replace

```powershell
        'voice-counts', 'voice-times', 'voice-route', 'voice-task', 'voice-echo', 'location', 'spike-results', 'files',
        'watch', 'profiles',
        'snapshots')][string]$Command,
```

with

```powershell
        'voice-counts', 'voice-times', 'voice-route', 'voice-spotter', 'voice-task', 'voice-echo', 'location',
        'spike-results', 'files', 'watch', 'profiles', 'snapshots')][string]$Command,
```

In `scripts/hook.ps1`, replace

```powershell
    [switch]$Named
)
```

with

```powershell
    [switch]$Named,
    [double]$Boost = -1,
    [double]$Threshold = -1
)
```

In `scripts/hook.ps1`, replace

```powershell
if ($Named) { $extras += '--ez named true' }
```

with

```powershell
if ($Named) { $extras += '--ez named true' }
# In the invariant culture, whose decimal separator is always '.'.
if ($Boost -ge 0) { $extras += "--ef boost $($Boost.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($Threshold -ge 0) { $extras += "--ef threshold $($Threshold.ToString([Globalization.CultureInfo]::InvariantCulture))" }
```

- [ ] **Step 5: Run the tests that this task touches**

Run: `scripts\test-scripts.ps1`
Expected: Pester P₀ + 6 in both shells, and PSScriptAnalyzer reports no problem.

Take the emulator lock first, and delete it after the runs, also when they fail.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: `Voice tests: D₀ + 4 run, 0 failed, 0 errors, 0 skipped.`, and for each of the three settings three lines `keyword spotter, boost <b> threshold <t>, "...": a hit for Kestrel in <n> of 10 clips; seconds from the utterance's start: ...` and one line `keyword spotter, boost <b> threshold <t>, speech without a keyword: false hits in <n> of 10 sentences (...); <n> ms of processing per second of audio`. Write the counts into the task report.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -File scripts\hook.ps1 -Serial emulator-5554 voice-spotter -Boost 3 -Threshold 0.1; pwsh -File scripts\hook.ps1 -Serial emulator-5554 voice-spotter -Boost 2 -Threshold 0.15`
Expected: `boost=3.0 threshold=0.1`, then `boost=2.0 threshold=0.15`.

- [ ] **Step 6: Check the build and stop Gradle**

Run: `scripts\gradle.ps1 lint kestrelAudit; scripts\gradle.ps1 --stop`
Expected: `BUILD SUCCESSFUL`, no lint issues.

- [ ] **Step 7: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/KeywordSpotter.kt core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/KeywordLines.kt core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/KeywordTuningTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt scripts/hook.ps1
git commit -m "feat(voice): measure the keyword spotter's hits and false hits with three settings on the device, and let a debug hook set its boost and threshold" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```


### Task 8: Kokoro's speed on the phone, Piper where Kokoro is too slow, and Kokoro's int8 model for a measure

Analysis item 9. Kokoro's fp32 model (325 MB) loaded in 2355 ms on the A71 and spoke 1.6 s in 2902 ms (a real-time factor of 1.81), and its first start reached 2049 MB. sherpa-onnx's `kokoro-int8-multi-lang-v1_0` (Hugging Face `csukuangfj/kokoro-int8-multi-lang-v1_0`, commit 57adb37, uploaded on 2026-10-26 with the fix of sherpa-onnx PR 4087) has `model.int8.onnx` of 114,202,993 bytes (SHA-256 `75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e`) under the same Apache License 2.0, and its `voices.bin` and `tokens.txt` are byte for byte those that Kestrel copies already. Research r11 reports int8 about 25 % slower than fp32 on a Pixel 10 Pro and about twice as slow on an iPhone 15, with only about 6 % less memory (786 against 837 MB), so neither the emulator, whose processor is x86_64, nor the research can decide for the A71. Kestrel therefore keeps the fp32 model. The int8 model becomes a second optional model, which `push-models.ps1 -Kokoro` copies and only a voice device test loads: `KokoroModelsTest` loads each Kokoro model that is on the device, with 2 and with 4 threads, and logs the load time, the real-time factor and the PSS. The A71 session runs it in its step voice-tests, and the section "After the session" at the end of this plan applies the result. Kestrel also measures Kokoro's real-time factor when Kokoro first loads for a voice session on a phone, once for each model and install of Kestrel, and stores it; above 1, Piper speaks, the page "Voice and speech" says why, and later starts load Piper at once, without Kokoro. Choosing Kokoro again on the page, or through the hook voice-choice, and an update of Kestrel forget the stored measure.

**Files:**
- Modify: `models/manifest.json` (the optional model `tts-kokoro-int8`), `scripts/push-models.ps1`, `THIRD_PARTY_NOTICES.md`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt` (`kokoroModel`, `kokoro(model, threads)`, `KOKORO_INT8_MODEL`), `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt` (`threads`)
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/voice/KokoroModelsTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt` (`KokoroSpeed`, `SPEED_SAMPLE`, `SPEED_WARM_UP`, `KokoroSilentException`, `realTimeFactor`, `loadChosenVoice`, `loadStoredVoice`, `LoadedVoice.slow`)
- Modify: `app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (`SettingKeys.KOKORO_SPEED`, `installedAt`, `kokoroKnownTooSlow`, `kokoroTooSlow`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt` (`kokoroSlow`, `KokoroTooSlowException`, the choice of Kokoro, "Try it"), `VoicePage.kt`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt` (`voice-choice`), `scripts/hook.ps1`, `scripts/a71-session.ps1` (the old session's pattern of voice-choice, until Task 9)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.2 and 15), `README.md`

**Interfaces:**
- Consumes: `AppGraph` as Task 6 left it.
- Produces: `@Serializable data class KokoroSpeed(model: String = "", installed: Long = 0L, factor: Float = 0f)` with `measures(model, installed): Boolean` and `tooSlow(model, installed): Float?`; `internal const val SPEED_SAMPLE`, `SPEED_WARM_UP`, `internal class KokoroSilentException` and `internal fun realTimeFactor(voice: Synthesizer, clock: () -> Long): Float`; `loadChosenVoice(chosen, report, knownTooSlow: suspend () -> Boolean = { false }, tooSlow: suspend (Synthesizer) -> Boolean = { false }, load)` and `loadStoredVoice(read, report, knownTooSlow, tooSlow, load)`; `LoadedVoice(..., slow: Boolean = false)`; `SettingKeys.KOKORO_SPEED`; `AppGraph.installedAt` and `AppGraph.kokoroKnownTooSlow()`; `VoiceEngines.kokoroModel: String` (`"tts-kokoro/model.onnx"`) and `VoiceEngines.kokoro(model: String, threads: Int): Synthesizer`; `SettingsData.kokoroSlow: Flow<Float?>`; `class KokoroTooSlowException` (package `dev.kestrel.app.settings`); the strings `voice_kokoro_slow` and `voice_try_kokoro_off`; `voice-choice`'s answer ends with ` kokoroSlow=<factor with two decimals|none> kokoroSpeed=<factor with two decimals|none>`, and `voice-choice -Voice kokoro` forgets the stored measure; the log lines of `KokoroModelsTest`, `kokoro models, <int8|fp32>, <n> threads: loaded in <ms> ms; <s> s of speech in <ms> ms (real-time factor <f>); process PSS <n> MB before the load, <n> MB after it, <n> MB after the speech`, or `kokoro models, <int8|fp32>: not on the device`. Task 9's steps voice-tests and memory read them.

- [ ] **Step 1: Write the failing JVM tests of the speed**

In `app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt`, replace

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
```

with

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
```

In `app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt`, replace

```kotlin
    private class FakeVoice : Synthesizer {
        override val sampleRate = 22_050

        override fun synthesize(text: String) = FloatArray(0)

        override fun close() {}
    }
```

with

```kotlin
    private class FakeVoice : Synthesizer {
        override val sampleRate = 22_050
        var closed = false

        override fun synthesize(text: String) = FloatArray(0)

        override fun close() {
            closed = true
        }
    }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt`, replace

```kotlin
    @Test
    fun theStoredVoiceLoadsAtTheStoredSpeed() = runTest {
```

with

```kotlin
    @Test
    fun aKokoroVoiceThatIsTooSlowOnThePhoneGivesWayToPiper() = runTest {
        val kokoro = FakeVoice()
        val piper = FakeVoice()

        val chosen = VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 1.1f)
        val result = loadChosenVoice(chosen, tooSlow = { it === kokoro }) { options ->
            loaded += options
            if (options.voice == VoiceChoice.KOKORO) kokoro else piper
        }

        assertSame(piper, result.synthesizer)
        assertTrue(kokoro.closed)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(VoiceOptions(speed = 1.1f), result.options)
        // The page says that Kokoro is too slow, not that it did not load.
        assertTrue(result.slow)
        assertFalse(result.fellBack)
    }

    @Test
    fun aKokoroVoiceThatThePhoneMeasuredAsTooSlowDoesNotLoadAgain() = runTest {
        val piper = FakeVoice()

        val result = loadChosenVoice(
            VoiceOptionsSetting(VoiceChoice.KOKORO),
            knownTooSlow = { true },
            tooSlow = { throw AssertionError("Kokoro must not load") },
        ) { options ->
            loaded += options
            piper
        }

        assertSame(piper, result.synthesizer)
        assertEquals(listOf(VoiceChoice.PIPER), loaded.map { it.voice })
        assertTrue(result.slow)
    }

    @Test
    fun aMeasureThatFailsKeepsKokoroAndIsReported() = runTest {
        val kokoro = FakeVoice()
        val reports = mutableListOf<String>()

        val result = loadChosenVoice(
            VoiceOptionsSetting(VoiceChoice.KOKORO),
            report = { what, _ -> reports += what },
            tooSlow = { throw IOException("The store could not be written") },
        ) { kokoro }

        assertSame(kokoro, result.synthesizer)
        assertFalse(kokoro.closed)
        assertEquals(listOf("Measuring the Kokoro voice"), reports)
    }

    @Test
    fun aKokoroVoiceThatSaysNothingCountsAsNotLoaded() = runTest {
        val kokoro = FakeVoice()
        val piper = FakeVoice()
        val reports = mutableListOf<String>()

        val result = loadChosenVoice(
            VoiceOptionsSetting(VoiceChoice.KOKORO),
            report = { what, _ -> reports += what },
            tooSlow = { throw KokoroSilentException() },
        ) { options -> if (options.voice == VoiceChoice.KOKORO) kokoro else piper }

        assertSame(piper, result.synthesizer)
        assertTrue(kokoro.closed)
        assertTrue(result.fellBack)
        assertFalse(result.slow)
        assertEquals(listOf("Measuring the Kokoro voice"), reports)
    }

    @Test
    fun theRealTimeFactorIsTheTimeToSpeakAfterAWarmUpOverTheLengthOfTheSpeech() {
        var now = 1_000L
        val said = mutableListOf<String>()
        // 2 s of speech at 22,050 Hz, which takes 3 s to synthesize.
        val voice = object : Synthesizer {
            override val sampleRate = 22_050

            override fun synthesize(text: String): FloatArray {
                said += text
                now += 3_000
                return FloatArray(44_100)
            }

            override fun close() {}
        }

        assertEquals(1.5f, realTimeFactor(voice) { now }, 0.001f)
        assertEquals(listOf(SPEED_WARM_UP, SPEED_SAMPLE), said)
        assertThrows(KokoroSilentException::class.java) { realTimeFactor(FakeVoice()) { now } }
    }

    @Test
    fun aStoredSpeedCountsOnlyForItsModelAndInstallAndAboveOne() {
        val stored = KokoroSpeed("tts-kokoro/model.onnx", installed = 5L, factor = 1.8f)

        assertEquals(1.8f, stored.tooSlow("tts-kokoro/model.onnx", 5L))
        assertNull(stored.tooSlow("tts-kokoro-int8/model.int8.onnx", 5L))
        // An update of Kestrel measures again.
        assertNull(stored.tooSlow("tts-kokoro/model.onnx", 6L))
        assertNull(KokoroSpeed("tts-kokoro/model.onnx", 5L, 0.7f).tooSlow("tts-kokoro/model.onnx", 5L))
        assertTrue(KokoroSpeed("tts-kokoro/model.onnx", 5L, 0.7f).measures("tts-kokoro/model.onnx", 5L))
    }

    @Test
    fun theStoredVoiceLoadsAtTheStoredSpeed() = runTest {
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.VoiceOptionsSettingTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `No parameter with name 'tooSlow' found`, `No parameter with name 'knownTooSlow' found` and `Unresolved reference 'realTimeFactor'`, `'KokoroSilentException'`, `'SPEED_WARM_UP'`, `'KokoroSpeed'` and `'slow'`.

- [ ] **Step 2: Measure Kokoro's speed at its first load, and let Piper speak where it is too slow**

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt`, replace

```kotlin
/**
 * A voice that Kestrel loaded for its voice session: [synthesizer], which speaks with [options]. [fellBack]: the owner
 * chose Kokoro, and Piper speaks instead, because Kokoro did not load.
 */
internal class LoadedVoice(val synthesizer: Synthesizer, val options: VoiceOptions, val fellBack: Boolean = false)

/**
 * Loads the voice that the owner chose, at the chosen speed, with [load] (design spec, sections 8.2 and 15). When the
 * Kokoro voice does not load, because its files are missing or damaged or for any other failure, Piper speaks instead,
 * so that an optional voice never keeps Kestrel from listening, and [report] gets the failure, because Kestrel never
 * fails silently (section 14). A failure of the Piper voice still throws, such as [ModelsNotReadyException].
 */
internal suspend fun loadChosenVoice(
    chosen: VoiceOptionsSetting,
    report: (String, Throwable) -> Unit = { _, _ -> },
    load: suspend (VoiceOptions) -> Synthesizer,
): LoadedVoice {
    val options = chosen.options()
    if (options.voice == VoiceChoice.PIPER) return LoadedVoice(load(options), options)
    return try {
        LoadedVoice(load(options), options)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Logged by its class and stack frames only (section 13.5).
        report("Loading the Kokoro voice", e)
        val piper = options.copy(voice = VoiceChoice.PIPER)
        LoadedVoice(load(piper), piper, fellBack = true)
    }
}
```

with

```kotlin
/**
 * Kokoro's speed on this phone (design spec, sections 5.3 and 8.2): the real-time factor that Kestrel measured when
 * [model], the file of the Kokoro model, first loaded for a voice session after Kestrel was installed or updated at
 * [installed] (PackageInfo.lastUpdateTime). Above 1, the phone needs longer to say a sentence than the sentence lasts, so
 * Piper speaks instead. Another model or another install is measured again, and so is a Kokoro that the owner chooses
 * again. Stored encrypted like every setting.
 */
@Serializable
data class KokoroSpeed(val model: String = "", val installed: Long = 0L, val factor: Float = 0f) {
    /** Whether this is the measure of [model] in the install of [installed]. */
    fun measures(model: String, installed: Long): Boolean = this.model == model && this.installed == installed

    /** The measured factor when it [measures] [model] and [installed] and is above 1, else null. */
    fun tooSlow(model: String, installed: Long): Float? = factor.takeIf { measures(model, installed) && it > 1f }
}

/** About 5 s of speech without a keyword or a command, for the measure of Kokoro's speed. */
internal const val SPEED_SAMPLE = "The weather tomorrow will be mild, with a light wind from the west in the afternoon."

/** A short sentence that Kokoro says before the measure, so that the measure does not time its first run. */
internal const val SPEED_WARM_UP = "Hello."

/** Kokoro said nothing for the sentence of its measure, which counts as a failure to load. */
internal class KokoroSilentException : IllegalStateException("Kokoro said nothing")

/**
 * The real-time factor of [voice] for [SPEED_SAMPLE], after it has said [SPEED_WARM_UP]: the milliseconds that [clock]
 * counts while the voice synthesizes the sample, divided by the milliseconds that the sample lasts. Throws
 * [KokoroSilentException] when the voice says nothing.
 */
internal fun realTimeFactor(voice: Synthesizer, clock: () -> Long): Float {
    voice.synthesize(SPEED_WARM_UP)
    val started = clock()
    val samples = voice.synthesize(SPEED_SAMPLE)
    val took = clock() - started
    if (samples.isEmpty()) throw KokoroSilentException()
    return took / (samples.size * 1_000f / voice.sampleRate)
}

/**
 * A voice that Kestrel loaded for its voice session: [synthesizer], which speaks with [options]. [fellBack]: the owner
 * chose Kokoro, and Piper speaks instead, because Kokoro did not load. [slow]: the owner chose Kokoro, and Piper speaks
 * instead, because Kokoro speaks more slowly than real time on this phone.
 */
internal class LoadedVoice(
    val synthesizer: Synthesizer,
    val options: VoiceOptions,
    val fellBack: Boolean = false,
    val slow: Boolean = false,
)

/**
 * Loads the voice that the owner chose, at the chosen speed, with [load] (design spec, sections 8.2 and 15). When the
 * Kokoro voice does not load, because its files are missing or damaged or for any other failure, Piper speaks instead,
 * so that an optional voice never keeps Kestrel from listening, and [report] gets the failure, because Kestrel never
 * fails silently (section 14). Kokoro speaks more slowly than real time on some phones (plan 2E): when [knownTooSlow]
 * says that this phone's stored measure found that, Piper speaks without loading Kokoro at all; otherwise, when
 * [tooSlow] measures the loaded Kokoro voice as too slow, Kestrel closes it, and Piper speaks too. A Kokoro that says
 * nothing in the measure counts as not loaded; any other failure of the measure is reported, and Kokoro speaks. A failure
 * of the Piper voice still throws, such as [ModelsNotReadyException].
 */
internal suspend fun loadChosenVoice(
    chosen: VoiceOptionsSetting,
    report: (String, Throwable) -> Unit = { _, _ -> },
    knownTooSlow: suspend () -> Boolean = { false },
    tooSlow: suspend (Synthesizer) -> Boolean = { false },
    load: suspend (VoiceOptions) -> Synthesizer,
): LoadedVoice {
    val options = chosen.options()
    if (options.voice == VoiceChoice.PIPER) return LoadedVoice(load(options), options)
    val piper = options.copy(voice = VoiceChoice.PIPER)
    val known = try {
        knownTooSlow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("Reading Kokoro's speed", e)
        false
    }
    if (known) return LoadedVoice(load(piper), piper, slow = true)
    val kokoro = try {
        load(options)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Logged by its class and stack frames only (section 13.5).
        report("Loading the Kokoro voice", e)
        return LoadedVoice(load(piper), piper, fellBack = true)
    }
    val slow = try {
        tooSlow(kokoro)
    } catch (e: CancellationException) {
        kokoro.close()
        throw e
    } catch (e: KokoroSilentException) {
        report("Measuring the Kokoro voice", e)
        kokoro.close()
        return LoadedVoice(load(piper), piper, fellBack = true)
    } catch (e: Exception) {
        report("Measuring the Kokoro voice", e)
        false
    }
    if (!slow) return LoadedVoice(kokoro, options)
    kokoro.close()
    return LoadedVoice(load(piper), piper, slow = true)
}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt`, replace

```kotlin
internal suspend fun loadStoredVoice(
    read: suspend () -> VoiceOptionsSetting,
    report: (String, Throwable) -> Unit,
    load: suspend (VoiceOptions) -> Synthesizer,
): LoadedVoice {
```

with

```kotlin
internal suspend fun loadStoredVoice(
    read: suspend () -> VoiceOptionsSetting,
    report: (String, Throwable) -> Unit,
    knownTooSlow: suspend () -> Boolean = { false },
    tooSlow: suspend (Synthesizer) -> Boolean = { false },
    load: suspend (VoiceOptions) -> Synthesizer,
): LoadedVoice {
```

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt`, replace

```kotlin
    return loadChosenVoice(chosen, report, load)
```

with

```kotlin
    return loadChosenVoice(chosen, report, knownTooSlow, tooSlow, load)
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.VoiceOptionsSettingTest`
Expected: `BUILD SUCCESSFUL`; `VoiceOptionsSettingTest` has 13 tests, all passing.

- [ ] **Step 3: Add Kokoro's int8 model for a measure on the phone**

The manifest's test of the engines' paths says that Kokoro's voice is the only optional model; from now on the int8 model is a second one, and its path must be in the manifest too.

In `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`, replace

```kotlin
    fun theKokoroVoicesFilesAreInTheManifestAndOnlyItIsOptional() {
        for (path in listOf(VoiceEngines.KOKORO_MODEL, VoiceEngines.KOKORO_VOICES, VoiceEngines.KOKORO_TOKENS)) {
            assertEquals(path, Engine.TTS, engineOf(path))
        }
        val ids = manifest.models.map { it.id }.toSet()
        for (id in VoiceEngines.PIPER_MODELS + VoiceEngines.KOKORO_MODELS) assertTrue(id, id in ids)
        assertEquals(listOf("tts-kokoro"), manifest.models.filter { it.optional }.map { it.id })
    }
```

with

```kotlin
    fun theKokoroVoicesFilesAreInTheManifestAndOnlyKokoroIsOptional() {
        val paths = listOf(VoiceEngines.KOKORO_MODEL, VoiceEngines.KOKORO_VOICES, VoiceEngines.KOKORO_TOKENS, VoiceEngines.KOKORO_INT8_MODEL)
        for (path in paths) {
            assertEquals(path, Engine.TTS, engineOf(path))
        }
        val ids = manifest.models.map { it.id }.toSet()
        for (id in VoiceEngines.PIPER_MODELS + VoiceEngines.KOKORO_MODELS + VoiceEngines.KOKORO_INT8_ID) assertTrue(id, id in ids)
        // Kokoro's voice, and its int8 model, which only a voice device test loads (plan 2E).
        assertEquals(listOf("tts-kokoro", "tts-kokoro-int8"), manifest.models.filter { it.optional }.map { it.id })
    }
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.VoiceModelPathsTest`
Expected: `BUILD FAILED` in `:core:voice:compileDebugUnitTestKotlin`, with `Unresolved reference 'KOKORO_INT8_MODEL'` and `'KOKORO_INT8_ID'`.

In `models/manifest.json`, replace

```json
          "url": "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b/tokens.txt"
        }
      ]
    }
  ]
}
```

with

```json
          "url": "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b/tokens.txt"
        }
      ]
    },
    {
      "id": "tts-kokoro-int8",
      "engine": "tts",
      "name": "Kokoro v1.0's int8 model, for a measure on the phone",
      "licence": "Apache-2.0",
      "project": "https://huggingface.co/hexgrad/Kokoro-82M",
      "optional": true,
      "files": [
        {
          "path": "tts-kokoro-int8/model.int8.onnx",
          "size": 114202993,
          "sha256": "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e",
          "url": "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/57adb37fc0e8d4bd430cfc1b8f8c2294945b9af2/model.int8.onnx"
        }
      ]
    }
  ]
}
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt`, replace

```kotlin
    private val options: VoiceOptions,
) : Synthesizer {
```

with

```kotlin
    private val options: VoiceOptions,
    threads: Int = THREADS,
) : Synthesizer {
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt`, replace

```kotlin
                numThreads = THREADS,
```

with

```kotlin
                numThreads = threads,
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, replace

```kotlin
    suspend fun speechDetector(): SpeechDetector = load(Engine.VAD) {
```

with

```kotlin
    /** The file of the Kokoro model that [synthesizer] loads, which names a measure of Kokoro's speed (plan 2E). */
    val kokoroModel: String get() = KOKORO_MODEL

    /**
     * Kokoro with the model file [model], a path in the models folder such as "tts-kokoro-int8/model.int8.onnx", and
     * [threads] threads, for the voice device test that compares Kokoro's models on a phone (plan 2E); Kestrel's voice
     * session uses [synthesizer]. Throws [ModelsNotReadyException] when the model's files are missing or damaged.
     */
    suspend fun kokoro(model: String, threads: Int): Synthesizer {
        val ids = if (model == KOKORO_INT8_MODEL) KOKORO_MODELS + KOKORO_INT8_ID else KOKORO_MODELS
        return load(Engine.TTS, ids) {
            SherpaKokoroSynthesizer(
                models.file(model),
                models.file(KOKORO_VOICES),
                models.file(KOKORO_TOKENS),
                models.file(ESPEAK_DATA),
                VoiceOptions(voice = VoiceChoice.KOKORO),
                threads,
            )
        }
    }

    suspend fun speechDetector(): SpeechDetector = load(Engine.VAD) {
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, replace

```kotlin
        const val KOKORO_TOKENS = "tts-kokoro/tokens.txt"
```

with

```kotlin
        const val KOKORO_TOKENS = "tts-kokoro/tokens.txt"

        // Kokoro's int8 model, which only a voice device test loads until the A71 session has compared it (plan 2E).
        const val KOKORO_INT8_ID = "tts-kokoro-int8"
        const val KOKORO_INT8_MODEL = "tts-kokoro-int8/model.int8.onnx"
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/KokoroModelsTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.os.SystemClock
import dev.kestrel.app.listen.SPEED_SAMPLE
import dev.kestrel.app.listen.SPEED_WARM_UP
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.pssMegabytes
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Kokoro's two models on the device's own processor (plan 2E; A71 session of sub-project 2, item 9): Kestrel's fp32 model
 * and sherpa-onnx's int8 model, each with 2 and with 4 threads. For each, it logs the time to load, the real-time factor
 * of the sentence of Kestrel's own measure after a short warm-up, and the process's PSS before the load, after it and
 * after the sentence. The rise during the load (after it minus before it) is what the A71 session compares: the int8
 * model loads first, so that among the test's own loads, memory that the process keeps after a closed voice can only
 * lower the fp32 model's rise, never the int8 model's; a test that ran before it, such as EchoTimingTest's Kokoro case,
 * can lower both. A model that is not on the device is logged as such. Counts and times only. It fails
 * only when no Kokoro model could be measured. It changes nothing on the device, so it runs on a phone too.
 */
class KokoroModelsTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.TTS, optional = setOf("tts-kokoro"))

    @Test
    fun eachKokoroModelOnTheDeviceIsMeasured() = runBlocking {
        var measured = 0
        for ((name, model) in MODELS) {
            for (threads in THREADS) {
                val before = pssMegabytes()
                val started = SystemClock.elapsedRealtime()
                val voice = try {
                    graph.voice.kokoro(model, threads)
                } catch (e: ModelsNotReadyException) {
                    log("kokoro models, $name: not on the device")
                    break
                }
                voice.use {
                    val loaded = SystemClock.elapsedRealtime()
                    val afterLoad = pssMegabytes()
                    it.synthesize(SPEED_WARM_UP)
                    val spoken = SystemClock.elapsedRealtime()
                    val samples = it.synthesize(SPEED_SAMPLE)
                    val took = SystemClock.elapsedRealtime() - spoken
                    val seconds = samples.size / it.sampleRate.toFloat()
                    log(
                        String.format(
                            Locale.ROOT,
                            "kokoro models, %s, %d threads: loaded in %d ms; %.1f s of speech in %d ms (real-time " +
                                "factor %.2f); process PSS %d MB before the load, %d MB after it, %d MB after the speech",
                            name, threads, loaded - started, seconds, took, took / 1_000f / seconds, before, afterLoad,
                            pssMegabytes(),
                        ),
                    )
                    measured++
                }
            }
        }
        assertTrue("No Kokoro model could be measured", measured > 0)
    }

    private companion object {
        // The int8 model first: among this test's loads, kept memory can then only lower fp32's rise.
        val MODELS = listOf("int8" to "tts-kokoro-int8/model.int8.onnx", "fp32" to "tts-kokoro/model.onnx")
        val THREADS = listOf(2, 4)
    }
}
```

In `scripts/push-models.ps1`, replace

```powershell
# optional Kokoro voice (design spec, section 8.2), which adds about 354 MB. Uninstalling Kestrel deletes the models from
```

with

```powershell
# optional Kokoro voice (design spec, section 8.2), about 354 MB, and Kokoro's int8 model, 114 MB more, which only a voice
# device test loads until the A71 session has compared it (plan 2E). Uninstalling Kestrel deletes the models from
```

In `THIRD_PARTY_NOTICES.md`, replace

```markdown
The Kokoro voice is optional: `scripts\push-models.ps1` copies it only with `-Kokoro`, from the sherpa-onnx project's copy of the package (https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0).
```

with

```markdown
The Kokoro voice is optional: `scripts\push-models.ps1` copies it only with `-Kokoro`, from the sherpa-onnx project's copy of the package (https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0). With it, the script copies the same model with its weights quantised to int8 from the sherpa-onnx project's int8 package (https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0), under the same Apache License 2.0; only a measure on the phone loads it.
```

- [ ] **Step 4: Wire the measure, and say it on the page "Voice and speech"**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.content.Intent
import android.os.SystemClock
```

with

```kotlin
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.FeedbackBar
```

with

```kotlin
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.KokoroSpeed
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.openWithBoostWords
```

with

```kotlin
import dev.kestrel.app.listen.openWithBoostWords
import dev.kestrel.app.listen.realTimeFactor
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.voice.VoiceEngines
```

with

```kotlin
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceEngines
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
    /** The owner's voice and speed (design spec, sections 8.2 and 15). */
    val VOICE = SettingKey("voice", VoiceOptionsSetting.serializer(), VoiceOptionsSetting())
```

with

```kotlin
    /** The owner's voice and speed (design spec, sections 8.2 and 15). */
    val VOICE = SettingKey("voice", VoiceOptionsSetting.serializer(), VoiceOptionsSetting())

    /** Kokoro's speed on this phone, measured at its first load for each model and install (sections 5.3 and 8.2). */
    val KOKORO_SPEED = SettingKey("kokoro-speed", KokoroSpeed.serializer(), KokoroSpeed())
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
                            // The page "Voice and speech" says when Kokoro did not load.
                            loadVoice = {
                                loadStoredVoice({ settings.get(SettingKeys.VOICE) }, reportFailure) { voice.synthesizer(it) }
                                    .also { kokoroFallback.value = it.fellBack }
                            },
```

with

```kotlin
                            // The page "Voice and speech" says when Kokoro did not load or was too slow.
                            loadVoice = {
                                loadStoredVoice(
                                    { settings.get(SettingKeys.VOICE) },
                                    reportFailure,
                                    ::kokoroKnownTooSlow,
                                    ::kokoroTooSlow,
                                ) { voice.synthesizer(it) }.also { kokoroFallback.value = it.fellBack }
                            },
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
    internal fun attachScreen(session: ScreenSession) {
```

with

```kotlin
    /** When Kestrel was installed or last updated: a measure of Kokoro's speed belongs to one install (plan 2E). */
    internal val installedAt: Long by lazy {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
            .lastUpdateTime
    }

    /** Whether this phone's stored measure says that Kokoro is too slow, so that Piper speaks without loading Kokoro. */
    internal suspend fun kokoroKnownTooSlow(): Boolean =
        settings.get(SettingKeys.KOKORO_SPEED).tooSlow(voice.kokoroModel, installedAt) != null

    /**
     * Whether Kokoro speaks more slowly than real time on this phone (design spec, sections 5.3 and 8.2): measured once for
     * each Kokoro model and install of Kestrel, when Kokoro first loads for a voice session, and stored. The measure
     * synthesizes a short sentence and then one of about 5 s, which takes about 10 s on the A71 with the fp32 model, on
     * the thread that opens the session, so it delays that session's start once.
     */
    private suspend fun kokoroTooSlow(kokoro: Synthesizer): Boolean {
        val stored = settings.get(SettingKeys.KOKORO_SPEED)
        if (stored.measures(voice.kokoroModel, installedAt)) return stored.factor > 1f
        val factor = realTimeFactor(kokoro, SystemClock::elapsedRealtime)
        settings.set(SettingKeys.KOKORO_SPEED, KokoroSpeed(voice.kokoroModel, installedAt, factor))
        return factor > 1f
    }

    internal fun attachScreen(session: ScreenSession) {
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.VoiceOptionsSetting
```

with

```kotlin
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.listen.KokoroSpeed
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.VoiceOptionsSetting
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
import dev.kestrel.privacy.logs.NetworkEvent
```

with

```kotlin
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.voice.VoiceChoice
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
    /** Whether the owner chose Kokoro and Piper spoke instead the last time that Kestrel turned on (section 14). */
    val kokoroFailed: Flow<Boolean>
```

with

```kotlin
    /** Whether the owner chose Kokoro and Piper spoke instead the last time that Kestrel turned on (section 14). */
    val kokoroFailed: Flow<Boolean>

    /**
     * Kokoro's real-time factor on this phone when Kestrel measured it above 1, so that Piper speaks instead, or null
     * (sections 5.3 and 8.2).
     */
    val kokoroSlow: Flow<Float?>
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
class StoredSettingsData(private val graph: AppGraph, private val context: Context) : SettingsData {
```

with

```kotlin
/**
 * "Try it" with Kokoro while Kestrel listens with Piper, because Kokoro is too slow on this phone: a second Kokoro beside
 * the session would load several hundred MB, so the page asks the owner to turn Kestrel off first (plan 2E).
 */
class KokoroTooSlowException : IllegalStateException("Kokoro is too slow on this phone while Kestrel listens")

class StoredSettingsData(private val graph: AppGraph, private val context: Context) : SettingsData {
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
    override suspend fun setVoiceOptions(options: VoiceOptionsSetting) = write {
        graph.settings.set(SettingKeys.VOICE, options)
    }
```

with

```kotlin
    override suspend fun setVoiceOptions(options: VoiceOptionsSetting) = write {
        // Plan 2E: choosing Kokoro, also with a tap on its row while it is chosen, forgets its stored speed, so that its
        // next start measures it again. "Slower" and "Faster" change the speed and keep the measure.
        val stored = graph.settings.get(SettingKeys.VOICE)
        if (options.voice == VoiceChoice.KOKORO && (stored.voice != VoiceChoice.KOKORO || options == stored)) {
            graph.settings.set(SettingKeys.KOKORO_SPEED, KokoroSpeed())
        }
        graph.settings.set(SettingKeys.VOICE, options)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
        val spoke = graph.listening.speakWithSessionVoice(options.options(), sample) ?: withContext(Dispatchers.IO) {
            graph.voice.synthesizer(options.options()).use { voice ->
```

with

```kotlin
        val spoke = graph.listening.speakWithSessionVoice(options.options(), sample) ?: run {
            // Plan 2E: where Kokoro is too slow, the open session speaks with Piper, and a second Kokoro beside it would
            // load several hundred MB.
            if (options.voice == VoiceChoice.KOKORO && graph.listening.sessionOpen.value && graph.kokoroKnownTooSlow()) {
                throw KokoroTooSlowException()
            }
            null
        } ?: withContext(Dispatchers.IO) {
            graph.voice.synthesizer(options.options()).use { voice ->
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
    override val kokoroFailed: Flow<Boolean> = graph.kokoroFailed
```

with

```kotlin
    override val kokoroFailed: Flow<Boolean> = graph.kokoroFailed

    override val kokoroSlow: Flow<Float?> =
        graph.settings.observe(SettingKeys.KOKORO_SPEED).map { it.tooSlow(graph.voice.kokoroModel, graph.installedAt) }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, replace

```kotlin
    val kokoroFailed by data.kokoroFailed.collectAsState(initial = false)
```

with

```kotlin
    val kokoroFailed by data.kokoroFailed.collectAsState(initial = false)
    val kokoroSlow by data.kokoroSlow.collectAsState(initial = null)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, replace

```kotlin
    var failed by remember { mutableStateOf(false) }
```

with

```kotlin
    var failed by remember { mutableStateOf(false) }
    var tooSlowToTry by remember { mutableStateOf(false) }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, replace

```kotlin
    if (kokoroFailed && current.voice == VoiceChoice.KOKORO) {
        Text(stringResource(R.string.voice_kokoro_failed), style = MaterialTheme.typography.bodySmall)
    }
```

with

```kotlin
    if (kokoroFailed && current.voice == VoiceChoice.KOKORO) {
        Text(stringResource(R.string.voice_kokoro_failed), style = MaterialTheme.typography.bodySmall)
    }
    // Plan 2E: Kestrel measured Kokoro's speed at its first load on this phone.
    kokoroSlow?.takeIf { current.voice == VoiceChoice.KOKORO }?.let { factor ->
        Text(
            stringResource(R.string.voice_kokoro_slow, String.format(Locale.ROOT, "%.2f", factor)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, replace

```kotlin
    fun change(options: VoiceOptionsSetting) {
        written = options
        failed = false
```

with

```kotlin
    fun change(options: VoiceOptionsSetting) {
        written = options
        failed = false
        tooSlowToTry = false
```

In `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, replace

```kotlin
            trying = true
            failed = false
```

with

```kotlin
            trying = true
            failed = false
            tooSlowToTry = false
```

In `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, replace

```kotlin
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
```

with

```kotlin
                } catch (e: CancellationException) {
                    throw e
                } catch (e: KokoroTooSlowException) {
                    tooSlowToTry = true
                } catch (e: Exception) {
                    failed = true
```

In `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, replace

```kotlin
    if (failed) Text(stringResource(R.string.voice_try_failed), style = MaterialTheme.typography.bodyMedium)
```

with

```kotlin
    if (failed) Text(stringResource(R.string.voice_try_failed), style = MaterialTheme.typography.bodyMedium)
    if (tooSlowToTry) Text(stringResource(R.string.voice_try_kokoro_off), style = MaterialTheme.typography.bodyMedium)
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="voice_kokoro_failed">Kokoro did not load the last time that Kestrel turned on, so Piper spoke. Kestrel tries Kokoro again the next time it turns on.</string>
```

with

```xml
    <string name="voice_kokoro_failed">Kokoro did not load the last time that Kestrel turned on, so Piper spoke. Kestrel tries Kokoro again the next time it turns on.</string>
    <string name="voice_kokoro_slow">Kokoro is too slow on this phone: it needs %1$s seconds to say one second of speech, so Piper speaks instead. Tap Kokoro again to measure it again.</string>
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="voice_try_failed">Kestrel could not speak just now.</string>
```

with

```xml
    <string name="voice_try_failed">Kestrel could not speak just now.</string>
    <string name="voice_try_kokoro_off">Kestrel speaks with Piper while it listens, because Kokoro is too slow on this phone. Turn Kestrel off to try Kokoro.</string>
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace

```kotlin
            graph.settings.set(SettingKeys.VOICE, graph.settings.get(SettingKeys.VOICE).copy(voice = voice))
        }
        val stored = graph.settings.get(SettingKeys.VOICE)
        return "voice=${stored.voice.name.lowercase()} speed=${speedText(stored.options().speed)} " +
            "kokoroFailed=${graph.kokoroFailed.value}"
```

with

```kotlin
            // Plan 2E: choosing Kokoro forgets its stored speed, so that the session measures it at a known moment.
            if (voice == VoiceChoice.KOKORO) graph.settings.set(SettingKeys.KOKORO_SPEED, KokoroSpeed())
            graph.settings.set(SettingKeys.VOICE, graph.settings.get(SettingKeys.VOICE).copy(voice = voice))
        }
        val stored = graph.settings.get(SettingKeys.VOICE)
        // Kokoro's real-time factor on the phone, as Kestrel measured it for this model and install (plan 2E).
        val speed = graph.settings.get(SettingKeys.KOKORO_SPEED)
        val measured = speed.factor.takeIf { speed.measures(graph.voice.kokoroModel, graph.installedAt) }
        val slow = measured?.takeIf { it > 1f }
        return "voice=${stored.voice.name.lowercase()} speed=${speedText(stored.options().speed)} " +
            "kokoroFailed=${graph.kokoroFailed.value} kokoroSlow=${factorText(slow)} kokoroSpeed=${factorText(measured)}"
    }

    private fun factorText(factor: Float?): String {
        return factor?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "none"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace

```kotlin
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.ListeningSetting
```

with

```kotlin
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.KokoroSpeed
import dev.kestrel.app.listen.ListeningSetting
```

In `scripts/hook.ps1`, replace

```powershell
#                                           spoke the last time that a voice session opened, after storing -Voice at
#                                           the stored speed; a new voice counts from Kestrel's next start
```

with

```powershell
#                                           spoke the last time that a voice session opened, Kokoro's real-time factor
#                                           on the phone when Kestrel measured it above 1 (kokoroSlow=none otherwise),
#                                           and the factor that it measured (kokoroSpeed=none before a measure), after
#                                           storing -Voice at the stored speed; a new voice counts from Kestrel's next
#                                           start, and -Voice kokoro forgets the measure, so that the next start with
#                                           Kokoro measures it again
```

The old session script reads the answer of voice-choice with a pattern that ends after `kokoroFailed=`. Task 9 replaces the whole script; until then, it must still read the longer answer.

In `scripts/a71-session.ps1`, replace

```powershell
    if ($text -notmatch '^voice=(\w+) speed=([\d.]+) kokoroFailed=(true|false)$') { throw 'Unexpected answer of voice-choice.' }
```

with

```powershell
    if ($text -notmatch '^voice=(\w+) speed=([\d.]+) kokoroFailed=(true|false)\b') { throw 'Unexpected answer of voice-choice.' }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace

```kotlin
        override val kokoroFailed = MutableStateFlow(false)
```

with

```kotlin
        override val kokoroFailed = MutableStateFlow(false)
        override val kokoroSlow = MutableStateFlow<Float?>(null)
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace

```kotlin
    @Test
    fun theAboutPageSaysThatAnOptionalModelIsNotOnThePhone() {
```

with

```kotlin
    @Test
    fun theVoicePageSaysWhenKokoroIsTooSlowOnThePhone() {
        data.voiceOptions.value = VoiceOptionsSetting(VoiceChoice.KOKORO)
        data.kokoroSlow.value = 1.81f
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, READY_KOKORO))))
        openVoicePage()

        compose.onNodeWithText(text(R.string.voice_kokoro_slow, "1.81")).assertIsDisplayed()
        // While Kestrel listens with Piper, "Try it" does not load a second Kokoro.
        data.tryFailure = KokoroTooSlowException()
        compose.onNodeWithText(text(R.string.voice_try)).performClick()
        compose.onNodeWithText(text(R.string.voice_try_kokoro_off)).assertIsDisplayed()
        // With Piper chosen, the page has nothing to say about Kokoro's speed.
        compose.onNodeWithText(text(R.string.voice_piper)).performClick()
        compose.onAllNodesWithText(text(R.string.voice_kokoro_slow, "1.81")).assertCountEquals(0)
    }

    @Test
    fun theAboutPageSaysThatAnOptionalModelIsNotOnThePhone() {
```

- [ ] **Step 5: Say it in the documents**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
The A71 session measures how fast each voice speaks there; if Kokoro is slower than real time on the A71, a change after the session makes the page say so next to the choice.
```

with

```markdown
Kestrel measures Kokoro's real-time factor with one sentence, after a short warm-up, when Kokoro first loads for a voice session on a phone, once for each Kokoro model and install of Kestrel, and stores it; when it is above 1, Kestrel closes Kokoro, Piper speaks, the page "Voice and speech" says that Kokoro is too slow on this phone, and later voice sessions load Piper without Kokoro. Choosing Kokoro on the page, also with a tap on Kokoro while it is chosen, measures it again. On the A71, Kokoro's fp32 model spoke 1.81 times slower than real time on 2026-11-17. Plan 2E adds sherpa-onnx's int8 model (`kokoro-int8-multi-lang-v1_0`, the upload of 2026-10-26 with the fix of PR 4087) as a second optional file, which only a voice device test loads: research r11 found int8 about 25 % slower than fp32 on a Pixel 10 Pro and about twice as slow on an iPhone 15, with only about 6 % less memory, so the A71 session compares both models there before Kestrel would change its model.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
When Kokoro is chosen and did not load the last time that Kestrel turned on, the page says so.
```

with

```markdown
When Kokoro is chosen and did not load the last time that Kestrel turned on, the page says so; when Kestrel measured Kokoro as too slow on this phone (section 8.2), the page says that too, with the measured factor, and while Kestrel listens with Piper for that reason, "Try it" with Kokoro asks the owner to turn Kestrel off first, so that no second voice is loaded beside the session's.
```

In `README.md`, replace

```markdown
With `-Kokoro`, the script also copies the optional Kokoro voice, about 354 MB more; then you can choose it on the page "Voice and speech".
```

with

```markdown
With `-Kokoro`, the script also copies the optional Kokoro voice, about 354 MB more, and Kokoro's int8 model, 114 MB more, which only a voice device test loads until the A71 session has compared the two models; then you can choose Kokoro on the page "Voice and speech". The first time that Kestrel turns on with Kokoro, it measures how fast Kokoro speaks on the phone; where Kokoro needs longer to say a sentence than the sentence lasts, Piper speaks instead, and the page says so. A tap on Kokoro on the page, also while it is chosen, measures it again.
```

- [ ] **Step 6: Run the JVM tests, and the device tests on the emulator**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:app` A₀ + 19 tests (`VoiceOptionsSettingTest` 13), `:core:voice` V₀ + 6 tests (`VoiceModelPathsTest` with its changed test), all passing.

Take the emulator lock only now, keep it until the last device command of this step has run, and delete it then, also when a command fails.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554 | Select-String 'kokoro|Voice tests:'`
Expected: the first run downloads `model.int8.onnx` (114 MB) and copies it into `tts-kokoro-int8`; `Voice tests: D₀ + 5 run, 0 failed, 0 errors, 0 skipped.`, with `KokoroModelsTest`, `SynthesizerTest`'s Kokoro test and `EchoTimingTest`'s Kokoro case among the passed tests, and four lines `kokoro models, <int8|fp32>, <2|4> threads: loaded in … ms; … (real-time factor …); process PSS …`. Write them into the task report.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.settings.SettingsAppTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: every `SettingsAppTest` test passes, among them `theVoicePageSaysWhenKokoroIsTooSlowOnThePhone`; the other device tests count O₀ + 1 from here on.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -File scripts\hook.ps1 -Serial emulator-5554 voice-choice`
Expected: one line `voice=piper speed=1.0 kokoroFailed=false kokoroSlow=none kokoroSpeed=none` (or the stored voice and speed, and the factor of a measure that has run).

- [ ] **Step 7: Check the build and stop Gradle**

Run: `scripts\test-scripts.ps1; scripts\gradle.ps1 lint kestrelAudit; scripts\gradle.ps1 --stop`
Expected: Pester P₀ + 6 in both shells (`Models.Tests.ps1` reads the changed manifest), no analyzer problem; `BUILD SUCCESSFUL`, no lint issues.

- [ ] **Step 8: Commit**

```powershell
git add models/manifest.json scripts/push-models.ps1 THIRD_PARTY_NOTICES.md core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/KokoroModelsTest.kt app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt app/src/main/res/values/strings.xml app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt scripts/hook.ps1 scripts/a71-session.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(voice): measure Kokoro's speed on the phone and let Piper speak where Kokoro is too slow, and add Kokoro's int8 model for a measure on the A71" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```


### Task 9: The session of the re-checks on the A71

Size: L. Direction 8 of the brief and items 1 to 11 of the analysis: a new `scripts\a71-session.ps1` that repeats only what failed or was not valid on 2026-11-17, with every time read from the phone (no measure counts from the owner's Enter), every prompt saying what the owner does and what the phone shows or does not show, and the measures that were missing: the foreground record on Android 13, the last byte of `dumpsys accessibility`, the route of the earbud recording, the keyword spotter's hits and false hits with three settings and the setting that a stated rule keeps, Kokoro's two models side by side, the reactions timed from the end of speech, the memory categories and the peak while a session opens, the cold start after `kill -9`, and three battery measures that give the model "% per hour = baseline + cost per transcribed minute × minutes". It also fixes the prompt of `scripts\a71-commands.ps1`'s step `recordings`, which the owner did not understand on 2026-11-17, and that step's estimate, now that it records all 160 lines that are marked for the owner.

The sitting: the owner runs `scripts\a71-commands.ps1` first, with all its steps, so that he records the voice corpus while he is fresh, and then `scripts\a71-session.ps1` with all its steps. `a71-commands.ps1`'s install copies neither Kokoro's two models nor the microphone's and the notifications' permissions, so the session keeps its own install step, which then finds Kestrel's build up to date and copies only Kokoro's models. `a71-commands.ps1`'s estimates add up to 116 minutes (about 100 to 125 minutes, of which about 90 to 115 need the owner), and the session's to 3 hours 58 minutes (about 65 minutes of the owner's hands or voice), so the sitting takes about 6 hours, of which about 2.5 to 3 hours need the owner. The owner may split it into two, at the boundary between the scripts.

**Files:**
- Modify: `scripts/lib/Session.ps1` (`Get-BatteryUse` keeps the breakdown; new helpers at the end of the file)
- Modify: `scripts/a71-session.ps1` (the whole script)
- Modify: `scripts/a71-commands.ps1` (the recordings' prompts, comment, refusal and estimate, and the header's total)
- Modify: `README.md` (the section "The A71 session", the corpus's recordings and `a71-commands.ps1`'s length)
- Test: `scripts/tests/Session.Tests.ps1` (9 new Pester tests)

**Interfaces:**
- Consumes: from Task 2, `Get-ForegroundRecord -Dump <string[]>` in `scripts/lib/Session.ps1`, which returns `Listed`, `Foreground` and `Types`, and the line `demoted=` of the hook `voice-times`; from Task 3, `ConvertFrom-HookPairs -Text <string>` (an ordered table of names and values), `ConvertFrom-TimeMark -Text <string>` (`Kind`, `At`, `SinceSpeech`, or `$null` for `none`), the hook `voice-times` (`now=`, `started=`, `opened=`, `speechEnded=`, `reacted=`, `stopped=`, `settingsAsked=`, `settingsResumed=`, `settingsInFront=`, `foreground=`, `demoted=`) and the counts of `voice-counts` (`hit-<keyword>`, `utterances`, `named`, `audioMillis`, `read-<kind>`); from Task 4, the reaction `settings-blocked` in `reacted=` and the notification "Open Kestrel's settings" with the ID 7104; from Task 5, the marker `kestrel-end` of `Invoke-SettingsCommand` and `Get-FailureDescriptions`, which keeps `Class`; from Task 6, the hook `voice-route` (`earbud=`, `microphone=`, `holding=`, `headset=`, `linkedMillis=`, `communication=`, `routed=`, `loudest=`); from Task 7, the hook `voice-spotter [-Boost b] [-Threshold t]` and the voice device test `KeywordTuningTest`; from Task 8, `kokoroSlow=` and `kokoroSpeed=` in the answer of `voice-choice`, which forgets the measure with `-Voice kokoro`, and the voice device test `KokoroModelsTest`.
- Produces: `Get-BatteryUse` also returns `Breakdown` (a text such as `cpu=54.2 cpu:fg=50.1 audio=12.3`); `Get-MemoryCategories -Dump <string[]>` (an ordered table of MB), `Format-MemoryCategories <table>` (text), `ConvertFrom-DumpDuration -Text <string>` (seconds), `Get-BatteryHours -Dump <string[]>` (hours or `$null`), `Get-BatteryCost -Baseline <double> -PercentPerHour <double> -MinutesPerHour <double>` (% per hour for each transcribed minute in an hour, or `$null`), `Test-EndsWithLineBreak -Answer <string[]>` (`$true`, `$false` or `$null`), `ConvertFrom-Uptime -Text <string>` (milliseconds), `Get-CountRise <before> <after>` (text), `Get-ProcessState -Dump <string[]>` (text) and `Select-SpotterWinner <rounds>` (the round that the rule keeps, or `$null`). The section "After the session" uses the session's lines.

The session's steps and their estimates, which add up to 3 hours 58 minutes; about 65 minutes of them need the owner's hands or voice (listen-check's unlock, battery-rules, activation, earbuds, spotter, voice and the tap of the memory step), and the owner may leave during install, voice-tests and the first two battery measures:

| Step | Minutes | What it re-checks or adds (analysis item) | The owner |
|---|---|---|---|
| install | 10 | Kestrel, the models with both of Kokoro's models, the permissions | may leave |
| voice-tests | 40 | `ListeningServiceTest` as Android 13 prints the foreground (1), `KeywordTuningTest` (8), `KokoroModelsTest` (9) | may leave |
| failures | 1 | the logged failures, with their classes (4) | none |
| listen-check | 12 | the last byte of `dumpsys accessibility`, then `listen-check.ps1 -Reboot` (3) | unlocks once |
| battery-rules | 10 | the foreground record and Kestrel's own view, every 5 s for 3 minutes, without and with the restriction (2) | looks at the shade twice |
| activation | 8 | shortcut, button and tile, timed from the event, and whether Android showed a dialog (11) | hands |
| assistant | 5 | "Kestrel, open settings" with the role alone, timed on the phone, with Kestrel's verdict and notification read on the phone (5), and the restore after a crash | none |
| earbuds | 8 | the route of the recording (6), music in the earbuds, and "Kestrel" into the earbuds | voice, earbuds |
| spotter | 16 | "Hey Kestrel" 5 times and 2 minutes of played speech without a keyword with each of three settings, and the setting that the rule keeps (8) | speaks, then stays quiet |
| voice | 20 | "Kestrel", "Hey Kestrel", "Kestrel, wake up", interruptions after about 1, 1.5 and 2 s, "Stop Kestrel" (8) | voice |
| memory | 12 | categories and peaks with Piper and Kokoro, Kokoro's speed measured at a known moment, and the cold start after `kill -9` (7, 9) | taps the tile once |
| battery | 95 | quiet room, quiet room with a task every 5 minutes, room talk (10), with the instant commands counted | may leave during the first two |
| failures-late | 1 | the failures since listen-check | none |

- [ ] **Step 1: Write the failing tests of the new helpers**

In `scripts/tests/Session.Tests.ps1`, add at the end of the file:

```powershell

Describe 'The helpers of the re-checks of plan 2E' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'keeps the breakdown of Kestrel''s line of batterystats, without its times' {
        $dump = @(
            '  Estimated power use (mAh):'
            '    Capacity: 4500, Computed drain: 120, actual drain: 100-110'
            '    UID u0a334: 81.4 ( cpu=54.2 (13m 1s 405ms) cpu:fg=50.1 (12m 3s) wakelock=0.04 (2s 3ms) audio=12.3 (30m 0s 0ms) CUSTOM_0=0.5 ) Including smearing: 85.1'
        )

        $use = Get-BatteryUse -Dump $dump -Uid 'u0a334' -Hours 0.5

        $use.MilliampHours | Should -Be 81.4
        $use.Breakdown | Should -BeExactly 'cpu=54.2 cpu:fg=50.1 wakelock=0.04 audio=12.3 CUSTOM_0=0.5'
    }

    It 'reads the time on battery that batterystats counted, in hours' {
        Get-BatteryHours -Dump @('  Time on battery: 30m 0s 0ms (100.0%) realtime, 29m 59s 1ms (99.9%) uptime') | Should -Be 0.5
        Get-BatteryHours -Dump @('  Time on battery: 1h 0m 36s 0ms (100.0%) realtime') | Should -Be 1.01
        Get-BatteryHours -Dump @('no such line') | Should -BeNullOrEmpty
    }

    It 'gives the cost of each transcribed minute in an hour above the quiet baseline' {
        Get-BatteryCost -Baseline 0.8 -PercentPerHour 3.7 -MinutesPerHour 29 | Should -Be 0.1
        Get-BatteryCost -Baseline 0.8 -PercentPerHour 0.9 -MinutesPerHour 0 | Should -BeNullOrEmpty
    }

    It 'reads the memory categories of a process in MB' {
        $dump = @(
            '                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap'
            '                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free'
            '  Native Heap   421888   421800        0        0   425000   500000   450000    50000'
            '  Dalvik Heap    10240    10000        0        0    12000    20000    10000    10000'
            '        .so mmap    37888     1000    30000        0    80000'
            '       Other mmap   215040      100   210000        0   220000'
            '          Unknown    97280    97000        0        0    98000'
            '            TOTAL  1003520   600000   300000        0  1100000   520000   460000    60000'
            ' App Summary'
            '           TOTAL PSS:  1003520            TOTAL RSS:  1100000'
        )

        $memory = Get-MemoryCategories -Dump $dump

        Format-MemoryCategories $memory | Should -BeExactly 'Native Heap 412, .so mmap 37, Other mmap 210, Unknown 95, TOTAL 980 (MB)'
        Format-MemoryCategories (Get-MemoryCategories -Dump @('No process found for: dev.kestrel')) | Should -BeExactly 'not listed'
    }

    It 'tells whether a command''s output ended with a line break from its last byte' {
        Test-EndsWithLineBreak -Answer @(' 0a') | Should -BeTrue
        Test-EndsWithLineBreak -Answer @(' 7d') | Should -BeFalse
        Test-EndsWithLineBreak -Answer @('') | Should -BeNullOrEmpty
    }

    It 'reads the time since boot in milliseconds' {
        ConvertFrom-Uptime -Text '12345.67 23456.78' | Should -Be 12345670
        { ConvertFrom-Uptime -Text 'none' } | Should -Throw
    }

    It 'lists what rose between two answers of voice-counts, without the waits and tasks' {
        $before = ConvertFrom-HookPairs -Text 'waits=1 tasks=0 lastTaskWords=0 hits=3 hit-kestrel=3 utterances=4'
        $after = ConvertFrom-HookPairs -Text 'waits=2 tasks=0 lastTaskWords=0 hits=4 hit-kestrel=4 named=1 read-name=1 utterances=5'

        Get-CountRise $before $after | Should -BeExactly 'hit-kestrel+1 named+1 read-name+1 utterances+1'
        Get-CountRise $after $after | Should -BeExactly 'none'
    }

    It 'reads the process state and the oom adj of a process' {
        $dump = @(
            '  *APP* UID 10334 ProcessRecord{1a2b3c 4321:dev.kestrel/u0a334}'
            '    oom adj: max=1001 curRaw=0 setRaw=0 cur=0 set=0'
            '    curSchedGroup=2 setSchedGroup=2 systemNoUi=false'
            '    curProcState=2 mRepProcState=2 pssProcState=2 setProcState=2'
        )

        Get-ProcessState -Dump $dump | Should -BeExactly 'procState=2 adj=0'
        Get-ProcessState -Dump @('  (nothing)') | Should -BeExactly 'not listed'
    }

    It 'keeps the spotter''s setting with the most hits among those with few false hits' {
        $rounds = @(
            [pscustomobject]@{ Setting = 'boost=2.0 threshold=0.15'; Default = $true; Hits = 1; FalseHits = 2 }
            [pscustomobject]@{ Setting = 'boost=3.0 threshold=0.1'; Default = $false; Hits = 4; FalseHits = 5 }
            [pscustomobject]@{ Setting = 'boost=4.0 threshold=0.05'; Default = $false; Hits = 5; FalseHits = 9 }
        )
        $tie = @($rounds[0], [pscustomobject]@{ Setting = 'boost=3.0 threshold=0.1'; Default = $false; Hits = 1; FalseHits = 2 })

        (Select-SpotterWinner $rounds).Setting | Should -BeExactly 'boost=3.0 threshold=0.1'
        (Select-SpotterWinner $tie).Setting | Should -BeExactly 'boost=2.0 threshold=0.15'
        Select-SpotterWinner @($rounds[1]) | Should -BeNullOrEmpty
    }
}
```

- [ ] **Step 2: Run them to make sure that they fail**

Run: `pwsh -NoProfile -Command "Import-Module Pester -RequiredVersion 6.2.0; Invoke-Pester scripts\tests\Session.Tests.ps1"`
Expected: FAIL: the 9 tests of 'The helpers of the re-checks of plan 2E' fail, the breakdown test because `Breakdown` is empty and the others with "The term 'Get-BatteryHours' is not recognized" and the like; every other test passes.

- [ ] **Step 3: Keep the breakdown of the battery use**

In `scripts/lib/Session.ps1`, replace

```powershell
# An app's battery use from 'dumpsys batterystats --charged' (research r10): the estimated mAh of the UID, the
# battery's capacity, and the share of the battery per hour over $Hours, in percent with two decimals. Returns $null
# when the dump has no estimate for the UID or no capacity.
function Get-BatteryUse([string[]]$Dump, [string]$Uid, [double]$Hours) {
    $capacity = $null
    $used = $null
    foreach ($line in $Dump) {
        if ($null -eq $capacity -and $line -match '^\s*Capacity:\s*(\d+)') { $capacity = [double]$Matches[1] }
        if ($null -eq $used -and $line -match "^\s*Uid\s+$([regex]::Escape($Uid)):\s*([\d.]+)") {
            $used = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    if ($null -eq $used -or -not $capacity -or $Hours -le 0) { return $null }
    [pscustomobject]@{
        MilliampHours = $used
        Capacity = $capacity
        PercentPerHour = [math]::Round($used / $Hours / $capacity * 100, 2)
    }
}
```

with

```powershell
# An app's battery use from 'dumpsys batterystats --charged' (research r10): the estimated mAh of the UID, the
# battery's capacity, the share of the battery per hour over $Hours, in percent with two decimals, and the breakdown that
# the UID's line gives in brackets, such as 'cpu=54.2 wakelock=0.04 audio=12.3', the parts' mAh without their times
# (analysis of the A71 session of sub-project 2, item 10). Returns $null when the dump has no estimate for the UID or no
# capacity.
function Get-BatteryUse([string[]]$Dump, [string]$Uid, [double]$Hours) {
    $capacity = $null
    $used = $null
    $breakdown = ''
    foreach ($line in $Dump) {
        if ($null -eq $capacity -and $line -match '^\s*Capacity:\s*(\d+)') { $capacity = [double]$Matches[1] }
        if ($null -eq $used -and $line -match "^\s*Uid\s+$([regex]::Escape($Uid)):\s*([\d.]+)(.*)$") {
            $used = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
            # The times in brackets inside the breakdown, such as '(13m 1s 405ms)', go first.
            $parts = [regex]::Matches(($Matches[2] -replace '\([^()]*\)', ''), '(?<![\w:])([\w:]+)=([\d.]+)')
            $breakdown = @($parts | ForEach-Object { "$($_.Groups[1].Value)=$($_.Groups[2].Value)" }) -join ' '
        }
    }
    if ($null -eq $used -or -not $capacity -or $Hours -le 0) { return $null }
    [pscustomobject]@{
        MilliampHours = $used
        Capacity = $capacity
        PercentPerHour = [math]::Round($used / $Hours / $capacity * 100, 2)
        Breakdown = $breakdown
    }
}
```

- [ ] **Step 4: Add the new helpers**

In `scripts/lib/Session.ps1`, add at the end of the file:

```powershell

# The memory categories of 'dumpsys meminfo <package>' that show where a process's memory stays (analysis of the A71
# session of sub-project 2, item 9): the PSS of Native Heap, .so mmap, Other mmap, Unknown and TOTAL, in MB, from the
# first number of each row of the table. Returns an ordered table, which is empty when the process does not run.
function Get-MemoryCategories([string[]]$Dump) {
    $categories = [ordered]@{}
    foreach ($line in $Dump) {
        if ($line -match '^\s*(Native Heap|\.so mmap|Other mmap|Unknown|TOTAL)\s+(\d+)\s' -and -not $categories.Contains($Matches[1])) {
            $categories[$Matches[1]] = [int][math]::Round([double]$Matches[2] / 1024)
        }
    }
    $categories
}

# The categories of Get-MemoryCategories as one text, such as 'Native Heap 412, .so mmap 37, TOTAL 980 (MB)', or
# 'not listed'.
function Format-MemoryCategories($Categories) {
    if (-not $Categories -or $Categories.Count -eq 0) { return 'not listed' }
    "$(@($Categories.Keys | ForEach-Object { "$_ $($Categories[$_])" }) -join ', ') (MB)"
}

# A duration as dumpsys prints it, such as '1h 2m 3s 456ms', in seconds.
function ConvertFrom-DumpDuration([string]$Text) {
    $seconds = 0.0
    foreach ($part in [regex]::Matches($Text, '(\d+)(ms|d|h|m|s)\b')) {
        $value = [double]$part.Groups[1].Value
        $seconds += switch ($part.Groups[2].Value) {
            'd' { $value * 86400 }
            'h' { $value * 3600 }
            'm' { $value * 60 }
            's' { $value }
            'ms' { $value / 1000 }
        }
    }
    $seconds
}

# The time that 'dumpsys batterystats --charged' counted on battery since its reset, in hours, from its line such as
# 'Time on battery: 30m 1s 234ms (100.0%) realtime, ...', so that a battery measure is timed by batterystats, or $null
# when the dump has no such line.
function Get-BatteryHours([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match 'Time on battery:\s*([^(]+)\(') { return (ConvertFrom-DumpDuration $Matches[1]) / 3600 }
    }
    $null
}

# Kestrel's battery model from two measures (plan 2E): '% per hour = baseline + cost per transcribed minute x minutes',
# where the minutes are the minutes of audio that Kestrel transcribed in an hour. $Baseline is the % per hour of a
# measure in which nobody spoke; a measure with transcribed audio, $PercentPerHour with $MinutesPerHour, gives the cost.
# Returns the cost in % per hour for each transcribed minute in an hour, with three decimals, or $null when that measure
# transcribed nothing.
function Get-BatteryCost([double]$Baseline, [double]$PercentPerHour, [double]$MinutesPerHour) {
    if ($MinutesPerHour -le 0) { return $null }
    [math]::Round(($PercentPerHour - $Baseline) / $MinutesPerHour, 3)
}

# Whether the answer of '<command> | tail -c 1 | od -An -tx1' says that the command's output ended with a line break
# (0a), as the session checks One UI's 'dumpsys accessibility' (analysis, item 3): $true, $false for another last byte,
# or $null when the answer has no byte. Only that byte is read, never the output.
function Test-EndsWithLineBreak([string[]]$Answer) {
    $byte = @("$($Answer -join ' ')" -split '\s+' | Where-Object { $_ -match '^[0-9a-fA-F]{2}$' }) | Select-Object -Last 1
    if (-not $byte) { return $null }
    $byte -eq '0a'
}

# The time since boot in milliseconds from the answer of 'cat /proc/uptime', such as '12345.67 23456.78', which counts the
# time in deep sleep too, as Kestrel's SystemClock.elapsedRealtime does, so the two can be compared.
function ConvertFrom-Uptime([string]$Text) {
    if ("$Text".Trim() -notmatch '^(\d+(?:\.\d+)?)\s') { throw "Not an answer of /proc/uptime: '$Text'" }
    [long][math]::Round([double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) * 1000)
}

# What rose between two answers of voice-counts, read with ConvertFrom-HookPairs, such as 'hit-kestrel+1 named+1
# read-name+1 utterances+1', in the order of the later answer; or 'none'. The waits and the tasks, which the steps check
# themselves, are left out.
function Get-CountRise($Before, $After) {
    $rises = foreach ($name in $After.Keys) {
        if ($name -in 'waits', 'tasks', 'lastTaskWords', 'hits') { continue }
        $was = if ($Before.Contains($name)) { [long]$Before[$name] } else { 0 }
        $rise = [long]$After[$name] - $was
        if ($rise -gt 0) { "$name+$rise" }
    }
    if ($rises) { @($rises) -join ' ' } else { 'none' }
}

# The state of a process from 'dumpsys activity processes <package>': its procState and its oom adj, such as
# 'procState=2 adj=0', or 'not listed' (analysis, item 7). Only these numbers are kept.
function Get-ProcessState([string[]]$Dump) {
    $procState = $null
    $adj = $null
    foreach ($line in $Dump) {
        if ($null -eq $procState -and $line -match '\bcurProcState=(-?\d+)') { $procState = $Matches[1] }
        if ($null -eq $adj -and $line -match '\boom adj:.*?\bcur=(-?\d+)') { $adj = $Matches[1] }
    }
    if ($null -eq $procState -and $null -eq $adj) { return 'not listed' }
    "procState=$(if ($null -ne $procState) { $procState } else { 'unknown' }) adj=$(if ($null -ne $adj) { $adj } else { 'unknown' })"
}

# The keyword spotter's setting that the A71 session keeps (plan 2E), from the rounds of its step spotter: each has
# Setting (such as 'boost=2.0 threshold=0.15'), Default ($true for Kestrel's own), Hits (how many of the owner's tries
# of "Hey Kestrel" Kestrel reacted to) and FalseHits (the hits of all three keywords in the played speech without a
# keyword, the same speech for every setting). The rule: among the settings whose false hits are at most twice the
# default's plus 2, the one with the most hits; a tie goes to fewer false hits, and then to the default. Returns $null
# when the default was not measured.
function Select-SpotterWinner([object[]]$Rounds) {
    $default = @($Rounds | Where-Object { $_.Default }) | Select-Object -First 1
    if (-not $default) { return $null }
    $limit = 2 * $default.FalseHits + 2
    @($Rounds | Where-Object { $_.FalseHits -le $limit }) |
        Sort-Object @{ Expression = { $_.Hits }; Descending = $true }, @{ Expression = { $_.FalseHits }; Descending = $false }, @{ Expression = { -not $_.Default }; Descending = $false } |
        Select-Object -First 1
}
```

- [ ] **Step 5: Run the tests to make sure that they pass**

Run: `pwsh -NoProfile -Command "Import-Module Pester -RequiredVersion 6.2.0; Invoke-Pester scripts\tests\Session.Tests.ps1"`
Expected: PASS, with 9 more tests than after Task 5 and none failed.

Run the same in Windows PowerShell: `powershell -NoProfile -Command "Import-Module Pester -RequiredVersion 6.2.0; Invoke-Pester scripts\tests\Session.Tests.ps1"`
Expected: the same counts, none failed.

- [ ] **Step 6: Write the session of the re-checks**

In `scripts/a71-session.ps1`, replace the whole file with:

```powershell
# The second session on the Galaxy A71 with the owner (plan 2E): it repeats the checks of sub-project 2 that failed or
# were not valid in the session of 2026-11-17 (the controller's analysis of the session), and adds the measures that
# were missing. Every time comes from the phone: Kestrel records when it was started, opened its session, heard the end
# of speech, reacted and turned itself off, and the script reads these times through the hook voice-times; no measure
# counts from the owner's Enter. Each prompt says what the owner does and what the phone shows or does not show. It asks
# before it reads the device's model and before each step. The steps' estimates add up to 3 hours 58 minutes; about 65
# minutes need the owner's hands or voice, and the owner may leave during the steps install and voice-tests and during
# the first two battery measures. The owner does it in one sitting with scripts\a71-commands.ps1, which comes first, so
# that the owner records the voice corpus while fresh: a71-commands.ps1 with all its steps (about 2 hours, of which
# about 1.5 to 2 hours need the owner), then this script with all its steps (about 4 hours, of which about 1 hour needs
# the owner); together about 6 hours, of which about 2.5 to 3 hours need the owner. The owner may split the sitting into
# two at the boundary between the scripts. a71-commands.ps1's install copies neither Kokoro's models nor the
# microphone's permission, so this script keeps its own install step, which then finds Kestrel's build up to date. The
# steps:
#   install       installs Kestrel's debug build, copies the speech models with the Kokoro voice and Kokoro's int8
#                 model, which only a voice device test loads, and grants Kestrel the microphone and notification
#                 permissions, which stay granted
#   voice-tests   the voice device tests that may run on a phone (scripts\voice-test.ps1), among them
#                 ListeningServiceTest, which reads the foreground as Android 13 prints it, KeywordTuningTest, which
#                 feeds generated "Kestrel", "Hey Kestrel" and "Kestrel, wake up" and speech without a keyword to the
#                 keyword spotter with each of its three settings, and KokoroModelsTest, which measures Kokoro's fp32
#                 and int8 models side by side; the tests that did not pass are recorded by name
#   failures      Kestrel's logged failures since the voice tests, by their fixed descriptions and classes, before
#                 listen-check, whose notification check makes Android refuse a start on purpose
#   listen-check  first whether 'dumpsys accessibility' ends with a line break (only its last byte), then
#                 scripts\listen-check.ps1 -Reboot, which prints one line for each check; the owner unlocks the phone
#                 after the restart
#   battery-rules without and with the battery restriction and without Kestrel's accessibility service: Android's record
#                 of the listening service, Kestrel's own view of it, and when Kestrel turned itself off, every 5 s for
#                 3 minutes, timed on the phone
#   activation    the accessibility shortcut, the accessibility button and the tile with Kestrel in the background:
#                 the time from the event to listening, timed on the phone, and whether Android showed a dialog
#   assistant     with only the assistant role and Kestrel in the background: a start through the hook, "Kestrel, open
#                 settings" with the time from Kestrel's request to its settings in front, timed on the phone, or
#                 Kestrel's own verdict that Android blocked them and its notification, read on the phone, and the
#                 restore after Android ended Kestrel's process; it waits, and says so, until 2 minutes have passed
#                 since the session's last crash of Kestrel, which it keeps in corpus\owner\session-state.txt
#   earbuds       the route of the recording with the owner's earbuds (the hook voice-route), "Kestrel" into the
#                 earbuds with the time from the end of the speech to the wait for a task, and the phone's microphone
#                 after the earbuds went away
#   spotter       for each of three settings of the keyword spotter's boost and threshold: "Hey Kestrel" 5 times, and
#                 about 2 minutes of generated speech without a keyword from this computer's speaker while Kestrel is
#                 paused, the same for every setting; the hits and false hits are counted on the phone, the script
#                 prints the setting that its rule keeps (Select-SpotterWinner), and the default comes back at the end
#   voice         "Kestrel", "Hey Kestrel", "Kestrel, wake up" while paused, interruptions with pauses of about 1, 1.5
#                 and 2 s, which this window's prompts set, and "Stop Kestrel": per try, the spotter's hits by keyword,
#                 the transcribed and named utterances, how each was read, and the time from the end of the speech to
#                 Kestrel's reaction, all from the phone
#   memory        three starts and closes with the chosen voice and with Kokoro, whose speed Kestrel measures at its
#                 first start here, with the peak PSS while each session opens and the memory categories before, while
#                 listening and after each close, and a cold start through the tile after the script ended Kestrel's
#                 process itself
#   battery       three measures of 30 minutes, each timed by batterystats: a quiet room, the quiet room with one
#                 generated task for Kestrel from this computer's speaker every 5 minutes, and ordinary room talk; with
#                 the breakdown of Kestrel's use, the audio that it transcribed, its utterances, hits and instant
#                 commands, and the model "% per hour = baseline + cost per transcribed minute x minutes"
#   failures-late Kestrel's logged failures from the end of listen-check to the end of the session
# The results go into corpus\owner\session-<time>.txt; git ignores it. Neither it nor the console ever hold what Kestrel
# heard: the hooks answer only counts, times, kinds and device types. Once the first step runs, the phone's screen stays
# on while the phone is plugged in. The script puts back what it changes on the phone, also when a step fails or Ctrl+C
# stops it: Kestrel's mode Off, the hooks' slow task, the keyword spotter's setting, the setting "Stay awake", the
# assistant role and its settings, Kestrel's battery restriction (as allow or ignore, never as default, which Android
# counts as restricted), Kestrel's voice, the accessibility settings, the screen's timeout and the battery's state. It
# cannot put back the battery statistics, which the battery measures reset, and it asks the owner to turn "Use the
# earbud microphone" off again and to swipe away the notifications that stay. It refuses every device but the A71 and
# kestrel-api37. -DryRun runs the session on kestrel-api37 without the owner: it answers every question itself, sends
# what the owner would say through the voice hook, taps Kestrel's tile with 'cmd statusbar click-tile' in place of the
# owner, also for the cold start, plays no sound, skips what needs the owner's hands otherwise, runs listen-check.ps1
# without -Reboot, skips voice-test.ps1, and shortens the waits and the battery measures to a minute. -Step runs only the
# named steps, also as one text separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-session.ps1 -Serial R58N12345AB
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$DryRun,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Session.ps1"
Use-KestrelToolchain

$stepNames = @('install', 'voice-tests', 'failures', 'listen-check', 'battery-rules', 'activation', 'assistant', 'earbuds',
    'spotter', 'voice', 'memory', 'battery', 'failures-late')
# pwsh -File passes '-Step voice,battery' as one text, so the names are split at commas.
$Step = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($Step) {
    $unknown = @($Step | Where-Object { $_ -notin $stepNames })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($stepNames -join ', ')." }
}
# Before any command reaches a device: a dry run answers the owner's questions itself, so it must never reach a phone.
if ($DryRun -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }

$owner = Join-Path $PSScriptRoot '..\corpus\owner'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = if ($DryRun) { Join-Path $owner "session-dry-run-$stamp.txt" } else { Join-Path $owner "session-$stamp.txt" }
# The session's state across its runs, such as a run with -Step: the time of its last crash of Kestrel.
$stateFile = if ($DryRun) { Join-Path $owner 'session-dry-run-state.txt' } else { Join-Path $owner 'session-state.txt' }
$corpusFile = Join-Path $PSScriptRoot '..\corpus\commands.txt'
$generatedClips = Join-Path $PSScriptRoot '..\corpus\generated\piper'
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$assistantRole = 'android.app.role.ASSISTANT'
$earbudsChanged = $false
$gradleUsed = $false
$prepared = $false
# The device's time, as logcat -T reads it, when the session's first step began and when listen-check ended.
$sessionStart = $null
$afterListenCheck = $null
# The shorter waits of a dry run.
$minuteScale = if ($DryRun) { 1 / 30 } else { 1 }

# Asks the owner to do something, and returns 'go' after Enter or 'skip' after s. q stops the session. In a dry run it
# answers $DryRunAnswer itself.
function Request-Owner([string]$Prompt, [string]$DryRunAnswer = 'skip') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    $answer = (Read-Host $Prompt).Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the session.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

# Asks the owner a question, and returns the answer as typed. In a dry run it answers $DryRunAnswer itself.
function Read-OwnerAnswer([string]$Prompt, [string]$DryRunAnswer) {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    (Read-Host $Prompt).Trim()
}

if ((Request-Owner "The session first reads the model of the device $Serial, to check that it is the Galaxy A71. Press Enter to go ahead, or q to stop." 'go') -ne 'go') {
    exit 1
}
# Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this script
# never passes.
Assert-TestDevice -Serial $Serial
$isEmulator = $KestrelTestDevices[$Serial].Pattern -eq 'kestrel-api37'
if ($DryRun -and -not $isEmulator) { throw 'A dry run runs only on the emulator kestrel-api37.' }
$adb = Get-Adb

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

# Runs a command on the device and fails when it fails; it runs only on the device that Assert-TestDevice accepted.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
}

# In a dry run, sends what the owner would say, as if Kestrel had heard it, with a pause after each text; a text that
# starts with '+' comes with the keyword spotter's "Kestrel".
function Send-Heard([string[]]$Texts) {
    foreach ($text in $Texts) {
        Invoke-Hook voice @{ Send = 'heard'; Text = $text.TrimStart('+'); Named = $text.StartsWith('+') } | Out-Null
        Start-Sleep -Seconds 2
    }
}

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer.
function Get-VoiceState {
    try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
}

# The counts of voice-counts (waits, tasks, lastTaskWords, hits, and the hits by keyword, the utterances and the reads
# where they are above 0), and the phone's own times of voice-times; see scripts\hook.ps1.
function Get-Counts { ConvertFrom-HookPairs (Invoke-Hook voice-counts) }

function Get-Times { ConvertFrom-HookPairs (Invoke-Hook voice-times) }

# The device's time since boot in milliseconds, on the clock of Kestrel's times.
function Get-DeviceUptime { ConvertFrom-Uptime ((Invoke-Checked 'cat /proc/uptime') -join ' ') }

# The device's time as logcat -T reads it.
function Get-DeviceTime { ((Invoke-Checked "date '+%m-%d %H:%M:%S.000'") -join '').Trim() }

# Waits until $Condition is true for Kestrel's state, which it gets as its argument. Returns the seconds that it waited,
# or -1 after the timeout. The seconds only bound a wait; no measure uses them.
function Wait-State([scriptblock]$Condition, [double]$TimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-VoiceState
        if ($state -and (& $Condition $state)) { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
}

# Waits until Kestrel is in the mode, with its voice session open, or closed for 'off'.
function Wait-Mode([string]$Mode, [double]$TimeoutSeconds) {
    $session = if ($Mode -eq 'off') { 'closed' } else { 'open' }
    Wait-State ({ param($State) $State.mode -eq $Mode -and $State.session -eq $session }.GetNewClosure()) $TimeoutSeconds
}

function Format-Wait([double]$Seconds) { if ($Seconds -lt 0) { 'failed' } else { 'ok' } }

# Whether Kestrel's accessibility service is on now, for the lines of the battery and memory measures, because its own
# work counts in Kestrel's use (research r10, gap 6).
function Get-KestrelServiceState {
    $services = Get-SecureSetting -Serial $Serial -Name enabled_accessibility_services
    if (@("$services" -split ':') -contains $service) { 'on' } else { 'off' }
}

# Turns Kestrel off, which it also stores, and waits until its voice session has closed.
function Stop-Kestrel {
    Invoke-Hook voice @{ Send = 'off' } | Out-Null
    if ((Wait-Mode off 30) -lt 0) { throw 'Kestrel did not turn off within 30 s.' }
}

# Turns Kestrel on with its own microphone, from the front, where Android lets it start its microphone service, and goes
# back to the home screen. Loading the models takes up to a minute on the A71.
function Start-Kestrel {
    Invoke-Hook voice-clip @{ Clear = $true } | Out-Null
    $state = Get-VoiceState
    if ($state -and $state.mode -eq 'paused') { Invoke-Hook voice @{ Send = 'resume' } | Out-Null }
    if (-not $state -or $state.mode -eq 'off') {
        Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
    }
    if ((Wait-Mode listening 120) -lt 0) { throw 'Kestrel did not start listening within 120 s.' }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

# Kestrel listens, says nothing and waits for no task, so that a try starts from the same state each time.
function Wait-Quiet {
    $state = Get-VoiceState
    if (-not $state -or $state.mode -ne 'listening') { Start-Kestrel }
    # "Kestrel, stop" ends a reply, cancels a task and ends a wait for a task (design spec, section 8.4). The hook's
    # transcript does not pass the session's pipeline, so the counts of the tries do not see it.
    Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, stop.'; Named = $true } | Out-Null
    Start-Sleep -Seconds 1
    if ((Wait-State { param($State) $State.work -eq 'idle' -and $State.speaking -eq 'false' } 40) -lt 0) {
        throw 'Kestrel did not come to rest within 40 s.'
    }
}

# The riids of Kestrel's recordings that 'dumpsys audio' lists now.
function Get-KestrelRecording { Get-RecordingId -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel }

# Waits up to 5 s until Kestrel's newest recording is new and not silenced. Returns nothing then, and otherwise why not.
function Get-RecordingProblem([string[]]$Before) {
    $state = 'none'
    for ($i = 0; $i -lt 10; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $state = Get-NewRecordingState -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel -Before $Before
        if ($state -eq 'not silenced') { return }
    }
    if ($state -eq 'silenced') { 'Android silenced the microphone' } else { 'no new recording of Kestrel''s showed within 5 s' }
}

# Kestrel's latest reaction from voice-times, if it came after $Since (milliseconds on the phone's clock): 'wait 640 ms
# after the end of speech', or $null.
function Get-Reaction([long]$Since, [string]$Kind = '') {
    $mark = ConvertFrom-TimeMark -Text ((Get-Times)['reacted'])
    if (-not $mark -or $mark.At -le $Since -or ($Kind -and $mark.Kind -ne $Kind)) { return $null }
    # The latency counts from the latest transcribed speech, whatever caused the reaction, so it counts only when that
    # speech ended in this try.
    $after = if ($null -ne $mark.SinceSpeech -and $mark.At - $mark.SinceSpeech -gt $Since) { "$($mark.SinceSpeech) ms after the end of the speech" } else { 'with no transcribed speech in this try' }
    "$($mark.Kind) $after"
}

# What the script has still to put back. Each restore enters the list before its change, and leaves it when it worked.
# $ByHand says how to put it back by hand, for the warning at the end.
$pending = [System.Collections.Generic.List[object]]::new()

function Register-Restore([string]$What, [string]$ByHand, $Data, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Data = $Data; Action = $Action; Reason = 'the session ended before it' }
    $pending.Add($restore)
    $restore
}

# Runs a restore that is still pending, and keeps it pending, with the reason, when it fails.
function Complete-Restore($Restore) {
    if (-not $pending.Contains($Restore)) { return }
    try {
        & $Restore.Action $Restore.Data | Out-Null
        $pending.Remove($Restore) | Out-Null
    } catch {
        $Restore.Reason = $_.Exception.Message
    }
}

# Runs the restores in order, each in the finally block of the one before, so that a stop such as Ctrl+C during one of
# them still runs the others.
function Invoke-Restore([object[]]$Restores, [int]$Index = 0) {
    if ($Index -ge $Restores.Count) { return }
    try {
        Complete-Restore $Restores[$Index]
    } finally {
        Invoke-Restore $Restores ($Index + 1)
    }
}

# Before the first step that runs: the restores that every session needs, and a screen that stays on, because Kestrel
# records only while the screen is on and the phone unlocked. Registered first, so that they come last.
function Initialize-Session {
    if ($script:prepared) { return }
    $script:prepared = $true
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" $null { Stop-Kestrel } | Out-Null
    Register-Restore "the hooks' tasks" "Run: scripts\hook.ps1 -Serial $Serial voice-task -Clear" $null {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
    } | Out-Null
    $stayOn = ((Invoke-Checked 'settings get global stay_on_while_plugged_in') -join '').Trim()
    $byHand = "Run: & '$adb' -s $Serial shell settings put global stay_on_while_plugged_in $stayOn"
    Register-Restore 'the setting "Stay awake"' $byHand $stayOn {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $Data" }
        Invoke-Checked $command | Out-Null
    } | Out-Null
    Invoke-Checked 'svc power stayon true' | Out-Null
    $script:sessionStart = Get-DeviceTime
    Request-Owner 'The phone''s screen now stays on while the phone is plugged in. Keep the phone unlocked during the whole session, unless a step asks otherwise. Press Enter to go on.' 'go' | Out-Null
}

# Runs a command on the device once, for a command that a repeat would do twice, such as 'am crash': Android does not
# restart an app that crashed twice within 2 minutes. The first echo gives the marker a line of its own, also after
# output without a final line break (Invoke-SettingsCommand). It runs only on the device that Assert-TestDevice accepted.
function Invoke-Once([string]$Command) {
    $ErrorActionPreference = 'Continue'
    $output = @(& $adb -s $Serial shell (Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo && echo kestrel-end") 2>&1)
    if ("$($output | Select-Object -Last 1)".Trim() -ne 'kestrel-end') { throw "Could not run '$Command' on $Serial." }
}

# Waits up to 30 s until Kestrel sees its accessibility service 'on' or 'off', and returns whether it did.
function Wait-KestrelService([string]$State) {
    for ($i = 0; $i -lt 30; $i++) {
        if ((Invoke-Hook state) -like "screen=$State*") { return $true }
        Start-Sleep -Seconds 1
    }
    $false
}

# The accessibility settings, which a step changes and puts back.
function Register-AccessibilityRestore {
    $saved = Get-AccessibilitySettings -Serial $Serial
    Register-Restore 'the accessibility settings' 'Put back the accessibility services, the shortcut and the button in Settings > Accessibility.' $saved {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $Data)) { throw 'see the warning above' }
    }
}

# The enabled accessibility services of the saved settings, without Kestrel's.
function Get-OtherServices($Saved) {
    $services = if ($Saved.Contains('enabled_accessibility_services')) { $Saved['enabled_accessibility_services'] } else { 'null' }
    @(@("$services" -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service })
}

# The command that enables the accessibility services $Services, or deletes the setting when there are none. The list is
# quoted for the device's shell.
function Get-ServicesCommand([string[]]$Services) {
    if ($Services) { "settings put secure enabled_accessibility_services '$($Services -join ':')'" } else { 'settings delete secure enabled_accessibility_services' }
}

function Get-KestrelPss { Get-TotalPss -Dump @(Invoke-Checked 'dumpsys meminfo dev.kestrel') }

function Get-KestrelMemory { Format-MemoryCategories (Get-MemoryCategories -Dump @(Invoke-Checked 'dumpsys meminfo dev.kestrel')) }

function Get-KestrelProcess { ((Invoke-Checked 'pidof dev.kestrel || true') -join '').Trim() }

# The answer of the hook voice-choice: the stored voice, whether Kokoro was chosen and Piper spoke instead the last time
# that a voice session opened because Kokoro did not load, Kokoro's real-time factor on the phone when Kestrel measured
# it above 1, and the factor that Kestrel measured. -Voice kokoro forgets the measure.
function Get-VoiceChoice([hashtable]$With = @{}) {
    $text = Invoke-Hook voice-choice $With
    if ($text -notmatch '^voice=(\w+) speed=([\d.]+) kokoroFailed=(true|false) kokoroSlow=([\d.]+|none) kokoroSpeed=([\d.]+|none)$') { throw 'Unexpected answer of voice-choice.' }
    [pscustomobject]@{ Voice = $Matches[1]; KokoroFailed = $Matches[3] -eq 'true'; KokoroSlow = $Matches[4]; KokoroSpeed = $Matches[5] }
}

# In a dry run, the owner's taps on Kestrel's tile: the tile goes into Quick Settings through SystemUI once, with a
# restore, and 'cmd statusbar click-tile' taps it while Quick Settings is open, as listen-check.ps1's tile check does.
$tileComponent = 'dev.kestrel/dev.kestrel.app.listen.KestrelTileService'
$tileRestore = $null
function Add-DryRunTile {
    if ($script:tileRestore -or (Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles) -match 'KestrelTileService') { return }
    # SystemUI removes only a tile that it has made, so the restore removes the tile that this run added.
    $script:tileRestore = Register-Restore "Quick Settings' tiles" "Run: & '$adb' -s $Serial shell cmd statusbar remove-tile $tileComponent" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "cmd statusbar remove-tile $tileComponent" | Out-Null
    }
    Invoke-Checked "cmd statusbar add-tile $tileComponent" | Out-Null
}

function Invoke-DryRunTileTap {
    Add-DryRunTile
    Invoke-Checked 'cmd statusbar expand-settings' | Out-Null
    # SystemUI makes and binds the tile a moment after it lists it, and a tap before that is lost.
    Start-Sleep -Seconds 5
    Invoke-Once "cmd statusbar click-tile $tileComponent"
    Start-Sleep -Seconds 1
    Invoke-Checked 'cmd statusbar collapse' | Out-Null
}

function Invoke-InstallStep {
    # On Android 13 an update ends Kestrel's process with the reason of the owner's stop, so Kestrel would stay off after it.
    try { Stop-Kestrel } catch { Write-Host 'Kestrel did not answer, so it is probably not installed yet.' }
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 failed.' }
    # With Kokoro's fp32 model, which Kestrel uses, and its int8 model, which only KokoroModelsTest loads. After
    # a71-commands.ps1's install, only these are new.
    & "$PSScriptRoot\push-models.ps1" -Serial $Serial -Kokoro
    # After a new install Kestrel has neither permission, and without the microphone it turns itself off at the start
    # (design spec, section 14.6). Both stay granted after the session, as after scripts\voice-test.ps1.
    Invoke-Checked 'pm grant dev.kestrel android.permission.RECORD_AUDIO' | Out-Null
    Invoke-Checked 'pm grant dev.kestrel android.permission.POST_NOTIFICATIONS' | Out-Null
    Add-Result install 'Kestrel installed, the speech models copied with both of Kokoro''s models, and the microphone and notification permissions granted'
    Start-Kestrel
    Add-Result install 'Kestrel started listening when the session started it'
    Stop-Kestrel
}

function Invoke-VoiceTestStep {
    if ($DryRun) {
        Add-Result voice-tests 'skipped in a dry run; the plan runs scripts\voice-test.ps1 itself'
        return
    }
    $script:gradleUsed = $true
    # An earlier run's results, such as the emulator's, must never count as this phone's, also when voice-test.ps1 fails
    # before it deletes them itself.
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
    # The tests log only measurements, never what was said, and voice-test.ps1 prints those lines.
    $lines = @(& "$PSScriptRoot\voice-test.ps1" -Serial $Serial *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    foreach ($line in $lines | Where-Object { $_ -match '^Voice tests: |KestrelVoiceTest' }) {
        Add-Result voice-tests ($line -replace '^.*KestrelVoiceTest\s*:\s*', '')
    }
    if ($code -ne 0) { Add-Result voice-tests "ended with exit code $code; the results are in app\build\outputs\androidTest-results\connected" }
    # By name, because the next run of the device tests deletes the result files. Only the names of classes and tests.
    foreach ($test in Get-UnpassedTests -Folder $results) { Add-Result voice-tests "did not pass: $test" }
    foreach ($class in 'ListeningServiceTest', 'KeywordTuningTest', 'KokoroModelsTest', 'SynthesizerTest') {
        Add-Result voice-tests "${class}: $(if (Test-ClassPassed -Folder $results -Class $class) { 'passed' } else { 'did not pass' })"
    }
    # connected-test.ps1 ends with 'am force-stop', which leaves a record of the owner's stop, so a restore keeps Kestrel
    # off; the session starts it itself.
    Start-Kestrel
    Stop-Kestrel
}

# Kestrel's logged failures in the device's log since $Since, a time as logcat -T reads it, or in the whole log.
function Add-Failures([string]$StepName, [string]$Since) {
    $command = if ($Since) { "logcat -d -T '$Since' -s Kestrel:W" } else { 'logcat -d -s Kestrel:W' }
    $failures = @(Get-FailureDescriptions -Log @(Invoke-Checked $command))
    if ($failures.Count -eq 0) {
        Add-Result $StepName 'Kestrel logged no failure in this part of the device''s log'
        return
    }
    foreach ($failure in $failures) { Add-Result $StepName "$($failure.Count) x ""$($failure.What) failed"" ($($failure.Class))" }
}

# Right after the voice tests, whose script cleared the log at its start, and before listen-check, whose notification
# check makes Android refuse a start in the background on purpose (analysis, item 4).
function Invoke-FailuresStep { Add-Failures failures $null }

# From the end of listen-check, or from the session's start when listen-check did not run in this run.
function Invoke-LateFailuresStep {
    $since = if ($script:afterListenCheck) { $script:afterListenCheck } else { $script:sessionStart }
    Add-Failures failures-late $since
}

function Invoke-ListenCheckStep {
    # Only the last byte of the dump: whether One UI's 'dumpsys accessibility' ends without a line break (analysis,
    # item 3). No text of the dump is read.
    $ends = Test-EndsWithLineBreak -Answer @(Invoke-Checked 'dumpsys accessibility | tail -c 1 | od -An -tx1')
    Add-Result listen-check "dumpsys accessibility ends with a line break: $(if ($null -eq $ends) { 'no byte read' } elseif ($ends) { 'yes' } else { 'no' })"
    # The notification check reads One UI's shade with uiautomator dump, which also holds the owner's notifications;
    # listen-check.ps1 deletes the dump after each read and never prints it.
    if (-not $DryRun -and (Request-Owner 'Next comes listen-check, about 10 minutes. Do not touch the phone while it runs: it opens and closes the notification shade, Quick Settings and Kestrel''s settings by itself, and it restarts the phone. After the restart, unlock the phone as soon as it asks for your PIN, without waiting for this window, and do nothing else. When Kestrel listens again, this window asks you once to check that the phone is unlocked and to press Enter; the time of the restart is read on the phone before that, so it does not depend on your Enter. Press Enter to start, or s to skip.') -eq 'skip') {
        Add-Result listen-check 'skipped'
        return
    }
    $lines = @(& "$PSScriptRoot\listen-check.ps1" -Serial $Serial -Reboot:(-not $DryRun) *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    # listen-check's restart and role checks end Kestrel's process with 'am crash'; its end is no earlier than the last.
    Write-SessionCrash -Path $stateFile -Serial $Serial -Time (Get-Date)
    $script:afterListenCheck = Get-DeviceTime
    # Each check's line holds its name, ok, failed or skipped, and what it measured on the device.
    foreach ($line in $lines | Where-Object { $_ -match '^[a-z]+\s+(ok|failed|skipped)\b' }) { Add-Result listen-check $line }
    if ($code -ne 0) { Add-Result listen-check "ended with exit code $code" }
    Request-Owner 'If Kestrel''s notice that its accessibility service is off is in the notification shade, swipe it away. Press Enter after it.' 'go' | Out-Null
}

# What Android 13 and One UI do to Kestrel's microphone service in the background after its accessibility service is
# turned off, without and with the battery restriction (analysis, items 1 and 2): Android's record of the service, which
# every version prints as 'isForeground=...', Kestrel's own view of it through Android's list of its running services,
# and when Kestrel turned itself off and why. The times come from the phone: the device's uptime when the service went
# off, and Kestrel's own time of its turn-off.
function Invoke-BatteryRulesStep {
    # 'allow', also for an op that has no entry ('No operations.'), or 'ignore'; never 'default', which Android counts as
    # restricted (prototype P2).
    $before = Get-BackgroundOpRestore -Answer @(Invoke-Checked 'cmd appops get dev.kestrel RUN_ANY_IN_BACKGROUND')
    $opRestore = Register-Restore "Kestrel's battery restriction" "Run: & '$adb' -s $Serial shell cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($before -eq 'ignore') { 'ignore' } else { 'allow' })" $before {
        param($Data)
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($Data -eq 'ignore') { 'ignore' } else { 'allow' })" | Out-Null
        $now = Get-AppOpMode -Answer @(Invoke-Checked 'cmd appops get dev.kestrel RUN_ANY_IN_BACKGROUND') -Op RUN_ANY_IN_BACKGROUND
        # Android may drop an entry that is set to the op's normal mode, and then answers 'No operations.'.
        if ($now -ne $Data -and -not ($null -eq $now -and $Data -eq 'allow')) { throw "the op reads '$now', not '$Data'" }
    }
    $accessibilityRestore = Register-AccessibilityRestore
    try {
        $others = @(Get-OtherServices $accessibilityRestore.Data)
        $seconds = [int](180 * $minuteScale)
        foreach ($restricted in $false, $true) {
            $label = if ($restricted) { 'with the battery restriction' } else { 'without the battery restriction' }
            Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($restricted) { 'ignore' } else { 'allow' })" | Out-Null
            Invoke-Checked (Get-ServicesCommand (@($others) + $service)) | Out-Null
            Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
            Start-Kestrel
            $start = Get-ForegroundRecord -Dump @(Invoke-Checked 'dumpsys activity services dev.kestrel')
            Invoke-Checked (Get-ServicesCommand $others) | Out-Null
            $off = Get-DeviceUptime
            Write-Host "Kestrel listens in the background, $label, and its accessibility service is off now. Do not touch the phone for $([math]::Round($seconds / 60, 1)) minutes: it stays unlocked with the home screen on. The script checks every 5 s by itself."
            $lost = $null
            $record = $start
            $view = $null
            $watch = [Diagnostics.Stopwatch]::StartNew()
            while ($watch.Elapsed.TotalSeconds -lt $seconds) {
                Start-Sleep -Seconds 5
                $record = Get-ForegroundRecord -Dump @(Invoke-Checked 'dumpsys activity services dev.kestrel')
                $view = Get-Times
                if ($null -eq $lost -and $record.Foreground -ne 'true') { $lost = (Get-DeviceUptime) - $off }
                $stopped = ConvertFrom-TimeMark -Text $view['stopped']
                if ($stopped -and $stopped.At -gt $off) { break }
            }
            $state = Get-VoiceState
            $stopped = if ($view) { ConvertFrom-TimeMark -Text $view['stopped'] }
            $turnOff = if ($stopped -and $stopped.At -gt $off) { "Kestrel turned itself off for $($stopped.Kind) $($stopped.At - $off) ms after its accessibility service went off" } else { "Kestrel did not turn itself off; its mode is $(if ($state) { $state.mode } else { 'unknown' }), the microphone silenced: $(if ($state) { $state.micBusy } else { 'unknown' })" }
            $foreground = if ($view) { "foreground=$($view['foreground']) demoted=$($view['demoted'])" } else { 'not read' }
            $android = "Android's record at the start: listed $($start.Listed), isForeground $($start.Foreground), types $($start.Types); at the end: listed $($record.Listed), isForeground $($record.Foreground), types $($record.Types)"
            $lostText = if ($null -ne $lost) { "Android's record lost the foreground within $lost ms" } else { 'Android''s record kept the foreground' }
            $notice = Read-OwnerAnswer 'Pull down the notification shade and look for Kestrel''s notices. Type b if one says that Kestrel''s battery use is restricted, a if one says only that its accessibility service is off, o for another text of Kestrel''s, or n for none. Then close the shade.' 'n'
            Add-Result battery-rules "${label}: $android; $lostText; Kestrel's view at the end: $foreground; $turnOff; Kestrel's notice: $notice"
            Stop-Kestrel
        }
    } finally {
        Complete-Restore $accessibilityRestore
        Complete-Restore $opRestore
    }
}

# The accessibility shortcut, the accessibility button and the tile with Kestrel in the background (analysis, item 11):
# the time from Kestrel's own record of the event to its open voice session, and whether Android showed a dialog.
function Invoke-ActivationStep {
    $restore = Register-AccessibilityRestore
    try {
        # Kestrel's service must be on for the shortcut and the button; the owner's other services stay on. Android shows
        # its dialog about the shortcut only once, and this setting says that it was shown; the restore puts it back.
        Invoke-Checked (Get-ServicesCommand (@(Get-OtherServices $restore.Data) + $service)) | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
        Invoke-Checked "settings put secure accessibility_shortcut_target_service $service" | Out-Null
        Invoke-Checked "settings put secure accessibility_button_targets $service" | Out-Null
        Invoke-Checked 'settings put secure accessibility_shortcut_dialog_shown 1' | Out-Null
        $methods = [ordered]@{
            shortcut = 'Press and hold both volume keys for 3 s, once'
            button = 'Tap the accessibility button once, in the navigation bar or floating over the screen'
            tile = 'Swipe down to Quick Settings and tap Kestrel''s tile once; add the tile first if it is not there'
        }
        $sources = @{ shortcut = 'accessibility'; button = 'accessibility'; tile = 'tile' }
        # A dry run taps the tile itself, and skips the shortcut and the button, which need hands.
        if ($DryRun) { Add-DryRunTile }
        foreach ($name in $methods.Keys) {
            Stop-Kestrel
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            $before = ConvertFrom-TimeMark -Text ((Get-Times)['started'])
            $dryRunAnswer = if ($name -eq 'tile') { 'go' } else { 'skip' }
            if ((Request-Owner "Kestrel is off and in the background. $($methods[$name]). Then press Enter at once, whether or not the phone reacts. Kestrel shows nothing on the screen when it starts, only its notification. Or press s to skip." $dryRunAnswer) -eq 'skip') {
                Add-Result activation "${name}: skipped"
                continue
            }
            if ($DryRun) { Invoke-DryRunTileTap }
            $on = Wait-Mode listening 120
            $times = Get-Times
            $started = ConvertFrom-TimeMark -Text $times['started']
            $opened = [long]$times['opened']
            $timed = if ($on -lt 0) {
                'did not start listening within 120 s'
            } elseif (-not $started -or ($before -and $started.At -le $before.At) -or $started.Kind -ne $sources[$name]) {
                "listening, but Kestrel recorded no event of the $($sources[$name]) for this start"
            } else {
                "listening $($opened - $started.At) ms after the event of the $($started.Kind), timed on the phone"
            }
            $dialog = Read-OwnerAnswer 'Did Android show a dialog or a message before Kestrel started, for example about the shortcut? Type y or n.' 'n'
            Request-Owner "Now do it once more: $($methods[$name]). Kestrel should pause; its notification then says that Kestrel is paused. Then press Enter at once, whether or not the phone reacts." 'go' | Out-Null
            if ($DryRun) { Invoke-DryRunTileTap }
            $paused = Wait-Mode paused 15
            Add-Result activation "${name}: $timed; a dialog: $dialog; the second time paused it: $(Format-Wait $paused)"
        }
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

function Get-AssistantHolders { Get-RoleHolders ((Invoke-Checked "cmd role get-role-holders $assistantRole") -join ' ') }

# With only the assistant role, without Kestrel's accessibility service and with Kestrel in the background (analysis,
# item 5): a start through the hook, "Kestrel, open settings", and the restore through the assistant service after
# Android ended Kestrel's process (design spec, sections 8.5, 8.6 and 14). None needs the owner's hands.
function Invoke-AssistantStep {
    $holders = @(Get-AssistantHolders)
    $settings = [ordered]@{}
    foreach ($name in 'assistant', 'voice_interaction_service', 'voice_recognition_service') { $settings[$name] = Get-SecureSetting -Serial $Serial -Name $name }
    $byHand = "In Settings > Apps > Default apps > Digital assistant app, choose $(if ($holders) { $holders -join ', ' } else { 'None' }) again."
    $restore = Register-Restore 'the assistant role and its settings' $byHand @{ Holders = $holders; Settings = $settings } {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if ($Data.Holders -notcontains 'dev.kestrel') { Invoke-Checked "cmd role remove-role-holder $assistantRole dev.kestrel" | Out-Null }
        foreach ($holder in $Data.Holders) { Invoke-Checked "cmd role add-role-holder $assistantRole $holder" | Out-Null }
        # Android points the three settings at the holder's services a moment after the change, as voice-test.ps1 knows.
        Start-Sleep -Seconds 2
        foreach ($name in $Data.Settings.Keys) {
            if (-not (Set-SecureSetting -Serial $Serial -Name $name -Value $Data.Settings[$name])) { throw "the setting $name differs from before" }
        }
        if ((@(Get-AssistantHolders | Sort-Object) -join ',') -ne (@($Data.Holders | Sort-Object) -join ',')) {
            throw 'the holders of the assistant role differ from before'
        }
    }
    $accessibility = Register-AccessibilityRestore
    try {
        Invoke-Checked "cmd role add-role-holder $assistantRole dev.kestrel" | Out-Null
        if (@(Get-AssistantHolders) -notcontains 'dev.kestrel') {
            Add-Result assistant 'failed: Android did not make Kestrel the assistant'
            return
        }
        Stop-Kestrel
        Invoke-Checked (Get-ServicesCommand @(Get-OtherServices $accessibility.Data)) | Out-Null
        if (-not (Wait-KestrelService off)) { throw 'Kestrel''s accessibility service did not turn off within 30 s.' }
        Write-Host 'Do not touch the phone for about 5 minutes: the script works on it by itself, and it shows Kestrel''s settings for a moment. Kestrel may speak; you need not answer.'
        Invoke-RoleAloneChecks
        Stop-Kestrel
    } finally {
        Complete-Restore $accessibility
        Complete-Restore $restore
    }
}

function Invoke-RoleAloneChecks {
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # Not the exemption of an app that was in front a moment ago (prototype P2).
    Start-Sleep -Seconds 10
    $before = Get-KestrelRecording
    Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
    $on = Wait-Mode listening 120
    if ($on -lt 0) {
        Add-Result assistant 'the role alone: a start through the hook in the background: failed: no listening within 120 s'
        return
    }
    $problem = Get-RecordingProblem $before
    Add-Result assistant "the role alone: a start through the hook in the background: listening, $(if ($problem) { "but $problem" } else { 'not silenced' })"
    # Kestrel acts on a request only while the screen is on.
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep -Seconds 2
    $since = Get-DeviceTime
    $asked = [long](Get-Times)['settingsAsked']
    Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, open settings.'; Named = $true } | Out-Null
    $opened = $null
    $times = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($null -eq $opened -and $watch.Elapsed.TotalSeconds -lt 10) {
        Start-Sleep -Milliseconds 500
        $times = Get-Times
        if ([long]$times['settingsAsked'] -gt $asked -and [long]$times['settingsResumed'] -ge [long]$times['settingsAsked']) {
            $opened = [long]$times['settingsResumed'] - [long]$times['settingsAsked']
        }
    }
    # Only a count of Android's lines about blocked starts in the background that name Kestrel, never their text.
    $blocked = @(Invoke-Checked "logcat -d -T '$since' -s ActivityTaskManager:W" | Where-Object { $_ -match 'Background activity start' -and $_ -match 'dev\.kestrel' }).Count
    # Kestrel's own verdict (its reaction settings-blocked after its 2 s) and its notification "Open Kestrel's settings"
    # (ProblemNotifications.SETTINGS_ID 7104), both read on the phone: of the notifications only a count leaves it.
    $askedNow = if ($times) { [long]$times['settingsAsked'] } else { 0 }
    $reaction = if ($times) { ConvertFrom-TimeMark -Text $times['reacted'] }
    $verdict = $askedNow -gt $asked -and $reaction -and $reaction.Kind -eq 'settings-blocked' -and $reaction.At -ge $askedNow
    # Only the posted notifications: their keys, such as 0|dev.kestrel|7104|null|10334, without the dump's archive.
    $offered = ((Invoke-Checked "cmd notification list | grep -c '|dev.kestrel|7104|' || true") -join '').Trim()
    $text = if ($null -ne $opened -and -not $verdict) {
        "Kestrel's settings came to the front $opened ms after Kestrel asked for them, timed on the phone"
    } elseif ($null -ne $opened) {
        "Kestrel's settings came to the front $opened ms after Kestrel asked for them, but only after Kestrel had given up after $($reaction.At - $askedNow) ms and said that Android blocked them"
    } elseif ($verdict) {
        "the settings did not come to the front within 10 s; Kestrel said that Android blocked them $($reaction.At - $askedNow) ms after it asked, and the records of its notification that opens them: $(if ($offered) { $offered } else { 'none read' })"
    } elseif ($askedNow -gt $asked) {
        'Kestrel asked for its settings, and they did not come to the front within 10 s, and Kestrel recorded no verdict'
    } else {
        'Kestrel did not ask for its settings within 10 s'
    }
    Add-Result assistant "the role alone: ""Kestrel, open settings"": $text; Android's log has $blocked blocked starts in the background of Kestrel's"
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # Kestrel stores Listening a moment after its voice session opened; a crash before that write would leave Off stored.
    Start-Sleep -Seconds 2
    $wait = Get-CrashWait -LastCrash (Read-SessionCrash -Path $stateFile -Serial $Serial) -Now (Get-Date)
    if ($wait -gt 0) {
        Write-Host "Waiting $wait s, so that 2 minutes have passed since the session's last crash of Kestrel; Android does not restart an app that crashed twice within 2 minutes."
        Start-Sleep -Seconds $wait
    }
    $recording = Get-KestrelRecording
    $process = Get-KestrelProcess
    Write-SessionCrash -Path $stateFile -Serial $Serial -Time (Get-Date)
    Invoke-Once 'am crash dev.kestrel'
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $new = ''
    while ($watch.Elapsed.TotalSeconds -lt 60) {
        Start-Sleep -Seconds 1
        $new = Get-KestrelProcess
        if ($new -and $new -ne $process) { break }
    }
    if (-not $new -or $new -eq $process) {
        Add-Result assistant 'the role alone: the restore through the assistant service: failed: Kestrel''s process did not start again within 60 s of its end'
        return
    }
    $again = Wait-Mode listening 120
    $problem = if ($again -ge 0) { Get-RecordingProblem $recording }
    $text = if ($again -lt 0) { 'failed: no listening within 120 s of the new start' } elseif ($problem) { "listening again, but $problem" } else { 'listening again, not silenced' }
    Add-Result assistant "the role alone: the restore through the assistant service after Android ended Kestrel's process: $text"
}

# Reads voice-route once a second for $Seconds seconds, and returns the last answer and whether any answer had the
# recording routed to a Bluetooth input.
function Watch-Route([int]$Seconds) {
    $last = $null
    $bluetooth = $false
    for ($i = 0; $i -lt $Seconds; $i++) {
        Start-Sleep -Seconds 1
        $last = ConvertFrom-HookPairs (Invoke-Hook voice-route)
        if ("$($last['routed'])" -in 'bluetooth-sco', 'ble-headset') { $bluetooth = $true }
    }
    [pscustomobject]@{ Last = $last; Bluetooth = $bluetooth }
}

function Format-Route($Route) {
    if (-not $Route.Last) { return 'no answer' }
    $parts = foreach ($name in 'earbud', 'microphone', 'holding', 'headset', 'linkedMillis', 'communication', 'routed', 'loudest') {
        if ($Route.Last.Contains($name)) { "$name=$($Route.Last[$name])" }
    }
    "$($parts -join ' '); routed to the earbuds at some second: $($Route.Bluetooth)"
}

# "Kestrel" once, and Kestrel's wait for a task, timed on the phone from the end of the speech; nothing is timed from
# Enter.
function Measure-Name([string]$Prompt) {
    $before = Get-Counts
    $since = [long](Get-Times)['now']
    if ((Request-Owner $Prompt 'go') -eq 'skip') { return 'skipped' }
    if ($DryRun) { Send-Heard '+Kestrel' }
    $reaction = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($null -eq $reaction -and $watch.Elapsed.TotalSeconds -lt 15) {
        Start-Sleep -Milliseconds 500
        $reaction = Get-Reaction $since 'wait'
    }
    $rise = Get-CountRise $before (Get-Counts)
    if ($reaction) { "Kestrel began to wait for a task: $reaction; counts: $rise" } else { "no wait for a task within 15 s; counts: $rise" }
}

# The earbud microphone with the owner's earbuds (analysis, item 6): where Android routes the recording, "Kestrel" into
# the earbuds, and the phone's microphone after the earbuds went away.
function Invoke-EarbudStep {
    Stop-Kestrel
    if ((Request-Owner 'Connect the Galaxy Buds to the phone and put them in your ears. Open Kestrel''s settings, the page "Listening and activation", and turn on "Use the earbud microphone". Press Enter when it is on, or s to skip.') -eq 'skip') {
        Add-Result earbuds 'skipped'
        return
    }
    $script:earbudsChanged = $true
    Start-Kestrel
    Write-Host 'Kestrel listens. Do not speak for 5 s; the script reads where Android sends the recording.'
    $route = Watch-Route 5
    Add-Result earbuds "the route in the 5 s after the start: $(Format-Route $route)"
    $music = Read-OwnerAnswer 'Start some music on the phone, so that it plays in the earbuds, and listen for a few seconds. Does it sound like a phone call, thin and muffled? Type y or n, or x if you cannot play music now.' 'x'
    $named = Measure-Name 'Stop the music. Put the phone under a pillow; it stays on and unlocked while it is plugged in, and you need not look at it. Say "Kestrel" once into the earbuds. Kestrel should say "Yes?" in the earbuds and wait; the script ends the wait itself. Then press Enter at once, whether or not you hear anything.'
    $heard = Watch-Route 1
    $inEarbuds = Read-OwnerAnswer 'Did you hear "Yes?" in the earbuds? Type y or n.' 'n'
    Add-Result earbuds """Kestrel"" into the earbuds: $named; the route afterwards: $(Format-Route $heard); ""Yes?"" in the earbuds: $inEarbuds; music sounded like a call: $music"
    Request-Owner 'Put the earbuds into their case, so that they disconnect, and take the phone from under the pillow. Press Enter then; the script waits until Kestrel records from the phone again.' 'go' | Out-Null
    # Up to 15 s, until Kestrel has given the earbuds up.
    $gone = $false
    for ($i = 0; $i -lt 15 -and -not $gone; $i++) {
        $gone = (ConvertFrom-HookPairs (Invoke-Hook voice-route))['holding'] -ne 'true'
        if (-not $gone) { Start-Sleep -Seconds 1 }
    }
    if (-not $gone) { Add-Result earbuds 'Kestrel still held the earbuds 15 s after they went into their case' }
    $phone = Measure-Name 'Say "Kestrel" once to the phone. Kestrel should say "Yes?" from the phone and wait; the script ends the wait itself. Then press Enter at once, whether or not the phone reacts.'
    Add-Result earbuds "after the earbuds went away, ""Kestrel"" to the phone: $phone"
    Stop-Kestrel
    Request-Owner 'Turn "Use the earbud microphone" off again in Kestrel''s settings, unless you want to keep it. Press Enter after it.' 'go' | Out-Null
}

# One measure of the voice step or the spotter step: $Tries times, $Prepare brings Kestrel into the state that each try
# needs, the owner says $Say once and presses Enter, and the script waits up to $TimeoutSeconds until $Reacted, which gets
# the counts from before the try and Kestrel's state, is true. For each try it records the rise of the counts on the
# phone (the spotter's hits by keyword, the transcribed and named utterances, how each was read) and Kestrel's latest
# reaction since the try began, with its time after the end of the speech, all from the phone; nothing is timed from
# Enter. A dry run makes one try, in which it sends $DryRunHeard (see Send-Heard) in place of the owner. A failure, such
# as a Kestrel that does not come to rest, costs only this measure's lines.
function Measure-Tries(
    [string]$StepName,
    [string]$Name,
    [string]$Say,
    [int]$Tries,
    [scriptblock]$Prepare,
    [scriptblock]$Reacted,
    [double]$TimeoutSeconds = 10,
    [string[]]$DryRunHeard = @()
) {
    if ($DryRun) { $Tries = 1 }
    $hits = 0
    $done = 0
    $script:triesReacted = $null
    try {
        for ($try = 1; $try -le $Tries; $try++) {
            & $Prepare
            $counts = Get-Counts
            $since = [long](Get-Times)['now']
            if ((Request-Owner "$Say  (try $try of ${Tries}: say it once, then press Enter at once, whether or not the phone reacts; s skips the rest)" 'go') -eq 'skip') { break }
            if ($DryRun) { Send-Heard $DryRunHeard }
            $done++
            $seen = $false
            $watch = [Diagnostics.Stopwatch]::StartNew()
            do {
                $state = Get-VoiceState
                $seen = $state -and (& $Reacted $counts $state)
                if (-not $seen) { Start-Sleep -Milliseconds 250 }
            } while (-not $seen -and $watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
            if ($seen) { $hits++ }
            # The pipeline's counts come once the utterance is transcribed, a moment after the reaction.
            Start-Sleep -Seconds 1
            $reaction = Get-Reaction $since
            Add-Result $StepName "${Name}, try ${try}: $(if ($seen) { 'reacted' } else { "no reaction within $TimeoutSeconds s" }); Kestrel's latest reaction: $(if ($reaction) { $reaction } else { 'none' }); counts: $(Get-CountRise $counts (Get-Counts))"
        }
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
        Add-Result $StepName "${Name}: failed after $hits of $done tries: $($_.Exception.Message)"
        return
    }
    Add-Result $StepName "${Name}: $(if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" })"
    if ($done -gt 0) { $script:triesReacted = $hits }
}

# Generated clips without any of Kestrel's keywords, from the corpus's conversation and confusable lines, about 2 minutes
# of speech, which the step spotter plays for each setting of the keyword spotter.
function Get-PlainSpeechClips {
    $clips = [System.Collections.Generic.List[object]]::new()
    $total = 0.0
    foreach ($row in Read-Corpus -Path $corpusFile) {
        if ($row.Group -notin 'conversation', 'confusable' -or $row.Text -match '(?i)kestrel|stop|cancel') { continue }
        $file = Join-Path $generatedClips "$($row.Id).wav"
        if (-not (Test-Path -LiteralPath $file)) { continue }
        # 16 kHz, 16-bit mono after a header of 44 bytes.
        $seconds = ((Get-Item -LiteralPath $file).Length - 44) / 32000
        if ($total + $seconds -gt 120) { break }
        $total += $seconds
        $clips.Add([pscustomobject]@{ Id = $row.Id; File = $file; Seconds = $seconds })
    }
    , $clips.ToArray()
}

# Plays $Clips through this computer's speaker while Kestrel is paused, so that nothing that the clips say runs, and
# returns the rise of the spotter's hits of all three keywords: false hits, because no clip holds a keyword (analysis,
# item 8). A dry run plays nothing and returns 0.
function Measure-FalseHits([string]$Setting, [object[]]$Clips) {
    if ($DryRun) {
        Add-Result spotter "${Setting}: no speech played in a dry run"
        return 0
    }
    if ($Clips.Count -eq 0) {
        Add-Result spotter "${Setting}: no false hits measured, because corpus\generated\piper has no clips of conversation or confusable lines; run scripts\make-corpus.ps1 first"
        return $null
    }
    Write-Host 'Kestrel is paused now, and this computer plays about 2 minutes of speech without Kestrel''s name. Do not speak, and do not touch the phone.'
    Invoke-Hook voice @{ Send = 'pause' } | Out-Null
    Wait-Mode paused 10 | Out-Null
    $before = Get-Counts
    $seconds = 0.0
    foreach ($clip in $Clips) {
        # A false "Kestrel, wake up" would make Kestrel listen, and act on the clips after it.
        $state = Get-VoiceState
        if ($state -and $state.mode -ne 'paused') { Invoke-Hook voice @{ Send = 'pause' } | Out-Null }
        $player = [System.Media.SoundPlayer]::new($clip.File)
        try { $player.PlaySync() } finally { $player.Dispose() }
        $seconds += $clip.Seconds
    }
    Start-Sleep -Seconds 2
    $after = Get-Counts
    $rise = 0
    foreach ($name in 'hit-kestrel', 'hit-stop', 'hit-cancel') {
        $rise += [long]$(if ($after.Contains($name)) { $after[$name] } else { 0 }) - [long]$(if ($before.Contains($name)) { $before[$name] } else { 0 })
    }
    Add-Result spotter "${Setting}: $rise false hits in $([math]::Round($seconds / 60, 1)) minutes of played speech without a keyword, with Kestrel paused; counts: $(Get-CountRise $before $after)"
    $rise
}

# "Hey Kestrel" with three settings of the keyword spotter (analysis, item 8): Kestrel's settings since plan 2A, and two
# that hear more. Each setting counts from Kestrel's next start; the default comes back at the end.
function Invoke-SpotterStep {
    $default = Invoke-Hook voice-spotter
    $tuningRestore = Register-Restore "the keyword spotter's setting" "Run: scripts\hook.ps1 -Serial $Serial voice-spotter -Boost 2 -Threshold 0.15" $null {
        Invoke-Hook voice-spotter @{ Boost = 2; Threshold = 0.15 } | Out-Null
    }
    $rounds = [System.Collections.Generic.List[object]]::new()
    try {
        if ((Request-Owner 'Next come three rounds, about 16 minutes, one for each setting of the keyword spotter, in a quiet room. In each round you say "Hey Kestrel" 5 times to the phone, through its own microphone; Kestrel should say "Yes?" and wait, and the script ends each wait itself. Then this computer''s speaker plays about 2 minutes of generated speech without Kestrel''s name while Kestrel is paused, and the phone shows nothing; do not speak then. Put the phone about 1 m from this computer''s speaker, and set the speaker as loud as a voice in the room. Press Enter to begin, or s to skip.' 'go') -eq 'skip') {
            Add-Result spotter 'skipped'
            return
        }
        $clips = Get-PlainSpeechClips
        $tunings = @(@{ Boost = 2; Threshold = 0.15 }, @{ Boost = 3; Threshold = 0.1 }, @{ Boost = 4; Threshold = 0.05 })
        if ($DryRun) { $tunings = @($tunings[0], $tunings[1]) }
        foreach ($tuning in $tunings) {
            $set = Invoke-Hook voice-spotter $tuning
            Stop-Kestrel
            Start-Kestrel
            # A process that ended since the hook would measure the default under this setting's name.
            $now = Invoke-Hook voice-spotter
            if ($now -ne $set) {
                Add-Result spotter "${set}: not measured, because Kestrel's process reads $now"
                continue
            }
            $quiet = { Wait-Quiet }
            Measure-Tries spotter "$set, ""Hey Kestrel""" 'Say "Hey Kestrel" once. Kestrel should say "Yes?" and wait; the script ends the wait itself.' 5 $quiet { param($Counts, $State) $State -and [int](Get-Counts)['waits'] -gt [int]$Counts['waits'] } 8 -DryRunHeard '+Hey Kestrel.'
            $reacted = $script:triesReacted
            $falseHits = Measure-FalseHits $set $clips
            $rounds.Add([pscustomobject]@{ Setting = $set; Default = $tuning.Boost -eq 2 -and $tuning.Threshold -eq 0.15; Hits = $reacted; FalseHits = $falseHits })
        }
    } finally {
        Complete-Restore $tuningRestore
        Stop-Kestrel
    }
    Add-Result spotter "the setting before the step: $default"
    $winner = Select-SpotterWinner @($rounds | Where-Object { $null -ne $_.Hits -and $null -ne $_.FalseHits })
    Add-Result spotter $(if ($winner) { "the setting that the rule keeps: $($winner.Setting) (the most reactions to your ""Hey Kestrel"" among the settings with at most twice the default's false hits plus 2; a tie goes to fewer false hits, then to the default)" } else { 'no setting chosen, because the default setting was not measured whole' })
}

# An interruption (analysis, item 8): Kestrel reads a long reply, the owner says "Kestrel", and after a pause "open the
# weather". This window tells the owner when to say each part, so that the pause is about $Pause s: the second prompt
# comes 0.6 s plus the pause after the first, for a "Kestrel" of about 0.6 s, so the pause is approximate; the phone
# times only Kestrel's reactions.
function Measure-Interruption([string]$Pause) {
    $name = "an interruption with a pause of about $Pause s"
    $tries = if ($DryRun) { 1 } else { 2 }
    $done = 0
    $hits = 0
    for ($try = 1; $try -le $tries; $try++) {
        Wait-Quiet
        $counts = Get-Counts
        $since = [long](Get-Times)['now']
        if ((Request-Owner "$name, try $try of ${tries}: when you press Enter, say ""Kestrel, read me the news"" once. Kestrel answers and reads a long reply. Then this window says ""Kestrel"": say it once, and Kestrel stops reading. When the window then says ""open the weather"", say that once. Press Enter to begin, or s to skip the rest." 'go') -eq 'skip') { break }
        $done++
        if ($DryRun) { Send-Heard '+Kestrel, read me the news' } else { Write-Host 'Say "Kestrel, read me the news" now.' }
        $speaking = Wait-State { param($State) $State.speaking -eq 'true' } 30
        if ($speaking -lt 0) {
            Add-Result voice "${name}, try ${try}: Kestrel did not begin its reply within 30 s; counts: $(Get-CountRise $counts (Get-Counts))"
            continue
        }
        Start-Sleep -Seconds 3
        if ($DryRun) { Send-Heard '+Kestrel' } else { Write-Host 'Say "Kestrel" now.' }
        Start-Sleep -Milliseconds ([int](([double]::Parse($Pause, [Globalization.CultureInfo]::InvariantCulture) + 0.6) * 1000))
        if ($DryRun) { Send-Heard 'open the weather' } else { Write-Host 'Say "open the weather" now.' }
        # The condition runs in Wait-State, called from here, so it reads $counts of this try.
        $tasks = Wait-State { param($State) $State -and [int](Get-Counts)['tasks'] -ge [int]$counts['tasks'] + 2 } 30
        if ($tasks -ge 0) { $hits++ }
        Start-Sleep -Seconds 1
        $reaction = Get-Reaction $since
        Add-Result voice "${name}, try ${try}: $(if ($tasks -ge 0) { 'both tasks ran' } else { 'the second task did not run within 30 s' }); Kestrel's latest reaction: $(if ($reaction) { $reaction } else { 'none' }); counts: $(Get-CountRise $counts (Get-Counts))"
    }
    Add-Result voice "${name}: $(if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" })"
}

function Invoke-VoiceStep {
    $quiet = { Wait-Quiet }
    if ((Request-Owner 'Next you talk to Kestrel through the phone''s own speaker and microphone, without earbuds, in a quiet room, for about 20 minutes. For each try the script shows what to say: say it once, as you would say it to Kestrel, and then press Enter at once, whether or not the phone reacts. Press Enter to begin, or s to skip.' 'go') -eq 'skip') {
        Add-Result voice 'skipped'
        return
    }
    $waited = { param($Counts, $State) $State -and [int](Get-Counts)['waits'] -gt [int]$Counts['waits'] }
    Measure-Tries voice '"Kestrel"' 'Say "Kestrel". Kestrel should say "Yes?" and wait; the script ends the wait itself.' 5 $quiet $waited 10 -DryRunHeard '+Kestrel'
    Measure-Tries voice '"Hey Kestrel"' 'Say "Hey Kestrel". Kestrel should say "Yes?" and wait; the script ends the wait itself.' 5 $quiet $waited 10 -DryRunHeard '+Hey Kestrel.'
    $paused = {
        Wait-Quiet
        Invoke-Hook voice @{ Send = 'pause' } | Out-Null
        Wait-Mode paused 10 | Out-Null
    }
    Measure-Tries voice '"Kestrel, wake up" while paused' 'Kestrel is paused. Say "Kestrel, wake up". Kestrel should listen again; its notification then says that it listens.' 5 $paused { param($Counts, $State) $Counts -and $State.mode -eq 'listening' } 10 -DryRunHeard '+Kestrel, wake up.'
    # Each task now takes a second and then reads a long reply without any of Kestrel's keywords, which the owner
    # interrupts. A pause shorter than the speech detector's 0.7 s joins the request to Kestrel's own voice, which the
    # pipeline drops after an interruption (analysis, item 8), so the pauses start at 1 s.
    $story = 'Here is a long reply, so that you can interrupt it. ' + ('The news of the day comes one sentence after another, at an even pace. ' * 6)
    Invoke-Hook voice-task @{ Seconds = 1; Reply = $story } | Out-Null
    try {
        foreach ($pause in '1', '1.5', '2') { Measure-Interruption $pause }
    } finally {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
    }
    Measure-Tries voice '"Stop Kestrel"' 'Say "Stop Kestrel". Kestrel should turn off; its notification then goes.' 3 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'off' } 10 -DryRunHeard 'Stop Kestrel.'
}

# Three starts and closes from the front with the stored voice: the peak PSS while each session opens, from samples of
# 'dumpsys meminfo' as fast as adb answers them, with 0.5 s between them, and the memory categories before, while
# listening and after the close (analysis, item 9), and whether one process ran them all. A short peak can fall between
# two samples, so the line gives their number and their mean interval.
function Measure-Starts([string]$Voice) {
    $process = Get-KestrelProcess
    for ($cycle = 1; $cycle -le 3; $cycle++) {
        $idle = Get-KestrelMemory
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
        $peak = 0
        $samples = 0
        $watch = [Diagnostics.Stopwatch]::StartNew()
        do {
            $pss = Get-KestrelPss
            $samples++
            if ($pss -gt $peak) { $peak = $pss }
            $state = Get-VoiceState
            $open = $state -and $state.mode -eq 'listening' -and $state.session -eq 'open'
            if (-not $open) { Start-Sleep -Milliseconds 500 }
        } while (-not $open -and $watch.Elapsed.TotalSeconds -lt 120)
        $interval = [math]::Round($watch.Elapsed.TotalSeconds / [math]::Max(1, $samples), 1)
        $listening = Get-KestrelMemory
        $choice = Get-VoiceChoice
        $fellBack = if ($Voice -ne 'kokoro') { '' } elseif ($choice.KokoroFailed) { '; Kokoro did not load, so Piper spoke' } elseif ($choice.KokoroSlow -ne 'none') { "; Kokoro's real-time factor on this phone is $($choice.KokoroSlow), so Piper spoke" } else { "; Kokoro spoke, with a real-time factor on this phone of $($choice.KokoroSpeed)" }
        Invoke-Hook voice @{ Send = 'off' } | Out-Null
        $closed = Wait-Mode off 60
        $after = Get-KestrelMemory
        Add-Result memory "$Voice, start ${cycle}: the session opened: $(if ($open) { 'ok' } else { 'failed' }); peak PSS while it opened $peak MB, of $samples samples about $interval s apart; before: $idle; while listening: $listening; after the close, which ended $(Format-Wait $closed): $after$fellBack; Kestrel's accessibility service: $(Get-KestrelServiceState)"
    }
    Add-Result memory "${Voice}: one process ran the three starts: $($process -and (Get-KestrelProcess) -eq $process)"
}

function Invoke-MemoryStep {
    Stop-Kestrel
    # In front, Android lets the hook start Kestrel's microphone service.
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $chosen = Get-VoiceChoice
    # Kestrel measures Kokoro's speed at the first start after the choice, at a known moment of the session.
    if ($chosen.Voice -eq 'kokoro') { Get-VoiceChoice @{ Voice = 'kokoro' } | Out-Null }
    Measure-Starts $chosen.Voice
    # Spec 5.3's memory target holds also for an owner who chooses Kokoro; Kestrel measures Kokoro's speed at its first
    # load on the phone, and Piper speaks where it is too slow.
    $voiceRestore = Register-Restore "Kestrel's voice" "Run: scripts\hook.ps1 -Serial $Serial voice-choice -Voice $($chosen.Voice), or choose the voice on Kestrel's page ""Voice and speech""." $chosen.Voice {
        param($Data)
        if ((Get-VoiceChoice @{ Voice = $Data }).Voice -ne $Data) { throw 'the stored voice differs from before' }
    }
    try {
        if ($chosen.Voice -ne 'kokoro') {
            Get-VoiceChoice @{ Voice = 'kokoro' } | Out-Null
            Measure-Starts kokoro
        }
    } finally {
        Complete-Restore $voiceRestore
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # A cold start: the tile starts the service in a new process, which must reach startForeground() in time. The script
    # ends Kestrel's process itself, which the debug build allows, so that a kill that Android refuses cannot look like
    # a new process (analysis, item 7).
    $restore = Register-AccessibilityRestore
    try {
        if ((Request-Owner 'Next comes a cold start through the tile: the script turns Kestrel''s accessibility service off and ends Kestrel''s process, and then you tap the tile once. Check that Kestrel''s tile is in Quick Settings. Press Enter to go ahead, or s to skip.' 'go') -eq 'skip') {
            Add-Result memory 'a cold start through the tile: skipped'
            return
        }
        # Before the kill: SystemUI binds the tile's service, which starts Kestrel's process, as on the owner's phone.
        if ($DryRun) { Add-DryRunTile }
        Invoke-Checked (Get-ServicesCommand @(Get-OtherServices $restore.Data)) | Out-Null
        if (-not (Wait-KestrelService off)) { throw 'Kestrel''s accessibility service did not turn off within 30 s.' }
        $old = Get-KestrelProcess
        $state = Get-ProcessState -Dump @(Invoke-Checked 'dumpsys activity processes dev.kestrel')
        if ($old) { Invoke-Checked "run-as dev.kestrel kill -9 $old" | Out-Null }
        Start-Sleep -Seconds 2
        $now = Get-KestrelProcess
        $ended = if (-not $old) { 'no process ran' } elseif (-not $now) { 'it ended' } elseif ($now -ne $old) { 'a new process started at once' } else { 'it did not end' }
        Add-Result memory "a cold start through the tile: Kestrel's process before the kill: $state; after 'run-as dev.kestrel kill -9': $ended"
        # A process that Android started at once is seconds old and has loaded no model, so the tap still checks a young
        # process; only a process that did not end makes the tap pointless.
        if ($now -and $now -eq $old) {
            Add-Result memory 'a cold start through the tile: not tried, because Kestrel''s process did not end'
            return
        }
        Request-Owner 'Now swipe down to Quick Settings and tap Kestrel''s tile once. Kestrel shows nothing on the screen when it starts, only its notification. Then press Enter at once, whether or not the phone reacts.' 'go' | Out-Null
        if ($DryRun) { Invoke-DryRunTileTap }
        $on = Wait-Mode listening 120
        $process = Get-KestrelProcess
        $times = Get-Times
        $started = ConvertFrom-TimeMark -Text $times['started']
        $timed = if ($on -ge 0 -and $started -and $started.Kind -eq 'tile') { "listening $([long]$times['opened'] - $started.At) ms after the tap, timed on the phone" } elseif ($on -ge 0) { 'listening, but Kestrel recorded no tap of the tile in its new process' } else { 'no listening within 120 s' }
        if ($now) { $timed = "$timed, in the process that Android started at once after the kill" }
        # A service that misses Android's deadline for startForeground() ends the process.
        Start-Sleep -Seconds 15
        $same = $process -and (Get-KestrelProcess) -eq $process -and (Get-VoiceState).mode -eq 'listening'
        Add-Result memory "a cold start through the tile: $timed; the same process still listened 15 s later: $same"
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

# The generated clips that the second battery measure plays, one every 5 minutes, through this computer's speaker, from
# corpus\generated\piper, which scripts\make-corpus.ps1 makes: tasks with Kestrel's name, which the hooks' task that ends
# at once takes, so that Kestrel answers and keeps listening with the home screen in front. Never a line that turns
# Kestrel off, pauses it, opens a page, stops, sends or calls.
$batteryClipIds = @('c074', 'c078', 'c084', 'c091', 'c089', 'c082')
function Get-CommandClips {
    @(Read-Corpus -Path $corpusFile | Where-Object { $_.Id -in $batteryClipIds -and $_.Expected -eq 'Task' } |
            ForEach-Object { [pscustomobject]@{ Id = $_.Id; Text = $_.Text; File = Join-Path $generatedClips "$($_.Id).wav" } } |
            Where-Object { Test-Path -LiteralPath $_.File })
}

# One battery measure of $Minutes minutes, timed by batterystats (research r10): Kestrel listens with the screen on and
# the phone counted as on battery; $Every runs every 5 minutes, from the start. Returns Kestrel's % per hour and the
# minutes of audio that it transcribed in an hour, or $null without an estimate.
function Measure-Battery([string]$Name, [double]$Minutes, [string]$Uid, [scriptblock]$Every = {}) {
    $batteryRestore = Register-Restore "the battery's state" "Run: & '$adb' -s $Serial shell dumpsys battery reset" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked 'dumpsys battery reset' | Out-Null
    }
    try {
        Start-Kestrel
        $before = Get-Counts
        # Research r10: first on battery, because Android resets the statistics itself when a phone at 90 % or more goes on
        # battery, and only then the reset. The battery's level stays frozen until the battery's state is put back.
        Invoke-Checked 'dumpsys battery unplug' | Out-Null
        Invoke-Checked 'dumpsys batterystats --reset' | Out-Null
        $restarts = 0
        $played = 0
        # Instant commands run on room talk since sub-project 3, and a locked screen stops the recording while the mode
        # stays listening, so each poll also reads the command number and the screen.
        $first = Get-VoiceState
        $firstCommand = if ($first) { [long]$first['command'] } else { 0 }
        $lastCommand = $firstCommand
        $screenOff = $false
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($watch.Elapsed.TotalMinutes -lt $Minutes) {
            if ($watch.Elapsed.TotalMinutes -ge $played * 5) {
                & $Every
                $played++
            }
            # Each poll runs in Kestrel's process, so the script polls only every 30 s.
            Start-Sleep -Seconds ([math]::Min(30, [math]::Max(1, ($Minutes - $watch.Elapsed.TotalMinutes) * 60)))
            $state = Get-VoiceState
            if ($state -and $state.Contains('command')) { $lastCommand = [long]$state['command'] }
            if ($state -and $state['screenOn'] -eq 'false') { $screenOff = $true }
            if (-not $state -or $state.mode -ne 'listening') {
                # A false "Stop listening" or "Kestrel, turn off" would leave Kestrel paused or off for the rest of the
                # measure, which would then measure a Kestrel that does not listen.
                try { Start-Kestrel; $restarts++ } catch { $restarts++ }
            }
        }
        $after = Get-Counts
        # Batterystats gives an app the power of its recording only once the recording stops (seen on kestrel-api37), so
        # Kestrel turns off for the dump.
        Stop-Kestrel
        $charged = @(Invoke-Checked 'dumpsys batterystats --charged')
        $hours = Get-BatteryHours -Dump $charged
        $timedBy = 'batterystats'
        if (-not $hours) { $hours = $watch.Elapsed.TotalHours; $timedBy = 'this computer, because batterystats printed no time on battery' }
        $onBattery = Test-OnBattery -Dump $charged
        Complete-Restore $batteryRestore
        $use = if ($Uid) { Get-BatteryUse -Dump $charged -Uid $Uid -Hours $hours }
        $audio = ([long]$(if ($after.Contains('audioMillis')) { $after['audioMillis'] } else { 0 }) - [long]$(if ($before.Contains('audioMillis')) { $before['audioMillis'] } else { 0 })) / 60000
        $minutesPerHour = if ($hours -gt 0) { [math]::Round($audio / $hours, 2) } else { 0 }
        $counted = "$([math]::Round($hours * 60, 1)) minutes timed by $timedBy; batterystats counted the phone as on battery at the end: $(if ($null -eq $onBattery) { 'not shown' } else { $onBattery }); Kestrel's accessibility service: $(Get-KestrelServiceState); restarts of listening: $restarts; instant commands that ran: $($lastCommand - $firstCommand); the screen was off at a poll: $screenOff; Kestrel transcribed $([math]::Round($audio, 2)) minutes of audio, $minutesPerHour minutes per hour; counts: $(Get-CountRise $before $after)"
        if (-not $Uid) {
            Add-Result battery "${Name}: no estimate, because neither dumpsys package nor pm list packages named Kestrel's UID; $counted"
            return $null
        }
        if (-not $use) {
            Add-Result battery "${Name}: no estimate: $(Get-BatteryUseGap -Dump $charged -Uid $Uid); $counted"
            return $null
        }
        Add-Result battery "${Name}: Kestrel (UID $Uid) used $($use.MilliampHours) mAh, with a capacity of $($use.Capacity) mAh: $($use.PercentPerHour) % per hour; its parts in mAh: $(if ($use.Breakdown) { $use.Breakdown } else { 'not printed' }); $counted"
        [pscustomobject]@{ PercentPerHour = $use.PercentPerHour; MinutesPerHour = $minutesPerHour }
    } finally {
        Complete-Restore $batteryRestore
    }
}

# Three measures of 30 minutes (analysis, item 10), and the model "% per hour = baseline + cost per transcribed minute x
# minutes" from them. The screen stays on, because Kestrel records only then, and "Stay awake" ends once Android counts
# the phone as on battery.
function Invoke-BatteryStep {
    $minutes = 30 * $minuteScale
    # Android 13 prints the UID as userId= and later versions as appId=; the list of packages names it too.
    $userId = Get-AppUserId -Dump @(Invoke-Checked 'dumpsys package dev.kestrel')
    if (-not $userId) { $userId = Get-ListedUserId -List @(Invoke-Checked 'pm list packages -U dev.kestrel') -Package dev.kestrel }
    $uid = if ($userId) { ConvertTo-UidLabel -UserId $userId } else { $null }
    $timeout = ((Invoke-Checked 'settings get system screen_off_timeout') -join '').Trim()
    $timeoutRestore = Register-Restore "the screen's timeout" "Run: & '$adb' -s $Serial shell settings put system screen_off_timeout $timeout" $timeout {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -eq 'null') { 'settings delete system screen_off_timeout' } else { "settings put system screen_off_timeout $Data" }
        Invoke-Checked $command | Out-Null
    }
    try {
        Invoke-Checked "settings put system screen_off_timeout $([int](($minutes + 10) * 60000))" | Out-Null
        $quiet = $null
        $typical = $null
        $talk = $null
        if ((Request-Owner "Battery, measure 1 of 3, $minutes minutes: a quiet room. Put the phone on a table, unlocked and plugged into this computer; its screen stays on, and it shows only the home screen. Nobody speaks near it, and no television or music plays. You may leave. Press Enter to start, or s to skip this measure." 'go') -ne 'skip') {
            $quiet = Measure-Battery 'measure 1, a quiet room' $minutes $uid
        } else {
            Add-Result battery 'measure 1, a quiet room: skipped'
        }
        $clips = @(Get-CommandClips)
        if (-not $DryRun -and $clips.Count -eq 0) {
            Add-Result battery 'measure 2, typical use: skipped, because corpus\generated\piper has no clips of commands with Kestrel''s name; run scripts\make-corpus.ps1 first'
        } elseif ((Request-Owner "Battery, measure 2 of 3, $minutes minutes: the same quiet room, while this computer's speaker plays a short task for Kestrel every 5 minutes, such as ""Kestrel, what's the weather tomorrow?"". Put the phone about 1 m from the speaker, and set the speaker as loud as a voice in the room. Nobody else speaks. Kestrel answers each task and goes on listening. You may leave. Press Enter to start, or s to skip this measure." 'go') -ne 'skip') {
            $every = if ($DryRun) {
                { Send-Heard "+$((Read-Corpus -Path $corpusFile | Where-Object { $_.Id -eq $batteryClipIds[0] }).Text)" }
            } else {
                {
                    $player = [System.Media.SoundPlayer]::new($clips[$script:clipIndex % $clips.Count].File)
                    try { $player.PlaySync() } finally { $player.Dispose() }
                    $script:clipIndex++
                }
            }
            $script:clipIndex = 0
            Add-Result battery "measure 2 plays the clips $(@($clips | ForEach-Object { $_.Id }) -join ', ')$(if ($DryRun) { ' (in a dry run, the voice hook sends the first one''s text)' })"
            $typical = Measure-Battery 'measure 2, typical use' $minutes $uid $every
        } else {
            Add-Result battery 'measure 2, typical use: skipped'
        }
        if ((Request-Owner "Battery, measure 3 of 3, $minutes minutes: ordinary room sound, as in the session of 2026-11-17. Let people talk or a television play near the phone, which lies there unlocked; nobody speaks to Kestrel. Kestrel may act on what it hears, for example scroll or open an app; let it, and do not touch the phone. Press Enter once the sound plays, or s to skip this measure." 'go') -ne 'skip') {
            $talk = Measure-Battery 'measure 3, room talk' $minutes $uid
        } else {
            Add-Result battery 'measure 3, room talk: skipped'
        }
        # The cost comes from the measure with the most transcribed audio, and the other one checks the model.
        $costly = @($typical, $talk) | Where-Object { $_ } | Sort-Object MinutesPerHour -Descending | Select-Object -First 1
        $cost = if ($quiet -and $costly) { Get-BatteryCost -Baseline $quiet.PercentPerHour -PercentPerHour $costly.PercentPerHour -MinutesPerHour $costly.MinutesPerHour }
        if ($null -eq $cost) {
            Add-Result battery 'the model: not computed, because the quiet measure or a measure with transcribed audio is missing'
        } else {
            $check = @($typical, $talk) | Where-Object { $_ -and $_ -ne $costly } | Select-Object -First 1
            $predicted = if ($check) { "; for the other measure it predicts $([math]::Round($quiet.PercentPerHour + $cost * $check.MinutesPerHour, 2)) % per hour, and $($check.PercentPerHour) % per hour was measured" } else { '' }
            Add-Result battery "the model: % per hour = $($quiet.PercentPerHour) + $cost x the minutes of audio that Kestrel transcribes in an hour$predicted"
        }
    } finally {
        Complete-Restore $timeoutRestore
    }
}

$plan = @(
    @{ Name = 'install'; Minutes = 10; Title = 'install Kestrel and copy the speech models with both of Kokoro''s models; you may leave'; Run = 'Invoke-InstallStep' }
    @{ Name = 'voice-tests'; Minutes = 40; Title = 'the voice device tests, among them the keyword spotter''s settings and Kokoro''s two models; you may leave'; Run = 'Invoke-VoiceTestStep' }
    @{ Name = 'failures'; Minutes = 1; Title = 'Kestrel''s logged failures since the voice tests'; Run = 'Invoke-FailuresStep' }
    @{ Name = 'listen-check'; Minutes = 12; Title = 'listen-check.ps1 with a restart of the phone; you unlock it once'; Run = 'Invoke-ListenCheckStep' }
    @{ Name = 'battery-rules'; Minutes = 10; Title = 'Android''s battery rules without the accessibility service; you look at the shade twice'; Run = 'Invoke-BatteryRulesStep' }
    @{ Name = 'activation'; Minutes = 8; Title = 'the shortcut, the button and the tile, with your hands'; Run = 'Invoke-ActivationStep' }
    @{ Name = 'assistant'; Minutes = 5; Title = 'Kestrel as the assistant without its accessibility service; no hands'; Run = 'Invoke-AssistantStep' }
    @{ Name = 'earbuds'; Minutes = 8; Title = 'the earbud microphone, with your earbuds, some music and your voice'; Run = 'Invoke-EarbudStep' }
    @{ Name = 'spotter'; Minutes = 16; Title = '"Hey Kestrel" and played speech with three settings of the keyword spotter; you speak, then stay quiet'; Run = 'Invoke-SpotterStep' }
    @{ Name = 'voice'; Minutes = 20; Title = 'talking to Kestrel, with your voice'; Run = 'Invoke-VoiceStep' }
    @{ Name = 'memory'; Minutes = 12; Title = 'memory with Piper and Kokoro, and a cold start through the tile, which you tap once'; Run = 'Invoke-MemoryStep' }
    @{ Name = 'battery'; Minutes = 95; Title = 'three battery measures of 30 minutes; you may leave during the first two'; Run = 'Invoke-BatteryStep' }
    @{ Name = 'failures-late'; Minutes = 1; Title = 'Kestrel''s logged failures since listen-check'; Run = 'Invoke-LateFailuresStep' }
)

$completed = $false
$stopped = $false
Add-Result session "started on $(if ($isEmulator) { 'kestrel-api37' } else { 'the Galaxy A71' })$(if ($DryRun) { ', as a dry run' })"
try {
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        if ((Request-Owner "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title). Press Enter to run it, s to skip it, or q to stop the session." 'go') -eq 'skip') {
            Add-Result $entry.Name 'skipped by the owner'
            continue
        }
        Initialize-Session
        try {
            & $entry.Run
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The scripts' and hooks' errors name only steps, files and classes, never what Kestrel heard.
            Add-Result $entry.Name "failed: $($_.Exception.Message)"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $($_.Exception.Message)"
} finally {
    try {
        # The restores that are left, the newest first, so that the screen, the hooks' tasks and Kestrel's mode come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        if ($earbudsChanged) { Write-Host 'If you have not done it yet, turn "Use the earbud microphone" off in Kestrel''s settings, unless you want to keep it.' }
        # The restores of battery-rules, activation, assistant and memory can leave Kestrel's notices.
        if ($prepared -and -not $DryRun) { Write-Host 'Kestrel''s notices, such as that its accessibility service is off, that Android stopped its listening, or "Open Kestrel''s settings", may still be in the notification shade; swipe them away.' }
        foreach ($restore in $pending) { Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)" }
        # install.ps1 and voice-test.ps1 build through Gradle, which keeps a daemon running.
        if ($gradleUsed) { & "$PSScriptRoot\gradle.ps1" --stop | Out-Null }
        Write-Host "The results are in $((Resolve-Path -LiteralPath $resultsFile).Path)."
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. pwsh -File ends a stopped script
        # that declares parameters with exit code 0, even after 'exit 1', unless the script gives the host its exit code;
        # run from a prompt, that would close the window, so the script does it only when nothing called it.
        if (-not ($completed -or $stopped)) {
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
if ($stopped -or $pending.Count -gt 0) { exit 1 }
```

Notes for the implementer. The script reads every time from the phone through `voice-times` and `/proc/uptime` (both on `SystemClock.elapsedRealtime`'s clock); `Stopwatch` only bounds waits, and the interruptions' prompts only set the pauses. A reaction's latency counts only when the transcribed speech that it counts from ended in the same try (`Get-Reaction`). It sends `run-as dev.kestrel kill -9 <pid>` for the cold start, which only a debug build allows, and a dry run taps Kestrel's tile with `cmd statusbar click-tile`, also for the cold start, so that the dry run runs every branch that does not need the owner's hands. It reads only the last byte of `dumpsys accessibility`, and of `cmd notification list`, which lists only the posted notifications, only a count that `grep -c` makes on the phone. It never prints what Kestrel heard: the hooks answer counts, times, kinds and device types. The spotter step plays the corpus's generated conversation and confusable lines, which hold no keyword, while Kestrel is paused, so that nothing that they say runs; battery measure 2 plays only tasks (c074, c078, c084, c091, c089, c082), which the hooks' task that ends at once takes, so that Kestrel answers and keeps listening.

- [ ] **Step 7: Make the recordings' prompts say what the owner does and what the phone shows**

In `scripts/a71-commands.ps1`, replace

```powershell
    $started = Invoke-Hook voice-record @{ File = 'owner'; Seconds = $Seconds }
    if ($started -ne 'recording') { throw "voice-record did not start: $started" }
    Write-Host "Speak now ($Seconds s)"
```

with

```powershell
    $started = Invoke-Hook voice-record @{ File = 'owner'; Seconds = $Seconds }
    if ($started -ne 'recording') {
        # The hook refuses to record while Kestrel is on, as on 2026-11-17, when the owner had turned it on.
        Stop-Kestrel
        Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Write-Host 'Kestrel was on, so the script turned it off. Say the line when this window says "Speak now".'
        $started = Invoke-Hook voice-record @{ File = 'owner'; Seconds = $Seconds }
        if ($started -ne 'recording') { throw "voice-record did not start: $started" }
    }
    Write-Host "Speak now: say the line once ($Seconds s). The phone does not change and does not answer while it records."
```

In `scripts/a71-commands.ps1`, replace

```powershell
    # Only the lines that no earlier session recorded: plan 2D's session recorded the first 100.
```

with

```powershell
    # Only the lines that have no clip yet, so that a session that stopped can go on. Plan 2D's session recorded none,
    # because the owner turned Kestrel on during the step (plan 2E).
```

In `scripts/a71-commands.ps1`, replace

```powershell
    if ((Request-Owner "Next come $($rows.Count) lines to say, about $minutes minutes. Sit in a quiet room and hold the phone as you would to talk to it; the script keeps Kestrel's settings in front. For each line, press Enter, wait for ""Speak now"", and say the line once, as you would say it to Kestrel. Press Enter to begin, or s to skip the recordings." 'go') -eq 'skip') {
```

with

```powershell
    if ((Request-Owner "Next come $($rows.Count) lines to say, about $minutes minutes. Kestrel stays off during this step: do not turn it on, and do not wait for it to answer. Sit in a quiet room and hold the phone as you would to talk to it. The phone shows Kestrel's settings; it does not change and does not answer while it records. For each line: press Enter, wait until this window says ""Speak now"", say the line once, as you would say it to Kestrel, and then press Enter for the next line, or r to say it again. Press Enter to begin, or s to skip the recordings." 'go') -eq 'skip') {
```

In `scripts/a71-commands.ps1`, replace

```powershell
        $answer = Read-OwnerAnswer "[$($i + 1)/$($rows.Count)] Say: $($row.Text)    (Enter to record, s to skip the line, q to stop)" ''
```

with

```powershell
        $answer = Read-OwnerAnswer "[$($i + 1)/$($rows.Count)] Next line: ""$($row.Text)"" Press Enter, wait for ""Speak now"", then say it. (s skips the line, q stops)" ''
```

In `scripts/a71-commands.ps1`, replace

```powershell
    @{ Name = 'recordings'; Minutes = 14; Title = 'your recordings of the new command lines'; Run = 'Invoke-RecordingStep' }
```

with

```powershell
    @{ Name = 'recordings'; Minutes = 35; Title = 'your recordings of the 160 lines that are marked for you'; Run = 'Invoke-RecordingStep' }
```

In `scripts/a71-commands.ps1`, replace

```powershell
# add up to 95 minutes; the session takes about 80 to 105 minutes, of which about 70 to 95 need the owner, because
```

with

```powershell
# add up to 116 minutes; the session takes about 100 to 125 minutes, of which about 90 to 115 need the owner, because
```

- [ ] **Step 8: Describe the sitting in the README**

In `README.md`, replace

```markdown
`scripts\a71-session.ps1 -Serial $serial` takes the owner through one session on the Galaxy A71, about 5 to 5.5 hours, of which about 2 hours need your hands and voice and the rest runs while you may leave, with every check of the voice features that needs the A71 or the owner (design spec, section 19.1): the voice device tests, `listen-check.ps1 -Reboot`, Android's and Samsung's battery rules, three restarts of the phone, the unlock paths, the activation methods, Kestrel as the assistant without its accessibility service (a start in the background, "Kestrel, open settings", the restore after a crash, the assistant gesture and the Galaxy Buds gesture), the earbud microphone, the owner's recordings of the voice corpus and their measurements, the owner's voice with Kestrel, Kestrel's memory with the chosen voice and with Kokoro, an hour of ordinary room audio with the keyword spotter's hits and the battery test. Its first step installs Kestrel and grants it the microphone and notification permissions, which stay granted afterwards. It asks before each step, and before it first reads the device; a step that needs your hands says what to do and waits, and `s` skips, `q` stops. `-Step` runs only the named steps. It refuses every device but the A71 and `kestrel-api37`. The results go into `corpus\owner\session-<time>.txt` and the recordings into `corpus\owner\clips`; git ignores both, and neither the results nor the console ever hold what Kestrel heard. While the session runs, the phone's screen stays on while it is plugged in. Afterwards, also when a step fails or Ctrl+C stops it, the script puts back Kestrel's mode Off, the hooks' slow task, the setting "Stay awake", the assistant role and its settings, Kestrel's battery restriction (as `allow` or `ignore`, never as `default`, which Android counts as restricted), Kestrel's voice, the accessibility settings, the screen's timeout, the battery's state and the clips in Kestrel's files folder. The battery test resets the phone's battery statistics, which cannot be put back, and the script asks you to turn "Use the earbud microphone" off again if you turned it on. `-DryRun` runs the session on `kestrel-api37` without the owner, to try a change of the script; it answers its own questions, sends what the owner would say through the voice hook, posts the notification of the voice step, which stays on the emulator, skips what needs the owner's hands otherwise, and shortens the waits. It needs PowerShell 7.3 or newer, as `hook.ps1` does.
```

with

```markdown
`scripts\a71-session.ps1 -Serial $serial` takes the owner through the second session on the Galaxy A71, about 4 hours, of which about 65 minutes need your hands or voice and the rest runs while you may leave or stay quiet. It repeats the checks of the voice features that failed or were not valid in the session of 2026-11-17 (design spec, section 19.1), and adds the measures that were missing: the voice device tests, among them the keyword spotter's three settings with generated speech and Kokoro's fp32 and int8 models side by side; `listen-check.ps1 -Reboot`, after a check of the last byte of `dumpsys accessibility`; Android's and Samsung's battery rules, with Android's record of the listening service every 5 s; the shortcut, the button and the tile; "Kestrel, open settings" with only the assistant role, and the restore after a crash; the route of the earbud microphone; "Hey Kestrel" and 2 minutes of played speech without a keyword with each of the three settings of the keyword spotter, after which the script names the setting that its rule keeps; the owner's voice with Kestrel, with interruptions after pauses of about 1, 1.5 and 2 s; Kestrel's memory categories with Piper and Kokoro, and a cold start through the tile; and three battery measures of 30 minutes (a quiet room, a task every 5 minutes from this computer's speaker, and room talk), which give "% per hour = baseline + cost per transcribed minute × minutes". Every time comes from the phone: Kestrel records when it was started, opened its session, heard the end of speech, reacted and turned itself off, and the script reads these times through `hook.ps1 voice-times`; no measure counts from your Enter. Each prompt says what you do and what the phone shows or does not show, such as "Say "Hey Kestrel" once. Kestrel should say "Yes?" and wait; the script ends the wait itself." The session and `scripts\a71-commands.ps1` make one sitting of about 6 hours, of which about 2.5 to 3 hours need you: `a71-commands.ps1` first, with all its steps, so that you record the voice corpus while you are fresh, and then `a71-session.ps1` with all its steps; you may split the sitting into two at the boundary between the scripts. The session's first step installs Kestrel, copies the models with both of Kokoro's models, which `a71-commands.ps1` does not copy, and grants Kestrel the microphone and notification permissions, which stay granted afterwards. It asks before each step, and before it first reads the device; `s` skips, `q` stops. `-Step` runs only the named steps. It refuses every device but the A71 and `kestrel-api37`. The results go into `corpus\owner\session-<time>.txt`, which git ignores, and neither the results nor the console ever hold what Kestrel heard: the hooks answer only counts, times, kinds and device types. While the session runs, the phone's screen stays on while it is plugged in. Afterwards, also when a step fails or Ctrl+C stops it, the script puts back Kestrel's mode Off, the hooks' slow task, the keyword spotter's setting, the setting "Stay awake", the assistant role and its settings, Kestrel's battery restriction (as `allow` or `ignore`, never as `default`, which Android counts as restricted), Kestrel's voice, the accessibility settings, the screen's timeout and the battery's state. The battery measures reset the phone's battery statistics, which cannot be put back, and the script asks you to turn "Use the earbud microphone" off again if you turned it on. `-DryRun` runs the session on `kestrel-api37` without the owner, to try a change of the script; it answers its own questions, sends what the owner would say through the voice hook, taps Kestrel's tile through SystemUI, also for the cold start, plays no sound, skips what needs the owner's hands otherwise, runs `listen-check.ps1` without `-Reboot`, skips the voice device tests, and shortens the waits and the battery measures to a minute. It needs PowerShell 7.3 or newer, as `hook.ps1` does.
```

In `README.md`, replace

```markdown
The owner's recordings, which the A71 session makes, go into `corpus\owner\clips`;
```

with

```markdown
The owner's recordings, which the step `recordings` of `scripts\a71-commands.ps1` makes, go into `corpus\owner\clips`;
```

In `README.md`, replace

```markdown
takes you through the checks of the instant commands on the Galaxy A71, in about 80 to 105 minutes, of which about 70 to 95 need you;
```

with

```markdown
takes you through the checks of the instant commands on the Galaxy A71, and through your recordings of the 160 lines of the voice corpus that are marked for you, in about 100 to 125 minutes, of which about 90 to 115 need you; it comes first in the sitting with `scripts\a71-session.ps1`, described above;
```

- [ ] **Step 9: Run the script tests and the analyzer**

Run: `scripts\test-scripts.ps1`
Expected: Pester P₀ + 15 tests passed in Windows PowerShell 5.1 and P₀ + 15 in PowerShell 7, none failed, and PSScriptAnalyzer reports nothing.

- [ ] **Step 10: Run the session as a dry run on the emulator**

Start the emulator if it does not run (`scripts\start-emulator.ps1`), use the serial that it prints in place of `emulator-5554`, and take the emulator lock. Keep the lock until the last command of this step has run, and delete it then, also when a command fails. Each device command below starts with the check of the Global Constraints.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 shell settings get global stay_on_while_plugged_in`
Expected: one number, such as `0`; write it down for the comparison after the run.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\a71-session.ps1 -Serial emulator-5554 -DryRun`
Expected: exit code 0 after about 40 minutes. Its install step installs Kestrel and copies the models with both of Kokoro's models itself. The results file `corpus\owner\session-dry-run-<time>.txt` has these lines, among others, and no line names a text that Kestrel heard:
- `session: started on kestrel-api37, as a dry run`;
- `voice-tests: skipped in a dry run; the plan runs scripts\voice-test.ps1 itself`;
- `listen-check: dumpsys accessibility ends with a line break: yes` (or `no`), and listen-check.ps1's lines, each `ok`;
- two lines `battery-rules: without the battery restriction: …` and `… with the battery restriction: …`, each with `Android's record at the start: listed True, isForeground true`;
- `activation: shortcut: skipped`, `activation: button: skipped`, and `activation: tile: listening <n> ms after the event of the tile, timed on the phone; a dialog: n; the second time paused it: ok`;
- `assistant: the role alone: "Kestrel, open settings": Kestrel's settings came to the front <n> ms after Kestrel asked for them, timed on the phone; …`, and the restore's line;
- `earbuds: skipped`;
- for the settings `boost=2.0 threshold=0.15` and `boost=3.0 threshold=0.1`: a try line `…, "Hey Kestrel", try 1: reacted; Kestrel's latest reaction: wait with no transcribed speech in this try; counts: none` and `…: no speech played in a dry run`; then `spotter: the setting that the rule keeps: boost=2.0 threshold=0.15 …`;
- the voice step's tries, each with `Kestrel's latest reaction: …` and usually `counts: none`, because the voice hook's text does not pass the session's pipeline, and the three interruptions;
- for each start of the memory step, the categories in MB and `peak PSS while it opened <n> MB, of <n> samples about <s> s apart`, the Kokoro starts with `Kokoro spoke, with a real-time factor on this phone of <f>` or `Kokoro's real-time factor on this phone is <f>, so Piper spoke`, `a cold start through the tile: Kestrel's process before the kill: procState=<n> adj=<n>; after 'run-as dev.kestrel kill -9': it ended` (or `a new process started at once`), and `a cold start through the tile: listening <n> ms after the tap, timed on the phone; the same process still listened 15 s later: True`;
- `battery: measure 2 plays the clips c074, c078, c084, c091, c089, c082 (in a dry run, the voice hook sends the first one's text)`, three measures with `minutes timed by batterystats`, `instant commands that ran:` and `its parts in mAh:` (or `no estimate: …` with the reason), and a line that starts with `battery: the model:` (in a dry run usually `not computed`, because the voice hook sends text and Kestrel transcribes no audio);
- `failures-late: …`.
No warning "Could not put back" follows the run.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 shell settings get global stay_on_while_plugged_in; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 voice-state; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 voice-spotter`
Expected: the number from before the run; a line that starts with `mode=off`; `boost=2.0 threshold=0.15`.

When a step fails on the emulator because of the script, fix the script, rerun only that step with `-Step <name>`, and run the whole dry run again at the end. When it fails because of the app, stop and report it to the controller.

- [ ] **Step 11: Stop Gradle and commit**

Run: `scripts\gradle.ps1 --stop`

```powershell
git add scripts/lib/Session.ps1 scripts/tests/Session.Tests.ps1 scripts/a71-session.ps1 scripts/a71-commands.ps1 README.md
git commit -m "feat(scripts): the A71 session of the re-checks, with times from the phone, the missing measures, the spotter's rule and a clear recordings prompt" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## After the session

The plan cannot know the A71's results in advance. Nothing ships a setting or a model that the A71 did not measure: until the controller applies a result here, Kestrel keeps the spotter's setting 2.0/0.15 and Kokoro's fp32 model. The controller applies the results after the session, each in its own commit, and records them in spec 19.1 (the bullet of Task 1 gets a sentence on the session of plan 2E) and in the README's table of the A71 session's measurements.

**The keyword spotter's setting.** The session's results have the line `spotter: the setting that the rule keeps: <setting> (…)`. The rule (`Select-SpotterWinner`): among the settings whose false hits in the played speech are at most twice the default's plus 2, the one with the most reactions to the owner's "Hey Kestrel"; a tie goes to fewer false hits, and then to the default. Before the controller applies it, the lines of `KeywordTuningTest` from the same session (in the step voice-tests) must not contradict it: the kept setting must have at least as many hits of the three generated phrases as the default, and at most twice its false hits plus 2 in the sentences without a keyword. When the rule keeps the default, nothing changes. Otherwise:
- In `core/voice/src/main/kotlin/dev/kestrel/voice/KeywordSpotter.kt`, change the defaults of `KeywordTuning(val boost: Float = 2.0f, val threshold: Float = 0.15f)` to the kept setting, and the sentence of its KDoc that names Kestrel's values since plan 2A.
- In `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt`, change the assertion `lines.all { " :2.0 #0.15 @" in it }` and its comment to the kept setting, and run `scripts\gradle.ps1 :core:voice:testDebugUnitTest`.
- In `app/src/androidTest/kotlin/dev/kestrel/app/voice/KeywordTuningTest.kt`, the first entry of `TUNINGS` stays `KeywordTuning()`; replace the kept setting's own entry with the old default, `KeywordTuning(2f, 0.15f)`, so that the test still compares three settings.
- In `scripts/a71-session.ps1`, change the restore of the spotter's setting (`voice-spotter @{ Boost = 2; Threshold = 0.15 }` and its text by hand), the first entry of `$tunings` and the test of `Default` in `Invoke-SpotterStep` to the kept setting, and in `scripts/hook.ps1` nothing.
- In spec section 8.2, write the kept boost and threshold, the session's numbers (hits and false hits of each setting) and the rule.

**Kokoro's model.** The session's results have, from the step voice-tests, the lines `kokoro models, <int8|fp32>, <2|4> threads: …` of `KokoroModelsTest`. Kestrel switches to the int8 model only when, on the A71 with the same threads, its real-time factor is at most 1.1 times fp32's and the rise of its PSS during the load (the line's "after it" minus "before the load") is at least 10 % lower than fp32's; otherwise it keeps fp32. The test loads int8 first, so among its own loads, memory that the process keeps after a closed voice can only lower fp32's rise, never int8's; the voice tests that ran before it in the same process can lower both rises. Independently, Kestrel uses 4 threads instead of 2 only when 4 threads lower the kept model's factor by at least 20 %. When neither changes, the controller removes nothing, but notes the numbers in spec section 8.2. To switch to int8:
- In `models/manifest.json`, move the file `tts-kokoro-int8/model.int8.onnx` into the model `tts-kokoro` as `tts-kokoro/model.int8.onnx` in place of `tts-kokoro/model.onnx`, rename that model to "Kokoro v1.0 voice af_heart (int8)", and delete the model `tts-kokoro-int8`. In `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`, the test of the Kokoro voice then expects `listOf("tts-kokoro")` alone again as the optional models, without `KOKORO_INT8_MODEL` and `KOKORO_INT8_ID`.
- In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, set `KOKORO_MODEL` to `"tts-kokoro/model.int8.onnx"`, and delete `KOKORO_INT8_ID` and `KOKORO_INT8_MODEL` with the branch of `kokoro()` that uses them; in `KokoroModelsTest`, measure only the kept model. Kestrel measures Kokoro's speed again by itself, because the stored measure names the model.
- In `scripts/push-models.ps1`, `README.md` and the spec's table of models in section 8.2, change the size of the Kokoro voice to about 142 MB, and in `THIRD_PARTY_NOTICES.md` name the int8 package as the one that Kestrel copies.
- To use 4 threads, change `THREADS` in `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt`, with its comment.
- Run `scripts\gradle.ps1 :core:voice:testDebugUnitTest :app:testDebugUnitTest`, `scripts\test-scripts.ps1` and `scripts\voice-test.ps1` on the emulator.

**The battery model and the earbuds' limit.** The line `battery: the model: % per hour = <baseline> + <cost> x …` goes into spec 19.1 and the README's battery row; whether Kestrel should transcribe less while it is idle (the item of "Not in this plan") is decided from it. When the earbuds' line shows a `linkedMillis` near or above 3,000, or `linkedMillis=none` with the earbuds in use, the controller raises `HeadsetRoute.LINK_MILLIS` and says so in spec 8.8.

## Self-review

The directions of the brief, in its order and with its numbers, and the tasks that carry them:

1. Demotion on Android 13 (analysis items 1 and 2): Task 2 (`DemotionTest`, `ListeningServiceTest`, `Get-ForegroundRecord`, which also reads Android 15 to 17's records), and Task 9's steps voice-tests and battery-rules.
2. "Kestrel, open settings" with the assistant role alone (item 5): Task 4 (`SettingsOpener`, its JVM tests, the verdict `settings-blocked`), and Task 9's step assistant, which times it and reads Kestrel's verdict and notification on the phone.
3. The scripts (items 3, 4, 7 and 11): Task 5 (the marker on a line of its own, one line for each check of listen-check, the failures' classes, and listen-check's reboot time on the phone's clock from the unlock that the script sees, without an Enter), Task 3 (`Timeline` and `voice-times`, which time the activation methods on the phone), and Task 9's steps listen-check (only the last byte of `dumpsys accessibility`), failures, activation and memory (the cold start after `kill -9`, with `Get-ProcessState`, also in the dry run).
4. The earbuds (item 6): Task 6 (`HeadsetRoute` waits for the link and ends the wait at a loss, `voice-route` and its device test), and Task 9's step earbuds.
5. The keyword spotter (item 8): Task 7 (`KeywordTuning`, `voice-spotter`, `KeywordTuningTest` with three settings and false hits), Task 3 (the counts by keyword, utterance and read, and the reactions timed from the end of speech), Task 9's steps spotter (hits, false hits and the rule) and voice (pauses of about 1, 1.5 and 2 s only), and "After the session".
6. Kokoro (item 9): Task 8 (the speed at the first load, Piper without loading Kokoro where it is known to be too slow, the int8 model for a measure, `KokoroModelsTest`), Task 9's steps voice-tests and memory, and "After the session".
7. The battery (item 10): Task 3 (the transcribed audio in `voice-counts`), and Task 9's step battery (`Get-BatteryHours`, `Breakdown`, `Get-BatteryCost`, the clips by id, the instant commands counted) and "After the session".
8. The owner's lesson: Task 9's prompts, the recordings' prompts of `a71-commands.ps1`, and the description of the sitting.
9. The documents: Task 1 (spec 19.1 and the README's table), and the documents of Tasks 2, 4, 6, 8 and 9.

Placeholders: none, except the starting totals A₀, L₀, R₀, V₀, D₀, O₀ and P₀, which the controller fills in before Task 1, and the A71's results, which "After the session" applies.

Names across tasks: `Timeline` and its marks (`started`, `opened`, `speechEnded`, `reacted`, `stopped`, `settingsAsked`, `settingsResumes`, `settingsInFront`) are defined in Task 3 and used in Tasks 4, 5 and 9 (the hook prints `settingsResumed=`); `SettingsOpening` and `SettingsOpener` in Task 4; `CommunicationDevices.current()` and `HeadsetRoute.linkedMillis` in Task 6; `KeywordTuning`, `KeywordLines.text(tuning)`, `VoiceEngines.keywordTuning` and `KeywordTuningTest` in Task 7; `KokoroSpeed`, `LoadedVoice.slow`, `AppGraph.kokoroKnownTooSlow`, `VoiceEngines.kokoro`, `KokoroModelsTest` and `SettingsData.kokoroSlow` in Task 8; the hooks' keys as Task 9's Interfaces list them (`voice-route`'s `earbud=` and `headset=`); the PowerShell helpers' names in Tasks 2, 3, 5 and 9 as their Interfaces list them.
