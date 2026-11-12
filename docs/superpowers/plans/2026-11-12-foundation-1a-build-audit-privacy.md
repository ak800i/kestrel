# Kestrel Foundation 1A: Build, Audit and Privacy Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create Kestrel's repository with a Gradle build, a build-time privacy audit, CI, the `kestrel-api37` test emulator and the encrypted privacy core (logs, settings, host allowlist and network gateway), so that plan 1B can build the screen layer on top of it.

**Architecture:** A multi-module Android project. Convention plugins in `build-logic/` set the SDK levels and apply the `kestrel.audit` plugin to every module that ships in the app. `:core:privacy` owns Kestrel's encrypted Room storage and its only HTTP client, `:core:safety` holds the safety settings, and `:app` wires the parts together by hand in `AppGraph`.

**Tech Stack:** Gradle 9.9.1 (Kotlin DSL, version catalog, included build), Android Gradle plugin 9.5.1 with built-in Kotlin 2.4.21, KSP 2.3.13, Room 2.8.5, kotlinx.serialization 1.13.0, kotlinx.coroutines 1.11.0, OkHttp 5.5.1, Jetpack Compose (BOM 2026.09.00), ASM 9.11.1, JUnit 4, GitHub Actions and PowerShell.

**Spec:** [2026-11-12-kestrel-design.md](../specs/2026-11-12-kestrel-design.md). This is plan 1A of sub-project 1, Foundation (spec section 18.1). Plan 1B (screen layer) and plan 1C (settings app and spikes) are written after this plan is done, so that they can use its real code.

## Global Constraints

- Package and application ID `dev.kestrel`; repository `H:\Code\kestrel`, published as `github.com/ak800i/kestrel` (D21).
- Licence GPL-3.0-or-later for all of Kestrel; every dependency that ships in the app must have a licence that is compatible with GPL-3.0, such as Apache-2.0, MIT or BSD. Tools used only to build or test Kestrel, such as JUnit, are exempt (D28, section 17).
- `minSdk 33`, `targetSdk 37` and `compileSdk 37` (section 6).
- Gradle with the Kotlin DSL and a version catalog; Android Gradle plugin 9.5.1 and Kotlin 2.4.21 (section 17). The plugin has Kotlin built in: never apply `org.jetbrains.kotlin.android`, and use KSP instead of kapt.
- Libraries in this plan: Jetpack Compose, Kotlin coroutines, kotlinx.serialization, OkHttp and Room. There is no dependency-injection framework; one `AppGraph` class wires the parts together by hand (section 17).
- Kestrel has no WebView, no analytics, no crash reporting and no ads, and it does not use Google Play services, Firebase, ML Kit or Android's geocoder (section 13.1).
- All network traffic from Kotlin code goes through one OkHttp client in `:core:privacy`. Requests to hosts outside the allowlist are refused and logged. HTTPS is required, except for local-network or localhost servers that the owner adds (section 13.1).
- Kestrel stores its data in a Room database. Sensitive fields are encrypted with AES-GCM using Android Keystore keys. `android:allowBackup="false"` and data-extraction rules exclude everything (section 13.4).
- The activity log and the network log are encrypted and kept for 7 days. "Delete history" wipes both. Screenshots, audio and credentials are never logged (section 13.5).
- Test mode is on during all testing (D25).
- Testing happens only on the `kestrel-api37` emulator (Android 17, API 37, Google Play x86_64 image) and the Galaxy A71. Never test on the Galaxy S23+ unless the owner asks, and never change the existing AVD `zoran` (D20, section 6).
- The Android SDK is in `D:\AndroidVMs\SDK`. AVDs live in `D:\AndroidVMs\.android\.android\avd`, which the tools find through the user variable `ANDROID_SDK_HOME`.
- Scripts are PowerShell (section 17). Environment changes stay inside the process that makes them.
- Kestrel is installed with `adb install` (section 13.6).

## How to work in this plan

- Run every command in PowerShell from the repository root, `H:\Code\kestrel`.
- Run Gradle only through `scripts\gradle.ps1`, which Task 1 creates. It sets `JAVA_HOME` to Android Studio's JDK and `ANDROID_HOME` to the SDK for its own process.
- Run on-device tests only through `scripts\connected-test.ps1`, which Task 3 creates. It refuses every device except `kestrel-api37` and the Galaxy A71.
- `scripts\start-emulator.ps1` (Task 3) starts `kestrel-api37` when it is not running and prints its adb serial, for example `emulator-5554`. Commands below pass that serial with `-Serial (scripts\start-emulator.ps1)`. If your shell stops child processes when a command ends, start the emulator once from a separate terminal that stays open.
- A passing Gradle command ends with `BUILD SUCCESSFUL`. A failing one ends with `BUILD FAILED` and prints the reason above it.
- Commit at the end of every task. Use a Conventional Commits subject and end every commit message with this trailer line:

  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

  In PowerShell: `git commit -m "<subject>" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"`.

## File map

| Path | Responsibility |
|---|---|
| `.gitignore`, `.gitattributes`, `LICENSE` | Ignored build output, line endings, and the GPL-3.0 text |
| `scripts/lib/Kestrel.ps1` | Shared script helpers: toolchain paths, adb, the AVD folder, and the device guard |
| `scripts/gradle.ps1` | Runs the Gradle wrapper with the right JDK and SDK |
| `scripts/create-avd.ps1`, `scripts/start-emulator.ps1` | Create and start `kestrel-api37` |
| `scripts/connected-test.ps1`, `scripts/install.ps1` | Run on-device tests and install the app on one allowed device |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` | The root build and the version catalog |
| `build-logic/convention/.../*ConventionPlugin.kt`, `KestrelSdk.kt`, `AndroidDefaults.kt` | Shared module settings: SDK levels, Java 17, and the audit |
| `build-logic/convention/.../audit/*` | The `kestrel.audit` plugin: dependency rules, network-API scan and native-library scan |
| `audit/rules.txt` | The audit's deny, pin and allow rules and its justified exceptions |
| `.github/workflows/ci.yml` | CI: unit tests, lint and the audit |
| `core/safety/` | `SafetySettings`, including test mode |
| `core/privacy/.../crypto/` | AES-GCM field encryption with Android Keystore keys |
| `core/privacy/.../db/` | The Room database, its tables and its queries |
| `core/privacy/.../logs/` | The encrypted activity log and network log with 7-day retention |
| `core/privacy/.../settings/` | Encrypted settings |
| `core/privacy/.../network/` | The host allowlist and the network gateway, Kestrel's only HTTP client |
| `core/privacy/.../PrivacyStore.kt` | Opens the encrypted storage for the app |
| `app/` | The app: manifest, launcher icon, placeholder screen, `KestrelApplication` and `AppGraph` |
| `README.md`, `THIRD_PARTY_NOTICES.md` | What Kestrel is, how to build it, and the licences of its dependencies |

---

### Task 1: Repository basics, Gradle wrapper and `:core:safety`

**Files:**
- Create: `.gitignore`, `.gitattributes`, `LICENSE`
- Create: `scripts/lib/Kestrel.ps1`, `scripts/gradle.ps1`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `settings.gradle.kts`, `gradle.properties`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `build-logic/settings.gradle.kts`, `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/JvmLibraryConventionPlugin.kt`
- Create: `core/safety/build.gradle.kts`, `core/safety/src/main/kotlin/dev/kestrel/safety/SafetySettings.kt`
- Test: `core/safety/src/test/kotlin/dev/kestrel/safety/SafetySettingsTest.kt`

**Interfaces:**
- Produces: `scripts\gradle.ps1 <gradle arguments>`, which every later task uses.
- Produces: the shared helpers `Use-KestrelToolchain`, `Get-AvdHome`, `Get-Adb`, `Find-EmulatorSerial <avd name>` and `Assert-TestDevice -Serial <serial> [-AllowDailyPhone]` in `scripts/lib/Kestrel.ps1`.
- Produces: the plugin ID `kestrel.jvm.library` and the version-catalog aliases in `gradle/libs.versions.toml`.
- Produces: `dev.kestrel.safety.SafetySettings(testMode: Boolean = true)`, a `@Serializable` data class.

- [ ] **Step 1: Create `.gitignore` and `.gitattributes`**

`.gitignore`:

```gitignore
# Gradle and Kotlin
.gradle/
.kotlin/
build/
# Source folders named "build", such as the package dev.kestrel.build in build-logic
!**/src/**/build/

# Android Studio
.idea/
*.iml
local.properties
captures/

# Native builds
.cxx/
.externalNativeBuild/
```

`.gitattributes`:

```gitattributes
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
*.jar binary
*.so binary
*.png binary
```

- [ ] **Step 2: Download the licence and check it**

```powershell
Invoke-WebRequest -UseBasicParsing https://www.gnu.org/licenses/gpl-3.0.txt -OutFile LICENSE
(Get-FileHash LICENSE -Algorithm SHA256).Hash.ToLower()
```

Expected: `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`

- [ ] **Step 3: Create the script helpers**

`scripts/lib/Kestrel.ps1`:

```powershell
# Shared helpers for Kestrel's scripts. Dot-source this file; it changes only the current process.

function Use-KestrelToolchain {
    # Gradle runs on the JDK that comes with Android Studio (design spec, section 6).
    $studioJdk = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $studioJdk) {
        $env:JAVA_HOME = $studioJdk
    } elseif (-not $env:JAVA_HOME) {
        throw 'Set JAVA_HOME to a JDK 17 or newer.'
    }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = 'D:\AndroidVMs\SDK' }
    if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME" }
    # Newer emulator versions may ignore the legacy ANDROID_SDK_HOME variable, so point them at the AVDs directly.
    if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = Get-AvdHome }
}

function Get-AvdHome {
    if ($env:ANDROID_AVD_HOME) { return $env:ANDROID_AVD_HOME }
    if ($env:ANDROID_EMULATOR_HOME) { return (Join-Path $env:ANDROID_EMULATOR_HOME 'avd') }
    if ($env:ANDROID_USER_HOME) { return (Join-Path $env:ANDROID_USER_HOME 'avd') }
    if ($env:ANDROID_SDK_HOME) { return (Join-Path $env:ANDROID_SDK_HOME '.android\avd') }
    return (Join-Path $env:USERPROFILE '.android\avd')
}

function Get-Adb {
    return (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
}

function Find-EmulatorSerial([string]$AvdName) {
    $adb = Get-Adb
    foreach ($line in (& $adb devices | Select-Object -Skip 1)) {
        if ($line -match '^(emulator-\d+)\s+device') {
            $serial = $Matches[1]
            $name = & $adb -s $serial emu avd name 2>$null | Select-Object -First 1
            if ($name -and $name.Trim() -eq $AvdName) { return $serial }
        }
    }
    return $null
}

# Kestrel is tested only on kestrel-api37 and the Galaxy A71 (SM-A715F). The Galaxy S23+ (SM-S916*) is the
# owner's daily phone and is accepted only with -AllowDailyPhone (design spec, D20). Every other device is refused.
function Assert-TestDevice([string]$Serial, [switch]$AllowDailyPhone) {
    $adb = Get-Adb
    $state = & $adb -s $Serial get-state 2>$null
    if ($state -ne 'device') { throw "Device $Serial is not connected." }
    $model = "$(& $adb -s $Serial shell getprop ro.product.model)".Trim()
    # Emulators report their AVD name here, however adb reaches them.
    $avd = "$(& $adb -s $Serial shell getprop ro.boot.qemu.avd_name)".Trim()
    if ($avd -eq 'kestrel-api37' -or $model -like 'SM-A715F*') { return }
    if ($model -like 'SM-S916*' -and $AllowDailyPhone) { return }
    throw "Refusing to use $Serial (model '$model', AVD '$avd'). Kestrel is tested only on kestrel-api37 and the Galaxy A71."
}
```

`scripts/gradle.ps1`:

```powershell
# Runs the Gradle wrapper with Android Studio's JDK and the shared Android SDK.
# Example: scripts\gradle.ps1 check kestrelAudit
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
& "$PSScriptRoot\..\gradlew.bat" --console=plain @args
exit $LASTEXITCODE
```

- [ ] **Step 4: Bootstrap the Gradle 9.9.1 wrapper**

There is no global Gradle, so borrow the wrapper of another project on this machine, then let Gradle 9.9.1 regenerate its own wrapper files. The minimal `settings.gradle.kts` keeps the `wrapper` task from configuring a build that does not exist yet.

```powershell
New-Item -ItemType Directory -Force gradle\wrapper | Out-Null
Copy-Item H:\Code\Auralis\gradlew, H:\Code\Auralis\gradlew.bat .
Copy-Item H:\Code\Auralis\gradle\wrapper\gradle-wrapper.jar gradle\wrapper\
Set-Content gradle\wrapper\gradle-wrapper.properties "distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.1-bin.zip`ndistributionSha256Sum=d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228"
Set-Content settings.gradle.kts 'rootProject.name = "kestrel"'
scripts\gradle.ps1 wrapper --gradle-version 9.9.1 --distribution-type bin --gradle-distribution-sha256-sum d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228
(Get-FileHash gradle\wrapper\gradle-wrapper.jar -Algorithm SHA256).Hash.ToLower()
scripts\gradle.ps1 --version
```

Expected: `BUILD SUCCESSFUL` from the `wrapper` task, the jar hash `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8` (the official Gradle 9.9.1 wrapper), and `Gradle 9.9.1` with `Kotlin: 2.4.21` in the version output. `gradle/wrapper/gradle-wrapper.properties` now contains the 9.9.1 `distributionUrl` and `distributionSha256Sum`.

- [ ] **Step 5: Create the root build files**

`settings.gradle.kts` (replaces the minimal file from Step 4):

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kestrel"

include(":core:safety")
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
kotlin.code.style=official
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
```

`gradle/libs.versions.toml` (the whole catalog for this plan):

```toml
[versions]
agp = "9.5.1"
kotlin = "2.4.21"
ksp = "2.3.13"
room = "2.8.5"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
coreKtx = "1.19.1"
startup = "1.2.0"
coroutines = "1.11.0"
serialization = "1.13.0"
okhttp = "5.5.1"
junit = "4.13.2"
androidxTestExtJunit = "1.3.0"
androidxTestRunner = "1.7.0"
androidxTestRules = "1.7.0"
asm = "9.11.1"

[libraries]
android-gradleApi = { module = "com.android.tools.build:gradle-api", version.ref = "agp" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
asm = { module = "org.ow2.asm:asm", version.ref = "asm" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-startup-runtime = { module = "androidx.startup:startup-runtime", version.ref = "startup" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "okhttp" }
junit = { module = "junit:junit", version.ref = "junit" }
kotlin-test-junit = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidxTestRules" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

- [ ] **Step 6: Create the convention-plugin build**

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
```

`build-logic/convention/build.gradle.kts` (Tasks 2, 4 and 8 register more plugins here):

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradleApi)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.asm)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "kestrel.jvm.library"
            implementationClass = "dev.kestrel.build.JvmLibraryConventionPlugin"
        }
    }
}
```

`build-logic/convention/src/main/kotlin/dev/kestrel/build/JvmLibraryConventionPlugin.kt` (Task 4 adds the audit):

```kotlin
package dev.kestrel.build

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** For the plain Kotlin modules under `core/`. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
    }
}
```

Java and Kotlin target 17 everywhere, so that plain Kotlin modules and Android modules agree on the bytecode level.

- [ ] **Step 7: Write the failing test**

`core/safety/build.gradle.kts`:

```kotlin
plugins {
    id("kestrel.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

`core/safety/src/test/kotlin/dev/kestrel/safety/SafetySettingsTest.kt`:

```kotlin
package dev.kestrel.safety

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SafetySettingsTest {
    @Test
    fun settingsSavedWithoutTestModeKeepTestModeOn() {
        assertEquals(SafetySettings(testMode = true), Json.decodeFromString(SafetySettings.serializer(), "{}"))
    }

    @Test
    fun testModeOffSurvivesSaving() {
        val json = Json.encodeToString(SafetySettings.serializer(), SafetySettings(testMode = false))

        assertEquals(SafetySettings(testMode = false), Json.decodeFromString(SafetySettings.serializer(), json))
    }
}
```

- [ ] **Step 8: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:safety:test`

Expected: `BUILD FAILED`, with `Unresolved reference 'SafetySettings'` from `compileTestKotlin`.

- [ ] **Step 9: Write the implementation**

`core/safety/src/main/kotlin/dev/kestrel/safety/SafetySettings.kt`:

```kotlin
package dev.kestrel.safety

import kotlinx.serialization.Serializable

/** Safety settings. Test mode is on until the owner turns it off for daily use (design spec, D25 and section 12.7). */
@Serializable
data class SafetySettings(val testMode: Boolean = true)
```

- [ ] **Step 10: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :core:safety:test`

Expected: `BUILD SUCCESSFUL`. `core/safety/build/test-results/test/TEST-dev.kestrel.safety.SafetySettingsTest.xml` reports `tests="2" failures="0" errors="0"`.

- [ ] **Step 11: Commit**

Git on Windows does not record the executable bit by itself, and CI needs it for `gradlew`.

```powershell
git add -A
git update-index --chmod=+x gradlew
git commit -m "build: add Gradle build, script helpers and :core:safety" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Android app skeleton

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/KestrelSdk.kt`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidDefaults.kt`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidApplicationConventionPlugin.kt`
- Modify: `build-logic/convention/build.gradle.kts` (register `kestrel.android.application`)
- Modify: `settings.gradle.kts` (include `:app`)
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi/ic_launcher.xml`

**Interfaces:**
- Consumes: the version catalog and `scripts\gradle.ps1` from Task 1.
- Produces: the plugin ID `kestrel.android.application`, and `internal fun Project.applyKestrelApplicationDefaults()`, which plan 1B reuses for `:testapp`.
- Produces: `internal fun CommonExtension.applyKestrelAndroidDefaults()`, the settings that every Android module shares. Task 8 reuses it for library modules.
- Produces: the app `dev.kestrel` with the launcher activity `dev.kestrel.app.MainActivity`.

- [ ] **Step 1: Add the SDK levels and the application convention**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/KestrelSdk.kt`:

```kotlin
package dev.kestrel.build

/** SDK levels from the design spec (section 6). */
internal object KestrelSdk {
    const val COMPILE = 37
    const val TARGET = 37
    const val MIN = 33
}
```

`build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidDefaults.kt`:

```kotlin
package dev.kestrel.build

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

/** Settings that every Kestrel Android module shares: SDK levels (design spec, section 6), Java 17 and packaging. */
internal fun CommonExtension.applyKestrelAndroidDefaults() {
    compileSdk { version = release(KestrelSdk.COMPILE) }
    defaultConfig.apply {
        minSdk { version = release(KestrelSdk.MIN) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    // Kestrel has no native code of its own. Libraries from dependencies arrive stripped,
    // and stripping them again would need an NDK on every machine that builds Kestrel.
    packaging.jniLibs.keepDebugSymbols += "**/*.so"
}
```

`compileSdk { version = release(37) }` is the new SDK syntax of the Android Gradle plugin 9.5. It resolves the installed `platforms/android-37.0` package.

`build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidApplicationConventionPlugin.kt` (Task 4 adds the audit):

```kotlin
package dev.kestrel.build

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** For `:app`. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.applyKestrelApplicationDefaults()
    }
}

/** Shared by `:app` and, from plan 1B, `:testapp`. */
internal fun Project.applyKestrelApplicationDefaults() {
    pluginManager.apply("com.android.application")
    extensions.configure<ApplicationExtension> {
        applyKestrelAndroidDefaults()
        defaultConfig {
            targetSdk { version = release(KestrelSdk.TARGET) }
        }
    }
}
```

In `build-logic/convention/build.gradle.kts`, replace the `gradlePlugin` block with:

```kotlin
gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "kestrel.android.application"
            implementationClass = "dev.kestrel.build.AndroidApplicationConventionPlugin"
        }
        register("jvmLibrary") {
            id = "kestrel.jvm.library"
            implementationClass = "dev.kestrel.build.JvmLibraryConventionPlugin"
        }
    }
}
```

In `settings.gradle.kts`, replace `include(":core:safety")` with:

```kotlin
include(":app")
include(":core:safety")
```

- [ ] **Step 2: Create the app module**

`app/build.gradle.kts` (Task 13 adds the privacy core):

```kotlin
plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kestrel.app"
    defaultConfig {
        applicationId = "dev.kestrel"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
}
```

`app` depends on `androidx.startup` directly because its manifest refers to `androidx.startup.InitializationProvider`. Without the direct dependency, lint fails with `MissingClass`.

`app/src/main/AndroidManifest.xml` (Task 13 adds `android:name`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="Kestrel"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

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

`app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Kestrel keeps its data on the phone: nothing goes into cloud backups or device-to-device transfers. -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```

`app/src/main/res/drawable/ic_launcher_background.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#9C4A1A"
        android:pathData="M0,0h108v108h-108z" />
</vector>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- A plain letter K, drawn inside the 66 dp safe zone of an adaptive icon. -->
    <path
        android:pathData="M42,34 V74 M68,34 L44,56 M53,48 L69,74"
        android:strokeColor="#FFFFFF"
        android:strokeLineCap="square"
        android:strokeWidth="8" />
</vector>
```

`app/src/main/res/mipmap-anydpi/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` (a placeholder until plan 1C builds the settings app):

```kotlin
package dev.kestrel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text("Kestrel")
            }
        }
    }
}
```

- [ ] **Step 3: Build the app and run lint**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:lintDebug`

Expected: `BUILD SUCCESSFUL`, and `app/build/reports/lint-results-debug.txt` says `No issues found.`

- [ ] **Step 4: Check the merged manifest**

```powershell
$manifest = 'app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml'
Select-String -Path $manifest -Pattern 'allowBackup|dataExtractionRules|EmojiCompatInitializer'
```

Expected: one line with `android:allowBackup="false"`, one with `android:dataExtractionRules="@xml/data_extraction_rules"`, and no line with `androidx.emoji2.text.EmojiCompatInitializer`.

- [ ] **Step 5: Commit**

```powershell
git add -A
git commit -m "feat(app): add Android app skeleton without backups or EmojiCompat" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Test emulator and device scripts

**Files:**
- Create: `scripts/create-avd.ps1`, `scripts/start-emulator.ps1`, `scripts/connected-test.ps1`, `scripts/install.ps1`

**Interfaces:**
- Consumes: `Use-KestrelToolchain`, `Get-AvdHome`, `Get-Adb`, `Find-EmulatorSerial` and `Assert-TestDevice` from `scripts/lib/Kestrel.ps1` (Task 1).
- Produces: the AVD `kestrel-api37`; `scripts\start-emulator.ps1`, which prints the emulator's serial; `scripts\connected-test.ps1 -Serial <serial> <gradle tasks>`; `scripts\install.ps1 -Serial <serial> [-AllowDailyPhone]`.

The owner approved updating the shared emulator package from 35.3.11 to 37.2.13 on 12 November 2026, because the Android 17 image `system-images;android-37.0;google_apis_playstore;x86_64` needs emulator 37.2.11 or newer. `zoran` keeps its data. The newer emulator may discard `zoran`'s quick-boot snapshot, which costs one cold boot. `platform-tools` stays at 35.0.2.

- [ ] **Step 1: Create the scripts**

`scripts/create-avd.ps1`:

```powershell
# Installs the Android 17 system image and creates the kestrel-api37 emulator (design spec, section 6).
# The image needs emulator 37.2.11 or newer. The owner approved one update of the shared emulator package
# (35.3.11 to 37.2.13) on 12 November 2026, so this script installs only what is missing or too old and
# never upgrades a package that already works. Other AVDs, such as zoran, are never changed.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

$name = 'kestrel-api37'
$image = 'system-images;android-37.0;google_apis_playstore;x86_64'
$tools = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin'

$missing = @()
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'system-images\android-37.0\google_apis_playstore\x86_64'))) { $missing += $image }
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-37.0'))) { $missing += 'platforms;android-37.0' }
$emulatorProperties = Join-Path $env:ANDROID_HOME 'emulator\source.properties'
$emulatorRevision = if (Test-Path $emulatorProperties) { Select-String -Path $emulatorProperties -Pattern '^Pkg\.Revision=(.+)$' }
if (-not $emulatorRevision -or [version]$emulatorRevision.Matches[0].Groups[1].Value -lt [version]'37.2.11') { $missing += 'emulator' }
if ($missing) {
    & "$tools\sdkmanager.bat" --install @missing
    if ($LASTEXITCODE -ne 0) { throw 'sdkmanager failed' }
} else {
    Write-Host 'The system image, the platform and the emulator are already installed.'
}

$avdHome = Get-AvdHome
$avdDir = Join-Path $avdHome "$name.avd"
if (Test-Path $avdDir) {
    Write-Host "$name already exists in $avdHome"
} else {
    'no' | & "$tools\avdmanager.bat" create avd --name $name --package $image --device pixel_8 --sdcard 512M
    if ($LASTEXITCODE -ne 0) { throw 'avdmanager failed' }
    # More memory and storage than the defaults, for the test apps and the speech models of later sub-projects.
    $config = Join-Path $avdDir 'config.ini'
    $settings = [ordered]@{ 'hw.ramSize' = '4096'; 'disk.dataPartition.size' = '16G'; 'hw.keyboard' = 'yes' }
    $lines = Get-Content $config | Where-Object { $key = ($_ -split '=', 2)[0].Trim(); -not $settings.Contains($key) }
    $lines += $settings.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    Set-Content -Path $config -Value $lines -Encoding ascii
}

$avds = & (Join-Path $env:ANDROID_HOME 'emulator\emulator.exe') -list-avds
Write-Host "AVDs: $($avds -join ', ')"
if ($avds -notcontains $name) { throw "$name is missing from the AVD list" }
```

`scripts/start-emulator.ps1`:

```powershell
# Starts kestrel-api37 if it is not running, waits until Android has booted and prints its adb serial.
# The emulator keeps running in its own window after this script ends.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

$name = 'kestrel-api37'
$adb = Get-Adb
$deadline = (Get-Date).AddMinutes(5)
$serial = Find-EmulatorSerial $name
if (-not $serial) {
    # While the emulator boots, adb lists it as offline. Look for its process before starting a second copy.
    $running = Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" |
        Where-Object { $_.CommandLine -match "-avd\s+$([regex]::Escape($name))(\s|$)" }
    $started = $null
    if (-not $running) {
        $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
        $started = Start-Process -FilePath $emulator -ArgumentList '-avd', $name, '-no-boot-anim' -WindowStyle Minimized -PassThru
        $null = $started.Handle  # Keeps the exit code readable after the process ends.
    }
    do {
        Start-Sleep -Seconds 5
        if ($started -and $started.HasExited -and $started.ExitCode -ne 0) {
            throw "The emulator exited with code $($started.ExitCode) before $name appeared in adb"
        }
        $serial = Find-EmulatorSerial $name
    } until ($serial -or (Get-Date) -gt $deadline)
    if (-not $serial) { throw "$name did not appear in adb within 5 minutes" }
}
do {
    Start-Sleep -Seconds 3
    $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
} until ("$booted".Trim() -eq '1' -or (Get-Date) -gt $deadline)
if ("$booted".Trim() -ne '1') { throw "$name ($serial) did not finish booting within 5 minutes" }
Write-Output $serial
```

`scripts/connected-test.ps1`:

```powershell
# Runs Gradle's connected (on-device) tests on one test device only.
# Example: scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Tasks
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
# The Android Gradle plugin runs connected tests only on the devices listed in ANDROID_SERIAL.
$env:ANDROID_SERIAL = $Serial
& "$PSScriptRoot\..\gradlew.bat" --console=plain @Tasks
exit $LASTEXITCODE
```

`scripts/install.ps1`:

```powershell
# Builds the debug APK and installs it with adb install -r (design spec, sections 13.6 and 15).
# Installing with adb avoids Android's restricted-settings block for accessibility services.
# Example: scripts\install.ps1 -Serial emulator-5554
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Only for the owner's decision to start daily use on the Galaxy S23+.
    [switch]$AllowDailyPhone
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial -AllowDailyPhone:$AllowDailyPhone
& "$PSScriptRoot\..\gradlew.bat" --console=plain ':app:assembleDebug'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Get-Adb) -s $Serial install -r (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk')
exit $LASTEXITCODE
```

- [ ] **Step 2: Record the state before the update**

```powershell
& D:\AndroidVMs\SDK\emulator\emulator.exe -list-avds
Select-String -Path D:\AndroidVMs\SDK\emulator\source.properties -Pattern 'Pkg.Revision'
```

Expected: `zoran`, and `Pkg.Revision=35.3.11`.

- [ ] **Step 3: Create the emulator**

Run: `scripts\create-avd.ps1`

This downloads about 1.5 GB. Expected: the last line is `AVDs: kestrel-api37, zoran` (in either order), and no error. Two kinds of tool noise are known and harmless: `sdkmanager` warns that it is deprecated, and `avdmanager` prints six lines that start with `Error: Could not load devices from …\devices.xml` and still exits with code 0. A later run of the script installs nothing, because the packages are already there, and says `kestrel-api37 already exists`.

- [ ] **Step 4: Check the emulator update and `zoran`**

```powershell
Select-String -Path D:\AndroidVMs\SDK\emulator\source.properties -Pattern 'Pkg.Revision'
. .\scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
```

Expected: `Pkg.Revision=37.2.13` or newer, and both `kestrel-api37` and `zoran` in the list. If `zoran` is missing, stop and tell the owner; do not change or recreate it.

- [ ] **Step 5: Start the emulator and check its Android version**

```powershell
$serial = scripts\start-emulator.ps1
$serial
& D:\AndroidVMs\SDK\platform-tools\adb.exe -s $serial shell getprop ro.build.version.sdk
```

Expected: a serial such as `emulator-5554`, then `37`. The first boot can take a few minutes.

- [ ] **Step 6: Install and launch the app**

```powershell
$serial = scripts\start-emulator.ps1
scripts\install.ps1 -Serial $serial
& D:\AndroidVMs\SDK\platform-tools\adb.exe -s $serial shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity
```

Expected: `Success` from `adb install`, then `Status: ok` from `am start`. The emulator shows the word "Kestrel".

- [ ] **Step 7: Check that the guard refuses other devices**

Run: `scripts\install.ps1 -Serial emulator-9999`

Expected: an error that says `Device emulator-9999 is not connected.`, and nothing is built or installed.

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "build: add scripts for the kestrel-api37 emulator and device tests" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Privacy audit, part 1: dependency rules

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditRules.kt`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/CheckDependenciesTask.kt`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt`
- Test: `build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/AuditRulesTest.kt`
- Modify: `build-logic/convention/build.gradle.kts` (register `kestrel.audit`)
- Modify: `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidApplicationConventionPlugin.kt`, `JvmLibraryConventionPlugin.kt` (apply the audit)
- Create: `audit/rules.txt`

**Interfaces:**
- Consumes: `applyKestrelApplicationDefaults()` and `kestrel.jvm.library` from Tasks 1 and 2.
- Produces: `AuditRules.parse(text: String): AuditRules`, with `checkDependencies(dependencies: Collection<Coordinates>): List<String>`, `isNetworkClassExcepted(internalClassName: String): Boolean` and `isNativeSymbolExcepted(libraryName: String, symbol: String): Boolean`.
- Produces: `data class Coordinates(group: String, name: String, version: String)` and `AuditRules.GPL3_COMPATIBLE_LICENSES`.
- Produces: the plugin ID `kestrel.audit` with the task `kestrelAudit` in every module that uses a Kestrel convention plugin, and the report `app/build/reports/kestrel-audit/dependencies-release.txt`.
- Produces: the rule file format of `audit/rules.txt`: `deny <group:name> <reason>`, `pin <group:name> <version glob>`, `allow <group:name> <SPDX licence>`, `allow-network-class <internal class prefix> <justification>` and `allow-native-symbol <library.so> <symbol> <justification>`.

The audit checks the release build's runtime dependencies, because those are what ships. The convention plugins apply it, so that a new module cannot skip it. Plan 1B adds a separate convention for `:testapp`, which never ships and is not audited.

- [ ] **Step 1: Write the failing test**

`build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/AuditRulesTest.kt`:

```kotlin
package dev.kestrel.build.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRulesTest {
    private val rules = AuditRules.parse(
        """
        # A comment, followed by a blank line.

        deny com.google.firebase:* Firebase sends analytics to Google
        pin com.microsoft.onnxruntime:* 1.28.*
        allow androidx.*:* Apache-2.0
        allow com.microsoft.onnxruntime:* MIT
        allow org.example:gpl2 GPL-2.0-only
        allow-network-class dev/kestrel/tools/ Runs on the PC only
        allow-native-symbol libexample.so connect Connects to a local socket only
        """.trimIndent(),
    )

    @Test
    fun allowedDependencyPasses() {
        assertEquals(emptyList<String>(), rules.checkDependencies(listOf(Coordinates("androidx.core", "core-ktx", "1.19.1"))))
    }

    @Test
    fun deniedDependencyFailsEvenWithoutAnAllowRule() {
        val problems = rules.checkDependencies(listOf(Coordinates("com.google.firebase", "firebase-analytics", "23.0.0")))
        assertEquals(listOf("com.google.firebase:firebase-analytics:23.0.0 is denied: Firebase sends analytics to Google"), problems)
    }

    @Test
    fun dependencyWithoutAllowRuleFails() {
        val problems = rules.checkDependencies(listOf(Coordinates("org.unknown", "lib", "1.0")))
        assertEquals(
            listOf("org.unknown:lib:1.0 has no allow rule; review its licence and add a rule to audit/rules.txt"),
            problems,
        )
    }

    @Test
    fun incompatibleLicenceFails() {
        val problems = rules.checkDependencies(listOf(Coordinates("org.example", "gpl2", "1.0")))
        assertEquals(listOf("org.example:gpl2:1.0 uses GPL-2.0-only, which is not compatible with GPL-3.0"), problems)
    }

    @Test
    fun pinnedVersionPassesAndOtherVersionFails() {
        assertEquals(
            emptyList<String>(),
            rules.checkDependencies(listOf(Coordinates("com.microsoft.onnxruntime", "onnxruntime-android", "1.28.1"))),
        )
        assertEquals(
            listOf("com.microsoft.onnxruntime:onnxruntime-android:1.31.0 must have a version that matches 1.28.*"),
            rules.checkDependencies(listOf(Coordinates("com.microsoft.onnxruntime", "onnxruntime-android", "1.31.0"))),
        )
    }

    @Test
    fun exceptionsNeedAnExactMatch() {
        assertTrue(rules.isNetworkClassExcepted("dev/kestrel/tools/Fetcher"))
        assertFalse(rules.isNetworkClassExcepted("dev/kestrel/app/Fetcher"))
        assertTrue(rules.isNativeSymbolExcepted("libexample.so", "connect"))
        assertFalse(rules.isNativeSymbolExcepted("libexample.so", "socket"))
        assertFalse(rules.isNativeSymbolExcepted("libother.so", "connect"))
    }

    @Test
    fun malformedLineReportsItsLineNumber() {
        val error = runCatching { AuditRules.parse("allow androidx.*:* Apache-2.0\nallow-network-class dev/kestrel/") }
            .exceptionOrNull()
        assertEquals(
            "audit/rules.txt line 2: expected 'allow-network-class <class prefix> <justification>'",
            error?.message,
        )
    }

    @Test
    fun unknownRuleIsRejected() {
        val error = runCatching { AuditRules.parse("permit androidx.*:* Apache-2.0") }.exceptionOrNull()
        assertEquals("audit/rules.txt line 1: unknown rule 'permit'", error?.message)
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :build-logic:convention:test`

Expected: `BUILD FAILED`, with `Unresolved reference 'AuditRules'` from `:build-logic:convention:compileTestKotlin`.

- [ ] **Step 3: Write the rule parser and checker**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditRules.kt`:

```kotlin
package dev.kestrel.build.audit

/** One resolved external dependency. */
data class Coordinates(val group: String, val name: String, val version: String) {
    override fun toString(): String = "$group:$name:$version"
}

/** A `group:name` pattern in which `*` matches any run of characters. */
class CoordinatePattern(val text: String) {
    private val group: Regex
    private val name: Regex

    init {
        val parts = text.split(':')
        require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "Expected group:name but was '$text'" }
        group = globToRegex(parts[0])
        name = globToRegex(parts[1])
    }

    fun matches(coordinates: Coordinates): Boolean =
        group.matches(coordinates.group) && name.matches(coordinates.name)
}

internal fun globToRegex(glob: String): Regex =
    Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })

data class AllowRule(val pattern: CoordinatePattern, val license: String)
data class DenyRule(val pattern: CoordinatePattern, val reason: String)
data class PinRule(val pattern: CoordinatePattern, val versionGlob: String)
data class NetworkClassException(val classPrefix: String, val justification: String)
data class NativeSymbolException(val libraryName: String, val symbol: String, val justification: String)

/** The parsed contents of `audit/rules.txt`. */
class AuditRules(
    val allows: List<AllowRule>,
    val denies: List<DenyRule>,
    val pins: List<PinRule>,
    val networkClassExceptions: List<NetworkClassException>,
    val nativeSymbolExceptions: List<NativeSymbolException>,
) {
    /** Returns one message per problem. An empty list means that every dependency passed. */
    fun checkDependencies(dependencies: Collection<Coordinates>): List<String> {
        val problems = mutableListOf<String>()
        for (dependency in dependencies.sortedBy { it.toString() }) {
            val deny = denies.firstOrNull { it.pattern.matches(dependency) }
            if (deny != null) {
                problems += "$dependency is denied: ${deny.reason}"
                continue
            }
            val pin = pins.firstOrNull { it.pattern.matches(dependency) }
            if (pin != null && !globToRegex(pin.versionGlob).matches(dependency.version)) {
                problems += "$dependency must have a version that matches ${pin.versionGlob}"
            }
            val allow = allows.firstOrNull { it.pattern.matches(dependency) }
            when {
                allow == null ->
                    problems += "$dependency has no allow rule; review its licence and add a rule to audit/rules.txt"
                allow.license !in GPL3_COMPATIBLE_LICENSES ->
                    problems += "$dependency uses ${allow.license}, which is not compatible with GPL-3.0"
            }
        }
        return problems
    }

    fun isNetworkClassExcepted(internalClassName: String): Boolean =
        networkClassExceptions.any { internalClassName.startsWith(it.classPrefix) }

    fun isNativeSymbolExcepted(libraryName: String, symbol: String): Boolean =
        nativeSymbolExceptions.any { it.libraryName == libraryName && it.symbol == symbol }

    companion object {
        val GPL3_COMPATIBLE_LICENSES = setOf(
            "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib", "CC0-1.0",
            "Unlicense", "MPL-2.0", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
            "GPL-3.0-only", "GPL-3.0-or-later",
        )

        fun parse(text: String): AuditRules {
            val allows = mutableListOf<AllowRule>()
            val denies = mutableListOf<DenyRule>()
            val pins = mutableListOf<PinRule>()
            val networkClasses = mutableListOf<NetworkClassException>()
            val nativeSymbols = mutableListOf<NativeSymbolException>()
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                fun rest(from: Int) = parts.drop(from).joinToString(" ")
                fun fail(message: String): Nothing =
                    throw IllegalArgumentException("audit/rules.txt line ${index + 1}: $message")
                when (parts[0]) {
                    "allow" -> {
                        if (parts.size != 3) fail("expected 'allow <group:name> <SPDX licence>'")
                        allows += AllowRule(CoordinatePattern(parts[1]), parts[2])
                    }
                    "deny" -> {
                        if (parts.size < 3) fail("expected 'deny <group:name> <reason>'")
                        denies += DenyRule(CoordinatePattern(parts[1]), rest(2))
                    }
                    "pin" -> {
                        if (parts.size != 3) fail("expected 'pin <group:name> <version glob>'")
                        pins += PinRule(CoordinatePattern(parts[1]), parts[2])
                    }
                    "allow-network-class" -> {
                        if (parts.size < 3) fail("expected 'allow-network-class <class prefix> <justification>'")
                        networkClasses += NetworkClassException(parts[1], rest(2))
                    }
                    "allow-native-symbol" -> {
                        if (parts.size < 4) fail("expected 'allow-native-symbol <library.so> <symbol> <justification>'")
                        nativeSymbols += NativeSymbolException(parts[1], parts[2], rest(3))
                    }
                    else -> fail("unknown rule '${parts[0]}'")
                }
            }
            return AuditRules(allows, denies, pins, networkClasses, nativeSymbols)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :build-logic:convention:test`

Expected: `BUILD SUCCESSFUL`. `build-logic/convention/build/test-results/test/TEST-dev.kestrel.build.audit.AuditRulesTest.xml` reports `tests="8" failures="0" errors="0"`.

- [ ] **Step 5: Add the dependency check and the plugin**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/CheckDependenciesTask.kt`:

```kotlin
package dev.kestrel.build.audit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails when a runtime dependency is denied, has the wrong pinned version or is not reviewed (design spec, section 13.2). */
abstract class CheckDependenciesTask : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val dependencies = linkedSetOf<Coordinates>()
        val seen = mutableSetOf<String>()
        fun visit(component: ResolvedComponentResult) {
            if (!seen.add(component.id.displayName)) return
            val id = component.id
            if (id is ModuleComponentIdentifier) dependencies += Coordinates(id.group, id.module, id.version)
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { visit(it.selected) }
        }
        visit(rootComponent.get())
        val problems = AuditRules.parse(rulesFile.get().asFile.readText()).checkDependencies(dependencies)
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            dependencies.joinToString("\n", postfix = "\n") +
                if (problems.isEmpty()) "\nAll dependencies passed.\n" else "\nProblems:\n" + problems.joinToString("\n", postfix = "\n"),
        )
        if (problems.isNotEmpty()) {
            throw GradleException("Dependency audit failed (report: $reportFile):\n" + problems.joinToString("\n"))
        }
    }
}
```

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt` (Tasks 5 and 6 add the other checks):

```kotlin
package dev.kestrel.build.audit

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.TaskProvider

/**
 * Registers `kestrelAudit` and the checks it runs (design spec, section 13.2). Exceptions live in
 * `audit/rules.txt`, each with a written justification.
 */
class AuditPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rules = project.rootProject.layout.projectDirectory.file("audit/rules.txt")
        val audit = project.tasks.register("kestrelAudit") {
            group = "verification"
            description = "Runs Kestrel's privacy audit for this module."
        }
        project.pluginManager.withPlugin("com.android.application") {
            configureAndroid(project, rules, audit, isApplication = true)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureAndroid(project, rules, audit, isApplication = false)
        }
    }

    private fun configureAndroid(project: Project, rules: RegularFile, audit: TaskProvider<Task>, isApplication: Boolean) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }
            val reports = project.layout.buildDirectory.dir("reports/kestrel-audit")
            if (isApplication) {
                val dependencies = project.tasks.register("kestrelCheckDependencies$suffix", CheckDependenciesTask::class.java) {
                    rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                    rulesFile.set(rules)
                    report.set(reports.map { it.file("dependencies-${variant.name}.txt") })
                }
                audit.configure { dependsOn(dependencies) }
            }
        }
    }
}
```

In `build-logic/convention/build.gradle.kts`, replace the `gradlePlugin` block with:

```kotlin
gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "kestrel.android.application"
            implementationClass = "dev.kestrel.build.AndroidApplicationConventionPlugin"
        }
        register("jvmLibrary") {
            id = "kestrel.jvm.library"
            implementationClass = "dev.kestrel.build.JvmLibraryConventionPlugin"
        }
        register("audit") {
            id = "kestrel.audit"
            implementationClass = "dev.kestrel.build.audit.AuditPlugin"
        }
    }
}
```

- [ ] **Step 6: Apply the audit in the convention plugins**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidApplicationConventionPlugin.kt`:

```kotlin
package dev.kestrel.build

import com.android.build.api.dsl.ApplicationExtension
import dev.kestrel.build.audit.AuditPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** For `:app`. It also applies the privacy audit. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.applyKestrelApplicationDefaults()
        target.pluginManager.apply(AuditPlugin::class.java)
    }
}

/** Shared by `:app` and, from plan 1B, `:testapp`. Only `:app` is audited, because `:testapp` is never shipped. */
internal fun Project.applyKestrelApplicationDefaults() {
    pluginManager.apply("com.android.application")
    extensions.configure<ApplicationExtension> {
        applyKestrelAndroidDefaults()
        defaultConfig {
            targetSdk { version = release(KestrelSdk.TARGET) }
        }
    }
}
```

`build-logic/convention/src/main/kotlin/dev/kestrel/build/JvmLibraryConventionPlugin.kt`:

```kotlin
package dev.kestrel.build

import dev.kestrel.build.audit.AuditPlugin
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** For the plain Kotlin modules under `core/`. It also applies the privacy audit. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
            pluginManager.apply(AuditPlugin::class.java)
        }
    }
}
```

- [ ] **Step 7: Write the rules**

`audit/rules.txt` (Tasks 11 and 12 add OkHttp and the network exception):

```text
# Kestrel build-time audit rules (design spec, section 13.2).
# Rule forms:
#   deny <group:name> <reason>
#   pin <group:name> <version glob>
#   allow <group:name> <SPDX licence>
#   allow-network-class <internal class prefix> <justification>
#   allow-native-symbol <library.so> <symbol> <justification>
# "*" matches any run of characters. Deny rules win over allow rules.
# Every exception needs a written justification.

# Telemetry, advertising and Google services (design spec, sections 13.1 and 13.2)
deny com.google.firebase:* Firebase sends analytics and crash reports to Google
deny com.google.android.gms:* Google Play services
deny com.google.android.play:* Google Play libraries
deny com.google.mlkit:* ML Kit sends usage metrics to Google (B20)
deny com.google.android.datatransport:* Google telemetry transport
deny com.google.android.ump:* Google advertising consent SDK
deny com.google.android.libraries.ads*:* advertising
deny *:*analytics* analytics SDK
deny *:*crashlytics* crash reporting
deny io.sentry:* crash reporting
deny com.bugsnag:* crash reporting
deny ch.acra:* crash reporting
deny com.instabug*:* crash reporting
deny com.facebook.android:* Facebook SDK
deny com.appsflyer:* attribution tracking
deny com.adjust.sdk:* attribution tracking
deny com.amplitude:* analytics
deny com.mixpanel.android:* analytics
deny com.datadoghq:* monitoring
deny com.newrelic.agent.android:* monitoring
deny io.branch.sdk.android:* attribution tracking
deny com.onesignal:* push notifications and analytics

# ONNX Runtime 1.31 turns telemetry on by default on Android (B14).
pin com.microsoft.onnxruntime:* 1.28.*

# Reviewed licences
allow androidx.*:* Apache-2.0
allow org.jetbrains.kotlin:* Apache-2.0
allow org.jetbrains.kotlinx:* Apache-2.0
allow org.jetbrains:annotations Apache-2.0
allow com.google.guava:listenablefuture Apache-2.0
allow org.jspecify:jspecify Apache-2.0
```

- [ ] **Step 8: Run the audit**

Run: `scripts\gradle.ps1 kestrelAudit`

Expected: `BUILD SUCCESSFUL`. The last line of `app/build/reports/kestrel-audit/dependencies-release.txt` is `All dependencies passed.`, and the lines above it list about 90 `androidx`, `org.jetbrains` and `com.google.guava` libraries.

- [ ] **Step 9: Check that the audit fails when a rule is missing**

```powershell
Copy-Item audit\rules.txt $env:TEMP\kestrel-rules.txt
(Get-Content audit\rules.txt) -replace '^allow androidx', '# allow androidx' | Set-Content audit\rules.txt
scripts\gradle.ps1 :app:kestrelAudit
Copy-Item $env:TEMP\kestrel-rules.txt audit\rules.txt -Force
scripts\gradle.ps1 :app:kestrelAudit
```

Expected: the first audit ends with `BUILD FAILED` and lines such as `androidx.core:core:1.19.1 has no allow rule; review its licence and add a rule to audit/rules.txt`. The second ends with `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```powershell
git add -A
git commit -m "build: add privacy audit with dependency deny, pin and licence rules" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Privacy audit, part 2: network-API scan

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/NetworkApiScanner.kt`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ScanNetworkApisTask.kt`
- Modify: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt`
- Test: `build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/NetworkApiScannerTest.kt`
- Test fixtures: `build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/fixtures/NetworkFixtures.kt`, `build-logic/convention/src/test/java/dev/kestrel/build/audit/fixtures/JavaMethodReference.java`

**Interfaces:**
- Consumes: `AuditRules.isNetworkClassExcepted(internalClassName)` from Task 4.
- Produces: `NetworkApiScanner().scan(classBytes: ByteArray): List<NetworkFinding>`, `data class NetworkFinding(className: String, methodName: String, reference: String)`, and `NetworkApiRules.isNetworkType(internalName)` and `isNetworkCall(owner, name, descriptor)`.
- Produces: the tasks `kestrelScanNetworkApisRelease` (Android modules) and `kestrelScanNetworkApis` (plain Kotlin modules), with reports in `<module>/build/reports/kestrel-audit/network-apis*.txt`.

The scan reads compiled classes with ASM. It flags sockets, URL connections, `java.net.http`, `javax.net`, WebView, `DownloadManager`, `Geocoder`, Cronet and the construction or use of an `OkHttpClient`. Other OkHttp types such as `Request` stay allowed, because later sub-projects build requests for the gateway's shared client. It scans the project's own classes; dependencies are covered by the deny rules, by the native scan and, in sub-project 6, by the traffic check (spec section 13.3).

- [ ] **Step 1: Write the test fixtures and the failing test**

`build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/fixtures/NetworkFixtures.kt`:

```kotlin
package dev.kestrel.build.audit.fixtures

import java.net.Socket
import java.net.URL

// Compiled into the test classes, then read back as bytes by NetworkApiScannerTest.

class OpensUrl {
    fun open(url: URL) = url.openConnection()
}

class CreatesSocket {
    fun create() = Socket()
}

class MentionsSocketClass {
    fun type(): Class<*> = Socket::class.java
}

object SocketTools {
    fun close(socket: Socket) = socket.close()
}

class PassesSocket {
    fun finish(socket: Socket) = SocketTools.close(socket)
}

class Harmless {
    fun length(text: String) = text.length
}
```

`build-logic/convention/src/test/java/dev/kestrel/build/audit/fixtures/JavaMethodReference.java`:

```java
package dev.kestrel.build.audit.fixtures;

import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.Callable;

// javac turns this method reference into an invokedynamic instruction that points straight at URL.openStream.
public class JavaMethodReference {
    public Callable<InputStream> opener(URL url) {
        return url::openStream;
    }
}
```

`build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/NetworkApiScannerTest.kt`:

```kotlin
package dev.kestrel.build.audit

import dev.kestrel.build.audit.fixtures.CreatesSocket
import dev.kestrel.build.audit.fixtures.Harmless
import dev.kestrel.build.audit.fixtures.JavaMethodReference
import dev.kestrel.build.audit.fixtures.MentionsSocketClass
import dev.kestrel.build.audit.fixtures.OpensUrl
import dev.kestrel.build.audit.fixtures.PassesSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkApiScannerTest {
    private val scanner = NetworkApiScanner()

    private fun referencesIn(type: Class<*>): List<String> {
        val bytes = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        return scanner.scan(bytes).map { it.reference }
    }

    @Test
    fun findsUrlOpenConnection() {
        assertEquals(listOf("java/net/URL.openConnection"), referencesIn(OpensUrl::class.java))
    }

    @Test
    fun findsSocketConstruction() {
        assertTrue(referencesIn(CreatesSocket::class.java).contains("java/net/Socket"))
    }

    @Test
    fun findsClassLiteral() {
        assertEquals(listOf("java/net/Socket"), referencesIn(MentionsSocketClass::class.java))
    }

    @Test
    fun findsCallsWhoseSignatureMentionsANetworkType() {
        assertEquals(
            listOf("dev/kestrel/build/audit/fixtures/SocketTools.close"),
            referencesIn(PassesSocket::class.java),
        )
    }

    @Test
    fun findsJavaMethodReference() {
        assertEquals(listOf("java/net/URL.openStream"), referencesIn(JavaMethodReference::class.java))
    }

    @Test
    fun ignoresOrdinaryCode() {
        assertEquals(emptyList<String>(), referencesIn(Harmless::class.java))
    }

    @Test
    fun classifiesLibraryAndAndroidNetworkTypes() {
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/OkHttpClient"))
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/OkHttpClient\$Builder"))
        assertTrue(NetworkApiRules.isNetworkType("android/webkit/WebView"))
        assertTrue(NetworkApiRules.isNetworkType("android/location/Geocoder"))
        assertTrue(NetworkApiRules.isNetworkType("java/net/http/HttpClient"))
        assertFalse(NetworkApiRules.isNetworkType("okhttp3/Request"))
        assertFalse(NetworkApiRules.isNetworkType("okhttp3/Call\$Factory"))
        assertFalse(NetworkApiRules.isNetworkType("java/net/URI"))
        assertFalse(NetworkApiRules.isNetworkType("android/net/Uri"))
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :build-logic:convention:test`

Expected: `BUILD FAILED`, with `Unresolved reference 'NetworkApiScanner'`.

- [ ] **Step 3: Write the scanner**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/NetworkApiScanner.kt`:

```kotlin
package dev.kestrel.build.audit

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

data class NetworkFinding(val className: String, val methodName: String, val reference: String) {
    override fun toString(): String = "$className.$methodName uses $reference"
}

/** Which JVM and Android APIs count as network access (design spec, section 13.2). */
object NetworkApiRules {
    private val networkTypes = setOf(
        "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket", "java/net/MulticastSocket",
        "java/net/URLConnection", "java/net/HttpURLConnection", "java/net/JarURLConnection",
        "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel",
        "java/nio/channels/DatagramChannel", "java/nio/channels/AsynchronousSocketChannel",
        "android/webkit/WebView", "android/app/DownloadManager", "android/location/Geocoder",
        // Only the gateway may build an OkHttp client. Other code uses the gateway's Call.Factory.
        "okhttp3/OkHttpClient", "okhttp3/OkHttpClient\$Builder",
    )
    private val networkPackages = listOf("java/net/http/", "javax/net/", "android/net/http/", "org/chromium/net/")
    private val networkMethods = mapOf(
        "java/net/URL" to setOf("openConnection", "openStream", "getContent"),
        "java/net/InetAddress" to setOf("getByName", "getAllByName", "getLocalHost"),
        "android/net/Network" to setOf("openConnection", "bindSocket", "getSocketFactory", "getByName", "getAllByName"),
    )
    private val typeInDescriptor = Regex("L([^;]+);")

    fun isNetworkType(internalName: String): Boolean =
        internalName in networkTypes || networkPackages.any { internalName.startsWith(it) }

    fun isNetworkCall(owner: String, name: String, descriptor: String): Boolean =
        isNetworkType(owner) ||
            networkMethods[owner]?.contains(name) == true ||
            (owner.startsWith("kotlin/io/") && descriptor.contains("Ljava/net/URL;")) ||
            typeInDescriptor.findAll(descriptor).any { isNetworkType(it.groupValues[1]) }
}

/** Finds references to network APIs in compiled JVM classes. */
class NetworkApiScanner {
    fun scan(classBytes: ByteArray): List<NetworkFinding> {
        val findings = mutableListOf<NetworkFinding>()
        val reader = ClassReader(classBytes)
        val className = reader.className
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val methodName = name
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (NetworkApiRules.isNetworkCall(owner, name, descriptor)) {
                            findings += NetworkFinding(className, methodName, "$owner.$name")
                        }
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) {
                        if (NetworkApiRules.isNetworkType(type)) findings += NetworkFinding(className, methodName, type)
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        if (NetworkApiRules.isNetworkType(owner)) findings += NetworkFinding(className, methodName, "$owner.$name")
                    }

                    override fun visitLdcInsn(value: Any?) {
                        if (value is Type && value.sort == Type.OBJECT && NetworkApiRules.isNetworkType(value.internalName)) {
                            findings += NetworkFinding(className, methodName, value.internalName)
                        }
                    }

                    override fun visitInvokeDynamicInsn(
                        name: String,
                        descriptor: String,
                        bootstrapMethodHandle: Handle,
                        vararg bootstrapMethodArguments: Any?,
                    ) {
                        bootstrapMethodArguments.filterIsInstance<Handle>().forEach { handle ->
                            if (NetworkApiRules.isNetworkCall(handle.owner, handle.name, handle.desc)) {
                                findings += NetworkFinding(className, methodName, "${handle.owner}.${handle.name}")
                            }
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return findings.distinct()
    }
}
```

- [ ] **Step 4: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :build-logic:convention:test`

Expected: `BUILD SUCCESSFUL`. `TEST-dev.kestrel.build.audit.NetworkApiScannerTest.xml` reports `tests="7" failures="0" errors="0"`.

- [ ] **Step 5: Add the scan task and wire it into the plugin**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ScanNetworkApisTask.kt`:

```kotlin
package dev.kestrel.build.audit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/** Fails when compiled project code uses network APIs outside the exceptions in `audit/rules.txt` (design spec, section 13.2). */
abstract class ScanNetworkApisTask : DefaultTask() {
    /** Android modules: the classes that AGP compiled for the variant. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classJars: ListProperty<RegularFile>

    /** Plain Kotlin modules: the class folders of the main source set. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extraClassDirectories: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        classDirectories.convention(emptyList())
        classJars.convention(emptyList())
    }

    @TaskAction
    fun scan() {
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        val rules = AuditRules.parse(rulesFile.get().asFile.readText())
        val scanner = NetworkApiScanner()
        val findings = mutableListOf<NetworkFinding>()
        val directories = classDirectories.get().map { it.asFile } + extraClassDirectories.files
        directories.filter { it.isDirectory }.forEach { directory ->
            directory.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { findings += scanner.scan(it.readBytes()) }
        }
        classJars.get().map { it.asFile }.filter { it.isFile }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry -> findings += scanner.scan(zip.getInputStream(entry).use { it.readBytes() }) }
            }
        }
        val (excepted, violations) = findings.distinct().partition { rules.isNetworkClassExcepted(it.className) }
        reportFile.writeText(
            buildString {
                appendLine(if (violations.isEmpty()) "No network API use outside the exceptions." else "Violations:")
                violations.forEach { appendLine(it.toString()) }
                if (excepted.isNotEmpty()) {
                    appendLine()
                    appendLine("Allowed by audit/rules.txt:")
                    excepted.forEach { appendLine(it.toString()) }
                }
            },
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Network APIs are used outside the network gateway (report: $reportFile):\n" + violations.joinToString("\n"),
            )
        }
    }
}
```

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt` (Task 6 adds the native scan):

```kotlin
package dev.kestrel.build.audit

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider

/**
 * Registers `kestrelAudit` and the checks it runs (design spec, section 13.2). Exceptions live in
 * `audit/rules.txt`, each with a written justification.
 */
class AuditPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rules = project.rootProject.layout.projectDirectory.file("audit/rules.txt")
        val audit = project.tasks.register("kestrelAudit") {
            group = "verification"
            description = "Runs Kestrel's privacy audit for this module."
        }
        project.pluginManager.withPlugin("com.android.application") {
            configureAndroid(project, rules, audit, isApplication = true)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureAndroid(project, rules, audit, isApplication = false)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val classes = project.extensions.getByType(SourceSetContainer::class.java).getByName("main").output.classesDirs
            val scan = project.tasks.register("kestrelScanNetworkApis", ScanNetworkApisTask::class.java) {
                extraClassDirectories.from(classes)
                rulesFile.set(rules)
                report.set(project.layout.buildDirectory.file("reports/kestrel-audit/network-apis.txt"))
            }
            audit.configure { dependsOn(scan) }
        }
    }

    private fun configureAndroid(project: Project, rules: RegularFile, audit: TaskProvider<Task>, isApplication: Boolean) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }
            val reports = project.layout.buildDirectory.dir("reports/kestrel-audit")
            val scan = project.tasks.register("kestrelScanNetworkApis$suffix", ScanNetworkApisTask::class.java) {
                rulesFile.set(rules)
                report.set(reports.map { it.file("network-apis-${variant.name}.txt") })
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
                .use(scan)
                .toGet(ScopedArtifact.CLASSES, ScanNetworkApisTask::classJars, ScanNetworkApisTask::classDirectories)
            audit.configure { dependsOn(scan) }
            if (isApplication) {
                val dependencies = project.tasks.register("kestrelCheckDependencies$suffix", CheckDependenciesTask::class.java) {
                    rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                    rulesFile.set(rules)
                    report.set(reports.map { it.file("dependencies-${variant.name}.txt") })
                }
                audit.configure { dependsOn(dependencies) }
            }
        }
    }
}
```

- [ ] **Step 6: Run the audit**

Run: `scripts\gradle.ps1 kestrelAudit`

Expected: `BUILD SUCCESSFUL`. `app/build/reports/kestrel-audit/network-apis-release.txt` and `core/safety/build/reports/kestrel-audit/network-apis.txt` both start with `No network API use outside the exceptions.`

- [ ] **Step 7: Check that the scan catches network code**

```powershell
Set-Content core\safety\src\main\kotlin\dev\kestrel\safety\Leak.kt "package dev.kestrel.safety`n`nfun leak(): ByteArray = java.net.URL(`"https://example.com`").openStream().readBytes()`n"
scripts\gradle.ps1 :core:safety:kestrelAudit
Remove-Item core\safety\src\main\kotlin\dev\kestrel\safety\Leak.kt
scripts\gradle.ps1 :core:safety:kestrelAudit
```

Expected: the first audit ends with `BUILD FAILED` and the line `dev/kestrel/safety/LeakKt.leak uses java/net/URL.openStream`. The second ends with `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "build: scan compiled code for network APIs in the privacy audit" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Privacy audit, part 3: native-library scan

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ElfReader.kt`
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ScanNativeLibsTask.kt`
- Modify: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt`
- Create: `build-logic/convention/src/test/elf-src/net.c`, `quiet.c`, `build-fixtures.ps1`
- Create (generated, committed): `build-logic/convention/src/test/resources/elf/libnet-arm64.so`, `libnet-arm32.so`, `libquiet-arm64.so`
- Test: `build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/ElfReaderTest.kt`

**Interfaces:**
- Consumes: `AuditRules.isNativeSymbolExcepted(libraryName, symbol)` from Task 4.
- Produces: `ElfReader.importedSymbols(bytes: ByteArray): Set<String>`, which throws `IllegalArgumentException` for input that is not a little-endian ELF file, has no dynamic symbol table, or is truncated or malformed. Also produces `NativeLibraryRules.NETWORK_SYMBOLS`: the public lookup and resolver functions of the NDK's libc and libandroid, plus `socket` and `connect`.
- Produces: the task `kestrelScanNativeLibsRelease` in `:app`, with the report `app/build/reports/kestrel-audit/native-libs-release.txt`.

The scan opens the release APK and reads the imported symbols of every `lib/*/*.so` file. A library that imports `socket`, `connect`, `getaddrinfo` or another name-lookup function fails the build, unless `audit/rules.txt` has an `allow-native-symbol` exception for it. Sub-project 2 adds sherpa-onnx, the first large native library.

- [ ] **Step 1: Write the fixture sources and build the fixtures**

`build-logic/convention/src/test/elf-src/net.c`:

```c
/* Test fixture for ElfReaderTest: a library that imports networking functions. */
#include <netdb.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int kestrel_open(const char *host) {
    struct addrinfo hints;
    struct addrinfo *result = 0;
    memset(&hints, 0, sizeof hints);
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, "80", &hints, &result) != 0) return -1;
    int fd = socket(result->ai_family, result->ai_socktype, result->ai_protocol);
    if (fd >= 0 && connect(fd, result->ai_addr, result->ai_addrlen) != 0) {
        close(fd);
        fd = -1;
    }
    freeaddrinfo(result);
    return fd;
}
```

`build-logic/convention/src/test/elf-src/quiet.c`:

```c
/* Test fixture for ElfReaderTest: a library that imports no networking functions. */
#include <string.h>

size_t kestrel_length(const char *text) {
    return strlen(text);
}
```

`build-logic/convention/src/test/elf-src/build-fixtures.ps1`:

```powershell
# Rebuilds the ELF libraries that ElfReaderTest reads. Needs the Android NDK.
param([string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\AndroidVMs\SDK' }))
$ErrorActionPreference = 'Stop'
$ndk = Get-ChildItem (Join-Path $Sdk 'ndk') -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
if (-not $ndk) { throw "No NDK found under $Sdk\ndk" }
$clang = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$out = Join-Path $PSScriptRoot '..\resources\elf'
New-Item -ItemType Directory -Force $out | Out-Null
$builds = @(
    @{ Source = 'net.c'; Target = 'aarch64-linux-android33'; Output = 'libnet-arm64.so' },
    @{ Source = 'net.c'; Target = 'armv7a-linux-androideabi33'; Output = 'libnet-arm32.so' },
    @{ Source = 'quiet.c'; Target = 'aarch64-linux-android33'; Output = 'libquiet-arm64.so' }
)
foreach ($build in $builds) {
    & $clang "--target=$($build.Target)" -shared -fPIC -O2 -o (Join-Path $out $build.Output) (Join-Path $PSScriptRoot $build.Source)
    if ($LASTEXITCODE -ne 0) { throw "clang failed for $($build.Output)" }
}
Get-ChildItem $out | Select-Object Name, Length
```

Run: `build-logic\convention\src\test\elf-src\build-fixtures.ps1`

Expected: a table with `libnet-arm32.so`, `libnet-arm64.so` and `libquiet-arm64.so`, each a few kilobytes. The script uses the newest NDK under `D:\AndroidVMs\SDK\ndk` (27.1.12297006 today). CI never runs it; it uses the committed files.

- [ ] **Step 2: Write the failing test**

`build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/ElfReaderTest.kt`:

```kotlin
package dev.kestrel.build.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfReaderTest {
    private fun library(name: String): ByteArray =
        javaClass.getResourceAsStream("/elf/$name")!!.use { it.readBytes() }

    private fun rejectionOf(bytes: ByteArray): String? =
        assertThrows(IllegalArgumentException::class.java) { ElfReader.importedSymbols(bytes) }.message

    @Test
    fun readsImportsFrom64BitLibrary() {
        val imports = ElfReader.importedSymbols(library("libnet-arm64.so"))
        assertTrue(imports.containsAll(listOf("socket", "connect", "getaddrinfo")))
    }

    @Test
    fun readsImportsFrom32BitLibrary() {
        val imports = ElfReader.importedSymbols(library("libnet-arm32.so"))
        assertTrue(imports.containsAll(listOf("socket", "connect", "getaddrinfo")))
    }

    @Test
    fun doesNotReportExportedFunctions() {
        assertFalse(ElfReader.importedSymbols(library("libnet-arm64.so")).contains("kestrel_open"))
        assertFalse(ElfReader.importedSymbols(library("libnet-arm32.so")).contains("kestrel_open"))
    }

    @Test
    fun libraryWithoutNetworkingHasNoNetworkImports() {
        val imports = ElfReader.importedSymbols(library("libquiet-arm64.so"))
        assertTrue(imports.contains("strlen"))
        assertEquals(emptySet<String>(), imports.intersect(NativeLibraryRules.NETWORK_SYMBOLS))
    }

    @Test
    fun rejectsFilesThatAreNotElf() {
        assertEquals("Not an ELF file", rejectionOf(ByteArray(64)))
    }

    @Test
    fun rejectsUnknownClassesAndBigEndianFiles() {
        assertEquals("Unknown ELF class 3", rejectionOf(library("libquiet-arm64.so").also { it[4] = 3 }))
        assertEquals("Only little-endian ELF files are supported", rejectionOf(library("libquiet-arm64.so").also { it[5] = 2 }))
    }

    @Test
    fun rejectsLibrariesWithoutDynamicSymbolTable() {
        // Setting the section count to zero hides every section, as some packers do.
        val withoutSections = library("libquiet-arm64.so").also {
            it[0x3C] = 0
            it[0x3D] = 0
        }
        assertEquals("No dynamic symbol table", rejectionOf(withoutSections))
    }

    @Test
    fun rejectsTruncatedFiles() {
        assertEquals("Truncated or malformed ELF file", rejectionOf(library("libnet-arm64.so").copyOf(1024)))
    }
}
```

- [ ] **Step 3: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :build-logic:convention:test`

Expected: `BUILD FAILED`, with `Unresolved reference 'ElfReader'`.

- [ ] **Step 4: Write the ELF reader**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ElfReader.kt`:

```kotlin
package dev.kestrel.build.audit

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the dynamic symbols that a little-endian ELF shared library imports. */
object ElfReader {
    private const val SHT_DYNSYM = 11

    private data class Section(val type: Int, val offset: Long, val size: Long, val link: Int, val entrySize: Long)

    /** Throws [IllegalArgumentException] for input that is not a readable little-endian ELF library. */
    fun importedSymbols(bytes: ByteArray): Set<String> {
        require(
            bytes.size >= 64 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte(),
        ) { "Not an ELF file" }
        val is64 = when (bytes[4].toInt()) {
            1 -> false
            2 -> true
            else -> throw IllegalArgumentException("Unknown ELF class ${bytes[4]}")
        }
        require(bytes[5].toInt() == 1) { "Only little-endian ELF files are supported" }
        return try {
            readImports(bytes, is64)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Truncated or malformed ELF file", e)
        }
    }

    private fun readImports(bytes: ByteArray, is64: Boolean): Set<String> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sectionTable = if (is64) buffer.getLong(0x28) else buffer.getInt(0x20).toUInt().toLong()
        val sectionHeaderSize = buffer.getShort(if (is64) 0x3A else 0x2E).toInt() and 0xFFFF
        val sectionCount = buffer.getShort(if (is64) 0x3C else 0x30).toInt() and 0xFFFF
        val sections = (0 until sectionCount).map { index ->
            val base = (sectionTable + index.toLong() * sectionHeaderSize).toInt()
            if (is64) {
                Section(
                    type = buffer.getInt(base + 0x04),
                    offset = buffer.getLong(base + 0x18),
                    size = buffer.getLong(base + 0x20),
                    link = buffer.getInt(base + 0x28),
                    entrySize = buffer.getLong(base + 0x38),
                )
            } else {
                Section(
                    type = buffer.getInt(base + 0x04),
                    offset = buffer.getInt(base + 0x10).toUInt().toLong(),
                    size = buffer.getInt(base + 0x14).toUInt().toLong(),
                    link = buffer.getInt(base + 0x18),
                    entrySize = buffer.getInt(base + 0x24).toUInt().toLong(),
                )
            }
        }
        // Without a dynamic symbol table, the imports cannot be checked, so the library must not pass silently.
        val symbols = sections.firstOrNull { it.type == SHT_DYNSYM }
            ?: throw IllegalArgumentException("No dynamic symbol table")
        val strings = sections[symbols.link]
        val entrySize = if (symbols.entrySize > 0) symbols.entrySize else if (is64) 24L else 16L
        val imported = linkedSetOf<String>()
        for (index in 0 until (symbols.size / entrySize).toInt()) {
            val base = (symbols.offset + index * entrySize).toInt()
            val nameOffset = buffer.getInt(base)
            val sectionIndex = buffer.getShort(base + if (is64) 6 else 14).toInt() and 0xFFFF
            if (nameOffset != 0 && sectionIndex == 0) {
                imported += readString(bytes, (strings.offset + nameOffset).toInt())
            }
        }
        return imported
    }

    private fun readString(bytes: ByteArray, start: Int): String {
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, start, end - start, Charsets.US_ASCII)
    }
}

/**
 * Native functions that open network connections, resolve host names or addresses, or send DNS queries:
 * the public lookup and resolver functions of the NDK's libc and libandroid.
 */
object NativeLibraryRules {
    val NETWORK_SYMBOLS = setOf(
        "socket", "connect",
        "getaddrinfo", "getnameinfo", "gethostbyname", "gethostbyname_r", "gethostbyname2", "gethostbyname2_r",
        "gethostbyaddr", "gethostbyaddr_r", "res_query", "res_search",
        "__res_send", "__res_nsend", "__res_nquery", "__res_nsearch", "__res_querydomain", "__res_nquerydomain",
        "android_getaddrinfofornetwork", "android_res_nquery", "android_res_nsend",
    )
}
```

- [ ] **Step 5: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :build-logic:convention:test`

Expected: `BUILD SUCCESSFUL`. `TEST-dev.kestrel.build.audit.ElfReaderTest.xml` reports `tests="8" failures="0" errors="0"`.

- [ ] **Step 6: Add the scan task and wire it into the plugin**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ScanNativeLibsTask.kt`:

```kotlin
package dev.kestrel.build.audit

import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/** Fails when a native library in the APK imports networking functions (design spec, section 13.2). */
abstract class ScanNativeLibsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val artifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("No APK found in ${apkDirectory.get().asFile}")
        val rules = AuditRules.parse(rulesFile.get().asFile.readText())
        val scanned = mutableListOf<String>()
        val violations = mutableListOf<String>()
        artifacts.elements.forEach { element ->
            ZipFile(File(element.outputFile)).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .forEach { entry ->
                        scanned += entry.name
                        val library = entry.name.substringAfterLast('/')
                        val imports = try {
                            ElfReader.importedSymbols(zip.getInputStream(entry).use { it.readBytes() })
                        } catch (e: IllegalArgumentException) {
                            throw GradleException("Cannot read the imports of ${entry.name}: ${e.message}", e)
                        }
                        imports.filter { it in NativeLibraryRules.NETWORK_SYMBOLS }
                            .filterNot { rules.isNativeSymbolExcepted(library, it) }
                            .forEach { violations += "${entry.name} imports $it" }
                    }
            }
        }
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            "Scanned ${scanned.size} native libraries.\n" + scanned.joinToString("\n", postfix = "\n") +
                if (violations.isEmpty()) "No networking imports found.\n" else violations.joinToString("\n", postfix = "\n"),
        )
        if (violations.isNotEmpty()) {
            throw GradleException("Native libraries import networking functions (report: $reportFile):\n" + violations.joinToString("\n"))
        }
    }
}
```

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt` (final version):

```kotlin
package dev.kestrel.build.audit

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider

/**
 * Registers `kestrelAudit` and the checks it runs (design spec, section 13.2). Exceptions live in
 * `audit/rules.txt`, each with a written justification.
 */
class AuditPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rules = project.rootProject.layout.projectDirectory.file("audit/rules.txt")
        val audit = project.tasks.register("kestrelAudit") {
            group = "verification"
            description = "Runs Kestrel's privacy audit for this module."
        }
        project.pluginManager.withPlugin("com.android.application") {
            configureAndroid(project, rules, audit, isApplication = true)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureAndroid(project, rules, audit, isApplication = false)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val classes = project.extensions.getByType(SourceSetContainer::class.java).getByName("main").output.classesDirs
            val scan = project.tasks.register("kestrelScanNetworkApis", ScanNetworkApisTask::class.java) {
                extraClassDirectories.from(classes)
                rulesFile.set(rules)
                report.set(project.layout.buildDirectory.file("reports/kestrel-audit/network-apis.txt"))
            }
            audit.configure { dependsOn(scan) }
        }
    }

    private fun configureAndroid(project: Project, rules: RegularFile, audit: TaskProvider<Task>, isApplication: Boolean) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }
            val reports = project.layout.buildDirectory.dir("reports/kestrel-audit")
            val scan = project.tasks.register("kestrelScanNetworkApis$suffix", ScanNetworkApisTask::class.java) {
                rulesFile.set(rules)
                report.set(reports.map { it.file("network-apis-${variant.name}.txt") })
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
                .use(scan)
                .toGet(ScopedArtifact.CLASSES, ScanNetworkApisTask::classJars, ScanNetworkApisTask::classDirectories)
            audit.configure { dependsOn(scan) }
            if (isApplication) {
                val dependencies = project.tasks.register("kestrelCheckDependencies$suffix", CheckDependenciesTask::class.java) {
                    rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                    rulesFile.set(rules)
                    report.set(reports.map { it.file("dependencies-${variant.name}.txt") })
                }
                val native = project.tasks.register("kestrelScanNativeLibs$suffix", ScanNativeLibsTask::class.java) {
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                    rulesFile.set(rules)
                    report.set(reports.map { it.file("native-libs-${variant.name}.txt") })
                }
                audit.configure { dependsOn(dependencies, native) }
            }
        }
    }
}
```

- [ ] **Step 7: Run the audit**

Run: `scripts\gradle.ps1 kestrelAudit`

Expected: `BUILD SUCCESSFUL`. `app/build/reports/kestrel-audit/native-libs-release.txt` starts with `Scanned 4 native libraries.`, lists `lib/<abi>/libandroidx.graphics.path.so` for four ABIs, and ends with `No networking imports found.`

- [ ] **Step 8: Check that the scan catches a networking library**

```powershell
New-Item -ItemType Directory -Force app\src\main\jniLibs\arm64-v8a | Out-Null
Copy-Item build-logic\convention\src\test\resources\elf\libnet-arm64.so app\src\main\jniLibs\arm64-v8a\libnet.so
scripts\gradle.ps1 :app:kestrelAudit
Remove-Item -Recurse app\src\main\jniLibs
scripts\gradle.ps1 :app:kestrelAudit
```

Expected: the first audit ends with `BUILD FAILED` and the lines `lib/arm64-v8a/libnet.so imports getaddrinfo`, `lib/arm64-v8a/libnet.so imports socket` and `lib/arm64-v8a/libnet.so imports connect`. The second ends with `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add -A
git commit -m "build: scan bundled native libraries for networking imports" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: CI, README and the public repository

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: the Gradle tasks `:build-logic:convention:check`, `check` and `kestrelAudit` from Tasks 1 to 6.
- Produces: the public repository `github.com/ak800i/kestrel` with the remote `origin`, and a CI workflow named `CI` that runs on every push to `main` and on pull requests.

CI does not run emulator tests yet (spec section 17). The last step fails when the build changed a tracked file, for example a Room schema that was not committed.

- [ ] **Step 1: Create the workflow**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v6
      - name: Install Android SDK packages
        run: |
          yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" "build-tools;36.0.0" > /dev/null
      - name: Unit tests, lint and the privacy audit
        run: ./gradlew :build-logic:convention:check check kestrelAudit --console=plain
      - name: Check that generated files are committed
        run: |
          git status --porcelain
          test -z "$(git status --porcelain)"
      - name: Upload reports
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: reports
          path: |
            **/build/reports/
            **/build/test-results/
```

- [ ] **Step 2: Create the README**

`README.md`:

````markdown
# Kestrel

Kestrel is a private, voice-first assistant for Android. It is meant to replace Google's Voice Access on the owner's phone. It keeps the direct commands that Voice Access has, such as "scroll down", "tap Send" or "show numbers", and it adds whole tasks in one sentence, such as "Kestrel, send Ana a Viber message that I'm 10 minutes late". An agent on the phone then performs the task through Android's accessibility service.

> **Status:** early development. Sub-project 1 (Foundation) is in progress. Kestrel cannot listen or run tasks yet.

## Privacy rules

- Nothing leaves the phone except the traffic to the LLM provider that the owner configures. Speech recognition, voice output, OCR and all storage run on the phone, and there is no telemetry of any kind.
- All network traffic goes through one HTTP client with a host allowlist. Every request is written to an encrypted network log that the owner can view and delete.
- A build-time audit fails the build when a dependency is a known telemetry or advertising library, when a licence is not compatible with GPL-3.0, when code outside the network gateway uses network APIs, or when a native library imports socket functions.

The full design is in [docs/superpowers/specs/2026-11-12-kestrel-design.md](docs/superpowers/specs/2026-11-12-kestrel-design.md).

## Building

Kestrel builds on Windows with PowerShell. You need:

- the JDK that comes with Android Studio, in `C:\Program Files\Android\Android Studio\jbr` (if it is not there, set `JAVA_HOME` to JDK 17 or newer);
- the Android SDK with the `platforms;android-37.0` package, in the folder that `ANDROID_HOME` names (`D:\AndroidVMs\SDK` if the variable is not set).

```powershell
scripts\gradle.ps1 assembleDebug                    # build the app
scripts\gradle.ps1 check kestrelAudit               # unit tests, lint and the privacy audit
scripts\gradle.ps1 :build-logic:convention:check    # tests of the audit itself
```

To open the project in Android Studio, use Android Studio Rabbit 2 (2026.2.2) or newer, which supports version 9.5 of the Android Gradle plugin.

## Testing on a device

Kestrel is tested only on the `kestrel-api37` emulator and on a Galaxy A71. The scripts refuse other devices.

```powershell
scripts\create-avd.ps1                    # once: installs Android 17 and creates kestrel-api37
$serial = scripts\start-emulator.ps1      # starts the emulator and prints its adb serial
scripts\connected-test.ps1 -Serial $serial :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
scripts\install.ps1 -Serial $serial       # installs the debug build with adb install -r
```

Kestrel is installed with `adb install`, which avoids Android's block on accessibility access for apps from unknown sources. On a Samsung phone, Auto Blocker must be off while USB debugging is in use.

## Licence

Kestrel is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).
````

- [ ] **Step 3: Run the CI command locally**

Run: `scripts\gradle.ps1 :build-logic:convention:check check kestrelAudit`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```powershell
git add -A
git commit -m "ci: add GitHub Actions workflow and README" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 5: Create the public repository and push**

```powershell
gh repo create ak800i/kestrel --public --source . --remote origin --push --description "A private, voice-first assistant for Android that performs tasks through the accessibility service. Only the configured LLM provider receives data."
```

Expected: `Created repository ak800i/kestrel on GitHub` and the push of `main`.

- [ ] **Step 6: Watch CI**

```powershell
Start-Sleep -Seconds 10
$run = gh run list --limit 1 --json databaseId | ConvertFrom-Json
gh run watch $run[0].databaseId --exit-status
```

Expected: the `CI` run finishes with a green check, and the command exits with code 0. If it fails, read the log with `gh run view $run[0].databaseId --log-failed`, fix the cause, commit, push with `git push`, and watch the new run. The most likely cause is a `gradlew` without the executable bit; Task 1 Step 11 sets it.

---

### Task 8: `:core:privacy` module and field encryption

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidLibraryConventionPlugin.kt`
- Modify: `build-logic/convention/build.gradle.kts` (register `kestrel.android.library`)
- Modify: `settings.gradle.kts` (include `:core:privacy`)
- Create: `core/privacy/build.gradle.kts`
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/FieldCipher.kt`, `KeystoreKeys.kt`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/crypto/AesGcmFieldCipherTest.kt`
- Test (device): `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KeystoreCipherTest.kt`

**Interfaces:**
- Consumes: `KestrelSdk` and `applyKestrelAndroidDefaults()` (Task 2), `AuditPlugin` (Task 4), and `scripts\connected-test.ps1` and `scripts\start-emulator.ps1` (Task 3).
- Produces: the plugin ID `kestrel.android.library`.
- Produces: `interface FieldCipher { fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray; fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray }` and `class AesGcmFieldCipher(key: () -> SecretKey) : FieldCipher`.
- Produces: `KeystoreKeys.getOrCreate(alias: String): SecretKey` and `KeystoreKeys.DATA_KEY = "kestrel.data"`.

The associated data names the place where a value is stored, such as `settings/safety`. A value that someone copies to another place no longer decrypts. Android Keystore does not let the caller choose the IV, so the cipher generates it and stores it in front of the ciphertext. Credentials get their own key in sub-project 4 (spec section 13.4).

- [ ] **Step 1: Create the library convention and the module**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidLibraryConventionPlugin.kt`:

```kotlin
package dev.kestrel.build

import com.android.build.api.dsl.LibraryExtension
import dev.kestrel.build.audit.AuditPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** For the Android modules under `core/`. It also applies the privacy audit. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> { applyKestrelAndroidDefaults() }
            pluginManager.apply(AuditPlugin::class.java)
        }
    }
}
```

`build-logic/convention/build.gradle.kts` (final version):

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradleApi)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.asm)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "kestrel.android.application"
            implementationClass = "dev.kestrel.build.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "kestrel.android.library"
            implementationClass = "dev.kestrel.build.AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "kestrel.jvm.library"
            implementationClass = "dev.kestrel.build.JvmLibraryConventionPlugin"
        }
        register("audit") {
            id = "kestrel.audit"
            implementationClass = "dev.kestrel.build.audit.AuditPlugin"
        }
    }
}
```

`settings.gradle.kts` (final version):

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kestrel"

include(":app")
include(":core:privacy")
include(":core:safety")
```

`core/privacy/build.gradle.kts` (Tasks 9, 11 and 12 add more):

```kotlin
plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.privacy"
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

- [ ] **Step 2: Write the failing test**

`core/privacy/src/test/kotlin/dev/kestrel/privacy/crypto/AesGcmFieldCipherTest.kt`:

```kotlin
package dev.kestrel.privacy.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class AesGcmFieldCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmFieldCipher { key }
    private val place = "settings/safety".encodeToByteArray()

    @Test
    fun decryptsWhatItEncrypted() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        assertEquals("hello", cipher.decrypt(sealed, place).decodeToString())
    }

    @Test
    fun encryptingTheSameValueTwiceGivesDifferentBytes() {
        val first = cipher.encrypt("hello".encodeToByteArray(), place)
        val second = cipher.encrypt("hello".encodeToByteArray(), place)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun outputHasVersionByteAndTwelveByteIv() {
        val sealed = cipher.encrypt(ByteArray(10), place)
        assertEquals(1.toByte(), sealed[0])
        assertEquals(1 + 12 + 10 + 16, sealed.size)
    }

    @Test
    fun valueMovedToAnotherPlaceDoesNotDecrypt() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(sealed, "settings/other".encodeToByteArray()) }
    }

    @Test
    fun tamperedValueDoesNotDecrypt() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(sealed, place) }
    }

    @Test
    fun unknownFormatIsRejected() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        sealed[0] = 2
        val error = assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(sealed, place) }
        assertEquals("Unknown ciphertext format", error.message)
    }

    @Test
    fun emptyValueRoundTrips() {
        assertArrayEquals(ByteArray(0), cipher.decrypt(cipher.encrypt(ByteArray(0), place), place))
    }
}
```

- [ ] **Step 3: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'AesGcmFieldCipher'`.

- [ ] **Step 4: Write the cipher**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/FieldCipher.kt`:

```kotlin
package dev.kestrel.privacy.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts single database fields. The associated data binds a value to the place where it is stored. */
interface FieldCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * AES-256-GCM (design spec, section 13.4). The output is a version byte, the 12-byte IV that the
 * cipher generates, and the ciphertext with its 16-byte tag.
 */
class AesGcmFieldCipher(private val key: () -> SecretKey) : FieldCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore keys refuse caller-chosen IVs, so the cipher picks a random one.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext.size > HEADER_BYTES && ciphertext[0] == VERSION) { "Unknown ciphertext format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, ciphertext, 1, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, HEADER_BYTES, ciphertext.size - HEADER_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val HEADER_BYTES = 1 + IV_BYTES
        const val TAG_BITS = 128
    }
}
```

- [ ] **Step 5: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`. `TEST-dev.kestrel.privacy.crypto.AesGcmFieldCipherTest.xml` reports `tests="7" failures="0" errors="0"`.

- [ ] **Step 6: Write the failing device test**

`core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KeystoreCipherTest.kt`:

```kotlin
package dev.kestrel.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    private val alias = "kestrel.test.${System.nanoTime()}"
    private val place = "settings/test".encodeToByteArray()

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun keystoreKeyDecryptsInANewCipherInstance() {
        val sealed = AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }.encrypt("hello".encodeToByteArray(), place)

        val opened = AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }.decrypt(sealed, place)

        assertEquals("hello", opened.decodeToString())
    }
}
```

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :core:privacy:connectedDebugAndroidTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'KeystoreKeys'`.

- [ ] **Step 7: Write the Keystore key provider**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt`:

```kotlin
package dev.kestrel.privacy.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-256 keys that never leave Android Keystore (design spec, section 13.4). */
object KeystoreKeys {
    /** Encrypts logs and settings. Credentials get their own key in sub-project 4. */
    const val DATA_KEY = "kestrel.data"

    private const val PROVIDER = "AndroidKeyStore"

    @Synchronized
    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
```

- [ ] **Step 8: Run the device test to verify that it passes**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :core:privacy:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, and the output names the device `kestrel-api37`. `core/privacy/build/outputs/androidTest-results/connected/` contains a result file with `tests="1" failures="0"`. If the output also names another device, stop: `ANDROID_SERIAL` did not limit the run, and the guard must be fixed before any more device tests.

- [ ] **Step 9: Run the audit**

Run: `scripts\gradle.ps1 kestrelAudit`

Expected: `BUILD SUCCESSFUL`. `core/privacy/build/reports/kestrel-audit/network-apis-release.txt` starts with `No network API use outside the exceptions.`

- [ ] **Step 10: Commit**

```powershell
git add -A
git commit -m "feat(privacy): add AES-GCM field encryption with Android Keystore keys" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: Encrypted activity log and network log

**Files:**
- Modify: `core/privacy/build.gradle.kts` (KSP, Room, kotlinx.serialization and coroutines)
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/LogEntryRow.kt`, `KestrelDatabase.kt`
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogEvents.kt`, `LogStore.kt`
- Create (generated, committed): `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/1.json`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/LogStoreTest.kt`
- Test (device): `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt`

**Interfaces:**
- Consumes: `FieldCipher` and `AesGcmFieldCipher` from Task 8.
- Produces: the Room entity `LogEntryRow(id: Long = 0, log: String, timestampMillis: Long, payload: ByteArray)` and `LogDao` with `insert(row)`, `newest(log, limit)`, `deleteOlderThan(cutoffMillis)` and `deleteAll()`.
- Produces: `abstract class KestrelDatabase : RoomDatabase` with `logDao()` and `KestrelDatabase.create(context)`.
- Produces: `ActivityEvent(kind: ActivityKind, text: String, taskId: String? = null)` with `enum class ActivityKind { ACTION, NOTE, ERROR }`, and `NetworkEvent(method, host, path, requestBytes, responseBytes, status: Int? = null, outcome: NetworkOutcome, taskId: String? = null, detail: String? = null)` with `enum class NetworkOutcome { COMPLETED, FAILED, BLOCKED }`.
- Produces: `class LogStore(dao: LogDao, cipher: FieldCipher, clock: () -> Long)` with `suspend fun append(event: ActivityEvent)`, `suspend fun append(event: NetworkEvent)`, `suspend fun activity(limit: Int = 500): List<LogEntry<ActivityEvent>>`, `suspend fun network(limit: Int = 500): List<LogEntry<NetworkEvent>>`, `suspend fun purgeExpired(): Int`, `suspend fun deleteHistory(): Int`, and `LogStore.RETENTION_MILLIS` (7 days).
- Produces: `data class LogEntry<T>(id: Long, timestampMillis: Long, event: T)`.

Both logs share one table. Only the timestamp and the log's name are stored in plain text, because retention and ordering need them; the event itself is encrypted JSON. The network log never stores a query string, headers or bodies, because a query string can contain an API key. Later sub-projects add more `ActivityKind` values, such as prompts and replies.

- [ ] **Step 1: Add Room and kotlinx.serialization to the module**

`core/privacy/build.gradle.kts` (Tasks 11 and 12 add OkHttp):

```kotlin
plugins {
    id("kestrel.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.privacy"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

- [ ] **Step 2: Write the failing test**

`core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/LogStoreTest.kt`:

```kotlin
package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.KeyGenerator

class LogStoreTest {
    private val dao = FakeLogDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_000_000_000_000L
    private val store = LogStore(dao, AesGcmFieldCipher { key }) { now }

    @Test
    fun readsActivityBackNewestFirst() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "first"))
        now += 1_000
        store.append(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"))

        val entries = store.activity()

        assertEquals(listOf("second", "first"), entries.map { it.event.text })
        assertEquals(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"), entries.first().event)
        assertEquals(now, entries.first().timestampMillis)
    }

    @Test
    fun storesPayloadsEncrypted() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "a secret note"))

        val stored = dao.rows.single().payload.decodeToString(throwOnInvalidSequence = false)

        assertFalse(stored.contains("secret"))
    }

    @Test
    fun keepsTheTwoLogsApart() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        assertEquals(listOf("note"), store.activity().map { it.event.text })
        assertEquals(listOf("api.example.com"), store.network().map { it.event.host })
    }

    @Test
    fun keepsEntriesForSevenDays() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS

        assertEquals(listOf("old"), store.activity().map { it.event.text })

        now += 1
        assertEquals(emptyList<String>(), store.activity().map { it.event.text })
    }

    @Test
    fun appendingRemovesExpiredEntries() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS + 1

        store.append(ActivityEvent(ActivityKind.NOTE, "new"))

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun deleteHistoryWipesBothLogs() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        store.deleteHistory()

        assertEquals(0, dao.rows.size)
    }

    private fun networkEvent(host: String) = NetworkEvent(
        method = "GET",
        host = host,
        path = "/",
        requestBytes = 0,
        responseBytes = 10,
        status = 200,
        outcome = NetworkOutcome.COMPLETED,
    )
}

/** Mimics the SQL in [LogDao]; KestrelDatabaseTest checks the real queries on a device. */
private class FakeLogDao : LogDao {
    val rows = mutableListOf<LogEntryRow>()
    private var nextId = 1L

    override suspend fun insert(row: LogEntryRow): Long {
        val id = nextId++
        rows += LogEntryRow(id, row.log, row.timestampMillis, row.payload)
        return id
    }

    override suspend fun newest(log: String, limit: Int): List<LogEntryRow> =
        rows.filter { it.log == log }
            .sortedWith(compareByDescending<LogEntryRow> { it.timestampMillis }.thenByDescending { it.id })
            .take(limit)

    override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
        val expired = rows.filter { it.timestampMillis < cutoffMillis }
        rows.removeAll(expired)
        return expired.size
    }

    override suspend fun deleteAll(): Int {
        val count = rows.size
        rows.clear()
        return count
    }
}
```

- [ ] **Step 3: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD FAILED`, with unresolved references to `LogDao`, `LogEntryRow`, `LogStore` and the event classes.

- [ ] **Step 4: Write the table, the events and the store**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/db/LogEntryRow.kt`:

```kotlin
package dev.kestrel.privacy.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** One entry of the activity log or the network log. Only the payload is sensitive, and it is encrypted. */
@Entity(tableName = "log_entries", indices = [Index(value = ["log", "timestamp_millis"])])
class LogEntryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val log: String,
    @ColumnInfo(name = "timestamp_millis") val timestampMillis: Long,
    val payload: ByteArray,
)

@Dao
interface LogDao {
    @Insert
    suspend fun insert(row: LogEntryRow): Long

    @Query("SELECT * FROM log_entries WHERE log = :log ORDER BY timestamp_millis DESC, id DESC LIMIT :limit")
    suspend fun newest(log: String, limit: Int): List<LogEntryRow>

    @Query("DELETE FROM log_entries WHERE timestamp_millis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM log_entries")
    suspend fun deleteAll(): Int
}
```

`core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt` (Task 10 adds the settings table):

```kotlin
package dev.kestrel.privacy.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LogEntryRow::class], version = 1, exportSchema = true)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        const val FILE_NAME = "kestrel.db"

        fun create(context: Context): KestrelDatabase =
            Room.databaseBuilder(context, KestrelDatabase::class.java, FILE_NAME).build()
    }
}
```

`core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogEvents.kt`:

```kotlin
package dev.kestrel.privacy.logs

import kotlinx.serialization.Serializable

/** What kind of activity-log entry this is (design spec, section 13.5). Later sub-projects add more kinds. */
@Serializable
enum class ActivityKind { ACTION, NOTE, ERROR }

@Serializable
data class ActivityEvent(
    val kind: ActivityKind,
    val text: String,
    val taskId: String? = null,
)

@Serializable
enum class NetworkOutcome { COMPLETED, FAILED, BLOCKED }

/** One request in the network log. It never contains the query string, headers or bodies. */
@Serializable
data class NetworkEvent(
    val method: String,
    val host: String,
    val path: String,
    val requestBytes: Long,
    val responseBytes: Long,
    val status: Int? = null,
    val outcome: NetworkOutcome,
    val taskId: String? = null,
    val detail: String? = null,
)
```

`core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogStore.kt`:

```kotlin
package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

data class LogEntry<T>(val id: Long, val timestampMillis: Long, val event: T)

/** The encrypted activity log and network log, which keep entries for 7 days (design spec, section 13.5). */
class LogStore(
    private val dao: LogDao,
    private val cipher: FieldCipher,
    private val clock: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastPurgeMillis: Long? = null

    suspend fun append(event: ActivityEvent) = append(ACTIVITY, ActivityEvent.serializer(), event)

    suspend fun append(event: NetworkEvent) = append(NETWORK, NetworkEvent.serializer(), event)

    suspend fun activity(limit: Int = DEFAULT_LIMIT): List<LogEntry<ActivityEvent>> =
        read(ACTIVITY, ActivityEvent.serializer(), limit)

    suspend fun network(limit: Int = DEFAULT_LIMIT): List<LogEntry<NetworkEvent>> =
        read(NETWORK, NetworkEvent.serializer(), limit)

    /** Deletes the entries of both logs that are older than 7 days. */
    suspend fun purgeExpired(): Int {
        val now = clock()
        lastPurgeMillis = now
        return dao.deleteOlderThan(now - RETENTION_MILLIS)
    }

    /** "Delete history" wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory(): Int = dao.deleteAll()

    private suspend fun <T> append(log: String, serializer: KSerializer<T>, event: T) {
        val now = clock()
        val lastPurge = lastPurgeMillis
        if (lastPurge == null || now - lastPurge >= PURGE_INTERVAL_MILLIS) purgeExpired()
        val plaintext = json.encodeToString(serializer, event).encodeToByteArray()
        dao.insert(LogEntryRow(log = log, timestampMillis = now, payload = cipher.encrypt(plaintext, associatedData(log))))
    }

    private suspend fun <T> read(log: String, serializer: KSerializer<T>, limit: Int): List<LogEntry<T>> {
        purgeExpired()
        return dao.newest(log, limit).map { row ->
            val plaintext = cipher.decrypt(row.payload, associatedData(log)).decodeToString()
            LogEntry(row.id, row.timestampMillis, json.decodeFromString(serializer, plaintext))
        }
    }

    private fun associatedData(log: String) = "log_entries/$log".encodeToByteArray()

    companion object {
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val PURGE_INTERVAL_MILLIS = 60L * 60 * 1000
        const val DEFAULT_LIMIT = 500
        private const val ACTIVITY = "activity"
        private const val NETWORK = "network"
    }
}
```

- [ ] **Step 5: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`. `TEST-dev.kestrel.privacy.logs.LogStoreTest.xml` reports `tests="6" failures="0" errors="0"`, and the Room schema `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/1.json` exists.

- [ ] **Step 6: Check the real SQL on the emulator**

`core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt` (Task 10 adds a settings test):

```kotlin
package dev.kestrel.privacy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KestrelDatabaseTest {
    private lateinit var db: KestrelDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KestrelDatabase::class.java).build()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun newestReturnsOneLogNewestFirst() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 1, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 3, payload = byteArrayOf(3)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 2, payload = byteArrayOf(2)))

        assertEquals(listOf(3L, 1L), dao.newest("activity", 10).map { it.timestampMillis })
        assertEquals(listOf(3L), dao.newest("activity", 1).map { it.timestampMillis })
    }

    @Test
    fun deleteOlderThanKeepsEntriesAtTheCutoff() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 99, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 100, payload = byteArrayOf(2)))

        assertEquals(1, dao.deleteOlderThan(100))
        assertEquals(listOf(100L), dao.newest("network", 10).map { it.timestampMillis })
    }
}
```

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :core:privacy:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 3 tests passed on `kestrel-api37` (this test's two and `KeystoreCipherTest`).

- [ ] **Step 7: Commit**

```powershell
git add -A
git commit -m "feat(privacy): add encrypted activity and network logs with 7-day retention" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 10: Encrypted settings

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/SettingRow.kt`
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/settings/SettingsStore.kt`
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt` (add the settings table)
- Modify (generated): `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/1.json`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/settings/SettingsStoreTest.kt`
- Test (device): `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt`

**Interfaces:**
- Consumes: `FieldCipher` (Task 8) and `KestrelDatabase` (Task 9).
- Produces: the Room entity `SettingRow(name: String, payload: ByteArray)` and `SettingsDao` with `get(name)`, `observe(name): Flow<SettingRow?>` and `put(row)`.
- Produces: `class SettingKey<T>(name: String, serializer: KSerializer<T>, default: T)`.
- Produces: `class SettingsStore(dao: SettingsDao, cipher: FieldCipher)` with `suspend fun <T> get(key: SettingKey<T>): T`, `fun <T> observe(key: SettingKey<T>): Flow<T>` and `suspend fun <T> set(key: SettingKey<T>, value: T)`.

The database stays at version 1, because no build has been released yet. From the first release on, every schema change needs a new version and a migration.

- [ ] **Step 1: Write the failing test**

`core/privacy/src/test/kotlin/dev/kestrel/privacy/settings/SettingsStoreTest.kt`:

```kotlin
package dev.kestrel.privacy.settings

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.KeyGenerator

class SettingsStoreTest {
    @Serializable
    data class Volume(val level: Int)

    private val dao = FakeSettingsDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = SettingsStore(dao, AesGcmFieldCipher { key })
    private val volume = SettingKey("volume", Volume.serializer(), Volume(level = 5))

    @Test
    fun returnsTheDefaultUntilSet() = runTest {
        assertEquals(Volume(5), store.get(volume))
        assertEquals(Volume(5), store.observe(volume).first())
    }

    @Test
    fun returnsTheStoredValue() = runTest {
        store.set(volume, Volume(level = 9))

        assertEquals(Volume(9), store.get(volume))
        assertEquals(Volume(9), store.observe(volume).first())
    }

    @Test
    fun storesValuesEncrypted() = runTest {
        store.set(volume, Volume(level = 9))

        val stored = dao.rows.value.getValue("volume").payload.decodeToString(throwOnInvalidSequence = false)

        assertFalse(stored.contains("level"))
    }
}

/** Mimics the SQL in [SettingsDao]; KestrelDatabaseTest checks the real queries on a device. */
private class FakeSettingsDao : SettingsDao {
    val rows = MutableStateFlow<Map<String, SettingRow>>(emptyMap())

    override suspend fun get(name: String): SettingRow? = rows.value[name]

    override fun observe(name: String): Flow<SettingRow?> = rows.map { it[name] }

    override suspend fun put(row: SettingRow) {
        rows.value = rows.value + (row.name to row)
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD FAILED`, with unresolved references to `SettingsDao`, `SettingRow`, `SettingsStore` and `SettingKey`.

- [ ] **Step 3: Write the table and the store**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/db/SettingRow.kt`:

```kotlin
package dev.kestrel.privacy.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One setting, stored as encrypted JSON. */
@Entity(tableName = "settings")
class SettingRow(
    @PrimaryKey val name: String,
    val payload: ByteArray,
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE name = :name")
    suspend fun get(name: String): SettingRow?

    @Query("SELECT * FROM settings WHERE name = :name")
    fun observe(name: String): Flow<SettingRow?>

    @Upsert
    suspend fun put(row: SettingRow)
}
```

`core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt` (final version):

```kotlin
package dev.kestrel.privacy.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LogEntryRow::class, SettingRow::class], version = 1, exportSchema = true)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun settingsDao(): SettingsDao

    companion object {
        const val FILE_NAME = "kestrel.db"

        fun create(context: Context): KestrelDatabase =
            Room.databaseBuilder(context, KestrelDatabase::class.java, FILE_NAME).build()
    }
}
```

`core/privacy/src/main/kotlin/dev/kestrel/privacy/settings/SettingsStore.kt`:

```kotlin
package dev.kestrel.privacy.settings

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Names a setting, its JSON serializer and the value to use until the owner changes it. */
class SettingKey<T>(val name: String, val serializer: KSerializer<T>, val default: T)

/** Settings stored as encrypted JSON in Room (design spec, section 13.4). */
class SettingsStore(
    private val dao: SettingsDao,
    private val cipher: FieldCipher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun <T> get(key: SettingKey<T>): T = dao.get(key.name)?.let { decode(key, it) } ?: key.default

    fun <T> observe(key: SettingKey<T>): Flow<T> = dao.observe(key.name).map { row -> row?.let { decode(key, it) } ?: key.default }

    suspend fun <T> set(key: SettingKey<T>, value: T) {
        val plaintext = json.encodeToString(key.serializer, value).encodeToByteArray()
        dao.put(SettingRow(key.name, cipher.encrypt(plaintext, associatedData(key.name))))
    }

    private fun <T> decode(key: SettingKey<T>, row: SettingRow): T =
        json.decodeFromString(key.serializer, cipher.decrypt(row.payload, associatedData(key.name)).decodeToString())

    private fun associatedData(name: String) = "settings/$name".encodeToByteArray()
}
```

- [ ] **Step 4: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`. `TEST-dev.kestrel.privacy.settings.SettingsStoreTest.xml` reports `tests="3" failures="0" errors="0"`, and `1.json` now also describes the `settings` table.

- [ ] **Step 5: Check the settings SQL on the emulator**

`core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt` (final version):

```kotlin
package dev.kestrel.privacy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import dev.kestrel.privacy.db.SettingRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KestrelDatabaseTest {
    private lateinit var db: KestrelDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KestrelDatabase::class.java).build()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun newestReturnsOneLogNewestFirst() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 1, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 3, payload = byteArrayOf(3)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 2, payload = byteArrayOf(2)))

        assertEquals(listOf(3L, 1L), dao.newest("activity", 10).map { it.timestampMillis })
        assertEquals(listOf(3L), dao.newest("activity", 1).map { it.timestampMillis })
    }

    @Test
    fun deleteOlderThanKeepsEntriesAtTheCutoff() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 99, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 100, payload = byteArrayOf(2)))

        assertEquals(1, dao.deleteOlderThan(100))
        assertEquals(listOf(100L), dao.newest("network", 10).map { it.timestampMillis })
    }

    @Test
    fun settingsUpsertReplacesTheValue() = runBlocking {
        val dao = db.settingsDao()
        assertNull(dao.observe("volume").first())

        dao.put(SettingRow("volume", byteArrayOf(1)))
        dao.put(SettingRow("volume", byteArrayOf(2)))

        assertArrayEquals(byteArrayOf(2), dao.get("volume")!!.payload)
        assertArrayEquals(byteArrayOf(2), dao.observe("volume").first()!!.payload)
    }
}
```

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :core:privacy:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 4 tests passed on `kestrel-api37`.

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "feat(privacy): add encrypted settings store" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 11: Host allowlist

**Files:**
- Modify: `core/privacy/build.gradle.kts` (OkHttp)
- Modify: `audit/rules.txt` (allow OkHttp and Okio)
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/network/LocalHosts.kt`, `NetworkPolicy.kt`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/network/LocalHostsTest.kt`, `NetworkPolicyTest.kt`

**Interfaces:**
- Produces: `LocalHosts.isLocal(host: String): Boolean`.
- Produces: `sealed interface PolicyDecision { data object Allowed; data class Blocked(reason: String) }`.
- Produces: `class NetworkPolicy` with `setProfileHosts(hosts: Collection<String>)`, `setLocalServers(hosts: Collection<String>)` (throws `IllegalArgumentException` for a host that is not local), `openTemporaryHosts(hosts: Collection<String>): AutoCloseable` and `check(url: HttpUrl): PolicyDecision`.

Sub-project 4 fills the allowlist from the configured profiles and opens temporary hosts while a sign-in runs (spec section 13.1). The blocking reasons are shown to the owner in the network log, so they are plain sentences.

- [ ] **Step 1: Add OkHttp and review its licence**

In `core/privacy/build.gradle.kts`, add this line at the top of the `dependencies` block:

```kotlin
    api(libs.okhttp)
```

In `audit/rules.txt`, add these two lines directly after `allow org.jetbrains:annotations Apache-2.0`:

```text
allow com.squareup.okhttp3:* Apache-2.0
allow com.squareup.okio:* Apache-2.0
```

OkHttp and Okio use the Apache License 2.0 (https://github.com/square/okhttp/blob/master/LICENSE.txt, https://github.com/square/okio/blob/master/LICENSE.txt).

- [ ] **Step 2: Write the failing tests**

`core/privacy/src/test/kotlin/dev/kestrel/privacy/network/LocalHostsTest.kt`:

```kotlin
package dev.kestrel.privacy.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHostsTest {
    @Test
    fun acceptsLocalhostAndPrivateNetworks() {
        listOf(
            "localhost", "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.10",
            "169.254.1.1", "::1", "[::1]", "fd00::1", "fe80::1", "nas.local",
        ).forEach { assertTrue(it, LocalHosts.isLocal(it)) }
    }

    @Test
    fun rejectsPublicHostsAndMalformedAddresses() {
        listOf(
            "172.32.0.1", "8.8.8.8", "example.com", "1.2.3", "256.1.1.1", "fdroid.org", "2001:db8::1", "localhost.example.com",
        ).forEach { assertFalse(it, LocalHosts.isLocal(it)) }
    }
}
```

`core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkPolicyTest.kt`:

```kotlin
package dev.kestrel.privacy.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkPolicyTest {
    private val policy = NetworkPolicy()

    @Test
    fun allowsProfileHostsOverHttps() {
        policy.setProfileHosts(listOf("API.Example.com"))

        assertEquals(PolicyDecision.Allowed, policy.check("https://api.example.com/v1/chat".toHttpUrl()))
    }

    @Test
    fun blocksPlainHttpToProfileHosts() {
        policy.setProfileHosts(listOf("api.example.com"))

        assertEquals(
            PolicyDecision.Blocked("HTTPS is required for api.example.com"),
            policy.check("http://api.example.com/".toHttpUrl()),
        )
    }

    @Test
    fun blocksHostsOutsideTheAllowlist() {
        policy.setProfileHosts(listOf("api.example.com"))

        assertEquals(
            PolicyDecision.Blocked("tracker.example.net is not on the allowlist"),
            policy.check("https://tracker.example.net/".toHttpUrl()),
        )
    }

    @Test
    fun allowsLocalServersOverPlainHttp() {
        policy.setLocalServers(listOf("192.168.1.20"))

        assertEquals(PolicyDecision.Allowed, policy.check("http://192.168.1.20:1234/v1/models".toHttpUrl()))
    }

    @Test
    fun refusesPublicHostsAsLocalServers() {
        val error = assertThrows(IllegalArgumentException::class.java) { policy.setLocalServers(listOf("example.com")) }

        assertEquals("example.com is not a local-network or localhost address", error.message)
    }

    @Test
    fun temporaryHostsAreAllowedUntilEveryLeaseCloses() {
        val url = "https://login.example.com/device".toHttpUrl()
        val first = policy.openTemporaryHosts(listOf("login.example.com"))
        val second = policy.openTemporaryHosts(listOf("login.example.com"))

        first.close()
        first.close()
        assertEquals(PolicyDecision.Allowed, policy.check(url))

        second.close()
        assertEquals(PolicyDecision.Blocked("login.example.com is not on the allowlist"), policy.check(url))
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD FAILED`, with unresolved references to `LocalHosts`, `NetworkPolicy` and `PolicyDecision`.

- [ ] **Step 4: Write the allowlist**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/network/LocalHosts.kt`:

```kotlin
package dev.kestrel.privacy.network

/** Recognises localhost and private-network addresses, the only hosts that may use plain HTTP (design spec, section 13.1). */
object LocalHosts {
    fun isLocal(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        if (name == "localhost" || name.endsWith(".local")) return true
        if (':' in name) return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe80:")
        val octets = ipv4Octets(name) ?: return false
        val (first, second) = octets
        return first == 127 || first == 10 || (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) || (first == 169 && second == 254)
    }

    private fun ipv4Octets(name: String): List<Int>? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { all -> all.all { it in 0..255 } }
    }
}
```

`core/privacy/src/main/kotlin/dev/kestrel/privacy/network/NetworkPolicy.kt`:

```kotlin
package dev.kestrel.privacy.network

import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    data class Blocked(val reason: String) : PolicyDecision
}

/** The host allowlist (design spec, section 13.1). */
class NetworkPolicy {
    @Volatile
    private var profileHosts: Set<String> = emptySet()

    @Volatile
    private var localServers: Set<String> = emptySet()

    private val temporaryHosts = mutableMapOf<String, Int>()

    /** The hosts of the configured profiles, including the hosts they need for token refresh. */
    fun setProfileHosts(hosts: Collection<String>) {
        profileHosts = hosts.map(::normalize).toSet()
    }

    /** Local-network or localhost servers that the owner added. Only these may use plain HTTP. */
    fun setLocalServers(hosts: Collection<String>) {
        val normalized = hosts.map(::normalize)
        normalized.firstOrNull { !LocalHosts.isLocal(it) }?.let {
            throw IllegalArgumentException("$it is not a local-network or localhost address")
        }
        localServers = normalized.toSet()
    }

    /** Allows [hosts] until the returned lease is closed, for example while a sign-in runs. */
    fun openTemporaryHosts(hosts: Collection<String>): AutoCloseable {
        val normalized = hosts.map(::normalize).toSet()
        synchronized(temporaryHosts) {
            normalized.forEach { temporaryHosts[it] = (temporaryHosts[it] ?: 0) + 1 }
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(temporaryHosts) {
                    normalized.forEach { host ->
                        val remaining = temporaryHosts.getValue(host) - 1
                        if (remaining == 0) temporaryHosts.remove(host) else temporaryHosts[host] = remaining
                    }
                }
            }
        }
    }

    fun check(url: HttpUrl): PolicyDecision {
        val host = normalize(url.host)
        if (host in localServers) return PolicyDecision.Allowed
        if (!url.isHttps) return PolicyDecision.Blocked("HTTPS is required for $host")
        val temporary = synchronized(temporaryHosts) { host in temporaryHosts }
        return if (host in profileHosts || temporary) {
            PolicyDecision.Allowed
        } else {
            PolicyDecision.Blocked("$host is not on the allowlist")
        }
    }

    private fun normalize(host: String): String = host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`. `LocalHostsTest` reports `tests="2"` and `NetworkPolicyTest` reports `tests="6"`, all without failures.

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "feat(privacy): add host allowlist with HTTPS rule and local servers" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 12: Network gateway

**Files:**
- Modify: `core/privacy/build.gradle.kts` (MockWebServer for tests)
- Modify: `audit/rules.txt` (the network exception)
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/network/NetworkGateway.kt`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkGatewayTest.kt`

**Interfaces:**
- Consumes: `NetworkPolicy.check(url)` (Task 11) and `NetworkEvent`/`NetworkOutcome` (Task 9).
- Produces: `class NetworkGateway(policy: NetworkPolicy, record: (NetworkEvent) -> Unit)` with `val callFactory: okhttp3.Call.Factory`.
- Produces: `data class TaskTag(taskId: String)`, which callers attach with `Request.Builder().tag(TaskTag::class.java, TaskTag(id))`, and `class BlockedRequestException(url: HttpUrl, reason: String) : IOException`.

The gateway checks the allowlist in an application interceptor, before OkHttp looks up the host or opens a connection. It does not follow redirects on its own, because a redirect could lead to a host that was never checked, and it ignores system proxies. An event listener writes one network-log entry per call. Other modules get only the `Call.Factory`; the audit refuses an `OkHttpClient` anywhere outside `dev/kestrel/privacy/network/`.

- [ ] **Step 1: Add MockWebServer for the tests, and default return values for OkHttp**

`core/privacy/build.gradle.kts` (final version). Besides MockWebServer, it turns on default return values for this module's unit tests only. When the gateway's OkHttp client starts, OkHttp's Android build calls `android.util.Log`, and on the PC that call would fail and print a stack trace into the test report. All other modules keep failing loudly on Android calls that are not mocked (owner decision during the Task 2 review).

```kotlin
plugins {
    id("kestrel.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.privacy"
    testOptions {
        // OkHttp's Android build calls android.util.Log when it starts. On the PC that call would
        // fail and print a stack trace, so this module's unit tests get default return values.
        unitTests.isReturnDefaultValues = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

- [ ] **Step 2: Write the failing test**

`core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkGatewayTest.kt`:

```kotlin
package dev.kestrel.privacy.network

import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class NetworkGatewayTest {
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<NetworkEvent>()
    private val policy = NetworkPolicy()
    private val gateway = NetworkGateway(policy) { events += it }

    @Before
    fun startServer() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun serverUrl(path: String): HttpUrl = server.url(path).newBuilder().host("127.0.0.1").build()

    private fun execute(request: Request): Pair<Int, String> =
        gateway.callFactory.newCall(request).execute().use { it.code to it.body.string() }

    @Test
    fun logsAllowedRequestsWithoutTheQuery() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().body("hello").build())

        val request = Request.Builder().url(serverUrl("/v1/models?key=secret")).tag(TaskTag::class.java, TaskTag("task-1")).build()

        assertEquals(200 to "hello", execute(request))
        assertEquals(
            NetworkEvent(
                method = "GET",
                host = "127.0.0.1",
                path = "/v1/models",
                requestBytes = 0,
                responseBytes = 5,
                status = 200,
                outcome = NetworkOutcome.COMPLETED,
                taskId = "task-1",
            ),
            events.single(),
        )
    }

    @Test
    fun countsRequestBodyBytes() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().body("ok").build())

        execute(Request.Builder().url(serverUrl("/v1/chat")).post("hello".toRequestBody()).build())

        assertEquals(5L, events.single().requestBytes)
    }

    @Test
    fun blocksHostsOutsideTheAllowlistBeforeConnecting() {
        val request = Request.Builder().url("https://tracker.example.net/collect".toHttpUrl()).build()

        val error = assertThrows(BlockedRequestException::class.java) { execute(request) }

        assertEquals("tracker.example.net is not on the allowlist", error.message)
        val event = events.single()
        assertEquals(NetworkOutcome.BLOCKED, event.outcome)
        assertEquals("tracker.example.net is not on the allowlist", event.detail)
    }

    @Test
    fun blocksPlainHttpUnlessTheServerIsALocalServer() {
        server.enqueue(MockResponse.Builder().body("never sent").build())

        assertThrows(BlockedRequestException::class.java) { execute(Request.Builder().url(serverUrl("/")).build()) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun doesNotFollowRedirects() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", serverUrl("/elsewhere").toString()).build())

        val (code, _) = execute(Request.Builder().url(serverUrl("/start")).build())

        assertEquals(302, code)
        assertEquals(1, server.requestCount)
    }
}
```

- [ ] **Step 3: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD FAILED`, with unresolved references to `NetworkGateway`, `TaskTag` and `BlockedRequestException`.

- [ ] **Step 4: Write the gateway**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/network/NetworkGateway.kt`:

```kotlin
package dev.kestrel.privacy.network

import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Tags a request with the task that caused it, so that the network log can show it. */
data class TaskTag(val taskId: String)

class BlockedRequestException(val url: HttpUrl, reason: String) : IOException(reason)

/**
 * The only HTTP client in Kestrel (design spec, section 13.1). It refuses hosts outside the allowlist
 * before any DNS lookup or connection, never follows redirects on its own, ignores system proxies and
 * reports every request to [record] for the network log.
 */
class NetworkGateway(
    policy: NetworkPolicy,
    record: (NetworkEvent) -> Unit,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AllowlistInterceptor(policy))
        .eventListenerFactory { NetworkLogListener(record) }
        .build()

    /** Every request in Kestrel goes through this. */
    val callFactory: Call.Factory get() = client
}

private class AllowlistInterceptor(private val policy: NetworkPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        return when (val decision = policy.check(url)) {
            PolicyDecision.Allowed -> chain.proceed(chain.request())
            is PolicyDecision.Blocked -> throw BlockedRequestException(url, decision.reason)
        }
    }
}

private class NetworkLogListener(private val record: (NetworkEvent) -> Unit) : EventListener() {
    private var requestBytes = 0L
    private var responseBytes = 0L
    private var status: Int? = null

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        requestBytes += byteCount
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        status = response.code
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        responseBytes += byteCount
    }

    override fun callEnd(call: Call) = write(call, NetworkOutcome.COMPLETED, detail = null)

    override fun callFailed(call: Call, ioe: IOException) {
        val outcome = if (ioe is BlockedRequestException) NetworkOutcome.BLOCKED else NetworkOutcome.FAILED
        write(call, outcome, ioe.message ?: ioe.javaClass.simpleName)
    }

    private fun write(call: Call, outcome: NetworkOutcome, detail: String?) {
        val request = call.request()
        record(
            NetworkEvent(
                method = request.method,
                host = request.url.host,
                path = request.url.encodedPath,
                requestBytes = requestBytes,
                responseBytes = responseBytes,
                status = status,
                outcome = outcome,
                taskId = request.tag(TaskTag::class.java)?.taskId,
                detail = detail,
            ),
        )
    }
}
```

- [ ] **Step 5: Run the test to verify that it passes**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`. `TEST-dev.kestrel.privacy.network.NetworkGatewayTest.xml` reports `tests="5" failures="0" errors="0"`, and its `<system-err>` section is empty.

- [ ] **Step 6: See the audit refuse the gateway without an exception**

Run: `scripts\gradle.ps1 :core:privacy:kestrelAudit`

Expected: `BUILD FAILED`, with lines such as `dev/kestrel/privacy/network/NetworkGateway.<init> uses okhttp3/OkHttpClient$Builder`.

- [ ] **Step 7: Add the justified exception**

In `audit/rules.txt`, add these lines directly before `# Reviewed licences`:

```text
# Network API exceptions
allow-network-class dev/kestrel/privacy/network/ The network gateway is the only HTTP client in Kestrel (design spec, section 13.1).

```

Run: `scripts\gradle.ps1 kestrelAudit`

Expected: `BUILD SUCCESSFUL`. `core/privacy/build/reports/kestrel-audit/network-apis-release.txt` starts with `No network API use outside the exceptions.` and lists the `NetworkGateway` findings under `Allowed by audit/rules.txt:`.

- [ ] **Step 8: Check that the exception covers only the network package**

```powershell
Set-Content core\privacy\src\main\kotlin\dev\kestrel\privacy\logs\Leak.kt "package dev.kestrel.privacy.logs`n`nfun leak() = java.net.Socket(`"example.com`", 443)`n"
scripts\gradle.ps1 :core:privacy:kestrelAudit
Remove-Item core\privacy\src\main\kotlin\dev\kestrel\privacy\logs\Leak.kt
scripts\gradle.ps1 :core:privacy:kestrelAudit
```

Expected: the first audit ends with `BUILD FAILED` and the line `dev/kestrel/privacy/logs/LeakKt.leak uses java/net/Socket`. The second ends with `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add -A
git commit -m "feat(privacy): add network gateway that enforces the allowlist and logs every request" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 13: Wire the privacy core into the app

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `KestrelApplication.kt`
- Modify: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Test (device): `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`

**Interfaces:**
- Consumes: `LogStore` (Task 9), `SettingsStore` and `SettingKey` (Task 10), `NetworkPolicy` (Task 11), `NetworkGateway` (Task 12) and `SafetySettings` (Task 1).
- Produces: `class PrivacyStore(context: Context, clock: () -> Long = System::currentTimeMillis)` with `val logs: LogStore` and `val settings: SettingsStore`.
- Produces: `object SettingKeys { val SAFETY: SettingKey<SafetySettings> }`.
- Produces: `class AppGraph(context: Context)` with `scope: CoroutineScope`, `logs: LogStore`, `settings: SettingsStore`, `networkPolicy: NetworkPolicy` and `network: NetworkGateway`, reachable as `(context.applicationContext as KestrelApplication).graph`. Plans 1B and 1C add the screen layer and the settings app to it.

`PrivacyStore` keeps Room inside `:core:privacy`, so the app never sees Room's types. The app starts a purge of expired log entries when its process starts.

- [ ] **Step 1: Write the failing device test**

`app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`:

```kotlin
package dev.kestrel.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppGraphTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    @Test
    fun blockedRequestIsWrittenToTheEncryptedNetworkLog() = runBlocking {
        graph.logs.deleteHistory()
        val request = Request.Builder().url("https://tracker.example.net/collect").build()

        assertThrows(BlockedRequestException::class.java) { graph.network.callFactory.newCall(request).execute() }

        val entry = withTimeout(5_000) {
            var entries = graph.logs.network()
            while (entries.isEmpty()) {
                delay(50)
                entries = graph.logs.network()
            }
            entries.single()
        }
        assertEquals(NetworkOutcome.BLOCKED, entry.event.outcome)
        assertEquals("tracker.example.net", entry.event.host)
    }

    @Test
    fun safetySettingsSurviveTheEncryptedStore() = runBlocking {
        val original = graph.settings.get(SettingKeys.SAFETY)
        try {
            graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = false))

            assertEquals(SafetySettings(testMode = false), graph.settings.get(SettingKeys.SAFETY))
        } finally {
            graph.settings.set(SettingKeys.SAFETY, original)
        }
    }
}
```

`app/build.gradle.kts` (final version):

```kotlin
plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kestrel.app"
    defaultConfig {
        applicationId = "dev.kestrel"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:privacy"))
    implementation(project(":core:safety"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD FAILED`, with unresolved references to `KestrelApplication` and `SettingKeys`.

- [ ] **Step 3: Write the storage entry point and the app wiring**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt`:

```kotlin
package dev.kestrel.privacy

import android.content.Context
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.LogStore
import dev.kestrel.privacy.settings.SettingsStore

/** Kestrel's encrypted storage: the activity log, the network log and the settings (design spec, section 13.4). */
class PrivacyStore(context: Context, clock: () -> Long = System::currentTimeMillis) {
    private val dataKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.DATA_KEY) }
    private val cipher = AesGcmFieldCipher { dataKey }
    private val database = KestrelDatabase.create(context)

    val logs = LogStore(database.logDao(), cipher, clock)
    val settings = SettingsStore(database.settingsDao(), cipher)
}
```

`app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`:

```kotlin
package dev.kestrel.app

import android.content.Context
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}

/** Wires Kestrel's parts together by hand; there is no dependency-injection framework (design spec, section 17). */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()
    val network = NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } }
}
```

`app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`:

```kotlin
package dev.kestrel.app

import android.app.Application
import kotlinx.coroutines.launch

class KestrelApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch { graph.logs.purgeExpired() }
    }
}
```

`app/src/main/AndroidManifest.xml` (final version; `android:name` is new):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:name=".KestrelApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="Kestrel"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

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

- [ ] **Step 4: Run the test to verify that it passes**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 2 tests passed on `kestrel-api37`.

- [ ] **Step 5: Run lint and the audit**

Run: `scripts\gradle.ps1 check kestrelAudit`

Expected: `BUILD SUCCESSFUL`. `app/build/reports/kestrel-audit/dependencies-release.txt` now also lists `androidx.room`, `androidx.sqlite`, `com.squareup.okhttp3` and `com.squareup.okio` libraries and ends with `All dependencies passed.` `app/build/reports/kestrel-audit/network-apis-release.txt` starts with `No network API use outside the exceptions.`

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "feat(app): wire encrypted storage, allowlist and network gateway into AppGraph" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 14: Third-party notices and final verification

**Files:**
- Create: `THIRD_PARTY_NOTICES.md`
- Modify: `README.md` (link to the notices)

**Interfaces:**
- Consumes: the dependency report from Task 13.
- Produces: a green build on `kestrel-api37` and in CI; plan 1A is complete.

- [ ] **Step 1: List the dependencies that ship**

Run: `scripts\gradle.ps1 :app:kestrelAudit`, then open `app/build/reports/kestrel-audit/dependencies-release.txt`.

Expected: every listed group belongs to one of the rows of the table in Step 2. If a group is missing from the table, add a row for it, with its licence from its project page.

- [ ] **Step 2: Write the notices**

`THIRD_PARTY_NOTICES.md`:

```markdown
# Third-party notices

Kestrel's release build includes the libraries below. All of them use the Apache License 2.0, which is compatible with Kestrel's licence, GPL-3.0-or-later. The exact versions are in `app/build/reports/kestrel-audit/dependencies-release.txt` after `scripts\gradle.ps1 kestrelAudit` runs.

| Libraries | Licence | Project |
|---|---|---|
| AndroidX, including Jetpack Compose, Room, Activity, Core, Lifecycle and Startup (`androidx.*`) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library (`org.jetbrains.kotlin`) | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines and kotlinx.serialization (`org.jetbrains.kotlinx`) | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization |
| JetBrains Java Annotations (`org.jetbrains:annotations`) | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| OkHttp (`com.squareup.okhttp3`) | Apache-2.0 | https://github.com/square/okhttp |
| Okio (`com.squareup.okio`) | Apache-2.0 | https://github.com/square/okio |
| Guava ListenableFuture (`com.google.guava:listenablefuture`) | Apache-2.0 | https://github.com/google/guava |
| JSpecify (`org.jspecify:jspecify`) | Apache-2.0 | https://github.com/jspecify/jspecify |

The text of the Apache License 2.0 is at https://www.apache.org/licenses/LICENSE-2.0.

Later sub-projects add speech models, voices, and code and data derived from OpenClaw and android_world. Their notices are added here when they arrive.
```

In `README.md`, replace the `## Licence` section with:

```markdown
## Licence

Kestrel is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE). The licences of the libraries that Kestrel uses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
```

- [ ] **Step 3: Run every check**

```powershell
scripts\gradle.ps1 :build-logic:convention:check check kestrelAudit
scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
git status --porcelain
```

Expected: both Gradle runs end with `BUILD SUCCESSFUL`. The unit tests total 23 in `build-logic`, 2 in `:core:safety` and 29 in `:core:privacy`; the device tests total 4 in `:core:privacy` and 2 in `:app`. `git status --porcelain` lists only `THIRD_PARTY_NOTICES.md` and `README.md`.

- [ ] **Step 4: Commit and push**

```powershell
git add -A
git commit -m "docs: add third-party notices" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git push
```

- [ ] **Step 5: Watch CI**

```powershell
Start-Sleep -Seconds 10
$run = gh run list --limit 1 --json databaseId | ConvertFrom-Json
gh run watch $run[0].databaseId --exit-status
```

Expected: the `CI` run is green. Plan 1A is complete; the next step is to write plan 1B (screen layer).
