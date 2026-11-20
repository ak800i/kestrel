# Plan 4D: the interfaces between writer A's tasks (1 to 5) and writer B's tasks (6 to 11)

Writer A, 2026-11-20. Writer B: use only the names and formats in this file. Where this file and the map differ, this
file wins; writer A's report lists each difference.

## 0. Who changes what

- **`scripts\hook.ps1`** changes only in Tasks 2 to 5 (writer A): each task adds its hooks' commands and parameters
  there, with their help lines and a Pester case in `scripts\tests\Hook.Tests.ps1`. Writer B's tasks call `hook.ps1`
  (through `Invoke-Hook`, as today) and do not change it. If writer B needs another parameter, ask the controller.
- **Kotlin** changes only in Tasks 1 to 5. Writer B's tasks change no Kotlin file.
- **The spec:** Task 1 adds one sentence to 8.7 (R1 and scrolls) and one to 9.6 (a row's title). Tasks 2 to 5 add the new
  hooks to 16.1's paragraph, and Task 5 changes 9.7 and Appendix A's rows "Settings" and "Swipe gestures". Writer B's
  Task 11 changes 5.3, 8.3, 16.4, 18.3, 18.4 and 19.1, and must not replace the sentences that Tasks 1 to 5 add (their exact
  texts are in the plan).
- Every answer below is one line of `name=value` pairs separated by single spaces, with no spaces inside a value, so that
  `ConvertFrom-HookPairs` (scripts\lib\Session.ps1) reads every one of them. A failure is the broadcast's result code 1
  and the text `error: <message>`, which `hook.ps1` throws as today. No answer holds what Kestrel heard, screen text, a
  label, a name or a number from the screen, except the counts, flags, bounds and hashes named below.

## 1. Hashes

Some answers give the hash of a text instead of the text: the first 16 hexadecimal characters, in lower case, of the
SHA-256 of the text's UTF-8 bytes (no byte-order mark, no trimming, no change of case). Kotlin:
`dev.kestrel.app.debug.CommandTags.hash(text: String): String`. Writer B's `scripts\lib\Oracle.ps1` should have the same
function in PowerShell, which works in Windows PowerShell 5.1 and PowerShell 7:

```powershell
# The hash that the hooks give for a text: the first 16 hexadecimal characters of its SHA-256 (plan 4D interfaces, 1).
function Get-TextHash([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $bytes = $sha.ComputeHash([Text.UTF8Encoding]::new($false).GetBytes($Text)) } finally { $sha.Dispose() }
    -join ($bytes[0..7] | ForEach-Object { $_.ToString('x2') })
}
```

Check: `Get-TextHash 'Cancelled'` and `CommandTags.hash("Cancelled")` give the same 16 characters (Task 2's JVM test
pins the Kotlin side to a fixed value; writer B's Pester test should pin the same value: `Get-TextHash 'abc'` is
`ba7816bf8f01cfea`).

The fixed sentences that the map's checks compare (exact texts, from the Kotlin constants at 366d867):

| Check | Where it is | Text |
|---|---|---|
| C75 mute the alarm | `PhoneCommandTexts.MUTE_ALARM`, said | `Android doesn't let me mute the alarm volume.` |
| C87 circle to search | `PhoneCommandTexts.NO_CIRCLE_TO_SEARCH`, said | `Circle to Search isn't available on this phone.` |
| C108 call a number, no call starts | `PhoneCommandTexts.CALL_NOT_STARTED`, said | `The call didn't start. Say it again.` |
| C109 call 1234 | `PhoneCommandTexts.tapCallYourself(spoken("1234"))`, said | `I opened the phone app with 1 2 3 4. Tap Call yourself.` |
| The emergency question | `PhoneCommandTexts.EMERGENCY_QUESTION`, said and shown | `That may call an emergency number. Say 'yes, call' to do it.` |
| C113, C114 cancelled | `ListeningTexts.CANCELLED`, shown | `Cancelled` |

"Said" texts are in `said=`, bar texts in `shown=` (section 2). A command's result text that it both says and shows is in
both. Writer B should read a sentence's exact text from the Kotlin source when the map names another one.

## 2. H1: tagged transcripts (Task 2)

### `voice -Send heard -Text <t> -Tag <k> [-Named] [-Model <id>] [-Live]`

- Sends the transcript `t` to the Orchestrator, as today, and links the tag `k` to it. `-Tag` is optional; without it
  the hook works as before.
- `k`: 1 to 32 characters of `A-Z a-z 0-9 - _`. `hook.ps1` checks it with `ValidatePattern('^[A-Za-z0-9_-]{1,32}$')`
  and throws before it reaches adb.
- `-Model <id>` (`--es model`, the existing parameter of `hook.ps1`) and `-Live` (`--ez live true`), only with `-Tag`:
  the options of the task that the transcript starts, for sub-project 5's `AgentRunner`. When the tagged transcript
  starts a task, Kestrel stores `{model=<id>, live=true}` (only the ones given) under the task's number in the debug-only
  `TaskOptions`, before the task runs; `Listening.taskOptions(task)` gives them once and then forgets them. It is null in
  release builds, where no hook sets it. Nothing in plan 4D reads it, and a tagged command ignores the options. Sub-project
  5B adds further extras below the line `// sub-project 5B` in `VoiceHooks.taskOptions`.
- Answer: `sent heard tag=<k>` (without `-Tag`: `sent heard`, as today).
- Errors:
  - `error: the tag <k> is in use; give each transcript a new tag` (Kestrel keeps the newest 64 tags in its process;
    a tag is new when it is not among them).
  - `error: only the emulator may answer "yes, call"`: on any device whose `Build.HARDWARE` is not `ranchu` or
    `goldfish`, every `voice -Send heard` (with or without a tag) whose text is "yes, call", with or without "Kestrel"
    after it, in any case and punctuation (`EmergencyGuard.confirmed(text)`), is refused and not sent. The same refusal
    holds for `voice-clip -Text` (whose text Kestrel hears), for `screen-has -Template`'s filled text, and for a
    transcript that `voice-expect` matches (it is then dropped, as a stray).

### `voice-state -Tag <k>`

- Answer, always these fields in this order:
  `tag=<k> role=<role> command=<n> task=<n> kind=<kind> outcome=<outcome> asking=<true|false> phase=<phase> detail=<detail> said=<hashes> shown=<hashes> read=<read> latest=<n>`
- `role`:
  - `pending`: the Orchestrator has not handled the transcript yet (it handles it within milliseconds while Kestrel runs).
  - `command`: the transcript started command `n`, or its answer was no answer and Kestrel read it as a new utterance
    that started command `n`.
  - `task`: it started task `task=<n>` (for example "Kestrel, open the settings." with `-Named`); `command=0` then, and
    sub-project 5's `agent-task -Result -Tag k` finds the task by this number.
  - `answer`: it answered the question of command `n`, as "1" after "Which one?" or "no" after the emergency question.
  - `cancel`: it cancelled the running command `n`, as "stop", "cancel", or "no" during a countdown.
  - `none`: it started, answered and cancelled nothing (bare speech that Kestrel ignored, a command that came while
    another ran, a request of Kestrel's own such as "stop listening", or Kestrel off). `command=0` then.
- `command`: the command's number (as in `voice-state -Number`), or 0.
- `task`: the number of the task that the transcript started (the Orchestrator's task number), or 0.
- `kind`: the command's kind, as in `voice-state` (`Scroll`, `ScrollToEnd`, `Bare`, `Radio`, `Call`, ...), or `none`.
- `outcome`: `running`, `done`, `failed`, `ignored`, `cancelled`, `unknown` (older than the 16 commands that Kestrel
  keeps) or `none` (command 0, or not started yet).
- `asking`: whether command `n` waits for the answer to its question now.
- `phase`: `pending` (role pending), for a task `running` while it runs and `ended` after, `none` (command 0), `countdown` (command `n` counts down now, as before a call),
  `question` (it waits for an answer now), `running` (it runs and does neither), `ended`.
- `detail`: the route that the command took, or `none`. Today only the radios set it: `panel` (Wi-Fi through Android's
  Internet panel), `tile` (Bluetooth through its Quick Settings tile), `page` (Bluetooth through its settings page).
- `said`: the hashes (section 1) of what Kestrel said for command `n` (its announcements, its questions and its result's
  spoken text), oldest first, separated by commas, at most the newest 8; `none` when it said nothing.
- `shown`: the same for the feedback bar's texts of command `n` (its shown texts, its questions, and `Cancelled` when it
  was cancelled). Not the countdown's ticking text, which the command keeps on the bar itself.
- `read`: how Kestrel's `Requests` read the transcript when it was sent, without the state "after the name" (so while Kestrel waits for a
  task after "Kestrel" alone, `read=other` may still start the task): `other` (a command or bare speech), `stop`, `task`, `name`,
  `wakeup`, `pauselistening`, `turnoff`, `opensettings`, or `none` (no words). Lower case of the request's class name.
- `latest`: the latest command's number, as in `voice-state -Number`.
- Errors: `error: no transcript has the tag <k>` (never sent, or older than the newest 64 tags).
- The record of said, shown and detail starts when the first tagged transcript of Kestrel's process is sent; a command
  before it has `said=none shown=none detail=none`.

### `voice-clip -Quiet`

- Makes the clip microphone, with nothing queued, Kestrel's microphone from Kestrel's next start (or at once when Kestrel
  already listens through it), so that during the text pass no sound of the room reaches Kestrel. `voice-clip -Clear`
  gives Kestrel its own microphone back from its next start, as today.
- Answer: `quiet: Kestrel hears only the silent clip microphone from its next start, until voice-clip -Clear or the end of its process`
  (or, when it already listens through the clip microphone: `quiet: Kestrel hears only the silent clip microphone, until voice-clip -Clear or the end of its process`).
- A restart of listening (`voice -Send off`, then `voice -Send toggle`) makes it count.
- The clip microphone with nothing queued delivers silent 20 ms frames in real time, so a session (sub-project 5's sitting
  2 too) can run on it without room sound; Task 2's device test `quietMakesTheClipMicrophoneKestrelsAndItDeliversSilenceWithNothingQueued`
  checks it.

## 3. H6: notices (Task 2)

### `notices [-Clear]`

- Answer: `notices=<n> kinds=<kinds>` (today `notices=<n>`; `kinds` is new). `kinds`: the kinds of Kestrel's notices
  about problems in the shade, sorted, separated by commas, or `none`: `battery` (Kestrel stopped because its battery use
  is restricted: the stop notice with `StopReason.BATTERY_RESTRICTED`), `stopped` (the stop notice for any other
  reason), `accessibility` (its accessibility service is off), `settings` ("Open Kestrel's settings"). With `-Clear`
  the hook cancels them first, as today, and answers `notices=0 kinds=none`.
- The map's S15 letter: `battery` in kinds → b; only `accessibility` → a; another kind → o; `none` → n.

### `check-notice -On true|false` and `check-notice`

- `-On true` posts Kestrel's own notification "Kestrel check" (title `Kestrel check`, a two-line text, so that One UI
  offers to expand it; channel `kestrel-check`, importance default, no sound); `-On false` cancels it; without `-On` it
  changes nothing.
- Answer: `checkNotice=shown` or `checkNotice=gone` (whether it is in the shade now, from Kestrel's own active
  notifications).
- Errors: `error: Kestrel may not post notifications; allow them first` (no POST_NOTIFICATIONS on Android 13 and later).
- In the shade, `screen-has -Package com.android.systemui -Contains 'kestrel check'` finds its row (section 5).

## 4. H2, H5 and H7: speech through the air (Task 3)

### `voice-expect -Text <t> -Tag <k> [-Seconds <n>] [-NoRun]`

- Starts an expectation: for the next `n` seconds (default 15, 1 to 60), each transcript of Kestrel's voice session is
  compared on the phone with `t`, with `WordErrors.count(t, heard)` (voice-eval's comparison: case and punctuation do not
  count, numbers up to 99 count the same in words and digits).
- The first transcript with `errors * 2 <= words` (at most half the expected words wrong) matches. Without `-NoRun` it
  goes to the Orchestrator with the tag `k`, as a tagged `voice -Send heard` does, and runs as usual; with `-NoRun` it is
  only compared and resolved (as `voice-eval -Group appendix` does) and never reaches the Orchestrator.
- Every other transcript while the expectation holds is dropped (it never reaches the Orchestrator) and counted as
  `stray`; a stray whose words are a run of the expected words, in order, also counts as `split`. Without `-NoRun`,
  the expectation holds until its match or its end; with `-NoRun`, until its end, so that the rest of a split utterance
  never acts. A new `voice-expect -Text` may start once the previous one matched or ended.
- Kestrel must listen with its voice session open (`voice-state` `session=open`).
- `hook.ps1` checks `-Tag` as above; `-NoRun` is a switch.
- Answer: `expecting tag=<k> seconds=<n>`.
- Errors: `error: Kestrel's voice session is not open`; `error: an expectation waits; ask for its answer with -Result -Tag <k>`;
  `error: the tag <k> is in use; give each transcript a new tag`; `error: add -Text with words`;
  `error: only the emulator may answer "yes, call"` (as in section 2, for `-Text` without `-NoRun`).

### `voice-expect -Result -Tag <k>`

- While the expectation waits and nothing matched: `waiting tag=<k>`.
- After a match or the end of the time, always these fields in this order:
  `tag=<k> matched=<true|false> words=<w> errors=<e> stray=<s> split=<p> speechEnded=<ms> closed=<ms> transcribed=<ms> run=<true|false> command=<n> kind=<kind> outcome=<outcome> right=<1|0|->`
  - `words`: the expected words. `errors`: substitutions + deletions + insertions of the match; without a match, those
    of the stray with the fewest errors, or `words` when no stray came.
  - `speechEnded`, `closed`, `transcribed`: the phone's clock (`SystemClock.elapsedRealtime`, milliseconds; the same
    clock as `/proc/uptime` and `voice-times`) of the match: the end of its speech as the speech detector found it, the
    moment that the end-of-speech pause ended and the utterance went to the recogniser, and the moment that its text
    reached Kestrel. 0 without a match. Spec 5.3's transcription time is `transcribed - closed`.
  - `run=true` (without `-NoRun`): `command`, `kind` and `outcome` as `voice-state -Tag <k>` gives them, which also
    gives `said`, `shown`, `phase` and `detail` for the tag; `right=-`. Right after the match, the Orchestrator may not
    have handled the tagged transcript yet: then `voice-state -Tag <k>` gives `role=pending`, and this answer
    `command=0 kind=none outcome=none`. Wait until `voice-state -Tag <k>` no longer says `role=pending`, then read again.
    A match that is "yes, call" on a phone is dropped (`command=0`); its tag stays, and `voice-state -Tag <k>` answers
    `role=none command=0`.
  - `run=false` (with `-NoRun`): `command=0`, `kind` the kind of command that the transcript parses to (`CommandParser`,
    or `none`), `outcome=none`, and `right` 1 when the transcript leads to the same action as `t` with the screen's
    labels (`Commands.sameAction`, as voice-eval's `command=`), 0 when not, `-` when `t` is no instant command.
- The answer is kept until the next `voice-expect -Text`; a second `-Result` gives the same answer.
- Errors: `error: no expectation has the tag <k>`.

### `voice-times` (H5)

- Two marks are added at the end, before the hook's own `foreground=` and `demoted=`:
  `... settingsInFront=<b> segmentClosed=<ms> transcribed=<ms> foreground=<..> demoted=<..>`.
  `segmentClosed`: when the latest transcribed utterance's end-of-speech pause ended (the endpointer let it go to the
  recogniser). `transcribed`: when its text reached Kestrel. 0 before the first one. The existing fields keep their
  names, order and meaning.

### `voice-route` and `voice-record -Result` (H7)

- `voice-route` adds two fields at the end: `... loudest=<percent> rms=<dBFS> frameRms=<dBFS>`. `rms`: the RMS level of
  every sample since the last `voice-route`, in dBFS with one decimal (for example `-31.4`), `none` when no frame came;
  `frameRms`: the RMS level of the loudest 20 ms frame since then. Silence is `-120.0` at the lowest. Like `loudest`, both
  start again at each `voice-route`. While Kestrel has not recorded with its own microphone in this process,
  `voice-route` answers `earbud=<on|off> microphone=none` without `loudest`, `rms` and `frameRms`; treat a missing value
  as no level.
- `voice-record -Result` adds the same two values at the end of its answer:
  `recorded <s> s, loudest <p> %, silenced=<b>, rms=<dBFS> frameRms=<dBFS>`.
- The calibration of the map's `Set-SpeakerLevel`: read `voice-route` once to start the window, play the clip, read
  `voice-route` again and use `frameRms`. Writer B chooses the target level.

## 5. H3 and H4: the screen (Task 4)

### `screen-has [filters] [-Template <text> -Tag <k>]`

- Reads Kestrel's current snapshot of the screen (as `snapshot` does) on the phone and answers only counts, numbers,
  bounds, flags and hashes. Needs Kestrel's accessibility service.
- Filters, all optional; an element matches when it passes every filter given:
  - `-Package <p>`: its package is `p`.
  - `-ViewId <s>`: the part of its view ID after `:id/` contains `s` (case-sensitive), as `qs_tile` or `url_bar`.
  - `-Label <t>`: one of its label, text, content description or hint is `t` after Kestrel's normalising (case,
    punctuation and spaces do not count).
  - `-Contains <t>`: one of those contains `t` after normalising, as whole words in order.
  - `-Class <c>` (in `hook.ps1`: `-ClassName <c>`, because `class` is a keyword of PowerShell; the extra is `--es class`):
    its class name is `c` or ends with `.c`, as `SeekBar` or `android.widget.SeekBar`.
  - `-State <list>`: comma-separated, all must hold: `checked`, `unchecked`, `selected`, `focused`, `editable`,
    `password`, `scrollable`, `clickable`, `actionable`, `expandable` (offers expand), `collapsible` (offers collapse),
    `dismissable`, `slider`.
  - Only elements that a finger can reach count (`ScreenSnapshot.reachable`), as for the commands.
- Answer, always these fields in this order:
  `count=<n> number=<overlay number|none> bounds=<l,t,r,b|none> first=<hash|none> app=<package|none> appTitle=<hash|none> windows=<n>`
  - `number`: the number that the numbers overlay shows on the first match now (`none` while no numbers show or it has
    none).
  - `bounds`: the first match's bounds in screen pixels, for an `input tap` at its centre.
  - `first`: the hash of the first name of the first actionable element inside the first match (its label, text or
    description), so that a list's first visible row can be compared before and after a scroll; `none` when there is none.
  - `app`, `appTitle`: the package and the hash of the title of the active application window (the page in front;
    Android gives an activity's title as its window's title, as "Display" for Settings' page Display), whatever the
    filters; `none` without one.
  - `windows`: how many windows the snapshot has.
- `-Template '<text with {label}>' -Tag <k>`: after the count, sends the template, with `{label}` replaced by the first
  match's first name, as a tagged transcript (`voice -Send heard -Tag k`), so that a label of the owner's screen never
  reaches the PC, as for the map's C21 ("long press {label}"). The answer then ends with ` sent=<k>`.
- Sub-project 5B adds a kind of match below the line `// sub-project 5B` in `ScreenQuery`'s filter check; "text contains" exists already as `-Contains`.
- Errors: `error: Kestrel's accessibility service is off`; `error: nothing on the screen matches` (only with
  `-Template`); `error: add {label} to the template`; the tag errors of section 2.

### `field -Expected <t> [-ViewId <s>]`

- The focused text field (or, with `-ViewId`, the first reachable editable element whose view ID contains `s`), read live
  through `TextEditor.read`.
- Answer: `field=found equal=<true|false> length=<n> start=<n> end=<n> password=<true|false>`, or `field=none` when there
  is no such field. `equal`: its text is exactly `t` (a line break in `-Expected` stays a line break through `adb shell`'s
  single quotes; check it once on kestrel-api37 before a check relies on it). `start` and `end`: the selection (the cursor when equal). A password field compares nothing:
  `equal=false` and only its length.

### `state` (H4)

- Three fields are added at the end of the answer while the screen layer runs:
  `... feedback=<on|off> gridSize=<rows>x<columns>|none magnification=<scale>@<x>,<y>|none keyboard=<shown|hidden>`.
  `gridSize`: the grid's rows and columns while it shows (for example `8x4`); "more squares" raises the columns.
  `magnification`: the scale with two decimals and the centre in screen pixels while the screen is magnified (scale
  above 1), as `2.00@540,1200`; `none` otherwise. `keyboard`: whether an input method's window is on the screen in
  Kestrel's current snapshot.

## 6. Task 5: the One UI fixes and the clipboard

### What changes on the A71 (no new hook name except `clipboard`)

- Bluetooth: `voice-state -Tag` gives `detail=tile` when One UI's tile toggled it, `detail=page` when Kestrel went on to the
  settings page (also after a tile click that changed nothing within 6 s).
- Ring and notification volumes and "unmute the ringer" change on One UI; Kestrel shows the volume panel after the change.
  "Mute the alarm" still ends `outcome=failed` with `said` = hash of `Android doesn't let me mute the alarm volume.`
  (intended; count it as a pass).
- Samsung Notes: while Samsung Keyboard shows, the note's body counts as the focused field; Kestrel focuses it before the
  first edit, and "new line" works there.
- "Swipe from the left/right edge" (or the bottom edge) with the navigation bar's buttons, when the screen did not change:
  `outcome=done`, and `said` and `shown` hold the hash of
  `The navigation bar has buttons, so this swipe doesn't go back. Say 'go back'.` (left, right) or
  `The navigation bar has buttons, so this swipe doesn't go home. Say 'go home'.` (bottom). In gesture mode nothing changes.

### `clipboard -Save | -Restore | -Clear` (exactly one)

- `-Save`: needs Kestrel's settings in front with the input focus (start them with
  `am start -n dev.kestrel/dev.kestrel.app.MainActivity`, wait for `voice-times`' `settingsInFront=true`, and keep the
  shade and dialogs away), because Android 13 lets only the app with the focus read the clipboard. Keeps a copy in
  Kestrel's memory (never on disk, never in an answer). Answers:
  - `clip=text saved=true` or `clip=other saved=true` (a picture, a file, anything that is not text): saved.
  - `clip=unknown saved=false`: the clipboard was empty, or Android refused the read (it answers both alike), or Kestrel's
    settings were not in front. Nothing is saved, and a later `-Restore` changes nothing.
  - `clip=held saved=false`: a copy from an earlier `-Save` was not put back yet. It stays held, and the next `-Restore`
    puts that one back.
- `-Restore`: puts the held copy back (allowed from the background). Answers:
  - `restored=true`: put back, and forgotten.
  - `restored=failed`: Android refused it (as for a picture, whose read access ended when the check's copy replaced it).
    The copy is forgotten, because Android never gives that access back, so that a later `-Save` takes the owner's
    newer clip; the clipboard holds the check's text.
  - `restored=false`: no copy was held, so nothing changed.
  A restore never empties the clipboard. A held copy is also lost when Kestrel's process ends (a reinstall, a crash).
- `-Clear`: empties the clipboard. Answer: `cleared=true`. For the emulator's tests; the A71 scripts should not use it.
- `hook.ps1` throws `clipboard needs exactly one of -Save, -Restore and -Clear.` before it reaches a device.
- The rule for writer B: every check that copies (C92, C117, the Samsung Notes step) runs `clipboard -Save` before and
  `clipboard -Restore` after, also after a failure or Ctrl+C. When `-Save` answered `unknown` or `other`, or `-Restore`
  answered `failed`, the result line says that the clipboard may hold the check's test text (without the content), and the
  owner's start prompt says that a copy check runs. `clip=held` is no failure: the owner's earlier copy is still held.
- No device test touches the owner's clipboard: the hook's test `ClipboardHookTest` is in `dev.kestrel.app.debug`, runs
  only on an emulator and puts back the clip that the device had. No A71 script may run that package.

### The A71 checks of these fixes

Task 5 of the plan lists them, after its point 6 (Bluetooth, the volumes, collapse, Samsung Notes, the edge swipe, the
copy checks), with what passes and what is put back. Writer B's Task 8 runs them through the hooks above.

## 7. The test counts after writer A's tasks

As "N + k" from the counts before each task: Task 1 `:app` JVM + 2; Task 2 `:app` JVM + 8, device tests of `:app` + 3,
Pester + 1; Task 3 `:core:voice` JVM + 3, `:app` JVM + 3, device + 1, Pester + 1; Task 4 `:app` JVM + 3, device + 1,
Pester + 1; Task 5 `:app` JVM + 4, `:core:screen` JVM + 1, device + 1, Pester + 1. In all: `:app` JVM A₀ + 20 (with the
debug-only JVM tests of `app/src/testDebug`), `:core:voice` V₀ + 3, `:core:screen` S₀ + 1, device O₀ + 6, Pester T₀ + 4 in
each shell. Writer B's Tasks 6 to 11 start from these.

## 8. Files of Tasks 1 to 5 that writer B's steps may also change

`scripts/hook.ps1` (only to read; ask the controller before changing it), `docs/superpowers/specs/2026-11-12-kestrel-design.md`
(16.1, 8.7, 9.6, 9.7 and Appendix A's rows "Settings" and "Swipe gestures" change in Tasks 1 to 5), and
`scripts/tests/Hook.Tests.ps1` (Tasks 2 to 5 each add one `It` at the end of its `Describe`).
