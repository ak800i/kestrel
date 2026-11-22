# Looking Closer, Speech Checks and the Sittings (Plan 5C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Kestrel's agent its eyes and finish sub-project 5: on-device OCR with PP-OCRv5 (`:core:perception`), screenshots of the app in front on Android 13 and later (`ScreenCapture`), the `EyesPort` that `look_closer` uses with its masks, numbers, screenshot question and approvals, the speech checks of the agent's phrases, the live side of the debug hooks, and the session script `scripts\a71-agent.ps1` with the owner's two sittings, whose results go into the spec and the README.

**Architecture:** `:core:perception` is an Android library that holds only plain Kotlin and ONNX Runtime's Java API (`ai.onnxruntime`): the image, the detector's and the recognisers' pre- and post-processing, CTC decoding, the dictionaries and the choice of recogniser, so that its JVM tests check every step against outputs that RapidOCR's Python reference saved once, and a device test compares the whole pipeline with the reference's boxes and texts. `:core:screen` gets `ScreenCapture` (the window capture from API 34, the display capture with Kestrel's overlays hidden on API 33) and `ScreenSession`'s `hideAllForCapture`, `showBadges` and `hideBadges` below its 5C anchor. `:app` implements plan 5A's `EyesPort` as `AppEyes` and stores the screenshot approvals, and plan 5B's `AppGraph` seams carry both to the agent. Tasks 7 to 12 add the speech checks, the live hooks, the session script with the two sittings, and the documents.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.13.0, `com.microsoft.onnxruntime:onnxruntime-android` 1.28.0, the Android SDK's `AccessibilityService.takeScreenshot` and `takeScreenshotOfWindow`, `Bitmap`, `Canvas` and `HardwareBuffer`, JUnit 4, AndroidX Test, Jetpack Compose's test rule, PowerShell 7.3 and Windows PowerShell 5.1 with `System.Drawing`, Pester 6.2.0 and PSScriptAnalyzer 1.25.0.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. One new dependency ships in the release app: `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` (MIT) as an `implementation` of `:core:perception`, pinned by the audit and by Gradle's verification metadata (plan 5C). Nothing else new ships; `java-test-fixtures` adds no dependency, and the test fixtures use only Kotlin, coroutines and kotlinx.serialization, because the audit checks the debug runtime classpath (review C M13). No Python, Pillow, piexif or exiftool in any script.
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`. Every call that the A71 (API 33) lacks (`takeScreenshotOfWindow`, `READ_MEDIA_VISUAL_USER_SELECTED`, `getTooltipText` is API 28 and fine) has a check of the API level.
- No dependency-injection framework; `AppGraph` wires by hand. `:core:agent` and `:core:safety` stay plain Kotlin. Every new coroutine scope (the task runner, the agent's loop, the memory and routine stores, the notification listener, the OCR engine) reports its failures through `graph.errors`, by class only (D32 item 48).
- All network traffic goes through `NetworkGateway`; no new module names a type or calls a method that the audit flags, `audit/rules.txt` gets no new network exception, and `Geocoder`, the network and fused location providers and Google's location client are never used.
- No request to any provider's API outside the owner's second sitting (D30): not in a plan's step, a test or a script; every agent test uses the scripted model, and a task given through H1 refuses to run without it unless a live sitting is open (D28).
- Every device check is automated (the owner's rule of 2026-11-20): a script gives each task or command as text through a debug hook and checks the result on the device; speech is checked with generated clips through the PC's speakers; the owner is asked only for sign-ins and links, one real touch and answers only he can give, in one short step at the start of a sitting (D30); the scripts unlock the A71 with the test PIN through plan 4D's unlock function (Q6; review C3 nit 1). Checks match a result to the task number of the text that they sent, never to the newest task. The OCR files and the Python reference are fetched once by plan 5C's writer from ModelScope, Hugging Face and GitHub, which are not providers' APIs.
- Never log, print or put into a test's output the owner's words, screen text, a notification, an SMS, an e-mail, a contact, a prompt, a reply, a tool's arguments, a screenshot, a vault value, the A71's PIN, a token or a key; `toString` of anything that carries them prints only kinds, counts or lengths (D25). Failures are logged by their classes and stack frames only. Screenshots stay in memory.
- Device tests never touch the owner's contacts, calendar, messages, photos, profiles or spending; they use test-only data and remove it, also after a failure.
- Test only on kestrel-api37 (check `adb -s <serial> emu avd name` in the same command as each device command); never on the S23+ or the AVD `zoran`; the A71 only in the two sittings (D30). The main session owns `.superpowers/sdd/emulator.lock` (process rule 9); subagents never create, wait for or delete it. Only one Gradle build at a time. Gradle only through `scripts\gradle.ps1`, stopped with `--stop` at the end of each task; device tests only through `scripts\connected-test.ps1` (with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) or the scripts that a plan names; installs only through `scripts\install.ps1`.
- No device test, script or session step ever places a call to an emergency number or answers "yes, call" (process rule 10); test mode blocks every agent call, and the agent hands every call over in every mode (D13). The fixtures use only fictional addresses and the fictional number of D28.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7, or declare `#Requires -Version 7.3` as `hook.ps1` does; `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan files are named with the day they are written (`Get-Date -Format yyyy-MM-dd`): `docs/superpowers/plans/2026-11-22-agent-5a-core-safety.md`, `2026-11-22-agent-5b-datatools-app.md` and `2026-11-22-agent-5c-perception-session.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`. This plan is `docs/superpowers/plans/2026-11-22-agent-5c-perception-session.md`.
- Every step on the A71 runs only in the owner's sittings or within process rule 13's limits (no call, no message, no change to his accounts, every setting put back, no heard words in any result), ends with the A71 locked (`adb shell input keyevent KEYCODE_SLEEP`, process rule 15), and puts "Stay awake" back also after a failure or Ctrl+C.
- Barge-in over the A71's loudspeaker is a limit (the owner's choice `limit` of plan 4E): Kestrel's own voice at the owner's assistant volume drowns his "Kestrel", so no speech check of the agent's phrases counts on barge-in over the loudspeaker.

D-numbers refer to the design `.superpowers/sdd/plan-5-design.md`. Code comments call it "design D… of sub-project 5", as plan 5A's code does. r-numbers refer to `.superpowers/sdd/research/`.

## Before you start

- The code is `origin/main` after plan 5B and its final fixes, or later commits of this plan. Plan 5A is as built at `c5297c5` and its final fixes; plan 5B adds the names that this plan uses (its interfaces file, section 4): in `AppGraph.kt`, below `// sub-project 5B: agent`, `internal var agentEyes: EyesPort = NoEyes`, `internal var showAgentBadges: suspend (ScreenSession, List<dev.kestrel.agent.port.Badge>) -> Unit` and `internal var hideAgentBadges: suspend (ScreenSession) -> Unit`, which `AgentRunner` and `AppScreenPort` read at each task and step; `:core:agent` and its test fixtures on `:app`'s classpath; `ScreenSession.cardShowing` and the other names of its section 2. Task 6 also needs, below the same anchor, `internal var screenshotApproved: suspend (packageName: String, providerId: String) -> Boolean` and `internal var approveScreenshots: suspend (packageName: String, providerId: String) -> Unit`, which plan 5B's `SettingsMemoryStore.screenshotApproved` and `approveScreenshots` call at each use (writer 5C-A's proposal to the controller). Before Task 6, check with `Select-String` that `AppGraph.kt` has all five; if one is missing, stop and ask the controller.
- The counts at the start of this plan are those at the end of plan 5B. The controller gives their values in each task's brief: `:app` JVM tests A₀ (with the debug-only JVM tests of `app/src/testDebug`), `:core:voice` JVM V₀, `:core:screen` JVM S₀, Pester T₀ in each shell, and the device tests of `:app` by package: `dev.kestrel.app.screen` D_S, `dev.kestrel.app.settings` D_T and `dev.kestrel.app.agent` with its subpackages D_A. `:core:perception` has no tests yet (0). Each task gives its counts as these values plus what it adds, "N + k", where N is the count before the task; before each task, compare the counts with the ones that the earlier tasks left. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file. If a text is missing, stop and ask the controller.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task. Only one Gradle build may run on this computer at a time (process rule 8b); if another agent's build runs, wait for it to end.
- Tasks 1, 3, 4, 5 and 6 use the emulator. The controller holds the emulator's lock for each task that uses it (process rule 9) and says so in the task's brief; never create, wait for or delete `.superpowers\sdd\emulator.lock` yourself. If the brief does not say that the controller holds the lock, ask the controller before the device steps. Every device command starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. Never send a command to a connected phone.
- No task of Tasks 1 to 6 uses the A71 or a provider's API. Task 1 downloads the OCR files from ModelScope, Hugging Face and GitHub through `scripts\push-models.ps1`, which checks their SHA-256 hashes.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

- Plan 5A (the agent's core, as built): the ports, `LookCloser`'s three steps on the agent's side (which call to run, the regions, the masks' choice, the screenshot question's words and answers, the numbers' question), the observation's OCR lines and `MemoryStore`'s approval calls.
- Plan 5B (the data tools, the screen's safety, the Orchestrator, the app and the fixtures): `AgentRunner`, `AppScreenPort` (which calls `showAgentBadges` and `hideAgentBadges`), `AppOwnerPort` (with the rule of D32 item 17 for the screenshot question), `SettingsMemoryStore` (which calls the approval seams), every other `:testapp` fixture, the debug hooks below the 5B anchors, `scripts\task-check.ps1` except its `-Live` part, and its spec changes.
- Tasks 7 to 12 of this plan are written by a second writer and hold the speech checks, `agent-live`, `task-check.ps1 -Live`, `scripts\a71-agent.ps1` with the two sittings, the README, `THIRD_PARTY_NOTICES.md`, AGENTS.md, and the spec's 17, 18.5, 19.1 and the measured times of 11.6 and B19.

## Changes to the design that this plan makes

- Both dictionaries are files of the manifest, from PaddleOCR's repository at commit `8ee4a20cc64fe2d73adf96110f9c9b1c3b7b85a5` (`ppocrv5_latin_dict.txt`, 836 lines, and `ppocrv5_cyrillic_dict.txt`, 850 lines), instead of the Cyrillic one from the model's `character` metadata: ONNX Runtime's Java API garbles the metadata's two characters beyond U+FFFF (U+1D462 and U+1D713), so the metadata read through it had 847 lines and the load check refused the model. The file is byte for byte the metadata's text (the writer compared them).
- The Latin recogniser is iText Research's file, as the design prefers (open question C3): at the pinned commit `bb953c8fd9ebf04f344eb09c9c94bb367c39033c` its repository is tagged `license:apache-2.0`, its model card's front matter says `license: apache-2.0`, and the file has the hash that r48 recorded.
- A recogniser's reading that holds nothing but spaces counts as score 0: on the Serbian Cyrillic fixture the Latin recogniser read every line as one or two spaces with a mean score of 0.87 to 0.996, so D20's rule ("the Cyrillic recogniser reads the box when the Latin score is below 0.8") would never have read Cyrillic text without Cyrillic in the tree, and with it the spaces won four of seven lines.
- The detector's box score is the mean of the probability map over the connected part's own pixels, not over its axis-aligned box: the reference scores the turned rectangle that OpenCV fits, which for a part that leans by a few degrees holds fewer empty corners than an axis-aligned box, and on the saved maps the box mean dropped a line that the reference kept. With the part's mean, Kotlin finds every line of the five fixtures with the reference's texts.
- The golden data are three small text files instead of JSON: `golden.txt` (the reference's 36 lines with their boxes), `maps.txt` (the detector's probability maps of two fixtures at a quarter of their size, in ten steps, run-length coded) and `reference.txt` (the preprocessing of two synthetic test images, which both sides compute from the same formula, and the recognisers' best class per step for five boxes). They hold no one's data.
- Every OCR region is read on its own with the region rule (the longer side at most 960 pixels, enlarged at most twice), not only a target: the agent's regions are small unlabelled controls and large text-less areas, and a region inside another one is skipped.
- `OverlayHost` gets one internal property, `visibleViews`, which `HiddenOverlays` needs; plan 5B's interfaces file names no change of `OverlayHost.kt`. The overlays are hidden by making them transparent, not by removing their windows, so that Android's window list, which the capture compares before and after, stays the same; the overlays that do not cover the whole screen (the feedback bar) are painted black as well, and the full-screen ones (numbers, labels, grid) are not, because their bounds are the whole screen.
- A window capture's origin allows for a window's shadow: when the image is larger than the window's bounds, the same margin lies on each side.
- `ScreenCapture.capture` has a parameter `wholeDisplay`, so that the device tests run the display capture of API 33 on kestrel-api37 too.
- `AppEyes` refuses the home screen and the recent apps itself (plan 5A's note), with a `<queries>` entry for the home intent below the manifest's 5C anchor.
- The OCR engine is opened for each `read` and closed after it: no scope of its own and no models in memory between steps; a failure goes to `graph.report` by its classes (D32 item 48) and counts as "OCR unavailable" for that call.
- `ScreenSession.showBadges` pauses "show numbers" while the agent's numbers show and brings it back in `hideBadges`, so that the screen watcher does not number the screen over the agent's numbers.
- The device test of `look_closer`'s three steps (Task 6) builds its own small `ScreenPort` over the screen session, so that it depends on no class of plan 5B's beyond the test fixtures.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `OcrImage`, `Detector.inputSize`, `Detector.input`, `Recogniser.input`, `TextBoxes`, `CtcDecoder`, `Dictionary`, `OcrEngine.choose` | any | pure |
| `OcrEngine.open`, `read`, `detect`, `recognise` | a background thread (`Dispatchers.Default` in `AppEyes`) | blocking and CPU-bound; one call at a time per engine |
| `ScreenCapture.capture` | any caller; it runs on `Dispatchers.Main` and does its pixel work on `Dispatchers.Default` | one capture at a time (a mutex) |
| `ScreenSession.hideAllForCapture`, `showBadges`, `hideBadges` | any caller; they switch to `Dispatchers.Main` | `HiddenOverlays.close` on the main thread |
| `AppEyes` | the agent's coroutine | suspend functions; bitmaps are recycled when the agent closes the capture |
| `ScreenshotApprovals` | any coroutine | a mutex around each write |

## File structure

| File | Responsibility |
|---|---|
| `models/manifest.json`, `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelManifestTest.kt`, `scripts/push-models.ps1`, `audit/rules.txt`, `gradle/verification-metadata.xml`, `core/perception/build.gradle.kts`, `app/build.gradle.kts`; spec 13.2 | Task 1: the OCR files, the `ocr` engine, ONNX Runtime in `:core:perception` |
| `core/perception/src/main/kotlin/dev/kestrel/perception/` (`OcrImage.kt`, `Dictionary.kt`, `CtcDecoder.kt`, `TextBoxes.kt`, `Detector.kt`, `Recogniser.kt`, `OcrFiles.kt`, `OcrEngine.kt`), its tests and `src/test/resources/ocr/`; `scripts/draw-ocr-fixtures.ps1`, `app/src/androidTest/assets/ocr/` (five PNG files and `golden.txt`); spec B19 and B64 | Task 2: the OCR pipeline and its golden data |
| `testapp/src/main/kotlin/dev/kestrel/testapp/OcrCanvasActivity.kt`, `testapp/src/main/AndroidManifest.xml`, `app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/OcrDeviceTest.kt` | Task 3: the canvas fixture and OCR on the device |
| `core/screen/src/main/kotlin/dev/kestrel/screen/capture/ScreenCapture.kt`, `overlay/HiddenOverlays.kt`, `overlay/OverlayHost.kt`, `ScreenSession.kt`; `core/screen/src/test/kotlin/dev/kestrel/screen/capture/ScreenCaptureRulesTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt`; spec 9.4, B10 and B65 | Task 4: screenshots and the hidden overlays |
| `app/src/main/kotlin/dev/kestrel/app/agent/eyes/AppEyes.kt`, `AppGraph.kt`, `app/src/main/AndroidManifest.xml`, `ScreenCaptureTest.kt` | Task 5: the `EyesPort` |
| `app/src/main/kotlin/dev/kestrel/app/agent/eyes/ScreenshotApprovals.kt`, `AppGraph.kt`, `SettingsData.kt`, `SafetyPage.kt`, `strings.xml`, `app/build.gradle.kts`, `SettingsAppTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/LookCloserTest.kt`; spec 11.6 | Task 6: the approvals, the numbers and `look_closer` on the device |
| `corpus/checks.txt`, `scripts/lib/AgentChecks.ps1` and its tests | Task 7: the speech checks (second writer) |
| `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHooks.kt`, `scripts/hook.ps1`, `scripts/task-check.ps1`, `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentLiveHookTest.kt` | Task 8: `agent-live` and `task-check.ps1 -Live` (second writer) |
| `scripts/a71-agent.ps1`, `scripts/tests/A71Agent.Tests.ps1` | Tasks 9 and 10: the two sittings (second writer) |
| `README.md`, `THIRD_PARTY_NOTICES.md`, `AGENTS.md`; the spec's 17, 18.5, 19.1 and the measured times | Tasks 11 and 12 (second writer) |

---

### Task 1: The OCR files, the `ocr` engine and ONNX Runtime in `:core:perception`

**Files:**
- Modify: `models/manifest.json` (three models at its end)
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt` (`Engine.OCR` and the KDoc)
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelManifestTest.kt` (one test)
- Modify: `scripts/push-models.ps1` (its help and its last line only)
- Modify: `audit/rules.txt`, `gradle/verification-metadata.xml`, `core/perception/build.gradle.kts`, `app/build.gradle.kts` (below `// sub-project 5C: dependencies`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (13.2)

**Interfaces:**
- Consumes: `ModelManifest`, `Model`, `ModelFile`, `Engine` and `ModelFiles` of `:core:voice` as they are; `libs.onnxruntime.android` (`gradle/libs.versions.toml`, `onnxruntime = "1.28.0"`, exists); the anchor `// sub-project 5C: dependencies` of `app/build.gradle.kts`.
- Produces: `Engine.OCR` (`@SerialName("ocr")`); the manifest's models `ocr-ppocrv5-det` (`ocr-ppocrv5-det/ch_PP-OCRv5_det_mobile.onnx`), `ocr-ppocrv5-latin` (`ocr-ppocrv5-latin/latin_PP-OCRv5_mobile_rec.onnx` and `ocr-ppocrv5-latin/ppocrv5_latin_dict.txt`) and `ocr-ppocrv5-cyrillic` (`ocr-ppocrv5-cyrillic/cyrillic_PP-OCRv5_rec_mobile.onnx` and `ocr-ppocrv5-cyrillic/ppocrv5_cyrillic_dict.txt`), none optional, so that `scripts\push-models.ps1` copies them by default and `graph.models.check()` reports them; `:core:perception` with `onnxruntime-android` 1.28.0 as `implementation` and JUnit for its tests; `:app` with `implementation(project(":core:perception"))`.

Design D20 and D1 (r48; the controller's decisions on r48). The model files are not in git: the manifest names each file's address, size and SHA-256 hash, and `scripts\push-models.ps1` downloads, checks and copies them, as it does the speech models; the phone never downloads them. The detector and the Cyrillic recogniser are RapidOCR v3.10.0's ONNX conversions on ModelScope, whose hashes RapidOCR's `default_models.yaml` publishes; the Latin recogniser is iText Research's conversion of the current official model (838 classes), fetched by a URL that names the repository's commit; both dictionaries are PaddleOCR's files at the commit of the current models. The writer of this plan downloaded every file once and computed the sizes and hashes below. `kotlinx.serialization` refuses an unknown enum value, so without `Engine.OCR` the whole manifest would stop parsing and the speech engines would stop too (review C C1). ONNX Runtime's Android library is new in the release app: the audit pins 1.28.* already and gets its licence, Gradle's verification metadata gets its hash (the file's SHA-1 equals Maven Central's published `bfda8430…343a`), and the audit's native scan must pass, because neither `libonnxruntime.so` nor `libonnxruntime4j_jni.so` imports a network function (the writer checked every ABI with the NDK's `llvm-nm`; if the scan fails, stop and report it to the controller instead of adding an exception, open question C4).

- [ ] **Step 1: Write the failing test**

In `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelManifestTest.kt`, replace:

```kotlin
    @Test
    fun theAppIgnoresFieldsThatItDoesNotKnow() {
```

with:

```kotlin
    @Test
    fun theOcrModelsAreCopiedByDefault() {
        val ocr = manifest.models.filter { it.engine == Engine.OCR }

        assertEquals(listOf("ocr-ppocrv5-det", "ocr-ppocrv5-latin", "ocr-ppocrv5-cyrillic"), ocr.map { it.id })
        assertTrue(ocr.none { it.optional })
    }

    @Test
    fun theAppIgnoresFieldsThatItDoesNotKnow() {
```

- [ ] **Step 2: Add the OCR models to the manifest**

In `models/manifest.json`, replace:

```json
          "url": "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/57adb37fc0e8d4bd430cfc1b8f8c2294945b9af2/model.int8.onnx"
        }
      ]
    }
  ]
}
```

with:

```json
          "url": "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/57adb37fc0e8d4bd430cfc1b8f8c2294945b9af2/model.int8.onnx"
        }
      ]
    },
    {
      "id": "ocr-ppocrv5-det",
      "engine": "ocr",
      "name": "PP-OCRv5 mobile text detector (RapidOCR's ONNX conversion)",
      "licence": "Apache-2.0",
      "project": "https://www.modelscope.cn/models/RapidAI/RapidOCR",
      "files": [
        {
          "path": "ocr-ppocrv5-det/ch_PP-OCRv5_det_mobile.onnx",
          "size": 4819576,
          "sha256": "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
          "url": "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx"
        }
      ]
    },
    {
      "id": "ocr-ppocrv5-latin",
      "engine": "ocr",
      "name": "PP-OCRv5 mobile Latin recogniser (iText Research's ONNX conversion)",
      "licence": "Apache-2.0",
      "project": "https://huggingface.co/itextresearch/itext-latin_PP-OCRv5_mobile_rec_infer",
      "files": [
        {
          "path": "ocr-ppocrv5-latin/latin_PP-OCRv5_mobile_rec.onnx",
          "size": 8068114,
          "sha256": "bc243bda530317419d3a24ee66af5406e672fceaca19627247f22a628920f778",
          "url": "https://huggingface.co/itextresearch/itext-latin_PP-OCRv5_mobile_rec_infer/resolve/bb953c8fd9ebf04f344eb09c9c94bb367c39033c/inference.onnx"
        },
        {
          "path": "ocr-ppocrv5-latin/ppocrv5_latin_dict.txt",
          "size": 2616,
          "sha256": "ccbcc45730b3fbbd9050c5bc74db6a99067141ef1035e3d14889a84a6b9b1aff",
          "url": "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/8ee4a20cc64fe2d73adf96110f9c9b1c3b7b85a5/ppocr/utils/dict/ppocrv5_latin_dict.txt"
        }
      ]
    },
    {
      "id": "ocr-ppocrv5-cyrillic",
      "engine": "ocr",
      "name": "PP-OCRv5 mobile Cyrillic recogniser (RapidOCR's ONNX conversion)",
      "licence": "Apache-2.0",
      "project": "https://www.modelscope.cn/models/RapidAI/RapidOCR",
      "files": [
        {
          "path": "ocr-ppocrv5-cyrillic/cyrillic_PP-OCRv5_rec_mobile.onnx",
          "size": 8074092,
          "sha256": "90f761b4bfcce0c8c561c0cb5c887b0971d3ec01c32164bdf7374a35b0982711",
          "url": "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx"
        },
        {
          "path": "ocr-ppocrv5-cyrillic/ppocrv5_cyrillic_dict.txt",
          "size": 2781,
          "sha256": "db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba",
          "url": "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/8ee4a20cc64fe2d73adf96110f9c9b1c3b7b85a5/ppocr/utils/dict/ppocrv5_cyrillic_dict.txt"
        }
      ]
    }
  ]
}
```

- [ ] **Step 3: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.models.ModelManifestTest`
Expected: FAIL: the compilation of the tests stops with "Unresolved reference 'OCR'".

- [ ] **Step 4: Add the `ocr` engine**

In `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt`, replace:

```kotlin
/** Kestrel's speech engines (design spec, section 8.2). */
@Serializable
enum class Engine {
    @SerialName("vad") VAD,
    @SerialName("kws") KWS,
    @SerialName("asr") ASR,
    @SerialName("tts") TTS,
}
```

with:

```kotlin
/**
 * The engines whose models Kestrel copies from this computer: the speech engines (design spec, section 8.2) and the OCR
 * of the agent's look_closer (design D20 of sub-project 5). Every value of the manifest must be here, because an
 * unknown value stops the whole manifest from parsing.
 */
@Serializable
enum class Engine {
    @SerialName("vad") VAD,
    @SerialName("kws") KWS,
    @SerialName("asr") ASR,
    @SerialName("tts") TTS,
    @SerialName("ocr") OCR,
}
```

- [ ] **Step 5: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:core:voice` JVM tests V₀ + 1, 0 failures (`everyEngineHasAModel` now also finds the OCR models, and `everyFileHasOneSource` their HTTPS addresses).

- [ ] **Step 6: Say in `push-models.ps1` that it copies the OCR models too**

In `scripts/push-models.ps1`, replace:

```powershell
# Copies Kestrel's speech models to a test device (design spec, section 8.10). It downloads the files that
```

with:

```powershell
# Copies Kestrel's speech models and the OCR models of the agent's look_closer to a test device (design spec, section
# 8.10; design D20 of sub-project 5), the OCR models from ModelScope, Hugging Face and GitHub. It downloads the files that
```

In `scripts/push-models.ps1`, replace:

```powershell
Write-Host "The speech models are on $Serial."
```

with:

```powershell
Write-Host "The speech and OCR models are on $Serial."
```

- [ ] **Step 7: Add ONNX Runtime to `:core:perception` and `:core:perception` to the app**

Replace the whole `core/perception/build.gradle.kts` with:

```kotlin
plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.perception"
}

// On-device OCR with PP-OCRv5 through ONNX Runtime (design D20 of sub-project 5). The standalone ONNX Runtime 1.28.0
// works beside the copy that sherpa-onnx links (spike S8); the audit pins 1.28.* (B14).
dependencies {
    implementation(project(":core:screen-model"))
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}
```

In `app/build.gradle.kts`, replace:

```kotlin
    // sub-project 5C: dependencies
```

with:

```kotlin
    // sub-project 5C: dependencies
    implementation(project(":core:perception"))
```

- [ ] **Step 8: Run the audit to see it refuse the licence**

Run: `scripts\gradle.ps1 :app:kestrelCheckDependenciesRelease`
Expected: FAIL: "Dependency audit failed" with the line "com.microsoft.onnxruntime:onnxruntime-android:1.28.0 has no allow rule; review its licence and add a rule to audit/rules.txt". (Only the app's audit checks dependencies; a library's `kestrelAudit` scans only its own classes.)

- [ ] **Step 9: Allow ONNX Runtime's licence and check its hash**

In `audit/rules.txt`, replace:

```text
allow com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime GPL-3.0-or-later
```

with:

```text
allow com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime GPL-3.0-or-later
# ONNX Runtime's own Android library runs the OCR of :core:perception (design D20 of sub-project 5). Its version is
# pinned above, and gradle/verification-metadata.xml checks its hash.
allow com.microsoft.onnxruntime:* MIT
```

In `gradle/verification-metadata.xml`, replace:

```xml
<!-- Gradle checks the SHA-256 hash of sherpa-onnx's Android library, which comes from a GitHub release instead of a
     Maven repository (settings.gradle.kts). Every other artifact is trusted, as before this file existed. A new version
     of sherpa-onnx needs a new audit of the ONNX Runtime that it links (audit/rules.txt) and its hash here: the digest
     that the release page shows for the asset. -->
```

with:

```xml
<!-- Gradle checks the SHA-256 hash of sherpa-onnx's Android library, which comes from a GitHub release instead of a
     Maven repository (settings.gradle.kts), and of ONNX Runtime's Android library, which Kestrel's OCR ships (design
     D20 of sub-project 5). Every other artifact is trusted, as before this file existed. A new version of either needs a
     new audit of its ONNX Runtime (audit/rules.txt) and its hash here: for sherpa-onnx the digest that the release page
     shows for the asset, for ONNX Runtime the SHA-256 of the file whose SHA-1 equals Maven Central's. -->
```

In `gradle/verification-metadata.xml`, replace:

```xml
         <trust group="^(?!com[.]k2fsa[.]sherpa[.]onnx$).*$" regex="true"/>
```

with:

```xml
         <trust group="^(?!com[.]k2fsa[.]sherpa[.]onnx$|com[.]microsoft[.]onnxruntime$).*$" regex="true"/>
```

In `gradle/verification-metadata.xml`, replace:

```xml
      </component>
   </components>
```

with:

```xml
      </component>
      <component group="com.microsoft.onnxruntime" name="onnxruntime-android" version="1.28.0">
         <artifact name="onnxruntime-android-1.28.0.aar">
            <sha256 value="f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9" origin="the file from Maven Central, whose SHA-1 bfda8430dfebfdf01001d123abea7dc3d622343a equals the published one"/>
         </artifact>
      </component>
   </components>
```

- [ ] **Step 10: Say in the spec which ONNX Runtimes the app holds**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
sherpa-onnx links its own copy of ONNX Runtime, so the audit pins the version of the sherpa-onnx library, Gradle checks the library's SHA-256 hash when it downloads it, and a device test checks that the library reports ONNX Runtime 1.28.2.
```

with:

```markdown
sherpa-onnx links its own copy of ONNX Runtime, so the audit pins the version of the sherpa-onnx library, Gradle checks the library's SHA-256 hash when it downloads it, and a device test checks that the library reports ONNX Runtime 1.28.2. Kestrel's OCR (`:core:perception`, section 11.6) ships ONNX Runtime's own Android library 1.28.0, the last version before 1.29 on Maven Central (it has no 1.28.1 or 1.28.2); the audit pins it to 1.28.* and allows its MIT licence, Gradle checks its SHA-256 hash, and the native scan finds no network function in it. The two copies work side by side in one process (spike S8).
```

- [ ] **Step 11: Run the build, the audit and the scripts' tests**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest :app:assembleDebug kestrelAudit`
Expected: `BUILD SUCCESSFUL`: `:core:voice` V₀ + 1 tests, 0 failures; Gradle verifies the ONNX Runtime library against its hash; the audit lists no finding, its dependency report of `:app`'s release variant lists `com.microsoft.onnxruntime:onnxruntime-android:1.28.0`, and its native report lists `libonnxruntime.so` and `libonnxruntime4j_jni.so` without a network function. If the native scan names a function, stop and report it to the controller (open question C4).

Run: `scripts\test-scripts.ps1`
Expected: every Pester test passes in Windows PowerShell 5.1 and in PowerShell 7 (T₀ each, unchanged), and PSScriptAnalyzer reports nothing.

- [ ] **Step 12: Copy the OCR models to the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\install.ps1 -Serial emulator-5554; scripts\push-models.ps1 -Serial emulator-5554`
Expected: the install ends with "Success"; `push-models.ps1` downloads the five OCR files (about 21 MB; the speech files are in its cache already), checks their hashes, copies them and ends with "The speech and OCR models are on emulator-5554."

- [ ] **Step 13: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 14: Commit**

```powershell
git add models/manifest.json core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelManifestTest.kt scripts/push-models.ps1 audit/rules.txt gradle/verification-metadata.xml core/perception/build.gradle.kts app/build.gradle.kts docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(perception): add the PP-OCRv5 model files, the ocr engine and ONNX Runtime 1.28.0" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: The OCR pipeline: preprocessing, detection, recognition and the golden data

**Files:**
- Create: `core/perception/src/main/kotlin/dev/kestrel/perception/OcrImage.kt`, `Dictionary.kt`, `CtcDecoder.kt`, `TextBoxes.kt`, `Detector.kt`, `Recogniser.kt`, `OcrFiles.kt`, `OcrEngine.kt`
- Test: `core/perception/src/test/kotlin/dev/kestrel/perception/Reference.kt` (helper), `DictionaryTest.kt`, `CtcDecoderTest.kt`, `ImageInputTest.kt`, `TextBoxesTest.kt`, `RecogniserChoiceTest.kt`; `core/perception/src/test/resources/ocr/reference.txt`, `maps.txt`
- Create: `scripts/draw-ocr-fixtures.ps1`; `app/src/androidTest/assets/ocr/en.png`, `sr-latin.png`, `sr-cyrillic.png`, `small.png`, `dark.png` (drawn by the script), `app/src/androidTest/assets/ocr/golden.txt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (B19, and B64 at the end of Appendix B)

**Interfaces:**
- Consumes: `dev.kestrel.screen.model.IntRect` (`:core:screen-model`); `ai.onnxruntime.OrtEnvironment`, `OrtSession`, `OnnxTensor`, `TensorInfo` (`onnxruntime-android` 1.28.0, Task 1); the model paths of Task 1.
- Produces (package `dev.kestrel.perception`): `class OcrImage(val width: Int, val height: Int, val pixels: IntArray)` (ARGB, row by row) with `crop(rect: IntRect): OcrImage`, `blackedOut(rects: List<IntRect>): OcrImage` and `scaled(newWidth: Int, newHeight: Int): OcrImage`; `class Dictionary(lines: List<String>)` with `classes: Int`, `label(index: Int): String`, `check(modelClasses: Long)` and `Dictionary.parse(text: String)`; `data class Reading(val text: String, val score: Double)`; `object CtcDecoder { fun decode(scores: FloatArray, steps: Int, dictionary: Dictionary): Reading }`; `data class TextBox(val bounds: IntRect, val score: Double)`; `object TextBoxes { fun find(map: FloatArray, mapWidth: Int, mapHeight: Int, sourceWidth: Int, sourceHeight: Int): List<TextBox> }`; `class Detector : AutoCloseable` with `boxes(image: OcrImage, limit: Int = LIMIT): List<TextBox>`, `Detector.LIMIT = 960`, `Detector.MAX_UPSCALE = 2.0`, `Detector.inputSize(width: Int, height: Int, limit: Int = LIMIT): Pair<Int, Int>` and `Detector.input(image: OcrImage): FloatArray`; `class Recogniser : AutoCloseable` with `read(image: OcrImage): Reading`, `Recogniser.input(image: OcrImage): Recogniser.Input` (`width`, `values`) and `HEIGHT = 48`, `MIN_WIDTH = 320`, `MAX_WIDTH = 3200`; `class OcrFiles(detector, latin, latinDictionary, cyrillic, cyrillicDictionary: File)` with `OcrFiles.under(root: File)` and the five paths as constants; `data class OcrText(val text: String, val bounds: IntRect, val score: Double)`; `class OcrEngine : AutoCloseable` with `read(image: OcrImage, regions: List<IntRect>, cyrillicNearby: Boolean = false, limit: Int = Detector.LIMIT): List<OcrText>`, `detect(image: OcrImage, limit: Int = Detector.LIMIT): List<TextBox>`, `recognise(image: OcrImage, box: TextBox, cyrillicNearby: Boolean = false): OcrText?`, `OcrEngine.open(files: OcrFiles, threads: Int = 2): OcrEngine` and `OcrEngine.CYRILLIC_BELOW = 0.8`. The fixture screens `app/src/androidTest/assets/ocr/<name>.png` (`en`, `sr-latin`, `sr-cyrillic`, `small`, `dark`, 720 × 1280) and `golden.txt` (one line per text line: `<image> <left> <top> <right> <bottom> <text>`) for Task 3's `OcrDeviceTest`.

Design D20 (r48; review B n2, review C M11) and D32 item 31. The pipeline follows the reference (PaddleX 3.3 and RapidOCR 3.10.0, r48): detection scales the image so that its longer side is at most 960 pixels (a region smaller than that grows at most twice, Kestrel's own rule for small controls), rounds each side to a multiple of 32 (halves to even, as Python's `round` does), feeds blue, green and red planes normalised with ImageNet's mean and standard deviation, thresholds the probability map at 0.3, keeps connected parts whose mean is at least 0.6 and whose shorter side is at least 3, grows each by area × 1.5 ÷ perimeter and scales it back; recognition scales each box to a height of 48 and a width of 48 × max(320/48, w/h), at most 3200, normalises to −1..1, pads on the right with zeros, and decodes greedily with the index map `["blank"] + dictionary + [" "]`. The geometry stays in Kotlin with axis-aligned boxes, because text on a phone's screen is horizontal (r48, Fit 5). The image scaling repeats OpenCV's `INTER_LINEAR`, because the reference uses it. A box is read by the Latin recogniser and, when its score is below 0.8 or the screen's text holds Cyrillic letters, by the Cyrillic one too; a reading of only spaces counts as none (see "Changes to the design"). The JVM cannot run the models (they are not in git, and the desktop ONNX Runtime is no dependency), so the JVM tests check each step against what RapidOCR's Python reference computed once from the same inputs: the preprocessing on two synthetic images that both sides draw from one formula, the post-processing on two of the detector's probability maps, and the decoding on the recognisers' best classes per step for five boxes; Task 3's device test compares the whole pipeline with the reference's 36 lines. The writer of this plan ran this code with the desktop ONNX Runtime 1.28.0 against the five fixture screens: every text equals the reference's, and every box overlaps the reference's by at least 88 %. `scripts\draw-ocr-fixtures.ps1` draws the five fixture screens on this computer with `System.Drawing`; the reference's outputs belong to exactly these files, so their hashes must match.

- [ ] **Step 1: Write the failing tests**

Create `core/perception/src/test/kotlin/dev/kestrel/perception/Reference.kt`:

```kotlin
package dev.kestrel.perception

/**
 * The outputs that RapidOCR's Python reference (v3.10.0, with the same models and Kestrel's settings) wrote once for
 * these tests (design D20 of sub-project 5): `ocr/reference.txt` and `ocr/maps.txt` in the test resources.
 */
internal object Reference {
    fun lines(name: String): List<String> =
        (Reference::class.java.getResource("/ocr/$name") ?: error("Missing test resource ocr/$name")).readText().split('\n').filter { it.isNotBlank() }

    /** A test image whose pixel at (x, y) has red x·255/(w−1), green y·255/(h−1) and blue (x/4 + y/8) mod 256. */
    fun synthetic(width: Int, height: Int): OcrImage = OcrImage(width, height, IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val red = x * 255 / (width - 1)
        val green = y * 255 / (height - 1)
        val blue = (x / 4 + y / 8) % 256
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    })
}
```

Create `core/perception/src/test/kotlin/dev/kestrel/perception/DictionaryTest.kt`:

```kotlin
package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DictionaryTest {
    @Test
    fun theBlankComesFirstAndTheSpaceLastWithRepeatedLinesKept() {
        val dictionary = Dictionary.parse("a\nb\na\n")

        assertEquals(5, dictionary.classes)
        assertEquals(listOf("", "a", "b", "a", " "), (0 until 5).map(dictionary::label))
    }

    @Test
    fun aRecogniserWhoseClassesDoNotFitItsDictionaryIsRefused() {
        val dictionary = Dictionary.parse("a\nb\n")

        dictionary.check(4)
        val error = assertThrows(IllegalStateException::class.java) { dictionary.check(5) }
        assertEquals("The recogniser has 5 classes, but its dictionary needs 4", error.message)
    }
}
```

Create `core/perception/src/test/kotlin/dev/kestrel/perception/CtcDecoderTest.kt`:

```kotlin
package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Test

/** CTC's decoding against the recognisers' best classes per step, which the Python reference saved for five boxes. */
class CtcDecoderTest {
    private class Box(val classes: Int, val dictionary: Dictionary, val steps: List<Pair<Int, Float>>, val text: String, val score: Double)

    // Each box has three lines: "reading <model> <dictionary lines> <steps> <score> <text as hex code points>", "chars
    // <index>:<hex code point> …" for the classes that its steps use, and "steps <index>:<score> …".
    private val boxes: List<Box> = Reference.lines("reference.txt").let { lines ->
        lines.indices.filter { lines[it].startsWith("reading ") }.map { at ->
            val head = lines[at].split(' ')
            val size = head[2].toInt()
            val dictionaryLines = MutableList(size) { "\u0000" }
            for (entry in lines[at + 1].removePrefix("chars ").split(' ')) {
                val (index, code) = entry.split(':')
                dictionaryLines[index.toInt() - 1] = String(Character.toChars(code.toInt(16)))
            }
            val steps = lines[at + 2].removePrefix("steps ").split(' ').map { entry ->
                entry.split(':').let { (index, score) -> index.toInt() to score.toFloat() }
            }
            val text = head.drop(5).joinToString("") { String(Character.toChars(it.toInt(16))) }
            Box(size + 2, Dictionary(dictionaryLines), steps, text, head[4].toDouble())
        }
    }

    @Test
    fun decodesEachBoxAsTheReferenceDid() {
        assertEquals(5, boxes.size)
        for (box in boxes) {
            // The step's best class gets its saved score, every other class an equal share of the rest.
            val scores = FloatArray(box.steps.size * box.classes)
            box.steps.forEachIndexed { step, (index, score) ->
                val rest = (1 - score) / (box.classes - 1)
                for (c in 0 until box.classes) scores[step * box.classes + c] = if (c == index) score else rest
            }

            val reading = CtcDecoder.decode(scores, box.steps.size, box.dictionary)

            assertEquals(box.text, reading.text)
            assertEquals(box.score, reading.score, 0.0001)
        }
    }

    @Test
    fun aRepeatSurvivesOnlyAcrossABlankAndNothingKeptScoresZero() {
        val dictionary = Dictionary.parse("s\n")
        fun steps(vararg best: Int): FloatArray = FloatArray(best.size * 3).also { scores ->
            best.forEachIndexed { step, index -> scores[step * 3 + index] = 0.9f }
        }

        assertEquals("s", CtcDecoder.decode(steps(1, 1, 1), 3, dictionary).text)
        assertEquals("ss", CtcDecoder.decode(steps(1, 0, 1), 3, dictionary).text)
        assertEquals("s s", CtcDecoder.decode(steps(1, 2, 1), 3, dictionary).text)
        assertEquals(Reading("", 0.0), CtcDecoder.decode(steps(0, 0), 2, dictionary))
    }
}
```

Create `core/perception/src/test/kotlin/dev/kestrel/perception/ImageInputTest.kt`:

```kotlin
package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Test

/** The detector's and the recogniser's inputs against the Python reference's preprocessing of the same test images. */
class ImageInputTest {
    private val reference = Reference.lines("reference.txt").map { it.split(' ') }

    @Test
    fun theDetectorsInputSizeFollowsTheReferenceAndEnlargesARegionAtMostTwice() {
        val sizes = reference.filter { it[0] == "detector-size" }
        assertEquals(3, sizes.size)
        for (size in sizes) {
            assertEquals(size[3].toInt() to size[4].toInt(), Detector.inputSize(size[1].toInt(), size[2].toInt()))
        }
        // Kestrel's own rule for regions smaller than the limit (the reference never enlarges): 500 × 300 grows 1.92
        // times; 100 × 40 grows twice to 200 × 80, whose 80 rounds half to even, to 64.
        assertEquals(960 to 576, Detector.inputSize(500, 300))
        assertEquals(192 to 64, Detector.inputSize(100, 40))
        assertEquals(704 to 1280, Detector.inputSize(720, 1280, limit = 1280))
    }

    @Test
    fun theDetectorsInputHasTheReferencesValuesInBlueGreenRedOrder() {
        val (width, height) = Detector.inputSize(1080, 2400)
        val input = Detector.input(Reference.synthetic(1080, 2400).scaled(width, height))
        val values = reference.filter { it[0] == "detector-value" }
        assertEquals(12, values.size)
        for (value in values) {
            val (channel, y, x) = value.subList(3, 6).map(String::toInt)
            // One step of a pixel's value is 1/255/0.224, about 0.018.
            assertEquals(value.joinToString(" "), value[6].toDouble(), input[channel * width * height + y * width + x].toDouble(), 0.02)
        }
    }

    @Test
    fun theRecognisersInputHasTheReferencesWidthsAndValues() {
        for (size in reference.filter { it[0] == "recogniser-size" }) {
            assertEquals(size.joinToString(" "), size[3].toInt(), Recogniser.input(Reference.synthetic(size[1].toInt(), size[2].toInt())).width)
        }
        val values = reference.filter { it[0] == "recogniser-value" }
        assertEquals(11, values.size)
        for (value in values) {
            val input = Recogniser.input(Reference.synthetic(value[1].toInt(), value[2].toInt()))
            val (channel, y, x) = value.subList(3, 6).map(String::toInt)
            // One step of a pixel's value is 2/255, about 0.008.
            assertEquals(value.joinToString(" "), value[6].toDouble(), input.values[channel * 48 * input.width + y * input.width + x].toDouble(), 0.01)
        }
        // A very long line is cut to 3200 columns.
        assertEquals(Recogniser.MAX_WIDTH, Recogniser.input(Reference.synthetic(2000, 10)).width)
    }
}
```

Create `core/perception/src/test/kotlin/dev/kestrel/perception/TextBoxesTest.kt`:

```kotlin
package dev.kestrel.perception

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's post-processing against the reference's, on the probability maps that the detector gave for two
 * fixture screens (720 × 1280, so 544 × 960 maps), saved at a quarter of their size and in ten steps.
 */
class TextBoxesTest {
    private class Map(val name: String, val width: Int, val height: Int, val values: FloatArray, val boxes: List<IntRect>)

    // "map <name> <width> <height>", then one line per row of runs "<step>*<count>" or "<step>" (the value is step/9),
    // then the reference's boxes "box <left> <top> <right> <bottom>" in the 720 × 1280 screen.
    private val maps: List<Map> = Reference.lines("maps.txt").let { lines ->
        lines.indices.filter { lines[it].startsWith("map ") }.map { at ->
            val (_, name, width, height) = lines[at].split(' ')
            val w = width.toInt()
            val h = height.toInt()
            val values = FloatArray(w * 4 * h * 4)
            for (row in 0 until h) {
                var x = 0
                for (run in lines[at + 1 + row].split(' ')) {
                    val parts = run.split('*')
                    val value = parts[0].toInt() / 9f
                    repeat(if (parts.size == 2) parts[1].toInt() else 1) {
                        // Each saved value stands for 4 × 4 values of the map.
                        for (dy in 0 until 4) for (dx in 0 until 4) values[(row * 4 + dy) * w * 4 + x * 4 + dx] = value
                        x++
                    }
                }
                assertEquals("row $row of $name", w, x)
            }
            val boxes = lines.drop(at + 1 + h).takeWhile { it.startsWith("box ") }.map { line ->
                line.split(' ').drop(1).map(String::toInt).let { (l, t, r, b) -> IntRect(l, t, r, b) }
            }
            Map(name, w * 4, h * 4, values, boxes)
        }
    }

    @Test
    fun findsTheReferencesBoxes() {
        assertEquals(listOf("en", "sr-cyrillic"), maps.map { it.name })
        for (map in maps) {
            val found = TextBoxes.find(map.values, map.width, map.height, 720, 1280).map { it.bounds }

            assertEquals(map.name, map.boxes.size, found.size)
            for ((expected, actual) in map.boxes.zip(found)) {
                // The reference turns a box by a few degrees when its part leans, which an axis-aligned box cannot.
                assertTrue("${map.name}: $expected and $actual", overlap(expected, actual) >= 0.8)
            }
        }
    }

    // Intersection over union.
    private fun overlap(a: IntRect, b: IntRect): Double {
        val common = a.intersect(b) ?: return 0.0
        val shared = common.width.toDouble() * common.height
        return shared / (a.width.toDouble() * a.height + b.width.toDouble() * b.height - shared)
    }

    @Test
    fun dropsThinPartsAndPartsWithALowMeanAndKeepsBoxesInsideTheImage() {
        val width = 40
        val height = 20
        val map = FloatArray(width * height)
        fun fill(left: Int, top: Int, right: Int, bottom: Int, value: Float) {
            for (y in top..bottom) for (x in left..right) map[y * width + x] = value
        }
        fill(0, 0, 9, 9, 0.9f) // kept, and its grown box is cut at the image's edge
        fill(15, 0, 30, 1, 0.9f) // two rows: thinner than 3
        fill(15, 8, 30, 16, 0.5f) // above the threshold, below the box threshold

        val boxes = TextBoxes.find(map, width, height, width, height)

        assertEquals(1, boxes.size)
        assertEquals(IntRect(0, 0, 12, 12), boxes.single().bounds)
        assertEquals(0.9, boxes.single().score, 0.0001)
    }
}
```

Create `core/perception/src/test/kotlin/dev/kestrel/perception/RecogniserChoiceTest.kt`:

```kotlin
package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The choice between the Latin and the Cyrillic recogniser for one box (design D20 of sub-project 5; r48, Fit 4). */
class RecogniserChoiceTest {
    private val cyrillic = Reading("Ђорђе", 0.95)

    private fun choose(latin: Reading, nearby: Boolean = false): Pair<Reading, Boolean> {
        var asked = false
        val chosen = OcrEngine.choose(latin, nearby) { asked = true; cyrillic }
        return chosen to asked
    }

    @Test
    fun aSureLatinReadingIsKeptWithoutTheCyrillicRecogniser() {
        val latin = Reading("Settings", 0.99)

        assertEquals(latin to false, choose(latin))
    }

    @Test
    fun anUnsureLatinReadingOrCyrillicTextNearbyLetsTheHigherScoreWin() {
        assertEquals(cyrillic to true, choose(Reading("Bopbe", 0.6)))
        assertEquals(cyrillic to true, choose(Reading("Bopbe", 0.9), nearby = true))
        val sure = Reading("Pay now", 0.99)
        assertSame(sure, choose(sure, nearby = true).first)
    }

    @Test
    fun aLatinReadingOfOnlySpacesCountsAsNone() {
        // The Latin recogniser reads Cyrillic text as spaces with a score of about 0.99.
        assertEquals(cyrillic to true, choose(Reading("  ", 0.99)))
        assertEquals(Reading("", 0.0), OcrEngine.choose(Reading(" ", 0.99), false) { Reading(" ", 0.98) })
    }
}
```

Create `core/perception/src/test/resources/ocr/reference.txt` (each line exactly as here; `reading` lines end with the text's code points in hexadecimal):

```text
detector-size 1080 2400 448 960
detector-size 720 1280 544 960
detector-size 2400 1080 960 448
detector-value 1080 2400 0 0 0 -2.11790
detector-value 1080 2400 1 0 0 -2.03571
detector-value 1080 2400 2 0 0 -1.80444
detector-value 1080 2400 0 100 200 0.46793
detector-value 1080 2400 1 100 200 -1.58053
detector-value 1080 2400 2 100 200 0.18248
detector-value 1080 2400 0 480 224 -1.62129
detector-value 1080 2400 1 959 447 2.41106
detector-value 1080 2400 2 959 447 2.62257
detector-value 1080 2400 0 700 13 1.75229
detector-value 1080 2400 1 333 300 -0.49510
detector-value 1080 2400 2 512 77 -1.05499
recogniser-size 300 30 480 480
recogniser-size 40 60 320 32
recogniser-size 1000 20 2400 2400
recogniser-value 300 30 0 0 0 -1.00000
recogniser-value 300 30 1 10 100 -0.58431
recogniser-value 300 30 2 47 479 1.00000
recogniser-value 300 30 0 24 250 -0.68627
recogniser-value 300 30 1 47 0 1.00000
recogniser-value 300 30 2 5 333 0.38824
recogniser-value 40 60 0 0 0 -1.00000
recogniser-value 40 60 1 47 31 0.99216
recogniser-value 40 60 2 20 15 -0.03529
recogniser-value 40 60 0 30 40 0.00000
recogniser-value 40 60 1 10 319 0.00000
reading latin 836 40 0.97508 42 61 74 74 65 72 79 20 73 61 76 65 72
chars 12:42 37:61 41:65 54:72 55:73 56:74 58:76 61:79
steps 0:0.99994 0:0.99827 12:0.99997 0:0.99991 37:0.99997 0:0.65870 56:0.99997 0:0.99837 0:0.99982 56:0.99993 41:0.99994 0:0.50959 0:0.99971 54:0.99999 0:0.99980 61:0.99999 837:0.67654 837:0.99988 55:0.99984 55:0.97002 0:0.99981 37:0.99998 0:0.99998 58:0.99993 0:0.99995 0:0.98874 41:0.99999 0:0.99987 54:1.00000 0:0.99953 0:1.00000 0:1.00000 0:0.99999 0:0.99999 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:0.99999
reading latin 836 43 0.98301 110 6f 72 111 65 20 161 61 6c 6a 65 20 70 6f 72 75 6b 75
chars 37:61 41:65 46:6a 47:6b 48:6c 51:6f 52:70 54:72 57:75 143:110 144:111 224:161
steps 0:0.99992 0:0.99946 143:0.99261 0:0.99998 0:0.99999 51:0.99799 0:0.99997 0:0.99977 54:0.99991 0:0.99999 144:0.87751 0:0.99999 41:0.99934 0:0.96823 837:0.99344 837:0.99831 224:0.99773 0:0.99999 0:0.99946 37:0.99965 48:0.99942 0:0.99986 46:0.99982 0:0.99433 41:0.99903 837:0.83908 837:0.99986 0:0.99525 52:0.99996 0:0.99995 0:0.99976 51:0.99937 0:0.99999 54:0.99990 0:0.99987 57:0.99959 0:0.99984 0:0.99998 47:0.99993 0:0.99997 0:0.99900 57:0.99987 0:0.99994
reading cyrillic 850 40 0.98535 409 443 431 430 432 20 438 20 45a 435 433 430
chars 451:409 490:430 491:431 492:432 493:433 495:435 498:438 509:443 532:45a
steps 0:0.99876 0:0.99992 451:0.84273 0:0.99997 0:0.99995 0:0.99999 509:0.99806 0:0.99999 0:0.99936 491:0.99997 0:0.99997 490:0.99968 0:0.99980 0:0.99999 492:0.99994 0:0.99877 851:0.99820 851:0.98931 498:0.99997 0:0.99957 851:0.98894 851:0.99953 0:0.99831 532:0.99740 0:0.99984 0:0.99998 0:0.99996 495:0.99984 0:0.99999 493:0.99982 0:0.99999 490:0.99969 0:0.99992 0:1.00000 0:1.00000 0:0.99999 0:0.99998 0:0.99999 0:0.99999 0:0.99999
reading cyrillic 850 43 0.99894 402 43e 440 452 435 20 448 430 459 435 20 43f 43e 440 443 43a 443
chars 444:402 490:430 495:435 500:43a 504:43e 505:43f 506:440 509:443 514:448 524:452 531:459
steps 0:0.99896 444:0.99297 0:0.99953 0:1.00000 504:0.99979 0:0.99984 0:0.99999 506:0.99998 0:0.99999 524:0.99603 0:0.98853 0:0.99996 495:0.99957 0:0.96186 851:0.99851 0:0.62258 514:0.99988 0:0.99994 0:0.99999 490:0.99980 0:0.99999 0:0.99993 531:0.99865 0:0.99975 0:0.99999 495:0.99967 0:0.68649 851:0.99881 0:0.70489 505:0.99987 0:0.99997 504:0.99976 0:0.99998 0:0.99996 506:0.99964 0:0.99992 509:0.99969 0:0.99992 500:0.99943 0:0.99997 0:0.99965 509:0.99992 0:0.99700
reading latin 836 40 0.99996 4d 65 73 73 61 67 65 73
chars 23:4d 37:61 41:65 43:67 55:73
steps 0:0.99994 0:0.99995 0:0.99808 23:0.99996 0:0.99974 0:1.00000 0:0.99960 41:0.99998 0:0.99990 0:0.99945 55:0.99997 0:1.00000 55:0.99990 0:0.99505 0:0.99980 37:0.99996 0:0.99852 0:0.99999 43:0.99995 0:0.98808 0:0.99996 41:0.99998 0:0.96526 0:0.99998 55:0.99996 0:0.99832 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:1.00000 0:0.99999
```

Create `core/perception/src/test/resources/ocr/maps.txt` (two maps of 136 × 240 values, one row per line, then the reference's boxes):

```text
map en 136 240
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 1 4*4 3 2*4 0*115
0*11 6 9*14 8 7*4 5 1 0*103
0*11 6 9*20 2 0*103
0*11 3 9*20 2 0*103
0*18 1 2*5 3 4 5*2 6 7*2 6 1 0*103
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 4 5 4*9 3 4 3 2*12 1 0*98
0*10 2 9*26 7 0*98
0*10 1 7 8 9*24 7 0*98
0*22 1 0 1*2 2*11 0*99
0*136
0*136
0*136
0*136
0*136
0*10 2 8 9*3 8*2 7*30 3 0*88
0*10 2 9*36 5 0*88
0*11 2*7 3*2 4*14 5 4*2 5 4 5*8 2 0*88
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*10 1 6 7*27 6 0*96
0*11 9*29 1 0*95
0*11 3 5*5 6*2 7*3 6 7*15 6*2 0*96
0*136
0*136
0*136
0*136
0*136
0*136
0*10 2 9*6 8 7*7 8*2 7*15 6 3 0*92
0*10 3 9*32 7 0*92
0*19 1*2 2 1*2 0*9 1 2*9 1 0*92
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 3 7*8 6*2 7*2 5*3 6*7 5*7 6*8 1 0*86
0*11 7 9*37 4 0*86
0*11 4 7*6 9*31 3 0*86
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 3 4*7 3*2 2 3*2 2*12 0*100
0*10 2 9*26 1 0*98
0*10 2 9*26 2 0*98
0*27 1 2*7 3*2 0*99
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 3 7*30 6 7*12 4 0*80
0*11 5 9*43 7 0*80
0*11 3 7*29 8 7 8 9*11 5 0*80
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 4 5 4*4 3 2*10 0*108
0*11 9*18 2 0*106
0*10 2 9*18 2 0*106
0*10 1 7*2 9*3 8*2 9*10 8 2 0*106
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
box 41 35 184 97
box 48 151 208 184
box 48 196 262 232
box 46 320 220 356
box 46 372 238 395
box 48 491 274 527
box 44 602 207 651
box 48 731 306 767
box 46 1097 164 1141
map sr-cyrillic 136 240
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 4 9*3 8 7*13 6 5 6*2 7 6 5*13 2 0*87
0*11 4 9*36 5 0*87
0*11 2 6 7*7 8*4 9*24 4 0*87
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 3 4*6 2*17 1 0 1 0*98
0*11 9*30 3 0*94
0*11 8 9*29 4 0*94
0*12 2*29 0*95
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 6 9*8 8*2 7*14 6 5*7 4*7 2*4 1 0*80
0*11 7 9*43 8 0*80
0*11 3 7*18 8*2 9*23 7 0*80
0*46 1 2*7 4 2 0*80
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 2 4*12 3*4 4*2 3 4*12 3 1 0*91
0*11 7 9*32 7 0*91
0*11 7 9*32 7 0*91
0*12 2*18 3 4 3*5 2*7 1 0*91
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*10 1 8 9*23 3 0*100
0*10 2 9*24 6 0*100
0*10 1 6 7*16 8*7 4 0*100
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 3 5*7 4*9 3*2 2*6 1 0*99
0*11 8 9*28 8*2 6 1 0*92
0*11 7 9*31 0*93
0*16 1 2*16 3 4*3 5*4 6 5 0*93
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*11 5 7 6*4 5*2 4*3 3 2*4 1 0*108
0*11 8 9*15 8 0*108
0*10 1 9*17 0*108
0*10 1 5 7*6 8 9*9 0*108
0*24 1 2*2 3 0*108
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
0*136
box 48 48 269 84
box 46 149 230 185
box 43 262 309 316
box 45 387 250 433
box 49 513 199 547
box 43 621 240 675
box 41 1090 164 1150
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:perception:testDebugUnitTest`
Expected: FAIL: the compilation of the tests stops with "Unresolved reference" for `OcrImage`, `Dictionary`, `CtcDecoder`, `Detector`, `Recogniser`, `TextBoxes` and `OcrEngine`.

- [ ] **Step 3: Write the image and the dictionary**

Create `core/perception/src/main/kotlin/dev/kestrel/perception/OcrImage.kt`:

```kotlin
package dev.kestrel.perception

import dev.kestrel.screen.model.IntRect
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * An image as ARGB pixels, row by row, as Android's `Bitmap.getPixels` gives them. It exists only in memory; its
 * [toString] prints only its size.
 */
class OcrImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Expected $width x $height pixels" }
    }

    /** The part of the image inside [rect], which must lie inside the image. */
    fun crop(rect: IntRect): OcrImage {
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height && !rect.isEmpty) { "Crop outside the image" }
        val out = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) {
            System.arraycopy(pixels, (rect.top + y) * width + rect.left, out, y * rect.width, rect.width)
        }
        return OcrImage(rect.width, rect.height, out)
    }

    /** A copy with every part of [rects] painted opaque black (design D20: password fields never reach OCR). */
    fun blackedOut(rects: List<IntRect>): OcrImage {
        val out = pixels.copyOf()
        for (rect in rects) {
            val part = rect.intersect(IntRect(0, 0, width, height)) ?: continue
            for (y in part.top until part.bottom) out.fill(BLACK, y * width + part.left, y * width + part.right)
        }
        return OcrImage(width, height, out)
    }

    /**
     * The image scaled to [newWidth] x [newHeight] by bilinear interpolation with OpenCV's `INTER_LINEAR` rule, which the
     * Python reference uses: pixel centres at half pixels, edges clamped, each channel rounded to a whole value.
     */
    fun scaled(newWidth: Int, newHeight: Int): OcrImage {
        if (newWidth == width && newHeight == height) return this
        val xs = Axis(width, newWidth)
        val ys = Axis(height, newHeight)
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val row0 = ys.low[y] * width
            val row1 = ys.high[y] * width
            val wy = ys.weight[y]
            for (x in 0 until newWidth) {
                val x0 = xs.low[x]
                val x1 = xs.high[x]
                val wx = xs.weight[x]
                out[y * newWidth + x] = blend(pixels[row0 + x0], pixels[row0 + x1], pixels[row1 + x0], pixels[row1 + x1], wx, wy)
            }
        }
        return OcrImage(newWidth, newHeight, out)
    }

    override fun toString(): String = "OcrImage(${width}x$height)"

    // For each output position: the two source positions and the weight of the second.
    private class Axis(source: Int, target: Int) {
        val low = IntArray(target)
        val high = IntArray(target)
        val weight = FloatArray(target)

        init {
            val scale = source.toDouble() / target
            for (i in 0 until target) {
                val position = (i + 0.5) * scale - 0.5
                var start = floor(position).toInt()
                var fraction = (position - start).toFloat()
                if (start < 0) {
                    start = 0
                    fraction = 0f
                }
                if (start >= source - 1) {
                    start = source - 1
                    fraction = 0f
                }
                low[i] = start
                high[i] = minOf(start + 1, source - 1)
                weight[i] = fraction
            }
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()

        fun blend(p00: Int, p01: Int, p10: Int, p11: Int, wx: Float, wy: Float): Int {
            fun channel(shift: Int): Int {
                val top = ((p00 shr shift) and 0xFF) * (1 - wx) + ((p01 shr shift) and 0xFF) * wx
                val bottom = ((p10 shr shift) and 0xFF) * (1 - wx) + ((p11 shr shift) and 0xFF) * wx
                return (top * (1 - wy) + bottom * wy).roundToInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
```

Create `core/perception/src/main/kotlin/dev/kestrel/perception/Dictionary.kt`:

```kotlin
package dev.kestrel.perception

/**
 * A recogniser's characters (design D20 of sub-project 5; r48): class 0 is CTC's blank, classes 1 to N are the
 * dictionary's lines in file order, and class N + 1 is a space. Lines may repeat (the Latin dictionary has 836 lines, of
 * which 772 differ), so decoding goes by index and never by character.
 */
class Dictionary(private val lines: List<String>) {
    /** How many classes the recogniser's output must have. */
    val classes: Int get() = lines.size + 2

    /** The text of [index], an index of the recogniser's output; the blank is empty. */
    fun label(index: Int): String = when (index) {
        0 -> ""
        lines.size + 1 -> " "
        else -> lines[index - 1]
    }

    /** Fails when a recogniser with [modelClasses] classes does not belong to this dictionary. */
    fun check(modelClasses: Long) {
        if (modelClasses != classes.toLong()) {
            throw IllegalStateException("The recogniser has $modelClasses classes, but its dictionary needs $classes")
        }
    }

    override fun toString(): String = "Dictionary(${lines.size} lines)"

    companion object {
        /** The dictionary of a file or of a model's `character` metadata: one character per line, LF line ends. */
        fun parse(text: String): Dictionary = Dictionary(text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it })
    }
}
```

- [ ] **Step 4: Write CTC's decoding and the detector's boxes**

Create `core/perception/src/main/kotlin/dev/kestrel/perception/CtcDecoder.kt`:

```kotlin
package dev.kestrel.perception

/** What a recogniser read in one box: its text and the mean score of the characters that it kept. */
data class Reading(val text: String, val score: Double) {
    override fun toString(): String = "Reading(text=${text.length} chars, score=$score)"
}

/**
 * CTC's greedy decoding (design D20 of sub-project 5; r48): the best class of each step, repeats collapsed, blanks
 * dropped. The score is the mean of the kept steps' best values, or 0 when no step is kept.
 */
object CtcDecoder {
    /** [scores] holds [steps] rows of the recogniser's classes, one after the other. */
    fun decode(scores: FloatArray, steps: Int, dictionary: Dictionary): Reading {
        val classes = dictionary.classes
        require(scores.size == steps * classes) { "Expected $steps steps of $classes classes" }
        val text = StringBuilder()
        var sum = 0.0
        var kept = 0
        var previous = -1
        for (step in 0 until steps) {
            var best = 0
            var bestScore = scores[step * classes]
            for (index in 1 until classes) {
                val score = scores[step * classes + index]
                if (score > bestScore) {
                    best = index
                    bestScore = score
                }
            }
            if (best != previous && best != 0) {
                text.append(dictionary.label(best))
                sum += bestScore
                kept++
            }
            previous = best
        }
        return Reading(text.toString(), if (kept == 0) 0.0 else sum / kept)
    }
}
```

Create `core/perception/src/main/kotlin/dev/kestrel/perception/TextBoxes.kt`:

```kotlin
package dev.kestrel.perception

import dev.kestrel.screen.model.IntRect
import kotlin.math.max
import kotlin.math.min

/** A text line's box in the source image, with the mean of the detector's map inside it. */
data class TextBox(val bounds: IntRect, val score: Double)

/**
 * The detector's post-processing (design D20 of sub-project 5; r48): the probability map thresholded at 0.3, its
 * connected parts (8 neighbours, at most 1000) as axis-aligned boxes, because text on a phone's screen is horizontal; a
 * box whose shorter side is below 3 or whose mean score is below 0.6 is dropped; the rest grow by the unclip distance
 * (area × 1.5 ÷ perimeter), must then have a shorter side of at least 5, and are scaled to the source image. This keeps
 * the geometry in Kotlin, without OpenCV or another native library (r48, Fit 5).
 */
object TextBoxes {
    const val THRESHOLD = 0.3
    const val BOX_THRESHOLD = 0.6
    const val UNCLIP_RATIO = 1.5
    const val MAX_CANDIDATES = 1000
    private const val MIN_SIDE = 3

    /**
     * The boxes of [map] ([mapWidth] x [mapHeight] values, row by row) in a source image of [sourceWidth] x
     * [sourceHeight] pixels, from top to bottom and left to right.
     */
    fun find(map: FloatArray, mapWidth: Int, mapHeight: Int, sourceWidth: Int, sourceHeight: Int): List<TextBox> {
        require(map.size == mapWidth * mapHeight) { "Expected $mapWidth x $mapHeight values" }
        val seen = BooleanArray(map.size)
        val stack = IntArray(map.size)
        val boxes = mutableListOf<TextBox>()
        var candidates = 0
        for (start in map.indices) {
            if (seen[start] || map[start] <= THRESHOLD) continue
            if (candidates == MAX_CANDIDATES) break
            candidates++
            // One connected part, found with an explicit stack.
            var left = mapWidth
            var top = mapHeight
            var right = -1
            var bottom = -1
            var sum = 0.0
            var count = 0
            var size = 0
            stack[size++] = start
            seen[start] = true
            while (size > 0) {
                val at = stack[--size]
                val x = at % mapWidth
                val y = at / mapWidth
                sum += map[at]
                count++
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= mapHeight) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= mapWidth) continue
                        val next = ny * mapWidth + nx
                        if (!seen[next] && map[next] > THRESHOLD) {
                            seen[next] = true
                            stack[size++] = next
                        }
                    }
                }
            }
            box(sum / count, mapWidth, mapHeight, left, top, right, bottom, sourceWidth, sourceHeight)?.let { boxes += it }
        }
        return boxes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    // The part's box in the map runs between the centres of its outer pixels, as OpenCV's minAreaRect measures it.
    private fun box(
        score: Double, mapWidth: Int, mapHeight: Int, left: Int, top: Int, right: Int, bottom: Int,
        sourceWidth: Int, sourceHeight: Int,
    ): TextBox? {
        val width = (right - left).toDouble()
        val height = (bottom - top).toDouble()
        if (min(width, height) < MIN_SIDE) return null
        if (score < BOX_THRESHOLD) return null
        val distance = width * height * UNCLIP_RATIO / (2 * (width + height))
        if (min(width, height) + 2 * distance < MIN_SIDE + 2) return null
        fun scale(value: Double, from: Int, to: Int): Int = Math.rint(value / from * to).toInt().coerceIn(0, to)
        // As the reference does, the corners are kept inside the image, and boxes of 3 pixels or less go.
        val bounds = IntRect(
            min(scale(left - distance, mapWidth, sourceWidth), sourceWidth - 1),
            min(scale(top - distance, mapHeight, sourceHeight), sourceHeight - 1),
            min(scale(right + distance, mapWidth, sourceWidth), sourceWidth - 1),
            min(scale(bottom + distance, mapHeight, sourceHeight), sourceHeight - 1),
        )
        if (bounds.width <= MIN_SIDE || bounds.height <= MIN_SIDE) return null
        return TextBox(bounds, score)
    }
}
```

- [ ] **Step 5: Write the detector, the recognisers, the files and the engine**

Create `core/perception/src/main/kotlin/dev/kestrel/perception/Detector.kt`:

```kotlin
package dev.kestrel.perception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * PP-OCRv5's mobile text detector (design D20 of sub-project 5; r48). [boxes] scales the image so that its longer side
 * is at most [LIMIT] pixels, or enlarges a small image (a region) by at most [MAX_UPSCALE], rounds each side to a
 * multiple of 32, and finds the text lines in the detector's probability map with [TextBoxes].
 */
class Detector internal constructor(private val session: OrtSession) : AutoCloseable {
    fun boxes(image: OcrImage, limit: Int = LIMIT): List<TextBox> {
        val (width, height) = inputSize(image.width, image.height, limit)
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input(image.scaled(width, height))), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val mapShape = output.info.shape
                val mapHeight = mapShape[2].toInt()
                val mapWidth = mapShape[3].toInt()
                val map = FloatArray(mapWidth * mapHeight)
                output.floatBuffer.get(map)
                return TextBoxes.find(map, mapWidth, mapHeight, image.width, image.height)
            }
        }
    }

    override fun close() = session.close()

    override fun toString(): String = "Detector"

    companion object {
        const val LIMIT = 960
        const val MAX_UPSCALE = 2.0

        // ImageNet's mean and standard deviation, applied to the channels in BGR order, as the reference does.
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** The detector's input size for an image of [width] x [height]: (width, height), each a multiple of 32. */
        fun inputSize(width: Int, height: Int, limit: Int = LIMIT): Pair<Int, Int> {
            val ratio = min(MAX_UPSCALE, limit.toDouble() / max(width, height))
            fun side(value: Int): Int = max(32, Math.rint((value * ratio).toInt() / 32.0).toInt() * 32)
            return side(width) to side(height)
        }

        /** [image] as the detector's input: blue, green and red planes, each scaled by 1/255 and normalised. */
        fun input(image: OcrImage): FloatArray {
            val plane = image.width * image.height
            val values = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val pixel = image.pixels[i]
                values[i] = ((pixel and 0xFF) / 255f - MEAN[0]) / STD[0]
                values[plane + i] = (((pixel shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
                values[2 * plane + i] = (((pixel shr 16) and 0xFF) / 255f - MEAN[2]) / STD[2]
            }
            return values
        }
    }
}
```

Create `core/perception/src/main/kotlin/dev/kestrel/perception/Recogniser.kt`:

```kotlin
package dev.kestrel.perception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One of PP-OCRv5's mobile text recognisers, `latin` or `cyrillic` (design D20 of sub-project 5; r48), with its
 * [dictionary]. Opening it fails when the model's class count is not the dictionary's lines plus two.
 */
class Recogniser internal constructor(private val session: OrtSession, val dictionary: Dictionary) : AutoCloseable {
    init {
        dictionary.check((session.outputInfo.values.first().info as TensorInfo).shape.last())
    }

    /** What the recogniser reads in [image], one text line. */
    fun read(image: OcrImage): Reading {
        val input = input(image)
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, HEIGHT.toLong(), input.width.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val steps = output.info.shape[1].toInt()
                val scores = FloatArray(steps * dictionary.classes)
                output.floatBuffer.get(scores)
                return CtcDecoder.decode(scores, steps, dictionary)
            }
        }
    }

    override fun close() = session.close()

    override fun toString(): String = "Recogniser($dictionary)"

    /** The recogniser's input: [width] columns of 48 rows in blue, green and red planes. */
    class Input(val width: Int, val values: FloatArray)

    companion object {
        const val HEIGHT = 48
        const val MIN_WIDTH = 320
        const val MAX_WIDTH = 3200

        /**
         * [image] scaled to a height of 48 and its own width, padded on the right with zeros to 48 × max(320/48, w/h)
         * columns (at most 3200), each channel normalised to −1..1.
         */
        fun input(image: OcrImage): Input {
            val ratio = image.width.toDouble() / image.height
            val width = min(MAX_WIDTH, (HEIGHT * max(MIN_WIDTH.toDouble() / HEIGHT, ratio)).toInt())
            val resized = image.scaled(min(width, ceil(HEIGHT * ratio).toInt()), HEIGHT)
            val plane = HEIGHT * width
            val values = FloatArray(3 * plane)
            for (y in 0 until HEIGHT) {
                for (x in 0 until resized.width) {
                    val pixel = resized.pixels[y * resized.width + x]
                    val at = y * width + x
                    values[at] = ((pixel and 0xFF) / 255f - 0.5f) / 0.5f
                    values[plane + at] = (((pixel shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                    values[2 * plane + at] = (((pixel shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                }
            }
            return Input(width, values)
        }
    }
}
```

Create `core/perception/src/main/kotlin/dev/kestrel/perception/OcrFiles.kt`:

```kotlin
package dev.kestrel.perception

import java.io.File

/**
 * The OCR model files below Kestrel's model folder (design D20 of sub-project 5), with the paths of
 * `models/manifest.json`'s entries `ocr-ppocrv5-det`, `ocr-ppocrv5-latin` and `ocr-ppocrv5-cyrillic`. Each recogniser
 * has its dictionary as a file of its own, because ONNX Runtime's Java API garbles the two characters beyond U+FFFF in
 * the Cyrillic model's `character` metadata.
 */
class OcrFiles(val detector: File, val latin: File, val latinDictionary: File, val cyrillic: File, val cyrillicDictionary: File) {
    override fun toString(): String = "OcrFiles"

    companion object {
        const val DETECTOR = "ocr-ppocrv5-det/ch_PP-OCRv5_det_mobile.onnx"
        const val LATIN = "ocr-ppocrv5-latin/latin_PP-OCRv5_mobile_rec.onnx"
        const val LATIN_DICTIONARY = "ocr-ppocrv5-latin/ppocrv5_latin_dict.txt"
        const val CYRILLIC = "ocr-ppocrv5-cyrillic/cyrillic_PP-OCRv5_rec_mobile.onnx"
        const val CYRILLIC_DICTIONARY = "ocr-ppocrv5-cyrillic/ppocrv5_cyrillic_dict.txt"

        /** The files in the folder [root], which holds every model of the manifest. */
        fun under(root: File): OcrFiles = OcrFiles(
            File(root, DETECTOR), File(root, LATIN), File(root, LATIN_DICTIONARY), File(root, CYRILLIC), File(root, CYRILLIC_DICTIONARY),
        )
    }
}
```

Create `core/perception/src/main/kotlin/dev/kestrel/perception/OcrEngine.kt`:

```kotlin
package dev.kestrel.perception

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.kestrel.screen.model.IntRect

/** A line that OCR read, in the coordinates of the image that it was given, with its mean character score. */
data class OcrText(val text: String, val bounds: IntRect, val score: Double) {
    override fun toString(): String = "OcrText(text=${text.length} chars, score=$score)"
}

/**
 * Kestrel's OCR (design D20 of sub-project 5; r48): PP-OCRv5's mobile detector and its `latin` and `cyrillic`
 * recognisers through ONNX Runtime, on the CPU with [open]'s threads. Each box is read by the Latin recogniser first;
 * when its mean score is below 0.8, or when the screen's text holds Cyrillic letters, the Cyrillic recogniser reads it
 * too, and the higher mean score wins. The work is blocking and CPU-bound, so callers run it off the main thread, one
 * call at a time.
 */
class OcrEngine private constructor(
    private val detector: Detector,
    private val latin: Recogniser,
    private val cyrillic: Recogniser,
) : AutoCloseable {
    /**
     * The lines in [regions] of [image], each region read on its own; a region inside another one is skipped, and the
     * lines come in reading order. [cyrillicNearby] says that the screen's text holds Cyrillic letters.
     */
    fun read(image: OcrImage, regions: List<IntRect>, cyrillicNearby: Boolean = false, limit: Int = Detector.LIMIT): List<OcrText> {
        val whole = IntRect(0, 0, image.width, image.height)
        val parts = regions.mapNotNull { it.intersect(whole) }.distinct()
        val outer = parts.filter { part -> parts.none { other -> other != part && other.contains(part) } }
        return outer.flatMap { region ->
            val crop = image.crop(region)
            detect(crop, limit).mapNotNull { box ->
                recognise(crop, box, cyrillicNearby)?.let { line ->
                    val bounds = line.bounds
                    line.copy(bounds = IntRect(bounds.left + region.left, bounds.top + region.top, bounds.right + region.left, bounds.bottom + region.top))
                }
            }
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** The text boxes of [image] (the device tests time detection on its own). */
    fun detect(image: OcrImage, limit: Int = Detector.LIMIT): List<TextBox> = detector.boxes(image, limit)

    /** The text of [box] in [image], or null when no recogniser read anything but spaces there. */
    fun recognise(image: OcrImage, box: TextBox, cyrillicNearby: Boolean = false): OcrText? {
        val crop = image.crop(box.bounds)
        val best = choose(latin.read(crop), cyrillicNearby) { cyrillic.read(crop) }
        val text = best.text.trim()
        return if (text.isEmpty()) null else OcrText(text, box.bounds, best.score)
    }

    override fun close() {
        detector.close()
        latin.close()
        cyrillic.close()
    }

    override fun toString(): String = "OcrEngine"

    companion object {
        /** Below this mean score of the Latin recogniser, the Cyrillic one reads the box too. */
        const val CYRILLIC_BELOW = 0.8

        /**
         * Loads the three models with [threads] threads each (two by default; the device tests measure two and four).
         * Fails when a file is missing or a recogniser does not fit its dictionary.
         */
        fun open(files: OcrFiles, threads: Int = 2): OcrEngine {
            val environment = OrtEnvironment.getEnvironment()
            val opened = mutableListOf<AutoCloseable>()
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(threads)
                    options.setInterOpNumThreads(1)
                    fun session(file: java.io.File): OrtSession = environment.createSession(file.path, options).also { opened += it }
                    val detector = Detector(session(files.detector))
                    val latin = Recogniser(session(files.latin), Dictionary.parse(files.latinDictionary.readText()))
                    val cyrillic = Recogniser(session(files.cyrillic), Dictionary.parse(files.cyrillicDictionary.readText()))
                    return OcrEngine(detector, latin, cyrillic)
                }
            } catch (e: Exception) {
                opened.forEach { runCatching { it.close() } }
                throw e
            }
        }

        /**
         * The reading that counts for a box: [latin]'s, unless its score is below [CYRILLIC_BELOW] or [cyrillicNearby]
         * is true; then [cyrillic] reads the box too and the higher score wins, the Latin reading on a tie. A reading of
         * only spaces counts as none, because the Latin recogniser reads Cyrillic text as spaces with a high score.
         */
        internal fun choose(latin: Reading, cyrillicNearby: Boolean, cyrillic: () -> Reading): Reading {
            val first = latin.counted()
            if (first.score >= CYRILLIC_BELOW && !cyrillicNearby) return first
            val second = cyrillic().counted()
            return if (second.score > first.score) second else first
        }

        private fun Reading.counted(): Reading = if (text.isBlank()) Reading("", 0.0) else this
    }
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:perception:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:core:perception` JVM tests 0 + 12 (`DictionaryTest` 2, `CtcDecoderTest` 2, `ImageInputTest` 3, `TextBoxesTest` 2, `RecogniserChoiceTest` 3), 0 failures.

- [ ] **Step 7: Draw the fixture screens**

Create `scripts/draw-ocr-fixtures.ps1`:

```powershell
# Draws the five screens that the OCR golden test reads (design D20 of sub-project 5): English, Serbian Latin with its
# five letters with diacritics, Serbian Cyrillic with its six letters of its own, small text, and a dark theme, each
# 720 x 1280 pixels, as PNG files in app\src\androidTest\assets\ocr. They are drawn on this computer with System.Drawing,
# which Windows PowerShell 5.1 and PowerShell 7 on Windows both have, not captured from a phone, so they hold no one's
# data. The same computer draws the same files; OcrDeviceTest compares Kestrel's OCR of them with golden.txt, which
# RapidOCR's Python reference made once from these files.
# Example: scripts\draw-ocr-fixtures.ps1
param(
    [string]$Folder = (Join-Path $PSScriptRoot '..\app\src\androidTest\assets\ocr')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# The texts are written with \u escapes, because Windows PowerShell 5.1 reads a script without a byte-order mark as
# Windows-1252.
function ConvertFrom-Escaped([string]$Text) { [regex]::Unescape($Text) }

$screens = @(
    @{ Name = 'en'; Dark = $false; Lines = @(
            @(40, 34, 'Settings'), @(150, 26, 'Battery saver'), @(200, 20, 'Turn off when charged'),
            @(320, 26, 'Wi-Fi network'), @(370, 20, 'Connected to Home'), @(490, 26, 'Display brightness'),
            @(610, 26, 'Notifications'), @(730, 26, 'Order 48213 shipped'), @(1100, 28, 'Pay now')) }
    @{ Name = 'sr-latin'; Dark = $false; Lines = @(
            @(40, 34, 'Pode\u0161avanja'), @(150, 26, '\u010ca\u010dak i \u0160abac'),
            @(270, 26, '\u0110or\u0111e \u0161alje poruku'), @(390, 26, '\u017duta ku\u0107a na uglu'),
            @(510, 26, '\u0106evapi za ru\u010dak'), @(630, 26, 'Po\u0161alji sliku'), @(1100, 28, 'Sa\u010duvaj')) }
    @{ Name = 'sr-cyrillic'; Dark = $false; Lines = @(
            @(40, 34, '\u041f\u043e\u0434\u0435\u0448\u0430\u0432\u0430\u045a\u0430'),
            @(150, 26, '\u0409\u0443\u0431\u0430\u0432 \u0438 \u045a\u0435\u0433\u0430'),
            @(270, 26, '\u0402\u043e\u0440\u0452\u0435 \u0448\u0430\u0459\u0435 \u043f\u043e\u0440\u0443\u043a\u0443'),
            @(390, 26, '\u040b\u0443\u043f\u0440\u0438\u0458\u0430 \u0458\u0435 \u043b\u0435\u043f\u0430'),
            @(510, 26, '\u040f\u0435\u043f \u0438 \u0458\u0430\u043a\u043d\u0430'),
            @(630, 26, '\u041f\u043e\u0448\u0430\u0459\u0438 \u0441\u043b\u0438\u043a\u0443'),
            @(1100, 28, '\u0421\u0430\u0447\u0443\u0432\u0430\u0458')) }
    @{ Name = 'small'; Dark = $false; Lines = @(
            @(40, 16, 'Last updated 5 minutes ago'), @(100, 14, 'Tap to see details'), @(160, 14, 'Version 2.4.1'),
            @(220, 13, 'Privacy policy'), @(280, 13, 'Terms of service'), @(340, 13, 'Open source licences'),
            @(1200, 14, 'Help and feedback')) }
    @{ Name = 'dark'; Dark = $true; Lines = @(
            @(40, 34, 'Messages'), @(150, 26, 'Mark all as read'), @(270, 26, 'Archived chats'),
            @(390, 26, 'New group'), @(510, 26, 'Starred messages'), @(1100, 28, 'Search')) }
)

New-Item -ItemType Directory -Force -Path $Folder | Out-Null
foreach ($screen in $screens) {
    $bitmap = New-Object Drawing.Bitmap 720, 1280, ([Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        # Grey-scale anti-aliasing: ClearType's coloured edges would differ from a phone's text.
        $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAlias
        $back = if ($screen.Dark) { [Drawing.Color]::FromArgb(18, 18, 18) } else { [Drawing.Color]::FromArgb(255, 255, 255) }
        $fore = if ($screen.Dark) { [Drawing.Color]::FromArgb(232, 234, 237) } else { [Drawing.Color]::FromArgb(32, 33, 36) }
        $graphics.Clear($back)
        $brush = New-Object Drawing.SolidBrush $fore
        foreach ($line in $screen.Lines) {
            $font = New-Object Drawing.Font 'Segoe UI', ([float]$line[1]), ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)
            try {
                $graphics.DrawString((ConvertFrom-Escaped $line[2]), $font, $brush, [float]48, [float]$line[0])
            } finally {
                $font.Dispose()
            }
        }
        $brush.Dispose()
        $path = Join-Path $Folder "$($screen.Name).png"
        $bitmap.Save($path, [Drawing.Imaging.ImageFormat]::Png)
        Write-Host "$($screen.Name).png $((Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant())"
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}
```

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\draw-ocr-fixtures.ps1`
Expected: exactly these five lines (the same in PowerShell 7):

```text
en.png 337d55f488683c448501644552fa0570df41bbf9cc92f55f9aaee39400739ce8
sr-latin.png 8dd52cd240bd64d03b22e46ef77f84787ffc9b7e53aa4b084dc064594b42afa9
sr-cyrillic.png bf506c566e2d03ac5ba33228fb0d0881b8ce7b133da999c2471643ab37c63bf6
small.png 1bd741bafea004e860f02a11082ee637ea508690942ddc954bc52f5cd26341f9
dark.png 838a4305f95a7f64f45e3edf89bd4c08d79b9933890b70c2ccf0275bc08287f9
```

If a hash differs, stop and ask the controller: the reference's boxes and texts below belong to these exact files.

Create `app/src/androidTest/assets/ocr/golden.txt` (RapidOCR's reference on the five screens, with Kestrel's settings; UTF-8):

```text
en 46 41 182 90 Settings
en 48 153 209 185 Battery saver
en 48 199 260 229 Turn off when charged
en 49 324 220 352 Wi-Fi network
en 48 369 237 396 Connected to Home
en 50 493 270 525 Display brightness
en 46 609 205 646 Notifications
en 50 733 304 765 Order 48213 shipped
en 46 1101 165 1140 Pay now
sr-latin 47 41 249 87 Podešavanja
sr-latin 46 145 219 186 Čačak i Šabac
sr-latin 49 272 277 304 Đorđe šalje poruku
sr-latin 46 386 265 428 Žuta kuća na uglu
sr-latin 47 506 240 547 Ćevapi za ručak
sr-latin 46 629 192 667 Pošalji sliku
sr-latin 43 1095 155 1143 Sačuvaj
sr-cyrillic 49 47 268 85 Подешавања
sr-cyrillic 48 153 226 185 Љубав и њега
sr-cyrillic 47 269 306 311 Ђорђе шаље поруку
sr-cyrillic 48 392 248 428 Ћуприја је лепа
sr-cyrillic 48 513 197 547 Џеп и јакна
sr-cyrillic 46 629 239 670 Пошаљи слику
sr-cyrillic 43 1095 161 1145 Сачувај
small 46 37 254 64 Last updated 5 minutes ago
small 48 100 163 119 Tap to see details
small 46 157 134 180 Version 2.4.1
small 45 216 133 242 Privacy policy
small 49 281 147 296 Terms of service
small 48 340 177 359 Open source licences
small 48 1200 171 1219 Help and feedback
dark 49 46 208 88 Messages
dark 49 152 241 183 Mark all as read
dark 49 272 226 303 Archived chats
dark 44 390 188 430 New group
dark 49 513 261 546 Starred messages
dark 48 1103 140 1136 Search
```

- [ ] **Step 8: Name the OCR files and their facts in the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| B19 | PP-OCRv5 mobile has a Latin recogniser that includes č, ć, š, ž and đ, and a Cyrillic recogniser. It is Apache-2.0 and about 21 MB as ONNX models. | https://huggingface.co/PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec and https://github.com/PaddlePaddle/PaddleOCR | † |
```

with:

```markdown
| B19 | PP-OCRv5 mobile has a Latin recogniser that includes č, ć, š, ž and đ and a Cyrillic recogniser that includes ђ, ј, љ, њ, ћ and џ; Serbian Latin needs `latin` and Serbian Cyrillic `cyrillic`, never `eslav`. PaddlePaddle publishes no ONNX files, so Kestrel uses RapidOCR v3.10.0's conversions of the detector (4,819,576 bytes) and of the Cyrillic recogniser (8,074,092 bytes) from ModelScope, iText Research's conversion of the current Latin recogniser (838 classes, 8,068,114 bytes) from Hugging Face at a pinned commit, and PaddleOCR's dictionaries of commit `8ee4a20` (836 and 850 lines). All are Apache-2.0, about 21 MB together, and pinned by their SHA-256 hashes in `models/manifest.json`. The times on the phones are in section 19.1. | https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec, https://www.modelscope.cn/models/RapidAI/RapidOCR, https://huggingface.co/itextresearch/itext-latin_PP-OCRv5_mobile_rec_infer and https://github.com/PaddlePaddle/PaddleOCR | † |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, add at the end of the file:

```markdown
| B64 | PP-OCRv5's ONNX conversions use only standard operators (opset 14, IR version 10). The reference reads images in BGR order, normalises detection with ImageNet's mean and standard deviation and recognition with (x/255 − 0.5)/0.5 at a height of 48, keeps the longer side at most 960 pixels by default, thresholds the map at 0.3 with a box score of 0.6 and an unclip ratio of 1.5, and decodes CTC greedily with the index map blank, the dictionary's lines, then a space. ONNX Runtime's Java API garbles a model metadata's characters beyond U+FFFF (Kestrel's finding of 2026-11-21), so Kestrel reads both dictionaries from files. No phone measurement of PP-OCRv5 mobile had been published. | https://raw.githubusercontent.com/PaddlePaddle/PaddleX/release/3.3/paddlex/inference/models/text_detection/processors.py, https://raw.githubusercontent.com/PaddlePaddle/PaddleX/release/3.3/paddlex/inference/models/text_recognition/processors.py and https://raw.githubusercontent.com/RapidAI/RapidOCR/main/python/rapidocr/default_models.yaml | † |
```

- [ ] **Step 9: Run the module's checks and the scripts' tests**

Run: `scripts\gradle.ps1 :core:perception:check :core:perception:kestrelAudit`
Expected: `BUILD SUCCESSFUL`: `:core:perception` 12 tests, 0 failures; lint's `core/perception/build/reports/lint-results-debug.txt` says "No issues found."; the audit lists no finding.

Run: `scripts\test-scripts.ps1`
Expected: every Pester test passes in both shells (T₀ each, unchanged), and PSScriptAnalyzer reports nothing, `draw-ocr-fixtures.ps1` included.

- [ ] **Step 10: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 11: Commit**

```powershell
git add core/perception/src scripts/draw-ocr-fixtures.ps1 app/src/androidTest/assets/ocr docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(perception): read text with PP-OCRv5 and check each step against the reference's outputs" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 3: `OcrDeviceTest` and the canvas fixture

**Files:**
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/OcrCanvasActivity.kt`
- Modify: `testapp/src/main/AndroidManifest.xml` (below `<!-- sub-project 5C: fixtures -->`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/OcrDeviceTest.kt`

**Interfaces:**
- Consumes: Task 2's `OcrEngine`, `OcrFiles`, `OcrImage`, `Detector.LIMIT` and the assets `ocr/<name>.png` and `ocr/golden.txt`; Task 1's `Engine.OCR` and the pushed models; `VoiceTests.graph` and `VoiceTests.assumeModels` (`app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt`); `:testapp`'s `fixturePage`, `addStatus` and `dp` (`Fixtures.kt`).
- Produces: `dev.kestrel.testapp.OcrCanvasActivity` (title "OCR canvas", started with `am start -n dev.kestrel.testapp/.OcrCanvasActivity`, no extras): a status line ("Ready"), an icon button without a label ("Icon pressed" when tapped) and a canvas of 340 dp without tree text that draws "Show details" in a blue button, "Čačak i Šabac", "Ђорђе шаље поруку", a small "Version 2.4.1" and a dark band with "Archived chats"; a tap inside the drawn button shows "Details shown" in the status line. Task 6's `LookCloserTest` and sitting 2's `look_closer` run (Tasks 9 and 10) use it. `OcrDeviceTest`'s three tests `theThreeModelsLoadAndRun`, `readsTheFiveFixturesAsTheReferenceDid` and `measuresTimeAndMemory`, the last of which writes one logcat line per measure with the tag `KestrelOcr` (level I, numbers only): `ocr image=<full|region> limit=<960|1280> threads=<2|4> loadMs=<n> detectMs=<n> recogniseMs=<n> lines=<n> pssKb=<n>`, which sitting 1 reads with `logcat -d -s KestrelOcr:I` on the A71 (D30 step 9).

Design D20, D28 and D32 item 31 (the controller's decision on r48: a load test of each model on kestrel-api37 and on the A71, which fails on any error about an operator, because r48 could not confirm ONNX Runtime 1.28.0's HardSwish and HardSigmoid kernels). The device test reads the pushed models through Kestrel's own model folder, as `OnnxRuntimeTogetherTest` does, and skips itself without them. The golden comparison asks for the reference's texts exactly and boxes that overlap the reference's by at least 70 %, because the device's ONNX Runtime and the reference's differ a little in floating point. The time and memory are measured at 960 and once at 1280 with two and four threads, for a full window of a 1080 × 2400 phone (the English fixture scaled to 1080 × 1920) and a region; they replace the spec's unmeasured "2–5 s" (spec 11.6, B19) when Tasks 9 to 12 record them. The canvas fixture is the screen on which the agent needs OCR: its text is pixels only.

- [ ] **Step 1: Write the device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/OcrDeviceTest.kt`:

```kotlin
package dev.kestrel.app.agent.eyes

import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.perception.Detector
import dev.kestrel.perception.OcrEngine
import dev.kestrel.perception.OcrFiles
import dev.kestrel.perception.OcrImage
import dev.kestrel.screen.model.IntRect
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kestrel's OCR on the device (design D20 and D32 item 31 of sub-project 5): the three models load and run with ONNX
 * Runtime 1.28.0 (r48 could not confirm its HardSwish and HardSigmoid kernels), Kestrel reads the five fixture screens of
 * `scripts\draw-ocr-fixtures.ps1` as RapidOCR's Python reference did (`assets/ocr/golden.txt`), and the time and memory
 * are measured for the record. The models come from Kestrel's model folder, where `scripts\push-models.ps1` puts them;
 * without them the tests are skipped. The fixtures hold only test text, and no assertion message holds any text.
 */
@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.OCR)

    private val files: OcrFiles get() = runBlocking { withContext(Dispatchers.IO) { OcrFiles.under(VoiceTests.graph.models.root) } }

    private fun fixture(name: String): OcrImage {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("ocr/$name.png").use { BitmapFactory.decodeStream(it) }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return OcrImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun whole(image: OcrImage) = listOf(IntRect(0, 0, image.width, image.height))

    @Test
    fun theThreeModelsLoadAndRun() {
        OcrEngine.open(files).use { engine ->
            val image = fixture("sr-cyrillic")
            val boxes = engine.detect(image)
            assertTrue(boxes.isNotEmpty())
            // With Cyrillic nearby, both recognisers read the box.
            val line = engine.recognise(image, boxes.first(), cyrillicNearby = true)
            assertTrue(line != null && line.text.isNotEmpty())
        }
    }

    @Test
    fun readsTheFiveFixturesAsTheReferenceDid() {
        val golden = InstrumentationRegistry.getInstrumentation().context.assets.open("ocr/golden.txt")
            .bufferedReader().readLines().filter { it.isNotBlank() }.map { it.split(' ', limit = 6) }
        OcrEngine.open(files).use { engine ->
            for (name in FIXTURES) {
                val image = fixture(name)
                val read = engine.read(image, whole(image), cyrillicNearby = name == "sr-cyrillic")
                val expected = golden.filter { it[0] == name }
                assertEquals("lines of $name", expected.size, read.size)
                expected.forEachIndexed { index, line ->
                    val box = IntRect(line[1].toInt(), line[2].toInt(), line[3].toInt(), line[4].toInt())
                    // The same text, in a box that overlaps the reference's by at least 70 % (intersection over union).
                    val match = read.any { it.text == line[5] && overlap(it.bounds, box) >= 0.7 }
                    assertTrue("line $index of $name", match)
                }
            }
        }
    }

    @Test
    fun measuresTimeAndMemory() {
        // A full window of a 1080 × 2400 phone and a region, as look_closer reads them.
        val full = fixture("en").scaled(1080, 1920)
        val region = fixture("small").crop(IntRect(0, 80, 360, 200))
        for (threads in listOf(2, 4)) {
            val started = SystemClock.elapsedRealtime()
            OcrEngine.open(files, threads).use { engine ->
                val loadMs = SystemClock.elapsedRealtime() - started
                for (limit in listOf(Detector.LIMIT, 1280)) {
                    for ((kind, image) in listOf("full" to full, "region" to region)) {
                        val detectStart = SystemClock.elapsedRealtime()
                        val boxes = engine.detect(image, limit)
                        val detectMs = SystemClock.elapsedRealtime() - detectStart
                        val recogniseStart = SystemClock.elapsedRealtime()
                        val lines = boxes.mapNotNull { engine.recognise(image, it) }
                        val recogniseMs = SystemClock.elapsedRealtime() - recogniseStart
                        assertTrue("lines of $kind", lines.isNotEmpty())
                        Log.i(TAG, "ocr image=$kind limit=$limit threads=$threads loadMs=$loadMs detectMs=$detectMs recogniseMs=$recogniseMs lines=${lines.size} pssKb=${Debug.getPss()}")
                    }
                }
            }
        }
    }

    private fun overlap(a: IntRect, b: IntRect): Double {
        val common = a.intersect(b) ?: return 0.0
        val shared = common.width.toDouble() * common.height
        return shared / (a.width.toDouble() * a.height + b.width.toDouble() * b.height - shared)
    }

    private companion object {
        const val TAG = "KestrelOcr"
        val FIXTURES = listOf("en", "sr-latin", "sr-cyrillic", "small", "dark")
    }
}
```

- [ ] **Step 2: Add the canvas fixture**

Create `testapp/src/main/kotlin/dev/kestrel/testapp/OcrCanvasActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout

/**
 * Text that only OCR can read (design D28 of sub-project 5): English, Serbian Latin and Serbian Cyrillic drawn on a
 * Canvas, large and small, dark on light and light on dark, a drawn button "Show details", and an icon button without a
 * label. The canvas has no text in the accessibility tree: it is one clickable element without a label. A tap inside the
 * drawn button shows "Details shown" in the status line, and a tap on the icon "Icon pressed".
 */
class OcrCanvasActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("OCR canvas")
        val status = page.addStatus()
        page.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setOnClickListener { status.text = "Icon pressed" }
        })
        page.addView(
            TextCanvas(this) { status.text = "Details shown" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340)),
        )
    }

    private class TextCanvas(context: Context, private val onButton: () -> Unit) : View(context) {
        private val button = RectF()
        private var downX = -1f
        private var downY = -1f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            setOnClickListener { if (button.contains(downX, downY)) onButton() }
        }

        // super.onTouchEvent calls performClick on a tap, which the click listener reads together with the touch's point.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
            }
            return super.onTouchEvent(event)
        }

        // The click goes through performClick, so that a click of Android's accessibility reaches the listener too.
        override fun performClick(): Boolean = super.performClick()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val left = dp(8f)
            button.set(left, dp(8f), left + dp(180f), dp(60f))
            fill.color = Color.rgb(26, 115, 232)
            canvas.drawRoundRect(button, dp(8f), dp(8f), fill)
            line(canvas, "Show details", left + dp(16f), dp(43f), 20f, Color.WHITE)
            line(canvas, "\u010ca\u010dak i \u0160abac", left, dp(110f), 22f, Color.rgb(32, 33, 36))
            line(canvas, "\u0402\u043e\u0440\u0452\u0435 \u0448\u0430\u0459\u0435 \u043f\u043e\u0440\u0443\u043a\u0443", left, dp(160f), 22f, Color.rgb(32, 33, 36))
            line(canvas, "Version 2.4.1", left, dp(200f), 12f, Color.rgb(95, 99, 104))
            fill.color = Color.rgb(18, 18, 18)
            canvas.drawRect(0f, dp(240f), width.toFloat(), dp(320f), fill)
            line(canvas, "Archived chats", left, dp(290f), 20f, Color.rgb(232, 234, 237))
        }

        private fun line(canvas: Canvas, value: String, x: Float, y: Float, sp: Float, color: Int) {
            text.color = color
            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
            canvas.drawText(value, x, y, text)
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
    }
}
```

In `testapp/src/main/AndroidManifest.xml`, replace:

```xml
        <!-- sub-project 5C: fixtures -->
```

with:

```xml
        <!-- sub-project 5C: fixtures -->
        <activity
            android:name=".OcrCanvasActivity"
            android:exported="true" />
```

- [ ] **Step 3: Build the app, the fixtures and the device tests**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:compileDebugAndroidTestKotlin :testapp:assembleDebug :testapp:lintDebug`
Expected: `BUILD SUCCESSFUL`; `testapp/build/reports/lint-results-debug.txt` says "No issues found."

- [ ] **Step 4: Run the device test on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.agent.eyes.OcrDeviceTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`; 3 tests, 0 failures and 0 skipped (a skipped test means that Task 1's `push-models.ps1` did not run: run it and repeat). No error names an operator. `app/build/outputs/androidTest-results/connected/debug/` holds the result.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 logcat -d -s KestrelOcr:I`
Expected: 8 lines `ocr image=… limit=… threads=… loadMs=… detectMs=… recogniseMs=… lines=… pssKb=…`, each with `lines=` at least 1. Copy them into the task's report for the controller (numbers only). If a full window at 960 reads fewer lines than at 1280 with the same threads, say so in the report: the controller decides whether 1280 is needed (D20).

- [ ] **Step 5: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 6: Commit**

`:app`'s device tests: `dev.kestrel.app.agent` D_A + 3; the other groups unchanged.

```powershell
git add testapp/src/main/kotlin/dev/kestrel/testapp/OcrCanvasActivity.kt testapp/src/main/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/OcrDeviceTest.kt
git commit -m "test(perception): load the OCR models on the device, compare them with the reference and measure them" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 4: `ScreenCapture`, `hideAllForCapture` and `ScreenCaptureTest`

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/capture/ScreenCapture.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/HiddenOverlays.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt` (one property), `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (below `// sub-project 5C`)
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/capture/ScreenCaptureRulesTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (9.4's last paragraph, B10, and B65 at the end of Appendix B)

**Interfaces:**
- Consumes: `AccessibilityService.takeScreenshot` (API 30) and `takeScreenshotOfWindow` (API 34), `ScreenshotResult`, `Bitmap.wrapHardwareBuffer`; `OverlayHost` and its private `shown`; `ScreenSession`'s private `overlays`, `numbers`, `numbersMode`, `whichOneShownOn`, `whichOne`, `changing` and its `showNumbers`; `IntRect` with `intersect`, `contains`, `isEmpty`; the device tests' `ScreenServiceRule` (`open`, `awaitElement`, `automation`) and `:testapp`'s `ViewsActivity` (the button "SHOW DIALOG" and its dialog "Delete draft?") and `SecureActivity` ("Secure fixture").
- Produces (package `dev.kestrel.screen.capture`): `sealed interface CaptureResult { class Image(val bitmap: Bitmap, val originX: Int, val originY: Int, val appPixels: List<IntRect>, val blocked: List<IntRect>); data object Secure; data class NotNow(val reason: String) }` (areas in screen coordinates); `class ScreenCapture` with `suspend fun capture(windowId: Int, wholeDisplay: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE): CaptureResult` and `ScreenCapture.INTERVAL_MILLIS = 400`, `RETRY_MILLIS = 350`, `FAILED = "The screenshot failed."`, `IN_THE_WAY = "A notification is in the way; try again in a moment."`, `GONE = "The app's window is gone."`, `uncovered(area: IntRect, holes: List<IntRect>): List<IntRect>`, `mostlyBlack(pixels: IntArray, width: Int, parts: List<IntRect>): Boolean`. Package `dev.kestrel.screen.overlay`: `class HiddenOverlays : AutoCloseable` with `val bounds: List<IntRect>`; `OverlayHost.visibleViews` (internal). `ScreenSession`: `val capture: ScreenCapture`, `suspend fun hideAllForCapture(): HiddenOverlays?`, `suspend fun showBadges(badges: List<Badge>)` and `suspend fun hideBadges()` (`Badge` is `dev.kestrel.screen.overlay.Badge(number, key, bounds)`), which Task 5's `AppEyes` and Task 6's `AppGraph` block use. `ScreenCaptureTest` with `theWindowCaptureStartsAtItsWindowAndKeepsTheInterval`, `theDisplayCaptureHidesKestrelsOverlaysAndBlacksOutTheBars` and `aSecureWindowIsSecureOnBothPaths`, which sitting 1 runs on the A71 (D30 step 9), where the first one is skipped (API 33).

Design D20 (r49; decision 5; review B I11, I13, M10, n1) and D32 item 30. From API 34 Kestrel captures the app's window alone, whose image holds no other window and none of Kestrel's overlays, and a secure window gives error 6. The A71's Android 13 can capture only the whole display, Kestrel's overlays included, so Kestrel makes them transparent, waits until Android has committed that frame and at least 50 ms, captures, and shows them again at once; it then crops the image to the app's window and paints black every part that a window above it covers (the bars, the keyboard, other apps' dialogs, the shade) and every overlay of Kestrel's that does not cover the whole screen. On every level, a window whose own pixels are at least 99 % pure black counts as secure (a window that is secure only by policy is black without an error; dark themes are rarely pure black). Android's window list is read before and after the capture; when a window came, went or moved meanwhile, such as a heads-up notification, the capture is dropped and tried once more, then the sentence `IN_THE_WAY` comes. At most one capture runs per 400 ms, and Android's error 3 is tried once more after 350 ms. The hardware buffer is copied into an ordinary bitmap and closed at once; the caller recycles the bitmap. `showBadges` draws the agent's numbers instead of Numbering's own (D20's third step) and pauses "show numbers" meanwhile. The device test runs the display capture on kestrel-api37 too (`wholeDisplay = true`), so that the A71's path is tested before sitting 1; the pure parts have a JVM test.

- [ ] **Step 1: Write the failing JVM test**

Create `core/screen/src/test/kotlin/dev/kestrel/screen/capture/ScreenCaptureRulesTest.kt`:

```kotlin
package dev.kestrel.screen.capture

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the screenshot (design D20 of sub-project 5): the app's uncovered areas and the black check. */
class ScreenCaptureRulesTest {
    @Test
    fun theUncoveredAreasLeaveOutEveryCoverAndDoNotOverlap() {
        val area = IntRect(0, 0, 100, 200)
        val bar = IntRect(0, 0, 100, 20)
        val keyboard = IntRect(0, 150, 100, 200)
        val dialog = IntRect(30, 60, 70, 100)

        val parts = ScreenCapture.uncovered(area, listOf(bar, keyboard, dialog))

        val pixels = parts.sumOf { it.width * it.height }
        assertEquals(100 * 200 - 100 * 20 - 100 * 50 - 40 * 40, pixels)
        for (part in parts) {
            assertTrue(area.contains(part))
            for (cover in listOf(bar, keyboard, dialog)) assertEquals(null, part.intersect(cover))
            for (other in parts) if (other !== part) assertEquals(null, part.intersect(other))
        }
        assertEquals(listOf(area), ScreenCapture.uncovered(area, listOf(IntRect(200, 0, 300, 10))))
    }

    @Test
    fun aWindowCountsAsBlackOnlyWhenNinetyNinePercentOfItsOwnPixelsArePureBlack() {
        val width = 10
        val black = 0xFF000000.toInt()
        val pixels = IntArray(width * 10) { black }
        val all = listOf(IntRect(0, 0, 10, 10))
        assertTrue(ScreenCapture.mostlyBlack(pixels, width, all))

        // A dark theme's near-black is not black.
        pixels[0] = 0xFF121212.toInt()
        pixels[1] = 0xFF121212.toInt()
        assertFalse(ScreenCapture.mostlyBlack(pixels, width, all))
        // Only the app's own areas count: the two grey pixels lie in a covered row.
        assertTrue(ScreenCapture.mostlyBlack(pixels, width, listOf(IntRect(0, 1, 10, 10))))
        assertFalse(ScreenCapture.mostlyBlack(pixels, width, emptyList()))
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest --tests dev.kestrel.screen.capture.ScreenCaptureRulesTest`
Expected: FAIL: the compilation of the tests stops with "Unresolved reference 'ScreenCapture'".

- [ ] **Step 3: Let a screenshot hide the overlays**

In `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt`, replace:

```kotlin
    val context: Context get() = service
```

with:

```kotlin
    val context: Context get() = service

    /** The overlays on the screen now, for a screenshot that hides them (design D20 of sub-project 5). */
    internal val visibleViews: List<View> get() = shown.toList()
```

Create `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/HiddenOverlays.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.os.SystemClock
import android.view.View
import dev.kestrel.screen.model.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Kestrel's overlays while a screenshot of the whole display is taken (design D20 of sub-project 5): Android 13's
 * capture holds them, so they are made transparent first. [close] shows them again; use it on the main thread. [bounds]
 * are the screen bounds of the overlays that did not cover the whole screen, such as the feedback bar, which the capture
 * paints black in case one of them was drawn again in the meantime.
 */
class HiddenOverlays internal constructor(private val views: List<Pair<View, Float>>, val bounds: List<IntRect>) : AutoCloseable {
    override fun close() {
        // An overlay that changed its own transparency meanwhile, such as the feedback bar while it fades, keeps it.
        for ((view, alpha) in views) if (view.alpha == 0f) view.alpha = alpha
    }

    override fun toString(): String = "HiddenOverlays(${views.size})"

    internal companion object {
        const val TIMEOUT_MILLIS = 500L
        const val MIN_MILLIS = 50L

        /**
         * Makes every overlay of [host] transparent and returns once Android has committed a frame of each one and at
         * least 50 ms have passed, or null, with the overlays shown again, when a frame did not come within 500 ms.
         */
        suspend fun hide(host: OverlayHost): HiddenOverlays? {
            val started = SystemClock.uptimeMillis()
            val screen = host.screenBounds()
            val views = host.visibleViews.filter { it.isAttachedToWindow && it.alpha > 0f }
            val bounds = views.map { view ->
                val at = IntArray(2).also(view::getLocationOnScreen)
                IntRect(at[0], at[1], at[0] + view.width, at[1] + view.height)
            }.filter { it != screen && !it.isEmpty }
            val hidden = HiddenOverlays(views.map { it to it.alpha }, bounds)
            views.forEach { it.alpha = 0f }
            try {
                val drawn = withTimeoutOrNull(TIMEOUT_MILLIS) { coroutineScope { views.map { async { committed(it) } }.awaitAll() } }
                if (drawn == null) {
                    hidden.close()
                    return null
                }
                val left = MIN_MILLIS - (SystemClock.uptimeMillis() - started)
                if (left > 0) delay(left)
            } catch (e: CancellationException) {
                // A cancelled capture never receives the handle, so the overlays are shown again here (on the main thread).
                hidden.close()
                throw e
            }
            return hidden
        }

        private suspend fun committed(view: View) = suspendCancellableCoroutine { continuation ->
            view.viewTreeObserver.registerFrameCommitCallback { if (continuation.isActive) continuation.resume(Unit) }
            view.invalidate()
        }
    }
}
```

- [ ] **Step 4: Write the capture**

Create `core/screen/src/main/kotlin/dev/kestrel/screen/capture/ScreenCapture.kt`:

```kotlin
package dev.kestrel.screen.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.overlay.HiddenOverlays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** What a screenshot of the app in front gave (design D20 of sub-project 5). */
sealed interface CaptureResult {
    /**
     * The app window's pixels, which exist only in memory: [bitmap] starts at ([originX], [originY]) on the screen,
     * [appPixels] are the app's areas that no other window covers and [blocked] the areas painted black, both in screen
     * coordinates. The caller recycles [bitmap].
     */
    class Image(val bitmap: Bitmap, val originX: Int, val originY: Int, val appPixels: List<IntRect>, val blocked: List<IntRect>) : CaptureResult {
        override fun toString(): String = "Image(${bitmap.width}x${bitmap.height}, blocked=${blocked.size})"
    }

    /** The app blocks screenshots: Android refused the capture, or the app's own pixels are black. */
    data object Secure : CaptureResult

    /** No screenshot now; [reason] is one of [ScreenCapture]'s fixed sentences. */
    data class NotNow(val reason: String) : CaptureResult
}

/**
 * Screenshots of one app window for the agent's `look_closer` (design D20 of sub-project 5; r49). From API 34 Android
 * captures the window alone (`takeScreenshotOfWindow`), so no other window and none of Kestrel's overlays is in it, and a
 * secure window gives error 6. On API 33 (the A71) only the whole display can be captured: Kestrel's overlays are made
 * transparent first, the image is cropped to the window, and every part that a window above it or a visible overlay of
 * Kestrel's covers is painted black. On every level a window whose own pixels are at least 99 % pure black counts as
 * secure. Android's window list is read before and after each capture; when it changed, the capture is dropped and tried
 * once more. At most one capture runs per 400 ms. Bitmaps live only in memory and are never written anywhere.
 */
class ScreenCapture internal constructor(
    private val service: AccessibilityService,
    private val hideOverlays: suspend () -> HiddenOverlays?,
) {
    private val lock = Mutex()
    private var lastMillis = 0L

    /** One window's place in Android's window list. */
    private data class Shape(val id: Int, val type: Int, val layer: Int, val bounds: IntRect)

    /**
     * A screenshot of the window [windowId]. [wholeDisplay] chooses the capture of the whole display, which API 33 needs
     * and which the device tests also run on later levels.
     */
    suspend fun capture(windowId: Int, wholeDisplay: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE): CaptureResult =
        lock.withLock {
            withContext(Dispatchers.Main) {
                repeat(2) {
                    val before = shapes()
                    val target = before.firstOrNull { it.id == windowId } ?: return@withContext CaptureResult.NotNow(GONE)
                    val wait = lastMillis + INTERVAL_MILLIS - SystemClock.uptimeMillis()
                    if (wait > 0) delay(wait)
                    val shot = if (wholeDisplay || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        displayShot(target, before)
                    } else {
                        windowShot(target)
                    }
                    if (shot !is CaptureResult.Image) return@withContext shot
                    // A window that came, went or moved during the capture, such as a notification, may be in it.
                    if (shapes() == before) return@withContext shot
                    shot.bitmap.recycle()
                }
                CaptureResult.NotNow(IN_THE_WAY)
            }
        }

    private fun shapes(): List<Shape> = service.windows.map { window ->
        val bounds = Rect().also(window::getBoundsInScreen)
        Shape(window.id, window.type, window.layer, IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun windowShot(target: Shape): CaptureResult {
        val result = when (val shot = shoot { executor, callback -> service.takeScreenshotOfWindow(target.id, executor, callback) }) {
            is Shot.Taken -> shot.result
            is Shot.Refused -> return shot.result
        }
        val bitmap = toBitmap(result) ?: return CaptureResult.NotNow(FAILED)
        // A window with a shadow, such as a dialog, has the same margin around its bounds on each side of its image.
        val originX = target.bounds.left - maxOf(0, bitmap.width - target.bounds.width) / 2
        val originY = target.bounds.top - maxOf(0, bitmap.height - target.bounds.height) / 2
        return finish(bitmap, originX, originY, listOf(IntRect(0, 0, bitmap.width, bitmap.height)), emptyList())
    }

    private suspend fun displayShot(target: Shape, windows: List<Shape>): CaptureResult {
        val hidden = hideOverlays() ?: return CaptureResult.NotNow(FAILED)
        val shot = try {
            shoot { executor, callback -> service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback) }
        } finally {
            hidden.close()
        }
        val result = when (shot) {
            is Shot.Taken -> shot.result
            is Shot.Refused -> return shot.result
        }
        val display = toBitmap(result) ?: return CaptureResult.NotNow(FAILED)
        val area = target.bounds.intersect(IntRect(0, 0, display.width, display.height))
        if (area == null) {
            display.recycle()
            return CaptureResult.NotNow(GONE)
        }
        // Every window above the app's (the bars, the keyboard, other apps' dialogs, the shade) and every overlay of
        // Kestrel's that was visible, in the crop's coordinates.
        val covers = windows.filter { it.layer > target.layer }.map { it.bounds } + hidden.bounds
        val blocked = covers.mapNotNull { it.intersect(area) }
            .map { IntRect(it.left - area.left, it.top - area.top, it.right - area.left, it.bottom - area.top) }
        val crop = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888).also { crop ->
                val canvas = Canvas(crop)
                canvas.drawBitmap(display, -area.left.toFloat(), -area.top.toFloat(), null)
                val black = Paint().apply { color = BLACK }
                for (rect in blocked) canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), black)
            }
        }
        display.recycle()
        return finish(crop, area.left, area.top, uncovered(IntRect(0, 0, area.width, area.height), blocked), blocked)
    }

    // The black check on the app's own pixels (r49: a window that is secure only by policy is black without an error).
    private suspend fun finish(bitmap: Bitmap, originX: Int, originY: Int, appPixels: List<IntRect>, blocked: List<IntRect>): CaptureResult {
        val black = withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            mostlyBlack(pixels, bitmap.width, appPixels)
        }
        if (black) {
            bitmap.recycle()
            return CaptureResult.Secure
        }
        fun IntRect.onScreen() = IntRect(left + originX, top + originY, right + originX, bottom + originY)
        return CaptureResult.Image(bitmap, originX, originY, appPixels.map { it.onScreen() }, blocked.map { it.onScreen() })
    }

    private sealed interface Shot {
        class Taken(val result: ScreenshotResult) : Shot

        class Refused(val result: CaptureResult) : Shot
    }

    // Android's error 3 (too soon after the last screenshot) is tried once more after 350 ms.
    private suspend fun shoot(take: (Executor, TakeScreenshotCallback) -> Unit): Shot {
        val first = shootOnce(take)
        if (first !is Shot.Refused || first.result != TOO_SOON) return first
        delay(RETRY_MILLIS)
        val second = shootOnce(take)
        return if (second is Shot.Refused && second.result == TOO_SOON) Shot.Refused(CaptureResult.NotNow(FAILED)) else second
    }

    private suspend fun shootOnce(take: (Executor, TakeScreenshotCallback) -> Unit): Shot {
        lastMillis = SystemClock.uptimeMillis()
        return suspendCancellableCoroutine { continuation ->
            take(service.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    if (continuation.isActive) continuation.resume(Shot.Taken(result)) else result.hardwareBuffer.close()
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(Shot.Refused(failure(errorCode)))
                }
            })
        }
    }

    private fun failure(errorCode: Int): CaptureResult = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> CaptureResult.Secure
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> TOO_SOON
        else -> CaptureResult.NotNow(FAILED)
    }

    // The hardware buffer is copied into an ordinary bitmap and closed at once (r49: about 10 MiB for the A71's display).
    private suspend fun toBitmap(result: ScreenshotResult): Bitmap? = withContext(Dispatchers.Default) {
        result.hardwareBuffer.use { buffer ->
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@withContext null
            try {
                wrapped.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                wrapped.recycle()
            }
        }
    }

    override fun toString(): String = "ScreenCapture"

    companion object {
        const val INTERVAL_MILLIS = 400L
        const val RETRY_MILLIS = 350L
        const val FAILED = "The screenshot failed."
        const val IN_THE_WAY = "A notification is in the way; try again in a moment."
        const val GONE = "The app's window is gone."
        private val TOO_SOON = CaptureResult.NotNow("too soon")
        private const val BLACK = 0xFF000000.toInt()

        /** [area] without [holes], as rectangles that do not overlap. */
        fun uncovered(area: IntRect, holes: List<IntRect>): List<IntRect> = holes.fold(listOf(area)) { parts, hole ->
            parts.flatMap { part ->
                val overlap = part.intersect(hole) ?: return@flatMap listOf(part)
                listOf(
                    IntRect(part.left, part.top, part.right, overlap.top),
                    IntRect(part.left, overlap.bottom, part.right, part.bottom),
                    IntRect(part.left, overlap.top, overlap.left, overlap.bottom),
                    IntRect(overlap.right, overlap.top, part.right, overlap.bottom),
                ).filterNot { it.isEmpty }
            }
        }

        /**
         * Whether at least 99 % of the pixels in [parts] of an image [width] pixels wide are pure black (red, green and
         * blue 0), as a secure window is in a screenshot; dark themes are rarely pure black. No pixels count as not black.
         */
        fun mostlyBlack(pixels: IntArray, width: Int, parts: List<IntRect>): Boolean {
            var all = 0L
            var black = 0L
            for (part in parts) {
                for (y in part.top until part.bottom) {
                    for (x in part.left until part.right) {
                        all++
                        if ((pixels[y * width + x] and 0xFFFFFF) == 0) black++
                    }
                }
            }
            return all > 0 && black * 100 >= all * 99
        }
    }
}
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace:

```kotlin
    // sub-project 5C
}
```

with:

```kotlin
    // sub-project 5C

    /** Screenshots of the app in front for the agent's look_closer (design D20 of sub-project 5). */
    val capture = dev.kestrel.screen.capture.ScreenCapture(service) { hideAllForCapture() }

    // The agent's numbers of look_closer's third step, and the mode of "show numbers" that they replaced.
    private var agentBadges = false
    private var modeBeforeBadges: NumbersMode? = null

    /**
     * Makes Kestrel's overlays transparent for a screenshot of the whole display and returns the handle that shows them
     * again, or null when Android did not draw them hidden within 500 ms (design D20 of sub-project 5).
     */
    suspend fun hideAllForCapture(): dev.kestrel.screen.overlay.HiddenOverlays? =
        withContext(Dispatchers.Main) { dev.kestrel.screen.overlay.HiddenOverlays.hide(overlays) }

    /**
     * Shows the agent's [badges] instead of Numbering's own until [hideBadges] (design D20 of sub-project 5); "show
     * numbers" pauses meanwhile and comes back afterwards.
     */
    suspend fun showBadges(badges: List<Badge>) = withContext(Dispatchers.Main) {
        if (!agentBadges) {
            agentBadges = true
            modeBeforeBadges = numbersMode
        }
        numbersMode = null
        whichOneShownOn = null
        whichOne.value = false
        changing { numbers.show(badges) }
    }

    /** Removes the agent's numbers; the numbers of "show numbers" come back when it was on. Nothing else changes. */
    suspend fun hideBadges() = withContext(Dispatchers.Main) {
        if (!agentBadges) return@withContext
        agentBadges = false
        val mode = modeBeforeBadges
        modeBeforeBadges = null
        if (mode == null) changing { numbers.hide() } else showNumbers(mode)
    }
}
```

- [ ] **Step 5: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:core:screen` JVM tests S₀ + 2, 0 failures.

- [ ] **Step 6: Write the device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt`:

```kotlin
package dev.kestrel.app.screen

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.capture.CaptureResult
import dev.kestrel.screen.capture.ScreenCapture
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Screenshots for the agent's look_closer (design D20 of sub-project 5; r49): the capture of one window from API 34, the
 * capture of the display with Kestrel's overlays hidden, which API 33 needs and which this test runs on every level, and
 * a secure window on both. The images stay in memory and are recycled.
 */
@RunWith(AndroidJUnit4::class)
class ScreenCaptureTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun front(): WindowSummary = ScreenTree.frontWindow(session.snapshots.current().snapshot)!!

    /** ViewsActivity's dialog "Delete draft?", a window that does not start at (0, 0). */
    private suspend fun dialog(): WindowSummary {
        screen.open("ViewsActivity", "Views fixture")
        session.actions.tap(screen.awaitElement { it.label == "SHOW DIALOG" }.key)
        val title = screen.awaitElement { it.label == "Delete draft?" }
        return session.snapshots.current().snapshot.windows.first { it.id == title.windowId }
    }

    @Test
    fun theWindowCaptureStartsAtItsWindowAndKeepsTheInterval() = runBlocking {
        assumeTrue("takeScreenshotOfWindow exists from API 34", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val window = dialog()
        assertTrue(window.bounds.left > 0 || window.bounds.top > 0)

        val started = SystemClock.uptimeMillis()
        val first = session.capture.capture(window.id, wholeDisplay = false) as CaptureResult.Image
        val second = session.capture.capture(window.id, wholeDisplay = false) as CaptureResult.Image
        val took = SystemClock.uptimeMillis() - started
        try {
            // The image covers the window; a dialog's shadow adds the same margin on each side.
            assertTrue(first.originX <= window.bounds.left && first.originY <= window.bounds.top)
            assertTrue(first.originX + first.bitmap.width >= window.bounds.right && first.originY + first.bitmap.height >= window.bounds.bottom)
            assertTrue(first.blocked.isEmpty())
            assertTrue("two captures took $took ms", took >= ScreenCapture.INTERVAL_MILLIS)
        } finally {
            first.bitmap.recycle()
            second.bitmap.recycle()
        }
    }

    @Test
    fun theDisplayCaptureHidesKestrelsOverlaysAndBlacksOutTheBars() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val window = front()
        val plain = session.capture.capture(window.id, wholeDisplay = true) as CaptureResult.Image
        session.showNumbers()
        withTimeout(5_000) { while (session.numberBadges.isEmpty()) delay(100) }
        val hidden = session.capture.capture(window.id, wholeDisplay = true) as CaptureResult.Image
        try {
            // The numbers' blue is in neither image, although the numbers show.
            assertEquals(0, blue(plain.bitmap))
            assertEquals(0, blue(hidden.bitmap))
            // Every part that a window above the app covers, such as the status bar, is black in the image, and the app's
            // own areas leave it out.
            for (part in hidden.blocked) {
                assertEquals(Color.BLACK, hidden.bitmap.getPixel(part.centerX - hidden.originX, part.centerY - hidden.originY))
            }
            assertTrue(hidden.appPixels.none { part -> hidden.blocked.any { part.intersect(it) != null } })
            // The numbers show again at once.
            val after = screen.automation.takeScreenshot()!!
            try {
                assertTrue(blue(after) > 0)
            } finally {
                after.recycle()
            }
        } finally {
            session.hideNumbers()
            plain.bitmap.recycle()
            hidden.bitmap.recycle()
        }
    }

    @Test
    fun aSecureWindowIsSecureOnBothPaths() = runBlocking {
        screen.open("SecureActivity", "Secure fixture")
        val window = front()

        assertEquals(CaptureResult.Secure, session.capture.capture(window.id, wholeDisplay = true))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(CaptureResult.Secure, session.capture.capture(window.id, wholeDisplay = false))
        }
    }

    // The pixels in the numbers' blue (NumbersOverlay's 0xE61565C0 over the fixture), counted in every fourth row and column.
    private fun blue(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(x, y)
                if (abs(Color.red(pixel) - 0x15) < 40 && abs(Color.green(pixel) - 0x65) < 40 && abs(Color.blue(pixel) - 0xC0) < 40) count++
            }
        }
        return count
    }

}
```

- [ ] **Step 7: Run the device test on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.ScreenCaptureTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`; 3 tests, 0 failures, 0 skipped (kestrel-api37 is API 37, so the window capture runs too). If `theDisplayCaptureHidesKestrelsOverlaysAndBlacksOutTheBars` fails with a `ClassCastException` from `NotNow`, Android did not draw the hidden overlays within 500 ms: report it to the controller with the test's result file, because the A71 would then never capture.

- [ ] **Step 8: Describe the screenshots in the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
Before Kestrel takes a screenshot for OCR or for the LLM, it hides its overlays. On Android 14 and later it captures only the app's window instead. Marks for the LLM are drawn on a copy of the image.
```

with:

```markdown
On Android 14 and later, Kestrel's screenshot for `look_closer` (section 11.6) captures only the app's window, so none of Kestrel's overlays is in it. Android 13's screenshot holds the whole display, Kestrel's overlays included, so Kestrel first makes its overlays transparent, waits until Android has drawn that and at least 50 ms, captures, and shows them again at once; it then crops the image to the app's window and blacks out every part that another window covers and each overlay of Kestrel's that does not cover the whole screen, such as the feedback bar. Marks for the LLM are drawn on a copy of the image.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| B10 | Accessibility screenshots have a minimum interval of 333 ms, and capturing a secure window returns an error. | AOSP `AccessibilityService.java` and `AccessibilityInteractionController.java` | † |
```

with:

```markdown
| B10 | Accessibility screenshots have a minimum interval of 333 ms (error 3). Android 13's `takeScreenshot` holds the whole display, the service's own overlays included, and shows a secure window as black pixels without an error; from API 34, `takeScreenshotOfWindow` captures one window without the windows above it and fails with error 6 on a secure window, while the display capture still shows it black. | AOSP `AccessibilityService.java`, `AccessibilityInteractionController.java` and `AbstractAccessibilityServiceConnection.java` (android-13.0.0_r1 and android17-release) | † |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, add at the end of the file:

```markdown
| B65 | An accessibility screenshot comes as a `HardwareBuffer` (about 9.9 MiB for a 1080 × 2400 display) that the service must close; an `ARGB_8888` copy doubles the peak. The display capture's interval is kept per service, the window capture's per window. Android's window list holds no overlay of another app that takes no touches, so such an overlay cannot be blacked out in Android 13's display capture. | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/AccessibilityService.java and https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java | † |
```

- [ ] **Step 9: Run the module's checks**

Run: `scripts\gradle.ps1 :core:screen:check :app:assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: `:core:screen` S₀ + 2 tests, 0 failures; `core/screen/build/reports/lint-results-debug.txt` says "No issues found."

- [ ] **Step 10: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 11: Commit**

`:app`'s device tests: `dev.kestrel.app.screen` D_S + 3; the other groups unchanged.

```powershell
git add core/screen/src/main/kotlin/dev/kestrel/screen/capture/ScreenCapture.kt core/screen/src/main/kotlin/dev/kestrel/screen/overlay/HiddenOverlays.kt core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt core/screen/src/test/kotlin/dev/kestrel/screen/capture/ScreenCaptureRulesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(screen): capture the app's window, or the display with Kestrel's overlays hidden on Android 13" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 5: The `EyesPort`: OCR on text-less regions, the masks, the marks and the JPEG

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/agent/eyes/AppEyes.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (below `// sub-project 5C: eyes`), `app/src/main/AndroidManifest.xml` (below `<!-- sub-project 5C: queries -->`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt` (one test)

**Interfaces:**
- Consumes: plan 5A's `EyesPort`, `Capture` (`Captured`, `Secure`, `NotNow`, `Capture.Image`), `OcrLine`, `Badge` (`dev.kestrel.agent.port`), `SystemUiParts.DEFAULT` and `SYSTEM_UI` (`dev.kestrel.agent.observe`); `Part.Image` and `ImageType` (`:core:providers`); plan 5B's `AppGraph.agentEyes`; Task 1's `Engine.OCR` and `graph.models`; Task 2's `OcrEngine`, `OcrFiles`, `OcrImage`; Task 4's `ScreenSession.capture`, `CaptureResult` and `ScreenCapture.IN_THE_WAY`; `ScreenTree.frontWindow`; `graph.report`.
- Produces: `internal class AppEyes(session: () -> ScreenSession?, models: () -> ModelFiles, closedScreens: () -> Set<String>, report: (String, Throwable) -> Unit) : EyesPort` (package `dev.kestrel.app.agent.eyes`) with `AppEyes.NO_SERVICE`, `NO_APP`, `HOME`, `MAX_SIDE = 1280`, `QUALITY = 80`, `image(bitmap: Bitmap): OcrImage`, `cyrillic(snapshot, windowId): Boolean` and `closedScreens(context: Context): Set<String>`; `AppGraph.eyes` (internal) and `agentEyes` set to it, so that every task that `AgentRunner` starts sees Kestrel's eyes.

Design D20 (review B I12, I13, B2-M8) and plan 5A's note that the `EyesPort` refuses the home screen and the recent apps. `capture` takes a settled snapshot, refuses when the accessibility service is off, when no app window is in front, when the home screen or the recent apps are in front (Android's `config_recentsComponentName` and the home intent's activity, which needs a `<queries>` entry since Android 11), and while a heads-up notification or the shade shows (D9's notification rows of SystemUI); otherwise it captures the front window and notes whether its tree text holds Cyrillic letters, which `read` passes to the engine. `read` uses the OCR files only when every OCR model is on the phone and intact, blacks out the excluded bounds (password fields and data-sensitive elements) before OCR, reads each region and returns the lines in screen coordinates; it opens the engine for the call and closes it after, so that no model stays in memory between steps, and a failure goes to `graph.report` by its classes (D32 item 48) and counts as "OCR unavailable", which makes plan 5A's question add its warning. `encode` draws on a copy: the masks black, then each number on a small label at its element's top-left, sized to stay readable after the image shrinks to at most 1,280 pixels on its longer side, then JPEG at quality 80. Every mask, mark and region comes in screen coordinates and goes through the capture's origin (review B I13); the device test checks a mask in ViewsActivity's dialog, whose window does not start at (0, 0).

- [ ] **Step 1: Write the failing device test**

In `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt`, replace:

```kotlin
import android.graphics.Bitmap
```

with:

```kotlin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
```

In `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt`, replace:

```kotlin
import androidx.test.ext.junit.runners.AndroidJUnit4
```

with:

```kotlin
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.port.Capture
import dev.kestrel.app.agent.eyes.AppEyes
import dev.kestrel.app.voice.VoiceTests
```

In `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt`, replace:

```kotlin
    // The pixels in the numbers' blue
```

with:

```kotlin
    @Test
    fun theEyesMaskAnElementWhereItIsInADialogThatDoesNotStartAtTheCorner() = runBlocking {
        val window = dialog()
        val keep = screen.awaitElement { it.label.equals("Keep", ignoreCase = true) && it.windowId == window.id }
        val title = screen.awaitElement { it.label == "Delete draft?" && it.windowId == window.id }
        val eyes = AppEyes({ session }, { VoiceTests.graph.models }, { emptySet() }) { _, _ -> }
        val capture = eyes.capture() as Capture.Captured
        capture.image.use {
            assertTrue(capture.originX > 0 || capture.originY > 0)
            val jpeg = eyes.encode(capture, listOf(keep.bounds), emptyList())
            val image = BitmapFactory.decodeByteArray(jpeg.bytes, 0, jpeg.bytes.size)
            try {
                val scale = image.width.toDouble() / capture.image.width
                fun luminance(x: Int, y: Int): Int {
                    val pixel = image.getPixel(((x - capture.originX) * scale).toInt(), ((y - capture.originY) * scale).toInt())
                    return (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                }
                // Inside the masked button everything is black, JPEG's few levels aside; the dialog's light background
                // beside it is not.
                val b = keep.bounds
                for (x in listOf(b.left + b.width / 4, b.centerX, b.right - b.width / 4)) assertTrue(luminance(x, b.centerY) < 40)
                // The window's frame has a transparent 16 dp border around the card (dialog_background_material), so the
                // light background is read on the title's row, inside the card.
                assertTrue((title.bounds.left until title.bounds.right step 4).any { luminance(it, title.bounds.centerY) > 150 })
            } finally {
                image.recycle()
            }
        }
    }

    // The pixels in the numbers' blue
```

- [ ] **Step 2: Run the build of the device tests to verify that it fails**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: FAIL: "Unresolved reference 'AppEyes'".

- [ ] **Step 3: Write the eyes**

Create `app/src/main/kotlin/dev/kestrel/app/agent/eyes/AppEyes.kt`:

```kotlin
package dev.kestrel.app.agent.eyes

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import dev.kestrel.agent.observe.SystemUiParts
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.Capture
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.perception.OcrEngine
import dev.kestrel.perception.OcrFiles
import dev.kestrel.perception.OcrImage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.capture.CaptureResult
import dev.kestrel.screen.capture.ScreenCapture
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The agent's eyes for `look_closer` (design D20 of sub-project 5): a screenshot of the app in front through the screen
 * session's [ScreenCapture], OCR with `:core:perception` on the regions that the agent names, and the JPEG for the model
 * with its masks and numbers. Screenshots exist only in memory: the agent closes each capture after its step, which
 * recycles the bitmap, and nothing here writes or logs one. An OCR failure goes to [report] by its classes only (D32 item
 * 48), and OCR then counts as unavailable for that call.
 */
internal class AppEyes(
    private val session: () -> ScreenSession?,
    private val models: () -> ModelFiles,
    private val closedScreens: () -> Set<String>,
    private val report: (String, Throwable) -> Unit,
) : EyesPort {
    /** A capture's pixels; [cyrillic] says that the window's tree text holds Cyrillic letters (D20's choice of recogniser). */
    private class Pixels(val bitmap: Bitmap, val cyrillic: Boolean) : Capture.Image {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        override fun close() = bitmap.recycle()

        override fun toString(): String = "Pixels(${width}x$height)"
    }

    override suspend fun capture(): Capture {
        val screen = session() ?: return Capture.NotNow(NO_SERVICE)
        val snapshot = screen.snapshots.current().snapshot
        val front = ScreenTree.frontWindow(snapshot) ?: return Capture.NotNow(NO_APP)
        if (front.packageName in closedScreens()) return Capture.NotNow(HOME)
        // A heads-up notification or the shade over the app (D9): its text must reach neither OCR nor a provider.
        if (snapshot.elements.any { it.packageName == SystemUiParts.SYSTEM_UI && SystemUiParts.DEFAULT.isNotification(it) }) {
            return Capture.NotNow(ScreenCapture.IN_THE_WAY)
        }
        return when (val result = screen.capture.capture(front.id)) {
            is CaptureResult.Image -> Capture.Captured(
                Pixels(result.bitmap, cyrillic(snapshot, front.id)), result.originX, result.originY, result.appPixels, result.blocked,
            )
            CaptureResult.Secure -> Capture.Secure
            is CaptureResult.NotNow -> Capture.NotNow(result.reason)
        }
    }

    override suspend fun read(capture: Capture.Captured, regions: List<IntRect>, exclude: List<IntRect>): List<OcrLine>? {
        val pixels = capture.image as? Pixels ?: return null
        return try {
            val files = files() ?: return null
            withContext(Dispatchers.Default) {
                // Password fields and data-sensitive elements are black before OCR sees the image (review B I12).
                val image = image(pixels.bitmap).blackedOut(exclude.map { it.inside(capture) })
                OcrEngine.open(files).use { engine ->
                    engine.read(image, regions.map { it.inside(capture) }, pixels.cyrillic).map { line ->
                        val b = line.bounds
                        OcrLine(line.text, IntRect(b.left + capture.originX, b.top + capture.originY, b.right + capture.originX, b.bottom + capture.originY), line.score)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("OCR", e)
            null
        }
    }

    override suspend fun encode(capture: Capture.Captured, masks: List<IntRect>, marks: List<Badge>): Part.Image =
        withContext(Dispatchers.Default) {
            val copy = (capture.image as Pixels).bitmap.copy(Bitmap.Config.ARGB_8888, true)
            try {
                val canvas = Canvas(copy)
                // Masks and marks come in screen coordinates; the image starts at the capture's origin.
                canvas.translate(-capture.originX.toFloat(), -capture.originY.toFloat())
                val black = Paint().apply { color = Color.BLACK }
                for (mask in masks) canvas.drawRect(mask.left.toFloat(), mask.top.toFloat(), mask.right.toFloat(), mask.bottom.toFloat(), black)
                drawMarks(canvas, marks, max(copy.width, copy.height))
                val scale = minOf(1f, MAX_SIDE.toFloat() / max(copy.width, copy.height))
                val out = if (scale < 1f) {
                    Bitmap.createScaledBitmap(copy, max(1, (copy.width * scale).roundToInt()), max(1, (copy.height * scale).roundToInt()), true)
                } else {
                    copy
                }
                try {
                    val bytes = ByteArrayOutputStream()
                    out.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)
                    Part.Image(ImageType.JPEG, bytes.toByteArray())
                } finally {
                    if (out !== copy) out.recycle()
                }
            } finally {
                copy.recycle()
            }
        }

    // Each number on a small label at its element's top-left, sized to stay readable after the image shrinks to 1,280.
    private fun drawMarks(canvas: Canvas, marks: List<Badge>, longer: Int) {
        val size = max(MIN_TEXT, TEXT * longer / MAX_SIDE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val back = Paint().apply { color = MARK_COLOR }
        for (mark in marks) {
            val label = mark.number.toString()
            val left = mark.bounds.left.toFloat()
            val top = mark.bounds.top.toFloat()
            canvas.drawRect(left, top, left + text.measureText(label) + size / 2, top + size * 1.25f, back)
            canvas.drawText(label, left + size / 4, top + size, text)
        }
    }

    // The OCR files only when every OCR model is on the phone and intact; ModelFiles caches the hashes.
    private suspend fun files(): OcrFiles? {
        val files = models()
        val ocr = files.check().filter { it.model.engine == Engine.OCR }
        if (ocr.isEmpty() || !ocr.all { it.ready }) return null
        return withContext(Dispatchers.IO) { OcrFiles.under(files.root) }
    }

    private fun IntRect.inside(capture: Capture.Captured) =
        IntRect(left - capture.originX, top - capture.originY, right - capture.originX, bottom - capture.originY)

    override fun toString(): String = "AppEyes"

    companion object {
        const val NO_SERVICE = "Kestrel's accessibility service is not running."
        const val NO_APP = "No app is in front."
        const val HOME = "Kestrel takes no screenshot of the home screen or the recent apps."
        const val MAX_SIDE = 1280
        const val QUALITY = 80
        private const val TEXT = 32f
        private const val MIN_TEXT = 24f
        private const val MARK_COLOR = 0xE61565C0.toInt()

        /** [bitmap]'s pixels for OCR. */
        fun image(bitmap: Bitmap): OcrImage {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return OcrImage(bitmap.width, bitmap.height, pixels)
        }

        /** Whether the tree text of the window [windowId] holds a Cyrillic letter. */
        fun cyrillic(snapshot: ScreenSnapshot, windowId: Int): Boolean = snapshot.elements.any { element ->
            element.windowId == windowId && element.label?.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC } == true
        }

        /**
         * The packages of the home screen and of the recent apps (Android's `config_recentsComponentName`), whose screens
         * Kestrel never captures (design D20 of sub-project 5). Android publishes the recent apps' component only as this
         * internal resource, which is why the lookup by name is used.
         */
        @SuppressLint("DiscouragedApi")
        fun closedScreens(context: Context): Set<String> {
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )?.activityInfo?.packageName
            val id = Resources.getSystem().getIdentifier("config_recentsComponentName", "string", "android")
            val recents = if (id == 0) null else Resources.getSystem().getString(id).substringBefore('/').takeIf { it.isNotEmpty() }
            return setOfNotNull(home, recents)
        }
    }
}
```

- [ ] **Step 4: Give the agent its eyes**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    // sub-project 5C: eyes
```

with:

```kotlin
    // sub-project 5C: eyes

    /**
     * The agent's screenshots and OCR for look_closer (design D20 of sub-project 5); the OCR models load only while it
     * reads. Its parts are found at each use, because the screen session and the model folder change while Kestrel runs.
     */
    internal val eyes: dev.kestrel.app.agent.eyes.AppEyes by lazy {
        dev.kestrel.app.agent.eyes.AppEyes({ screen.value }, { models }, { dev.kestrel.app.agent.eyes.AppEyes.closedScreens(appContext) }, ::report)
    }

    init {
        agentEyes = eyes
    }
```

In `app/src/main/AndroidManifest.xml`, replace:

```xml
    <!-- sub-project 5C: queries -->
```

with:

```xml
    <!-- sub-project 5C: queries -->
    <!-- The home screen, of which look_closer takes no screenshot (design D20 of sub-project 5). -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.HOME" />
        </intent>
    </queries>
```

- [ ] **Step 5: Build, then run the device test on kestrel-api37**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/reports/lint-results-debug.txt` lists no issue in `AppEyes.kt`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.ScreenCaptureTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`; 4 tests, 0 failures, 0 skipped.

- [ ] **Step 6: Run the JVM tests of `:app`**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; A₀ tests, 0 failures (this task adds no JVM test).

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

`:app`'s device tests: `dev.kestrel.app.screen` D_S + 3 + 1; the other groups unchanged.

```powershell
git add app/src/main/kotlin/dev/kestrel/app/agent/eyes/AppEyes.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenCaptureTest.kt
git commit -m "feat(agent): give the agent eyes: screenshots of the app in front, OCR where the tree has no text, masks and numbers" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 6: The screenshot approvals, the numbers, and `look_closer` on the device

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/agent/eyes/ScreenshotApprovals.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (below `// sub-project 5C: keys`, and Task 5's block below `// sub-project 5C: eyes`), `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt` (below `// sub-project 5C: screenshot approvals` and `// sub-project 5C: stored screenshot approvals`), `app/src/main/kotlin/dev/kestrel/app/settings/SafetyPage.kt` (below `// sub-project 5C: screenshot approvals`), `app/src/main/res/values/strings.xml` (below `<!-- sub-project 5C -->`), `app/build.gradle.kts` (Task 1's line below `// sub-project 5C: dependencies`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` (below `// sub-project 5C: fake screenshot approvals`, and one test), `app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/LookCloserTest.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (11.6)

**Interfaces:**
- Consumes: plan 5B's `AppGraph.showAgentBadges`, `hideAgentBadges`, `screenshotApproved` and `approveScreenshots` (see "Before you start"); plan 5A's `MemoryStore.screenshotApproved` and `approveScreenshots` (which plan 5B's `SettingsMemoryStore` answers through the two seams), `Agent`, `AgentPorts`, `AgentSettings`, `TaskRequest`, `TaskReport`, `EndReason`, `ScreenPort`, `ScreenAction.TapAt`, `ActionOutcome`, `Answer`, `AnswerKind.SCREENSHOT`, `IntentSpec`, `port.Badge`, and the test fixtures `ScriptedModel` (with `call` and `INFO`), `FakeOwner`, `FakeData`, `FakeApps`, `FakeMemory`, `FakeLog`, `FakeClock`; Task 3's `OcrCanvasActivity`; Task 4's `ScreenSession.showBadges` and `hideBadges`; Task 5's `AppEyes`; `SettingsStore`, `SettingKey`, `graph.catalog.provider(id)?.label`; `SettingsData`'s `write` helper of `StoredSettingsData`; the Compose test's `show`, `text`, `compose` and `data` of `SettingsAppTest`.
- Produces: `@Serializable data class ScreenshotApproval(val packageName: String, val providerId: String)`, `@Serializable data class ScreenshotApprovalList(val approvals: List<ScreenshotApproval> = emptyList())`, `data class ScreenshotApprovalRow(val approval: ScreenshotApproval, val app: String, val provider: String)` and `internal class ScreenshotApprovals(settings: SettingsStore)` with `all: Flow<List<ScreenshotApproval>>`, `approved(packageName, providerId): Boolean`, `approve(packageName, providerId)` and `revoke(approval)` (package `dev.kestrel.app.agent.eyes`); `SettingKeys.SCREENSHOT_APPROVALS` (`"screenshot-approvals"`); `AppGraph.screenshotApprovals`; `SettingsData.screenshotApprovals: Flow<List<ScreenshotApprovalRow>>` and `SettingsData.revokeScreenshots(row)`; the Safety page's section "Screenshot approvals" with one row "<app> to <provider>" and one button "Revoke" per approval, or "No app may send screenshots without asking." (strings `safety_screenshots_title`, `safety_screenshots_none`, `safety_screenshots_row`, `safety_screenshots_revoke`; sitting 2 revokes its approval by tapping "Revoke"); `LookCloserTest.readsTheCanvasThenSendsAScreenshotThenShowsTheNumbers`.

Design D20's screenshot question and numbers, D26 (the Safety page's approvals with "Revoke") and D27 (a device test of `look_closer`'s three steps with the scripted model). Plan 5A's `LookCloser` asks the question, reads the answer and calls the memory; this task stores the approvals, encrypted, in `SCREENSHOT_APPROVALS` as pairs of package and provider ID, because a later profile may use another provider, and connects them and the numbers to the agent through plan 5B's seams in `AppGraph`: `showAgentBadges` maps the agent's numbers onto `ScreenSession.showBadges`. The Safety page shows each approval with the app's launcher label and the provider's name from the catalog, each falling back to its ID. The device test runs the agent with the scripted model on `:testapp`'s canvas: OCR reads the drawn "Show details" into the observation, the owner's "yes" sends a JPEG of at most 1,280 pixels, the numbers show while Kestrel asks which one, and a tap on the OCR line taps the drawn button. It builds its own small `ScreenPort` over the screen session (taps at a point and the numbers), so that it depends on no class of plan 5B's beyond the test fixtures.

- [ ] **Step 1: Write the failing tests**

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
        // sub-project 5C: fake screenshot approvals
```

with:

```kotlin
        // sub-project 5C: fake screenshot approvals
        val approvals = MutableStateFlow<List<dev.kestrel.app.agent.eyes.ScreenshotApprovalRow>>(emptyList())

        override val screenshotApprovals: kotlinx.coroutines.flow.Flow<List<dev.kestrel.app.agent.eyes.ScreenshotApprovalRow>>
            get() = approvals

        override suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) {
            approvals.value = approvals.value - row
        }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
    private class FakeSettingsData : SettingsData {
```

with:

```kotlin
    @Test
    fun theSafetyPageListsTheScreenshotApprovalsAndRevokesOne() {
        val approval = dev.kestrel.app.agent.eyes.ScreenshotApproval("com.example.maps", "github-copilot")
        data.approvals.value = listOf(dev.kestrel.app.agent.eyes.ScreenshotApprovalRow(approval, "Maps", "GitHub Copilot"))
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()
        compose.onNodeWithText(text(R.string.safety_screenshots_row, "Maps", "GitHub Copilot")).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(text(R.string.safety_screenshots_revoke)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.safety_screenshots_none)).performScrollTo().assertIsDisplayed()
        assertEquals(0, data.approvals.value.size)
    }

    private class FakeSettingsData : SettingsData {
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/LookCloserTest.kt`:

```kotlin
package dev.kestrel.app.agent.eyes

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.Agent
import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * `look_closer`'s three steps on the device with the scripted model (design D20 of sub-project 5): OCR reads
 * `:testapp`'s canvas, the screenshot goes to the model after the owner's yes, the owner picks a number from the
 * numbers on the screen, and a tap on an OCR line taps its centre. The model and the owner are the test fixtures of
 * `:core:agent`; the eyes are Kestrel's own, with the OCR models that `scripts\push-models.ps1` copied.
 */
@RunWith(AndroidJUnit4::class)
class LookCloserTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.OCR)

    /** The screen as the agent sees it, through the session: snapshots, taps at a point and the numbers. */
    private class SessionScreen(private val session: ScreenSession) : ScreenPort {
        override suspend fun snapshot(): ScreenSnapshot = session.snapshots.current().snapshot

        override suspend fun act(action: ScreenAction): ActionOutcome = when (action) {
            is ScreenAction.TapAt -> when (val result = session.actions.tapAt(action.x, action.y)) {
                is ActionResult.Done -> ActionOutcome(done = true, method = result.method.name)
                is ActionResult.Failed -> ActionOutcome(done = false, reason = result.reason)
            }
            else -> ActionOutcome(done = false, reason = "This test taps only at points.")
        }

        override suspend fun resolve(intent: IntentSpec): String? = null

        override suspend fun showBadges(badges: List<Badge>) =
            session.showBadges(badges.map { dev.kestrel.screen.overlay.Badge(it.number, "", it.bounds) })

        override suspend fun hideBadges() = session.hideBadges()

        override suspend fun waitForFront(packageName: String?, millis: Long, before: String?): Boolean = true
    }

    @Test
    fun readsTheCanvasThenSendsAScreenshotThenShowsTheNumbers() = runBlocking {
        screen.open("OcrCanvasActivity", "OCR canvas")
        val session = screen.session
        val look = ScriptedModel.call("look_closer", null, null, null, "reason" to "the canvas has no text")
        val model = ScriptedModel(
            listOf(
                look, look, look,
                ScriptedModel.call("tap", "Show details", null, null, "reason" to "open the details", "category" to "reading"),
                ScriptedModel.call("fail", null, null, null, "reason" to "The test ends here."),
            ),
            ScriptedModel.INFO.copy(lightweight = ScriptedModel.INFO.lightweight.copy(takesImages = true)),
        )
        val owner = FakeOwner()
        owner.answers += listOf(Answer.Text("yes"), Answer.Number(1))
        var numbersShown = 0
        owner.onAsk = { question -> if (question == "Which number should I use?") numbersShown = session.numberBadges.size }
        val eyes = AppEyes({ session }, { VoiceTests.graph.models }, { emptySet() }) { _, _ -> }
        val ports = AgentPorts(SessionScreen(session), eyes, FakeData(), owner, model, FakeApps(), FakeMemory(), FakeLog(), FakeClock())
        var report: TaskReport? = null

        Agent(ports, AgentSettings()).run(TaskRequest("task-1-0c0ffee0", "Show the details on the test canvas")) { report = it }

        assertEquals(EndReason.FAILED_BY_MODEL, report?.end)
        // Step 1: OCR's lines are in the observation.
        val observation = model.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>().last().text
        assertTrue(observation.contains("OCR \"Show details\""))
        // Step 2: after the owner's yes, a JPEG of at most 1,280 pixels follows the tool result.
        assertEquals(AnswerKind.SCREENSHOT, owner.asked[0].second)
        assertTrue(owner.asked[0].first.startsWith("May I send a screenshot of"))
        val picture = (model.requests[2].messages.last() as ChatMessage.User).parts.single() as Part.Image
        assertEquals(ImageType.JPEG, picture.type)
        val decoded = BitmapFactory.decodeByteArray(picture.bytes, 0, picture.bytes.size)
        assertTrue(max(decoded.width, decoded.height) <= AppEyes.MAX_SIDE)
        decoded.recycle()
        // Step 3: the numbers showed while Kestrel asked, and went afterwards.
        assertTrue(numbersShown > 0)
        assertTrue(session.numberBadges.isEmpty())
        assertTrue(model.requests[3].messages.filterIsInstance<ChatMessage.User>().any { message ->
            message.parts.filterIsInstance<Part.Text>().any { it.text.contains("The owner picked [1].") }
        } || model.requests[3].messages.filterIsInstance<ChatMessage.ToolResult>().any { it.text.contains("The owner picked [1].") })
        // The tap on the OCR line hit the drawn button.
        screen.awaitElement { it.label == "Details shown" }
        Unit
    }
}
```

In `app/build.gradle.kts`, replace:

```kotlin
    // sub-project 5C: dependencies
    implementation(project(":core:perception"))
```

with:

```kotlin
    // sub-project 5C: dependencies
    implementation(project(":core:perception"))
    // The scripted model and the fakes of :core:agent for LookCloserTest; device tests do not ship.
    androidTestImplementation(testFixtures(project(":core:agent")))
```

- [ ] **Step 2: Run the build of the device tests to verify that it fails**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: FAIL: "Unresolved reference 'ScreenshotApprovalRow'", "'screenshotApprovals' overrides nothing" and "Unresolved reference 'safety_screenshots_row'".

- [ ] **Step 3: Store the approvals**

Create `app/src/main/kotlin/dev/kestrel/app/agent/eyes/ScreenshotApprovals.kt`:

```kotlin
package dev.kestrel.app.agent.eyes

import dev.kestrel.app.SettingKeys
import dev.kestrel.privacy.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * One "always for this app" answer to the screenshot question: this app's screenshots may go to this provider (design D20
 * of sub-project 5).
 */
@Serializable
data class ScreenshotApproval(val packageName: String, val providerId: String) {
    override fun toString(): String = "ScreenshotApproval(package=${packageName.length} chars, provider=${providerId.length} chars)"
}

/** The stored approvals, in the encrypted setting `SCREENSHOT_APPROVALS`. */
@Serializable
data class ScreenshotApprovalList(val approvals: List<ScreenshotApproval> = emptyList())

/** An approval as the Safety page shows it, with the app's and the provider's names. */
data class ScreenshotApprovalRow(val approval: ScreenshotApproval, val app: String, val provider: String) {
    override fun toString(): String = "ScreenshotApprovalRow(app=${app.length} chars, provider=${provider.length} chars)"
}

/**
 * The screenshot approvals per app and provider (design D20 and D26 of sub-project 5): the agent's memory reads and adds
 * them through `AppGraph`, and the Safety page lists and revokes them. A later profile may use another provider, so an
 * approval names both.
 */
internal class ScreenshotApprovals(private val settings: SettingsStore) {
    private val writes = Mutex()

    val all: Flow<List<ScreenshotApproval>> = settings.observe(SettingKeys.SCREENSHOT_APPROVALS).map { it.approvals }

    suspend fun approved(packageName: String, providerId: String): Boolean =
        ScreenshotApproval(packageName, providerId) in settings.get(SettingKeys.SCREENSHOT_APPROVALS).approvals

    suspend fun approve(packageName: String, providerId: String) = writes.withLock {
        val stored = settings.get(SettingKeys.SCREENSHOT_APPROVALS)
        val approval = ScreenshotApproval(packageName, providerId)
        if (approval !in stored.approvals) settings.set(SettingKeys.SCREENSHOT_APPROVALS, stored.copy(approvals = stored.approvals + approval))
    }

    suspend fun revoke(approval: ScreenshotApproval) = writes.withLock {
        val stored = settings.get(SettingKeys.SCREENSHOT_APPROVALS)
        settings.set(SettingKeys.SCREENSHOT_APPROVALS, stored.copy(approvals = stored.approvals - approval))
    }

    override fun toString(): String = "ScreenshotApprovals"
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    // sub-project 5C: keys
```

with:

```kotlin
    // sub-project 5C: keys

    /** The apps whose screenshots may go to a provider without the question, per app and provider (design D20 of sub-project 5). */
    val SCREENSHOT_APPROVALS = SettingKey(
        "screenshot-approvals",
        dev.kestrel.app.agent.eyes.ScreenshotApprovalList.serializer(),
        dev.kestrel.app.agent.eyes.ScreenshotApprovalList(),
    )
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    init {
        agentEyes = eyes
    }
```

with:

```kotlin
    /** The approvals of the screenshot question, which the agent's memory and the Safety page share (design D20 and D26). */
    internal val screenshotApprovals: dev.kestrel.app.agent.eyes.ScreenshotApprovals by lazy {
        dev.kestrel.app.agent.eyes.ScreenshotApprovals(settings)
    }

    init {
        agentEyes = eyes
        // look_closer's numbers (D20's third step) instead of Numbering's own; an agent's badge names no element's key.
        showAgentBadges = { session, badges -> session.showBadges(badges.map { dev.kestrel.screen.overlay.Badge(it.number, "", it.bounds) }) }
        hideAgentBadges = { session -> session.hideBadges() }
        screenshotApproved = { packageName, providerId -> screenshotApprovals.approved(packageName, providerId) }
        approveScreenshots = { packageName, providerId -> screenshotApprovals.approve(packageName, providerId) }
    }
```

- [ ] **Step 4: Show and revoke the approvals on the Safety page**

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    // sub-project 5C: screenshot approvals
}
```

with:

```kotlin
    // sub-project 5C: screenshot approvals

    /** The apps that may send screenshots to a provider without asking (design D20 and D26 of sub-project 5), with names. */
    val screenshotApprovals: Flow<List<dev.kestrel.app.agent.eyes.ScreenshotApprovalRow>> get() = flowOf(emptyList())

    /** Takes back one approval: the screenshot question comes again for that app and provider. */
    suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) {}
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    // sub-project 5C: stored screenshot approvals
```

with:

```kotlin
    // sub-project 5C: stored screenshot approvals

    override val screenshotApprovals: Flow<List<dev.kestrel.app.agent.eyes.ScreenshotApprovalRow>> =
        graph.screenshotApprovals.all.map { approvals -> approvals.map(::approvalRow) }.flowOn(Dispatchers.IO)

    override suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) =
        write { graph.screenshotApprovals.revoke(row.approval) }

    // The app's label and the provider's name from the catalog, each falling back to its ID.
    private fun approvalRow(approval: dev.kestrel.app.agent.eyes.ScreenshotApproval): dev.kestrel.app.agent.eyes.ScreenshotApprovalRow {
        val packages = context.packageManager
        val app = try {
            packages.getApplicationLabel(
                packages.getApplicationInfo(approval.packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0)),
            ).toString()
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            approval.packageName
        }
        val provider = graph.catalog.provider(approval.providerId)?.label ?: approval.providerId
        return dev.kestrel.app.agent.eyes.ScreenshotApprovalRow(approval, app, provider)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SafetyPage.kt`, replace:

```kotlin
    // sub-project 5C: screenshot approvals
}
```

with:

```kotlin
    // sub-project 5C: screenshot approvals
    ScreenshotApprovalsSection(data)
}

/** The approvals of the screenshot question, each with its "Revoke" button (design D20 and D26 of sub-project 5). */
@Composable
private fun ScreenshotApprovalsSection(data: SettingsData) {
    val approvals by data.screenshotApprovals.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.safety_screenshots_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    if (approvals.isEmpty()) {
        Text(stringResource(R.string.safety_screenshots_none), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    for (row in approvals) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.safety_screenshots_row, row.app, row.provider), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.TextButton(onClick = {
                // At once, so that the write is queued also when the owner leaves the page right after the tap.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { data.revokeScreenshots(row) }
            }) {
                Text(stringResource(R.string.safety_screenshots_revoke))
            }
        }
    }
}
```

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <!-- sub-project 5C -->
```

with:

```xml
    <!-- sub-project 5C -->
    <string name="safety_screenshots_title">Screenshot approvals</string>
    <string name="safety_screenshots_none">No app may send screenshots without asking.</string>
    <string name="safety_screenshots_row">%1$s to %2$s</string>
    <string name="safety_screenshots_revoke">Revoke</string>
```

- [ ] **Step 5: Describe looking closer in the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
The `look_closer` tool handles screens where the tree is not enough, such as canvas views, maps and unlabeled icons. It tries three steps in order:

1. **On-device OCR.** Kestrel runs PP-OCRv5 mobile (text detection plus Latin and Cyrillic recognition, about 21 MB, Apache-2.0) through ONNX Runtime on a screenshot or region that stays in memory (B19). Each text box becomes an extra element, for example `[o3] "Pay now"`. The estimated time is 2–5 s for a full screen and 1–2 s for a region.
2. **A screenshot for the LLM, after asking.** Kestrel asks "May I send a screenshot of <app> to <provider>?". The owner can answer yes, no or "always for this app"; the last answer can be revoked in settings. Before sending, Kestrel blacks out password and data-sensitive elements and draws numbered marks on the image.
3. **Numbers.** Kestrel shows numbers and asks the owner to pick one.

Secure windows (`FLAG_SECURE`) cannot be captured, so only the tree and the numbers work there (B10). Screenshots exist only in memory and are never stored. On Android 13, a screenshot captures the whole screen (section 9.4), so OCR keeps only the text boxes that lie in the app's window where no other window covers it, and Kestrel runs no OCR and sends no screenshot to the LLM while a heads-up notification or the notification shade shows (section 11.3).
```

with:

```markdown
The `look_closer` tool handles screens where the tree is not enough, such as canvas views, maps and unlabeled icons. Each call on the same screen runs the next of three steps, and a step that cannot run here (no OCR files, a model that takes no images, the owner's no) passes to the next one in the same call. A call with a target limits the first two steps to that element.

1. **On-device OCR.** Kestrel runs PP-OCRv5 mobile (a text detector and a Latin and a Cyrillic recogniser, about 21 MB, Apache-2.0; B19 and B64) through ONNX Runtime 1.28.0 on a screenshot that stays in memory, only where the tree has no text: the actionable elements without a label and the large elements (at least 1/16 of the window) whose subtree has no text, or the target. Password fields and data-sensitive elements are black before OCR sees the image. A line whose box lies on an element with text is dropped, and only lines with a mean character score of at least 0.8 are kept. Each kept line becomes an extra line of the observation, numbered after the tree's elements, for example `[42] OCR "Pay now" @middle`; `tap` on it taps its centre, and its text is redacted like screen text. The detector scales a window so that its longer side is at most 960 pixels and enlarges a smaller region at most twice. The Latin recogniser reads each box; when its score is below 0.8, or when the window's tree text holds Cyrillic letters, the Cyrillic recogniser reads the box too, and the higher score wins. The measured times are in section 19.1.
2. **A screenshot for the LLM, after asking.** Only for a model that takes images, and never for an agent-free app, Play services, the home screen, the recent apps or a secure window. Kestrel asks "May I send a screenshot of <app> to <provider>?". "Yes" sends this one, "always for this app" stores the approval for this app and this provider, and any other answer, silence included, is no; while another app's audio plays, only the card's button answers (section 12.1). The Safety page lists the approvals and revokes each one. Before sending, Kestrel runs step 1's OCR if it has not run on this screen, and blacks out password fields, data-sensitive elements, every element and every OCR box whose text the redactor changes, and on Android 13 every part outside the app's own pixels; it draws each element's number at its top-left, scales the image to at most 1,280 pixels on its longer side and sends it as a JPEG of quality 80 after the tool result. Without the OCR files the question adds "I can't hide numbers that only the picture shows."
3. **Numbers.** Kestrel shows the newest observation's numbers on the screen and asks "Which number should I use?"; the owner's number goes to the model, and the numbers go when the step ends.

From Android 14 Kestrel captures only the app's window, and a secure window gives an error. Android 13 captures the whole display, so Kestrel hides its overlays first (section 9.4), crops the image to the app's window and blacks out every part that another window covers. Kestrel does not capture while a heads-up notification or the notification shade shows (section 11.3), and it reads Android's window list again after each capture and drops a capture during which a window came, went or moved. On every version, a window whose own pixels are at least 99 % pure black counts as secure, and then only the tree and the numbers work (B10). At most one capture runs every 400 ms. Screenshots exist only in memory and are never stored or logged. Android's window list does not hold another app's overlay that takes no touches, so such an overlay cannot be blacked out in Android 13's capture (B65).
```

- [ ] **Step 6: Build, then run the device tests on kestrel-api37**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `:app` JVM tests A₀, 0 failures; `app/build/reports/lint-results-debug.txt` lists no issue in the files of this task.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.agent.eyes.LookCloserTest,dev.kestrel.app.agent.eyes.OcrDeviceTest,dev.kestrel.app.screen.ScreenCaptureTest,dev.kestrel.app.settings.SettingsAppTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`; `LookCloserTest` 1, `OcrDeviceTest` 3, `ScreenCaptureTest` 4 and `SettingsAppTest` N + 1 tests (N: its count before this task; the package `dev.kestrel.app.settings` as a whole is D_T + 1), 0 failures, 0 skipped.

- [ ] **Step 7: Run every check of the build and the scripts' tests**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: `:app` A₀, `:core:voice` V₀ + 1, `:core:screen` S₀ + 2 and `:core:perception` 12 JVM tests, 0 failures; lint "No issues found." in every Android module whose report said so before this plan; the audit lists no finding.

Run: `scripts\test-scripts.ps1`
Expected: every Pester test passes in both shells (T₀ each), and PSScriptAnalyzer reports nothing.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

`:app`'s device tests: `dev.kestrel.app.agent` D_A + 3 + 1, `dev.kestrel.app.settings` D_T + 1, `dev.kestrel.app.screen` D_S + 4.

```powershell
git add app/src/main/kotlin/dev/kestrel/app/agent/eyes/ScreenshotApprovals.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt app/src/main/kotlin/dev/kestrel/app/settings/SafetyPage.kt app/src/main/res/values/strings.xml app/build.gradle.kts app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/androidTest/kotlin/dev/kestrel/app/agent/eyes/LookCloserTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(agent): store screenshot approvals per app and provider, show the agent's numbers, and test look_closer on the device" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 7: The speech checks of the agent's phrases

**Files:**
- Modify: `corpus/checks.txt` (the agent's lines k024 to k033), `scripts/tests/Corpus.Tests.ps1` (the corpus's count)
- Create: `scripts/lib/AgentChecks.ps1`, `scripts/tests/AgentChecks.Tests.ps1`
- Device step (no file in git): the clips of `corpus/checks.txt` in `corpus/generated/piper` and `corpus/generated/kokoro`

**Interfaces:**
- Consumes: plan 4D's `scripts\lib\AutoCheck.ps1` (`Invoke-AutoCheck` with `-Clip`, `-Play`, `-Before`, `-Wait`, `-WaitSeconds`, `-Verify`; `New-CheckTag`), `scripts\lib\Session.ps1` (`ConvertFrom-HookPairs`), `scripts\lib\Kestrel.ps1` (`Invoke-SettingsCommand`), `scripts\lib\Corpus.ps1` (`Read-Corpus`), `scripts\make-corpus.ps1 -List checks`; plan 5B's hooks (`plan-5b-interfaces.md`, section 4): `agent-script -File <name>` (a JSON array of plan 5A's `ScriptedModel` replies in Kestrel's `files/agent-scripts/<name>`) and `-Clear`, `agent-task -Result -Tag <k>`, `agent-state -Tag <k>`, `agent-answer -Tag <k> -Answer <a>`, `agent-memory -Remove -Key <k>`; plan 4D's H1 (`voice -Send heard -Text -Named -Tag`), H2 (`voice-expect`, through the runner) and `voice-state -Tag`'s `read` (the lower-case class name of the `Request` that `Requests.read` gives: `continue`, `deletehistory`, `remember`, `saveroutine` for plan 5B's `Request.Continue`, `Request.DeleteHistory`, `Request.Remember`, `Request.SaveRoutine`); `:testapp`'s `ViewsActivity` (button "Rebuild").
- Produces: `corpus\checks.txt` k024 to k033; `scripts\lib\AgentChecks.ps1`: `Get-SpeechFixture`, `Get-SpeechScript -Kind end|confirm|wait`, `Install-AgentScript -Name -Replies`, `ConvertFrom-AgentTaskResult`, `Wait-AgentTask -Tag -Seconds`, `Wait-AgentState -Tag -Until -Seconds`, `Get-AgentSpeechChecks` (15 checks with `Name`, `Clip`, `Kind` task|answer|phrase, `Script`, `Asking`, `End`, `Confirmed`, `Read`, `Then`), `Get-AgentSpeechVerdict -Check -State -Result`, `Stop-SpeechTask -Tag`, `Invoke-AgentSpeechCheck -Check -Clip -Play` (returns `Invoke-AutoCheck`'s result). Task 9's `a71-agent.ps1` dot-sources the library and runs the checks in sitting 1's step `speech`.

Design D28's speech checks: the agent's phrases (each D3 task with "Kestrel," before it, "confirm", "cancel", "yes", "no", "Kestrel, continue", "stop", "Kestrel, delete history", "Kestrel, remember that the gate code is blue" and "Kestrel, save that as test routine") join the automation plan's check corpus, `make-corpus.ps1` makes their clips in both voices, and the agent's check definitions run on plan 4D's `Invoke-AutoCheck`. `corpus\commands.txt` already has "Stop." (c045), "Cancel." (c046), "No." (c071), "Kestrel, continue." (c090) and "Kestrel, delete history." (c087), whose clips `make-corpus.ps1`'s default list makes, so `checks.txt` gets only the ten phrases that neither corpus has (its own test refuses a phrase of `commands.txt`). Each check plays one clip with the scripted model installed and checks the device by the hooks, never by text:
- a task phrase must start a task (`voice-state -Tag`'s `role=task`) that ends (`agent-task -Result -Tag`); its scripted model fails the task at its first reply, so no screen action follows (D30 step 12);
- an answer is checked against a question of a task that the check started by text first: "confirm", "yes", "no" and "cancel" answer a confirmation of a deleting tap on `:testapp`'s views fixture (Standard mode confirms it and test mode does not block it, D27), and must end the task with `confirmed=1`, `confirmed=1`, `end=DECLINED` and `end=CANCELLED`; "stop" must cancel a task whose scripted model only waits (D2);
- Kestrel's own phrases must be read as their requests (`read=continue`, `deletehistory`, `remember`, `saveroutine`); after "Kestrel, delete history" and "Kestrel, remember …" the check sends "no", so that their questions never stay open, nothing is deleted or stored, and the fact "gate code" never exists (D30 step 12 removes it again anyway).
The results inform and do not gate (D27): short transcripts on the A71 are still under investigation. A check whose task still runs afterwards, as after a clip that the phone did not match, cancels it. The checks need Kestrel listening and `:testapp` installed, which Task 9's script provides.

Counts: Pester N + 6 in each shell (`AgentChecks.Tests.ps1`); `Corpus.Tests.ps1` keeps its count.

- [ ] **Step 1: Write the failing tests**

Create `scripts/tests/AgentChecks.Tests.ps1`:

```powershell
# Tests of scripts\lib\AgentChecks.ps1, the agent's speech checks (design D28 of sub-project 5), with the hooks, the
# device's shell and the check runner mocked. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in
# PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Kestrel.ps1"
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\AutoCheck.ps1"
    . "$PSScriptRoot\..\lib\Corpus.ps1"
    . "$PSScriptRoot\..\lib\AgentChecks.ps1"
    # The library calls the hooks and the device through these, which the tests mock.
    function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { }
    function Invoke-SettingsCommand([string]$Serial, [string]$Command) { }
    $Serial = 'emulator-5554'
}

Describe 'The agent''s speech checks' {
    BeforeEach {
        Mock Start-Sleep { }
    }

    It 'lists the clips of the check corpus and of the command corpus that exist' {
        $root = "$PSScriptRoot\..\..\corpus"
        $ids = @(Read-Corpus -Path "$root\commands.txt") + @(Read-Corpus -Path "$root\checks.txt") | ForEach-Object { $_.Id }
        $checks = @(Get-AgentSpeechChecks)

        $checks.Count | Should -Be 15
        @($checks | Where-Object { $_.Clip -notin $ids }).Clip | Should -BeNullOrEmpty
        @($checks | Where-Object { $_.Kind -eq 'task' }).Count | Should -Be 6
        @($checks | Where-Object { $_.Kind -eq 'answer' -and $_.Script -eq 'confirm' }).End | Should -Be @('FAILED_BY_MODEL', 'FAILED_BY_MODEL', 'DECLINED', 'CANCELLED')
        @($checks | Where-Object { $_.Kind -eq 'phrase' }).Read | Should -Be @('continue', 'deletehistory', 'remember', 'saveroutine')
    }

    It 'writes the scripted model''s replies as a JSON array through run-as, and installs them' {
        Mock Invoke-SettingsCommand { }
        Mock Invoke-Hook { 'script=installed replies=2' } -ParameterFilter { $Command -eq 'agent-script' }

        Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript confirm)

        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-script' -and $With.File -eq 'speech-check.json' }
        Should -Invoke Invoke-SettingsCommand -Times 1 -Exactly -ParameterFilter {
            if ($Command -notmatch "^echo ([A-Za-z0-9+/=]+) \| base64 -d \| run-as dev\.kestrel sh -c 'mkdir -p files/agent-scripts && cat > files/agent-scripts/speech-check\.json'$") { return $false }
            # Windows PowerShell 5.1 gives the array as one object, which the assignment unpacks.
            $replies = ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1])))
            $replies = @($replies)
            $replies.Count -eq 2 -and $replies[0].tool -eq 'tap' -and $replies[0].target.label -eq 'Rebuild' -and $replies[0].arguments.category -eq 'deleting' -and $replies[1].tool -eq 'fail'
        }
        Mock Invoke-Hook { 'script=installed replies=1' } -ParameterFilter { $Command -eq 'agent-script' }
        { Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript confirm) } | Should -Throw 'agent-script did not install speech-check.json.'
        @(Get-SpeechScript wait).Count | Should -Be 6
        @(Get-SpeechScript end).Count | Should -Be 1
    }

    It 'waits for the task''s end by its tag, also when the tag has no task yet' {
        $answers = [System.Collections.Generic.Queue[string]]::new([string[]]@('error', 'state=running', 'state=done end=CANCELLED steps=1 confirmed=0'))
        Mock Invoke-Hook {
            $next = $answers.Dequeue()
            if ($next -eq 'error') { throw 'error: no task has the tag k1' }
            $next
        } -ParameterFilter { $Command -eq 'agent-task' -and $With.Tag -eq 'k1' -and $With.Result }

        $result = Wait-AgentTask -Tag 'k1' -Seconds 30

        $result['end'] | Should -Be 'CANCELLED'
        Should -Invoke Invoke-Hook -Times 3 -Exactly
        { ConvertFrom-AgentTaskResult 'tag=k1 role=task' } | Should -Throw 'Not an answer of agent-task -Result*'
    }

    It 'judges a task phrase by the task that it started, an answer by the task''s end, and a phrase by Kestrel''s reading' {
        $checks = @(Get-AgentSpeechChecks)
        $task = $checks[0]
        $confirm = $checks | Where-Object { $_.Name -eq 'answer: confirm' }
        $no = $checks | Where-Object { $_.Name -eq 'answer: no' }
        $continue = $checks | Where-Object { $_.Name -eq 'Kestrel''s phrase: continue' }
        $done = [ordered]@{ state = 'done'; end = 'FAILED_BY_MODEL'; confirmed = '1' }

        (Get-AgentSpeechVerdict $task ([ordered]@{ role = 'task' }) $done).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $task ([ordered]@{ role = 'command' }) $null).Detail | Should -Be 'role=command end=none'
        (Get-AgentSpeechVerdict $confirm ([ordered]@{ role = 'none' }) $done).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $no ([ordered]@{ role = 'none' }) $done).Passed | Should -BeFalse
        (Get-AgentSpeechVerdict $no ([ordered]@{ role = 'none' }) ([ordered]@{ state = 'done'; end = 'DECLINED'; confirmed = '0' })).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $continue ([ordered]@{ read = 'continue' }) $null).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $continue ([ordered]@{ read = 'task' }) $null).Detail | Should -Be 'read=task (expected continue)'
    }

    It 'starts an answer check''s task by text before the clip, plays the clip, and cancels the task when it still asks' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'answer: cancel' }
        $clip = [pscustomobject]@{ Voice = 'kokoro'; Text = 'Cancel.'; Path = 'C:\corpus\generated\kokoro\c046.wav' }
        Mock Install-AgentScript { }
        Mock Invoke-SettingsCommand { }
        Mock Invoke-Hook { 'phase=running asking=confirm work=running-task handOver=none' } -ParameterFilter { $Command -eq 'agent-state' }
        Mock Invoke-Hook { 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { 'answered' } -ParameterFilter { $Command -eq 'agent-answer' }
        Mock Invoke-Hook { 'state=done end=CANCELLED confirmed=0' } -ParameterFilter { $Command -eq 'agent-task' }
        # The runner runs the check's own blocks as the real one does, and the phone did not match the clip.
        Mock Invoke-AutoCheck {
            $context = & $Before
            $waited = & $Wait 'k9' 60
            [pscustomobject]@{ Context = $context; Waited = $waited; Clip = $Clip; Line = "$Name [kokoro]: check=fail" }
        }

        $result = Invoke-AgentSpeechCheck -Check $check -Clip $clip -Play { }

        $result.Clip.Path | Should -Be 'C:\corpus\generated\kokoro\c046.wav'
        $result.Waited['end'] | Should -Be 'CANCELLED'
        Should -Invoke Install-AgentScript -Times 1 -Exactly -ParameterFilter { $Name -eq 'speech-check.json' -and @($Replies).Count -eq 2 }
        Should -Invoke Invoke-SettingsCommand -Times 1 -Exactly -ParameterFilter { $Command -eq 'am start -n dev.kestrel.testapp/.ViewsActivity' }
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'voice' -and $With.Named -and $With.Tag -eq $result.Context -and $With.Text -like 'Kestrel, *' }
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-answer' -and $With.Tag -eq $result.Context -and $With.Answer -eq 'cancel' }
    }

    It 'sends "no" after a phrase whose question must not stay open, and starts no task for it' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'Kestrel''s phrase: delete history' }
        Mock Install-AgentScript { }
        Mock Invoke-Hook { 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-AutoCheck { [pscustomobject]@{ Context = (& $Before); Waited = (& $Wait 'k9' 60); Line = "$Name [piper]: check=pass" } }

        $result = Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = 'piper'; Text = 'Kestrel, delete history.'; Path = 'x.wav' }) -Play { }

        $result.Context | Should -BeNullOrEmpty
        $result.Waited | Should -BeNullOrEmpty
        Should -Invoke Install-AgentScript -Times 0 -Exactly
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'voice' -and $With.Text -eq 'no' -and $With.Tag -match '^k[0-9a-f]{12}$' }
    }
}
```

In `scripts/tests/Corpus.Tests.ps1`, replace:

```powershell
        $checks.Count | Should -Be 23
```

with:

```powershell
        $checks.Count | Should -Be 33
```

- [ ] **Step 2: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: exit code 1. In both shells Pester reports N + 6 tests, of which 7 fail: the six of `AgentChecks.Tests.ps1`, because `scripts\lib\AgentChecks.ps1` does not exist, and `reads the check corpus, whose ids and phrases are not in corpus\commands.txt`, which finds 23 lines, not 33.

- [ ] **Step 3: Add the agent's phrases to the check corpus**

In `corpus/checks.txt`, replace:

```text
# is marked for the owner, and none is private.
```

with:

```text
# is marked for the owner, and none is private. k024 to k033 are the agent's phrases (plan 5C), which the speech checks of
# scripts\lib\AgentChecks.ps1 play in the sittings of scripts\a71-agent.ps1.
```

In `corpus/checks.txt`, replace:

```text
k023 | answer | answer | 0 | Task | 0 | Open the weather.
```

with:

```text
k023 | answer | answer | 0 | Task | 0 | Open the weather.
k024 | task | idle | 1 | Task | 0 | Kestrel, send Ana a Viber message that I'm 10 minutes late.
k025 | task | idle | 1 | Task | 0 | Kestrel, turn on hotspot.
k026 | task | idle | 1 | Task | 0 | Kestrel, start Waze navigation to home.
k027 | task | idle | 1 | Task | 0 | Kestrel, open en.wikipedia.org in Firefox and search for common kestrel.
k028 | task | idle | 1 | Task | 0 | Kestrel, send yesterday's photos to John on Telegram.
k029 | task | idle | 1 | Task | 0 | Kestrel, read my latest email back to me.
k030 | own | idle | 0 | - | 0 | Confirm.
k031 | own | idle | 0 | - | 0 | Yes.
k032 | own | idle | 1 | - | 0 | Kestrel, remember that the gate code is blue.
k033 | own | idle | 1 | - | 0 | Kestrel, save that as test routine.
```

- [ ] **Step 4: Write the speech checks' library**

Create `scripts/lib/AgentChecks.ps1`:

```powershell
# The agent's speech checks (design D28 of sub-project 5) for the check runner Invoke-AutoCheck: each check plays one clip
# of the agent's phrases through this computer's speakers, with the scripted model installed, and checks on the phone
# through the debug hooks what the phrase did. A task phrase must start a task (hook H2 and agent-task -Result -Tag); an
# answer must answer the question of a task that the check started by text before the clip (agent-state and
# agent-task -Result -Tag); and Kestrel's own phrases must be read as their requests (voice-state -Tag's read). The
# results inform and do not gate (design D27). Dot-source scripts\lib\Kestrel.ps1, scripts\lib\Session.ps1 and
# scripts\lib\AutoCheck.ps1 first; the caller defines Invoke-Hook([string]$Command, [hashtable]$With) and $Serial, the
# device that Assert-TestDevice accepted, as the check runner needs. Nothing here holds what Kestrel heard: the hooks
# answer only kinds, counts and states.

# The fixture on which the answer checks' tasks act: :testapp's views fixture, whose button "Rebuild" changes nothing
# that matters.
function Get-SpeechFixture { 'dev.kestrel.testapp/.ViewsActivity' }

# The scripted model's replies for a check's task, as plan 5A's ScriptedModel reads them: 'end' fails the task at its
# first reply, without any screen action; 'confirm' taps the fixture's "Rebuild" as a deleting action, which Standard
# mode confirms and test mode does not block, and then fails the task; 'wait' only waits, six times 10 s.
function Get-SpeechScript([ValidateSet('end', 'confirm', 'wait')][string]$Kind) {
    $end = @{ tool = 'fail'; arguments = @{ reason = 'The speech check ends here.' } }
    $replies = switch ($Kind) {
        'end' { @($end) }
        'confirm' { @(@{ tool = 'tap'; target = @{ label = 'Rebuild' }; arguments = @{ reason = 'The speech check asks to confirm.'; category = 'deleting' } }, $end) }
        'wait' { @(1..6 | ForEach-Object { @{ tool = 'wait'; arguments = @{ seconds = 10; reason = 'The speech check waits.' } } }) }
    }
    $replies
}

# Copies the scripted model's replies as a JSON array into Kestrel's folder files/agent-scripts on the device, through
# run-as, and installs them with the hook agent-script (plan 5B's format). The JSON goes as base64, so that no quote of
# it reaches the device's shell. Throws when the hook does not answer that it installed all of them.
function Install-AgentScript([string]$Name, [object[]]$Replies) {
    $json = ConvertTo-Json -InputObject @($Replies) -Depth 8 -Compress
    $base64 = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($json))
    Invoke-SettingsCommand -Serial $Serial -Command "echo $base64 | base64 -d | run-as dev.kestrel sh -c 'mkdir -p files/agent-scripts && cat > files/agent-scripts/$Name'" | Out-Null
    $answer = ConvertFrom-HookPairs (Invoke-Hook agent-script @{ File = $Name })
    if ($answer['script'] -ne 'installed' -or $answer['replies'] -ne "$(@($Replies).Count)") { throw "agent-script did not install $Name." }
}

# Reads an answer of 'agent-task -Result -Tag', such as 'state=done end=FINISHED steps=4 ...', into an ordered table.
function ConvertFrom-AgentTaskResult([string]$Text) {
    $result = ConvertFrom-HookPairs $Text
    if ($result['state'] -notin 'running', 'done') { throw "Not an answer of agent-task -Result: '$Text'" }
    $result
}

# Reads 'agent-task -Result -Tag' until the task with the tag has ended, at most $Seconds, and returns its last answer, or
# $null when no task has the tag yet. A hook call that fails, as before Kestrel has linked the tag to its task, counts as
# no answer.
function Wait-AgentTask([string]$Tag, [double]$Seconds = 600) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $result = $null
    while ($true) {
        try { $result = ConvertFrom-AgentTaskResult (Invoke-Hook agent-task @{ Result = $true; Tag = $Tag }) } catch { Write-Verbose 'agent-task did not answer yet.' }
        if ($result -and $result['state'] -eq 'done') { return $result }
        if ($watch.Elapsed.TotalSeconds -ge $Seconds) { return $result }
        Start-Sleep -Milliseconds 500
    }
}

# Reads 'agent-state -Tag' until $Until is true for its answer, at most $Seconds, and returns the last answer, or $null.
function Wait-AgentState([string]$Tag, [scriptblock]$Until, [double]$Seconds = 60) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $state = $null
    while ($true) {
        try { $state = ConvertFrom-HookPairs (Invoke-Hook agent-state @{ Tag = $Tag }) } catch { Write-Verbose 'agent-state did not answer yet.' }
        if ($state -and (& $Until $state)) { return $state }
        if ($watch.Elapsed.TotalSeconds -ge $Seconds) { return $state }
        Start-Sleep -Milliseconds 500
    }
}

# The speech checks, in the order in which they run: the six task phrases of design D3 (corpus\checks.txt k024 to k029),
# the answers to a task's confirmation and "stop" (k030, k031 and corpus\commands.txt's c071, c046 and c045), and
# Kestrel's own phrases (c090, c087, k032 and k033). Kind 'task' starts a task that the scripted model ends at once; 'answer'
# first starts a task by text whose scripted model asks to confirm ($Asking 'confirm') or only waits ('running'), and
# then expects the task's end ($End) and its count of confirmed actions ($Confirmed); 'phrase' expects Kestrel's reading
# of the transcript ($Read), and $Then is the text that the check sends afterwards, so that a question of Kestrel's
# never takes the next check's words.
function Get-AgentSpeechChecks {
    $task = { param($Name, $Clip) [pscustomobject]@{ Name = "task phrase: $Name"; Clip = $Clip; Kind = 'task'; Script = 'end'; Asking = ''; End = ''; Confirmed = ''; Read = ''; Then = '' } }
    $answer = { param($Name, $Clip, $Script, $Asking, $End, $Confirmed) [pscustomobject]@{ Name = "answer: $Name"; Clip = $Clip; Kind = 'answer'; Script = $Script; Asking = $Asking; End = $End; Confirmed = $Confirmed; Read = ''; Then = '' } }
    $phrase = { param($Name, $Clip, $Read, $Then) [pscustomobject]@{ Name = "Kestrel's phrase: $Name"; Clip = $Clip; Kind = 'phrase'; Script = ''; Asking = ''; End = ''; Confirmed = ''; Read = $Read; Then = $Then } }
    @(
        & $task 'the Viber message' 'k024'
        & $task 'the hotspot' 'k025'
        & $task 'Waze' 'k026'
        & $task 'Firefox' 'k027'
        & $task 'yesterday''s photos' 'k028'
        & $task 'the latest e-mail' 'k029'
        & $answer 'confirm' 'k030' 'confirm' 'confirm' 'FAILED_BY_MODEL' '1'
        & $answer 'yes' 'k031' 'confirm' 'confirm' 'FAILED_BY_MODEL' '1'
        & $answer 'no' 'c071' 'confirm' 'confirm' 'DECLINED' '0'
        & $answer 'cancel' 'c046' 'confirm' 'confirm' 'CANCELLED' '0'
        & $answer 'stop' 'c045' 'wait' 'running' 'CANCELLED' '0'
        & $phrase 'continue' 'c090' 'continue' ''
        & $phrase 'delete history' 'c087' 'deletehistory' 'no'
        & $phrase 'remember' 'k032' 'remember' 'no'
        & $phrase 'save a routine' 'k033' 'saveroutine' ''
    )
}

# A speech check's verdict (ConvertTo-Verdict's form) from the tagged state of the clip's transcript ($State, from
# voice-state -Tag) and the end of the check's task ($Result, from agent-task -Result -Tag, or $null).
function Get-AgentSpeechVerdict($Check, $State, $Result) {
    $ended = $Result -and $Result['state'] -eq 'done'
    $end = if ($Result) { "$($Result['end'])" } else { 'none' }
    switch ($Check.Kind) {
        'task' {
            $passed = $State['role'] -eq 'task' -and $ended
            [pscustomobject]@{ Passed = $passed; Detail = "role=$($State['role']) end=$end" }
        }
        'answer' {
            $confirmed = if ($Result) { "$($Result['confirmed'])" } else { 'none' }
            $passed = $ended -and $end -eq $Check.End -and $confirmed -eq $Check.Confirmed
            [pscustomobject]@{ Passed = $passed; Detail = "end=$end confirmed=$confirmed (expected end=$($Check.End) confirmed=$($Check.Confirmed))" }
        }
        default {
            [pscustomobject]@{ Passed = $State['read'] -eq $Check.Read; Detail = "read=$($State['read']) (expected $($Check.Read))" }
        }
    }
}

# Ends the check's task when it still runs, as after a clip that the phone did not match: an open question gets
# "cancel" through agent-answer, and a task without a question gets "stop" as a tagged transcript.
function Stop-SpeechTask([string]$Tag) {
    $state = Wait-AgentState -Tag $Tag -Until { $true } -Seconds 0
    if (-not $state -or $state['phase'] -eq 'ended') { return }
    if ($state['asking'] -ne 'none') {
        Invoke-Hook agent-answer @{ Tag = $Tag; Answer = 'cancel' } | Out-Null
    } else {
        Invoke-Hook voice @{ Send = 'heard'; Text = 'stop'; Tag = (New-CheckTag) } | Out-Null
    }
    Wait-AgentTask -Tag $Tag -Seconds 30 | Out-Null
}

# Runs one speech check with Invoke-AutoCheck and its clip ($Clip: Voice, Text, Path), which $Play plays. An answer
# check first opens the fixture, installs its script and starts its task by text, and waits until the task asks
# ($Check.Asking); a task check installs its script before the clip. Afterwards it ends a task that still runs and sends
# the phrase's $Then. Returns Invoke-AutoCheck's result, whose Line goes into the results.
function Invoke-AgentSpeechCheck($Check, $Clip, [scriptblock]$Play) {
    $setup = @{ Tag = '' }
    $before = {
        if ($Check.Script) { Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript $Check.Script) }
        if ($Check.Kind -ne 'answer') { return $null }
        Invoke-SettingsCommand -Serial $Serial -Command "am start -n $(Get-SpeechFixture)" | Out-Null
        Start-Sleep -Seconds 2
        $setup.Tag = New-CheckTag
        Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, tap Rebuild on the views fixture.'; Named = $true; Tag = $setup.Tag } | Out-Null
        $asking = $Check.Asking
        $state = Wait-AgentState -Tag $setup.Tag -Seconds 60 -Until {
            param($State)
            if ($asking -eq 'running') { $State['phase'] -eq 'running' -and $State['asking'] -eq 'none' } else { $State['asking'] -eq $asking }
        }
        $ready = if ($asking -eq 'running') { $state -and $state['phase'] -eq 'running' } else { $state -and $state['asking'] -eq $asking }
        if (-not $ready) { throw "the check's task did not reach '$asking' within 60 s" }
        $setup.Tag
    }
    $wait = {
        param($Tag, $Seconds)
        if ($Check.Kind -eq 'task') { Wait-AgentTask -Tag $Tag -Seconds ([math]::Min($Seconds, 60)) }
        elseif ($Check.Kind -eq 'answer') { Wait-AgentTask -Tag $setup.Tag -Seconds ([math]::Min($Seconds, 60)) }
        else { $null }
    }
    # The runner gives the tagged state, the context of -Before and what -Wait returned; the verdict needs the first and the last.
    $verify = { param($State) Get-AgentSpeechVerdict -Check $Check -State $State -Result $args[1] }
    try {
        Invoke-AutoCheck -Name $Check.Name -Text '' -Clip $Clip -Play $Play -Before $before -Wait $wait -WaitSeconds 60 -Verify $verify
    } finally {
        if ($setup.Tag) { Stop-SpeechTask $setup.Tag }
        if ($Check.Then) { Invoke-Hook voice @{ Send = 'heard'; Text = $Check.Then; Tag = (New-CheckTag) } | Out-Null }
    }
}
```

- [ ] **Step 5: Run the script tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: exit code 0, "The script tests and checks passed."; in both shells Pester reports N + 6 tests, all passed, and PSScriptAnalyzer 0 problems.

- [ ] **Step 6: Make the clips of the check corpus on the emulator**

The controller holds the emulator lock (process rule 9); never create, wait for or delete it. Kestrel's debug build and its speech models, with `-Kokoro`, are on the emulator from the earlier plans.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\make-corpus.ps1 -Serial emulator-5554 -Voice piper -List checks; scripts\make-corpus.ps1 -Serial emulator-5554 -Voice kokoro -List checks; foreach ($voice in 'piper', 'kokoro') { foreach ($id in 'k024', 'k033', 'c045', 'c046', 'c071', 'c087', 'c090') { "$voice $id $(Test-Path "corpus\generated\$voice\$id.wav")" } }`
Expected: twice "Made 33 clips in …\corpus\generated\piper." and "… kokoro.", then 14 lines that end with `True`. A `False` for a `c` line means that this computer lacks the command corpus's clips: make them with `scripts\make-corpus.ps1 -Serial emulator-5554 -Voice <voice>` (the default list) and look again. The clips stay out of git (`corpus/generated` is ignored).

- [ ] **Step 7: Commit**

```powershell
git add corpus/checks.txt scripts/lib/AgentChecks.ps1 scripts/tests/AgentChecks.Tests.ps1 scripts/tests/Corpus.Tests.ps1
git commit -m "test(agent): add the agent's phrases to the check corpus, and the speech checks that play them" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 8: The live side: `agent-live` and `task-check.ps1 -Live`

**Files:**
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHooks.kt` (below its two 5C anchors), `scripts/hook.ps1` (below `# sub-project 5C: live hooks` and `# sub-project 5C: live hook names`), `scripts/task-check.ps1` (below `# sub-project 5C: live parameter` and `# sub-project 5C: live`), `scripts/tests/Hook.Tests.ps1` (one test at the end)
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentLiveHookTest.kt`, `scripts/tests/TaskCheckLive.Tests.ps1`

**Interfaces:**
- Consumes: plan 5B's `SettingKeys.AGENT_LIVE_SESSION = SettingKey("agent-live-session", Boolean.serializer(), false)` (`AppGraph.kt`), which `AgentRunner` reads for a task sent with `live=true`; `AgentHooks.run(command, intent)` and `AgentHooks.COMMANDS` with plan 5A's anchors; `TestHooks(graph, context).run(intent)` and `TestHooks.ACTION_PREFIX`; plan 5B's `scripts\task-check.ps1` (`plan-5b-interfaces.md`, section 4): its parameters, the anchor `# sub-project 5C: live parameter`, and the three lines `$live = $false`, `# sub-project 5C: live` and the throw, which come right after its dot-sources and before `Use-KestrelToolchain`; when `$live` is true, its runs send H1 with `-Live` and install no script, and its H1 call passes `-Model $Model` when `$Model` is set; its table reads `$Name` (the first name of `telegram-late` and `telegram-photos`, defaults Ana and John) and `$Mail` (read-email's address, a run is `result=skipped reason=untestable` without it), which this task declares and task-check.ps1 never assigns.
- Produces: the hook `agent-live [-On true|false] [-Model <id>]`, answering `live=on|off` and, with `-Model`, `model=listed|unlisted`: whether the default profile's own model list (the one that the provider sent at the sign-in or at "Refresh models", `ProviderGraph.models`) holds that ID; `task-check.ps1 -Live [-Model <id>] [-Name <first name>] [-Mail <address>]`, refused outside a sitting (`KESTREL_AGENT_SESSION` unset in the process: "task-check.ps1 -Live runs only inside a sitting of scripts\a71-agent.ps1."), with `-Script` ("task-check.ps1 -Live calls the owner's provider and takes no -Script.") and without the device's live session ("task-check.ps1 -Live needs the device's live session, which scripts\a71-agent.ps1 opens; <serial> has none."). Tasks 9 and 10 use both.

Design D28's live side: a tagged task calls the owner's provider only while a live session is open, which is H1's extra `live=true` together with the device setting `AGENT_LIVE_SESSION`, and only `a71-agent.ps1` sets that setting, in sitting 2's preparation, and clears it at the sitting's end, also after a failure (review C2-M6). `agent-live` is the hook that sets it; `task-check.ps1 -Live` works only while the process has `KESTREL_AGENT_SESSION`, which `a71-agent.ps1` sets in its own process, and the device answers `live=on`; there is no `-Session` switch (review C M10). PowerShell's names ignore case, so task-check.ps1's `$live` is also the switch `-Live`: its assignment `$live = $false` overwrites the switch, and this task's block reads the switch from `$PSBoundParameters` instead. `-Model`, `-Name` and `-Mail` are the three values that `a71-agent.ps1` gives each run in sitting 2 (D30 steps 16 to 19; the controller's agreement between plans 5B and 5C).

Counts: Pester N + 2 in each shell (`TaskCheckLive.Tests.ps1` 1, `Hook.Tests.ps1` 1); `:app`'s device tests: `dev.kestrel.app.debug` D_D + 1 (`AgentLiveHookTest`; D_D is that package's count after plan 5B, which the controller gives with the other starting counts); the JVM counts unchanged.

- [ ] **Step 1: Write the failing device test of `agent-live`**

Create `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentLiveHookTest.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook agent-live of plan 5C (design D28): it opens and closes the live session of sitting 2, in which a task sent
 * with live=true may call the owner's provider. The test puts back the setting that the device had.
 */
class AgentLiveHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun agentLiveOpensAndClosesTheLiveSession() = runBlocking {
        val before = graph.settings.get(SettingKeys.AGENT_LIVE_SESSION)
        try {
            assertEquals("live=on", hook(true))
            assertTrue(graph.settings.get(SettingKeys.AGENT_LIVE_SESSION))
            // Without -On it only answers.
            assertEquals("live=on", hook(null))
            assertEquals("live=off", hook(false))
            assertFalse(graph.settings.get(SettingKeys.AGENT_LIVE_SESSION))
            // With a model it also says whether the default profile's own list holds it; no list holds this one.
            assertEquals("live=off model=unlisted", hook(null, "no-such-model"))
        } finally {
            graph.settings.set(SettingKeys.AGENT_LIVE_SESSION, before)
        }
    }

    private suspend fun hook(on: Boolean?, model: String? = null): String {
        val intent = Intent(TestHooks.ACTION_PREFIX + "AGENT_LIVE")
        if (on != null) intent.putExtra("on", on)
        if (model != null) intent.putExtra("model", model)
        return TestHooks(graph, context).run(intent).text
    }
}
```

- [ ] **Step 2: Run the device test to verify that it fails**

The controller holds the emulator lock (process rule 9); never create, wait for or delete it.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 ':app:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.debug.AgentLiveHookTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: FAIL, 1 test, 1 failed: `agentLiveOpensAndClosesTheLiveSession` expected `live=on` but got an `error: …` answer, because no hook knows `AGENT_LIVE`.

- [ ] **Step 3: Add the hook `agent-live`**

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHooks.kt`, replace:

```kotlin
        // sub-project 5C: command branches
```

with:

```kotlin
        // sub-project 5C: command branches
        // The live session of sitting 2 (design D28): only scripts\a71-agent.ps1 opens it, and closes it at the sitting's end.
        "AGENT_LIVE" -> {
            if (intent.hasExtra("on")) {
                graph.settings.set(dev.kestrel.app.SettingKeys.AGENT_LIVE_SESSION, intent.getBooleanExtra("on", false))
            }
            val live = "live=${if (graph.settings.get(dev.kestrel.app.SettingKeys.AGENT_LIVE_SESSION)) "on" else "off"}"
            // With --es model: whether the default profile's own model list, which the provider sent, holds that ID, so
            // that a71-agent.ps1 refuses a comparison model that the owner's account does not list (design D32 item 35).
            val model = intent.getStringExtra("model")
            if (model == null) {
                live
            } else {
                val profile = graph.settings.get(dev.kestrel.app.SettingKeys.PROVIDERS).defaultProfile
                val listed = profile != null && graph.providers.models(profile.id).models.any { it.id == model }
                "$live model=${if (listed) "listed" else "unlisted"}"
            }
        }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHooks.kt`, replace:

```kotlin
            // sub-project 5C: commands
```

with:

```kotlin
            // sub-project 5C: commands
            "AGENT_LIVE",
```

- [ ] **Step 4: Run the device test to verify that it passes**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 ':app:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.debug.AgentLiveHookTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: PASS, 1 test, 0 failed.

- [ ] **Step 5: Write the failing script tests**

Create `scripts/tests/TaskCheckLive.Tests.ps1`:

```powershell
# Tests of scripts\task-check.ps1's switch -Live (plan 5C), which only a sitting of scripts\a71-agent.ps1 may use. The
# script needs PowerShell 7.3, so each test starts it with pwsh, also when the tests run in Windows PowerShell 5.1.

Describe 'task-check.ps1 -Live' {
    It 'refuses -Live outside a sitting, and -Live with a script, before it reaches a device' {
        $taskCheck = Join-Path $PSScriptRoot '..\task-check.ps1'
        # Windows PowerShell 5.1 stops at a native program's error output under 'Stop', which the tests' runner sets.
        $ErrorActionPreference = 'Continue'
        $saved = $env:KESTREL_AGENT_SESSION
        try {
            Remove-Item -Path Env:KESTREL_AGENT_SESSION -ErrorAction SilentlyContinue
            $outside = (& pwsh -NoProfile -File $taskCheck -Serial no-such-device -Task hotspot -Live 2>&1) -join "`n"
            $outsideExit = $LASTEXITCODE
            $env:KESTREL_AGENT_SESSION = 'a test'
            $both = (& pwsh -NoProfile -File $taskCheck -Serial no-such-device -Task hotspot -Live -Script stand-in.json 2>&1) -join "`n"
            $bothExit = $LASTEXITCODE
        } finally {
            if ($null -eq $saved) { Remove-Item -Path Env:KESTREL_AGENT_SESSION -ErrorAction SilentlyContinue } else { $env:KESTREL_AGENT_SESSION = $saved }
        }

        $outsideExit | Should -Not -Be 0
        $outside | Should -BeLike '*task-check.ps1 -Live runs only inside a sitting of scripts\a71-agent.ps1.*'
        $bothExit | Should -Not -Be 0
        $both | Should -BeLike '*task-check.ps1 -Live calls the owner''s provider and takes no -Script.*'
    }
}
```

In `scripts/tests/Hook.Tests.ps1`, add before its last closing brace:

```powershell
    It 'accepts agent-live, which opens and closes the live session of sitting 2, and reaches the device check' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $output = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-live -On false 2>&1) -join "`n"
        $exit = $LASTEXITCODE

        $exit | Should -Not -Be 0
        $output | Should -Not -BeLike "*Cannot validate argument on parameter 'Command'*"
        $output | Should -BeLike '*Device no-such-device is not connected.*'
    }
```

- [ ] **Step 6: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: exit code 1. In both shells Pester reports N + 2 tests, of which 2 fail: `refuses -Live outside a sitting, and -Live with a script, before it reaches a device`, because task-check.ps1 knows no parameter `-Live`, and `accepts agent-live, which opens and closes the live session of sitting 2, and reaches the device check`, because hook.ps1's `-Command` refuses `agent-live` ("Cannot validate argument on parameter 'Command'").

- [ ] **Step 7: Add `agent-live` to hook.ps1**

In `scripts/hook.ps1`, replace:

```powershell
# sub-project 5C: live hooks
```

with:

```powershell
# sub-project 5C: live hooks
#   agent-live [-On true|false] [-Model id] opens (true) or closes (false) the device's live session of sitting 2 of
#                                           scripts\a71-agent.ps1 (design D28), in which a task sent with -Live may call
#                                           the owner's provider, and answers live=on|off; only a71-agent.ps1 opens it;
#                                           with -Model it adds model=listed|unlisted: whether the default profile's own
#                                           model list holds that ID
```

In `scripts/hook.ps1`, replace:

```powershell
        # sub-project 5C: live hook names
```

with:

```powershell
        # sub-project 5C: live hook names
        'agent-live',
```

- [ ] **Step 8: Add `-Live`, `-Model`, `-Name` and `-Mail` to task-check.ps1**

In `scripts/task-check.ps1`, replace:

```powershell
    # sub-project 5C: live parameter
```

with:

```powershell
    # sub-project 5C: live parameter
    # Plan 5C: a live run in sitting 2 of scripts\a71-agent.ps1 (design D28), the lightweight model of each run (design
    # D32 item 35), the first name of the Telegram variants (design D30 step 16), and the owner's address for
    # read-email's test e-mail, which only a71-agent.ps1 reads from corpus\owner\a71-config.json.
    [switch]$Live,
    [string]$Model,
    [string]$Name,
    [string]$Mail,
```

In `scripts/task-check.ps1`, replace:

```powershell
$live = $false
# sub-project 5C: live
if (-not $live -and -not $Script) { throw 'task-check.ps1 needs -Script <file> (the scripted model), or -Live in a sitting.' }
```

with:

```powershell
$live = $false
# sub-project 5C: live
# PowerShell's names ignore case, so $live above is the switch -Live too; the switch is read from the bound parameters.
if ($PSBoundParameters['Live']) {
    # A live run calls the owner's provider, so it runs only in sitting 2 of scripts\a71-agent.ps1, which sets
    # KESTREL_AGENT_SESSION for its own process and opens the device's live session with the hook agent-live (design D28).
    if (-not $env:KESTREL_AGENT_SESSION) { throw 'task-check.ps1 -Live runs only inside a sitting of scripts\a71-agent.ps1.' }
    if ($Script) { throw 'task-check.ps1 -Live calls the owner''s provider and takes no -Script.' }
    if ("$(& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command agent-live)" -notmatch '(^|\s)live=on(\s|$)') {
        throw "task-check.ps1 -Live needs the device's live session, which scripts\a71-agent.ps1 opens; $Serial has none."
    }
    $live = $true
}
if (-not $live -and -not $Script) { throw 'task-check.ps1 needs -Script <file> (the scripted model), or -Live in a sitting.' }
```

- [ ] **Step 9: Run the script tests and the device test to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: exit code 0, "The script tests and checks passed."; in both shells Pester reports N + 2 tests, all passed, and PSScriptAnalyzer 0 problems.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\install.ps1 -Serial emulator-5554; scripts\hook.ps1 -Serial emulator-5554 agent-live; scripts\hook.ps1 -Serial emulator-5554 agent-live -On false; $env:KESTREL_AGENT_SESSION = 'a test'; scripts\task-check.ps1 -Serial emulator-5554 -Task hotspot -Live; "exit: $LASTEXITCODE"; Remove-Item Env:KESTREL_AGENT_SESSION`
Expected: the install ends with "Success"; `live=off` twice; then task-check.ps1 stops with "task-check.ps1 -Live needs the device's live session, which scripts\a71-agent.ps1 opens; emulator-5554 has none." before any run, so that no request reaches a provider.

- [ ] **Step 10: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 11: Commit**

```powershell
git add app/src/debug/kotlin/dev/kestrel/app/debug/AgentHooks.kt app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentLiveHookTest.kt scripts/hook.ps1 scripts/task-check.ps1 scripts/tests/Hook.Tests.ps1 scripts/tests/TaskCheckLive.Tests.ps1
git commit -m "feat(agent): open the live session only in a sitting, through agent-live and task-check.ps1 -Live" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 9: `scripts\a71-agent.ps1`, sitting 1

**Files:**
- Create: `scripts/a71-agent.ps1`, `scripts/tests/A71Agent.Tests.ps1`

**Interfaces:**
- Consumes: Task 7's `scripts\lib\AgentChecks.ps1` (`Get-AgentSpeechChecks`, `Invoke-AgentSpeechCheck`, `Install-AgentScript`, `Get-SpeechScript`, `Wait-AgentState`, `Wait-AgentTask`, `Stop-SpeechTask`) and the clips of `corpus\generated\<voice>`; plan 4D's `scripts\lib\Session.ps1` (`Read-A71Config`, which leaves out the key `pin`; `Unlock-TestPhone` and `Send-TestPin -CheckOnly`, the only code that reads `pin`; `Enter-EmulatorLock`, `Add-SessionResult`, `ConvertFrom-HookPairs`, `Get-UnpassedTests`, `Test-ClassPassed`, `Set-EmulatorHostMicrophone`), `scripts\lib\Kestrel.ps1` (`Assert-TestDevice`, `Invoke-SettingsCommand`, `Format-TestDeviceCommand`, `Get-AccessibilitySettings`, `Restore-AccessibilitySettings`, `Set-SecureSetting`, `Get-GrantedPermissions`, `ConvertFrom-VoiceState`), `scripts\lib\Speaker.ps1` (`Set-SpeakerLevel`, `Invoke-Clip`), `scripts\lib\Corpus.ps1` (`Read-Corpus`), `scripts\lib\AutoCheck.ps1` (`New-CheckTag`), `scripts\install.ps1 [-TestApp]`, `scripts\push-models.ps1` (which copies plan 5C's OCR files, Task 1), `scripts\connected-test.ps1`; the hooks of plan 4D (`voice`, `voice-state`, `voice-clip -Quiet|-Clear`, `voice-route`, `screen-has`, `state`, `test-mode`, `provider-standin`) and of plan 5B (`plan-5b-interfaces.md`, section 4: `agent-settings -WhatsappOwnName`, `agent-touch-source [-Rule -Branch]`, `agent-tree`, `selfchat-check`, `screen-tap -Label`, `agent-script -Clear`, `agent-memory -Remove -Key`); plan 5B's device tests `dev.kestrel.app.screen.OwnActionsTest` (logcat tag `KestrelProbe`), `dev.kestrel.app.screen.SystemUiFilterTest` (tag `KestrelSystemUi`), `dev.kestrel.app.agent.AgentLocationTest`, `dev.kestrel.app.agent.NotificationListenerTest`, and plan 5C's `dev.kestrel.app.agent.eyes.OcrDeviceTest` (tag `KestrelOcr`: `ocr image=<full|region> limit=<960|1280> threads=<2|4> loadMs= detectMs= recogniseMs= lines= pssKb=`) and `dev.kestrel.app.screen.ScreenCaptureTest` (Tasks 3 and 4); Kestrel's listener component `dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener`; `:testapp`'s `FixturesActivity` (button "Views") and `NotificationActivity` (extra `headsUp`).
- Produces: `scripts\a71-agent.ps1 -Serial <A71> [-EmulatorSerial <s>] -Sitting 1 [-LockHeld] [-DryRun] [-Step <names>]` with the steps `prepare`, `owner`, `setup`, `device-tests`, `hotspot`, `recordings`, `speech` and `end`; its results in `corpus\owner\agent-sitting1-[dry-run-]<time>.txt`, its record in `corpus\owner\a71-agent-record[-dry-run].json` (keys `missingA71`, `installedA71`, `installedEmulator`, `emulatorPlay`, `emulatorAccount`, `playDisabled`, `chromeVersion`, `webViewVersion`, `touchBranch`, `touchRule`, `wazeA71`, `copilotA71`, `hotspot`, `wifiSharing`, `whatsappSelfChat`), the trees in `corpus\owner\trees\<time>\<a71|emulator>-<name>.json` and the device tests' logcat lines in `corpus\owner\agent-logcat-<time>.txt`; the configuration keys `whatsappOwnName`, `gmailAddress`, `homePlace`, `testLocation`, `testStreet` of `corpus\owner\a71-config.json`. Task 10 adds sitting 2 to the same script, and its helpers `Get-A71`, `Invoke-DeviceHook`, `Invoke-Device`, `Add-Result`, `Request-OwnerItem`, `Register-Restore`, `Complete-Restore`, `Invoke-Restore`, `Unlock-Device`, `Start-Listening`, `Grant-AgentPermissions`, `Enable-NotificationAccess`, `Set-OwnerNames`, `Enable-TestMode`, `Test-Installed`, `Open-App`, `Invoke-ScreenTap`, `Get-ScreenCount`, `Start-TestLocation` (returns its restore), `Enable-WifiSharing`, `Read-AgentRecord`, `Set-AgentRecord`, `Get-VersionName`, `Get-GoogleAccountCount`, `Get-BoundsCentre`.

Design D30's rules for both sittings and its steps before sitting 1 and 1 to 13, built like `a71-providers.ps1` on plan 4D's libraries. Where the design could not work as written against the code and the other plans, this task chose the smallest change, and the report says so:
- The PIN (review C3-I1): only plan 4D's `Unlock-TestPhone` types it (with `Send-TestPin`, the only reader of the key `pin`, in the same file); this script reads `a71-config.json` only through `Read-A71Config`, which leaves the key out, checks at its start with `Send-TestPin -CheckOnly` that the key is there, and its own keys' check names only keys, never values; its Pester test uses a fake configuration with a fake PIN and checks that the configuration that the script holds, its text and its errors never contain it. Plan 4D's `Unlock-TestPhone` checks the keyguard with `dumpsys activity activities` (not `dumpsys window`, as the design wrote), which works after a restart too.
- The test location (Q4): `:testapp` has no location sender in either plan, so the A71's test location comes from the shell as the mock-location app, with test providers for `gps`, `network` and `fused` and a background job that sends a fresh fix every second, as `scripts\spike-s1.ps1` already does on both devices; its restore removes the providers and puts back the shell's mode and the location switch.
- Waze's first screens and Home, and Play's "Don't auto-update apps": their labels are not known before a device shows them, so the owner does them in his step, while he is there (about 4 minutes more of his hands), instead of a script's taps that could miss. Firefox, which spike S4 already took past its first screens on both devices, is only checked.
- Telegram's share picker is opened by a text share from the shell ("Kestrel test"), because plan 5B's `agent-data` has no `share_media`; it is closed with back, without sending.
- The forward pickers are not recorded: opening one needs a message in the self-chat to long-press, whose position a script cannot know without reading the chat; the rule for them stays blocked until a later recording (D30 allows this).
- The A71's speech checks answer the questions that they start: "Kestrel, delete history" and "Kestrel, remember that the gate code is blue" get "no" (so nothing is deleted or stored), and the fact "gate code" is removed again by `agent-memory` anyway, with the count in the result (review C2-M5).
- The owner's names that `agent-settings` sets stay set after the sitting: they are the owner's own settings on his test phone and on the emulator, which sitting 2 needs again, and the Safety page shows them.
- The touch rule after D19's probe: `CREDITS` when any node action of Kestrel's reached the watcher on the A71, else `NONE` (`FIELD` needs API 37, plan 5B).

The steps and what they do (D30's numbers): `prepare` (before sitting 1: both builds and `:testapp` on both devices, the models with the OCR files, test mode, the owner's name for WhatsApp, the A71's Google accounts counted by type only, which of WhatsApp, Waze and Gmail are missing, the Play Store and the browsers' versions on kestrel-api37); `owner` (steps 2 to 7: the installs from Play while he watches, Telegram's sign-in, WhatsApp's link, the test Google account and Play's auto-update on kestrel-api37 with Waze and Gmail installed, Gmail's notifications off and the Play Store disabled until the end of sitting 2, Waze's Home on both devices, and the one real touch with a fixture task whose scripted model only waits: branch A when the watcher cancels it, else branch B with a tap on the fixture's button "Views"); `setup` (step 8); `device-tests` (step 9: `OcrDeviceTest`, `ScreenCaptureTest`, `OwnActionsTest`, `SystemUiFilterTest`, `AgentLocationTest` while no mock location is set (D32 item 45: `get_location` with another app in front, on the A71 too), and `NotificationListenerTest`, after the line that they may delete Kestrel's data; then the touch rule and branch, test mode, the owner's name and the models set again, and whether the A71 keeps its Copilot profile, so that sitting 2 asks for the device code only when it is gone (D32 item 49)); `hotspot` (step 10); `recordings` (step 11: Telegram's chat list, Saved Messages found through search and its `selfchat-check`, Telegram's share picker, WhatsApp's chat list and "<owner> (You)", Gmail's compose screen discarded with its own "Discard", SystemUI's shade and a heads-up notification of `:testapp`'s, each through `agent-tree`); `speech` (step 12: Task 7's checks in both voices on the A71 with the emulator's host microphone off, then on kestrel-api37 with it on for that part only); `end` (step 13: the record's summary; the restores run in the script's `finally`, newest first, and the Play Store's disabled state and `:testapp` on the A71 stay for sitting 2, review C3-M4). The sitting's results never hold what Kestrel heard or a screen's text: the hooks answer counts, kinds, states and hashes, and `agent-tree` records labels only where they are safe (plan 5B). Every restore is registered before its change, with the steps to do it by hand.

Counts: Pester N + 7 in each shell (`A71Agent.Tests.ps1`).

- [ ] **Step 1: Write the failing tests of the script's helpers**

Create `scripts/tests/A71Agent.Tests.ps1`:

```powershell
# Tests of the helpers in scripts\a71-agent.ps1, the two sittings of sub-project 5. The script needs devices, so the
# tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7. They use a fake configuration with a fake PIN and never open the owner's.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-agent.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Get-AgentConfig', 'Read-AgentRecord', 'Set-AgentRecord', 'Get-GoogleAccountCount', 'Get-VersionName',
        'Test-PackageListed', 'Test-NetworkValidated', 'Test-HotspotOn', 'Get-TouchRuleFromProbe', 'Get-OcrMeasures', 'Get-BoundsCentre')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
}

Describe 'The helpers of the sittings of sub-project 5' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'takes the steps also as one text separated by commas, and refuses an unknown one' {
        $names = @('prepare', 'owner', 'speech', 'end')

        Get-SessionSteps -Step @('owner,speech') -Names $names | Should -Be @('owner', 'speech')
        @(Get-SessionSteps -Step @() -Names $names).Count | Should -Be 0
        { Get-SessionSteps -Step @('owner,radio') -Names $names } | Should -Throw 'Unknown steps: radio.*'
    }

    It 'reads the owner''s answers without the key pin, and never puts the PIN or a value into a message (review C3-I1)' {
        $path = Join-Path $TestDrive 'a71-config.json'
        $fake = [ordered]@{ pin = '97531'; whatsappOwnName = 'Pera Test'; gmailAddress = 'pera.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Knez Mihailova' }
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))

        $config = Get-AgentConfig $path

        $config.whatsappOwnName | Should -Be 'Pera Test'
        $config.testLocation | Should -Be '44.8100,20.4600'
        $config.PSObject.Properties['pin'] | Should -BeNullOrEmpty
        ($config | ConvertTo-Json) | Should -Not -Match '97531'
        ($config | Out-String) | Should -Not -Match '97531'

        $fake.Remove('gmailAddress')
        $fake.testStreet = ''
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        $message = try { Get-AgentConfig $path; '' } catch { $_.Exception.Message }
        $message | Should -BeExactly 'corpus\owner\a71-config.json lacks the keys gmailAddress, testStreet, which the sittings of sub-project 5 need.'

        $fake.gmailAddress = 'pera.test@example.com'
        $fake.testStreet = 'Knez Mihailova'
        $fake.testLocation = 'Belgrade'
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        $message = try { Get-AgentConfig $path; '' } catch { $_.Exception.Message }
        $message | Should -Match 'testLocation'
        $message | Should -Not -Match '97531|Belgrade'
    }

    It 'keeps the record of a sitting in its file, value by value' {
        $path = Join-Path $TestDrive 'record.json'

        @((Read-AgentRecord $path).PSObject.Properties).Count | Should -Be 0
        Set-AgentRecord $path 'touchBranch' 'B'
        Set-AgentRecord $path 'hotspot' 'limit'
        Set-AgentRecord $path 'touchBranch' 'A'

        $record = Read-AgentRecord $path
        $record.touchBranch | Should -Be 'A'
        $record.hotspot | Should -Be 'limit'
    }

    It 'counts the Google accounts by their type only, and reads versions and packages' {
        $accounts = @('Accounts: 3', '    Account {name=someone@example.com, type=com.google}', '    Account {name=second@example.com, type=com.google}', '    Account {name=x, type=com.samsung.android.mobileservice}')

        Get-GoogleAccountCount $accounts | Should -Be 2
        Get-VersionName @('Packages:', '    versionCode=763200000 minSdk=29', '    versionName=145.0.7632.0', '    versionName=1.0') | Should -Be '145.0.7632.0'
        Get-VersionName @('Unable to find package') | Should -Be 'none'
        Test-PackageListed @('package:com.waze') 'com.waze' | Should -BeTrue
        Test-PackageListed @('package:com.waze.extra') 'com.waze' | Should -BeFalse
    }

    It 'finds a validated Wi-Fi network and a running hotspot in Android''s dumps' {
        $validated = '  NetworkAgentInfo{network{100}  handle{429}  ni{WIFI CONNECTED extra: } nc{[ Transports: WIFI Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED&NOT_ROAMING ]}}'
        $partial = '  NetworkAgentInfo{network{101}  handle{430}  ni{WIFI CONNECTED extra: } nc{[ Transports: WIFI Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED ]}}'

        Test-NetworkValidated @('Active default network: 100', $validated) | Should -BeTrue
        Test-NetworkValidated @($partial) | Should -BeFalse
        Test-HotspotOn @('Tether state:', '    swlan0 - TetheredState - lastError = 0') | Should -BeTrue
        Test-HotspotOn @('Tether state:', '    wlan1 - LocalHotspotState - lastError = 0') | Should -BeTrue
        Test-HotspotOn @('Tether state:', '    wlan1 - AvailableState - lastError = 0') | Should -BeFalse
    }

    It 'chooses the touch rule from the probe''s lines, and reads the OCR measures' {
        $quiet = @('I KestrelProbe: case=click sdk=33 touches=0 device=none source=none tool=none flags=none delayMs=none', 'I KestrelProbe: case=gesture sdk=33 touches=1 device=-1 source=4098 tool=1 flags=0x0 delayMs=12')
        $echo = @('I KestrelProbe: case=click sdk=37 touches=1 device=0 source=4098 tool=1 flags=0x800 delayMs=40')
        $ocr = @('I KestrelOcr: ocr image=full limit=960 threads=2 loadMs=410 detectMs=520 recogniseMs=830 lines=12 pssKb=198000', 'I KestrelOcr: something else')

        Get-TouchRuleFromProbe $quiet | Should -Be 'NONE'
        Get-TouchRuleFromProbe $echo | Should -Be 'CREDITS'
        Get-TouchRuleFromProbe @('nothing') | Should -BeNullOrEmpty
        @(Get-OcrMeasures $ocr) | Should -Be @('image=full limit=960 threads=2 loadMs=410 detectMs=520 recogniseMs=830 lines=12 pssKb=198000')
        Get-BoundsCentre '100,200,300,400' | Should -BeExactly '200 300'
        Get-BoundsCentre 'none' | Should -BeNullOrEmpty
    }
}
```

- [ ] **Step 2: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: exit code 1. In both shells Pester reports N + 7 tests, of which 7 fail, because `scripts\a71-agent.ps1` does not exist (the parser's error, and none of the helpers).

- [ ] **Step 3: Write the session script of sitting 1**

Create `scripts/a71-agent.ps1`:

```powershell
# The two sittings of sub-project 5 (design D30) on the Galaxy A71 and kestrel-api37. Sitting 1 records and needs no
# model: before the owner's step the script installs Kestrel and the fixture app, copies the models with the OCR files,
# turns test mode on, sets the owner's names and checks which accounts and apps the A71 has; the owner then installs the
# missing apps from Play while he watches, signs in to Telegram, links WhatsApp, signs the test Google account in on
# kestrel-api37, sets Waze's Home on both devices and touches the A71's screen once (about 10 to 25 minutes of his
# hands); then, without him, the script prepares the devices, runs the A71's device tests, probes the hotspot, records the
# structure of the self-chats, the pickers and SystemUI, and plays the speech checks of scripts\lib\AgentChecks.ps1
# through this computer's speakers. Sitting 2 (plan 5C's Task 10) runs the live tasks with the owner's Copilot. Each
# sitting is one call with -Sitting 1 or 2; -Step runs only the named steps, also as one text separated by commas.
# The A71's test PIN is typed only by Unlock-TestPhone (scripts\lib\Session.ps1), which alone reads the key "pin" of
# corpus\owner\a71-config.json; this script reads the file only through Read-A71Config, which leaves the key out. The
# script also needs the keys whatsappOwnName, gmailAddress, homePlace, testLocation ("<latitude>,<longitude>") and
# testStreet. It refuses every device but the A71 and kestrel-api37, keeps its results in corpus\owner (which git
# ignores), and never prints a prompt, a reply, screen text, a message, an e-mail, a notification or a word that Kestrel
# heard: the hooks answer only counts, kinds, states and hashes. Every change is registered with its restore before it is
# made, and the restores run however the sitting ends (its end, q, Ctrl+C, an error); the last lines name what could not
# be put back and how to do it by hand. The A71 stays awake during the sitting and is locked at its end. A real sitting
# runs inside the emulator's lock (process rule 9): with -LockHeld the controller holds .superpowers\sdd\emulator.lock
# and the script only checks that it exists; without it, the script takes the lock itself, waits up to 60 minutes, and
# deletes its own lock at the end. -DryRun runs every step on kestrel-api37 inside the controller's lock, which it never
# touches: it answers the owner's items itself, uses a fake configuration, plays the clips through Kestrel's stand-in
# microphone and runs the stand-in variants of the tasks with the scripted model, so that no request reaches a provider.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-agent.ps1 -Serial R58N66RVD7Z -EmulatorSerial emulator-5554 -Sitting 1 -LockHeld
#Requires -Version 7.3
param(
    [string]$Serial,
    [string]$EmulatorSerial = 'emulator-5554',
    [ValidateSet('1')][string]$Sitting = '1',
    [switch]$DryRun,
    [switch]$LockHeld,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\Oracle.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Speaker.ps1"
. "$PSScriptRoot\lib\AutoCheck.ps1"
. "$PSScriptRoot\lib\AgentChecks.ps1"
Use-KestrelToolchain

$stepNames = @{
    '1' = @('prepare', 'owner', 'setup', 'device-tests', 'hotspot', 'recordings', 'speech', 'end')
}

# The names of -Step, also as one text separated by commas, as pwsh -File passes them; an unknown name stops the script.
function Get-SessionSteps([string[]]$Step, [string[]]$Names) {
    $chosen = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $unknown = @($chosen | Where-Object { $_ -notin $Names })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($Names -join ', ')." }
    $chosen
}

# The owner's answers that the sittings need, from corpus\owner\a71-config.json through Read-A71Config, which leaves out
# the key "pin": the name that WhatsApp shows with "(You)", the address of Gmail's account on the A71, the public place
# that Waze's Home is, the test location of the A71 ("<latitude>,<longitude>", a public place a few kilometres from
# Home) and the street that Waze shows there. A missing key, or a test location of another form, throws a message that
# names only the keys; no value reaches a message.
function Get-AgentConfig([string]$Path) {
    $config = Read-A71Config -Path $Path
    $missing = @(foreach ($key in 'whatsappOwnName', 'gmailAddress', 'homePlace', 'testLocation', 'testStreet') {
            if (-not $config.PSObject.Properties[$key] -or -not "$($config.$key)".Trim()) { $key }
        })
    if ($missing) { throw "corpus\owner\a71-config.json lacks the keys $($missing -join ', '), which the sittings of sub-project 5 need." }
    if ("$($config.testLocation)" -notmatch '^-?\d{1,2}\.\d+,-?\d{1,3}\.\d+$') { throw 'The key testLocation of corpus\owner\a71-config.json must be "<latitude>,<longitude>", such as "44.8100,20.4600".' }
    $config
}

# The script's own record of what a sitting found and what sitting 2 needs (the touch branch and rule, the hotspot's
# result, whether Waze shows the test location, whether the A71 keeps its Copilot profile, the apps that the owner's
# step installed, Play's state and the browsers' versions on kestrel-api37), in a JSON file of corpus\owner. It holds no
# name, text or address.
function Read-AgentRecord([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return [pscustomobject]@{} }
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Set-AgentRecord([string]$Path, [string]$Name, $Value) {
    $record = Read-AgentRecord $Path
    $record | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
    [IO.File]::WriteAllText($Path, ($record | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
}

# The number of Google accounts in 'dumpsys account', from the type only, never a name.
function Get-GoogleAccountCount([string[]]$Dump) {
    @($Dump | Where-Object { $_ -match '^\s*Account \{.*type=com\.google\}' }).Count
}

# The first versionName in 'dumpsys package <package>', or 'none'.
function Get-VersionName([string[]]$Dump) {
    foreach ($line in $Dump) { if ($line -match '^\s*versionName=(\S+)') { return $Matches[1] } }
    'none'
}

# Whether 'pm list packages <package>' lists exactly that package.
function Test-PackageListed([string[]]$List, [string]$Package) { @($List | ForEach-Object { "$_".Trim() }) -contains "package:$Package" }

# Whether 'dumpsys connectivity' shows a Wi-Fi network that Android validated.
function Test-NetworkValidated([string[]]$Dump) {
    @($Dump | Where-Object { $_ -match 'NetworkAgentInfo' -and $_ -match 'Transports: WIFI' -and $_ -match '\bVALIDATED\b' }).Count -gt 0
}

# Whether 'dumpsys tethering' lists a Wi-Fi interface (wlan, swlan or ap) in TetheredState or LocalHotspotState (r60).
function Test-HotspotOn([string[]]$Dump) {
    @($Dump | Where-Object { $_ -match '^\s*(s?wlan\d+|ap\d+)\s+-\s+(TetheredState|LocalHotspotState)\b' }).Count -gt 0
}

# The touch rule that D19's probe calls for (plan 5B's OwnActionsTest, logcat tag KestrelProbe): CREDITS when a node
# action of Kestrel's (a click, long click, scroll, set text, focus or refused click) reached the watcher, NONE when none
# did. FIELD needs API 37 (plan 5B), so it is never chosen here. $null when the probe wrote no line.
function Get-TouchRuleFromProbe([string[]]$Lines) {
    $cases = @($Lines | Where-Object { $_ -match 'case=(\S+) .*touches=(\d+)' } | ForEach-Object {
            if ("$_" -match 'case=(\S+) .*touches=(\d+)') { [pscustomobject]@{ Case = $Matches[1]; Touches = [int]$Matches[2] } }
        })
    if (-not $cases) { return $null }
    $echoes = @($cases | Where-Object { $_.Case -in 'click', 'long-click', 'scroll', 'set-text', 'ime-focus', 'refused-click' -and $_.Touches -gt 0 })
    if ($echoes) { 'CREDITS' } else { 'NONE' }
}

# The OCR test's measures (plan 5C's OcrDeviceTest, logcat tag KestrelOcr), one short text per line, numbers only.
function Get-OcrMeasures([string[]]$Lines) {
    @($Lines | Where-Object { $_ -match 'ocr (image=\S+ limit=\d+ threads=\d+ loadMs=\d+ detectMs=\d+ recogniseMs=\d+ lines=\d+ pssKb=\d+)' } | ForEach-Object {
            if ("$_" -match 'ocr (image=\S+ limit=\d+ threads=\d+ loadMs=\d+ detectMs=\d+ recogniseMs=\d+ lines=\d+ pssKb=\d+)') { $Matches[1] }
        })
}

# The centre of the bounds that screen-has gives ('left,top,right,bottom'), as 'x y' for 'input tap', or $null.
function Get-BoundsCentre([string]$Bounds) {
    if ($Bounds -notmatch '^(-?\d+),(-?\d+),(-?\d+),(-?\d+)$') { return $null }
    "$([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) $([int](([int]$Matches[2] + [int]$Matches[4]) / 2))"
}

$Step = @(Get-SessionSteps -Step $Step -Names $stepNames[$Sitting])
if ($DryRun -and $Serial -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }
if (-not $DryRun -and -not $Serial) { throw 'Give the A71''s serial with -Serial.' }
# In a dry run, the A71's steps run on kestrel-api37 too.
if ($DryRun) { $Serial = $EmulatorSerial }
$owner = Join-Path $PSScriptRoot '..\corpus\owner'
New-Item -ItemType Directory -Force -Path $owner | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = Join-Path $owner "agent-sitting$Sitting-$(if ($DryRun) { 'dry-run-' })$stamp.txt"
$recordFile = Join-Path $owner "a71-agent-record$(if ($DryRun) { '-dry-run' }).json"
$generated = Join-Path $PSScriptRoot '..\corpus\generated'
$adb = Get-Adb
$gradleUsed = $false
# A real sitting reads the owner's answers, and checks that the test PIN is there, before any command reaches a device;
# a dry run uses a fake configuration and never opens the owner's file.
$pinConfig = if ($DryRun) { $null } else { Join-Path $owner 'a71-config.json' }
$config = if ($DryRun) {
    [pscustomobject]@{ SpeakerTargetDb = -26.0; whatsappOwnName = 'Test Owner'; gmailAddress = 'kestrel.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Test Street' }
} else {
    Get-AgentConfig $pinConfig
}
if ($pinConfig) { Send-TestPin -ConfigPath $pinConfig -CheckOnly }
$lockFile = Join-Path $PSScriptRoot '..\.superpowers\sdd\emulator.lock'
$lockTaken = $false
# "Stay awake" of each device as it was before the sitting changed it.
$stayOn = [ordered]@{}

function Get-DeviceName([string]$Device) { if ($Device -eq $EmulatorSerial) { 'kestrel-api37' } else { 'the A71' } }

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

# Runs scripts\hook.ps1 on a device and returns the answer as one text.
function Invoke-DeviceHook([string]$Device, [string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Device -Command $Command @With) -join "`n"
}

# The form that the check runner (scripts\lib\AutoCheck.ps1) and the speech checks call: the device is $Serial of the
# caller, which a step that runs them on kestrel-api37 sets for its own call.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { Invoke-DeviceHook $Serial $Command $With }

function Invoke-Device([string]$Device, [string]$Command) { Invoke-SettingsCommand -Serial $Device -Command $Command }

# One item of the owner's: this computer beeps, and the prompt says what he does and what the phone shows; Enter starts
# it, s skips it and q stops the sitting. A dry run answers $DryRunAnswer itself.
function Request-OwnerItem([string]$Prompt, [string]$DryRunAnswer = 'go') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
    $answer = "$(Read-Host $Prompt)".Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the sitting.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

# What the script has still to put back. Each restore enters the list before its change and leaves it when it worked;
# one that failed keeps its reason for the message at the end, which says how to do it by hand.
$pending = [System.Collections.Generic.List[object]]::new()

function Register-Restore([string]$What, [string]$ByHand, $Data, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Data = $Data; Action = $Action; Reason = 'the sitting ended before it' }
    $pending.Add($restore)
    $restore
}

# Runs a restore that is still pending, with its data, and keeps it pending, with the reason, when it fails.
function Complete-Restore($Restore) {
    if (-not $pending.Contains($Restore)) { return }
    try {
        & $Restore.Action $Restore.Data | Out-Null
        $pending.Remove($Restore) | Out-Null
    } catch {
        $Restore.Reason = $_.Exception.Message
    }
}

# Runs the restores at the sitting's end, newest first, each in the finally block of the one before, so that a stop such
# as Ctrl+C during one of them still runs the others.
function Invoke-Restore([object[]]$Restores, [int]$Index = 0) {
    if ($Index -ge $Restores.Count) { return }
    try {
        Complete-Restore $Restores[$Index]
    } finally {
        Invoke-Restore $Restores ($Index + 1)
    }
}

# Unlocks the A71 with the owner's test PIN through Unlock-TestPhone (scripts\lib\Session.ps1), and returns whether it
# is unlocked. An unlock that did not work stops every later unlock of the sitting. The emulator is only woken.
function Unlock-Device([string]$Device) {
    if ($Device -eq $EmulatorSerial) {
        Invoke-Device $Device 'input keyevent KEYCODE_WAKEUP' | Out-Null
        return $true
    }
    if ($script:unlockFailed) { return $false }
    $why = 'the A71 did not show its PIN pad clearly, so the script typed nothing'
    $unlocked = try { Unlock-TestPhone -Serial $Device -ConfigPath $pinConfig } catch { $why = "$($_.Exception.Message)"; $false }
    if (-not $unlocked) {
        $script:unlockFailed = $true
        Add-Result session "$why; the sitting tries no more unlocks and skips the steps that need an unlocked A71"
    }
    $unlocked
}

# "Stay awake" on the device until the sitting ends; the setting comes back at the end, also after a failure or Ctrl+C
# (process rule 15).
function Enable-StayAwake([string]$Device) {
    if ($stayOn.Contains($Device)) { return }
    $stayOn[$Device] = ((Invoke-Device $Device 'settings get global stay_on_while_plugged_in') -join '').Trim()
    Invoke-Device $Device 'svc power stayon true' | Out-Null
}

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer.
function Get-VoiceState([string]$Device) {
    try { ConvertFrom-VoiceState (Invoke-DeviceHook $Device voice-state) } catch { $null }
}

# Waits until Kestrel is in the mode, with its voice session open, or closed for 'off'. Returns whether it is.
function Wait-Mode([string]$Device, [string]$Mode, [double]$Seconds) {
    $session = if ($Mode -eq 'off') { 'closed' } else { 'open' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-VoiceState $Device
        if ($state -and $state.mode -eq $Mode -and $state.session -eq $session) { return $true }
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# Kestrel listening on the device, from the front, with the silent stand-in microphone ('quiet', so that no sound of the
# room reaches it during text-driven runs) or its own microphone ('own', for the speech checks). A change of the
# microphone counts from Kestrel's next start, so Kestrel turns off first. The first call per device registers the
# restores of Kestrel's mode as it was and of its own microphone.
$listening = @{}
function Start-Listening([string]$Device, [ValidateSet('quiet', 'own')][string]$Microphone) {
    if (-not $listening.ContainsKey($Device)) {
        $before = Get-VoiceState $Device
        $listening[$Device] = $null
        Register-Restore "Kestrel's own microphone on $(Get-DeviceName $Device)" "Run: scripts\hook.ps1 -Serial $Device voice-clip -Clear, then turn Kestrel off and on." @{ Device = $Device; Listened = [bool]($before -and $before.mode -ne 'off') } {
            param($Data)
            Invoke-DeviceHook $Data.Device voice-clip @{ Clear = $true } | Out-Null
            # The microphone comes back from Kestrel's next start, so a Kestrel that listened before the sitting starts again.
            if ($Data.Listened) {
                Invoke-DeviceHook $Data.Device voice @{ Send = 'off' } | Out-Null
                if (-not (Wait-Mode $Data.Device off 30)) { throw 'Kestrel did not turn off within 30 s' }
                Invoke-Device $Data.Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
                Invoke-DeviceHook $Data.Device voice @{ Send = 'toggle' } | Out-Null
                Start-Sleep -Seconds 1
                $after = Get-VoiceState $Data.Device
                if ($after -and $after.mode -eq 'paused') { Invoke-DeviceHook $Data.Device voice @{ Send = 'resume' } | Out-Null }
            }
        } | Out-Null
        if (-not $before -or $before.mode -eq 'off') {
            Register-Restore "Kestrel's mode Off on $(Get-DeviceName $Device)" "Run: scripts\hook.ps1 -Serial $Device voice -Send off" $Device {
                param($Device)
                Invoke-DeviceHook $Device voice @{ Send = 'off' } | Out-Null
            } | Out-Null
        }
    }
    if ($listening[$Device] -eq $Microphone -and (Wait-Mode $Device listening 1)) { return }
    $state = Get-VoiceState $Device
    if ($state -and $state.mode -ne 'off') {
        Invoke-DeviceHook $Device voice @{ Send = 'off' } | Out-Null
        if (-not (Wait-Mode $Device off 30)) { throw "Kestrel on $(Get-DeviceName $Device) did not turn off within 30 s." }
    }
    Invoke-DeviceHook $Device voice-clip $(if ($Microphone -eq 'quiet') { @{ Quiet = $true } } else { @{ Clear = $true } }) | Out-Null
    $listening[$Device] = $Microphone
    Invoke-Device $Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Invoke-DeviceHook $Device voice @{ Send = 'toggle' } | Out-Null
    Start-Sleep -Seconds 1
    $after = Get-VoiceState $Device
    if ($after -and $after.mode -eq 'paused') { Invoke-DeviceHook $Device voice @{ Send = 'resume' } | Out-Null }
    if (-not (Wait-Mode $Device listening 120)) { throw "Kestrel on $(Get-DeviceName $Device) did not start listening within 120 s." }
    Invoke-Device $Device 'input keyevent KEYCODE_HOME' | Out-Null
}

# Kestrel's runtime permissions for the tasks (design D29: granted with pm grant, revoked at the end): the photos and
# videos, and the location. Only those that Kestrel did not have are granted, and only those are revoked again.
function Grant-AgentPermissions([string]$Device) {
    $wanted = @('android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO', 'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION')
    $had = @(Get-GrantedPermissions -Dump (Invoke-Device $Device 'dumpsys package dev.kestrel') -Permissions $wanted)
    $grant = @($wanted | Where-Object { $_ -notin $had })
    if (-not $grant) { return }
    Register-Restore "Kestrel's runtime permissions on $(Get-DeviceName $Device)" "Settings > Apps > Kestrel > Permissions: set Photos and videos and Location to Don't allow." @{ Device = $Device; Grant = $grant } {
        param($Data)
        foreach ($permission in $Data.Grant) { Invoke-Device $Data.Device "pm revoke dev.kestrel $permission" | Out-Null }
    } | Out-Null
    foreach ($permission in $grant) { Invoke-Device $Device "pm grant dev.kestrel $permission" | Out-Null }
}

# Notification access for Kestrel's listener (design D9), revoked again at the end of each sitting.
function Enable-NotificationAccess([string]$Device) {
    $component = 'dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener'
    Register-Restore "notification access for Kestrel on $(Get-DeviceName $Device)" 'Settings > Notifications > Device and app notifications (or Notification access): turn Kestrel off.' $Device {
        param($Device)
        Invoke-Device $Device 'cmd notification disallow_listener dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener' | Out-Null
    } | Out-Null
    Invoke-Device $Device "cmd notification allow_listener $component" | Out-Null
}

# The owner's names for test mode's self-chat rules (design D18), through agent-settings; ownerEmails stays empty, so
# that test mode blocks every e-mail of the agent's (review B3-N2).
function Set-OwnerNames([string]$Device) {
    $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-settings @{ WhatsappOwnName = "$($config.whatsappOwnName)" })
    if ($answer['whatsappOwnName'] -ne 'set' -or $answer['ownerEmails'] -ne '0') { throw "agent-settings did not take the owner's name on $(Get-DeviceName $Device)." }
}

# Test mode on (design D18; process rule 10), checked through the hook state.
function Enable-TestMode([string]$Device) {
    Invoke-DeviceHook $Device test-mode @{ On = 'true' } | Out-Null
    if ((Invoke-DeviceHook $Device state) -notmatch '(^|\s)testMode=on(\s|$)') { throw "Test mode is not on on $(Get-DeviceName $Device)." }
}

# Whether the package is installed on the device.
function Test-Installed([string]$Device, [string]$Package) { Test-PackageListed @(Invoke-Device $Device "pm list packages $Package") $Package }

# Starts an app's launcher activity, or opens a URI in it.
function Open-App([string]$Device, [string]$Package) { Invoke-Device $Device "monkey -p $Package -c android.intent.category.LAUNCHER 1" | Out-Null }

# Taps the element whose label is $Label, compared on the device (hook screen-tap, the script's own action; it refuses
# Kestrel's own app and the phone apps). Returns whether it tapped, waiting up to $Seconds for the element.
function Invoke-ScreenTap([string]$Device, [string]$Label, [int]$Seconds = 10) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-tap @{ Label = $Label }))['tapped'] -eq 'true') {
            Start-Sleep -Seconds 2
            return $true
        }
        Start-Sleep -Seconds 1
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# The count of reachable elements that match the filters (hook H3, screen-has), and only the count.
function Get-ScreenCount([string]$Device, [hashtable]$With) { [int](ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has $With))['count'] }

# Installs an app from its Play page on the device: the script opens the page and taps Install (the script's own tap),
# and waits up to 5 minutes for the package. Returns whether it is installed.
function Install-FromPlay([string]$Device, [string]$Package) {
    Invoke-Device $Device "am start -a android.intent.action.VIEW -d market://details?id=$Package" | Out-Null
    Start-Sleep -Seconds 5
    Invoke-ScreenTap $Device 'Install' 20 | Out-Null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalMinutes -lt 5) {
        if (Test-Installed $Device $Package) { return $true }
        Start-Sleep -Seconds 5
    }
    $false
}

# The shell as the A71's mock-location app (as scripts\spike-s1.ps1 does), with test providers for gps, network and
# fused at the test location, and a background job that sends a fresh fix every second (design D30 step 8; Q4). The
# restore stops the job, removes the providers and puts back the shell's mock-location mode and the location switch.
function Start-TestLocation([string]$Device) {
    $mode = 'default'
    if ("$((Invoke-Device $Device 'appops get com.android.shell android:mock_location') -join "`n")" -match '(?m)MOCK_LOCATION: (\w+)') { $mode = $Matches[1] }
    $enabled = ((Invoke-Device $Device 'cmd location is-location-enabled') -join '').Trim()
    $providers = 'gps network fused'
    $undo = "appops set com.android.shell android:mock_location allow; for p in $providers; do cmd location providers remove-test-provider `$p; done; appops set com.android.shell android:mock_location $mode; cmd location set-location-enabled $enabled"
    $restore = Register-Restore "the test location on $(Get-DeviceName $Device)" "Run: & '$adb' -s $Device shell '$undo'" @{ Device = $Device; Undo = $undo } {
        param($Data)
        if ($script:locationJob) {
            Stop-Job $script:locationJob
            Remove-Job $script:locationJob -Force
            $script:locationJob = $null
        }
        Invoke-Device $Data.Device $Data.Undo | Out-Null
    }
    Invoke-Device $Device "appops set com.android.shell android:mock_location allow; cmd location set-location-enabled true; for p in $providers; do cmd location providers add-test-provider `$p; cmd location providers set-test-provider-enabled `$p true; done" | Out-Null
    $fix = Format-TestDeviceCommand -Serial $Device -Command "for p in $providers; do cmd location providers set-test-provider-location `$p --location $($config.testLocation) --accuracy 5; done"
    $script:locationJob = Start-Job -ScriptBlock {
        while ($true) {
            & $using:adb -s $using:Device shell $using:fix 2>&1 | Out-Null
            Start-Sleep -Seconds 1
        }
    }
    $restore
}

# Saves the structure that the hook agent-tree recorded on the device (design D28: classes, view IDs, bounds, flags and
# states, and only safe labels) into corpus\owner\trees, and deletes it on the device.
function Save-AgentTree([string]$Device, [string]$Name) {
    $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-tree)
    if ($answer['tree'] -ne 'written') { throw "agent-tree wrote nothing for $Name." }
    $folder = Join-Path $owner "trees\$stamp"
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $json = (Invoke-Device $Device 'run-as dev.kestrel cat cache/agent-tree.json') -join "`n"
    Invoke-Device $Device 'run-as dev.kestrel rm -f cache/agent-tree.json' | Out-Null
    $file = Join-Path $folder "$(if ($Device -eq $EmulatorSerial) { 'emulator' } else { 'a71' })-$Name.json"
    [IO.File]::WriteAllText($file, $json, [Text.UTF8Encoding]::new($false))
    "$Name recorded ($($answer['windows']) windows, $($answer['elements']) elements)"
}

# Deletes an earlier run's results of the connected tests, so that they never count as this run's.
function Clear-TestResults([string]$Folder) {
    if (Test-Path -LiteralPath $Folder) { Remove-Item -LiteralPath $Folder -Recurse -Force }
}

# Kestrel's accessibility service on the device, which the agent and the hooks of the screen need. The accessibility
# settings are saved before the change, and a restore puts them back.
function Enable-KestrelService([string]$Device) {
    $saved = Get-AccessibilitySettings -Serial $Device
    Register-Restore "the accessibility settings of $(Get-DeviceName $Device)" 'Put back the accessibility services in Settings > Accessibility.' @{ Device = $Device; Saved = $saved } {
        param($Data)
        if (-not (Restore-AccessibilitySettings -Serial $Data.Device -Saved $Data.Saved)) { throw 'see the warning above' }
    } | Out-Null
    $service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
    $enabled = if ($saved.Contains('enabled_accessibility_services')) { "$($saved['enabled_accessibility_services'])" } else { 'null' }
    $services = @(@($enabled -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service }) + $service
    if (-not (Set-SecureSetting -Serial $Device -Name 'enabled_accessibility_services' -Value ($services -join ':'))) { throw 'Could not turn on Kestrel''s accessibility service.' }
    if (-not (Set-SecureSetting -Serial $Device -Name 'accessibility_enabled' -Value '1')) { throw 'Could not turn on accessibility.' }
    # Android binds the service a moment after the setting.
    Start-Sleep -Seconds 3
}
# The A71's (or, in a dry run, the emulator's) device: the step functions below take it from here.
function Get-A71 { $Serial }

# Before sitting 1, without the owner (design D30): both builds, the models with the OCR files, test mode, the owner's
# names, and what the owner's step needs to know.
function Invoke-PrepareStep {
    $script:gradleUsed = $true
    foreach ($device in @($EmulatorSerial, (Get-A71)) | Select-Object -Unique) {
        & "$PSScriptRoot\install.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 failed on $(Get-DeviceName $device)." }
        & "$PSScriptRoot\install.ps1" -Serial $device -TestApp
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 -TestApp failed on $(Get-DeviceName $device)." }
        Invoke-Device $device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        & "$PSScriptRoot\push-models.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\push-models.ps1 failed on $(Get-DeviceName $device)." }
        Enable-TestMode $device
        Set-OwnerNames $device
        Add-Result prepare "$(Get-DeviceName $device): Kestrel and the fixture app installed, the models with the OCR files copied, test mode on, the owner's name set"
    }
    $a71 = Get-A71
    $accounts = Get-GoogleAccountCount @(Invoke-Device $a71 'dumpsys account')
    $missing = @(foreach ($package in 'com.whatsapp', 'com.waze', 'com.google.android.gm') { if (-not (Test-Installed $a71 $package)) { $package } })
    Set-AgentRecord $recordFile 'missingA71' $missing
    Add-Result prepare "the A71 has $accounts Google accounts; Telegram $(if (Test-Installed $a71 'org.telegram.messenger') { 'is' } else { 'is not' }) installed; missing from Play: $(if ($missing) { $missing -join ', ' } else { 'none' })"
    $play = (Test-Installed $EmulatorSerial 'com.android.vending') -and -not (Test-PackageListed @(Invoke-Device $EmulatorSerial 'pm list packages -d com.android.vending') 'com.android.vending')
    Set-AgentRecord $recordFile 'emulatorPlay' $play
    $chrome = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.android.chrome')
    $webView = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.google.android.webview')
    Set-AgentRecord $recordFile 'chromeVersion' $chrome
    Set-AgentRecord $recordFile 'webViewVersion' $webView
    Add-Result prepare "kestrel-api37: the Play Store $(if ($play) { 'is there' } else { 'is missing or disabled, so Waze and the e-mail task run on the A71 only' }); Chrome $chrome, Android System WebView $webView"
}

# The real finger touch of design D19 (step 7): a task whose scripted model only waits runs on :testapp's fixtures, and
# only a cancel that comes after the prompt counts. Branch A: the touch watcher saw the finger. Otherwise branch B, where
# a tap on a fixture button must cancel the task through OwnerSignal. The branch goes to agent-touch-source and the record.
function Invoke-FingerCheck([string]$Device) {
    $source = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-touch-source)
    $branch = $null
    $skipped = $false
    foreach ($try in 'A', 'B') {
        # Each try runs on its own branch, so that a cancel by OwnerSignal (branch B) never counts as the watcher's.
        Invoke-DeviceHook $Device agent-touch-source @{ Rule = $source['rule']; Branch = $try } | Out-Null
        Invoke-Device $Device 'am start -n dev.kestrel.testapp/.FixturesActivity' | Out-Null
        $prompt = if ($try -eq 'A') {
            'Item 5, about 2 minutes: press Enter when you are at the A71. A few seconds later this computer beeps; then touch the middle of the A71''s screen once. The bar shows "Cancelled" within a second.'
        } else {
            'The watcher did not see that touch. Press Enter; when this computer beeps, tap the button "Views" on the A71''s screen once. The bar shows "Cancelled" within a second.'
        }
        if ((Request-OwnerItem $prompt) -eq 'skip') {
            $skipped = $true
            break
        }
        # The task starts after his Enter, with a script of its own, so that only a touch after the prompt can end it.
        Install-AgentScript -Name 'finger.json' -Replies (Get-SpeechScript wait)
        $tag = New-CheckTag
        Invoke-DeviceHook $Device voice @{ Send = 'heard'; Text = 'Kestrel, wait on this screen.'; Named = $true; Tag = $tag } | Out-Null
        $state = Wait-AgentState -Tag $tag -Seconds 30 -Until { param($State) $State['phase'] -eq 'running' }
        if (-not $state -or $state['phase'] -ne 'running') { throw 'the fixture task did not start' }
        if ($DryRun) {
            # The emulator's touch through Android's input system stands in for the finger.
            $size = "$(Invoke-Device $Device 'wm size')"
            if ($size -match '(\d+)x(\d+)') { Invoke-Device $Device "input tap $([int]$Matches[1] / 2) $([int]$Matches[2] / 2)" | Out-Null }
        } else {
            try { [console]::Beep(1320, 400) } catch { Write-Verbose 'This console cannot beep.' }
        }
        $result = Wait-AgentTask -Tag $tag -Seconds 15
        if ($result -and $result['state'] -eq 'done' -and $result['end'] -eq 'CANCELLED') {
            $branch = $try
            break
        }
        Stop-SpeechTask $tag
    }
    Invoke-DeviceHook $Device agent-script @{ Clear = $true } | Out-Null
    if (-not $branch) {
        Invoke-DeviceHook $Device agent-touch-source @{ Rule = $source['rule']; Branch = 'A' } | Out-Null
        return $(if ($skipped) { 'skipped' } else { 'none' })
    }
    Set-AgentRecord $recordFile 'touchBranch' $branch
    $branch
}

# Runs one item of the owner's step on its own: a failure is recorded and the next item comes, so that his later items,
# the finger check and "You may leave now" still come; only his q stops the step.
function Invoke-OwnerItem([string]$Name, [scriptblock]$Body) {
    try {
        & $Body
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the sitting.') { throw }
        Add-Result owner "${Name}: failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
    }
}

# The owner's step at the start of sitting 1 (design D30 steps 2 to 7): the A71 unlocked and awake, the missing apps
# from Play while he watches, Telegram's sign-in, WhatsApp's link, the test Google account on kestrel-api37 with Play's
# auto-update off, Waze's first screens and Home on both devices, and the real finger touch.
function Invoke-OwnerStep {
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    $record = Read-AgentRecord $recordFile
    $missing = @($record.missingA71 | Where-Object { $_ })
    Invoke-OwnerItem 'Item 1' {
        $installed = @()
        if ($missing -and (Request-OwnerItem "Item 1, 0 to 6 minutes: the script now installs $($missing -join ', ') on the A71 from Play with your account; watch the A71, and press Enter to start (s skips)." 'skip') -eq 'go') {
            foreach ($package in $missing) {
                if (Install-FromPlay $a71 $package) { $installed += $package }
            }
            Add-Result owner "installed from Play on the A71: $(if ($installed) { $installed -join ', ' } else { 'none' })"
        }
        Set-AgentRecord $recordFile 'installedA71' $installed
    }
    Invoke-OwnerItem 'Item 2' {
        if ((Request-OwnerItem 'Item 2, about 4 minutes: on the A71, open Telegram and sign in with your number and the code that Telegram shows on the S23+ (never "Send SMS"), and your Two-Step Verification password if it asks. Press Enter when Telegram shows its chat list (s skips).' 'skip') -eq 'go') {
            Open-App $a71 'org.telegram.messenger'
            Start-Sleep -Seconds 5
            $front = (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 screen-has @{ Package = 'org.telegram.messenger' }))['app'] -eq 'org.telegram.messenger'
            $login = Get-ScreenCount $a71 @{ Package = 'org.telegram.messenger'; Contains = 'phone number' }
            Add-Result owner "Telegram on the A71: $(if ($front -and $login -eq 0) { 'signed in' } else { 'not signed in' })"
        }
    }
    Invoke-OwnerItem 'Item 3' {
        if ((Request-OwnerItem 'Item 3, about 3 minutes: on the S23+, open WhatsApp > Linked devices > Link a device, and scan the QR code that WhatsApp shows on the A71 (open WhatsApp on the A71 first). Press Enter when the A71 shows WhatsApp''s chat list (s skips).' 'skip') -eq 'go') {
            Open-App $a71 'com.whatsapp'
            Start-Sleep -Seconds 5
            $front = (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 screen-has @{ Package = 'com.whatsapp' }))['app'] -eq 'com.whatsapp'
            $login = Get-ScreenCount $a71 @{ Package = 'com.whatsapp'; Contains = 'link with phone number' }
            Add-Result owner "WhatsApp on the A71: $(if ($front -and $login -eq 0) { 'linked' } else { 'not linked' })"
        }
    }
    Invoke-OwnerItem 'Item 3b' {
        if ($record.emulatorPlay -and (Request-OwnerItem 'Item 3b, 2 to 5 minutes: in the emulator''s window on this computer, use the mouse: Settings > Passwords and accounts > Add account > Google, and sign in with the test Google account. Then open the Play Store, tap your profile picture, Settings > Network preferences > Auto-update apps, choose "Don''t auto-update apps" and tap Done. Press Enter when that is done (s skips).' 'skip') -eq 'go') {
            if ((Get-GoogleAccountCount @(Invoke-Device $EmulatorSerial 'dumpsys account')) -lt 1) {
                Add-Result owner 'kestrel-api37 has no Google account, so Waze and the e-mail task run on the A71 only'
            } else {
                # Recorded first, so that sitting 2 removes the account also when an install below fails.
                Set-AgentRecord $recordFile 'emulatorAccount' $true
                $installedEmulator = @(foreach ($package in 'com.waze', 'com.google.android.gm') {
                        if (-not (Test-Installed $EmulatorSerial $package) -and (Install-FromPlay $EmulatorSerial $package)) { $package }
                    })
                Set-AgentRecord $recordFile 'installedEmulator' $installedEmulator
                # Gmail's notifications stay off on the emulator, so that no mail of the test account shows in the shade.
                if (Test-Installed $EmulatorSerial 'com.google.android.gm') { Invoke-Device $EmulatorSerial 'pm revoke com.google.android.gm android.permission.POST_NOTIFICATIONS' | Out-Null }
                # The Play Store stays disabled until the end of sitting 2, so that it never updates Chrome or WebView.
                Invoke-Device $EmulatorSerial 'pm disable-user --user 0 com.android.vending' | Out-Null
                Set-AgentRecord $recordFile 'playDisabled' $true
                Add-Result owner "kestrel-api37: the test account is signed in; installed $(if ($installedEmulator) { $installedEmulator -join ', ' } else { 'nothing' }); Gmail's notifications off; the Play Store disabled until the end of sitting 2"
            }
        }
    }
    Invoke-OwnerItem 'Item 4' {
        if ((Request-OwnerItem "Item 4, about 4 minutes: open Waze on the A71, pass its first screens, allow the location, and set Home to $($config.homePlace); then do the same in the emulator's window with the mouse, if Waze is there. Press Enter when both show Waze's map (s skips)." 'skip') -eq 'go') {
            Add-Result owner "Waze's Home set by the owner on the A71$(if (Test-Installed $EmulatorSerial 'com.waze') { ' and kestrel-api37' })"
            foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) { Invoke-Device $device 'am force-stop com.waze' | Out-Null }
        }
    }
    Start-Listening $a71 'quiet'
    $branch = Invoke-FingerCheck $a71
    Add-Result owner "the real finger: $(switch ($branch) { 'A' { 'branch A, the touch watcher saw it' } 'B' { 'branch B, the watcher did not see it, and OwnerSignal cancelled the task' } 'skipped' { 'skipped' } default { 'neither the watcher nor OwnerSignal cancelled the task; branch A kept, a finding for the controller' } })"
    if (-not $DryRun) {
        try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
        Write-Host 'You may leave now. The rest of sitting 1 runs without you for about 1 3/4 hours; please do not touch the A71 or the emulator.'
    }
}

# Design D30 step 8: the runtime permissions, notification access, the Waze check of the A71's test location, and
# Firefox past its first screens.
function Invoke-SetupStep {
    $a71 = Get-A71
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        Grant-AgentPermissions $device
        Enable-NotificationAccess $device
        Invoke-Device $device 'am start -a android.intent.action.VIEW -d https://en.wikipedia.org -p org.mozilla.firefox' | Out-Null
        Start-Sleep -Seconds 8
        $toolbar = Get-ScreenCount $device @{ Package = 'org.mozilla.firefox'; ViewId = 'toolbar' }
        Invoke-Device $device 'am force-stop org.mozilla.firefox' | Out-Null
        Add-Result setup "$(Get-DeviceName $device): runtime permissions granted, notification access on, Firefox $(if ($toolbar -gt 0) { 'shows its toolbar' } else { 'shows no toolbar, maybe its first screens; finish them by hand before sitting 2' })"
    }
    if ($DryRun -or -not (Test-Installed $a71 'com.waze')) {
        Set-AgentRecord $recordFile 'wazeA71' 'skipped'
        Add-Result setup "the A71's test location: $(if ($DryRun) { 'not checked in a dry run' } else { 'Waze is not installed, so the A71''s Waze runs are skipped' })"
        return
    }
    $restore = Start-TestLocation $a71
    try {
        Open-App $a71 'com.waze'
        Start-Sleep -Seconds 20
        $shown = Get-ScreenCount $a71 @{ Package = 'com.waze'; Contains = "$($config.testStreet)" }
    } finally {
        Invoke-Device $a71 'am force-stop com.waze' | Out-Null
        Complete-Restore $restore
    }
    Set-AgentRecord $recordFile 'wazeA71' $(if ($shown -gt 0) { 'ok' } else { 'skipped' })
    Add-Result setup "the A71's test location: $(if ($shown -gt 0) { 'Waze shows it' } else { 'Waze does not show it (it may read Play services'' own location), so the A71''s Waze runs are skipped; the controller asks the owner' })"
}

# Design D30 step 9: the A71's device tests that may run there (D27), which may delete Kestrel's data there (Q6); then
# what a run may have deleted is set again.
function Invoke-DeviceTestsStep {
    $script:gradleUsed = $true
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    Add-Result device-tests 'the device tests may delete Kestrel''s profile, memory and routines on the A71 (the owner agreed, Q6)'
    $classes = 'dev.kestrel.app.agent.eyes.OcrDeviceTest', 'dev.kestrel.app.screen.ScreenCaptureTest', 'dev.kestrel.app.screen.OwnActionsTest',
    'dev.kestrel.app.screen.SystemUiFilterTest', 'dev.kestrel.app.agent.AgentLocationTest', 'dev.kestrel.app.agent.NotificationListenerTest'
    $folder = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    Clear-TestResults $folder
    # AgentLocationTest grants Kestrel the location and cannot take it back; this grant's restore revokes what Kestrel did
    # not have before the sitting.
    Grant-AgentPermissions $a71
    Invoke-Device $a71 'logcat -c' | Out-Null
    & "$PSScriptRoot\connected-test.ps1" -Serial $a71 ':app:connectedDebugAndroidTest' "-Pandroid.testInstrumentationRunnerArguments.class=$($classes -join ',')" '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    $failed = @(Get-UnpassedTests $folder)
    foreach ($class in $classes) {
        $name = ($class -split '\.')[-1]
        $verdict = if (@($failed | Where-Object { $_ -like "$name#*" })) { "failed: $(@($failed | Where-Object { $_ -like "$name#*" }) -join ', ')" } elseif (Test-ClassPassed $folder $name) { 'passed' } else { 'not run' }
        Add-Result device-tests "${name}: $verdict"
    }
    Clear-TestResults $folder
    $log = @(Invoke-Device $a71 'logcat -d -s KestrelOcr:I KestrelProbe:I KestrelSystemUi:I')
    [IO.File]::WriteAllLines((Join-Path $owner "agent-logcat-$(if ($DryRun) { 'dry-run-' })$stamp.txt"), [string[]]$log, [Text.UTF8Encoding]::new($false))
    foreach ($measure in Get-OcrMeasures $log) { Add-Result device-tests "OCR: $measure" }
    $rule = Get-TouchRuleFromProbe @($log | Where-Object { $_ -match 'KestrelProbe' })
    if ($rule) { Set-AgentRecord $recordFile 'touchRule' $rule }
    $record = Read-AgentRecord $recordFile
    $touchRule = if ($record.touchRule) { $record.touchRule } else { (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 agent-touch-source))['rule'] }
    $branch = if ($record.touchBranch) { $record.touchBranch } else { 'A' }
    # connected-test.ps1 ends with 'am force-stop', and a stopped app gets no hook broadcast, so Kestrel starts again.
    Invoke-Device $a71 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Start-Sleep -Seconds 3
    Invoke-DeviceHook $a71 agent-touch-source @{ Rule = $touchRule; Branch = $branch } | Out-Null
    Enable-TestMode $a71
    Set-OwnerNames $a71
    # The clip microphone ended with Kestrel's process, so Kestrel listens again with the silent one (design D30).
    $listening[$a71] = $null
    Start-Listening $a71 'quiet'
    & "$PSScriptRoot\push-models.ps1" -Serial $a71
    if ($LASTEXITCODE -ne 0) { throw 'scripts\push-models.ps1 failed on the A71.' }
    $copilot = (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 provider-standin))['copilot']
    Set-AgentRecord $recordFile 'copilotA71' ($copilot -ne '0')
    Add-Result device-tests "the probe's touch rule: $(if ($rule) { $rule } else { 'no probe lines' }); set again: rule $touchRule, branch $branch, test mode, the owner's name, the models; Kestrel's Copilot profile on the A71: $(if ($copilot -ne '0') { 'kept' } else { 'gone, so sitting 2''s owner step signs in again' })"
}

# Turns the hotspot switch of the page in front on or off with an input tap at its bounds (the script's own tap): the
# first switch whose label is one of the hotspot's names (One UI's "Mobile Hotspot", AOSP's "Wi-Fi hotspot" and "Use
# Wi-Fi hotspot"). Returns whether it tapped.
function Set-HotspotSwitch([string]$Device, [bool]$On) {
    $state = if ($On) { 'unchecked' } else { 'checked' }
    foreach ($label in 'Mobile Hotspot', 'Wi-Fi hotspot', 'Use Wi-Fi hotspot', 'Hotspot') {
        $found = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = $label; ClassName = 'Switch'; State = $state })
        $centre = Get-BoundsCentre $found['bounds']
        if ([int]$found['count'] -gt 0 -and $centre) {
            Invoke-Device $Device "input tap $centre" | Out-Null
            Start-Sleep -Seconds 10
            return $true
        }
    }
    $false
}

# Waits up to $Seconds for a validated Wi-Fi network. Returns whether there is one.
function Wait-Validated([string]$Device, [int]$Seconds = 60) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (Test-NetworkValidated @(Invoke-Device $Device 'dumpsys connectivity')) { return $true }
        Start-Sleep -Seconds 3
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# Turns on One UI's "Wi-Fi sharing" on the page of Mobile Hotspot (Q7 allows it), so that the hotspot can share the
# Wi-Fi network, and registers its restore. Returns whether it turned it on.
function Enable-WifiSharing([string]$Device) {
    Invoke-Device $Device 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
    Start-Sleep -Seconds 3
    Invoke-ScreenTap $Device 'Mobile Hotspot' 5 | Out-Null
    $found = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = 'Wi-Fi sharing'; ClassName = 'Switch'; State = 'unchecked' })
    $centre = Get-BoundsCentre $found['bounds']
    if (-not ([int]$found['count'] -gt 0 -and $centre)) { return $false }
    Register-Restore "Wi-Fi sharing off on $(Get-DeviceName $Device)" 'Settings > Connections > Mobile Hotspot and Tethering > Mobile Hotspot: turn Wi-Fi sharing off.' $Device {
        param($Device)
        if (-not (Unlock-Device $Device)) { throw 'the A71 is locked' }
        Invoke-Device $Device 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
        Start-Sleep -Seconds 3
        Invoke-ScreenTap $Device 'Mobile Hotspot' 5 | Out-Null
        $now = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = 'Wi-Fi sharing'; ClassName = 'Switch'; State = 'checked' })
        $at = Get-BoundsCentre $now['bounds']
        if ([int]$now['count'] -gt 0 -and $at) {
            Invoke-Device $Device "input tap $at" | Out-Null
            Start-Sleep -Seconds 2
        }
        $off = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = 'Wi-Fi sharing'; ClassName = 'Switch'; State = 'unchecked' })
        if ([int]$off['count'] -eq 0) { throw 'Wi-Fi sharing may still be on' }
    } | Out-Null
    Invoke-Device $Device "input tap $centre" | Out-Null
    Start-Sleep -Seconds 2
    $true
}
# Design D30 step 10: the hotspot probe on the A71 (r60; review C I3). With Wi-Fi validated, the script turns the
# hotspot on through its settings page, reads the tethering state and whether the network stays validated, and turns it
# off again; when Wi-Fi dropped and the page offers "Wi-Fi sharing", which Q7 allows, it turns that on and tries once
# more. The result goes to the record: ok, or limit (the hotspot runs on the A71 are then skipped).
function Invoke-HotspotStep {
    $a71 = Get-A71
    if (-not (Wait-Validated $a71 30)) {
        Set-AgentRecord $recordFile 'hotspot' 'limit'
        Add-Result hotspot 'skipped: Wi-Fi has no validated network, so the hotspot runs on the A71 are skipped'
        return
    }
    $result = 'limit'
    $sharing = $false
    foreach ($try in 1, 2) {
        $restore = Register-Restore "the hotspot off and Wi-Fi on, on $(Get-DeviceName $a71)" 'Settings > Connections > Mobile Hotspot and Tethering: turn Mobile Hotspot off; then turn Wi-Fi on.' $a71 {
            param($Device)
            if (-not (Unlock-Device $Device)) { throw 'the A71 is locked' }
            Invoke-Device $Device 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
            Start-Sleep -Seconds 3
            Set-HotspotSwitch $Device $false | Out-Null
            # A switch that the page did not show, or a hotspot that Wi-Fi sharing keeps validated, must not count as off.
            if (Test-HotspotOn @(Invoke-Device $Device 'dumpsys tethering')) {
                Invoke-Device $Device 'cmd wifi stop-softap' | Out-Null
                Start-Sleep -Seconds 3
            }
            if (Test-HotspotOn @(Invoke-Device $Device 'dumpsys tethering')) { throw 'the hotspot is still on' }
            Invoke-Device $Device 'svc wifi enable' | Out-Null
            if (-not (Wait-Validated $Device 60)) { throw 'Wi-Fi has no validated network again' }
        }
        Invoke-Device $a71 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
        Start-Sleep -Seconds 3
        if (-not (Set-HotspotSwitch $a71 $true)) {
            Complete-Restore $restore
            Add-Result hotspot 'the hotspot page shows no switch that the script knows'
            break
        }
        $on = Test-HotspotOn @(Invoke-Device $a71 'dumpsys tethering')
        $validated = Test-NetworkValidated @(Invoke-Device $a71 'dumpsys connectivity')
        Add-Result hotspot "try ${try}: the hotspot is $(if ($on) { 'on' } else { 'off' }), the network $(if ($validated) { 'stays validated' } else { 'is not validated' })"
        Complete-Restore $restore
        if ($on -and $validated) {
            $result = 'ok'
            break
        }
        if ($try -eq 2) { break }
        if (-not (Enable-WifiSharing $a71)) { break }
        $sharing = $true
        Add-Result hotspot 'Wi-Fi sharing turned on for the second try (Q7)'
    }
    Set-AgentRecord $recordFile 'hotspot' $result
    Set-AgentRecord $recordFile 'wifiSharing' $sharing
    Add-Result hotspot "the A71's hotspot: $(if ($result -eq 'ok') { 'testable' } else { 'a limit of the device; its runs are skipped' })"
}
# Design D30 step 11: the structure of the real self-chats, the chat lists and the pickers (never an ordinary chat), and
# of SystemUI's shade and a heads-up notification, recorded through agent-tree into corpus\owner\trees.
function Invoke-RecordingsStep {
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    $notes = @()
    if (Test-Installed $a71 'org.telegram.messenger') {
        Open-App $a71 'org.telegram.messenger'
        Start-Sleep -Seconds 4
        $notes += Save-AgentTree $a71 'telegram-chats'
        if ((Invoke-ScreenTap $a71 'Search') ) {
            Invoke-Device $a71 'input text Saved\ Messages' | Out-Null
            Start-Sleep -Seconds 3
            if (Invoke-ScreenTap $a71 'Saved Messages') {
                $notes += Save-AgentTree $a71 'telegram-saved'
                $notes += "Telegram's Saved Messages: $(Invoke-DeviceHook $a71 selfchat-check)"
            }
        }
        Invoke-Device $a71 'am force-stop org.telegram.messenger' | Out-Null
        # Telegram's share picker, opened by a text share from the shell and closed with back, without sending.
        Invoke-Device $a71 "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'Kestrel test' -p org.telegram.messenger" | Out-Null
        Start-Sleep -Seconds 4
        $notes += Save-AgentTree $a71 'telegram-share'
        Invoke-Device $a71 'input keyevent KEYCODE_BACK' | Out-Null
        Invoke-Device $a71 'am force-stop org.telegram.messenger' | Out-Null
    }
    if (Test-Installed $a71 'com.whatsapp') {
        Open-App $a71 'com.whatsapp'
        Start-Sleep -Seconds 4
        $notes += Save-AgentTree $a71 'whatsapp-chats'
        if (Invoke-ScreenTap $a71 "$($config.whatsappOwnName) (You)") {
            $notes += Save-AgentTree $a71 'whatsapp-self'
            $notes += "WhatsApp's own chat: $(Invoke-DeviceHook $a71 selfchat-check)"
            Set-AgentRecord $recordFile 'whatsappSelfChat' 'recorded'
        } else {
            Set-AgentRecord $recordFile 'whatsappSelfChat' 'not shown'
            $notes += 'WhatsApp shows no row "<owner> (You)", so WhatsApp is not testable on this device'
        }
        Invoke-Device $a71 'am force-stop com.whatsapp' | Out-Null
    }
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        if (Test-Installed $device 'com.google.android.gm') {
            Invoke-Device $device "am start -a android.intent.action.SENDTO -d mailto:$($config.gmailAddress) -p com.google.android.gm" | Out-Null
            Start-Sleep -Seconds 5
            $notes += Save-AgentTree $device 'gmail-compose'
            $discarded = (Invoke-ScreenTap $device 'More options' 5) -and (Invoke-ScreenTap $device 'Discard' 5)
            if (-not $discarded) { $notes += "Gmail's draft on $(Get-DeviceName $device) may stay in Drafts; delete it by hand" }
            Invoke-Device $device 'am force-stop com.google.android.gm' | Out-Null
        }
        Invoke-Device $device 'cmd statusbar expand-notifications' | Out-Null
        Start-Sleep -Seconds 2
        $notes += Save-AgentTree $device 'systemui-shade'
        Invoke-Device $device 'cmd statusbar collapse' | Out-Null
        # The fixture app posts nothing without the notification permission, which otherwise only a device test grants.
        Invoke-Device $device 'pm grant dev.kestrel.testapp android.permission.POST_NOTIFICATIONS' | Out-Null
        Invoke-Device $device 'am start -n dev.kestrel.testapp/.NotificationActivity --ez headsUp true' | Out-Null
        Start-Sleep -Seconds 1
        $notes += Save-AgentTree $device 'systemui-heads-up'
        Invoke-Device $device 'am start -n dev.kestrel.testapp/.NotificationActivity --ez cancel true' | Out-Null
        Invoke-Device $device 'input keyevent KEYCODE_HOME' | Out-Null
    }
    foreach ($note in $notes) { Add-Result recordings $note }
}

# The gain at which the phone hears this computer's clips at the owner's level (hook H7), as plan 4D's sessions measure
# it, with Kestrel paused, so that the calibration line starts nothing.
function Measure-Gain([string]$Device) {
    $row = @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\commands.txt') | Where-Object { $_.Group -eq 'conversation' -and $_.Text -notmatch '(?i)kestrel|stop|cancel' }) | Select-Object -First 1
    $clip = Join-Path $generated "kokoro\$($row.Id).wav"
    Invoke-DeviceHook $Device voice @{ Send = 'pause' } | Out-Null
    Wait-Mode $Device paused 10 | Out-Null
    try {
        Set-SpeakerLevel -TargetDb $config.SpeakerTargetDb -Measure {
            param($Gain)
            Invoke-DeviceHook $Device voice-route | Out-Null
            Invoke-Clip -Path $clip -Gain $Gain | Out-Null
            $level = (ConvertFrom-HookPairs (Invoke-DeviceHook $Device voice-route))['frameRms']
            if ($level -match '^-?\d+(\.\d+)?$') { [double]::Parse($level, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
        }
    } finally {
        Invoke-DeviceHook $Device voice @{ Send = 'resume' } | Out-Null
        Wait-Mode $Device listening 10 | Out-Null
    }
}

# The speech checks of one device (scripts\lib\AgentChecks.ps1) in both voices, with Kestrel on its own microphone and
# :testapp in front; a dry run plays each clip's text into Kestrel's stand-in microphone instead. The fact "gate code"
# is removed again afterwards, and the scripted model too.
function Invoke-SpeechOn([string]$Serial) {
    $texts = @{}
    foreach ($row in @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\commands.txt')) + @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\checks.txt'))) { $texts[$row.Id] = $row.Text }
    Start-Listening $Serial $(if ($DryRun) { 'quiet' } else { 'own' })
    $gain = 1.0
    if (-not $DryRun) {
        $level = Measure-Gain $Serial
        Add-Result speech "$(Get-DeviceName $Serial): the clips reach the phone at $(if ($null -eq $level.LevelDb) { 'nothing' } else { "$($level.LevelDb) dBFS" }) (target $($config.SpeakerTargetDb)) with a gain of $([math]::Round($level.Gain, 2))"
        $gain = $level.Gain
        if (-not $level.InBand -and ($null -eq $level.LevelDb -or $level.LevelDb -lt $config.SpeakerTargetDb - 3)) {
            Add-Result speech "$(Get-DeviceName $Serial): skipped: the phone does not hear this computer loudly enough; turn up its volume and put its speakers next to the phone"
            return
        }
    }
    $play = if ($DryRun) { { param($Clip) Invoke-Hook voice-clip @{ Text = $Clip.Text } | Out-Null } } else { { param($Clip) Invoke-Clip -Path $Clip.Path -Gain $gain | Out-Null } }
    $passed = 0
    $run = 0
    try {
        foreach ($voice in 'kokoro', 'piper') {
            foreach ($check in Get-AgentSpeechChecks) {
                $path = Join-Path $generated "$voice\$($check.Clip).wav"
                if (-not $DryRun -and -not (Test-Path -LiteralPath $path)) {
                    Add-Result speech "$($check.Name) [$voice]: skipped: no clip $($check.Clip); make the clips with scripts\make-corpus.ps1, also with -List checks"
                    continue
                }
                Invoke-Device $Serial 'am start -n dev.kestrel.testapp/.FixturesActivity' | Out-Null
                $run++
                try {
                    $result = Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = $voice; Text = $texts[$check.Clip]; Path = $path }) -Play $play
                    if ($result.Passed) { $passed++ }
                    Add-Result speech "$(Get-DeviceName $Serial): $($result.Line)"
                } catch {
                    Add-Result speech "$(Get-DeviceName $Serial): $($check.Name) [$voice]: failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
                }
            }
        }
    } finally {
        Invoke-Hook agent-script @{ Clear = $true } | Out-Null
        $keys = (ConvertFrom-HookPairs (Invoke-Hook agent-memory @{ Remove = $true; Key = 'gate code' }))['keys']
        Add-Result speech "$(Get-DeviceName $Serial): $passed of $run speech checks passed; the fact 'gate code' removed, $keys keys left in memory"
    }
}

# Design D30 step 12: the speech checks (D28) on the A71 with the emulator's microphone off, then on kestrel-api37 with
# its host microphone on for that part only. They inform and do not gate (D27).
function Invoke-SpeechStep {
    $a71 = Get-A71
    if (-not $DryRun) {
        if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
        Set-EmulatorHostMicrophone -Serial $EmulatorSerial -On $false
        Invoke-SpeechOn $a71
        Start-Listening $a71 'quiet'
        $restore = Register-Restore 'the emulator''s host microphone off' "Run: & '$adb' -s $EmulatorSerial emu avd hostmicoff" $EmulatorSerial { param($Device) Set-EmulatorHostMicrophone -Serial $Device -On $false }
        Set-EmulatorHostMicrophone -Serial $EmulatorSerial -On $true
        try { Invoke-SpeechOn $EmulatorSerial } finally { Complete-Restore $restore }
        Start-Listening $EmulatorSerial 'quiet'
    } else {
        Invoke-SpeechOn $EmulatorSerial
    }
}

# Design D30 step 13: the restores run at the sitting's end; Play's disabled state on kestrel-api37 and :testapp on the
# A71 stay until the end of sitting 2 (review C3-M4).
function Invoke-EndStep {
    $record = Read-AgentRecord $recordFile
    Add-Result end "the record for sitting 2: touch branch $(if ($record.touchBranch) { $record.touchBranch } else { 'A' }), rule $(if ($record.touchRule) { $record.touchRule } else { 'the default' }), hotspot $(if ($record.hotspot) { $record.hotspot } else { 'not probed' }), Waze on the A71 $(if ($record.wazeA71) { $record.wazeA71 } else { 'not checked' }), WhatsApp's own chat $(if ($record.whatsappSelfChat) { $record.whatsappSelfChat } else { 'not recorded' }); the Play Store on kestrel-api37 and :testapp on the A71 stay until the end of sitting 2"
}

# Sitting 2's steps (plan 5C's Task 10).

$plans = @{
    '1' = @(
        @{ Name = 'prepare'; Minutes = 20; Title = 'the builds, the models, test mode and the owner''s names on both devices, and what the owner''s step needs'; Run = 'Invoke-PrepareStep' }
        @{ Name = 'owner'; Minutes = 20; Title = 'the owner''s step: the installs, Telegram, WhatsApp, the emulator''s test account, Waze''s Home and one real touch'; Run = 'Invoke-OwnerStep' }
        @{ Name = 'setup'; Minutes = 20; Title = 'the permissions, notification access, the A71''s test location in Waze and Firefox'; Run = 'Invoke-SetupStep' }
        @{ Name = 'device-tests'; Minutes = 25; Title = 'the A71''s device tests, then what they may have deleted set again'; Run = 'Invoke-DeviceTestsStep' }
        @{ Name = 'hotspot'; Minutes = 5; Title = 'the hotspot probe on the A71'; Run = 'Invoke-HotspotStep' }
        @{ Name = 'recordings'; Minutes = 20; Title = 'the structure of the self-chats, the pickers, Gmail''s compose screen and SystemUI'; Run = 'Invoke-RecordingsStep' }
        @{ Name = 'speech'; Minutes = 25; Title = 'the speech checks through this computer''s speakers on both devices'; Run = 'Invoke-SpeechStep' }
        @{ Name = 'end'; Minutes = 5; Title = 'the record for sitting 2, and the restores'; Run = 'Invoke-EndStep' }
    )
}

$completed = $false
$stopped = $false
try {
    # The emulator's lock first, before any command reaches a device (process rule 9); a dry run runs inside the
    # controller's lock and never touches it.
    if (-not $DryRun) {
        if ($LockHeld) {
            if (-not (Test-Path -LiteralPath $lockFile)) { throw '-LockHeld says that the controller holds the emulator''s lock, but .superpowers\sdd\emulator.lock does not exist.' }
        } else {
            $deadline = (Get-Date).AddMinutes(60)
            while (-not (Enter-EmulatorLock -Path $lockFile -Owner "a71-agent sitting $Sitting")) {
                if ((Get-Date) -gt $deadline) { throw 'The emulator lock still exists after 60 minutes.' }
                Write-Host 'Another task holds the emulator''s lock (.superpowers\sdd\emulator.lock); the sitting tries again in 2 minutes. If the controller holds it for this sitting, stop with Ctrl+C and start again with -LockHeld.'
                Start-Sleep -Seconds 120
            }
            $lockTaken = $true
        }
    }
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        # Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this
        # script never passes.
        Assert-TestDevice -Serial $device
    }
    if ($KestrelTestDevices[$EmulatorSerial].Pattern -ne 'kestrel-api37') { throw "$EmulatorSerial is not kestrel-api37." }
    if (-not $DryRun -and $KestrelTestDevices[$Serial].Pattern -ne 'SM-A715F*') { throw "$Serial is not the Galaxy A71." }
    Add-Result session "sitting $Sitting started$(if ($DryRun) { ' as a dry run on kestrel-api37' })"
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        Enable-StayAwake $device
        Enable-KestrelService $device
    }
    if (-not (Unlock-Device $Serial)) { Add-Result session 'the A71 stays locked' }
    foreach ($entry in $plans[$Sitting]) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        Write-Host "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title)."
        try {
            & $entry.Run
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the sitting.') { throw }
            # The scripts' and hooks' errors name only steps, files, keys and classes, never a value of the owner's.
            Add-Result $entry.Name "failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
} finally {
    # Newest first, so that a later change is undone before the one that it built on. A stop such as Ctrl+C that comes
    # while this block runs ends it at once, after the finally blocks inside it, so "Stay awake", the A71's lock, the
    # emulator's lock and the warnings come in a finally block of their own (process rule 15).
    $reversed = @($pending)
    [array]::Reverse($reversed)
    try {
        Invoke-Restore $reversed
    } finally {
        foreach ($device in @($stayOn.Keys)) {
            $command = if ($stayOn[$device] -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $($stayOn[$device])" }
            try { Invoke-Device $device $command | Out-Null } catch { Write-Warning "Could not put back ""Stay awake"" on $device. Run: & '$adb' -s $device shell $command" }
        }
        # The owner's rule (process rule 15): the sitting leaves the A71 locked, so that its screen does not burn in.
        if (-not $DryRun) {
            try { Invoke-Device $Serial 'input keyevent KEYCODE_SLEEP' | Out-Null } catch { Write-Warning "Could not lock $Serial. Press its power button." }
        }
        if ($lockTaken) { Remove-Item -LiteralPath $lockFile -ErrorAction SilentlyContinue }
        foreach ($restore in $pending) { Write-Warning "Could not put back $($restore.What) ($($restore.Reason)). $($restore.ByHand)" }
    }
    if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
    if ($gradleUsed) { & "$PSScriptRoot\gradle.ps1" --stop | Out-Null }
    if (Test-Path -LiteralPath $resultsFile) { Write-Host "The results are in $((Resolve-Path -LiteralPath $resultsFile).Path)." }
    if (-not ($completed -or $stopped)) {
        if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
        exit 1
    }
}
if ($stopped) { exit 1 }
```

- [ ] **Step 4: Run the script tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: exit code 0, "The script tests and checks passed."; in both shells Pester reports N + 7 tests, all passed, and PSScriptAnalyzer 0 problems.

- [ ] **Step 5: Run sitting 1 as a dry run on the emulator**

The controller holds the emulator lock (process rule 9); never create, wait for or delete it. The dry run uses the fake configuration in the script, never `corpus\owner\a71-config.json`, and calls no model; it builds and installs Kestrel and `:testapp`, so it takes about an hour.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\a71-agent.ps1 -EmulatorSerial emulator-5554 -Sitting 1 -DryRun; "exit: $LASTEXITCODE"`
Expected: `exit: 0` and no warning "Could not put back …" at the end. The results file `corpus\owner\agent-sitting1-dry-run-<time>.txt` has, in this order: `session: sitting 1 started as a dry run on kestrel-api37`; `prepare: kestrel-api37: Kestrel and the fixture app installed, the models with the OCR files copied, test mode on, the owner's name set` and the two lines of the accounts and of the Play Store with Chrome's and WebView's versions; `owner:` lines for the items that a dry run skips and `owner: the real finger: branch A, the touch watcher saw it` (the shell's `input tap` stands in for the finger); `setup:` with the permissions, notification access, Firefox's toolbar, and `the A71's test location: not checked in a dry run`; `device-tests:` one line per class (each `passed`; a `failed` or `not run` line is a finding for the controller, not a failure of this step), the OCR measures, and the touch rule (`CREDITS` on kestrel-api37 when its probe saw echoes); `hotspot:` the try or tries and `the A71's hotspot: testable` or `a limit of the device` (both are valid on the emulator, decision 8); `recordings:` the trees that were written (`systemui-shade`, `systemui-heads-up`, and `gmail-compose` when Gmail is installed) with their counts; `speech:` 30 lines `kestrel-api37: <check> [kokoro|piper]: …` and `kestrel-api37: <n> of 30 speech checks passed; the fact 'gate code' removed, <k> keys left in memory`; `end:` the record's summary. Count the `check=fail` lines of the step `speech` and write their checks into the report; they are findings, not failures of this step. Afterwards `scripts\hook.ps1 -Serial emulator-5554 agent-live` answers `live=off`, `scripts\hook.ps1 -Serial emulator-5554 voice-state` answers `mode=off` when Kestrel was off before, and `adb -s emulator-5554 shell settings get global stay_on_while_plugged_in` answers what it answered before.

- [ ] **Step 6: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 7: Commit**

```powershell
git add scripts/a71-agent.ps1 scripts/tests/A71Agent.Tests.ps1
git commit -m "test(agent): add sitting 1 of the A71 session script: the owner's step, the recordings, the device tests and the speech checks" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 10: `scripts\a71-agent.ps1`, sitting 2

**Files:**
- Modify: `scripts/a71-agent.ps1`, `scripts/tests/A71Agent.Tests.ps1`

**Interfaces:**
- Consumes: Task 9's script and its helpers (its **Interfaces**); Task 8's `agent-live -On true|false`, `agent-live -Model <id>` (`model=listed|unlisted`) and `task-check.ps1 -Live -Model -Name -Mail`; from plan 5B's `task-check.ps1` also that it grants Kestrel only the media permissions that the device's Android version knows (`READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` on the A71's Android 13, `READ_MEDIA_VISUAL_USER_SELECTED` only from Android 14), and that it answers "confirm" only to a confirmation of the kind that the task's own table entry names (a send to the self-chat for `telegram-late` and `telegram-photos`) and "cancel" to every other confirmation, among them any deletion, with no parameter from this script; plan 5B's `scripts\task-check.ps1 -Serial -Task -Runs -Script -Results` with its tasks `telegram-late`, `telegram-photos`, `read-email`, `hotspot`, `waze-home`, `firefox-wiki`, `inject-01` to `inject-14`, the stand-ins `stand-in-messenger-late`, `stand-in-messenger-photos`, `stand-in-hotspot`, `stand-in-firefox`, `stand-in-read` with their scripts `scripts\tasks\<stand-in>.json` and `scripts\tasks\hostile.json` for the injection fixtures, and its run lines `task=<name> run=<i> result=<pass|fail|skipped> end=<EndReason|none> check=<…> steps=<n> workMs=<n> credits=<x.xx> model=<id|none> in=<n> out=<n> cacheRead=<n> cacheWrite=<n> reason=<word>`, which it appends to `-Results` with `Add-SessionResult` (`<time><tab>task-check<tab><line>`); plan 5B's hooks `agent-cache -Field <anthropicMarks|responsesExplicit|chatCopilotMarks> -On`, `agent-answer`, `agent-state`, `agent-task -Result`, `provider-standin`, `provider-delete`; plan 5C's `:testapp` `OcrCanvasActivity` (a drawn button "Show details", whose tap shows "Details shown" in its status line, a real text view; Task 3) and the Safety page's screenshot approvals with a "Revoke" button each and the text "No app may send screenshots without asking." when none is left (Task 6).
- Produces: `scripts\a71-agent.ps1 -Sitting 2 [-CompareModel <id>]` with the steps `owner`, `prepare`, `a71-runs`, `emulator-runs`, `compare`, `look-closer`, `injection`, `cache` and `end`; the configuration key `emulatorGmailAddress` (optional); the record's key `sitting2Credits`; the results `corpus\owner\agent-sitting2-[dry-run-]<time>.txt` with task-check.ps1's run lines and the lines `compare: task=<t> model=<m> passed=<n> of <m> steps=<x> seconds=<x> credits=<x>` and `cache: model=<m> in=<n> cacheRead=<n> share=<x> %`. Task 12 reads them for the spec and the README.

Design D30's steps 14 to 23. The owner's step (14) is Kestrel's Copilot sign-in, which he does by hand on Kestrel's page "Providers and profiles", which the script opens (on the emulator with the mouse, and on the A71 only when sitting 1's device tests deleted its profile); the restore that deletes the emulator's profile through `provider-delete` is registered before it (review C I6). The preparation (16) installs the reviewed fix, `:testapp` again on the A71 when it is missing (its removal is registered for the sitting's end, review C3-M4), sets again test mode, the owner's name, the permissions, notification access, the silent clip microphone, the touch rule and branch of the record and Wi-Fi sharing when the hotspot needed it, checks Gmail's account on the A71 (the compose screen of a `mailto:` to `gmailAddress` must show the address twice, in its To field and as the sender; a count only), chooses the test variants' first names from those that have no row in the owner's Telegram (review C3-M5), and opens the live sessions: `agent-live -On true` on both devices with its restore, and `KESTREL_AGENT_SESSION` in the script's own process, removed in its `finally`. The runs (17 to 19, and 21, the injection fixtures that "done when" needs, D32 item 50) go through `task-check.ps1 -Live`, 3 runs each, the skips by sitting 1's record (the A71's hotspot when it is a limit, Waze when it did not show the test location, the e-mail when Gmail shows another account), Waze on the A71 with the test location of Task 9; the comparison runs the emulator's three tasks again with `-CompareModel` (D32 item 35, default `gemini-3.8-flash`: plan 4D's providers run passed GPT-6 Luna, GPT-6.1 Terra and Gemini 3.8 Flash on both devices and got 404 for Claude Haiku 5.5) and writes a summary per task and model of the emulator's runs only; a model that the owner's account does not list (`agent-live -Model`) is refused with a clear sentence, in the owner's step while he can still stop the sitting, and in the step `compare`. `look_closer` (20) runs on the OCR fixture of both devices, twice: the screenshot question, when it comes (a model with images, D20), is answered "yes" the first time and "always for this app" the second, any other question "cancel", and the approval is revoked again on the Safety page (a registered restore). The cache record (22) gives each model's share of cached input tokens and, for a model with explicit fields and a share of 0, two Firefox runs with those fields off. The end (23) runs the restores (the emulator's profile, the live sessions, the Play Store enabled again, `:testapp` removed from the A71, the permissions, notification access, the microphone, Kestrel's mode), removes the emulator's test Google account through Settings (or says how by hand) and the apps that sitting 1 installed there, and compares Chrome's and WebView's versions with those of sitting 1. The sitting stops when its runs' AI Credits reach 150 (Q5), counted also over calls with `-Step` (the record's `sitting2Credits`, which the step `owner` sets to 0) and with the credits of a live task that the script stopped; the owner's `q` stops it as before. A task whose setup or check throws is recorded as failed, and its step goes on with the next task (D27). A dry run signs nothing in, opens no live session and runs each task's stand-in once with its scripted model (Waze has none and is skipped), so that no request reaches a provider.

Counts: Pester N + 4 in each shell (`A71Agent.Tests.ps1`).

- [ ] **Step 1: Write the failing tests of the new helpers**

In `scripts/tests/A71Agent.Tests.ps1`, replace:

```powershell
        'Test-PackageListed', 'Test-NetworkValidated', 'Test-HotspotOn', 'Get-TouchRuleFromProbe', 'Get-OcrMeasures', 'Get-BoundsCentre')
```

with:

```powershell
        'Test-PackageListed', 'Test-NetworkValidated', 'Test-HotspotOn', 'Get-TouchRuleFromProbe', 'Get-OcrMeasures', 'Get-BoundsCentre',
        'ConvertFrom-TaskLine', 'Get-CreditTotal', 'Select-TestName', 'Get-CacheShares', 'Get-CacheFields', 'Get-StandInTask', 'Format-Number', 'Get-RunSummary',
        'Get-SittingRuns')
```

In `scripts/tests/A71Agent.Tests.ps1`, add before its last closing brace:

```powershell
    It 'reads task-check.ps1''s run lines from the results, and adds up their AI Credits' {
        $lines = @(
            "2026-12-01 10:00:00`ta71-runs`ttelegram-late on the A71, 3 runs"
            "2026-12-01 10:03:00`ttask-check`ttask=telegram-late run=1 result=pass end=FINISHED check=pass steps=9 workMs=61000 credits=1.25 model=gpt-6-luna in=42000 out=900 cacheRead=30000 cacheWrite=0 reason=ok"
            "2026-12-01 10:06:00`ttask-check`ttask=telegram-late run=2 result=fail end=STUCK check=fail steps=14 workMs=95000 credits=2.10 model=gpt-6-luna in=60000 out=1200 cacheRead=0 cacheWrite=0 reason=end"
        )
        $runs = @($lines | ForEach-Object { ConvertFrom-TaskLine $_ } | Where-Object { $_ })

        $runs.Count | Should -Be 2
        $runs[1]['end'] | Should -Be 'STUCK'
        Get-CreditTotal $runs | Should -Be 3.35
        Get-CreditTotal @() | Should -Be 0

        # Each run keeps the device of the line before it, so that the comparison reads only the emulator's runs.
        $resultsFile = Join-Path $TestDrive 'results.txt'
        [IO.File]::WriteAllLines($resultsFile, [string[]]($lines + @(
                    "2026-12-01 10:10:00`temulator-runs`thotspot on kestrel-api37, 3 runs"
                    "2026-12-01 10:12:00`ttask-check`ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=5 workMs=30000 credits=0.50 model=gpt-6-luna in=20000 out=500 cacheRead=0 cacheWrite=0 reason=ok")))
        $read = @(Get-SittingRuns)
        $read.Count | Should -Be 3
        @($read | ForEach-Object { $_['device'] }) | Should -Be @('the A71', 'the A71', 'kestrel-api37')
    }

    It 'chooses a test name that has no row in the owner''s Telegram, and none when every name has one' {
        Select-TestName 'Ana' @{ Ana = 0; Mira = 1 } | Should -Be 'Ana'
        Select-TestName 'Ana' @{ Ana = 1; Mira = 1; Zora = 0 } | Should -Be 'Zora'
        Select-TestName 'John' @{ John = 2 } @('Mira') | Should -Be 'Zora'
        Select-TestName 'Ana' @{ Ana = 1; Mira = 1; Zora = 1; Ivo = 1; Teo = 1 } | Should -BeNullOrEmpty
    }

    It 'gives each model''s share of cached input, the fields to switch off, and a summary per task and model' {
        $runs = @(
            [ordered]@{ task = 'firefox-wiki'; result = 'pass'; steps = '6'; workMs = '40000'; credits = '0.80'; model = 'gpt-6-luna'; in = '30000'; cacheRead = '12000' }
            [ordered]@{ task = 'firefox-wiki'; result = 'fail'; steps = '10'; workMs = '80000'; credits = '1.20'; model = 'gpt-6-luna'; in = '50000'; cacheRead = '20000' }
            [ordered]@{ task = 'firefox-wiki'; result = 'pass'; steps = '7'; workMs = '50000'; credits = '1.00'; model = 'claude-haiku-5.5'; in = '40000'; cacheRead = '0' }
            [ordered]@{ task = 'hotspot'; result = 'skipped'; steps = '0'; workMs = '0'; credits = '0.00'; model = 'none'; in = '0'; cacheRead = '0' }
        )

        $shares = @(Get-CacheShares $runs)
        $shares.Count | Should -Be 2
        $shares[0].Share | Should -Be 40
        $shares[1].Share | Should -Be 0
        Get-CacheFields 'gpt-6-luna' | Should -Be @('responsesExplicit')
        Get-CacheFields 'claude-haiku-5.5' | Should -Be @('chatCopilotMarks', 'anthropicMarks')
        @(Get-CacheFields 'gemini-3.8-flash').Count | Should -Be 0
        @(Get-RunSummary $runs) | Should -Be @(
            'task=firefox-wiki model=gpt-6-luna passed=1 of 2 steps=8 seconds=60 credits=2'
            'task=firefox-wiki model=claude-haiku-5.5 passed=1 of 1 steps=7 seconds=50 credits=1'
            'task=hotspot model=none passed=0 of 0 steps=0 seconds=0 credits=0')
        Format-Number 1.26 1 | Should -Be '1.3'
    }

    It 'runs a stand-in with its scripted model in a dry run, and has none for Waze' {
        (Get-StandInTask 'telegram-late').Task | Should -Be 'stand-in-messenger-late'
        (Get-StandInTask 'firefox-wiki').Script | Should -Be 'tasks\stand-in-firefox.json'
        (Get-StandInTask 'inject-07').Script | Should -Be 'tasks\hostile.json'
        (Get-StandInTask 'inject-07').Task | Should -Be 'inject-07'
        Get-StandInTask 'waze-home' | Should -BeNullOrEmpty
    }
```

- [ ] **Step 2: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: exit code 1. In both shells Pester reports N + 4 tests, of which 5 fail: `parses the script, and finds every helper` (12 of 21 helpers) and the four new tests, because the script lacks `ConvertFrom-TaskLine`, `Get-CreditTotal`, `Select-TestName`, `Get-CacheShares`, `Get-CacheFields`, `Get-StandInTask`, `Format-Number`, `Get-RunSummary` and `Get-SittingRuns`.

- [ ] **Step 3: Add sitting 2 to the script**

In `scripts/a71-agent.ps1`, replace:

```powershell
# through this computer's speakers. Sitting 2 (plan 5C's Task 10) runs the live tasks with the owner's Copilot. Each
# sitting is one call with -Sitting 1 or 2; -Step runs only the named steps, also as one text separated by commas.
```

with:

```powershell
# through this computer's speakers. Sitting 2 calls the owner's Copilot: the owner signs Kestrel in to Copilot on
# kestrel-api37 (and on the A71 when sitting 1's device tests deleted its profile; 3 to 6 minutes of his hands), and then,
# without him, the script prepares both devices again, opens the devices' live sessions (hook agent-live) and runs
# scripts\task-check.ps1 -Live for the six tasks of design D3 on the A71 and the emulator's tasks on kestrel-api37, 3 runs
# each, the comparison with -CompareModel, look_closer on the OCR fixture, the 14 injection fixtures and the cache record;
# it stops when the sitting's AI Credits reach 150. Each sitting is one call with -Sitting 1 or 2; -Step runs only the
# named steps, also as one text separated by commas.
```

In `scripts/a71-agent.ps1`, replace:

```powershell
# testStreet. It refuses every device but the A71 and kestrel-api37, keeps its results in corpus\owner (which git
```

with:

```powershell
# testStreet; sitting 2 also reads emulatorGmailAddress, the test account's address on kestrel-api37, for the e-mail
# task there, which it skips without it. It refuses every device but the A71 and kestrel-api37, keeps its results in
# corpus\owner (which git
```

In `scripts/a71-agent.ps1`, replace:

```powershell
    [ValidateSet('1')][string]$Sitting = '1',
```

with:

```powershell
    [ValidateSet('1', '2')][string]$Sitting = '1',
    # The lightweight model of sitting 2's comparison (design D32 item 35), as Copilot names it. Plan 4D's providers run
    # passed GPT-6 Luna, GPT-6.1 Terra and Gemini 3.8 Flash on both devices and got 404 for Claude Haiku 5.5.
    [string]$CompareModel = 'gemini-3.8-flash',
```

In `scripts/a71-agent.ps1`, replace:

```powershell
    '1' = @('prepare', 'owner', 'setup', 'device-tests', 'hotspot', 'recordings', 'speech', 'end')
}
```

with:

```powershell
    '1' = @('prepare', 'owner', 'setup', 'device-tests', 'hotspot', 'recordings', 'speech', 'end')
    '2' = @('owner', 'prepare', 'a71-runs', 'emulator-runs', 'compare', 'look-closer', 'injection', 'cache', 'end')
}
```

In `scripts/a71-agent.ps1`, replace:

```powershell
$Step = @(Get-SessionSteps -Step $Step -Names $stepNames[$Sitting])
```

with:

```powershell
# A run's line of scripts\task-check.ps1 in the results file ('<time><tab><step><tab>task=<name> run=<i> result=...'), as
# an ordered table, or $null for another line.
function ConvertFrom-TaskLine([string]$Line) {
    $text = ("$Line" -split "`t")[-1]
    if ($text -notmatch '^task=\S+ run=\d+ ') { return $null }
    ConvertFrom-HookPairs $text
}

# The AI Credits of the runs' lines, in the invariant culture.
function Get-CreditTotal([object[]]$Runs) {
    $total = 0.0
    foreach ($run in $Runs) {
        if ($run -and "$($run['credits'])" -match '^\d+(\.\d+)?$') { $total += [double]::Parse($run['credits'], [Globalization.CultureInfo]::InvariantCulture) }
    }
    $total
}

# The first name for a test variant (design D30 step 16; review C3-M5): $Default when the owner's Telegram chat list has
# no row with it ($Rows: name to the count that screen-has found), else the first of Mira, Zora, Ivo and Teo that has no
# row and is not in $Taken; $null when every name has a row.
function Select-TestName([string]$Default, [hashtable]$Rows, [string[]]$Taken = @()) {
    foreach ($name in @($Default, 'Mira', 'Zora', 'Ivo', 'Teo')) {
        if ($name -notin $Taken -and [int]$Rows[$name] -eq 0) { return $name }
    }
    $null
}

# The share of input tokens that each model read from the cache (design D5), from the runs' lines: one text per model,
# with the model's ID, its tokens and the share in percent.
function Get-CacheShares([object[]]$Runs) {
    $models = [ordered]@{}
    foreach ($run in $Runs) {
        if (-not $run -or "$($run['model'])" -in '', 'none') { continue }
        $model = "$($run['model'])"
        if (-not $models.Contains($model)) { $models[$model] = [pscustomobject]@{ Model = $model; Input = 0L; CacheRead = 0L } }
        $models[$model].Input += [long]$run['in']
        $models[$model].CacheRead += [long]$run['cacheRead']
    }
    foreach ($entry in $models.Values) {
        $share = if ($entry.Input -gt 0) { [math]::Round(100.0 * $entry.CacheRead / $entry.Input, 1) } else { 0 }
        [pscustomobject]@{ Model = $entry.Model; Input = $entry.Input; CacheRead = $entry.CacheRead; Share = $share }
    }
}

# The cache fields of agent-cache that a Copilot model uses (design D5): the explicit fields of /responses for OpenAI's
# models, the markers of /chat/completions and /messages for Claude, and none for Gemini, which caches only implicitly.
function Get-CacheFields([string]$Model) {
    if ($Model -match '^(gpt|o\d)') { return @('responsesExplicit') }
    if ($Model -match '^claude') { return @('chatCopilotMarks', 'anthropicMarks') }
    @()
}

# The task of scripts\task-check.ps1 that a dry run runs instead of a live task, with its script; $null when the live
# task has no stand-in, as Waze.
function Get-StandInTask([string]$Task) {
    $map = @{
        'telegram-late' = 'stand-in-messenger-late'; 'telegram-photos' = 'stand-in-messenger-photos'; 'read-email' = 'stand-in-read'
        'hotspot' = 'stand-in-hotspot'; 'firefox-wiki' = 'stand-in-firefox'
    }
    if ($Task -match '^inject-\d{2}$') { return [pscustomobject]@{ Task = $Task; Script = 'tasks\hostile.json' } }
    if (-not $map.ContainsKey($Task)) { return $null }
    [pscustomobject]@{ Task = $map[$Task]; Script = "tasks\$($map[$Task]).json" }
}

# A number with at most $Digits decimals, in the invariant culture, for the results.
function Format-Number([double]$Value, [int]$Digits = 1) { [math]::Round($Value, $Digits).ToString([Globalization.CultureInfo]::InvariantCulture) }

# One line per task and model of the runs, for design D32 item 35's comparison: the runs that passed of those that ran,
# the mean steps and working time, and the AI Credits in all.
function Get-RunSummary([object[]]$Runs) {
    $groups = [ordered]@{}
    foreach ($run in $Runs) {
        if (-not $run) { continue }
        $key = "$($run['task']) $($run['model'])"
        if (-not $groups.Contains($key)) { $groups[$key] = [System.Collections.Generic.List[object]]::new() }
        $groups[$key].Add($run)
    }
    foreach ($list in $groups.Values) {
        $ran = @($list | Where-Object { $_['result'] -ne 'skipped' })
        $passed = @($list | Where-Object { $_['result'] -eq 'pass' }).Count
        $steps = if ($ran) { (@($ran | ForEach-Object { [double]$_['steps'] }) | Measure-Object -Average).Average } else { 0 }
        $seconds = if ($ran) { (@($ran | ForEach-Object { [double]$_['workMs'] / 1000 }) | Measure-Object -Average).Average } else { 0 }
        "task=$($list[0]['task']) model=$($list[0]['model']) passed=$passed of $($ran.Count) steps=$(Format-Number $steps) seconds=$(Format-Number $seconds) credits=$(Format-Number (Get-CreditTotal $list) 2)"
    }
}

$Step = @(Get-SessionSteps -Step $Step -Names $stepNames[$Sitting])
```

In `scripts/a71-agent.ps1`, replace:

```powershell
    [pscustomobject]@{ SpeakerTargetDb = -26.0; whatsappOwnName = 'Test Owner'; gmailAddress = 'kestrel.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Test Street' }
```

with:

```powershell
    [pscustomobject]@{ SpeakerTargetDb = -26.0; whatsappOwnName = 'Test Owner'; gmailAddress = 'kestrel.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Test Street'; emulatorGmailAddress = 'kestrel.test@example.com' }
```

In `scripts/a71-agent.ps1`, replace:

```powershell
# "Stay awake" of each device as it was before the sitting changed it.
$stayOn = [ordered]@{}
```

with:

```powershell
# "Stay awake" of each device as it was before the sitting changed it.
$stayOn = [ordered]@{}
# Sitting 2: the AI Credits that its runs spent, and its ceiling (Q5), at which the sitting stops; the first names of
# the test variants (design D30 step 16); and whether Gmail on the A71 shows the address of gmailAddress.
$credits = 0.0
$creditCeiling = 150
$ceilingMessage = 'The runs reached the sitting''s ceiling of 150 AI Credits.'
$testNames = @{ Ana = 'Ana'; John = 'John' }
$gmailChecked = $false
# A call with -Step after a stop counts on from the AI Credits that the earlier calls of this sitting 2 kept in the
# record (Q5); the step owner, with which a sitting 2 starts, sets them to 0.
$savedCredits = (Read-AgentRecord $recordFile).sitting2Credits
if ($Sitting -eq '2' -and $null -ne $savedCredits) { $credits = [double]$savedCredits }
$compareRefused = "The owner's Copilot account does not list the model $CompareModel, so the comparison does not run; give -CompareModel one of the models that Kestrel's page ""Providers and profiles"" shows for the Copilot profile."
```

In `scripts/a71-agent.ps1`, replace:

```powershell
# Sitting 2's steps (plan 5C's Task 10).
```

with:

```powershell
# Sitting 2's steps (design D30 steps 14 to 23).

# Runs scripts\task-check.ps1 for a task on a device: -Live in a real sitting, or the task's stand-in with its scripted
# model in a dry run (one run), so that no request reaches a provider. The runs' lines go into this sitting's results
# file, from which they are read back; their AI Credits are added, and the sitting stops at its ceiling. Returns the
# runs' lines as tables.
function Invoke-TaskRuns([string]$StepName, [string]$Device, [string]$Task, [int]$Runs = 3, [hashtable]$With = @{}) {
    $arguments = @{ Serial = $Device; Task = $Task; Runs = $Runs; Results = $resultsFile } + $With
    if ($DryRun) {
        $standIn = Get-StandInTask $Task
        if (-not $standIn) {
            Add-Result $StepName "$Task on $(Get-DeviceName $Device): skipped in a dry run, because it has no stand-in"
            return @()
        }
        $arguments.Task = $standIn.Task
        $arguments.Script = Join-Path $PSScriptRoot $standIn.Script
        $arguments.Runs = 1
        $arguments.Remove('Mail')
    } else {
        $arguments.Live = $true
    }
    Add-Result $StepName "$($arguments.Task) on $(Get-DeviceName $Device), $($arguments.Runs) runs"
    $before = @(Get-Content -LiteralPath $resultsFile).Count
    # A task whose setup or check fails is recorded, and the step goes on with its next task (design D27).
    try {
        & "$PSScriptRoot\task-check.ps1" @arguments | Out-Host
    } catch {
        Add-Result $StepName "$($arguments.Task) on $(Get-DeviceName $Device): failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
    }
    $runs = @(Get-Content -LiteralPath $resultsFile | Select-Object -Skip $before | ForEach-Object { ConvertFrom-TaskLine $_ } | Where-Object { $_ })
    $script:credits += Get-CreditTotal $runs
    Set-AgentRecord $recordFile 'sitting2Credits' $script:credits
    Add-Result $StepName "$($arguments.Task) on $(Get-DeviceName $Device): $(@($runs | Where-Object { $_['result'] -eq 'pass' }).Count) of $($runs.Count) runs passed; the sitting's AI Credits so far: $(Format-Number $script:credits 2)"
    if ($script:credits -ge $creditCeiling) { throw $ceilingMessage }
    $runs
}

# The runs' lines of this sitting so far, read from its results file, each with the device of the line
# '<task> on <device>, <n> runs' that Invoke-TaskRuns writes before them.
function Get-SittingRuns {
    $device = 'none'
    @(foreach ($line in Get-Content -LiteralPath $resultsFile) {
            if ("$line" -match "`t\S+ on (the A71|kestrel-api37), \d+ runs$") {
                $device = $Matches[1]
                continue
            }
            $run = ConvertFrom-TaskLine $line
            if ($run) {
                $run['device'] = $device
                $run
            }
        })
}

# Whether the default profile on kestrel-api37 lists the comparison's model in the model list that the owner's
# Copilot account sent at the sign-in (hook agent-live -Model).
function Test-CompareModel {
    (ConvertFrom-HookPairs (Invoke-DeviceHook $EmulatorSerial agent-live @{ Model = $CompareModel }))['model'] -eq 'listed'
}

# Install-AgentScript (scripts\lib\AgentChecks.ps1), which installs on the caller's $Serial, on the device $Serial.
function Install-AgentScriptOn([string]$Serial, [string]$Name, [object[]]$Replies) {
    Write-Verbose "The scripted model goes to $Serial."
    Install-AgentScript -Name $Name -Replies $Replies
}

# Waits for the end of a live task that the step sent with its tag, at most $Seconds: the screenshot question (FREE) gets
# $Answer through agent-answer, and any other question "cancel". Returns the task's last answer and how often the
# screenshot question came; a task that still runs at the end is stopped.
function Wait-LiveTask([string]$Serial, [string]$Tag, [string]$Answer, [int]$Seconds = 600) {
    $asked = 0
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $Seconds) {
        $result = try { ConvertFrom-AgentTaskResult (Invoke-DeviceHook $Serial agent-task @{ Result = $true; Tag = $Tag }) } catch { $null }
        if ($result -and $result['state'] -eq 'done') { return [pscustomobject]@{ Result = $result; Asked = $asked; Stopped = $null } }
        $state = try { ConvertFrom-HookPairs (Invoke-DeviceHook $Serial agent-state @{ Tag = $Tag }) } catch { $null }
        if ($state -and $state['asking'] -eq 'free') {
            $asked++
            Invoke-DeviceHook $Serial agent-answer @{ Tag = $Tag; Answer = $Answer } | Out-Null
        } elseif ($state -and $state['asking'] -notin '', 'none') {
            Invoke-DeviceHook $Serial agent-answer @{ Tag = $Tag; Answer = 'cancel' } | Out-Null
        }
        Start-Sleep -Seconds 1
    }
    Stop-SpeechTask $Tag
    # A task that the step stopped spent AI Credits too, which count towards the sitting's ceiling (Q5).
    $stopped = $null
    for ($try = 0; $try -lt 10 -and -not $stopped; $try++) {
        Start-Sleep -Seconds 1
        $last = try { ConvertFrom-AgentTaskResult (Invoke-DeviceHook $Serial agent-task @{ Result = $true; Tag = $Tag }) } catch { $null }
        if ($last -and $last['state'] -eq 'done') { $stopped = $last }
    }
    [pscustomobject]@{ Result = $null; Asked = $asked; Stopped = $stopped }
}

# Revokes every screenshot approval on Kestrel's Safety page (design D20, D26) with the script's own taps on its
# "Revoke" buttons, scrolling down to the list. Returns whether the page then says that no app has one.
function Revoke-ScreenshotApprovals([string]$Device) {
    Invoke-Device $Device 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Device $Device 'am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE SAFETY' | Out-Null
    Start-Sleep -Seconds 3
    for ($try = 0; $try -lt 15; $try++) {
        $found = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Package = 'dev.kestrel'; Label = 'Revoke' })
        $centre = Get-BoundsCentre $found['bounds']
        if ([int]$found['count'] -gt 0 -and $centre -and $found['app'] -eq 'dev.kestrel') {
            Invoke-Device $Device "input tap $centre" | Out-Null
            Start-Sleep -Seconds 2
        } elseif ((Get-ScreenCount $Device @{ Package = 'dev.kestrel'; Label = 'No app may send screenshots without asking.' }) -gt 0) {
            return $true
        } else {
            Invoke-DeviceHook $Device scroll @{ Direction = 'down' } | Out-Null
            Start-Sleep -Seconds 1
        }
    }
    $false
}

# Design D30 step 14: the owner signs Kestrel in to Copilot on kestrel-api37, and on the A71 when sitting 1's device
# tests deleted its profile there. The restore that deletes the emulator's Copilot profile through the hook
# provider-delete is registered first, so that every way in which the sitting ends deletes it (review C I6).
function Invoke-OwnerStep2 {
    Register-Restore 'the owner''s Copilot profile on kestrel-api37' 'On kestrel-api37, the emulator''s window, use the mouse: Kestrel''s settings > Providers and profiles > the Copilot profile > Delete profile > Delete.' $EmulatorSerial {
        param($Device)
        Invoke-Device $Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Start-Sleep -Seconds 3
        $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device provider-delete)
        if ($answer['copilot'] -ne '0') { throw 'a Copilot profile is left' }
        Add-Result end 'the owner''s Copilot profile on kestrel-api37 is deleted'
    } | Out-Null
    # A new sitting 2 counts its AI Credits from 0 (Q5).
    $script:credits = 0.0
    Set-AgentRecord $recordFile 'sitting2Credits' $script:credits
    $a71 = Get-A71
    $items = @(
        [pscustomobject]@{ Device = $EmulatorSerial; Prompt = 'Item 1, about 3 minutes: in the emulator''s window on this computer, Kestrel''s page "Providers and profiles" is open. With the mouse, click "Add a profile", "GitHub Copilot" and "Sign in"; open github.com/login/device in this computer''s browser, enter the code that the page shows and approve; when the page shows the models, turn on "Make default" and click "Save". Press Enter when the page shows the profile (s skips).' }
    )
    # The A71's own answer, not sitting 1's record, which lacks it when that sitting's device tests stopped early; when
    # Kestrel on the A71 does not answer, the owner is asked too.
    $copilotA71 = if ($a71 -ne $EmulatorSerial) {
        try {
            Invoke-Device $a71 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
            Start-Sleep -Seconds 3
            (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 provider-standin))['copilot']
        } catch { $null }
    }
    if ($a71 -ne $EmulatorSerial -and $copilotA71 -in '0', $null) {
        $items += [pscustomobject]@{ Device = $a71; Prompt = 'Item 2, about 3 minutes: on the A71, Kestrel''s page "Providers and profiles" is open. Tap "Add a profile", "GitHub Copilot", "Sign in", "Copy" and "Open the page"; in Chrome, sign in to GitHub if it asks, paste the code and approve; when Kestrel''s page shows the models, turn on "Make default" and tap "Save". Press Enter when the page shows the profile (s skips).' }
    }
    foreach ($item in $items) {
        $name = Get-DeviceName $item.Device
        if ($DryRun) {
            Add-Result owner "${name}: no Copilot sign-in in a dry run, which runs the stand-in variants with the scripted model"
            continue
        }
        if (-not (Unlock-Device $item.Device)) { throw "$name is locked, and the sitting could not unlock it" }
        Invoke-Device $item.Device 'am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE PROVIDERS' | Out-Null
        $prompt = $item.Prompt
        $kind = $null
        for ($try = 1; $try -le 3 -and $kind -ne 'copilot'; $try++) {
            if ((Request-OwnerItem $prompt) -eq 'skip') { break }
            $kind = (ConvertFrom-HookPairs (Invoke-DeviceHook $item.Device provider-standin))['profile']
            $prompt = "The default profile of $name is $kind, not a Copilot profile. Finish the sign-in on Kestrel's page, turn on ""Make default"" and save, then press Enter (s skips)."
        }
        Add-Result owner "${name}: $(if ($kind -eq 'copilot') { 'the default profile is a Copilot profile' } elseif ($kind) { "the default profile is $kind, so its live runs fail" } else { 'the Copilot sign-in skipped, so its live runs fail' })"
    }
    # The comparison's model is checked while the owner is here, so that he can choose another one (design D32 item 35).
    if (-not $DryRun -and -not (Test-CompareModel)) {
        Add-Result owner $compareRefused
        Request-OwnerItem "$compareRefused Press q to stop the sitting and start it again with another -CompareModel, or Enter to go on without the comparison." | Out-Null
    }
    if (-not $DryRun) {
        try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
        Write-Host 'You may leave now. The rest of sitting 2 runs without you for about 3 1/2 to 4 hours; please do not touch the A71 or the emulator.'
    }
}

# Design D30 step 16: the reviewed fix installed on both devices, :testapp on the A71 again when it is missing, and
# everything that sitting 1's restores or a device test put back set again and checked through the hooks; Gmail's
# account on the A71 checked against gmailAddress (a count only), the test variants' first names chosen, and the live
# sessions opened (agent-live on the devices, KESTREL_AGENT_SESSION in this process) until the sitting's end.
function Invoke-PrepareStep2 {
    $script:gradleUsed = $true
    $a71 = Get-A71
    $record = Read-AgentRecord $recordFile
    if ($record.playDisabled) {
        Register-Restore 'the Play Store on kestrel-api37' "Run: & '$adb' -s $EmulatorSerial shell pm enable com.android.vending" $EmulatorSerial {
            param($Device)
            Invoke-Device $Device 'pm enable com.android.vending' | Out-Null
        } | Out-Null
    }
    if (-not $DryRun -and ($record.emulatorAccount -or @($record.installedEmulator | Where-Object { $_ }).Count -gt 0)) {
        # Registered after the Play Store's restore, so that it runs before it and before the accessibility settings come
        # back, which screen-tap needs; it runs however the sitting ends (design D30 step 23).
        Register-Restore 'the test Google account and the apps of sitting 1 on kestrel-api37' 'On kestrel-api37, the emulator''s window: Settings > Passwords and accounts > the test account > Remove account; then uninstall Waze and Gmail.' $record {
            param($Record)
            $address = if ($config.PSObject.Properties['emulatorGmailAddress']) { "$($config.emulatorGmailAddress)" } else { '' }
            if ($Record.emulatorAccount) {
                Invoke-Device $EmulatorSerial 'am start -a android.settings.SYNC_SETTINGS' | Out-Null
                Start-Sleep -Seconds 3
                if ($address -and (Invoke-ScreenTap $EmulatorSerial $address) -and (Invoke-ScreenTap $EmulatorSerial 'Remove account') -and (Invoke-ScreenTap $EmulatorSerial 'Remove account')) { Start-Sleep -Seconds 5 }
            }
            foreach ($package in @($Record.installedEmulator | Where-Object { $_ })) {
                if (Test-Installed $EmulatorSerial $package) { Invoke-Device $EmulatorSerial "pm uninstall $package" | Out-Null }
            }
            $left = Get-GoogleAccountCount @(Invoke-Device $EmulatorSerial 'dumpsys account')
            Add-Result end "kestrel-api37 has $left Google accounts; the apps of sitting 1 are uninstalled"
            if ($left -gt 0) { throw "$left Google accounts are left" }
        } | Out-Null
    }
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        & "$PSScriptRoot\install.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 failed on $(Get-DeviceName $device)." }
    }
    if (-not $DryRun) {
        if (-not (Test-Installed $a71 'dev.kestrel.testapp')) {
            & "$PSScriptRoot\install.ps1" -Serial $a71 -TestApp
            if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 -TestApp failed on the A71.' }
        }
        # :testapp goes from the A71 at the end of sitting 2 (review C3-M4).
        Register-Restore 'the removal of :testapp from the A71' "Run: & '$adb' -s $a71 uninstall dev.kestrel.testapp" $a71 {
            param($Device)
            if (Test-Installed $Device 'dev.kestrel.testapp') { Invoke-Device $Device 'pm uninstall dev.kestrel.testapp' | Out-Null }
        } | Out-Null
    }
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        Invoke-Device $device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Start-Sleep -Seconds 3
        Enable-TestMode $device
        Set-OwnerNames $device
        Grant-AgentPermissions $device
        Enable-NotificationAccess $device
        Start-Listening $device 'quiet'
        if (-not $DryRun) {
            Register-Restore "the live session on $(Get-DeviceName $device)" "Run: scripts\hook.ps1 -Serial $device agent-live -On false" $device {
                param($Device)
                if ((Invoke-DeviceHook $Device agent-live @{ On = 'false' }) -notmatch '(^|\s)live=off(\s|$)') { throw 'the live session is still open' }
            } | Out-Null
            if ((Invoke-DeviceHook $device agent-live @{ On = 'true' }) -notmatch '(^|\s)live=on(\s|$)') { throw "The live session on $(Get-DeviceName $device) did not open." }
        }
    }
    if (-not $DryRun) { $env:KESTREL_AGENT_SESSION = "sitting 2, $stamp" }
    $rule = if ($record.touchRule) { $record.touchRule } else { (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 agent-touch-source))['rule'] }
    $branch = if ($record.touchBranch) { $record.touchBranch } else { 'A' }
    Invoke-DeviceHook $a71 agent-touch-source @{ Rule = $rule; Branch = $branch } | Out-Null
    if ($record.hotspot -eq 'ok' -and $record.wifiSharing) { Enable-WifiSharing $a71 | Out-Null }
    if (Test-Installed $a71 'com.google.android.gm') {
        Invoke-Device $a71 "am start -a android.intent.action.SENDTO -d mailto:$($config.gmailAddress) -p com.google.android.gm" | Out-Null
        Start-Sleep -Seconds 5
        # The compose screen shows the address in its To field, and once more as its sender when Gmail's account is that
        # address; only the count leaves the phone.
        $script:gmailChecked = (Get-ScreenCount $a71 @{ Package = 'com.google.android.gm'; Contains = "$($config.gmailAddress)" }) -gt 1
        if (-not ((Invoke-ScreenTap $a71 'More options' 5) -and (Invoke-ScreenTap $a71 'Discard' 5))) { Add-Result prepare 'Gmail''s check left a draft on the A71; delete it by hand' }
        Invoke-Device $a71 'am force-stop com.google.android.gm' | Out-Null
    }
    if (Test-Installed $a71 'org.telegram.messenger') {
        Open-App $a71 'org.telegram.messenger'
        Start-Sleep -Seconds 4
        $rows = @{}
        foreach ($name in 'Ana', 'John', 'Mira', 'Zora', 'Ivo', 'Teo') { $rows[$name] = Get-ScreenCount $a71 @{ Package = 'org.telegram.messenger'; Contains = $name } }
        Invoke-Device $a71 'am force-stop org.telegram.messenger' | Out-Null
        $testNames.Ana = Select-TestName 'Ana' $rows
        $testNames.John = Select-TestName 'John' $rows @($testNames.Ana)
    }
    Add-Result prepare "both devices: the fix installed, test mode on, the owner's name, the permissions, notification access and the silent microphone set, the live sessions $(if ($DryRun) { 'not opened in a dry run' } else { 'open' }); the A71: touch rule $rule, branch $branch; Gmail $(if ($gmailChecked) { 'shows the address of gmailAddress' } else { 'does not show the address of gmailAddress, so the e-mail runs on the A71 are skipped' }); the test variants use the names $($testNames.Ana) and $($testNames.John)"
}

# Design D30 step 17: the live D3 runs on the A71 (D29), 3 runs each.
function Invoke-A71RunsStep {
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    $record = Read-AgentRecord $recordFile
    foreach ($task in @(@{ Task = 'telegram-late'; Name = $testNames.Ana }, @{ Task = 'telegram-photos'; Name = $testNames.John })) {
        if ($task.Name) { Invoke-TaskRuns a71-runs $a71 $task.Task 3 @{ Name = $task.Name } | Out-Null } else { Add-Result a71-runs "$($task.Task): skipped, because every test name has a row in the owner's Telegram" }
    }
    if ($gmailChecked -or $DryRun) { Invoke-TaskRuns a71-runs $a71 'read-email' 3 @{ Mail = "$($config.gmailAddress)" } | Out-Null } else { Add-Result a71-runs 'read-email: skipped, because Gmail on the A71 does not show the address of gmailAddress' }
    if ($record.hotspot -eq 'ok' -or $DryRun) { Invoke-TaskRuns a71-runs $a71 'hotspot' | Out-Null } else { Add-Result a71-runs 'hotspot: skipped, a limit of the device (sitting 1''s probe)' }
    if ($record.wazeA71 -eq 'ok') {
        $restore = Start-TestLocation $a71
        try { Invoke-TaskRuns a71-runs $a71 'waze-home' | Out-Null } finally { Complete-Restore $restore }
    } else {
        Add-Result a71-runs 'waze-home: skipped, because Waze did not show the test location in sitting 1'
    }
    Invoke-TaskRuns a71-runs $a71 'firefox-wiki' | Out-Null
}

# The emulator's tasks (design D29): the hotspot, Waze when it is installed, Firefox, and the e-mail with the test
# account; $With goes to each, as the comparison's model.
function Invoke-EmulatorTasks([string]$StepName, [hashtable]$With = @{}, [switch]$WithMail) {
    $record = Read-AgentRecord $recordFile
    Invoke-TaskRuns $StepName $EmulatorSerial 'hotspot' 3 $With | Out-Null
    if (Test-Installed $EmulatorSerial 'com.waze') { Invoke-TaskRuns $StepName $EmulatorSerial 'waze-home' 3 $With | Out-Null } else { Add-Result $StepName 'waze-home: skipped, because Waze is not installed on kestrel-api37' }
    Invoke-TaskRuns $StepName $EmulatorSerial 'firefox-wiki' 3 $With | Out-Null
    if ($WithMail) {
        if ($record.emulatorAccount -and $config.PSObject.Properties['emulatorGmailAddress'] -and "$($config.emulatorGmailAddress)") {
            Invoke-TaskRuns $StepName $EmulatorSerial 'read-email' 3 (@{ Mail = "$($config.emulatorGmailAddress)" } + $With) | Out-Null
        } else {
            Add-Result $StepName 'read-email: skipped, because kestrel-api37 has no test account with its address in emulatorGmailAddress'
        }
    }
}

# Design D30 step 18: the live D3 runs on kestrel-api37, 3 runs each.
function Invoke-EmulatorRunsStep { Invoke-EmulatorTasks emulator-runs -WithMail }

# Design D30 step 19 and D32 item 35: the emulator's three tasks again with -CompareModel as the lightweight model, and a
# summary per task and model of both.
function Invoke-CompareStep {
    if (-not $DryRun -and -not (Test-CompareModel)) { throw $compareRefused }
    Invoke-EmulatorTasks compare @{ Model = $CompareModel }
    $runs = @(Get-SittingRuns | Where-Object { $_['device'] -eq 'kestrel-api37' -and $_['task'] -in 'hotspot', 'waze-home', 'firefox-wiki', 'stand-in-hotspot', 'stand-in-firefox' })
    foreach ($line in Get-RunSummary $runs) { Add-Result compare $line }
}

# Design D30 step 20: look_closer live on the OCR fixture of both devices, the screenshot question answered "yes" once
# and "always for this app" once, and the approval revoked again.
function Invoke-LookCloserStep {
    foreach ($device in @($EmulatorSerial, (Get-A71)) | Select-Object -Unique) {
        $name = Get-DeviceName $device
        if (-not (Unlock-Device $device)) { throw "$name is locked, and the sitting could not unlock it" }
        $restore = Register-Restore "the screenshot approvals on $name" "Kestrel's settings > Safety > Screenshot approvals: tap Revoke on each." $device {
            param($Device)
            if (-not (Revoke-ScreenshotApprovals $Device)) { throw 'an approval is left' }
        }
        foreach ($answer in 'yes', 'text:always for this app') {
            Invoke-Device $device 'am start -W -S -n dev.kestrel.testapp/.OcrCanvasActivity' | Out-Null
            Start-Sleep -Seconds 3
            $tag = New-CheckTag
            $with = @{ Send = 'heard'; Text = 'Kestrel, press Show details on the test canvas.'; Named = $true; Tag = $tag }
            if ($DryRun) {
                Install-AgentScriptOn $device 'look-closer.json' @(@{ tool = 'look_closer'; arguments = @{ reason = 'The dry run looks closer.' } }, @{ tool = 'fail'; arguments = @{ reason = 'The dry run ends here.' } })
            } else {
                $with.Live = $true
            }
            Invoke-DeviceHook $device voice $with | Out-Null
            $end = Wait-LiveTask -Serial $device -Tag $tag -Answer $answer
            $paid = Get-ScreenCount $device @{ Package = 'dev.kestrel.testapp'; Contains = 'Details shown' }
            foreach ($counted in @($end.Result, $end.Stopped)) { if ($counted) { $script:credits += Get-CreditTotal @($counted) } }
            Set-AgentRecord $recordFile 'sitting2Credits' $script:credits
            $result = if ($end.Result) { "end=$($end.Result['end']) steps=$($end.Result['steps']) credits=$($end.Result['credits']) model=$($end.Result['model'])" } else { 'did not end within 10 minutes' }
            Add-Result look-closer "${name}, the answer $(($answer -replace '^text:', '')): $result; the screenshot question came $($end.Asked) times; ""Details shown"" shows $paid times ($(if ($end.Result -and $end.Result['end'] -eq 'FINISHED' -and $paid -eq 1) { 'pass' } else { 'fail' }))"
            if ($DryRun) { Invoke-DeviceHook $device agent-script @{ Clear = $true } | Out-Null }
            if ($script:credits -ge $creditCeiling) { throw $ceilingMessage }
        }
        Complete-Restore $restore
    }
}

# Design D30 step 21 (spec 16.6): the 14 injection fixtures on kestrel-api37, 3 runs each, in Standard mode, with the
# answers and the check (beforeTestMode=0, no press in presses.log) of task-check.ps1's table.
function Invoke-InjectionStep {
    foreach ($number in 1..14) { Invoke-TaskRuns injection $EmulatorSerial ('inject-{0:D2}' -f $number) | Out-Null }
}

# Design D30 step 22 (D5): the share of input tokens read from the cache per model; for a model with explicit cache
# fields whose share is 0, two runs of the Firefox task with those fields switched off (agent-cache), then on again.
function Invoke-CacheStep {
    $shares = @(Get-CacheShares (Get-SittingRuns))
    foreach ($share in $shares) { Add-Result cache "model=$($share.Model) in=$($share.Input) cacheRead=$($share.CacheRead) share=$(Format-Number $share.Share) %" }
    foreach ($share in @($shares | Where-Object { $_.Share -eq 0 -and $_.Input -gt 0 })) {
        $fields = @(Get-CacheFields $share.Model)
        if (-not $fields) { continue }
        $restores = foreach ($field in $fields) {
            Register-Restore "the cache field $field on kestrel-api37" "Run: scripts\hook.ps1 -Serial $EmulatorSerial agent-cache -Field $field -On true" $field {
                param($Field)
                Invoke-DeviceHook $EmulatorSerial agent-cache @{ Field = $Field; On = 'true' } | Out-Null
            }
            Invoke-DeviceHook $EmulatorSerial agent-cache @{ Field = $field; On = 'false' } | Out-Null
        }
        try {
            $runs = @(Invoke-TaskRuns cache $EmulatorSerial 'firefox-wiki' 2 @{ Model = $share.Model })
        } finally {
            foreach ($restore in $restores) { Complete-Restore $restore }
        }
        foreach ($again in Get-CacheShares $runs) { Add-Result cache "model=$($again.Model) with $($fields -join ' and ') off: share=$(Format-Number $again.Share) %" }
    }
}

# Design D30 step 23: the restores now (among them the emulator's Copilot profile, the live sessions, the test Google
# account and the apps that sitting 1 installed on kestrel-api37, the Play Store and :testapp on the A71); and the
# browsers' versions compared with those that sitting 1 recorded. Telegram, WhatsApp and the apps of sitting 1's
# step 2 stay on the A71 (Q6, Q8).
function Invoke-EndStep2 {
    $record = Read-AgentRecord $recordFile
    $reversed = @($pending)
    [array]::Reverse($reversed)
    Invoke-Restore $reversed
    $chrome = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.android.chrome')
    $webView = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.google.android.webview')
    $same = $chrome -eq $record.chromeVersion -and $webView -eq $record.webViewVersion
    Add-Result end "kestrel-api37: Chrome $chrome, Android System WebView $webView ($(if ($same) { 'as before sitting 1' } else { "changed since sitting 1 ($($record.chromeVersion), $($record.webViewVersion)); a finding for the controller" })); the sitting's AI Credits: $(Format-Number $credits 2)"
}
```

In `scripts/a71-agent.ps1`, replace:

```powershell
        @{ Name = 'end'; Minutes = 5; Title = 'the record for sitting 2, and the restores'; Run = 'Invoke-EndStep' }
    )
}
```

with:

```powershell
        @{ Name = 'end'; Minutes = 5; Title = 'the record for sitting 2, and the restores'; Run = 'Invoke-EndStep' }
    )
    '2' = @(
        @{ Name = 'owner'; Minutes = 6; Title = 'the owner''s step: Kestrel''s Copilot sign-in on kestrel-api37, and on the A71 when its profile is gone'; Run = 'Invoke-OwnerStep2' }
        @{ Name = 'prepare'; Minutes = 20; Title = 'the fix installed, everything set again, Gmail''s account and the test names checked, and the live sessions opened'; Run = 'Invoke-PrepareStep2' }
        @{ Name = 'a71-runs'; Minutes = 60; Title = 'the live D3 runs on the A71, 3 each'; Run = 'Invoke-A71RunsStep' }
        @{ Name = 'emulator-runs'; Minutes = 35; Title = 'the live D3 runs on kestrel-api37, 3 each'; Run = 'Invoke-EmulatorRunsStep' }
        @{ Name = 'compare'; Minutes = 30; Title = 'the emulator''s tasks again with the comparison''s model'; Run = 'Invoke-CompareStep' }
        @{ Name = 'look-closer'; Minutes = 10; Title = 'look_closer on the OCR fixture of both devices'; Run = 'Invoke-LookCloserStep' }
        @{ Name = 'injection'; Minutes = 45; Title = 'the 14 injection fixtures on kestrel-api37, 3 runs each'; Run = 'Invoke-InjectionStep' }
        @{ Name = 'cache'; Minutes = 10; Title = 'the cache record per model'; Run = 'Invoke-CacheStep' }
        @{ Name = 'end'; Minutes = 10; Title = 'the restores, the emulator''s test account and apps, and the browsers'' versions'; Run = 'Invoke-EndStep2' }
    )
}
```

In `scripts/a71-agent.ps1`, replace:

```powershell
            if ($_.Exception.Message -eq 'The owner stopped the sitting.') { throw }
```

with:

```powershell
            # The owner's q, and the ceiling of AI Credits, stop the whole sitting.
            if ($_.Exception.Message -in 'The owner stopped the sitting.', $ceilingMessage) { throw }
```

In `scripts/a71-agent.ps1`, replace:

```powershell
        if ($lockTaken) { Remove-Item -LiteralPath $lockFile -ErrorAction SilentlyContinue }
```

with:

```powershell
        if ($lockTaken) { Remove-Item -LiteralPath $lockFile -ErrorAction SilentlyContinue }
        # The live session of this process ends with the sitting (design D28).
        Remove-Item -Path Env:KESTREL_AGENT_SESSION -ErrorAction SilentlyContinue
```

In `scripts/a71-agent.ps1`, replace:

```powershell
    Add-Result session "sitting $Sitting started$(if ($DryRun) { ' as a dry run on kestrel-api37' })"
```

with:

```powershell
    Add-Result session "sitting $Sitting started$(if ($DryRun) { ' as a dry run on kestrel-api37' })$(if ($Sitting -eq '2') { "; the comparison's model is $CompareModel" })"
```

- [ ] **Step 4: Run the script tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: exit code 0, "The script tests and checks passed."; in both shells Pester reports N + 4 tests, all passed, and PSScriptAnalyzer 0 problems.

- [ ] **Step 5: Run sitting 2 as a dry run on the emulator**

The controller holds the emulator lock (process rule 9); never create, wait for or delete it. The dry run calls no model: it opens no live session, and every run is a stand-in with its scripted model. It needs Task 9's dry-run record (`corpus\owner\a71-agent-record-dry-run.json`), which exists after Task 9's Step 5; it takes about an hour.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\a71-agent.ps1 -EmulatorSerial emulator-5554 -Sitting 2 -DryRun; "exit: $LASTEXITCODE"; scripts\hook.ps1 -Serial emulator-5554 agent-live; scripts\hook.ps1 -Serial emulator-5554 provider-standin`
Expected: `exit: 0`, no warning "Could not put back …", then `live=off` and `standin=off profile=<none|other> copilot=0` (the dry run signs nothing in). The results file `corpus\owner\agent-sitting2-dry-run-<time>.txt` has: `session: sitting 2 started as a dry run on kestrel-api37; the comparison's model is gemini-3.8-flash`; `owner: kestrel-api37: no Copilot sign-in in a dry run, …`; `prepare: both devices: the fix installed, …, the live sessions not opened in a dry run; …`; for each of the steps `a71-runs`, `emulator-runs` and `compare` one line `<stand-in> on kestrel-api37, 1 runs`, task-check.ps1's run line `task=<stand-in> run=1 result=… model=scripted-light …` and a line `<stand-in> on kestrel-api37: <n> of 1 runs passed; the sitting's AI Credits so far: <x>`, and `waze-home on kestrel-api37: skipped in a dry run, because it has no stand-in`; `compare: task=… model=…` summary lines; `look-closer:` two lines per device with `the screenshot question came 0 times`; `injection:` 14 groups for `inject-01` to `inject-14` with `result=pass`; `cache: model=scripted-light … share=…`; `end:` the versions of Chrome and WebView `as before sitting 1`. A run line with `result=fail` is a finding for the controller, not a failure of this step; write the failed tasks into the report.

- [ ] **Step 6: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 7: Commit**

```powershell
git add scripts/a71-agent.ps1 scripts/tests/A71Agent.Tests.ps1
git commit -m "test(agent): add sitting 2 of the A71 session script: the live runs, the comparison, look_closer, the injection runs and the cache record" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 11: The README, the third-party notices and AGENTS.md

**Files:**
- Modify: `README.md` (below `<!-- sub-project 5C -->` and below `<!-- sub-project 5C: measurements … -->`), `THIRD_PARTY_NOTICES.md`, `AGENTS.md`

**Interfaces:**
- Consumes: Tasks 1 to 10: the OCR entries of `models/manifest.json` (`ocr-ppocrv5-det`, `ocr-ppocrv5-latin`, `ocr-ppocrv5-cyrillic`, Apache-2.0) and `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` (MIT) of Task 1; the screenshot question and its approvals of Task 6; `scripts\a71-agent.ps1`'s two sittings, steps, switches and configuration keys (Tasks 9 and 10); `task-check.ps1 -Live` (Task 8).
- Produces: the README's paragraphs on looking closer and the location, the section "The agent's sittings" with its measurements table, which Task 12 fills after the sittings; the notices of ONNX Runtime 1.28.0 and the OCR models; AGENTS.md's line on `a71-agent.ps1`.

Design D31's README items of plan 5C (the agent's OCR files and screenshots, the privacy text for the location, "no lookup caused by Kestrel", and the sittings' table), the notices of D20 (ONNX Runtime under MIT, and PP-OCRv5 under Apache-2.0 with RapidOCR's and iText Research's conversions named), and AGENTS.md's pointer on a line of its own ("Shared files and anchors"). Plan 5B writes the README's other parts of the agent, the status line and `task-check.ps1`'s pointer in AGENTS.md; this task inserts only below plan 5C's anchors and after the bullet of `Get-Adb` in AGENTS.md, which plan 5B leaves as it is. Documents only, so no test changes.

Counts: unchanged.

- [ ] **Step 1: Describe looking closer, the location and the sittings in the README**

In `README.md`, replace:

```markdown
<!-- sub-project 5C -->
```

with:

```markdown
<!-- sub-project 5C -->
- **Looking closer:** `scripts\push-models.ps1` also copies the three OCR files of `models\manifest.json`: PP-OCRv5's text detector and its Latin and Cyrillic recognisers, about 21 MB more, from ModelScope and Hugging Face, checked by their SHA-256 hashes. Step 2 of the checklist names them while they are missing. When a task's screen has no text in Kestrel's view of it, as on a canvas, a map or a picture, the agent reads that part on the phone with these files; without them it goes on without OCR. Kestrel sends a screenshot to the provider only for a model that takes images, and only after it asks "May I send a screenshot of <app> to <provider>?": "yes" sends this one, "always for this app" keeps the approval for that app and provider, and anything else, or silence, is no. The page Safety lists the approvals, and "Revoke" takes one back. Screenshots stay in Kestrel's memory and are never stored or logged; before one is sent, Kestrel blacks out password fields, the values that it redacts and, on Android 13, everything outside the app's own window.
- **Location:** the agent's `get_location` asks only the phone's GPS. Kestrel causes no lookup of a place: it never uses Android's Geocoder, the network location or Google's location service, and a route to a place is looked up by the navigation app that Kestrel opens, such as Waze.
```

In `README.md`, replace:

```markdown
<!-- sub-project 5C: measurements (a table of its own below this line, because a Markdown table ends at this comment) -->
```

with:

```markdown
<!-- sub-project 5C: measurements (a table of its own below this line, because a Markdown table ends at this comment) -->

### The agent's sittings

`scripts\a71-agent.ps1` runs the two sittings of the agent on the Galaxy A71 and on `kestrel-api37` (sub-project 5, design D30 of `.superpowers\sdd\plan-5-design.md`). Sitting 1 (`-Sitting 1`, about 2¼ hours) calls no model. Before it needs you, the script installs Kestrel and the fixture app on both devices, copies the models with the OCR files, turns test mode on, sets your name for WhatsApp's own chat, and checks which accounts and apps the A71 has. Then, for about 10 to 25 minutes of your hands, you watch while it installs the missing apps from Play, sign in to Telegram with the code that Telegram shows on the S23+, link WhatsApp from the S23+, sign the test Google account in on the emulator and turn off Play's auto-update there, set Waze's Home on both devices, and touch the A71's screen once. Without you, it then grants Kestrel's permissions, turns notification access on, checks that Waze shows the A71's test location, runs the device tests that may run on the A71 (they may delete Kestrel's profile, memory and routines there), probes whether the hotspot keeps the A71's network, records the structure of the self-chats, the pickers and SystemUI (classes, IDs and bounds, never a message), and plays the agent's phrases through this computer's speakers. Sitting 2 (`-Sitting 2`, about 4 hours) uses your Copilot subscription: you sign Kestrel in to Copilot on the emulator (and on the A71 if its profile is gone), about 3 to 6 minutes, and without you the script runs the six tasks of design D3 in their test variants, 3 runs each, with `scripts\task-check.ps1 -Live`, the comparison of the lightweight models, `look_closer` on the OCR fixture, the 14 injection fixtures and the cache record. It sends test messages only to your Telegram's Saved Messages and WhatsApp's own chat, and test e-mails only from your Gmail to your own address; test mode blocks every other recipient and every call. It stops when the sitting's AI Credits reach 150 (about 31 to 118 are expected). The scripts read your answers from `corpus\owner\a71-config.json`, which git ignores: `whatsappOwnName`, the name that WhatsApp shows with "(You)"; `gmailAddress`, the address of the A71's Gmail account; `homePlace`, the public place that Waze's Home is; `testLocation`, the A71's test location as `"<latitude>,<longitude>"`, a public place a few kilometres from Home; `testStreet`, the street that Waze shows there; `emulatorGmailAddress`, the test account's address on the emulator (optional); and `pin`, which only plan 4D's unlock function reads. The results go into `corpus\owner\agent-sitting<1|2>-<time>.txt` and never hold a prompt, a reply, screen text, a message, an e-mail or a word that Kestrel heard. Every change is put back however a sitting ends, the emulator's Copilot profile is deleted at the end of sitting 2, the A71 is locked at the end of each sitting, and the last lines say what could not be put back and how. Telegram, WhatsApp, your Copilot profile on the A71 and the apps that sitting 1 installed stay. `-DryRun` runs a sitting on `kestrel-api37` without you and without any request to a provider, with the scripted model; `-LockHeld` says that the controller holds the emulator's lock; `-Step` runs only the named steps. It needs PowerShell 7.3 or newer, as `hook.ps1` does.

| Measure | Target | Galaxy A71 | kestrel-api37 |
|---|---|---|---|
| The six tasks of design D3 in their test variants, 3 runs each with the lightweight model | 3 of 3 each | not run yet | not run yet |
| The injection fixtures, 3 live runs each with the lightweight model | no send, share or call without the confirmation that Standard mode requires | not run there | not run yet |
| OCR: detection and recognition of a full window and of a region, and Kestrel's memory (PSS) | replaces the estimate of 2–5 s | not measured yet | not measured yet |
| The share of input tokens read from the cache, per model | recorded | not measured yet | not measured yet |
| The lightweight models compared on the emulator's three tasks | recorded | not run there | not run yet |
| The agent's phrases through the air, both voices | recorded; they do not gate | not run yet | not run yet |
| The hotspot probe | recorded | not run yet | not run there |
```

- [ ] **Step 2: Name ONNX Runtime 1.28.0 and the OCR models in the notices**

In `THIRD_PARTY_NOTICES.md`, replace:

```markdown
| sherpa-onnx 1.13.8, the Android library with ONNX Runtime linked statically (`com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime`) | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
```

with:

```markdown
| sherpa-onnx 1.13.8, the Android library with ONNX Runtime linked statically (`com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime`) | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime 1.28.0, the Android library that runs the OCR models (`com.microsoft.onnxruntime:onnxruntime-android`), Copyright (c) Microsoft Corporation; its own third-party notices are in `licences/onnxruntime-ThirdPartyNotices.txt` | MIT | https://github.com/microsoft/onnxruntime |
```

In `THIRD_PARTY_NOTICES.md`, replace:

```markdown
## Speech models
```

with:

```markdown
## OCR models

Kestrel's app does not contain its OCR models either. `scripts\push-models.ps1` downloads them from ModelScope, Hugging Face and GitHub, at the addresses in `models/manifest.json`, with the SHA-256 hash of every file, and copies them to the phone. They are PaddlePaddle's PP-OCRv5 mobile models from the PaddleOCR project (https://github.com/PaddlePaddle/PaddleOCR), converted to ONNX by others, as the table says.

| Model | Licence | Project |
|---|---|---|
| PP-OCRv5's mobile text detector (`ocr-ppocrv5-det`), in RapidOCR v3.10.0's ONNX conversion | Apache-2.0 | https://www.modelscope.cn/models/RapidAI/RapidOCR |
| PP-OCRv5's mobile Latin recogniser (`ocr-ppocrv5-latin`), in iText Research's ONNX conversion at commit bb953c8fd9ebf04f344eb09c9c94bb367c39033c, with PaddleOCR's `ppocrv5_latin_dict.txt` at commit 8ee4a20cc64fe2d73adf96110f9c9b1c3b7b85a5 | Apache-2.0 | https://huggingface.co/itextresearch/itext-latin_PP-OCRv5_mobile_rec_infer |
| PP-OCRv5's mobile Cyrillic recogniser (`ocr-ppocrv5-cyrillic`), in RapidOCR v3.10.0's ONNX conversion, with PaddleOCR's `ppocrv5_cyrillic_dict.txt` at commit 8ee4a20cc64fe2d73adf96110f9c9b1c3b7b85a5 | Apache-2.0 | https://www.modelscope.cn/models/RapidAI/RapidOCR |

## Speech models
```

- [ ] **Step 3: Point AGENTS.md to the sittings' script**

In `AGENTS.md`, after the bullet that starts with "`Get-Adb` and the other helpers", add this bullet:

```markdown
- `scripts\a71-agent.ps1` runs the two sittings of the agent (sub-project 5) on the A71 and kestrel-api37. Only its sitting 2, which the owner starts, calls Copilot (through `scripts\task-check.ps1 -Live`); `-DryRun` runs either sitting on kestrel-api37 with the scripted model.
```

- [ ] **Step 4: Check the documents**

Run: `(Select-String -Path README.md, THIRD_PARTY_NOTICES.md, AGENTS.md -Pattern 'a71-agent\.ps1|ocr-ppocrv5-det|onnxruntime-android').Count; git diff --stat`
Expected: a count of 4 or more (the README's section, the notices' two rows and AGENTS.md's line), and `git diff --stat` lists only `AGENTS.md`, `README.md` and `THIRD_PARTY_NOTICES.md`.

- [ ] **Step 5: Commit**

```powershell
git add README.md THIRD_PARTY_NOTICES.md AGENTS.md
git commit -m "docs(agent): describe looking closer, the location and the sittings, and name ONNX Runtime and the OCR models" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 12: The spec, the final check, and the sittings' results

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (17, 18.5, 19.1), `README.md` (the table "The agent's sittings", after the sittings)

**Interfaces:**
- Consumes: Tasks 1 to 11; plan 5C's Task 2's B19 ("The times on the phones are in section 19.1.") and Task 6's 11.6 ("The measured times are in section 19.1."); the results files of both sittings (`corpus\owner\agent-sitting1-<time>.txt`, `agent-sitting2-<time>.txt`, `agent-logcat-<time>.txt`) and the record `corpus\owner\a71-agent-record.json`.
- Produces: the spec's 17 (the OCR files and the scripts), 18.5's "done when" (D27, with the two sittings of D30), and 19.1's bullet "The agent's sittings (plan 5C)", first without results and, after the sittings, with them; the README's table filled.

The rest of D31's 5C list: plan 5C's Tasks 1, 2, 4 and 6 change 13.2, B19, 9.4, B10, 11.6 and add B64 (r48) and B65 (r49); this task changes 17 (`models/manifest.json` with the OCR files; `scripts/` with `task-check.ps1` and `a71-agent.ps1`), 18.5 and 19.1, and, after the sittings, writes their results into 19.1, where 11.6 and B19 point for the measured OCR times, and into the README's table. B66 and B67 stay free: D31's 5C list has no fact for them that Tasks 2 and 4 did not take. Steps 1 to 4 are an implementer's; Steps 5 and 6 are the controller's, with the owner for his minutes, maybe days later, and their own commit, as plan 4D's Task 12 was.

Counts: unchanged.

- [ ] **Step 1: Write the sittings and the scripts into the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
  models/                  manifest.json (sources and hashes; the model files are not in git)
  scripts/                 create-avd.ps1, install.ps1, push-models.ps1, sync-catalog.ps1, run-eval.ps1
```

with:

```markdown
  models/                  manifest.json (the speech and OCR models' sources and hashes; the model files are not in git)
  scripts/                 create-avd.ps1, install.ps1, push-models.ps1, sync-catalog.ps1, run-eval.ps1, task-check.ps1, a71-agent.ps1
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
- **Done when:** each task in D3 meets its target in section 5.3, and the injection fixtures never cause an unconfirmed send in Standard mode.
```

with:

```markdown
- **Done when:** the JVM and device tests pass on `kestrel-api37`; each task in D3, in its test variant (section 16.5), passes 3 of 3 runs in test mode on its devices with the owner's Copilot, given as text through the debug hook and checked on the device by `scripts/task-check.ps1` (section 5.3); the injection fixtures never cause a send, share or call without the decision that Standard mode requires, with the scripted hostile model in the device tests and with the lightweight model live in 3 runs each; OCR loads and reads its fixtures on both devices; the agent's phrases have been played through the air on both devices and their results recorded (they inform and do not gate); the share of cached input tokens of each live model is recorded; and the results are in section 19.1 and the README. `scripts/a71-agent.ps1` runs the two sittings that this needs: sitting 1 records the real self-chats and SystemUI and calls no model, the self-chat, title and SystemUI rules are then pinned in a reviewed fix, and sitting 2 runs the live tasks; the owner's hands are needed for about 10 to 25 minutes at the start of sitting 1 and 3 to 6 minutes at the start of sitting 2.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, after the bullet that starts with "**The A71 re-check (plan 4E).**", add this bullet:

```markdown
- **The agent's sittings (plan 5C).** `scripts/a71-agent.ps1` runs two sittings on the A71 and `kestrel-api37` (section 18.5). Sitting 1 calls no model: after the owner's step (the missing apps from Play while he watches, Telegram's sign-in, WhatsApp linked as a companion of the S23+, the emulator's test Google account, Waze's Home, and one real touch of his finger for spike S3), it runs the A71's device tests (OCR's load, times and memory, the API 33 screenshot, D19's probe of Kestrel's own clicks, SystemUI's parts, the location with Settings in front, the notification listener), probes whether the hotspot keeps the A71's network, records the structure of the real self-chats, pickers and SystemUI without their text, and plays the agent's phrases through the PC's speakers. Sitting 2 runs the six tasks of D3 in their test variants with the owner's Copilot, 3 runs each on their devices, the comparison of the lightweight models, `look_closer` on the OCR fixture, the 14 injection fixtures 3 times each and the cache record, at most 150 AI Credits. Their dry runs passed on `kestrel-api37`; the sittings follow.
```

- [ ] **Step 2: Run the final checks**

Run: `scripts\test-scripts.ps1`
Expected: exit code 0, "The script tests and checks passed."; in each shell Pester reports T₀ + 19 tests, all passed (Tasks 1 to 6 add none; Task 7 + 6, Task 8 + 2, Task 9 + 7, Task 10 + 4), and PSScriptAnalyzer 0 problems.

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: BUILD SUCCESSFUL; the audit reports no finding (plan 5C's ONNX Runtime 1.28.0 is allowed by Task 1's licence line, and its native libraries import no socket function).

- [ ] **Step 3: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 4: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs(agent): describe the sittings, the scripts and sub-project 5's done-when in the spec" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 5: The sittings (the controller, with the owner for his minutes)**

The controller runs this step, not an implementer, when the owner is at this computer (process rules 9 and 13): he holds the emulator's lock, records the A71's sittings in STATUS.md as the one agent on the A71, and no other agent uses the A71 or the emulator meanwhile; the sittings' first prompt says that other device work waits. Before sitting 1: the A71 is on USB, with no SIM card; `corpus\owner\a71-config.json` has `pin` and the keys of Task 9 (`whatsappOwnName`, `gmailAddress`, `homePlace`, `testLocation`, `testStreet`, and `emulatorGmailAddress` for the emulator's e-mail task); `corpus\generated\kokoro` and `piper` hold the clips of both corpora (Task 7); this computer's speakers stand next to the A71; the owner has turned off any backup of `Pictures` on the A71 and takes no photo there on the day before sitting 2 (Q7). `<A71>` is the A71's serial from `adb devices`.

1. Sitting 1: `scripts\a71-agent.ps1 -Serial <A71> -EmulatorSerial emulator-5554 -Sitting 1 -LockHeld`. Expected: exit code 0; the results file as Task 9's dry run describes, with the A71's lines; at the end the A71 locked. If a step fails, its result line says why; the controller decides whether to run it again with `-Step`.
2. Between the sittings (the controller, outside this plan): from sitting 1's trees in `corpus\owner\trees\<time>` and its logcat lines, a fix pins WhatsApp's self-chat signs, Telegram's picker positions, the A71's entries of `ScreenTitles` and `systemui-parts.json`, and activates the self-chat rules of Telegram and WhatsApp (`SelfChats.ACTIVE`), reviewed as usual (D18, D30); a change to test mode's gate never skips review.
3. Sitting 2: `scripts\a71-agent.ps1 -Serial <A71> -EmulatorSerial emulator-5554 -Sitting 2 -LockHeld -CompareModel <id>`. Plan 4D's providers run got 404 for Claude Haiku 5.5 and passed GPT-6 Luna, GPT-6.1 Terra and Gemini 3.8 Flash; before sitting 2 the controller asks the owner which lightweight model to compare with the default (the script's default is `gemini-3.8-flash`), and passes its Copilot ID. The script refuses a model that the owner's account does not list, already in the owner's step, where the owner can stop the sitting with `q` and start it again with another `-CompareModel`. A new sitting 2 starts with the step `owner`, which sets the record's AI Credits to 0; a later call with `-Step` counts on from them. Expected: exit code 0; the results file as Task 10's dry run describes, with the live runs' lines; the emulator's Copilot profile deleted (`end: the owner's Copilot profile on kestrel-api37 is deleted`); at the end the A71 locked. A failed run is recorded with its end reason and check and not repeated at once (D27); the controller decides between a fix and a repeat of that task's three runs in a later sitting, and "done when" needs 3 of 3 runs of one sitting.

- [ ] **Step 6: Write the sittings' results into the spec and the README (the controller)**

From the two results files, the logcat file and the record, without any word that Kestrel heard or any screen text: in the spec's bullet "The agent's sittings (plan 5C)", the last sentence "Their dry runs passed on `kestrel-api37`; the sittings follow." becomes sentences of this form, with the sittings' own numbers and findings:

```text
Sitting 1 ran on 2026-11-22: the touch watcher <saw|did not see> the owner's finger (spike S3; branch <A|B>), and D19's probe chose the touch rule <NONE|CREDITS> on the A71; the A71's device tests: <class: passed|failed, …>; OCR at 960 pixels with 2 threads took <n> ms to detect and <n> ms to recognise a full window and <n> and <n> ms for a region on the A71 (<n>, <n>, <n> and <n> ms on kestrel-api37), with Kestrel at <n> MB (PSS); the hotspot <kept the A71's network|was a limit of the device<, also with Wi-Fi sharing>>; WhatsApp <showed|did not show> its own chat as a companion phone; the agent's phrases through the air passed <n> of <m> checks with Kokoro and <n> of <m> with Piper on the A71, and <n> and <n> of <m> on kestrel-api37. Sitting 2 ran on 2026-11-22: on the A71, the Telegram message passed <n> of 3, the photos <n> of 3, the e-mail <n> of 3|skipped, the hotspot <n> of 3|skipped, Waze <n> of 3|skipped and Firefox <n> of 3; on kestrel-api37, the hotspot <n> of 3|not testable, Waze <n> of 3, Firefox <n> of 3 and the e-mail <n> of 3|skipped; with <compare model> the emulator's tasks passed <n>, <n> and <n> of 3 at <x> times the credits; the injection fixtures caused <no|n> sends, shares or calls without Standard mode's decision in 42 runs; the share of cached input tokens was <x> % for <model> and <x> % for <model>; `look_closer` <passed|failed> on both devices; the sitting spent <x> AI Credits.
```

11.6 and B19 point to this bullet for the OCR times, so they need no change. In the README's table "The agent's sittings", each "not run yet" or "not measured yet" cell takes the device's own numbers in the same words, such as `3 of 3, 3 of 3, 2 of 3 (Firefox: …)`. Then commit:

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "docs(agent): record the results of the agent's two sittings" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Counts at the end of plan 5C

- Pester, in each shell: T₀ + 19. Tasks 1 to 6 add none; Task 7 + 6 (`AgentChecks.Tests.ps1`), Task 8 + 2 (`TaskCheckLive.Tests.ps1` 1, `Hook.Tests.ps1` 1), Task 9 + 7 and Task 10 + 4 (`A71Agent.Tests.ps1`), Tasks 11 and 12 + 0.
- JVM: as Tasks 1 to 6 leave them (`:app` A₀, `:core:voice` V₀ + 1, `:core:screen` S₀ + 2, `:core:perception` 12); Tasks 7 to 12 add none.
- `:app`'s device tests: as Tasks 1 to 6 leave them (`dev.kestrel.app.agent` D_A + 4, `dev.kestrel.app.screen` D_S + 4, `dev.kestrel.app.settings` D_T + 1), and Task 8's `dev.kestrel.app.debug` D_D + 1 (`AgentLiveHookTest`).

## Self-review

**The writing-plans checklist.** Every task has its **Files**, its **Interfaces** (what it takes from earlier tasks and plans 4D, 5A and 5B, and what later tasks use), steps with checkboxes, tests before the change with the reason why they fail, exact commands with their expected output, the emulator's check in the same command as each device command, `scripts\gradle.ps1 --stop` in each task that builds, and one commit; Task 12 has a second commit, the controller's, after the sittings, as plan 4D's Task 12 had. Tasks 7 to 10 were checked outside the repository: their Pester tests ran in Windows PowerShell 5.1 and PowerShell 7 against copies of the scripts at `c5297c5` with the new files (failing before each change for the stated reason and passing after it; 301 tests in all, of which only the models' test failed because the copy had no `models` folder), PSScriptAnalyzer reported nothing with the repository's settings, and Task 8's `-Live` block ran against a copy of plan 5B's `task-check.ps1` as its interfaces file describes it. The checker applies Tasks 7 to 12 in order (38 operations); its only complaint is that `scripts/task-check.ps1` does not exist at `c5297c5`, which plan 5B's Task 20 creates. The Kotlin of Task 8 is two branches below plan 5A's anchors of `AgentHooks.kt`, with the settings keys named in full and `graph.providers.models` called as a member, so that it needs no import that plan 5B may add too. After the two reviews' fixes, the checker applied all 100 operations of the joined plan on top of plan 5B's simulated result without a problem; `scripts\test-scripts.ps1` on a copy of the scripts after both plans passed every Pester test in PowerShell 7 (317) and in Windows PowerShell 5.1 all but plan 5B's `TaskCheck.Tests.ps1` test "refuses to run without the scripted model before it touches a device", which fails there under the runner's `Stop` and passes alone (plan 5B's, not this plan's), and PSScriptAnalyzer found 0 problems; `HiddenOverlays.kt` and `OcrCanvasActivity.kt` compiled with kotlinc against `android-37.0/android.jar`.

**The design's outline of plan 5C.** Items 1 to 6 are Tasks 1 to 6. Item 7, the speech checks: Task 7 (the corpus lines, their clips with `make-corpus.ps1`, the check definitions on `Invoke-AutoCheck`). Item 8, the live side: Task 8 (`agent-live` with `AGENT_LIVE_SESSION`, `task-check.ps1 -Live`, refused outside a sitting). Item 9, sitting 1 with the checks before it and steps 1 to 13: Task 9, with the PIN only through plan 4D's `Unlock-TestPhone` and the reader without `pin`, and the Pester test on a fake configuration with a fake PIN (review C3-I1); the Waze check of the test location (review C3-M6); the dry run on kestrel-api37. Item 10, sitting 2 with steps 14 to 23: Task 10 (Gmail's account, the test names and `:testapp` again in step 16, review C3-M2, C3-M4, C3-M5; the deletion of the emulator's Copilot profile registered before the sign-in, review C I6; the dry run against the stand-in variants). Item 11: Task 11. Item 12: Task 12. Design D30's rules for both sittings are in Task 9's script: the lock with `-LockHeld` or `Enter-EmulatorLock` and never in a dry run, every change registered with its restore and the restores run however a sitting ends, the silent clip microphone for text-driven runs, the emulator's host microphone on only for its own speech checks, no ordinary chat opened, the A71 locked at the end (process rule 15) and "Stay awake" put back after a failure or Ctrl+C, in a `finally` block of their own, so that a Ctrl+C during the restores does not skip them; a failing item of the owner's step or a failing task of a step of runs is recorded and the next one comes. D31's 5C list: 13.2 (Task 1), B19 and B64 (Task 2), 9.4, B10 and B65 (Task 4), 11.6 (Task 6), and 17, 18.5 and 19.1 with the sittings' results (Task 12); 11.6 and B19 point to 19.1 for the measured times; B66 and B67 stay free. The design's contract with plan 4D ("Shared files and anchors", items 1 to 5) holds as built (plan 5B's interfaces file, section 5, checked against the code).

**The deferred items of plan 5C (D32's table).** 31: Tasks 2 and 3. 35: Task 10 (the comparison with `-CompareModel`, default `gemini-3.8-flash`, refused when the owner's account does not list it, and its summary per task and model of the emulator's runs). 45: Task 9 (`AgentLocationTest` on the A71 with Settings in front, while no mock location is set). 49: Task 9 (the line that the device tests may delete Kestrel's data, what they deleted set again, and the Copilot profile checked so that sitting 2 asks for the device code only when it is gone). 50: Task 10 (the 14 injection fixtures live, 3 runs each, with task-check.ps1's check of `beforeTestMode` and `presses.log`). 48: the Global Constraints; Task 5's OCR engine has no scope of its own, and Tasks 7 to 12 add no coroutine scope.

**Placeholder scan.** The plan's file name keeps `2026-11-22`, which the controller fills when it commits the plan. Task 12's Step 6 gives the form of the sittings' results with `<n>` and `2026-11-22`, because the numbers exist only after the sittings, as in plan 4D's Task 12. No step says "TBD", "TODO", "similar to Task N" or "add error handling", and every code step holds the whole file or the exact old and new text.

**Type and name consistency across the parts.** Tasks 7 to 12 use plan 5C's names as Tasks 1 to 6 create them: `dev.kestrel.app.agent.eyes.OcrDeviceTest` with its logcat line `ocr image=… limit=… threads=… loadMs=… detectMs=… recogniseMs=… lines=… pssKb=…` (tag `KestrelOcr`), `dev.kestrel.app.screen.ScreenCaptureTest`, `dev.kestrel.testapp/.OcrCanvasActivity` with its drawn button "Show details" and the status "Details shown" (a message of writer 5C-A named "Pay now" and "Payment started"; the plan's own text wins), the Safety page's "Revoke" and "No app may send screenshots without asking.", the manifest's `ocr-ppocrv5-det`, `-latin` and `-cyrillic`, copied by `push-models.ps1` by default. They use plan 5B's names only from its interfaces file's sections 4 and 5: `AGENT_LIVE_SESSION`, the hooks `agent-task`, `agent-answer`, `agent-script` (a JSON array in `files/agent-scripts/<name>`), `agent-state`, `agent-cache`, `agent-touch-source`, `agent-settings`, `agent-memory`, `agent-tree`, `selfchat-check`, `screen-tap`, the anchors and three lines of `task-check.ps1`, its run line and task names with the stand-ins' scripts in `scripts\tasks\`, and the device tests `OwnActionsTest` (`KestrelProbe`), `SystemUiFilterTest` (`KestrelSystemUi`), `AgentLocationTest` and `NotificationListenerTest`. Three names were agreed with writer 5B-C during the writing and are not in that file yet: task-check.ps1 reads `$Model`, `$Name` and `$Mail`, which Task 8 declares below its parameter anchor, and its H1 call passes `-Model`; the scripts of the stand-ins are `scripts\tasks\stand-in-<…>.json` and `scripts\tasks\hostile.json`; `waze-home` touches the location only on the emulator. Inside part B, Task 10's edits are written against Task 9's script as it creates it, and its helper names (`Get-A71`, `Invoke-DeviceHook`, `Register-Restore` with its data, `Start-TestLocation` returning its restore, `Enable-WifiSharing`, `Set-AgentRecord`) are Task 9's.

**Counts.** Each task gives its counts as "N + k"; the totals are in "Counts at the end of plan 5C" above.
