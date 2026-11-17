# Voice 2D: Calls, the Assistant Role, Settings, the Corpus and the A71 Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish sub-project 2: Kestrel stops speaking when another app or a call takes the audio focus, follows incoming calls with the modes Ringing and In call and the call commands, starts from the assistant gesture and the earbuds' assistant button, has the settings pages "Listening and activation" and "Voice and speech" (with Kokoro as a second voice), measures itself against a voice corpus, and prepares one session on the Galaxy A71 with the owner for every check that needs the phone or the owner.

**Architecture:** `VoicePlayer` holds its audio focus through `ReplyFocus`, which keeps the focus between queued replies, lowers the player's volume while another app may only duck it, and stops the player on a transient or lasting loss. A `CallMonitor` reports the call state while a voice session is open; the Rules turn it into the modes `RINGING` and `IN_CALL`, read the call commands with `Requests.readCall`, and hand them to `CallActions`, which uses Android's call API with `ANSWER_PHONE_CALLS` and clicks the phone app's controls through the accessibility service otherwise. A `VoiceInteractionService` with a session that hides itself, and a trampoline activity for `VOICE_COMMAND`, call `Listening.assist()`, which sends `Event.Assist` once a voice session is open. The owner's choices for listening are a `ListeningOptions` setting that `Listening` reads as a flow and passes to the Rules as `Event.Options`. Tasks 6 to 9 add the earbud microphone, the voice settings with Kokoro, the corpus with `voice-eval.ps1`, and `a71-session.ps1`.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0 (kotlinx-coroutines-test for the JVM tests), kotlinx.serialization for the stored settings, Android's `AudioFocusRequest`, `TelephonyCallback.CallStateListener`, `TelecomManager`, `VoiceInteractionService`, `RoleManager` and `AccessibilityService.getWindows()` at compileSdk 37, Jetpack Compose for the settings pages, JUnit 4, Pester 6.2.0, device tests on the `kestrel-api37` emulator through `scripts\voice-test.ps1`, `scripts\connected-test.ps1` and `scripts\listen-check.ps1`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app.
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`.
- No dependency-injection framework; `AppGraph` wires by hand.
- Never log audio, transcripts, replies, boost words, app labels, screen text, phone numbers, exception messages or an exit record's description (section 13.5); `toString` of anything that carries the owner's words prints only lengths or counts.
- Test only on `kestrel-api37` (emulator-5554), and check it in the same command as each device command; never on the S23+ or the AVD `zoran`; the A71 only in the session with the owner. Gradle only through `scripts\gradle.ps1` (stopped with `--stop` at the end of each task); device tests only through `scripts\voice-test.ps1`, `scripts\connected-test.ps1` (with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) or `scripts\listen-check.ps1`, as each task says; installs only through `scripts\install.ps1`. Device tests and scripts restore every setting, app op, role, permission and call-log entry that they change, and keep the APKs installed. A reboot of the emulator is a cold boot.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan file is named with the day it is written (`Get-Date -Format yyyy-MM-dd`), and it is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

## Before you start

- Plan 2C-4 is complete; its last commit is in `.superpowers/sdd/progress.md`, section "Plan 2C-4". This plan was written against the code as plan 2C-4 leaves it. The totals at its start: `:app` JVM 190 tests (`ListeningTest` 95, `RulesTest` 43, `OrchestratorTest` 9, `RequestsTest` 24, `SetupChecklistTest` 9, `BoostWordSourceTest` 6, `LicenceParagraphsTest` 3, `ExitRecordsTest` 1), `:core:voice` JVM 116, voice device tests 66 (`VoicePlayerTest` 9), the other device tests of `:app` 103 (among them `SettingsAppTest` 17, `SetupStatusReaderTest` 3, `BatteryRestrictionTest` 1, `AccessibilityNoticeTest` 1 and `MainActivityTest` 4), Pester 99 per shell. These are the totals at commit 7493c42, after plan 2C-4's final fix wave.
- Before each task, check that every text that the task replaces exists, and exists once, in its file, and compare the counts with the ones that the earlier tasks left: before Task 1, the ones above; before Task 2, `:app` 191 (`ListeningTest` 96), `:core:voice` 122, voice device tests 68; before Task 3, `:app` 204 (`ListeningTest` 101, `RulesTest` 51); before Task 4, `:app` 221 (`ListeningTest` 104, `RulesTest` 53, `RequestsTest` 27, `SetupChecklistTest` 10, `CallActionsTest` 8), other device tests 104 (`SettingsAppTest` 18), Pester 101; before Task 5, `:app` 229 (`ListeningTest` 108, `RulesTest` 56, `SetupChecklistTest` 11), voice device tests 70 (`AssistTest` 2), other device tests 105 (`SettingsAppTest` 19); before Task 6, `:app` 236 (`ListeningTest` 111, `RulesTest` 59, `OrchestratorTest` 10), `:core:voice` 122, voice device tests 70, other device tests 108 (`SettingsAppTest` 21, `SetupStatusReaderTest` 4), Pester 101; before Task 7, `:app` 237 (`ListeningTest` 112), `:core:voice` 131, voice device tests 71, other device tests 108, Pester 101; before Task 8, `:app` 243 (`SetupChecklistTest` 12, `VoiceOptionsSettingTest` 5), `:core:voice` 136, voice device tests 74, other device tests 114 (`SettingsAppTest` 27), Pester 103; before Task 9, `:app` 248 (`WordErrorsTest` 5), `:core:voice` 136, voice device tests 78, other device tests 114, Pester 116. If a text is missing or a count differs, stop and ask the controller.
- What the listening classes do now (`.superpowers/sdd/progress.md`, plans 2C-1 to 2C-4): `Listening` (in `AppGraph.listening`) owns the Orchestrator on one single-threaded dispatcher (`thread`). When the mode leaves Off, `follow()` asks Android for `ListeningService`; once the service reports (`onForeground(instance, startId)`), `open()` opens a `VoiceSession` under the `lifecycle` lock and removes the problems notification. `Effect.StopService` runs `shutdown()`, and `ServiceControl.stop(instance, startId)` stops the service only up to the newest start that `Listening` has seen (commit 0328fdc); `ListeningTest` keeps calling `onForeground(service)` and `onStartAction(service)` through two private helpers that add the start ID. `ListeningService` reports the screen only in `onCreate` and in its screen receiver, not at each start. A failure turns Kestrel off through `stopBecause(reason)`, which shows the reason on the feedback bar and in the problems notification. Every `Feedback.ModeChanged` goes through `noteMode`, which stores the mode once a session is open, and the restore after a restart sends `Event.Start` in the stored mode. A reply that Android refuses (`Spoke.REFUSED`) goes to the bar, except "Yes?", which the bar shows at the start of each wait for a task. `VoicePlayer` asks for `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` without a listener for each reply and abandons it after the reply.
- Facts from prototype P3 (`.superpowers/sdd/research/p3-prototype-2d.md`, kestrel-api37 with Google's phone app) that this plan relies on: a `TelephonyCallback.CallStateListener` with `READ_PHONE_STATE` reports ringing, active and idle within about 1 s; revoking a permission ends Kestrel's process. With `ANSWER_PHONE_CALLS`, `acceptRingingCall()` and `endCall()` answer, decline and hang up, also on the lock screen; the in-call screen does not come to the front after an answer through the API. Unlocked, a call shows as a heads-up notification whose two buttons have the content descriptions "Answer" and "Decline"; locked, as `InCallActivity` over the lock screen with two such buttons; `ACTION_CLICK` works on both, and the in-call screen's end-call button has no description. With Kestrel's accessibility service on, Android does not silence Kestrel's recording during a call. While a call rings, Android refuses Kestrel's audio focus; a call that starts while Kestrel speaks gives `LOSS_TRANSIENT`, and the player speaks on over the ringtone unless it stops itself. Kestrel qualifies for the assistant role with a `VoiceInteractionService`, a session service and a stub `RecognitionService`; `KEYCODE_ASSIST` and a long press of Home reach `onShow` with flags 4, unlocked and on the lock screen; `ListeningService` starts from `onShow` without being silenced; the session's window covers the screen and takes the focus, so `onShow` must call `hide()`; the role alone lets Kestrel start its microphone service from the background. `KEYCODE_VOICE_ASSIST` and the long presses of the headset keys start `WEB_SEARCH` (unlocked) or `VOICE_SEARCH_HANDS_FREE` (locked) through Android's chooser, never `onShow`, and a trampoline activity that the owner picks there starts listening, also without the role; `VOICE_COMMAND` goes through the chooser too. P3 did not look at the in-call screen's speaker and mute controls, so Task 3 looks at them first.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task.
- Run the voice device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`; it installs Kestrel, copies the models and keeps both installed. It runs only the package `dev.kestrel.app.voice`. The other device test classes run through `scripts\connected-test.ps1`, always with `'-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`, as each task says. `scripts\listen-check.ps1` needs PowerShell 7.3 (`pwsh`), as `scripts\hook.ps1` does.
- Every device command of this plan starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. `voice-test.ps1`, `connected-test.ps1` and `listen-check.ps1` also refuse other devices themselves (`Assert-TestDevice`). A step that would reboot the emulator closes it and starts it from a cold boot instead.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Sub-project 3 and later: the instant commands, providers, the agent and its evaluation. A task that a call interrupts is cancelled; asking "Continue the task?" after the call comes with the tasks of sub-project 5 (section 14.4). The call controls are found by their English labels; the phone app's other languages come with the matching of sub-project 3.

The side key, which exists only on One UI 9 (the S23+), so it cannot be checked (spec 19, S2). One UI's incoming-call screen: the A71 has no SIM card, and the S23+ is off-limits, so the screen path of the call commands is checked only with Google's phone app on the emulator; the call API path does not depend on the phone app.

`android.speech.action.WEB_SEARCH`: a wired headset's long press sends it while the phone is unlocked, but other apps use it for typed web searches, and an owner who picked "Always" in the chooser would send them all to Kestrel. The trampoline also has no `showWhenLocked`: on the lock screen Android's chooser stays hidden anyway (P3), and an exported activity that any app may start must not turn the microphone on over the lock screen.

A setting for Ringing on the lock screen: the design spec's table named one, on by default; plan 2D records on the lock screen while a call rings without a setting, and Task 2 changes the table.

"Commands during a call" with the phone locked: during a call Kestrel records only while the phone is unlocked, because section 8.5 makes an exception only for a ringing call.

The notification's own actions for a call: design D1 also named the actions of the call's notification as a place to search. Task 3 searches only the windows on the screen, which hold the heads-up notification's buttons (P3); when the owner has pushed the heads-up notification away, the screen path finds nothing to click and says so, and the call API path still works.

Calls in other apps, such as Viber or WhatsApp: Android's call-state listener reports only the phone's own calls, so such a call changes no mode (spec 8.5 says so). The call state of a second SIM: the monitor follows the default subscription (research r8), while `CallApi.active()` asks Telecom, which covers every SIM.

An unlock during a call: the wake-up rule of Task 5 applies only while Kestrel listens or is paused, so an unlock during a call does not count when the call ends.

A stop of the reply under another app's short sound: a notification's sound or a navigation prompt asks for focus that lets others duck, and Kestrel speaks on more quietly under it (the controller's decision, which replaces design D3's stop on every loss). Android does not lower the volume of speech by itself, so Kestrel lowers its own to a fifth until the focus comes back. A call, an app that asks for exclusive transient focus and another assistant still stop the reply at once.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `ReplyFocus.asked` | The thread that calls `VoicePlayer.speak` (for `Listening`, the controller's thread) | The reply's coroutine starts undispatched, and `asked()` comes before its first suspension |
| `ReplyFocus.take`, `finish` | A coroutine of `VoicePlayer`'s scope (`Dispatchers.IO`) | Under `ReplyFocus`'s own lock; `take` runs while the reply holds the player's turn |
| `ReplyFocus.changed` and `VoicePlayer.focusChanged` | The main thread (Android's focus listener) | `changed` releases its lock before `focusChanged` takes the player's `lock`; `focusChanged` and `withTrack` read `ReplyFocus.volume()` while they hold the player's `lock`, and `ReplyFocus` never takes the player's `lock`, so the two locks are always taken in the same order |
| `CallMonitor.start(report)` and `stop()` | The controller's thread (`open`, `watchCalls`, `shutdown`) | `report` runs on a binder thread and sends `Event.CallState` through the session's listener, so a report that comes after `stop()` is dropped, because the closed session's listener is inactive |
| `CallControl.perform(command)` | A coroutine of `Listening`'s `scope` (`Dispatchers.IO` in `AppGraph`) | `CallActions` makes binder calls into Telecom and, through `ScreenActions.clickLabelled`, into the accessibility service's windows; a failure shows on the bar |
| `Listening.assist()` | The main thread (`AssistSession.onShow`, `AssistActivity.onCreate`); it launches on the controller's thread | Sends `Event.Assist` at once while a session is open; otherwise sets `assistWhenOpen` and sends `Event.Start()`; `open()` sends `Event.Assist` and `shutdown()` clears the flag; the Rules ignore an Assist while Kestrel is off |
| `Listening.watchCalls()` | The main thread (`MainActivity.refresh()`, when `READ_PHONE_STATE` has newly appeared); it launches on the controller's thread | Starts the call monitor again only while a session is open |
| The options' collector | The controller's thread | Sets `currentOptions` and sends `Event.Options`; a failure of the flow is reported and the last options stay |
| The silence timer | The Orchestrator's coroutine | Like the wait for a task: `Effect.TimeSilence` starts it in place of the one that runs, and it sends `Event.Silent(number)` |

## File structure

| File | Responsibility |
|---|---|
| `core/voice/src/main/kotlin/dev/kestrel/voice/speak/ReplyFocus.kt` | New (Task 1): `AudioFocus`, `ReplyFocus` (the focus kept between queued replies; a "may duck" loss makes the player quieter, a transient or lasting loss stops it) and `FocusChange` and `AndroidAudioFocus` (one request with its own listener for each player) |
| `core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt` | Task 1: `Spoke.INTERRUPTED`, the focus through `ReplyFocus`, `focusChanged` |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` | Task 1: the KDoc of `Event.SpeechEnded`; Task 2: `Mode.RINGING`, `Mode.IN_CALL`, `CallState`, `Event.CallState`, `State.beforeCall`; Task 3: `State.callCommands`, `Effect.CallCommand`; Task 4: `Event.Assist`; Task 5: `WakeUpMode`, `Event.Options`, `Event.Silent`, `Effect.TimeSilence`, the wake-up rule and the silence timer |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt` | Task 3: `Request.Call` with `Answer`, `Decline`, `HangUp`, `Mute`, `Speaker`, and `Requests.readCall` |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` | Task 5: the timer of the pause after 30 s without speech |
| `app/src/main/kotlin/dev/kestrel/app/listen/CallMonitor.kt` | New (Task 2): `CallMonitor`, `AndroidCallMonitor`, `callStateOf` |
| `app/src/main/kotlin/dev/kestrel/app/listen/CallActions.kt` | New (Task 3): `CallControl`, `CallApi`, `CallScreen`, `CallActions`, `AndroidCallApi` |
| `app/src/main/kotlin/dev/kestrel/app/listen/ListeningOptions.kt` | New (Task 5): the stored `ListeningOptions` |
| `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` | `INTERRUPTED` to the bar (Task 1); the call monitor, the stored mode during a call and a reply that a call stopped (Task 2); the call commands and `watchCalls` (Task 3); `assist()` and `onAssistantServiceReady()` (Task 4); the options (Task 5); the restart of a running recording when the earbud setting changes (Task 6); `runTask` (Task 9) |
| `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, `KestrelTileService.kt` | Task 2: the notification and the tile during a call |
| `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, `AssistActivity.kt`, `app/src/main/res/xml/voice_interaction.xml` | New (Task 4): `KestrelAssistantService`, `KestrelSessionService` with its session, `KestrelRecognitionService`; the trampoline for `VOICE_COMMAND` and `VOICE_SEARCH_HANDS_FREE` |
| `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` | Task 3: `clickLabelled`, a click by content description or text in any window |
| `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `ChecklistPage.kt`, `SetupStatusReader.kt`, `SettingsApp.kt`, `SettingsData.kt` | The call step (Task 3), the assistant step (Task 4), the page "Listening and activation" and its data (Task 5), the page "Voice and speech" and an absent optional model (Task 7) |
| `app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt` | New (Task 5): the page "Listening and activation" |
| `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` | The phone permissions and the call monitor after a grant (Task 3), Android's default apps (Task 4) |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` | `SettingKeys.LISTENING_OPTIONS` (Task 5) and `SettingKeys.VOICE` (Task 7); wires the call monitor, the call actions and the options (Tasks 2 to 5), `openSettings` with the assistant role (Task 4), the microphone factory (Task 6) and the session's voice (Task 7) |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml` | The phone permissions, the assistant's services and the trampoline, `MODIFY_AUDIO_SETTINGS` (Task 6); the new texts |
| `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`, `AndroidCommunicationDevices.kt`, `MicCapture.kt` | Task 6: the earbud microphone (`Headset`, `CommunicationDevices`, `HeadsetRoute`; the whole of `MicCapture.kt`) |
| `models/manifest.json`, `core/voice/src/main/kotlin/dev/kestrel/voice/Synthesizer.kt`, `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt`, `ModelFiles.kt`, `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt`, `core/voice/src/main/kotlin/dev/kestrel/voice/speak/EchoFilter.kt` | Task 7: Kokoro as an optional voice (`VoiceChoice`, `Model.optional`, `ModelStatus.absent`, the Kokoro synthesizer), and two comments of the echo filter |
| `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`, `AboutPage.kt`, `THIRD_PARTY_NOTICES.md` | Task 7: the stored voice and speed, the page "Voice and speech" with "Try it", an absent optional model on the About page, Kokoro's licence notes |
| `scripts/push-models.ps1`, `scripts/lib/Models.ps1`, `scripts/voice-test.ps1` | Task 7: `push-models.ps1 -Kokoro` (the whole file), `Select-ModelManifest`, the push with `-Kokoro` |
| `corpus/commands.txt`, `app/src/main/kotlin/dev/kestrel/app/listen/WordErrors.kt`, `core/voice/src/main/kotlin/dev/kestrel/voice/Recognizer.kt`, `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaRecognizer.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/Wav.kt`, `EvalHooks.kt`, `TestHooks.kt`, `scripts/lib/Corpus.ps1`, `scripts/make-corpus.ps1`, `scripts/voice-eval.ps1`, `scripts/hook.ps1`, `.gitignore` | Task 8: the corpus, the word errors, the recogniser's counts, the evaluation hooks and scripts, the owner's recordings (git-ignored) |
| `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, `TestHooks.kt`, `scripts/lib/Session.ps1`, `scripts/a71-session.ps1`, `scripts/hook.ps1`, `scripts/listen-check.ps1` | Task 9: the session hooks, the A71 session with the owner and its dry run on the emulator, the unlock on a phone before the reboot check's recording check |
| `scripts/lib/Kestrel.ps1`, `scripts/listen-check.ps1`, `scripts/voice-test.ps1` | `Get-LargestRowId` and the call check (Task 3); the assistant test on emulators only and the restore of the role and its settings (Task 4); the earbud test on emulators only (Task 6) |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md`, `README.md` | Sections 8.2, 8.4 to 8.8, 8.10, 13.6, 14.6, 15, 16.4, 18.2, 19 and 19.1, and the README's paragraphs, each with the task that changes the behaviour |

Tests: `core/voice/src/test/kotlin/dev/kestrel/voice/speak/ReplyFocusTest.kt` (JVM, new, Task 1); `app/src/test/kotlin/dev/kestrel/app/listen/CallActionsTest.kt` (JVM, new, Task 3); `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `RequestsTest.kt`, `OrchestratorTest.kt`, `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt` (JVM, changed); `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoicePlayerTest.kt` (voice device test, changed, Task 1); `app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt` (voice device test, new, Task 4); `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` and `SetupStatusReaderTest.kt` (device, changed); `scripts/tests/Kestrel.Tests.ps1` (Pester, changed); and the tests of Tasks 6 to 9.

---

### Task 1: Audio focus

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/speak/ReplyFocus.kt`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt` (imports, `Spoke`, the class's KDoc and fields, `speak`, `stop`, new `focusChanged`, `withTrack`, `withFocus` removed)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`say`), `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (the KDoc of `Event.SpeechEnded`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.4 and 14.6)
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/speak/ReplyFocusTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoicePlayerTest.kt`

**Interfaces:**
- Consumes: `VoicePlayer(context, synthesizer)`, `VoicePlayer.speak(text): Deferred<Spoke>`, `VoicePlayer.stop()`, `Spoke`, `Listening.say(reply)`.
- Produces: `enum class Spoke { FINISHED, STOPPED, REFUSED, INTERRUPTED }`; `internal interface AudioFocus { fun request(): Boolean; fun abandon() }`; `internal class ReplyFocus(focus: AudioFocus)` with `asked()`, `take(): Boolean`, `finish()` and `changed(change: Int): FocusChange` and `volume(): Float`; `internal enum class FocusChange { NONE, STOP, LOWER, RESTORE }`; `internal class AndroidAudioFocus(context: Context, attributes: AudioAttributes, onChange: (Int) -> Unit) : AudioFocus`. Task 7 does not change `VoicePlayer`; its "Try it" makes a second player, whose own request lets others duck, so a session reply that plays at that moment goes on more quietly.

Final review of plan 2C-1, item 14, and the review of plan 2B's Task 5, N4 and N5. Today the player asks for transient focus without a listener for each reply, so it speaks on over a ringing call or another app's navigation prompt (P3), every request of Kestrel's has the same client ID, and between two queued replies it gives the focus up and asks again, so an app whose volume Android lowered gets louder for a moment. Now each player has one `AudioFocusRequest` with its own listener. A transient or lasting loss (a call, an app that asks for exclusive transient focus, another assistant) stops the player at once, as `stop()` does, and the replies that played or waited end with the new `Spoke.INTERRUPTED`, which `Listening` shows on the bar like a refused reply (spec 14.6). A "may duck" loss (a notification's sound, a navigation prompt) keeps the reply going: Android does not lower the volume of speech by itself and reports the loss instead, so the player lowers its track's volume to `ReplyFocus.QUIET_VOLUME` until the focus comes back. This is the controller's decision after the first review, in place of design D3's stop on every loss. The focus stays while replies wait in the queue and goes when none waits. A refusal keeps the path that `RefusedReplyTest` pins: `take()` returns false, the reply ends with `REFUSED`, and `Listening` shows its text on the bar (final review of plan 2C-4, "For plan 2D", item 6). The JVM tests cover `ReplyFocus` with a fake; a device test checks that a second, exclusive transient focus request, as another assistant would make it, stops a long reply.

- [ ] **Step 1: Write the failing tests**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/speak/ReplyFocusTest.kt`:

```kotlin
package dev.kestrel.voice.speak

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFocusTest {
    private val focus = FakeFocus()
    private val replies = ReplyFocus(focus)

    @Test
    fun aReplyTakesTheFocusAndGivesItUpWhenItEnds() {
        replies.asked()
        assertTrue(replies.take())
        replies.finish()

        assertEquals(listOf("request", "abandon"), focus.calls)
    }

    @Test
    fun theFocusStaysBetweenQueuedReplies() {
        replies.asked()
        replies.asked()
        assertTrue(replies.take())
        replies.finish()
        // The second reply's turn comes while Kestrel still holds the focus.
        assertTrue(replies.take())
        replies.finish()

        assertEquals(listOf("request", "abandon"), focus.calls)
    }

    @Test
    fun theNextReplyAsksAgainAfterARefusal() {
        focus.granted = false
        replies.asked()
        assertFalse(replies.take())
        replies.finish()
        focus.granted = true
        replies.asked()
        assertTrue(replies.take())
        replies.finish()

        // After the refusal there is nothing to give up.
        assertEquals(listOf("request", "request", "abandon"), focus.calls)
    }

    @Test
    fun aTransientOrLastingLossStopsTheReplyAndGivesTheFocusUpOnce() {
        // A call, an app that asks for exclusive transient focus, or another assistant.
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            val lost = FakeFocus()
            val reply = ReplyFocus(lost)
            reply.asked()
            reply.take()

            assertEquals("Loss $loss", FocusChange.STOP, reply.changed(loss))
            reply.finish()
            assertEquals(listOf("request", "abandon"), lost.calls)
        }
    }

    @Test
    fun aLossThatLetsKestrelDuckKeepsTheReplyGoingMoreQuietly() {
        replies.asked()
        replies.take()

        // A notification's sound or a navigation prompt: Kestrel speaks on, more quietly, and keeps the focus.
        assertEquals(FocusChange.LOWER, replies.changed(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(ReplyFocus.QUIET_VOLUME, replies.volume(), 0f)
        assertEquals(listOf("request"), focus.calls)
        assertEquals(FocusChange.RESTORE, replies.changed(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(1f, replies.volume(), 0f)
        replies.finish()

        assertEquals(listOf("request", "abandon"), focus.calls)
    }

    @Test
    fun aGainOrAChangeWithoutTheFocusStopsNothing() {
        assertEquals(FocusChange.NONE, replies.changed(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        replies.asked()
        replies.take()

        assertEquals(FocusChange.NONE, replies.changed(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(listOf("request"), focus.calls)
        // After a loss, the next reply asks Android again.
        assertEquals(FocusChange.STOP, replies.changed(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        replies.asked()
        assertTrue(replies.take())
        assertEquals(listOf("request", "abandon", "request"), focus.calls)
    }

    private class FakeFocus : AudioFocus {
        val calls = mutableListOf<String>()
        var granted = true

        override fun request(): Boolean {
            calls += "request"
            return granted
        }

        override fun abandon() {
            calls += "abandon"
        }
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    @Test
    fun aReplyWithoutASessionIsEndedByTheOrchestrator() = runTest {
```

with

```kotlin
    @Test
    fun aReplyThatAndroidInterruptsIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        shown.clear()

        // Android took the audio focus away, for a call or an app that asks for exclusive focus (section 14.6).
        session.replies.single().complete(Spoke.INTERRUPTED)
        runCurrent()

        assertEquals(listOf(ListeningTexts.NO_TASKS), shown)
        assertFalse(listening.state.value.speaking)
    }

    @Test
    fun aReplyWithoutASessionIsEndedByTheOrchestrator() = runTest {
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.speak.ReplyFocusTest :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ReplyFocus'" and "Unresolved reference 'AudioFocus'" in `ReplyFocusTest.kt`. (Gradle stops at the first module; `ListeningTest` fails to compile with "Unresolved reference 'INTERRUPTED'" once `:core:voice` compiles.)

- [ ] **Step 3: Write the focus classes**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/speak/ReplyFocus.kt`:

```kotlin
package dev.kestrel.voice.speak

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Android's audio focus as Kestrel's player uses it. [AndroidAudioFocus] is the real one; the JVM tests use a fake. */
internal interface AudioFocus {
    /** Asks Android for the focus, and returns whether Android granted it. */
    fun request(): Boolean

    /** Gives the focus up. */
    fun abandon()
}

/**
 * When Kestrel holds the audio focus for its replies (design spec, sections 8.4 and 14.6). A reply asks for the focus
 * when its turn comes ([take]). The focus stays while more replies wait, so that an app whose volume Android lowered
 * does not get louder for a moment between them, and [finish] gives it up once no reply waits. [changed] learns when
 * Android takes the focus away, as for a ringing call or an app that asks for exclusive focus, and the player then
 * stops speaking; or when another app, such as a notification's sound or a navigation prompt, only lets Kestrel lower
 * its volume, and the player then speaks on at [volume]. Any thread may call it.
 */
internal class ReplyFocus(private val focus: AudioFocus) {
    private val lock = Any()
    private var held = false
    private var replies = 0

    // Another app speaks over Kestrel, which plays more quietly until Android gives the focus back.
    private var lowered = false

    /** A reply was asked for. Call [finish] once for it when it ends, however it ends. */
    fun asked() {
        synchronized(lock) { replies++ }
    }

    /** A reply's turn has come. Returns whether Kestrel holds the focus, and asks Android for it when it does not. */
    fun take(): Boolean {
        synchronized(lock) {
            if (!held) held = focus.request()
            return held
        }
    }

    /** A reply ended. Gives the focus up when no other reply waits. */
    fun finish() {
        synchronized(lock) {
            replies--
            if (replies == 0 && held) {
                held = false
                lowered = false
                focus.abandon()
            }
        }
    }

    /** The volume at which the player plays now: [QUIET_VOLUME] while another app speaks over Kestrel, and 1 otherwise. */
    fun volume(): Float = synchronized(lock) { if (lowered) QUIET_VOLUME else 1f }

    /**
     * Android changed Kestrel's focus to [change], one of AudioManager's `AUDIOFOCUS_` values, and returns what the
     * player does. A loss that lets Kestrel lower its volume, as for a notification's sound or a navigation prompt, keeps
     * the reply going more quietly ([FocusChange.LOWER]) until the focus comes back ([FocusChange.RESTORE]). A transient
     * or lasting loss, as for a call, an app that asks for exclusive focus or another assistant, stops the reply
     * ([FocusChange.STOP]); Kestrel gives the focus up then, so that Android does not give it back in the middle of a
     * later reply.
     */
    fun changed(change: Int): FocusChange {
        synchronized(lock) {
            if (!held) return FocusChange.NONE
            return when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    lowered = true
                    FocusChange.LOWER
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (lowered) {
                    lowered = false
                    FocusChange.RESTORE
                } else {
                    FocusChange.NONE
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    held = false
                    lowered = false
                    focus.abandon()
                    FocusChange.STOP
                }
                else -> FocusChange.NONE
            }
        }
    }

    internal companion object {
        // About 14 dB quieter, as Android lowers other apps' audio.
        const val QUIET_VOLUME = 0.2f
    }
}

/** What the player does after a change of its audio focus (design spec, section 8.4). */
internal enum class FocusChange { NONE, STOP, LOWER, RESTORE }

/**
 * Transient focus that lets other apps lower their volume, for audio with [attributes]. Each instance has its own
 * request with its own listener, because Android tells requests apart by their listener: without one, every request
 * of Kestrel's would have the same client ID, and one player's abandon would drop another's focus (plan 2B, Task 5,
 * review N5). Android calls [onChange] with each change, on the main thread.
 */
internal class AndroidAudioFocus(context: Context, attributes: AudioAttributes, onChange: (Int) -> Unit) : AudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    // Android does not lower the volume of speech by itself, because that would make it hard to understand; it reports
    // a "may duck" loss to Kestrel instead, and ReplyFocus lowers the volume.
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> onChange(change) }
        .build()

    override fun request(): Boolean {
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        // Android keeps a refused request's listener until it is abandoned, which would keep this player reachable.
        audio.abandonAudioFocusRequest(request)
        return false
    }

    override fun abandon() {
        audio.abandonAudioFocusRequest(request)
    }
}
```

- [ ] **Step 4: Hold the focus through `ReplyFocus` in the player**

In `core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt`, replace

```kotlin
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
```

with

```kotlin
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
```

Replace

```kotlin
    /** Android refused audio focus, so Kestrel spoke nothing (B5). */
    REFUSED,
}
```

with

```kotlin
    /**
     * Android refused audio focus, so Kestrel spoke nothing: in the background without a suitable foreground service
     * (B5), or while a call rings or lasts (prototype P3).
     */
    REFUSED,

    /**
     * Android took Kestrel's audio focus away while the reply played or waited, as for a ringing call or an app that asks
     * for exclusive focus, so Kestrel stopped it as [VoicePlayer.stop] would (design spec, section 14.6). The caller shows
     * its text instead.
     */
    INTERRUPTED,
}
```

Replace

```kotlin
 * sentence while one plays, so that it starts soon and [stop] takes effect at once. The audio has the usage "assistant",
 * and other apps lower their volume while Kestrel speaks. Android 17 refuses audio focus to an app in the background
 * without a suitable foreground service (B5); then the player speaks nothing and returns [Spoke.REFUSED]. One reply
 * plays at a time, in the order of the [speak] calls.
```

with

```kotlin
 * sentence while one plays, so that it starts soon and [stop] takes effect at once. The audio has the usage "assistant",
 * and other apps lower their volume while Kestrel speaks. Kestrel keeps its audio focus between the replies of a queue
 * and gives it up once no reply waits. Android 17 refuses audio focus to an app in the background without a suitable
 * foreground service (B5), and to every app while a call rings or lasts (prototype P3); then the player speaks nothing
 * and returns [Spoke.REFUSED]. When another app only lets Kestrel lower its volume, as for a notification's sound, the
 * player speaks on more quietly until the focus comes back. When Android takes the focus away while Kestrel speaks, for
 * a ringing call or an app that asks for exclusive focus, the player stops at once, and the replies that played or
 * waited return [Spoke.INTERRUPTED]. One reply plays at a time, in the order of the [speak] calls.
```

Replace

```kotlin
class VoicePlayer(context: Context, private val synthesizer: Synthesizer) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
```

with

```kotlin
class VoicePlayer(context: Context, private val synthesizer: Synthesizer) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = ReplyFocus(AndroidAudioFocus(context, attributes) { change -> focusChanged(change) })
```

Replace

```kotlin
    private var track: AudioTrack? = null
    private var clips: Channel<Clip>? = null
```

with

```kotlin
    private var track: AudioTrack? = null
    private var clips: Channel<Clip>? = null

    // The generations whose replies a loss of the audio focus ended. Only code that holds [lock] uses it.
    private val interruptedGenerations = mutableSetOf<Int>()
```

Replace

```kotlin
     * Speaks [text] after the replies that came before it, and completes when it has been spoken, stopped or refused, or
     * with the error when synthesis or the audio output fails. A [stop] after this call ends the reply, also when it has
     * not begun yet. A reply whose sentences all lack letters and digits plays nothing and completes with
     * [Spoke.FINISHED]. After [close], the reply completes with [Spoke.STOPPED] at once.
```

with

```kotlin
     * Speaks [text] after the replies that came before it, and completes when it has been spoken, stopped, interrupted
     * or refused, or with the error when synthesis or the audio output fails. A [stop] after this call ends the reply,
     * also when it has not begun yet. A reply whose sentences all lack letters and digits plays nothing and completes
     * with [Spoke.FINISHED]. After [close], the reply completes with [Spoke.STOPPED] at once.
```

Replace

```kotlin
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            turn.withLock {
                yield()
                if (generation.get() != mine) return@withLock Spoke.STOPPED
                // The synthesizer serves one sentence at a time, and a stopped reply's last sentence may still be in it.
                synthesis?.join()
                val sentences = Sentences.split(text).filter { sentence -> sentence.any(Char::isLetterOrDigit) }
                when {
                    generation.get() != mine -> Spoke.STOPPED
                    sentences.isEmpty() -> Spoke.FINISHED
                    else -> withFocus { withTrack { output -> play(output, sentences, mine) } }
                }
            }
        }
```

with

```kotlin
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            // Counted before the reply waits for its turn, so that the focus stays while it waits (section 8.4).
            focus.asked()
            try {
                val spoke = turn.withLock {
                    yield()
                    if (generation.get() != mine) return@withLock Spoke.STOPPED
                    // The synthesizer serves one sentence at a time, and a stopped reply's last sentence may still be
                    // in it.
                    synthesis?.join()
                    val sentences = Sentences.split(text).filter { sentence -> sentence.any(Char::isLetterOrDigit) }
                    when {
                        generation.get() != mine -> Spoke.STOPPED
                        sentences.isEmpty() -> Spoke.FINISHED
                        !focus.take() -> Spoke.REFUSED
                        else -> withTrack { output -> play(output, sentences, mine) }
                    }
                }
                // Section 14.6: a reply that a loss of the focus ended goes to the bar.
                if (spoke == Spoke.STOPPED && wasInterrupted(mine)) Spoke.INTERRUPTED else spoke
            } finally {
                focus.finish()
            }
        }
```

Replace

```kotlin
    /** Stops the reply that plays and the replies that were asked for before. Any thread may call it. */
    fun stop() {
        // A reply that reads the new generation hands over its channel and track only after this section, so the stop
        // never ends a reply that was asked for after it.
        synchronized(lock) {
            generation.incrementAndGet()
            clips?.close()
            track?.run {
                pause()
                flush()
            }
        }
    }
```

with

```kotlin
    /** Stops the reply that plays and the replies that were asked for before. Any thread may call it. */
    fun stop() {
        synchronized(lock) { endGeneration() }
    }

    // Section 8.4: another app that only lets Kestrel lower its volume, such as a notification's sound, makes the reply
    // quieter until the focus comes back. Section 14.6: when Android takes the focus away, for a call or an app that asks
    // for exclusive focus, the replies that play or wait end as a stop ends them, and they count as interrupted. Android
    // calls it on the main thread.
    private fun focusChanged(change: Int) {
        when (focus.changed(change)) {
            FocusChange.NONE -> Unit
            FocusChange.LOWER, FocusChange.RESTORE -> synchronized(lock) { track?.setVolume(focus.volume()) }
            FocusChange.STOP -> synchronized(lock) {
                interruptedGenerations += generation.get()
                endGeneration()
            }
        }
    }

    // A reply that reads the new generation hands over its channel and track only after the caller's section, so a stop
    // never ends a reply that was asked for after it. The caller holds [lock].
    private fun endGeneration() {
        generation.incrementAndGet()
        clips?.close()
        track?.run {
            pause()
            flush()
        }
    }

    private fun wasInterrupted(mine: Int): Boolean = synchronized(lock) { mine in interruptedGenerations }
```

Replace

```kotlin
        synchronized(lock) { track = output }
        try {
            return speak(output)
```

with

```kotlin
        synchronized(lock) {
            track = output
            // A reply that starts while another app speaks over Kestrel starts quietly too (section 8.4).
            output.setVolume(focus.volume())
        }
        try {
            return speak(output)
```

Replace

```kotlin
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
```

with

```kotlin
    private class Clip(val text: String, val samples: FloatArray)
```

- [ ] **Step 5: Show an interrupted reply on the bar**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
        // Section 14.6: a reply that Kestrel cannot speak goes to the bar, except "Yes?", which the bar shows already
        // at the start of the wait for the task (Feedback.AwaitingTask).
```

with

```kotlin
        // Section 14.6: a reply that Kestrel cannot speak, or that Android interrupted, goes to the bar, except "Yes?",
        // which the bar shows already at the start of the wait for the task (Feedback.AwaitingTask).
```

Replace

```kotlin
            } else if (error == null && spoken.getCompleted() == Spoke.REFUSED) {
                showInstead()
            }
```

with

```kotlin
            } else if (error == null && spoken.getCompleted().let { it == Spoke.REFUSED || it == Spoke.INTERRUPTED }) {
                showInstead()
            }
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    /** A reply has been spoken, stopped or refused, or speaking it failed. Send it once for each reply, or recognition stays off. */
```

with

```kotlin
    /**
     * A reply has been spoken, stopped, interrupted or refused, or speaking it failed. Send it once for each reply, or
     * recognition stays off.
     */
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.speak.ReplyFocusTest :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 6 tests in `ReplyFocusTest` and 96 in `ListeningTest` (95 and `aReplyThatAndroidInterruptsIsShownOnTheBar`).

- [ ] **Step 7: Add the device test of another app's focus**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoicePlayerTest.kt`, replace

```kotlin
import android.media.AudioAttributes
import android.media.AudioManager
```

with

```kotlin
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
```

Replace

```kotlin
    @Test
    fun stopDuringTheSynthesisOfALongSentenceEndsTheReplyAtOnce() = withPlayer { player ->
```

with

```kotlin
    @Test
    fun anotherAppsFocusStopsTheReplyAtOnceAndTheNextReplyAsksAgain() = withPlayer { player ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        // Exclusive transient focus, as another assistant would ask for it; a request that lets others duck would only
        // make Kestrel quieter. Its own listener gives it a client ID of its own.
        val prompt = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        runBlocking {
            val reply = player.speak("This is a long reply. It has several sentences. Each of them takes a while. A prompt stops it.")
            delay(1_500)
            val requestedAt = SystemClock.elapsedRealtime()
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(prompt))
                // Section 14.6: the reply counts as interrupted, so that its text goes to the bar.
                assertEquals(Spoke.INTERRUPTED, withTimeout(10_000) { reply.await() })
                val millis = SystemClock.elapsedRealtime() - requestedAt
                VoiceTests.log("Voice output: another app's focus stopped the reply in $millis ms")
                assertTrue("Stopping took $millis ms", millis < 500)
            } finally {
                audio.abandonAudioFocusRequest(prompt)
            }
            assertEquals(Spoke.FINISHED, withTimeout(10_000) { player.speak("Done.").await() })
        }
    }

    @Test
    fun aSoundThatLetsKestrelDuckLeavesTheReplyPlaying() = withPlayer { player ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        // A notification's sound, as Android's notification player asks for it, with a listener of its own.
        val sound = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        runBlocking {
            val reply = player.speak("This is a long reply. It has several sentences. A short sound comes while it plays.")
            delay(1_500)
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(sound))
                delay(500)
            } finally {
                audio.abandonAudioFocusRequest(sound)
            }
            // Section 8.4: Kestrel spoke on under the sound, more quietly, and finished the reply.
            assertEquals(Spoke.FINISHED, withTimeout(30_000) { reply.await() })
        }
    }

    @Test
    fun stopDuringTheSynthesisOfALongSentenceEndsTheReplyAtOnce() = withPlayer { player ->
```

- [ ] **Step 8: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.4, replace

```markdown
While it speaks, it holds transient audio focus that lets other apps lower their volume.
```

with

```markdown
While it speaks, it holds transient audio focus that lets other apps lower their volume, and it keeps the focus between the replies of one answer. When another app asks for focus that lets others duck, as for a notification's sound or a navigation prompt, Kestrel speaks on and lowers its own volume until the focus comes back, because Android does not lower the volume of speech by itself. When Android takes the focus away, as for a ringing call, an app that asks for exclusive focus or another assistant, Kestrel stops speaking at once and shows the reply's text on the feedback bar (section 14.6).
```

In section 14.6, replace

```markdown
| Voice output fails | Kestrel shows its text on screen only |
```

with

```markdown
| Voice output fails | Kestrel shows its text on screen only |
| Android takes the audio focus away while Kestrel speaks, for a ringing call or an app that asks for exclusive focus | Kestrel stops speaking at once and shows the reply's text on the feedback bar |
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (191 in `:app`, 122 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 10: Run the voice device tests**

`VoicePlayerTest` runs the new test; `ConversationTest`, `RefusedReplyTest` and `VoiceSessionTest` speak through the changed player.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 68 run, 0 failed, 0 errors, 0 skipped." (66, `anotherAppsFocusStopsTheReplyAtOnceAndTheNextReplyAsksAgain` and `aSoundThatLetsKestrelDuckLeavesTheReplyPlaying`), the log line "Voice output: another app's focus stopped the reply in <n> ms", and no warning from the restores at the end.

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/speak/ReplyFocus.kt core/voice/src/main/kotlin/dev/kestrel/voice/speak/VoicePlayer.kt core/voice/src/test/kotlin/dev/kestrel/voice/speak/ReplyFocusTest.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoicePlayerTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(voice): stop speaking when Android takes the audio focus away, and keep the focus between queued replies" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The call state and the modes Ringing and In call

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/CallMonitor.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`Mode`, new `CallState`, `State`, `Event.CallState`, `Rules.next`, `heard`, `spotted`, `turnOff`, new `call`, `enterCall`, `afterCall`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`ListeningTexts`, the class's KDoc and constructor, `remember`, `open`, `shutdown`, new `startCalls`, `say`, `text`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt` (the class's KDoc, `notification`), `app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt` (the KDoc, `show`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `calls`)
- Modify: `app/src/main/AndroidManifest.xml` (`READ_PHONE_STATE`), `app/src/main/res/values/strings.xml` (four strings)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.5)
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `Rules.next(state, event)`, `switch(state, mode, vararg first)`, `Effect.StopSaying`, `Effect.CancelTask`, `Feedback.ModeChanged`; `Listening(scope, thread, sessions, services, bar, report, openSettings, memory, conditions, notices, exits, clock, microphones)`, `Listening.open()`, `shutdown()`, `noteMode`, `remember`.
- Produces: `enum class Mode { OFF, LISTENING, PAUSED, RINGING, IN_CALL }`; `enum class CallState { IDLE, RINGING, ACTIVE }` (package `dev.kestrel.app.orchestrator`); `Event.CallState(state: CallState)`; `State.beforeCall: Mode?` and `State.inCall: Boolean`; `internal interface CallMonitor { fun start(report: (CallState) -> Unit); fun stop() }`, `internal class AndroidCallMonitor(context: Context) : CallMonitor`, `internal fun callStateOf(state: Int): CallState`; the constructor parameter `calls: CallMonitor` of `Listening`; the private function `Listening.startCalls()`; `ListeningTexts.RINGING` and `IN_CALL`; the strings `listening_title_ringing`, `listening_title_in_call`, `tile_ringing` and `tile_in_call`. Task 3 adds the call commands to the Rules and `watchCalls()` to `Listening`; Task 4 makes `Event.Assist` change nothing during a call.

Design D1 and the final review of plan 2C-2 ("For plan 2D", first item). A `TelephonyCallback.CallStateListener`, registered while a voice session is open and only with `READ_PHONE_STATE`, reports the call state as `Event.CallState`. A call that rings while Kestrel listens or is paused starts the mode Ringing; an answered call, or a call that is already active when Kestrel starts, the mode In call; the end of the call brings back the mode before it. The call cancels a running task and the wait for a task, and stops Kestrel's speech at once, because Android refuses the audio focus during a call (P3). While a call rings, Kestrel records also on the lock screen, which is section 8.5's one exception, and recognises every utterance, because the call commands of Task 3 come without the name; until Task 3, every transcript is ignored during a call. During the call the microphone is off. The stored mode is never Ringing or In call: `remember` stores the mode that the call interrupted, so that a restart during a call brings back the mode that comes after it; `noteMode` still clears the time of a restore at every Off, and the restore still sends `Event.Start(paused = stored.mode == PAUSED)` (final review of plan 2C-4, "For plan 2D", item 4). Transcripts without words and `NothingHeard` do not count towards the hint about the microphone during a call: recognition then takes only the call commands, and the other person's voice and the ringtone give many transcripts without words, so the hint would be wrong. During an active call without the accessibility service, Android silences Kestrel (P3); the first "Microphone blocked" then also runs the battery check, which turns Kestrel off only when its battery use is Restricted and Android demoted its service, as it should then. During a call the toggle, the pause and the resume choose that mode, and the bar shows it; the notification offers only Stop, and the tile shows the call. Revoking `READ_PHONE_STATE` ends Kestrel's process (P3), so the listener never outlives the permission. No task of this plan adds a way to turn off that does not go through `turnOff` or `stopBecause` (item 3 of the same list).

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace

```kotlin
    @Test
    fun neverMindEndsTheWaitForATask() {
```

with

```kotlin
    @Test
    fun aCallRingsWhileKestrelListensOrIsPausedAndTheModeComesBackAfterIt() {
        for (before in listOf(listening, paused)) {
            val ringing = Rules.next(before, Event.CallState(CallState.RINGING))
            assertEquals(Mode.RINGING, ringing.state.mode)
            assertEquals(before.mode, ringing.state.beforeCall)
            assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.RINGING))), ringing.effects)

            val answered = Rules.next(ringing.state, Event.CallState(CallState.ACTIVE))
            assertEquals(Mode.IN_CALL, answered.state.mode)
            assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.IN_CALL))), answered.effects)

            val ended = Rules.next(answered.state, Event.CallState(CallState.IDLE))
            assertEquals(Step(before, listOf(Effect.Show(Feedback.ModeChanged(before.mode)))), ended)
        }
    }

    @Test
    fun aCallThatRingsStopsTheSpeechAndCancelsTheTask() {
        val busy = listening.copy(work = Work.RUNNING_TASK, task = 2, speeches = 1)

        val ringing = Rules.next(busy, Event.CallState(CallState.RINGING))

        assertEquals(
            listOf(Effect.StopSaying, Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.RINGING))),
            ringing.effects,
        )
        assertEquals(Work.IDLE, ringing.state.work)
        assertTrue(ringing.state.cancelled)
        // A wait for a task ends too, and nothing comes back after the call (section 14.4).
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        assertEquals(Work.IDLE, Rules.next(waiting, Event.CallState(CallState.RINGING)).state.work)
    }

    @Test
    fun aCallThatIsActiveWithoutRingingIsInCall() {
        // An outgoing call, or one that was active when Kestrel started.
        val active = Rules.next(paused, Event.CallState(CallState.ACTIVE))

        assertEquals(Mode.IN_CALL, active.state.mode)
        assertEquals(Mode.PAUSED, active.state.beforeCall)
        assertEquals(Mode.PAUSED, Rules.next(active.state, Event.CallState(CallState.IDLE)).state.mode)
        // A second report of a call changes nothing.
        assertEquals(Step(active.state), Rules.next(active.state, Event.CallState(CallState.ACTIVE)))
        assertEquals(Step(active.state), Rules.next(active.state, Event.CallState(CallState.RINGING)))
    }

    @Test
    fun callsChangeNothingWhileKestrelIsOffOrWithoutACall() {
        for (call in CallState.entries) {
            assertEquals(Step(State()), Rules.next(State(), Event.CallState(call)))
        }
        assertEquals(Step(listening), Rules.next(listening, Event.CallState(CallState.IDLE)))
    }

    @Test
    fun whileACallRingsKestrelRecordsAlsoOnTheLockScreenButNotDuringTheCall() {
        val locked = listening.copy(screenOn = false)
        val ringing = Rules.next(locked, Event.CallState(CallState.RINGING)).state

        assertFalse(locked.microphone)
        // Section 8.5: the one exception to the rule that Kestrel records only while the phone is unlocked. The call
        // commands come without the name.
        assertTrue(ringing.microphone)
        assertEquals(Recognition.ALL, ringing.recognition)
        val inCall = Rules.next(ringing.copy(screenOn = true), Event.CallState(CallState.ACTIVE)).state
        assertFalse(inCall.microphone)
        assertEquals(Recognition.NONE, inCall.recognition)
    }

    @Test
    fun duringACallTheTogglePauseAndResumeChooseTheModeAfterIt() {
        val ringing = Rules.next(listening, Event.CallState(CallState.RINGING)).state

        val chosen = Rules.next(ringing, Event.Pause)
        assertEquals(Mode.RINGING, chosen.state.mode)
        assertEquals(Mode.PAUSED, chosen.state.beforeCall)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.PAUSED))), chosen.effects)
        assertEquals(Step(chosen.state), Rules.next(chosen.state, Event.Pause))
        assertEquals(Mode.LISTENING, Rules.next(chosen.state, Event.Toggle).state.beforeCall)
        assertEquals(Mode.LISTENING, Rules.next(chosen.state, Event.Resume).state.beforeCall)
        assertEquals(Mode.PAUSED, Rules.next(chosen.state, Event.CallState(CallState.IDLE)).state.mode)
    }

    @Test
    fun transcriptsAndKeywordsOtherThanTheCallCommandsAreIgnoredDuringACall() {
        val ringing = Rules.next(listening, Event.CallState(CallState.RINGING)).state

        assertEquals(Step(ringing), Rules.next(ringing, Event.Heard("Kestrel, open settings.", named = true)))
        assertEquals(Step(ringing), Rules.next(ringing, Event.Heard("Kestrel", named = true)))
        val speaking = ringing.copy(speeches = 1)
        assertEquals(Step(speaking), Rules.next(speaking, Event.Spotted(Keyword.KESTREL)))
        // Transcripts without words do not count towards the hint during a call (section 14.6).
        val counting = ringing.copy(emptyTranscripts = 2)
        assertEquals(Step(counting), Rules.next(counting, Event.Heard("", named = false)))
        assertEquals(Step(counting), Rules.next(counting, Event.NothingHeard))
    }

    @Test
    fun turningOffDuringACallForgetsTheCall() {
        val inCall = Rules.next(listening, Event.CallState(CallState.ACTIVE)).state

        val off = Rules.next(inCall, Event.TurnOff)

        assertEquals(State(), off.state)
        // The end of the call changes nothing afterwards.
        assertEquals(Step(State()), Rules.next(off.state, Event.CallState(CallState.IDLE)))
    }

    @Test
    fun neverMindEndsTheWaitForATask() {
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
```

with

```kotlin
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
```

Replace

```kotlin
    private val exits = FakeExits()
```

with

```kotlin
    private val exits = FakeExits()
    private val calls = FakeCallMonitor()
```

Replace

```kotlin
    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

with

```kotlin
    @Test
    fun theCallStateIsWatchedWhileASessionIsOpen() = runTest {
        val listening = listeningWithSession()
        assertEquals(1, calls.starts)

        calls.change(CallState.RINGING)
        runCurrent()
        assertEquals(Mode.RINGING, listening.state.value.mode)

        listening.send(Event.TurnOff)
        runCurrent()
        assertEquals(1, calls.stops)
        assertEquals(Mode.OFF, listening.state.value.mode)
    }

    @Test
    fun aCallIsNeverStoredButTheModeThatItInterrupted() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Pause)
        runCurrent()

        calls.change(CallState.RINGING)
        runCurrent()
        calls.change(CallState.ACTIVE)
        runCurrent()
        // The owner chooses listening for after the call, with the tile.
        listening.send(Event.Toggle)
        runCurrent()
        calls.change(CallState.IDLE)
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        // Section 8.5: a restart during the call brings back the mode that comes after it.
        assertEquals(listOf(Mode.LISTENING, Mode.PAUSED, Mode.LISTENING), memory.writes)
        assertEquals(
            listOf(
                ListeningTexts.LISTENING,
                ListeningTexts.PAUSED,
                ListeningTexts.RINGING,
                ListeningTexts.IN_CALL,
                ListeningTexts.LISTENING,
                ListeningTexts.LISTENING,
            ),
            shown,
        )
    }

    @Test
    fun theMicrophoneRecordsWhileACallRingsOnTheLockScreen() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Screen(on = false))
        runCurrent()
        log.clear()

        calls.change(CallState.RINGING)
        runCurrent()
        // Section 8.5: a ringing call is the one exception to the rule that Kestrel records only while the phone is
        // unlocked.
        assertEquals(listOf("microphone on"), log)

        calls.change(CallState.IDLE)
        runCurrent()
        assertEquals(listOf("microphone on", "microphone off"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aCallStateThatCannotBeWatchedIsReportedAndKestrelListensOn() = runTest {
        calls.failure = SecurityException("No permission to read the phone state")
        val listening = listeningWithSession()

        assertEquals("Watching the call state", reports.single().first)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun aReplyThatACallStopsIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        shown.clear()

        calls.change(CallState.RINGING)
        runCurrent()
        // The call's StopSaying ended the reply before Android took the audio focus away (prototype P3).
        session.replies.single().complete(Spoke.STOPPED)
        runCurrent()

        // Section 14.6: the reply's text goes to the bar after the call's mode.
        assertEquals(listOf(ListeningTexts.RINGING, ListeningTexts.NO_TASKS), shown)
    }

    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

Replace

```kotlin
        clock = { now },
        microphones = { microphone },
    )
```

with

```kotlin
        clock = { now },
        calls = calls,
        microphones = { microphone },
    )
```

Replace

```kotlin
    private class FakeMicrophone : Microphone {
```

with

```kotlin
    private class FakeCallMonitor : CallMonitor {
        var starts = 0
        var stops = 0
        var failure: Exception? = null
        private var report: ((CallState) -> Unit)? = null

        override fun start(report: (CallState) -> Unit) {
            starts++
            failure?.let { throw it }
            this.report = report
        }

        override fun stop() {
            stops++
            report = null
        }

        /** The phone's call state changes to [state], as Android's listener reports it. */
        fun change(state: CallState) {
            checkNotNull(report) { "Nothing watches the call state" }.invoke(state)
        }
    }

    private class FakeMicrophone : Microphone {
```

Replace

```kotlin
            exits = exits,
            clock = { now },
            // Its first write throws an Error, and the later ones work.
```

with

```kotlin
            exits = exits,
            clock = { now },
            calls = calls,
            // Its first write throws an Error, and the later ones work.
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'CallState'", "Unresolved reference 'CallMonitor'", "Unresolved reference 'beforeCall'" and "No parameter with name 'calls' found.".

- [ ] **Step 3: Add the modes and the call state to the Rules**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
/** The listening modes (design spec, section 8.5). Ringing and In call come with calls in plan 2D. */
enum class Mode { OFF, LISTENING, PAUSED }
```

with

```kotlin
/**
 * The listening modes (design spec, section 8.5). [RINGING]: a call rings, and Kestrel listens only for the call
 * commands, also on the lock screen. [IN_CALL]: a call is active, and Kestrel is paused.
 */
enum class Mode { OFF, LISTENING, PAUSED, RINGING, IN_CALL }

/** The phone's call state (design spec, section 8.7), as Android's call-state listener reports it. */
enum class CallState { IDLE, RINGING, ACTIVE }
```

Replace

```kotlin
 * [emptyTranscripts] counts the transcripts without words in a row, and [noWordsShown] is true once the hint about them
 * has shown since Kestrel turned on (section 14.6).
 */
```

with

```kotlin
 * [emptyTranscripts] counts the transcripts without words in a row, and [noWordsShown] is true once the hint about them
 * has shown since Kestrel turned on (section 14.6). [beforeCall] is the mode that a call interrupted, while the mode is
 * Ringing or In call; it comes back after the call (section 8.7).
 */
```

Replace

```kotlin
    val noWordsShown: Boolean = false,
) {
    val speaking: Boolean get() = speeches > 0

    /** Whether Kestrel records: in every mode but off, while the screen is on and the phone unlocked (section 8.5). */
    val microphone: Boolean get() = mode != Mode.OFF && screenOn
```

with

```kotlin
    val noWordsShown: Boolean = false,
    val beforeCall: Mode? = null,
) {
    val speaking: Boolean get() = speeches > 0

    /** Whether a call rings or is active (section 8.7). */
    val inCall: Boolean get() = mode == Mode.RINGING || mode == Mode.IN_CALL

    /**
     * Whether Kestrel records: while it listens or is paused, with the screen on and the phone unlocked, and while a call
     * rings, also on the lock screen (section 8.5). Not during a call.
     */
    val microphone: Boolean
        get() = when (mode) {
            Mode.OFF, Mode.IN_CALL -> false
            Mode.RINGING -> true
            Mode.LISTENING, Mode.PAUSED -> screenOn
        }
```

Replace

```kotlin
            speaking -> Recognition.NONE
```

with

```kotlin
            speaking -> Recognition.NONE
            // The call commands come without the name (section 8.7).
            mode == Mode.RINGING -> Recognition.ALL
            mode == Mode.IN_CALL -> Recognition.NONE
```

Replace

```kotlin
    data object NothingHeard : Event
}
```

with

```kotlin
    data object NothingHeard : Event

    /** The phone's call state changed (section 8.7). Only Listening's call-state listener sends it. */
    data class CallState(val state: dev.kestrel.app.orchestrator.CallState) : Event
}
```

Replace

```kotlin
        Event.Toggle -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
```

with

```kotlin
        Event.Toggle -> when {
            state.inCall -> afterCall(state, if (state.beforeCall == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
            else -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        }
```

Replace

```kotlin
        Event.Pause -> if (state.mode == Mode.LISTENING) switch(state, Mode.PAUSED) else Step(state)
        // The notification exists only while Kestrel is on, so a Resume while off comes from a stale notification.
        Event.Resume -> if (state.mode == Mode.PAUSED) switch(state, Mode.LISTENING) else Step(state)
```

with

```kotlin
        Event.Pause -> when {
            state.inCall -> afterCall(state, Mode.PAUSED)
            state.mode == Mode.LISTENING -> switch(state, Mode.PAUSED)
            else -> Step(state)
        }
        // The notification exists only while Kestrel is on, so a Resume while off comes from a stale notification.
        Event.Resume -> when {
            state.inCall -> afterCall(state, Mode.LISTENING)
            state.mode == Mode.PAUSED -> switch(state, Mode.LISTENING)
            else -> Step(state)
        }
```

Replace

```kotlin
        Event.NothingHeard ->
            if (state.mode != Mode.OFF && state.work == Work.AWAITING_TASK) waitForTask(state) else Step(state)
    }
```

with

```kotlin
        Event.NothingHeard ->
            if (state.mode != Mode.OFF && state.work == Work.AWAITING_TASK) waitForTask(state) else Step(state)
        is Event.CallState -> call(state, event.state)
    }
```

Replace

```kotlin
        if (state.mode == Mode.OFF) return Step(state)
        val awaiting = state.work == Work.AWAITING_TASK
```

with

```kotlin
        // During a call, only the call commands count (section 8.7).
        if (state.mode == Mode.OFF || state.inCall) return Step(state)
        val awaiting = state.work == Work.AWAITING_TASK
```

Replace

```kotlin
        if (!state.speaking || state.mode == Mode.OFF) return Step(state)
```

with

```kotlin
        if (!state.speaking || state.mode == Mode.OFF || state.inCall) return Step(state)
```

Replace

```kotlin
            emptyTranscripts = 0,
            noWordsShown = false,
        ),
```

with

```kotlin
            emptyTranscripts = 0,
            noWordsShown = false,
            beforeCall = null,
        ),
```

Replace

```kotlin
    /** This step, then the step that [next] makes from its state. */
```

with

```kotlin
    // Section 8.7: a call that rings while Kestrel listens or is paused, or that is active, interrupts the mode, and the
    // mode comes back after the call. While Kestrel is off, calls change nothing.
    private fun call(state: State, call: CallState): Step = when (call) {
        CallState.RINGING ->
            if (state.mode == Mode.LISTENING || state.mode == Mode.PAUSED) enterCall(state, Mode.RINGING) else Step(state)
        CallState.ACTIVE -> when (state.mode) {
            Mode.LISTENING, Mode.PAUSED -> enterCall(state, Mode.IN_CALL)
            Mode.RINGING -> switch(state, Mode.IN_CALL)
            Mode.OFF, Mode.IN_CALL -> Step(state)
        }
        CallState.IDLE -> {
            val back = state.beforeCall
            if (!state.inCall || back == null) Step(state) else switch(state.copy(beforeCall = null), back)
        }
    }

    // Kestrel stops speaking at once, because Android refuses its audio focus during a call (prototype P3). The call
    // cancels a running task and ends the wait for one; sub-project 5 asks "Continue the task?" afterwards (section 14.4).
    private fun enterCall(state: State, mode: Mode): Step = switch(
        state.copy(beforeCall = state.mode, work = Work.IDLE, cancelled = state.cancelled || state.work == Work.RUNNING_TASK),
        mode,
        *listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.CancelTask.takeIf { state.work == Work.RUNNING_TASK },
        ).toTypedArray(),
    )

    // During a call, the toggle, the pause and the resume choose the mode that comes back after the call, and the bar
    // shows it.
    private fun afterCall(state: State, mode: Mode): Step =
        if (state.beforeCall == mode) Step(state) else Step(state.copy(beforeCall = mode), listOf(Effect.Show(Feedback.ModeChanged(mode))))

    /** This step, then the step that [next] makes from its state. */
```

- [ ] **Step 4: Watch the call state while a session is open**

Create `app/src/main/kotlin/dev/kestrel/app/listen/CallMonitor.kt`:

```kotlin
package dev.kestrel.app.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.kestrel.app.orchestrator.CallState
import java.util.concurrent.Executor

/** Tells Kestrel the phone's call state (design spec, section 8.7). Any thread may call it. */
internal interface CallMonitor {
    /**
     * Starts to report the call state to [report]: the state at the start, and then every change. Does nothing without
     * the permission READ_PHONE_STATE, so that Kestrel never enters Ringing then. [report] is called on a thread of
     * Android's and must return at once. A start replaces the reports of an earlier one.
     */
    fun start(report: (CallState) -> Unit)

    /** Stops the reports. A report that is on its way may still come. */
    fun stop()
}

/**
 * The call state from Android's call-state listener, which reports a ringing call, an answer and the end of a call
 * within about a second (prototype P3). It reads no phone number and no call log. Revoking READ_PHONE_STATE ends
 * Kestrel's process, so the listener never outlives the permission.
 */
internal class AndroidCallMonitor(private val context: Context) : CallMonitor {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val lock = Any()
    private var listener: TelephonyCallback? = null

    override fun start(report: (CallState) -> Unit) {
        synchronized(lock) {
            stopLocked()
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    report(callStateOf(state))
                }
            }
            // Android's binder thread reports at once; the report only queues an event.
            telephony.registerTelephonyCallback(Executor { command -> command.run() }, callback)
            listener = callback
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        listener?.let { telephony.unregisterTelephonyCallback(it) }
        listener = null
    }
}

/** Android's call state, one of TelephonyManager's `CALL_STATE_` values, as Kestrel's [CallState]. */
internal fun callStateOf(state: Int): CallState = when (state) {
    TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
    TelephonyManager.CALL_STATE_OFFHOOK -> CallState.ACTIVE
    else -> CallState.IDLE
}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    const val OFF = "Off"
```

with

```kotlin
    const val OFF = "Off"
    const val RINGING = "A call is ringing."
    const val IN_CALL = "Paused during the call"
```

Replace

```kotlin
 */
class Listening internal constructor(
```

with

```kotlin
 * [calls] reports the call state while a voice session is open (section 8.7). The stored mode is never Ringing or In
 * call, but the mode that the call interrupted.
 */
class Listening internal constructor(
```

Replace

```kotlin
    private val clock: () -> Long,
    microphones: () -> Microphone,
) {
```

with

```kotlin
    private val clock: () -> Long,
    private val calls: CallMonitor,
    microphones: () -> Microphone,
) {
```

Replace

```kotlin
    private fun remember(mode: Mode) {
        if (mode != Mode.OFF && session == null) return
        if (mode == lastMode) return
        lastMode = mode
        modeToStore.value = mode
    }
```

with

```kotlin
    private fun remember(mode: Mode) {
        // Section 8.5: a call's mode is never stored, but the mode that the call interrupted, which comes back after it.
        val lasting = if (mode == Mode.RINGING || mode == Mode.IN_CALL) state.value.beforeCall ?: return else mode
        if (lasting != Mode.OFF && session == null) return
        if (lasting == lastMode) return
        lastMode = lasting
        modeToStore.value = lasting
    }
```

Replace

```kotlin
                // After followSafely, which starts the microphone, so that a native crash there comes before Listening is
                // stored.
                remember(state.value.mode)
```

with

```kotlin
                // After followSafely, which starts the microphone, so that a native crash there comes before Listening is
                // stored.
                remember(state.value.mode)
                // Last, so that a failure here costs only the call state (section 8.7).
                startCalls()
```

Replace

```kotlin
        // The Orchestrator may turn Kestrel on again in the same run of events. Its state then equals the one that the
        // collector saw last, and StateFlow skips an equal value, so follow() would never see this Off.
        scope.launch(thread) { followSafely(state.value) }
    }
```

with

```kotlin
        // The Orchestrator may turn Kestrel on again in the same run of events. Its state then equals the one that the
        // collector saw last, and StateFlow skips an equal value, so follow() would never see this Off.
        scope.launch(thread) { followSafely(state.value) }
        // Last, so that a failure here never keeps the session or the service while Kestrel is off (section 8.7).
        try {
            calls.stop()
        } catch (e: Exception) {
            report("Watching the call state", e)
        }
    }
```

Replace

```kotlin
    private fun stopService() {
```

with

```kotlin
    // Section 8.7: the call state, from now until the session closes. The reports go through the session's listener, so
    // that one that comes after the close is dropped. Without READ_PHONE_STATE nothing comes, and Kestrel never enters
    // Ringing.
    private fun startCalls() {
        val events = listener ?: return
        try {
            calls.start { call -> events.send(Event.CallState(call)) }
        } catch (e: Exception) {
            report("Watching the call state", e)
        }
    }

    private fun stopService() {
```

Replace

```kotlin
            Mode.LISTENING -> ListeningTexts.LISTENING
            Mode.PAUSED -> ListeningTexts.PAUSED
            Mode.OFF -> (offReason ?: ListeningTexts.OFF).also { offReason = null }
```

with

```kotlin
            Mode.LISTENING -> ListeningTexts.LISTENING
            Mode.PAUSED -> ListeningTexts.PAUSED
            Mode.RINGING -> ListeningTexts.RINGING
            Mode.IN_CALL -> ListeningTexts.IN_CALL
            Mode.OFF -> (offReason ?: ListeningTexts.OFF).also { offReason = null }
```

Replace

```kotlin
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                showInstead()
            } else if (error == null && spoken.getCompleted().let { it == Spoke.REFUSED || it == Spoke.INTERRUPTED }) {
```

with

```kotlin
        // Section 14.6: a reply that Android refused or interrupted goes to the bar, and so does one that a call stopped,
        // because during a call nothing else stops a reply. The call's report usually comes before Android's loss of the
        // focus (prototype P3), so the call's StopSaying ends the reply first.
        val unspoken = { spoke: Spoke ->
            spoke == Spoke.REFUSED || spoke == Spoke.INTERRUPTED || (spoke == Spoke.STOPPED && state.value.inCall)
        }
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                showInstead()
            } else if (error == null && unspoken(spoken.getCompleted())) {
```

- [ ] **Step 5: Show the call in the notification and on the tile, and wire the monitor**

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace

```kotlin
 * (B5). [Listening] starts and stops it, and holds the voice session while it runs. The service shows the notification
 * with Pause or Resume and Stop, or without actions once Kestrel is turning off, and tells [Listening] whether the
 * screen is on and the phone unlocked. The actions are broadcasts to [NotificationActionReceiver], so they need no new
 * start of the service. Since Android 14 the owner can swipe the notification away while the phone is unlocked; it
 * comes back with the next change of mode. The service stops itself when [Listening] asks, unless Android gave it a start
 * that Listening has not seen yet. Android calls every callback on the main thread.
```

with

```kotlin
 * (B5). [Listening] starts and stops it, and holds the voice session while it runs. The service shows the notification
 * with Pause or Resume and Stop, with only Stop during a call, or without actions once Kestrel is turning off, and
 * tells [Listening] whether the screen is on and the phone unlocked. The actions are broadcasts to
 * [NotificationActionReceiver], so they need no new start of the service. Since Android 14 the owner can swipe the
 * notification away while the phone is unlocked; it comes back with the next change of mode. The service stops itself
 * when [Listening] asks, unless Android gave it a start that Listening has not seen yet. Android calls every callback
 * on the main thread.
```

Replace

```kotlin
    /** The notification of [mode]. Off: the service is about to stop, so it offers no actions until then. */
    private fun notification(mode: Mode): Notification {
        val title = when (mode) {
            Mode.LISTENING -> R.string.listening_title_listening
            Mode.PAUSED -> R.string.listening_title_paused
            Mode.OFF -> R.string.listening_title_stopping
        }
```

with

```kotlin
    /**
     * The notification of [mode]. Off: the service is about to stop, so it offers no actions until then. During a call it
     * offers only Stop.
     */
    private fun notification(mode: Mode): Notification {
        val title = when (mode) {
            Mode.LISTENING -> R.string.listening_title_listening
            Mode.PAUSED -> R.string.listening_title_paused
            Mode.RINGING -> R.string.listening_title_ringing
            Mode.IN_CALL -> R.string.listening_title_in_call
            Mode.OFF -> R.string.listening_title_stopping
        }
```

Replace

```kotlin
        if (mode != Mode.OFF) {
            val toggle = if (mode == Mode.LISTENING) {
                action(R.string.listening_pause, ACTION_PAUSE)
            } else {
                action(R.string.listening_resume, ACTION_RESUME)
            }
            builder.addAction(toggle).addAction(action(R.string.listening_stop, ACTION_STOP))
        }
        return builder.build()
```

with

```kotlin
        // During a call the tile and the shortcut choose the mode that comes back after it (section 8.5).
        when (mode) {
            Mode.LISTENING -> builder.addAction(action(R.string.listening_pause, ACTION_PAUSE))
            Mode.PAUSED -> builder.addAction(action(R.string.listening_resume, ACTION_RESUME))
            Mode.RINGING, Mode.IN_CALL, Mode.OFF -> Unit
        }
        if (mode != Mode.OFF) builder.addAction(action(R.string.listening_stop, ACTION_STOP))
        return builder.build()
```

In `app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt`, replace

```kotlin
 * Kestrel's Quick Settings tile (design spec, sections 7.2, 8.5 and 8.6). A tap toggles between listening and paused, or
 * starts Kestrel when it is off. While the owner sees the tile, it shows the mode: active while Kestrel listens, inactive
 * while it is paused or off. Android calls every callback on the main thread.
```

with

```kotlin
 * Kestrel's Quick Settings tile (design spec, sections 7.2, 8.5 and 8.6). A tap toggles between listening and paused, or
 * starts Kestrel when it is off; during a call it chooses the mode that comes back after the call. While the owner sees
 * the tile, it shows the mode: active while Kestrel listens, inactive otherwise. Android calls every callback on the main
 * thread.
```

Replace

```kotlin
                Mode.LISTENING -> R.string.tile_listening
                Mode.PAUSED -> R.string.tile_paused
                Mode.OFF -> R.string.tile_off
```

with

```kotlin
                Mode.LISTENING -> R.string.tile_listening
                Mode.PAUSED -> R.string.tile_paused
                Mode.RINGING -> R.string.tile_ringing
                Mode.IN_CALL -> R.string.tile_in_call
                Mode.OFF -> R.string.tile_off
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="listening_title_stopping">Kestrel is turning off</string>
```

with

```xml
    <string name="listening_title_stopping">Kestrel is turning off</string>
    <string name="listening_title_ringing">A call is ringing</string>
    <string name="listening_title_in_call">Kestrel is paused during the call</string>
```

Replace

```xml
    <string name="tile_off">Off</string>
```

with

```xml
    <string name="tile_off">Off</string>
    <string name="tile_ringing">Call ringing</string>
    <string name="tile_in_call">In a call</string>
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

with

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <!-- Optional, in the setup checklist: the call state, for the modes Ringing and In call (design spec, sections 8.7
         and 13.6). Kestrel reads no phone number and no call log. -->
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.AndroidExitRecords
```

with

```kotlin
import dev.kestrel.app.listen.AndroidCallMonitor
import dev.kestrel.app.listen.AndroidExitRecords
```

Replace

```kotlin
            // The wall clock, as in Android's exit records.
            clock = System::currentTimeMillis,
```

with

```kotlin
            // The wall clock, as in Android's exit records.
            clock = System::currentTimeMillis,
            // Section 8.7: the call state, while a voice session is open.
            calls = AndroidCallMonitor(appContext),
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 51 tests in `RulesTest` (43 and the 8 new ones) and 101 in `ListeningTest` (96 and the 5 new ones).

- [ ] **Step 7: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.5, replace

```markdown
| Ringing | Recognition with the call commands only | An incoming call while Kestrel is listening or paused, also on the lock screen (a setting, on by default; spike S1) |
| In call | Nothing by default; optional in-call commands | The owner answers the call |
```

with

```markdown
| Ringing | Recognition with the call commands only, also on the lock screen | An incoming call while Kestrel is listening or paused (spike S1) |
| In call | Nothing by default; the optional in-call commands while the phone is unlocked | The owner answers or makes a call, or Kestrel turns on while a call is active |
```

Replace

```markdown
After unlock, Kestrel follows the "on screen wake-up" setting, as Voice Access does: resume the previous mode (the default), always start listening, or stay off.
```

with

```markdown
After unlock, Kestrel follows the "on screen wake-up" setting, as Voice Access does: resume the previous mode (the default), always start listening, or stay off.
- A call interrupts the mode, and the mode before the call returns after it. While a call rings or lasts, Kestrel stores the mode that the call interrupted, for the restore below, and the tile and the accessibility shortcut choose the mode that returns after the call; the notification offers only Stop then. Kestrel enters Ringing only with the permission `READ_PHONE_STATE` (section 8.7). Ringing and In call follow the phone's own calls, which Android's call-state listener reports; a call in another app, such as Viber, does not change the mode. During a call, transcripts without words do not count towards the hint about the microphone (section 14.6).
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (204 in `:app`, 122 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 9: Run the voice device tests**

`ListeningServiceTest` checks the notification's actions while Kestrel listens and is paused, and every test that starts listening now starts the call monitor, which does nothing without `READ_PHONE_STATE`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 68 run, 0 failed, 0 errors, 0 skipped.", and no warning from the restores at the end.

- [ ] **Step 10: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 11: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/CallMonitor.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): follow calls with the modes Ringing and In call, record while a call rings also on the lock screen, and store the mode that a call interrupted" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The call commands, the call actions and the call check

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/CallActions.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt` (`Request.Call` and its commands, `Requests.readCall`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`State.callCommands`, `microphone`, `recognition`, `Effect.CallCommand`, `heard`, `whileAwaitingTask`, `whileIdle`, new `callCommand`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (imports, `ListeningTexts`, the class's KDoc and constructor, new `watchCalls`, `performUnguarded`, new `carryOut`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` (imports, new `clickLabelled`, `isOn` and `labelled`, `MAX_LABEL_NODES`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `callActions`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `ChecklistPage.kt`, `SetupStatusReader.kt`, `SettingsApp.kt` (`SetupStep.CALLS`, `SetupStatus.callsAllowed`, `SetupAction.CALL_PERMISSIONS`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` (`refresh`, `perform`, `askForPermissions`)
- Modify: `app/src/main/AndroidManifest.xml` (`ANSWER_PHONE_CALLS`), `app/src/main/res/values/strings.xml` (three strings)
- Modify: `scripts/lib/Kestrel.ps1` (`Get-LargestRowId`), `scripts/listen-check.ps1` (the call check)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.7, 13.6, 15 and 19.1), `README.md` (the paragraphs on listening, on `listen-check.ps1` and on the setup checklist)
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/CallActionsTest.kt` (new), `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, `scripts/tests/Kestrel.Tests.ps1`

**Interfaces:**
- Consumes: `Mode.RINGING`, `Mode.IN_CALL`, `CallState`, `Event.CallState`, `State.inCall`, `CallMonitor` and `Listening.startCalls()` from Task 2; `Requests.read`, `nameAt`, `nameBefore`, `phrase`; `ScreenSession.actions`, `ScreenActions.touchTarget`; `SetupStatus`, `SetupChecklist.states`, `ChecklistPage`'s `StepTexts`; `listen-check.ps1`'s `Invoke-Checked`, `Invoke-Hook`, `Wait-Mode`, `Get-VoiceState`, `Get-KestrelRecording`, `Get-RecordingProblem`, `Get-NewRecordingState`, `Wait-Condition`, `Register-Restore`.
- Produces: `sealed interface Request.Call : Request` with `Request.Answer(speaker: Boolean)`, `Request.Decline`, `Request.HangUp`, `Request.Mute`, `Request.Speaker`; `fun Requests.readCall(transcript: String, spotted: Boolean, ringing: Boolean): Request.Call?`; `State.callCommands: Boolean = false`; `Effect.CallCommand(command: Request.Call)`; `internal fun interface CallControl { suspend fun perform(command: Request.Call): String? }`, `internal interface CallApi`, `internal fun interface CallScreen { fun click(label: String, ifOff: Boolean): Boolean }`, `internal class CallActions(api: CallApi, screen: () -> CallScreen?) : CallControl`, `internal class AndroidCallApi(context: Context) : CallApi`; `fun ScreenActions.clickLabelled(label: String, ifOff: Boolean = false): Boolean`; the constructor parameter `callActions: CallControl` and `fun Listening.watchCalls()`; the texts `ListeningTexts.CALLS_NEED_ACCESS`, `CALLS_NEED_SERVICE`, `CANNOT_ANSWER`, `CANNOT_DECLINE`, `CANNOT_HANG_UP`, `HANG_UP_NEEDS_PERMISSION`, `CANNOT_MUTE`, `CANNOT_SPEAKER`, `ANSWERED_WITHOUT_SPEAKER`, `CALL_FAILED`; `SetupStep.CALLS`, `SetupStatus.callsAllowed`, `SetupAction.CALL_PERMISSIONS`; the strings `setup_calls_title`, `setup_calls_detail`, `setup_allow_calls`; `Get-LargestRowId` in `Kestrel.ps1`; the result line `call` of `listen-check.ps1`. Task 5 sets `callCommands` from the stored options.

Design D1. While a call rings, the Rules read every transcript with `Requests.readCall`: "answer", "answer on speaker" and "decline", with or without the name and with the polite words of Kestrel's other phrases, also on the lock screen; everything else is ignored. During the call, with `State.callCommands`, which Task 5's setting "Commands during a call" sets, Kestrel records while the phone is unlocked and understands "hang up", "mute" and "speaker". A command shows on the bar and becomes `Effect.CallCommand`, which `Listening` hands to `CallActions` in a coroutine of its scope, because the actions make binder calls; a failure shows its reason on the bar (section 14). The order of the actions is a change from spec 8.7, which put the screen first: with `ANSWER_PHONE_CALLS`, Android's call API answers, declines and hangs up, also on the lock screen and with any phone app; without it, Kestrel clicks the control whose content description or text is "Answer" or "Decline" in any window on the screen, which finds the heads-up notification's buttons and the buttons over the lock screen (P3). "Answer on speaker" waits until the call is active, brings the in-call screen to the front with `TelecomManager.showInCallScreen`, because an answer through the API leaves it behind (P3), and clicks "Speaker" unless that control is on already, as on the emulator, which has no earpiece and puts every call on the speaker from the start; "speaker" during a call also leaves a control that is on as it is; without the control, Kestrel says "I answered, but I couldn't turn on the speaker." Google's end-call control has no label (P3), so "hang up" without the permission says that it needs the permission. The screen path searches only the windows on the screen, not the call notification's own actions that design D1 also named: when the owner has pushed the heads-up notification away, Kestrel finds nothing to click and says so, and the call API path still works. Because P3 did not look at the in-call screen's speaker and mute controls, Step 1 first looks at them on the emulator, and the code uses the labels that this look finds. The setup checklist gets an optional step for both permissions, and a grant while Kestrel listens starts the call monitor at once (`watchCalls`). `listen-check.ps1` gets a call check on emulators: the console's `gsm call` rings while Kestrel listens in the background, and the check declines the first call and answers the second through the screen. Then, with `ANSWER_PHONE_CALLS`, it answers a third call with a plain "Answer.", which must leave the in-call screen behind, and a fourth with "Answer on speaker.", which must bring the in-call screen to the front with its speaker control on; last, with the swipe lock screen, it checks that a fifth call that wakes the locked screen starts an unsilenced recording (spike S1's open scenario) and declines it. The script deletes only its own calls from the call log and puts the permissions and the lock screen back as it found them.

- [ ] **Step 1: Look at the in-call screen on the emulator**

Prototype P3 looked only at the in-call screen's end-call control, so nobody has seen whether its speaker and mute controls carry the labels "Speaker" and "Mute" and take a click (review of plan 2D, part B, I1). This step places a call from the emulator's console, answers it on the heads-up notification, prints the labelled elements of every window on the screen (the in-call screen, and the status and navigation bars too), with every digit masked so that no number shows, and prints whether the speaker control is on after the answer. The emulator has no earpiece (goldfish's audio policy lists only the speaker), so its calls are expected to start on the speaker (review 3 of plan 2D, I1); the step records it and goes on. `uiautomator dump --windows` reads every window, because the heads-up notification is a window of its own that does not take the focus (I-1). It ends the call, deletes the call from the call log by its ID and its test number, and deletes the dump of the screen. Kestrel takes no part. If the step fails while the call still rings, its end cancels a ringing call, and the phone app may keep a "Missed call" notification for it; swipe it away.

Run this in PowerShell, in the repository's root:

```powershell
. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $serial = 'emulator-5554'
if ("$(& $adb -s $serial emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }
$number = '5550175'
$dump = '/data/local/tmp/kestrel-look.xml'
# The call of this look is newer than the newest row of the call log now. The query reads only the rows' IDs.
$largest = [long]0
foreach ($line in (& $adb -s $serial shell content query --uri content://call_log/calls --projection _id)) {
    if ($line -match '\b_id=(\d+)') { $largest = [math]::Max($largest, [long]$Matches[1]) }
}
function Read-Screen {
    foreach ($try in 1..3) {
        & $adb -s $serial shell uiautomator dump --windows $dump | Out-Null
        $text = (& $adb -s $serial shell cat $dump) -join "`n"
        & $adb -s $serial shell rm -f $dump
        if ($text -match '<hierarchy') { return [xml]$text }
        Start-Sleep -Seconds 1
    }
    throw 'uiautomator could not dump the screen'
}
function Find-Labelled([xml]$Screen, [string]$Label) {
    $Screen.SelectNodes('//node') |
        Where-Object { "$($_.'content-desc')".Trim() -eq $Label -or "$($_.text)".Trim() -eq $Label } |
        Select-Object -First 1
}
# The node itself or its nearest parent that takes a click, as ScreenActions' touchTarget finds it.
function Get-Clickable($Node) {
    for ($at = $Node; $at -and $at.LocalName -eq 'node'; $at = $at.ParentNode) { if ($at.clickable -eq 'true') { return $at } }
    $null
}
function Invoke-Tap($Node) {
    if ($Node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'The control has no bounds' }
    & $adb -s $serial shell input tap (([int]$Matches[1] + [int]$Matches[3]) / 2) (([int]$Matches[2] + [int]$Matches[4]) / 2)
}
try {
    & $adb -s $serial shell input keyevent KEYCODE_WAKEUP
    & $adb -s $serial shell wm dismiss-keyguard
    & $adb -s $serial shell input keyevent KEYCODE_HOME
    & $adb -s $serial emu gsm call $number | Out-Null
    Start-Sleep -Seconds 4
    $answer = Find-Labelled (Read-Screen) 'Answer'
    if (-not $answer -or -not (Get-Clickable $answer)) { throw 'The ringing call shows no clickable control named Answer' }
    Invoke-Tap (Get-Clickable $answer)
    Start-Sleep -Seconds 4
    $screen = Read-Screen
    foreach ($node in $screen.SelectNodes('//node')) {
        if (-not ("$($node.'content-desc')" -or "$($node.text)")) { continue }
        $clickable = Get-Clickable $node
        $parent = [bool]($clickable -and -not [object]::ReferenceEquals($clickable, $node))
        ("{0} | {1} | clickable={2} | clickable parent={3} | desc='{4}' | text='{5}'" -f
            $node.class, $node.'resource-id', $node.clickable, $parent, $node.'content-desc', $node.text) -replace '\d', '#'
    }
    $speaker = Find-Labelled $screen 'Speaker'
    $control = if ($speaker) { Get-Clickable $speaker }
    # Checked on the element or on the parent that takes the click, as listen-check.ps1's Test-SpeakerOn reads it.
    $on = if ($control) { $speaker.checked -eq 'true' -or $control.checked -eq 'true' } else { 'no control' }
    "Speaker control found: $([bool]$control); on after the answer: $on"
} finally {
    & $adb -s $serial emu gsm cancel $number | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $serial shell "content delete --uri content://call_log/calls --where '_id>$largest AND number=$number'"
    & $adb -s $serial shell rm -f $dump
    & $adb -s $serial shell input keyevent KEYCODE_HOME
}
```

Expected: one line for each labelled element of every window, among them one whose `desc` or `text` is `Speaker` and one whose `desc` or `text` is `Mute`, each with `clickable=true` or `clickable parent=True`, and at the end "Speaker control found: True; on after the answer: True", because the call starts on the speaker. No line shows a digit. Put the lines into the task's report.

What to do with the result:
- If the labels are different, for example "Speakerphone" or "Turn on speaker", use the labels that the step printed: in Step 6, for the constants `MUTE` and `SPEAKER` of `CallActions`; in Step 2, in `CallActionsTest` (the default of `inCallControls` and the lists of clicks); in Step 11, for `$speakerLabel` in `listen-check.ps1`; and in Step 13, in the spec's and the README's texts that name the "Speaker" control. Change nothing else.
- If neither the speaker control nor the mute control, nor any of their parents, takes a click, stop and ask the controller, because then "answer on speaker", "mute" and "speaker" need another way, such as a tap at the control's place.
- If the speaker control is on after the answer, as expected, Step 11's call check cannot see Kestrel switch to the speaker: it checks that "Answer on speaker." brings the in-call screen to the front and leaves the control on, and that a plain "Answer." through the call API leaves the screen behind. `CallActions` clicks the control only when it is off. If the control is off after the answer, the emulator has an earpiece after all; the call check still passes when Kestrel turns the speaker on, and the step's line goes into the task's report.
- If no ringing call shows a clickable "Answer" in any window, the heads-up notification did not show: check that the emulator's screen is on and unlocked and run the step again; if it still does not show, stop and ask the controller, because the call check of Step 11 answers the second call through it.

- [ ] **Step 2: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, replace

```kotlin
    @Test
    fun aTranscriptWithoutWordsIsNothing() {
```

with

```kotlin
    @Test
    fun theCommandsWhileACallRings() {
        assertEquals(Request.Answer(speaker = false), Requests.readCall("Answer.", spotted = false, ringing = true))
        assertEquals(Request.Answer(speaker = false), Requests.readCall("Kestrel, answer, please.", spotted = true, ringing = true))
        assertEquals(Request.Answer(speaker = true), Requests.readCall("Answer on speaker.", spotted = false, ringing = true))
        assertEquals(Request.Answer(speaker = true), Requests.readCall("Answer on the speaker, Kestrel.", spotted = false, ringing = true))
        assertEquals(Request.Decline, Requests.readCall("Decline now.", spotted = false, ringing = true))
        // As in read(): one short word may come before a name that the spotter heard, and a polite word after a name.
        assertEquals(Request.Answer(speaker = false), Requests.readCall("Hey Kestrel, answer.", spotted = true, ringing = true))
        assertEquals(Request.Decline, Requests.readCall("Decline, Kestrel, please.", spotted = false, ringing = true))
    }

    @Test
    fun theCommandsDuringACall() {
        assertEquals(Request.HangUp, Requests.readCall("Hang up.", spotted = false, ringing = false))
        assertEquals(Request.Mute, Requests.readCall("Kestrel, mute.", spotted = true, ringing = false))
        assertEquals(Request.Speaker, Requests.readCall("Speaker, please.", spotted = false, ringing = false))
    }

    @Test
    fun everythingElseIsNoCallCommand() {
        assertNull(Requests.readCall("Hang up.", spotted = false, ringing = true))
        assertNull(Requests.readCall("Answer.", spotted = false, ringing = false))
        assertNull(Requests.readCall("Answer the phone and say that I'm busy.", spotted = false, ringing = true))
        assertNull(Requests.readCall("Kestrel.", spotted = true, ringing = true))
        assertNull(Requests.readCall("", spotted = false, ringing = true))
    }

    @Test
    fun aTranscriptWithoutWordsIsNothing() {
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace

```kotlin
    @Test
    fun neverMindEndsTheWaitForATask() {
```

with

```kotlin
    @Test
    fun whileACallRingsOnlyTheCallCommandsCountAlsoOnTheLockScreen() {
        val ringing = Rules.next(listening.copy(screenOn = false), Event.CallState(CallState.RINGING)).state

        assertEquals(
            Step(ringing, listOf(Effect.Show(Feedback.Heard("Answer.")), Effect.CallCommand(Request.Answer(speaker = false)))),
            Rules.next(ringing, Event.Heard("Answer.", named = false)),
        )
        assertEquals(
            Effect.CallCommand(Request.Answer(speaker = true)),
            Rules.next(ringing, Event.Heard("Kestrel, answer on speaker, please.", named = true)).effects.last(),
        )
        assertEquals(Effect.CallCommand(Request.Decline), Rules.next(ringing, Event.Heard("Decline, Kestrel.", named = false)).effects.last())
        assertEquals(Step(ringing), Rules.next(ringing, Event.Heard("Hang up.", named = false)))
    }

    @Test
    fun duringACallItsCommandsCountOnlyWithTheSetting() {
        val inCall = Rules.next(listening, Event.CallState(CallState.ACTIVE)).state

        assertEquals(Step(inCall), Rules.next(inCall, Event.Heard("Hang up.", named = false)))
        val commands = inCall.copy(callCommands = true)
        assertEquals(Effect.CallCommand(Request.HangUp), Rules.next(commands, Event.Heard("Hang up.", named = false)).effects.last())
        assertEquals(Effect.CallCommand(Request.Mute), Rules.next(commands, Event.Heard("Mute.", named = false)).effects.last())
        assertEquals(Effect.CallCommand(Request.Speaker), Rules.next(commands, Event.Heard("Speaker, please.", named = false)).effects.last())
        assertEquals(Step(commands), Rules.next(commands, Event.Heard("Answer.", named = false)))
        // With the setting, Kestrel records during the call while the phone is unlocked; the other person hears the
        // commands too (section 8.7).
        assertTrue(commands.microphone)
        assertEquals(Recognition.ALL, commands.recognition)
        assertFalse(commands.copy(screenOn = false).microphone)
    }

    @Test
    fun neverMindEndsTheWaitForATask() {
```

Create `app/src/test/kotlin/dev/kestrel/app/listen/CallActionsTest.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Request
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallActionsTest {
    private val api = FakeCallApi()
    private val clicks = mutableListOf<String>()

    // The controls with these labels are on the screen now, and those of them that are on.
    private var controls = setOf("Answer", "Decline")
    private var checked = emptySet<String>()
    private var serviceOn = true
    private val actions = CallActions(api) {
        if (serviceOn) {
            CallScreen { label, ifOff ->
                if (ifOff && label in controls && label in checked) {
                    true
                } else {
                    clicks += label
                    label in controls
                }
            }
        } else {
            null
        }
    }

    @Test
    fun withThePermissionAndroidsCallApiAnswersDeclinesAndHangsUp() = runTest {
        api.permitted = true

        assertNull(actions.perform(Request.Answer(speaker = false)))
        assertNull(actions.perform(Request.Decline))
        assertNull(actions.perform(Request.HangUp))

        assertEquals(listOf("answer", "end", "end"), api.calls)
        // Without the screen, so that it works on the lock screen and with any phone app.
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun withoutThePermissionKestrelClicksTheIncomingCallControls() = runTest {
        assertNull(actions.perform(Request.Answer(speaker = false)))
        assertNull(actions.perform(Request.Decline))

        assertEquals(listOf("Answer", "Decline"), clicks)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun withNeitherThePermissionNorTheServiceKestrelSaysWhy() = runTest {
        serviceOn = false

        assertEquals(ListeningTexts.CALLS_NEED_ACCESS, actions.perform(Request.Answer(speaker = false)))
        assertEquals(ListeningTexts.CALLS_NEED_ACCESS, actions.perform(Request.Decline))
        assertEquals(ListeningTexts.HANG_UP_NEEDS_PERMISSION, actions.perform(Request.HangUp))
        assertEquals(ListeningTexts.CALLS_NEED_SERVICE, actions.perform(Request.Mute))
    }

    @Test
    fun aControlThatDoesNotShowAndACallThatHasEndedAreReported() = runTest {
        controls = emptySet()

        assertEquals(ListeningTexts.CANNOT_ANSWER, actions.perform(Request.Answer(speaker = false)))
        assertEquals(ListeningTexts.CANNOT_DECLINE, actions.perform(Request.Decline))
        // The end-call control of Google's phone app has no label (prototype P3), so only the permission helps.
        assertEquals(ListeningTexts.HANG_UP_NEEDS_PERMISSION, actions.perform(Request.HangUp))
        api.permitted = true
        api.ends = false
        assertEquals(ListeningTexts.CANNOT_DECLINE, actions.perform(Request.Decline))
    }

    @Test
    fun answerOnSpeakerTurnsTheSpeakerOnOnceTheCallIsActive() = runTest {
        api.permitted = true
        api.activeAfter = 3

        assertNull(actions.perform(Request.Answer(speaker = true)))

        // The in-call screen does not come to the front after an answer through the API (prototype P3).
        assertEquals(listOf("answer", "active", "active", "active", "show in-call screen"), api.calls)
        assertEquals(listOf("Speaker"), clicks)
    }

    @Test
    fun answerOnSpeakerWithoutTheSpeakerControlSaysSo() = runTest {
        // The call never becomes active.
        api.activeAfter = Int.MAX_VALUE
        assertEquals(ListeningTexts.ANSWERED_WITHOUT_SPEAKER, actions.perform(Request.Answer(speaker = true)))
        // The in-call screen shows no speaker control.
        api.activeAfter = 0
        api.inCallControls = emptySet()
        assertEquals(ListeningTexts.ANSWERED_WITHOUT_SPEAKER, actions.perform(Request.Answer(speaker = true)))
        // The accessibility service is off, and the call API answered.
        api.permitted = true
        serviceOn = false
        assertEquals(ListeningTexts.ANSWERED_WITHOUT_SPEAKER, actions.perform(Request.Answer(speaker = true)))
    }

    @Test
    fun aSpeakerControlThatIsOnAlreadyStaysOn() = runTest {
        api.permitted = true
        // On a phone without an earpiece, such as the emulator, every call starts on the speaker.
        api.inCallChecked = setOf("Speaker")

        assertNull(actions.perform(Request.Answer(speaker = true)))
        assertNull(actions.perform(Request.Speaker))

        assertTrue(clicks.isEmpty())
        assertEquals(listOf("answer", "active", "show in-call screen", "show in-call screen"), api.calls)
    }

    @Test
    fun duringTheCallMuteAndSpeakerPressTheInCallControls() = runTest {
        assertNull(actions.perform(Request.Mute))
        assertNull(actions.perform(Request.Speaker))

        assertEquals(listOf("show in-call screen", "show in-call screen"), api.calls)
        assertEquals(listOf("Mute", "Speaker"), clicks)
        api.inCallControls = emptySet()
        assertEquals(ListeningTexts.CANNOT_MUTE, actions.perform(Request.Mute))
        assertEquals(ListeningTexts.CANNOT_SPEAKER, actions.perform(Request.Speaker))
    }

    private inner class FakeCallApi : CallApi {
        val calls = mutableListOf<String>()
        var permitted = false
        var ends = true

        // The check of the call state that first finds the call active.
        var activeAfter = 0
        var inCallControls = setOf("Mute", "Speaker")
        var inCallChecked = emptySet<String>()
        private var activeChecks = 0

        override fun allowed() = permitted

        override fun answer() {
            calls += "answer"
        }

        override fun end(): Boolean {
            calls += "end"
            return ends
        }

        override fun active(): Boolean {
            calls += "active"
            return ++activeChecks >= activeAfter
        }

        override fun showInCallScreen() {
            calls += "show in-call screen"
            controls = inCallControls
            checked = inCallChecked
        }
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Work
```

with

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Work
```

Replace

```kotlin
    private val calls = FakeCallMonitor()
```

with

```kotlin
    private val calls = FakeCallMonitor()
    private val callActions = FakeCallControl()
```

Replace

```kotlin
    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

with

```kotlin
    @Test
    fun aCallCommandIsCarriedOutAndAFailureIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        calls.change(CallState.RINGING)
        runCurrent()
        shown.clear()

        listening.send(Event.Heard("Answer.", named = false))
        runCurrent()
        assertEquals(listOf<Request.Call>(Request.Answer(speaker = false)), callActions.commands)
        assertEquals(listOf("Answer."), shown)

        callActions.result = ListeningTexts.CANNOT_DECLINE
        listening.send(Event.Heard("Decline.", named = false))
        runCurrent()
        // Section 14: Kestrel says why a command failed.
        assertEquals(listOf("Answer.", "Decline.", ListeningTexts.CANNOT_DECLINE), shown)
    }

    @Test
    fun aCallCommandThatThrowsIsReportedAndShown() = runTest {
        val listening = listeningWithSession()
        calls.change(CallState.RINGING)
        runCurrent()
        callActions.failure = SecurityException("No permission to answer calls")

        listening.send(Event.Heard("Answer.", named = false))
        runCurrent()

        assertEquals("A call command", reports.single().first)
        assertEquals(ListeningTexts.CALL_FAILED, shown.last())
    }

    @Test
    fun aPermissionGivenWhileKestrelListensStartsTheCallState() = runTest {
        val listening = listeningWithSession()

        listening.watchCalls()
        runCurrent()
        assertEquals(2, calls.starts)

        // Without a session, the next start watches the call state.
        listening.send(Event.TurnOff)
        runCurrent()
        listening.watchCalls()
        runCurrent()
        assertEquals(2, calls.starts)
    }

    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

Replace

```kotlin
        calls = calls,
        microphones = { microphone },
    )
```

with

```kotlin
        calls = calls,
        callActions = callActions,
        microphones = { microphone },
    )
```

Replace

```kotlin
    private class FakeMicrophone : Microphone {
```

with

```kotlin
    private class FakeCallControl : CallControl {
        val commands = mutableListOf<Request.Call>()
        var result: String? = null
        var failure: Exception? = null

        override suspend fun perform(command: Request.Call): String? {
            commands += command
            failure?.let { throw it }
            return result
        }
    }

    private class FakeMicrophone : Microphone {
```

Replace

```kotlin
            calls = calls,
            // Its first write throws an Error, and the later ones work.
```

with

```kotlin
            calls = calls,
            callActions = callActions,
            // Its first write throws an Error, and the later ones work.
```

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, replace

```kotlin
                SetupStep.DEFAULT_ASSISTANT to StepState.LATER,
                SetupStep.PROVIDER to StepState.LATER,
```

with

```kotlin
                SetupStep.DEFAULT_ASSISTANT to StepState.LATER,
                SetupStep.CALLS to StepState.OPTIONAL,
                SetupStep.PROVIDER to StepState.LATER,
```

Replace

```kotlin
    @Test
    fun theModelStepIsDoneOnlyWhenEveryModelIsReady() {
```

with

```kotlin
    @Test
    fun theCallStepIsDoneWithBothPhonePermissions() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.CALLS)

        assertEquals(StepState.OPTIONAL, state(nothingSetUp))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(callsAllowed = true)))
    }

    @Test
    fun theModelStepIsDoneOnlyWhenEveryModelIsReady() {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace

```kotlin
        compose.activity.resources.getQuantityString(R.plurals.settings_checklist_progress, 8, done, 8)
```

with

```kotlin
        compose.activity.resources.getQuantityString(R.plurals.settings_checklist_progress, 9, done, 9)
```

Replace

```kotlin
        val labels = listOf(R.string.setup_allow, R.string.setup_open_app_settings, R.string.setup_open_accessibility)
```

with

```kotlin
        val labels = listOf(
            R.string.setup_allow,
            R.string.setup_open_app_settings,
            R.string.setup_open_accessibility,
            R.string.setup_allow_calls,
        )
```

Replace

```kotlin
            voiceAccessOn = false,
            models = ModelCheck.Done(listOf(READY_VOICE)),
        )
```

with

```kotlin
            voiceAccessOn = false,
            models = ModelCheck.Done(listOf(READY_VOICE)),
            callsAllowed = true,
        )
```

Replace

```kotlin
    private class FakeSettingsData : SettingsData {
```

with

```kotlin
    @Test
    fun theCallStepAsksForThePhonePermissions() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_calls_detail)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_allow_calls)).performScrollTo().performClick()

        assertEquals(listOf(SetupAction.CALL_PERMISSIONS), actions)
    }

    private class FakeSettingsData : SettingsData {
```

In `scripts/tests/Kestrel.Tests.ps1`, add at the end of the file:

```powershell

Describe 'Get-LargestRowId' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns the largest _id of the rows' {
        Get-LargestRowId -Rows @('Row: 0 _id=3', 'Row: 1 _id=12', 'Row: 2 _id=7') | Should -Be 12
    }

    It 'returns 0 when the query found no row' {
        Get-LargestRowId -Rows @('No result found.') | Should -Be 0
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RequestsTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.CallActionsTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD FAILED`, because the test sources do not compile: among others "Unresolved reference 'readCall'", "Unresolved reference 'Answer'", "Unresolved reference 'CallCommand'", "Unresolved reference 'CallActions'", "Unresolved reference 'CallControl'", "Unresolved reference 'CALLS'" and "No parameter with name 'callsAllowed' found.".

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 99, Failed: 2" in both shells; both failures are the new tests of `Get-LargestRowId`, whose messages name `Get-LargestRowId`, because the function does not exist yet.

- [ ] **Step 4: Read the call commands**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace

```kotlin
    /** Anything else, which the instant commands of sub-project 3 will read. */
    data class Other(val text: String) : Request {
```

with

```kotlin
    /** A call command (section 8.7), which [Requests.readCall] reads while a call rings or lasts. */
    sealed interface Call : Request

    /** "Answer", or "answer on speaker" ([speaker]), while a call rings. */
    data class Answer(val speaker: Boolean) : Call

    /** "Decline", while a call rings. */
    data object Decline : Call

    /** "Hang up", during a call. */
    data object HangUp : Call

    /** "Mute", during a call: the in-call screen's mute control. */
    data object Mute : Call

    /** "Speaker", during a call: the in-call screen's speaker control. */
    data object Speaker : Call

    /** Anything else, which the instant commands of sub-project 3 will read. */
    data class Other(val text: String) : Request {
```

Replace

```kotlin
    private val AROUND_NEVER_MIND =
        listOf(listOf("no"), listOf("oh"), listOf("ah"), listOf("thanks"), listOf("thank", "you"))
```

with

```kotlin
    private val AROUND_NEVER_MIND =
        listOf(listOf("no"), listOf("oh"), listOf("ah"), listOf("thanks"), listOf("thank", "you"))

    // The call commands, as phrase() gives them (section 8.7): while a call rings, and during the call.
    private val RINGING_COMMANDS: Map<String, Request.Call> = mapOf(
        "answer" to Request.Answer(speaker = false),
        "answer on speaker" to Request.Answer(speaker = true),
        "answer on the speaker" to Request.Answer(speaker = true),
        "decline" to Request.Decline,
    )
    private val CALL_COMMANDS: Map<String, Request.Call> = mapOf(
        "hang up" to Request.HangUp,
        "mute" to Request.Mute,
        "speaker" to Request.Speaker,
    )
```

Replace

```kotlin
    private class Word(val text: String, val range: IntRange)
```

with

```kotlin
    /**
     * Reads a call command (section 8.7): while a call rings ([ringing]) "answer", "answer on speaker" or "decline", and
     * during a call "hang up", "mute" or "speaker". The name may come before or after the command, and Kestrel's polite
     * words may come with it, as in "Kestrel, answer, please". Returns null for anything else, which Kestrel ignores
     * during a call. [spotted]: the keyword spotter heard "Kestrel" at the start.
     */
    fun readCall(transcript: String, spotted: Boolean, ringing: Boolean): Request.Call? {
        // The polite words go first, so that they may also follow a trailing name, as in "Decline, Kestrel, please".
        val words = WORD.findAll(transcript).map { Word(it.value.lowercase().replace("'", ""), it.range) }
            .filter { it.text !in POLITE }
            .toList()
        val limit = if (spotted) SPOTTED_LIMIT else HEARD_LIMIT
        // As in read(): when the spotter heard the name, one short word such as "hey" may come before it.
        val start = if (spotted && words.isNotEmpty() && words[0].text in BEFORE_NAME && nameAt(words, 1, limit) > 0) 1 else 0
        val afterName = words.drop(start + nameAt(words, start, limit))
        val command = afterName.dropLast(nameBefore(afterName, afterName.size, limit))
        return (if (ringing) RINGING_COMMANDS else CALL_COMMANDS)[phrase(command)]
    }

    private class Word(val text: String, val range: IntRange)
```

- [ ] **Step 5: Turn the call commands into effects in the Rules**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
 * has shown since Kestrel turned on (section 14.6). [beforeCall] is the mode that a call interrupted, while the mode is
 * Ringing or In call; it comes back after the call (section 8.7).
 */
```

with

```kotlin
 * has shown since Kestrel turned on (section 14.6). [beforeCall] is the mode that a call interrupted, while the mode is
 * Ringing or In call; it comes back after the call (section 8.7). [callCommands]: the owner turned on "Commands during a
 * call", so that Kestrel listens for them during a call (section 8.7).
 */
```

Replace

```kotlin
    val beforeCall: Mode? = null,
) {
```

with

```kotlin
    val beforeCall: Mode? = null,
    val callCommands: Boolean = false,
) {
```

Replace

```kotlin
    /**
     * Whether Kestrel records: while it listens or is paused, with the screen on and the phone unlocked, and while a call
     * rings, also on the lock screen (section 8.5). Not during a call.
     */
    val microphone: Boolean
        get() = when (mode) {
            Mode.OFF, Mode.IN_CALL -> false
            Mode.RINGING -> true
            Mode.LISTENING, Mode.PAUSED -> screenOn
        }
```

with

```kotlin
    /**
     * Whether Kestrel records: while it listens or is paused, with the screen on and the phone unlocked; while a call
     * rings, also on the lock screen (section 8.5); and during a call only with [callCommands] and the phone unlocked.
     */
    val microphone: Boolean
        get() = when (mode) {
            Mode.OFF -> false
            Mode.RINGING -> true
            Mode.IN_CALL -> callCommands && screenOn
            Mode.LISTENING, Mode.PAUSED -> screenOn
        }
```

Replace

```kotlin
            mode == Mode.IN_CALL -> Recognition.NONE
```

with

```kotlin
            mode == Mode.IN_CALL -> if (callCommands) Recognition.ALL else Recognition.NONE
```

Replace

```kotlin
    /** Opens Kestrel's settings app, for "Kestrel, open settings" (section 15). */
    data object OpenSettings : Effect
}
```

with

```kotlin
    /** Opens Kestrel's settings app, for "Kestrel, open settings" (section 15). */
    data object OpenSettings : Effect

    /** Carries out a call command (section 8.7). */
    data class CallCommand(val command: Request.Call) : Effect
}
```

Replace

```kotlin
        // During a call, only the call commands count (section 8.7).
        if (state.mode == Mode.OFF || state.inCall) return Step(state)
        val awaiting = state.work == Work.AWAITING_TASK
```

with

```kotlin
        if (state.mode == Mode.OFF) return Step(state)
        if (state.inCall) return callCommand(state, event)
        val awaiting = state.work == Work.AWAITING_TASK
```

Replace

```kotlin
        is Request.OpenSettings -> Step(state.copy(work = Work.IDLE), listOf(shown, Effect.OpenSettings))
    }
```

with

```kotlin
        is Request.OpenSettings -> Step(state.copy(work = Work.IDLE), listOf(shown, Effect.OpenSettings))
        // Requests.read never returns a call command; only readCall does.
        is Request.Call -> Step(state)
    }
```

Replace

```kotlin
        is Request.Other -> command(state, request.text, shown)
    }
```

with

```kotlin
        is Request.Other -> command(state, request.text, shown)
        // Requests.read never returns a call command; only readCall does.
        is Request.Call -> Step(state)
    }
```

Replace

```kotlin
    // Section 8.7: a call that rings while Kestrel listens or is paused, or that is active, interrupts the mode, and the
```

with

```kotlin
    // Section 8.7: while a call rings, only "answer", "answer on speaker" and "decline" count, also on the lock screen;
    // during the call, only "hang up", "mute" and "speaker", and only with the setting "Commands during a call".
    private fun callCommand(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.IN_CALL && !state.callCommands) return Step(state)
        val command = Requests.readCall(event.text, event.named, ringing = state.mode == Mode.RINGING) ?: return Step(state)
        return Step(state, listOf(Effect.Show(Feedback.Heard(event.text)), Effect.CallCommand(command)))
    }

    // Section 8.7: a call that rings while Kestrel listens or is paused, or that is active, interrupts the mode, and the
```

- [ ] **Step 6: Write the call actions**

Create `app/src/main/kotlin/dev/kestrel/app/listen/CallActions.kt`:

```kotlin
package dev.kestrel.app.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import dev.kestrel.app.orchestrator.Request
import kotlinx.coroutines.delay

/** What Kestrel does with a call command (design spec, section 8.7). */
internal fun interface CallControl {
    /** Carries out [command]. Returns null when it worked, or why it did not, as a text for the feedback bar. */
    suspend fun perform(command: Request.Call): String?
}

/** Android's call API, which needs ANSWER_PHONE_CALLS, and the phone's call state, which needs READ_PHONE_STATE. */
internal interface CallApi {
    /** Whether Kestrel holds ANSWER_PHONE_CALLS. */
    fun allowed(): Boolean

    /** Answers the ringing call. */
    fun answer()

    /** Declines the ringing call, or ends the active one. Returns false when there was none. */
    fun end(): Boolean

    /** Whether a call is active. */
    fun active(): Boolean

    /** Brings the phone app's in-call screen to the front, where its speaker and mute controls are. */
    fun showInCallScreen()
}

/** Clicks a control on the screen through Kestrel's accessibility service. */
internal fun interface CallScreen {
    /**
     * Clicks the control whose content description or text is [label]. With [ifOff], a control that is on already, such
     * as a speaker control on a phone that puts the call on the speaker from the start, stays as it is. Returns whether a
     * control took the click, or was on already.
     */
    fun click(label: String, ifOff: Boolean): Boolean
}

/**
 * Carries out the call commands (design spec, section 8.7). With ANSWER_PHONE_CALLS, it answers, declines and hangs up
 * through Android's call API, which works on the lock screen and does not depend on the phone app's layout or
 * language. Without it, it clicks the incoming-call controls "Answer" and "Decline" through Kestrel's accessibility
 * service, which [screen] gives while the service runs: in the call's heads-up notification, or over the lock screen
 * (prototype P3). "Mute" and "Speaker" are only on the in-call screen, which it brings to the front first, and which
 * needs a moment to show them. The labels are the English ones of Google's phone app; the phone app's other languages
 * come with the matching of sub-project 3. It makes binder calls, so the caller runs it off the main thread.
 */
internal class CallActions(private val api: CallApi, private val screen: () -> CallScreen?) : CallControl {
    override suspend fun perform(command: Request.Call): String? = when (command) {
        is Request.Answer -> answer(command.speaker)
        Request.Decline -> decline()
        Request.HangUp -> hangUp()
        Request.Mute -> inCall(MUTE, ifOff = false, ListeningTexts.CANNOT_MUTE)
        // "Speaker" turns the speaker on, so a control that is on already stays on.
        Request.Speaker -> inCall(SPEAKER, ifOff = true, ListeningTexts.CANNOT_SPEAKER)
    }

    private suspend fun answer(speaker: Boolean): String? {
        if (api.allowed()) {
            api.answer()
        } else {
            val controls = screen() ?: return ListeningTexts.CALLS_NEED_ACCESS
            if (!waitFor(CONTROL_MILLIS) { controls.click(ANSWER, ifOff = false) }) return ListeningTexts.CANNOT_ANSWER
        }
        if (!speaker) return null
        // The in-call screen shows its speaker control once the call is active.
        val controls = screen()
        if (controls == null || !waitFor(ACTIVE_MILLIS) { api.active() } || !clickInCall(controls, SPEAKER, ifOff = true)) {
            return ListeningTexts.ANSWERED_WITHOUT_SPEAKER
        }
        return null
    }

    private suspend fun decline(): String? {
        if (api.allowed()) return if (api.end()) null else ListeningTexts.CANNOT_DECLINE
        val controls = screen() ?: return ListeningTexts.CALLS_NEED_ACCESS
        return if (waitFor(CONTROL_MILLIS) { controls.click(DECLINE, ifOff = false) }) null else ListeningTexts.CANNOT_DECLINE
    }

    private suspend fun hangUp(): String? {
        if (api.allowed()) return if (api.end()) null else ListeningTexts.CANNOT_HANG_UP
        // Google's phone app gives its end-call control no label (prototype P3), so without the permission this works
        // only with a phone app that names it "End call".
        val controls = screen() ?: return ListeningTexts.HANG_UP_NEEDS_PERMISSION
        return if (clickInCall(controls, END_CALL, ifOff = false)) null else ListeningTexts.HANG_UP_NEEDS_PERMISSION
    }

    private suspend fun inCall(label: String, ifOff: Boolean, failed: String): String? {
        val controls = screen() ?: return ListeningTexts.CALLS_NEED_SERVICE
        return if (clickInCall(controls, label, ifOff)) null else failed
    }

    // The in-call screen does not come to the front after an answer through the API (prototype P3), so Kestrel brings
    // it there first.
    private suspend fun clickInCall(controls: CallScreen, label: String, ifOff: Boolean): Boolean {
        api.showInCallScreen()
        return waitFor(CONTROL_MILLIS) { controls.click(label, ifOff) }
    }

    // Tries [condition] every POLL_MILLIS until it is true or [millis] have passed.
    private suspend fun waitFor(millis: Long, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (!condition()) {
            if (waited >= millis) return false
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        return true
    }

    private companion object {
        // The labels of Google's phone app on the emulator: the incoming-call controls from prototype P3, the in-call
        // controls from Step 1 of plan 2D's Task 3.
        const val ANSWER = "Answer"
        const val DECLINE = "Decline"
        const val END_CALL = "End call"
        const val MUTE = "Mute"
        const val SPEAKER = "Speaker"

        // How long a call may take to become active after the answer, and a control to show.
        const val ACTIVE_MILLIS = 5_000L
        const val CONTROL_MILLIS = 3_000L
        const val POLL_MILLIS = 200L
    }
}

/**
 * Android's call API (research r8). acceptRingingCall() and endCall() are deprecated, but Android 17 still has them, and
 * they are the only way for an app that is not the phone app. Each call checks its permission first, because the owner
 * can take it back at any time.
 */
internal class AndroidCallApi(private val context: Context) : CallApi {
    private val telecom = context.getSystemService(TelecomManager::class.java)

    override fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    override fun answer() {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Kestrel may not answer calls")
        }
        telecom.acceptRingingCall()
    }

    @Suppress("DEPRECATION")
    override fun end(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false
        return telecom.endCall()
    }

    // Telecom's view covers the calls of every SIM, and isInCall() follows the call (prototype P3).
    override fun active(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        return telecom.isInCall && !telecom.isRinging
    }

    override fun showInCallScreen() {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        telecom.showInCallScreen(false)
    }
}
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt`, replace

```kotlin
import android.os.Bundle
```

with

```kotlin
import android.os.Build
import android.os.Bundle
```

Replace

```kotlin
    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
```

with

```kotlin
    /**
     * Clicks the element whose content description or text is [label], in any letter case, in any window on the screen,
     * such as a call's heads-up notification, the incoming-call screen over the lock screen or the in-call screen (design
     * spec, section 8.7): the element itself, or its nearest parent that handles touches. Returns whether an element took
     * the click. With [ifOff], an element that is checked already, or whose parent that handles touches is, counts as
     * clicked and stays as it is, such as a speaker control that is on. It looks at no more than [MAX_LABEL_NODES]
     * elements in each window, and it keeps no text that it read.
     */
    fun clickLabelled(label: String, ifOff: Boolean = false): Boolean {
        for (window in service.windows) {
            val node = window.root?.let { labelled(it, label) } ?: continue
            val target = node.touchTarget(AccessibilityNodeInfo.ACTION_CLICK) ?: continue
            if (ifOff && (node.isOn() || target.node.isOn())) return true
            if (target.node.isEnabled && target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    // Android 16 replaced the yes-or-no checked state with one that can also be "partly checked".
    private fun AccessibilityNodeInfo.isOn(): Boolean =
        if (Build.VERSION.SDK_INT >= 36) {
            checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            isChecked
        }

    /** The first visible node below [root], breadth first, whose content description or text is [label]. */
    private fun labelled(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_LABEL_NODES) {
            val node = queue.removeFirst()
            seen++
            val named = node.contentDescription?.toString()?.trim().equals(label, ignoreCase = true) ||
                node.text?.toString()?.trim().equals(label, ignoreCase = true)
            if (named && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
```

Replace

```kotlin
        const val MAX_PARENT_DEPTH = 20
```

with

```kotlin
        const val MAX_PARENT_DEPTH = 20

        // A call's screens have a few dozen elements; the limit keeps a search of a large window short.
        const val MAX_LABEL_NODES = 2_000
```

- [ ] **Step 7: Carry out the call commands in the controller, and wire them**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Reply
```

with

```kotlin
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.Request
```

Replace

```kotlin
    const val RINGING = "A call is ringing."
    const val IN_CALL = "Paused during the call"
```

with

```kotlin
    const val RINGING = "A call is ringing. Say \"answer\" or \"decline\"."
    const val IN_CALL = "Paused during the call"

    // Why a call command failed (section 8.7).
    const val CALLS_NEED_ACCESS = "For the call commands, Kestrel needs the permission for calls or its accessibility " +
        "service."
    const val HANG_UP_NEEDS_PERMISSION = "To hang up, Kestrel needs the permission for calls. See the setup checklist."
    const val CALLS_NEED_SERVICE = "Kestrel needs its accessibility service to press the call's buttons."
    const val CANNOT_ANSWER = "I couldn't find the button that answers the call."
    const val CANNOT_DECLINE = "I couldn't decline the call."
    const val CANNOT_HANG_UP = "I couldn't hang up."
    const val CANNOT_MUTE = "I couldn't find the mute button."
    const val CANNOT_SPEAKER = "I couldn't find the speaker button."
    const val ANSWERED_WITHOUT_SPEAKER = "I answered, but I couldn't turn on the speaker."
    const val CALL_FAILED = "Something went wrong with the call."
```

Replace

```kotlin
 * call, but the mode that the call interrupted.
 */
class Listening internal constructor(
```

with

```kotlin
 * call, but the mode that the call interrupted. [callActions] carries out the call commands in a coroutine of [scope],
 * and the bar shows why one failed.
 */
class Listening internal constructor(
```

Replace

```kotlin
    private val calls: CallMonitor,
    microphones: () -> Microphone,
) {
```

with

```kotlin
    private val calls: CallMonitor,
    private val callActions: CallControl,
    microphones: () -> Microphone,
) {
```

Replace

```kotlin
    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = orchestrator.send(event)
```

with

```kotlin
    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = orchestrator.send(event)

    /**
     * The owner allowed Kestrel to read the phone state (section 8.7): while a voice session is open, Kestrel watches the
     * call state from now on, and otherwise from its next start. Any thread may call it.
     */
    fun watchCalls() {
        scope.launch(thread) { if (session != null) startCalls() }
    }
```

Replace

```kotlin
            Effect.OpenSettings -> openSettingsOrSayWhy()
        }
```

with

```kotlin
            Effect.OpenSettings -> openSettingsOrSayWhy()
            is Effect.CallCommand -> carryOut(effect.command)
        }
```

Replace

```kotlin
    // Section 14: Kestrel never fails silently. Android blocks an activity start from the background without an
```

with

```kotlin
    // Section 8.7: the call commands act on Android's call or on the screen through binder calls, so they run off the
    // controller's thread. A failure shows its reason on the bar (section 14).
    private fun carryOut(command: Request.Call) {
        scope.launch {
            val failure = try {
                callActions.perform(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("A call command", e)
                ListeningTexts.CALL_FAILED
            }
            if (failure != null) bar.show(failure)
        }
    }

    // Section 14: Kestrel never fails silently. Android blocks an activity start from the background without an
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.AndroidCallMonitor
import dev.kestrel.app.listen.AndroidExitRecords
import dev.kestrel.app.listen.BoostWordSource
```

with

```kotlin
import dev.kestrel.app.listen.AndroidCallApi
import dev.kestrel.app.listen.AndroidCallMonitor
import dev.kestrel.app.listen.AndroidExitRecords
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.CallActions
import dev.kestrel.app.listen.CallScreen
```

Replace

```kotlin
            calls = AndroidCallMonitor(appContext),
```

with

```kotlin
            calls = AndroidCallMonitor(appContext),
            // The call commands: Android's call API with ANSWER_PHONE_CALLS, otherwise the phone app's controls through
            // the accessibility service while it runs. Listening runs them on Dispatchers.IO.
            callActions = CallActions(AndroidCallApi(appContext)) {
                screen.value?.let { session -> CallScreen { label, ifOff -> session.actions.clickLabelled(label, ifOff) } }
            },
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <!-- Optional, in the setup checklist: the call state, for the modes Ringing and In call (design spec, sections 8.7
         and 13.6). Kestrel reads no phone number and no call log. -->
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

with

```xml
    <!-- Optional, in the setup checklist: the call state, for the modes Ringing and In call, and the answer, decline and
         hang-up through Android's call API (design spec, sections 8.7 and 13.6). Kestrel reads no phone number and no
         call log. -->
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
```

- [ ] **Step 8: Add the call step to the setup checklist**

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace

```kotlin
    DEFAULT_ASSISTANT,
    PROVIDER,
```

with

```kotlin
    DEFAULT_ASSISTANT,
    CALLS,
    PROVIDER,
```

Replace

```kotlin
 * Kestrel's battery use to Restricted. [samsung]: the phone is a Samsung, whose battery settings also have "Never
 * sleeping apps".
 */
```

with

```kotlin
 * Kestrel's battery use to Restricted. [samsung]: the phone is a Samsung, whose battery settings also have "Never
 * sleeping apps". [callsAllowed]: Kestrel may read the phone state and answer calls (READ_PHONE_STATE and
 * ANSWER_PHONE_CALLS, section 8.7).
 */
```

Replace

```kotlin
    val samsung: Boolean = false,
)
```

with

```kotlin
    val samsung: Boolean = false,
    val callsAllowed: Boolean = false,
)
```

Replace

```kotlin
            SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
```

with

```kotlin
            SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
            SetupStep.CALLS -> if (status.callsAllowed) StepState.DONE else StepState.OPTIONAL
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace

```kotlin
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS }
```

with

```kotlin
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS, CALL_PERMISSIONS }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, replace

```kotlin
    SetupStep.DEFAULT_ASSISTANT -> StepTexts(R.string.setup_assistant_title, R.string.setup_assistant_detail)
```

with

```kotlin
    SetupStep.DEFAULT_ASSISTANT -> StepTexts(R.string.setup_assistant_title, R.string.setup_assistant_detail)
    SetupStep.CALLS -> StepTexts(
        R.string.setup_calls_title,
        R.string.setup_calls_detail,
        SetupAction.CALL_PERMISSIONS,
        R.string.setup_allow_calls,
    )
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt`, replace

```kotlin
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
```

with

```kotlin
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            callsAllowed = granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS),
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace

```kotlin
    private fun refresh() {
        // Checking the model files can take seconds the first time, so the other steps show at once, and the model
        // files keep their last known status until the check ends.
        status = SetupStatusReader(this).read().copy(models = status?.models ?: ModelCheck.Checking)
        checkModels()
    }
```

with

```kotlin
    // Whether READ_PHONE_STATE was granted at the last refresh. A grant in Android's settings does not restart Kestrel,
    // so the call monitor starts here too (section 8.7).
    private var phoneStateGranted = false

    private fun refresh() {
        // Checking the model files can take seconds the first time, so the other steps show at once, and the model
        // files keep their last known status until the check ends.
        status = SetupStatusReader(this).read().copy(models = status?.models ?: ModelCheck.Checking)
        // Kestrel watches the call state from now on, also while it listens already. refresh() runs in onResume and
        // after the permission dialog, so this covers a grant in the dialog and one in Android's settings.
        val granted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (granted && !phoneStateGranted) graph.listening.watchCalls()
        phoneStateGranted = granted
        checkModels()
    }
```

Replace

```kotlin
            SetupAction.PERMISSIONS -> requestPermissions()
```

with

```kotlin
            SetupAction.PERMISSIONS -> askForPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            // Section 8.7: the call state and the call commands.
            SetupAction.CALL_PERMISSIONS ->
                askForPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
```

Replace

```kotlin
    private fun requestPermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        missingWithoutRationale = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it) }
            .toSet()
        permissions.launch(wanted)
    }
```

with

```kotlin
    private fun askForPermissions(vararg wanted: String) {
        missingWithoutRationale = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it) }
            .toSet()
        permissions.launch(arrayOf(*wanted))
    }
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="setup_assistant_detail">Optional: for the side key and earbuds. This step comes with Kestrel\'s voice features.</string>
```

with

```xml
    <string name="setup_assistant_detail">Optional: for the side key and earbuds. This step comes with Kestrel\'s voice features.</string>
    <string name="setup_calls_title">Let Kestrel answer and decline calls</string>
    <string name="setup_calls_detail">Optional: while a call rings, Kestrel then listens for \"answer\", \"answer on speaker\" and \"decline\", also on the lock screen. Kestrel reads no phone number and no call log.</string>
```

Replace

```xml
    <string name="setup_open_app_settings">Open app settings</string>
```

with

```xml
    <string name="setup_open_app_settings">Open app settings</string>
    <string name="setup_allow_calls">Allow calls</string>
```

- [ ] **Step 9: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RequestsTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.CallActionsTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD SUCCESSFUL`, with 27 tests in `RequestsTest` (24 and 3), 53 in `RulesTest` (51 and 2), 8 in `CallActionsTest`, 104 in `ListeningTest` (101 and 3) and 10 in `SetupChecklistTest` (9 and `theCallStepIsDoneWithBothPhonePermissions`).

- [ ] **Step 10: Add the largest row ID to the script helpers**

In `scripts/lib/Kestrel.ps1`, add at the end of the file:

```powershell

# Returns the largest _id in the output of 'content query --uri <uri> --projection _id', whose lines read
# 'Row: 0 _id=12', or 0 when it has no row, as when the device answers 'No result found.'.
function Get-LargestRowId([string[]]$Rows) {
    $largest = [long]0
    foreach ($line in $Rows) {
        if ($line -match '\b_id=(\d+)') { $largest = [math]::Max($largest, [long]$Matches[1]) }
    }
    $largest
}
```

- [ ] **Step 11: Add the call check to listen-check.ps1**

In `scripts/listen-check.ps1`, replace

```powershell
#   settings  with the accessibility service on, 'Kestrel, open settings' (a transcript that the hook sends) brings
#             Kestrel's settings app to the front
```

with

```powershell
#   settings  with the accessibility service on, 'Kestrel, open settings' (a transcript that the hook sends) brings
#             Kestrel's settings app to the front
#   call      on an emulator only, because only its console can place a call ('gsm call', 'gsm cancel'): with the
#             accessibility service on and Kestrel in the background, a call rings, Kestrel shows Ringing and Android
#             does not silence its recording; "decline" (a transcript that the hook sends) declines it, and "answer"
#             answers the next one, both through the call's heads-up notification; Kestrel shows In call, and listening
#             comes back when the call ends. Then, with the permission to answer calls, a plain "answer" answers a third
#             call through Android's call API and leaves the in-call screen behind, and "answer on speaker" answers a
#             fourth one, brings the in-call screen to the front and leaves its speaker control on (the emulator has no
#             earpiece, so its calls are on the speaker from the start). Last, with the swipe lock screen on, a call
#             that wakes the locked screen starts Kestrel's recording, which Android must not silence, and "decline"
#             declines it (design spec, section 8.7; spike S1). On a phone the check is skipped and changes nothing
```

Replace

```powershell
# notification check, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check.
# Afterwards, also when it fails or is interrupted, it turns Kestrel off, closes the panel and puts back the tiles, the
# accessibility settings and the permissions, and it deletes the dump of the screen. When Kestrel's accessibility
# service was off before the run, Kestrel's notification that the service is off stays afterwards, because the shell
# cannot remove another app's notification; swipe it away. If it cannot put something back, also because Ctrl+C
# interrupted it while it put things back, it says at the end what is left and how to put it back by hand. Then, after
```

with

```powershell
# notification check, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check. On an
# emulator, it also grants Kestrel the permission to read the phone state, takes away its permission to answer calls
# until the call check needs it, and turns the swipe lock screen on during the call check.
# Afterwards, also when it fails or is interrupted, it turns Kestrel off, closes the panel and puts back the tiles, the
# accessibility settings, the lock screen and the permissions, deletes the calls that it made from the call log, and
# deletes the dump of the screen. When Kestrel's accessibility service was off before the run, Kestrel's notification
# that the service is off stays afterwards, because the shell cannot remove another app's notification; swipe it away.
# If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what
# is left and how to put it back by hand. Then, after
```

Replace

```powershell
# Where 'uiautomator dump' writes the screen's tree on the device; Get-UiDump deletes it after each read.
$uiDumpFile = '/data/local/tmp/kestrel-ui.xml'
```

with

```powershell
# Where 'uiautomator dump' writes the screen's tree on the device; Get-UiDump deletes it after each read.
$uiDumpFile = '/data/local/tmp/kestrel-ui.xml'
# The call check's permissions, and the number from which the emulator's console calls. The script deletes these calls
# from the call log afterwards, and it never prints the call log.
$phoneStatePermission = 'android.permission.READ_PHONE_STATE'
$answerCallsPermission = 'android.permission.ANSWER_PHONE_CALLS'
$testNumber = '5550175'
# The label of the in-call screen's speaker control (Step 1 of plan 2D's Task 3 looked at it), and Google's in-call
# screen in front (prototype P3).
$speakerLabel = 'Speaker'
$inCallScreenPattern = 'topResumedActivity=ActivityRecord\{[^}]* com\.google\.android\.dialer/\S*InCallActivity'
```

Replace

```powershell
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }
```

with

```powershell
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

# Sends a command to the emulator's console, such as 'gsm call', after checking that the serial still names
# kestrel-api37: only an emulator has a console. The console answers 'OK'.
function Invoke-Emulator([string]$Command) {
    # adb's error lines become part of the error message, as in Invoke-Once.
    $ErrorActionPreference = 'Continue'
    # Read before the if, because PSScriptAnalyzer takes a 2> in an if's condition for a comparison.
    $name = "$(& $adb -s $Serial emu avd name 2>$null)"
    if ($name -notmatch '^kestrel-api37\b') {
        throw "Refusing to send '$Command' to ${Serial}: it is no longer kestrel-api37."
    }
    $output = @(& $adb -s $Serial emu @($Command -split ' ') 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not ($output -match '^OK')) { throw "The emulator did not take '$Command': $($output -join ' ')" }
}

# Whether Google's in-call screen is in front. Only the answer leaves this function, because the dump names other apps.
function Test-InCallScreen {
    ((Invoke-Checked 'dumpsys activity activities') -join "`n") -match $inCallScreenPattern
}

# Whether the in-call screen's speaker control is on: the element labelled $speakerLabel, or a parent of it, is checked.
# Only the answer leaves this function, because the dump names the call.
function Test-SpeakerOn {
    $xml = New-Object System.Xml.XmlDocument
    try { $xml.LoadXml((Get-UiDump)) } catch { return $false }
    foreach ($node in @($xml.SelectNodes('//node'))) {
        if ($node.GetAttribute('content-desc').Trim() -ne $speakerLabel -and $node.GetAttribute('text').Trim() -ne $speakerLabel) {
            continue
        }
        for ($at = $node; $at -and $at.LocalName -eq 'node'; $at = $at.ParentNode) {
            if ($at.GetAttribute('checked') -eq 'true') { return $true }
        }
    }
    $false
}
```

Replace

```powershell
function Invoke-RestartCheck {
```

with

```powershell
function Invoke-CallCheck {
    if (-not $isEmulator) { return 'skipped: only an emulator can place a call to itself' }
    Stop-Kestrel
    # The accessibility service is on since the shortcut check; Kestrel clicks the call's controls through it.
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before the call$modelsHint" }
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    try {
        # Without the permission to answer calls, Kestrel clicks the controls of the call's heads-up notification.
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing within 15 s of an incoming call' }
        # Kestrel's recording goes on from before the call, so its newest line counts. Android may silence a recording a
        # moment after the call starts to ring, so the check looks again for 3 s.
        foreach ($look in 1..6) {
            $state = Get-NewRecordingState -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel -Before @()
            if ($state -ne 'not silenced') { return "failed: while the call rang, Kestrel's newest recording was '$state'" }
            Start-Sleep -Milliseconds 500
        }
        Invoke-Hook voice -Send heard -Text 'Decline.' | Out-Null
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: "decline" did not end the ringing call within 15 s' }
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing for the second call within 15 s' }
        Invoke-Hook voice -Send heard -Text 'Answer.' | Out-Null
        if ((Wait-Mode in_call 15) -lt 0) { return 'failed: "answer" did not answer the call within 15 s' }
        Invoke-Emulator "gsm cancel $testNumber"
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: listening did not come back within 15 s of the end of the call' }
        # With the permission, Kestrel answers through Android's call API, which leaves the in-call screen behind
        # (prototype P3): a plain "answer" must leave it there, and "answer on speaker" must bring it to the front with its
        # speaker control on. The emulator has no earpiece, so its calls are on the speaker from the start, and Kestrel
        # must leave the control as it is.
        Invoke-Checked "pm grant dev.kestrel $answerCallsPermission" | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing for the third call within 15 s' }
        Invoke-Hook voice -Send heard -Text 'Answer.' | Out-Null
        if ((Wait-Mode in_call 15) -lt 0) { return 'failed: "answer" did not answer the third call within 15 s' }
        $plainShown = Wait-Condition -TimeoutSeconds 5 { Test-InCallScreen }
        Invoke-Emulator "gsm cancel $testNumber"
        if ($plainShown) {
            return 'failed: the in-call screen came to the front after a plain "answer", so the check cannot tell whether "answer on speaker" brought it there'
        }
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: listening did not come back within 15 s of the end of the third call' }
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing for the fourth call within 15 s' }
        Invoke-Hook voice -Send heard -Text 'Answer on speaker.' | Out-Null
        if ((Wait-Mode in_call 15) -lt 0) { return 'failed: "answer on speaker" did not answer the call within 15 s' }
        # Kestrel waits up to 5 s for the active call, brings the in-call screen to the front, and then looks for the
        # speaker control for up to 3 s.
        $shown = Wait-Condition -TimeoutSeconds 15 { Test-InCallScreen }
        if ($shown) { Start-Sleep -Seconds 4 }
        $speakerOn = $shown -and (Test-SpeakerOn)
        Invoke-Emulator "gsm cancel $testNumber"
        if (-not $shown) { return 'failed: "answer on speaker" answered the call, but the in-call screen did not come to the front within 15 s' }
        if (-not $speakerOn) { return 'failed: "answer on speaker" brought the in-call screen to the front, but its speaker control was not on' }
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: listening did not come back within 15 s of the end of the fourth call' }
        # Also on the lock screen, Kestrel uses Android's call API (spike S1's open scenario).
        Invoke-Checked 'cmd lock_settings set-disabled false' | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_SLEEP' | Out-Null
        if (-not (Wait-Condition -TimeoutSeconds 10 { (Get-VoiceState).screenOn -eq 'false' })) {
            return 'failed: Kestrel did not see the screen turn off within 10 s'
        }
        $before = Get-KestrelRecording
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing on the lock screen within 15 s' }
        $problem = Get-RecordingProblem $before
        if ($problem) { return "failed: Ringing on the lock screen, but $problem" }
        Invoke-Hook voice -Send heard -Text 'Decline.' | Out-Null
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: "decline" did not end the call on the lock screen within 15 s' }
        ('ok: "decline" and "answer" worked through the call''s notification, a plain "answer" through the call API left ' +
            'the in-call screen behind, "answer on speaker" brought it to the front with the speaker on, and "decline" ' +
            'worked with the permission to answer calls on the lock screen, where Kestrel recorded while the call rang')
    } finally {
        # A call that a failed step left goes. The later checks need the screen on and unlocked; the reboot check also
        # needs the lock screen off.
        try { Invoke-Emulator "gsm cancel $testNumber" } catch { Write-Verbose 'No call was left to end.' }
        if ($lockScreenOff) { Invoke-Checked 'cmd lock_settings set-disabled true' | Out-Null }
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Invoke-Checked 'wm dismiss-keyguard' | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

function Invoke-RestartCheck {
```

Replace

```powershell
$notificationsBefore = Test-KestrelPermission $notificationPermission
```

with

```powershell
$notificationsBefore = Test-KestrelPermission $notificationPermission
# The call check runs only on an emulator; on a phone none of these is read or changed.
$phoneStateBefore = $isEmulator -and (Test-KestrelPermission $phoneStatePermission)
$answerCallsBefore = $isEmulator -and (Test-KestrelPermission $answerCallsPermission)
$lockScreenOff = $isEmulator -and ((Invoke-Checked 'cmd lock_settings get-disabled') -join '').Trim() -eq 'true'
# The check's calls are newer than the newest row of the call log now. The query reads only the rows' IDs.
$callLogLargest = if ($isEmulator) { Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://call_log/calls --projection _id') } else { 0 }
$callLogDelete = "content delete --uri content://call_log/calls --where '_id>$callLogLargest AND number=$testNumber'"
```

Replace

```powershell
    # Revoking a permission ends Kestrel's process, so these come last.
    if (-not $notificationsBefore) {
```

with

```powershell
    # The call check's lock screen, calls and permission to answer calls, on an emulator only. The check turns the lock
    # screen off again itself; this restore also covers a Ctrl+C.
    if ($lockScreenOff) {
        Register-Restore 'the disabled lock screen' (Format-DeviceCommand 'cmd lock_settings set-disabled true') {
            Assert-TestDevice -Serial $Serial
            Invoke-Checked 'cmd lock_settings set-disabled true' | Out-Null
        }
    }
    if ($isEmulator) {
        Register-Restore 'the call log without the test calls' (Format-DeviceCommand $callLogDelete) {
            Assert-TestDevice -Serial $Serial
            Invoke-Checked $callLogDelete | Out-Null
        }
    }
    if ($answerCallsBefore) {
        Register-Restore "Kestrel's permission to answer calls" (Format-DeviceCommand "pm grant dev.kestrel $answerCallsPermission") {
            Assert-TestDevice -Serial $Serial
            Invoke-Checked "pm grant dev.kestrel $answerCallsPermission" | Out-Null
        }
    }
    # Revoking a permission ends Kestrel's process, so these come last.
    if (-not $notificationsBefore) {
```

Replace

```powershell
            if (Test-KestrelPermission $microphonePermission) { Invoke-Checked "pm revoke dev.kestrel $microphonePermission" | Out-Null }
            if (Test-KestrelPermission $microphonePermission) { throw 'it is still granted' }
        }
    }
)
```

with

```powershell
            if (Test-KestrelPermission $microphonePermission) { Invoke-Checked "pm revoke dev.kestrel $microphonePermission" | Out-Null }
            if (Test-KestrelPermission $microphonePermission) { throw 'it is still granted' }
        }
    }
    if ($isEmulator -and -not $answerCallsBefore) {
        Register-Restore "Kestrel's permission to answer calls" (Format-DeviceCommand "pm revoke dev.kestrel $answerCallsPermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $answerCallsPermission) { Invoke-Checked "pm revoke dev.kestrel $answerCallsPermission" | Out-Null }
            if (Test-KestrelPermission $answerCallsPermission) { throw 'it is still granted' }
        }
    }
    if ($isEmulator -and -not $phoneStateBefore) {
        Register-Restore "Kestrel's permission to read the phone state" (Format-DeviceCommand "pm revoke dev.kestrel $phoneStatePermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $phoneStatePermission) { Invoke-Checked "pm revoke dev.kestrel $phoneStatePermission" | Out-Null }
            if (Test-KestrelPermission $phoneStatePermission) { throw 'it is still granted' }
        }
    }
)
```

Replace

```powershell
    # The notification check taps Kestrel's problems notification.
    Invoke-Checked "pm grant dev.kestrel $notificationPermission" | Out-Null
```

with

```powershell
    # The notification check taps Kestrel's problems notification.
    Invoke-Checked "pm grant dev.kestrel $notificationPermission" | Out-Null
    if ($isEmulator) {
        # The call check: Kestrel watches the call state only with this permission.
        Invoke-Checked "pm grant dev.kestrel $phoneStatePermission" | Out-Null
        # The call check first clicks the phone app's controls, which Kestrel does only without this permission.
        # Revoking it ends Kestrel's process, which the hook below starts again.
        if ($answerCallsBefore) { Invoke-Checked "pm revoke dev.kestrel $answerCallsPermission" | Out-Null }
    }
```

Replace

```powershell
    $results['settings'] = Invoke-SettingsCheck
```

with

```powershell
    $results['settings'] = Invoke-SettingsCheck
    $results['call'] = Invoke-CallCheck
```

- [ ] **Step 12: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 101, Failed: 0" in both shells (99 and the two tests of `Get-LargestRowId`), "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 13: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.7, replace

```markdown
- While a call rings, Kestrel understands only "answer", "answer on speaker" and "decline". It performs them on the incoming-call screen with a button press or a swipe gesture. If that fails, it uses Android's call API, which needs the `ANSWER_PHONE_CALLS` permission. Spike S1 checks that this also works while the phone is locked.
- During the call Kestrel is paused. An optional setting keeps it listening for "hang up", "mute" and "speaker". The other person can hear these commands.
- After the call, the previous listening mode returns. If the call interrupted a task, Kestrel asks "Continue the task?" (section 14.4).
```

with

```markdown
- Kestrel learns the call state from Android's call-state listener while a voice session is open and it has the permission `READ_PHONE_STATE`. Without it, Kestrel never enters Ringing and says nothing about calls. It reads no phone number and no call log.
- While a call rings, Kestrel stops speaking at once, records also on the lock screen (section 8.5), and understands only "answer", "answer on speaker" and "decline", with or without its name and with the polite words of its other phrases. Everything else is ignored. With the permission `ANSWER_PHONE_CALLS`, it answers and declines through Android's call API (`TelecomManager.acceptRingingCall()` and `endCall()`), which works on the lock screen and does not depend on the phone app's layout or language. Without it, it clicks the control whose content description or text is "Answer" or "Decline" in the windows on the screen, such as the call's heads-up notification or the incoming-call screen over the lock screen, through its accessibility service. It does not search the call notification's own actions, so when the owner has pushed the heads-up notification away, it finds nothing to click and says so. The labels are English; the phone app's other languages come with sub-project 3's matching. "Answer on speaker" answers, brings the in-call screen to the front once the call is active, and clicks its "Speaker" control unless the control is on already; if it finds none, Kestrel shows "I answered, but I couldn't turn on the speaker." Every failure shows its reason on the feedback bar (section 14) while the accessibility service runs; without the service there is no bar, and Kestrel cannot speak during a call, so a failure shows nothing.
- During the call Kestrel is paused, and its microphone is off. With the setting "Commands during a call" (off by default, section 15), it listens for "hang up" (Android's call API; without the permission, a control labelled "End call", which Google's phone app does not have, so Kestrel says that it needs the permission), "mute" and "speaker" (the in-call screen's controls) while the phone is unlocked. The other person can hear these commands. They need Kestrel's accessibility service: without it, Android silences Kestrel's recording during an active call (prototype P3), and "mute" and "speaker" press the in-call screen's controls through it. Android refuses Kestrel's audio focus while a call rings or lasts, so Kestrel's replies go to the feedback bar.
- After the call, the previous listening mode returns. If the call interrupted a task, the task is cancelled; sub-project 5 asks "Continue the task?" (section 14.4).
- The setup checklist asks for both permissions in an optional step (section 15). A grant while Kestrel listens counts at once, or, when it is made in Android's settings, when the owner next opens Kestrel's settings.
```

In section 13.6, replace

```markdown
| `READ_CONTACTS`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_CALENDAR`, `READ_SMS`, `READ_CALL_LOG`, `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Runtime | First use |
```

with

```markdown
| `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS` | Runtime | Optional, in the setup checklist (section 8.7) |
| `READ_CONTACTS`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_CALENDAR`, `READ_SMS`, `READ_CALL_LOG`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Runtime | First use |
```

In section 15, replace

```markdown
7. Sign in to a provider and choose the models.
8. Optionally, turn off Voice Access, so that the two apps don't react to the same words. Kestrel shows a warning while both are on.
```

with

```markdown
7. Optionally, let Kestrel answer and decline calls (`READ_PHONE_STATE` and `ANSWER_PHONE_CALLS`, section 8.7).
8. Sign in to a provider and choose the models.
9. Optionally, turn off Voice Access, so that the two apps don't react to the same words. Kestrel shows a warning while both are on.
```

In section 19.1, replace

```markdown
The recording while a call rings is no longer scripted; plan 2D checks it again.
```

with

```markdown
Plan 2D's call check in `scripts\listen-check.ps1` checks the recording while a call rings again, on the emulator only, because only the emulator's console can place a call: with the accessibility service on and Kestrel in the background, Android must not silence the recording while a call rings, also when the call wakes a locked screen and Kestrel's microphone starts then. The check also answers a call with "answer on speaker" through Android's call API and checks that the in-call screen comes to the front with its speaker control on, which a plain answer through the API does not do. The switch from the earpiece to the speaker is checked on no device: the emulator has no earpiece, so its calls are on the speaker from the start, the A71 has no SIM card, and the S23+ is not used for tests.
```

Replace

```markdown
(section 15, step 8)
```

with

```markdown
(section 15, step 9)
```

In `README.md`, replace

```markdown
Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock.
```

with

```markdown
Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock. With the permission to read the phone state (setup checklist, step 7), a ringing call makes Kestrel stop speaking and listen only for "answer", "answer on speaker" and "decline", also on the lock screen. It answers and declines through Android's call API when it may answer calls, and otherwise by pressing the phone app's buttons through its accessibility service. During the call Kestrel is paused, its notification offers only Stop, the tile and the shortcut choose the mode that comes back after the call, and that mode returns when the call ends.
```

Replace

```markdown
"Kestrel, open settings" (a transcript that the hook sends) while the service is on, and the return of listening
```

with

```markdown
"Kestrel, open settings" (a transcript that the hook sends) while the service is on, calls on an emulator (below), and the return of listening
```

Replace

```markdown
While it runs, it grants Kestrel the microphone and notification permissions, replaces the list of enabled accessibility services, sets the target of the accessibility shortcut, opens the notification shade, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check.
```

with

```markdown
While it runs, it grants Kestrel the microphone and notification permissions, replaces the list of enabled accessibility services, sets the target of the accessibility shortcut, opens the notification shade, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check. The call check runs only on an emulator, whose console places the calls (`gsm call`): a call rings while Kestrel listens in the background, Kestrel shows Ringing and Android does not silence its recording, "decline" declines it and "answer" answers the next one through the call's notification, and listening comes back after the call; with the permission to answer calls, a plain "answer" leaves the in-call screen behind, and "answer on speaker" brings it to the front with its speaker control on; then a call that wakes the locked screen starts Kestrel's recording, and "decline" declines it. For it the script grants Kestrel the permission to read the phone state, takes away its permission to answer calls until the third call, and turns the swipe lock screen on; afterwards it puts these back and deletes its calls from the call log, which it never prints. After a failed run, the phone app may keep a "Missed call" notification for a deleted call; swipe it away. The switch from the earpiece to the speaker is checked on no device: the emulator has no earpiece, the A71 has no SIM card, and the S23+ is not used for tests.
```

Replace

```markdown
the checklist reads the state of steps 3, 4, 5 and 8 from Android,
```

with

```markdown
the checklist reads the state of steps 3, 4, 5, 7 and 9 from Android,
```

Replace

```markdown
7. Sign in to an AI provider and choose the models. This step comes with Kestrel's provider settings.
8. Optionally, turn off Voice Access,
```

with

```markdown
7. Optionally, let Kestrel answer and decline calls. The step's button asks Android for the permissions to read the phone state and to answer calls. Kestrel reads no phone number and no call log.
8. Sign in to an AI provider and choose the models. This step comes with Kestrel's provider settings.
9. Optionally, turn off Voice Access,
```

- [ ] **Step 14: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (221 in `:app`, 122 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 15: Run the voice device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 68 run, 0 failed, 0 errors, 0 skipped.", and no warning from the restores at the end.

- [ ] **Step 16: Run the device tests of the settings app**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.settings.SettingsAppTest,dev.kestrel.app.MainActivityTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 22 tests and 0 failures: 18 in `SettingsAppTest` (17 and `theCallStepAsksForThePhonePermissions`) and 4 in `MainActivityTest`.

- [ ] **Step 17: Run listen-check.ps1**

The run needs Kestrel's current debug build, which Steps 15 and 16 installed, and the speech models, which stay on the device. No `am crash` has run in the 2 minutes before it. First note the permissions, the lock screen and the size of the call log, which the run must leave as they are:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; (& $adb -s emulator-5554 shell dumpsys package dev.kestrel) -match 'READ_PHONE_STATE|ANSWER_PHONE_CALLS'; & $adb -s emulator-5554 shell cmd lock_settings get-disabled; @((& $adb -s emulator-5554 shell content query --uri content://call_log/calls --projection _id) -match '_id=').Count`
Expected: the lines that name the two permissions (or none), `true` for the disabled lock screen, and the number of rows of the call log (0 when it is empty). Keep the output for the check after the run.

Then run every check except the reboot:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\listen-check.ps1 -Serial emulator-5554; "exit code $LASTEXITCODE"`
Expected: six lines, each of which begins with the check's name and then `ok:`, in this order, with the seconds that the run measured, and then `exit code 0`:

```
notification ok: Android refused a start in the background, and "Start listening" in the notification started listening in <seconds> s
tile         ok: listening <seconds> s after a tap, and a second tap paused it
shortcut     ok: listening <seconds> s after the shortcut, and a second press paused it
settings     ok: Kestrel's settings came to the front <seconds> s after 'Kestrel, open settings'
call         ok: "decline" and "answer" worked through the call's notification, a plain "answer" through the call API left the in-call screen behind, "answer on speaker" brought it to the front with the speaker on, and "decline" worked with the permission to answer calls on the lock screen, where Kestrel recorded while the call rang
restart      ok: listening again <seconds> s after Android ended Kestrel's process
```

No warning follows the lines. If the call line fails on the lock screen with "Android silenced the microphone", Android silences a recording that starts while a call rings on the lock screen: stop, and report it to the controller, because the design spec's exception of section 8.5 then needs another way. If it fails with "the in-call screen did not come to the front" or "its speaker control was not on", the cause is in `CallActions`, `showInCallScreen` or the label that Step 1 found; report it to the controller. Put the six lines into the task's report, so that the controller can record them in `.superpowers/sdd/progress.md`.

Then run the first command of this step again.
Expected: the same output as before the run: the same permissions, `true`, and the same number of rows.

- [ ] **Step 18: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 19: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/CallActions.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/test/kotlin/dev/kestrel/app/listen/CallActionsTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt scripts/lib/Kestrel.ps1 scripts/tests/Kestrel.Tests.ps1 scripts/listen-check.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): answer, decline and hang up by voice through Android's call API or the phone app's buttons, ask for the phone permissions in the checklist, and check calls with listen-check.ps1" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The assistant role and the earbuds' assistant button

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`, `app/src/main/kotlin/dev/kestrel/app/assist/AssistActivity.kt`, `app/src/main/res/xml/voice_interaction.xml`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`Event.Assist`, `Rules.next`, `askForTask`, new `assist`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (the class's KDoc, `assistWhenOpen`, new `assist` and `onAssistantServiceReady`, `open`, `shutdown`), `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `openSettings`)
- Modify: `app/src/main/AndroidManifest.xml` (the three services and the activity)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `ChecklistPage.kt`, `SetupStatusReader.kt`, `SettingsApp.kt` (`SetupStatus.assistantRole`, the assistant step, `SetupAction.DEFAULT_APPS`), `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` (`perform`), `app/src/main/res/values/strings.xml` (two strings)
- Modify: `scripts/voice-test.ps1` (the assistant test on emulators only, the role and its two settings put back at the end)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 7.2, 8.6 and 15), `README.md` (the paragraphs on listening, on the voice tests and on the setup checklist)
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt` (new)

**Interfaces:**
- Consumes: `State.inCall` (Task 2); `askForTask`, `waitForTask`, `changeMode`, `Step.then`; `Listening.watchCalls()`, `open()`, `shutdown()` (Tasks 2 and 3); `SetupStatus`, `SetupAction`, `StepTexts`; `VoiceTests.graph`, `useMicrophone`, `stopListening`, `assumeEmulator`, `shell`, `waitUntil`.
- Produces: `Event.Assist`; `fun Listening.assist()`; `fun Listening.onAssistantServiceReady()`; the classes `KestrelAssistantService`, `KestrelSessionService`, `KestrelRecognitionService` and `AssistActivity` in `dev.kestrel.app.assist`; `SetupStatus.assistantRole: Boolean = false`; `SetupAction.DEFAULT_APPS`; the string `setup_open_default_apps`; the device test class `dev.kestrel.app.voice.AssistTest` with `theAssistKeyStartsListeningAndWaitsForATask` (emulator only) and `theEarbudsAssistantButtonStartsListeningAndWaitsForATask`. Task 5's page "Listening and activation" shows `assistantRole` and opens `DEFAULT_APPS`.

Design D2 and spike S2. Kestrel qualifies for Android's assistant role with a `VoiceInteractionService`, a session service and a recognition service that refuses every request, all in Kestrel's main process and protected by `BIND_VOICE_INTERACTION` (research r6, P3). The assistant gesture (a long press of Home, the assist key, and on One UI 9 the side key) reaches the session's `onShow`, which calls `Listening.assist()` and hides the session's window at once, because the window covers the screen and takes the focus (P3). The earbuds' and headsets' buttons never reach the session: Android sends `VOICE_COMMAND` or `VOICE_SEARCH_HANDS_FREE` through its chooser, and a transparent trampoline activity, which the owner picks there, calls `Listening.assist()` and closes; it has a task of its own, so that closing it brings back the app that was in front, and it does not take `WEB_SEARCH` (see "Not in this plan"). `Listening.assist()` sends `Event.Assist` at once while a voice session is open; otherwise it turns Kestrel on and sends `Event.Assist` once the session is open, so that "Yes?" can be spoken and the wait for the task counts from there, also when the models take several seconds to load. `Event.Assist` starts listening if Kestrel is paused and asks for a task as after "Kestrel" on its own; over Kestrel's own voice it only stops speaking and waits, as after an interruption with "Kestrel"; while a task runs, it does what "Kestrel" does then; during a call, nothing. On the lock screen, where Kestrel does not record, it only starts listening (and stops Kestrel's voice), and asks nothing: the owner says "Kestrel, ..." after the unlock. While Kestrel is off, it changes nothing, because `Listening` turns Kestrel on first, and an Assist that comes while Kestrel is off was queued behind a turn-off, such as one after a microphone that cannot start. The setup checklist's step 6 shows whether Kestrel holds the role, and its button opens Android's default apps, because the role cannot be requested with a dialog (r6). Kestrel's assistant service tells Android not to collect the screen's text or a screenshot for its sessions, because Kestrel reads the screen through its accessibility service. A device test makes Kestrel the holder with `cmd role`, waits until Android has pointed the settings `assistant` and `voice_interaction_service` at Kestrel's service, presses `KEYCODE_ASSIST` with Kestrel in the background and its accessibility service off, and puts the holder and both settings back; it runs only on an emulator, and `voice-test.ps1` puts the holder (only when it differs) and the settings back at the end in case the test's process died. A second test starts the trampoline with `VOICE_COMMAND` explicitly, so that no chooser shows. Final review of plan 2C-4, "For plan 2D", item 5: Android lets the holder of the assistant role start activities from the background, so `AppGraph.openSettings` also opens the settings when Kestrel holds the role; `SETTINGS_NEED_SERVICE` keeps the text of plan 2C-4's fix wave, because it shows only when Kestrel has neither the service nor the role, and the accessibility service is the checklist's main way. The assistant service's `onReady` also runs the restore of the listening mode (`Listening.onAssistantServiceReady()`), once in a process, whichever service comes first: Android binds the role's service after a restart as it binds the accessibility service, and the role lets Kestrel start its microphone service from the background (P3), so an owner who uses the role without the accessibility service also gets listening back.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace

```kotlin
    @Test
    fun neverMindEndsTheWaitForATask() {
```

with

```kotlin
    @Test
    fun theAssistantGestureStartsListeningAndWaitsForATask() {
        val expected = listOf(
            Effect.Show(Feedback.ModeChanged(Mode.LISTENING)),
            Effect.Show(Feedback.AwaitingTask),
            Effect.WaitForTask(),
            Effect.Say(Reply.Yes),
        )
        val step = Rules.next(paused, Event.Assist)
        assertEquals(expected, step.effects)
        assertEquals(Mode.LISTENING, step.state.mode)
        assertEquals(Work.AWAITING_TASK, step.state.work)
        // While Kestrel listens, it only asks.
        assertEquals(expected.drop(1), Rules.next(listening, Event.Assist).effects)
        // Listening turns Kestrel on first and sends the Assist once the session is open, so one that comes while
        // Kestrel is off was queued behind a turn-off, and changes nothing.
        assertEquals(Step(State()), Rules.next(State(), Event.Assist))
        // Section 8.5: on the lock screen Kestrel does not record, so it only starts listening and asks nothing.
        val locked = paused.copy(screenOn = false)
        assertEquals(
            Step(locked.copy(mode = Mode.LISTENING), listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)))),
            Rules.next(locked, Event.Assist),
        )
        assertEquals(
            Step(
                locked.copy(mode = Mode.LISTENING, speeches = 1),
                listOf(Effect.StopSaying, Effect.Show(Feedback.ModeChanged(Mode.LISTENING))),
            ),
            Rules.next(locked.copy(speeches = 1), Event.Assist),
        )
    }

    @Test
    fun theAssistantGestureOverKestrelsVoiceOrWhileATaskRunsActsLikeTheName() {
        // As after an interruption with "Kestrel": Kestrel stops speaking and waits for the task.
        assertEquals(
            listOf(Effect.StopSaying, Effect.Show(Feedback.AwaitingTask), Effect.WaitForTask()),
            Rules.next(listening.copy(speeches = 1), Event.Assist).effects,
        )
        val running = paused.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)
        assertEquals(Step(running, listOf(Effect.StopSaying)), Rules.next(running, Event.Assist))
        val silent = running.copy(speeches = 0)
        assertEquals(Step(silent), Rules.next(silent, Event.Assist))
    }

    @Test
    fun theAssistantGestureChangesNothingDuringACall() {
        val ringing = Rules.next(listening, Event.CallState(CallState.RINGING)).state

        assertEquals(Step(ringing), Rules.next(ringing, Event.Assist))
    }

    @Test
    fun neverMindEndsTheWaitForATask() {
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

with

```kotlin
    @Test
    fun anAssistWhileKestrelIsOffAsksOnceTheSessionIsOpen() = runTest {
        val listening = listening()

        listening.assist()
        runCurrent()
        // Kestrel starts; "Yes?" waits for the voice session, so that it can be spoken and the wait counts from it.
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(Work.IDLE, listening.state.value.work)

        listening.onForeground(service)
        runCurrent()
        assertEquals(listOf(ListeningTexts.YES), sessions.opened.single().said)
        assertEquals(Work.AWAITING_TASK, listening.state.value.work)
    }

    @Test
    fun anAssistWithASessionAsksAtOnceAndOneWhoseStartFailedIsForgotten() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Pause)
        runCurrent()

        listening.assist()
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.YES), sessions.opened.single().said)

        listening.send(Event.TurnOff)
        runCurrent()
        services.failure = IllegalStateException("Not allowed in the background")
        listening.assist()
        runCurrent()
        assertEquals(Mode.OFF, listening.state.value.mode)
        services.failure = null
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        // The next session does not ask for the task of the assist whose start failed.
        assertTrue(sessions.opened[1].said.isEmpty())
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun anAssistWhoseSessionCannotStartTheMicrophoneLeavesKestrelOff() = runTest {
        sessions.microphoneFailure = SecurityException("Kestrel may not record audio")
        val listening = listening()

        listening.assist()
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        // The microphone's failure turns Kestrel off before the Assist's turn comes, and the Assist does not start it
        // again.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(1, log.count { it == "start" })
    }

    @Test
    fun theAssistantServiceRestoresTheStoredModeOnceWithTheAccessibilityService() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()

        listening.onAssistantServiceReady()
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        // The accessibility service that connects later in the same process finds the restore done.
        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(listOf("start"), log)
        assertEquals(1, memory.reads)
    }

    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, replace

```kotlin
                SetupStep.DEFAULT_ASSISTANT to StepState.LATER,
```

with

```kotlin
                SetupStep.DEFAULT_ASSISTANT to StepState.OPTIONAL,
```

Replace

```kotlin
    @Test
    fun theModelStepIsDoneOnlyWhenEveryModelIsReady() {
```

with

```kotlin
    @Test
    fun theAssistantStepFollowsTheRole() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.DEFAULT_ASSISTANT)

        assertEquals(StepState.OPTIONAL, state(nothingSetUp))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(assistantRole = true)))
    }

    @Test
    fun theModelStepIsDoneOnlyWhenEveryModelIsReady() {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace

```kotlin
    private class FakeSettingsData : SettingsData {
```

with

```kotlin
    @Test
    fun theAssistantStepOpensTheDefaultApps() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_open_default_apps)).performScrollTo().performClick()

        assertEquals(listOf(SetupAction.DEFAULT_APPS), actions)
    }

    private class FakeSettingsData : SettingsData {
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt`:

```kotlin
package dev.kestrel.app.voice

import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The assistant gesture and the earbuds' assistant button (design spec, section 8.6; prototype P3), with Kestrel in the
 * background and its accessibility service off. Both start listening and wait for a task. The test of the assistant
 * role runs only on an emulator, because it makes Kestrel the holder of the role: it puts the holder back itself, and
 * scripts\voice-test.ps1 puts it back at the end in case the test's process died before it could.
 */
class AssistTest {
    private val listening = VoiceTests.graph.listening

    // Hears nothing, so that the wait for a task lasts.
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        check(listening.state.value.mode == Mode.OFF) { "Listening is already on" }
        // As in ActivationTest: the service would let Kestrel record in the background by itself. It also removes
        // Kestrel's notice that the service is off; the script that runs the tests puts the accessibility settings back.
        VoiceTests.turnOffAccessibilityService()
        VoiceTests.useMicrophone(clip)
        VoiceTests.shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun theAssistKeyStartsListeningAndWaitsForATask() {
        // It changes the holder of the assistant role, which only voice-test.ps1 puts back if the test's process dies.
        VoiceTests.assumeEmulator()
        val holder = roleHolders()
        val settings = ASSISTANT_SETTINGS.associateWith { secure(it) }
        var failure: Throwable? = null
        try {
            VoiceTests.shell("cmd role add-role-holder $ROLE dev.kestrel")
            // Android points both settings at Kestrel's service a moment after the role changes, and the key goes to the
            // assistant that they name.
            assertTrue(
                "Kestrel did not become the assistant",
                VoiceTests.waitUntil(10_000) {
                    roleHolders() == "dev.kestrel" && ASSISTANT_SETTINGS.all { secure(it).startsWith("dev.kestrel/") }
                },
            )
            VoiceTests.shell("input keyevent KEYCODE_ASSIST")

            awaitTheWaitForATask()
            // The session hides its window at once, so that the window does not cover the app in front (prototype P3).
            assertFalse(
                "The assistant session's window has the focus",
                FOCUSED_SESSION.containsMatchIn(VoiceTests.shell("dumpsys window")),
            )
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                // The role is exclusive, so adding the earlier holder removes Kestrel, and Android points the settings
                // assistant and voice_interaction_service at that holder's service again (prototype P3).
                if (holder.isEmpty()) {
                    VoiceTests.shell("cmd role remove-role-holder $ROLE dev.kestrel")
                } else {
                    VoiceTests.shell("cmd role add-role-holder $ROLE $holder")
                }
                check(
                    VoiceTests.waitUntil(10_000) {
                        roleHolders() == holder && ASSISTANT_SETTINGS.all { unset(secure(it)) == unset(settings.getValue(it)) }
                    },
                ) { "The assistant role or its settings were not put back" }
            } catch (e: Throwable) {
                // The test's own failure comes first, with the failed restore added to it.
                val first = failure ?: throw e
                first.addSuppressed(e)
            }
        }
    }

    @Test
    fun theEarbudsAssistantButtonStartsListeningAndWaitsForATask() {
        // Started directly, as Android starts it once the owner picked Kestrel in its chooser (prototype P3).
        VoiceTests.shell("am start -n dev.kestrel/.app.assist.AssistActivity -a android.intent.action.VOICE_COMMAND")

        awaitTheWaitForATask()
        // The activity closes at once and leaves the launcher in front.
        assertTrue(
            "Kestrel's activity is still in front",
            VoiceTests.waitUntil(5_000) { !KESTREL_IN_FRONT.containsMatchIn(VoiceTests.shell("dumpsys activity activities")) },
        )
    }

    /** Waits until Kestrel listens, with its voice session open and the test's microphone recording, and waits for a task. */
    private fun awaitTheWaitForATask() {
        assertTrue(
            "Kestrel does not wait for a task; the state is ${listening.state.value}",
            VoiceTests.waitUntil(60_000) {
                val state = listening.state.value
                state.mode == Mode.LISTENING && state.work == Work.AWAITING_TASK && listening.sessionOpen.value && clip.recording
            },
        )
    }

    /** The holders of the assistant role, as `cmd role` prints them: a package name, or nothing. */
    private fun roleHolders(): String = VoiceTests.shell("cmd role get-role-holders $ROLE").trim()

    /** A secure setting as the shell prints it: "null" when it is not set. */
    private fun secure(name: String): String = VoiceTests.shell("settings get secure $name").trim()

    // Without an assistant, Android writes an empty value where the shell printed "null" before; both mean none.
    private fun unset(value: String): String = if (value == "null") "" else value

    private companion object {
        const val ROLE = "android.app.role.ASSISTANT"

        // The settings that name the assistant's service, which Android changes with the role (design D2).
        val ASSISTANT_SETTINGS = listOf("assistant", "voice_interaction_service")
        val FOCUSED_SESSION = Regex("""mCurrentFocus=Window\{[^}]*VoiceInteractionSession""")
        val KESTREL_IN_FRONT = Regex("""topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/""")
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'Assist'", "Unresolved reference 'assist'", "Unresolved reference 'onAssistantServiceReady'" and "No parameter with name 'assistantRole' found.".

- [ ] **Step 3: Ask for a task after the assistant gesture in the Rules**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    /** The phone's call state changed (section 8.7). Only Listening's call-state listener sends it. */
    data class CallState(val state: dev.kestrel.app.orchestrator.CallState) : Event
}
```

with

```kotlin
    /** The phone's call state changed (section 8.7). Only Listening's call-state listener sends it. */
    data class CallState(val state: dev.kestrel.app.orchestrator.CallState) : Event

    /**
     * The assistant gesture or an earbud's assistant button (section 8.6): Kestrel listens, if it was paused, and waits
     * for a task, as after "Kestrel" on its own. Listening turns Kestrel on first and sends it once a voice session is
     * open, so the Rules ignore it while Kestrel is off.
     */
    data object Assist : Event
}
```

Replace

```kotlin
        is Event.CallState -> call(state, event.state)
    }
```

with

```kotlin
        is Event.CallState -> call(state, event.state)
        Event.Assist -> assist(state)
    }
```

Replace

```kotlin
    private fun askForTask(state: State, shown: Effect): Step =
        waitForTask(state, shown, Effect.Show(Feedback.AwaitingTask)).then { say(it, Reply.Yes) }
```

with

```kotlin
    private fun askForTask(state: State, vararg first: Effect): Step =
        waitForTask(state, *first, Effect.Show(Feedback.AwaitingTask)).then { say(it, Reply.Yes) }
```

Replace

```kotlin
    // Section 8.7: while a call rings, only "answer", "answer on speaker" and "decline" count, also on the lock screen;
```

with

```kotlin
    // Section 8.6: the assistant gesture or an earbud's button. Kestrel listens, if it was paused, and waits for a task
    // as after "Kestrel" on its own; over its own voice it stops speaking and waits, as after an interruption with
    // "Kestrel". While a task runs, it does what "Kestrel" does then: it stops speaking. A call keeps its mode, and an
    // Assist while Kestrel is off was queued behind a turn-off, because Listening sends it only to an open session.
    private fun assist(state: State): Step = when {
        state.mode == Mode.OFF || state.inCall -> Step(state)
        state.work == Work.RUNNING_TASK -> Step(state, listOfNotNull(Effect.StopSaying.takeIf { state.speaking }))
        // Section 8.5: Kestrel does not record on the lock screen, so it asks nothing there; it hears the owner after the
        // unlock.
        !state.screenOn ->
            changeMode(state, Mode.LISTENING, *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray())
        state.speaking ->
            changeMode(state, Mode.LISTENING).then { waitForTask(it, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask)) }
        else -> changeMode(state, Mode.LISTENING).then { askForTask(it) }
    }

    // Section 8.7: while a call rings, only "answer", "answer on speaker" and "decline" count, also on the lock screen;
```

- [ ] **Step 4: Send the assist once a session is open**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
 * and the bar shows why one failed.
 */
class Listening internal constructor(
```

with

```kotlin
 * and the bar shows why one failed. [assist] turns Kestrel on for the assistant gesture and asks for a task once the
 * voice session is open (section 8.6).
 */
class Listening internal constructor(
```

Replace

```kotlin
    private var wasOn = false
```

with

```kotlin
    private var wasOn = false

    // Only [thread] uses it. The assistant gesture came while no voice session was open: the session asks for the task
    // once it opens, so that "Yes?" is spoken and the wait counts from there (section 8.6).
    private var assistWhenOpen = false
```

Replace

```kotlin
    fun watchCalls() {
        scope.launch(thread) { if (session != null) startCalls() }
    }
```

with

```kotlin
    fun watchCalls() {
        scope.launch(thread) { if (session != null) startCalls() }
    }

    /**
     * The assistant gesture or an earbud's assistant button (section 8.6): Kestrel listens, if it was off or paused, and
     * waits for a task. While no voice session is open, Kestrel turns on if it is off, and asks once the session is open.
     * Any thread may call it.
     */
    fun assist() {
        scope.launch(thread) {
            if (session != null) {
                send(Event.Assist)
            } else {
                assistWhenOpen = true
                // Acts only while Kestrel is off; otherwise a start is on its way, and its session asks.
                send(Event.Start())
            }
        }
    }

    /**
     * Kestrel's assistant service is ready, which also happens after Android ended Kestrel's process or the phone
     * restarted, while Kestrel holds the assistant role. The role lets Kestrel start its foreground service from the
     * background (prototype P3), so the restore runs as when the accessibility service connects (section 8.5): once in a
     * process, whichever service comes first. Any thread may call it.
     */
    fun onAssistantServiceReady() = onAccessibilityServiceConnected()
```

Replace

```kotlin
                // After followSafely, which starts the microphone, so that a native crash there comes before Listening is
                // stored.
                remember(state.value.mode)
```

with

```kotlin
                // After followSafely, which starts the microphone, so that a native crash there comes before Listening is
                // stored.
                remember(state.value.mode)
                if (assistWhenOpen) {
                    assistWhenOpen = false
                    send(Event.Assist)
                }
```

Replace

```kotlin
        listener?.active = false
        session = null
        listener = null
```

with

```kotlin
        listener?.active = false
        session = null
        listener = null
        // An assist that waited for a session is forgotten with the start that failed or was turned off.
        assistWhenOpen = false
```

- [ ] **Step 5: Add the assistant's services and the trampoline**

Create `app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt`:

```kotlin
package dev.kestrel.app.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.kestrel.app.KestrelApplication

/**
 * Makes Kestrel eligible for Android's assistant role (design spec, section 8.6; research r6, prototype P3). Android
 * binds it while Kestrel holds the role, also after a restart, so it runs the restore of the listening mode (section
 * 8.5). The assistant gesture opens [KestrelSessionService].
 */
class KestrelAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // An owner who uses the role without the accessibility service also gets listening back after a restart.
        (applicationContext as KestrelApplication).graph.listening.onAssistantServiceReady()
        // Kestrel reads the screen through its accessibility service, so Android need not collect the app's text or a
        // screenshot for the session at every gesture (section 13).
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

/** Opens an [AssistSession] each time the owner calls the assistant (design spec, section 8.6). */
class KestrelSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * The assistant gesture, such as a long press of Home or the side key (prototype P3): Kestrel listens and waits for a
 * task. The session's window would cover the screen and take every touch and key, so the session hides it at once.
 */
private class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        (context.applicationContext as KestrelApplication).graph.listening.assist()
        hide()
    }
}

/**
 * A speech recognition service, which Android's assistant role requires of a VoiceInteractionService (research r6).
 * Kestrel offers no speech recognition to other apps, so it refuses every request. Only Android can bind it.
 */
class KestrelRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: RemoteException) {
            // The caller has gone, so nobody is left to tell.
        }
    }

    override fun onCancel(listener: RecognitionService.Callback?) = Unit

    override fun onStopListening(listener: RecognitionService.Callback?) = Unit
}
```

Create `app/src/main/kotlin/dev/kestrel/app/assist/AssistActivity.kt`:

```kotlin
package dev.kestrel.app.assist

import android.app.Activity
import android.os.Bundle
import dev.kestrel.app.KestrelApplication

/**
 * The earbuds' and headsets' assistant button (design spec, section 8.6; prototype P3). Android starts it for
 * VOICE_COMMAND, which Bluetooth headsets send, and for VOICE_SEARCH_HANDS_FREE, once the owner has picked Kestrel in
 * Android's chooser. It starts listening and waits for a task, as the assistant gesture does, and closes at once
 * without showing anything. While it is in front, Android lets Kestrel start its microphone service.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as KestrelApplication).graph.listening.assist()
        finish()
    }
}
```

Create `app/src/main/res/xml/voice_interaction.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Design spec, section 8.6; research r6: Android's assistant role needs a session service, a recognition service and
     supportsAssist. -->
<voice-interaction-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:recognitionService="dev.kestrel.app.assist.KestrelRecognitionService"
    android:sessionService="dev.kestrel.app.assist.KestrelSessionService"
    android:supportsAssist="true" />
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
        <!-- EmojiCompat would ask Google Play services for a downloadable emoji font. -->
```

with

```xml
        <!-- The assistant role (design spec, section 8.6; research r6): the service that makes Kestrel eligible, the
             session that the assistant gesture opens, and the recognition service that the role requires. Only Android,
             which holds BIND_VOICE_INTERACTION, can bind them. -->
        <service
            android:name=".assist.KestrelAssistantService"
            android:exported="true"
            android:permission="android.permission.BIND_VOICE_INTERACTION">
            <intent-filter>
                <action android:name="android.service.voice.VoiceInteractionService" />
            </intent-filter>
            <meta-data
                android:name="android.voice_interaction"
                android:resource="@xml/voice_interaction" />
        </service>
        <service
            android:name=".assist.KestrelSessionService"
            android:exported="true"
            android:permission="android.permission.BIND_VOICE_INTERACTION" />
        <service
            android:name=".assist.KestrelRecognitionService"
            android:exported="true"
            android:permission="android.permission.BIND_VOICE_INTERACTION">
            <intent-filter>
                <action android:name="android.speech.RecognitionService" />
            </intent-filter>
        </service>

        <!-- The earbuds' and headsets' assistant button (design spec, section 8.6; prototype P3): transparent, with no
             place in the recent apps or the back stack, and in a task of its own, so that closing it brings back the
             app that was in front. Not WEB_SEARCH, which other apps send for typed web searches. -->
        <activity
            android:name=".assist.AssistActivity"
            android:excludeFromRecents="true"
            android:exported="true"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.VOICE_COMMAND" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.speech.action.VOICE_SEARCH_HANDS_FREE" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <!-- EmojiCompat would ask Google Play services for a downloadable emoji font. -->
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.app.ActivityManager
import android.content.Context
```

with

```kotlin
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
```

Replace

```kotlin
            // accessibility service, Android blocks the start from the background without an exception (prototype P1),
            // so Kestrel does not try, and says why instead.
            openSettings = {
                if (screen.value == null) {
```

with

```kotlin
            // accessibility service, Android blocks the start from the background without an exception (prototype P1),
            // so Kestrel does not try, and says why instead. Android lets the holder of the assistant role start
            // activities from the background too, because it binds the role's VoiceInteractionService (section 8.6).
            openSettings = {
                if (screen.value == null &&
                    !appContext.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
```

- [ ] **Step 6: Show the role in the setup checklist**

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace

```kotlin
 * ANSWER_PHONE_CALLS, section 8.7).
 */
```

with

```kotlin
 * ANSWER_PHONE_CALLS, section 8.7). [assistantRole]: Kestrel holds Android's assistant role (section 8.6).
 */
```

Replace

```kotlin
    val callsAllowed: Boolean = false,
)
```

with

```kotlin
    val callsAllowed: Boolean = false,
    val assistantRole: Boolean = false,
)
```

Replace

```kotlin
            SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
```

with

```kotlin
            SetupStep.PROVIDER -> StepState.LATER
            SetupStep.DEFAULT_ASSISTANT -> if (status.assistantRole) StepState.DONE else StepState.OPTIONAL
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace

```kotlin
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS, CALL_PERMISSIONS }
```

with

```kotlin
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS, CALL_PERMISSIONS, DEFAULT_APPS }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, replace

```kotlin
    SetupStep.DEFAULT_ASSISTANT -> StepTexts(R.string.setup_assistant_title, R.string.setup_assistant_detail)
```

with

```kotlin
    SetupStep.DEFAULT_ASSISTANT -> StepTexts(
        R.string.setup_assistant_title,
        R.string.setup_assistant_detail,
        SetupAction.DEFAULT_APPS,
        R.string.setup_open_default_apps,
    )
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt`, replace

```kotlin
import android.app.ActivityManager
```

with

```kotlin
import android.app.ActivityManager
import android.app.role.RoleManager
```

Replace

```kotlin
            callsAllowed = granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS),
```

with

```kotlin
            callsAllowed = granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS),
            assistantRole = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT),
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace

```kotlin
            SetupAction.APP_SETTINGS -> openAppSettings()
```

with

```kotlin
            SetupAction.APP_SETTINGS -> openAppSettings()
            // Android does not let an app ask for the assistant role, so the owner picks Kestrel there (research r6).
            SetupAction.DEFAULT_APPS -> startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="setup_assistant_detail">Optional: for the side key and earbuds. This step comes with Kestrel\'s voice features.</string>
```

with

```xml
    <string name="setup_assistant_detail">Optional: so that the assistant gesture, such as a long press of Home or the side key, starts Kestrel and lets you say a task. Android does not let an app ask for this, so pick Kestrel as the digital assistant app in Default apps.</string>
```

Replace

```xml
    <string name="setup_allow_calls">Allow calls</string>
```

with

```xml
    <string name="setup_allow_calls">Allow calls</string>
    <string name="setup_open_default_apps">Open default apps</string>
```

- [ ] **Step 7: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD SUCCESSFUL`, with 56 tests in `RulesTest` (53 and 3), 108 in `ListeningTest` (104 and 4) and 11 in `SetupChecklistTest` (10 and `theAssistantStepFollowsTheRole`).

- [ ] **Step 8: Leave the assistant test out on phones, and put the role back**

In `scripts/voice-test.ps1`, replace

```powershell
# RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar, which sets Kestrel's app op TAKE_AUDIO_FOCUS to ignore and
# back to foreground, the op's normal mode on Android 17. The run fails when one of these four tests ran on a phone or
# did not run on an emulator. On an emulator the script turns the lock screen off again at the end, sets
# TAKE_AUDIO_FOCUS back to foreground, wakes the screen and dismisses the keyguard, also when the run failed or was
# interrupted.
```

with

```powershell
# RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar, which sets Kestrel's app op TAKE_AUDIO_FOCUS to ignore and
# back to foreground, the op's normal mode on Android 17. So does AssistTest#theAssistKeyStartsListeningAndWaitsForATask,
# which makes Kestrel the holder of the assistant role. The run fails when one of these five tests ran on a phone or did
# not run on an emulator. On an emulator the script turns the lock screen off again at the end, sets TAKE_AUDIO_FOCUS
# back to foreground, gives the assistant role and the settings assistant and voice_interaction_service back as they
# were before the run, wakes the screen and dismisses the keyguard, also when the run failed or was interrupted.
```

Replace

```powershell
$lockScreenOff = $false
```

with

```powershell
$lockScreenOff = $false
# The holder of the assistant role before the run, or an empty text when no app held it; $null until it is read.
$assistantHolder = $null
# The settings that name the assistant's service before the run, which Android changes with the role.
$assistantSettings = [ordered]@{}
```

Replace

```powershell
        $lockScreenOff = ((Invoke-SettingsCommand -Serial $Serial -Command 'cmd lock_settings get-disabled') -join '').Trim() -eq 'true'
```

with

```powershell
        $lockScreenOff = ((Invoke-SettingsCommand -Serial $Serial -Command 'cmd lock_settings get-disabled') -join '').Trim() -eq 'true'
        # The assistant test makes Kestrel the holder of the role and puts the holder back, unless its process dies.
        $assistantHolder = ((Invoke-SettingsCommand -Serial $Serial -Command 'cmd role get-role-holders android.app.role.ASSISTANT') -join '').Trim()
        foreach ($name in 'assistant', 'voice_interaction_service') { $assistantSettings[$name] = Get-SecureSetting -Serial $Serial -Name $name }
```

Replace

```powershell
        # Tests that a phone must not run: one switches the microphone's privacy toggle, one turns on a lock screen that it
        # unlocks with adb, one changes the list of Quick Settings tiles, and one refuses Kestrel's audio focus.
```

with

```powershell
        # Tests that a phone must not run: one switches the microphone's privacy toggle, one turns on a lock screen that it
        # unlocks with adb, one changes the list of Quick Settings tiles, one refuses Kestrel's audio focus, and one makes
        # Kestrel the assistant.
```

Replace

```powershell
            'dev.kestrel.app.voice.RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar'
        )
```

with

```powershell
            'dev.kestrel.app.voice.RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar'
            'dev.kestrel.app.voice.AssistTest#theAssistKeyStartsListeningAndWaitsForATask'
        )
```

Replace

```powershell
            if (-not (Invoke-DeviceRestore 'give Kestrel back its audio focus' 'cmd appops set dev.kestrel TAKE_AUDIO_FOCUS foreground')) {
                $code = 1
            }
```

with

```powershell
            if (-not (Invoke-DeviceRestore 'give Kestrel back its audio focus' 'cmd appops set dev.kestrel TAKE_AUDIO_FOCUS foreground')) {
                $code = 1
            }
            # The assistant test puts the role back itself, unless its process died in between. The role is exclusive, so
            # adding the earlier holder removes Kestrel. Adding the app that holds the role already grants the role's
            # permissions again, so the script changes the holder only when it differs.
            if ($null -ne $assistantHolder) {
                $holderNow = try {
                    ((Invoke-SettingsCommand -Serial $Serial -Command 'cmd role get-role-holders android.app.role.ASSISTANT') -join '').Trim()
                } catch {
                    $null
                }
                if ($holderNow -ne $assistantHolder) {
                    $role = if ($assistantHolder) {
                        "cmd role add-role-holder android.app.role.ASSISTANT $assistantHolder"
                    } else {
                        'cmd role remove-role-holder android.app.role.ASSISTANT dev.kestrel'
                    }
                    if (-not (Invoke-DeviceRestore 'give the assistant role back' $role)) { $code = 1 }
                    # Android points both settings at the holder's service a moment after the change.
                    Start-Sleep -Seconds 2
                }
                foreach ($name in $assistantSettings.Keys) {
                    $now = try { Get-SecureSetting -Serial $Serial -Name $name } catch { $null }
                    if ($now -cne $assistantSettings[$name] -and -not (Set-SecureSetting -Serial $Serial -Name $name -Value $assistantSettings[$name])) {
                        $code = 1
                    }
                }
            }
```

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 101, Failed: 0" in both shells, "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 9: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.6, replace

```markdown
| Side key (press and hold), with Kestrel as the default digital assistant | Starts listening if needed and treats the next utterance as a task |
| Earbud assistant gesture (also through the assistant role) | The same as the side key |
```

with

```markdown
| Side key (press and hold), the assistant gesture or a long press of Home, with Kestrel as the default digital assistant | Starts listening if needed and treats the next utterance as a task: Kestrel says "Yes?" and waits for it |
| Earbud or headset assistant button (`VOICE_COMMAND`, or `VOICE_SEARCH_HANDS_FREE`), once the owner picks Kestrel in Android's chooser | The same as the side key |
```

Replace

```markdown
The side-key and earbud routes depend on Kestrel holding the assistant role (spike S2). If they fail, the shortcut, the tile and the notification still work.
```

with

```markdown
Kestrel qualifies for the assistant role with a `VoiceInteractionService`, a session service and a recognition service that refuses every request, all protected by `BIND_VOICE_INTERACTION` (spike S2, prototype P3). The assistant gesture reaches the session's `onShow`, which starts listening and hides the session's window at once, because the window would cover the screen and take every touch. Holding the role also lets Kestrel start its microphone service from the background. When Kestrel is off, it asks for the task once its voice session is open, so that "Yes?" can be spoken and the wait counts from there. On the lock screen, where Kestrel does not record (section 8.5), the gesture only starts listening and asks nothing; the owner says "Kestrel, ..." after the unlock. Kestrel's assistant service tells Android not to collect the screen's text or a screenshot for its sessions, because it reads the screen through its accessibility service. The earbuds' and headsets' buttons never reach the session: with or without the role, Android sends `VOICE_COMMAND` (Bluetooth headsets), `WEB_SEARCH` or `VOICE_SEARCH_HANDS_FREE` through its chooser. Kestrel's transparent trampoline activity takes `VOICE_COMMAND` and `VOICE_SEARCH_HANDS_FREE`, starts listening and closes. It does not take `WEB_SEARCH`, which other apps use for typed web searches. On the lock screen the chooser stays hidden (prototype P3); the A71 session checks what One UI sends for the Galaxy Buds' gesture. The role cannot be requested with a dialog, so the setup checklist opens Android's default apps. If these routes fail, the shortcut, the tile and the notification still work.
```

In section 7.2, replace

```markdown
| `KestrelAssistantService` | `VoiceInteractionService`, with the session and recognition services that the platform expects in an assistant declaration | Makes Kestrel selectable as the default digital assistant. This enables the side-key and earbud gestures. When the owner selects Kestrel as the assistant, it can also give Kestrel Android's documented exemption for starting the microphone service from the background (B4). Spikes S1 and S2 check both points. |
```

with

```markdown
| `KestrelAssistantService` | `VoiceInteractionService`, with the session and recognition services that the platform expects in an assistant declaration | Makes Kestrel selectable as the default digital assistant. This enables the assistant gesture and the side key. Holding the role also gives Kestrel Android's documented exemption for starting the microphone service from the background (B4, prototype P3), and its `onReady` restores the listening mode after a restart (section 8.5). |
| `AssistActivity` | A transparent activity for `VOICE_COMMAND` and `VOICE_SEARCH_HANDS_FREE` | The earbuds' and headsets' assistant buttons, which Android sends through its chooser with or without the role (section 8.6). |
```

In section 15, replace

```markdown
6. Optionally, make Kestrel the default digital assistant, for the side key and the earbuds.
```

with

```markdown
6. Optionally, make Kestrel the default digital assistant, for the assistant gesture and the side key. Android does not let an app ask for the role, so the step shows whether Kestrel holds it and opens Android's default apps.
```

In `README.md`, replace

```markdown
Each of them toggles between listening and paused, or starts Kestrel when it is off.
```

with

```markdown
Each of them toggles between listening and paused, or starts Kestrel when it is off. When Kestrel is the default digital assistant (setup checklist, step 6), the assistant gesture, such as a long press of Home, starts listening and asks "Yes?" for a task; on the lock screen it only starts listening, and you say "Kestrel, ..." after the unlock. The earbuds' assistant button does the same once you pick Kestrel in the chooser that Android opens for it.
```

Replace

```markdown
and `voice-test.ps1` leaves all four tests out there (the third is `ActivationTest`'s tile test, above).
```

with

```markdown
and `voice-test.ps1` leaves all five tests out there (the third is `ActivationTest`'s tile test, above).
```

Replace

```markdown
On an emulator the script turns the lock screen off again at the end and sets the op back to `foreground`, in case the test's process died before it could.
```

with

```markdown
The fifth, `AssistTest`'s test of the assist key, makes Kestrel the holder of the assistant role and gives the role back to the app that held it, which also puts back the settings `assistant` and `voice_interaction_service`. On an emulator the script turns the lock screen off again at the end, sets the op back to `foreground`, and gives the assistant role and those two settings back if they changed, in case a test's process died before it could.
```

Replace

```markdown
the checklist reads the state of steps 3, 4, 5, 7 and 9 from Android,
```

with

```markdown
the checklist reads the state of steps 3 to 7 and 9 from Android,
```

Replace

```markdown
6. Optionally, make Kestrel the default digital assistant, for the side key and earbuds. This step comes with Kestrel's voice features.
```

with

```markdown
6. Optionally, make Kestrel the default digital assistant, so that the assistant gesture, such as a long press of Home or the side key, starts Kestrel and lets you say a task. Android does not let an app ask for this role, so the step's button opens Android's default apps, where you pick Kestrel as the digital assistant app.
```

- [ ] **Step 10: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (229 in `:app`, 122 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 11: Run the voice device tests**

First note the holder of the assistant role and the two settings that name its service, which the run must leave as they are:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 shell cmd role get-role-holders android.app.role.ASSISTANT; & (Get-Adb) -s emulator-5554 shell settings get secure assistant; & (Get-Adb) -s emulator-5554 shell settings get secure voice_interaction_service`
Expected: the holder, such as `com.google.android.googlequicksearchbox`, its assistant service, and its voice interaction service.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 70 run, 0 failed, 0 errors, 0 skipped." (68 and the two tests of `AssistTest`), and no warning from the restores at the end.

Then run the first command again.
Expected: the same three lines as before.

- [ ] **Step 12: Run the device tests of the settings app**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.settings.SettingsAppTest,dev.kestrel.app.MainActivityTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 23 tests and 0 failures: 19 in `SettingsAppTest` (18 and `theAssistantStepOpensTheDefaultApps`) and 4 in `MainActivityTest`.

- [ ] **Step 13: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 14: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/assist/AssistantServices.kt app/src/main/kotlin/dev/kestrel/app/assist/AssistActivity.kt app/src/main/res/xml/voice_interaction.xml app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/AndroidManifest.xml app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/res/values/strings.xml scripts/voice-test.ps1 app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/AssistTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): start listening and ask for a task from the assistant gesture and the earbuds' assistant button, and show the assistant role in the checklist" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The listening settings

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningOptions.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (new `WakeUpMode`, `State`, `Event.Options`, `Event.Silent`, `Effect.TimeSilence`, `Rules.next`, `turnOff`, new `react`, `screen`, `silent`, `silenceTimer`, `hears`, `assist`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` (the KDoc, the constructor, `handle`, the silence timer)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (imports, the class's KDoc and constructor, `currentOptions`, the options' collector, `performUnguarded`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `SettingKeys.LISTENING_OPTIONS`, `options`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt` (`listening`, `setListening`), `SettingsApp.kt` (`SettingsPage.LISTENING`, the home page), `SetupChecklist.kt` (`SetupStatus.shortcutTarget` and `buttonTarget`), `SetupStatusReader.kt` (the targets), `app/src/main/res/values/strings.xml` (the page's strings)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.5 and 15), `README.md` (the settings pages)
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt`

**Interfaces:**
- Consumes: `State.callCommands` (Task 3), `SetupStatus.assistantRole` and `SetupAction.DEFAULT_APPS` (Task 4); `SettingsStore.observe(key): Flow<T>` and `set(key, value)`; `SettingKey(name, serializer, default)`; `PageHeader`, `PageEntry`, `ScrollingPage`; `Orchestrator(scope, perform, onError, waitMillis, answerMillis)`.
- Produces: `enum class WakeUpMode { RESUME, LISTEN, STAY_OFF }` (package `dev.kestrel.app.orchestrator`); `@Serializable data class ListeningOptions(onWakeUp: WakeUpMode = WakeUpMode.RESUME, pauseAfterSilence: Boolean = false, callCommands: Boolean = false, earbudMicrophone: Boolean = false)` (package `dev.kestrel.app.listen`); `SettingKeys.LISTENING_OPTIONS`; `State.onWakeUp`, `State.pauseAfterSilence`, `State.silence`, `State.screenSeen`; `Event.Options(onWakeUp: WakeUpMode, pauseAfterSilence: Boolean, callCommands: Boolean)`, `Event.Silent(silence: Int)`, `Effect.TimeSilence`; the `Orchestrator` parameter `silenceMillis: Long = 30_000`; the `Listening` constructor parameter `options: Flow<ListeningOptions>` and `internal val Listening.currentOptions: StateFlow<ListeningOptions>`; `SettingsData.listening: Flow<ListeningOptions>` and `suspend fun SettingsData.setListening(options: ListeningOptions)`; `enum class SettingsPage { HOME, CHECKLIST, LISTENING, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }`; `internal fun ListeningPage(data: SettingsData, status: SetupStatus, onAction: (SetupAction) -> Unit, onBack: () -> Unit)`; `SetupStatus.shortcutTarget` and `buttonTarget`; the strings `settings_listening` and `settings_listening_summary`. Task 6 reads `Listening.currentOptions.value.earbudMicrophone` for the microphone; Task 7 adds `SettingsPage.VOICE` and its own members of `SettingsData` beside these.

Design D4. The page "Listening and activation" holds "On screen wake-up" (resume the previous mode, the default; always start listening; or stay off), "Pause after 30 s without speech" (off by default), "Commands during a call" (off by default, Task 3) and "Use the earbud microphone" (off by default, Task 6), and shows how the ways to start Kestrel stand: the accessibility service, the targets of the accessibility shortcut and button, the tile, for which it says how to add it, because Android does not tell an app whether its tile is added, and the assistant role, with the button that opens Android's default apps. The choices are one `ListeningOptions` setting, stored encrypted by `SettingsStore` like every setting, and `Listening` collects them as a flow on the controller's thread and passes them to the Rules as `Event.Options`, so that a change counts at once; it also keeps them in `currentOptions` for Task 6's microphone, which reads the earbud choice when it starts, and Task 6 restarts a running recording when that choice changes. Task 5 writes the texts about the earbud microphone in their final form, so Task 6 does not change them. The Rules follow "On screen wake-up" when the screen comes back on and the phone is unlocked while Kestrel listens or is paused; a call keeps its mode, and "Stay off" turns Kestrel off then. An unlock counts as a wake-up only after the running service has reported the screen on and the phone unlocked since Kestrel turned on (`State.screenSeen`, which a turn-off clears): so the first report after a start, the unlock after a start or an assistant gesture on the lock screen, and a late report from a service that stops never apply the rule. A turn-off keeps the last screen report, because a start during the close goes to the service that still runs, which reports the screen only when it is created and when the screen changes; so Kestrel never records on the lock screen after such a start. An unlock during a call does not count. "Pause after 30 s without speech" works like the wait for a task: `Effect.TimeSilence` starts a numbered timer in the Orchestrator whenever Kestrel can hear the owner again (it starts to listen, the screen comes back on, or its reply, the wait for a task or a task ends), when the setting goes on, when the owner starts to speak and when an utterance ends; when it runs out while Kestrel listens, idle and recording, Kestrel pauses; while Kestrel speaks, waits for a task, runs one or does not record, the timer starts again.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace

```kotlin
    @Test
    fun neverMindEndsTheWaitForATask() {
```

with

```kotlin
    @Test
    fun afterAnUnlockKestrelFollowsTheWakeUpSetting() {
        fun unlocked(state: State, wakeUp: WakeUpMode): Step {
            val chosen = Rules.next(state, Event.Options(wakeUp, pauseAfterSilence = false, callCommands = false)).state
            // The running service has reported the screen on and unlocked before, in this run.
            return Rules.next(chosen.copy(screenOn = false, screenSeen = true), Event.Screen(on = true))
        }

        assertEquals(Mode.PAUSED, unlocked(paused, WakeUpMode.RESUME).state.mode)
        val listened = unlocked(paused, WakeUpMode.LISTEN)
        assertEquals(Mode.LISTENING, listened.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING))), listened.effects)
        val off = unlocked(listening, WakeUpMode.STAY_OFF)
        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(Effect.StopService, off.effects.last())
        // Only a change from off to on counts, and a call keeps its mode.
        val stayOff = Rules.next(listening, Event.Options(WakeUpMode.STAY_OFF, pauseAfterSilence = false, callCommands = false)).state
        assertEquals(Step(stayOff.copy(screenSeen = true)), Rules.next(stayOff, Event.Screen(on = true)))
        val ringing = stayOff.copy(mode = Mode.RINGING, beforeCall = Mode.LISTENING, screenOn = false)
        assertEquals(Mode.RINGING, Rules.next(ringing, Event.Screen(on = true)).state.mode)
        // A turn-off forgets the reports, and an unlock after a start on the lock screen is no wake-up.
        assertFalse(Rules.next(listening.copy(screenOn = false, screenSeen = true), Event.TurnOff).state.screenSeen)
        val startedLocked = Rules.next(stayOff, Event.Screen(on = false)).state
        assertEquals(Mode.LISTENING, Rules.next(startedLocked, Event.Screen(on = true)).state.mode)
        // A report of the screen on, then a lock and an unlock: that unlock is a wake-up.
        val used = Rules.next(Rules.next(stayOff, Event.Screen(on = true)).state, Event.Screen(on = false)).state
        assertEquals(Mode.OFF, Rules.next(used, Event.Screen(on = true)).state.mode)
        // The assistant gesture on the lock screen asks the owner to speak after the unlock, so that unlock keeps Kestrel
        // on.
        val gestured = Rules.next(stayOff.copy(mode = Mode.PAUSED, screenOn = false, screenSeen = true), Event.Assist).state
        assertEquals(Mode.LISTENING, Rules.next(gestured, Event.Screen(on = true)).state.mode)
    }

    @Test
    fun thirtySecondsWithoutSpeechPauseKestrelWhenTheSettingIsOn() {
        // The timer starts when the setting goes on while Kestrel listens, and again when the owner starts to speak.
        val on = Rules.next(listening, Event.Options(WakeUpMode.RESUME, pauseAfterSilence = true, callCommands = false))
        assertEquals(listOf(Effect.TimeSilence), on.effects)
        assertEquals(1, on.state.silence)
        val spoke = Rules.next(on.state, Event.Speaking)
        assertEquals(listOf(Effect.TimeSilence), spoke.effects)
        assertEquals(2, spoke.state.silence)
        // The end of an utterance starts it again, so that the time counts from the end of the owner's speech.
        assertEquals(listOf(Effect.TimeSilence), Rules.next(spoke.state, Event.NothingHeard).effects)
        assertEquals(listOf(Effect.TimeSilence), Rules.next(spoke.state, Event.Heard("", named = false)).effects)

        // The end of an older timer changes nothing; the end of the latest one pauses Kestrel.
        assertEquals(Step(spoke.state), Rules.next(spoke.state, Event.Silent(1)))
        val silent = Rules.next(spoke.state, Event.Silent(2))
        assertEquals(Mode.PAUSED, silent.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.PAUSED))), silent.effects)

        // Listening again starts the timer again.
        assertEquals(
            listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)), Effect.TimeSilence),
            Rules.next(silent.state, Event.Toggle).effects,
        )
        // The owner gets the whole 30 s again when Kestrel can hear again: after an unlock, and after a reply.
        val dark = listening.copy(pauseAfterSilence = true, silence = 3, screenOn = false)
        assertEquals(listOf(Effect.TimeSilence), Rules.next(dark, Event.Screen(on = true)).effects)
        val replying = listening.copy(pauseAfterSilence = true, silence = 3, speeches = 1)
        assertEquals(listOf(Effect.TimeSilence), Rules.next(replying, Event.SpeechEnded).effects)
        // Without the setting, nothing times the silence.
        assertEquals(emptyList<Effect>(), Rules.next(listening, Event.Speaking).effects)
        assertEquals(Step(listening.copy(silence = 2)), Rules.next(listening.copy(silence = 2), Event.Silent(2)))
    }

    @Test
    fun theSilenceTimerStartsAgainWhileKestrelIsBusyOrDoesNotRecord() {
        val timed = listening.copy(pauseAfterSilence = true, silence = 1)

        for (busy in listOf(timed.copy(speeches = 1), timed.copy(work = Work.RUNNING_TASK, task = 1), timed.copy(screenOn = false))) {
            assertEquals(Step(busy.copy(silence = 2), listOf(Effect.TimeSilence)), Rules.next(busy, Event.Silent(1)))
        }
    }

    @Test
    fun neverMindEndsTheWaitForATask() {
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace

```kotlin
    @Test
    fun anEffectThatFailsIsReportedAndTheNextOnesStillCount() = runTest {
```

with

```kotlin
    @Test
    fun thirtySecondsWithoutSpeechPauseKestrel() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Options(WakeUpMode.RESUME, pauseAfterSilence = true, callCommands = false))
        orchestrator.send(Event.Toggle)
        runCurrent()
        // The Orchestrator runs the timer itself.
        assertFalse(performed.any { it is Effect.TimeSilence })
        advanceTimeBy(20_000)
        orchestrator.send(Event.Speaking)
        runCurrent()

        // Counted from the start, the pause would come at 30 s; counted from the speech, at 50 s.
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(Mode.LISTENING, orchestrator.state.value.mode)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Mode.PAUSED, orchestrator.state.value.mode)
    }

    @Test
    fun anEffectThatFailsIsReportedAndTheNextOnesStillCount() = runTest {
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Work
```

with

```kotlin
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.WakeUpMode
import dev.kestrel.app.orchestrator.Work
```

Replace

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
```

with

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
```

Replace

```kotlin
    private val callActions = FakeCallControl()
```

with

```kotlin
    private val callActions = FakeCallControl()
    private val storedOptions = MutableStateFlow(ListeningOptions())
```

Replace

```kotlin
    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

with

```kotlin
    @Test
    fun theStoredOptionsReachTheOrchestratorWhenTheyChange() = runTest {
        val listening = listeningWithSession()

        storedOptions.value = ListeningOptions(WakeUpMode.LISTEN, pauseAfterSilence = true, callCommands = true, earbudMicrophone = true)
        runCurrent()

        val state = listening.state.value
        assertEquals(WakeUpMode.LISTEN, state.onWakeUp)
        assertTrue(state.pauseAfterSilence)
        assertTrue(state.callCommands)
        assertEquals(storedOptions.value, listening.currentOptions.value)
    }

    @Test
    fun optionsThatCannotBeReadAreReportedAndTheDefaultsStay() = runTest {
        val listening = listening(options = flow { throw IOException("The settings cannot be read") })
        runCurrent()

        assertEquals("Reading the listening options", reports.single().first)
        assertEquals(ListeningOptions(), listening.currentOptions.value)
    }

    @Test
    fun aStartDuringTheCloseOnTheLockScreenDoesNotRecord() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Screen(on = false))
        runCurrent()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()

        // The tile on the lock screen while the session closes: the start goes to the service that still runs, which
        // reports the screen only when it is created and when the screen changes.
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        closing.complete(Unit)
        runCurrent()

        // Section 8.5: Kestrel records on the lock screen only while a call rings.
        assertTrue(listening.sessionOpen.value)
        assertFalse(listening.state.value.microphone)
    }

    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

Replace

```kotlin
    private fun TestScope.listening(scope: CoroutineScope = backgroundScope): Listening = Listening(
```

with

```kotlin
    private fun TestScope.listening(
        scope: CoroutineScope = backgroundScope,
        options: Flow<ListeningOptions> = storedOptions,
    ): Listening = Listening(
```

Replace

```kotlin
        callActions = callActions,
        microphones = { microphone },
    )
```

with

```kotlin
        callActions = callActions,
        options = options,
        microphones = { microphone },
    )
```

Replace

```kotlin
            callActions = callActions,
            // Its first write throws an Error, and the later ones work.
```

with

```kotlin
            callActions = callActions,
            options = storedOptions,
            // Its first write throws an Error, and the later ones work.
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace

```kotlin
import androidx.compose.ui.test.assertIsOn
```

with

```kotlin
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
```

Replace

```kotlin
import dev.kestrel.app.R
import dev.kestrel.privacy.logs.ActivityEvent
```

with

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.orchestrator.WakeUpMode
import dev.kestrel.privacy.logs.ActivityEvent
```

Replace

```kotlin
    private class FakeSettingsData : SettingsData {
        override val testMode = MutableStateFlow(true)
```

with

```kotlin
    @Test
    fun theListeningPageChangesTheOptions() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_listening)).performClick()

        compose.onNodeWithText(text(R.string.listening_wake_up_resume)).assertIsSelected()
        compose.onNodeWithText(text(R.string.listening_wake_up_stay_off)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.listening_pause_after_silence)).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.listening_call_commands)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.listening_earbud_microphone)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.listening_earbud_microphone)).assertIsOn()
        compose.onNodeWithText(text(R.string.listening_wake_up_stay_off)).assertIsSelected()
        assertEquals(
            ListeningOptions(WakeUpMode.STAY_OFF, pauseAfterSilence = true, callCommands = true, earbudMicrophone = true),
            data.listening.value,
        )
    }

    @Test
    fun theListeningPageShowsTheWaysToStartKestrel() {
        show(NOTHING_SET_UP.copy(serviceOn = true, shortcutTarget = true))
        compose.onNodeWithText(text(R.string.settings_listening)).performClick()

        compose.onNodeWithText(text(R.string.listening_service_on)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_shortcut_set)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_button_not_set)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_tile)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_assistant_not_held)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_default_apps)).performScrollTo().performClick()

        assertEquals(listOf(SetupAction.DEFAULT_APPS), actions)
    }

    private class FakeSettingsData : SettingsData {
        override val testMode = MutableStateFlow(true)
        override val listening = MutableStateFlow(ListeningOptions())
```

Replace

```kotlin
        override suspend fun setTestMode(on: Boolean) {
            testMode.value = on
        }
```

with

```kotlin
        override suspend fun setTestMode(on: Boolean) {
            testMode.value = on
        }

        override suspend fun setListening(options: ListeningOptions) {
            listening.value = options
        }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt`, replace

```kotlin
import android.Manifest
```

with

```kotlin
import android.Manifest
import android.os.ParcelFileDescriptor
```

Replace

```kotlin
    @Test
    fun seesUnrestrictedBatteryUse() {
```

with

```kotlin
    @Test
    fun seesTheShortcutTarget() {
        val saved = screen.shell("settings get secure $SHORTCUT").trim()
        try {
            putSecure(SHORTCUT, "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService")
            assertTrue(reader.read().shortcutTarget)

            putSecure(SHORTCUT, "null")
            assertFalse(reader.read().shortcutTarget)
        } finally {
            putSecure(SHORTCUT, saved)
        }
    }

    /** Writes a secure setting through the device's shell, which reads quotes, so an empty value too; "null" deletes it. */
    private fun putSecure(name: String, value: String) {
        val command = if (value == "null") "settings delete secure $name" else "settings put secure $name '${value.replace("'", "'\\''")}'"
        val (output, input) = screen.automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("$command\n".toByteArray()) }
        // The shell ends when its input ends, and only then its output, so the setting is written when this returns.
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    @Test
    fun seesUnrestrictedBatteryUse() {
```

Replace

```kotlin
            screen.shell("dumpsys deviceidle whitelist -${context.packageName}")
        }
    }
}
```

with

```kotlin
            screen.shell("dumpsys deviceidle whitelist -${context.packageName}")
        }
    }

    private companion object {
        const val SHORTCUT = "accessibility_shortcut_target_service"
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'WakeUpMode'", "Unresolved reference 'Options'", "Unresolved reference 'Silent'", "Unresolved reference 'TimeSilence'" and "Unresolved reference 'ListeningOptions'".

- [ ] **Step 3: Follow the wake-up setting and time the silence in the Rules**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
/** The phone's call state (design spec, section 8.7), as Android's call-state listener reports it. */
enum class CallState { IDLE, RINGING, ACTIVE }
```

with

```kotlin
/** The phone's call state (design spec, section 8.7), as Android's call-state listener reports it. */
enum class CallState { IDLE, RINGING, ACTIVE }

/**
 * What Kestrel does when the screen comes back on and the phone is unlocked (design spec, section 8.5): it keeps the
 * mode that it had ([RESUME]), listens ([LISTEN]), or turns off ([STAY_OFF]).
 */
enum class WakeUpMode { RESUME, LISTEN, STAY_OFF }
```

Replace

```kotlin
 * call", so that Kestrel listens for them during a call (section 8.7).
 */
```

with

```kotlin
 * call", so that Kestrel listens for them during a call (section 8.7). [onWakeUp] is what Kestrel does after an unlock,
 * and [pauseAfterSilence] pauses listening after 30 s without speech (section 8.5); [silence] is the number of the
 * latest timer of that pause, so that the end of an older one changes nothing. [screenSeen]: since Kestrel turned on,
 * the running ListeningService has reported the screen on and the phone unlocked, so that the next unlock is a wake-up
 * (section 8.5).
 */
```

Replace

```kotlin
    val callCommands: Boolean = false,
) {
```

with

```kotlin
    val callCommands: Boolean = false,
    val onWakeUp: WakeUpMode = WakeUpMode.RESUME,
    val pauseAfterSilence: Boolean = false,
    val silence: Int = 0,
    val screenSeen: Boolean = false,
) {
```

Replace

```kotlin
    data object Assist : Event
}
```

with

```kotlin
    data object Assist : Event

    /** The owner's choices for listening, as Listening read them at its start or after a change (section 15). */
    data class Options(val onWakeUp: WakeUpMode, val pauseAfterSilence: Boolean, val callCommands: Boolean) : Event

    /** Timer number [silence] of the pause after 30 s without speech has run out (section 8.5). */
    data class Silent(val silence: Int) : Event
}
```

Replace

```kotlin
    data class CallCommand(val command: Request.Call) : Effect
}
```

with

```kotlin
    data class CallCommand(val command: Request.Call) : Effect

    /**
     * Starts the timer of the pause after 30 s without speech, in place of the one that runs (section 8.5). The
     * Orchestrator runs it itself.
     */
    data object TimeSilence : Effect
}
```

Replace

```kotlin
    fun next(state: State, event: Event): Step = when (event) {
```

with

```kotlin
    fun next(state: State, event: Event): Step = silenceTimer(state, event, react(state, event))

    private fun react(state: State, event: Event): Step = when (event) {
```

Replace

```kotlin
        is Event.Screen -> Step(
            state.copy(screenOn = event.on, work = if (!event.on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work),
        )
```

with

```kotlin
        is Event.Screen -> screen(state, event.on)
```

Replace

```kotlin
        Event.Assist -> assist(state)
    }
```

with

```kotlin
        Event.Assist -> assist(state)
        is Event.Options -> Step(
            state.copy(onWakeUp = event.onWakeUp, pauseAfterSilence = event.pauseAfterSilence, callCommands = event.callCommands),
        )
        is Event.Silent -> silent(state, event.silence)
    }
```

Replace

```kotlin
    // Section 8.6: the assistant gesture or an earbud's button. Kestrel listens, if it was paused, and waits for a task
```

with

```kotlin
    // Section 8.5: after an unlock, Kestrel follows the setting "On screen wake-up" while it listens or is paused; a call
    // keeps its mode. Waiting for a task ends when the screen turns off.
    private fun screen(state: State, on: Boolean): Step {
        val changed = state.copy(
            screenOn = on,
            work = if (!on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work,
            // An unlock is a wake-up only after the owner has used the phone in this run: not the first report after a
            // start, not the unlock after a start on the lock screen, and not a report from a service that stops.
            screenSeen = state.mode != Mode.OFF && (on || state.screenSeen),
        )
        if (!on || state.screenOn || !state.screenSeen || (state.mode != Mode.LISTENING && state.mode != Mode.PAUSED)) {
            return Step(changed)
        }
        return when (state.onWakeUp) {
            WakeUpMode.RESUME -> Step(changed)
            WakeUpMode.LISTEN -> changeMode(changed, Mode.LISTENING)
            WakeUpMode.STAY_OFF -> turnOff(changed)
        }
    }

    // Section 8.5: 30 s without speech pause listening. A timer that ran out while Kestrel spoke, waited for a task, ran
    // one or did not record starts again, because the owner was not silent then, or could not be heard.
    private fun silent(state: State, silence: Int): Step = when {
        silence != state.silence || state.mode != Mode.LISTENING || !state.pauseAfterSilence -> Step(state)
        state.speaking || state.work != Work.IDLE || !state.microphone ->
            Step(state.copy(silence = state.silence + 1), listOf(Effect.TimeSilence))
        else -> switch(state, Mode.PAUSED)
    }

    // Section 8.5: while Kestrel listens with the setting on, the timer starts whenever Kestrel can hear the owner again
    // (it starts to listen, the screen comes back on, or its reply, the wait for a task or a task ends), when the setting
    // goes on, when the owner starts to speak, and when an utterance ends, so that the time counts from the end of the
    // owner's speech. Each timer gets the next number.
    private fun silenceTimer(before: State, event: Event, step: Step): Step {
        val after = step.state
        val start = after.pauseAfterSilence && hears(after) &&
            (
                !hears(before) || !before.pauseAfterSilence || event == Event.Speaking || event is Event.Heard ||
                    event == Event.NothingHeard
                )
        return if (start) Step(after.copy(silence = after.silence + 1), step.effects + Effect.TimeSilence) else step
    }

    // Kestrel listens, idle, silent itself and recording: only then does the time without speech count.
    private fun hears(state: State): Boolean =
        state.mode == Mode.LISTENING && !state.speaking && state.work == Work.IDLE && state.microphone

    // Section 8.6: the assistant gesture or an earbud's button. Kestrel listens, if it was paused, and waits for a task
```

Replace

```kotlin
            noWordsShown = false,
            beforeCall = null,
        ),
```

with

```kotlin
            noWordsShown = false,
            beforeCall = null,
            // The next run's reports start the count of wake-ups again (section 8.5).
            screenSeen = false,
        ),
```

Replace

```kotlin
        // Section 8.5: Kestrel does not record on the lock screen, so it asks nothing there; it hears the owner after the
        // unlock.
        !state.screenOn ->
            changeMode(state, Mode.LISTENING, *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray())
```

with

```kotlin
        // Section 8.5: Kestrel does not record on the lock screen, so it asks nothing there; it hears the owner after the
        // unlock, which is therefore no wake-up, as after a start on the lock screen.
        !state.screenOn -> changeMode(
            state.copy(screenSeen = false),
            Mode.LISTENING,
            *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
        )
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace

```kotlin
 * that TODO() throws, is not caught and ends the Orchestrator's loop. The timer that ends the wait for a task after
 * "Yes?" runs here: [waitMillis] for the owner to start the answer, and [answerMillis] once the owner has started it.
 */
```

with

```kotlin
 * that TODO() throws, is not caught and ends the Orchestrator's loop. The timer that ends the wait for a task after
 * "Yes?" runs here: [waitMillis] for the owner to start the answer, and [answerMillis] once the owner has started it. So
 * does the timer of the pause after [silenceMillis] without speech (section 8.5).
 */
```

Replace

```kotlin
    private val answerMillis: Long = ANSWER_MILLIS,
) {
```

with

```kotlin
    private val answerMillis: Long = ANSWER_MILLIS,
    private val silenceMillis: Long = SILENCE_MILLIS,
) {
```

Replace

```kotlin
    private var wait: Job? = null
```

with

```kotlin
    private var wait: Job? = null
    private var silence: Job? = null
```

Replace

```kotlin
        if (step.state.work != Work.AWAITING_TASK) {
            wait?.cancel()
            wait = null
        }
```

with

```kotlin
        if (step.state.work != Work.AWAITING_TASK) {
            wait?.cancel()
            wait = null
        }
        if (step.state.mode != Mode.LISTENING) {
            silence?.cancel()
            silence = null
        }
```

Replace

```kotlin
                wait = scope.launch {
                    delay(millis)
                    send(Event.WaitEnded(number))
                }
            } else {
```

with

```kotlin
                wait = scope.launch {
                    delay(millis)
                    send(Event.WaitEnded(number))
                }
            } else if (effect == Effect.TimeSilence) {
                val number = step.state.silence
                silence?.cancel()
                silence = scope.launch {
                    delay(silenceMillis)
                    send(Event.Silent(number))
                }
            } else {
```

Replace

```kotlin
        const val ANSWER_MILLIS = 35_000L
```

with

```kotlin
        const val ANSWER_MILLIS = 35_000L

        // Section 8.5: the optional pause after 30 s without speech.
        const val SILENCE_MILLIS = 30_000L
```

- [ ] **Step 4: Store the options and pass them to the Orchestrator**

Create `app/src/main/kotlin/dev/kestrel/app/listen/ListeningOptions.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.WakeUpMode
import kotlinx.serialization.Serializable

/**
 * The owner's choices for listening on the settings page "Listening and activation" (design spec, sections 8.5, 8.7,
 * 8.8 and 15). [onWakeUp]: what Kestrel does after an unlock. [pauseAfterSilence]: 30 s without speech pause listening.
 * [callCommands]: during a call, Kestrel listens for "hang up", "mute" and "speaker". [earbudMicrophone]: Kestrel
 * records with a connected Bluetooth headset's microphone. A fresh install, and a value that this version cannot read,
 * give the defaults.
 */
@Serializable
data class ListeningOptions(
    val onWakeUp: WakeUpMode = WakeUpMode.RESUME,
    val pauseAfterSilence: Boolean = false,
    val callCommands: Boolean = false,
    val earbudMicrophone: Boolean = false,
)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
```

with

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
```

Replace

```kotlin
import kotlinx.coroutines.flow.asStateFlow
```

with

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
```

Replace

```kotlin
 * voice session is open (section 8.6).
 */
class Listening internal constructor(
```

with

```kotlin
 * voice session is open (section 8.6). [options] are the owner's choices for listening (section 15); Listening collects
 * them on [thread], passes them to the Orchestrator, and keeps the newest in [currentOptions].
 */
class Listening internal constructor(
```

Replace

```kotlin
    private val callActions: CallControl,
    microphones: () -> Microphone,
) {
```

with

```kotlin
    private val callActions: CallControl,
    options: Flow<ListeningOptions>,
    microphones: () -> Microphone,
) {
```

Replace

```kotlin
    internal val sessionOpen: StateFlow<Boolean> = sessionState.asStateFlow()
```

with

```kotlin
    internal val sessionOpen: StateFlow<Boolean> = sessionState.asStateFlow()

    private val chosenOptions = MutableStateFlow(ListeningOptions())

    /** The owner's choices for listening, as Kestrel read them last, or the defaults before the first read (section 15). */
    internal val currentOptions: StateFlow<ListeningOptions> = chosenOptions.asStateFlow()
```

Replace

```kotlin
    init {
        scope.launch(thread) { orchestrator.state.collect { followSafely(it) } }
```

with

```kotlin
    init {
        scope.launch(thread) { orchestrator.state.collect { followSafely(it) } }
        // Section 15: a change counts at once. A failure to read the options is reported, and the last ones stay.
        scope.launch(thread) {
            options
                .catch { error -> if (error is Exception) report("Reading the listening options", error) else throw error }
                .collect { chosen ->
                    chosenOptions.value = chosen
                    send(Event.Options(chosen.onWakeUp, chosen.pauseAfterSilence, chosen.callCommands))
                }
        }
```

Replace

```kotlin
            // The Orchestrator runs the wait itself.
            is Effect.WaitForTask -> Unit
```

with

```kotlin
            // The Orchestrator runs the timers itself.
            is Effect.WaitForTask, Effect.TimeSilence -> Unit
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.ListeningConditions
```

with

```kotlin
import dev.kestrel.app.listen.ListeningConditions
import dev.kestrel.app.listen.ListeningOptions
```

Replace

```kotlin
    val LISTENING = SettingKey("listening", ListeningSetting.serializer(), ListeningSetting())
}
```

with

```kotlin
    val LISTENING = SettingKey("listening", ListeningSetting.serializer(), ListeningSetting())

    /** The owner's choices for listening (design spec, sections 8.5, 8.7, 8.8 and 15). */
    val LISTENING_OPTIONS = SettingKey("listening-options", ListeningOptions.serializer(), ListeningOptions())
}
```

Replace

```kotlin
            callActions = CallActions(AndroidCallApi(appContext)) {
                screen.value?.let { session -> CallScreen { label, ifOff -> session.actions.clickLabelled(label, ifOff) } }
            },
```

with

```kotlin
            callActions = CallActions(AndroidCallApi(appContext)) {
                screen.value?.let { session -> CallScreen { label, ifOff -> session.actions.clickLabelled(label, ifOff) } }
            },
            // Section 15: encrypted like every setting. Listening passes a change on at once.
            options = settings.observe(SettingKeys.LISTENING_OPTIONS),
```

- [ ] **Step 5: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 59 tests in `RulesTest` (56 and 3), 10 in `OrchestratorTest` (9 and `thirtySecondsWithoutSpeechPauseKestrel`) and 111 in `ListeningTest` (108 and 3).

- [ ] **Step 6: Add the page "Listening and activation"**

Create `app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.orchestrator.WakeUpMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The page "Listening and activation" (design spec, sections 8.5 to 8.8 and 15): what Kestrel does after an unlock, the
 * pause after 30 s without speech, the commands during a call and the earbud microphone, which all count at once, and
 * how the ways to start Kestrel stand, from [status]. Its button opens Android's default apps through [onAction].
 */
@Composable
internal fun ListeningPage(data: SettingsData, status: SetupStatus, onAction: (SetupAction) -> Unit, onBack: () -> Unit) {
    val stored by data.listening.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val writes = remember { Mutex() }
    // The options as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<ListeningOptions?>(null) }
    val options = written ?: stored
    // Stores the current options with one change, in the order of the changes. Nothing changes until the stored options
    // have been read. It reads the newest written options itself, so that two changes in one frame both count.
    val change: ((ListeningOptions) -> ListeningOptions) -> Unit = { edit ->
        (written ?: stored)?.let { current ->
            val next = edit(current)
            written = next
            scope.launch { writes.withLock { data.setListening(next) } }
        }
    }
    PageHeader(stringResource(R.string.settings_listening), onBack)
    Text(stringResource(R.string.listening_wake_up), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    for ((mode, label) in WAKE_UP_CHOICES) {
        val selected = options?.onWakeUp == mode
        // The whole row is one choice, so that its label names it for Voice Access and TalkBack.
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = options != null, role = Role.RadioButton) {
                    change { it.copy(onWakeUp = mode) }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = options != null)
            Text(stringResource(label), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    OptionSwitch(
        R.string.listening_pause_after_silence,
        R.string.listening_pause_after_silence_detail,
        options?.pauseAfterSilence,
    ) { on -> change { it.copy(pauseAfterSilence = on) } }
    OptionSwitch(R.string.listening_call_commands, R.string.listening_call_commands_detail, options?.callCommands) { on ->
        change { it.copy(callCommands = on) }
    }
    OptionSwitch(
        R.string.listening_earbud_microphone,
        R.string.listening_earbud_microphone_detail,
        options?.earbudMicrophone,
    ) { on -> change { it.copy(earbudMicrophone = on) } }
    Text(stringResource(R.string.listening_activation), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    Line(if (status.serviceOn) R.string.listening_service_on else R.string.listening_service_off)
    Line(if (status.shortcutTarget) R.string.listening_shortcut_set else R.string.listening_shortcut_not_set)
    Line(if (status.buttonTarget) R.string.listening_button_set else R.string.listening_button_not_set)
    // Android does not tell an app whether its tile is in Quick Settings.
    Line(R.string.listening_tile)
    Line(if (status.assistantRole) R.string.listening_assistant_held else R.string.listening_assistant_not_held)
    Button(onClick = { onAction(SetupAction.DEFAULT_APPS) }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.setup_open_default_apps))
    }
}

/** A switch whose whole row toggles it, so that its title names it for Voice Access and TalkBack, with a detail below. */
@Composable
private fun OptionSwitch(@StringRes title: Int, @StringRes detail: Int, on: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on == true, enabled = on != null, role = Role.Switch, onValueChange = onChange)
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = on == true, onCheckedChange = null, enabled = on != null)
    }
    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
}

/** One line about a way to start Kestrel. */
@Composable
private fun Line(@StringRes text: Int) {
    Text(stringResource(text), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
}

private val WAKE_UP_CHOICES = listOf(
    WakeUpMode.RESUME to R.string.listening_wake_up_resume,
    WakeUpMode.LISTEN to R.string.listening_wake_up_listen,
    WakeUpMode.STAY_OFF to R.string.listening_wake_up_stay_off,
)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
import dev.kestrel.app.SettingKeys
```

with

```kotlin
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.ListeningOptions
```

Replace

```kotlin
    suspend fun setTestMode(on: Boolean)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>
```

with

```kotlin
    suspend fun setTestMode(on: Boolean)

    /** The owner's choices for listening (design spec, section 15). */
    val listening: Flow<ListeningOptions>

    suspend fun setListening(options: ListeningOptions)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>
```

Replace

```kotlin
    override suspend fun activityLog() = graph.logs.activity()
```

with

```kotlin
    override val listening: Flow<ListeningOptions> = graph.settings.observe(SettingKeys.LISTENING_OPTIONS)

    override suspend fun setListening(options: ListeningOptions) {
        graph.settings.set(SettingKeys.LISTENING_OPTIONS, options)
    }

    override suspend fun activityLog() = graph.logs.activity()
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace

```kotlin
enum class SettingsPage { HOME, CHECKLIST, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }
```

with

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }
```

Replace

```kotlin
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, status.models, status.samsung, onAction, home) }
```

with

```kotlin
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, status.models, status.samsung, onAction, home) }
            SettingsPage.LISTENING -> ScrollingPage { ListeningPage(data, status, onAction, home) }
```

Replace

```kotlin
    ) { onOpen(SettingsPage.CHECKLIST) }
```

with

```kotlin
    ) { onOpen(SettingsPage.CHECKLIST) }
    PageEntry(stringResource(R.string.settings_listening), stringResource(R.string.settings_listening_summary)) {
        onOpen(SettingsPage.LISTENING)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace

```kotlin
 * ANSWER_PHONE_CALLS, section 8.7). [assistantRole]: Kestrel holds Android's assistant role (section 8.6).
 */
```

with

```kotlin
 * ANSWER_PHONE_CALLS, section 8.7). [assistantRole]: Kestrel holds Android's assistant role (section 8.6).
 * [shortcutTarget] and [buttonTarget]: Android's accessibility settings name Kestrel as the target of the accessibility
 * shortcut, and of the accessibility button or gesture (section 8.6).
 */
```

Replace

```kotlin
    val assistantRole: Boolean = false,
)
```

with

```kotlin
    val assistantRole: Boolean = false,
    val shortcutTarget: Boolean = false,
    val buttonTarget: Boolean = false,
)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt`, replace

```kotlin
            assistantRole = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT),
        )
    }
```

with

```kotlin
            assistantRole = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT),
            shortcutTarget = names(SHORTCUT_TARGET, kestrel),
            buttonTarget = names(BUTTON_TARGETS, kestrel) || names(GESTURE_TARGETS, kestrel),
        )
    }

    // Whether the secure setting [name], a list of components joined by ':', names [service]. Android may refuse an app
    // some of its settings; then the way to start Kestrel shows as not set.
    private fun names(name: String, service: ComponentName): Boolean = try {
        Settings.Secure.getString(context.contentResolver, name).orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .contains(service)
    } catch (e: SecurityException) {
        false
    }
```

Replace

```kotlin
        const val VOICE_ACCESS = "com.google.android.apps.accessibility.voiceaccess"
```

with

```kotlin
        const val VOICE_ACCESS = "com.google.android.apps.accessibility.voiceaccess"

        // The targets of the accessibility shortcut, and of the accessibility button with the navigation bar's buttons or
        // with gesture navigation (research R14).
        const val SHORTCUT_TARGET = "accessibility_shortcut_target_service"
        const val BUTTON_TARGETS = "accessibility_button_targets"
        const val GESTURE_TARGETS = "accessibility_gesture_targets"
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="tile_in_call">In a call</string>
```

with

```xml
    <string name="tile_in_call">In a call</string>

    <!-- The page "Listening and activation" (design spec, sections 8.5 to 8.8 and 15). -->
    <string name="settings_listening">Listening and activation</string>
    <string name="settings_listening_summary">After an unlock, during calls, with earbuds, and the ways to start Kestrel</string>
    <string name="listening_wake_up">On screen wake-up</string>
    <string name="listening_wake_up_resume">Resume the mode from before</string>
    <string name="listening_wake_up_listen">Always start listening</string>
    <string name="listening_wake_up_stay_off">Stay off</string>
    <string name="listening_pause_after_silence">Pause after 30 s without speech</string>
    <string name="listening_pause_after_silence_detail">While Kestrel listens, 30 seconds without speech pause it. \"Kestrel, wake up\" resumes it.</string>
    <string name="listening_call_commands">Commands during a call</string>
    <string name="listening_call_commands_detail">During a call, Kestrel listens for \"hang up\", \"mute\" and \"speaker\" while the phone is unlocked. The other person hears these commands too. They need Kestrel\'s accessibility service: without it, Android silences Kestrel\'s microphone during a call.</string>
    <string name="listening_earbud_microphone">Use the earbud microphone</string>
    <string name="listening_earbud_microphone_detail">With Bluetooth earbuds connected, Kestrel records with their microphone. The earbuds then switch to their call profile, which makes music sound worse.</string>
    <string name="listening_activation">Ways to start Kestrel</string>
    <string name="listening_service_on">Kestrel\'s accessibility service is on.</string>
    <string name="listening_service_off">Kestrel\'s accessibility service is off, so the shortcut and the button do not start Kestrel.</string>
    <string name="listening_shortcut_set">The accessibility shortcut starts Kestrel.</string>
    <string name="listening_shortcut_not_set">The accessibility shortcut does not start Kestrel. Choose Kestrel for it in Android\'s accessibility settings.</string>
    <string name="listening_button_set">The accessibility button starts Kestrel.</string>
    <string name="listening_button_not_set">The accessibility button does not start Kestrel. Choose Kestrel for it in Android\'s accessibility settings.</string>
    <string name="listening_tile">Kestrel\'s Quick Settings tile starts Kestrel. Android does not tell apps whether you added it, so add it when you edit Quick Settings.</string>
    <string name="listening_assistant_held">Kestrel is the digital assistant, so the assistant gesture, such as a long press of Home, starts it.</string>
    <string name="listening_assistant_not_held">Kestrel is not the digital assistant. Pick it as the digital assistant app in Default apps.</string>
```

- [ ] **Step 7: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.5, replace

```markdown
- An optional setting, off by default, pauses listening after 30 s without speech.
```

with

```markdown
- An optional setting, off by default, pauses listening after 30 s without speech. The time counts from the moment Kestrel can hear the owner while it listens (it starts to listen, the screen comes back on, or its reply, the wait for a task or a task ends), from the moment the setting goes on, and from the start and the end of the owner's last utterance; it starts again while Kestrel speaks, waits for a task, runs one or does not record. "Stay off" in "on screen wake-up" turns Kestrel off at the unlock; a call keeps its mode then, and an unlock during a call does not count when the call ends. An unlock counts as a wake-up only after Kestrel has seen the screen on and the phone unlocked since it turned on, so a start on the lock screen, or the assistant gesture there, is not undone at the unlock that follows. The time without speech counts from the start of listening, which comes a few seconds before the voice session has loaded its models.
```

In section 15, replace

```markdown
and an About page with licences, versions and the status of the model files. "Kestrel, open settings" opens it.
```

with

```markdown
and an About page with licences, versions and the status of the model files. "Kestrel, open settings" opens it.

The page "Listening and activation" holds "On screen wake-up" (section 8.5), "Pause after 30 s without speech" (section 8.5), "Commands during a call" (section 8.7) and "Use the earbud microphone" (section 8.8). It also shows how the ways to start Kestrel stand: the accessibility service, the targets of the accessibility shortcut and of the accessibility button, the tile (Android does not tell an app whether its tile is added, so the page says how to add it) and the assistant role, with a button that opens Android's default apps. The choices are stored encrypted, like every setting. Each choice counts at once; a change of the earbud microphone restarts a running recording (section 8.8).
```

In `README.md`, replace

```markdown
- **Safety:** test mode, which is on until you turn it off.
```

with

```markdown
- **Listening and activation:** what Kestrel does when you unlock the phone (resume the mode from before, always start listening, or stay off), a pause after 30 s without speech, the commands during a call ("hang up", "mute" and "speaker"), the earbud microphone, and whether the accessibility service, the shortcut, the button and the assistant role start Kestrel, with a button that opens Android's default apps. Each choice counts at once.
- **Safety:** test mode, which is on until you turn it off.
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (236 in `:app`, 122 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 9: Run the voice device tests**

Every start of listening now reads the stored options, and every screen report goes through the wake-up rule.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 70 run, 0 failed, 0 errors, 0 skipped.", and no warning from the restores at the end.

- [ ] **Step 10: Run the other device tests of `:app`**

Tasks 3 to 5 changed the checklist, its reader, the manifest and the settings app's pages. This step runs every device test of `:app` outside the voice package; `voice-test.ps1` ran those of the voice package in Step 9.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 108 tests and 0 failures: the 103 of plan 2C-4, the four tests that Tasks 3 to 5 added to `SettingsAppTest` (21 in all) and `SetupStatusReaderTest.seesTheShortcutTarget` (4 in all). A failure of `seesTheShortcutTarget` is not expected, because the keys of the accessibility shortcut and button are readable by apps (`@Readable` in AOSP's `Settings`). If it fails because `shortcutTarget` stays false, Android 17 refuses Kestrel the setting: report it to the controller, because the page then always says that the shortcut does not start Kestrel. On the A71 (Android 13) the gesture's key does not exist, which reads as not set.

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/ListeningOptions.kt app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): add the page Listening and activation, with the wake-up rule, the pause after 30 s without speech, the call commands and the earbud microphone" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The earbud microphone

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt` (`Headset`, `CommunicationDevices`, `HeadsetRoute`)
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/AndroidCommunicationDevices.kt`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt` (the whole file: the class's KDoc, the constructor, `open`, `close`, new `usePhoneMicrophone`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (the microphone factory)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (the options' collector restarts a recording when the earbud setting changes)
- Modify: `scripts/voice-test.ps1` (the earbud test only on emulators)
- Modify: `app/src/main/AndroidManifest.xml` (`MODIFY_AUDIO_SETTINGS`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.8 and 13.6), `README.md` (the paragraph on listening and the one on the voice tests)
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt` (JVM, new), `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt` (JVM, one new test), `app/src/androidTest/kotlin/dev/kestrel/app/voice/MicCaptureTest.kt` (voice device test, changed; run by `voice-test.ps1`)

**Interfaces:**
- Consumes: `ListeningOptions.earbudMicrophone`, `Listening.currentOptions: StateFlow<ListeningOptions>` and the options' collector from Task 5, which runs on the controller's thread; `Listening`'s constructor parameter `microphones: () -> Microphone` and its private `session`, `microphoneOn`, `releaseMicrophone` and `followSafely`; `ListeningTest`'s `storedOptions` (Task 5); `VoiceTests.assumeEmulator()`; `voice-test.ps1`'s `$emulatorOnly` list as Task 4 leaves it.
- Produces: `data class Headset(val id: Int, val leAudio: Boolean)`; `interface CommunicationDevices { fun headsets(): List<Headset>; fun choose(headset: Headset): Boolean; fun clear(); fun watch(changed: (Int?) -> Unit): AutoCloseable }`; `class HeadsetRoute(devices: CommunicationDevices) { fun open(onLost: () -> Unit): Headset?; fun close() }`; `internal class AndroidCommunicationDevices(audio: AudioManager, executor: Executor)` with `fun input(headset: Headset): AudioDeviceInfo?`; `class MicCapture(context: Context, earbud: () -> Boolean = { false })`. No later task uses them.

Design D5 and research r12. With the setting "Use the earbud microphone" on and Bluetooth earbuds connected, each start of the microphone asks Android for the earbuds as the communication device before the recording exists: an LE Audio headset first, because it keeps the music's quality, else a classic (SCO) headset. Android routes the source `VOICE_RECOGNITION` to a classic headset's microphone once the headset is the communication device, but to an LE Audio headset's microphone only when the recording also names it as its preferred device (r12, items 2 and 4), so `MicCapture` does that too for an LE Audio headset. The route withdraws its request when the microphone stops, and also when Android reports another communication device: the earbuds went away, or a call took the audio over. Then the recording goes back to the phone's microphone until the microphone starts again. Kestrel never sets Android's audio mode, so a call keeps its own route (r12, item 7). `HeadsetRoute` holds the decisions and works on a small interface, so that JVM tests cover it with a fake; `AndroidCommunicationDevices` is the thin part that calls `AudioManager`. The emulator has no Bluetooth, so the device test only checks that the setting changes nothing without earbuds and leaves no request behind; the A71 session (Task 9) checks the earbuds with the owner. The microphone reads the setting on the controller's thread when it starts, from `Listening.currentOptions`, which `Listening` keeps up to date on the same thread (Task 5), so it never waits for the settings store. A change of the setting counts at once: `Listening` restarts a running recording, so that the new recording asks for the earbuds or gives them up; while the microphone is off, for example with the screen off, the change counts from its next start. When Android lists no microphone for an LE Audio headset, the request would only move other apps' audio, so `MicCapture` withdraws it and records from the phone. `MicCaptureTest`'s new test runs only on an emulator, because on a phone with earbuds connected it would switch them to their call profile. Kestrel declares `MODIFY_AUDIO_SETTINGS`, a permission that Android grants at install, as r12 advises for `setCommunicationDevice`.

- [ ] **Step 1: Write the failing JVM tests**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt`:

```kotlin
package dev.kestrel.voice.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HeadsetRouteTest {
    /** Android's communication devices: the headsets that it offers, and the requests and watchers that it got. */
    private class FakeDevices(var headsets: List<Headset> = emptyList(), var accepts: Boolean = true) : CommunicationDevices {
        val chosen = mutableListOf<Headset>()
        var cleared = 0
        val watchers = mutableListOf<(Int?) -> Unit>()

        override fun headsets() = headsets

        override fun choose(headset: Headset): Boolean {
            chosen += headset
            return accepts
        }

        override fun clear() {
            cleared++
        }

        override fun watch(changed: (Int?) -> Unit): AutoCloseable {
            watchers += changed
            return AutoCloseable { watchers -= changed }
        }

        /** Android reports a new communication device, or null for none. */
        fun change(id: Int?) = watchers.toList().forEach { it(id) }
    }

    private val classic = Headset(id = 7, leAudio = false)
    private val le = Headset(id = 26, leAudio = true)

    @Test
    fun withoutEarbudsThePhonesMicrophoneStays() {
        val devices = FakeDevices()
        val route = HeadsetRoute(devices)

        assertNull(route.open {})
        route.close()

        assertEquals(emptyList<Headset>(), devices.chosen)
        assertEquals(0, devices.watchers.size)
        assertEquals(0, devices.cleared)
    }

    @Test
    fun anLeAudioHeadsetComesBeforeAClassicOne() {
        val devices = FakeDevices(headsets = listOf(classic, le))

        assertSame(le, HeadsetRoute(devices).open {})
        assertEquals(listOf(le), devices.chosen)
    }

    @Test
    fun aClassicHeadsetIsAskedForWhenItIsTheOnlyOne() {
        val devices = FakeDevices(headsets = listOf(classic))

        assertSame(classic, HeadsetRoute(devices).open {})
        assertEquals(listOf(classic), devices.chosen)
        assertEquals(1, devices.watchers.size)
    }

    @Test
    fun aRefusedRequestLeavesThePhonesMicrophone() {
        val devices = FakeDevices(headsets = listOf(classic), accepts = false)
        val route = HeadsetRoute(devices)

        assertNull(route.open {})
        route.close()

        assertEquals(0, devices.watchers.size)
        assertEquals(0, devices.cleared)
    }

    @Test
    fun closeWithdrawsTheRequestOnceAndStopsWatching() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        route.open {}

        route.close()
        route.close()

        assertEquals(1, devices.cleared)
        assertEquals(0, devices.watchers.size)
    }

    @Test
    fun earbudsThatGoAwayGiveTheRecordingBackToThePhone() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        var lost = 0
        route.open { lost++ }

        // Android reports no communication device, or another one, such as the earpiece of a call.
        devices.change(null)
        devices.change(3)
        route.close()

        assertEquals(1, lost)
        assertEquals(1, devices.cleared)
        assertEquals(0, devices.watchers.size)
    }

    @Test
    fun aReportOfTheChosenHeadsetKeepsTheRoute() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        var lost = 0
        route.open { lost++ }

        // Android reports the headset itself once the link is up.
        devices.change(classic.id)

        assertEquals(0, lost)
        assertEquals(0, devices.cleared)
        assertEquals(1, devices.watchers.size)
    }

    @Test
    fun theRouteOpensOnceAtATimeAndAgainAfterAClose() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        route.open {}

        assertThrows(IllegalStateException::class.java) { route.open {} }
        route.close()

        assertSame(classic, route.open {})
        assertEquals(listOf(classic, classic), devices.chosen)
    }

    @Test
    fun aLateReportToAnEarlierOpenChangesNothing() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        var lost = 0
        route.open { lost++ }
        val earlier = devices.watchers.single()
        route.close()
        route.open { lost++ }

        // Android delivers a report to the first open's watcher after its close, as it may.
        earlier(3)

        assertEquals(0, lost)
        assertEquals(1, devices.cleared)
        assertEquals(1, devices.watchers.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.HeadsetRouteTest`
Expected: `BUILD FAILED` in `:core:voice:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `CommunicationDevices`, `Headset` and `HeadsetRoute`.

- [ ] **Step 3: Write the route**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt`:

```kotlin
package dev.kestrel.voice.listen

/**
 * A Bluetooth headset that Android offers as a communication device. [id] is the ID of its output device. [leAudio]: an
 * LE Audio headset, whose microphone Android gives the recording only when the recording also asks for it as its
 * preferred device (research r12, item 4); otherwise a classic headset, which Android reaches through SCO.
 */
data class Headset(val id: Int, val leAudio: Boolean)

/** The part of Android's AudioManager that [HeadsetRoute] uses. AndroidCommunicationDevices calls Android; tests use a fake. */
interface CommunicationDevices {
    /** The Bluetooth headsets, classic or LE Audio, among the communication devices that Android offers now. */
    fun headsets(): List<Headset>

    /**
     * Asks Android to use [headset] as the communication device, whose microphone then also records the source
     * VOICE_RECOGNITION. Returns false when Android refuses, for example because the headset went away meanwhile.
     */
    fun choose(headset: Headset): Boolean

    /** Withdraws Kestrel's request, so that Android's own choice counts again. */
    fun clear()

    /**
     * Calls [changed] after each change of the communication device, with the new device's ID or null for none, until the
     * returned handle is closed. [changed] may come on another thread.
     */
    fun watch(changed: (Int?) -> Unit): AutoCloseable
}

/**
 * Routes Kestrel's recording to the microphone of connected Bluetooth earbuds while the owner allows it (design spec,
 * section 8.8; research r12). [open] asks Android for an LE Audio headset, or else for a classic one, as the
 * communication device, and [close] withdraws the request. When Android reports another communication device, because
 * the headset went away or a call took the audio over, the route withdraws its request and calls the `onLost` of
 * [open], so that the recording goes back to the phone's microphone; it asks again only at the next [open]. It never
 * sets Android's audio mode, so a call keeps its own route (r12, item 7). Any thread may call [open] and [close], one at
 * a time.
 */
class HeadsetRoute(private val devices: CommunicationDevices) {
    private val lock = Any()
    // The open that holds the request. Each open has its own token, so that a late call of an earlier open's watcher,
    // which Android may still deliver after the close, changes nothing.
    private var held: Any? = null
    private var watching: AutoCloseable? = null

    /**
     * Asks for a connected headset and returns it, or returns null when no headset is connected or Android refused it;
     * the phone's microphone then records as before. [onLost] runs at most once, on the thread of Android's report, when
     * the route has given the headset up on its own. Throws [IllegalStateException] while a headset is asked for.
     */
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
            watching = devices.watch { id -> if (id != headset.id) lose(token, onLost) }
            return headset
        }
    }

    /** Withdraws the request of [open], if there is one. */
    fun close() {
        val watch = synchronized(lock) {
            if (held == null) return
            held = null
            watching.also { watching = null }
        }
        watch?.close()
        devices.clear()
    }

    private fun lose(token: Any, onLost: () -> Unit) {
        val watch = synchronized(lock) {
            // A close, or the open after it, came first.
            if (held !== token) return
            held = null
            watching.also { watching = null }
        }
        watch?.close()
        devices.clear()
        onLost()
    }
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/listen/AndroidCommunicationDevices.kt`:

```kotlin
package dev.kestrel.voice.listen

import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.concurrent.Executor

/**
 * [CommunicationDevices] through Android's AudioManager (research r12). [executor] runs the watchers' calls. Android
 * keeps a request until it is cleared, the headset disconnects or Kestrel's process ends.
 */
internal class AndroidCommunicationDevices(private val audio: AudioManager, private val executor: Executor) : CommunicationDevices {
    override fun headsets(): List<Headset> = audio.availableCommunicationDevices.mapNotNull { device ->
        when (device.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> Headset(device.id, leAudio = true)
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Headset(device.id, leAudio = false)
            else -> null
        }
    }

    override fun choose(headset: Headset): Boolean {
        val device = audio.availableCommunicationDevices.firstOrNull { it.id == headset.id } ?: return false
        return audio.setCommunicationDevice(device)
    }

    override fun clear() {
        audio.clearCommunicationDevice()
    }

    override fun watch(changed: (Int?) -> Unit): AutoCloseable {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device -> changed(device?.id) }
        audio.addOnCommunicationDeviceChangedListener(executor, listener)
        return AutoCloseable { audio.removeOnCommunicationDeviceChangedListener(listener) }
    }

    /**
     * The microphone of the LE Audio [headset], for the recording's preferred device: the input with the address of the
     * headset's output, or else the first LE Audio input. Null when Android lists none.
     */
    fun input(headset: Headset): AudioDeviceInfo? {
        val output = audio.availableCommunicationDevices.firstOrNull { it.id == headset.id } ?: return null
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        return inputs.firstOrNull { it.address == output.address } ?: inputs.firstOrNull()
    }
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.HeadsetRouteTest`
Expected: `BUILD SUCCESSFUL`, with 9 tests in `HeadsetRouteTest`.

- [ ] **Step 5: Record from the earbuds in MicCapture**

Replace the whole content of `core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt` with:

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
 * Kestrel silence: when another app has priority on the microphone, when the microphone's privacy toggle is on, or when
 * Android counts Kestrel as idle, such as in the background without a foreground service.
 *
 * When [earbud] returns true at a start, the microphone of connected Bluetooth earbuds records instead (section 8.8): a
 * [HeadsetRoute] asks Android for the earbuds before the recording exists, so that Android routes the recording there
 * from its first frame, and for an LE Audio headset the recording also names the headset's microphone as its preferred
 * device, because Android gives that microphone to VOICE_RECOGNITION only then (research r12). When the earbuds go away,
 * or a call takes the audio over, the phone's microphone records until the next start. [stop] withdraws the request.
 * Without earbuds, when Android refuses them, or when Android lists no microphone for an LE Audio headset, the phone's
 * microphone records as before.
 *
 * Any thread may call [start] and [stop], except the microphone's own thread.
 */
class MicCapture(private val context: Context, private val earbud: () -> Boolean = { false }) : Microphone {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val devices = AndroidCommunicationDevices(audio, context.mainExecutor)
    private val silencedState = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var canceller: AcousticEchoCanceler? = null
    private var route: HeadsetRoute? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    /**
     * True while Android silences Kestrel's recording, for any of the reasons above: "mic busy" on the feedback bar
     * (section 8.1).
     */
    override val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    /** Whether Android's echo canceller works on the current recording. */
    @Volatile
    override var echoCancelling = false
        private set

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = update(configs)
    }

    /**
     * Starts recording. [onFrame] gets each 20 ms frame of samples between -1 and 1. Both callbacks run on the
     * microphone's own thread.
     *
     * [onError] is called at most once, and then the thread ends: with an [IllegalStateException] when reading the
     * microphone fails, or with the exception that [onFrame] threw, so that such an exception does not end Kestrel's
     * process. [onError] itself must not throw. After an error nothing reads the microphone any more, but the recording
     * stays open, and Android keeps showing that Kestrel uses the microphone, until [stop] closes it. [start] and [stop]
     * must not be called from [onFrame] or [onError], because [stop] waits for the thread; they throw
     * [IllegalStateException] there. The caller hands the error to another thread, which then stops the microphone.
     *
     * Throws [SecurityException] without the microphone permission and [IllegalStateException] when the microphone does
     * not start; then it has released everything, also the request for the earbuds, so that a later call can try again.
     * Only when Android's audio service fails after the microphone opened, or no thread can be created, does [start]
     * throw with the microphone still open; then [stop] releases it.
     */
    override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        checkNotOnTheMicrophonesThread()
        synchronized(this) { open(onFrame, onError) }
    }

    private fun open(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
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
        // Before the recording exists, so that Android routes it to the earbuds from its first frame (research r12).
        var earbuds = if (earbud()) HeadsetRoute(devices) else null
        val headset = earbuds?.open(onLost = ::usePhoneMicrophone)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, BUFFER_BYTES))
                .setPrivacySensitive(false)
                .build()
        } catch (e: Exception) {
            earbuds?.close()
            throw if (e is UnsupportedOperationException) IllegalStateException("The microphone could not be opened", e) else e
        }
        var effect: AcousticEchoCanceler? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
            // Android gives an LE Audio headset's microphone to VOICE_RECOGNITION only as a preferred device (r12, item 4).
            // Without such a microphone, the request would only move other apps' audio, so it is withdrawn.
            if (headset?.leAudio == true) {
                val input = devices.input(headset)
                if (input != null) {
                    recorder.setPreferredDevice(input)
                } else {
                    earbuds?.close()
                    earbuds = null
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                effect = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.setEnabled(true) }
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone did not start" }
        } catch (e: Exception) {
            effect?.release()
            recorder.release()
            earbuds?.close()
            throw e
        }
        canceller = effect
        route = earbuds
        echoCancelling = effect?.enabled == true
        record = recorder
        audio.registerAudioRecordingCallback(callback, main)
        // The callback reports only changes. Reading the current recordings on the callback's thread keeps this reading
        // in order with the reports, so that it cannot replace a newer one.
        main.post { update(audio.activeRecordingConfigurations) }
        running = true
        // The thread is known before it runs, so that stop() can recognise a call from [onFrame] or [onError].
        val thread = Thread({ read(recorder, onFrame, onError) }, "Kestrel microphone")
        reader = thread
        thread.start()
    }

    /**
     * Stops recording, and waits until the microphone's thread has finished. It also closes a recording whose thread
     * ended with an error, and withdraws the request for the earbuds. Throws [IllegalStateException] when called from
     * [start]'s callbacks, where it would wait for ever.
     */
    override fun stop() {
        checkNotOnTheMicrophonesThread()
        synchronized(this) { close() }
    }

    private fun close() {
        val recorder = record ?: return
        running = false
        try {
            recorder.stop()
            reader?.let(::joinUninterruptibly)
            reader = null
            audio.unregisterAudioRecordingCallback(callback)
            echoCancelling = false
            canceller?.release()
            canceller = null
        } finally {
            // Before the release, as research r12 advises, and also when the stop failed, so that the request for the
            // earbuds never outlives the recording.
            route?.close()
            route = null
        }
        synchronized(lock) {
            record = null
            silencedState.value = false
        }
        recorder.release()
    }

    // The earbuds went away, or a call took the audio over, and the route has withdrawn its request. Runs on the main
    // thread. Without a preferred device, Android records from the phone's microphone again.
    private fun usePhoneMicrophone() {
        synchronized(lock) { record?.setPreferredDevice(null) }
    }

    // Checked before taking the monitor: a stop() on another thread holds it while it waits for the microphone's thread.
    private fun checkNotOnTheMicrophonesThread() {
        check(Thread.currentThread() !== reader) { "start() and stop() must not be called from the microphone's thread" }
    }

    // An interrupt must not leave the microphone open, so the wait goes on, and the interrupt is kept for the caller.
    private fun joinUninterruptibly(thread: Thread) {
        var interrupted = false
        while (true) {
            try {
                thread.join()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    // A report that does not list the current recording, such as one that Android queued before the last stop(),
    // changes nothing. The lock keeps a report from landing after stop() has reset the value.
    private fun update(configs: List<AudioRecordingConfiguration>) {
        synchronized(lock) {
            val session = record?.audioSessionId ?: return
            val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            silencedState.value = mine.isClientSilenced
        }
    }

    private fun read(recorder: AudioRecord, onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(FRAME)
        while (running) {
            val count = recorder.read(buffer, 0, FRAME, AudioRecord.READ_BLOCKING)
            // A read returns fewer samples when stop() interrupts it, or when an error follows some samples; then the next
            // read reports the error. The rest is dropped.
            if (count == FRAME) {
                val frame = FloatArray(count) { buffer[it] / 32_768f }
                // Errors, such as OutOfMemoryError, are not caught.
                try {
                    onFrame(frame)
                } catch (e: Exception) {
                    onError(e)
                    return
                }
            } else if (count < 0 && running) {
                onError(IllegalStateException("The microphone failed with error $count"))
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

The changes against the old file: the KDoc's second paragraph, the sentence on the request in `start`'s KDoc and the one on the earbuds in `stop`'s KDoc; the constructor's `earbud`; the fields `devices` and `route`; in `open`, the route, the LE Audio microphone, and a `catch` around the builder that now takes every `Exception`, so that it can withdraw the request before it throws (it still turns an `UnsupportedOperationException` into an `IllegalStateException`); in `close`, the route, closed in a `finally`; and `usePhoneMicrophone`.

- [ ] **Step 6: Write the failing JVM test of a change of the setting**

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

with

```kotlin
    @Test
    fun aChangeOfTheEarbudMicrophoneRestartsARecordingAndOtherChangesDoNot() = runTest {
        val listening = listeningWithSession()
        log.clear()

        storedOptions.value = ListeningOptions(pauseAfterSilence = true)
        runCurrent()
        storedOptions.value = ListeningOptions(pauseAfterSilence = true, earbudMicrophone = true)
        runCurrent()
        listening.send(Event.Screen(on = false))
        runCurrent()
        // While the microphone is off, a change waits for its next start.
        storedOptions.value = ListeningOptions(pauseAfterSilence = true)
        runCurrent()
        listening.send(Event.Screen(on = true))
        runCurrent()

        assertEquals(
            listOf("microphone off", "microphone on", "microphone off", "microphone on"),
            log.filter { it.startsWith("microphone") },
        )
    }

    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")
```

- [ ] **Step 7: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, with 1 failing test, `aChangeOfTheEarbudMicrophoneRestartsARecordingAndOtherChangesDoNot`: the log has only the two entries of the screen.

- [ ] **Step 8: Restart a recording when the setting changes, give the microphone the owner's choice, and declare the permission**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, in the collector of the options that Task 5 added, replace

```kotlin
                .collect { chosen ->
                    chosenOptions.value = chosen
                    send(Event.Options(chosen.onWakeUp, chosen.pauseAfterSilence, chosen.callCommands))
                }
```

with

```kotlin
                .collect { chosen ->
                    val earbudChanged = chosen.earbudMicrophone != chosenOptions.value.earbudMicrophone
                    chosenOptions.value = chosen
                    send(Event.Options(chosen.onWakeUp, chosen.pauseAfterSilence, chosen.callCommands))
                    // Section 8.8: the microphone asks for the earbuds, or gives them up, only when it starts, so a change
                    // restarts a recording. Only one of the two recordings asks for the earbuds, so no late report of
                    // Android's about the first one can withdraw the second one's request.
                    val current = session
                    if (earbudChanged && current != null && microphoneOn) {
                        microphoneOn = false
                        releaseMicrophone(current)
                        followSafely(state.value)
                    }
                }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
            microphones = { MicCapture(appContext) },
```

with

```kotlin
            // The owner's choice as Listening read it last, on the controller's thread, where the microphone starts.
            microphones = { MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }) },
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

with

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <!-- Granted at install. Kestrel asks for the earbuds as the device of calls' audio when the owner allows the earbud
         microphone (design spec, section 8.8; research r12). -->
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

- [ ] **Step 9: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 112 tests in `ListeningTest` (111 and 1).

- [ ] **Step 10: Write the device test without earbuds, which only an emulator runs**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/MicCaptureTest.kt`, replace

```kotlin
    @Test
    fun theMicrophoneStartsAgainAfterAStop() {
```

with

```kotlin
    @Test
    fun withTheEarbudMicrophoneAllowedAndNoEarbudsThePhoneRecordsAndNoRequestStays() {
        // On a phone with earbuds connected, the test would switch them to their call profile.
        VoiceTests.assumeEmulator()
        val frames = AtomicInteger()
        val errors = CopyOnWriteArrayList<Exception>()
        // The emulator has no Bluetooth, so Android offers no headset, and the communication device must stay as it was.
        val before = audio.communicationDevice?.id

        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context, earbud = { true })
            capture.start(onFrame = { frames.incrementAndGet() }, onError = { errors += it })
            try {
                Thread.sleep(1_000)
                assertEquals(before, audio.communicationDevice?.id)
            } finally {
                capture.stop()
            }
        }

        assertEquals(emptyList<Exception>(), errors)
        assertTrue("${frames.get()} frames in 1 s", frames.get() in 30..70)
        assertEquals(before, audio.communicationDevice?.id)
    }

    @Test
    fun theMicrophoneStartsAgainAfterAStop() {
```

- [ ] **Step 11: Leave the earbud test out on phones**

In `scripts/voice-test.ps1`, replace

```powershell
# Each command at the end runs only if the serial still names the device that the script accepted at the start. If a
```

with

```powershell
# The test MicCaptureTest#withTheEarbudMicrophoneAllowedAndNoEarbudsThePhoneRecordsAndNoRequestStays asks Android for
# connected earbuds, which would switch a phone's earbuds to their call profile, so on a phone the script leaves it out
# as well, and the run fails when it ran there; it changes nothing that the script must put back.
# Each command at the end runs only if the serial still names the device that the script accepted at the start. If a
```

and replace

```powershell
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
```

with

```powershell
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
            # This one asks Android for connected earbuds.
            'dev.kestrel.app.voice.MicCaptureTest#withTheEarbudMicrophoneAllowedAndNoEarbudsThePhoneRecordsAndNoRequestStays'
```

In `README.md`, replace

```markdown
`connected-test.ps1` runs these tests too.
```

with

```markdown
`MicCaptureTest`'s test of the earbud microphone asks Android for connected earbuds, which would switch a phone's earbuds to their call profile, so it also skips itself on a phone, and `voice-test.ps1` leaves it out there. `connected-test.ps1` runs these tests too.
```

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 101, Failed: 0" in Windows PowerShell 5.1 and in PowerShell 7, as before the task, "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 12: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.8, replace

```markdown
- The phone's own microphone stays the input by default. Using the earbud microphone switches Bluetooth to its low-quality call profile, which makes music sound worse. A setting allows the earbud microphone for owners who prefer it.
```

with

```markdown
- The phone's own microphone stays the input by default. Using the earbud microphone switches Bluetooth to its low-quality call profile, which makes music sound worse. The setting "Use the earbud microphone" (off by default, on the page "Listening and activation") allows it for owners who prefer it. While it is on and Bluetooth earbuds are connected, each start of the microphone asks Android for the earbuds as the communication device (`AudioManager.setCommunicationDevice`), an LE Audio headset before a classic one, before the recording exists. For an LE Audio headset, the recording also names the headset's microphone as its preferred device, because Android gives that microphone to the source `VOICE_RECOGNITION` only then (research r12). Kestrel withdraws the request when the microphone stops, and also when Android reports another communication device, because the earbuds went away or a call took the audio over; the phone's microphone then records until the microphone starts again. Kestrel never sets Android's audio mode, so a call keeps its own route. A change of the setting counts at once: Kestrel restarts a running recording, and while the microphone is off, the change counts from its next start. When Android lists no microphone for an LE Audio headset, Kestrel withdraws the request and records from the phone. While the request holds, other apps that record from the microphone also hear the earbuds. The emulator has no Bluetooth, so the A71 session checks the earbud microphone with the owner's Galaxy Buds.
```

In section 13.6, replace

```markdown
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `INTERNET`, `ACCESS_NETWORK_STATE`, `SET_ALARM` | Normal | Granted at install |
```

with

```markdown
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `MODIFY_AUDIO_SETTINGS` (for the earbud microphone, section 8.8), `INTERNET`, `ACCESS_NETWORK_STATE`, `SET_ALARM` | Normal | Granted at install |
```

In `README.md`, replace

```markdown
Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock.
```

with

```markdown
Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock. With "Use the earbud microphone" on the page "Listening and activation", Kestrel records from connected Bluetooth earbuds instead of the phone's microphone. This switches classic Bluetooth earbuds to their call profile, which makes music sound worse while Kestrel records; when the earbuds go away, the phone's microphone records again.
```

If Task 5 or an earlier task changed that sentence of the README, put the new sentences right after the sentence that says that Kestrel stops recording while the screen is off.

- [ ] **Step 13: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (237 in `:app`: 236 and the new `ListeningTest` test; 131 in `:core:voice`: 122 and the 9 of `HeadsetRouteTest`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 14: Run the voice device tests**

`voice-test.ps1` runs the package `dev.kestrel.app.voice`, which holds `MicCaptureTest`; every voice session of the run also starts the changed `MicCapture` through `AppGraph`, with the setting off.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 71 run, 0 failed, 0 errors, 0 skipped." (70 and `MicCaptureTest.withTheEarbudMicrophoneAllowedAndNoEarbudsThePhoneRecordsAndNoRequestStays`), and no warning from the restores at the end.

- [ ] **Step 15: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 16: Commit**

```powershell
git add core/voice/src/main/kotlin/dev/kestrel/voice/listen/HeadsetRoute.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/AndroidCommunicationDevices.kt core/voice/src/main/kotlin/dev/kestrel/voice/listen/MicCapture.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/HeadsetRouteTest.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/voice/MicCaptureTest.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt scripts/voice-test.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(voice): record from connected earbuds when the owner allows the earbud microphone" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The voice and speech settings, and Kokoro

**Files:**
- Modify: `models/manifest.json` (the optional model `tts-kokoro`)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/Synthesizer.kt` (`VoiceOptions.voice`, new `VoiceChoice`)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt` (`Model.optional`)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt` (`ModelStatus.absent`)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt` (`synthesizer`, `load`, `requireReady`, the Kokoro paths)
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt` (`SherpaKokoroSynthesizer`, `kokoroCanSay`)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/speak/EchoFilter.kt` (two comments on digits)
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt` (`VoiceOptionsSetting`, `loadChosenVoice`)
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `SettingKeys.VOICE`, the session's voice, with the default when the setting cannot be read)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt` (two sentences of `open`'s KDoc)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt` (`voiceOptions`, `setVoiceOptions`, `tryVoice`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt` (`SettingsPage.VOICE`, the check of the models, the home page, the page)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `ChecklistPage.kt`, `AboutPage.kt` (an absent optional model)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `scripts/push-models.ps1` (the whole file: `-Kokoro`), `scripts/lib/Models.ps1` (new `Select-ModelManifest`), `scripts/voice-test.ps1` (the header, the push with `-Kokoro`)
- Modify: `THIRD_PARTY_NOTICES.md`, `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.2, 8.10 and 15), `README.md` (setup step 2, the settings pages)
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceEnginesTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt` (JVM, changed), `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KokoroTextTest.kt` (JVM, new), `app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt` (JVM, new), `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt` (JVM, changed), `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` (device, changed), `app/src/androidTest/kotlin/dev/kestrel/app/voice/SynthesizerTest.kt` (voice device test, changed), `app/src/androidTest/kotlin/dev/kestrel/app/voice/EchoTimingTest.kt` (voice device test, new), `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt` (`assumeModels` and optional models), `scripts/tests/Models.Tests.ps1` (Pester, changed). `voice-test.ps1` runs the tests of the package `dev.kestrel.app.voice`; `connected-test.ps1` runs `SettingsAppTest`

**Interfaces:**
- Consumes: `SettingsPage.LISTENING` from Task 5 (the new page comes after it); `SettingsData`, `StoredSettingsData`, `ModelCheck`, `PageHeader`, `PageEntry`, `ScrollingPage`; `VoiceEngines.load`; `VoiceSession.open(..., loadSynthesizer = ...)`; `VoicePlayer(context, synthesizer)`, `speak(text): Deferred<Spoke>` and `close()` as Task 1 leaves them; `ClipMicrophone.speech(voice, text)`; `VoiceTests.frames`, `VoiceTests.log`, `VoiceTests.assumeModels`.
- Produces: `@Serializable enum class VoiceChoice { PIPER, KOKORO }` in `dev.kestrel.voice`; `VoiceOptions(speed: Float = 1f, variation: Float = 1f, voice: VoiceChoice = VoiceChoice.PIPER)`; `Model.optional: Boolean = false`; `ModelStatus.absent: Boolean`; `VoiceEngines.load(engine: Engine, ids: Set<String>? = null, create: () -> T)` and the constants `PIPER_MODELS`, `KOKORO_MODELS`, `KOKORO_MODEL`, `KOKORO_VOICES`, `KOKORO_TOKENS`; `@Serializable data class VoiceOptionsSetting(val voice: VoiceChoice = VoiceChoice.PIPER, val speed: Float = 1.0f)` in `dev.kestrel.app.listen` with `options()`, `slower()`, `faster()`, `SLOWEST = 0.8f`, `FASTEST = 1.4f`; `SettingKeys.VOICE`; `internal suspend fun loadChosenVoice(chosen: VoiceOptionsSetting, report: (String, Throwable) -> Unit = { _, _ -> }, load: suspend (VoiceOptions) -> Synthesizer): Synthesizer`; `VoiceTests.assumeModels(vararg engines: Engine, optional: Set<String> = emptySet())`; `SettingsData.voiceOptions: Flow<VoiceOptionsSetting>`, `setVoiceOptions(options)`, `tryVoice(options)`; `SettingsPage.VOICE`; `push-models.ps1 -Kokoro`; `function Select-ModelManifest($Manifest, [switch]$IncludeOptional)`. Tasks 8 and 9 use `VoiceChoice`, `VoiceOptions.voice` and `push-models.ps1 -Kokoro`.

Design D6 and research r11. The page "Voice and speech" offers the voice, Piper's `en_US-ljspeech-medium` (the default) or Kokoro's `af_heart`, and the speed from 0.8 to 1.4 in steps of 0.1, with "Slower" and "Faster" buttons, which Voice Access and TalkBack can name, instead of a slider. "Try it" speaks a fixed sentence with the choice on the page through a player of its own. Kestrel's voice session reads the choice when it opens, so a change counts from the next time that Kestrel turns on; when Kokoro is chosen but does not load, because its files are missing or damaged or for any other failure, Piper speaks and Kestrel logs the failure, so that an optional voice never keeps Kestrel from listening. The sample of "Try it" has no keyword and no command, because Kestrel's own listening hears it, and the echo filter knows only the voice session's sentences. Kokoro is `kokoro-multi-lang-v1_0` in fp32 (r11, item 1), whose files `push-models.ps1 -Kokoro` downloads from the sherpa-onnx project's Hugging Face copy at a fixed commit: `model.onnx` (325,560,556 bytes), `voices.bin` (28,200,960 bytes) and `tokens.txt` (687 bytes), with the hashes that Hugging Face lists for them. It uses the espeak-ng data that Kestrel already has: the Hugging Face copy's files `phontab`, `phonindex`, `phondata`, `intonations`, `en_dict`, `lang/gmw/en` and `lang/gmw/en-US` have exactly the sizes of Kestrel's. The voice needs no lexicon: sherpa-onnx 1.13.8 reads English text with espeak-ng whenever a language is set, and this model's metadata sets `en-us` itself (`kokoro-multi-lang-lexicon.cc`, `ConvertNonChineseToTokenIDs`), so `lexicon-us-en.txt`, which design D6 lists, would never be read. The sample rate is 24,000 Hz, and `af_heart` is speaker 3. sherpa-onnx logs every word of a Chinese text that its Kokoro lexicon lacks, also without `debug`, so the Kokoro voice returns no samples for a sentence with a Chinese character (section 13.5); Kestrel's English replies never have one. `ModelFiles` gets an optional model: the manifest marks it, and a status whose files are all missing is `absent`, which the setup checklist and the About page do not count as a problem; a file that exists is checked as before, and the hash cache keeps the 325 MB model from being hashed at every check. The echo filter places a keyword in its sentence's playing time in proportion to the characters, and the player measures each sentence's playing time from its samples, so the filter must hold for each voice's pace and pauses. Design D6 asks for a JVM test of this; the timing comes from each voice's real audio, which a JVM test cannot produce, so `EchoTimingTest` checks it on the device instead: it synthesizes sentences in which Kestrel says a keyword, in both voices, feeds them to the real keyword spotter, and checks that the filter takes every hit for Kestrel's own voice; Piper's case checks the rule that Kestrel already has, and Kokoro's case is the new one. `voice-test.ps1` copies Kokoro too, so that its tests run in every voice run, and `VoiceTests.assumeModels` asks for the optional Kokoro only in the tests that name it.

- [ ] **Step 1: Write the failing JVM tests of `:core:voice`**

In `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt`, replace

```kotlin
    @Test
    fun theHashIsLowercaseHex() {
```

with

```kotlin
    @Test
    fun anOptionalModelWithoutAnyOfItsFilesIsAbsent() = runTest {
        val optional = model("k", "k/a.onnx" to "abc", "k/b.bin" to "de").copy(optional = true)
        val files = modelFiles(ModelManifest(format = 1, models = listOf(optional, model("m", "m/a.onnx" to "abc"))))

        val (kokoro, other) = files.check()

        assertTrue(kokoro.absent)
        assertFalse(kokoro.ready)
        assertFalse(other.absent)
        // Once one of its files is there, it counts as missing files again, so that the checklist names the others.
        write("k/a.onnx", "abc")
        assertFalse(files.check().first().absent)
    }

    @Test
    fun theHashIsLowercaseHex() {
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceEnginesTest.kt`, replace

```kotlin
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelManifest
```

with

```kotlin
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelFile
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelManifest
```

and replace

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
```

with

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
```

Then replace

```kotlin
    @Test
    fun anEngineWhoseLoadWasCancelledIsClosed() = runBlocking {
```

with

```kotlin
    /** A required voice whose file is there, and an optional voice whose file is missing. */
    private fun voices(): VoiceEngines {
        val root = folder.newFolder("voices")
        File(root, "piper/voice.onnx").apply {
            parentFile!!.mkdirs()
            writeText("abc")
        }
        fun file(path: String) = ModelFile(path, 3, ABC, url = "https://example.org/$path")
        val piper = Model("tts-piper-ljspeech", Engine.TTS, "Piper", "MIT", "https://example.org", listOf(file("piper/voice.onnx")))
        val kokoro = Model("tts-kokoro", Engine.TTS, "Kokoro", "Apache-2.0", "https://example.org", listOf(file("kokoro/model.onnx")), optional = true)
        val models = ModelFiles(root, ModelManifest(format = 1, models = listOf(piper, kokoro)), File(folder.root, "voice-hashes.json"))
        return VoiceEngines(models, folder.newFolder("voice-work"))
    }

    @Test
    fun anEngineLoadsWithoutAMissingOptionalModel() = runBlocking {
        var created = false

        voices().load(Engine.TTS) {
            created = true
            AutoCloseable {}
        }.close()

        assertTrue(created)
    }

    @Test
    fun aLoadThatNamesTheOptionalModelNeedsItsFiles() = runBlocking {
        val error = try {
            voices().load(Engine.TTS, setOf("tts-kokoro")) { AutoCloseable {} }
            null
        } catch (e: ModelsNotReadyException) {
            e
        }

        assertNotNull("The load did not fail", error)
        assertEquals(listOf("tts-kokoro"), error!!.statuses.map { it.model.id })
    }

    @Test
    fun anEngineWhoseLoadWasCancelledIsClosed() = runBlocking {
```

and at the end of the class, replace

```kotlin
        assertEquals(setOf("keywords.txt", "parakeet-bpe.vocab678.tmp", "keywords.txt.bak"), work.list()!!.toSet())
    }
}
```

with

```kotlin
        assertEquals(setOf("keywords.txt", "parakeet-bpe.vocab678.tmp", "keywords.txt.bak"), work.list()!!.toSet())
    }

    private companion object {
        // The SHA-256 hash of "abc".
        const val ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`, replace

```kotlin
    @Test
    fun theKeywordSpottersFilesAreInTheManifest() {
```

with

```kotlin
    @Test
    fun theKokoroVoicesFilesAreInTheManifestAndOnlyItIsOptional() {
        for (path in listOf(VoiceEngines.KOKORO_MODEL, VoiceEngines.KOKORO_VOICES, VoiceEngines.KOKORO_TOKENS)) {
            assertEquals(path, Engine.TTS, engineOf(path))
        }
        val ids = manifest.models.map { it.id }.toSet()
        for (id in VoiceEngines.PIPER_MODELS + VoiceEngines.KOKORO_MODELS) assertTrue(id, id in ids)
        assertEquals(listOf("tts-kokoro"), manifest.models.filter { it.optional }.map { it.id })
    }

    @Test
    fun theKeywordSpottersFilesAreInTheManifest() {
```

Create `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KokoroTextTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.models.ModelFilesTest --tests dev.kestrel.voice.VoiceEnginesTest --tests dev.kestrel.voice.VoiceModelPathsTest --tests dev.kestrel.voice.sherpa.KokoroTextTest`
Expected: `BUILD FAILED` in `:core:voice:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `optional`, `absent`, `KOKORO_MODEL`, `KOKORO_VOICES`, `KOKORO_TOKENS`, `PIPER_MODELS`, `KOKORO_MODELS` and `kokoroCanSay`, and an error that `load` takes no argument of the type `Set<String>`.

- [ ] **Step 3: Add the optional model, the choice of voice and the Kokoro voice**

In `core/voice/src/main/kotlin/dev/kestrel/voice/Synthesizer.kt`, replace

```kotlin
package dev.kestrel.voice

```

with

```kotlin
package dev.kestrel.voice

import kotlinx.serialization.Serializable

```

and replace

```kotlin
    /** The sample rate of [synthesize]'s output, in Hz. Piper's voices use 22,050 Hz. */
```

with

```kotlin
    /** The sample rate of [synthesize]'s output, in Hz. Piper's voices use 22,050 Hz, and Kokoro 24,000 Hz. */
```

and replace

```kotlin
/**
 * How Kestrel's voice sounds. A [speed] of 1 is the voice's normal pace, and it must be above 0. [variation] scales the
 * small random variation in the voice's sound: 1 is the voice's normal amount, and 0 makes the voice say a text the same
 * way every time, which the device tests need.
 */
data class VoiceOptions(val speed: Float = 1f, val variation: Float = 1f) {
```

with

```kotlin
/** Kestrel's voices (design spec, section 8.2): Piper's `en_US-ljspeech-medium`, and Kokoro's `af_heart`, an option. */
@Serializable
enum class VoiceChoice { PIPER, KOKORO }

/**
 * How Kestrel's voice sounds. [voice] is the voice. A [speed] of 1 is the voice's normal pace, and it must be above 0.
 * [variation] scales the small random variation in Piper's sound: 1 is the voice's normal amount, and 0 makes the voice
 * say a text the same way every time, which the device tests need. Kokoro has no such variation, so it ignores it.
 */
data class VoiceOptions(val speed: Float = 1f, val variation: Float = 1f, val voice: VoiceChoice = VoiceChoice.PIPER) {
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt`, replace

```kotlin
/** A model that one of the engines needs. An engine works when every model of its [engine] is ready. */
@Serializable
data class Model(
    val id: String,
    val engine: Engine,
    val name: String,
    val licence: String,
    val project: String,
    val files: List<ModelFile>,
)
```

with

```kotlin
/**
 * A model that one of the engines needs. An engine works when every model of its [engine] that it needs is ready.
 * [optional]: Kestrel works without the model, such as the Kokoro voice, which scripts\push-models.ps1 copies only with
 * -Kokoro; an engine needs such a model only when it is asked for by its id.
 */
@Serializable
data class Model(
    val id: String,
    val engine: Engine,
    val name: String,
    val licence: String,
    val project: String,
    val files: List<ModelFile>,
    val optional: Boolean = false,
)
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt`, replace

```kotlin
/** Whether a model's files are on the phone and intact. [missing] and [damaged] hold paths from the manifest. */
data class ModelStatus(val model: Model, val missing: List<String>, val damaged: List<String>) {
    val ready: Boolean get() = missing.isEmpty() && damaged.isEmpty()
}
```

with

```kotlin
/** Whether a model's files are on the phone and intact. [missing] and [damaged] hold paths from the manifest. */
data class ModelStatus(val model: Model, val missing: List<String>, val damaged: List<String>) {
    val ready: Boolean get() = missing.isEmpty() && damaged.isEmpty()

    /**
     * An optional model of which no file is on the phone: the owner did not copy it, which is no problem. Once one of its
     * files is there, the others count as missing again.
     */
    val absent: Boolean get() = model.optional && damaged.isEmpty() && missing.isNotEmpty() && missing.size == model.files.size
}
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, replace

```kotlin
import dev.kestrel.voice.sherpa.KeywordLines
import dev.kestrel.voice.sherpa.SherpaKeywordSpotter
```

with

```kotlin
import dev.kestrel.voice.sherpa.KeywordLines
import dev.kestrel.voice.sherpa.SherpaKeywordSpotter
import dev.kestrel.voice.sherpa.SherpaKokoroSynthesizer
```

and replace

```kotlin
class VoiceEngines(private val models: ModelFiles, private val workFolder: File) {
    suspend fun synthesizer(options: VoiceOptions = VoiceOptions()): Synthesizer = load(Engine.TTS) {
        SherpaSynthesizer(models.file(TTS_MODEL), models.file(TTS_TOKENS), models.file(ESPEAK_DATA), options)
    }
```

with

```kotlin
class VoiceEngines(private val models: ModelFiles, private val workFolder: File) {
    /** The voice of [options]: each voice needs only its own models and espeak-ng's data (section 8.2). */
    suspend fun synthesizer(options: VoiceOptions = VoiceOptions()): Synthesizer = when (options.voice) {
        VoiceChoice.PIPER -> load(Engine.TTS, PIPER_MODELS) {
            SherpaSynthesizer(models.file(TTS_MODEL), models.file(TTS_TOKENS), models.file(ESPEAK_DATA), options)
        }
        VoiceChoice.KOKORO -> load(Engine.TTS, KOKORO_MODELS) {
            SherpaKokoroSynthesizer(
                models.file(KOKORO_MODEL),
                models.file(KOKORO_VOICES),
                models.file(KOKORO_TOKENS),
                models.file(ESPEAK_DATA),
                options,
            )
        }
    }
```

Replace

```kotlin
    /**
     * Checks [engine]'s models and creates the engine on [Dispatchers.IO]. If the caller is cancelled while the engine
     * loads, `withContext` drops the loaded engine, so this closes it before the cancellation goes on.
     */
    internal suspend fun <T : AutoCloseable> load(engine: Engine, create: () -> T): T {
        var created: T? = null
        try {
            return withContext(Dispatchers.IO) {
                requireReady(engine)
```

with

```kotlin
    /**
     * Checks [engine]'s models and creates the engine on [Dispatchers.IO]: the models whose ids are in [ids], or without
     * [ids] every model of the engine that is not optional. If the caller is cancelled while the engine loads,
     * `withContext` drops the loaded engine, so this closes it before the cancellation goes on.
     */
    internal suspend fun <T : AutoCloseable> load(engine: Engine, ids: Set<String>? = null, create: () -> T): T {
        var created: T? = null
        try {
            return withContext(Dispatchers.IO) {
                requireReady(engine, ids)
```

Replace

```kotlin
    private suspend fun requireReady(engine: Engine) {
        val notReady = models.check().filter { it.model.engine == engine && !it.ready }
        if (notReady.isNotEmpty()) throw ModelsNotReadyException(engine, notReady)
    }
```

with

```kotlin
    private suspend fun requireReady(engine: Engine, ids: Set<String>?) {
        val notReady = models.check().filter { status ->
            status.model.engine == engine && !status.ready && (ids?.contains(status.model.id) ?: !status.model.optional)
        }
        if (notReady.isNotEmpty()) throw ModelsNotReadyException(engine, notReady)
    }
```

Replace

```kotlin
        const val ESPEAK_DATA = "espeak-ng-data"
```

with

```kotlin
        const val ESPEAK_DATA = "espeak-ng-data"

        // The ids in models/manifest.json of the models that each voice needs.
        val PIPER_MODELS = setOf("tts-piper-ljspeech", "tts-espeak-ng-data")
        val KOKORO_MODELS = setOf("tts-kokoro", "tts-espeak-ng-data")
        const val KOKORO_MODEL = "tts-kokoro/model.onnx"
        const val KOKORO_VOICES = "tts-kokoro/voices.bin"
        const val KOKORO_TOKENS = "tts-kokoro/tokens.txt"
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import java.io.File

/**
 * Kokoro v1.0's voice `af_heart` through sherpa-onnx (design spec, section 8.2; research r11), which gets the phonemes of
 * English text from espeak-ng's data in [espeakData], as Piper does. A call to [synthesize] after [close] throws
 * [IllegalStateException]; [close] waits for a call that is going on. Close the voice only after the last call has
 * returned. [sampleRate] is stored when the voice loads, so it can be read at any time.
 */
internal class SherpaKokoroSynthesizer(
    model: File,
    voices: File,
    tokens: File,
    espeakData: File,
    private val options: VoiceOptions,
) : Synthesizer {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.path,
                    voices = voices.path,
                    tokens = tokens.path,
                    dataDir = espeakData.path,
                    // With a language, sherpa-onnx 1.13.8 reads all text but Chinese with espeak-ng, so the voice needs no
                    // lexicon file.
                    lang = LANGUAGE,
                ),
                numThreads = THREADS,
            ),
        ),
    )

    override val sampleRate: Int = tts.sampleRate()

    private var closed = false

    override fun synthesize(text: String): FloatArray = synchronized(this) {
        check(!closed) { "The voice is closed" }
        if (!kokoroCanSay(text)) return FloatArray(0)
        tts.generate(text, sid = SPEAKER, speed = options.speed).samples
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            tts.release()
        }
    }

    private companion object {
        const val LANGUAGE = "en-us"

        // af_heart in kokoro-multi-lang-v1_0's voices.bin.
        const val SPEAKER = 3

        // As for Piper; the A71 session measures the voice's speed.
        const val THREADS = 2
    }
}

/**
 * Whether Kokoro may get [text]: not when it has a Chinese character, because sherpa-onnx 1.13.8 logs every word of a
 * Chinese text that Kokoro's lexicon lacks, also without debug output (design spec, section 13.5). Kestrel has no
 * lexicon for Chinese, so the voice could not say such a text anyway.
 */
internal fun kokoroCanSay(text: String): Boolean = text.none { it in '\u4e00'..'\u9fff' }
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/speak/EchoFilter.kt`, replace

```kotlin
// A digit counts as three characters, because Piper speaks digits as words.
```

with

```kotlin
// A digit counts as three characters, because both voices speak digits as words, with espeak-ng's phonemes.
```

and replace

```kotlin
 * point. A digit counts as three characters, because Piper speaks digits as words. A hit is Kestrel's own voice
```

with

```kotlin
 * point. A digit counts as three characters, because both voices speak digits as words. A hit is Kestrel's own voice
```

In `models/manifest.json`, replace

```json
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/lang/gmw/en-US"
        }
      ]
    }
  ]
}
```

with

```json
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/lang/gmw/en-US"
        }
      ]
    },
    {
      "id": "tts-kokoro",
      "engine": "tts",
      "name": "Kokoro v1.0 voice af_heart",
      "licence": "Apache-2.0",
      "project": "https://huggingface.co/hexgrad/Kokoro-82M",
      "optional": true,
      "files": [
        {
          "path": "tts-kokoro/model.onnx",
          "size": 325560556,
          "sha256": "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11",
          "url": "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b/model.onnx"
        },
        {
          "path": "tts-kokoro/voices.bin",
          "size": 28200960,
          "sha256": "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
          "url": "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b/voices.bin"
        },
        {
          "path": "tts-kokoro/tokens.txt",
          "size": 687,
          "sha256": "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
          "url": "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b/tokens.txt"
        }
      ]
    }
  ]
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with 136 tests in `:core:voice` (131 and 5: `ModelFilesTest.anOptionalModelWithoutAnyOfItsFilesIsAbsent`, `VoiceEnginesTest`'s two new tests, `VoiceModelPathsTest.theKokoroVoicesFilesAreInTheManifestAndOnlyItIsOptional` and `KokoroTextTest`). `ModelManifestTest` passes too: the manifest's new field `optional` is known, and every file has an https address, a size and a hash.

- [ ] **Step 5: Write the failing JVM tests of `:app`**

Create `app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class VoiceOptionsSettingTest {
    private class FakeVoice : Synthesizer {
        override val sampleRate = 22_050

        override fun synthesize(text: String) = FloatArray(0)

        override fun close() {}
    }

    private val loaded = mutableListOf<VoiceOptions>()

    @Test
    fun theChosenVoiceLoadsAtTheChosenSpeed() = runTest {
        val voice = FakeVoice()

        val result = loadChosenVoice(VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 1.2f)) { options ->
            loaded += options
            voice
        }

        assertSame(voice, result)
        assertEquals(listOf(VoiceOptions(speed = 1.2f, voice = VoiceChoice.KOKORO)), loaded)
    }

    @Test
    fun aMissingKokoroVoiceGivesWayToPiper() = runTest {
        val piper = FakeVoice()

        val result = loadChosenVoice(VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 0.9f)) { options ->
            loaded += options
            if (options.voice == VoiceChoice.KOKORO) throw ModelsNotReadyException(Engine.TTS, emptyList())
            piper
        }

        assertSame(piper, result)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(0.9f, loaded.last().speed)
    }

    @Test
    fun aMissingPiperVoiceStillFails() = runTest {
        try {
            loadChosenVoice(VoiceOptionsSetting()) { options ->
                loaded += options
                throw ModelsNotReadyException(Engine.TTS, emptyList())
            }
            fail("The load did not fail")
        } catch (e: ModelsNotReadyException) {
            assertEquals(1, loaded.size)
        }
    }

    @Test
    fun aKokoroVoiceThatFailsToLoadGivesWayToPiperAndIsReported() = runTest {
        val piper = FakeVoice()
        val reports = mutableListOf<String>()

        val chosen = VoiceOptionsSetting(VoiceChoice.KOKORO)
        val result = loadChosenVoice(chosen, report = { what, _ -> reports += what }) { options ->
            loaded += options
            if (options.voice == VoiceChoice.KOKORO) throw IllegalStateException("The voice could not be created")
            piper
        }

        assertSame(piper, result)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(listOf("Loading the Kokoro voice"), reports)
    }

    @Test
    fun theSpeedStaysWithinItsLimitsInStepsOfATenth() {
        assertEquals(0.8f, VoiceOptionsSetting(speed = 0.3f).options().speed)
        assertEquals(1.4f, VoiceOptionsSetting(speed = 9f).options().speed)
        assertEquals(0.8f, VoiceOptionsSetting(speed = 0.8f).slower().speed)
        assertEquals(1.4f, VoiceOptionsSetting(speed = 1.4f).faster().speed)
        assertEquals(1.1f, VoiceOptionsSetting().faster().speed)
        var setting = VoiceOptionsSetting(speed = 0.8f)
        repeat(6) { setting = setting.faster() }
        assertEquals(1.4f, setting.speed)
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, add at the end of the class, before its last closing brace, this test:

```kotlin

    @Test
    fun anOptionalModelThatIsNotOnThePhoneLeavesTheModelStepDone() {
        val voice = Model("tts", Engine.TTS, "Voice", "MIT", "https://example.org", listOf(ModelFile("tts/a.onnx", 1, "0".repeat(64))))
        val kokoro = Model(
            "tts-kokoro", Engine.TTS, "Kokoro", "Apache-2.0", "https://example.org",
            listOf(ModelFile("tts-kokoro/model.onnx", 1, "0".repeat(64))), optional = true,
        )
        val absent = ModelStatus(kokoro, missing = listOf("tts-kokoro/model.onnx"), damaged = emptyList())
        val status = SetupStatus(
            serviceOn = false,
            microphoneAllowed = false,
            notificationsAllowed = false,
            batteryUnrestricted = false,
            voiceAccessOn = false,
            models = ModelCheck.Done(listOf(ModelStatus(voice, emptyList(), emptyList()), absent)),
        )

        assertEquals(StepState.DONE, SetupChecklist.states(status).getValue(SetupStep.COPY_MODELS))
    }
```

At the top of the same file, make sure that these imports are there, and add those that are missing: `dev.kestrel.voice.models.Engine`, `dev.kestrel.voice.models.Model`, `dev.kestrel.voice.models.ModelFile`, `dev.kestrel.voice.models.ModelStatus` and `org.junit.Assert.assertEquals`.

- [ ] **Step 6: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.VoiceOptionsSettingTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `loadChosenVoice` and `VoiceOptionsSetting`.

- [ ] **Step 7: Store the choice, and load the chosen voice**

Create `app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * The owner's choice of voice and speed (design spec, sections 8.2 and 15), stored encrypted like every setting. The
 * speed lies between [SLOWEST] and [FASTEST] in steps of a tenth; a stored value outside them counts as the nearest one.
 */
@Serializable
data class VoiceOptionsSetting(val voice: VoiceChoice = VoiceChoice.PIPER, val speed: Float = 1.0f) {
    /** The options for Kestrel's voice engines. */
    fun options(): VoiceOptions = VoiceOptions(speed = speed.coerceIn(SLOWEST, FASTEST), voice = voice)

    /** A tenth slower, but not below [SLOWEST]. */
    fun slower(): VoiceOptionsSetting = copy(speed = stepped(-1))

    /** A tenth faster, but not above [FASTEST]. */
    fun faster(): VoiceOptionsSetting = copy(speed = stepped(1))

    // In whole tenths, so that steps add up exactly.
    private fun stepped(tenths: Int): Float =
        ((options().speed * 10).roundToInt() + tenths).coerceIn((SLOWEST * 10).roundToInt(), (FASTEST * 10).roundToInt()) / 10f

    companion object {
        const val SLOWEST = 0.8f
        const val FASTEST = 1.4f
    }
}

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
): Synthesizer {
    val options = chosen.options()
    if (options.voice == VoiceChoice.PIPER) return load(options)
    return try {
        load(options)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Logged by its class and stack frames only (section 13.5).
        report("Loading the Kokoro voice", e)
        load(options.copy(voice = VoiceChoice.PIPER))
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.listen.VoiceSessions
```

with

```kotlin
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.listen.VoiceSessions
```

Replace

```kotlin
import dev.kestrel.app.listen.openWithBoostWords
```

with

```kotlin
import dev.kestrel.app.listen.loadChosenVoice
import dev.kestrel.app.listen.openWithBoostWords
```

Replace

```kotlin
import kotlinx.coroutines.CoroutineExceptionHandler
```

with

```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
```

Replace

```kotlin
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
```

with

```kotlin
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())

    /** The owner's voice and speed (design spec, sections 8.2 and 15). */
    val VOICE = SettingKey("voice", VoiceOptionsSetting.serializer(), VoiceOptionsSetting())
```

Replace

```kotlin
                        VoiceSession.open(appContext, voice, microphone, listener, boostWords)
```

with

```kotlin
                        VoiceSession.open(
                            appContext,
                            voice,
                            microphone,
                            listener,
                            boostWords,
                            // The owner's voice and speed, read at each start, so that a change counts from the next one.
                            // A setting that cannot be read counts as the default, Piper, as the listening options do.
                            loadSynthesizer = {
                                val chosen = try {
                                    settings.get(SettingKeys.VOICE)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    reportFailure("Reading the voice setting", e)
                                    VoiceOptionsSetting()
                                }
                                loadChosenVoice(chosen, reportFailure) { voice.synthesizer(it) }
                            },
                        )
```

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, replace

```kotlin
         * Loads the engines and opens a session (section 8.2): the speech detector, the keyword spotter, the voice with
         * its default options, and the recogniser last, because it holds about 700 MB: a missing model of another
```

with

```kotlin
         * Loads the engines and opens a session (section 8.2): the speech detector, the keyword spotter, the voice that
         * [loadSynthesizer] loads (AppGraph's loads the owner's choice), and the recogniser last, because it holds about
         * 700 MB: a missing model of another
```

and replace

```kotlin
         * Kestrel turns on. The loaders are parameters only for the tests.
```

with

```kotlin
         * Kestrel turns on. The other loaders are parameters only for the tests.
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace

```kotlin
            SetupStep.COPY_MODELS -> doneIf((status.models as? ModelCheck.Done)?.statuses?.all { it.ready } == true)
```

with

```kotlin
            // An optional model that the owner did not copy, such as the Kokoro voice, is no problem.
            SetupStep.COPY_MODELS -> doneIf((status.models as? ModelCheck.Done)?.statuses?.all { it.ready || it.absent } == true)
```

- [ ] **Step 8: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.VoiceOptionsSettingTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD SUCCESSFUL`, with 5 tests in `VoiceOptionsSettingTest` and 12 in `SetupChecklistTest` (11 and 1).

- [ ] **Step 9: Add the page "Voice and speech"**

Create `app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.voice.VoiceChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** The id of the Kokoro voice's model in models/manifest.json. */
private const val KOKORO_MODEL = "tts-kokoro"

/**
 * The page "Voice and speech" (design spec, sections 8.2 and 15): the voice, Piper or Kokoro, the speed, and "Try it",
 * which speaks a fixed sentence with the choice on the page. Kokoro can be chosen only while its files are on the phone
 * and intact, as [models] says. A new choice counts from the next time that Kestrel turns on. The sample has no
 * keyword and no command, because Kestrel's own listening hears it, and its echo filter knows only the voice session's
 * sentences.
 */
@Composable
internal fun VoicePage(data: SettingsData, models: ModelCheck, onBack: () -> Unit) {
    val stored by data.voiceOptions.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val writes = remember { Mutex() }
    // The options as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<VoiceOptionsSetting?>(null) }
    var trying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Null while the check runs or after it failed; then the page says nothing about Kokoro's files.
    val kokoro = (models as? ModelCheck.Done)?.statuses?.firstOrNull { it.model.id == KOKORO_MODEL }
    val kokoroReady = kokoro?.ready == true
    PageHeader(stringResource(R.string.settings_voice), onBack)
    val current = written ?: stored ?: return
    val speed = current.options().speed

    // Stores the options in the order of the changes, as the page "Listening and activation" does.
    fun change(options: VoiceOptionsSetting) {
        written = options
        scope.launch { writes.withLock { data.setVoiceOptions(options) } }
    }

    Text(stringResource(R.string.voice_choice), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    VoiceRow(stringResource(R.string.voice_piper), current.voice == VoiceChoice.PIPER, enabled = true) {
        change(current.copy(voice = VoiceChoice.PIPER))
    }
    VoiceRow(stringResource(R.string.voice_kokoro), current.voice == VoiceChoice.KOKORO, enabled = kokoroReady) {
        change(current.copy(voice = VoiceChoice.KOKORO))
    }
    when {
        kokoro == null || kokoro.ready -> Unit
        kokoro.absent -> Text(stringResource(R.string.voice_kokoro_missing), style = MaterialTheme.typography.bodySmall)
        else -> Text(stringResource(R.string.voice_kokoro_damaged), style = MaterialTheme.typography.bodySmall)
    }
    Text(
        stringResource(R.string.voice_speed, speedText(speed)),
        Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
    )
    Row {
        Button(onClick = { change(current.slower()) }, enabled = speed > VoiceOptionsSetting.SLOWEST) {
            Text(stringResource(R.string.voice_slower))
        }
        Button(onClick = { change(current.faster()) }, Modifier.padding(start = 8.dp), enabled = speed < VoiceOptionsSetting.FASTEST) {
            Text(stringResource(R.string.voice_faster))
        }
    }
    Button(
        onClick = {
            trying = true
            failed = false
            scope.launch {
                try {
                    data.tryVoice(current)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                } finally {
                    trying = false
                }
            }
        },
        modifier = Modifier.padding(top = 16.dp),
        enabled = !trying,
    ) {
        Text(stringResource(if (trying) R.string.voice_trying else R.string.voice_try))
    }
    if (failed) Text(stringResource(R.string.voice_try_failed), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.voice_next_start), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
}

/** A speed as the pages show it, such as "1.2". */
internal fun speedText(speed: Float): String = String.format(Locale.ROOT, "%.1f", speed)

@Composable
private fun VoiceRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    // The whole row is one radio button, so that its label names it for Voice Access and TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.ListeningOptions
```

with

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.app.listen.VoiceOptionsSetting
```

Replace

```kotlin
import dev.kestrel.privacy.logs.NetworkEvent
import kotlinx.coroutines.Dispatchers
```

with

```kotlin
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
```

Replace

```kotlin
    suspend fun setTestMode(on: Boolean)
```

with

```kotlin
    suspend fun setTestMode(on: Boolean)

    /** The owner's voice and speed (design spec, section 15). */
    val voiceOptions: Flow<VoiceOptionsSetting>

    suspend fun setVoiceOptions(options: VoiceOptionsSetting)

    /**
     * Speaks a fixed sentence with [options], and returns when it has been spoken. Throws when the voice fails or Android
     * refuses Kestrel the audio, for example during a call.
     */
    suspend fun tryVoice(options: VoiceOptionsSetting)
```

Replace

```kotlin
    override suspend fun setTestMode(on: Boolean) {
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
    }
```

with

```kotlin
    override suspend fun setTestMode(on: Boolean) {
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
    }

    override val voiceOptions: Flow<VoiceOptionsSetting> = graph.settings.observe(SettingKeys.VOICE)

    override suspend fun setVoiceOptions(options: VoiceOptionsSetting) {
        graph.settings.set(SettingKeys.VOICE, options)
    }

    // A voice and a player of its own, beside a voice session that may be open; both are closed afterwards. The voice on
    // the page itself, without the session's fallback to Piper, so that a Kokoro that does not load shows as a failure.
    override suspend fun tryVoice(options: VoiceOptionsSetting) = withContext(Dispatchers.IO) {
        val spoke = graph.voice.synthesizer(options.options()).use { voice ->
            val player = VoicePlayer(context, voice)
            try {
                player.speak(context.getString(R.string.voice_sample)).await()
            } finally {
                // As VoiceSession.close does: a cancelled caller still waits until the player no longer uses the voice.
                withContext(NonCancellable) { player.close() }
            }
        }
        check(spoke != Spoke.REFUSED) { "Android refused the audio" }
    }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, add `VOICE` to the enum `SettingsPage` right after `LISTENING`, which Task 5 added. If Task 5 left the line as

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }
```

replace it with

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, VOICE, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }
```

Replace

```kotlin
        if (licence == null && (page == SettingsPage.CHECKLIST || page == SettingsPage.ABOUT)) onCheckModels()
```

with

```kotlin
        // The voice page offers Kokoro only while its files are ready.
        if (licence == null && (page == SettingsPage.CHECKLIST || page == SettingsPage.ABOUT || page == SettingsPage.VOICE)) {
            onCheckModels()
        }
```

Replace

```kotlin
 * [onCheckModels] checks the model files again, which happens whenever the checklist or the About page opens, and when
 * the About page shows again after a licence text (design spec, 8.10). [onExportLogs] asks the owner for a file to
 * export both logs to.
```

with

```kotlin
 * [onCheckModels] checks the model files again, which happens whenever the checklist, the About page or the voice page
 * opens, and when the About page shows again after a licence text (design spec, 8.10). [onExportLogs] asks the owner
 * for a file to export both logs to.
```

Replace

```kotlin
            SettingsPage.SAFETY -> ScrollingPage { SafetyPage(data, home) }
```

with

```kotlin
            SettingsPage.VOICE -> ScrollingPage { VoicePage(data, status.models, home) }
            SettingsPage.SAFETY -> ScrollingPage { SafetyPage(data, home) }
```

Replace

```kotlin
    val testMode by data.testMode.collectAsState(initial = null)
    val logsSummary = stringResource(R.string.settings_logs_summary)
```

with

```kotlin
    val testMode by data.testMode.collectAsState(initial = null)
    val voice by data.voiceOptions.collectAsState(initial = null)
    val logsSummary = stringResource(R.string.settings_logs_summary)
```

Replace

```kotlin
    PageEntry(stringResource(R.string.settings_safety), testModeSummary(testMode)) { onOpen(SettingsPage.SAFETY) }
```

with

```kotlin
    PageEntry(stringResource(R.string.settings_voice), voiceSummary(voice)) { onOpen(SettingsPage.VOICE) }
    PageEntry(stringResource(R.string.settings_safety), testModeSummary(testMode)) { onOpen(SettingsPage.SAFETY) }
```

Replace

```kotlin
@Composable
private fun testModeSummary(on: Boolean?): String = when (on) {
```

with

```kotlin
@Composable
private fun voiceSummary(options: VoiceOptionsSetting?): String {
    if (options == null) return ""
    val name = stringResource(if (options.voice == VoiceChoice.KOKORO) R.string.voice_kokoro_short else R.string.voice_piper_short)
    return stringResource(R.string.settings_voice_summary, name, speedText(options.options().speed))
}

@Composable
private fun testModeSummary(on: Boolean?): String = when (on) {
```

and replace

```kotlin
import dev.kestrel.app.R
```

with

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.voice.VoiceChoice
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, replace

```kotlin
        is ModelCheck.Done -> for (status in models.statuses) {
            for (path in status.missing) Text(stringResource(R.string.setup_models_missing, path), style = small)
```

with

```kotlin
        // An optional model that the owner did not copy is no problem (section 8.10).
        is ModelCheck.Done -> for (status in models.statuses.filterNot { it.absent }) {
            for (path in status.missing) Text(stringResource(R.string.setup_models_missing, path), style = small)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt`, replace

```kotlin
    val name = "${status.model.name} (${status.model.licence})"
    val lines = mutableListOf<String>()
```

with

```kotlin
    val name = "${status.model.name} (${status.model.licence})"
    if (status.absent) return listOf(stringResource(R.string.about_model_absent, name))
    val lines = mutableListOf<String>()
```

In `app/src/main/res/values/strings.xml`, replace

```xml
</resources>
```

with

```xml
    <string name="settings_voice">Voice and speech</string>
    <string name="settings_voice_summary">%1$s, speed %2$s</string>
    <string name="voice_choice">Voice</string>
    <string name="voice_piper">Piper (en_US-ljspeech-medium)</string>
    <string name="voice_piper_short">Piper</string>
    <string name="voice_kokoro">Kokoro (af_heart)</string>
    <string name="voice_kokoro_short">Kokoro</string>
    <string name="voice_kokoro_missing">Kokoro\'s files are not on the phone. Copy them with scripts\\push-models.ps1 -Kokoro.</string>
    <string name="voice_kokoro_damaged">Some of Kokoro\'s files are missing or damaged. Copy them again with scripts\\push-models.ps1 -Kokoro.</string>
    <string name="voice_speed">Speed: %1$s</string>
    <string name="voice_slower">Slower</string>
    <string name="voice_faster">Faster</string>
    <string name="voice_try">Try it</string>
    <string name="voice_trying">Speaking…</string>
    <string name="voice_try_failed">Kestrel could not speak with this voice.</string>
    <string name="voice_sample">This is how I sound at this speed.</string>
    <string name="voice_next_start">Kestrel uses a new voice or speed from the next time it turns on.</string>
    <string name="about_model_absent">%1$s: optional, not on the phone</string>
</resources>
```

- [ ] **Step 10: Copy Kokoro with push-models.ps1 -Kokoro**

In `scripts/lib/Models.ps1`, replace

```powershell
# Whether a path from the manifest is relative and stays inside its folder: segments of letters, digits, '.', '_' and
```

with

```powershell
# Returns the manifest without its optional models, such as the Kokoro voice, unless -IncludeOptional asks for them
# (design spec, sections 8.2 and 8.10). Kestrel counts an optional model of which no file is on the phone as absent.
function Select-ModelManifest($Manifest, [switch]$IncludeOptional) {
    if ($IncludeOptional) { return $Manifest }
    [pscustomobject]@{
        format = $Manifest.format
        archives = @($Manifest.archives | Where-Object { $null -ne $_ })
        models = @($Manifest.models | Where-Object { -not $_.optional })
    }
}

# Whether a path from the manifest is relative and stays inside its folder: segments of letters, digits, '.', '_' and
```

Replace the whole content of `scripts/push-models.ps1` with:

```powershell
# Copies Kestrel's speech models to a test device (design spec, section 8.10). It downloads the files that
# models\manifest.json lists into a cache on this computer, from the addresses in the manifest: sherpa-onnx's releases on
# GitHub (github.com) and Hugging Face (huggingface.co), which redirect to their own download servers. It checks the
# files' SHA-256 hashes. Then it copies the files that the device lacks, and those whose size or hash differs on the
# device, into Kestrel's folder /sdcard/Android/data/dev.kestrel/files/models. Kestrel must be installed first
# (scripts\install.ps1). Kestrel creates that folder when it starts, so the first run after an install may open Kestrel
# on the device. The first run downloads about 760 MB; later runs download nothing. With -Kokoro, it also copies the
# optional Kokoro voice (design spec, section 8.2), which adds about 354 MB. Uninstalling Kestrel deletes the models from
# the device.
# Example: scripts\push-models.ps1 -Serial emulator-5554 -Kokoro
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Outside the repository, so that git clean keeps it and every clone shares it.
    [string]$Cache = (Join-Path $env:LOCALAPPDATA 'Kestrel\models'),
    # Also the optional Kokoro voice.
    [switch]$Kokoro,
    # Only for the owner's decision to start daily use on the Galaxy S23+.
    [switch]$AllowDailyPhone
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Models.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial -AllowDailyPhone:$AllowDailyPhone
$manifest = Select-ModelManifest -Manifest (Read-ModelManifest -Path (Join-Path $PSScriptRoot '..\models\manifest.json')) -IncludeOptional:$Kokoro
# Before the first download, which takes a while, so that a missing Kestrel shows at once.
Assert-KestrelInstalled -Serial $Serial
Save-ModelFiles -Manifest $manifest -Cache $Cache
Push-ModelFiles -Serial $Serial -Manifest $manifest -Cache $Cache
Write-Host "The speech models are on $Serial."
```

In `scripts/voice-test.ps1`, replace

```powershell
# section 16.4). They need the speech models, so the script installs Kestrel, copies the models with
# scripts\push-models.ps1, and keeps Kestrel and its test APK installed afterwards, because uninstalling Kestrel deletes
# the models. The tests skip themselves when the models are missing, so a run with a skipped test fails. Afterwards the
# script prints the timings and memory use that the tests logged. After Ctrl+C, it ends with exit code 1.
```

with

```powershell
# section 16.4). They need the speech models, so the script installs Kestrel, copies the models with
# scripts\push-models.ps1 -Kokoro, also the optional Kokoro voice, whose tests run too, and keeps Kestrel and its test APK
# installed afterwards, because uninstalling Kestrel deletes the models. The tests skip themselves when the models are
# missing, so a run with a skipped test fails. Afterwards the script prints the timings and memory use that the tests
# logged. After Ctrl+C, it ends with exit code 1.
```

and replace

```powershell
        & "$PSScriptRoot\push-models.ps1" -Serial $Serial
```

with

```powershell
        & "$PSScriptRoot\push-models.ps1" -Serial $Serial -Kokoro
```

In `scripts/tests/Models.Tests.ps1`, add at the end of the file:

```powershell

Describe 'Select-ModelManifest' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        $manifest = [pscustomobject]@{
            format = 1
            archives = @([pscustomobject]@{ id = 'pack' })
            models = @(
                [pscustomobject]@{ id = 'voice'; files = @() }
                [pscustomobject]@{ id = 'kokoro'; optional = $true; files = @() }
            )
        }
    }

    It 'leaves out the optional models' {
        $selected = Select-ModelManifest -Manifest $manifest

        @($selected.models | ForEach-Object { $_.id }) | Should -Be @('voice')
        @($selected.archives).Count | Should -Be 1
    }

    It 'keeps them with -IncludeOptional' {
        $selected = Select-ModelManifest -Manifest $manifest -IncludeOptional

        @($selected.models | ForEach-Object { $_.id }) | Should -Be @('voice', 'kokoro')
    }
}
```

- [ ] **Step 11: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 103, Failed: 0" in Windows PowerShell 5.1 and in PowerShell 7 (101 and the two new tests), "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 12: Write the Compose tests of the page**

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace

```kotlin
import androidx.compose.ui.test.assertIsDisplayed
```

with

```kotlin
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
```

Task 5 already imports `assertIsSelected`.

Replace

```kotlin
import dev.kestrel.app.R
```

with

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.voice.VoiceChoice
```

Replace

```kotlin
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelStatus
```

with

```kotlin
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelFile
import dev.kestrel.voice.models.ModelStatus
```

Replace

```kotlin
    private class FakeSettingsData : SettingsData {
```

with

```kotlin
    private fun openVoicePage() {
        compose.onNodeWithText(text(R.string.settings_voice)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.voice_choice)).assertIsDisplayed()
    }

    @Test
    fun kokoroCanBeChosenWhenItsFilesAreReady() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, READY_KOKORO))))
        openVoicePage()
        // The page checks the models when it opens, so that it offers Kokoro only while its files are ready.
        compose.waitForIdle()
        assertEquals(1, checks)

        compose.onNodeWithText(text(R.string.voice_piper)).assertIsSelected()
        compose.onNodeWithText(text(R.string.voice_kokoro)).performClick()

        compose.onNodeWithText(text(R.string.voice_kokoro)).assertIsSelected()
        assertEquals(VoiceChoice.KOKORO, data.voiceOptions.value.voice)
        compose.onAllNodesWithText(text(R.string.voice_kokoro_missing)).assertCountEquals(0)
    }

    @Test
    fun kokoroCannotBeChosenWithoutItsFiles() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, ABSENT_KOKORO))))
        openVoicePage()

        compose.onNodeWithText(text(R.string.voice_kokoro)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.voice_kokoro_missing)).assertIsDisplayed()
        assertEquals(VoiceChoice.PIPER, data.voiceOptions.value.voice)
    }

    @Test
    fun theSpeedChangesInStepsAndStopsAtItsLimit() {
        show(NOTHING_SET_UP)
        openVoicePage()

        repeat(4) { compose.onNodeWithText(text(R.string.voice_faster)).performClick() }

        compose.onNodeWithText(text(R.string.voice_speed, "1.4")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_faster)).assertIsNotEnabled()
        assertEquals(1.4f, data.voiceOptions.value.speed)
        compose.onNodeWithText(text(R.string.voice_slower)).performClick()
        compose.onNodeWithText(text(R.string.voice_speed, "1.3")).assertIsDisplayed()
    }

    @Test
    fun tryItSpeaksWithTheChoiceOnThePage() {
        data.voiceOptions.value = VoiceOptionsSetting(VoiceChoice.PIPER, speed = 1.2f)
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_voice_summary, text(R.string.voice_piper_short), "1.2")).assertIsDisplayed()
        openVoicePage()

        compose.onNodeWithText(text(R.string.voice_try)).performScrollTo().performClick()

        compose.waitUntil(5_000) { data.tried.isNotEmpty() }
        assertEquals(listOf(VoiceOptionsSetting(VoiceChoice.PIPER, speed = 1.2f)), data.tried)
    }

    @Test
    fun theAboutPageSaysThatAnOptionalModelIsNotOnThePhone() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, ABSENT_KOKORO))))
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.about_model_absent, "Kokoro (Apache-2.0)")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theChecklistDoesNotNameTheFilesOfAnOptionalModelThatIsNotOnThePhone() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, ABSENT_KOKORO))))
        openChecklist()

        compose.onAllNodesWithText(text(R.string.setup_models_missing, "tts-kokoro/model.onnx")).assertCountEquals(0)
    }

    private class FakeSettingsData : SettingsData {
```

Replace

```kotlin
        override suspend fun setTestMode(on: Boolean) {
            testMode.value = on
        }
```

with

```kotlin
        override suspend fun setTestMode(on: Boolean) {
            testMode.value = on
        }

        override val voiceOptions = MutableStateFlow(VoiceOptionsSetting())
        val tried = mutableListOf<VoiceOptionsSetting>()

        override suspend fun setVoiceOptions(options: VoiceOptionsSetting) {
            voiceOptions.value = options
        }

        override suspend fun tryVoice(options: VoiceOptionsSetting) {
            tried += options
        }
```

Replace

```kotlin
        val READY_VOICE = ModelStatus(VOICE, emptyList(), emptyList())
```

with

```kotlin
        val READY_VOICE = ModelStatus(VOICE, emptyList(), emptyList())
        private val KOKORO = Model(
            "tts-kokoro", Engine.TTS, "Kokoro", "Apache-2.0", "https://example.org",
            listOf(ModelFile("tts-kokoro/model.onnx", 1, "0".repeat(64))), optional = true,
        )
        val READY_KOKORO = ModelStatus(KOKORO, emptyList(), emptyList())
        val ABSENT_KOKORO = ModelStatus(KOKORO, listOf("tts-kokoro/model.onnx"), emptyList())
```

- [ ] **Step 13: Write the device tests of the Kokoro voice and of the echo filter's timing**

The Kokoro voice is a TTS model, and `VoiceTests.assumeModels` skips a test unless every model of its engines is ready, so without Kokoro every test that names `Engine.TTS` would skip. From now on it asks for an optional model only when the test names it. In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt`, replace

```kotlin
    /** Skips the test unless every model of [engines] is on the device and intact. */
    fun assumeModels(vararg engines: Engine) {
        val notReady = runBlocking { graph.models.check() }.filter { it.model.engine in engines && !it.ready }
        assumeTrue("Models missing or damaged: ${notReady.map { it.model.id }}. Run scripts\\voice-test.ps1.", notReady.isEmpty())
    }
```

with

```kotlin
    /**
     * Skips the test unless every required model of [engines], and every optional model whose id is in [optional], is on
     * the device and intact.
     */
    fun assumeModels(vararg engines: Engine, optional: Set<String> = emptySet()) {
        val notReady = runBlocking { graph.models.check() }.filter { status ->
            !status.ready && (if (status.model.optional) status.model.id in optional else status.model.engine in engines)
        }
        assumeTrue("Models missing or damaged: ${notReady.map { it.model.id }}. Run scripts\\voice-test.ps1.", notReady.isEmpty())
    }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SynthesizerTest.kt`, replace

```kotlin
import dev.kestrel.voice.VoiceOptions
```

with

```kotlin
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
```

and replace

```kotlin
    @Test
    fun withoutVariationTheVoiceSaysATextTheSameWay() = runBlocking {
```

with

```kotlin
    @Test
    fun theKokoroVoiceSpeaksASentence() = runBlocking {
        VoiceTests.assumeModels(Engine.TTS, optional = setOf("tts-kokoro"))
        val started = SystemClock.elapsedRealtime()
        graph.voice.synthesizer(VoiceOptions(voice = VoiceChoice.KOKORO)).use { voice ->
            val loaded = SystemClock.elapsedRealtime()
            val samples = voice.synthesize("Kestrel, open the settings.")
            val took = SystemClock.elapsedRealtime() - loaded
            val seconds = samples.size / voice.sampleRate.toFloat()
            log(
                "kokoro: loaded in ${loaded - started} ms; ${"%.1f".format(seconds)} s of speech in $took ms " +
                    "(real-time factor ${"%.2f".format(took / 1_000f / seconds)}); process PSS ${pssMegabytes()} MB",
            )

            assertEquals(24_000, voice.sampleRate)
            assertTrue("$seconds s", seconds in 1f..4f)
            assertTrue(samples.maxOf { abs(it) } > 0.1f)
            // A broken model gives samples pinned at the ends of the range (research r11, item 8).
            assertTrue(samples.count { abs(it) >= 0.999f } < samples.size / 100)
        }
    }

    @Test
    fun withoutVariationTheVoiceSaysATextTheSameWay() = runBlocking {
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/EchoTimingTest.kt`:

```kotlin
package dev.kestrel.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.voice.VoiceTests.frames
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.Spoken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The echo filter places a keyword in its sentence's playing time in proportion to the characters before it (design
 * spec, section 8.4), and the player takes each sentence's playing time from its samples. This checks that rule against
 * each of Kestrel's voices: it says sentences with keywords in the voice, feeds them to the keyword spotter as the
 * microphone would hear them, and checks that the filter takes every hit for Kestrel's own voice.
 */
@RunWith(AndroidJUnit4::class)
class EchoTimingTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.KWS, Engine.TTS)

    // Piper's case checks the rule that Kestrel has used since plan 2B; Kokoro's case is new.
    @Test
    fun theFilterKnowsPipersKeywords() = check(VoiceChoice.PIPER)

    @Test
    fun theFilterKnowsKokorosKeywords() = check(VoiceChoice.KOKORO)

    private fun check(choice: VoiceChoice) = runBlocking {
        if (choice == VoiceChoice.KOKORO) VoiceTests.assumeModels(Engine.KWS, Engine.TTS, optional = setOf("tts-kokoro"))
        val filter = EchoFilter()
        var hits = 0
        // The number of hits in each sentence, so that the log shows which keywords the spotter heard where (counts only).
        val perSentence = IntArray(SENTENCES.size)
        graph.voice.synthesizer(VoiceOptions(variation = 0f, voice = choice)).use { voice ->
            graph.voice.keywordSpotter().use { spotter ->
                // The spotter counts its hits' times from the start of all the audio that it got.
                var fedMillis = 0L
                for (text in SENTENCES) {
                    // 0.5 s of silence, the sentence at 16 kHz, and 1 s of silence.
                    val audio = ClipMicrophone.speech(voice, text)
                    val playing = (audio.size - SAMPLE_RATE * 3 / 2) * 1_000L / SAMPLE_RATE
                    // As the player computes it: the sentence lasts as long as its samples.
                    val spoken = Spoken(text, fedMillis + 500, fedMillis + 500 + playing)
                    for (hit in frames(audio, spotter::accept)) {
                        hits++
                        perSentence[SENTENCES.indexOf(text)]++
                        val hitMillis = (hit.seconds * 1_000).toLong()
                        assertTrue(
                            "$choice: a hit of ${hit.keyword} at $hitMillis ms in sentence ${SENTENCES.indexOf(text) + 1} " +
                                "is not Kestrel's own voice",
                            filter.isOwnVoice(hit.keyword, hitMillis, listOf(spoken)),
                        )
                    }
                    fedMillis += audio.size * 1_000L / SAMPLE_RATE
                }
            }
        }
        val counts = perSentence.joinToString()
        log("echo timing: $choice gave $hits keyword hits, all of them Kestrel's own voice; per sentence $counts")
        assertTrue("The spotter heard no keyword in $choice's voice", hits > 0)
    }

    private companion object {
        // Keywords at the start, in the middle and at the end, inside words, across words and after digits.
        val SENTENCES = listOf(
            "Should I stop the timer?",
            "It's top-rated.",
            "That is unstoppable.",
            "I cancelled the alarm at 06:45.",
            "Kestrel is listening, and the code is 4 7 1 9.",
            "Your appointment is at 10:30 on 12 March, so I will stop the reminders now.",
        )
    }
}
```

- [ ] **Step 14: Update the notices, the spec and the README**

In `THIRD_PARTY_NOTICES.md`, replace

```markdown
| espeak-ng's data for English, from the voice's sherpa-onnx package | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |
```

with

```markdown
| espeak-ng's data for English, from the voice's sherpa-onnx package | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |
| Kokoro v1.0 (Kokoro-82M) with the voice `af_heart`, an optional voice, in sherpa-onnx's package `kokoro-multi-lang-v1_0` | Apache-2.0 | https://huggingface.co/hexgrad/Kokoro-82M |
```

and add at the end of the file:

```markdown

The Kokoro voice is optional: `scripts\push-models.ps1` copies it only with `-Kokoro`, from the sherpa-onnx project's copy of the package (https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0). hexgrad created Kokoro-82M and licenses its weights and all its English voices under the Apache License 2.0. Kestrel uses only the voice `af_heart`. taylorchu's kokoro-onnx project converted the model to ONNX (https://github.com/taylorchu/kokoro-onnx/releases/tag/v0.2.0), and the sherpa-onnx project packaged it. The package's `voices.bin` also holds other voices; among them, the French voice `ff_siwis` and the Japanese voices were trained with data under Creative Commons Attribution licences (SIWIS, Koniwa), and Kestrel does not use them. Kokoro's model card says that it was trained only on permissive or non-copyrighted audio, including synthetic audio from closed speech models, and it does not name the data of each voice. Kestrel copies neither the package's lexicons nor its own copy of espeak-ng's data: the voice gets its phonemes from the espeak-ng data above.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.2, replace

```markdown
| Voice output | Piper's `en_US-ljspeech-medium` voice, trained on the public-domain LJ Speech data, with Kokoro (Apache-2.0) as an option | about 64 MB | Speaks. Both voices get their phonemes from espeak-ng (GPL-3.0), which needs only the English part of its data. |
```

with

```markdown
| Voice output | Piper's `en_US-ljspeech-medium` voice, trained on the public-domain LJ Speech data, with Kokoro v1.0's voice `af_heart` (Apache-2.0) as an option | about 64 MB; Kokoro about 354 MB more | Speaks. Both voices get their phonemes from espeak-ng (GPL-3.0), which needs only the English part of its data. |
```

and replace

```markdown
- The keyword spotter's smaller "-mobile" package makes sherpa-onnx 1.13.8 end Kestrel's process, so Kestrel uses the regular package (B37).
```

with

```markdown
- The keyword spotter's smaller "-mobile" package makes sherpa-onnx 1.13.8 end Kestrel's process, so Kestrel uses the regular package (B37).
- Kokoro is sherpa-onnx's package `kokoro-multi-lang-v1_0` in fp32, because its int8 version is slower on ARM phones and had a defect that sherpa-onnx fixed only after 1.13.8 (research r11). Kestrel copies only `model.onnx`, `voices.bin` and `tokens.txt` and uses its own espeak-ng data: with a language set, sherpa-onnx 1.13.8 takes the phonemes of English text from espeak-ng, so the voice needs no lexicon. The voice speaks at 24 kHz. Kestrel gives it no sentence with a Chinese character, because sherpa-onnx logs the words of such a sentence (section 13.5). The owner chooses the voice and the speed on the page "Voice and speech" (section 15). When Kokoro is chosen but does not load, for example because its files are missing or damaged, Piper speaks, and Kestrel logs the failure. The echo filter's rule (section 8.4) holds for both voices: a device test feeds sentences in each voice to the keyword spotter, and the filter takes every hit for Kestrel's own voice. The A71 session measures how fast each voice speaks there; if Kokoro is slower than real time on the A71, a change after the session makes the page say so next to the choice.
```

In section 8.10, replace

```markdown
- `scripts/push-models.ps1` downloads the model files on the PC from their upstream sources into a cache folder (`%LOCALAPPDATA%\Kestrel\models`), checks their SHA-256 hashes against `models/manifest.json` in the repository, and copies them with `adb push` into `/sdcard/Android/data/dev.kestrel/files/models/`. The phone itself never downloads a model.
```

with

```markdown
- `scripts/push-models.ps1` downloads the model files on the PC from their upstream sources into a cache folder (`%LOCALAPPDATA%\Kestrel\models`), checks their SHA-256 hashes against `models/manifest.json` in the repository, and copies them with `adb push` into `/sdcard/Android/data/dev.kestrel/files/models/`. The phone itself never downloads a model. The manifest marks optional models, such as the Kokoro voice, which the script copies only with `-Kokoro`. An optional model of which no file is on the phone is absent, which the setup checklist and the About page do not count as a problem; once one of its files is there, Kestrel checks it like any other model.
```

Replace

```markdown
- The build copies `models/manifest.json` into the app's assets, so the hashes that Kestrel checks are part of the signed app. Before Kestrel loads an engine, and whenever the settings app shows the setup checklist or the About page, it checks the engine's files against them. It hashes a file again only when the file's size or modification time changes. A missing or corrupt file disables the engine that needs it, and the setup checklist names the file. While shared storage is not available, the models count as missing, and the next check or load after it is back finds them. When a check fails, the setup checklist and the About page say so, and they check again when they open again; the About page also checks again when the owner comes back to it from a licence text.
```

with

```markdown
- The build copies `models/manifest.json` into the app's assets, so the hashes that Kestrel checks are part of the signed app. Before Kestrel loads an engine, and whenever the settings app shows the setup checklist, the About page or the page "Voice and speech", it checks the engine's files against them. It hashes a file again only when the file's size or modification time changes. A missing or corrupt file disables the engine that needs it, and the setup checklist names the file. While shared storage is not available, the models count as missing, and the next check or load after it is back finds them. When a check fails, the setup checklist and the About page say so, and they check again when they open again; the About page also checks again when the owner comes back to it from a licence text.
```

In section 15, replace

```markdown
9. Optionally, turn off Voice Access, so that the two apps don't react to the same words. Kestrel shows a warning while both are on.
```

with

```markdown
9. Optionally, turn off Voice Access, so that the two apps don't react to the same words. Kestrel shows a warning while both are on.

The page "Voice and speech" offers the voice, Piper (the default) or Kokoro while its files are on the phone, and the speed from 0.8 to 1.4 in steps of 0.1 with the buttons "Slower" and "Faster", which Voice Access and TalkBack can name. "Try it" speaks a fixed sentence with the choice on the page. A new voice or speed counts from the next time that Kestrel turns on.
```

In `README.md`, replace

```markdown
Uninstalling Kestrel deletes the models from the phone.
```

with

```markdown
Uninstalling Kestrel deletes the models from the phone. With `-Kokoro`, the script also copies the optional Kokoro voice, about 354 MB more; then you can choose it on the page "Voice and speech".
```

and replace

```markdown
- **About:** Kestrel's version, the Android version, whether each speech model is ready, and the licence texts.
```

with

```markdown
- **Voice and speech:** the voice, Piper or Kokoro (when `push-models.ps1 -Kokoro` copied it), and the speed from 0.8 to 1.4. *Try it* speaks a sentence with the choice on the page. A new voice or speed counts from the next time that Kestrel turns on; when Kokoro does not load, for example because its files are missing, Piper speaks.
- **About:** Kestrel's version, the Android version, whether each speech model is ready (the optional Kokoro voice may be "not on the phone"), and the licence texts.
```

In the paragraph of `README.md` on the voice tests, replace

```markdown
The device tests of the speech engines need the speech models on the device. `scripts\voice-test.ps1 -Serial $serial` installs Kestrel, copies the models with `push-models.ps1`,
```

with

```markdown
The device tests of the speech engines need the speech models on the device. `scripts\voice-test.ps1 -Serial $serial` installs Kestrel, copies the models with `push-models.ps1 -Kokoro`, so also the optional Kokoro voice, whose tests run too,
```

- [ ] **Step 15: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (243 in `:app`: 237, the 5 of `VoiceOptionsSettingTest` and `SetupChecklistTest`'s new test; 136 in `:core:voice`: 131 and 5), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 16: Run the voice device tests**

The first run downloads about 354 MB for Kokoro into `%LOCALAPPDATA%\Kestrel\models` and copies it to the emulator. Every voice session of the run loads the voice through `loadChosenVoice`, with the stored default, Piper.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 74 run, 0 failed, 0 errors, 0 skipped." (71, `SynthesizerTest.theKokoroVoiceSpeaksASentence` and the two tests of `EchoTimingTest`), no warning from the restores at the end, and among the printed lines one that contains "kokoro: loaded in" and two that contain "echo timing:" (logcat puts its own prefix before them). Put the "kokoro:" and "echo timing:" lines into the task's report. If either case of `EchoTimingTest` fails, stop and report the failure message to the controller: it names the voice, the keyword, its time and the sentence's number, and the filter's rule would then need a change, which this plan does not make.

- [ ] **Step 17: Run the other device tests of `:app`**

Task 7 changed the settings app's pages, `StoredSettingsData` and the check of the models, which `MainActivityTest` drives with the real store. This step runs every device test of `:app` outside the voice package; `voice-test.ps1` ran those of the voice package in Step 16.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 114 tests and 0 failures: the 108 of Task 5 and `SettingsAppTest`'s six new tests (27 in `SettingsAppTest`).

- [ ] **Step 18: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 19: Commit**

```powershell
git add models/manifest.json core/voice/src/main/kotlin/dev/kestrel/voice/Synthesizer.kt core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKokoroSynthesizer.kt core/voice/src/main/kotlin/dev/kestrel/voice/speak/EchoFilter.kt core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt core/voice/src/test/kotlin/dev/kestrel/voice/VoiceEnginesTest.kt core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KokoroTextTest.kt
git add app/src/main/kotlin/dev/kestrel/app/listen/VoiceOptionsSetting.kt app/src/main/kotlin/dev/kestrel/app/settings/VoicePage.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/listen/VoiceOptionsSettingTest.kt app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/SynthesizerTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/EchoTimingTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt
git add scripts/push-models.ps1 scripts/lib/Models.ps1 scripts/voice-test.ps1 scripts/tests/Models.Tests.ps1 THIRD_PARTY_NOTICES.md docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(voice): the page Voice and speech, and Kokoro's af_heart as an optional voice" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The voice corpus and voice-eval.ps1

**Files:**
- Create: `corpus/commands.txt` (the corpus's utterances)
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/WordErrors.kt`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/Recognizer.kt` (`secondDecodings`, `keptBoostWords`), `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaRecognizer.kt` (both)
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/Wav.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/EvalHooks.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` (the dispatch to `EvalHooks`)
- Create: `scripts/lib/Corpus.ps1`, `scripts/make-corpus.ps1`, `scripts/voice-eval.ps1`
- Modify: `scripts/hook.ps1` (the header, `voice-synth`, `voice-record`, `voice-eval` and their parameters)
- Modify: `.gitignore`, `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 16.4), `README.md` (a paragraph on the corpus)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/WordErrorsTest.kt` (JVM, new), `app/src/androidTest/kotlin/dev/kestrel/app/voice/EvalHookTest.kt` (voice device test, new; `voice-test.ps1` runs it), `scripts/tests/Corpus.Tests.ps1` (Pester, new)

**Interfaces:**
- Consumes: `Requests.read(transcript, spotted, afterName)` and `Request.Stop(named)`; `BoostWordSource(labels, report).words()` and `LauncherAppLabels`; `VoiceEngines.recognizer()`, `speechDetector()` and `synthesizer(VoiceOptions)`; `SpeechDetector.accept` and `finish` with `Utterance.startSample`; `VoiceChoice` and `VoiceOptions(variation, voice)` from Task 7; `ClipMicrophone.speech(voice, text)`; `MicCapture(context)`; `Listening.sessionOpen`; `TestHooks.HookError`, `TestHooks.ACTION_PREFIX`; `Invoke-SettingsCommand`, `Assert-TestDevice`, `Get-Adb`.
- Produces: `internal data class WordErrors(val words: Int, val substitutions: Int, val deletions: Int, val insertions: Int)` with `errors` and `WordErrors.count(said, heard)`; `Recognizer.secondDecodings: Int` and `Recognizer.keptBoostWords(boostWords: List<String>): Int`; the hooks `VOICE_SYNTH`, `VOICE_RECORD` and `VOICE_EVAL` (`EvalHooks.COMMANDS`), with `hook.ps1 voice-synth -Text -File [-Voice] | -Close`, `voice-record -File -Seconds | -Result` and `voice-eval -Load | -Close | -File -Expected -Kind [-Spotted] [-AfterName] [-Reveal] [-NoBoost]`; `EvalHooks.GENERATED` ("gen-"); in `scripts\lib\Corpus.ps1`: `Read-Corpus([string]$Path)`, `Get-EvalFolder`, `Get-ClipSeconds([string]$Text)`, `Invoke-AdbCopy -Serial -Direction push|pull -From -To`, `ConvertFrom-HookAnswer([string]$Text)`, `New-EvalTotals`, `Add-EvalResult($Totals, $Answer)`, `Format-EvalSummary($Totals)`; `scripts\voice-eval.ps1 -Serial -Corpus generated|owner [-Voice piper|kokoro] [-Show] [-NoBoost]` and `scripts\make-corpus.ps1 -Serial [-Voice piper|kokoro]`. Task 9 uses all of these.

Design D7, spec 16.4. `corpus/commands.txt` holds about 110 English utterances, none of them private: Appendix A's commands for the later sub-projects, which `Requests` passes on as `Other`; Kestrel's own phrases; answers to "Yes?"; tasks with the name; phrases that are near Kestrel's words, such as "Chest roll and a pastry, please." and "It's top-rated."; and the call commands. Each line says what `Requests` should read, whether the keyword spotter hears "Kestrel" at the start, and whether the A71 session asks the owner to record it (100 lines). The expected readings follow the rules of `Requests` as they are, so that the generated corpus measures the recognition: for example, "Forget about it." in an answer reads as a task, because only "never mind", "forget it" and "nothing" end the wait (spec 8.3). The call commands are only transcribed, not read: `Requests` reads them only while a call rings or is active (Tasks 2 and 3), so their lines expect `-`. `make-corpus.ps1` says each utterance in one of Kestrel's voices on a test device through the hook `VOICE_SYNTH`, which writes a 16 kHz WAV clip into Kestrel's folder `files/eval` of its external files folder, and copies the clips into `corpus/generated/<voice>`, which git ignores, as it ignores `corpus/owner`, where the A71 session puts the owner's recordings (Task 9). `voice-eval.ps1` copies each clip with the text that it should say into the same folder, and the hook `VOICE_EVAL` cuts the clip's speech out with Kestrel's speech detector, as Kestrel's pipeline does, so that a clip's silence neither slows the transcription nor makes the recogniser decode it a second time, transcribes it with the boost words of Kestrel's voice session ("Kestrel" and the names of the apps), computes the word errors and checks the reading of `Requests` on the device, and answers only counts and times: words, substitutions, deletions, insertions, whether `Requests` read the utterance as intended, the transcription's milliseconds, the seconds of speech, Kestrel's memory (PSS), the number of utterances that the detector found, and whether the recogniser decoded the clip a second time without boost words (spec 8.3). A number counts the same in words and in digits, because the recogniser may write either. Design D7 lets the hook return the transcripts of the generated corpus and the script compare them on the PC; the reading of `Requests` exists only in Kotlin, so the hook compares on the device for both corpora, and for the generated clips `-Show` adds the transcript, which the script prints for each clip that it misheard. The hook shows a transcript only for a clip whose name starts with `gen-`, which is how the scripts name the generated clips on the device, so that even a call of `hook.ps1` by hand cannot show what Kestrel heard in an owner's recording. For the owner's recordings, the script never asks for the transcript and prints only totals. The recogniser of the evaluation is a second one besides a voice session's, about 700 MB, so the hook refuses while Kestrel's voice session is open, and `-Load` loads it in the background, because a load takes longer than a hook may run. The load's answer also gives how many boost words Kestrel read and how many of them sherpa-onnx gets after the guard of spec 8.3, which the A71 session records as counts. `VOICE_RECORD` records the microphone into a clip for the A71 session; it answers once the microphone records, so that the session asks the owner to speak only then, and `-Result` gives the recording's answer. The corpus scripts do not use it. The device test `EvalHookTest` is written first and fails to compile until the hooks exist.

- [ ] **Step 1: Write the failing JVM tests of the word errors**

Create `app/src/test/kotlin/dev/kestrel/app/listen/WordErrorsTest.kt`:

```kotlin
package dev.kestrel.app.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class WordErrorsTest {
    @Test
    fun aTranscriptThatMatchesHasNoErrorsWhateverItsCaseAndPunctuation() {
        assertEquals(WordErrors(3, 0, 0, 0), WordErrors.count("Kestrel, open settings.", "kestrel open settings"))
    }

    @Test
    fun substitutionsDeletionsAndInsertionsAreCounted() {
        assertEquals(WordErrors(4, 1, 1, 0), WordErrors.count("Open the settings now.", "open a settings"))
        assertEquals(WordErrors(3, 0, 0, 1), WordErrors.count("Open the settings.", "please open the settings"))
    }

    @Test
    fun nothingHeardIsADeletionOfEveryWord() {
        val errors = WordErrors.count("Stop listening.", "")

        assertEquals(WordErrors(2, 0, 2, 0), errors)
        assertEquals(2, errors.errors)
    }

    @Test
    fun anApostropheStaysInItsWordAndAHyphenSplitsWords() {
        assertEquals(WordErrors(3, 0, 0, 0), WordErrors.count("It's top-rated.", "its top rated"))
    }

    @Test
    fun numbersCountTheSameInWordsAndInDigits() {
        assertEquals(WordErrors(6, 0, 0, 0), WordErrors.count("Set an alarm for 6:45.", "set an alarm for six forty-five"))
        assertEquals(WordErrors(3, 0, 0, 0), WordErrors.count("Tap number 12.", "Tap number twelve."))
        val timer = WordErrors.count("Set a timer for twenty five minutes.", "set a timer for 25 minutes")
        assertEquals(WordErrors(6, 0, 0, 0), timer)
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.WordErrorsTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `WordErrors`.

- [ ] **Step 3: Count the word errors**

Create `app/src/main/kotlin/dev/kestrel/app/listen/WordErrors.kt`:

```kotlin
package dev.kestrel.app.listen

/**
 * How a transcript differs from what was said (design spec, section 16.4): the [words] that were said, and the
 * [substitutions], [deletions] and [insertions] of the alignment with the fewest changes. Letter case and punctuation do
 * not count, and an apostrophe stays in its word, as in Requests. A number counts the same in words and in digits, up to
 * 99, because the recogniser may write either: "six forty-five" is "6:45", and "twenty five" is "25". The word error
 * rate is [errors] divided by [words]. The evaluation hook of debug builds uses it. It holds only counts, so it may be
 * printed.
 */
internal data class WordErrors(val words: Int, val substitutions: Int, val deletions: Int, val insertions: Int) {
    val errors: Int get() = substitutions + deletions + insertions

    companion object {
        private val WORD = Regex("""[\p{L}\p{N}]+(?:'[\p{L}\p{N}]+)*""")

        fun count(said: String, heard: String): WordErrors {
            val a = words(said)
            val b = words(heard)
            // cost[i][j]: the fewest changes that turn the first i words that were said into the first j words heard.
            val cost = Array(a.size + 1) { i -> IntArray(b.size + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
            for (i in 1..a.size) {
                for (j in 1..b.size) {
                    val change = if (a[i - 1] == b[j - 1]) 0 else 1
                    cost[i][j] = minOf(cost[i - 1][j - 1] + change, cost[i - 1][j] + 1, cost[i][j - 1] + 1)
                }
            }
            // Back along one cheapest alignment, counting its changes by kind.
            var i = a.size
            var j = b.size
            var substitutions = 0
            var deletions = 0
            var insertions = 0
            while (i > 0 || j > 0) {
                val change = if (i > 0 && j > 0 && a[i - 1] == b[j - 1]) 0 else 1
                when {
                    i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + change -> {
                        substitutions += change
                        i--
                        j--
                    }
                    i > 0 && cost[i][j] == cost[i - 1][j] + 1 -> {
                        deletions++
                        i--
                    }
                    else -> {
                        insertions++
                        j--
                    }
                }
            }
            return WordErrors(a.size, substitutions, deletions, insertions)
        }

        private val UNITS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        )

        private fun words(text: String): List<String> =
            digits(WORD.findAll(text.lowercase()).map { it.value.replace("'", "") }.toList())

        // Writes the numbers up to 99 in digits: "forty five" and "forty-five", which are two words here, become "45".
        private fun digits(words: List<String>): List<String> {
            val result = mutableListOf<String>()
            var at = 0
            while (at < words.size) {
                val tens = TENS[words[at]]
                val unit = UNITS.indexOf(words[at])
                val next = words.getOrNull(at + 1)?.let { UNITS.indexOf(it) } ?: -1
                when {
                    tens != null && next in 1..9 -> {
                        result += (tens + next).toString()
                        at += 2
                    }
                    tens != null -> {
                        result += tens.toString()
                        at++
                    }
                    unit >= 0 -> {
                        result += unit.toString()
                        at++
                    }
                    else -> {
                        result += words[at]
                        at++
                    }
                }
            }
            return result
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.WordErrorsTest`
Expected: `BUILD SUCCESSFUL`, with 5 tests in `WordErrorsTest`.

- [ ] **Step 5: Let the recogniser report its second decodings and the boost words that it keeps**

In `core/voice/src/main/kotlin/dev/kestrel/voice/Recognizer.kt`, replace

```kotlin
interface Recognizer : AutoCloseable {
    /** Transcribes one utterance. [boostWords] are words that the speaker is likely to say, such as labels on the screen. */
    fun transcribe(samples: FloatArray, boostWords: List<String> = emptyList()): Transcript
}
```

with

```kotlin
interface Recognizer : AutoCloseable {
    /** Transcribes one utterance. [boostWords] are words that the speaker is likely to say, such as labels on the screen. */
    fun transcribe(samples: FloatArray, boostWords: List<String> = emptyList()): Transcript

    /**
     * How many utterances this recogniser has decoded a second time without boost words, because the transcript with
     * them ended too early (section 8.3). The A71 session counts them.
     */
    val secondDecodings: Int get() = 0

    /** How many of [boostWords] the recogniser passes on to its model; the others it drops (section 8.3). */
    fun keptBoostWords(boostWords: List<String>): Int = boostWords.size
}
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaRecognizer.kt`, replace

```kotlin
    private var closed = false

    override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript = synchronized(this) {
        check(!closed) { "The recognizer is closed" }
        val boost = BoostWords.format(boostWords, spelling)
        val first = decode(samples, boost)
        if (boost.isEmpty()) {
            first
        } else {
            TranscriptChoice.choose(samples.size / SAMPLE_RATE.toFloat(), first) { decode(samples, "") }
        }
    }
```

with

```kotlin
    private var closed = false

    @Volatile
    override var secondDecodings = 0
        private set

    override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript = synchronized(this) {
        check(!closed) { "The recognizer is closed" }
        val boost = BoostWords.format(boostWords, spelling)
        val first = decode(samples, boost)
        if (boost.isEmpty()) {
            first
        } else {
            TranscriptChoice.choose(samples.size / SAMPLE_RATE.toFloat(), first) {
                secondDecodings++
                decode(samples, "")
            }
        }
    }

    // The phrases that BoostWords keeps, which it separates with "/".
    override fun keptBoostWords(boostWords: List<String>): Int =
        BoostWords.format(boostWords, spelling).split('/').count { it.isNotEmpty() }
```

- [ ] **Step 6: Write the device test of the hooks**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/EvalHookTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.EvalHooks
import dev.kestrel.app.debug.HookReply
import dev.kestrel.app.debug.TestHooks
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The hooks of the voice corpus (design spec, section 16.4), called as TestHookReceiver calls them. */
class EvalHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.getExternalFilesDir(null), EvalHooks.FOLDER)

    @After
    fun tearDown() {
        hook("VOICE_EVAL", flags = setOf("close"))
        hook("VOICE_SYNTH", flags = setOf("close"))
        File(folder, "gen-eval-test.wav").delete()
        File(folder, "eval-test.wav").delete()
    }

    @Test
    fun aClipInKestrelsVoiceIsTranscribedAndComparedWithoutItsText() {
        VoiceTests.assumeModels(Engine.VAD, Engine.ASR, Engine.TTS)
        val text = "Kestrel, open settings."

        // A generated clip's name starts with "gen-"; only such a clip may show what Kestrel heard.
        val wrote = hook("VOICE_SYNTH", "text" to text, "file" to "gen-eval-test")
        assertTrue(wrote.text, wrote.text.startsWith("wrote "))
        // Loading the recogniser takes several seconds, longer than a hook may run.
        val loaded = VoiceTests.waitUntil(120_000) { hook("VOICE_EVAL", flags = setOf("load")).text.startsWith("ready") }
        assertTrue("The recogniser did not load", loaded)
        assertTrue(LOADED.matches(hook("VOICE_EVAL", flags = setOf("load")).text))
        val extras = arrayOf("file" to "gen-eval-test", "expected" to text, "kind" to "OpenSettings")
        val answer = hook("VOICE_EVAL", *extras, flags = setOf("spotted"))
        val revealed = hook("VOICE_EVAL", *extras, flags = setOf("spotted", "reveal"))

        assertEquals(answer.text, Activity.RESULT_OK, answer.code)
        assertTrue(answer.text, ANSWER.matches(answer.text))
        assertTrue(answer.text, answer.text.contains(" request=ok read=OpenSettings "))
        // The speech detector cuts off the clip's silence, as in Kestrel's pipeline, so one decoding is enough.
        assertTrue(answer.text, answer.text.endsWith(" parts=1 twice=0"))
        assertFalse(answer.text, answer.text.contains("heard="))
        assertTrue(revealed.text, revealed.text.contains(" heard="))
    }

    @Test
    fun whatKestrelHeardInAnOwnersClipIsNeverShown() {
        val extras = arrayOf("file" to "eval-test", "expected" to "Stop.", "kind" to "Stop")

        val reply = hook("VOICE_EVAL", *extras, flags = setOf("reveal"))

        val refusal = "error: only a generated clip, whose name starts with gen-, may show what Kestrel heard"
        assertEquals(HookReply(HookReply.FAILED, refusal), reply)
    }

    @Test
    fun aRecordingAnswersOnceItRecordsAndBecomesAClipOf16Kilohertz() {
        VoiceTests.grantListeningPermissions()

        // Android lets an app record only while it is visible or runs a foreground service of the type microphone.
        val result = ActivityScenario.launch(MainActivity::class.java).use {
            val started = hook("VOICE_RECORD", "file" to "eval-test", ints = mapOf("seconds" to 1))
            assertEquals(started.text, "recording", started.text)
            var reply = started
            val ended = VoiceTests.waitUntil(10_000) {
                reply = hook("VOICE_RECORD", flags = setOf("result"))
                reply.text != "recording"
            }
            assertTrue("The recording did not end", ended)
            reply
        }

        assertEquals(result.text, Activity.RESULT_OK, result.code)
        assertTrue(result.text, RECORDED.matches(result.text))
        // 44 bytes of header and two bytes a sample, for about a second at 16 kHz.
        val clip = File(folder, "eval-test.wav")
        assertTrue("${clip.length()} bytes", clip.length() in 44L + 2 * 12_000..44L + 2 * 20_000)
    }

    @Test
    fun aClipNameThatCouldLeaveTheFolderIsRefused() {
        val reply = hook("VOICE_SYNTH", "text" to "Stop.", "file" to "../escape")

        assertEquals(HookReply(HookReply.FAILED, "error: add --es file with a name of letters, digits, '-' and '_'"), reply)
    }

    /** Runs a hook command as TestHookReceiver does, with string extras, int extras and boolean extras that are true. */
    private fun hook(
        command: String,
        vararg extras: Pair<String, String>,
        ints: Map<String, Int> = emptyMap(),
        flags: Set<String> = emptySet(),
    ): HookReply {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        for ((key, value) in ints) intent.putExtra(key, value)
        for (flag in flags) intent.putExtra(flag, true)
        return runBlocking { TestHooks(VoiceTests.graph, context).run(intent) }
    }

    private companion object {
        val LOADED = Regex("ready words=\\d+ kept=\\d+")
        val ANSWER = Regex(
            "words=3 substitutions=\\d+ deletions=\\d+ insertions=\\d+ request=(ok|wrong) read=\\w+ millis=\\d+ " +
                "seconds=\\d+\\.\\d pss=\\d+ parts=\\d+ twice=\\d+",
        )
        val RECORDED = Regex("recorded \\d+\\.\\d s, loudest \\d+ %, silenced=(true|false)")
    }
}
```

- [ ] **Step 7: Run the build to verify that the test fails to compile**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with `Unresolved reference` errors for `EvalHooks`.

- [ ] **Step 8: Add the hooks of the corpus**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/Wav.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.voice.SAMPLE_RATE
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** The clips of the voice corpus: WAV files of 16 kHz mono 16-bit samples (design spec, section 16.4). */
internal object Wav {
    /** Writes [samples], between -1 and 1, into [file] through a temporary file, so that no half clip is ever read. */
    fun write(file: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) data.putShort((sample.coerceIn(-1f, 1f) * 32_767).roundToInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data.capacity()).put("WAVE".toByteArray(Charsets.US_ASCII))
        // The format: PCM, one channel, 16 kHz, two bytes a sample.
        header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        header.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(data.capacity())
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { output ->
            output.write(header.array())
            output.write(data.array())
        }
        file.delete()
        check(temporary.renameTo(file)) { "The clip could not be written" }
    }

    /**
     * Reads the samples of a WAV file of 16 kHz mono 16-bit PCM, between -1 and 1. Throws [IllegalArgumentException] for
     * another format or a damaged file.
     */
    fun read(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun name(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && name(0) == "RIFF" && name(8) == "WAVE") { "Not a WAV file" }
        var position = 12
        var formatRead = false
        while (position + 8 <= bytes.size) {
            val size = buffer.getInt(position + 4)
            val body = position + 8
            require(size >= 0 && body + size <= bytes.size) { "A damaged WAV file" }
            when (name(position)) {
                "fmt " -> {
                    require(
                        size >= 16 && buffer.getShort(body).toInt() == 1 && buffer.getShort(body + 2).toInt() == 1 &&
                            buffer.getInt(body + 4) == SAMPLE_RATE && buffer.getShort(body + 14).toInt() == 16,
                    ) { "Not 16 kHz mono 16-bit PCM" }
                    formatRead = true
                }
                "data" -> {
                    require(formatRead) { "The samples come before the format" }
                    return FloatArray(size / 2) { buffer.getShort(body + it * 2) / 32_768f }
                }
            }
            // A chunk of an odd size is followed by a byte of padding.
            position = body + size + size % 2
        }
        throw IllegalArgumentException("No samples")
    }
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/EvalHooks.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.listen.WordErrors
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.MicCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The hooks of the voice corpus and its measurements (design spec, sections 5.3 and 16.4), which scripts\make-corpus.ps1,
 * scripts\voice-eval.ps1 and scripts\a71-session.ps1 use. They read and write clips of 16 kHz in the folder `eval` of
 * Kestrel's external files folder, which adb can reach. VOICE_SYNTH says a text in one of Kestrel's voices into a clip.
 * VOICE_RECORD starts recording the microphone into a clip and answers once the microphone records; `result` then asks
 * for the recording's answer. VOICE_EVAL cuts a clip's speech out with Kestrel's speech detector, as Kestrel's pipeline
 * does, transcribes it with the boost words of Kestrel's voice session, and compares the transcript with the text that
 * was to be said. Their answers hold only counts and times, except that VOICE_EVAL with `reveal` adds the transcript,
 * which it does only for a generated clip, whose name starts with [GENERATED]. They log nothing.
 */
internal class EvalHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE_SYNTH" -> if (intent.getBooleanExtra(EXTRA_CLOSE, false)) closeVoice() else synthesize(intent)
        "VOICE_RECORD" -> if (intent.getBooleanExtra(EXTRA_RESULT, false)) recordingResult() else record(intent)
        "VOICE_EVAL" -> when {
            intent.getBooleanExtra(EXTRA_LOAD, false) -> load()
            intent.getBooleanExtra(EXTRA_CLOSE, false) -> closeRecognizer()
            else -> evaluate(intent)
        }
        else -> throw HookError("unknown command '$command'")
    }

    private fun folder(): File =
        File(context.getExternalFilesDir(null) ?: throw HookError("Kestrel's external files folder is not available"), FOLDER)
            .apply { mkdirs() }

    private fun clip(intent: Intent): File {
        val name = intent.getStringExtra(EXTRA_FILE)
        if (name == null || !CLIP_NAME.matches(name)) throw HookError("add --es file with a name of letters, digits, '-' and '_'")
        return File(folder(), "$name.wav")
    }

    // Kestrel's voice session would hold a second recogniser and use the microphone.
    private fun requireOff() {
        if (graph.listening.sessionOpen.value) throw HookError("turn Kestrel off first, with voice -Send off")
    }

    private suspend fun synthesize(intent: Intent): String {
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es text <text>")
        val file = clip(intent)
        val choice = when (intent.getStringExtra(EXTRA_VOICE) ?: "piper") {
            "piper" -> VoiceChoice.PIPER
            "kokoro" -> VoiceChoice.KOKORO
            else -> throw HookError("add --es voice with piper or kokoro")
        }
        val samples = voiceLock.withLock {
            val current = voice?.takeIf { voiceChoice == choice } ?: run {
                voice?.close()
                voice = null
                val loaded = try {
                    // Without variation, the voice says a text the same way every time.
                    graph.voice.synthesizer(VoiceOptions(variation = 0f, voice = choice))
                } catch (e: ModelsNotReadyException) {
                    throw HookError("the voice's model is missing; run scripts\\push-models.ps1" + if (choice == VoiceChoice.KOKORO) " -Kokoro" else "")
                }
                voice = loaded
                voiceChoice = choice
                loaded
            }
            withContext(Dispatchers.Default) { ClipMicrophone.speech(current, text) }
        }
        withContext(Dispatchers.IO) { Wav.write(file, samples) }
        return "wrote ${seconds(samples.size)} s"
    }

    private suspend fun closeVoice(): String {
        voiceLock.withLock {
            voice?.close()
            voice = null
        }
        return "closed the voice"
    }

    // Answers once the microphone records, so that the A71 session asks the owner to speak only then; the recording goes
    // on in the background, longer than a hook may run.
    private fun record(intent: Intent): String {
        val file = clip(intent)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
        if (seconds !in 1..MAX_RECORDING_SECONDS) throw HookError("add --ei seconds with 1 to $MAX_RECORDING_SECONDS")
        requireOff()
        synchronized(lock) {
            if (recording === Recording.Running) throw HookError("a recording runs; ask for its answer with --ez result true")
            recording = null
        }
        val frames = CopyOnWriteArrayList<FloatArray>()
        val errors = CopyOnWriteArrayList<Exception>()
        val microphone = MicCapture(context)
        try {
            microphone.start(onFrame = { frames += it }, onError = { errors += it })
        } catch (e: SecurityException) {
            microphone.stop()
            throw HookError("Kestrel may not record; allow the microphone")
        } catch (e: IllegalStateException) {
            // A start that failed after the microphone opened leaves it open until a stop.
            microphone.stop()
            throw HookError("the microphone did not start")
        }
        synchronized(lock) {
            recording = Recording.Running
            graph.scope.launch {
                val ended = try {
                    delay(seconds * 1_000L)
                    val silenced = microphone.silenced.value
                    microphone.stop()
                    if (errors.isEmpty()) {
                        Recording.Ended(save(file, frames, silenced), null)
                    } else {
                        Recording.Ended(null, "the microphone failed")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only the class, never a message (section 13.5).
                    Recording.Ended(null, "the clip could not be written (${e.javaClass.simpleName})")
                } finally {
                    microphone.stop()
                }
                synchronized(lock) { recording = ended }
            }
        }
        return "recording"
    }

    private suspend fun save(file: File, frames: List<FloatArray>, silenced: Boolean): String {
        val samples = FloatArray(frames.sumOf { it.size })
        var offset = 0
        for (frame in frames) {
            frame.copyInto(samples, offset)
            offset += frame.size
        }
        withContext(Dispatchers.IO) { Wav.write(file, samples) }
        val loudest = ((samples.maxOfOrNull { abs(it) } ?: 0f) * 100).roundToInt()
        return "recorded ${seconds(samples.size)} s, loudest $loudest %, silenced=$silenced"
    }

    private fun recordingResult(): String {
        val current = synchronized(lock) { recording.also { if (it is Recording.Ended) recording = null } }
        return when (current) {
            null -> throw HookError("no recording; start one with --es file and --ei seconds")
            Recording.Running -> "recording"
            is Recording.Ended -> current.answer ?: throw HookError(current.failure ?: "the recording failed")
        }
    }

    private fun load(): String {
        synchronized(lock) {
            when (val current = evaluation) {
                is Evaluation.Ready -> return "ready words=${current.words.size} kept=${current.kept}"
                is Evaluation.Loading -> return "loading"
                is Evaluation.Failed -> {
                    // The next load tries again.
                    evaluation = null
                    return "failed: ${current.what}"
                }
                null -> Unit
            }
            requireOff()
            folder()
            evaluation = Evaluation.Loading(
                graph.scope.launch {
                    val self = coroutineContext.job
                    val loaded = try {
                        loadEngines()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Only the class, never a message (section 13.5).
                        Evaluation.Failed(e.javaClass.simpleName)
                    }
                    synchronized(lock) {
                        // A close, or a newer load after it, came first.
                        if ((evaluation as? Evaluation.Loading)?.job === self) {
                            evaluation = loaded
                        } else {
                            (loaded as? Evaluation.Ready)?.close()
                        }
                    }
                },
            )
        }
        return "loading"
    }

    // The words come first, so that a cancellation while they are read leaves no engine open; VoiceEngines closes an
    // engine whose load a cancellation drops. The words are those of Kestrel's voice session: "Kestrel" and the names of
    // the apps. A failed read of the names leaves "Kestrel" alone, and nothing is logged.
    private suspend fun loadEngines(): Evaluation.Ready {
        val words = BoostWordSource(LauncherAppLabels(context)) { _, _ -> }.words()
        val detector = graph.voice.speechDetector()
        var recognizer: Recognizer? = null
        try {
            recognizer = graph.voice.recognizer()
            return Evaluation.Ready(recognizer, detector, words, recognizer.keptBoostWords(words))
        } catch (e: Throwable) {
            recognizer?.close()
            detector.close()
            throw e
        }
    }

    private suspend fun closeRecognizer(): String {
        // After an evaluation that runs, which uses the engines.
        val closing = evaluationLock.withLock { synchronized(lock) { evaluation.also { evaluation = null } } }
        when (closing) {
            is Evaluation.Ready -> closing.close()
            is Evaluation.Loading -> closing.job.cancel()
            else -> Unit
        }
        return "closed the recogniser"
    }

    private suspend fun evaluate(intent: Intent): String {
        val file = clip(intent)
        val reveal = intent.getBooleanExtra(EXTRA_REVEAL, false)
        // What Kestrel heard in an owner's recording never leaves the device (section 16.4).
        if (reveal && !file.name.startsWith(GENERATED)) {
            throw HookError("only a generated clip, whose name starts with $GENERATED, may show what Kestrel heard")
        }
        val expected = intent.getStringExtra(EXTRA_EXPECTED) ?: throw HookError("add --es expected <text>")
        val kind = intent.getStringExtra(EXTRA_KIND) ?: throw HookError("add --es kind with the expected request, or $SKIP")
        requireOff()
        if (!file.isFile) throw HookError("there is no clip ${file.name}")
        val samples = try {
            withContext(Dispatchers.IO) { Wav.read(file) }
        } catch (e: IllegalArgumentException) {
            throw HookError("the clip is not a WAV file of 16 kHz mono 16-bit PCM")
        }
        return evaluationLock.withLock {
            val ready = synchronized(lock) { evaluation as? Evaluation.Ready }
                ?: throw HookError("load the recogniser first with --ez load true, until it answers ready")
            withContext(Dispatchers.Default) { compare(ready, samples, expected, kind, intent, reveal) }
        }
    }

    private fun compare(
        ready: Evaluation.Ready,
        samples: FloatArray,
        expected: String,
        kind: String,
        intent: Intent,
        reveal: Boolean,
    ): String {
        // As in Kestrel's pipeline, the recogniser gets only the speech that the detector finds, here from the start of its
        // first utterance to the end of its last one, so that the clip's silence does not count (section 8.3). The detector
        // starts each clip anew.
        ready.detector.finish()
        val parts = ready.detector.accept(samples) + ready.detector.finish()
        val speech = if (parts.isEmpty()) {
            FloatArray(0)
        } else {
            val end = minOf(parts.last().startSample + parts.last().samples.size, samples.size.toLong())
            samples.copyOfRange(parts.first().startSample.toInt(), end.toInt())
        }
        val recognizer = ready.recognizer
        val before = recognizer.secondDecodings
        val started = SystemClock.elapsedRealtime()
        // Without the boost words on request, to show their effect (plan 2C-3's final review, item 3).
        val words = if (intent.getBooleanExtra(EXTRA_NO_BOOST, false)) emptyList() else ready.words
        val heard = if (speech.isEmpty()) "" else recognizer.transcribe(speech, words).text
        val millis = SystemClock.elapsedRealtime() - started
        val twice = recognizer.secondDecodings - before
        val errors = WordErrors.count(expected, heard)
        val read = if (kind == SKIP) {
            "-"
        } else {
            kindOf(
                Requests.read(
                    heard,
                    spotted = intent.getBooleanExtra(EXTRA_SPOTTED, false),
                    afterName = intent.getBooleanExtra(EXTRA_AFTER_NAME, false),
                ),
            )
        }
        val request = when {
            kind == SKIP -> "skipped"
            read == kind -> "ok"
            else -> "wrong"
        }
        val answer = "words=${errors.words} substitutions=${errors.substitutions} deletions=${errors.deletions} " +
            "insertions=${errors.insertions} request=$request read=$read millis=$millis seconds=${seconds(speech.size)} " +
            "pss=${Debug.getPss() / 1024} parts=${parts.size} twice=$twice"
        return if (reveal) "$answer heard=$heard" else answer
    }

    /** The name of a request's kind, as corpus\commands.txt writes it. */
    private fun kindOf(request: Request?): String = when (request) {
        null -> "None"
        is Request.Stop -> if (request.named) "StopNamed" else "Stop"
        else -> request::class.simpleName ?: "Unknown"
    }

    private fun seconds(samples: Int) = String.format(Locale.ROOT, "%.1f", samples / SAMPLE_RATE.toFloat())

    private sealed interface Evaluation {
        class Loading(val job: Job) : Evaluation

        class Ready(
            val recognizer: Recognizer,
            val detector: SpeechDetector,
            val words: List<String>,
            val kept: Int,
        ) : Evaluation {
            fun close() {
                recognizer.close()
                detector.close()
            }
        }

        class Failed(val what: String) : Evaluation
    }

    private sealed interface Recording {
        object Running : Recording

        class Ended(val answer: String?, val failure: String?) : Recording
    }

    companion object {
        val COMMANDS = setOf("VOICE_SYNTH", "VOICE_RECORD", "VOICE_EVAL")

        /** The folder of the clips in Kestrel's external files folder. */
        const val FOLDER = "eval"

        /** The start of a generated clip's name; only such a clip may show what Kestrel heard. */
        const val GENERATED = "gen-"

        // Kept between the calls of one corpus run, because loading a model takes seconds; the scripts close them.
        private val lock = Any()
        private var evaluation: Evaluation? = null
        private var recording: Recording? = null
        private val evaluationLock = Mutex()
        private val voiceLock = Mutex()
        private var voice: Synthesizer? = null
        private var voiceChoice: VoiceChoice? = null

        private val CLIP_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        private const val MAX_RECORDING_SECONDS = 15

        // The kind of a line whose reading is not checked, such as a call command (corpus\commands.txt writes '-').
        private const val SKIP = "skip"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_LOAD = "load"
        private const val EXTRA_CLOSE = "close"
        private const val EXTRA_EXPECTED = "expected"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SPOTTED = "spotted"
        private const val EXTRA_AFTER_NAME = "afterName"
        private const val EXTRA_REVEAL = "reveal"
        private const val EXTRA_NO_BOOST = "noBoost"
    }
}
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace

```kotlin
        if (command in VoiceHooks.COMMANDS) return VoiceHooks(graph).run(command, intent)
```

with

```kotlin
        if (command in VoiceHooks.COMMANDS) return VoiceHooks(graph).run(command, intent)
        if (command in EvalHooks.COMMANDS) return EvalHooks(graph, context).run(command, intent)
```

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Write the corpus**

Create `corpus/commands.txt`:

```text
# Kestrel's voice corpus (design spec, section 16.4). scripts\make-corpus.ps1 says each line in one of Kestrel's voices,
# the A71 session asks the owner to say the lines marked for the owner, and scripts\voice-eval.ps1 measures them. One
# utterance per line, in seven columns separated by '|'; lines that start with '#' are comments. The columns:
#   id        a plain name, which is also the name of the utterance's clips
#   group     appendix (Appendix A's commands, which later sub-projects handle), own (Kestrel's own phrases), answer
#             (answers to "Yes?"), task (tasks with Kestrel's name), confusable (phrases near Kestrel's words) or call
#             (the call commands)
#   context   idle, or answer for an answer to "Yes?"
#   spotted   1 when the keyword spotter hears "Kestrel" at the start of the utterance, otherwise 0
#   expected  what Requests should read: Name, WakeUp, Stop, StopNamed, PauseListening, TurnOff, OpenSettings, Task or
#             Other; or - when the reading is not checked, as for the call commands, which Requests reads only while a
#             call rings or is active
#   owner     1 when the A71 session asks the owner to record the line, otherwise 0
#   text      what is said. No line may hold private words.
# The expected readings follow the rules of Requests as they are (design spec, section 8.3), so that a perfect
# transcript always reads as expected. For example, "Forget about it." in an answer is a task, because only "never
# mind", "forget it" and "nothing" end the wait.
c001 | appendix | idle | 0 | Other | 1 | Open Chrome.
c002 | appendix | idle | 0 | Other | 1 | Open apps.
c003 | appendix | idle | 0 | Other | 1 | Go back.
c004 | appendix | idle | 0 | Other | 1 | Go home.
c005 | appendix | idle | 0 | Other | 1 | Show notifications.
c006 | appendix | idle | 0 | Other | 1 | Show Quick Settings.
c007 | appendix | idle | 0 | Other | 1 | Show recent apps.
c008 | appendix | idle | 0 | Other | 0 | Show previous app.
c009 | appendix | idle | 0 | Other | 1 | Lock screen.
c010 | appendix | idle | 0 | Other | 1 | Take a screenshot.
c011 | appendix | idle | 0 | Other | 1 | Again.
c012 | appendix | idle | 0 | Other | 1 | Media pause.
c013 | appendix | idle | 0 | Other | 1 | What can I say?
c014 | appendix | idle | 0 | Other | 1 | Show numbers.
c015 | appendix | idle | 0 | Other | 0 | Hide numbers.
c016 | appendix | idle | 0 | Other | 0 | Show labels.
c017 | appendix | idle | 0 | Other | 1 | Turn on the flashlight.
c018 | appendix | idle | 0 | Other | 1 | Turn off Bluetooth.
c019 | appendix | idle | 0 | Other | 1 | Turn the volume up.
c020 | appendix | idle | 0 | Other | 1 | Tap Send.
c021 | appendix | idle | 0 | Other | 1 | Tap 7.
c022 | appendix | idle | 0 | Other | 1 | Scroll down.
c023 | appendix | idle | 0 | Other | 0 | Scroll to top.
c024 | appendix | idle | 0 | Other | 0 | Swipe forwards.
c025 | appendix | idle | 0 | Other | 0 | Show grid.
c026 | appendix | idle | 0 | Other | 1 | Start editing.
c027 | appendix | idle | 0 | Other | 1 | Type hello there.
c028 | appendix | idle | 0 | Other | 1 | Undo.
c029 | appendix | idle | 0 | Other | 1 | Delete the last word.
c030 | appendix | idle | 0 | Other | 1 | Select all text.
c031 | appendix | idle | 0 | Other | 1 | Copy.
c032 | appendix | idle | 0 | Other | 1 | Paste.
c033 | appendix | idle | 0 | Other | 1 | Hide keyboard.
c034 | appendix | idle | 0 | Other | 1 | Zoom in.
c035 | appendix | idle | 0 | Other | 1 | Search for coffee near me.
c036 | appendix | idle | 0 | Other | 1 | Call Mom.
c037 | appendix | idle | 0 | Other | 0 | Long-press the second photo.
c038 | appendix | idle | 0 | Other | 1 | Check.
c039 | appendix | idle | 0 | Other | 1 | Scroll down on the list.
c040 | appendix | idle | 0 | Other | 1 | Tap number 12.
c041 | own | idle | 1 | Name | 1 | Kestrel.
c042 | own | idle | 1 | Name | 1 | Hey Kestrel.
c043 | own | idle | 1 | WakeUp | 1 | Kestrel, wake up.
c044 | own | idle | 1 | WakeUp | 1 | Kestrel, wake up, please.
c045 | own | idle | 0 | Stop | 1 | Stop.
c046 | own | idle | 0 | Stop | 1 | Cancel.
c047 | own | idle | 0 | Stop | 1 | Stop it.
c048 | own | idle | 0 | Stop | 1 | No, cancel the task.
c049 | own | idle | 1 | StopNamed | 1 | Kestrel, stop.
c050 | own | idle | 0 | StopNamed | 1 | Stop it, Kestrel.
c051 | own | idle | 0 | PauseListening | 1 | Stop listening.
c052 | own | idle | 0 | PauseListening | 1 | Stop listening, Kestrel.
c053 | own | idle | 0 | TurnOff | 1 | Stop Kestrel.
c054 | own | idle | 1 | TurnOff | 1 | Kestrel, turn off.
c055 | own | idle | 1 | TurnOff | 1 | Kestrel, switch off.
c056 | own | idle | 0 | TurnOff | 1 | Turn off, Kestrel.
c057 | own | idle | 1 | OpenSettings | 1 | Kestrel, open settings.
c058 | own | idle | 1 | OpenSettings | 1 | Kestrel settings.
c059 | own | idle | 1 | OpenSettings | 1 | Kestrel, open your settings.
c060 | own | idle | 0 | WakeUp | 1 | Wake up, Kestrel.
c061 | answer | answer | 0 | StopNamed | 1 | Never mind.
c062 | answer | answer | 0 | StopNamed | 1 | Forget it.
c063 | answer | answer | 0 | StopNamed | 1 | Nothing, thanks.
c064 | answer | answer | 0 | StopNamed | 1 | No, never mind.
c065 | answer | answer | 0 | Task | 1 | Open the settings.
c066 | answer | answer | 0 | StopNamed | 1 | Stop.
c067 | answer | answer | 0 | WakeUp | 1 | Wake up.
c068 | answer | answer | 0 | Task | 1 | Turn off the flashlight.
c069 | answer | answer | 0 | Task | 1 | Turn off.
c070 | answer | answer | 0 | Task | 1 | Send a message to Ana on WhatsApp.
c071 | answer | answer | 0 | Task | 1 | No.
c072 | answer | answer | 0 | Task | 1 | Forget about it.
c073 | task | idle | 1 | Task | 1 | Kestrel, open Viber and message Zoran.
c074 | task | idle | 1 | Task | 1 | Kestrel, what's the weather tomorrow?
c075 | task | idle | 1 | Task | 1 | Kestrel, send a message to Ana on WhatsApp saying I'm running late.
c076 | task | idle | 1 | Task | 1 | Kestrel, set an alarm for 6:45.
c077 | task | idle | 1 | Task | 1 | Kestrel, call Dragoljub on Viber.
c078 | task | idle | 1 | Task | 1 | Kestrel, find the nearest pharmacy.
c079 | task | idle | 1 | Task | 1 | Kestrel, read my last email.
c080 | task | idle | 1 | Task | 1 | Kestrel, turn on do not disturb until 8.
c081 | task | idle | 1 | Task | 1 | Hey Kestrel, play some jazz on Spotify.
c082 | task | idle | 1 | Task | 1 | Kestrel, remind me to buy milk when I get home.
c083 | task | idle | 1 | Task | 1 | Kestrel, open setting.
c084 | task | idle | 1 | Task | 1 | Kestrel, what time is it in Tokyo?
c085 | task | idle | 1 | Task | 0 | Kestrel, take a note: the meeting moved to Thursday.
c086 | task | idle | 1 | Task | 1 | Stop. Kestrel, open Viber.
c087 | task | idle | 1 | Task | 1 | Kestrel, delete history.
c088 | task | idle | 1 | Task | 1 | Kestrel, save that as morning routine.
c089 | task | idle | 1 | Task | 1 | Kestrel, remember that my sister is Ana.
c090 | task | idle | 1 | Task | 1 | Kestrel, continue.
c091 | task | idle | 1 | Task | 1 | Kestrel, set a timer for 25 minutes.
c092 | confusable | idle | 0 | Other | 1 | Chest roll and a pastry, please.
c093 | confusable | idle | 0 | Other | 1 | The bus stop is around the corner.
c094 | confusable | idle | 0 | Other | 1 | It's top-rated.
c095 | confusable | idle | 0 | Other | 1 | That movie was unstoppable.
c096 | confusable | idle | 0 | Other | 0 | Castle tour starts at ten.
c097 | confusable | idle | 0 | Other | 0 | Nest roll.
c098 | confusable | idle | 0 | Other | 0 | Cancel culture is a hot topic.
c099 | confusable | idle | 0 | Other | 1 | Please stop the music.
c100 | confusable | idle | 0 | Other | 1 | I need to stop at the store.
c101 | confusable | idle | 0 | Other | 1 | Settings.
c102 | confusable | idle | 0 | Other | 1 | Open settings.
c103 | confusable | idle | 0 | Other | 1 | Turn off.
c104 | confusable | idle | 0 | Other | 1 | Type my email is ana at example dot com.
c105 | call | idle | 0 | - | 1 | Answer.
c106 | call | idle | 0 | - | 1 | Answer on speaker.
c107 | call | idle | 0 | - | 1 | Decline.
c108 | call | idle | 1 | - | 1 | Kestrel, answer.
c109 | call | idle | 0 | - | 1 | Hang up.
c110 | call | idle | 0 | - | 1 | Mute.
c111 | call | idle | 0 | - | 1 | Speaker.
```

The file has 111 lines of utterances, 100 of them for the owner.

- [ ] **Step 10: Write the failing Pester tests of the corpus helpers**

Create `scripts/tests/Corpus.Tests.ps1`:

```powershell
# Tests of scripts\lib\Corpus.ps1, which scripts\make-corpus.ps1, scripts\voice-eval.ps1 and scripts\a71-session.ps1 use.
# scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'Read-Corpus' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"

        # Writes a corpus file with these lines, and returns its path.
        function New-CorpusFile([string[]]$Lines) {
            $path = Join-Path $TestDrive 'commands.txt'
            [IO.File]::WriteAllText($path, ($Lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
            $path
        }
    }

    It 'reads the repository''s corpus' {
        $rows = @(Read-Corpus -Path "$PSScriptRoot\..\..\corpus\commands.txt")

        $rows.Count | Should -Be 111
        @($rows | Where-Object { $_.Owner }).Count | Should -Be 100
        @($rows | Select-Object -ExpandProperty Id -Unique).Count | Should -Be 111
        $rows[0].Text | Should -BeExactly 'Open Chrome.'
        @($rows | Where-Object { $_.Context -eq 'answer' }).Count | Should -Be 12
    }

    It 'reads the columns, and skips comments and empty lines' {
        $rows = @(Read-Corpus -Path (New-CorpusFile @('# a comment', '', 'x1 | own | answer | 1 | StopNamed | 0 | Never mind, Kestrel.')))

        $rows.Count | Should -Be 1
        $rows[0].Id | Should -BeExactly 'x1'
        $rows[0].Group | Should -BeExactly 'own'
        $rows[0].Context | Should -BeExactly 'answer'
        $rows[0].Spotted | Should -BeTrue
        $rows[0].Expected | Should -BeExactly 'StopNamed'
        $rows[0].Owner | Should -BeFalse
        $rows[0].Text | Should -BeExactly 'Never mind, Kestrel.'
    }

    It 'refuses a line that it cannot read: <_>' -ForEach @(
        'x1 | own | idle | 0 | Stop | 1',
        'x/1 | own | idle | 0 | Stop | 1 | Stop.',
        'x1 | own | ringing | 0 | Stop | 1 | Stop.',
        'x1 | own | idle | 2 | Stop | 1 | Stop.',
        'x1 | own | idle | 0 | Halt | 1 | Stop.',
        'x1 | own | idle | 0 | Stop | 1 | '
    ) {
        { Read-Corpus -Path (New-CorpusFile @($_)) } | Should -Throw '*commands.txt:1:*'
    }

    It 'refuses an id that is listed twice' {
        $line = 'x1 | own | idle | 0 | Stop | 1 | Stop.'

        { Read-Corpus -Path (New-CorpusFile @($line, $line)) } | Should -Throw '*listed twice*'
    }
}

Describe 'Get-ClipSeconds' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"
    }

    It 'gives a short utterance 4 s, a long one more, and none more than 15 s' {
        Get-ClipSeconds -Text 'Stop.' | Should -Be 4
        Get-ClipSeconds -Text 'Kestrel, send a message to Ana on WhatsApp saying I''m running late.' | Should -Be 8
        Get-ClipSeconds -Text (@('word') * 40 -join ' ') | Should -Be 15
    }
}

Describe 'The evaluation''s answers and totals' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"
    }

    It 'reads the hook''s answer, and keeps the spaces of what Kestrel heard' {
        $answer = ConvertFrom-HookAnswer 'words=3 substitutions=0 deletions=1 insertions=0 request=ok read=Task millis=231 seconds=2.3 pss=953 twice=0 heard=open the  settings'

        $answer.words | Should -Be '3'
        $answer.deletions | Should -Be '1'
        $answer.read | Should -BeExactly 'Task'
        $answer.heard | Should -BeExactly 'open the  settings'
    }

    It 'refuses a text that is not such an answer, without repeating it' {
        { ConvertFrom-HookAnswer 'loading' } | Should -Throw 'Not an answer of the evaluation hook.'
    }

    It 'adds up the answers into the totals that it prints' {
        $totals = New-EvalTotals
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=3 substitutions=0 deletions=0 insertions=0 request=ok read=Name millis=200 seconds=2.0 pss=900 twice=0')
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=2 substitutions=1 deletions=0 insertions=1 request=wrong read=Other millis=400 seconds=1.0 pss=950 twice=1')
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=5 substitutions=0 deletions=0 insertions=0 request=skipped read=- millis=300 seconds=3.0 pss=940 twice=0')

        Format-EvalSummary $totals | Should -Be @(
            'Clips: 3; words: 10; word error rate: 20.0 % (1 substitutions, 0 deletions, 1 insertions)'
            'Requests read as intended: 1 of 2 (50.0 %)'
            'Transcription: median 300 ms, 90th percentile 300 ms, longest 400 ms; at most 400 ms per second of audio'
            'Decoded a second time without boost words: 1 of 3 clips'
            'Kestrel''s memory (PSS): at most 950 MB'
        )
    }
}
```

- [ ] **Step 11: Run the Pester tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: in both shells, the 13 tests of `Corpus.Tests.ps1` fail, because `scripts\lib\Corpus.ps1` does not exist; the others pass; the script ends with "The script tests or checks failed."

- [ ] **Step 12: Write the corpus helpers**

Create `scripts/lib/Corpus.ps1`:

```powershell
# Helpers for Kestrel's voice corpus (design spec, section 16.4): corpus\commands.txt, the clips that
# scripts\make-corpus.ps1 and the A71 session make, and the answers of the evaluation hook, which scripts\voice-eval.ps1
# adds up. Dot-source scripts\lib\Kestrel.ps1 first: Invoke-AdbCopy uses Get-Adb. Nothing here prints what Kestrel heard.

# The folder on a device in which the evaluation hooks read and write their clips: the folder 'eval' of Kestrel's
# external files folder, which adb can reach.
function Get-EvalFolder { '/sdcard/Android/data/dev.kestrel/files/eval' }

# Reads corpus\commands.txt: one utterance per line, in the columns id, group, context, spotted, expected, owner and
# text, separated by '|'. Lines that start with '#' and empty lines are skipped. Throws, with the file and the line's
# number, on a line with another number of columns, an id that is not lowercase letters, digits and '-' or that is
# listed twice, an unknown context or reading, a spotted or owner value other than 0 or 1, or an empty text.
function Read-Corpus([string]$Path) {
    $contexts = @('idle', 'answer')
    $readings = @('Name', 'WakeUp', 'Stop', 'StopNamed', 'PauseListening', 'TurnOff', 'OpenSettings', 'Task', 'Other', '-')
    $ids = @{}
    $number = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.UTF8Encoding]::new($false))) {
        $number++
        if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        $where = "${Path}:${number}:"
        $parts = @($line -split '\|' | ForEach-Object { $_.Trim() })
        if ($parts.Count -ne 7) { throw "$where the line needs 7 columns separated by '|'." }
        $id, $group, $context, $spotted, $expected, $owner, $text = $parts
        if ($id -cnotmatch '^[a-z0-9-]+\z') { throw "$where the id must be lowercase letters, digits and '-'." }
        if ($ids.ContainsKey($id)) { throw "$where the id $id is listed twice." }
        if ($context -cnotin $contexts) { throw "$where the context must be idle or answer." }
        if ($spotted -notin '0', '1' -or $owner -notin '0', '1') { throw "$where spotted and owner must be 0 or 1." }
        if ($expected -cnotin $readings) { throw "$where unknown expected reading '$expected'." }
        if (-not $text) { throw "$where the line needs a text." }
        $ids[$id] = $true
        [pscustomobject]@{
            Id = $id
            Group = $group
            Context = $context
            Spotted = $spotted -eq '1'
            Expected = $expected
            Owner = $owner -eq '1'
            Text = $text
        }
    }
}

# How long the A71 session records an utterance of the owner, in whole seconds: half a second for each word and 2 s more,
# at least 4 s, and at most 15 s, the longest recording that the hook makes.
function Get-ClipSeconds([string]$Text) {
    $words = @($Text -split '\s+' | Where-Object { $_ }).Count
    [int][math]::Min(15, [math]::Max(4, [math]::Ceiling($words * 0.5) + 2))
}

# Copies a file to a device (push) or from it (pull) with adb, and fails with adb's own words when adb fails. adb's
# progress lines must not stop the script.
function Invoke-AdbCopy([string]$Serial, [ValidateSet('push', 'pull')][string]$Direction, [string]$From, [string]$To) {
    $ErrorActionPreference = 'Continue'
    $output = @(& (Get-Adb) -s $Serial $Direction $From $To 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb $Direction failed on ${Serial}: $(($output | ForEach-Object { "$_".Trim() }) -join ' ')" }
}

# Reads an answer of the evaluation hook, such as 'words=3 substitutions=0 ... twice=0', into an ordered table. The
# transcript, which the hook adds only when asked to reveal it, comes last as 'heard=...' and keeps its spaces. The error
# does not repeat the text, which could hold a transcript.
function ConvertFrom-HookAnswer([string]$Text) {
    $answer = [ordered]@{}
    $rest = "$Text".Trim()
    $at = $rest.IndexOf('heard=')
    if ($at -eq 0 -or ($at -gt 0 -and $rest[$at - 1] -eq ' ')) {
        $answer['heard'] = $rest.Substring($at + 6)
        $rest = $rest.Substring(0, $at).Trim()
    }
    foreach ($pair in @($rest -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw 'Not an answer of the evaluation hook.' }
        $answer[$name] = $value
    }
    if (-not $answer.Contains('words')) { throw 'Not an answer of the evaluation hook.' }
    $answer
}

# The totals of an evaluation, which Add-EvalResult fills and Format-EvalSummary prints.
function New-EvalTotals {
    [pscustomobject]@{
        Clips = 0
        Words = 0
        Substitutions = 0
        Deletions = 0
        Insertions = 0
        Checked = 0
        Intended = 0
        Twice = 0
        Pss = 0
        Millis = [System.Collections.Generic.List[double]]::new()
        PerSecond = [System.Collections.Generic.List[double]]::new()
    }
}

# Adds one answer of ConvertFrom-HookAnswer to the totals. An answer whose request was skipped, such as a call command's,
# does not count towards the requests.
function Add-EvalResult($Totals, $Answer) {
    $invariant = [Globalization.CultureInfo]::InvariantCulture
    $Totals.Clips++
    $Totals.Words += [int]$Answer.words
    $Totals.Substitutions += [int]$Answer.substitutions
    $Totals.Deletions += [int]$Answer.deletions
    $Totals.Insertions += [int]$Answer.insertions
    if ($Answer.request -ne 'skipped') {
        $Totals.Checked++
        if ($Answer.request -eq 'ok') { $Totals.Intended++ }
    }
    $millis = [double]::Parse([string]$Answer.millis, $invariant)
    $seconds = [double]::Parse([string]$Answer.seconds, $invariant)
    $Totals.Millis.Add($millis)
    if ($seconds -gt 0) { $Totals.PerSecond.Add($millis / $seconds) }
    $Totals.Twice += [int]$Answer.twice
    $Totals.Pss = [math]::Max($Totals.Pss, [int]$Answer.pss)
}

# A share in percent with one decimal, such as '12.5', or '-' when there is nothing to share.
function Format-Percent($Part, $Whole) {
    if ($Whole -gt 0) { [string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0.0}', 100.0 * $Part / $Whole) } else { '-' }
}

# The lines that sum up an evaluation: the word error rate, the requests read as intended, the transcription times
# (median, 90th percentile, longest, and the longest per second of audio), the second decodings and the memory.
function Format-EvalSummary($Totals) {
    $sorted = @($Totals.Millis | Sort-Object)
    $median = 0
    $ninetieth = 0
    $longest = 0
    if ($sorted.Count -gt 0) {
        $median = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.5)]
        $ninetieth = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.9)]
        $longest = $sorted[-1]
    }
    $perSecond = if ($Totals.PerSecond.Count -gt 0) { [math]::Round(($Totals.PerSecond | Measure-Object -Maximum).Maximum) } else { 0 }
    $errors = $Totals.Substitutions + $Totals.Deletions + $Totals.Insertions
    "Clips: $($Totals.Clips); words: $($Totals.Words); word error rate: $(Format-Percent $errors $Totals.Words) % " +
        "($($Totals.Substitutions) substitutions, $($Totals.Deletions) deletions, $($Totals.Insertions) insertions)"
    "Requests read as intended: $($Totals.Intended) of $($Totals.Checked) ($(Format-Percent $Totals.Intended $Totals.Checked) %)"
    "Transcription: median $median ms, 90th percentile $ninetieth ms, longest $longest ms; at most $perSecond ms per second of audio"
    "Decoded a second time without boost words: $($Totals.Twice) of $($Totals.Clips) clips"
    "Kestrel's memory (PSS): at most $($Totals.Pss) MB"
}
```

- [ ] **Step 13: Run the Pester tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 116, Failed: 0" in Windows PowerShell 5.1 and in PowerShell 7 (103 and these 13), "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 14: Write the corpus scripts and the hooks' commands**

Create `scripts/make-corpus.ps1`:

```powershell
# Makes the generated voice corpus (design spec, section 16.4): a test device says every utterance of
# corpus\commands.txt in one of Kestrel's voices, through the hook voice-synth, as a WAV clip of 16 kHz, and the script
# copies the clips into corpus\generated\<voice>, which git ignores. It replaces the clips of an earlier run. It needs
# Kestrel's debug build, its speech models (scripts\push-models.ps1, with -Kokoro for -Voice kokoro) and PowerShell 7.3
# or newer, as scripts\hook.ps1 does. The device writes each clip into Kestrel's folder Android/data/dev.kestrel/files/eval;
# the script deletes it there once it has copied it, and at the end it deletes the folder's clips and closes the voice
# that the hook keeps loaded, also when it fails or is interrupted.
# Example: scripts\make-corpus.ps1 -Serial emulator-5554 -Voice kokoro
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('piper', 'kokoro')][string]$Voice = 'piper'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$rows = @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\commands.txt'))
$folder = Join-Path $PSScriptRoot "..\corpus\generated\$Voice"
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$remote = Get-EvalFolder

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
}

try {
    foreach ($row in $rows) {
        # A generated clip's name starts with gen-, so that the evaluation hook may show what Kestrel heard in it.
        Invoke-Hook voice-synth @{ Text = $row.Text; File = "gen-$($row.Id)"; Voice = $Voice } | Out-Null
        Invoke-AdbCopy -Serial $Serial -Direction pull -From "$remote/gen-$($row.Id).wav" -To (Join-Path $folder "$($row.Id).wav")
        Invoke-SettingsCommand -Serial $Serial -Command "rm -f $remote/gen-$($row.Id).wav" | Out-Null
    }
} finally {
    try {
        Invoke-Hook voice-synth @{ Close = $true } | Out-Null
    } catch {
        Write-Warning "Could not close the voice that the hook loaded ($($_.Exception.Message)); it closes when Kestrel's process ends."
    }
    try {
        Invoke-SettingsCommand -Serial $Serial -Command "rm -f $remote/*.wav" | Out-Null
    } catch {
        Write-Warning "Could not delete the clips in $remote on $Serial. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell 'rm -f $remote/*.wav'"
    }
}
Write-Host "Made $($rows.Count) clips in $((Resolve-Path -LiteralPath $folder).Path)."
```

Create `scripts/voice-eval.ps1`:

```powershell
# Measures Kestrel's recognition on a voice corpus on a test device (design spec, sections 5.3 and 16.4): the word error
# rate, the share of utterances that Kestrel's Requests reads as intended, how long each transcription takes, how often
# the recogniser decodes a clip a second time without boost words, and the memory that Kestrel uses (PSS). -Corpus
# generated uses the clips that scripts\make-corpus.ps1 made in corpus\generated\<voice>; -Corpus owner uses the owner's
# recordings in corpus\owner\clips, which the A71 session makes and git ignores. For each clip, the script copies the clip
# into Kestrel's folder Android/data/dev.kestrel/files/eval, and the hook voice-eval transcribes it with the boost words
# of Kestrel's voice session and compares it there with the text that was to be said. For the owner's recordings the
# hook answers only counts and times, and the script prints only totals; for the generated clips, -Show also prints each
# clip that Kestrel misheard or read otherwise, with what it heard. Kestrel must be off, because its voice session would
# hold a second recogniser of about 700 MB. At the end, also when it fails or is interrupted, the script deletes the
# folder's clips on the device and closes the hook's recogniser. -NoBoost transcribes without the boost words, so that
# a second run shows what they change. It needs Kestrel's debug build, its speech models and PowerShell 7.3 or newer, as
# scripts\hook.ps1 does.
# Example: scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -Show
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][ValidateSet('generated', 'owner')][string]$Corpus,
    [ValidateSet('piper', 'kokoro')][string]$Voice = 'piper',
    [switch]$Show,
    [switch]$NoBoost
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
if ($Show -and $Corpus -eq 'owner') { throw 'The owner''s recordings are private, so -Show works only with -Corpus generated.' }
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$root = Join-Path $PSScriptRoot '..\corpus'
$folder = if ($Corpus -eq 'owner') { Join-Path $root 'owner\clips' } else { Join-Path $root "generated\$Voice" }
$rows = @(Read-Corpus -Path (Join-Path $root 'commands.txt') | Where-Object { Test-Path -LiteralPath (Join-Path $folder "$($_.Id).wav") })
if ($rows.Count -eq 0) {
    $how = if ($Corpus -eq 'owner') { 'The A71 session records them.' } else { "Make them with scripts\make-corpus.ps1 -Serial $Serial -Voice $Voice." }
    throw "There are no clips in $folder. $how"
}
$remote = Get-EvalFolder
# The evaluation hook shows what Kestrel heard only in a clip whose name starts with gen-, so an owner's clip goes by
# another name.
$clip = if ($Corpus -eq 'owner') { 'clip' } else { 'gen-clip' }

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
}

$totals = New-EvalTotals
$misheard = [System.Collections.Generic.List[string]]::new()
try {
    # Loading takes longer than a hook may run, so the hook loads in the background, and the script asks again.
    $state = Invoke-Hook voice-eval @{ Load = $true }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($state -eq 'loading' -and $watch.Elapsed.TotalSeconds -lt 300) {
        Start-Sleep -Seconds 2
        $state = Invoke-Hook voice-eval @{ Load = $true }
    }
    if ($state -notmatch '^ready words=(\d+) kept=(\d+)$') { throw "Kestrel's recogniser for the evaluation did not load: $state" }
    Write-Host "Boost words of Kestrel's voice session: $($Matches[1]), of which the recogniser keeps $($Matches[2])$(if ($NoBoost) { '; this run does not use them' })."
    foreach ($row in $rows) {
        Invoke-AdbCopy -Serial $Serial -Direction push -From (Join-Path $folder "$($row.Id).wav") -To "$remote/$clip.wav"
        $kind = if ($row.Expected -eq '-') { 'skip' } else { $row.Expected }
        $options = @{
            File = $clip
            Expected = $row.Text
            Kind = $kind
            Spotted = $row.Spotted
            AfterName = $row.Context -eq 'answer'
            Reveal = [bool]$Show
            NoBoost = [bool]$NoBoost
        }
        $answer = ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)
        Add-EvalResult -Totals $totals -Answer $answer
        $errors = [int]$answer.substitutions + [int]$answer.deletions + [int]$answer.insertions
        if ($Show -and ($errors -gt 0 -or $answer.request -eq 'wrong')) {
            $misheard.Add("$($row.Id): '$($row.Text)' was heard as '$($answer.heard)' and read as $($answer.read)")
        }
    }
} finally {
    try {
        Invoke-Hook voice-eval @{ Close = $true } | Out-Null
    } catch {
        Write-Warning "Could not close the evaluation's recogniser ($($_.Exception.Message)); it closes when Kestrel's process ends."
    }
    # For -Corpus owner, the clip on the device is one of the owner's recordings.
    try {
        Invoke-SettingsCommand -Serial $Serial -Command "rm -f $remote/*.wav" | Out-Null
    } catch {
        Write-Warning "Could not delete the clips in $remote on $Serial. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell 'rm -f $remote/*.wav'"
    }
}
Format-EvalSummary $totals | ForEach-Object { Write-Host $_ }
$misheard | ForEach-Object { Write-Host $_ }
```

In `scripts/hook.ps1`, replace

```powershell
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
```

with

```powershell
# The voice corpus (scripts\make-corpus.ps1 and scripts\voice-eval.ps1 run these). A clip is a 16 kHz WAV file in
# Kestrel's folder Android/data/dev.kestrel/files/eval, which -File names without '.wav':
#   voice-synth -Text 'text' -File name [-Voice piper|kokoro]
#                                           says the text in Kestrel's voice into a clip
#   voice-synth -Close                      closes the voice that voice-synth keeps loaded
#   voice-record -File name -Seconds n      starts recording the microphone for n seconds, at most 15, into a clip, and
#                                           answers recording once the microphone records; Kestrel must be off and in
#                                           front
#   voice-record -Result                    answers recording until the recording has ended, then its length, the
#                                           loudest sample and whether Android silenced it, never the sound
#   voice-eval -Load                        loads a recogniser with the boost words of Kestrel's voice session in the
#                                           background, while Kestrel is off; answers loading, ready with the numbers of
#                                           boost words, or failed with the error's class
#   voice-eval -File name -Expected 'text' -Kind kind [-Spotted] [-AfterName] [-Reveal] [-NoBoost]
#                                           transcribes the clip and compares it with the text: the numbers of words and
#                                           errors, whether Requests read the kind (skip: not checked), the time and the
#                                           memory; -Reveal adds what Kestrel heard, only for a generated clip, whose
#                                           name starts with gen-; -NoBoost transcribes without the boost words
#   voice-eval -Close                       closes that recogniser
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
```

Replace

```powershell
        'voice-state', 'voice-clip',
```

with

```powershell
        'voice-state', 'voice-clip', 'voice-synth', 'voice-record', 'voice-eval',
```

Replace

```powershell
    [switch]$Clear,
```

with

```powershell
    [switch]$Clear,
    [string]$File,
    [ValidateSet('piper', 'kokoro')][string]$Voice,
    [string]$Expected,
    [string]$Kind,
    [switch]$Spotted,
    [switch]$AfterName,
    [switch]$Reveal,
    [switch]$Load,
    [switch]$Close,
    [switch]$Result,
    [switch]$NoBoost,
```

Replace

```powershell
if ($Named) { $extras += '--ez named true' }
```

with

```powershell
if ($Named) { $extras += '--ez named true' }
if ($File) { $extras += "--es file $(ConvertTo-ShellText $File)" }
if ($Voice) { $extras += "--es voice $(ConvertTo-ShellText $Voice.ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('Expected')) { $extras += "--es expected $(ConvertTo-ShellText $Expected)" }
if ($Kind) { $extras += "--es kind $(ConvertTo-ShellText $Kind)" }
if ($Spotted) { $extras += '--ez spotted true' }
if ($AfterName) { $extras += '--ez afterName true' }
if ($Reveal) { $extras += '--ez reveal true' }
if ($Load) { $extras += '--ez load true' }
if ($Close) { $extras += '--ez close true' }
if ($Result) { $extras += '--ez result true' }
if ($NoBoost) { $extras += '--ez noBoost true' }
```

In `.gitignore`, add at the end of the file:

```gitignore

# The clips of the voice corpus: the generated ones, which scripts\make-corpus.ps1 makes on demand, and the owner's
# recordings and the A71 session's results, which are private (design spec, section 16.4)
/corpus/generated/
/corpus/owner/
```

- [ ] **Step 15: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: N, Failed: 0" in both shells, with N as in Step 13, "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 16: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 16.4, replace

```markdown
- The corpus has generated English commands and about 100 recordings of the owner, made once on the A71 or on the PC. **The owner's recordings stay out of the public repository,** in a folder that git ignores.
- Command accuracy and word error rate are measured on the emulator for every release candidate, and whenever the voice code or a model changes.
```

with

```markdown
- The corpus has generated English commands and about 100 recordings of the owner, made once on the A71 or on the PC. **The owner's recordings stay out of the public repository,** in a folder that git ignores.
- `corpus/commands.txt` lists the corpus's utterances, none of them private: Appendix A's commands for the later sub-projects, Kestrel's own phrases, answers to "Yes?", tasks with the name, phrases near Kestrel's words and the call commands. Each line says what `Requests` should read, by the rules of section 8.3 as they are, and whether the A71 session asks the owner to record it (100 lines). `scripts/make-corpus.ps1` says each utterance in one of Kestrel's voices on a test device into a 16 kHz WAV clip in `corpus/generated/<voice>`, and the A71 session records the owner's into `corpus/owner/clips`; git ignores both folders.
- `scripts/voice-eval.ps1` copies each clip with its text into Kestrel's files folder, and a hook of the debug build cuts the clip's speech out with Kestrel's speech detector, as the pipeline does, transcribes it with the boost words of Kestrel's voice session and compares it on the device. A number counts the same in words and in digits. The hook answers only counts and times: words, substitutions, deletions and insertions, whether `Requests` read the utterance as intended, the transcription's time, the length of the speech, Kestrel's memory (PSS), the number of utterances that the detector found and whether the recogniser decoded the clip a second time without boost words. Only for a generated clip, whose name on the device starts with `gen-`, may it also return what it heard. `-NoBoost` transcribes without the boost words, which shows their effect. The script prints the word error rate, the share of requests read as intended, the median, the 90th percentile and the longest transcription time, and the memory.
- Command accuracy and word error rate are measured on the emulator for every release candidate, and whenever the voice code or a model changes.
```

In `README.md`, replace

```markdown
Release builds do not contain the hooks, and the privacy audit fails a release build that does.
```

with

```markdown
The voice corpus is in `corpus\commands.txt`: about 110 English utterances, none of them private, each with what Kestrel's `Requests` should read. `scripts\make-corpus.ps1 -Serial $serial -Voice piper` (or `kokoro`) has Kestrel say each one on the device and copies the clips into `corpus\generated\piper`, and `scripts\voice-eval.ps1 -Serial $serial -Corpus generated -Voice piper -Show` measures the word error rate, the share of utterances that `Requests` reads as intended, the transcription times and Kestrel's memory, and prints each clip that Kestrel misheard; with `-NoBoost` it transcribes without the boost words, to show their effect. Kestrel must be off for it (`scripts\hook.ps1 -Serial $serial voice -Send off`). The owner's recordings, which the A71 session makes, go into `corpus\owner\clips`; `scripts\voice-eval.ps1 -Corpus owner` prints only totals for them, never what Kestrel heard. Git ignores both folders of clips. Both scripts need PowerShell 7.3 or newer, as `hook.ps1` does, and they delete their clips on the device at the end.

Release builds do not contain the hooks, and the privacy audit fails a release build that does.
```

- [ ] **Step 17: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (248 in `:app`: 243 and the 5 of `WordErrorsTest`; 136 in `:core:voice`, unchanged in this task), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, which checks that the release build has no hook, and the device tests compile.

- [ ] **Step 18: Run the voice device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 78 run, 0 failed, 0 errors, 0 skipped." (74 and the 4 of `EvalHookTest`), and no warning from the restores at the end.

- [ ] **Step 19: Make the generated corpus and measure it on the emulator**

The voice device tests installed Kestrel's current debug build and the models with Kokoro. Kestrel must be off. The commands make the clips in both voices, measure each voice's corpus, and measure Piper's corpus once more without the boost words, so that the path that the A71 session takes with `-NoBoost` has run once; together they take about 25 minutes.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 voice -Send off; pwsh -NoProfile -File scripts\make-corpus.ps1 -Serial emulator-5554 -Voice piper; pwsh -NoProfile -File scripts\make-corpus.ps1 -Serial emulator-5554 -Voice kokoro; pwsh -NoProfile -File scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -Show; pwsh -NoProfile -File scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice kokoro -Show; pwsh -NoProfile -File scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -NoBoost`
Expected: "sent off"; "Made 111 clips in ...\corpus\generated\piper." and the same for `kokoro`; then, for each voice, a line "Boost words of Kestrel's voice session: ..., of which the recogniser keeps ...", the five summary lines of `Format-EvalSummary` with "Clips: 111", and the lines of the misheard clips, if any; for the run with `-NoBoost`, the line "Boost words of Kestrel's voice session: ..., of which the recogniser keeps ...; this run does not use them.", the five summary lines with "Clips: 111", and "Decoded a second time without boost words: 0 of 111 clips", as for every run without boost words. This is the first measurement, so no value is required; put the summary lines and the misheard lines of both voices into the task's report, so that the controller can record them in `.superpowers/sdd/progress.md`. If a voice's "Requests read as intended" is below 80 %, name in the report the groups of the misheard lines.

Then check that nothing stayed behind: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 shell ls /sdcard/Android/data/dev.kestrel/files/eval; git status --short corpus`
Expected: no file listed on the device, and `git status` shows only `?? corpus/commands.txt` (the clips in `corpus\generated` are ignored).

- [ ] **Step 20: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 21: Commit**

```powershell
git add corpus/commands.txt app/src/main/kotlin/dev/kestrel/app/listen/WordErrors.kt app/src/test/kotlin/dev/kestrel/app/listen/WordErrorsTest.kt core/voice/src/main/kotlin/dev/kestrel/voice/Recognizer.kt core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaRecognizer.kt app/src/debug/kotlin/dev/kestrel/app/debug/Wav.kt app/src/debug/kotlin/dev/kestrel/app/debug/EvalHooks.kt app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/EvalHookTest.kt
git add scripts/lib/Corpus.ps1 scripts/make-corpus.ps1 scripts/voice-eval.ps1 scripts/hook.ps1 scripts/tests/Corpus.Tests.ps1 .gitignore docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(voice): the voice corpus, make-corpus.ps1 and voice-eval.ps1, with hooks that answer only counts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: The A71 session script, its dry run on the emulator, and the session's results in the README and the spec

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (new `runTask`, `performUnguarded`'s `RunTask`)
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` (the dispatch to `SessionHooks`)
- Modify: `scripts/hook.ps1` (the header, `voice-counts`, `voice-task`, `voice-echo`, `-Reply`)
- Create: `scripts/lib/Session.ps1`, `scripts/a71-session.ps1`
- Modify: `scripts/listen-check.ps1` (the header; on a phone, the unlock before the reboot check's recording check)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 18.2 and 19.1), `README.md` (a section on the A71 session, with the table of its measurements)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt` (voice device test, new), `scripts/tests/Session.Tests.ps1` (Pester, new)

**Interfaces:**
- Consumes: Task 1's focus rule (on a "may duck" loss, Kestrel's own player lowers its voice, and Kestrel keeps speaking); Task 6's earbud setting (by the owner, on the page "Listening and activation" of Task 5); Task 4's assistant services and `VOICE_COMMAND` trampoline; Task 7's `push-models.ps1 -Kokoro`, `VoiceChoice` and `VoiceOptions(voice = ...)`; Task 8's `Read-Corpus`, `Get-ClipSeconds`, `Get-EvalFolder`, `Invoke-AdbCopy`, `ConvertFrom-HookAnswer`, `voice-record -File -Seconds | -Result`, `voice-eval.ps1`; `ConvertFrom-VoiceState`, `Get-RecordingId`, `Get-NewRecordingState`, `Get-AccessibilitySettings`, `Restore-AccessibilitySettings`, `Get-SecureSetting`, `Set-SecureSetting`, `Invoke-SettingsCommand`, `Assert-TestDevice` and `$KestrelTestDevices` in `scripts\lib\Kestrel.ps1`; `install.ps1`, `voice-test.ps1`, `listen-check.ps1`; `State.wait`, `State.task`, `Event.TaskEnded(task, reply)`, `VoicePlayer`, `EchoFilter`, `KeywordSpotter`, `MicCapture`.
- Produces: `internal var Listening.runTask: (task: Int, text: String) -> Unit`; the hooks `VOICE_COUNTS`, `VOICE_TASK` and `VOICE_ECHO` (`SessionHooks.COMMANDS`), with `hook.ps1 voice-counts` (`waits= tasks= lastTaskWords=`), `voice-task -Seconds -Reply | -Clear` and `voice-echo -Text [-Voice] | -Result`; in `scripts\lib\Session.ps1`: `Add-SessionResult`, `Get-TotalPss`, `Get-AppUserId`, `ConvertTo-UidLabel`, `Get-BatteryUse`, `Get-StartedActions`, `Get-RoleHolders`, `Get-FailureDescriptions`, `Test-OnBattery`; `scripts\a71-session.ps1 -Serial [-DryRun] [-Step name, ...]`.

Design D8 and spec 18.2. This task writes the script that takes the owner through one session on the Galaxy A71 and gathers there every check of sub-project 2 that needs the A71 or the owner. It runs the script once on the emulator in its dry run, and it stops before the A71: the controller asks the owner once, with the session's length, and runs the session with the owner. The script's steps follow design D8's list and the lists "For plan 2D" of the final reviews of plans 2C-1 to 2C-3:

| Step | What it checks | Who acts | Minutes |
|---|---|---|---|
| `install` | `install.ps1` and `push-models.ps1 -Kokoro`, after Kestrel is turned off, because on Android 13 an update ends Kestrel's process with the reason of the owner's stop; then the session starts Kestrel itself and records its mode after the update | the script | 10 |
| `voice-tests` | `voice-test.ps1`: spike S8 (`OnnxRuntimeTogetherTest`) and every voice device test that may run on a phone; the voices' load times and speed (`SynthesizerTest`), the app names and their first read (`AppNamesTest`); then the session starts Kestrel itself, because `connected-test.ps1` ends with `am force-stop`, after which a restore keeps Kestrel off | the script | 30 |
| `listen-check` | `listen-check.ps1 -Reboot`: the problems notification's "Start listening" on One UI's shade, probably with the swipe that expands it (also whether Android 13 refuses a microphone service in the background or starts it silenced), the tile and the shortcut, "Kestrel, open settings" from the background, the restore after a crash and after a reboot (the stored mode and the models are readable then); the owner unlocks after the reboot | the script and the owner | 10 |
| `failures` | Kestrel's logged failures since the reboot, by their fixed descriptions, for example "The microphone" for a restart of the microphone | the script | 1 |
| `battery-rules` | final review of plan 2C-4, "For plan 2D", item 7: with Kestrel listening in the background and its accessibility service turned off, without and with the battery restriction (`RUN_ANY_IN_BACKGROUND`), the type of the foreground service (0 when demoted), Kestrel's mode, whether the microphone is silenced and which notice Kestrel shows; the manufacturer and the checklist's text for Samsung | the script and the owner | 15 |
| `reboots` | items 7 and 10: with Kestrel's accessibility service on, whose connection runs the restore at boot, three restarts with `adb reboot`, each with a time limit and a new boot ID, while Kestrel listens: whether the restore at boot listens again, the newest exit record's reason, and whether Kestrel says that the models are missing | the script and the owner | 12 |
| `close-start` | a start during a close: the tile right after "Kestrel, turn off", and whether Kestrel answers "Yes?" after it | the owner | 4 |
| `lock-tile` | the tile on the lock screen, which decides about `TileService.unlockAndRun` | the owner | 3 |
| `unlock` | the unlock paths of One UI: PIN, fingerprint from the screen off, face and Smart Lock; Kestrel records again after each | the owner | 8 |
| `activation` | the accessibility shortcut and button, the tile, and the notification's Pause, Resume and Stop with another app in front; after each start, whether Kestrel answers "Yes?" (final review of plan 2C-1, item 3) | the owner | 12 |
| `assistant` | spike S2: Kestrel as the assistant, the assistant gesture with another app in front, and the Galaxy Buds gesture: which intent One UI starts (only the action) and whether the trampoline gets it; after each gesture, whether the recording is silenced and whether the owner hears "Yes?" | the owner | 8 |
| `earbuds` | the earbud microphone (Task 6) with the owner's Galaxy Buds, "Yes?" in the earbuds, the sound of the call profile, and the phone's microphone after the earbuds go away | the owner | 8 |
| `recordings` | the owner's recordings of the 100 lines of the corpus that are marked for the owner, into `corpus\owner\clips`; each recording starts before "Speak now", and a line that an earlier session recorded is kept | the owner | 30 |
| `corpus` | `voice-eval.ps1` on the A71 with the owner's recordings, with and without the boost words (final review of plan 2C-3, item 3), and the generated clips of both voices, about 420 clips, with Kestrel's settings in front so that Android keeps the evaluation's recogniser: word error rate, requests, transcription times (spec 5.3), second decodings, memory, the boost words that the guard keeps, and One UI's language | the script | 43 |
| `voice` | with the owner's voice through the A71's speaker and microphone: the time to "Yes?" and its misses, the 8 s wait, a quick answer to "Yes?" with its echo, a long dictated task (the 35 s wait, the 1.85 s hold and the time from the end of speech to the task), interruptions with "Kestrel" and pauses of 0.5 to 2 s, a notification sound during a long reply (Kestrel keeps speaking, and its player lowers its voice), "stop" while a task runs and Kestrel is silent, the misses of "Stop listening", "Kestrel, wake up", "Hey Kestrel", "Kestrel, turn off" and "Stop Kestrel", and the echo filter with the real player and spotter ("It's top-rated.", "unstoppable", digits) in both voices | the owner | 30 |
| `memory` | spike S5: Kestrel's memory (PSS) before, during and after three starts and stops, the time from a start to an open session and of a close, and a cold start through the tile after Kestrel's process ended | the script and the owner | 8 |
| `room` | spike S5: an hour of ordinary room audio, in which the script counts Kestrel's reactions (the tasks that it started, exactly, and the starts of waits for a task, replies and changes of mode, counts only, and starts listening again after a change of mode), with the 30-minute battery test of research r10 during its first half, which runs with the screen on and room talk, a worst case, and records whether batterystats counted the phone as on battery at its end | the owner leaves the phone | 60 |

The steps' estimates add up to 4 hours 52 minutes, and the session takes about 5 to 5.5 hours, because `voice-tests` may take up to an hour on the A71: about 2 hours need the owner's hands and voice, and about 2 hours 55 minutes run without the owner. The owner can leave during `install` and `voice-tests` (40 minutes or more), `corpus` (about 45 minutes) and `room` (an hour); the other waits without the owner fall inside `listen-check`, `battery-rules`, `reboots` and `memory`, where the next question comes within a few minutes. The script asks for Enter at the start of each step, so the owner comes back at the end of each block. The A71 has no SIM card, so the calls are checked only on the emulator (Task 3), and One UI's incoming-call screen stays unchecked; the call API does not depend on it.

Three hooks of the debug build let the script measure without reading what Kestrel heard. `VOICE_COUNTS` answers how many waits for a task and how many tasks Kestrel has started in its process (`State.wait` and `State.task`), and how many words the last slow task had, so that a reaction to the owner's voice shows as a count, and a dictated task that was cut short shows by its number of words. `VOICE_TASK` makes each task take some seconds and then say a given reply, in place of sub-project 2's task that ends at once; with it the owner can interrupt a long reply and say "stop" while a task runs. A task that Kestrel cancelled meanwhile ends without its reply, so that the next try starts quiet. It replaces `Listening.runTask`, a new seam that sub-project 5 will fill with the agent; until then it is the task that ends at once with "I can't run tasks yet.". `VOICE_ECHO` plays a text in one of Kestrel's voices through the speaker while the real microphone feeds the keyword spotter, and counts the keyword hits and those that the echo filter takes for Kestrel's own voice; it runs in the background, because Kokoro on the A71 may take longer than a hook may run, and `-Result` gives its answer. The script calls `hook.ps1` and its other scripts with hashtables for named parameters, because an array that holds parameter names would pass them as values. Before the first step, it keeps the screen on while the phone is plugged in (`svc power stayon true`), because Kestrel records only while the screen is on and the phone unlocked. The script never runs a command on the phone before the owner's go-ahead: it asks before it reads the device's model, and before each step; a step that needs the owner's hands says what to do and waits. It refuses every device but the A71 and kestrel-api37 (`Assert-TestDevice`, without `-AllowDailyPhone`). It writes its results, which hold only counts, times, states, Android's intent actions and Kestrel's fixed descriptions, into `corpus\owner\session-<time>.txt`, and the owner's recordings into `corpus\owner\clips`; git ignores both (Task 8). It never prints what Kestrel heard. It puts back what it changes: Kestrel's mode Off, the hooks' slow task, the setting "Stay awake", the assistant role and its three settings, Kestrel's battery restriction, the accessibility settings, the screen's timeout, the battery's state (`dumpsys battery reset`) and the clips in Kestrel's folder `files/eval`, each restore in the `finally` block of the one before, so that Ctrl+C during one still runs the others; after Ctrl+C it ends with exit code 1. It cannot put back the battery statistics, which the battery test resets (research r10), and the settings in Kestrel that the owner changes, which it asks the owner to put back. `-DryRun` runs it on kestrel-api37 without the owner, and refuses a serial that does not start with `emulator-` before any command reaches a device: it answers every question itself, sends what the owner would say in the `voice` step through the hook `voice -Send heard`, so that the step's code runs once, skips what needs the owner's hands otherwise, runs `listen-check.ps1` without `-Reboot`, skips `voice-test.ps1`, which this plan runs itself, and the restarts of the step `reboots`, records two silent clips into a folder of its own that it deletes, and shortens the room's hour to two minutes, with the battery read after one.

Before the dry run, the task checks the phone branch of `voice-test.ps1` on the emulator (final review of plan 2C-1, M-1 and "For plan 2D", item 1): it has never run, and it relies on `notClass` taking a list joined by commas. The check runs the voice package through `connected-test.ps1` with the same `notClass` and checks that the left-out tests did not run. `listen-check.ps1`'s reboot check gets one change for phones: after the reboot the screen is off or locked, and Kestrel records only while it is on and unlocked, so on a phone the check wakes the screen and asks the owner to unlock before it checks the recording.

- [ ] **Step 1: Write the device test of the session's hooks**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`:

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
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hooks of the A71 session (plan 2D, design D8), called as TestHookReceiver calls them. */
class SessionHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listening = VoiceTests.graph.listening

    @After
    fun tearDown() {
        hook("VOICE_TASK", flags = setOf("clear"))
        VoiceTests.stopListening()
    }

    @Test
    fun aSlowTaskRunsForItsTimeIsCountedAndClearBringsBackTheTaskThatEndsAtOnce() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        val stub = listening.runTask

        // Kestrel is in front, so that Android lets it start its foreground service of the type microphone.
        ActivityScenario.launch(MainActivity::class.java).use {
            VoiceTests.startListening(ClipMicrophone())
            val reply = hook("VOICE_TASK", "reply" to "The task is done.", ints = mapOf("seconds" to 3))
            assertEquals(reply.text, Activity.RESULT_OK, reply.code)
            assertTrue(reply.text, reply.text.startsWith("each task now takes 3 s and then says a reply of 17 characters"))
            val tasks = listening.state.value.task

            listening.send(Event.Heard("Kestrel, open the window.", named = true))

            assertTrue("No task ran", VoiceTests.waitUntil(5_000) { listening.state.value.work == Work.RUNNING_TASK })
            // The counts name the waits, the tasks and the words of the last task, never the words themselves.
            val counts = hook("VOICE_COUNTS").text
            assertTrue(counts, Regex("waits=\\d+ tasks=${tasks + 1} lastTaskWords=3").matches(counts))
            // The task is still running a second later, unlike the task that ends at once.
            Thread.sleep(1_000)
            assertEquals(Work.RUNNING_TASK, listening.state.value.work)
            assertTrue("The task did not end", VoiceTests.waitUntil(10_000) { listening.state.value.work == Work.IDLE })
        }
        assertEquals("each task ends at once again", hook("VOICE_TASK", flags = setOf("clear")).text)
        assertSame(stub, listening.runTask)
    }

    @Test
    fun theEchoCheckPlaysTheTextInTheBackgroundAndAnswersOnlyCounts() {
        VoiceTests.assumeModels(Engine.KWS, Engine.TTS)
        VoiceTests.grantListeningPermissions()

        // Android lets an app record and play only while it is visible or runs a suitable foreground service.
        val result = ActivityScenario.launch(MainActivity::class.java).use {
            val started = hook("VOICE_ECHO", "text" to "Should I stop the timer?")
            assertEquals(started.text, "playing", started.text)
            var reply = started
            val ended = VoiceTests.waitUntil(60_000) {
                reply = hook("VOICE_ECHO", flags = setOf("result"))
                reply.text != "playing"
            }
            assertTrue("The echo check did not end", ended)
            reply
        }

        assertEquals(result.text, Activity.RESULT_OK, result.code)
        // Kestrel is in front, so Android gives it the audio focus, and the reply plays to its end. The emulator's
        // microphone hears silence, so the numbers of hits may be 0.
        assertTrue(result.text, Regex("result=finished hits=\\d+ own=\\d+").matches(result.text))
    }

    /** Runs a hook command as TestHookReceiver does, with string extras, int extras and boolean extras that are true. */
    private fun hook(
        command: String,
        vararg extras: Pair<String, String>,
        ints: Map<String, Int> = emptyMap(),
        flags: Set<String> = emptySet(),
    ): HookReply {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        for ((key, value) in ints) intent.putExtra(key, value)
        for (flag in flags) intent.putExtra(flag, true)
        return runBlocking { TestHooks(VoiceTests.graph, context).run(intent) }
    }
}
```

- [ ] **Step 2: Run the device test to verify that it fails to compile**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, with an `Unresolved reference` error for `runTask` in `SessionHookTest.kt`.

- [ ] **Step 3: Add the seam for tasks, and the session's hooks**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    /** Makes the microphone of the next session. The device tests and the debug hooks replace it. */
    @Volatile
    internal var microphones: () -> Microphone = microphones
```

with

```kotlin
    /** Makes the microphone of the next session. The device tests and the debug hooks replace it. */
    @Volatile
    internal var microphones: () -> Microphone = microphones

    /**
     * Runs task number `task`, and must send [Event.TaskEnded] for it once it ends. Sub-project 5 runs tasks with the
     * agent; until then every task ends at once with a reply that says so, or Kestrel would wait for it for ever. The
     * debug hooks replace it with a task that takes its time. Called on the controller's thread; it must not block.
     */
    @Volatile
    internal var runTask: (task: Int, text: String) -> Unit = { task, _ -> send(Event.TaskEnded(task, ListeningTexts.NO_TASKS)) }
```

and replace

```kotlin
            // Sub-project 5 runs tasks. Until then every task ends at once, or Kestrel would wait for it for ever.
            is Effect.RunTask -> send(Event.TaskEnded(effect.task, ListeningTexts.NO_TASKS))
```

with

```kotlin
            is Effect.RunTask -> runTask(effect.task, effect.text)
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.MicCapture
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hooks of the A71 session (plan 2D, design D8), which scripts\a71-session.ps1 uses. VOICE_COUNTS answers how many
 * waits for a task and how many tasks Kestrel has started in its process, and how many words the last slow task had, so
 * that the session counts Kestrel's reactions, also false ones, without reading what Kestrel heard. VOICE_TASK makes each
 * task take some seconds and then say a given reply, in place of the task that ends at once, so that the owner can
 * interrupt a long reply or say "stop" while a task runs; a task that Kestrel cancelled meanwhile ends without its reply.
 * VOICE_ECHO plays a text in one of Kestrel's voices through the speaker while the real microphone feeds the keyword
 * spotter, and counts the keyword hits and those that the echo filter takes for Kestrel's own voice (design spec, section
 * 8.4); it runs in the background, longer than a hook may run, and `result` asks for its answer. Their answers hold only
 * counts and lengths.
 */
internal class SessionHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "VOICE_COUNTS" -> graph.listening.state.value.let {
            "waits=${it.wait} tasks=${it.task} lastTaskWords=$lastTaskWords"
        }
        "VOICE_TASK" -> if (intent.getBooleanExtra(EXTRA_CLEAR, false)) clearTask() else task(intent)
        "VOICE_ECHO" -> if (intent.getBooleanExtra(EXTRA_RESULT, false)) echoResult() else startEcho(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private fun task(intent: Intent): String {
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
        if (seconds !in 1..MAX_TASK_SECONDS) throw HookError("add --ei seconds with 1 to $MAX_TASK_SECONDS")
        val reply = intent.getStringExtra(EXTRA_REPLY)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es reply <text>")
        val listening = graph.listening
        synchronized(lock) {
            if (replacedTask == null) replacedTask = listening.runTask
            listening.runTask = { task, text ->
                // A count, never the words, so that the session can tell a dictated task that was split.
                lastTaskWords = text.split(Regex("\\s+")).count { it.isNotEmpty() }
                graph.scope.launch {
                    delay(seconds * 1_000L)
                    // A task that Kestrel cancelled meanwhile ends in silence, so that the next try starts quiet.
                    val state = listening.state.value
                    val running = state.task == task && state.work == Work.RUNNING_TASK
                    listening.send(Event.TaskEnded(task, reply.takeIf { running }))
                }
            }
        }
        return "each task now takes $seconds s and then says a reply of ${reply.length} characters, until voice-task -Clear " +
            "or the end of Kestrel's process"
    }

    private fun clearTask(): String {
        synchronized(lock) {
            replacedTask?.let { graph.listening.runTask = it }
            replacedTask = null
        }
        return "each task ends at once again"
    }

    private fun startEcho(intent: Intent): String {
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es text <text>")
        val choice = when (intent.getStringExtra(EXTRA_VOICE) ?: "piper") {
            "piper" -> VoiceChoice.PIPER
            "kokoro" -> VoiceChoice.KOKORO
            else -> throw HookError("add --es voice with piper or kokoro")
        }
        if (graph.listening.sessionOpen.value) throw HookError("turn Kestrel off first, so that its microphone is free")
        synchronized(lock) {
            if (echo === Echo.Playing) throw HookError("an echo check runs; ask for its answer with --ez result true")
            echo = Echo.Playing
            graph.scope.launch {
                val ended = try {
                    Echo.Ended(echo(text, choice), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HookError) {
                    Echo.Ended(null, e.message)
                } catch (e: Exception) {
                    // Only the class, never a message (section 13.5).
                    Echo.Ended(null, "the echo check failed (${e.javaClass.simpleName})")
                }
                synchronized(lock) { echo = ended }
            }
        }
        return "playing"
    }

    private fun echoResult(): String {
        val current = synchronized(lock) { echo.also { if (it is Echo.Ended) echo = null } }
        return when (current) {
            null -> throw HookError("no echo check; start one with --es text")
            Echo.Playing -> "playing"
            is Echo.Ended -> current.answer ?: throw HookError(current.failure ?: "the echo check failed")
        }
    }

    private suspend fun echo(text: String, choice: VoiceChoice): String {
        val voice = try {
            graph.voice.synthesizer(VoiceOptions(voice = choice))
        } catch (e: ModelsNotReadyException) {
            throw HookError("the voice's model is missing; run scripts\\push-models.ps1 -Kokoro")
        }
        val hits = AtomicInteger()
        val own = AtomicInteger()
        val result = voice.use {
            graph.voice.keywordSpotter().use { spotter ->
                val player = VoicePlayer(context, voice)
                val filter = EchoFilter()
                val microphone = MicCapture(context)
                try {
                    // The spotter serves only the microphone's thread until the microphone stops.
                    microphone.start(
                        onFrame = { frame ->
                            for (hit in spotter.accept(frame)) {
                                hits.incrementAndGet()
                                if (filter.isOwnVoice(hit.keyword, SystemClock.elapsedRealtime(), player.recent())) own.incrementAndGet()
                            }
                        },
                        onError = {},
                    )
                    val spoke = player.speak(text).await()
                    // The spotter reports a keyword up to about 0.55 s after the keyword ends (plan 2A).
                    delay(1_500)
                    spoke.name.lowercase()
                } catch (e: SecurityException) {
                    throw HookError("Kestrel may not record; allow the microphone")
                } finally {
                    microphone.stop()
                    withContext(NonCancellable) { player.close() }
                }
            }
        }
        return "result=$result hits=${hits.get()} own=${own.get()}"
    }

    private sealed interface Echo {
        object Playing : Echo

        class Ended(val answer: String?, val failure: String?) : Echo
    }

    companion object {
        val COMMANDS = setOf("VOICE_COUNTS", "VOICE_TASK", "VOICE_ECHO")

        private val lock = Any()

        // The task that VOICE_TASK replaced, which VOICE_TASK --ez clear true puts back.
        private var replacedTask: ((Int, String) -> Unit)? = null
        private var echo: Echo? = null

        @Volatile
        private var lastTaskWords = 0

        private const val MAX_TASK_SECONDS = 120
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_REPLY = "reply"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_RESULT = "result"
    }
}
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace

```kotlin
        if (command in EvalHooks.COMMANDS) return EvalHooks(graph, context).run(command, intent)
```

with

```kotlin
        if (command in EvalHooks.COMMANDS) return EvalHooks(graph, context).run(command, intent)
        if (command in SessionHooks.COMMANDS) return SessionHooks(graph, context).run(command, intent)
```

In `scripts/hook.ps1`, replace

```powershell
#   voice-eval -Close                       closes that recogniser
```

with

```powershell
#   voice-eval -Close                       closes that recogniser
# The A71 session (scripts\a71-session.ps1 runs these):
#   voice-counts                            how many waits for a task and how many tasks Kestrel started in its process,
#                                           and how many words the last slow task had
#   voice-task -Seconds n -Reply 'text'     makes each task take n seconds, at most 120, and then say the reply, unless
#                                           Kestrel cancelled it, until voice-task -Clear or the end of Kestrel's process
#   voice-task -Clear                       each task ends at once again, as in sub-project 2
#   voice-echo -Text 'text' [-Voice piper|kokoro]
#                                           starts playing the text in Kestrel's voice while the real microphone feeds the
#                                           keyword spotter, and answers playing; Kestrel must be off and in front
#   voice-echo -Result                      answers playing until the check has ended, then how the reply ended, the
#                                           keyword hits and those that the echo filter took for Kestrel's own voice
```

Replace

```powershell
        'voice-state', 'voice-clip', 'voice-synth', 'voice-record', 'voice-eval',
```

with

```powershell
        'voice-state', 'voice-clip', 'voice-synth', 'voice-record', 'voice-eval', 'voice-counts', 'voice-task', 'voice-echo',
```

Replace

```powershell
    [switch]$Close,
```

with

```powershell
    [switch]$Close,
    [string]$Reply,
```

Replace

```powershell
if ($Result) { $extras += '--ez result true' }
```

with

```powershell
if ($Result) { $extras += '--ez result true' }
if ($Reply) { $extras += "--es reply $(ConvertTo-ShellText $Reply)" }
```

`voice-task -Seconds n` uses the existing `-Seconds`, which `hook.ps1` sends as `--ei seconds`, and `voice-task -Clear` the existing `-Clear` (`--ez clear true`).

- [ ] **Step 4: Run the build to verify that the test compiles**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`; `ListeningTest` keeps its 112 tests from Task 6 and passes, because the task that ends at once is the default of `runTask`.

- [ ] **Step 5: Write the failing Pester tests of the session's helpers**

Create `scripts/tests/Session.Tests.ps1`:

```powershell
# Tests of scripts\lib\Session.ps1, which scripts\a71-session.ps1 uses. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.

Describe 'The helpers of the A71 session' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'appends the time, the step and the text to the results, and creates their folder' {
        $path = Join-Path $TestDrive 'owner\session.txt'

        Add-SessionResult -Path $path -Step 'memory' -Text 'start 1: session open after 6.2 s'
        Add-SessionResult -Path $path -Step 'room' -Text '0 waits for a task'

        $lines = @(Get-Content -LiteralPath $path)
        $lines.Count | Should -Be 2
        $lines[0] | Should -Match '^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\tmemory\tstart 1: session open after 6\.2 s$'
        ($lines[1] -split "`t")[1] | Should -BeExactly 'room'
    }

    It 'reads the memory of a process from the line TOTAL PSS, in MB' {
        $dump = @('** MEMINFO in pid 4321 [dev.kestrel] **', '           TOTAL    99999    88888', '            TOTAL PSS:   245,678            TOTAL RSS:   300,000')

        Get-TotalPss -Dump $dump | Should -Be 240
    }

    It 'reads it from the row TOTAL of the table when there is no such line' {
        Get-TotalPss -Dump @('                 Pss  Private', '           TOTAL   152345   140000      123') | Should -Be 149
        Get-TotalPss -Dump @('No process found for: dev.kestrel') | Should -BeNullOrEmpty
    }

    It 'names the UID of a package as batterystats does' {
        $userId = Get-AppUserId -Dump @('Packages:', '  Package [dev.kestrel] (1a2b3c):', '    userId=10234')

        $userId | Should -Be 10234
        ConvertTo-UidLabel -UserId $userId | Should -BeExactly 'u0a234'
    }

    It 'computes the share of the battery per hour from the estimate of batterystats' {
        $dump = @(
            'Estimated power use (mAh):'
            '    Capacity: 4500, Computed drain: 120, actual drain: 0-45'
            '    UID u0a111: 40.0 ( cpu=40.0 )'
            '    UID u0a234: 12.5 ( cpu=10.1 wakelock=0.0 audio=2.4 )'
        )

        $use = Get-BatteryUse -Dump $dump -Uid 'u0a234' -Hours 0.5

        $use.MilliampHours | Should -Be 12.5
        $use.Capacity | Should -Be 4500
        $use.PercentPerHour | Should -Be 0.56
        Get-BatteryUse -Dump $dump -Uid 'u0a999' -Hours 0.5 | Should -BeNullOrEmpty
    }

    It 'keeps only the actions of the started activities, each once' {
        $log = @(
            '11-16 22:31:36.449  1000  1234 I ActivityTaskManager: START u0 {act=android.intent.action.VOICE_COMMAND flg=0x10000000 cmp=dev.kestrel/.app.assist.AssistActivity} from uid 1002'
            '11-16 22:31:37.000  1000  1234 I ActivityTaskManager: START u0 {act=android.intent.action.VOICE_COMMAND flg=0x10000000 cmp=com.example/.Other} from uid 1002'
            '11-16 22:31:38.000  1000  1234 I ActivityTaskManager: START u0 {flg=0x10200000 cmp=com.example/.Main} from uid 10123'
            '11-16 22:31:39.000  1000  1234 I ActivityTaskManager: Displayed com.example/.Main'
        )

        Get-StartedActions -Log $log | Should -Be @('android.intent.action.VOICE_COMMAND')
    }

    It 'tells from dumpsys batterystats whether Android counted the phone as on battery' {
        Test-OnBattery -Dump @('Statistics since last charge:', '  System starts: 0, currently on battery: true') | Should -BeTrue
        Test-OnBattery -Dump @('  System starts: 0, currently on battery: false') | Should -BeFalse
        Test-OnBattery -Dump @('Can''t find service: batterystats') | Should -BeNullOrEmpty
    }

    It 'splits the holders of a role' {
        Get-RoleHolders -Text 'com.google.android.googlequicksearchbox' | Should -Be @('com.google.android.googlequicksearchbox')
        Get-RoleHolders -Text 'a.b;c.d' | Should -Be @('a.b', 'c.d')
        @(Get-RoleHolders -Text '').Count | Should -Be 0
    }

    It 'counts Kestrel''s logged failures by their fixed descriptions' {
        $log = @(
            '--------- beginning of main'
            '11-16 22:31:36.449  4321  4400 W Kestrel : The microphone failed: IllegalStateException at dev.kestrel.voice.listen.MicCapture.read'
            '11-16 22:31:36.450  4321  4400 W Kestrel : at dev.kestrel.app.listen.Listening.restartMicrophone'
            '11-16 22:32:00.000  4321  4400 W Kestrel : The microphone failed: IllegalStateException at dev.kestrel.voice.listen.MicCapture.read'
            '11-16 22:33:00.000  4321  4401 E Kestrel : A background task failed: IOException at dev.kestrel.privacy.Store.write'
        )

        $failures = @(Get-FailureDescriptions -Log $log)

        $failures.Count | Should -Be 2
        $failures[0].What | Should -BeExactly 'A background task'
        $failures[0].Count | Should -Be 1
        $failures[1].What | Should -BeExactly 'The microphone'
        $failures[1].Count | Should -Be 2
    }
}
```

- [ ] **Step 6: Run the Pester tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: in both shells, the 9 tests of `Session.Tests.ps1` fail, because `scripts\lib\Session.ps1` does not exist; the others pass; the script ends with "The script tests or checks failed."

- [ ] **Step 7: Write the session's helpers**

Create `scripts/lib/Session.ps1`:

```powershell
# Helpers for scripts\a71-session.ps1 (plan 2D, design D8). They read Android's dumps and logs on this computer and keep
# only numbers, intent actions and Kestrel's own fixed descriptions, never what the owner said or other apps' text.

# Appends a line to the session's results: the time, the step and the text, separated by tabs, and creates the file's
# folder. The text must hold no private data, such as what Kestrel heard.
function Add-SessionResult([string]$Path, [string]$Step, [string]$Text) {
    $folder = Split-Path -Parent $Path
    if ($folder) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    # In the invariant culture, whose time separator is always ':'.
    $time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    [IO.File]::AppendAllText($Path, "$time`t$Step`t$Text`n", [Text.UTF8Encoding]::new($false))
}

# The memory of a process (PSS) in MB, from 'dumpsys meminfo <package>': its line 'TOTAL PSS:', or else the row 'TOTAL' of
# its table, both in kB. Returns $null when the dump has neither, as when the process does not run.
function Get-TotalPss([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match 'TOTAL PSS:\s+([\d,]+)') { return [int][math]::Round([double]($Matches[1] -replace ',', '') / 1024) }
    }
    foreach ($line in $Dump) {
        if ($line -match '^\s*TOTAL\s+([\d,]+)\s') { return [int][math]::Round([double]($Matches[1] -replace ',', '') / 1024) }
    }
    $null
}

# The user ID of a package, from 'dumpsys package <package>' ('userId=10234'), or $null.
function Get-AppUserId([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\buserId=(\d+)') { return [int]$Matches[1] }
    }
    $null
}

# How batterystats names the UID of an app of the owner's user: 'u0a234' for the user ID 10234.
function ConvertTo-UidLabel([int]$UserId) {
    "u0a$($UserId - 10000)"
}

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

# The actions of the activities that Android started, from the lines 'START u0 {act=...' of its log, each once. The rest
# of a line, such as the component of another app, is dropped.
function Get-StartedActions([string[]]$Log) {
    @($Log | ForEach-Object { if ($_ -match '\bSTART u\d+ \{act=([^\s}]+)') { $Matches[1] } } | Select-Object -Unique)
}

# Whether batterystats counted the phone as on battery, from its line 'System starts: N, currently on battery: true'
# (research r10, E5 and gap 2), or $null when the dump has no such line.
function Test-OnBattery([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match 'currently on battery:\s*(true|false)') { return $Matches[1] -eq 'true' }
    }
    $null
}

# The packages in an answer of 'cmd role get-role-holders', which separates them with ';'.
function Get-RoleHolders([string]$Text) {
    @("$Text" -split '[;\s]+' | Where-Object { $_ })
}

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

- [ ] **Step 8: Run the Pester tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 125, Failed: 0" in Windows PowerShell 5.1 and in PowerShell 7 (116 and these 9), "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 9: Write the session script**

Create `scripts/a71-session.ps1`:

```powershell
# The session on the Galaxy A71 with the owner (plan 2D, design D8; design spec, sections 5.3, 16.4 and 18.2, and spikes
# S2, S5 and S8). It takes the owner through every check of sub-project 2 that needs the A71 or the owner, in order. It
# asks before it reads the device's model and before each step, and a step that needs the owner's hands says what to do
# and waits. The steps' estimates add up to 4 hours 52 minutes, and the session takes about 5 to 5.5 hours: about 2 hours
# need the owner's hands and voice, and about 2 hours 55 minutes run without the owner. The owner can leave during the
# steps install and voice-tests (40 minutes or more), corpus (about 45 minutes) and room (an hour, which the phone spends
# in a room with ordinary sound). The steps:
#   install       installs Kestrel's debug build and copies the speech models with the Kokoro voice
#   voice-tests   the voice device tests that may run on a phone (scripts\voice-test.ps1), among them spike S8
#                 (OnnxRuntimeTogetherTest), the voices' speed (SynthesizerTest) and the app names (AppNamesTest)
#   listen-check  scripts\listen-check.ps1 -Reboot: the start paths, "Kestrel, open settings", the restore after a crash and
#                 after a reboot; the owner unlocks the phone after the reboot
#   failures      Kestrel's logged failures since the reboot, by their fixed descriptions
#   battery-rules what Android 13 and One UI do to Kestrel in the background after its accessibility service is turned
#                 off, without and with the battery restriction, and the checklist's text for Samsung
#   reboots       three restarts of the phone while Kestrel listens: the restore at boot, the exit record and the models
#   close-start   a start during a close: the tile right after "Kestrel, turn off"
#   lock-tile     the tile on the lock screen
#   unlock        the unlock paths: PIN, fingerprint from the screen off, face and Smart Lock
#   activation    the accessibility shortcut and button, the tile and the notification's actions, with Kestrel in the
#                 background, and whether Kestrel can answer "Yes?" after each start
#   assistant     Kestrel as the assistant: the assistant gesture and the Galaxy Buds gesture (spike S2)
#   earbuds       the earbud microphone with the owner's earbuds, and replies in the earbuds
#   recordings    the owner's recordings of the voice corpus: the lines of corpus\commands.txt marked for the owner; a
#                 line that an earlier session recorded is kept unless the owner records all again
#   corpus        scripts\voice-eval.ps1 on the A71 with the owner's recordings and the generated clips (spike S5)
#   voice         the owner's voice with Kestrel: "Yes?", the waits, a dictated task, interruptions, a notification sound
#                 during a reply, misses of short commands, and the echo filter with the real player and spotter
#   memory        Kestrel's memory and the times of its starts and closes (spike S5), and a cold start through the tile
#   room          an hour of ordinary room audio, in which the script counts Kestrel's reactions, with the 30-minute
#                 battery test of research r10 in its first half (spike S5)
# The results go into corpus\owner\session-<time>.txt and the recordings into corpus\owner\clips; git ignores both. Neither
# they nor the console ever hold what Kestrel heard: the hooks answer only counts, times and states. Once the first step
# runs, the phone's screen stays on while the phone is plugged in. The script puts back what it changes on the phone,
# also when a step fails or Ctrl+C stops it: Kestrel's mode Off, the hooks' slow task, the setting "Stay awake", the
# assistant role and its settings, Kestrel's battery restriction, the accessibility settings, the screen's timeout, the
# battery's state and the clips in Kestrel's files folder. It cannot put back the battery statistics, which the battery test resets, and it asks the owner
# to put back the settings in Kestrel that the owner changed and to swipe away the notification that it posts. It refuses
# every device but the A71 and kestrel-api37. -DryRun runs the session on kestrel-api37 without the owner: it answers
# every question itself, sends what the owner would say in the step voice through the voice hook, skips what needs the
# owner's hands otherwise, runs listen-check.ps1 without -Reboot, skips voice-test.ps1, records two silent clips into a
# folder of its own that it deletes, and shortens the room's hour to two minutes. -Step runs only the named steps, also as
# one text separated by commas. It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with
# exit code 1.
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

$stepNames = @('install', 'voice-tests', 'listen-check', 'failures', 'battery-rules', 'reboots', 'close-start', 'lock-tile', 'unlock',
    'activation', 'assistant', 'earbuds', 'recordings', 'corpus', 'voice', 'memory', 'room')
# pwsh -File passes '-Step corpus,room' as one text, so the names are split at commas.
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
$clipsFolder = if ($DryRun) { Join-Path $owner "dry-run-clips-$stamp" } else { Join-Path $owner 'clips' }
$corpusFile = Join-Path $PSScriptRoot '..\corpus\commands.txt'
$remote = Get-EvalFolder
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$assistantRole = 'android.app.role.ASSISTANT'
$earbudsChanged = $false
$gradleUsed = $false
$prepared = $false

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

# Waits until $Condition is true for Kestrel's state, which it gets as its argument. Returns the seconds that it waited,
# or -1 after the timeout.
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

function Format-Wait([double]$Seconds) { if ($Seconds -lt 0) { 'failed' } else { "ok after $Seconds s" } }

# How many waits for a task and how many tasks Kestrel has started in its process, and how many words the last slow
# task of the hooks had.
function Get-Counts {
    $text = Invoke-Hook voice-counts
    if ($text -notmatch '^waits=(\d+) tasks=(\d+) lastTaskWords=(\d+)$') { throw 'Unexpected answer of voice-counts.' }
    [pscustomobject]@{ Waits = [int]$Matches[1]; Tasks = [int]$Matches[2]; LastTaskWords = [int]$Matches[3] }
}

# Waits until the count ('Waits' or 'Tasks') is above $Before. Returns the seconds that it waited, or -1.
function Wait-Count([string]$Name, [int]$Before, [double]$TimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-Counts).$Name -gt $Before) { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
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
    # "Kestrel, stop" ends a reply, cancels a task and ends a wait for a task (design spec, section 8.4); a plain "stop"
    # while nothing runs would be an instant command.
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

# After a start, the owner says "Kestrel", and the script records whether Kestrel waited for a task and whether the owner
# heard "Yes?", because Android 13 may refuse the replies of a service that started in the background (final review of
# plan 2C-1, item 3).
function Get-YesAfterStart {
    $counts = Get-Counts
    if ((Request-Owner 'Say "Kestrel". Press Enter right after you said it, or s to skip this.') -eq 'skip') { return '"Yes?" not tried' }
    $asked = Wait-Count Waits $counts.Waits 10
    $heard = Read-OwnerAnswer 'Did you hear "Yes?"? Type y or n.' 'n'
    "after ""Kestrel"", Kestrel waited for a task: $(Format-Wait $asked); ""Yes?"" was heard: $heard"
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
# records only while the screen is on and the phone unlocked, and Android silences a recording of Kestrel's settings once
# they are no longer in front. Registered first, so that they come last.
function Initialize-Session {
    if ($script:prepared) { return }
    $script:prepared = $true
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" $null { Stop-Kestrel } | Out-Null
    Register-Restore "the hooks' tasks" "Run: scripts\hook.ps1 -Serial $Serial voice-task -Clear" $null {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
    } | Out-Null
    Register-Restore "the clips in Kestrel's folder files/eval" "Run: & '$adb' -s $Serial shell 'rm -f $remote/*.wav'" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "rm -f $remote/*.wav" | Out-Null
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
    Request-Owner 'The phone''s screen now stays on while the phone is plugged in. Keep the phone unlocked during the session, except when a step asks you to lock it. Press Enter to go on.' 'go' | Out-Null
}

function Invoke-InstallStep {
    # On Android 13 an update ends Kestrel's process with the reason of the owner's stop, so Kestrel would stay off after it.
    try { Stop-Kestrel } catch { Write-Host 'Kestrel did not answer, so it is probably not installed yet.' }
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 failed.' }
    & "$PSScriptRoot\push-models.ps1" -Serial $Serial -Kokoro
    Add-Result install 'Kestrel installed, and the speech models copied with the Kokoro voice'
    # On Android 13 an update ends Kestrel with the reason of the owner's stop, so the restore keeps it off; the session
    # starts it itself (final review of plan 2C-4, "For plan 2D", item 7).
    $mode = (Get-VoiceState).mode
    Start-Kestrel
    Add-Result install "after the update Kestrel's mode was $mode, and it started listening when the session started it"
    Stop-Kestrel
}

function Invoke-VoiceTestStep {
    if ($DryRun) {
        Add-Result voice-tests 'skipped in a dry run; the plan runs scripts\voice-test.ps1 itself'
        return
    }
    $script:gradleUsed = $true
    # The tests log only measurements, never what was said, and voice-test.ps1 prints those lines.
    $lines = @(& "$PSScriptRoot\voice-test.ps1" -Serial $Serial *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    foreach ($line in $lines | Where-Object { $_ -match '^Voice tests: |KestrelVoiceTest' }) {
        Add-Result voice-tests ($line -replace '^.*KestrelVoiceTest\s*:\s*', '')
    }
    if ($code -ne 0) { Add-Result voice-tests "ended with exit code $code; the results are in app\build\outputs\androidTest-results\connected" }
    # connected-test.ps1 ends with 'am force-stop', which leaves a record of the owner's stop, so a restore keeps Kestrel
    # off; the session starts it itself (final review of plan 2C-4, "For plan 2D", item 9).
    $mode = (Get-VoiceState).mode
    Start-Kestrel
    Add-Result voice-tests "after the tests Kestrel's mode was $mode, and it started listening when the session started it"
    Stop-Kestrel
}

function Invoke-ListenCheckStep {
    # The notification check reads One UI's shade with uiautomator dump, which also holds the owner's notifications;
    # listen-check.ps1 deletes the dump after each read and never prints it.
    if (-not $DryRun -and (Request-Owner 'The check restarts the phone, and it reads the notification shade, whose text it deletes at once and never shows. Unlock the phone whenever the script asks, also after the restart. Press Enter to start, or s to skip.') -eq 'skip') {
        Add-Result listen-check 'skipped'
        return
    }
    $lines = @(& "$PSScriptRoot\listen-check.ps1" -Serial $Serial -Reboot:(-not $DryRun) *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    # Each check's line holds its name, ok, failed or skipped, and seconds.
    foreach ($line in $lines | Where-Object { $_ -match '^[a-z]+\s+(ok|failed|skipped)\b' }) { Add-Result listen-check $line }
    if ($code -ne 0) { Add-Result listen-check "ended with exit code $code" }
    # When Kestrel's accessibility service was off before the check, its notice about the service stays in the shade
    # (final review of plan 2C-4, N21).
    Request-Owner 'If Kestrel''s notice that its accessibility service is off is in the notification shade, swipe it away. Press Enter after it.' 'go' | Out-Null
}

function Invoke-FailuresStep {
    $failures = @(Get-FailureDescriptions -Log @(Invoke-Checked 'logcat -d -s Kestrel:W'))
    if ($failures.Count -eq 0) {
        Add-Result failures 'Kestrel logged no failure in the device''s log'
        return
    }
    foreach ($failure in $failures) { Add-Result failures "$($failure.Count) x ""$($failure.What) failed""" }
    # The log shows that the microphone failed, not whether listening came back a second later; ListeningTest checks that.
    if ($failures.What -contains 'The microphone') {
        Add-Result failures 'whether listening came back after each failure of the microphone is not in the log'
    }
}

function Invoke-CloseStartStep {
    Start-Kestrel
    $before = Get-KestrelRecording
    if ((Request-Owner 'Kestrel listens. Check that its tile is in Quick Settings. Say "Kestrel, turn off", and right after it, tap Kestrel''s tile. Press Enter once you tapped it, or s to skip.') -eq 'skip') {
        Add-Result close-start 'skipped'
        return
    }
    # The close comes first: the old session closes while the new start waits for it.
    $closing = Wait-State { param($State) $State.mode -ne 'listening' -or $State.session -eq 'closed' } 15
    if ($closing -lt 0) {
        Add-Result close-start 'failed: Kestrel did not turn off, so it did not hear "Kestrel, turn off"'
        return
    }
    $on = Wait-Mode listening 120
    if ($on -lt 0) {
        $mode = (Get-VoiceState).mode
        Add-Result close-start "failed: Kestrel did not listen again within 120 s; its mode is $mode"
        return
    }
    $problem = Get-RecordingProblem $before
    $listening = if ($problem) { "failed: listening again after the tap, but $problem" } else { 'ok: listening again after the tap, not silenced' }
    Add-Result close-start "$listening; $(Get-YesAfterStart)"
}

function Invoke-LockTileStep {
    Stop-Kestrel
    if ((Request-Owner 'Kestrel is off. Lock the phone with the side key, wake it to the lock screen, swipe down to Quick Settings, and tap Kestrel''s tile without unlocking. Press Enter afterwards, or s to skip.') -eq 'skip') {
        Add-Result lock-tile 'skipped'
        return
    }
    Start-Sleep -Seconds 5
    $state = Get-VoiceState
    $asked = Read-OwnerAnswer 'Did the phone ask you to unlock it before anything happened? Type y or n.' 'n'
    Add-Result lock-tile "after a tap on the lock screen, Kestrel's mode is $(if ($state) { $state.mode } else { 'unknown' }); the phone asked to unlock first: $asked"
    Request-Owner 'Unlock the phone. Press Enter after it.' 'go' | Out-Null
    Stop-Kestrel
}

function Invoke-UnlockStep {
    foreach ($method in 'a PIN', 'a fingerprint, from the screen off', 'the face', 'Smart Lock') {
        Start-Kestrel
        $before = Get-KestrelRecording
        if ((Request-Owner "Kestrel listens. Turn the screen off with the side key, wait 5 s, and unlock the phone with $method. Press Enter after the unlock, or s if the phone does not use it.") -eq 'skip') {
            Add-Result unlock "${method}: skipped"
            continue
        }
        $on = Wait-State { param($State) $State.mode -eq 'listening' -and $State.screenOn -eq 'true' -and $State.microphone -eq 'true' } 20
        if ($on -lt 0) {
            Add-Result unlock "${method}: failed: Kestrel did not record with the screen on within 20 s"
            continue
        }
        $problem = Get-RecordingProblem $before
        Add-Result unlock $(if ($problem) { "${method}: failed: $problem" } else { "${method}: ok: Kestrel records again, not silenced" })
    }
    Stop-Kestrel
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

function Invoke-ActivationStep {
    $restore = Register-AccessibilityRestore
    try {
        # Kestrel's service must be on for the shortcut and the button; the owner's other services stay on. The list is
        # quoted for the device's shell.
        $services = @(Get-OtherServices $restore.Data) + $service
        Invoke-Checked "settings put secure enabled_accessibility_services '$($services -join ':')'" | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
        Invoke-Checked "settings put secure accessibility_shortcut_target_service $service" | Out-Null
        Invoke-Checked "settings put secure accessibility_button_targets $service" | Out-Null
        $methods = [ordered]@{
            shortcut = 'Press and hold both volume keys for 3 s'
            button = 'Tap the accessibility button in the navigation bar'
            tile = 'Swipe down to Quick Settings and tap Kestrel''s tile; add the tile first if it is not there'
        }
        foreach ($name in $methods.Keys) {
            Stop-Kestrel
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            $before = Get-KestrelRecording
            if ((Request-Owner "Kestrel is off and in the background. $($methods[$name]). Press Enter after it, or s to skip.") -eq 'skip') {
                Add-Result activation "${name}: skipped"
                continue
            }
            $on = Wait-Mode listening 120
            $problem = if ($on -ge 0) { Get-RecordingProblem $before }
            $started = if ($on -lt 0) { 'did not start listening within 120 s' } elseif ($problem) { "started after $on s, but $problem" } else { "started after $on s, not silenced" }
            $yes = if ($on -ge 0) { Get-YesAfterStart } else { '"Yes?" not tried' }
            Request-Owner "Now do it again, which should pause Kestrel: $($methods[$name]). Press Enter after it." 'go' | Out-Null
            $paused = Wait-Mode paused 15
            Add-Result activation "${name}: $started; $yes; the second time paused it: $(Format-Wait $paused)"
        }
        Start-Kestrel
        if ((Request-Owner 'Kestrel listens. Open another app, such as Settings. Pull down the notifications and tap Pause in Kestrel''s notification. Press Enter after it, or s to skip.') -eq 'skip') {
            Add-Result activation 'notification: skipped'
        } else {
            $paused = Wait-Mode paused 15
            Request-Owner 'Now tap Resume in the notification. Press Enter after it.' 'go' | Out-Null
            $resumed = Wait-Mode listening 15
            Request-Owner 'Now tap Stop in the notification. Press Enter after it.' 'go' | Out-Null
            $stopped = Wait-Mode off 30
            Add-Result activation "notification with another app in front: Pause $(Format-Wait $paused), Resume $(Format-Wait $resumed), Stop $(Format-Wait $stopped)"
        }
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

# After a start that the count of waits showed ($Waited seconds, or -1), whether Kestrel's new recording is silenced.
function Get-StartRecording([double]$Waited, [string[]]$Before) {
    if ($Waited -lt 0) { return 'not checked' }
    $problem = Get-RecordingProblem $Before
    if ($problem) { $problem } else { 'not silenced' }
}

function Get-AssistantHolders { Get-RoleHolders ((Invoke-Checked "cmd role get-role-holders $assistantRole") -join ' ') }

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
    try {
        Invoke-Checked "cmd role add-role-holder $assistantRole dev.kestrel" | Out-Null
        if (@(Get-AssistantHolders) -notcontains 'dev.kestrel') {
            Add-Result assistant 'failed: Android did not make Kestrel the assistant'
            return
        }
        Stop-Kestrel
        $counts = Get-Counts
        $before = Get-KestrelRecording
        if ((Request-Owner 'Kestrel is the assistant now, and off. Open another app, such as Settings. Then long-press the home button, or swipe up from a bottom corner, whichever starts the assistant. Press Enter after it, or s to skip.') -eq 'skip') {
            Add-Result assistant 'the assistant gesture: skipped'
        } else {
            # Kestrel waits for a task after the gesture, as after "Kestrel" on its own; the count rises before "Yes?" is
            # spoken, so the recording and "Yes?" are checked too (spikes S1 and S2, final review of plan 2C-1, item 3).
            $waited = Wait-Count Waits $counts.Waits 120
            $recording = Get-StartRecording $waited $before
            $yes = Read-OwnerAnswer 'Did you hear "Yes?"? Type y or n.' 'n'
            $front = Read-OwnerAnswer 'Did the app in front stay as it was, without a window of Kestrel over it? Type y or n.' 'n'
            Add-Result assistant "the assistant gesture: Kestrel listened and waited for a task: $(Format-Wait $waited); its recording: $recording; ""Yes?"" was heard: $yes; the app in front stayed: $front"
        }
        Stop-Kestrel
        $since = ((Invoke-Checked "date '+%m-%d %H:%M:%S.000'") -join '').Trim()
        $counts = Get-Counts
        $before = Get-KestrelRecording
        if ((Request-Owner 'Connect the Galaxy Buds. Kestrel is off. Press and hold the touchpad of one earbud, the gesture that starts the assistant. If Android offers a choice of apps, pick Kestrel with "Just once". Press Enter after it, or s to skip.') -eq 'skip') {
            Add-Result assistant 'the Galaxy Buds: skipped'
        } else {
            $waited = Wait-Count Waits $counts.Waits 120
            $recording = Get-StartRecording $waited $before
            $yes = Read-OwnerAnswer 'Did you hear "Yes?"? Type y or n.' 'n'
            # Only the intents' actions are kept, not the components of other apps.
            $started = @(Get-StartedActions -Log @(Invoke-Checked "logcat -d -T '$since' -s ActivityTaskManager:I"))
            $chooser = Read-OwnerAnswer 'Did Android show a choice of apps? Type y or n.' 'n'
            $actions = if ($started) { $started -join ', ' } else { 'no activity' }
            Add-Result assistant "the Galaxy Buds: the gesture started $actions; Kestrel waited for a task: $(Format-Wait $waited); its recording: $recording; ""Yes?"" was heard: $yes; a choice of apps: $chooser"
        }
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

function Invoke-EarbudStep {
    Stop-Kestrel
    if ((Request-Owner 'Connect the Galaxy Buds. Open Kestrel''s settings, page "Listening and activation", and turn on "Use the earbud microphone". Press Enter after it, or s to skip.') -eq 'skip') {
        Add-Result earbuds 'skipped'
        return
    }
    $script:earbudsChanged = $true
    Start-Kestrel
    $counts = Get-Counts
    Request-Owner 'Kestrel listens. Leave the phone unlocked, with its screen on, in another room, or under a pillow, and say "Kestrel" into the earbuds. Press Enter after you said it.' 'go' | Out-Null
    $heard = Wait-Count Waits $counts.Waits 15
    $inEarbuds = Read-OwnerAnswer 'Did you hear "Yes?" in the earbuds? Type y or n.' 'n'
    $callSound = Read-OwnerAnswer 'While Kestrel listened, did music or Kestrel''s voice sound like a phone call? Type y or n.' 'n'
    Add-Result earbuds "Kestrel heard its name through the earbuds: $(Format-Wait $heard); ""Yes?"" in the earbuds: $inEarbuds; the sound of a call: $callSound"
    $counts = Get-Counts
    Request-Owner 'Put the earbuds into their case, so that they disconnect. Then take the phone and say "Kestrel" to it. Press Enter after you said it.' 'go' | Out-Null
    $heard = Wait-Count Waits $counts.Waits 15
    Add-Result earbuds "after the earbuds went away, Kestrel heard its name through the phone: $(Format-Wait $heard)"
    # What this step cannot tell (Task 6): whether the earbuds used LE Audio or classic Bluetooth, and what a call that
    # takes the audio over does, because the A71 has no SIM card.
    Add-Result earbuds 'not checked: whether the earbuds used LE Audio, so the LE Audio path stays unchecked, and a call that takes the audio over'
    Stop-Kestrel
    Request-Owner 'Turn "Use the earbud microphone" off again in Kestrel''s settings, unless you want to keep it. Press Enter after it.' 'go' | Out-Null
}

# Records one line of the owner into the clip $File on this computer. The recording starts before "Speak now", so that
# the first word is not cut, and the screen is woken and Kestrel's settings brought to the front first, because Android
# silences a recording otherwise. Returns the hook's answer.
function Invoke-OwnerRecording([string]$File, [int]$Seconds) {
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $started = Invoke-Hook voice-record @{ File = 'owner'; Seconds = $Seconds }
    if ($started -ne 'recording') { throw "voice-record did not start: $started" }
    Write-Host "Speak now ($Seconds s)"
    Start-Sleep -Seconds $Seconds
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $reply = Invoke-Hook voice-record @{ Result = $true }
        if ($reply -ne 'recording') { break }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt 10)
    Invoke-AdbCopy -Serial $Serial -Direction pull -From "$remote/owner.wav" -To $File
    Invoke-Checked "rm -f $remote/owner.wav" | Out-Null
    $reply
}

function Invoke-RecordingStep {
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Owner })
    if ($DryRun) { $rows = @($rows | Select-Object -First 2) }
    New-Item -ItemType Directory -Force -Path $clipsFolder | Out-Null
    # The lines that an earlier, interrupted session recorded stay, unless the owner records all of them again.
    $done = @($rows | Where-Object { Test-Path -LiteralPath (Join-Path $clipsFolder "$($_.Id).wav") })
    if ($done) {
        $again = Read-OwnerAnswer "$($done.Count) of the $($rows.Count) lines are recorded already. Type a to record all of them again, or press Enter to record only the others." ''
        if ($again -ne 'a') { $rows = @($rows | Where-Object { $_ -notin $done }) }
    }
    Stop-Kestrel
    if ((Request-Owner "Next come $($rows.Count) lines to say, about 30 minutes. Sit in a quiet room and hold the phone as you would to talk to it; the script keeps Kestrel's settings in front. For each line, press Enter, wait for ""Speak now"", and say the line once, as you would say it to Kestrel. Press Enter to begin, or s to skip the recordings." 'go') -eq 'skip') {
        Add-Result recordings 'skipped'
        return
    }
    $recorded = 0
    for ($i = 0; $i -lt $rows.Count; $i++) {
        $row = $rows[$i]
        $answer = Read-OwnerAnswer "[$($i + 1)/$($rows.Count)] Say: $($row.Text)    (Enter to record, s to skip the line, q to stop)" ''
        if ($answer -eq 'q') { break }
        if ($answer -eq 's') { continue }
        $seconds = if ($DryRun) { 1 } else { Get-ClipSeconds -Text $row.Text }
        $reply = Invoke-OwnerRecording -File (Join-Path $clipsFolder "$($row.Id).wav") -Seconds $seconds
        $quiet = $reply -match 'silenced=true' -or ($reply -match 'loudest (\d+) %' -and [int]$Matches[1] -lt 3)
        if ($quiet -and -not $DryRun) {
            Write-Host 'That recording was silenced or too quiet. Check that the screen is on, the phone unlocked and Kestrel''s settings in front, and say the line again.'
            $i--
            continue
        }
        if ((Read-OwnerAnswer 'Enter for the next line, or r to record this line again.' '') -eq 'r') {
            $i--
            continue
        }
        $recorded++
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Add-Result recordings "$recorded of $($rows.Count) lines recorded into $((Resolve-Path -LiteralPath $clipsFolder).Path)"
}

function Invoke-CorpusStep {
    Stop-Kestrel
    # The language that the owner chose, if any, and the list of languages, which a phone has also when the owner never
    # changed the language after the setup.
    $locale = ((Invoke-Checked 'getprop persist.sys.locale') -join '').Trim()
    $locales = ((Invoke-Checked 'settings get system system_locales') -join '').Trim()
    Add-Result corpus "the phone's language: '$locale'; its list of languages: '$locales'"
    # In a dry run, the generated clips of Piper only; the owner's recordings of a dry run are silent and go at the end.
    # The owner's recordings also run without the boost words, which shows their effect with the owner's voice (final
    # review of plan 2C-3, item 3).
    $runs = [System.Collections.Generic.List[hashtable]]::new()
    if (-not $DryRun) {
        $runs.Add(@{ Corpus = 'owner' })
        $runs.Add(@{ Corpus = 'owner'; NoBoost = $true })
    }
    $runs.Add(@{ Corpus = 'generated'; Voice = 'piper' })
    if (-not $DryRun) { $runs.Add(@{ Corpus = 'generated'; Voice = 'kokoro' }) }
    # In front, Kestrel's process keeps the evaluation's recogniser, about 1 GB, also when the phone runs short of memory.
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    try {
        foreach ($run in $runs) {
            $name = "-Corpus $($run.Corpus)" + $(if ($run.Voice) { " -Voice $($run.Voice)" }) + $(if ($run.NoBoost) { ' -NoBoost' })
            try {
                # voice-eval.ps1 prints only totals for the owner's recordings, and no transcript without -Show.
                $lines = @(& "$PSScriptRoot\voice-eval.ps1" -Serial $Serial @run *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
                foreach ($line in $lines | Where-Object { $_ -match '^(Boost words|Clips|Requests|Transcription|Decoded|Kestrel''s memory)' }) {
                    Add-Result corpus "${name}: $line"
                }
            } catch {
                Add-Result corpus "${name}: failed: $($_.Exception.Message)"
            }
        }
    } finally {
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

# Asks the owner $Tries times to say something, and counts how often Kestrel reacted within $TimeoutSeconds after Enter.
# $Prepare brings Kestrel into the state that each try needs. $Reacted gets the counts from before the try and Kestrel's
# state, and returns whether Kestrel reacted. A dry run makes one try, in which it sends $DryRunHeard (see Send-Heard)
# in place of the owner.
function Measure-Tries(
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
    $times = @()
    for ($try = 1; $try -le $Tries; $try++) {
        & $Prepare
        $counts = Get-Counts
        if ((Request-Owner "$Say  (try $try of ${Tries}: press Enter right after you said it, or s to skip the rest)" 'go') -eq 'skip') { break }
        if ($DryRun) { Send-Heard $DryRunHeard }
        $done++
        $watch = [Diagnostics.Stopwatch]::StartNew()
        do {
            $state = Get-VoiceState
            if ($state -and (& $Reacted $counts $state)) {
                $hits++
                $times += [math]::Round($watch.Elapsed.TotalSeconds, 1)
                break
            }
            Start-Sleep -Milliseconds 250
        } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    }
    $text = if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" }
    # Each poll takes two hook calls, so a time can be up to about 1.5 s late (review E of plan 2D, M6).
    if ($times) { $text += "; seconds after Enter, each up to 1.5 s late from the polling: $($times -join ', ')" }
    Add-Result voice "${Name}: $text"
}

# Design D3 as the controller changed it, and review A, M2: on a "may duck" loss of the audio focus, such as a
# notification sound, Kestrel's own player lowers its voice, and Kestrel keeps speaking. The hooks' slow task with the
# long reply must be set. The script posts a notification of the shell, whose sound plays over the reply, and the owner
# swipes it away.
function Invoke-NotificationDuckCheck {
    Wait-Quiet
    if ((Request-Owner 'Say "Kestrel, read me the news". Once Kestrel reads its long reply, press Enter: the script then posts a notification, whose sound should play over the reply. Or press s to skip.') -eq 'skip') {
        Add-Result voice 'a notification sound during a reply: skipped'
        return
    }
    if ((Wait-State { param($State) $State.speaking -eq 'true' } 15) -lt 0) {
        Add-Result voice 'a notification sound during a reply: Kestrel did not speak'
        return
    }
    Invoke-Checked "cmd notification post -t 'Kestrel session' kestrel-session 'A notification of the A71 session'" | Out-Null
    Start-Sleep -Seconds 3
    $state = Get-VoiceState
    $kept = [bool]($state -and $state.speaking -eq 'true')
    $heard = Read-OwnerAnswer 'Did a notification sound play, and did Kestrel''s voice get quieter under it and then go on? Type y, n, or none if no sound played.' 'none'
    Add-Result voice "a notification sound during a reply: Kestrel still spoke 3 s later: $kept; quieter and on, by ear: $heard"
    Request-Owner 'Swipe the notification "Kestrel session" away. Press Enter after it.' 'go' | Out-Null
}

# Plays a text in Kestrel's voice through the hook voice-echo, which runs in the background, and returns its answer.
function Invoke-Echo([string]$Text, [string]$Voice) {
    $reply = Invoke-Hook voice-echo @{ Text = $Text; Voice = $Voice }
    if ($reply -ne 'playing') { return $reply }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Seconds 1
        $reply = Invoke-Hook voice-echo @{ Result = $true }
    } while ($reply -eq 'playing' -and $watch.Elapsed.TotalSeconds -lt 180)
    $reply
}

function Invoke-VoiceStep {
    $quiet = { Wait-Quiet }
    $intro = 'Next you talk to Kestrel through the phone''s own speaker and microphone, without earbuds, in a quiet room, for about 30 minutes. Each time, say what the script shows, as you would say it to Kestrel, and press Enter right after it. Press Enter to begin, or s to skip.'
    if ((Request-Owner $intro 'go') -eq 'skip') {
        Add-Result voice 'talking to Kestrel: skipped'
    } else {
        Measure-Tries '"Kestrel", until "Yes?"' 'Say "Kestrel".' 5 $quiet { param($Counts, $State) $State.speaking -eq 'true' -or (Get-Counts).Waits -gt $Counts.Waits } -DryRunHeard '+Kestrel'
        Measure-Tries '"Hey Kestrel"' 'Say "Hey Kestrel".' 5 $quiet { param($Counts, $State) $State -and (Get-Counts).Waits -gt $Counts.Waits } -DryRunHeard '+Hey Kestrel.'
        # The wait for the task ends 8 s after "Yes?" without an answer (design spec, section 8.3).
        Wait-Quiet
        $counts = Get-Counts
        if ((Request-Owner 'Say "Kestrel", and then nothing. Press Enter right after you said it.' 'go') -ne 'skip') {
            if ($DryRun) { Send-Heard '+Kestrel' }
            $asked = Wait-Count Waits $counts.Waits 10
            $ended = if ($asked -ge 0) { Wait-State { param($State) $State.work -eq 'idle' } 20 } else { -1 }
            Add-Result voice "the wait after ""Yes?"": $(if ($asked -lt 0) { 'Kestrel did not answer' } else { "ended $ended s after Kestrel began to wait" })"
        }
        Measure-Tries 'a quick answer to "Yes?"' 'Say "Kestrel", and as soon as Kestrel starts to say "Yes?", say "open the weather".' 3 $quiet { param($Counts, $State) $State -and (Get-Counts).Tasks -gt $Counts.Tasks } 15 -DryRunHeard '+Kestrel', 'open the weather'
        # A long answer keeps the wait open until it ends, and Kestrel holds an answer 1.85 s after its end; a part after
        # a longer pause would be a task of its own, and the rest would be lost (design spec, section 8.3). Each task now
        # ends after a second, and the hooks count the words of the last task, never the words themselves.
        $long = 'send a message to Ana saying that I will be late because the bus did not come and I will take a taxi'
        $longWords = @($long -split '\s+').Count
        $whole = 0
        $short = 0
        $none = 0
        $tries = 0
        $delays = @()
        Invoke-Hook voice-task @{ Seconds = 1; Reply = 'Done.' } | Out-Null
        try {
            for ($try = 1; $try -le $(if ($DryRun) { 1 } else { 3 }); $try++) {
                Wait-Quiet
                $counts = Get-Counts
                if ((Request-Owner "Say ""Kestrel"", wait for ""Yes?"", and then say slowly, with the pauses that you would make: ""$long"". Press Enter right after the last word (try $try of 3), or s to skip the rest." 'go') -eq 'skip') { break }
                if ($DryRun) { Send-Heard '+Kestrel', $long }
                $tries++
                # From the end of the speech: the hold of 1.85 s and the transcription.
                $ran = Wait-Count Tasks $counts.Tasks 15
                if ($ran -lt 0) {
                    $none++
                    continue
                }
                $delays += $ran
                if ((Get-Counts).LastTaskWords -ge $longWords - 3) { $whole++ } else { $short++ }
            }
        } finally {
            Invoke-Hook voice-task @{ Clear = $true } | Out-Null
        }
        $delayText = if ($delays) { "; seconds from Enter to the task, each up to 1.5 s late from the polling: $($delays -join ', ')" } else { '' }
        Add-Result voice "a long dictated task of $longWords words: $whole of $tries tries became one task with at least $($longWords - 3) of its words, $short were cut short, and $none started no task within 15 s$delayText"
        # Each task now takes a second and then reads a long reply without any of Kestrel's keywords, which the owner
        # interrupts.
        $story = 'Here is a long reply, so that you can interrupt it. ' + ('The news of the day comes one sentence after another, at an even pace. ' * 6)
        Invoke-Hook voice-task @{ Seconds = 1; Reply = $story } | Out-Null
        try {
            foreach ($pause in '0.5', '1', '1.5', '2') {
                Measure-Tries "an interruption with a pause of $pause s" "Say ""Kestrel, read me the news"". While Kestrel reads its long reply, say ""Kestrel"", wait about $pause s, and say ""open the weather""." 2 $quiet { param($Counts, $State) $State -and (Get-Counts).Tasks -ge $Counts.Tasks + 2 } 30 -DryRunHeard '+Kestrel, read me the news', '+Kestrel', 'open the weather'
            }
            Invoke-NotificationDuckCheck
            Invoke-Hook voice-task @{ Seconds = 15; Reply = 'The task is done.' } | Out-Null
            Measure-Tries '"stop" while a task runs and Kestrel is silent' 'Say "Kestrel, open Viber", wait about 3 s, and say "stop".' 3 $quiet { param($Counts, $State) $State.work -eq 'idle' -and (Get-Counts).Tasks -gt $Counts.Tasks } 5 -DryRunHeard '+Kestrel, open Viber', 'Stop.'
        } finally {
            Invoke-Hook voice-task @{ Clear = $true } | Out-Null
        }
        Measure-Tries '"Stop listening"' 'Say "Stop listening".' 5 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'paused' } 8 -DryRunHeard 'Stop listening.'
        $paused = {
            Wait-Quiet
            Invoke-Hook voice @{ Send = 'pause' } | Out-Null
            Wait-Mode paused 10 | Out-Null
        }
        Measure-Tries '"Kestrel, wake up" while paused' 'Say "Kestrel, wake up".' 5 $paused { param($Counts, $State) $Counts -and $State.mode -eq 'listening' } 8 -DryRunHeard '+Kestrel, wake up.'
        Measure-Tries '"Kestrel, turn off"' 'Say "Kestrel, turn off".' 3 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'off' } 10 -DryRunHeard '+Kestrel, turn off.'
        Measure-Tries '"Stop Kestrel"' 'Say "Stop Kestrel".' 3 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'off' } 10 -DryRunHeard 'Stop Kestrel.'
    }
    # The echo filter with the real player and spotter: Kestrel speaks through the speaker, and its microphone hears it.
    Stop-Kestrel
    if ((Request-Owner 'Next Kestrel speaks a few sentences to itself. Keep quiet for about three minutes. Press Enter to begin, or s to skip.' 'go') -eq 'skip') {
        Add-Result voice 'the echo filter: skipped'
        return
    }
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $voices = if ($DryRun) { @('piper') } else { @('piper', 'kokoro') }
    $texts = @('Should I stop the timer?')
    if (-not $DryRun) { $texts = @("It's top-rated.", 'That movie was unstoppable.', 'I cancelled the alarm at 06:45, and the code is 4 7 1 9.') + $texts }
    foreach ($voice in $voices) {
        foreach ($text in $texts) {
            # One failure costs one line, not the rest of the step.
            $answer = try { Invoke-Echo $text $voice } catch { "failed: $($_.Exception.Message)" }
            Add-Result voice "the echo filter with $voice, ""$text"": $answer"
        }
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Get-KestrelPss { Get-TotalPss -Dump @(Invoke-Checked 'dumpsys meminfo dev.kestrel') }

function Get-KestrelProcess { ((Invoke-Checked 'pidof dev.kestrel || true') -join '').Trim() }

function Invoke-MemoryStep {
    Stop-Kestrel
    # In front, Android lets the hook start Kestrel's microphone service.
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    for ($cycle = 1; $cycle -le 3; $cycle++) {
        $idle = Get-KestrelPss
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
        $opened = Wait-Mode listening 120
        $listening = Get-KestrelPss
        Invoke-Hook voice @{ Send = 'off' } | Out-Null
        $closed = Wait-Mode off 60
        $after = Get-KestrelPss
        Add-Result memory "start ${cycle}: the session opened $(Format-Wait $opened); PSS $idle MB before, $listening MB while listening and $after MB after the close, which ended $(Format-Wait $closed)"
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # A cold start: the tile starts the service in a new process, which must reach startForeground() in time.
    $restore = Register-AccessibilityRestore
    try {
        if ((Request-Owner 'Next comes a cold start through the tile: the script turns Kestrel''s accessibility service off and ends Kestrel''s process, and then you tap the tile. Check that Kestrel''s tile is in Quick Settings. Press Enter to go ahead, or s to skip.') -eq 'skip') {
            Add-Result memory 'a cold start through the tile: skipped'
            return
        }
        $others = @(Get-OtherServices $restore.Data)
        $command = if ($others) { "settings put secure enabled_accessibility_services '$($others -join ':')'" } else { 'settings delete secure enabled_accessibility_services' }
        Invoke-Checked $command | Out-Null
        # Without its accessibility service, Kestrel's process in the background may be ended.
        Start-Sleep -Seconds 3
        Invoke-Checked 'am kill dev.kestrel' | Out-Null
        Start-Sleep -Seconds 2
        if (Get-KestrelProcess) {
            Add-Result memory 'a cold start through the tile: failed: Kestrel''s process did not end'
            return
        }
        Request-Owner 'Now swipe down to Quick Settings and tap Kestrel''s tile. Press Enter after it.' 'go' | Out-Null
        $on = Wait-Mode listening 120
        $process = Get-KestrelProcess
        # A service that misses Android's deadline for startForeground() ends the process.
        Start-Sleep -Seconds 15
        $same = $process -and (Get-KestrelProcess) -eq $process -and (Get-VoiceState).mode -eq 'listening'
        Add-Result memory "a cold start through the tile: listening $(Format-Wait $on); the same process still listened 15 s later: $same"
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

function Invoke-RoomStep {
    $minutes = if ($DryRun) { 2 } else { 60 }
    $batteryMinutes = if ($DryRun) { 1 } else { 30 }
    if ((Request-Owner "Next comes the room: for $minutes minutes the phone lies, unlocked and with its screen on, in a room with ordinary sound, such as talk or television, and nobody speaks to it. Kestrel listens, and the script counts how often it reacts. In the first $batteryMinutes minutes the script also measures Kestrel's battery use, which resets the phone's battery statistics. Leave the USB cable connected. Press Enter to start, or s to skip." 'go') -eq 'skip') {
        Add-Result room 'skipped'
        return
    }
    $uid = ConvertTo-UidLabel -UserId (Get-AppUserId -Dump @(Invoke-Checked 'dumpsys package dev.kestrel'))
    $timeout = ((Invoke-Checked 'settings get system screen_off_timeout') -join '').Trim()
    $timeoutRestore = Register-Restore "the screen's timeout" "Run: & '$adb' -s $Serial shell settings put system screen_off_timeout $timeout" $timeout {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -eq 'null') { 'settings delete system screen_off_timeout' } else { "settings put system screen_off_timeout $Data" }
        Invoke-Checked $command | Out-Null
    }
    $batteryRestore = Register-Restore "the battery's state" "Run: & '$adb' -s $Serial shell dumpsys battery reset" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked 'dumpsys battery reset' | Out-Null
    }
    try {
        # Kestrel records only while the screen is on, and "Stay awake" ends once Android counts the phone as on battery.
        Invoke-Checked "settings put system screen_off_timeout $(($minutes + 10) * 60000)" | Out-Null
        Start-Kestrel
        # Research r10: first on battery, because Android resets the statistics itself when a phone at 90 % or more goes on
        # battery, and only then the reset. The battery's level stays frozen until the battery's state is put back.
        Invoke-Checked 'dumpsys battery unplug' | Out-Null
        Invoke-Checked 'dumpsys batterystats --reset' | Out-Null
        $last = Get-VoiceState
        $waits = 0
        $replies = 0
        $modeChanges = [System.Collections.Generic.List[string]]::new()
        $batteryDone = $false
        # The tasks that Kestrel starts are counted exactly, within one process of Kestrel's; they also cover every wait
        # that room talk answered.
        $countsBefore = try { Get-Counts } catch { $null }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($watch.Elapsed.TotalMinutes -lt $minutes) {
            # Each poll runs in Kestrel's process, so the script polls less often during the battery test. A wait that
            # nobody answers lasts at least 8 s, so no poll misses it, but a wait that room talk answers, and a short reply,
            # may fall between two polls.
            Start-Sleep -Seconds $(if ($batteryDone) { 1 } else { 5 })
            $state = Get-VoiceState
            if ($state -and $last) {
                if ($state.work -eq 'awaiting_task' -and $last.work -ne 'awaiting_task') { $waits++ }
                if ($state.speaking -eq 'true' -and $last.speaking -ne 'true') { $replies++ }
                if ($state.mode -ne $last.mode) {
                    $modeChanges.Add("minute $([math]::Floor($watch.Elapsed.TotalMinutes)): $($last.mode) to $($state.mode)")
                    # A false "Stop listening" or "Kestrel, turn off" would leave Kestrel paused or off for the rest of
                    # the hour, and the battery test would measure a Kestrel that does not listen.
                    Start-Kestrel
                    $state = Get-VoiceState
                }
            }
            if ($state) { $last = $state }
            if (-not $batteryDone -and $watch.Elapsed.TotalMinutes -ge $batteryMinutes) {
                $batteryDone = $true
                $charged = @(Invoke-Checked 'dumpsys batterystats --charged')
                $use = Get-BatteryUse -Dump $charged -Uid $uid -Hours $watch.Elapsed.TotalHours
                # Research r10, gap 2: Samsung's code may send its own plug events, and batterystats then stops counting.
                $onBattery = Test-OnBattery -Dump $charged
                Complete-Restore $batteryRestore
                $counted = if ($null -eq $onBattery) { 'not shown' } else { $onBattery }
                $how = "with the screen on and room talk that Kestrel transcribed, a worst case; batterystats counted the phone as on battery at the end: $counted"
                if ($use) {
                    Add-Result room "battery: Kestrel used $($use.MilliampHours) mAh in $([math]::Round($watch.Elapsed.TotalMinutes)) minutes, with a capacity of $($use.Capacity) mAh: $($use.PercentPerHour) % per hour, $how"
                } else {
                    Add-Result room "battery: batterystats gave no estimate for Kestrel, $how"
                }
            }
        }
        $changes = if ($modeChanges.Count -gt 0) { " ($($modeChanges -join '; '); each time the script started listening again)" } else { '' }
        # A failed hook call, or a new process of Kestrel's during the hour, must not cost the hour's result line.
        $countsAfter = try { Get-Counts } catch { $null }
        $tasks = if ($countsBefore -and $countsAfter -and $countsAfter.Tasks -ge $countsBefore.Tasks) {
            $countsAfter.Tasks - $countsBefore.Tasks
        } else {
            'an unknown number of'
        }
        Add-Result room ("in $minutes minutes of room audio, Kestrel started $tasks tasks, began to wait for a task at least " +
            "$waits times, began to speak at least $replies times and changed its mode $($modeChanges.Count) times$changes")
        Stop-Kestrel
    } finally {
        Complete-Restore $batteryRestore
        Complete-Restore $timeoutRestore
    }
}

# The type of Kestrel's foreground service, as 'dumpsys activity services' lists it; 0 means that Android demoted it.
function Get-ServiceType {
    foreach ($line in @(Invoke-Checked 'dumpsys activity services dev.kestrel')) {
        if ($line -match '\b(?:foregroundServiceType|types)=(0x[0-9a-fA-F]+|\d+)') { return $Matches[1] }
    }
    'not listed'
}

# Final review of plan 2C-4, "For plan 2D", item 7: what Android 13 and One UI do to Kestrel's microphone service in the
# background after its accessibility service is turned off, without and with the battery restriction (the app op
# RUN_ANY_IN_BACKGROUND, which isBackgroundRestricted() reads), and whether the checklist names Samsung's rules.
function Invoke-BatteryRulesStep {
    $manufacturer = ((Invoke-Checked 'getprop ro.product.manufacturer') -join '').Trim()
    $samsungText = Read-OwnerAnswer 'Open Kestrel''s settings and the setup checklist. Does its battery step name Samsung''s "Never sleeping apps"? Type y or n, and then go back to the home screen.' 'n'
    Add-Result battery-rules "the manufacturer: $manufacturer; the checklist names ""Never sleeping apps"": $samsungText"
    $op = ((Invoke-Checked 'cmd appops get dev.kestrel RUN_ANY_IN_BACKGROUND') -join ' ')
    # An op that was never set reads as 'No operations.'; its mode is then the default.
    $before = if ($op -match 'RUN_ANY_IN_BACKGROUND: (\w+)') { $Matches[1] } else { 'default' }
    $opRestore = Register-Restore "Kestrel's battery restriction" "Run: & '$adb' -s $Serial shell cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $before" $before {
        param($Data)
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $Data" | Out-Null
    }
    $accessibilityRestore = Register-AccessibilityRestore
    try {
        $others = @(Get-OtherServices $accessibilityRestore.Data)
        $withKestrel = "settings put secure enabled_accessibility_services '$((@($others) + $service) -join ':')'"
        $withoutKestrel = if ($others) { "settings put secure enabled_accessibility_services '$($others -join ':')'" } else { 'settings delete secure enabled_accessibility_services' }
        $minutes = if ($DryRun) { 0.5 } else { 3 }
        foreach ($restricted in $false, $true) {
            $label = if ($restricted) { 'with the battery restriction' } else { 'without the battery restriction' }
            Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($restricted) { 'ignore' } else { 'allow' })" | Out-Null
            Invoke-Checked $withKestrel | Out-Null
            Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
            Start-Kestrel
            $typeBefore = Get-ServiceType
            Invoke-Checked $withoutKestrel | Out-Null
            Request-Owner "Kestrel listens in the background, and its accessibility service is off now, $label. Leave the phone unlocked, with its screen on, for $minutes minutes. Press Enter to start the wait." 'go' | Out-Null
            Start-Sleep -Seconds ([int]($minutes * 60))
            $state = Get-VoiceState
            $mode = if ($state) { $state.mode } else { 'unknown' }
            $busy = if ($state) { $state.micBusy } else { 'unknown' }
            $notice = Read-OwnerAnswer 'Look at the notification shade. Type b if Kestrel says that its battery use is restricted, a if it says only that its accessibility service is off, o for another text of Kestrel''s, or n for none.' 'n'
            Add-Result battery-rules "${label}: the service's type $typeBefore before and $(Get-ServiceType) after $minutes minutes; Kestrel's mode $mode; the microphone silenced: $busy; Kestrel's notice: $notice"
            Stop-Kestrel
        }
    } finally {
        Complete-Restore $accessibilityRestore
        Complete-Restore $opRestore
    }
}

# Final review of plan 2C-4, "For plan 2D", items 7 and 10: whether a restart of the A71 leaves an exit record, whether
# the restore at boot finds the models, and how often a restore at boot ends before its session opens. The phone restarts
# with adb reboot; the emulator is not restarted, because listen-check.ps1 cold-boots it instead. The restore at boot
# runs when Kestrel's accessibility service connects, so the step turns the service on, beside the owner's others, and
# puts the settings back afterwards.
function Invoke-RebootStep {
    if ($DryRun) {
        Add-Result reboots 'skipped in a dry run; on the emulator, listen-check.ps1 checks the restore after a cold boot'
        return
    }
    $restore = Register-AccessibilityRestore
    try {
        $services = @(Get-OtherServices $restore.Data) + $service
        Invoke-Checked "settings put secure enabled_accessibility_services '$($services -join ':')'" | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
        $tries = 3
        $done = 0
        $restored = 0
        for ($try = 1; $try -le $tries; $try++) {
            Start-Kestrel
            if ((Request-Owner "Restart $try of ${tries}: the script restarts the phone while Kestrel listens. Press Enter to restart it, or s to skip the rest.") -eq 'skip') { break }
            $done++
            # A new boot ID shows that the phone has started again; adb reboot gets a time limit, as in listen-check.ps1.
            $bootId = ((Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join '').Trim()
            Assert-TestDevice -Serial $Serial
            $call = Invoke-TimedProcess -FilePath $adb -ArgumentList '-s', $Serial, 'reboot' -TimeoutSeconds 60
            if (-not $call.Completed) { throw "'adb reboot' did not return within 60 s." }
            $watch = [Diagnostics.Stopwatch]::StartNew()
            do {
                Start-Sleep -Seconds 2
                $id = try { ((Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join '').Trim() } catch { '' }
                $bootDone = if ($id -and $id -ne $bootId) {
                    try { ((Invoke-Checked 'getprop sys.boot_completed') -join '').Trim() } catch { '' }
                }
                $booted = $bootDone -eq '1'
            } while (-not $booted -and $watch.Elapsed.TotalSeconds -lt 300)
            if (-not $booted) { throw 'The phone did not start again within 5 minutes of the restart.' }
            Assert-TestDevice -Serial $Serial
            Request-Owner 'Unlock the phone, and press Enter after the unlock.' 'go' | Out-Null
            $on = Wait-Mode listening 180
            if ($on -ge 0) { $restored++ }
            $mode = (Get-VoiceState).mode
            # Only the reason's number and name of the newest record, never its description.
            $exit = 'none'
            foreach ($line in @(Invoke-Checked 'dumpsys activity exit-info dev.kestrel')) {
                if ($line -match '\breason=(\d+ \([A-Z_ ]+\))') {
                    $exit = $Matches[1]
                    break
                }
            }
            $notice = Read-OwnerAnswer 'Look at the notification shade. Type m if Kestrel says that its speech models are missing, o for another text of Kestrel''s, or n for none.' 'n'
            Add-Result reboots "restart ${try}: listening again: $(Format-Wait $on); Kestrel's mode $mode; the newest exit record: $exit; Kestrel's notice: $notice"
        }
        Add-Result reboots "the restore at boot listened again after $restored of $done restarts"
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

$plan = @(
    @{ Name = 'install'; Minutes = 10; Title = 'install Kestrel and copy the speech models with the Kokoro voice; you may leave'; Run = 'Invoke-InstallStep' }
    @{ Name = 'voice-tests'; Minutes = 30; Title = 'the voice device tests, among them spike S8, which may take up to an hour; you may leave'; Run = 'Invoke-VoiceTestStep' }
    @{ Name = 'listen-check'; Minutes = 10; Title = 'listen-check.ps1, with a restart of the phone'; Run = 'Invoke-ListenCheckStep' }
    @{ Name = 'failures'; Minutes = 1; Title = 'Kestrel''s logged failures'; Run = 'Invoke-FailuresStep' }
    @{ Name = 'battery-rules'; Minutes = 15; Title = 'Android''s and Samsung''s battery rules without the accessibility service'; Run = 'Invoke-BatteryRulesStep' }
    @{ Name = 'reboots'; Minutes = 12; Title = 'three restarts of the phone while Kestrel listens'; Run = 'Invoke-RebootStep' }
    @{ Name = 'close-start'; Minutes = 4; Title = 'a start during a close, with your help'; Run = 'Invoke-CloseStartStep' }
    @{ Name = 'lock-tile'; Minutes = 3; Title = 'the tile on the lock screen, with your help'; Run = 'Invoke-LockTileStep' }
    @{ Name = 'unlock'; Minutes = 8; Title = 'the unlock paths, with your help'; Run = 'Invoke-UnlockStep' }
    @{ Name = 'activation'; Minutes = 12; Title = 'the shortcut, the button, the tile and the notification, with your help'; Run = 'Invoke-ActivationStep' }
    @{ Name = 'assistant'; Minutes = 8; Title = 'the assistant gesture and the Galaxy Buds gesture, with your help'; Run = 'Invoke-AssistantStep' }
    @{ Name = 'earbuds'; Minutes = 8; Title = 'the earbud microphone, with your earbuds'; Run = 'Invoke-EarbudStep' }
    @{ Name = 'recordings'; Minutes = 30; Title = 'your recordings of the voice corpus'; Run = 'Invoke-RecordingStep' }
    @{ Name = 'corpus'; Minutes = 43; Title = 'the corpus''s measurements on this phone, about 420 clips; you may leave'; Run = 'Invoke-CorpusStep' }
    @{ Name = 'voice'; Minutes = 30; Title = 'talking to Kestrel, and the echo filter'; Run = 'Invoke-VoiceStep' }
    @{ Name = 'memory'; Minutes = 8; Title = 'memory, the times of starts and closes, and a cold start through the tile'; Run = 'Invoke-MemoryStep' }
    @{ Name = 'room'; Minutes = 60; Title = 'an hour of room audio with the battery test; you may leave once it runs'; Run = 'Invoke-RoomStep' }
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
        # The restores that are left, the newest first, so that the screen, the clips, the hooks' tasks and Kestrel's mode
        # come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        if ($DryRun -and (Test-Path -LiteralPath $clipsFolder)) { Remove-Item -LiteralPath $clipsFolder -Recurse -Force }
        if ($earbudsChanged) { Write-Host 'If you have not done it yet, turn "Use the earbud microphone" off in Kestrel''s settings, unless you want to keep it.' }
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

The script puts nothing private into its results: the lines of `listen-check.ps1` and `voice-test.ps1` hold check names, states, seconds and measurements, `voice-eval.ps1` prints only totals without `-Show`, the hooks answer counts, and the intents of the Galaxy Buds gesture are reduced to their actions.

- [ ] **Step 10: Unlock a phone before the reboot check's recording check**

In `scripts/listen-check.ps1`, replace

```powershell
# -Reboot it reboots a phone, or closes an emulator and starts it from a cold boot; a phone with a PIN, pattern or
# password asks you to unlock it after the reboot. It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does.
```

with

```powershell
# -Reboot it reboots a phone, or closes an emulator and starts it from a cold boot; a phone with a PIN, pattern or
# password asks you to unlock it after the reboot, and on a phone the script also wakes the screen and asks you to
# unlock it before it checks the recording, because Kestrel records only while the screen is on and the phone unlocked.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does.
```

and replace

```powershell
    $again = [math]::Round($watch.Elapsed.TotalSeconds, 1)
    # 'dumpsys audio' starts its history afresh at each boot, so every recording of Kestrel's that it lists is new.
    $problem = Get-RecordingProblem @()
```

with

```powershell
    $again = [math]::Round($watch.Elapsed.TotalSeconds, 1)
    # On a phone, the screen is off or locked after the reboot, and Kestrel records only while it is on and unlocked.
    if (-not $isEmulator) {
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Read-Host 'Unlock the phone, then press Enter' | Out-Null
    }
    # 'dumpsys audio' starts its history afresh at each boot, so every recording of Kestrel's that it lists is new.
    $problem = Get-RecordingProblem @()
```

- [ ] **Step 11: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: N, Failed: 0" in both shells, with N as in Step 8, "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed." `a71-session.ps1`, `listen-check.ps1`, `make-corpus.ps1` and `voice-eval.ps1` declare `#Requires -Version 7.3`, so the analysis checks them without the rules for Windows PowerShell 5.1, as it does for `hook.ps1`; `Session.ps1` and `Corpus.ps1` are checked against 5.1 too.

- [ ] **Step 12: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 18.2, replace

```markdown
- **Done when:** the A71 meets the transcription, accuracy, memory and battery targets in section 5.3 (or the streaming command model is adopted), and every activation method works on the A71 and the emulator.
```

with

```markdown
- **Done when:** the A71 meets the transcription, accuracy, memory and battery targets in section 5.3 (or the streaming command model is adopted), and every activation method works on the A71 and the emulator. Plan 2D gathers every check that needs the A71 or the owner into one session, which `scripts/a71-session.ps1` runs with the owner (section 19.1).
```

In section 19.1, after the bullet that starts with "**S8 and the speech engines on the emulator (plan 2A).**", add this bullet:

```markdown
- **The A71 session (plan 2D).** `scripts\a71-session.ps1` takes the owner through every check of sub-project 2 that needs the A71 or the owner, in about 5 to 5.5 hours, of which about 2 hours need the owner's hands and voice: spike S8 and the voice device tests that may run on a phone; `scripts\listen-check.ps1 -Reboot`; Kestrel's logged failures; what Android 13 and One UI do to Kestrel in the background without its accessibility service, without and with the battery restriction; three restarts with the restore at boot; a start during a close; the tile on the lock screen; One UI's unlock paths; the accessibility shortcut and button, the tile and the notification's actions with Kestrel in the background; spike S2 with Kestrel as the assistant, the assistant gesture and the Galaxy Buds gesture; the earbud microphone; the owner's 100 recordings of the voice corpus and `scripts\voice-eval.ps1` with them and with the generated clips; the owner's voice with Kestrel (the time to "Yes?", the waits, a dictated task, interruptions with pauses, a notification sound during a reply, "stop" while a task runs, the misses of short commands, and the echo filter with the real player and spotter); and spike S5: memory and the times of starts and closes, a cold start through the tile, an hour of ordinary room audio with Kestrel's reactions counted, and the 30-minute battery test of research r10. The script asks the owner before each step, refuses every device but the A71 and kestrel-api37, puts back what it changes, keeps its results and the recordings in `corpus/owner`, which git ignores, and never prints what Kestrel heard. The A71 has no SIM card, so calls are checked only on the emulator. The results of the session go into this section and into the README's table of measurements.
```

In `README.md`, replace

```markdown
## Setting up Kestrel
```

with

```markdown
### The A71 session

`scripts\a71-session.ps1 -Serial $serial` takes the owner through one session on the Galaxy A71, about 5 to 5.5 hours, of which about 2 hours need your hands and voice and the rest runs while you may leave, with every check of the voice features that needs the A71 or the owner (design spec, section 19.1): the voice device tests, `listen-check.ps1 -Reboot`, Android's and Samsung's battery rules, three restarts of the phone, the unlock paths, the activation methods, Kestrel as the assistant with the assistant gesture and the Galaxy Buds gesture, the earbud microphone, the owner's recordings of the voice corpus and their measurements, the owner's voice with Kestrel, Kestrel's memory, an hour of ordinary room audio and the battery test. It asks before each step, and before it first reads the device; a step that needs your hands says what to do and waits, and `s` skips, `q` stops. `-Step` runs only the named steps. It refuses every device but the A71 and `kestrel-api37`. The results go into `corpus\owner\session-<time>.txt` and the recordings into `corpus\owner\clips`; git ignores both, and neither the results nor the console ever hold what Kestrel heard. While the session runs, the phone's screen stays on while it is plugged in. Afterwards, also when a step fails or Ctrl+C stops it, the script puts back Kestrel's mode Off, the hooks' slow task, the setting "Stay awake", the assistant role and its settings, Kestrel's battery restriction, the accessibility settings, the screen's timeout, the battery's state and the clips in Kestrel's files folder. The battery test resets the phone's battery statistics, which cannot be put back, and the script asks you to turn "Use the earbud microphone" off again if you turned it on. `-DryRun` runs the session on `kestrel-api37` without the owner, to try a change of the script; it answers its own questions, sends what the owner would say through the voice hook, skips what needs the owner's hands otherwise, and shortens the waits. It needs PowerShell 7.3 or newer, as `hook.ps1` does.

The session's measurements, with the targets of the design spec (section 5.3):

| Measure | Target | Galaxy A71 |
|---|---|---|
| Transcription time of a command | at most 4 s | not measured yet |
| Kestrel's memory while it listens (PSS) | at most 2 GB | not measured yet |
| Battery use while listening | at most 2 % per hour | not measured yet |
| Word error rate and requests read as intended, with the owner's recordings | none yet | not measured yet |
| Kestrel's reactions in an hour of ordinary room audio | none yet | not measured yet |
| Piper's and Kokoro's speed (real-time factor) | below 1, so that Kestrel speaks without gaps | not measured yet |

## Setting up Kestrel
```

- [ ] **Step 13: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (248 in `:app` and 136 in `:core:voice`, as after Task 8), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, which checks that the release build has none of the new hooks, and the device tests compile.

- [ ] **Step 14: Run the voice device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 80 run, 0 failed, 0 errors, 0 skipped." (78 and the 2 of `SessionHookTest`), and no warning from the restores at the end.

- [ ] **Step 15: Check the phone branch of voice-test.ps1 on the emulator**

The final review of plan 2C-1 (M-1, and "For plan 2D", item 1) asked for this check before the first run of `voice-test.ps1` on a phone. The command reads the list of emulator-only tests from `voice-test.ps1`, runs the voice package through `connected-test.ps1` with the same `notClass` that the script passes on a phone, and checks that none of the listed tests ran:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; $block = [regex]::Match((Get-Content scripts\voice-test.ps1 -Raw), '(?s)\$emulatorOnly = @\((.*?)\n\s*\)').Groups[1].Value; $left = @([regex]::Matches($block, "'(dev\.kestrel\.app\.voice\.\w+#\w+)'") | ForEach-Object { $_.Groups[1].Value }); "left out: $($left.Count)"; $results = 'app\build\outputs\androidTest-results\connected'; if (Test-Path $results) { Remove-Item $results -Recurse -Force }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.package=dev.kestrel.app.voice' "-Pandroid.testInstrumentationRunnerArguments.notClass=$($left -join ',')" '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache; $xml = @(Get-ChildItem $results -Recurse -Filter 'TEST-*.xml'); foreach ($test in $left) { if ($xml | Select-String -SimpleMatch "name=`"$(($test -split '#')[1])`"") { "RAN: $test" } }; $counts = Get-TestResultCounts $results; "run $($counts.Tests), failed $($counts.Failures), errors $($counts.Errors), skipped $($counts.Skipped)"`
Expected: "left out: N", where N is the number of tests in `$emulatorOnly` (6: the four of plan 2C-4, Task 4's assistant test and Task 6's earbud test); `BUILD SUCCESSFUL`; no line that starts with "RAN:"; and "run M, failed 0, errors 0, skipped 0", where M is 80 minus N. Put the two lines into the task's report. If a listed test ran, `notClass` does not take the list as `voice-test.ps1` passes it; stop and report it to the controller, because the session's `voice-tests` step would then run a test that must not run on a phone.

- [ ] **Step 16: Run the session's dry run on the emulator**

The dry run needs Kestrel's current debug build, which Steps 14 and 15 installed, the models with Kokoro, and the generated clips of Task 8 in `corpus\generated\piper`. It builds and installs Kestrel again in its `install` step, runs `listen-check.ps1` without `-Reboot`, sends the owner's words of the `voice` step through the voice hook, and takes about 40 minutes. The command first saves what the session changes on the emulator, then runs the dry run, and then compares:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; $read = { @((& $adb -s emulator-5554 shell 'cmd role get-role-holders android.app.role.ASSISTANT; settings get secure assistant; settings get secure voice_interaction_service; settings get secure enabled_accessibility_services; settings get secure accessibility_shortcut_target_service; settings get secure accessibility_button_targets; settings get system screen_off_timeout; settings get global stay_on_while_plugged_in') -join '|') }; $before = & $read; pwsh -NoProfile -File scripts\a71-session.ps1 -Serial emulator-5554 -DryRun; "exit code $LASTEXITCODE"; $after = & $read; if ($before -ceq $after) { 'the settings are as before' } else { "the settings differ: before $before, after $after" }; & $adb -s emulator-5554 shell 'dumpsys battery | grep -c ''UPDATES STOPPED''; ls /sdcard/Android/data/dev.kestrel/files/eval | wc -l'; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 voice-state; git status --short corpus`
Expected:
- the dry run prints "[dry run]" before each question that it answers, and a line for each result; the last line is "The results are in ...\corpus\owner\session-dry-run-<time>.txt.";
- "exit code 0", and no warning that something could not be put back;
- "the settings are as before";
- `0` twice: the battery's state is back, and no clip is left in Kestrel's folder;
- the voice state starts with `mode=off` and contains `session=closed`;
- `git status` shows nothing new in `corpus` (the results and the dry run's clips are in the ignored `corpus\owner`, and the dry run deleted its clips).

Then check the results file: `Get-Content (Get-ChildItem corpus\owner -Filter 'session-dry-run-*.txt' | Sort-Object LastWriteTime | Select-Object -Last 1).FullName`
Expected: lines for
- `session` ("started on kestrel-api37, as a dry run");
- `install` (two lines; the second says that Kestrel started listening when the session started it) and `voice-tests` ("skipped in a dry run ...");
- `listen-check` (one line for each check, each `ok`) and `failures`;
- `battery-rules` (the manufacturer, and one line without and one with the battery restriction, each with the service's type, Kestrel's mode, whether the microphone is silenced and the notice `n`) and `reboots` ("skipped in a dry run ...");
- `close-start`, `lock-tile`, four for `unlock`, four for `activation`, two for `assistant` and one for `earbuds`, all of them "skipped" after the dry run's answers;
- `recordings` ("2 of 2 lines recorded into ...dry-run-clips-...");
- `corpus` (the phone's language and its list, and the boost words and the five summary lines of `-Corpus generated -Voice piper`);
- `voice`: a line for each measure, each with "of 1 tries" or a time (the numbers may be anything, because the dry run checks only that the code runs), the dictated task, the notification sound "skipped", and one line of the echo filter with `result=finished`;
- `memory` (three starts, each with "the session opened ok after" and a PSS, and the cold start skipped); and
- `room` (a battery line and the counts of the two minutes).

No line holds a transcript. Put the results file's lines into the task's report. If a step's line says "failed", report it with the line; a failure of a check of `listen-check.ps1` that also fails on its own run is not the session's.

Once the dry run has passed, record it in the spec. In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 19.1, replace

```markdown
puts back what it changes, keeps its results and the recordings in `corpus/owner`, which git ignores, and never prints what Kestrel heard. The A71 has no SIM card,
```

with

```markdown
puts back what it changes, keeps its results and the recordings in `corpus/owner`, which git ignores, and never prints what Kestrel heard. Its dry run passed on kestrel-api37 in plan 2D. The A71 has no SIM card,
```

- [ ] **Step 17: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 18: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt scripts/hook.ps1 scripts/lib/Session.ps1 scripts/tests/Session.Tests.ps1 scripts/a71-session.ps1 scripts/listen-check.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat: the A71 session script with its dry run, and the hooks that it measures with" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 19: Stop before the A71**

Do not connect to the A71, and run nothing on it. Report to the controller that the session is ready: it takes about 5 to 5.5 hours, of which about 2 hours need the owner's hands and voice, and the owner may leave during `install` and `voice-tests`, `corpus` and the hour of room audio. The A71 needs to be charged, unlocked, with USB debugging on and the owner's Galaxy Buds at hand. The controller asks the owner once and runs `scripts\a71-session.ps1 -Serial <the A71's serial>` with the owner. Afterwards the controller records the results in `.superpowers/sdd/progress.md`, in spec section 19.1 and in the README's table of measurements, in a commit of its own.

---

## Self-review

**Spec and design coverage.**

| Design decision | Where |
|---|---|
| D3, as the controller changed it: transient focus with a listener; a transient or permanent loss stops the reply as a "stop" does and counts as interrupted, with the text on the bar, and on a "may duck" loss (a notification sound, most navigation prompts) Kestrel's own player lowers its voice while Kestrel keeps speaking; the focus kept between queued replies and given up when the queue is empty; one `AudioFocusRequest` per player; JVM tests with a fake focus; a device test in which another app's transient focus stops a long reply (final review of plan 2C-1, item 14; plan 2B's Task 5 review, N4 and N5) | Task 1 (`ReplyFocus`, `AndroidAudioFocus`, `Spoke.INTERRUPTED`, `VoicePlayer`, `anotherAppsFocusStopsTheReplyAtOnceAndTheNextReplyAsksAgain`); the A71 session's `voice` step checks a notification sound during a long reply (review A, M2) |
| D1: the call state from a `TelephonyCallback.CallStateListener` with `READ_PHONE_STATE`; the modes Ringing and In call, the return to the mode before the call, the stored mode never Ringing or In call; recording on the lock screen while a call rings; Kestrel stops speaking at once (final review of plan 2C-2, "For plan 2D", first item) | Task 2 (`Mode.RINGING`, `Mode.IN_CALL`, `CallState`, `Event.CallState`, `State.beforeCall`, `CallMonitor`, `AndroidCallMonitor`) |
| D1: "answer", "answer on speaker" and "decline" while ringing; "hang up", "mute" and "speaker" during a call with "Commands during a call" on; the Telecom API first, the controls by description without the permission; "I answered, but I couldn't turn on the speaker."; the permissions and the optional checklist step; `listen-check.ps1`'s `call` check on the emulator, which deletes only its own calls and restores the permissions | Task 3 (`Request.Call` with `Request.Answer(speaker)`, `Decline`, `HangUp`, `Mute`, `Speaker`; `Requests.readCall`; `CallActions`, `AndroidCallApi`, `CallScreen`; the checklist step; the `call` check) |
| D2: the assistant services in the main process, `onShow` sends `Event.Assist` and hides; the stub recognition service; the trampoline for `VOICE_COMMAND` and `VOICE_SEARCH_HANDS_FREE`, not `WEB_SEARCH`; the checklist's step 6 with the role and Android's default-apps settings; JVM tests of `Event.Assist`; the two device tests | Task 4 (`KestrelAssistantService`, `KestrelSessionService`, `KestrelRecognitionService`, `AssistActivity`, `Listening.assist()`, `SetupStatus.assistantRole`, `SetupAction.DEFAULT_APPS`, `AssistTest`) |
| D4: the page "Listening and activation" with "On screen wake-up", "Pause after 30 s without speech", "Commands during a call", "Use the earbud microphone" and the state of the activation methods; stored with `SettingsStore`; read by `Listening` as a flow, so that a change counts at once; JVM tests for each rule; Compose tests | Task 5 (`ListeningOptions`, `WakeUpMode`, `SettingKeys.LISTENING_OPTIONS`, `Event.Options`, `Event.Silent`, `Effect.TimeSilence`, `Listening.currentOptions`, `SettingsPage.LISTENING`, `SettingsAppTest`) |
| D5: with the setting on and a headset connected, the headset as the communication device (LE Audio first, then SCO) and the preferred device for LE Audio; the request withdrawn when the microphone stops and when Android reports another device; Kestrel never sets the audio mode, so a call wins; JVM tests with a fake; D4's "a change counts at once" | Task 6 (`HeadsetRoute` with a token per open, `CommunicationDevices`, `AndroidCommunicationDevices`, `MicCapture(earbud)`, which withdraws an LE Audio request without a microphone, `HeadsetRouteTest` 9, `MicCaptureTest`'s new test on emulators only, the options' collector in `Listening`, which restarts a running recording when the setting changes, and its `ListeningTest` test, `MODIFY_AUDIO_SETTINGS`); the A71 session's `earbuds` step checks real earbuds and says that it does not check the LE Audio path or a call |
| D6: the page "Voice and speech" with Piper (default) and Kokoro `af_heart` when its files are on the phone, the speed from 0.8 to 1.4, and "Try it" | Task 7 (`VoicePage`, which keeps its writes in order as Task 5's page does, `VoiceOptionsSetting`, `SettingsData.tryVoice`, which loads the voice on the page itself, so that a Kokoro that does not load shows "Kestrel could not speak with this voice.", and whose sample has no keyword and no command, `SettingsPage.VOICE`, `SettingsAppTest`'s six new tests); `AppGraph` reads a voice setting that cannot be read as the default, so a broken store never keeps Kestrel from listening |
| D6: Kokoro `kokoro-multi-lang-v1_0` fp32 as an optional group of the manifest; `push-models.ps1 -Kokoro`; `ModelFiles` checks it only when it is present or chosen; `VoiceEngines` loads the chosen voice; the licence notes on the About page and in the notices | Task 7 (`Model.optional`, `ModelStatus.absent`, `VoiceEngines.load(engine, ids, create)`, `SherpaKokoroSynthesizer`, `loadChosenVoice`, which falls back to Piper for any failure of Kokoro and reports it, `VoiceTests.assumeModels(..., optional)`, `Select-ModelManifest`, notices); without `lexicon-us-en.txt`, and with the CC BY voices still in `voices.bin` (see the departures below) |
| D6: the echo filter's timing for both voices | Task 7 (`EchoTimingTest`, a device test; see the departures below) |
| D6: if Kokoro is slower than real time on the A71, the page says so | Task 9's `voice-tests` step measures it; the text is added after the session (see the departures below) |
| D7: `corpus/` with generated English commands (Appendix A with the "Yes" rows, Kestrel's own phrases, tasks with the name, confusable phrases) and a script that synthesizes them on the emulator into 16 kHz clips in a git-ignored folder | Task 8 (`corpus/commands.txt`, `make-corpus.ps1`, the hook `VOICE_SYNTH`, `/corpus/generated/` in `.gitignore`) |
| D7: the owner's recordings in `corpus/owner/`, git-ignored, about 100 utterances that the session prompts for | Task 8 (`/corpus/owner/` in `.gitignore`, the `owner` column), Task 9 (the `recordings` step, `VOICE_RECORD`) |
| D7: `voice-eval.ps1` with the session's boost words, the word error rate and the share of requests read as intended; for the owner's recordings only counts leave the device, and the script prints only totals | Task 8 (`VOICE_EVAL`, which cuts each clip with Kestrel's speech detector as the pipeline does and shows a transcript only for a clip named `gen-...`; `WordErrors`, which counts numbers the same in words and digits; `Corpus.ps1`, `voice-eval.ps1`, `EvalHookTest`, Step 19 on the emulator) |
| D7: transcription time and memory from the same hook | Task 8 (`millis=`, `seconds=`, `pss=`, `parts=` and `twice=` in the hook's answer) |
| D8: one script that walks the owner through every A71 step, asks before each step, records results without private data, restores the phone; a dry run on the emulator; the plan stops before the A71 | Task 9 (`a71-session.ps1`, `Session.ps1`, Steps 16 and 19) |
| D8 list, item 1: the M-1 check of the final review of plan 2C-1 | Task 9, Step 15 |
| D8 list, item 2: S8 and the voice device tests that may run on a phone | Task 9, step `voice-tests` |
| D8 list, item 3: `listen-check.ps1` on the A71 with the unlock after the reboot; "Kestrel, open settings" from the background; Android 13 and the microphone service; a start during a close; the restore after a reboot; the tile on the lock screen | Task 9, steps `listen-check`, `failures`, `close-start` and `lock-tile`, and Step 10 (the unlock in `listen-check.ps1`); `listen-check.ps1`'s adaptations to One UI wait until the session shows what they need |
| D8 list, item 4: the unlock paths of One UI | Task 9, step `unlock` |
| D8 list, item 5: the activation methods, the assistant gesture and the Galaxy Buds gesture (S2); whether each start path on Android 13 records without being silenced and can speak (spike S1, final review of plan 2C-1, item 3) | Task 9, steps `activation`, `close-start` and `assistant` (the recording and "Yes?" after each start, also after the two gestures) |
| D8 list, item 6: the earbud microphone and replies in the earbuds | Task 9, step `earbuds` |
| D8 list, item 7: the corpus recordings, the conversation, the echo filter with the real player and spotter, interruptions and pauses, the delay to "Yes?", "stop" while a task runs, the misses of short commands and of "Kestrel", "Hey Kestrel", the waits and the hold | Task 9, steps `recordings` and `voice` (with `VOICE_COUNTS`, `VOICE_TASK`, `VOICE_ECHO` and `Listening.runTask`); the dictated task counts its words and the time from the end of speech to the task. Not measured: whether listening comes back a second after a failure of the microphone (final review of plan 2C-3, item 5), which the log cannot show; `ListeningTest` checks it |
| D8 list, item 8: S5's transcription time, memory, the times of a start and a close, a cold start through the tile, false keyword hits in an hour of room audio (counts only), Piper's and Kokoro's speed, the 30-minute battery test | Task 9, steps `corpus`, `memory`, `room` and `voice-tests` |
| D8 list, item 9: the app names on the A71, the first read's time, One UI's language, how many names the guard drops, how often `TranscriptChoice` decodes twice | Task 9, steps `voice-tests` (`AppNamesTest`) and `corpus` (`keptBoostWords`, `secondDecodings`) |
| D8 list, item 10: calls only on the emulator | Task 3's `call` check; Task 9's table and README section say so |
| D8: results into spec 19.1 and a table of measurements | Task 9, Step 12 (spec 18.2 and 19.1, the README's section "The A71 session" with its table), and Step 16 (the dry run's sentence) |
| Final review of plan 2C-4, "For plan 2D", item 7: `isBackgroundRestricted()` and the demotion, Samsung's battery rules, listening after the accessibility service is turned off under the restriction, the Samsung text; an update on Android 13; an exit record after a reboot, and the models at boot | Task 9, steps `battery-rules`, `install` (the session starts Kestrel itself after the update) and `reboots` |
| Item 8: `listen-check.ps1`'s notification check on One UI's shade, the owner's notifications in the dump, the notice about the accessibility service, `adb reboot` on the phone | Task 9, step `listen-check` (the owner is told that the dump is deleted and never shown, and swipes the notice away); `listen-check.ps1` keeps `adb reboot` on a phone, and Step 10 changes only its unlock |
| Item 9: after `connected-test.ps1`'s `am force-stop` a restore keeps Kestrel off | Task 9, step `voice-tests` (the session starts Kestrel itself afterwards) |
| Item 10: how often a restore at boot ends before its session opens | Task 9, step `reboots` (three restarts, counted, with Kestrel's accessibility service on) |
| Final review of plan 2C-3, item 3: the effect of the boost words with the owner's voice | Task 8 (`voice-eval.ps1 -NoBoost`, the hook's `noBoost`), Task 9's `corpus` step (a second run of the owner's recordings without them, about 8 minutes) |
| The spec's sections 8.5 to 8.8, 15, 16.4, 19 and 19.1 | Tasks 2 to 5 (8.5 to 8.7, 15), Task 6 (8.8, 13.6), Task 7 (8.2, 8.10, 15), Task 8 (16.4), Task 9 (18.2, 19.1) |
| The expected counts after each task | Each task's run steps; the table below |

**Placeholder scan.** No "TBD", "TODO", "similar to Task N" or step without its code or command. Every changed file shows the exact text that it replaces, or the whole new file. The open values are the measurements that the runs print: Task 3's call check, Task 7's "kokoro:" and "echo timing:" lines, Task 8's word error rates on the emulator, Task 9's dry run, and the README's table, which says "not measured yet" until the A71 session fills it in. The checker (`plan2d-check-parts.py` for the two parts, `plan2d-check-joined.py` for the joined plan) applies all 367 operations of the plan in order to the repository at 7493c42 and finds no problem, and its counts match the table below. PSScriptAnalyzer with the repository's settings, run in memory on the plan's `a71-session.ps1`, `Session.ps1`, `Corpus.ps1`, `voice-eval.ps1` and `make-corpus.ps1`, finds no problem. Every call that passes parameters by name through splatting uses a hashtable.

**Type consistency.**
- The design's shared names hold, with two refinements in part A: the call commands are the members of `sealed interface Request.Call : Request` (`Request.Answer(speaker: Boolean)`, `Decline`, `HangUp`, `Mute`, `Speaker`), which `Requests.readCall(transcript, spotted, ringing)` reads, and `WakeUpMode` lives in `dev.kestrel.app.orchestrator`. Task 8's corpus marks the call commands '-', so `voice-eval.ps1` does not check their reading, because `Requests.read` never returns them.
- `ListeningOptions.earbudMicrophone` and `Listening.currentOptions: StateFlow<ListeningOptions>` (Task 5) are what `AppGraph`'s `microphones` factory reads in Task 6; `MicCapture(context, earbud: () -> Boolean = { false })` keeps the old call `MicCapture(context)` working in the device tests.
- `SettingsPage` is `{ HOME, CHECKLIST, LISTENING, VOICE, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }` after Task 7. `SettingsData` gets `listening` and `setListening` in Task 5 and `voiceOptions`, `setVoiceOptions` and `tryVoice` in Task 7; `StoredSettingsData` and `SettingsAppTest`'s `FakeSettingsData` implement all five. Task 7 imports only `assertIsNotEnabled` into `SettingsAppTest`, because Task 5 imports `assertIsSelected`.
- `SettingKeys.LISTENING_OPTIONS` (Task 5) and `SettingKeys.VOICE` (Task 7) have distinct names, "listening-options" and "voice".
- `VoiceChoice` and `VoiceOptions(speed, variation, voice)` (Task 7) are what `loadChosenVoice`, `VoiceSession.open`'s `loadSynthesizer`, `SettingsData.tryVoice`, `EvalHooks` and `SessionHooks` use. `VoicePlayer(context, synthesizer)` is the constructor that Task 1 leaves, and Tasks 7 and 9 call it so.
- `Recognizer.secondDecodings` and `keptBoostWords(boostWords)` have defaults in the interface, so the fakes of the JVM tests need no change; `SherpaRecognizer` overrides both.
- `Listening.runTask` (Task 9) has the default task of sub-project 2, so `ListeningTest` keeps its count; `SessionHooks` replaces it and restores it at `-Clear`. The options' collector that Task 6 changes is Task 5's, with `chosenOptions`, `session`, `microphoneOn`, `releaseMicrophone` and `followSafely`.
- `voice-counts` answers `waits= tasks= lastTaskWords=`, which `Get-Counts` reads; `voice-record` and `voice-echo` answer `recording` or `playing` at once, and `-Result` gives the end, which `Invoke-OwnerRecording`, `Invoke-Echo`, `EvalHookTest` and `SessionHookTest` poll. `VoiceTests.assumeModels(vararg engines, optional)` keeps every old call working.
- The hooks' names (`VOICE_SYNTH`, `VOICE_RECORD`, `VOICE_EVAL`, `VOICE_COUNTS`, `VOICE_TASK`, `VOICE_ECHO`) and `hook.ps1`'s commands (`voice-synth`, `voice-record`, `voice-eval`, `voice-counts`, `voice-task`, `voice-echo`) match `EvalHookTest`, `SessionHookTest`, `make-corpus.ps1`, `voice-eval.ps1` and `a71-session.ps1`. `ConvertFrom-HookAnswer` reads the answer's fields `words=`, `substitutions=`, `deletions=`, `insertions=`, `request=`, `read=`, `millis=`, `seconds=`, `pss=`, `parts=` and `twice=`, which `EvalHooks` writes.

**Threads added by Tasks 6 to 9.**

| Call | Thread | Notes |
|---|---|---|
| `HeadsetRoute.open` and `close` | The thread that starts and stops `MicCapture` (the controller's thread) | `open` runs before the `AudioRecord` exists, `close` before it is released |
| The communication device's watcher | The main thread (`context.mainExecutor`) | Calls `onLost` at most once; `MicCapture` then removes the preferred device, which `AudioRecord` allows from any thread |
| `listening.currentOptions.value` in the `microphones` factory | The controller's thread | The value that `Listening` set last on the same thread |
| `SettingsData.tryVoice` | A coroutine of the settings page; the voice loads and speaks on `Dispatchers.IO` | The voice and its player close at the end, also when the coroutine is cancelled |
| The options' collector in `Listening` | The controller's thread | When the earbud setting changes while the microphone records, it stops the recording and starts it again through `followSafely` |
| `EvalHooks` and `SessionHooks` | The hooks' receiver hands each command to a background coroutine; the recording of `VOICE_RECORD`, the load of `VOICE_EVAL` and the check of `VOICE_ECHO` go on in `AppGraph.scope` | The hooks that use the microphone or load a recogniser refuse while a voice session is open; the evaluation's engines are used under a mutex; `VOICE_TASK` sets `runTask`, which is `@Volatile`, under the hooks' lock, and `Listening` calls it on the controller's thread |

**Counts.** The counts up to Task 5 are part A's.

| After | `:app` JVM | `:core:voice` JVM | Voice device tests (`voice-test.ps1`) | Other device tests (`connected-test.ps1`) | Pester (per shell) |
|---|---|---|---|---|---|
| Start (7493c42) | 190 (`ListeningTest` 95) | 116 | 66 | 103, among them `SettingsAppTest` 17 | 99 |
| Task 1 | 191 | 122 | 68 | not run | 99 |
| Task 2 | 204 | 122 | 68 | not run | 99 |
| Task 3 | 221 | 122 | 68 | 104 (`SettingsAppTest` 18) | 101 |
| Task 4 | 229 | 122 | 70 (`AssistTest` 2) | 105 (`SettingsAppTest` 19) | 101 |
| Task 5 | 236 (`ListeningTest` 111) | 122 | 70 | 108 (`SettingsAppTest` 21) | 101 |
| Task 6 | 237 (`ListeningTest` 112) | 131 (`HeadsetRouteTest` 9) | 71 (`MicCaptureTest` +1) | not run | 101 |
| Task 7 | 243 (`VoiceOptionsSettingTest` 5, `SetupChecklistTest` 11 → 12) | 136 (`ModelFilesTest` +1, `VoiceEnginesTest` +2, `VoiceModelPathsTest` +1, `KokoroTextTest` 1) | 74 (`SynthesizerTest` +1, `EchoTimingTest` 2) | 114 (`SettingsAppTest` 27) | 103 |
| Task 8 | 248 (`WordErrorsTest` 5) | 136 | 78 (`EvalHookTest` 4) | not run | 116 (`Corpus.Tests.ps1` 13) |
| Task 9 | 248 | 136 | 80 (`SessionHookTest` 2) | not run; Step 15 runs the voice package through it without the 6 tests that only an emulator may run | 125 (`Session.Tests.ps1` 9) |

**Decisions in Tasks 6 to 9 beyond the design, and where they depart from it.**
- D5: the microphone reads the setting when it starts, so `Listening` restarts a running recording when the setting changes, and the change counts at once, as D4 says. Kestrel declares `MODIFY_AUDIO_SETTINGS`, which r12 advises for `setCommunicationDevice` and which Android grants at install.
- D6: `lexicon-us-en.txt` is not copied. With `lang` set to "en-us", sherpa-onnx 1.13.8 sends every non-Chinese text through espeak-ng, which Kestrel already has, and never reads the lexicon; Kokoro's other three files are 354 MB already. Kokoro returns no samples for a text with Chinese characters (`kokoroCanSay`), because the Chinese path needs the lexicons and logs each unknown word. D6 says to leave out the CC BY voices; `voices.bin` holds them, and Kestrel cannot remove them without hosting its own copy, so it uses only `af_heart`, and the notice says so.
- D6: the echo filter's timing is checked by a device test (`EchoTimingTest`) that plays real replies in both voices and compares the filter's rule with the player's measured timing, instead of a JVM test, because the player's timing for Kokoro exists only on a device. If the test fails for Kokoro, the task stops and reports it; the plan does not change the filter's rule.
- D6: "Kokoro is slower than real time" next to the choice is not written now, because only the A71 session can show it; the controller adds it after the session if the `voice-tests` step shows it.
- D7: the corpus is one file, `corpus/commands.txt`, with seven columns separated by '|'. Its numbers stay as people say them, and `WordErrors` counts "six forty-five" and "6:45" the same; the corpus has no Wi-Fi and no real-looking e-mail address. The generated clips are also compared on the device, through the same hook as the owner's recordings, so both go through the same code; only `-Reveal`, which `voice-eval.ps1 -Show` passes for generated clips, adds the transcript.
- D8: Task 9 adds `Listening.runTask` and three session hooks, which D8 does not name, because the interruptions of a long reply, "stop" while a task runs and the false reactions during the room's hour cannot be measured without them. The room's hour counts Kestrel's reactions (the tasks that it started, exactly, and the starts of waits for a task, replies and changes of mode), not the spotter's raw hits, and the 30-minute battery test runs during its first half, with the screen on and room talk, which is a worst case beside research r10's test with the screen off. The session adds the steps `battery-rules` and `reboots` for the final review of plan 2C-4, and a notification sound during a long reply for the controller's change of D3.
- D8: the times of the `voice` step come from polling, two hook calls a poll, so each can be up to about 1.5 s late, and the result lines say so (review E, M6); a timestamp in the hooks would be more exact, but the corpus step already gives the transcription times exactly.
- D8: `voice-test.ps1` always copies Kokoro, so that the voice device tests can test it. The `install` step turns Kestrel off before the update, because on Android 13 an update ends Kestrel's process with the reason of the owner's stop, which keeps Kestrel off after it.
- Review E's N4 (`WordErrors` in the debug source set) is not done: its tests would then run only in `testDebugUnitTest`, and the counts of the two unit-test variants of `:app` would differ.
- The plan keeps nine tasks; Task 9 holds the session's hooks, its script, its dry run and the documents, because a reviewer cannot judge the script without the hooks that it calls.

**Open questions for the A71 session.** Whether Kokoro speaks faster than real time on the A71; whether Android 13 refuses a microphone service in the background or starts it silenced; how many boost words the guard drops among the owner's app names; what One UI sends for the Galaxy Buds gesture. Whether a shell notification makes a sound on One UI, which the notification check of the `voice` step needs. `listen-check.ps1`'s adaptations to One UI (the tile dump, action 13, the `rec` lines, the swipe that expands the notification) are made after the session, from what it shows.
