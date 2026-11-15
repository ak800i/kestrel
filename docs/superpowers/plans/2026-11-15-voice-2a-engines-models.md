# Kestrel Voice 2A: Speech Engines and Model Files Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start sub-project 2 (Voice): build sherpa-onnx into Kestrel with the audited ONNX Runtime, copy the speech model files to a test device and check them there, and run all four speech engines (voice activity detection, keyword spotting, recognition with boost words, and voice output) on the emulator, which also answers spike S8.

**Architecture:** A new Android library module, `:core:voice`, wraps sherpa-onnx 1.13.8 behind Kestrel's own small interfaces (`SpeechDetector`, `KeywordSpotter`, `Recognizer` and `Synthesizer`), so that plans 2B and 2C, and later a Serbian recogniser, depend only on Kestrel's types. `models/manifest.json` lists every model file with its size and SHA-256 hash. The build copies it into the app's assets, `ModelFiles` checks the files on the phone against it, and `scripts\push-models.ps1` uses the same manifest to download, check and copy the files. The pure decisions (the manifest, the hash cache, the boost words, the keyword list, the recogniser's word pieces and its second try) have JVM tests. The engines have device tests in `:app`, which `scripts\voice-test.ps1` runs with the models on the device. The settings app shows the status of the model files in setup step 2 and on the About page.

**Tech Stack:** As in sub-project 1: Gradle 9.9.1, the Android Gradle plugin 9.5.1 with built-in Kotlin 2.4.21, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.13.0, Jetpack Compose (BOM 2026.09.00), JUnit 4, AndroidX Test, Pester 6.2.0 and PSScriptAnalyzer 1.25.0. New in this plan: sherpa-onnx 1.13.8 (`sherpa-onnx-static-link-onnxruntime-1.13.8.aar` from sherpa-onnx's GitHub release, through an Ivy repository and Gradle's dependency verification), onnxruntime-android 1.28.0 (in device tests only, for spike S8), and Windows' own `tar.exe` for `.tar.bz2` archives.

**Spec:** [2026-11-12-kestrel-design.md](../specs/2026-11-12-kestrel-design.md), sections 8.1–8.3, 8.10, 13.2, 14.6, 15, 16.4, 17, 18.2 and 19 (S8), and Appendix B (B13, B14, B37–B39). This is plan 2A, the first of three plans for sub-project 2, Voice (section 18.2):

- **2A (this plan):** sherpa-onnx in the build, the model manifest, `push-models.ps1`, the check of the model files, their status in the settings app, the four engines with their device tests, and spike S8.
- **2B:** `ListeningService`, audio capture, endpointing, the task name, the Orchestrator's listening part, voice output with interruptions, the listening modes, lock and screen handling, the feedback bar's text, and activation by the shortcut, the notification, the tile and "Kestrel, wake up".
- **2C:** the assistant role (spike S2), calls, earbuds, the settings pages for listening and voice with Kokoro as a second voice (section 8.2), the voice corpus and its measurements, and a session on the A71 with the owner (spike S5).

The ledger `.superpowers/sdd/progress.md` lists the changes that plan 1C made to its plan; none of them touches the files of this plan, except `MainActivity.kt` (Task 4 keeps its `writeLogExport` call) and `scripts/lib/Kestrel.ps1` (Task 6 adds to it and keeps everything else).

## Global Constraints

- Package and application ID `dev.kestrel`; repository `H:\Code\kestrel`, published as `github.com/ak800i/kestrel` (D21).
- Licence GPL-3.0-or-later for all of Kestrel; every dependency that ships in the app must have a licence that is compatible with GPL-3.0, such as Apache-2.0, MIT or BSD. Tools used only to build or test Kestrel, such as JUnit, are exempt (D28, section 17).
- `minSdk 33`, `targetSdk 37` and `compileSdk 37` (section 6).
- Gradle with the Kotlin DSL and a version catalog; Android Gradle plugin 9.5.1 and Kotlin 2.4.21 (section 17). The plugin has Kotlin built in: never apply `org.jetbrains.kotlin.android`.
- There is no dependency-injection framework; one `AppGraph` class wires the parts together by hand (section 17).
- All engines run through sherpa-onnx. Kestrel uses sherpa-onnx 1.13.8, whose Android library links ONNX Runtime 1.28.2 statically and includes espeak-ng, so the ONNX Runtime has no Android telemetry (section 8.2, B13, B14).
- The build-time audit fails when the ONNX Runtime version differs from the pinned version that was audited, currently 1.28.x. sherpa-onnx links its own copy of ONNX Runtime, so the audit pins the version of the sherpa-onnx library, Gradle checks the library's SHA-256 hash when it downloads it, and a device test checks that the library reports ONNX Runtime 1.28.2 (section 13.2).
- A bundled native library must not import socket functions such as `socket`, `connect` or `getaddrinfo` (section 13.2).
- The models: Silero VAD (MIT); the keyword model `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (Apache-2.0), regular package, never the "-mobile" package; Parakeet-TDT-0.6B-v3, int8 (CC-BY-4.0); and Piper's `en_US-ljspeech-medium` voice with the English part of espeak-ng's data (section 8.2, B37, B38).
- The keyword spotter detects "Kestrel", "stop" and "cancel" without any training (section 8.2).
- `THIRD_PARTY_NOTICES.md` records every model, voice and licence, including the CC-BY attribution for Parakeet and espeak-ng's GPL-3.0 (sections 8.2 and 17).
- Boost words: sherpa-onnx applies them to Parakeet-TDT only with modified beam search and a list of the model's word pieces. Kestrel uses a moderate boost, and when the last word of a transcript ends more than 1 s before the audio does, it decodes the audio again without boost words and keeps the longer transcript (section 8.3, B39).
- An utterance ends after 0.7 s of silence and lasts at most 30 s (section 8.3).
- Kestrel records at 16 kHz in mono (section 8.1); the engines take 16 kHz samples.
- `scripts/push-models.ps1` downloads the model files on the PC from their upstream sources into a cache folder (`%LOCALAPPDATA%\Kestrel\models`), checks their SHA-256 hashes against `models/manifest.json`, and copies them with `adb push` into `/sdcard/Android/data/dev.kestrel/files/models/`. The phone itself never downloads a model (section 8.10).
- Kestrel creates its `files/models` folder when it starts. The script creates the folders below it with the mode 2775, and it copies only the files whose size or hash on the phone differs (section 8.10).
- The build copies `models/manifest.json` into the app's assets. Before Kestrel loads an engine, and whenever the settings app shows the setup checklist or the About page, it checks the files against it, and it hashes a file again only when the file's size or modification time changes. A missing or corrupt file disables the engine that needs it, and the setup checklist names the file (sections 8.10 and 14.6).
- The setup checklist's step 2 is "Copy the models with `scripts/push-models.ps1`"; the About page shows the status of the model files (section 15).
- Screenshots, audio and credentials are never logged (section 13.5). The engines never write audio or transcripts to logcat or to files.
- Errors go to logcat only as class names and stack frames, never with their messages, because a message can contain screen text (plan 1C).
- Debug builds contain test hooks that accept commands only from callers that hold `android.permission.DUMP`; release builds do not contain them (section 16.1).
- Testing happens only on the `kestrel-api37` emulator (Android 17, API 37) in this plan. Never test on the Galaxy S23+ or the AVD `zoran`. The Galaxy A71 is not used in this plan; plan 2C uses it with the owner (D20, section 6, AGENTS.md).
- The Android SDK is in `D:\AndroidVMs\SDK`. Scripts are PowerShell, and environment changes stay inside the process that makes them (section 17).

## How to work in this plan

- Run every command in PowerShell 7 from the repository root, `H:\Code\kestrel`.
- Run Gradle only through `scripts\gradle.ps1`, and device tests only through `scripts\connected-test.ps1` or, for the voice tests, `scripts\voice-test.ps1` (Task 6). `scripts\gradle.ps1` already passes `--console=plain`; do not pass it again. When you stop working, run `scripts\gradle.ps1 --stop`.
- `Get-Adb` and the other helpers exist only after `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain`, so run that in the same command that uses them.
- `scripts\start-emulator.ps1` starts `kestrel-api37` when it is not running and prints its adb serial, for example `emulator-5554`. The commands below write `<serial>` for it. Check with `& (Get-Adb) -s <serial> emu avd name` that it is `kestrel-api37` before you run anything on it.
- A passing Gradle command ends with `BUILD SUCCESSFUL`, a failing one with `BUILD FAILED` and the reason above it. Gradle prints Kotlin warnings as lines that start with `w:`; new code must not add any.
- After a change to a script, run `scripts\test-scripts.ps1`. New scripts must pass it: PSScriptAnalyzer checks them with the rules for Windows PowerShell 5.1, unless they start with `#Requires -Version 7`.
- The device tests of `scripts\connected-test.ps1` end with Gradle uninstalling Kestrel, and Android then deletes Kestrel's external files folder with the model files in it. `scripts\voice-test.ps1` copies them again, which takes about 20 s on the emulator once they are in the cache on this computer.
- If the device tests become slow, or Android restarts while they run, restart the emulator with a cold boot: `& (Get-Adb) -s <serial> emu kill`, wait until it has closed, then `scripts\start-emulator.ps1 -ColdBoot`. Do not run device tests in the first two minutes after the emulator boots.
- Commit at the end of every task, and earlier when a task is long. Use a Conventional Commits subject and end every commit message with this trailer line:

  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

  In PowerShell: `git commit -m "<subject>" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"`.

## Things that surprised us while building this plan

A prototype of this plan ran on `kestrel-api37` on 15 November 2026. The code below already handles all of these points. They are written down so that nobody "fixes" them back.

- sherpa-onnx 2.0.0 removed espeak-ng and piper-phonemize, so its Piper voices need phonemes from outside sherpa-onnx. sherpa-onnx 1.13.8 still includes both, and it links the same ONNX Runtime, 1.28.2. Kestrel therefore uses 1.13.8.
- sherpa-onnx's Android library is not in a Maven repository; it is an asset of the GitHub release. An Ivy repository with the pattern `v[revision]/[artifact]-[revision].[ext]` downloads it, and the dependency needs `@aar`. With `gradle/verification-metadata.xml`, a wrong hash fails the build with "Dependency verification failed for configuration ':app:androidApis'".
- The library for the `x86` ABI comes with a separate `libonnxruntime.so`, and every ABI adds 16–27 MB. Kestrel packages only `arm64-v8a` (the phones) and `x86_64` (the emulator). Its libraries for these ABIs import no networking functions, need only Android's own libraries, and export only their JNI functions.
- The encoders of the keyword model's "-mobile" package end Kestrel's process with `SIGABRT` ("Reshape node '/downsample/Reshape_1' … {17,1,128} … {8,2,1,128}"). The regular package works.
- A keyword is a line of the keyword model's word pieces. With the single spelling `▁K EST RE L`, the spotter never heard "Kestrel"; with nine spellings, a boost of 2.0, a threshold of 0.15 and 8 active paths, it heard every "Kestrel", "stop" and "cancel" in synthetic speech and nothing in the other phrases. It matched `▁K ES T R AL`. With 4 active paths it missed "Kestrel" inside a sentence.
- A piece that is not in the keyword model's `tokens.txt`, such as `ST` or `▁KE`, makes sherpa-onnx log "Encode keywords failed", and the whole spotter fails to load.
- Piper's `en_US-ljspeech-medium` voice uses espeak-ng's voice `en`, so `espeak-ng-data/lang/gmw/en` must be on the phone; without it, sherpa-onnx logs "Failed to set eSpeak-ng voice" and returns no samples. The voice needs only 7 of the 355 files of `espeak-ng-data`.
- Parakeet's boost words need `modelingUnit = "bpe"` and a `bpeVocab` file, which the model's package lacks. A file with one line `<piece>\t-1.0` for each line of `tokens.txt` works. A file that scored each piece by its position cut a transcript short ("Call Drago") with strong boosts, so Kestrel uses the even scores and a boost of 2.0. Boost words with emoji, Chinese or Arabic letters, or 100 phrases did not crash the recogniser and did not slow it down.
- Voice output with sherpa-onnx's VITS voices varies a little each time; with `noiseScale` and `noiseScaleW` set to 0 (Kestrel's `VoiceOptions(variation = 0f)`), the same text gives the same samples every time. The device tests use that, so that their speech does not change from run to run.
- sherpa-onnx's `silero_vad.onnx` (643,854 bytes) takes the inputs `x` [1, 512], `h` [2, 1, 64] and `c` [2, 1, 64].
- Gradle's device tests uninstall Kestrel at the end, which deletes `Android/data/dev.kestrel` and the models in it. The Android Gradle plugin's property `android.injected.androidTest.leaveApksInstalledAfterRun=true` keeps the apps installed.
- `adb push` keeps the file's modification time from this computer, and the pushed files belong to the shell user with the mode `rw-rw-rw-`. Folders that the shell creates with `mkdir` and then `chmod 2775` are readable by Kestrel.
- Hugging Face re-uploaded the Parakeet int8 package on 7 November 2026 with different files. Kestrel uses the revision `2bda32ec70b097a55adaa07d9a7173915b43cc78` of 16 August 2025, which sherpa-onnx 1.x was used with.
- On `kestrel-api37`, `sha256sum` hashed the 652 MB encoder in 1.8 s, and `adb push` copied it at 36 MB/s.
- Timings on `kestrel-api37` (2 threads): the voice loaded in 1.4 s and spoke at 0.06–0.07 times real time; the recogniser loaded in 3.1–3.7 s, raised the app's memory (PSS) from 110 MB to about 800 MB, and decoded 0.5–2.7 s of speech in 100–390 ms; the keyword spotter loaded in 0.2–0.6 s.

## File map

| Path | Responsibility |
|---|---|
| `settings.gradle.kts`, `gradle/verification-metadata.xml`, `gradle/libs.versions.toml` | sherpa-onnx from its GitHub release, checked by its hash |
| `build-logic/.../AndroidDefaults.kt`, `build-logic/.../CopyAssetsTask.kt` | The two ABIs, and a task that copies repository files into an app's assets |
| `audit/rules.txt` | The pin and the licence of sherpa-onnx |
| `core/voice/` | The new module: `SherpaOnnx` (versions), `models/` (manifest, file check) and `engines/` (the four engines and their pure helpers) |
| `models/manifest.json` | Every model file: its path on the phone, size, SHA-256 hash and upstream source |
| `scripts/push-models.ps1`, `scripts/lib/Models.ps1` | Download, check and copy the model files |
| `scripts/voice-test.ps1` | Install Kestrel, copy the models and run the voice device tests, keeping the apps installed |
| `scripts/tests/Models.Tests.ps1`, `scripts/tests/fake-adb-files.ps1`, `scripts/tests/fake-adb-files.cmd` | Pester tests of the model scripts, with a fake adb that keeps files |
| `app/.../AppGraph.kt` | `models` (the file check) and `voice` (the engines) |
| `app/.../settings/` | Setup step 2 and the About page show the model files' status |
| `app/src/androidTest/.../voice/` | The device tests of the engines and of spike S8 |
| `THIRD_PARTY_NOTICES.md`, `licences/MIT.txt`, `licences/hclust-cpp.txt` | Notices for sherpa-onnx's parts and for the models |

---

### Task 1: sherpa-onnx in the build

**Files:**
- Modify: `gradle/libs.versions.toml`, `settings.gradle.kts`
- Create: `gradle/verification-metadata.xml`
- Modify: `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidDefaults.kt`
- Create: `core/voice/build.gradle.kts`, `core/voice/consumer-rules.pro`, `core/voice/src/main/kotlin/dev/kestrel/voice/SherpaOnnx.kt`
- Test: `core/voice/src/androidTest/kotlin/dev/kestrel/voice/SherpaOnnxTest.kt`
- Modify: `app/build.gradle.kts`, `audit/rules.txt`
- Modify: `THIRD_PARTY_NOTICES.md`; create `licences/MIT.txt` and `licences/hclust-cpp.txt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt`, `app/src/main/res/values/strings.xml`, `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: plan 1A's audit (`audit/rules.txt`, `kestrelAudit`) and plan 1C's About page (`Licence`, `texts`).
- Produces: the module `:core:voice` (namespace `dev.kestrel.voice`), which depends on `com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime:1.13.8@aar` with `implementation`; `object SherpaOnnx { val version: String; val onnxRuntimeVersion: String }`; the catalog version `sherpaOnnx`; `:app` depends on `:core:voice`; every module packages only the ABIs `arm64-v8a` and `x86_64`; `Licence.MIT` and `Licence.HCLUST` on the About page.

- [ ] **Step 1: Write the failing device test**

Create `core/voice/src/androidTest/kotlin/dev/kestrel/voice/SherpaOnnxTest.kt`:

```kotlin
package dev.kestrel.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaOnnxTest {
    // The audit pins sherpa-onnx 1.13.8 because it links ONNX Runtime 1.28.2, which has no Android telemetry
    // (design spec, section 13.2, B13, B14). A new version needs a new audit, and then a new expected value here.
    @Test
    fun theLibraryIsTheAuditedOne() {
        assertEquals("1.13.8", SherpaOnnx.version)
        assertEquals("1.28.2", SherpaOnnx.onnxRuntimeVersion)
    }
}
```

- [ ] **Step 2: Add the library and the module**

In `gradle/libs.versions.toml`, add this line at the end of `[versions]`:

```toml
sherpaOnnx = "1.13.8"
```

In `settings.gradle.kts`, replace the block `dependencyResolutionManagement { ... }` with this block, and add `include(":core:voice")` after `include(":core:screen")`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx publishes its Android library only as an asset of its GitHub releases. Gradle downloads it from
        // there, and gradle/verification-metadata.xml checks its SHA-256 hash (design spec, section 17).
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpa-onnx releases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}
```

Create `gradle/verification-metadata.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Gradle checks the SHA-256 hash of sherpa-onnx's Android library, which comes from a GitHub release instead of a
     Maven repository (settings.gradle.kts). Every other artifact is trusted, as before this file existed. A new version
     of sherpa-onnx needs a new audit of the ONNX Runtime that it links (audit/rules.txt) and its hash here: the digest
     that the release page shows for the asset. -->
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd">
   <configuration>
      <verify-metadata>false</verify-metadata>
      <verify-signatures>false</verify-signatures>
      <trusted-artifacts>
         <trust group="^(?!com[.]k2fsa[.]sherpa[.]onnx$).*$" regex="true"/>
      </trusted-artifacts>
   </configuration>
   <components>
      <component group="com.k2fsa.sherpa.onnx" name="sherpa-onnx-static-link-onnxruntime" version="1.13.8">
         <artifact name="sherpa-onnx-static-link-onnxruntime-1.13.8.aar">
            <sha256 value="b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471" origin="the digest on https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
```

In `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidDefaults.kt`, replace the `defaultConfig.apply { ... }` block with:

```kotlin
    defaultConfig.apply {
        minSdk { version = release(KestrelSdk.MIN) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Kestrel runs on arm64 phones and on the x86_64 emulator. Each other ABI would add 16-27 MB of sherpa-onnx's
        // native library, and the one for x86 comes with a separate copy of ONNX Runtime (design spec, section 8.2).
        ndk.abiFilters += setOf("arm64-v8a", "x86_64")
    }
```

Create `core/voice/consumer-rules.pro`:

```
# sherpa-onnx's native code reads the fields of its configuration classes and creates its result classes through JNI,
# by their names, so a shrinker must keep them. Kestrel's release build does not shrink code today.
-keep class com.k2fsa.sherpa.onnx.** { *; }
```

Create `core/voice/build.gradle.kts`:

```kotlin
plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.voice"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // sherpa-onnx's Android library with ONNX Runtime linked statically (design spec, section 8.2). It comes from the
    // GitHub release that settings.gradle.kts names, and gradle/verification-metadata.xml checks its hash.
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime:${libs.versions.sherpaOnnx.get()}@aar")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/SherpaOnnx.kt`:

```kotlin
package dev.kestrel.voice

import com.k2fsa.sherpa.onnx.VersionInfo

/** The versions of sherpa-onnx and of the ONNX Runtime that it links statically (design spec, sections 8.2 and 13.2). */
object SherpaOnnx {
    val version: String get() = VersionInfo.version

    val onnxRuntimeVersion: String get() = VersionInfo.onnxruntimeVersion
}
```

- [ ] **Step 3: Run the test**

Run: `scripts\connected-test.ps1 -Serial <serial> :core:voice:connectedDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, and `core/voice/build/outputs/androidTest-results/connected/debug/TEST-*.xml` shows `tests="1" failures="0" errors="0" skipped="0"`. The first build downloads the 39 MB library from GitHub.

- [ ] **Step 4: Check that a wrong hash fails the build**

Change the last hex digit of the `sha256` value in `gradle/verification-metadata.xml` from `1` to `2`, and run: `scripts\gradle.ps1 :core:voice:assembleDebug`
Expected: `BUILD FAILED` with "Dependency verification failed" and "sherpa-onnx-static-link-onnxruntime-1.13.8.aar". Change the digit back to `1`, and run the same command again.
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add the module to the app and to the audit**

In `app/build.gradle.kts`, add `implementation(project(":core:voice"))` after `implementation(project(":core:screen"))`, and replace the block at the end of the file with:

```kotlin
// The instrumented tests drive the fixture screens of :testapp, so it is installed first. They also turn on
// Kestrel's accessibility service, so they run after the other modules' device tests instead of alongside them.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testapp:installDebug")
    mustRunAfter(":core:privacy:connectedDebugAndroidTest", ":core:voice:connectedDebugAndroidTest")
}
```

In `audit/rules.txt`, add these lines after the line `pin com.microsoft.onnxruntime:* 1.28.*`:

```
# sherpa-onnx 1.13.8 links ONNX Runtime 1.28.2 statically (B13, B14). A new version needs a new audit of the ONNX Runtime
# that it links, and a new hash in gradle/verification-metadata.xml.
pin com.k2fsa.sherpa.onnx:* 1.13.8
```

and add these lines at the end of the file:

```
# sherpa-onnx itself is Apache-2.0. Its library also contains espeak-ng (GPL-3.0-or-later) and piper-phonemize
# (GPL-3.0), so the library as a whole is under the GPL, version 3 (THIRD_PARTY_NOTICES.md).
allow com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime GPL-3.0-only
```

Run: `scripts\gradle.ps1 :app:assembleDebug kestrelAudit`
Expected: `BUILD SUCCESSFUL`. `app/build/reports/kestrel-audit/dependencies-debug.txt` lists `com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime:1.13.8` and ends with `All dependencies passed.`; `app/build/reports/kestrel-audit/native-libs-debug.txt` lists `lib/arm64-v8a/libsherpa-onnx-jni.so` and `lib/x86_64/libsherpa-onnx-jni.so`, no other ABI, and ends with `No networking imports found.`

- [ ] **Step 6: Commit**

```powershell
git add gradle/libs.versions.toml settings.gradle.kts gradle/verification-metadata.xml build-logic core/voice app/build.gradle.kts audit/rules.txt
git commit -m "build: add sherpa-onnx 1.13.8 with static ONNX Runtime in :core:voice" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 7: Add the notices and the licence texts**

In `THIRD_PARTY_NOTICES.md`, replace the first paragraph with:

```markdown
Kestrel's release build includes the libraries below. Their licences are the Apache License 2.0, the MIT License, the Mozilla Public License 2.0, a BSD 2-clause licence and the GNU General Public License, version 3, and all of them are compatible with Kestrel's licence, GPL-3.0-or-later. The exact library versions are in `app/build/reports/kestrel-audit/dependencies-release.txt` after `scripts\gradle.ps1 kestrelAudit` runs.
```

add this row at the end of the table:

```markdown
| sherpa-onnx 1.13.8, the Android library with ONNX Runtime linked statically (`com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime`) | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
```

and replace the last paragraph ("Later sub-projects add speech models, …") with this section, so that it ends the file; later sub-projects add their notices themselves:

```markdown
## The parts of sherpa-onnx's library

sherpa-onnx's build links these projects into its library `libsherpa-onnx-jni.so`, so Kestrel contains them too. Because the library contains espeak-ng and piper-phonemize, the library as a whole is under the GNU General Public License, version 3, like Kestrel. The versions are those that sherpa-onnx 1.13.8's build files name.

| Component | Licence | Project |
|---|---|---|
| ONNX Runtime 1.28.2, Copyright (c) Microsoft Corporation | MIT | https://github.com/microsoft/onnxruntime |
| espeak-ng, built from https://github.com/csukuangfj/espeak-ng at commit ed530aa | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |
| piper-phonemize, built from https://github.com/csukuangfj/piper-phonemize at commit f3ff95a | GPL-3.0 | https://github.com/rhasspy/piper-phonemize |
| kaldi-native-fbank 1.22.3 | Apache-2.0 | https://github.com/csukuangfj/kaldi-native-fbank |
| kaldi-decoder 0.3.0 | Apache-2.0 | https://github.com/k2-fsa/kaldi-decoder |
| simple-sentencepiece | Apache-2.0 | https://github.com/pkufool/simple-sentencepiece |
| OpenFst, built from https://github.com/csukuangfj/openfst at v1.8.5-2026-07-09 | Apache-2.0 | https://www.openfst.org |
| Eigen 5.0.1 | MPL-2.0 | https://gitlab.com/libeigen/eigen |
| hclust-cpp, with fastcluster, Copyright 2011 Daniel Müllner and 2018 Christoph Dalitz | BSD-2-Clause | https://github.com/cdalitz/hclust-cpp |
| JSON for Modern C++ 3.12.0, Copyright (c) 2013-2025 Niels Lohmann | MIT | https://github.com/nlohmann/json |
```

In the paragraph that starts "The texts of the Apache License 2.0", replace its first sentence with: "The texts of the Apache License 2.0, the Mozilla Public License 2.0, the MIT License and hclust-cpp's BSD 2-clause licence are in the `licences` folder; the first two are copied from https://www.apache.org/licenses/LICENSE-2.0.txt and https://www.mozilla.org/media/MPL/2.0/index.txt." Kestrel's own licence, in `LICENSE`, is the text of the GNU General Public License, version 3.

Create `licences/MIT.txt`:

```
MIT License

These components use the MIT License, each with its own copyright notice:

- ONNX Runtime: Copyright (c) Microsoft Corporation
- JSON for Modern C++: Copyright (c) 2013-2025 Niels Lohmann

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Create `licences/hclust-cpp.txt` with the text of https://github.com/csukuangfj/hclust-cpp/blob/2026-02-25/LICENSE, which is:

```
Copyright:
  * fastcluster_dm.cpp & fastcluster_R_dm.cpp:
     © 2011 Daniel Müllner <http://danifold.net>
  * fastcluster.(h|cpp) & demo.cpp & plotresult.r:
     © 2018 Christoph Dalitz <http://www.hsnr.de/ipattern/>
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

  * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
  * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

The build already copies every file in `licences` into the app's assets. In `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt`, replace the enum `Licence` and the function `texts` with:

```kotlin
/** The texts that the About page shows. The build copies them from the repository into the app's assets. */
enum class Licence(val asset: String) {
    KESTREL("licences/LICENSE"),
    NOTICES("licences/THIRD_PARTY_NOTICES.md"),
    APACHE("licences/Apache-2.0.txt"),
    MIT("licences/MIT.txt"),
    MPL("licences/MPL-2.0.txt"),
    HCLUST("licences/hclust-cpp.txt"),
}
```

```kotlin
/** The title and summary of a licence on the About page. */
private fun texts(licence: Licence): Pair<Int, Int> = when (licence) {
    Licence.KESTREL -> R.string.about_licence_kestrel to R.string.about_licence_kestrel_summary
    Licence.NOTICES -> R.string.about_licence_notices to R.string.about_licence_notices_summary
    Licence.APACHE -> R.string.about_licence_apache to R.string.about_licence_apache_summary
    Licence.MIT -> R.string.about_licence_mit to R.string.about_licence_mit_summary
    Licence.MPL -> R.string.about_licence_mpl to R.string.about_licence_mpl_summary
    Licence.HCLUST -> R.string.about_licence_hclust to R.string.about_licence_hclust_summary
}
```

In `app/src/main/res/values/strings.xml`, replace the line `about_licence_notices_summary` and add the new strings after `about_licence_mpl_summary`:

```xml
    <string name="about_licence_notices_summary">The libraries and speech models in Kestrel and their licences</string>
```

```xml
    <string name="about_licence_mit">MIT License</string>
    <string name="about_licence_mit_summary">The licence of ONNX Runtime, JSON for Modern C++ and some speech models</string>
    <string name="about_licence_hclust">hclust-cpp\'s BSD licence</string>
    <string name="about_licence_hclust_summary">The licence of the clustering code in sherpa-onnx</string>
```

In `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`, the test `theAboutPageShowsTheTextsThatTheBuildBundles` opens every licence and looks for a text from its body. Add these two entries to its map `expected`, after the entry for `about_licence_apache` (the licence page's title already contains "MIT License", so the test looks for a sentence of the body):

```kotlin
            R.string.about_licence_mit to "Permission is hereby granted, free of charge",
            R.string.about_licence_hclust to "Redistribution and use in source and binary forms",
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest` and then `scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.MainActivityTest,dev.kestrel.app.settings.SettingsAppTest'`
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 8: Document the download**

In `README.md`, add this paragraph after the paragraph that starts "`check` runs the privacy audit":

```markdown
The first build downloads sherpa-onnx's Android library (39 MB), which contains the speech engines, from sherpa-onnx's GitHub release. Gradle checks its SHA-256 hash against `gradle/verification-metadata.xml`. The library contains its own copy of ONNX Runtime, so a new version of sherpa-onnx needs a new audit of that copy (`audit/rules.txt`) and a new hash.
```

- [ ] **Step 9: Commit**

```powershell
git add THIRD_PARTY_NOTICES.md licences app/src README.md
git commit -m "docs: notices and licence texts for sherpa-onnx's parts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: The model manifest

**Files:**
- Create: `models/manifest.json`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/CopyAssetsTask.kt`
- Modify: `app/build.gradle.kts` (its licence assets use `CopyAssetsTask`)
- Modify: `core/voice/build.gradle.kts`
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelManifestTest.kt`
- Modify: `THIRD_PARTY_NOTICES.md`, `licences/MIT.txt`

**Interfaces:**
- Consumes: `:core:voice` from Task 1.
- Produces: `models/manifest.json` (format 1), which Task 5's script reads too; in package `dev.kestrel.voice.models`: `enum class Engine { VAD, KWS, ASR, TTS }`, `data class ModelFile(path: String, size: Long, sha256: String, url: String? = null, archive: String? = null, member: String? = null)`, `data class Model(id: String, engine: Engine, name: String, licence: String, project: String, files: List<ModelFile>)`, `data class Archive(id: String, url: String, size: Long, sha256: String)`, `data class ModelManifest(format: Int, archives: List<Archive> = emptyList(), models: List<Model>)` with `ModelManifest.ASSET = "models/manifest.json"`, `ModelManifest.parse(text: String)` and `ModelManifest.fromAssets(assets: AssetManager)`; the app's assets contain `models/manifest.json`; `dev.kestrel.build.CopyAssetsTask` (properties `files`, `folder` and `outputDirectory`).

The manifest's paths are relative to Kestrel's folder `files/models` on the phone. The engines of Tasks 6–8 use these paths: `vad-silero/silero_vad.onnx`; `kws-gigaspeech/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx`, `kws-gigaspeech/decoder-epoch-12-avg-2-chunk-16-left-64.onnx`, `kws-gigaspeech/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` and `kws-gigaspeech/tokens.txt`; `asr-parakeet-v3/encoder.int8.onnx`, `asr-parakeet-v3/decoder.int8.onnx`, `asr-parakeet-v3/joiner.int8.onnx` and `asr-parakeet-v3/tokens.txt`; `tts-piper-ljspeech/en_US-ljspeech-medium.onnx` and `tts-piper-ljspeech/tokens.txt`; and the folder `espeak-ng-data`.

- [ ] **Step 1: Write the failing test**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelManifestTest.kt`:

```kotlin
package dev.kestrel.voice.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the repository's `models/manifest.json`, which both the app and scripts\push-models.ps1 read. */
class ModelManifestTest {
    // Unknown fields fail here, so that a misspelt field name cannot hide a missing value.
    private val manifest = Json.decodeFromString(ModelManifest.serializer(), File("../../models/manifest.json").readText())
    private val files = manifest.models.flatMap { it.files }

    @Test
    fun everyEngineHasAModel() {
        assertEquals(1, manifest.format)
        assertEquals(Engine.entries.toSet(), manifest.models.map { it.engine }.toSet())
    }

    @Test
    fun modelsAndPathsAreUnique() {
        assertEquals(manifest.models.size, manifest.models.map { it.id }.toSet().size)
        assertEquals(files.size, files.map { it.path }.toSet().size)
    }

    @Test
    fun everyFileHasASafePathASizeAndAHash() {
        for (file in files) {
            assertTrue(file.path, SAFE_PATH.matches(file.path))
            assertFalse(file.path, file.path.split('/').any { it == "." || it == ".." })
            assertTrue(file.path, file.size > 0)
            assertTrue(file.path, HASH.matches(file.sha256))
        }
    }

    @Test
    fun everyFileHasOneSource() {
        val archives = manifest.archives.map { it.id }.toSet()
        for (file in files) {
            if (file.url != null) {
                assertTrue(file.path, file.url.startsWith("https://"))
                assertNull(file.path, file.archive)
                assertNull(file.path, file.member)
            } else {
                assertTrue(file.path, file.archive in archives)
                assertNotNull(file.path, file.member)
            }
        }
    }

    @Test
    fun everyArchiveIsUsedAndChecked() {
        val used = files.mapNotNull { it.archive }.toSet()
        for (archive in manifest.archives) {
            assertTrue(archive.id, archive.id in used)
            assertTrue(archive.id, archive.url.startsWith("https://"))
            assertTrue(archive.id, archive.size > 0)
            assertTrue(archive.id, HASH.matches(archive.sha256))
        }
    }

    @Test
    fun theAppIgnoresFieldsThatItDoesNotKnow() {
        val text = """{"format":1,"later":true,"models":[{"id":"m","engine":"vad","name":"M","licence":"MIT",""" +
            """"project":"https://example.org","files":[{"path":"m/a.onnx","size":1,"sha256":"${"0".repeat(64)}",""" +
            """"url":"https://example.org/a","later":1}]}]}"""

        assertEquals("m/a.onnx", ModelManifest.parse(text).models.single().files.single().path)
    }

    private companion object {
        val SAFE_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
        val HASH = Regex("[0-9a-f]{64}")
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD FAILED`, because `ModelManifest` does not exist.

- [ ] **Step 3: Write the manifest and its parser**

Create `models/manifest.json`:

```json
{
  "format": 1,
  "archives": [
    {
      "id": "kws-gigaspeech",
      "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2",
      "size": 17626723,
      "sha256": "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
    },
    {
      "id": "tts-piper-ljspeech",
      "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium.tar.bz2",
      "size": 67169893,
      "sha256": "3dfb4b759d8be032a4903a9538d128b0fda2a06ab1de6cbc2d93a97e2dd83dba"
    }
  ],
  "models": [
    {
      "id": "vad-silero",
      "engine": "vad",
      "name": "Silero VAD",
      "licence": "MIT",
      "project": "https://github.com/snakers4/silero-vad",
      "files": [
        {
          "path": "vad-silero/silero_vad.onnx",
          "size": 643854,
          "sha256": "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
          "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        }
      ]
    },
    {
      "id": "kws-gigaspeech",
      "engine": "kws",
      "name": "sherpa-onnx English keyword model (GigaSpeech, 3.3M)",
      "licence": "Apache-2.0",
      "project": "https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html",
      "files": [
        {
          "path": "kws-gigaspeech/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
          "size": 4807159,
          "sha256": "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678",
          "archive": "kws-gigaspeech",
          "member": "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        },
        {
          "path": "kws-gigaspeech/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
          "size": 1063189,
          "sha256": "f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3",
          "archive": "kws-gigaspeech",
          "member": "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        },
        {
          "path": "kws-gigaspeech/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
          "size": 163380,
          "sha256": "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c",
          "archive": "kws-gigaspeech",
          "member": "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        },
        {
          "path": "kws-gigaspeech/tokens.txt",
          "size": 5006,
          "sha256": "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53",
          "archive": "kws-gigaspeech",
          "member": "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/tokens.txt"
        }
      ]
    },
    {
      "id": "asr-parakeet-v3",
      "engine": "asr",
      "name": "Parakeet-TDT-0.6B-v3, int8",
      "licence": "CC-BY-4.0",
      "project": "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
      "files": [
        {
          "path": "asr-parakeet-v3/encoder.int8.onnx",
          "size": 652184281,
          "sha256": "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247",
          "url": "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/encoder.int8.onnx"
        },
        {
          "path": "asr-parakeet-v3/decoder.int8.onnx",
          "size": 11845275,
          "sha256": "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e",
          "url": "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/decoder.int8.onnx"
        },
        {
          "path": "asr-parakeet-v3/joiner.int8.onnx",
          "size": 6355277,
          "sha256": "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3",
          "url": "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/joiner.int8.onnx"
        },
        {
          "path": "asr-parakeet-v3/tokens.txt",
          "size": 93939,
          "sha256": "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
          "url": "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/tokens.txt"
        }
      ]
    },
    {
      "id": "tts-piper-ljspeech",
      "engine": "tts",
      "name": "Piper voice en_US-ljspeech-medium",
      "licence": "MIT",
      "project": "https://huggingface.co/rhasspy/piper-voices",
      "files": [
        {
          "path": "tts-piper-ljspeech/en_US-ljspeech-medium.onnx",
          "size": 63531507,
          "sha256": "8ceba58a4b540d4e7e7e24ad079cf0d92762a4fd334e059f12940787c6c37b3d",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/en_US-ljspeech-medium.onnx"
        },
        {
          "path": "tts-piper-ljspeech/tokens.txt",
          "size": 940,
          "sha256": "ef3a7e4a8d1af0c9d4dc45aaae1a6242ebe24a7ed6f3d025a49eb29682784c6d",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/tokens.txt"
        }
      ]
    },
    {
      "id": "tts-espeak-ng-data",
      "engine": "tts",
      "name": "espeak-ng's data for English",
      "licence": "GPL-3.0-or-later",
      "project": "https://github.com/espeak-ng/espeak-ng",
      "files": [
        {
          "path": "espeak-ng-data/phontab",
          "size": 55796,
          "sha256": "886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/phontab"
        },
        {
          "path": "espeak-ng-data/phonindex",
          "size": 39074,
          "sha256": "3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/phonindex"
        },
        {
          "path": "espeak-ng-data/phondata",
          "size": 550424,
          "sha256": "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/phondata"
        },
        {
          "path": "espeak-ng-data/intonations",
          "size": 2040,
          "sha256": "3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/intonations"
        },
        {
          "path": "espeak-ng-data/en_dict",
          "size": 166944,
          "sha256": "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/en_dict"
        },
        {
          "path": "espeak-ng-data/lang/gmw/en",
          "size": 140,
          "sha256": "4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/lang/gmw/en"
        },
        {
          "path": "espeak-ng-data/lang/gmw/en-US",
          "size": 257,
          "sha256": "41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704",
          "archive": "tts-piper-ljspeech",
          "member": "vits-piper-en_US-ljspeech-medium/espeak-ng-data/lang/gmw/en-US"
        }
      ]
    }
  ]
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelManifest.kt`:

```kotlin
package dev.kestrel.voice.models

import android.content.res.AssetManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kestrel's speech engines (design spec, section 8.2). */
@Serializable
enum class Engine {
    @SerialName("vad") VAD,
    @SerialName("kws") KWS,
    @SerialName("asr") ASR,
    @SerialName("tts") TTS,
}

/**
 * One file of a model: its [path] below Kestrel's folder `files/models`, its [size] in bytes and its [sha256] hash in
 * lowercase hex. [url], or [archive] and [member], say where scripts\push-models.ps1 gets the file; the app ignores them.
 */
@Serializable
data class ModelFile(
    val path: String,
    val size: Long,
    val sha256: String,
    val url: String? = null,
    val archive: String? = null,
    val member: String? = null,
)

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

/** A download that holds several model files, which scripts\push-models.ps1 unpacks. */
@Serializable
data class Archive(val id: String, val url: String, val size: Long, val sha256: String)

/** The repository's `models/manifest.json`, which the build copies into the app's assets (design spec, section 8.10). */
@Serializable
data class ModelManifest(val format: Int, val archives: List<Archive> = emptyList(), val models: List<Model>) {
    companion object {
        /** The manifest's path in the app's assets. */
        const val ASSET = "models/manifest.json"

        // A newer manifest may have fields that this version of Kestrel does not know.
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ModelManifest = json.decodeFromString(serializer(), text)

        fun fromAssets(assets: AssetManager): ModelManifest = parse(assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
```

Create `build-logic/convention/src/main/kotlin/dev/kestrel/build/CopyAssetsTask.kt`:

```kotlin
package dev.kestrel.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Copies files from the repository into one folder of an app's assets. Register it with
 * `variant.sources.assets?.addGeneratedSourceDirectory(task, CopyAssetsTask::outputDirectory)`. The About page's licence
 * texts and the models' manifest reach the app this way.
 */
abstract class CopyAssetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val files: ConfigurableFileCollection

    /** The folder in the assets, such as `licences`. */
    @get:Input
    abstract val folder: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystem.sync {
            from(files)
            into(outputDirectory.dir(folder))
        }
    }
}
```

In `app/build.gradle.kts`, delete the line `import javax.inject.Inject` and the class `LicenceAssets` with its comment, add `import dev.kestrel.build.CopyAssetsTask` as the first line, and replace the block `androidComponents { ... }` with:

```kotlin
androidComponents {
    onVariants { variant ->
        // Kestrel's licence, the third-party notices and the licence texts in `licences`, which the About page shows
        // (design spec, section 15).
        val licences = tasks.register<CopyAssetsTask>("${variant.name}LicenceAssets") {
            val root = rootProject.layout.projectDirectory
            files.from(root.file("LICENSE"), root.file("THIRD_PARTY_NOTICES.md"), root.dir("licences").asFileTree)
            folder.set("licences")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licences, CopyAssetsTask::outputDirectory)
    }
}
```

Replace `core/voice/build.gradle.kts` with:

```kotlin
import dev.kestrel.build.CopyAssetsTask

plugins {
    id("kestrel.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.voice"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

androidComponents {
    onVariants { variant ->
        // The models' manifest, whose hashes Kestrel checks the model files against (design spec, section 8.10).
        val manifest = tasks.register<CopyAssetsTask>("${variant.name}ModelManifestAsset") {
            files.from(rootProject.layout.projectDirectory.file("models/manifest.json"))
            folder.set("models")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(manifest, CopyAssetsTask::outputDirectory)
    }
}

// ModelManifestTest reads the repository's manifest.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("models/manifest.json"))
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("modelManifest")
}

dependencies {
    // sherpa-onnx's Android library with ONNX Runtime linked statically (design spec, section 8.2). It comes from the
    // GitHub release that settings.gradle.kts names, and gradle/verification-metadata.xml checks its hash.
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime:${libs.versions.sherpaOnnx.get()}@aar")
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

- [ ] **Step 4: Run the tests**

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, with 6 tests in `core/voice/build/test-results/testDebugUnitTest/`.

Run: `tar -tf app\build\outputs\apk\debug\app-debug.apk | Select-String '^assets/(models|licences)/'`
Expected: `assets/models/manifest.json` and the six files of `assets/licences/`, including `MIT.txt` and `hclust-cpp.txt`.

- [ ] **Step 5: Add the models to the notices**

In `THIRD_PARTY_NOTICES.md`, add this section at the end of the file, after the section that Task 1 added:

```markdown
## Speech models

Kestrel's app does not contain its speech models. `scripts\push-models.ps1` downloads them from the projects below and copies them to the phone, and `models/manifest.json` lists every file with its SHA-256 hash.

| Model | Licence | Project |
|---|---|---|
| Silero VAD, Copyright (c) 2020-present Silero Team | MIT | https://github.com/snakers4/silero-vad |
| sherpa-onnx's English keyword model `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, trained on GigaSpeech XL | Apache-2.0 | https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html |
| Parakeet-TDT-0.6B-v3, int8 | CC-BY-4.0 | https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 |
| Piper's voice `en_US-ljspeech-medium`, trained on the LJ Speech data, which is in the public domain | MIT | https://huggingface.co/rhasspy/piper-voices |
| espeak-ng's data for English, from the voice's sherpa-onnx package | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |

Parakeet-TDT-0.6B-v3 was created by NVIDIA (https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) and is licensed under the Creative Commons Attribution 4.0 International licence (https://creativecommons.org/licenses/by/4.0/). The sherpa-onnx project converted it to ONNX and quantised it to int8 (https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8); Kestrel uses that version unchanged.
```

In `licences/MIT.txt`, add these two lines after the line for JSON for Modern C++:

```
- Silero VAD: Copyright (c) 2020-present Silero Team
- Piper's voices: the Piper voices repository states the MIT License, without a copyright line
```

- [ ] **Step 6: Commit**

```powershell
git add models build-logic core/voice app/build.gradle.kts THIRD_PARTY_NOTICES.md licences/MIT.txt
git commit -m "feat(voice): model manifest in the app's assets" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 3: Checking the model files on the phone

**Files:**
- Modify: `core/voice/build.gradle.kts` (coroutines)
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ModelFolderTest.kt`

**Interfaces:**
- Consumes: `ModelManifest`, `Model`, `ModelFile` and `ModelManifest.fromAssets` (Task 2).
- Produces: in package `dev.kestrel.voice.models`: `data class ModelStatus(model: Model, missing: List<String>, damaged: List<String>) { val ready: Boolean }`; `class ModelFiles(root: File, manifest: ModelManifest, hashCache: File, hash: (File) -> String = ::sha256)` with `val root`, `val manifest`, `fun file(path: String): File` and `suspend fun check(): List<ModelStatus>` (one status for each model, in the manifest's order); `fun sha256(file: File): String`. `AppGraph.models: ModelFiles`, whose root is the folder `files/models` in Kestrel's external files folder; Kestrel creates that folder when it starts.

- [ ] **Step 1: Write the failing tests**

Add `implementation(libs.kotlinx.coroutines.android)` and `testImplementation(libs.kotlinx.coroutines.test)` to the `dependencies` of `core/voice/build.gradle.kts`.

Create `core/voice/src/test/kotlin/dev/kestrel/voice/models/ModelFilesTest.kt`:

```kotlin
package dev.kestrel.voice.models

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var cache: File
    private val hashed = mutableListOf<String>()

    @Before
    fun setUp() {
        root = folder.newFolder("models")
        cache = File(folder.root, "private/model-hashes.json")
    }

    private fun manifest(vararg files: Pair<String, String>) = ModelManifest(
        format = 1,
        models = listOf(
            Model(
                id = "m",
                engine = Engine.ASR,
                name = "M",
                licence = "MIT",
                project = "https://example.org",
                files = files.map { (path, content) ->
                    ModelFile(path, content.length.toLong(), hashOf(content), url = "https://example.org/$path")
                },
            ),
        ),
    )

    private fun modelFiles(manifest: ModelManifest) = ModelFiles(root, manifest, cache) { file ->
        hashed += file.name
        sha256(file)
    }

    private fun write(path: String, content: String) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(content)
    }

    private fun hashOf(content: String) =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun aMissingFileIsMissing() = runTest {
        val status = modelFiles(manifest("m/a.onnx" to "abc")).check().single()

        assertEquals(listOf("m/a.onnx"), status.missing)
        assertFalse(status.ready)
    }

    @Test
    fun aFileOfTheWrongSizeIsDamagedWithoutBeingRead() = runTest {
        write("m/a.onnx", "abcd")

        val status = modelFiles(manifest("m/a.onnx" to "abc")).check().single()

        assertEquals(listOf("m/a.onnx"), status.damaged)
        assertEquals(emptyList<String>(), hashed)
    }

    @Test
    fun aFileWithOtherContentIsDamaged() = runTest {
        write("m/a.onnx", "abd")

        assertEquals(listOf("m/a.onnx"), modelFiles(manifest("m/a.onnx" to "abc")).check().single().damaged)
    }

    @Test
    fun aModelWithAllItsFilesIsReady() = runTest {
        write("m/a.onnx", "abc")
        write("m/data/b", "de")

        val status = modelFiles(manifest("m/a.onnx" to "abc", "m/data/b" to "de")).check().single()

        assertTrue(status.ready)
        assertTrue(cache.isFile)
    }

    @Test
    fun anUnchangedFileIsHashedOnlyOnce() = runTest {
        write("m/a.onnx", "abc")
        val manifest = manifest("m/a.onnx" to "abc")

        modelFiles(manifest).check()
        val second = modelFiles(manifest).check().single()

        assertTrue(second.ready)
        assertEquals(listOf("a.onnx"), hashed)
    }

    @Test
    fun aFileWithANewModificationTimeIsHashedAgain() = runTest {
        val file = write("m/a.onnx", "abc")
        val manifest = manifest("m/a.onnx" to "abc")
        modelFiles(manifest).check()

        file.setLastModified(file.lastModified() - 60_000)
        modelFiles(manifest).check()

        assertEquals(listOf("a.onnx", "a.onnx"), hashed)
    }

    @Test
    fun aBrokenCacheIsIgnored() = runTest {
        write("m/a.onnx", "abc")
        cache.parentFile!!.mkdirs()
        cache.writeText("not json")

        assertTrue(modelFiles(manifest("m/a.onnx" to "abc")).check().single().ready)
    }

    @Test
    fun theHashIsLowercaseHex() {
        val file = write("x", "abc")

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256(file))
    }
}
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD FAILED`, because `ModelFiles` does not exist.

- [ ] **Step 2: Write `ModelFiles`**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/models/ModelFiles.kt`:

```kotlin
package dev.kestrel.voice.models

import kotlinx.coroutines.Dispatchers
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
 * private storage, because anyone with adb can change the files under [root].
 */
class ModelFiles(
    val root: File,
    val manifest: ModelManifest,
    private val hashCache: File,
    private val hash: (File) -> String = ::sha256,
) {
    private val lock = Mutex()

    /** Where a path from the manifest lives on this phone. */
    fun file(path: String): File = File(root, path)

    /** Checks every model, in the manifest's order. Hashing a large file takes seconds, so this runs on [Dispatchers.IO]. */
    suspend fun check(): List<ModelStatus> = withContext(Dispatchers.IO) {
        lock.withLock {
            val cached = readCache()
            val known = mutableMapOf<String, CachedHash>()
            val statuses = manifest.models.map { model ->
                val missing = mutableListOf<String>()
                val damaged = mutableListOf<String>()
                for (entry in model.files) {
                    val file = file(entry.path)
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
            if (known != cached) writeCache(known)
            statuses
        }
    }

    private fun hashOf(file: File, path: String, cached: Map<String, CachedHash>, known: MutableMap<String, CachedHash>): String {
        val size = file.length()
        val modified = file.lastModified()
        val hash = cached[path]?.takeIf { it.size == size && it.modified == modified }?.sha256 ?: hash(file)
        known[path] = CachedHash(size, modified, hash)
        return hash
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

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with 14 tests in `:core:voice`.

- [ ] **Step 3: Write the failing device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/ModelFolderTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.voice.models.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelFolderTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    // scripts\push-models.ps1 copies the models into this folder. On Android 17, Kestrel can read a folder that adb
    // creates only after a chmod, so Kestrel creates the folder itself (design spec, section 8.10).
    @Test
    fun kestrelCreatesItsModelsFolder() {
        val folder = graph.models.root
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!folder.isDirectory && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)

        assertTrue(folder.path, folder.isDirectory)
        assertTrue(folder.path, folder.path.endsWith("/Android/data/dev.kestrel/files/models"))
    }

    @Test
    fun theManifestComesWithTheApp() {
        assertEquals(Engine.entries.toSet(), graph.models.manifest.models.map { it.engine }.toSet())
    }
}
```

- [ ] **Step 4: Give the app its model files**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, add the imports `dev.kestrel.voice.models.ModelFiles`, `dev.kestrel.voice.models.ModelManifest` and `java.io.File`; add `private val appContext: Context = context.applicationContext` as the first line of the class body; and add this property after the property `network`:

```kotlin
    /**
     * Kestrel's speech model files, which scripts\push-models.ps1 copies into Kestrel's external files folder (design
     * spec, section 8.10). Built on first use, because finding that folder touches the disk. If shared storage is not
     * available, the models count as missing.
     */
    val models: ModelFiles by lazy {
        ModelFiles(
            root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models"),
            manifest = ModelManifest.fromAssets(appContext.assets),
            hashCache = File(appContext.noBackupFilesDir, "model-hashes.json"),
        )
    }
```

In `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`, replace the body of `onCreate` with:

```kotlin
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch { graph.logs.purgeExpired() }
        // Kestrel creates the folder for its models itself: on Android 17 it can read a folder that adb creates only
        // after a chmod (design spec, section 8.10).
        graph.scope.launch { graph.models.root.mkdirs() }
```

Run: `scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.voice.ModelFolderTest'`
Expected: `BUILD SUCCESSFUL` with 2 tests.

- [ ] **Step 5: Commit**

```powershell
git add core/voice app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt app/src/androidTest/kotlin/dev/kestrel/app/voice
git commit -m "feat(voice): check the model files against the manifest's hashes" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 4: The model files in the settings app

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `ChecklistPage.kt`, `AboutPage.kt`, `SettingsApp.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`, `README.md`
- Test: `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`

**Interfaces:**
- Consumes: `ModelStatus`, `Model`, `Engine` and `AppGraph.models.check()` (Tasks 2 and 3).
- Produces: `SetupStatus.models: List<ModelStatus>? = null`, which is null until Kestrel has checked the model files; setup step 2 (`SetupStep.COPY_MODELS`) is `DONE` when every model is ready and `TO_DO` otherwise; `ChecklistPage(states, models, onAction, onBack)` and `AboutPage(data, models, onOpen, onBack)`.

Plan 1C's `MainActivity` exports the logs through `writeLogExport` (`LogExportFile.kt`); keep that call.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, change `SetupStep.COPY_MODELS to StepState.LATER` to `SetupStep.COPY_MODELS to StepState.TO_DO` in the test `aNewInstallStillHasTheAndroidStepsToDo`, add the imports `dev.kestrel.voice.models.Engine`, `dev.kestrel.voice.models.Model` and `dev.kestrel.voice.models.ModelStatus`, and add this test:

```kotlin
    @Test
    fun theModelStepIsDoneOnlyWhenEveryModelIsReady() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.COPY_MODELS)
        val voice = Model("tts", Engine.TTS, "Voice", "MIT", "https://example.org", emptyList())
        val speech = Model("asr", Engine.ASR, "Speech", "CC-BY-4.0", "https://example.org", emptyList())
        val ready = ModelStatus(voice, emptyList(), emptyList())

        // Kestrel has not checked the files yet.
        assertEquals(StepState.TO_DO, state(nothingSetUp))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(models = listOf(ready, ModelStatus(speech, listOf("a"), emptyList())))))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(models = listOf(ready, ModelStatus(speech, emptyList(), listOf("a"))))))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(models = listOf(ready, ModelStatus(speech, emptyList(), emptyList())))))
    }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, add the imports `dev.kestrel.voice.models.Engine`, `dev.kestrel.voice.models.Model` and `dev.kestrel.voice.models.ModelStatus`, add `models = listOf(READY_VOICE)` to `EVERYTHING_SET_UP`, add these values at the start of the companion object, before `NOTHING_SET_UP` (Kotlin initialises them in this order, and `EVERYTHING_SET_UP` uses `READY_VOICE`):

```kotlin
        val VOICE = Model("tts", Engine.TTS, "Voice", "MIT", "https://example.org", emptyList())
        val SPEECH = Model("asr", Engine.ASR, "Speech", "CC-BY-4.0", "https://example.org", emptyList())
        val READY_VOICE = ModelStatus(VOICE, emptyList(), emptyList())
```

and add these tests:

```kotlin
    @Test
    fun theModelStepSaysWhileKestrelChecks() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_models_checking)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theModelStepNamesTheFilesThatAreMissingOrDamaged() {
        val speech = ModelStatus(SPEECH, missing = listOf("asr/encoder.onnx"), damaged = listOf("asr/tokens.txt"))
        show(NOTHING_SET_UP.copy(models = listOf(READY_VOICE, speech)))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_models_missing, "asr/encoder.onnx")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_models_damaged, "asr/tokens.txt")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAboutPageShowsEachModel() {
        val speech = ModelStatus(SPEECH, missing = listOf("asr/encoder.onnx", "asr/tokens.txt"), damaged = emptyList())
        show(NOTHING_SET_UP.copy(models = listOf(READY_VOICE, speech)))
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.about_model_ready, "Voice (MIT)")).performScrollTo().assertIsDisplayed()
        val missing = compose.activity.resources.getQuantityString(R.plurals.about_model_missing, 2, "Speech (CC-BY-4.0)", 2)
        compose.onNodeWithText(missing).performScrollTo().assertIsDisplayed()
    }
```

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`
Expected: `BUILD FAILED`, because `SetupStatus` has no `models`.

- [ ] **Step 2: Show the model files**

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, add `import dev.kestrel.voice.models.ModelStatus`, replace the class `SetupStatus` with:

```kotlin
/** What Kestrel can find out about its own setup on the phone. [models] is null until Kestrel has checked the model files. */
data class SetupStatus(
    val serviceOn: Boolean,
    val microphoneAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val voiceAccessOn: Boolean,
    val models: List<ModelStatus>? = null,
)
```

and replace the line `SetupStep.COPY_MODELS, SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER` with:

```kotlin
            SetupStep.COPY_MODELS -> doneIf(status.models?.all { it.ready } == true)
            SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, add `import dev.kestrel.voice.models.ModelStatus`, and replace the functions `ChecklistPage` and `StepCard` with:

```kotlin
/** The setup checklist (design spec, section 15). [models] is null until Kestrel has checked the model files. */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: List<ModelStatus>?,
    onAction: (SetupAction) -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) {
        StepCard(step, states.getValue(step), onAction) {
            if (step == SetupStep.COPY_MODELS) ModelProblems(models)
        }
    }
}

@Composable
private fun StepCard(step: SetupStep, state: StepState, onAction: (SetupAction) -> Unit, details: @Composable () -> Unit) {
    val texts = texts(step)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(texts.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(stateLabel(state)), style = MaterialTheme.typography.labelLarge)
            val detail = if (state == StepState.WARNING && texts.warning != null) texts.warning else texts.detail
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
            details()
            if (texts.action != null && texts.actionLabel != null && state in NEEDS_ACTION) {
                Button(onClick = { onAction(texts.action) }) { Text(stringResource(texts.actionLabel)) }
            }
        }
    }
}

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

In `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt`, add the imports `androidx.compose.ui.res.pluralStringResource` and `dev.kestrel.voice.models.ModelStatus`, and replace the function `AboutPage` with:

```kotlin
/** The About page: versions, the status of the model files and the licences (design spec, section 15). */
@Composable
internal fun AboutPage(data: SettingsData, models: List<ModelStatus>?, onOpen: (Licence) -> Unit, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.settings_about), onBack)
    Text(stringResource(R.string.about_version, data.version), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
    Text(stringResource(R.string.about_models), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    if (models == null) {
        Text(stringResource(R.string.about_models_checking))
    } else {
        for (status in models) Text(modelLine(status))
    }
    Text(stringResource(R.string.about_licences), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    for (licence in Licence.entries) {
        val texts = texts(licence)
        PageEntry(stringResource(texts.first), stringResource(texts.second)) { onOpen(licence) }
    }
}

/** A model's name and licence, and whether its files are ready. */
@Composable
private fun modelLine(status: ModelStatus): String {
    val name = "${status.model.name} (${status.model.licence})"
    return when {
        status.missing.isNotEmpty() -> pluralStringResource(R.plurals.about_model_missing, status.missing.size, name, status.missing.size)
        status.damaged.isNotEmpty() -> pluralStringResource(R.plurals.about_model_damaged, status.damaged.size, name, status.damaged.size)
        else -> stringResource(R.string.about_model_ready, name)
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace `ChecklistPage(states, onAction, home)` with `ChecklistPage(states, status.models, onAction, home)` and `AboutPage(data, { licence = it }, home)` with `AboutPage(data, status.models, { licence = it }, home)`.

In `app/src/main/res/values/strings.xml`, replace the strings `about_models` and `setup_models_detail` with these, and add the other strings and plurals after them:

```xml
    <string name="about_models">Speech models</string>
    <string name="about_models_checking">Checking the model files…</string>
    <string name="about_model_ready">%1$s: ready</string>
    <plurals name="about_model_missing">
        <item quantity="one">%1$s: %2$d file is missing</item>
        <item quantity="other">%1$s: %2$d files are missing</item>
    </plurals>
    <plurals name="about_model_damaged">
        <item quantity="one">%1$s: %2$d file is damaged</item>
        <item quantity="other">%1$s: %2$d files are damaged</item>
    </plurals>
```

```xml
    <string name="setup_models_detail">Run scripts\\push-models.ps1 -Serial &lt;serial&gt; on your computer. It downloads the speech models once and copies them to this phone, and Kestrel checks them against the hashes that come with the app.</string>
    <string name="setup_models_checking">Checking the model files…</string>
    <string name="setup_models_missing">Missing: %1$s</string>
    <string name="setup_models_damaged">Damaged: %1$s</string>
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace the function `refresh` with:

```kotlin
    private fun refresh() {
        // Checking the model files can take seconds the first time, so the other steps show at once, and the model
        // files keep their last known status until the check ends.
        status = SetupStatusReader(this).read().copy(models = status?.models)
        lifecycleScope.launch(graph.errors) {
            val models = graph.models.check()
            status = status?.copy(models = models)
        }
    }
```

- [ ] **Step 3: Run the tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`.

Run: `scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.settings.SettingsAppTest'`
Expected: `BUILD SUCCESSFUL`, with the three new tests among the passing ones.

- [ ] **Step 4: Check the page on the emulator**

Run: `scripts\install.ps1 -Serial <serial>`, then open Kestrel's settings with `& (Get-Adb) -s <serial> shell am start -S -n dev.kestrel/dev.kestrel.app.MainActivity` (after `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain` in the same command; `-S` restarts Kestrel, so that the page reads the files afresh), open "Setup checklist", and take a screenshot with `& (Get-Adb) -s <serial> exec-out screencap -p > $env:TEMP\models-step.png`.
Expected: step 2 says "To do" and lists the missing files, such as "Missing: asr-parakeet-v3/encoder.int8.onnx", unless the models are already on the emulator. Delete the screenshot afterwards.

- [ ] **Step 5: Document the pages**

In `README.md`, in "Setting up Kestrel", replace the sentence "The checklist reads the state of steps 3, 4, 5 and 8 from Android whenever Kestrel comes to the front, so it shows the changes that you make in Android's settings." with:

```markdown
Whenever Kestrel comes to the front, the checklist reads the state of steps 3, 4, 5 and 8 from Android, so it shows the changes that you make in Android's settings, and it checks the speech model files for step 2.
```

and replace the item "**About:**" with:

```markdown
- **About:** Kestrel's version, the Android version, whether each speech model is ready, and the licence texts.
```

- [ ] **Step 6: Commit**

```powershell
git add app/src README.md
git commit -m "feat(settings): show the model files' status in setup step 2 and on the About page" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 5: Copying the models with push-models.ps1

**Files:**
- Create: `scripts/lib/Models.ps1`, `scripts/push-models.ps1`
- Create: `scripts/tests/fake-adb-files.ps1`, `scripts/tests/fake-adb-files.cmd`
- Test: `scripts/tests/Models.Tests.ps1`
- Modify: `README.md`

**Interfaces:**
- Consumes: `models/manifest.json` (Task 2); Kestrel creates its models folder when it starts (Task 3); `Get-Adb`, `Invoke-SettingsCommand`, `Use-KestrelToolchain` and `Assert-TestDevice` from `scripts/lib/Kestrel.ps1`.
- Produces: `scripts\push-models.ps1 -Serial <serial> [-Cache <folder>] [-AllowDailyPhone]`, which Task 6's `voice-test.ps1` runs; in `scripts/lib/Models.ps1`: `Read-ModelManifest -Path`, `Save-ModelFiles -Manifest -Cache`, `Push-ModelFiles -Serial -Manifest -Cache`, `Get-CachedModelFile -Cache -Path`, `Get-DeviceModelsFolder`, and the helpers they use.

`Invoke-SettingsCommand` runs any device shell command, with three tries and an end marker; the device helpers below use it for every shell command. The scripts must work in Windows PowerShell 5.1 as well as in PowerShell 7, because `scripts\test-scripts.ps1` runs the Pester tests in both.

- [ ] **Step 1: Write the fake device**

Create `scripts/tests/fake-adb-files.cmd`:

```
@echo off
rem A stand-in for adb.exe in the tests of scripts\lib\Models.ps1. It runs fake-adb-files.ps1 in a process of its own,
rem as adb runs, so that its error lines reach PowerShell as the error output of a native program.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-adb-files.ps1" %*
exit /b %ERRORLEVEL%
```

Create `scripts/tests/fake-adb-files.ps1`:

```powershell
# A stand-in for adb in the tests of scripts\lib\Models.ps1; fake-adb-files.cmd runs it in its own process, as adb runs.
# It keeps the files of a fake device in a folder on this computer and understands the commands that Push-ModelFiles
# sends: 'adb -s <serial> push <file> <device path>', and 'adb -s <serial> shell "<command>"' for these commands, each
# followed by ' && echo kestrel-end':
#   pm path dev.kestrel || true
#   am start -n dev.kestrel/dev.kestrel.app.MainActivity    (creates Kestrel's models folder, as Kestrel does when it starts)
#   test -d '<folder>' && echo yes || echo no
#   mkdir '<folder>' && chmod 2775 '<folder>'               (fails when the folder exists or its parent does not)
#   stat -c %s '<file>' 2>/dev/null || echo missing
#   sha256sum '<file>'
# The JSON file whose path is in the environment variable KESTREL_FAKE_ADB holds the device's state:
#   root       the folder on this computer that stands for the device's '/'
#   installed  whether Kestrel is installed
#   modes      the mode that chmod gave each folder, in the order of the commands
#   calls      every command that it received, in order, with 'push <device path>' for a push
# Error lines go to standard error, as adb's do.
$statePath = $env:KESTREL_FAKE_ADB
if (-not $statePath -or -not (Test-Path -LiteralPath $statePath)) {
    [Console]::Error.WriteLine('fake adb: KESTREL_FAKE_ADB must name an existing state file')
    exit 1
}
$state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
$root = [string]$state.root
$modes = [ordered]@{}
foreach ($property in @($state.modes.PSObject.Properties)) { $modes[$property.Name] = [string]$property.Value }
$calls = @(@($state.calls) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })

function Save-State {
    $json = ConvertTo-Json -Depth 3 -InputObject ([ordered]@{ root = $root; installed = [bool]$state.installed; modes = $modes; calls = [string[]]$calls })
    [IO.File]::WriteAllText($statePath, $json)
}

# The folder or file on this computer that stands for a path on the device.
function Get-LocalPath([string]$DevicePath) {
    return Join-Path $root ($DevicePath.TrimStart('/') -replace '/', '\')
}

function Stop-WithError([string]$Message) {
    Save-State
    [Console]::Error.WriteLine($Message)
    exit 1
}

if ($args.Count -lt 4 -or $args[0] -ne '-s') { Stop-WithError "fake adb: unsupported arguments: $($args -join ' ')" }

if ($args[2] -eq 'push') {
    $calls += "push $($args[4])"
    $target = Get-LocalPath $args[4]
    if (-not (Test-Path -LiteralPath (Split-Path -Parent $target) -PathType Container)) {
        Stop-WithError "adb: error: failed to copy '$($args[3])' to '$($args[4])': remote couldn't create file: No such file or directory"
    }
    Copy-Item -LiteralPath $args[3] -Destination $target -Force
    Save-State
    "$($args[3]): 1 file pushed, 0 skipped."
    exit 0
}

if ($args[2] -ne 'shell') { Stop-WithError "fake adb: unsupported arguments: $($args -join ' ')" }
$command = $args[3..($args.Count - 1)] -join ' '
$calls += $command
$marker = ' && echo kestrel-end'
$withMarker = $command.EndsWith($marker)
$deviceCommand = if ($withMarker) { $command.Substring(0, $command.Length - $marker.Length) } else { $command }
$models = '/sdcard/Android/data/dev.kestrel/files/models'
if ($deviceCommand -eq 'pm path dev.kestrel || true') {
    $lines = @(if ($state.installed) { 'package:/data/app/dev.kestrel-1/base.apk' })
} elseif ($deviceCommand -eq 'am start -n dev.kestrel/dev.kestrel.app.MainActivity') {
    if ($state.installed) { New-Item -ItemType Directory -Force -Path (Get-LocalPath $models) | Out-Null }
    $lines = @('Starting: Intent { cmp=dev.kestrel/.app.MainActivity }')
} elseif ($deviceCommand -match "^test -d '([^']+)' && echo yes \|\| echo no$") {
    $lines = @(if (Test-Path -LiteralPath (Get-LocalPath $Matches[1]) -PathType Container) { 'yes' } else { 'no' })
} elseif ($deviceCommand -match "^mkdir '([^']+)' && chmod 2775 '([^']+)'$" -and $Matches[1] -eq $Matches[2]) {
    $folder = $Matches[1]
    $local = Get-LocalPath $folder
    if ((Test-Path -LiteralPath $local) -or -not (Test-Path -LiteralPath (Split-Path -Parent $local) -PathType Container)) {
        Stop-WithError "mkdir: '$folder': cannot create the folder"
    }
    New-Item -ItemType Directory -Path $local | Out-Null
    $modes[$folder] = '2775'
    $lines = @()
} elseif ($deviceCommand -match "^stat -c %s '([^']+)' 2>/dev/null \|\| echo missing$") {
    $local = Get-LocalPath $Matches[1]
    $lines = @(if (Test-Path -LiteralPath $local -PathType Leaf) { [string](Get-Item -LiteralPath $local).Length } else { 'missing' })
} elseif ($deviceCommand -match "^sha256sum '([^']+)'$") {
    $local = Get-LocalPath $Matches[1]
    if (-not (Test-Path -LiteralPath $local -PathType Leaf)) { Stop-WithError "sha256sum: $($Matches[1]): No such file or directory" }
    $lines = @("$((Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant())  $($Matches[1])")
} else {
    Stop-WithError "fake adb: unsupported command: $deviceCommand"
}
Save-State
foreach ($line in $lines) { $line }
if ($withMarker) { 'kestrel-end' }
exit 0
```

- [ ] **Step 2: Write the failing tests**

Create `scripts/tests/Models.Tests.ps1`:

```powershell
# Tests of scripts\lib\Models.ps1, which scripts\push-models.ps1 uses. The downloads come from fixture files instead of
# the internet, and the device is fake-adb-files.cmd, which keeps the fake device's files in a folder of the test drive.
# scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll and BeforeEach with the It blocks.')]
param()

Describe 'Read-ModelManifest' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        $hash = '0123456789abcdef' * 4

        # Writes a manifest with one model that has these files, and returns its path.
        function New-ManifestFile($Files, $Archives = @()) {
            $path = Join-Path $TestDrive 'manifest.json'
            $manifest = [ordered]@{ format = 1; archives = @($Archives); models = @([ordered]@{ id = 'm'; engine = 'asr'; files = @($Files) }) }
            [IO.File]::WriteAllText($path, (ConvertTo-Json -Depth 5 -InputObject $manifest))
            $path
        }
    }

    It 'accepts the repository''s manifest' {
        $manifest = Read-ModelManifest -Path "$PSScriptRoot\..\..\models\manifest.json"

        @($manifest.models).Count | Should -BeGreaterThan 0
    }

    It 'refuses a path that leaves the models folder: <_>' -ForEach @('../a.onnx', 'm/../../a.onnx', '/sdcard/a.onnx', 'm\a.onnx', 'm/./a.onnx') {
        $path = New-ManifestFile @(@{ path = $_; size = 1; sha256 = $hash; url = 'https://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*leaves the models folder*'
    }

    It 'refuses a path that is listed twice' {
        $file = @{ path = 'm/a.onnx'; size = 1; sha256 = $hash; url = 'https://example.org/a' }

        { Read-ModelManifest -Path (New-ManifestFile @($file, $file)) } | Should -Throw '*listed twice*'
    }

    It 'refuses a file without a size' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 0; sha256 = $hash; url = 'https://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*size*'
    }

    It 'refuses a hash that is not lowercase hex' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash.ToUpperInvariant(); url = 'https://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*SHA-256*'
    }

    It 'refuses a download that is not https' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash; url = 'http://example.org/a' })

        { Read-ModelManifest -Path $path } | Should -Throw '*https*'
    }

    It 'refuses a member of an archive that is not listed' {
        $path = New-ManifestFile @(@{ path = 'm/a.onnx'; size = 1; sha256 = $hash; archive = 'pack'; member = 'pack/a.onnx' })

        { Read-ModelManifest -Path $path } | Should -Throw '*archive*'
    }
}

Describe 'Save-ModelFiles' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        Mock Write-Host {}

        # The upstream files: one that is downloaded on its own, and an archive with two members.
        $sources = Join-Path $TestDrive 'sources'
        New-Item -ItemType Directory -Path (Join-Path $sources 'pack\data') | Out-Null
        Set-Content -LiteralPath (Join-Path $sources 'a.onnx') -Value 'model a' -NoNewline
        Set-Content -LiteralPath (Join-Path $sources 'pack\data\b.txt') -Value 'member b' -NoNewline
        Set-Content -LiteralPath (Join-Path $sources 'pack\c.txt') -Value 'member c' -NoNewline
        $archive = Join-Path $sources 'pack.tar.bz2'
        & "$env:SystemRoot\System32\tar.exe" -c -j -f $archive -C $sources pack

        function Get-TestHash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

        # A manifest entry for an upstream file, downloaded on its own or, with -Member, from the archive.
        function New-TestFile([string]$Name, [string]$Path, [string]$Member) {
            $source = Join-Path $sources ($Name -replace '/', '\')
            $file = [ordered]@{ path = $Path; size = (Get-Item -LiteralPath $source).Length; sha256 = Get-TestHash $source }
            if ($Member) { $file.archive = 'pack'; $file.member = $Member } else { $file.url = "https://example.org/$Name" }
            [pscustomobject]$file
        }

        $manifest = [pscustomobject]@{
            format = 1
            archives = @([pscustomobject]@{ id = 'pack'; url = 'https://example.org/pack.tar.bz2'; size = (Get-Item -LiteralPath $archive).Length; sha256 = Get-TestHash $archive })
            models = @([pscustomobject]@{ id = 'm'; engine = 'asr'; files = @(
                (New-TestFile 'a.onnx' 'm/a.onnx'),
                (New-TestFile 'pack/data/b.txt' 'm/data/b.txt' 'pack/data/b.txt'),
                (New-TestFile 'pack/c.txt' 'm/c.txt' 'pack/c.txt')
            ) })
        }

        Mock Invoke-ModelDownload { Copy-Item -LiteralPath (Join-Path $sources ($Url -replace '^https://example\.org/', '')) -Destination $Path }
    }

    BeforeEach {
        $cache = Join-Path $TestDrive "cache-$([guid]::NewGuid())"
    }

    It 'downloads each source once and unpacks the members of the archive' {
        Save-ModelFiles -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Join-Path $cache 'files\m\a.onnx') -Raw | Should -BeExactly 'model a'
        Get-Content -LiteralPath (Join-Path $cache 'files\m\data\b.txt') -Raw | Should -BeExactly 'member b'
        Get-Content -LiteralPath (Join-Path $cache 'files\m\c.txt') -Raw | Should -BeExactly 'member c'
        Should -Invoke Invoke-ModelDownload -Times 2 -Exactly
    }

    It 'downloads nothing when the cache holds every file' {
        Save-ModelFiles -Manifest $manifest -Cache $cache
        Save-ModelFiles -Manifest $manifest -Cache $cache

        Should -Invoke Invoke-ModelDownload -Times 2 -Exactly
    }

    It 'downloads a damaged file again' {
        Save-ModelFiles -Manifest $manifest -Cache $cache
        Set-Content -LiteralPath (Join-Path $cache 'files\m\a.onnx') -Value 'model x' -NoNewline

        Save-ModelFiles -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Join-Path $cache 'files\m\a.onnx') -Raw | Should -BeExactly 'model a'
        Should -Invoke Invoke-ModelDownload -Times 3 -Exactly
    }

    It 'unpacks a damaged member again from the archive in the cache' {
        Save-ModelFiles -Manifest $manifest -Cache $cache
        Set-Content -LiteralPath (Join-Path $cache 'files\m\c.txt') -Value 'member x' -NoNewline

        Save-ModelFiles -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Join-Path $cache 'files\m\c.txt') -Raw | Should -BeExactly 'member c'
        Should -Invoke Invoke-ModelDownload -Times 2 -Exactly
    }

    It 'refuses a download with the wrong hash and keeps nothing of it' {
        $file = [pscustomobject]@{ path = 'm/a.onnx'; size = 7; sha256 = '0' * 64; url = 'https://example.org/a.onnx' }
        $wrong = [pscustomobject]@{ format = 1; archives = @(); models = @([pscustomobject]@{ id = 'm'; engine = 'asr'; files = @($file) }) }

        { Save-ModelFiles -Manifest $wrong -Cache $cache } | Should -Throw '*SHA-256*'
        @(Get-ChildItem -LiteralPath $cache -Recurse -File) | Should -BeNullOrEmpty
    }
}

Describe 'Push-ModelFiles' {
    BeforeAll {
        # The scripts run with 'Stop', under which Windows PowerShell 5.1 stops at adb's first error line unless the
        # helpers protect themselves, so the tests run with it too.
        $ErrorActionPreference = 'Stop'
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Models.ps1"
        $fakeAdb = Join-Path $PSScriptRoot 'fake-adb-files.cmd'
        Mock Get-Adb { $fakeAdb }
        Mock Start-Sleep {}
        Mock Write-Host {}
        $models = '/sdcard/Android/data/dev.kestrel/files/models'

        # A cache with three files, as Save-ModelFiles leaves it, and a manifest that lists them.
        $cache = Join-Path $TestDrive 'cache'
        $contents = [ordered]@{ 'm/a.onnx' = 'model a'; 'm/data/b.txt' = 'member b'; 'n/c.txt' = 'member c' }
        $files = @(foreach ($path in $contents.Keys) {
                $local = Get-CachedModelFile -Cache $cache -Path $path
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $local) | Out-Null
                Set-Content -LiteralPath $local -Value $contents[$path] -NoNewline
                [pscustomobject]@{
                    path = $path
                    size = (Get-Item -LiteralPath $local).Length
                    sha256 = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
                    url = "https://example.org/$path"
                }
            })
        $manifest = [pscustomobject]@{ format = 1; archives = @(); models = @([pscustomobject]@{ id = 'm'; engine = 'asr'; files = $files }) }

        # Starts a fake device, with or without Kestrel and with or without the models folder that Kestrel creates, and
        # returns the folder that stands for the device's '/'.
        function Set-FakeDevice([switch]$NotInstalled, [switch]$WithModelsFolder) {
            $root = Join-Path $TestDrive "device-$([guid]::NewGuid())"
            New-Item -ItemType Directory -Path $root | Out-Null
            if ($WithModelsFolder) {
                New-Item -ItemType Directory -Force -Path (Join-Path $root 'sdcard\Android\data\dev.kestrel\files\models') | Out-Null
            }
            $state = [ordered]@{ root = $root; installed = -not $NotInstalled; modes = @{}; calls = @() }
            [IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))
            $root
        }

        function Get-FakeDevice { [IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json }

        # The file of the fake device that stands for a model file.
        function Get-DeviceCopy([string]$Root, [string]$Path) {
            Join-Path $Root ('sdcard\Android\data\dev.kestrel\files\models\' + ($Path -replace '/', '\'))
        }
    }

    BeforeEach {
        $env:KESTREL_FAKE_ADB = Join-Path $TestDrive 'device.json'
    }

    AfterAll {
        Remove-Item Env:\KESTREL_FAKE_ADB -ErrorAction SilentlyContinue
    }

    It 'refuses a device without Kestrel' {
        Set-FakeDevice -NotInstalled | Out-Null

        { Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache } | Should -Throw '*not installed*'
    }

    It 'starts Kestrel when its models folder is missing, then copies every file' {
        $root = Set-FakeDevice

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        foreach ($path in $contents.Keys) {
            Get-Content -LiteralPath (Get-DeviceCopy $root $path) -Raw | Should -BeExactly $contents[$path]
        }
        (Get-FakeDevice).calls | Should -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo kestrel-end'
    }

    It 'gives the folders that it creates the mode 2775, each after the folder that holds it' {
        Set-FakeDevice -WithModelsFolder | Out-Null

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        $device = Get-FakeDevice
        @($device.modes.PSObject.Properties | ForEach-Object { $_.Name }) | Should -Be @("$models/m", "$models/n", "$models/m/data")
        @($device.modes.PSObject.Properties | ForEach-Object { $_.Value } | Select-Object -Unique) | Should -Be @('2775')
        $device.calls | Should -Not -Contain 'am start -n dev.kestrel/dev.kestrel.app.MainActivity && echo kestrel-end'
    }

    It 'copies nothing that the device already has' {
        Set-FakeDevice -WithModelsFolder | Out-Null

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache
        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        @((Get-FakeDevice).calls | Where-Object { $_ -like 'push *' }).Count | Should -Be 3
    }

    It 'copies a file again when the device holds another version of it' {
        $root = Set-FakeDevice -WithModelsFolder
        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache
        Set-Content -LiteralPath (Get-DeviceCopy $root 'm/a.onnx') -Value 'model x' -NoNewline

        Push-ModelFiles -Serial 'fake' -Manifest $manifest -Cache $cache

        Get-Content -LiteralPath (Get-DeviceCopy $root 'm/a.onnx') -Raw | Should -BeExactly 'model a'
        @((Get-FakeDevice).calls | Where-Object { $_ -like 'push */m/a.onnx' }).Count | Should -Be 2
    }
}
```

Run: `scripts\test-scripts.ps1`
Expected: the new tests fail, because `scripts\lib\Models.ps1` does not exist.

- [ ] **Step 3: Write the helpers**

Create `scripts/lib/Models.ps1`:

```powershell
# Helpers for scripts\push-models.ps1 (design spec, section 8.10). They read models\manifest.json, keep the model files
# in a cache on this computer, and copy them into Kestrel's models folder on a device. Dot-source scripts\lib\Kestrel.ps1
# first: the device helpers use Get-Adb and Invoke-SettingsCommand.

# Reads models\manifest.json and checks what the scripts rely on: every path stays inside the models folder and is listed
# once, and every file and archive has an https source, a size and a SHA-256 hash, so that nothing unchecked reaches a
# device.
function Read-ModelManifest([string]$Path) {
    $manifest = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.format -ne 1) { throw "${Path}: unknown format '$($manifest.format)'." }
    $archives = @{}
    foreach ($archive in @($manifest.archives | Where-Object { $null -ne $_ })) {
        $what = "${Path}: the archive '$($archive.id)'"
        Assert-HttpsSource -What $what -Url $archive.url
        Assert-SizeAndHash -What $what -Size $archive.size -Sha256 $archive.sha256
        $archives[[string]$archive.id] = $true
    }
    $paths = @{}
    foreach ($model in @($manifest.models)) {
        foreach ($file in @($model.files)) {
            $path = [string]$file.path
            $what = "${Path}: the file '$path'"
            $parts = $path -split '/'
            if ($path -notmatch '^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$' -or $parts -contains '.' -or $parts -contains '..') {
                throw "$what has a path that leaves the models folder."
            }
            if ($paths.ContainsKey($path)) { throw "$what is listed twice." }
            $paths[$path] = $true
            if ($file.url) {
                Assert-HttpsSource -What $what -Url $file.url
            } elseif (-not ($file.archive -and $archives.ContainsKey([string]$file.archive) -and $file.member)) {
                throw "$what needs a url, or an archive from the list of archives and a member of it."
            }
            Assert-SizeAndHash -What $what -Size $file.size -Sha256 $file.sha256
        }
    }
    return $manifest
}

function Assert-HttpsSource([string]$What, $Url) {
    if ([string]$Url -notlike 'https://*') { throw "$What needs an https address." }
}

function Assert-SizeAndHash([string]$What, $Size, $Sha256) {
    if (-not ($Size -as [long]) -or ($Size -as [long]) -le 0) { throw "$What needs a size in bytes." }
    if ([string]$Sha256 -cnotmatch '^[0-9a-f]{64}$') { throw "$What needs a SHA-256 hash in lowercase hex." }
}

# The file in the cache that holds a model file. The cache keeps the manifest's paths.
function Get-CachedModelFile([string]$Cache, [string]$Path) {
    return Join-Path (Join-Path $Cache 'files') ($Path -replace '/', '\')
}

# Whether a file exists with this size and SHA-256 hash.
function Test-FileHash([string]$Path, $Size, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    if ((Get-Item -LiteralPath $Path).Length -ne ($Size -as [long])) { return $false }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -eq $Sha256
}

# Downloads one address into one file. The tests replace it.
function Invoke-ModelDownload([string]$Url, [string]$Path) {
    # Windows PowerShell 5.1 downloads large files much more slowly while it shows its progress bar.
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $Url -OutFile $Path -UseBasicParsing
}

# Moves a downloaded or unpacked file to $Target if it has this size and hash, and deletes it otherwise.
function Move-CheckedFile([string]$Source, [string]$Target, $Size, [string]$Sha256, [string]$What) {
    if (-not (Test-FileHash -Path $Source -Size $Size -Sha256 $Sha256)) {
        Remove-Item -LiteralPath $Source -Force -ErrorAction SilentlyContinue
        throw "$What does not have the size and SHA-256 hash that models\manifest.json gives."
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
    Move-Item -LiteralPath $Source -Destination $Target -Force
}

# Downloads one address into $Target through a temporary file, which it keeps only with this size and hash.
function Save-CheckedDownload([string]$Url, [string]$Target, $Size, [string]$Sha256) {
    Write-Host "Downloading $Url"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
    $temporary = "$Target.download"
    Invoke-ModelDownload -Url $Url -Path $temporary
    Move-CheckedFile -Source $temporary -Target $Target -Size $Size -Sha256 $Sha256 -What "The download of $Url"
}

# Unpacks the members that $Files name from an archive into the cache, with Windows' own tar.exe.
function Expand-ModelFiles([string]$Archive, $Files, [string]$Cache) {
    $folder = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $folder | Out-Null
    try {
        $members = @($Files | ForEach-Object { [string]$_.member })
        & "$env:SystemRoot\System32\tar.exe" -x -f $Archive -C $folder @members
        if ($LASTEXITCODE -ne 0) { throw "tar.exe could not unpack $Archive." }
        foreach ($file in $Files) {
            $source = Join-Path $folder ([string]$file.member -replace '/', '\')
            $target = Get-CachedModelFile -Cache $Cache -Path $file.path
            Move-CheckedFile -Source $source -Target $target -Size $file.size -Sha256 $file.sha256 -What "$($file.member) in $Archive"
        }
    } finally {
        Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Fills the cache with every model file that it does not already hold with the right hash. An archive is downloaded once
# into $Cache\archives, and the members that are needed are unpacked from it. A download or a member with the wrong size
# or hash fails, and nothing of it stays in the cache.
function Save-ModelFiles($Manifest, [string]$Cache) {
    $archives = @{}
    foreach ($archive in @($Manifest.archives | Where-Object { $null -ne $_ })) { $archives[[string]$archive.id] = $archive }
    $fromArchives = @{}
    foreach ($file in @($Manifest.models | ForEach-Object { $_.files })) {
        $target = Get-CachedModelFile -Cache $Cache -Path $file.path
        if (Test-FileHash -Path $target -Size $file.size -Sha256 $file.sha256) { continue }
        if ($file.url) {
            Save-CheckedDownload -Url $file.url -Target $target -Size $file.size -Sha256 $file.sha256
        } else {
            $id = [string]$file.archive
            if (-not $fromArchives.ContainsKey($id)) { $fromArchives[$id] = New-Object System.Collections.ArrayList }
            [void]$fromArchives[$id].Add($file)
        }
    }
    foreach ($id in @($fromArchives.Keys)) {
        $archive = $archives[$id]
        $archiveFile = Join-Path (Join-Path $Cache 'archives') (([string]$archive.url -split '/')[-1])
        if (-not (Test-FileHash -Path $archiveFile -Size $archive.size -Sha256 $archive.sha256)) {
            Save-CheckedDownload -Url $archive.url -Target $archiveFile -Size $archive.size -Sha256 $archive.sha256
        }
        Expand-ModelFiles -Archive $archiveFile -Files $fromArchives[$id] -Cache $Cache
    }
}

# The folder in which Kestrel looks for its models on a device (design spec, section 8.10).
function Get-DeviceModelsFolder {
    return '/sdcard/Android/data/dev.kestrel/files/models'
}

function Test-DeviceFolder([string]$Serial, [string]$Path) {
    return (@(Invoke-SettingsCommand -Serial $Serial -Command "test -d '$Path' && echo yes || echo no") -join '').Trim() -eq 'yes'
}

# The size of a file on a device, or -1 when the file does not exist.
function Get-DeviceFileSize([string]$Serial, [string]$Path) {
    $answer = (@(Invoke-SettingsCommand -Serial $Serial -Command "stat -c %s '$Path' 2>/dev/null || echo missing") -join '').Trim()
    if ($answer -match '^\d+$') { return [long]$answer }
    return -1
}

# The SHA-256 hash of a file on a device, in lowercase hex.
function Get-DeviceFileHash([string]$Serial, [string]$Path) {
    $answer = (@(Invoke-SettingsCommand -Serial $Serial -Command "sha256sum '$Path'") -join '').Trim()
    return ($answer -split '\s+')[0].ToLowerInvariant()
}

# Copies the model files from the cache into Kestrel's models folder on a device, except those that the device already
# holds with the right size and hash, and checks the hash of each copy on the device. Kestrel must be installed. It
# creates its models folder when it starts, so the function starts Kestrel when the folder is missing. The folders below
# it get the mode 2775, because on Android 17 Kestrel cannot read a folder that adb creates otherwise (spike S7).
function Push-ModelFiles([string]$Serial, $Manifest, [string]$Cache) {
    # adb's progress and error lines must not stop the function; its own checks decide.
    $ErrorActionPreference = 'Continue'
    $root = Get-DeviceModelsFolder
    $installed = @(Invoke-SettingsCommand -Serial $Serial -Command 'pm path dev.kestrel || true') -match '^package:'
    if (-not $installed) { throw "Kestrel is not installed on $Serial. Install it first with scripts\install.ps1 -Serial $Serial." }
    if (-not (Test-DeviceFolder -Serial $Serial -Path $root)) {
        Invoke-SettingsCommand -Serial $Serial -Command 'am start -n dev.kestrel/dev.kestrel.app.MainActivity' | Out-Null
        $deadline = (Get-Date).AddSeconds(15)
        while (-not (Test-DeviceFolder -Serial $Serial -Path $root)) {
            if ((Get-Date) -gt $deadline) { throw "Kestrel did not create $root on $Serial." }
            Start-Sleep -Milliseconds 500
        }
    }
    $files = @($Manifest.models | ForEach-Object { $_.files })
    $folders = @{}
    foreach ($file in $files) {
        $parts = ([string]$file.path) -split '/'
        for ($count = 1; $count -lt $parts.Count; $count++) { $folders[($parts[0..($count - 1)] -join '/')] = $true }
    }
    # Each folder comes after the folder that holds it.
    foreach ($folder in @($folders.Keys | Sort-Object { ($_ -split '/').Count }, { $_ })) {
        $path = "$root/$folder"
        if (-not (Test-DeviceFolder -Serial $Serial -Path $path)) {
            Invoke-SettingsCommand -Serial $Serial -Command "mkdir '$path' && chmod 2775 '$path'" | Out-Null
        }
    }
    $adb = Get-Adb
    foreach ($file in $files) {
        $remote = "$root/$($file.path)"
        $size = Get-DeviceFileSize -Serial $Serial -Path $remote
        if ($size -eq ($file.size -as [long]) -and (Get-DeviceFileHash -Serial $Serial -Path $remote) -eq $file.sha256) {
            Write-Host "Already on $Serial`: $($file.path)"
            continue
        }
        Write-Host "Copying $($file.path) to $Serial"
        $output = @(& $adb -s $Serial push (Get-CachedModelFile -Cache $Cache -Path $file.path) $remote 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "adb could not copy $($file.path) to ${Serial}: $(($output | ForEach-Object { "$_".Trim() }) -join ' ')" }
        if ((Get-DeviceFileHash -Serial $Serial -Path $remote) -ne $file.sha256) {
            throw "The copy of $($file.path) on $Serial does not have the hash that models\manifest.json gives."
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `scripts\test-scripts.ps1`
Expected: every test passes in both shells, and PSScriptAnalyzer reports nothing.

- [ ] **Step 5: Write the script**

Create `scripts/push-models.ps1`:

```powershell
# Copies Kestrel's speech models to a test device (design spec, section 8.10). It downloads the files that
# models\manifest.json lists from their upstream sources into a cache on this computer, checks their SHA-256 hashes, and
# copies the files that the device lacks into Kestrel's folder /sdcard/Android/data/dev.kestrel/files/models. Kestrel
# must be installed first (scripts\install.ps1). The first run downloads about 760 MB; later runs download nothing.
# Uninstalling Kestrel deletes the models from the device.
# Example: scripts\push-models.ps1 -Serial emulator-5554
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Outside the repository, so that git clean keeps it and every clone shares it.
    [string]$Cache = (Join-Path $env:LOCALAPPDATA 'Kestrel\models'),
    # Only for the owner's decision to start daily use on the Galaxy S23+.
    [switch]$AllowDailyPhone
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Models.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial -AllowDailyPhone:$AllowDailyPhone
$manifest = Read-ModelManifest -Path (Join-Path $PSScriptRoot '..\models\manifest.json')
Save-ModelFiles -Manifest $manifest -Cache $Cache
Push-ModelFiles -Serial $Serial -Manifest $manifest -Cache $Cache
Write-Host "The speech models are on $Serial."
```

- [ ] **Step 6: Copy the models to the emulator**

Run: `scripts\install.ps1 -Serial <serial>`, then `scripts\push-models.ps1 -Serial <serial>`
Expected: the first run downloads about 760 MB (a few minutes), copies 18 files, and ends with "The speech models are on <serial>." A second run downloads nothing and says "Already on …" for every file.

Then check that Kestrel finds them: open Kestrel's settings (`& (Get-Adb) -s <serial> shell am start -S -n dev.kestrel/dev.kestrel.app.MainActivity`, after `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain` in the same command; `-S` restarts Kestrel, because `push-models.ps1` may have left its settings open with the status from before the copy) and open "Setup checklist".
Expected: step 2 is "Done" within a few seconds; the first check hashes the 652 MB encoder.

- [ ] **Step 7: Document the script**

In `README.md`, in "Setting up Kestrel", replace step 2 with:

```markdown
2. Copy the speech models with `scripts\push-models.ps1 -Serial $serial`. Kestrel must be installed first. The first run downloads about 760 MB from the models' projects into `%LOCALAPPDATA%\Kestrel\models` and checks each file's SHA-256 hash against `models\manifest.json`. Then it copies the files that the phone lacks, about 740 MB, into Kestrel's folder `Android/data/dev.kestrel/files/models`, and Kestrel checks them against the same hashes. Uninstalling Kestrel deletes the models from the phone.
```

- [ ] **Step 8: Commit**

```powershell
git add scripts README.md
git commit -m "feat(scripts): push-models.ps1 downloads, checks and copies the speech models" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 6: Voice output and speech detection

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/Synthesizer.kt`, `SpeechDetector.kt`, `VoiceEngines.kt`
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaSynthesizer.kt`, `SherpaSpeechDetector.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/SynthesizerTest.kt`, `SpeechDetectorTest.kt`
- Create: `scripts/voice-test.ps1`
- Modify: `scripts/lib/Kestrel.ps1` (`Get-TestResultCounts`), `scripts/tests/Kestrel.Tests.ps1`
- Modify: `README.md`

**Interfaces:**
- Consumes: `ModelFiles`, `ModelStatus`, `Engine` and `AppGraph.models` (Tasks 2 and 3); `scripts\install.ps1`, `scripts\push-models.ps1` (Task 5) and `scripts\connected-test.ps1`.
- Produces: in package `dev.kestrel.voice`: `const val SAMPLE_RATE = 16_000`; `interface Synthesizer : AutoCloseable { val sampleRate: Int; fun synthesize(text: String): FloatArray }`; `data class VoiceOptions(speed: Float = 1f, variation: Float = 1f)`; `class Utterance(startSample: Long, samples: FloatArray) { val seconds: Float }`; `interface SpeechDetector : AutoCloseable { fun accept(samples: FloatArray): List<Utterance>; fun finish(): List<Utterance>; val hearsSpeech: Boolean }`; `class VoiceEngines(models: ModelFiles, workFolder: File)` with `suspend fun synthesizer(options: VoiceOptions = VoiceOptions()): Synthesizer` and `suspend fun speechDetector(): SpeechDetector`, and the internal path constants `VAD_MODEL`, `TTS_MODEL`, `TTS_TOKENS` and `ESPEAK_DATA` in its companion object; `class ModelsNotReadyException(engine: Engine, statuses: List<ModelStatus>) : IllegalStateException`. `AppGraph.voice: VoiceEngines`. For the device tests: `object VoiceTests` with `graph`, `assumeModels(vararg engines: Engine)`, `voice(text: String): FloatArray` (the voice alone, at 16 kHz), `speech(text: String): FloatArray` (the voice with 0.5 s of silence before it and 1 s after it), `silence(seconds: Float): FloatArray`, `frames(samples, feed)`, `log(message: String)` and `pssMegabytes()`. `scripts\voice-test.ps1 -Serial <serial>`; `Get-TestResultCounts -Folder` in `scripts/lib/Kestrel.ps1`.

The engines are not thread-safe, and each holds its model in native memory until it is closed. sherpa-onnx can end Kestrel's process when a model file is damaged, so `VoiceEngines` checks the engine's models before it loads them.

- [ ] **Step 1: Write the failing JVM test**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`:

```kotlin
package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The engines find their files by the manifest's paths, so each path that they use must be in the manifest. */
class VoiceModelPathsTest {
    private val manifest = ModelManifest.parse(File("../../models/manifest.json").readText())

    private fun engineOf(path: String): Engine = manifest.models.single { model -> model.files.any { it.path == path } }.engine

    @Test
    fun theSpeechDetectorsModelIsInTheManifest() {
        assertEquals(Engine.VAD, engineOf(VoiceEngines.VAD_MODEL))
    }

    @Test
    fun theVoicesFilesAreInTheManifest() {
        assertEquals(Engine.TTS, engineOf(VoiceEngines.TTS_MODEL))
        assertEquals(Engine.TTS, engineOf(VoiceEngines.TTS_TOKENS))
        // The voice uses espeak-ng's voice "en".
        assertEquals(Engine.TTS, engineOf("${VoiceEngines.ESPEAK_DATA}/lang/gmw/en"))
    }
}
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD FAILED`, because `VoiceEngines` does not exist.

- [ ] **Step 2: Write the voice and the speech detector**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/Synthesizer.kt`:

```kotlin
package dev.kestrel.voice

/**
 * Kestrel's voice (design spec, section 8.4). Not thread-safe: use one instance from one thread at a time, and close it
 * when it is no longer needed, because it holds the voice model in native memory.
 */
interface Synthesizer : AutoCloseable {
    /** The sample rate of [synthesize]'s output, in Hz. Piper's voices use 22,050 Hz. */
    val sampleRate: Int

    /** Speaks [text] into samples in [-1, 1] at [sampleRate]. Returns no samples when the voice cannot say the text. */
    fun synthesize(text: String): FloatArray
}

/**
 * How Kestrel's voice sounds. A [speed] of 1 is the voice's normal pace. [variation] scales the small random variation in
 * the voice's sound: 1 is the voice's normal amount, and 0 makes the voice say a text the same way every time, which the
 * device tests need.
 */
data class VoiceOptions(val speed: Float = 1f, val variation: Float = 1f)
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/SpeechDetector.kt`:

```kotlin
package dev.kestrel.voice

/** The sample rate of the audio that the engines take: 16 kHz, as Kestrel records (design spec, section 8.1). */
const val SAMPLE_RATE = 16_000

/** An utterance that the speech detector found: its [samples] at 16 kHz, which start [startSample] samples into the audio. */
class Utterance(val startSample: Long, val samples: FloatArray) {
    val seconds: Float get() = samples.size / SAMPLE_RATE.toFloat()
}

/**
 * Splits audio into utterances (design spec, sections 8.2 and 8.3). An utterance ends after 0.7 s of silence and lasts at
 * most 30 s. Not thread-safe: feed it from one thread, and close it when it is no longer needed.
 */
interface SpeechDetector : AutoCloseable {
    /** Feeds 16 kHz samples, any number at a time, and returns the utterances that ended in them. */
    fun accept(samples: FloatArray): List<Utterance>

    /** Ends the audio: returns the utterance that is still going on, if any, and counts samples from 0 again. */
    fun finish(): List<Utterance>

    /** Whether the audio that came last sounds like speech. */
    val hearsSpeech: Boolean
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaSynthesizer.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import java.io.File

/** A Piper voice through sherpa-onnx, which gets its phonemes from espeak-ng's data in [espeakData] (B38). */
internal class SherpaSynthesizer(model: File, tokens: File, espeakData: File, private val options: VoiceOptions) : Synthesizer {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    tokens = tokens.path,
                    dataDir = espeakData.path,
                    noiseScale = NOISE_SCALE * options.variation,
                    noiseScaleW = NOISE_SCALE_W * options.variation,
                ),
                numThreads = THREADS,
            ),
        ),
    )

    override val sampleRate: Int = tts.sampleRate()

    override fun synthesize(text: String): FloatArray = tts.generate(text, sid = 0, speed = options.speed).samples

    override fun close() = tts.release()

    private companion object {
        // sherpa-onnx's defaults for VITS voices, such as Piper's.
        const val NOISE_SCALE = 0.667f
        const val NOISE_SCALE_W = 0.8f
        const val THREADS = 2
    }
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaSpeechDetector.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import java.io.File

/** Silero VAD through sherpa-onnx. The model takes the audio in windows of 512 samples, so this collects them. */
internal class SherpaSpeechDetector(model: File) : SpeechDetector {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.path,
                minSilenceDuration = END_SILENCE_SECONDS,
                maxSpeechDuration = MAX_SPEECH_SECONDS,
                windowSize = WINDOW,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )
    private val window = FloatArray(WINDOW)
    private var filled = 0

    override val hearsSpeech: Boolean get() = vad.isSpeechDetected()

    override fun accept(samples: FloatArray): List<Utterance> {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(WINDOW - filled, samples.size - offset)
            samples.copyInto(window, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == WINDOW) {
                vad.acceptWaveform(window)
                filled = 0
            }
        }
        return utterances()
    }

    override fun finish(): List<Utterance> {
        vad.flush()
        val utterances = utterances()
        vad.reset()
        filled = 0
        return utterances
    }

    private fun utterances(): List<Utterance> {
        val utterances = mutableListOf<Utterance>()
        while (!vad.empty()) {
            val segment = vad.front()
            utterances += Utterance(segment.start.toLong(), segment.samples)
            vad.pop()
        }
        return utterances
    }

    override fun close() = vad.release()

    private companion object {
        const val WINDOW = 512
        // Design spec, section 8.3.
        const val END_SILENCE_SECONDS = 0.7f
        const val MAX_SPEECH_SECONDS = 30f
    }
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`:

```kotlin
package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelStatus
import dev.kestrel.voice.sherpa.SherpaSpeechDetector
import dev.kestrel.voice.sherpa.SherpaSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates Kestrel's speech engines from the model files that [models] checks (design spec, section 8.2). Loading a model
 * takes up to a few seconds, so the engines are created on [Dispatchers.IO], and the caller closes each engine. A damaged
 * model file could end Kestrel's process inside sherpa-onnx, so each call first checks its engine's models and throws
 * [ModelsNotReadyException] when they are not ready. [workFolder] is a private folder for files that the engines derive
 * from the models.
 */
class VoiceEngines(private val models: ModelFiles, private val workFolder: File) {
    suspend fun synthesizer(options: VoiceOptions = VoiceOptions()): Synthesizer = withContext(Dispatchers.IO) {
        requireReady(Engine.TTS)
        SherpaSynthesizer(models.file(TTS_MODEL), models.file(TTS_TOKENS), models.file(ESPEAK_DATA), options)
    }

    suspend fun speechDetector(): SpeechDetector = withContext(Dispatchers.IO) {
        requireReady(Engine.VAD)
        SherpaSpeechDetector(models.file(VAD_MODEL))
    }

    private suspend fun requireReady(engine: Engine) {
        val notReady = models.check().filter { it.model.engine == engine && !it.ready }
        if (notReady.isNotEmpty()) throw ModelsNotReadyException(engine, notReady)
    }

    internal companion object {
        const val VAD_MODEL = "vad-silero/silero_vad.onnx"
        const val TTS_MODEL = "tts-piper-ljspeech/en_US-ljspeech-medium.onnx"
        const val TTS_TOKENS = "tts-piper-ljspeech/tokens.txt"
        const val ESPEAK_DATA = "espeak-ng-data"
    }
}

/** The models of [engine] are missing or damaged; [statuses] say which files. */
class ModelsNotReadyException(val engine: Engine, val statuses: List<ModelStatus>) :
    IllegalStateException("The models of $engine are missing or damaged")
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Give the app its engines**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, add `import dev.kestrel.voice.VoiceEngines`, and add this property after the property `models`:

```kotlin
    /** Kestrel's speech engines, which load their models from [models] (design spec, section 8.2). */
    val voice: VoiceEngines by lazy { VoiceEngines(models, File(appContext.noBackupFilesDir, "voice")) }
```

- [ ] **Step 4: Write the device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceTests.kt`:

```kotlin
package dev.kestrel.app.voice

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
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

    // Kept open for the whole test run, because loading the voice takes more than a second.
    private var synthesizer: Synthesizer? = null
    private val spoken = mutableMapOf<String, FloatArray>()

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

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/SynthesizerTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SynthesizerTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.TTS)

    @Test
    fun theVoiceSpeaksASentence() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        graph.voice.synthesizer().use { voice ->
            val loaded = SystemClock.elapsedRealtime()
            val samples = voice.synthesize("Kestrel, open the settings.")
            val seconds = samples.size / voice.sampleRate.toFloat()
            log("voice: loaded in ${loaded - started} ms; ${"%.1f".format(seconds)} s of speech in ${SystemClock.elapsedRealtime() - loaded} ms")

            assertEquals(22_050, voice.sampleRate)
            assertTrue("$seconds s", seconds in 1f..4f)
            assertTrue(samples.maxOf { abs(it) } > 0.1f)
        }
    }

    @Test
    fun withoutVariationTheVoiceSaysATextTheSameWay() = runBlocking {
        graph.voice.synthesizer(VoiceOptions(variation = 0f)).use { voice ->
            assertArrayEquals(voice.synthesize("Stop."), voice.synthesize("Stop."), 0f)
        }
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/SpeechDetectorTest.kt`:

```kotlin
package dev.kestrel.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.frames
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.silence
import dev.kestrel.app.voice.VoiceTests.speech
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechDetectorTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.VAD, Engine.TTS)

    @Test
    fun aSentenceBetweenSilencesIsOneUtterance() = runBlocking {
        val audio = speech("Scroll down and tap the blue button.")
        graph.voice.speechDetector().use { detector ->
            val utterance = (frames(audio, detector::accept) + detector.finish()).single()

            // The speech starts after 0.5 s of silence, and the voice adds a little silence of its own.
            assertEquals(0.5f, utterance.startSample / SAMPLE_RATE.toFloat(), 0.3f)
            assertEquals(audio.size / SAMPLE_RATE.toFloat() - 1.5f, utterance.seconds, 0.5f)
        }
    }

    @Test
    fun twoSentencesWithAPauseBetweenThemAreTwoUtterances() = runBlocking {
        // 1.5 s of silence lies between the two, longer than the 0.7 s that ends an utterance.
        val audio = speech("Stop.") + speech("Cancel that.")
        graph.voice.speechDetector().use { detector ->
            assertEquals(2, (frames(audio, detector::accept) + detector.finish()).size)
        }
    }

    @Test
    fun silenceHasNoUtterance() = runBlocking {
        graph.voice.speechDetector().use { detector ->
            assertEquals(0, (frames(silence(2f), detector::accept) + detector.finish()).size)
            assertFalse(detector.hearsSpeech)
        }
    }
}
```

- [ ] **Step 5: Write the test runner for the voice tests**

In `scripts/tests/Kestrel.Tests.ps1`, add this block at the end:

```powershell
Describe 'Get-TestResultCounts' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'adds up the suites of every result file below the folder' {
        $folder = Join-Path $TestDrive 'results'
        New-Item -ItemType Directory -Path (Join-Path $folder 'device') | Out-Null
        Set-Content -LiteralPath (Join-Path $folder 'TEST-a.xml') -Value '<?xml version="1.0"?><testsuite name="a" tests="3" failures="1" errors="0" skipped="1"><testcase name="x"/></testsuite>'
        Set-Content -LiteralPath (Join-Path $folder 'device\TEST-b.xml') -Value '<?xml version="1.0"?><testsuite name="b" tests="2" failures="0" errors="1" skipped="0"/>'

        $counts = Get-TestResultCounts -Folder $folder

        $counts.Tests | Should -Be 5
        $counts.Failures | Should -Be 1
        $counts.Errors | Should -Be 1
        $counts.Skipped | Should -Be 1
    }

    It 'counts no tests in a folder that does not exist' {
        (Get-TestResultCounts -Folder (Join-Path $TestDrive 'none')).Tests | Should -Be 0
    }
}
```

Run: `scripts\test-scripts.ps1`
Expected: the two new tests fail, because `Get-TestResultCounts` does not exist.

In `scripts/lib/Kestrel.ps1`, add this function at the end:

```powershell
# Adds up the results that Gradle's connected tests wrote as JUnit XML files below a folder: the numbers of tests,
# failures, errors and skipped tests. A folder that does not exist counts no tests.
function Get-TestResultCounts([string]$Folder) {
    $counts = [ordered]@{ Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
    if (Test-Path -LiteralPath $Folder) {
        foreach ($file in Get-ChildItem -LiteralPath $Folder -Recurse -Filter 'TEST-*.xml') {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            foreach ($suite in @($xml.SelectNodes('//testsuite'))) {
                $counts.Tests += [int]$suite.tests
                $counts.Failures += [int]$suite.failures
                $counts.Errors += [int]$suite.errors
                $counts.Skipped += [int]$suite.skipped
            }
        }
    }
    return [pscustomobject]$counts
}
```

Create `scripts/voice-test.ps1`:

```powershell
# Runs the device tests of Kestrel's speech engines (the package dev.kestrel.app.voice) on one test device (design spec,
# section 16.4). They need the speech models, so the script installs Kestrel, copies the models with
# scripts\push-models.ps1, and keeps Kestrel and its test app installed afterwards, because uninstalling Kestrel deletes
# the models. The tests skip themselves when the models are missing, so a run with a skipped test fails. Afterwards the
# script prints the timings and memory use that the tests logged.
# Example: scripts\voice-test.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
& "$PSScriptRoot\install.ps1" -Serial $Serial
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$PSScriptRoot\push-models.ps1" -Serial $Serial
$results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
$adb = Get-Adb
# The tests log their measurements; clearing the log first keeps only this run's lines.
& $adb -s $Serial logcat -c
& "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':app:connectedDebugAndroidTest' `
    '-Pandroid.testInstrumentationRunnerArguments.package=dev.kestrel.app.voice' `
    '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
$code = $LASTEXITCODE
$counts = Get-TestResultCounts -Folder $results
Write-Host "Voice tests: $($counts.Tests) run, $($counts.Failures) failed, $($counts.Errors) errors, $($counts.Skipped) skipped."
& $adb -s $Serial logcat -d -s 'KestrelVoiceTest:I' | ForEach-Object { Write-Host $_ }
if ($code -ne 0) { exit $code }
if ($counts.Tests -eq 0 -or $counts.Skipped -gt 0) {
    Write-Host 'The run has no tests or has skipped tests, so it fails.'
    exit 1
}
exit 0
```

Run: `scripts\test-scripts.ps1`
Expected: every test passes in both shells, and PSScriptAnalyzer reports nothing.

- [ ] **Step 6: Run the voice tests**

Run: `scripts\voice-test.ps1 -Serial <serial>`
Expected: it ends with exit code 0 and prints "Voice tests: 7 run, 0 failed, 0 errors, 0 skipped." (2 tests of `ModelFolderTest`, 2 of `SynthesizerTest` and 3 of `SpeechDetectorTest`), followed by a line "voice: loaded in … ms; … s of speech in … ms".

Run: `scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.voice.SynthesizerTest'` after `& (Get-Adb) -s <serial> uninstall dev.kestrel` (after `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain` in the same command).
Expected: `BUILD SUCCESSFUL`, and the result file shows `skipped="2"`: without the models, the tests skip themselves.

- [ ] **Step 7: Document the voice tests**

In `README.md`, add this paragraph after the code block in "Testing on a device":

```markdown
The device tests of the speech engines need the speech models on the device. `scripts\voice-test.ps1 -Serial $serial` installs Kestrel, copies the models with `push-models.ps1`, runs the tests of the package `dev.kestrel.app.voice`, and keeps Kestrel and its test app installed, because uninstalling Kestrel deletes the models. It fails when a test was skipped, and it prints the timings and the memory use that the tests measured. `connected-test.ps1` runs these tests too, but they skip themselves when the models are missing, and at the end Gradle uninstalls Kestrel, which deletes the models.
```

- [ ] **Step 8: Commit**

```powershell
git add core/voice app/src scripts README.md
git commit -m "feat(voice): Piper voice and Silero speech detection through sherpa-onnx, with device tests" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 7: Keyword spotting

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/KeywordSpotter.kt`
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/KeywordLines.kt`, `SherpaKeywordSpotter.kt`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/KeywordSpotterTest.kt`

**Interfaces:**
- Consumes: `VoiceEngines`, `SAMPLE_RATE` and `VoiceTests` (Task 6).
- Produces: in package `dev.kestrel.voice`: `enum class Keyword { KESTREL, STOP, CANCEL }`, `data class KeywordHit(keyword: Keyword, seconds: Float)`, `interface KeywordSpotter : AutoCloseable { fun accept(samples: FloatArray): List<KeywordHit> }`, and the member function `suspend fun keywordSpotter(): KeywordSpotter` of `VoiceEngines`.

The spotter's settings come from the prototype (see "Things that surprised us"): nine spellings of "Kestrel", a boost of 2.0 and a threshold of 0.15 for every keyword, and 8 active paths. Plan 2C tunes them with the owner's recordings and an hour of room audio (spike S5).

- [ ] **Step 1: Write the failing JVM tests**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/KeywordLinesTest.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Keyword
import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordLinesTest {
    private val lines = KeywordLines.text().lines().filter { it.isNotEmpty() }

    @Test
    fun everyLineNamesAKeywordAndEveryKeywordHasALine() {
        val named = lines.map { line -> KeywordLines.keyword(line.substringAfterLast(" @")) }

        assertEquals(Keyword.entries.toSet(), named.toSet())
    }

    @Test
    fun anUnknownNameIsNoKeyword() {
        assertEquals(null, KeywordLines.keyword(""))
    }
}
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`, add this test:

```kotlin
    @Test
    fun theKeywordSpottersFilesAreInTheManifest() {
        for (path in listOf(VoiceEngines.KWS_ENCODER, VoiceEngines.KWS_DECODER, VoiceEngines.KWS_JOINER, VoiceEngines.KWS_TOKENS)) {
            assertEquals(path, Engine.KWS, engineOf(path))
        }
    }
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD FAILED`, because `KeywordLines` and the paths do not exist.

- [ ] **Step 2: Write the keyword spotter**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/KeywordSpotter.kt`:

```kotlin
package dev.kestrel.voice

/** The words that the keyword spotter listens for (design spec, section 8.2). */
enum class Keyword { KESTREL, STOP, CANCEL }

/** A keyword that the spotter heard, [seconds] after the start of its audio, which is shortly after the keyword ended. */
data class KeywordHit(val keyword: Keyword, val seconds: Float)

/**
 * Listens for "Kestrel", "stop" and "cancel" in a stream of 16 kHz audio (design spec, sections 8.2–8.4). Not
 * thread-safe: feed it from one thread, and close it when it is no longer needed.
 */
interface KeywordSpotter : AutoCloseable {
    /** Feeds 16 kHz samples, any number at a time, and returns the keywords that it heard in them. */
    fun accept(samples: FloatArray): List<KeywordHit>
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/KeywordLines.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Keyword

/**
 * The keyword spotter's keywords, written in the word pieces of its model, one keyword spelling to a line:
 * `<pieces> :<boost> #<threshold> @<name>` (design spec, section 8.2, B37). With a single spelling, the spotter never heard
 * "Kestrel" in tests, so it knows nine. Every piece must be in the model's tokens.txt: one unknown piece makes the whole
 * spotter fail to load.
 */
internal object KeywordLines {
    private val KESTREL = listOf(
        "▁K EST RE L", "▁K ES T RE L", "▁K EST R EL", "▁K ES T R EL", "▁K E S T R E L",
        "▁K EST R A L", "▁K ES T R AL", "▁C EST RE L", "▁K EST R OL",
    )
    private const val STOP = "▁ST O P"
    private const val CANCEL = "▁CAN CE L"
    private const val TUNING = ":2.0 #0.15"

    /** The text of the spotter's keywords file. */
    fun text(): String = buildString {
        for (spelling in KESTREL) append("$spelling $TUNING @kestrel\n")
        append("$STOP $TUNING @stop\n")
        append("$CANCEL $TUNING @cancel\n")
    }

    /** The keyword that a line's name stands for; null for an empty or unknown name. */
    fun keyword(name: String): Keyword? = when (name) {
        "kestrel" -> Keyword.KESTREL
        "stop" -> Keyword.STOP
        "cancel" -> Keyword.CANCEL
        else -> null
    }
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaKeywordSpotter.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.kestrel.voice.KeywordHit
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.SAMPLE_RATE
import java.io.File
import com.k2fsa.sherpa.onnx.KeywordSpotter as SherpaSpotter

/** sherpa-onnx's English keyword model, listening to one continuous stream of audio (B37). */
internal class SherpaKeywordSpotter(encoder: File, decoder: File, joiner: File, tokens: File, keywords: File) : KeywordSpotter {
    private val spotter = SherpaSpotter(
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = encoder.path, decoder = decoder.path, joiner = joiner.path),
                tokens = tokens.path,
                modelType = "zipformer2",
                numThreads = 1,
            ),
            // With 4 active paths, the spotter missed "Kestrel" inside a sentence.
            maxActivePaths = 8,
            keywordsFile = keywords.path,
        ),
    )
    private val stream = spotter.createStream()
    private var samplesSeen = 0L

    override fun accept(samples: FloatArray): List<KeywordHit> {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        samplesSeen += samples.size
        val hits = mutableListOf<KeywordHit>()
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = KeywordLines.keyword(spotter.getResult(stream).keyword) ?: continue
            hits += KeywordHit(keyword, samplesSeen / SAMPLE_RATE.toFloat())
            // Listens afresh, so that one spoken keyword gives one hit.
            spotter.reset(stream)
        }
        return hits
    }

    override fun close() {
        stream.release()
        spotter.release()
    }
}
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, add the import `dev.kestrel.voice.sherpa.KeywordLines` and `dev.kestrel.voice.sherpa.SherpaKeywordSpotter`, add this function after `speechDetector`:

```kotlin
    suspend fun keywordSpotter(): KeywordSpotter = withContext(Dispatchers.IO) {
        requireReady(Engine.KWS)
        // sherpa-onnx reads the keywords from a file.
        val keywords = File(workFolder, "keywords.txt")
        keywords.parentFile?.mkdirs()
        keywords.writeText(KeywordLines.text())
        SherpaKeywordSpotter(models.file(KWS_ENCODER), models.file(KWS_DECODER), models.file(KWS_JOINER), models.file(KWS_TOKENS), keywords)
    }
```

and add these constants to its companion object:

```kotlin
        const val KWS_ENCODER = "kws-gigaspeech/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val KWS_DECODER = "kws-gigaspeech/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val KWS_JOINER = "kws-gigaspeech/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val KWS_TOKENS = "kws-gigaspeech/tokens.txt"
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Write the device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/KeywordSpotterTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.frames
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.speech
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeywordSpotterTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.KWS, Engine.TTS)

    private fun heard(spotter: KeywordSpotter, text: String): List<Keyword> = frames(speech(text), spotter::accept).map { it.keyword }

    @Test
    fun itHearsItsKeywords() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        graph.voice.keywordSpotter().use { spotter ->
            log("keyword spotter: loaded in ${SystemClock.elapsedRealtime() - started} ms")

            // One spotter hears the phrases one after another, as it hears the microphone.
            assertEquals(listOf(Keyword.KESTREL), heard(spotter, "Kestrel, open the settings."))
            assertEquals(listOf(Keyword.KESTREL), heard(spotter, "Hey Kestrel, what is the weather?"))
            assertEquals(listOf(Keyword.STOP), heard(spotter, "Stop."))
            assertEquals(listOf(Keyword.CANCEL), heard(spotter, "Cancel that."))
        }
    }

    @Test
    fun itIgnoresOtherSpeech() = runBlocking {
        graph.voice.keywordSpotter().use { spotter ->
            for (text in listOf("Scroll down and tap the blue button.", "Chest roll and a pastry, please.", "Open Viber and message Zoran.")) {
                assertEquals(text, emptyList<Keyword>(), heard(spotter, text))
            }
        }
    }

    @Test
    fun aHitComesSoonAfterTheKeyword() = runBlocking {
        graph.voice.keywordSpotter().use { spotter ->
            val audio = speech("Stop.")
            val hit = frames(audio, spotter::accept).single()

            // The word starts after 0.5 s of silence, and the audio ends 1 s after the voice.
            assertTrue("${hit.seconds} s", hit.seconds in 0.5f..audio.size / SAMPLE_RATE.toFloat())
        }
    }
}
```

- [ ] **Step 4: Run the voice tests**

Run: `scripts\voice-test.ps1 -Serial <serial>`
Expected: "Voice tests: 10 run, 0 failed, 0 errors, 0 skipped.", and a line "keyword spotter: loaded in … ms".

If `itHearsItsKeywords` or `itIgnoresOtherSpeech` fails, do not change the keyword lines by trial and error. Report the phrase and the hits, so that the controller can decide with the prototype's notes; the spotter's tuning belongs to spike S5 in plan 2C.

- [ ] **Step 5: Commit**

```powershell
git add core/voice app/src/androidTest
git commit -m "feat(voice): keyword spotting for Kestrel, stop and cancel" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 8: Speech recognition with boost words, and spike S8

**Files:**
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/Recognizer.kt`
- Create: `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/BoostWords.kt`, `BpeVocab.kt`, `TranscriptChoice.kt`, `SherpaRecognizer.kt`
- Modify: `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`
- Test: `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/BoostWordsTest.kt`, `BpeVocabTest.kt`, `TranscriptChoiceTest.kt`, and `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/RecognizerTest.kt`, `OnnxRuntimeTogetherTest.kt`

**Interfaces:**
- Consumes: `VoiceEngines`, `SAMPLE_RATE`, `Utterance`, `SherpaOnnx` and `VoiceTests` (Tasks 1 and 6).
- Produces: in package `dev.kestrel.voice`: `data class Transcript(text: String, pieces: Int, lastWordEnd: Float)`, `interface Recognizer : AutoCloseable { fun transcribe(samples: FloatArray, boostWords: List<String> = emptyList()): Transcript }`, and the member function `suspend fun recognizer(): Recognizer` of `VoiceEngines`. Plan 2B passes the labels on the screen, the app names and the other boost words of section 8.3.

Never log a transcript: the owner's speech must not reach logcat. The device tests log only durations and memory use.

- [ ] **Step 1: Write the failing JVM tests**

Create `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/BoostWordsTest.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class BoostWordsTest {
    @Test
    fun phrasesAreJoinedWithSlashes() {
        assertEquals("Viber/Zoran Petrović", BoostWords.format(listOf("Viber", "Zoran Petrović")))
    }

    @Test
    fun separatorsAndSymbolsBecomeSpaces() {
        assertEquals("a b/c d/e f", BoostWords.format(listOf("a/b", "c:d", "e 😀 f")))
    }

    @Test
    fun apostrophesHyphensAndDigitsStay() {
        assertEquals("O'Brien/Jean-Luc/R2-D2", BoostWords.format(listOf("O'Brien", "Jean-Luc", "R2-D2")))
    }

    @Test
    fun emptyLongAndRepeatedPhrasesAreDropped() {
        assertEquals("Viber", BoostWords.format(listOf(" ", "/", "x".repeat(41), "Viber", "VIBER")))
    }

    @Test
    fun atMostAHundredPhrasesAreKept() {
        assertEquals(100, BoostWords.format((1..150).map { "Contact $it" }).split('/').size)
    }
}
```

Create `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/BpeVocabTest.kt`:

```kotlin
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
```

Create `core/voice/src/test/kotlin/dev/kestrel/voice/sherpa/TranscriptChoiceTest.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TranscriptChoiceTest {
    private val whole = Transcript("Call Dragoljub on WhatsApp.", pieces = 9, lastWordEnd = 2.6f)
    private val cut = Transcript("Call Drago", pieces = 4, lastWordEnd = 0.9f)

    @Test
    fun aTranscriptThatReachesTheEndIsKeptWithoutASecondDecoding() {
        var decoded = 0

        assertSame(whole, TranscriptChoice.choose(2.7f, whole) { decoded++; cut })
        assertEquals(0, decoded)
    }

    @Test
    fun aTranscriptThatEndsEarlyGivesWayToALongerOne() {
        assertSame(whole, TranscriptChoice.choose(2.7f, cut) { whole })
    }

    @Test
    fun aTranscriptThatEndsEarlyStaysWhenTheSecondIsNotLonger() {
        assertSame(cut, TranscriptChoice.choose(2.7f, cut) { Transcript("Call", pieces = 1, lastWordEnd = 0.3f) })
    }

    @Test
    fun anEmptyTranscriptOfLongAudioIsDecodedAgain() {
        var decoded = 0

        TranscriptChoice.choose(2f, Transcript("", pieces = 0, lastWordEnd = 0f)) { decoded++; Transcript("", 0, 0f) }

        assertEquals(1, decoded)
    }
}
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/VoiceModelPathsTest.kt`, add this test:

```kotlin
    @Test
    fun theRecognizersFilesAreInTheManifest() {
        for (path in listOf(VoiceEngines.ASR_ENCODER, VoiceEngines.ASR_DECODER, VoiceEngines.ASR_JOINER, VoiceEngines.ASR_TOKENS)) {
            assertEquals(path, Engine.ASR, engineOf(path))
        }
    }
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD FAILED`, because these classes and paths do not exist.

- [ ] **Step 2: Write the pure parts**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/Recognizer.kt`:

```kotlin
package dev.kestrel.voice

/**
 * What the recogniser heard: the [text], the number of word [pieces] in it, and the time in seconds at which the last
 * word piece ends ([lastWordEnd]), which is 0 when it heard nothing.
 */
data class Transcript(val text: String, val pieces: Int, val lastWordEnd: Float)

/**
 * Transcribes utterances of 16 kHz audio (design spec, sections 8.2 and 8.3). Not thread-safe: use one instance from one
 * thread at a time, and close it when it is no longer needed, because it holds about 700 MB of model in native memory.
 */
interface Recognizer : AutoCloseable {
    /** Transcribes one utterance. [boostWords] are words that the speaker is likely to say, such as labels on the screen. */
    fun transcribe(samples: FloatArray, boostWords: List<String> = emptyList()): Transcript
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/BoostWords.kt`:

```kotlin
package dev.kestrel.voice.sherpa

/**
 * Puts boost words into sherpa-onnx's format for one utterance: phrases separated by "/" (design spec, section 8.3, B39).
 * "/" separates phrases and ":" would start a boost of its own, so every character other than a letter, a digit, an
 * apostrophe or a hyphen becomes a space. Empty phrases, phrases longer than [MAX_LENGTH] characters and repeated phrases
 * (ignoring case) are dropped, and at most [MAX_PHRASES] phrases are kept. Phrases in other scripts or with emoji were
 * harmless in tests, because sherpa-onnx skips what the model cannot spell.
 */
internal object BoostWords {
    const val MAX_PHRASES = 100
    const val MAX_LENGTH = 40

    fun format(phrases: List<String>): String = phrases.asSequence()
        .map(::clean)
        .filter { it.isNotEmpty() && it.length <= MAX_LENGTH }
        .distinctBy { it.lowercase() }
        .take(MAX_PHRASES)
        .joinToString("/")

    private fun clean(phrase: String): String = phrase
        .map { if (it.isLetterOrDigit() || it == '\'' || it == '-') it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/BpeVocab.kt`:

```kotlin
package dev.kestrel.voice.sherpa

/**
 * sherpa-onnx needs Parakeet's word pieces as a `bpe.vocab` file before it applies boost words, and Parakeet's package
 * has none (B39). This turns each line `<piece> <id>` of the model's tokens.txt into a line `<piece>\t-1.0`. Pieces that
 * were scored by their position cut a transcript short in tests; the same score for every piece did not.
 */
internal object BpeVocab {
    fun fromTokens(tokens: String): String = buildString {
        for (line in tokens.lineSequence()) {
            val piece = line.substringBeforeLast(' ', missingDelimiterValue = "")
            if (piece.isNotEmpty()) append(piece).append("\t-1.0\n")
        }
    }
}
```

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/TranscriptChoice.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Transcript

/**
 * Decides whether a transcript that was decoded with boost words is kept (design spec, section 8.3). A strong boost once
 * cut a transcript short in tests, so when the last word ends more than [GAP_SECONDS] before the audio does, the audio is
 * decoded again without boost words, and the second transcript wins if it has more word pieces.
 */
internal object TranscriptChoice {
    const val GAP_SECONDS = 1.0f

    fun choose(audioSeconds: Float, boosted: Transcript, plain: () -> Transcript): Transcript {
        if (audioSeconds - boosted.lastWordEnd <= GAP_SECONDS) return boosted
        val second = plain()
        return if (second.pieces > boosted.pieces) second else boosted
    }
}
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD FAILED` only because `VoiceEngines.ASR_*` do not exist yet.

- [ ] **Step 3: Write the recogniser**

Create `core/voice/src/main/kotlin/dev/kestrel/voice/sherpa/SherpaRecognizer.kt`:

```kotlin
package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Transcript
import java.io.File

/** Parakeet-TDT-0.6B-v3 through sherpa-onnx, with boost words (design spec, sections 8.2 and 8.3, B39). */
internal class SherpaRecognizer(encoder: File, decoder: File, joiner: File, tokens: File, vocab: File) : Recognizer {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = encoder.path, decoder = decoder.path, joiner = joiner.path),
                tokens = tokens.path,
                modelType = "nemo_transducer",
                numThreads = THREADS,
                // Without these two, sherpa-onnx ends the process when it gets boost words.
                modelingUnit = "bpe",
                bpeVocab = vocab.path,
            ),
            // Only modified beam search applies boost words.
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            hotwordsScore = BOOST,
        ),
    )

    override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
        val boost = BoostWords.format(boostWords)
        val first = decode(samples, boost)
        if (boost.isEmpty()) return first
        return TranscriptChoice.choose(samples.size / SAMPLE_RATE.toFloat(), first) { decode(samples, "") }
    }

    private fun decode(samples: FloatArray, boost: String): Transcript {
        val stream = if (boost.isEmpty()) recognizer.createStream() else recognizer.createStream(boost)
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val end = if (result.timestamps.isEmpty()) 0f else result.timestamps.last() + (result.durations.lastOrNull() ?: 0f)
            return Transcript(result.text.trim(), result.tokens.size, end)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    private companion object {
        // Plan 2C measures whether more threads are faster on the A71 (spike S5).
        const val THREADS = 2
        // A moderate boost: strong ones cut transcripts short in tests (design spec, section 8.3).
        const val BOOST = 2.0f
    }
}
```

In `core/voice/src/main/kotlin/dev/kestrel/voice/VoiceEngines.kt`, add the imports `dev.kestrel.voice.sherpa.BpeVocab` and `dev.kestrel.voice.sherpa.SherpaRecognizer`, add this function after `keywordSpotter`:

```kotlin
    suspend fun recognizer(): Recognizer = withContext(Dispatchers.IO) {
        requireReady(Engine.ASR)
        val vocab = File(workFolder, "parakeet-bpe.vocab")
        vocab.parentFile?.mkdirs()
        vocab.writeText(BpeVocab.fromTokens(models.file(ASR_TOKENS).readText()))
        SherpaRecognizer(models.file(ASR_ENCODER), models.file(ASR_DECODER), models.file(ASR_JOINER), models.file(ASR_TOKENS), vocab)
    }
```

and add these constants to its companion object:

```kotlin
        const val ASR_ENCODER = "asr-parakeet-v3/encoder.int8.onnx"
        const val ASR_DECODER = "asr-parakeet-v3/decoder.int8.onnx"
        const val ASR_JOINER = "asr-parakeet-v3/joiner.int8.onnx"
        const val ASR_TOKENS = "asr-parakeet-v3/tokens.txt"
```

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Write the device tests**

In `gradle/libs.versions.toml`, add `onnxruntime = "1.28.0"` at the end of `[versions]`, and this line at the end of `[libraries]`:

```toml
onnxruntime-android = { module = "com.microsoft.onnxruntime:onnxruntime-android", version.ref = "onnxruntime" }
```

In `app/build.gradle.kts`, add this line after `androidTestImplementation(libs.okhttp.mockwebserver3)`:

```kotlin
    // Spike S8: Kestrel's OCR will use onnxruntime-android beside sherpa-onnx's own ONNX Runtime (design spec, section 19).
    androidTestImplementation(libs.onnxruntime.android)
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/RecognizerTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.pssMegabytes
import dev.kestrel.app.voice.VoiceTests.voice
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Transcript
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The speech detector cuts an utterance where the speech ends, so these tests transcribe the voice without silence around
 * it. With a second of silence after the last word, every transcript with boost words would be decoded a second time
 * without them (TranscriptChoice), and a boost that cuts a transcript short would go unnoticed.
 */
@RunWith(AndroidJUnit4::class)
class RecognizerTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.ASR, Engine.TTS)

    /** The words of a text in lowercase, without punctuation, for comparing transcripts. */
    private fun words(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun transcribe(text: String, boostWords: List<String> = emptyList()): Transcript {
        val audio = voice(text)
        val started = SystemClock.elapsedRealtime()
        return recognizer().transcribe(audio, boostWords).also {
            log("recognizer: ${"%.1f".format(audio.size / SAMPLE_RATE.toFloat())} s of audio in ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    @Test
    fun itTranscribesCommands() {
        assertEquals("scroll down and tap the blue button", words(transcribe("Scroll down and tap the blue button.").text))
        assertEquals("kestrel open the settings", words(transcribe("Kestrel, open the settings.").text))
    }

    @Test
    fun boostWordsDoNotChangeACorrectTranscript() {
        val text = "Scroll down and tap the blue button."
        val boostWords = listOf("Viber", "日本語", "😀 smile", "مرحبا", "O'Brien", "Jean-Luc", "R2-D2", " / ", "a:b") + (1..100).map { "Contact $it" }

        assertEquals(words(transcribe(text).text), words(transcribe(text, boostWords).text))
    }

    @Test
    fun theLastWordEndsWhereTheVoiceStops() {
        val text = "Scroll down and tap the blue button."
        val transcript = transcribe(text)

        // The voice ends with a little silence of its own.
        assertEquals(voice(text).size / SAMPLE_RATE.toFloat(), transcript.lastWordEnd, 0.5f)
    }

    companion object {
        // Loading takes seconds and about 700 MB, so the tests share one recogniser.
        private var shared: Recognizer? = null

        @Synchronized
        private fun recognizer(): Recognizer = shared ?: run {
            val before = pssMegabytes()
            val started = SystemClock.elapsedRealtime()
            runBlocking { graph.voice.recognizer() }.also {
                log("recognizer: loaded in ${SystemClock.elapsedRealtime() - started} ms; memory (PSS) $before MB -> ${pssMegabytes()} MB")
                shared = it
            }
        }

        @JvmStatic
        @AfterClass
        fun closeTheRecognizer() {
            shared?.close()
            shared = null
        }
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/OnnxRuntimeTogetherTest.kt`:

```kotlin
package dev.kestrel.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.silence
import dev.kestrel.voice.SherpaOnnx
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer

/**
 * Spike S8 (design spec, section 19): sherpa-onnx links its own ONNX Runtime 1.28.2 statically, and Kestrel's OCR will
 * use onnxruntime-android. Both must work in one process.
 */
@RunWith(AndroidJUnit4::class)
class OnnxRuntimeTogetherTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.VAD)

    @Test
    fun bothOnnxRuntimesWorkInOneProcess() = runBlocking {
        graph.voice.speechDetector().use { detector ->
            detector.accept(silence(0.1f))

            // onnxruntime-android runs Silero VAD's model on silence.
            val environment = OrtEnvironment.getEnvironment()
            environment.createSession(graph.models.file("vad-silero/silero_vad.onnx").path, OrtSession.SessionOptions()).use { session ->
                val inputs = session.inputInfo.mapValues { (_, node) ->
                    val shape = (node.info as TensorInfo).shape.map { if (it < 0) 1L else it }.toLongArray()
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(shape.fold(1L, Long::times).toInt())), shape)
                }
                session.run(inputs).use { result -> assertEquals(session.outputNames.size, result.size()) }
                inputs.values.forEach { it.close() }
            }

            // sherpa-onnx still works after the other ONNX Runtime ran.
            assertEquals(0, (detector.accept(silence(0.5f)) + detector.finish()).size)
        }
        assertEquals("1.28.2", SherpaOnnx.onnxRuntimeVersion)
    }
}
```

- [ ] **Step 5: Run the voice tests**

Run: `scripts\voice-test.ps1 -Serial <serial>`
Expected: "Voice tests: 14 run, 0 failed, 0 errors, 0 skipped.", with lines that give the recogniser's load time, its memory use (PSS) before and after loading, and the time for each transcription. On kestrel-api37 the prototype measured 3.1–3.7 s to load, about 800 MB after loading, and 100–400 ms per transcription.

If `OnnxRuntimeTogetherTest` fails, do not change the build to make it pass: S8 is a spike, and a failure is its answer. Report the failure with the test's message, and the controller decides on the fallback in section 19 of the spec, a sherpa-onnx build that shares one ONNX Runtime library.

- [ ] **Step 6: Commit**

```powershell
git add core/voice gradle/libs.versions.toml app/build.gradle.kts app/src/androidTest
git commit -m "feat(voice): Parakeet recognition with boost words, and spike S8" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 9: Full checks, and the results in the spec

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 19.1)
- Modify: `README.md` (section "Spikes")

**Interfaces:**
- Consumes: everything from Tasks 1–8.
- Produces: the result of spike S8 and the emulator's measurements in section 19.1 of the spec.

- [ ] **Step 1: Run every check on this computer**

Run: `scripts\gradle.ps1 check kestrelAudit` and then `scripts\gradle.ps1 :build-logic:convention:check`
Expected: both `BUILD SUCCESSFUL`, without new `w:` lines.

Run: `scripts\test-scripts.ps1`
Expected: every test passes in both shells, and PSScriptAnalyzer reports nothing.

- [ ] **Step 2: Run every device test**

Restart the emulator with a cold boot first if it has run for more than a few hours (see "How to work in this plan").

Run: `scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :core:voice:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`. The voice tests run if the models are still on the emulator from an earlier `voice-test.ps1`, and skip themselves otherwise; at the end Gradle uninstalls Kestrel, which deletes the models.

Run: `scripts\voice-test.ps1 -Serial <serial>`
Expected: exit code 0 and "Voice tests: 14 run, 0 failed, 0 errors, 0 skipped." Keep the measurement lines that it prints for Step 3.

- [ ] **Step 3: Write the results into the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, add this paragraph at the end of section 19.1, after the paragraph that starts "**Device tests on the A71 (plan 1C).**". Replace each value in angle brackets with the number from the lines that `voice-test.ps1` printed in Step 2: the line "voice: loaded in …" for the voice, the lines "recognizer: …" for the recogniser (the load time, the memory before and after loading, and the shortest and longest transcription time), and the line "keyword spotter: loaded in …".

```markdown
- **S8 and the speech engines on the emulator (plan 2A).** In Kestrel's process on kestrel-api37, onnxruntime-android 1.28.0 ran Silero VAD's model while sherpa-onnx's library, with its own ONNX Runtime 1.28.2 linked statically, was loaded, and sherpa-onnx's speech detector still worked afterwards (`OnnxRuntimeTogetherTest`). sherpa-onnx's library exports only its JNI functions, so the two copies of ONNX Runtime do not meet. The A71 repeats the check in plan 2C. In the same run, with synthetic speech in Kestrel's own voice: the voice loaded in <voice load> ms; the keyword spotter loaded in <spotter load> ms and heard "Kestrel", "stop" and "cancel" in every test phrase and nothing in the others; the recogniser loaded in <recogniser load> ms, the app's memory (PSS) grew from <PSS before> MB to <PSS after> MB, and each transcription of 1–3 s of audio took <shortest>–<longest> ms. These numbers come from the emulator, which runs on the PC's processor; the A71's numbers for the targets of section 5.3 come from spike S5 in plan 2C.
```

In `README.md`, in the section "Spikes", add this item after the item for `scripts\spike-s7.ps1`:

```markdown
- Spike S8, whether sherpa-onnx's own ONNX Runtime and onnxruntime-android work in one process, is the device test `OnnxRuntimeTogetherTest`, which `scripts\voice-test.ps1 -Serial $serial` runs with the other voice tests.
```

- [ ] **Step 4: Commit**

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "docs: spike S8 and the speech engines' measurements on the emulator" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

After this task, run `scripts\gradle.ps1 --stop`. The emulator keeps Kestrel and the models from `voice-test.ps1`; plan 2B starts from there.
