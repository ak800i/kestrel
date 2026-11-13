# Kestrel Foundation 1B: Screen Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Kestrel's screen layer: the accessibility service, screen snapshots, every action with its fallbacks, all overlays including the touch watcher, the `:testapp` fixture screens with instrumented tests, and the adb test hooks. Later sub-projects use this layer to read and operate any app.

**Architecture:** A new module, `:core:screen`, holds the screen layer. Its model, snapshot builder, numbering and grid are pure Kotlin with JVM unit tests. Thin adapters connect them to Android's accessibility APIs. `ScreenSession` joins the reader, the change tracker, the actions and the overlays for one connection of `KestrelAccessibilityService`. The service lives in `:app` and hands its session to `AppGraph`. Overlays are plain Views in accessibility overlay windows. The device tests drive the fixture screens of a separate app, `:testapp`, on `kestrel-api37`.

**Tech Stack:** As in plan 1A: Gradle 9.9.1, the Android Gradle plugin 9.5.1 with built-in Kotlin 2.4.21, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.13.0, Jetpack Compose (BOM 2026.09.00) and JUnit 4. New in this plan: Android's accessibility service APIs (API 33 and later), AndroidX Test for the instrumented tests, and Android's `uinput` shell tool, which the tests use to touch the screen like a finger.

**Spec:** [2026-11-12-kestrel-design.md](../specs/2026-11-12-kestrel-design.md). This is plan 1B of sub-project 1, Foundation (spec section 18.1). It builds on plan 1A, [2026-11-12-foundation-1a-build-audit-privacy.md](2026-11-12-foundation-1a-build-audit-privacy.md). Plan 1C (the settings app, spikes S1, S4 and S7, and the checks on the A71) is written after this plan is done. Two fixtures from spec section 16.3 come later, with the code that uses them: the OCR tests on fixture screenshots with `look_closer` in sub-project 5, and the injection screen with the injection suite in sub-project 6.

## Global Constraints

- Package and application ID `dev.kestrel`; repository `H:\Code\kestrel`, published as `github.com/ak800i/kestrel` (D21).
- Licence GPL-3.0-or-later for all of Kestrel; every dependency that ships in the app must have a licence that is compatible with GPL-3.0, such as Apache-2.0, MIT or BSD. Tools used only to build or test Kestrel, such as JUnit, are exempt (D28, section 17).
- `minSdk 33`, `targetSdk 37` and `compileSdk 37` (section 6).
- Gradle with the Kotlin DSL and a version catalog; Android Gradle plugin 9.5.1 and Kotlin 2.4.21 (section 17). The plugin has Kotlin built in: never apply `org.jetbrains.kotlin.android`.
- There is no dependency-injection framework; one `AppGraph` class wires the parts together by hand (section 17).
- Kestrel has no WebView, no analytics, no crash reporting and no ads, and it does not use Google Play services, Firebase, ML Kit or Android's geocoder (section 13.1). `:testapp` is never shipped, so a fixture may use a WebView.
- All network traffic from Kotlin code goes through one OkHttp client in `:core:privacy` (section 13.1). The screen layer makes no network calls.
- Screenshots, audio and credentials are never logged (section 13.5). Errors go to logcat only as class names and stack frames, never with their messages, because a message can contain screen text.
- Accessibility service: all event types (`typeAllMask`); include views that are not important for accessibility, report view IDs, retrieve interactive windows, request the accessibility button, and act as an input method; retrieve window content, perform gestures, take screenshots and control magnification; `isAccessibilityTool="true"` (section 9.1, D19).
- Snapshots: Kestrel reads all interactive windows and ignores its own overlay windows. Each element gets a stable key built from the window's package, the view ID, the label and the element's position among similar siblings. Snapshots are cached and rebuilt after the screen has been quiet for about 150 ms (section 9.2).
- A text field is never named by the text typed into it, and its key uses only its own description or hint, so that the key stays the same while the owner types. While an empty field shows its hint, Kestrel treats the field as empty (sections 9.2 and 9.7).
- Actions: tap with the element's click action or its nearest clickable parent's, falling back to a gesture at the element's centre; type with set-text, falling back to the accessibility input method; scroll with scroll actions, falling back to swipes; long-press with the long-click action, falling back to a gesture; drag, pinch and zoom with gestures and the magnification controller; global actions; apps and links with intents. An action looks up its element in the current snapshot, and Kestrel never taps, long-presses or types into a disabled element. Scrolling uses forward and backward only along a list's known axis, its swipe goes across the list, and it never swipes sideways across a list that moves up and down. After each action, Kestrel waits until the window events have been quiet for 150–300 ms, but never longer than 3 s (section 9.3).
- If an app rejects set-text, or the text does not read back correctly, Kestrel uses the accessibility input method for that app from then on. A field that is disabled or has gone does not count as a rejection (section 9.7).
- All overlays are accessibility overlay windows, so Kestrel needs no "display over other apps" permission (section 9.4). The feedback bar never receives touches (section 8.9). The confirmation card's Confirm and Cancel buttons accept touches (section 12.4).
- The touch watcher reports the owner's touches, and Kestrel ignores the touches that it injects itself (section 14.1).
- Test hooks: debug builds contain a broadcast receiver that accepts commands only from callers that hold `android.permission.DUMP`, which in practice means `adb shell`. Release builds do not contain it (section 16.1).
- Instrumented tests run on `kestrel-api37`, and on the A71 before each release. They use the fixture screens in `:testapp` (section 16.3).
- Test mode is on during all testing (D25).
- Testing happens only on the `kestrel-api37` emulator (Android 17, API 37) and the Galaxy A71. Never test on the Galaxy S23+ unless the owner asks, and never change the existing AVD `zoran` (D20, section 6).
- The Android SDK is in `D:\AndroidVMs\SDK`. Scripts are PowerShell, and environment changes stay inside the process that makes them (section 17).

## How to work in this plan

- Run every command in PowerShell from the repository root, `H:\Code\kestrel`.
- Run Gradle only through `scripts\gradle.ps1`, and on-device tests only through `scripts\connected-test.ps1`. Both come from plan 1A. `connected-test.ps1` refuses every device except `kestrel-api37` and the Galaxy A71.
- `scripts\start-emulator.ps1` starts `kestrel-api37` when it is not running and prints its adb serial, for example `emulator-5554`. The commands below pass that serial with `-Serial (scripts\start-emulator.ps1)`. If your shell stops child processes when a command ends, start the emulator once from a separate terminal that stays open.
- To run one test class on the device, add `'-Pandroid.testInstrumentationRunnerArguments.class=<class name>'` after the Gradle task. The results are in `app/build/outputs/androidTest-results/connected/debug/TEST-*.xml`, and a failure prints `There were failing tests` with a link to the HTML report.
- After the device tests, Gradle uninstalls Kestrel and its test APK. `:testapp` stays installed.
- If the device tests become slow, or Android restarts while they run ("System has crashed", or a watchdog in logcat), restart the emulator: run `adb -s <serial> emu kill`, then start it again. Restarting Android inside the emulator is not enough. Do not run device tests in the first two minutes after the emulator boots, while Android is still busy.
- A passing Gradle command ends with `BUILD SUCCESSFUL`. A failing one ends with `BUILD FAILED` and prints the reason above it.
- Commit at the end of every task. Use a Conventional Commits subject and end every commit message with this trailer line:

  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

  In PowerShell: `git commit -m "<subject>" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"`.

## Things that surprised us while building this plan

The code below already handles all of these. They are written down so that nobody "fixes" them back.

- Platform buttons show their text in capitals (`textAllCaps`), so accessibility services read "Send" as "SEND".
- Android reports the hint of an empty text field as the field's text, and marks the node with `isShowingHintText`. A Compose text field shows its label as a text inside the field instead of as a hint. Its placeholder, its character counter and a clear button are texts inside the field too, and they change while the owner types.
- A clickable Compose button takes the text of its child as its own label, so the button and its text share a label. Tests match `clickable && label == ...`.
- `am start -W -n` may only bring the fixture app's existing task to the front. The tests use `am start -W -S`, which stops the app first.
- Android ends a continued gesture stroke at its last change of position: a hold that does not move ends at once. The hold of a drag therefore drifts by 10 pixels.
- UiAutomation must be connected with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, or it turns off every accessibility service, Kestrel's included.
- UiAutomation's list of windows contains Kestrel's touchable overlays, such as the confirmation card, but not the overlays that take no touches. Tests find those in `dumpsys window windows` by their titles.
- A new overlay window gets touches only after Android's input dispatcher lists it, a moment after it was added.
- Touches from `adb shell input` come from a virtual device and skip Android's accessibility input filter. A virtual touchscreen made with the `uinput` tool behaves like a finger. The tool on Android 13 reads only numbers, has no `sync` command, and ends its replies without a line break.
- In `am broadcast -n dev.kestrel/.x.Y`, the short class name is relative to the package `dev.kestrel`, not to the namespace `dev.kestrel.app`, so the hooks use the full class name.
- Android lets an app use its own components even when they require a permission that the app lacks. A test that "another app is refused" must therefore send from another app.
- AndroidX's `ProfileInstallReceiver` also requires `android.permission.DUMP`, so the release check looks only at Kestrel's own components.
- After many hours, the emulator's host-side rendering slows down: Android's graphics composer spends most of its time in the kernel, apps start in 3 s instead of 1 s, and Android's watchdog may restart the system. Restarting the emulator process fixes it.
- The emulator process once closed during the zoom test, while its rendering was slow in this way. The Windows Application log showed a timeout in the NVIDIA graphics driver (a TDR). After a fresh start, all the device tests passed. If the emulator closes during the tests, start it again and run the tests again.

## File map

| Path | Responsibility |
|---|---|
| `core/screen/.../model/` | The snapshot model (`ScreenSnapshot`, `ScreenElement`, `WindowSummary`, `IntRect`) and its JSON form |
| `core/screen/.../read/` | Turning windows and nodes into snapshots: labels, keys, the change tracker, the snapshot cache and the Android adapters |
| `core/screen/.../act/` | Actions and their fallbacks: taps, typing, scrolling, gestures, zoom, global actions and intents |
| `core/screen/.../overlay/` | The overlays: numbers, labels, grid, feedback bar, confirmation card and touch watcher |
| `core/screen/.../ScreenSession.kt` | One connection of the accessibility service: snapshots, actions and overlays together |
| `app/.../KestrelAccessibilityService.kt` | The accessibility service; it creates and closes the `ScreenSession` |
| `app/src/main/res/xml/accessibility_service_config.xml` | The service's event types, flags and capabilities |
| `app/.../AppGraph.kt` | Hands the current `ScreenSession` to the rest of the app |
| `app/src/debug/` | The test hooks, which only debug builds contain |
| `app/src/androidTest/` | The instrumented tests, their rule (`ScreenServiceRule`) and the virtual finger (`TestFinger`) |
| `testapp/` | The fixture screens that the instrumented tests drive; never shipped |
| `build-logic/.../AndroidTestAppConventionPlugin.kt` | The build settings of `:testapp`, without the audit |
| `build-logic/.../audit/ManifestScanner.kt`, `CheckManifestTask.kt` | The audit check that release builds contain no test hooks |
| `scripts/hook.ps1` | Sends test-hook commands over adb |

---

### Task 1: The screen model

**Files:**
- Create: `core/screen/build.gradle.kts`
- Modify: `settings.gradle.kts` (include `:core:screen`)
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/model/IntRect.kt`, `ScreenSnapshot.kt`, `ScreenJson.kt`
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/model/ScreenModelTest.kt`

**Interfaces:**
- Consumes: the plugin `kestrel.android.library` and the catalog aliases `libs.kotlinx.serialization.json` and `libs.junit` from plan 1A.
- Produces: `data class IntRect(left, top, right, bottom)` with `width`, `height`, `centerX`, `centerY`, `isEmpty` and `intersect(other): IntRect?`.
- Produces: `enum class WindowKind { APPLICATION, INPUT_METHOD, SYSTEM, SPLIT_SCREEN_DIVIDER, OTHER }`, `data class WindowSummary(id, kind, packageName, title, bounds, layer, active, focused)`, `enum class ElementAction`, `data class ScreenElement(key, windowId, parent, packageName, className, text, contentDescription, hint, stateDescription, viewId, label, suggestedName, bounds, actions, enabled, clickable, longClickable, editable, checkable, checked, scrollable, focused, selected, password, dataSensitive)` with `isActionable`, and `data class ScreenSnapshot(takenAtMillis, windows, elements)` with `element(key): ScreenElement?`. All are `@Serializable`.
- Produces: `object ScreenJson` with `encode(snapshot): String` and `decode(text): ScreenSnapshot`.

The model is plain data, so that the agent, routines and tests can use snapshots without Android. The JSON form leaves out default values, which keeps the snapshots that later go to the LLM short.

- [ ] **Step 1: Create the module**

`core/screen/build.gradle.kts`:

```kotlin
plugins {
    id("kestrel.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.screen"
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

Replace the whole content of `settings.gradle.kts` with:

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
include(":core:screen")
include(":core:safety")
```

- [ ] **Step 2: Write the failing test**

`core/screen/src/test/kotlin/dev/kestrel/screen/model/ScreenModelTest.kt`:

```kotlin
package dev.kestrel.screen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    private val bounds = IntRect(0, 0, 100, 50)

    @Test
    fun intersectReturnsTheOverlapOrNull() {
        assertEquals(IntRect(50, 20, 100, 50), bounds.intersect(IntRect(50, 20, 300, 300)))
        assertNull(bounds.intersect(IntRect(100, 0, 200, 50)))
    }

    @Test
    fun onlyEnabledElementsThatCanBeTappedTypedOrCheckedAreActionable() {
        val element = ScreenElement(key = "k", windowId = 1, bounds = bounds)

        assertFalse(element.isActionable)
        assertFalse(element.copy(scrollable = true).isActionable)
        assertTrue(element.copy(clickable = true).isActionable)
        assertTrue(element.copy(editable = true).isActionable)
        assertTrue(element.copy(checkable = true).isActionable)
        assertFalse(element.copy(clickable = true, enabled = false).isActionable)
    }

    @Test
    fun jsonLeavesOutDefaultsAndReadsBackTheSameSnapshot() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 42,
            windows = listOf(WindowSummary(id = 7, kind = WindowKind.APPLICATION, packageName = "dev.app", bounds = bounds)),
            elements = listOf(
                ScreenElement(
                    key = "dev.app|send|Send|Button|0",
                    windowId = 7,
                    label = "Send",
                    bounds = bounds,
                    actions = setOf(ElementAction.CLICK),
                    clickable = true,
                ),
            ),
        )

        val json = ScreenJson.encode(snapshot)

        assertFalse("defaults are left out: $json", json.contains("\"password\""))
        assertFalse("nulls are left out: $json", json.contains("null"))
        assertEquals(snapshot, ScreenJson.decode(json))
    }
}
```

- [ ] **Step 3: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'IntRect'` from `:core:screen:compileDebugUnitTestKotlin`.

- [ ] **Step 4: Write the model**

`core/screen/src/main/kotlin/dev/kestrel/screen/model/IntRect.kt`:

```kotlin
package dev.kestrel.screen.model

import kotlinx.serialization.Serializable

/** A rectangle in screen pixels. As in Android's `Rect`, [right] and [bottom] lie just outside it. */
@Serializable
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** The part of this rectangle that lies inside [other], or null when they do not overlap. */
    fun intersect(other: IntRect): IntRect? {
        val overlap = IntRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
        return overlap.takeUnless { it.isEmpty }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/model/ScreenSnapshot.kt`:

```kotlin
package dev.kestrel.screen.model

import kotlinx.serialization.Serializable

/** The kind of window that an element comes from. Snapshots never contain Kestrel's own overlays. */
@Serializable
enum class WindowKind { APPLICATION, INPUT_METHOD, SYSTEM, SPLIT_SCREEN_DIVIDER, OTHER }

@Serializable
data class WindowSummary(
    val id: Int,
    val kind: WindowKind,
    val packageName: String? = null,
    val title: String? = null,
    val bounds: IntRect,
    val layer: Int = 0,
    val active: Boolean = false,
    val focused: Boolean = false,
)

/** The standard accessibility actions that an element offers (design spec, sections 9.2 and 9.3). */
@Serializable
enum class ElementAction {
    CLICK,
    LONG_CLICK,
    FOCUS,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    EXPAND,
    COLLAPSE,
    DISMISS,
}

/**
 * One element of a [ScreenSnapshot] (design spec, section 9.2). Its [key] stays the same as long as the
 * window's package, the view ID, the label and the element's position among similar siblings stay the same.
 */
@Serializable
data class ScreenElement(
    val key: String,
    val windowId: Int,
    /** The index in [ScreenSnapshot.elements] of the nearest ancestor that is also an element. */
    val parent: Int? = null,
    val packageName: String? = null,
    val className: String? = null,
    /** Always null for password fields. */
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val stateDescription: String? = null,
    val viewId: String? = null,
    /** The name that Kestrel uses for the element: its text, description or hint, or the text inside it. */
    val label: String? = null,
    /** For an element without a [label], a name derived from its view ID, for example "compose" for `compose_button`. */
    val suggestedName: String? = null,
    val bounds: IntRect,
    val actions: Set<ElementAction> = emptySet(),
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val password: Boolean = false,
    val dataSensitive: Boolean = false,
) {
    /** Whether the owner can act on the element directly. Numbers go on these elements (design spec, section 9.4). */
    val isActionable: Boolean get() = enabled && (clickable || longClickable || editable || checkable)
}

/** The interactive windows on the screen and their elements, top window first (design spec, section 9.2). */
@Serializable
data class ScreenSnapshot(
    val takenAtMillis: Long,
    val windows: List<WindowSummary>,
    val elements: List<ScreenElement>,
) {
    fun element(key: String): ScreenElement? = elements.firstOrNull { it.key == key }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/model/ScreenJson.kt`:

```kotlin
package dev.kestrel.screen.model

import kotlinx.serialization.json.Json

/** The JSON form of a [ScreenSnapshot]. Fields that have their default value are left out. */
object ScreenJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: ScreenSnapshot): String = json.encodeToString(ScreenSnapshot.serializer(), snapshot)

    fun decode(text: String): ScreenSnapshot = json.decodeFromString(ScreenSnapshot.serializer(), text)
}
```

- [ ] **Step 5: Run the tests and the module's checks**

Run: `scripts\gradle.ps1 :core:screen:check`

Expected: `BUILD SUCCESSFUL`. `core/screen/build/test-results/testDebugUnitTest/TEST-dev.kestrel.screen.model.ScreenModelTest.xml` reports `tests="3" failures="0" errors="0"`. The convention plugin applies the privacy audit, so `check` also runs `kestrelAudit` for the module.

- [ ] **Step 6: Commit**

```powershell
git add settings.gradle.kts core/screen
git commit -m "feat(screen): add the screen model and its JSON form" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Building snapshots from windows and nodes

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/read/Labels.kt`, `NodeData.kt`, `SnapshotBuilder.kt`
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/read/FakeNodes.kt` (test helper), `LabelsTest.kt`, `SnapshotBuilderTest.kt`

**Interfaces:**
- Consumes: the model from Task 1.
- Produces: `object Labels` with `clean(text: CharSequence?): String?`, `join(pieces: List<String>): String?` (at most 80 characters), `shorten(text: String, maxLength: Int): String` and `suggestedName(viewId: String?): String?`.
- Produces: `interface NodeData` (the node's texts, view ID, bounds, visibility, states, `actions: Set<ElementAction>`, `childCount` and `child(index)`) and `interface WindowData` (`id`, `kind`, `isOverlay`, `title`, `bounds`, `layer`, `active`, `focused`, `root`).
- Produces: `class SnapshotBuilder(clock: () -> Long = System::currentTimeMillis)` with `build(windows: List<WindowData>): SnapshotBuilder.Result`, where `Result(snapshot: ScreenSnapshot, nodes: Map<String, NodeData>)` maps each key back to its node.

The builder works on two small interfaces instead of Android's classes, so that all its rules have fast JVM tests. Task 5 adds the adapters for Android's `AccessibilityNodeInfo` and `AccessibilityWindowInfo`. The rules:

- Kestrel's own overlay windows are left out, and windows are ordered from the top layer down.
- Invisible nodes and their subtrees are left out, and bounds are clipped to the window.
- A node becomes an element when it is actionable or scrollable, or has a label or a state description. Layouts without either are left out.
- Password text never appears; the hint names the field.
- A text field is named by its description or hint, or else by the texts inside it (Compose shows a text field's label that way), never by what was typed into it. Its key uses only its own description or hint, because the texts inside a field, such as a placeholder or a character counter, can change while the owner types. Routines, retries and the undo history of spec section 9.7 rely on keys that stay the same.
- An actionable element without its own label gets up to three texts of its descendants as its label, so a tappable row is named after its text.
- An element without a label gets a suggested name from its view ID, such as "compose" for `compose_button`.
- Keys are `package|view ID name|label (40 characters)|class|position among similar siblings`. A key that still repeats gets `~2`, `~3` and so on. Labels are cut without splitting a character that takes two UTF-16 units, such as most emoji, because half a character would not survive saving, and a saved key would no longer match.

- [ ] **Step 1: Write the failing tests**

`core/screen/src/test/kotlin/dev/kestrel/screen/read/FakeNodes.kt`:

```kotlin
package dev.kestrel.screen.read

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind

/** A node for tests on the PC. Each property has the value of a plain, visible, enabled view. */
class FakeNode(
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val hint: String? = null,
    override val stateDescription: String? = null,
    override val viewId: String? = null,
    override val className: String? = "android.view.View",
    override val packageName: String? = "dev.app",
    override val bounds: IntRect = IntRect(0, 0, 100, 100),
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val clickable: Boolean = false,
    override val longClickable: Boolean = false,
    override val editable: Boolean = false,
    override val checkable: Boolean = false,
    override val checked: Boolean = false,
    override val scrollable: Boolean = false,
    override val focused: Boolean = false,
    override val selected: Boolean = false,
    override val password: Boolean = false,
    override val dataSensitive: Boolean = false,
    override val actions: Set<ElementAction> = emptySet(),
    val children: List<FakeNode> = emptyList(),
) : NodeData {
    override val childCount: Int get() = children.size

    override fun child(index: Int): NodeData = children[index]
}

class FakeWindow(
    override val root: NodeData?,
    override val id: Int = 1,
    override val kind: WindowKind = WindowKind.APPLICATION,
    override val isOverlay: Boolean = false,
    override val title: String? = null,
    override val bounds: IntRect = IntRect(0, 0, 1080, 2400),
    override val layer: Int = 0,
    override val active: Boolean = true,
    override val focused: Boolean = true,
) : WindowData
```

`core/screen/src/test/kotlin/dev/kestrel/screen/read/LabelsTest.kt`:

```kotlin
package dev.kestrel.screen.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelsTest {
    @Test
    fun suggestedNamesDropGenericWordsFromTheViewId() {
        assertEquals("compose", Labels.suggestedName("dev.app:id/compose_button"))
        assertEquals("send message", Labels.suggestedName("dev.app:id/btnSendMessage"))
        assertEquals("search", Labels.suggestedName("dev.app:id/ic_search_24"))
        assertNull(Labels.suggestedName("dev.app:id/fab"))
        assertNull(Labels.suggestedName(null))
    }

    @Test
    fun cleanCollapsesWhitespaceAndJoinKeepsAtMostEightyCharacters() {
        assertEquals("Send now", Labels.clean("  Send \n now "))
        assertNull(Labels.clean(" \t "))
        assertEquals(80, Labels.join(listOf("a".repeat(50), "b".repeat(50)))!!.length)
    }

    @Test
    fun shortenNeverSplitsAnEmoji() {
        val emoji = "\uD83D\uDE00"

        assertEquals("ab", Labels.shorten("ab${emoji}c", 3))
        assertEquals("ab$emoji", Labels.shorten("ab${emoji}c", 4))
        assertEquals("abc", Labels.shorten("abc", 5))
        assertEquals("a".repeat(79), Labels.join(listOf("a".repeat(79) + emoji)))
    }
}
```

`core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotBuilderTest.kt`:

```kotlin
package dev.kestrel.screen.read

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SnapshotBuilderTest {
    private val builder = SnapshotBuilder(clock = { 1_000L })

    private fun build(vararg windows: FakeWindow) = builder.build(windows.toList())

    @Test
    fun layoutsWithoutTextOrActionsAreLeftOutAndATappableRowIsNamedAfterItsText() {
        val row = FakeNode(
            clickable = true,
            className = "android.widget.LinearLayout",
            children = listOf(
                FakeNode(text = "Ana Petrović", className = "android.widget.TextView"),
                FakeNode(text = "Online", className = "android.widget.TextView"),
            ),
        )

        val elements = build(FakeWindow(FakeNode(className = "android.widget.FrameLayout", children = listOf(row)))).snapshot.elements

        assertEquals(listOf("Ana Petrović Online", "Ana Petrović", "Online"), elements.map { it.label })
        assertEquals(listOf(null, 0, 0), elements.map { it.parent })
        assertNull("the row's own text", elements[0].text)
    }

    @Test
    fun passwordTextNeverAppearsAndTheHintNamesTheField() {
        val field = FakeNode(text = "hunter2", hint = "Password", editable = true, password = true)

        val snapshot = build(FakeWindow(field)).snapshot

        assertNull(snapshot.elements.single().text)
        assertEquals("Password", snapshot.elements.single().label)
        assertFalse(ScreenJson.encode(snapshot).contains("hunter2"))
    }

    @Test
    fun aTextFieldKeepsItsNameAndItsKeyWhileTheOwnerTypes() {
        fun field(text: String?) = FakeNode(text = text, hint = "Message", className = "android.widget.EditText", editable = true)

        // Compose shows a text field's label, placeholder and character counter as texts inside the field.
        fun composeField(text: String?, vararg inside: String) =
            FakeNode(text = text, className = "android.widget.EditText", editable = true, children = inside.map { FakeNode(text = it) })

        fun element(node: FakeNode) = build(FakeWindow(node)).snapshot.elements.first()

        val typed = element(field("Hello Ana"))

        assertEquals("Message", typed.label)
        assertEquals("Hello Ana", typed.text)
        assertEquals(element(field(null)).key, typed.key)
        assertEquals("Name", element(composeField("Ana", "Name")).label)
        assertEquals(element(composeField(null, "Type a message", "0/160")).key, element(composeField("Hello", "Clear", "5/160")).key)
    }

    @Test
    fun anUnlabeledButtonGetsANameFromItsViewId() {
        val button = FakeNode(viewId = "dev.app:id/compose_button", className = "android.widget.ImageButton", clickable = true)

        val element = build(FakeWindow(button)).snapshot.elements.single()

        assertNull(element.label)
        assertEquals("compose", element.suggestedName)
        assertEquals("dev.app|compose_button||ImageButton|0", element.key)
    }

    @Test
    fun similarSiblingsAreNumberedAndRepeatedKeysGetASuffix() {
        fun item() = FakeNode(text = "Item", viewId = "dev.app:id/row", className = "android.widget.TextView")
        val list = FakeNode(scrollable = true, className = "android.widget.ListView", children = listOf(item(), item()))
        val dialog = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(contentDescription = "Group", children = listOf(FakeNode(text = "OK", className = "android.widget.Button"))),
                FakeNode(contentDescription = "Group", children = listOf(FakeNode(text = "OK", className = "android.widget.Button"))),
            ),
        )

        val keys = build(FakeWindow(FakeNode(children = listOf(list, dialog)))).snapshot.elements.map { it.key }

        assertEquals(
            listOf(
                "dev.app|||ListView|0",
                "dev.app|row|Item|TextView|0",
                "dev.app|row|Item|TextView|1",
                "dev.app||Group|View|0",
                "dev.app||OK|Button|0",
                "dev.app||Group|View|1",
                "dev.app||OK|Button|0~2",
            ),
            keys,
        )
    }

    @Test
    fun overlaysAreLeftOutAndTheTopWindowComesFirst() {
        val snapshot = build(
            FakeWindow(FakeNode(text = "App"), id = 1, layer = 1),
            FakeWindow(FakeNode(text = "Number badge", packageName = "dev.kestrel"), id = 2, layer = 9, isOverlay = true),
            FakeWindow(FakeNode(text = "Clock", packageName = "com.android.systemui"), id = 3, layer = 5, kind = WindowKind.SYSTEM),
        ).snapshot

        assertEquals(listOf(3, 1), snapshot.windows.map { it.id })
        assertEquals(listOf("Clock", "App"), snapshot.elements.map { it.label })
        assertEquals("com.android.systemui", snapshot.windows.first().packageName)
    }

    @Test
    fun invisibleSubtreesAndOffscreenNodesAreLeftOutAndBoundsAreClippedToTheWindow() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "Hidden", visible = false, children = listOf(FakeNode(text = "Hidden child"))),
                FakeNode(text = "Below the window", bounds = IntRect(0, 3000, 100, 3100)),
                FakeNode(text = "Partly visible", bounds = IntRect(-50, 2350, 100, 2450)),
            ),
        )

        val elements = build(FakeWindow(root)).snapshot.elements

        assertEquals(listOf("Partly visible"), elements.map { it.label })
        assertEquals(IntRect(0, 2350, 100, 2400), elements.single().bounds)
    }

    @Test
    fun eachKeyLeadsBackToItsNode() {
        val button = FakeNode(text = "Send", clickable = true)

        val result = build(FakeWindow(FakeNode(children = listOf(button))))

        assertSame(button, result.nodes.getValue(result.snapshot.elements.single().key))
        assertEquals(1_000L, result.snapshot.takenAtMillis)
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'NodeData'` from `:core:screen:compileDebugUnitTestKotlin`.

- [ ] **Step 3: Write the labels, the interfaces and the builder**

`core/screen/src/main/kotlin/dev/kestrel/screen/read/Labels.kt`:

```kotlin
package dev.kestrel.screen.read

/** How Kestrel names elements (design spec, sections 9.2 and 9.4). */
object Labels {
    private const val MAX_LENGTH = 80
    private val whitespace = Regex("\\s+")
    private val camelHump = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val genericWords = setOf(
        "btn", "button", "fab", "ib", "ic", "icon", "image", "img", "imageview", "iv", "layout", "text", "textview", "tv", "view",
    )

    /** Collapses runs of whitespace into one space, or returns null for blank text. */
    fun clean(text: CharSequence?): String? = text?.toString()?.replace(whitespace, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** Joins texts found inside an element into one label of at most 80 characters. */
    fun join(pieces: List<String>): String? = clean(pieces.joinToString(" "))?.let { shorten(it, MAX_LENGTH) }?.trimEnd()

    /**
     * Returns the first [maxLength] UTF-16 units of [text], or one fewer when the cut would split a character that
     * takes two units, such as most emoji. Half a character turns into "?" when the text is saved, and a saved key
     * would then no longer match.
     */
    fun shorten(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.substring(0, if (text[maxLength - 1].isHighSurrogate()) maxLength - 1 else maxLength)
    }

    /** Derives a name from a view ID: `dev.app:id/compose_button` becomes "compose", and `btnSendMessage` "send message". */
    fun suggestedName(viewId: String?): String? {
        val name = viewId?.substringAfter(":id/") ?: return null
        val words = name.split('_', '-', '.')
            .flatMap { it.split(camelHump) }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() && it !in genericWords && !it.all(Char::isDigit) }
        return words.joinToString(" ").ifEmpty { null }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/read/NodeData.kt`:

```kotlin
package dev.kestrel.screen.read

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind

/** What a snapshot reads from one accessibility node. Android's nodes and the tests' fake nodes provide it. */
interface NodeData {
    val packageName: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val hint: String?
    val stateDescription: String?
    val viewId: String?
    val bounds: IntRect
    val visible: Boolean
    val enabled: Boolean
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val scrollable: Boolean
    val focused: Boolean
    val selected: Boolean
    val password: Boolean
    val dataSensitive: Boolean
    val actions: Set<ElementAction>
    val childCount: Int

    fun child(index: Int): NodeData?
}

/** What a snapshot reads from one window. */
interface WindowData {
    val id: Int
    val kind: WindowKind
    /** Accessibility and magnification overlays, including Kestrel's own, are left out of snapshots (design spec, section 9.2). */
    val isOverlay: Boolean
    val title: String?
    val bounds: IntRect
    val layer: Int
    val active: Boolean
    val focused: Boolean
    val root: NodeData?
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotBuilder.kt`:

```kotlin
package dev.kestrel.screen.read

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowSummary

/** Builds a [ScreenSnapshot] from the windows on the screen (design spec, section 9.2). */
class SnapshotBuilder(private val clock: () -> Long = System::currentTimeMillis) {
    /** A snapshot, together with the node behind each element, so that actions can use the live nodes. */
    class Result(val snapshot: ScreenSnapshot, val nodes: Map<String, NodeData>)

    fun build(windows: List<WindowData>): Result {
        val drafts = mutableListOf<Draft>()
        val summaries = mutableListOf<WindowSummary>()
        for (window in windows.filterNot { it.isOverlay }.sortedByDescending { it.layer }) {
            val root = window.root
            summaries += WindowSummary(
                id = window.id,
                kind = window.kind,
                packageName = root?.packageName,
                title = window.title,
                bounds = window.bounds,
                layer = window.layer,
                active = window.active,
                focused = window.focused,
            )
            if (root != null) visit(root, window, depth = 0, parent = null, out = drafts)
        }
        val keys = Keys()
        val elements = drafts.map { it.toElement(keys.next(it)) }
        val nodes = elements.indices.associate { elements[it].key to drafts[it].node }
        return Result(ScreenSnapshot(clock(), summaries, elements), nodes)
    }

    /** Adds the elements of [node]'s subtree to [out], and returns up to three texts from the subtree, its own first. */
    private fun visit(node: NodeData, window: WindowData, depth: Int, parent: Int?, out: MutableList<Draft>): List<String> {
        if (!node.visible || depth > MAX_DEPTH || out.size >= MAX_ELEMENTS) return emptyList()
        val text = if (node.password) null else Labels.clean(node.text)
        val name = Labels.clean(node.contentDescription) ?: Labels.clean(node.hint)
        // A text field is named by its description or hint, never by what was typed into it.
        val ownLabel = if (node.editable) name else text ?: name
        val bounds = node.bounds.intersect(window.bounds)
        val actionable = node.clickable || node.longClickable || node.editable || node.checkable
        val described = ownLabel != null || Labels.clean(node.stateDescription) != null
        val index = if (bounds != null && (actionable || node.scrollable || described)) {
            out += Draft(node, window.id, parent, bounds, text, ownLabel)
            out.lastIndex
        } else {
            null
        }
        val pieces = mutableListOf<String>()
        ownLabel?.let(pieces::add)
        for (i in 0 until node.childCount) {
            val child = node.child(i) ?: continue
            for (piece in visit(child, window, depth + 1, index ?: parent, out)) {
                if (pieces.size < MAX_LABEL_PIECES) pieces += piece
            }
        }
        // A tappable row without text of its own is named after the text inside it, as in Voice Access.
        if (index != null && ownLabel == null && actionable) out[index].innerLabel = Labels.join(pieces)
        return pieces
    }

    private class Draft(
        val node: NodeData,
        val windowId: Int,
        val parent: Int?,
        val bounds: IntRect,
        val text: String?,
        val ownLabel: String?,
    ) {
        var innerLabel: String? = null
        val label: String? get() = ownLabel ?: innerLabel

        fun toElement(key: String) = ScreenElement(
            key = key,
            windowId = windowId,
            parent = parent,
            packageName = node.packageName,
            className = node.className,
            text = text,
            contentDescription = Labels.clean(node.contentDescription),
            hint = Labels.clean(node.hint),
            stateDescription = Labels.clean(node.stateDescription),
            viewId = node.viewId,
            label = label,
            suggestedName = if (label == null) Labels.suggestedName(node.viewId) else null,
            bounds = bounds,
            actions = node.actions,
            enabled = node.enabled,
            clickable = node.clickable,
            longClickable = node.longClickable,
            editable = node.editable,
            checkable = node.checkable,
            checked = node.checked,
            scrollable = node.scrollable,
            focused = node.focused,
            selected = node.selected,
            password = node.password,
            dataSensitive = node.dataSensitive,
        )
    }

    /** Builds keys from the window's package, the view ID, the label and the position among similar siblings. */
    private class Keys {
        private val siblings = mutableMapOf<Triple<Int, Int?, String>, Int>()
        private val used = mutableMapOf<String, Int>()

        fun next(draft: Draft): String {
            // A text field's key uses only its own description or hint, because the texts inside a field, such as
            // a placeholder or a character counter, can change while the owner types.
            val name = if (draft.node.editable) draft.ownLabel else draft.label
            val base = listOf(
                draft.node.packageName.orEmpty(),
                draft.node.viewId?.substringAfter(":id/").orEmpty(),
                name?.let { Labels.shorten(it, KEY_LABEL_LENGTH) }.orEmpty(),
                draft.node.className?.substringAfterLast('.').orEmpty(),
            ).joinToString("|")
            val group = Triple(draft.windowId, draft.parent, base)
            val position = siblings.getOrDefault(group, 0)
            siblings[group] = position + 1
            val key = "$base|$position"
            // Similar elements under different parents would get the same key, so later ones get a suffix.
            val seen = used.getOrDefault(key, 0) + 1
            used[key] = seen
            return if (seen == 1) key else "$key~$seen"
        }
    }

    private companion object {
        const val MAX_DEPTH = 100
        const val MAX_ELEMENTS = 2_000
        const val MAX_LABEL_PIECES = 3
        const val KEY_LABEL_LENGTH = 40
    }
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:screen:check`

Expected: `BUILD SUCCESSFUL`. The test results report `LabelsTest` with `tests="3"` and `SnapshotBuilderTest` with `tests="8"`, all without failures.

- [ ] **Step 5: Commit**

```powershell
git add core/screen
git commit -m "feat(screen): build snapshots with labels and stable keys" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The change tracker and the snapshot cache

**Files:**
- Modify: `core/screen/build.gradle.kts` (coroutines, and coroutines-test for the tests)
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/read/ScreenChangeTracker.kt`, `ScreenEvents.kt`, `SnapshotCache.kt`
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/read/ScreenChangeTrackerTest.kt`, `SnapshotCacheTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `class ScreenChangeTracker(clock: () -> Long)` with `version: StateFlow<Long>`, `onScreenChanged()` and `suspend awaitQuiet(quietMillis: Long, since: Long = Long.MIN_VALUE, maxWaitMillis: Long = MAX_WAIT_MILLIS): Boolean`, and the constants `SNAPSHOT_QUIET_MILLIS = 150`, `ACTION_QUIET_MILLIS = 200` and `MAX_WAIT_MILLIS = 3_000`.
- Produces: `object ScreenEvents` with `changesScreen(eventType: Int): Boolean`.
- Produces: `class SnapshotCache<T : Any>(tracker: ScreenChangeTracker, build: suspend () -> T)` with `suspend current(): T` and `invalidate()`.

Every window event that can change the screen raises the tracker's version. The cache builds a new snapshot only when the version has changed, and only after 150 ms without changes, so that Kestrel does not read a screen in the middle of an animation. `invalidate()` makes the next `current()` build a new snapshot. The cache counts these calls, so that a call that arrives while a build is running is not lost: that build's snapshot goes to its caller, but the cache does not keep it. `awaitQuiet` is also how an action waits for the screen to settle: 200 ms of quiet, counted from the end of the action, and never more than 3 s. The tests use virtual time, so they run instantly.

- [ ] **Step 1: Add the coroutine libraries**

Replace the whole content of `core/screen/build.gradle.kts` with:

```kotlin
plugins {
    id("kestrel.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.screen"
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write the failing tests**

`core/screen/src/test/kotlin/dev/kestrel/screen/read/ScreenChangeTrackerTest.kt`:

```kotlin
package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenChangeTrackerTest {
    @Test
    fun awaitQuietReturnsOnceTheScreenHasBeenQuietForTheQuietTime() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        launch {
            delay(100)
            tracker.onScreenChanged()
        }

        assertTrue(tracker.awaitQuiet(quietMillis = 200))
        assertEquals(300, testScheduler.currentTime)
    }

    @Test
    fun awaitQuietGivesUpAfterTheLongestWait() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val changes = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(50)
            }
        }

        assertFalse(tracker.awaitQuiet(quietMillis = 200))
        assertEquals(ScreenChangeTracker.MAX_WAIT_MILLIS, testScheduler.currentTime)
        changes.cancel()
    }

    @Test
    fun quietTimeCountsFromAnActionEvenWhenTheScreenWasAlreadyQuiet() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        delay(1_000)

        assertTrue(tracker.awaitQuiet(quietMillis = 200, since = testScheduler.currentTime))
        assertEquals(1_200, testScheduler.currentTime)
    }

    @Test
    fun onlyEventsThatCanChangeTheScreenCount() {
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        assertFalse(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER))
        assertFalse(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    }
}
```

`core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotCacheTest.kt`:

```kotlin
package dev.kestrel.screen.read

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotCacheTest {
    @Test
    fun reusesTheSnapshotUntilTheScreenChangesAndThenWaitsForAQuietScreen() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }

        assertEquals(1, cache.current())
        assertEquals(1, cache.current())
        val changedAt = testScheduler.currentTime
        tracker.onScreenChanged()

        assertEquals(2, cache.current())
        assertTrue(testScheduler.currentTime - changedAt >= ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
    }

    @Test
    fun aChangeDuringABuildMakesTheNextCallBuildAgain() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) {
            builds++
            if (builds == 1) tracker.onScreenChanged()
            builds
        }

        assertEquals(1, cache.current())
        assertEquals(2, cache.current())
    }

    @Test
    fun anInvalidateDuringABuildIsNotLost() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        lateinit var cache: SnapshotCache<Int>
        cache = SnapshotCache(tracker) {
            builds++
            // Another caller invalidates the cache while this snapshot is being built.
            if (builds == 1) cache.invalidate()
            builds
        }

        assertEquals(1, cache.current())
        assertEquals(2, cache.current())
    }

    @Test
    fun invalidateMakesTheNextCallBuildAgain() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }
        cache.current()

        cache.invalidate()

        assertEquals(2, cache.current())
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'ScreenChangeTracker'` from `:core:screen:compileDebugUnitTestKotlin`.

- [ ] **Step 4: Write the tracker, the event filter and the cache**

`core/screen/src/main/kotlin/dev/kestrel/screen/read/ScreenChangeTracker.kt`:

```kotlin
package dev.kestrel.screen.read

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Remembers when the screen last changed, so that Kestrel can wait until it is quiet (design spec, sections 9.2
 * and 9.3). [clock] counts milliseconds and never jumps, like `SystemClock.uptimeMillis`.
 */
class ScreenChangeTracker(private val clock: () -> Long) {
    private val versions = MutableStateFlow(0L)

    @Volatile
    private var lastChangeAt = clock()

    /** Counts the changes so far. A snapshot built at one version is stale at any later version. */
    val version: StateFlow<Long> = versions.asStateFlow()

    fun onScreenChanged() {
        lastChangeAt = clock()
        versions.update { it + 1 }
    }

    /**
     * Waits until the screen has had no change for [quietMillis], counted from [since] at the earliest, and
     * returns true. Returns false when that takes longer than [maxWaitMillis].
     */
    suspend fun awaitQuiet(quietMillis: Long, since: Long = Long.MIN_VALUE, maxWaitMillis: Long = MAX_WAIT_MILLIS): Boolean {
        val deadline = clock() + maxWaitMillis
        while (true) {
            val now = clock()
            val quietFor = now - maxOf(lastChangeAt, since)
            if (quietFor >= quietMillis) return true
            if (now >= deadline) return false
            delay(minOf(quietMillis - quietFor, deadline - now))
        }
    }

    companion object {
        /** How long the screen must be quiet before Kestrel builds a new snapshot. */
        const val SNAPSHOT_QUIET_MILLIS = 150L

        /** How long the screen must be quiet after an action. */
        const val ACTION_QUIET_MILLIS = 200L

        /** The longest wait for a quiet screen. */
        const val MAX_WAIT_MILLIS = 3_000L
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/read/ScreenEvents.kt`:

```kotlin
package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent

/** Which accessibility events mean that the screen may have changed. */
object ScreenEvents {
    private const val NOT_SCREEN_CHANGES =
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED or
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY or
            AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT or
            AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

    fun changesScreen(eventType: Int): Boolean = eventType and NOT_SCREEN_CHANGES == 0
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotCache.kt`:

```kotlin
package dev.kestrel.screen.read

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the last snapshot until the screen changes. After a change, it builds a new snapshot once the screen
 * has been quiet for [ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS] (design spec, section 9.2).
 */
class SnapshotCache<T : Any>(private val tracker: ScreenChangeTracker, private val build: suspend () -> T) {
    private val mutex = Mutex()
    private var cached: T? = null

    @Volatile
    private var cachedVersion = -1L

    // Counts the calls of invalidate(), so that a snapshot whose build was already running is not kept.
    private val invalidations = AtomicLong()

    suspend fun current(): T = mutex.withLock {
        cached?.takeIf { cachedVersion == tracker.version.value }?.let { return it }
        tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
        val version = tracker.version.value
        val invalidated = invalidations.get()
        build().also {
            cached = it
            cachedVersion = version
            if (invalidations.get() != invalidated) cachedVersion = -1L
        }
    }

    /**
     * Makes the next [current] call build a new snapshot, for example before an action is retried. This also holds
     * when another caller is building a snapshot at the same moment.
     */
    fun invalidate() {
        invalidations.incrementAndGet()
        cachedVersion = -1L
    }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:screen:check`

Expected: `BUILD SUCCESSFUL`. The test results report `ScreenChangeTrackerTest` with `tests="4"` and `SnapshotCacheTest` with `tests="4"`, all without failures.

- [ ] **Step 6: Commit**

```powershell
git add core/screen
git commit -m "feat(screen): rebuild snapshots only after the screen is quiet" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The fixture app `:testapp`

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidTestAppConventionPlugin.kt`
- Modify: `build-logic/convention/build.gradle.kts` (register `kestrel.android.testapp`)
- Modify: `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidApplicationConventionPlugin.kt` (a comment)
- Modify: `settings.gradle.kts` (include `:testapp`)
- Create: `testapp/build.gradle.kts`, `testapp/src/main/AndroidManifest.xml`, `testapp/src/main/res/values/ids.xml`
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/Fixtures.kt`, `FixturesActivity.kt`, `ViewsActivity.kt`, `ScrollActivity.kt`, `GesturesActivity.kt`, `ComposeActivity.kt`, `WebActivity.kt`, `SecureActivity.kt`

**Interfaces:**
- Consumes: `applyKestrelApplicationDefaults()` from plan 1A.
- Produces: the plugin ID `kestrel.android.testapp` and the app `dev.kestrel.testapp`, whose activities later tasks start with `am start -W -S -n dev.kestrel.testapp/.<Activity>`.
- Produces: these fixtures, with the labels that the tests look for. Platform buttons are read in capitals, so the tests look for "SEND", "SHOW DIALOG", "REBUILD", "SECRET ACTION" and "VIEWS".
  - `FixturesActivity` ("Kestrel fixtures"), the launcher, also opened by links with the scheme `kestrel-fixtures://`. It has a button for each fixture, starting with "Views". Its title is also the app's name, which an icon on the home screen can show, so tests recognise this screen by its "VIEWS" button.
  - `ViewsActivity` ("Views fixture"): a status line that starts as "Ready"; the button "Send", which reports "Clicked Send" and "Long-pressed Send"; an icon button without a name, view ID `compose_button`; the check box "Remember me"; a tappable row "Ana Petrović" / "Online"; a view "Touch target" that reacts only to touches and reports "Touched target"; the text field "Message"; a field `strict_field` that refuses set-text; a password field; "Show dialog", which opens the dialog "Delete draft?" with Delete and Keep; and "Rebuild", which replaces the Send button with an identical new one and reports "Rebuilt".
  - `ScrollActivity` ("Scroll fixture"): 60 rows; with `--ez swipeOnly true`, a list that offers no scroll actions.
  - `GesturesActivity` ("Gestures fixture"): a "Gesture pad" that reports "Tap", "Long press", "Swipe left/right/up/down", "Drag left/right/up/down", "Pinch in" and "Pinch out".
  - `ComposeActivity`, `WebActivity` ("Web button", "Web field", "Web link") and `SecureActivity` (`FLAG_SECURE`, with "Secret action").

The fixture app is a separate app, so that Kestrel reads it the way it reads any other app. It never ships, so it uses its own convention plugin without the privacy audit. All its activities are exported, so that tests can start them with `am start`.

- [ ] **Step 1: Add the convention plugin**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidTestAppConventionPlugin.kt`:

```kotlin
package dev.kestrel.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/** For `:testapp`, the fixture screens of the instrumented tests. It is never shipped, so it is not audited. */
class AndroidTestAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.applyKestrelApplicationDefaults()
    }
}
```

Replace the whole content of `build-logic/convention/build.gradle.kts` with:

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

tasks.test {
    // AuditRulesTest also reads the project's own rules.
    inputs.file("../../audit/rules.txt").withPathSensitivity(PathSensitivity.NONE).withPropertyName("auditRules")
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
        register("androidTestApp") {
            id = "kestrel.android.testapp"
            implementationClass = "dev.kestrel.build.AndroidTestAppConventionPlugin"
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

Replace the whole content of `build-logic/convention/src/main/kotlin/dev/kestrel/build/AndroidApplicationConventionPlugin.kt` with:

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

/** Shared by `:app` and `:testapp`. Only `:app` is audited, because `:testapp` is never shipped. */
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

Replace the whole content of `settings.gradle.kts` with:

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
include(":core:screen")
include(":core:safety")
include(":testapp")
```

- [ ] **Step 2: Add the app's build file, manifest and view IDs**

`testapp/build.gradle.kts`:

```kotlin
plugins {
    id("kestrel.android.testapp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kestrel.testapp"
    defaultConfig {
        applicationId = "dev.kestrel.testapp"
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // The fixtures show fixed English text, which tests look for, so they have no translatable resources.
        disable += setOf("SetTextI18n", "HardcodedText")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    // Otherwise lifecycle 2.9 arrives through Compose, and its lint checks no longer run with this version of lint.
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
```

`testapp/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Fixture screens for Kestrel's instrumented tests (design spec, section 16.3). This app is never shipped. -->
    <application
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="Kestrel fixtures"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".FixturesActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Lets tests open the app with a link, as Kestrel opens links and deep links. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="kestrel-fixtures" />
            </intent-filter>
        </activity>
        <activity
            android:name=".ViewsActivity"
            android:exported="true" />
        <activity
            android:name=".ScrollActivity"
            android:exported="true" />
        <activity
            android:name=".GesturesActivity"
            android:exported="true" />
        <activity
            android:name=".ComposeActivity"
            android:exported="true" />
        <activity
            android:name=".WebActivity"
            android:exported="true" />
        <activity
            android:name=".SecureActivity"
            android:exported="true" />
    </application>
</manifest>
```

`testapp/src/main/res/values/ids.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- View IDs give Kestrel's snapshots resource names such as dev.kestrel.testapp:id/send_button. -->
<resources>
    <item name="status" type="id" />
    <item name="send_button" type="id" />
    <item name="compose_button" type="id" />
    <item name="remember_me" type="id" />
    <item name="contact_row" type="id" />
    <item name="touch_target" type="id" />
    <item name="message_field" type="id" />
    <item name="strict_field" type="id" />
    <item name="password_field" type="id" />
    <item name="show_dialog" type="id" />
    <item name="rebuild" type="id" />
    <item name="gesture_pad" type="id" />
</resources>
```

- [ ] **Step 3: Add the fixtures**

`testapp/src/main/kotlin/dev/kestrel/testapp/Fixtures.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shows a vertical page with a [title]. The page stays clear of the system bars, so that a tap at the centre of
 * one of its elements reaches that element.
 */
internal fun Activity.fixturePage(title: String): LinearLayout {
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(8), bars.right + dp(16), bars.bottom + dp(8))
            insets
        }
    }
    page.addView(TextView(this).apply {
        text = title
        textSize = 22f
    })
    setContentView(page)
    return page
}

/** Adds the status line, where a fixture reports what happened to it. */
internal fun LinearLayout.addStatus(): TextView = TextView(context).apply {
    id = R.id.status
    text = "Ready"
}.also(::addView)

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
```

`testapp/src/main/kotlin/dev/kestrel/testapp/FixturesActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

/** The launcher screen. It lists the fixtures for manual use; tests start each fixture directly. */
class FixturesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Kestrel fixtures")
        val fixtures = listOf(
            "Views" to ViewsActivity::class.java,
            "Scroll" to ScrollActivity::class.java,
            "Gestures" to GesturesActivity::class.java,
            "Compose" to ComposeActivity::class.java,
            "Web" to WebActivity::class.java,
            "Secure" to SecureActivity::class.java,
        )
        for ((name, activity) in fixtures) {
            page.addView(Button(this).apply {
                text = name
                setOnClickListener { startActivity(Intent(context, activity)) }
            })
        }
    }
}
```

`testapp/src/main/kotlin/dev/kestrel/testapp/ViewsActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/** Classic Views: buttons, an unlabeled icon, a check box, a tappable row, text fields and a dialog. */
class ViewsActivity : Activity() {
    private lateinit var page: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = fixturePage("Views fixture")
        status = page.addStatus()
        page.addView(sendButton())
        page.addView(ImageButton(this).apply {
            id = R.id.compose_button
            setImageResource(android.R.drawable.ic_menu_edit)
            // No content description on purpose: Kestrel has to name this button from its view ID.
            setOnClickListener { report("Clicked compose") }
        })
        page.addView(CheckBox(this).apply {
            id = R.id.remember_me
            text = "Remember me"
            setOnCheckedChangeListener { _, checked -> report(if (checked) "Checked Remember me" else "Unchecked Remember me") }
        })
        page.addView(LinearLayout(this).apply {
            id = R.id.contact_row
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { report("Clicked contact row") }
            addView(TextView(context).apply { text = "Ana Petrović" })
            addView(TextView(context).apply {
                text = "Online"
                setPadding(dp(16), 0, 0, 0)
            })
        })
        page.addView(
            TouchOnlyView(this).apply {
                id = R.id.touch_target
                contentDescription = "Touch target"
                onTouched = { report("Touched target") }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)),
        )
        page.addView(EditText(this).apply {
            id = R.id.message_field
            hint = "Message"
        })
        page.addView(RejectingEditText(this).apply {
            id = R.id.strict_field
            hint = "Strict field"
        })
        page.addView(EditText(this).apply {
            id = R.id.password_field
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        page.addView(Button(this).apply {
            id = R.id.show_dialog
            text = "Show dialog"
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("Delete draft?")
                    .setPositiveButton("Delete", null)
                    .setNegativeButton("Keep", null)
                    .show()
            }
        })
        page.addView(Button(this).apply {
            id = R.id.rebuild
            text = "Rebuild"
            setOnClickListener { replaceSendButton() }
        })
    }

    private fun sendButton() = Button(this).apply {
        id = R.id.send_button
        text = "Send"
        setOnClickListener { report("Clicked Send") }
        setOnLongClickListener {
            report("Long-pressed Send")
            true
        }
    }

    /** Replaces the Send button with an identical new one, so that a test can hold the node of a view that is gone. */
    private fun replaceSendButton() {
        val index = page.indexOfChild(page.findViewById(R.id.send_button))
        page.removeViewAt(index)
        page.addView(sendButton(), index)
        report("Rebuilt")
    }

    private fun report(text: String) {
        status.text = text
    }
}

/** Reacts to touches but offers no click action, so that Kestrel has to tap it with a gesture. */
class TouchOnlyView(context: Context) : View(context) {
    var onTouched: () -> Unit = {}

    init {
        setBackgroundColor(0xFFE0E0E0.toInt())
    }

    // On purpose: this view must not offer a click action to accessibility services.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTouched()
        return true
    }
}

/** Refuses the set-text action, as some messaging apps do, so that Kestrel has to type through its input method. */
class RejectingEditText(context: Context) : EditText(context) {
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        action != AccessibilityNodeInfo.ACTION_SET_TEXT && super.performAccessibilityAction(action, arguments)
}
```

`testapp/src/main/kotlin/dev/kestrel/testapp/ScrollActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A long list of rows. With the extra `swipeOnly`, the list offers no scroll actions to accessibility services,
 * so that Kestrel has to scroll it with a swipe gesture.
 */
class ScrollActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val swipeOnly = intent.getBooleanExtra(EXTRA_SWIPE_ONLY, false)
        val page = fixturePage(if (swipeOnly) "Swipe-only scroll fixture" else "Scroll fixture")
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in 1..ROWS) {
            rows.addView(TextView(this).apply {
                text = "Row $row"
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        val list = if (swipeOnly) SwipeOnlyScrollView(this) else ScrollView(this)
        list.addView(rows)
        page.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    companion object {
        const val EXTRA_SWIPE_ONLY = "swipeOnly"
        private const val ROWS = 60
    }
}

/** Scrolls with touch but hides its scroll actions from accessibility services and refuses them. */
class SwipeOnlyScrollView(context: Context) : ScrollView(context) {
    private val scrollActions = listOf(
        AccessibilityAction.ACTION_SCROLL_FORWARD,
        AccessibilityAction.ACTION_SCROLL_BACKWARD,
        AccessibilityAction.ACTION_SCROLL_DOWN,
        AccessibilityAction.ACTION_SCROLL_UP,
    )

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = false
        scrollActions.forEach(info::removeAction)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        scrollActions.none { it.id == action } && super.performAccessibilityAction(action, arguments)
}
```

`testapp/src/main/kotlin/dev/kestrel/testapp/GesturesActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/** A pad that names the last gesture made on it, in its status line. */
class GesturesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Gestures fixture")
        val status = page.addStatus()
        page.addView(
            GesturePadView(this).apply {
                id = R.id.gesture_pad
                contentDescription = "Gesture pad"
                onGesture = { status.text = it }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
}

/**
 * Recognises a tap, a long press, a swipe, a drag (a long press and then a move) and a two-finger pinch, and
 * reports names such as "Tap", "Swipe up", "Drag right" or "Pinch out".
 */
class GesturePadView(context: Context) : View(context) {
    var onGesture: (String) -> Unit = {}

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var movedAt = 0L
    private var pinching = false
    private var startSpan = 0f
    private var endSpan = 0f

    init {
        setBackgroundColor(0xFFE3F2FD.toInt())
    }

    // On purpose: the pad reads raw touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                downX = event.x
                downY = event.y
                movedAt = 0L
                pinching = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                pinching = true
                startSpan = span(event)
                endSpan = startSpan
            }
            MotionEvent.ACTION_MOVE -> when {
                pinching && event.pointerCount >= 2 -> endSpan = span(event)
                movedAt == 0L && hypot(event.x - downX, event.y - downY) > SLOP -> movedAt = event.eventTime
            }
            MotionEvent.ACTION_UP -> onGesture(name(event))
        }
        return true
    }

    private fun name(up: MotionEvent): String = when {
        pinching -> if (endSpan > startSpan) "Pinch out" else "Pinch in"
        movedAt == 0L -> if (up.eventTime - downAt >= LONG_PRESS_MILLIS) "Long press" else "Tap"
        movedAt - downAt >= LONG_PRESS_MILLIS -> "Drag ${direction(up)}"
        else -> "Swipe ${direction(up)}"
    }

    private fun direction(up: MotionEvent): String {
        val dx = up.x - downX
        val dy = up.y - downY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun span(event: MotionEvent) = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private companion object {
        const val SLOP = 30f
        const val LONG_PRESS_MILLIS = 500L
    }
}
```

`testapp/src/main/kotlin/dev/kestrel/testapp/ComposeActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The same kinds of elements as the Views fixture, built with Jetpack Compose. */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Ready") }
                var subscribed by remember { mutableStateOf(false) }
                var name by remember { mutableStateOf("") }
                Column(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Compose fixture", style = MaterialTheme.typography.headlineSmall)
                    Text(status)
                    Button(onClick = { status = "Clicked Compose button" }) { Text("Compose button") }
                    Row(
                        modifier = Modifier.toggleable(value = subscribed, role = Role.Checkbox, onValueChange = { subscribed = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = subscribed, onCheckedChange = null)
                        Text("Subscribe")
                    }
                    TextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                }
            }
        }
    }
}
```

`testapp/src/main/kotlin/dev/kestrel/testapp/WebActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout

/** A web page in a WebView, without JavaScript and without network access. */
class WebActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Web fixture")
        val web = WebView(this)
        page.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
    }

    private companion object {
        const val PAGE = """<!doctype html>
<html>
<body>
<h1>Web page</h1>
<p>Paragraph text</p>
<button type="button">Web button</button>
<input type="text" aria-label="Web field">
<a href="#end">Web link</a>
</body>
</html>"""
    }
}
```

`testapp/src/main/kotlin/dev/kestrel/testapp/SecureActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button

/** A window with FLAG_SECURE. Screenshots of it fail, but its accessibility tree can still be read (B10). */
class SecureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val page = fixturePage("Secure fixture")
        page.addView(Button(this).apply { text = "Secret action" })
    }
}
```

- [ ] **Step 4: Build the app and run its lint checks**

Run: `scripts\gradle.ps1 :testapp:assembleDebug :testapp:lintDebug check`

Expected: `BUILD SUCCESSFUL`, and `testapp/build/reports/lint-results-debug.txt` says `No issues found.`

- [ ] **Step 5: Look at the fixtures on the emulator**

Run:

```powershell
$serial = scripts\start-emulator.ps1
$adb = 'D:\AndroidVMs\SDK\platform-tools\adb.exe'
& $adb -s $serial install -r testapp\build\outputs\apk\debug\testapp-debug.apk
& $adb -s $serial shell am start -W -S -n dev.kestrel.testapp/.FixturesActivity
```

Expected: `Success`, then `Status: ok`, and the emulator shows "Kestrel fixtures" with a button for each fixture. Install with adb and the serial as shown; Gradle's `:testapp:installDebug` alone would install on every connected device. From Task 5 on, `scripts\connected-test.ps1` installs the fixture app on the chosen device before the device tests.

- [ ] **Step 6: Commit**

```powershell
git add build-logic settings.gradle.kts testapp
git commit -m "test: add the :testapp fixture screens" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The accessibility service, the screen reader and the first device tests

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/ErrorSummary.kt`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/ErrorSummaryTest.kt`
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/read/AccessibilityNodeData.kt`, `ScreenReader.kt`
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml` (declare the service), `app/build.gradle.kts` (depend on `:core:screen`; install `:testapp` before the device tests)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (the current screen session; log errors without messages)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` (test rule), `SnapshotTest.kt`

**Interfaces:**
- Consumes: `SnapshotBuilder`, `NodeData`, `WindowData`, `ScreenChangeTracker`, `ScreenEvents` and `SnapshotCache` from Tasks 2 and 3; the fixtures from Task 4; `AppGraph` and `KestrelApplication.graph` from plan 1A.
- Produces: `fun Throwable.describeWithoutMessages(): String` in `dev.kestrel.privacy.logs`.
- Produces: `class LiveSnapshot`, which the reader builds, with `snapshot: ScreenSnapshot` and `node(key): AccessibilityNodeInfo?`, and `class ScreenReader(service)` with `read(): LiveSnapshot`.
- Produces: `internal fun AccessibilityNodeInfo.shownText(): String?`, the node's text, or null while a text field shows its hint.
- Produces: `class ScreenSession(service: AccessibilityService)` with `snapshots: SnapshotCache<LiveSnapshot>` and `onAccessibilityEvent(event)`, and the internal `tracker`.
- Produces: `AppGraph.screen: StateFlow<ScreenSession?>`, which holds the session while the service is connected, and the internal `attachScreen(session)` and `detachScreen(session)`.
- Produces: the test rule `ScreenServiceRule` with `session`, `automation`, `shell(command): String`, `open(activity, title, extras = ""): ScreenElement` and `suspend awaitElement(timeoutMillis = 5_000, predicate): ScreenElement`, and the constant `FIXTURES`.

The service stays thin: it creates a `ScreenSession` when Android connects it, passes every accessibility event to the session, and hands the session to `AppGraph`, from which the rest of the app reaches the screen layer. Because the service runs for days, it also purges expired log entries once a day (spec section 13.5). `AppGraph`'s error handler now writes only class names and stack frames to logcat, because an error's message can contain screen text. Android reports the hint of an empty text field as its text, so the adapter reports no text while a field shows its hint; otherwise a snapshot would claim that an empty field contains "Message".

The test rule gives every test a new session: it turns the service off, waits until `AppGraph.screen` is empty, turns the service on and waits for the new session. It connects UiAutomation with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, because otherwise UiAutomation turns off every accessibility service. The rule replaces the whole list of enabled accessibility services, so it also switches off any other service on the test device; the test devices are only `kestrel-api37` and the A71. After the last test, Gradle uninstalls Kestrel, and Android then removes its service from the list.

- [ ] **Step 1: Write the failing unit test for error summaries**

`core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/ErrorSummaryTest.kt`:

```kotlin
package dev.kestrel.privacy.logs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorSummaryTest {
    @Test
    fun leavesOutMessagesButKeepsClassesFramesAndCauses() {
        val error = IllegalStateException("Message from Ana: see you at 5", IOException("token=secret"))

        val text = error.describeWithoutMessages()

        assertFalse(text, text.contains("Ana"))
        assertFalse(text, text.contains("secret"))
        assertTrue(text, text.startsWith("java.lang.IllegalStateException\n    at dev.kestrel.privacy.logs.ErrorSummaryTest"))
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'describeWithoutMessages'` from `:core:privacy:compileDebugUnitTestKotlin`.

- [ ] **Step 3: Write the error summary**

`core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/ErrorSummary.kt`:

```kotlin
package dev.kestrel.privacy.logs

private const val MAX_CAUSES = 8
private const val MAX_FRAMES = 30

/**
 * Describes this error by its class names and stack frames only, for logcat. Messages are left out, because
 * they can contain screen text or other personal data (design spec, section 13.5).
 */
fun Throwable.describeWithoutMessages(): String = buildString {
    var error: Throwable? = this@describeWithoutMessages
    var depth = 0
    while (error != null && depth < MAX_CAUSES) {
        if (depth > 0) append("Caused by: ")
        append(error.javaClass.name)
        for (frame in error.stackTrace.take(MAX_FRAMES)) append("\n    at ").append(frame)
        append('\n')
        error = error.cause?.takeIf { it !== error }
        depth++
    }
}.trimEnd()
```

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, and `TEST-dev.kestrel.privacy.logs.ErrorSummaryTest.xml` reports `tests="1" failures="0"`.

- [ ] **Step 4: Write the failing device tests**

`app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt`:

```kotlin
package dev.kestrel.app.screen

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * Gives each test a new screen session by turning Kestrel's accessibility service off and on again, so that
 * nothing one test leaves behind affects the next. The tests run only on the test emulator and the A71
 * (design spec, D20), where the service may stay on afterwards.
 */
class ScreenServiceRule : ExternalResource() {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    lateinit var session: ScreenSession
        private set

    val automation: UiAutomation get() = Companion.automation

    override fun before() {
        runBlocking {
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }
            shell("settings put secure enabled_accessibility_services $SERVICE")
            shell("settings put secure accessibility_enabled 1")
            session = withTimeout(10_000) { graph.screen.filterNotNull().first() }
        }
    }

    /** Runs a command as the shell user, which may change secure settings and start other apps' activities. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * Starts an activity of the fixture app and waits until its [title] is on the screen. The fixture app is
     * stopped first, because Android would otherwise only bring its existing task to the front.
     */
    fun open(activity: String, title: String, extras: String = ""): ScreenElement {
        shell("am start -W -S -n $FIXTURES/.$activity $extras")
        return runBlocking { awaitElement { it.label == title } }
    }

    /** Waits until an element that matches [predicate] is on the screen. */
    suspend fun awaitElement(timeoutMillis: Long = 5_000, predicate: (ScreenElement) -> Boolean): ScreenElement {
        var labels: List<String?> = emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            var found: ScreenElement? = null
            while (found == null) {
                val elements = session.snapshots.current().snapshot.elements
                labels = elements.map { it.label }
                found = elements.firstOrNull(predicate)
                if (found == null) delay(100)
            }
            found
        } ?: throw AssertionError("No matching element appeared within $timeoutMillis ms. The screen showed $labels")
    }

    companion object {
        const val FIXTURES = "dev.kestrel.testapp"
        private const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"

        // Connecting UiAutomation without this flag would turn off every accessibility service, Kestrel's included.
        private val automation: UiAutomation by lazy {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }
    }
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/SnapshotTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Test
    fun viewsAreReadWithTheirNamesStatesAndActions() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        val snapshot = screen.session.snapshots.current().snapshot
        val send = snapshot.elements.single { it.viewId == "$FIXTURES:id/send_button" }
        val compose = snapshot.elements.single { it.viewId == "$FIXTURES:id/compose_button" }
        val row = snapshot.elements.single { it.viewId == "$FIXTURES:id/contact_row" }
        val password = snapshot.elements.single { it.viewId == "$FIXTURES:id/password_field" }
        val message = snapshot.elements.single { it.viewId == "$FIXTURES:id/message_field" }

        assertEquals("SEND", send.label)
        assertTrue(send.clickable && ElementAction.CLICK in send.actions)
        assertNull(compose.label)
        assertEquals("compose", compose.suggestedName)
        assertEquals("Ana Petrović Online", row.label)
        assertTrue(password.password && password.editable)
        assertNull(password.text)
        assertEquals("Password", password.label)
        assertNull("An empty field's hint is not its text", message.text)
        assertEquals("Message", message.label)
        assertTrue(snapshot.elements.single { it.label == "Remember me" }.checkable)
        assertTrue(snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES })
    }

    @Test
    fun keysStayTheSameInANewSnapshotOfTheSameScreen() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = screen.session.snapshots.current().snapshot.elements.map { it.key }

        screen.session.snapshots.invalidate()
        val second = screen.session.snapshots.current().snapshot.elements.map { it.key }

        assertEquals(first, second)
    }

    @Test
    fun composeElementsAreRead() = runBlocking {
        screen.open("ComposeActivity", "Compose fixture")

        val elements = screen.session.snapshots.current().snapshot.elements

        assertTrue(elements.single { it.clickable && it.label == "Compose button" }.clickable)
        assertTrue(elements.any { it.checkable && it.label == "Subscribe" })
        assertTrue(elements.any { it.editable })
    }

    @Test
    fun webPageContentIsRead() = runBlocking {
        screen.open("WebActivity", "Web fixture")

        assertTrue(screen.awaitElement { it.label == "Web button" }.clickable)
        screen.awaitElement { it.label == "Web link" }
        screen.awaitElement { it.editable && it.label == "Web field" }
        Unit
    }

    @Test
    fun aSecureWindowCanStillBeRead() = runBlocking {
        screen.open("SecureActivity", "Secure fixture")

        assertTrue(screen.awaitElement { it.label == "SECRET ACTION" }.clickable)
    }

    @Test
    fun aDialogComesBeforeTheActivityBehindIt() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val showDialog = screen.session.snapshots.current().snapshot.elements.single { it.label == "SHOW DIALOG" }
        screen.shell("input tap ${showDialog.bounds.centerX} ${showDialog.bounds.centerY}")

        val title = screen.awaitElement { it.label == "Delete draft?" }

        val snapshot = screen.session.snapshots.current().snapshot
        assertEquals(title.windowId, snapshot.windows.first { it.kind == WindowKind.APPLICATION }.id)
        assertTrue(snapshot.elements.any { it.label == "KEEP" && it.windowId == title.windowId })
        screen.shell("input keyevent KEYCODE_BACK")
        Unit
    }
}
```

- [ ] **Step 5: Run the device tests to verify that they fail**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD FAILED`, with `Unresolved reference` errors from `:app:compileDebugAndroidTestKotlin`, because `ScreenSession` and `AppGraph.screen` do not exist yet.

- [ ] **Step 6: Write the Android adapters, the reader and the session**

`core/screen/src/main/kotlin/dev/kestrel/screen/read/AccessibilityNodeData.kt`:

```kotlin
package dev.kestrel.screen.read

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind

/** Reads a snapshot's data from one of Android's accessibility nodes. */
internal class AccessibilityNodeData(val info: AccessibilityNodeInfo) : NodeData {
    override val packageName: String? get() = info.packageName?.toString()
    override val className: String? get() = info.className?.toString()
    override val text: String? get() = info.shownText()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val hint: String? get() = info.hintText?.toString()
    override val stateDescription: String? get() = info.stateDescription?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val bounds: IntRect get() = Rect().also(info::getBoundsInScreen).toIntRect()
    override val visible: Boolean get() = info.isVisibleToUser
    override val enabled: Boolean get() = info.isEnabled
    override val clickable: Boolean get() = info.isClickable
    override val longClickable: Boolean get() = info.isLongClickable
    override val editable: Boolean get() = info.isEditable
    override val checkable: Boolean get() = info.isCheckable
    override val checked: Boolean get() = info.isCheckedCompat()
    override val scrollable: Boolean get() = info.isScrollable
    override val focused: Boolean get() = info.isFocused
    override val selected: Boolean get() = info.isSelected
    override val password: Boolean get() = info.isPassword
    override val dataSensitive: Boolean get() = Build.VERSION.SDK_INT >= 34 && info.isAccessibilityDataSensitive
    override val actions: Set<ElementAction> get() = info.actionList.mapNotNullTo(mutableSetOf()) { STANDARD_ACTIONS[it.id] }
    override val childCount: Int get() = info.childCount

    override fun child(index: Int): NodeData? = info.getChild(index)?.let(::AccessibilityNodeData)

    private companion object {
        val STANDARD_ACTIONS = mapOf(
            AccessibilityAction.ACTION_CLICK.id to ElementAction.CLICK,
            AccessibilityAction.ACTION_LONG_CLICK.id to ElementAction.LONG_CLICK,
            AccessibilityAction.ACTION_FOCUS.id to ElementAction.FOCUS,
            AccessibilityAction.ACTION_SET_TEXT.id to ElementAction.SET_TEXT,
            AccessibilityAction.ACTION_SCROLL_FORWARD.id to ElementAction.SCROLL_FORWARD,
            AccessibilityAction.ACTION_SCROLL_BACKWARD.id to ElementAction.SCROLL_BACKWARD,
            AccessibilityAction.ACTION_SCROLL_UP.id to ElementAction.SCROLL_UP,
            AccessibilityAction.ACTION_SCROLL_DOWN.id to ElementAction.SCROLL_DOWN,
            AccessibilityAction.ACTION_SCROLL_LEFT.id to ElementAction.SCROLL_LEFT,
            AccessibilityAction.ACTION_SCROLL_RIGHT.id to ElementAction.SCROLL_RIGHT,
            AccessibilityAction.ACTION_EXPAND.id to ElementAction.EXPAND,
            AccessibilityAction.ACTION_COLLAPSE.id to ElementAction.COLLAPSE,
            AccessibilityAction.ACTION_DISMISS.id to ElementAction.DISMISS,
        )
    }
}

/** Reads a snapshot's data from one of Android's accessibility windows. */
internal class AccessibilityWindowData(private val info: AccessibilityWindowInfo) : WindowData {
    override val id: Int get() = info.id
    override val kind: WindowKind
        get() = when (info.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowKind.SPLIT_SCREEN_DIVIDER
            else -> WindowKind.OTHER
        }
    override val isOverlay: Boolean
        get() = info.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            info.type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY
    override val title: String? get() = info.title?.toString()
    override val bounds: IntRect get() = Rect().also(info::getBoundsInScreen).toIntRect()
    override val layer: Int get() = info.layer
    override val active: Boolean get() = info.isActive
    override val focused: Boolean get() = info.isFocused
    override val root: NodeData? get() = info.root?.let(::AccessibilityNodeData)
}

private fun Rect.toIntRect() = IntRect(left, top, right, bottom)

/** Android 16 replaced the yes-or-no checked state with one that can also be "partly checked". */
private fun AccessibilityNodeInfo.isCheckedCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= 36) {
        checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
    } else {
        @Suppress("DEPRECATION")
        isChecked
    }

/**
 * The node's text, or null while a text field shows its hint: Android then reports the hint as the text and sets
 * `isShowingHintText`, and an empty field must not seem to contain its hint.
 */
internal fun AccessibilityNodeInfo.shownText(): String? = if (isShowingHintText) null else text?.toString()
```

`core/screen/src/main/kotlin/dev/kestrel/screen/read/ScreenReader.kt`:

```kotlin
package dev.kestrel.screen.read

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.ScreenSnapshot

/** A snapshot whose elements still lead to their live accessibility nodes, which actions need. */
class LiveSnapshot internal constructor(val snapshot: ScreenSnapshot, private val nodes: Map<String, NodeData>) {
    /** The live node of the element with [key], or null when this snapshot has no such element. */
    fun node(key: String): AccessibilityNodeInfo? = (nodes[key] as? AccessibilityNodeData)?.info
}

/** Reads every interactive window that Kestrel's accessibility service can see (design spec, section 9.2). */
class ScreenReader(private val service: AccessibilityService, private val builder: SnapshotBuilder = SnapshotBuilder()) {
    fun read(): LiveSnapshot {
        val result = builder.build(service.windows.map(::AccessibilityWindowData))
        return LiveSnapshot(result.snapshot, result.nodes)
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }
}
```

- [ ] **Step 7: Add the service to the app**

`app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Design spec, section 9.1. Kestrel receives every event type, because Chrome gives less detail to services that
    ask for only a few (B11). As an accessibility tool, it also sees views that apps mark as data-sensitive (B9).
-->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagIncludeNotImportantViews|flagReportViewIds|flagRetrieveInteractiveWindows|flagRequestAccessibilityButton|flagInputMethodEditor"
    android:canControlMagnification="true"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/accessibility_service_description"
    android:isAccessibilityTool="true"
    android:notificationTimeout="50"
    android:settingsActivity="dev.kestrel.app.MainActivity" />
```

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="accessibility_service_description">Kestrel reads the screen and acts on it when you ask, so that you can use the phone by voice. What it reads stays on the phone, except what you choose to send to your own AI provider.</string>
</resources>
```

`app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt`:

```kotlin
package dev.kestrel.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reads the screen and acts on it for Kestrel (design spec, sections 7.2 and 9). */
class KestrelAccessibilityService : AccessibilityService() {
    private val graph: AppGraph get() = (application as KestrelApplication).graph
    private var session: ScreenSession? = null
    private var purging: Job? = null

    override fun onServiceConnected() {
        val connected = ScreenSession(this)
        session = connected
        graph.attachScreen(connected)
        // The service runs for days at a time, so it also keeps the logs within their 7 days (design spec, section 13.5).
        purging = graph.scope.launch {
            while (isActive) {
                graph.logs.purgeExpired()
                delay(PURGE_INTERVAL_MILLIS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        session?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        purging?.cancel()
        purging = null
        session?.let(graph::detachScreen)
        session = null
    }

    private companion object {
        const val PURGE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
```

Replace the whole content of `app/src/main/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />

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

Replace the whole content of `app/build.gradle.kts` with:

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
    implementation(project(":core:screen"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver3)
}

// The instrumented tests drive the fixture screens of :testapp, so it is installed first. They also turn on
// Kestrel's accessibility service, so they run after the other modules' device tests instead of alongside them.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testapp:installDebug")
    mustRunAfter(":core:privacy:connectedDebugAndroidTest")
}
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` with:

```kotlin
package dev.kestrel.app

import android.content.Context
import android.util.Log
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessages
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}

/** Wires Kestrel's parts together by hand; there is no dependency-injection framework (design spec, section 17). */
class AppGraph(context: Context) {
    /**
     * Background work. A failed job is written to logcat instead of ending the app's process. Only the error's
     * classes and stack frames are written, because a message can contain screen text.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "A background task failed: ${error.describeWithoutMessages()}") },
    )

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()
    val network = NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } }

    private val screens = MutableStateFlow<ScreenSession?>(null)

    /** Kestrel's screen layer while its accessibility service is connected, otherwise null. */
    val screen: StateFlow<ScreenSession?> = screens.asStateFlow()

    internal fun attachScreen(session: ScreenSession) {
        screens.value = session
    }

    internal fun detachScreen(session: ScreenSession) {
        screens.compareAndSet(session, null)
    }

    private companion object {
        const val TAG = "Kestrel"
    }
}
```

- [ ] **Step 8: Run the device tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`. The XML file in `app/build/outputs/androidTest-results/connected/debug/` reports 10 test cases (plan 1A's `AppGraphTest` 4 and `SnapshotTest` 6) and no failures.

- [ ] **Step 9: Run the checks**

Run: `scripts\gradle.ps1 check`

Expected: `BUILD SUCCESSFUL`. The privacy audit passes for every variant.

- [ ] **Step 10: Commit**

```powershell
git add core app
git commit -m "feat(screen): add the accessibility service and read snapshots" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Tap and long press, with their fallbacks

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt`, `GestureRunner.kt`, `Gestures.kt`, `ScreenActions.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (add `actions`)
- Modify: `testapp/src/main/kotlin/dev/kestrel/testapp/ViewsActivity.kt` (a disabled button inside a tappable card)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/TapTest.kt`

**Interfaces:**
- Consumes: `ScreenSession`, `LiveSnapshot` and `ScreenChangeTracker` from Tasks 3 and 5.
- Produces: `enum class ActionMethod { ACTION, PARENT_ACTION, RETRY_ACTION, GESTURE }` (later tasks add more) and `sealed interface ActionResult` with `Done(method: ActionMethod)` and `Failed(reason: String)`.
- Produces: `enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED }` and `class GestureRunner(service)` with `suspend perform(gesture): GestureOutcome`, which runs one gesture at a time.
- Produces: `class ScreenActions` with `suspend tap(key: String)`, `longPress(key: String)`, `tapAt(x, y)` and `longPressAt(x, y)`, all returning `ActionResult`. `ScreenSession.actions` gives access to them.
- Produces: in the Views fixture, the card "Blue mug", which reports "Clicked product card", with the disabled button "Add to cart" (read as "ADD TO CART") inside it.

A tap looks up its key in the current snapshot, never in an older one: a list may have reused an old row's view for another item, and a tap on that view would hit the wrong item. The snapshot cache returns the same snapshot while the screen has not changed, so the lookup costs nothing then. The tap then finds the node that a finger's touch on the element would reach: the element itself or its nearest parent that handles touches, up to 20 levels up. If that node is disabled, the tap fails with "The element is disabled", because a finger's touch would stop there too. Otherwise the tap tries, in order: that node's click action; the same in a fresh snapshot, when the node has gone or refused the action (spec section 14.3); and finally a tap gesture at the element's centre, where it is now. A long press works the same way with the long-click action. Failure messages never contain the key, because a key contains screen text. Each action then waits until the screen has been quiet for 200 ms, counted from the end of the action (for a gesture, from the moment the finger is lifted), but never longer than 3 s. A gesture that a touch cancels fails with "A touch on the screen cancelled the gesture".

- [ ] **Step 1: Add the disabled button and write the failing test**

Replace the whole content of `testapp/src/main/kotlin/dev/kestrel/testapp/ViewsActivity.kt` with:

```kotlin
package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Classic Views: buttons, an unlabeled icon, a check box, a tappable row, text fields, a dialog, and a disabled button
 * inside a tappable card.
 */
class ViewsActivity : Activity() {
    private lateinit var page: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = fixturePage("Views fixture")
        status = page.addStatus()
        page.addView(sendButton())
        page.addView(ImageButton(this).apply {
            id = R.id.compose_button
            setImageResource(android.R.drawable.ic_menu_edit)
            // No content description on purpose: Kestrel has to name this button from its view ID.
            setOnClickListener { report("Clicked compose") }
        })
        page.addView(CheckBox(this).apply {
            id = R.id.remember_me
            text = "Remember me"
            setOnCheckedChangeListener { _, checked -> report(if (checked) "Checked Remember me" else "Unchecked Remember me") }
        })
        page.addView(LinearLayout(this).apply {
            id = R.id.contact_row
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { report("Clicked contact row") }
            addView(TextView(context).apply { text = "Ana Petrović" })
            addView(TextView(context).apply {
                text = "Online"
                setPadding(dp(16), 0, 0, 0)
            })
        })
        page.addView(
            TouchOnlyView(this).apply {
                id = R.id.touch_target
                contentDescription = "Touch target"
                onTouched = { report("Touched target") }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)),
        )
        page.addView(EditText(this).apply {
            id = R.id.message_field
            hint = "Message"
        })
        page.addView(RejectingEditText(this).apply {
            id = R.id.strict_field
            hint = "Strict field"
        })
        page.addView(EditText(this).apply {
            id = R.id.password_field
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        page.addView(Button(this).apply {
            id = R.id.show_dialog
            text = "Show dialog"
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("Delete draft?")
                    .setPositiveButton("Delete", null)
                    .setNegativeButton("Keep", null)
                    .show()
            }
        })
        page.addView(Button(this).apply {
            id = R.id.rebuild
            text = "Rebuild"
            setOnClickListener { replaceSendButton() }
        })
        page.addView(LinearLayout(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { report("Clicked product card") }
            addView(TextView(context).apply { text = "Blue mug" })
            addView(Button(context).apply {
                text = "Add to cart"
                // Disabled on purpose: a tap on it must not reach the card around it.
                isEnabled = false
            })
        })
    }

    private fun sendButton() = Button(this).apply {
        id = R.id.send_button
        text = "Send"
        setOnClickListener { report("Clicked Send") }
        setOnLongClickListener {
            report("Long-pressed Send")
            true
        }
    }

    /** Replaces the Send button with an identical new one, so that a test can hold the node of a view that is gone. */
    private fun replaceSendButton() {
        val index = page.indexOfChild(page.findViewById(R.id.send_button))
        page.removeViewAt(index)
        page.addView(sendButton(), index)
        report("Rebuilt")
    }

    private fun report(text: String) {
        status.text = text
    }
}

/** Reacts to touches but offers no click action, so that Kestrel has to tap it with a gesture. */
class TouchOnlyView(context: Context) : View(context) {
    var onTouched: () -> Unit = {}

    init {
        setBackgroundColor(0xFFE0E0E0.toInt())
    }

    // On purpose: this view must not offer a click action to accessibility services.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTouched()
        return true
    }
}

/** Refuses the set-text action, as some messaging apps do, so that Kestrel has to type through its input method. */
class RejectingEditText(context: Context) : EditText(context) {
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        action != AccessibilityNodeInfo.ACTION_SET_TEXT && super.performAccessibilityAction(action, arguments)
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/TapTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    @Test
    fun tapUsesTheElementsClickAction() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" }

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.tap(send.key))
        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun tapOnTextInsideARowClicksTheRow() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val name = screen.awaitElement { it.label == "Ana Petrović" }

        assertEquals(ActionResult.Done(ActionMethod.PARENT_ACTION), actions.tap(name.key))
        screen.awaitElement { it.label == "Clicked contact row" }
        Unit
    }

    @Test
    fun tapUsesAGestureWhenNothingOffersAClick() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val target = screen.awaitElement { it.label == "Touch target" }

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), actions.tap(target.key))
        screen.awaitElement { it.label == "Touched target" }
        Unit
    }

    @Test
    fun aKeyFromAnOlderSnapshotTapsTheElementThatIsOnTheScreenNow() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val old = screen.session.snapshots.current().snapshot
        actions.tap(old.elements.single { it.label == "REBUILD" }.key)
        screen.awaitElement { it.label == "Rebuilt" }

        // Rebuild has replaced the Send button, so the node in the old snapshot has gone.
        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.tap(old.elements.single { it.label == "SEND" }.key))
        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun aDisabledElementIsNotTappedAndTheTapDoesNotReachItsParent() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val addToCart = screen.awaitElement { it.label == "ADD TO CART" }

        assertEquals(ActionResult.Failed("The element is disabled"), actions.tap(addToCart.key))
        assertTrue(screen.session.snapshots.current().snapshot.elements.any { it.label == "Ready" })
    }

    @Test
    fun longPressUsesTheElementsLongClickAction() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" }

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.longPress(send.key))
        screen.awaitElement { it.label == "Long-pressed Send" }
        Unit
    }

    @Test
    fun anUnknownKeyFails() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertTrue(actions.tap("no such key") is ActionResult.Failed)
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.TapTest'`

Expected: `BUILD FAILED`, with `Unresolved reference 'actions'` from `:app:compileDebugAndroidTestKotlin`.

- [ ] **Step 3: Write the actions**

`core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt`:

```kotlin
package dev.kestrel.screen.act

/** How Kestrel performed an action (design spec, section 9.3). */
enum class ActionMethod {
    /** The element's own accessibility action. */
    ACTION,

    /** The action of the element's nearest parent that offers it. */
    PARENT_ACTION,

    /** The element's action in a fresh snapshot, after its node had gone or refused the action (design spec, section 14.3). */
    RETRY_ACTION,
    GESTURE,
}

sealed interface ActionResult {
    data class Done(val method: ActionMethod) : ActionResult

    data class Failed(val reason: String) : ActionResult
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/act/GestureRunner.kt`:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What became of a gesture that Kestrel dispatched. */
enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED }

/** Runs Kestrel's own gestures one at a time (design spec, section 9.3). */
class GestureRunner(private val service: AccessibilityService) {
    private val mutex = Mutex()

    suspend fun perform(gesture: GestureDescription): GestureOutcome = mutex.withLock { dispatch(gesture) }

    private suspend fun dispatch(gesture: GestureDescription): GestureOutcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
            }
            if (!service.dispatchGesture(gesture, callback, null)) continuation.resume(GestureOutcome.REJECTED)
        }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/act/Gestures.kt`:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path

/** Builds Kestrel's gestures. Coordinates are screen pixels (design spec, section 9.3). */
internal object Gestures {
    private const val TAP_MILLIS = 50L
    private const val LONG_PRESS_MILLIS = 800L

    fun tap(x: Int, y: Int): GestureDescription = stroke(point(x, y), TAP_MILLIS)

    fun longPress(x: Int, y: Int): GestureDescription = stroke(point(x, y), LONG_PRESS_MILLIS)

    private fun point(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private fun stroke(path: Path, durationMillis: Long): GestureDescription =
        GestureDescription.Builder().addStroke(StrokeDescription(path, 0, durationMillis)).build()
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt`:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache

/** Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). */
class ScreenActions internal constructor(
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gesture(Gestures.tap(x, y))

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gesture(Gestures.longPress(x, y))

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
        val node = snapshots.current().node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh()) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            return gesture(fallback(bounds.centerX, bounds.centerY))
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return ActionResult.Failed("The element is no longer on the screen")
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return gesture(fallback(element.bounds.centerX, element.bounds.centerY))
    }

    private suspend fun gesture(description: GestureDescription): ActionResult =
        when (gestures.perform(description)) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
        }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = clock())
        return ActionResult.Done(method)
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /**
     * The element's node, or its nearest parent up to [MAX_PARENT_DEPTH] levels up, that handles touches. A finger's
     * touch stops at that view even when it is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (current.isClickable || current.isLongClickable || current.offers(action)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` with:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    val actions = ScreenActions(snapshots, gestures, tracker)

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }
}
```

- [ ] **Step 4: Run the device tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 17 test cases (`TapTest` 7) and no failures.

- [ ] **Step 5: Run the checks**

Run: `scripts\gradle.ps1 check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add core/screen app/src/androidTest testapp
git commit -m "feat(screen): tap and long press with fallbacks" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Typing and scrolling, with their fallbacks

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/act/SwipePaths.kt`, `ScrollActions.kt`
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/act/SwipePathsTest.kt`, `ScrollActionsTest.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt` (add `INPUT_METHOD`), `Gestures.kt` (add `swipe`), `ScreenActions.kt` (add `setText` and `scroll`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (pass the service to `ScreenActions`)
- Modify: `testapp/src/main/kotlin/dev/kestrel/testapp/ViewsActivity.kt` (a disabled text field) and `ScrollActivity.kt` (a pager, and a swipe-only list with room below it)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/TypeTest.kt`, `ScrollTest.kt`

**Interfaces:**
- Consumes: `ScreenActions`, `GestureRunner` and `Gestures` from Task 6, and `shownText()` from Task 5.
- Produces: `enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }`, the internal `ScreenPoint(x, y)` and `SwipePaths.forScroll(area: IntRect, direction): Pair<ScreenPoint, ScreenPoint>`, and the internal `ScrollActions` with `axis(offered, className, rows, columns): ScrollAxis` (`VERTICAL`, `HORIZONTAL`, `BOTH` or `UNKNOWN`), `forDirection(direction, axis): List<Int>` and `movesAlong(axis, direction): Boolean`.
- Produces: `ScreenActions.setText(key: String, text: String): ActionResult` and `ScreenActions.scroll(direction: ScrollDirection, key: String? = null): ActionResult`. Like a tap, both look up the key in the current snapshot.
- Produces: `ActionMethod.INPUT_METHOD`.
- Produces: in the Views fixture, the disabled text field "Disabled field". In the Scroll fixture with `--ez pages true`, the "Pager scroll fixture": two pages side by side in a horizontal scroll view, with the rows "Row 1" to "Row 60" and "Second page row 1" to "Second page row 60". With `--ez swipeOnly true`, the list fills only the upper half of the page, above the text "Below the list". With `--ez opaquePager true`, the "Opaque pager scroll fixture": the same two pages in a pager that hides its axis, as Flutter's pagers do.

`setText` first uses the set-text action and reads the text back, up to 5 times 50 ms apart; it never reads back a password field. The read-back uses the text that the field shows, without its hint, so that clearing a field with an empty text reads back as empty. If the app refuses set-text, or the text does not read back, Kestrel types through its accessibility input method: it waits for the input to start in the field, checks that the field still has the focus, selects all and commits the new text. It remembers the app and uses the input method for it from then on (spec section 9.7). Only a refusal counts: Kestrel does not type into a disabled field, and when the field has gone, it looks for the field once more in a fresh snapshot instead of remembering the app.

`scroll` uses the scroll action of the element or of its nearest parent that can scroll in the direction. Android's forward and backward actions do not say which way they move, so Kestrel uses them only when it knows the list's axis and the axis matches: on a horizontal pager, forward would show the next tab instead of more of the list. `ScrollActions` works out the axis from the list's directional actions, its class, or the shape of its collection; for the count that a single-row or single-column list does not use, Flutter reports 0 and Compose reports -1. Flutter's pagers hide their axis, so a list whose axis stays unknown never gets forward or backward, and a list that offers directional actions for both axes never needs them. Without a key, `scroll` takes a list in the top app window that has one: the largest list that can scroll in the direction, or else the largest that moves along that axis, or else the largest list. When no action scrolls that way, Kestrel swipes from 70% to 30% across the nearest list that moves along that axis, across its middle. A sideways swipe across a row can archive a message or start a call, so Kestrel swipes sideways only across a list that moves sideways, and otherwise fails with "Nothing on the screen scrolls that way". Up and down, it swipes across the window when no list moves that way. A list whose axis is unknown counts as vertical.

- [ ] **Step 1: Write the failing tests**

The new tests need two fixtures. Replace the whole content of `testapp/src/main/kotlin/dev/kestrel/testapp/ViewsActivity.kt` with:

```kotlin
package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Classic Views: buttons, an unlabeled icon, a check box, a tappable row, text fields (one of them disabled), a dialog,
 * and a disabled button inside a tappable card.
 */
class ViewsActivity : Activity() {
    private lateinit var page: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = fixturePage("Views fixture")
        status = page.addStatus()
        page.addView(sendButton())
        page.addView(ImageButton(this).apply {
            id = R.id.compose_button
            setImageResource(android.R.drawable.ic_menu_edit)
            // No content description on purpose: Kestrel has to name this button from its view ID.
            setOnClickListener { report("Clicked compose") }
        })
        page.addView(CheckBox(this).apply {
            id = R.id.remember_me
            text = "Remember me"
            setOnCheckedChangeListener { _, checked -> report(if (checked) "Checked Remember me" else "Unchecked Remember me") }
        })
        page.addView(LinearLayout(this).apply {
            id = R.id.contact_row
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { report("Clicked contact row") }
            addView(TextView(context).apply { text = "Ana Petrović" })
            addView(TextView(context).apply {
                text = "Online"
                setPadding(dp(16), 0, 0, 0)
            })
        })
        page.addView(
            TouchOnlyView(this).apply {
                id = R.id.touch_target
                contentDescription = "Touch target"
                onTouched = { report("Touched target") }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)),
        )
        page.addView(EditText(this).apply {
            id = R.id.message_field
            hint = "Message"
        })
        page.addView(RejectingEditText(this).apply {
            id = R.id.strict_field
            hint = "Strict field"
        })
        page.addView(EditText(this).apply {
            id = R.id.password_field
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        page.addView(EditText(this).apply {
            hint = "Disabled field"
            // Disabled on purpose: Kestrel must not type into it, nor switch the app to its input method because of it.
            isEnabled = false
        })
        page.addView(Button(this).apply {
            id = R.id.show_dialog
            text = "Show dialog"
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("Delete draft?")
                    .setPositiveButton("Delete", null)
                    .setNegativeButton("Keep", null)
                    .show()
            }
        })
        page.addView(Button(this).apply {
            id = R.id.rebuild
            text = "Rebuild"
            setOnClickListener { replaceSendButton() }
        })
        page.addView(LinearLayout(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { report("Clicked product card") }
            addView(TextView(context).apply { text = "Blue mug" })
            addView(Button(context).apply {
                text = "Add to cart"
                // Disabled on purpose: a tap on it must not reach the card around it.
                isEnabled = false
            })
        })
    }

    private fun sendButton() = Button(this).apply {
        id = R.id.send_button
        text = "Send"
        setOnClickListener { report("Clicked Send") }
        setOnLongClickListener {
            report("Long-pressed Send")
            true
        }
    }

    /** Replaces the Send button with an identical new one, so that a test can hold the node of a view that is gone. */
    private fun replaceSendButton() {
        val index = page.indexOfChild(page.findViewById(R.id.send_button))
        page.removeViewAt(index)
        page.addView(sendButton(), index)
        report("Rebuilt")
    }

    private fun report(text: String) {
        status.text = text
    }
}

/** Reacts to touches but offers no click action, so that Kestrel has to tap it with a gesture. */
class TouchOnlyView(context: Context) : View(context) {
    var onTouched: () -> Unit = {}

    init {
        setBackgroundColor(0xFFE0E0E0.toInt())
    }

    // On purpose: this view must not offer a click action to accessibility services.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTouched()
        return true
    }
}

/** Refuses the set-text action, as some messaging apps do, so that Kestrel has to type through its input method. */
class RejectingEditText(context: Context) : EditText(context) {
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        action != AccessibilityNodeInfo.ACTION_SET_TEXT && super.performAccessibilityAction(action, arguments)
}
```

Replace the whole content of `testapp/src/main/kotlin/dev/kestrel/testapp/ScrollActivity.kt` with:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A long list of rows. With the extra `swipeOnly`, the list offers no scroll actions to accessibility services, so
 * that Kestrel has to scroll it with a swipe gesture, and it fills only the upper half of the page. With the extra
 * `pages`, the list is the first of two pages side by side in a horizontal scroll view, like tabs. With the extra
 * `opaquePager`, the same pager hides its axis from accessibility services, as Flutter's pagers do.
 */
class ScrollActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val swipeOnly = intent.getBooleanExtra(EXTRA_SWIPE_ONLY, false)
        val pages = intent.getBooleanExtra(EXTRA_PAGES, false)
        val opaquePager = intent.getBooleanExtra(EXTRA_OPAQUE_PAGER, false)
        val page = fixturePage(
            when {
                swipeOnly -> "Swipe-only scroll fixture"
                pages -> "Pager scroll fixture"
                opaquePager -> "Opaque pager scroll fixture"
                else -> "Scroll fixture"
            },
        )
        when {
            swipeOnly -> {
                page.addView(SwipeOnlyScrollView(this).apply { addView(rows("Row")) }, restOfThePage())
                // Below the list, so that a swipe across the whole window would start outside the list.
                page.addView(TextView(this).apply { text = "Below the list" }, restOfThePage())
            }
            pages || opaquePager -> {
                val strip = LinearLayout(this)
                for (prefix in listOf("Row", "Second page row")) {
                    // Each page is as wide as the screen, so the second page starts outside the screen.
                    val width = resources.displayMetrics.widthPixels
                    strip.addView(ScrollView(this).apply { addView(rows(prefix)) }, LinearLayout.LayoutParams(width, MATCH))
                }
                val pager = if (opaquePager) OpaquePager(this) else HorizontalScrollView(this)
                page.addView(pager.apply { addView(strip, FrameLayout.LayoutParams(WRAP, MATCH)) }, restOfThePage())
            }
            else -> page.addView(ScrollView(this).apply { addView(rows("Row")) }, restOfThePage())
        }
    }

    /** Shares the rest of the page equally with the page's other children that use it. */
    private fun restOfThePage() = LinearLayout.LayoutParams(MATCH, 0, 1f)

    private fun rows(prefix: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (row in 1..ROWS) {
            addView(TextView(context).apply {
                text = "$prefix $row"
                setPadding(0, dp(12), 0, dp(12))
            })
        }
    }

    companion object {
        const val EXTRA_SWIPE_ONLY = "swipeOnly"
        const val EXTRA_PAGES = "pages"
        const val EXTRA_OPAQUE_PAGER = "opaquePager"
        private const val ROWS = 60
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/**
 * Scrolls with touch but hides its scroll actions from accessibility services and refuses them. It still says that it
 * can scroll, as such lists do, so Kestrel knows where to swipe.
 */
class SwipeOnlyScrollView(context: Context) : ScrollView(context) {
    private val scrollActions = listOf(
        AccessibilityAction.ACTION_SCROLL_FORWARD,
        AccessibilityAction.ACTION_SCROLL_BACKWARD,
        AccessibilityAction.ACTION_SCROLL_DOWN,
        AccessibilityAction.ACTION_SCROLL_UP,
    )

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        scrollActions.forEach(info::removeAction)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        scrollActions.none { it.id == action } && super.performAccessibilityAction(action, arguments)
}

/**
 * A horizontal pager that hides its axis from accessibility services, as Flutter's pagers do: it reports a plain view
 * that offers only the forward and backward scroll actions.
 */
class OpaquePager(context: Context) : HorizontalScrollView(context) {
    override fun getAccessibilityClassName(): CharSequence = View::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.removeAction(AccessibilityAction.ACTION_SCROLL_LEFT)
        info.removeAction(AccessibilityAction.ACTION_SCROLL_RIGHT)
    }
}
```

`core/screen/src/test/kotlin/dev/kestrel/screen/act/SwipePathsTest.kt`:

```kotlin
package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipePathsTest {
    private val area = IntRect(100, 200, 1100, 2200)

    @Test
    fun scrollingDownMovesTheFingerUpWithinTheMiddleOfTheArea() {
        assertEquals(ScreenPoint(600, 1600) to ScreenPoint(600, 800), SwipePaths.forScroll(area, ScrollDirection.DOWN))
        assertEquals(ScreenPoint(600, 800) to ScreenPoint(600, 1600), SwipePaths.forScroll(area, ScrollDirection.UP))
    }

    @Test
    fun scrollingRightMovesTheFingerLeft() {
        assertEquals(ScreenPoint(800, 1200) to ScreenPoint(400, 1200), SwipePaths.forScroll(area, ScrollDirection.RIGHT))
        assertEquals(ScreenPoint(400, 1200) to ScreenPoint(800, 1200), SwipePaths.forScroll(area, ScrollDirection.LEFT))
    }
}
```

`core/screen/src/test/kotlin/dev/kestrel/screen/act/ScrollActionsTest.kt`:

```kotlin
package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollActionsTest {
    private val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    private val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    private val up = android.R.id.accessibilityActionScrollUp
    private val down = android.R.id.accessibilityActionScrollDown
    private val right = android.R.id.accessibilityActionScrollRight
    private val pageUp = android.R.id.accessibilityActionPageUp
    private val pageDown = android.R.id.accessibilityActionPageDown
    private val pageRight = android.R.id.accessibilityActionPageRight

    @Test
    fun theDirectionalActionsTellTheAxis() {
        assertEquals(ScrollAxis.VERTICAL, ScrollActions.axis(setOf(forward, down), "android.widget.ScrollView", null, null))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward, right), "android.widget.HorizontalScrollView", null, null))
        assertEquals(ScrollAxis.BOTH, ScrollActions.axis(setOf(down, right), "android.webkit.WebView", null, null))
    }

    @Test
    fun withoutDirectionalActionsTheClassOrTheShapeOfTheListTellsTheAxis() {
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "androidx.viewpager.widget.ViewPager", null, null))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "android.support.v4.view.ViewPager", null, null))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "androidx.recyclerview.widget.RecyclerView", 1, 12))
        assertEquals(ScrollAxis.VERTICAL, ScrollActions.axis(setOf(forward), "androidx.recyclerview.widget.RecyclerView", 12, 1))
        assertEquals(ScrollAxis.UNKNOWN, ScrollActions.axis(setOf(forward), "androidx.recyclerview.widget.RecyclerView", 12, 3))
        assertEquals(ScrollAxis.UNKNOWN, ScrollActions.axis(setOf(forward, backward), "android.view.View", null, null))
    }

    @Test
    fun flutterAndComposeListsTellTheirAxisInTheirOwnWays() {
        assertEquals(ScrollAxis.VERTICAL, ScrollActions.axis(setOf(forward), "android.widget.ScrollView", null, null))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "android.view.View", 0, 8))
        assertEquals(ScrollAxis.VERTICAL, ScrollActions.axis(setOf(forward), "android.view.View", 8, 0))
        assertEquals(ScrollAxis.HORIZONTAL, ScrollActions.axis(setOf(forward), "android.view.View", 1, -1))
        assertEquals(ScrollAxis.VERTICAL, ScrollActions.axis(setOf(forward), "android.view.View", -1, 1))
    }

    @Test
    fun forwardAndBackwardAreUsedOnlyAlongAKnownAxis() {
        assertEquals(listOf(down, pageDown, forward), ScrollActions.forDirection(ScrollDirection.DOWN, ScrollAxis.VERTICAL))
        assertEquals(listOf(down, pageDown), ScrollActions.forDirection(ScrollDirection.DOWN, ScrollAxis.HORIZONTAL))
        assertEquals(listOf(right, pageRight, forward), ScrollActions.forDirection(ScrollDirection.RIGHT, ScrollAxis.HORIZONTAL))
        assertEquals(listOf(right, pageRight), ScrollActions.forDirection(ScrollDirection.RIGHT, ScrollAxis.UNKNOWN))
        assertEquals(listOf(up, pageUp, backward), ScrollActions.forDirection(ScrollDirection.UP, ScrollAxis.VERTICAL))
        assertEquals(listOf(up, pageUp), ScrollActions.forDirection(ScrollDirection.UP, ScrollAxis.UNKNOWN))
        assertEquals(listOf(down, pageDown), ScrollActions.forDirection(ScrollDirection.DOWN, ScrollAxis.BOTH))
    }

    @Test
    fun aSidewaysSwipeCrossesOnlyAListThatMovesSideways() {
        assertTrue(ScrollActions.movesAlong(ScrollAxis.HORIZONTAL, ScrollDirection.RIGHT))
        assertFalse(ScrollActions.movesAlong(ScrollAxis.VERTICAL, ScrollDirection.RIGHT))
        assertFalse(ScrollActions.movesAlong(ScrollAxis.UNKNOWN, ScrollDirection.LEFT))
        assertTrue(ScrollActions.movesAlong(ScrollAxis.UNKNOWN, ScrollDirection.DOWN))
        assertFalse(ScrollActions.movesAlong(ScrollAxis.HORIZONTAL, ScrollDirection.DOWN))
        assertTrue(ScrollActions.movesAlong(ScrollAxis.BOTH, ScrollDirection.LEFT))
    }
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/TypeTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypeTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    private suspend fun field(name: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/$name" }

    @Test
    fun setTextUsesTheSetTextActionAndReadsTheTextBack() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(field("message_field").key, "Hello Ana"))
        screen.awaitElement { it.viewId == "$FIXTURES:id/message_field" && it.text == "Hello Ana" }
        Unit
    }

    @Test
    fun aFieldKeepsItsKeyWhileTypingAndCanBeCleared() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val key = field("message_field").key

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(key, "Hello Ana"))
        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(key, ""))
        screen.awaitElement { it.key == key && it.text == null }
        Unit
    }

    @Test
    fun anAppThatRefusesSetTextGetsTheInputMethodFromThenOn() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.INPUT_METHOD), actions.setText(field("strict_field").key, "Strict text"))
        assertEquals(ActionResult.Done(ActionMethod.INPUT_METHOD), actions.setText(field("message_field").key, "Typed text"))
        screen.awaitElement { it.viewId == "$FIXTURES:id/strict_field" && it.text == "Strict text" }
        screen.awaitElement { it.viewId == "$FIXTURES:id/message_field" && it.text == "Typed text" }
        Unit
    }

    @Test
    fun aPasswordIsTypedButNeverReadBack() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(field("password_field").key, "hunter2"))
        screen.session.snapshots.invalidate()
        assertNull(field("password_field").text)
    }

    @Test
    fun aDisabledFieldIsNotTypedIntoAndDoesNotSwitchTheAppToTheInputMethod() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val disabled = screen.awaitElement { it.editable && it.label == "Disabled field" }

        assertEquals(ActionResult.Failed("The element is disabled"), actions.setText(disabled.key, "Hello"))
        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(field("message_field").key, "Hello"))
    }

    @Test
    fun setTextRefusesAnElementThatIsNotATextField() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertTrue(actions.setText(field("send_button").key, "Hello") is ActionResult.Failed)
    }
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/ScrollTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ScrollDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    private suspend fun firstRowIsHidden() {
        screen.awaitElement { it.label == "Row 25" }
        assertFalse(screen.session.snapshots.current().snapshot.elements.any { it.label == "Row 1" })
    }

    @Test
    fun scrollUsesTheListsScrollAction() = runBlocking {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.scroll(ScrollDirection.DOWN))
        firstRowIsHidden()
    }

    @Test
    fun scrollOnARowScrollsTheListAroundIt() = runBlocking {
        screen.open("ScrollActivity", "Scroll fixture")
        val row = screen.awaitElement { it.label == "Row 2" }

        assertEquals(ActionResult.Done(ActionMethod.PARENT_ACTION), actions.scroll(ScrollDirection.DOWN, row.key))
        firstRowIsHidden()
    }

    @Test
    fun scrollSwipesAcrossTheListWhenTheListOffersNoScrollActions() = runBlocking {
        screen.open("ScrollActivity", "Swipe-only scroll fixture", extras = "--ez swipeOnly true")

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), actions.scroll(ScrollDirection.DOWN))
        // The list fills only the upper half of the page, so a swipe across the whole window would miss it.
        withTimeout(5_000) {
            while (screen.session.snapshots.current().snapshot.elements.any { it.label == "Row 1" }) delay(100)
        }
    }

    @Test
    fun scrollRightNeverSwipesAcrossAListThatMovesUpAndDown() = runBlocking {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals(ActionResult.Failed("Nothing on the screen scrolls that way"), actions.scroll(ScrollDirection.RIGHT))
    }

    @Test
    fun scrollDownScrollsTheListInsideAPagerAndNotThePager() = runBlocking {
        screen.open("ScrollActivity", "Pager scroll fixture", extras = "--ez pages true")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.scroll(ScrollDirection.DOWN))
        firstRowIsHidden()
    }

    @Test
    fun scrollDownNeverTurnsAPagerThatHidesItsAxis() = runBlocking {
        screen.open("ScrollActivity", "Opaque pager scroll fixture", extras = "--ez opaquePager true")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.scroll(ScrollDirection.DOWN))
        firstRowIsHidden()
    }

    @Test
    fun scrollRightShowsThePagersNextPage() = runBlocking {
        screen.open("ScrollActivity", "Pager scroll fixture", extras = "--ez pages true")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.scroll(ScrollDirection.RIGHT))
        screen.awaitElement { it.label == "Second page row 1" }
        Unit
    }
}
```

- [ ] **Step 2: Run the unit tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference` errors, for example for `SwipePaths` and `ScrollActions`, from `:core:screen:compileDebugUnitTestKotlin`.

- [ ] **Step 3: Write the swipe paths and the scroll actions, and run the unit tests**

`core/screen/src/main/kotlin/dev/kestrel/screen/act/SwipePaths.kt`:

```kotlin
package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect

internal data class ScreenPoint(val x: Int, val y: Int)

/** Where a swipe that scrolls an area starts and ends. It stays clear of the screen edges, where swipes mean "back" or "home". */
internal object SwipePaths {
    private const val NEAR = 0.3
    private const val FAR = 0.7

    fun forScroll(area: IntRect, direction: ScrollDirection): Pair<ScreenPoint, ScreenPoint> {
        fun x(fraction: Double) = area.left + (area.width * fraction).toInt()
        fun y(fraction: Double) = area.top + (area.height * fraction).toInt()
        return when (direction) {
            ScrollDirection.DOWN -> ScreenPoint(area.centerX, y(FAR)) to ScreenPoint(area.centerX, y(NEAR))
            ScrollDirection.UP -> ScreenPoint(area.centerX, y(NEAR)) to ScreenPoint(area.centerX, y(FAR))
            ScrollDirection.RIGHT -> ScreenPoint(x(FAR), area.centerY) to ScreenPoint(x(NEAR), area.centerY)
            ScrollDirection.LEFT -> ScreenPoint(x(NEAR), area.centerY) to ScreenPoint(x(FAR), area.centerY)
        }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/act/ScrollActions.kt`:

```kotlin
package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityNodeInfo

/** Which way to scroll. "Down" shows what is further down, as in Voice Access. */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

internal val ScrollDirection.isVertical: Boolean get() = this == ScrollDirection.UP || this == ScrollDirection.DOWN

/** The axis along which a list moves, as far as its accessibility node tells. */
internal enum class ScrollAxis { VERTICAL, HORIZONTAL, BOTH, UNKNOWN }

/**
 * Which accessibility actions scroll a list in a direction. Android's forward and backward actions do not say which
 * way they move, so Kestrel uses them only when it knows the list's axis and the axis matches: on a horizontal pager,
 * forward shows the next tab instead of more of the list (design spec, section 9.3).
 */
internal object ScrollActions {
    private const val UP = android.R.id.accessibilityActionScrollUp
    private const val DOWN = android.R.id.accessibilityActionScrollDown
    private const val LEFT = android.R.id.accessibilityActionScrollLeft
    private const val RIGHT = android.R.id.accessibilityActionScrollRight
    private const val PAGE_UP = android.R.id.accessibilityActionPageUp
    private const val PAGE_DOWN = android.R.id.accessibilityActionPageDown
    private const val PAGE_LEFT = android.R.id.accessibilityActionPageLeft
    private const val PAGE_RIGHT = android.R.id.accessibilityActionPageRight
    private const val FORWARD = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    private const val BACKWARD = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

    private val verticalActions = setOf(UP, DOWN, PAGE_UP, PAGE_DOWN)
    private val horizontalActions = setOf(LEFT, RIGHT, PAGE_LEFT, PAGE_RIGHT)
    private val horizontalClasses = setOf(
        "android.widget.HorizontalScrollView",
        "androidx.viewpager.widget.ViewPager",
        "android.support.v4.view.ViewPager",
    )

    // Flutter gives its vertical scrollers this class, with only the forward and backward actions.
    private val verticalClasses = setOf("android.widget.ScrollView")

    /**
     * The axis of a list: from the directional actions that it offers, then from its class, and then from the shape of
     * its collection: a single row or a single column. For the count that such a list does not use, Flutter reports 0
     * and Compose reports -1.
     */
    fun axis(offered: Set<Int>, className: String?, rows: Int?, columns: Int?): ScrollAxis {
        val vertical = offered.any { it in verticalActions }
        val horizontal = offered.any { it in horizontalActions }
        return when {
            vertical && horizontal -> ScrollAxis.BOTH
            vertical -> ScrollAxis.VERTICAL
            horizontal -> ScrollAxis.HORIZONTAL
            className in horizontalClasses -> ScrollAxis.HORIZONTAL
            className in verticalClasses -> ScrollAxis.VERTICAL
            rows.isOne() && columns.isMany() -> ScrollAxis.HORIZONTAL
            columns.isOne() && rows.isMany() -> ScrollAxis.VERTICAL
            else -> ScrollAxis.UNKNOWN
        }
    }

    private fun Int?.isOne() = this == 0 || this == 1

    private fun Int?.isMany() = this != null && this != 0 && this != 1

    /**
     * The actions that scroll a list with [axis] in [direction], in the order to try them. Forward and backward are
     * used only when the list's axis is known and matches: Flutter's pagers hide their axis, and on them forward would
     * turn the page. A list that scrolls both ways offers directional actions, so it never needs forward or backward.
     */
    fun forDirection(direction: ScrollDirection, axis: ScrollAxis): List<Int> {
        val alongTheAxis = when (axis) {
            ScrollAxis.VERTICAL -> direction.isVertical
            ScrollAxis.HORIZONTAL -> !direction.isVertical
            ScrollAxis.BOTH, ScrollAxis.UNKNOWN -> false
        }
        return when (direction) {
            ScrollDirection.DOWN -> listOf(DOWN, PAGE_DOWN) + if (alongTheAxis) listOf(FORWARD) else emptyList()
            ScrollDirection.UP -> listOf(UP, PAGE_UP) + if (alongTheAxis) listOf(BACKWARD) else emptyList()
            ScrollDirection.RIGHT -> listOf(RIGHT, PAGE_RIGHT) + if (alongTheAxis) listOf(FORWARD) else emptyList()
            ScrollDirection.LEFT -> listOf(LEFT, PAGE_LEFT) + if (alongTheAxis) listOf(BACKWARD) else emptyList()
        }
    }

    /**
     * Whether a swipe in [direction] may cross a list with [axis]: a list that moves along that axis, or both ways. A
     * list whose axis is unknown counts as vertical, because a sideways swipe across a row can archive a message or
     * start a call.
     */
    fun movesAlong(axis: ScrollAxis, direction: ScrollDirection): Boolean = when (axis) {
        ScrollAxis.BOTH -> true
        ScrollAxis.HORIZONTAL -> !direction.isVertical
        ScrollAxis.VERTICAL, ScrollAxis.UNKNOWN -> direction.isVertical
    }
}
```

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`. `SwipePathsTest` reports `tests="2"` and `ScrollActionsTest` reports `tests="5"`, without failures.

- [ ] **Step 4: Run the device tests to verify that they fail**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'setText'` and `Unresolved reference 'scroll'` from `:app:compileDebugAndroidTestKotlin`.

- [ ] **Step 5: Write typing and scrolling**

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt` with:

```kotlin
package dev.kestrel.screen.act

/** How Kestrel performed an action (design spec, section 9.3). */
enum class ActionMethod {
    /** The element's own accessibility action. */
    ACTION,

    /** The action of the element's nearest parent that offers it. */
    PARENT_ACTION,

    /** The element's action in a fresh snapshot, after its node had gone or refused the action (design spec, section 14.3). */
    RETRY_ACTION,
    GESTURE,

    /** Kestrel's accessibility input method, for apps that refuse the set-text action (design spec, section 9.7). */
    INPUT_METHOD,
}

sealed interface ActionResult {
    data class Done(val method: ActionMethod) : ActionResult

    data class Failed(val reason: String) : ActionResult
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/Gestures.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path

/** Builds Kestrel's gestures. Coordinates are screen pixels (design spec, section 9.3). */
internal object Gestures {
    private const val TAP_MILLIS = 50L
    private const val LONG_PRESS_MILLIS = 800L
    private const val SWIPE_MILLIS = 500L

    fun tap(x: Int, y: Int): GestureDescription = stroke(point(x, y), TAP_MILLIS)

    fun longPress(x: Int, y: Int): GestureDescription = stroke(point(x, y), LONG_PRESS_MILLIS)

    fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long = SWIPE_MILLIS): GestureDescription =
        stroke(line(from, to), durationMillis)

    private fun point(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private fun line(from: ScreenPoint, to: ScreenPoint) = Path().apply {
        moveTo(from.x.toFloat(), from.y.toFloat())
        lineTo(to.x.toFloat(), to.y.toFloat())
    }

    private fun stroke(path: Path, durationMillis: Long): GestureDescription =
        GestureDescription.Builder().addStroke(StrokeDescription(path, 0, durationMillis)).build()
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). */
class ScreenActions internal constructor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /** Apps that refused the set-text action or did not show the text afterwards (design spec, section 9.7). */
    private val inputMethodApps = ConcurrentHashMap.newKeySet<String>()

    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gesture(Gestures.tap(x, y))

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gesture(Gestures.longPress(x, y))

    /**
     * Replaces the text of the field with [key] by [text]. Kestrel uses the set-text action and reads the text
     * back. If the app refuses the action or the field does not show the text, Kestrel types through its
     * accessibility input method instead, and from then on does so for the whole app (design spec, section 9.7).
     * Kestrel does not type into a disabled field, and a field that has gone does not count as a refusal.
     */
    suspend fun setText(key: String, text: String): ActionResult = setText(key, text, retry = true)

    private suspend fun setText(key: String, text: String, retry: Boolean): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!element.editable) return ActionResult.Failed("The element is not a text field")
        if (!element.enabled) return DISABLED
        val app = element.packageName
        if (app == null || app !in inputMethodApps) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && node.shows(text, element.password)) {
                return settled(ActionMethod.ACTION)
            }
            if (!node.refresh()) {
                // The field has gone, so the app refused nothing: look for it again once (design spec, section 14.3).
                if (!retry) return GONE
                snapshots.invalidate()
                return setText(key, text, retry = false)
            }
            if (!node.isEnabled) return DISABLED
            if (app != null) inputMethodApps += app
        }
        return typeWithInputMethod(element, node, text)
    }

    /**
     * Scrolls the element with [key] or, when [key] is null, a list in the top app window that has one. Kestrel uses
     * the scroll action of the element or of its nearest parent that can scroll in [direction]. Otherwise it swipes
     * across the nearest list that moves along that axis. A sideways swipe across a row can archive a message or
     * start a call, so Kestrel swipes sideways only across a list that moves sideways. Up and down, it swipes across
     * the window when no list moves that way (design spec, section 9.3).
     */
    suspend fun scroll(direction: ScrollDirection, key: String? = null): ActionResult {
        val live = snapshots.current()
        val element = when (key) {
            null -> live.scrollableFor(direction)
            else -> live.snapshot.element(key) ?: return NOT_ON_SCREEN
        }
        val node = element?.let { live.node(it.key) ?: return NOT_ON_SCREEN }
        val target = node?.selfOrParent { it.scrollAction(direction) != null }
        val action = target?.node?.scrollAction(direction)
        if (target != null && action != null && target.node.performAction(action)) {
            return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
        }
        val window = live.snapshot.windowBounds(element?.windowId)
        val list = (target ?: node?.selfOrParent { it.isScrollable && it.movesAlong(direction) })?.node?.screenBounds()
        val area = (if (list != null && window != null) list.intersect(window) else list)
            ?: window?.takeIf { direction.isVertical }
            ?: return ActionResult.Failed("Nothing on the screen scrolls that way")
        val (from, to) = SwipePaths.forScroll(area, direction)
        return gesture(Gestures.swipe(from, to))
    }

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
        val node = snapshots.current().node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh()) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            return gesture(fallback(bounds.centerX, bounds.centerY))
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return GONE
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return gesture(fallback(element.bounds.centerX, element.bounds.centerY))
    }

    /** Focuses the field, waits until it connects to Kestrel's input method, and replaces its text. */
    private suspend fun typeWithInputMethod(element: ScreenElement, node: AccessibilityNodeInfo, text: String): ActionResult {
        val inputMethod = service.inputMethod ?: return ActionResult.Failed("Kestrel's input method is not available")
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = node.isFocused
        if (!wasFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val connection = withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                var ready: InputMethod.AccessibilityInputConnection? = null
                while (ready == null) {
                    val editor = inputMethod.currentInputEditorInfo
                    val started = inputMethod.currentInputStarted && editor?.packageName == element.packageName &&
                        (wasFocused || editor !== before)
                    ready = if (started) inputMethod.currentInputConnection else null
                    if (ready == null) delay(POLL_MILLIS)
                }
                ready
            }
        } ?: return ActionResult.Failed("The text field did not connect to Kestrel's input method")
        // If the app has moved the focus to another field, the input would replace that field's text instead.
        if (!(node.refresh() && node.isFocused)) return ActionResult.Failed("The text field lost the focus")
        withContext(Dispatchers.Main) {
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText(text, 1, null)
        }
        return if (node.shows(text, element.password)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            ActionResult.Failed("The text field did not take the text")
        }
    }

    private suspend fun gesture(description: GestureDescription): ActionResult =
        when (gestures.perform(description)) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
        }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = clock())
        return ActionResult.Done(method)
    }

    /**
     * Whether the field shows [expected] after a short wait. A password field shows only dots, so for it the
     * action's own result has to do.
     */
    private suspend fun AccessibilityNodeInfo.shows(expected: String, password: Boolean): Boolean {
        if (password) return true
        repeat(READ_BACK_TRIES) {
            if (refresh() && shownText().orEmpty() == expected) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /** This node or its nearest parent, up to [MAX_PARENT_DEPTH] levels up, that [matches]. */
    private fun AccessibilityNodeInfo.selfOrParent(matches: (AccessibilityNodeInfo) -> Boolean): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (matches(current)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * The element's node or its nearest parent that handles touches. A finger's touch stops at that view even when it
     * is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? =
        selfOrParent { it.isClickable || it.isLongClickable || it.offers(action) }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    /** The first action that scrolls this node in [direction], if the node offers one and is enabled. */
    private fun AccessibilityNodeInfo.scrollAction(direction: ScrollDirection): Int? {
        if (!isEnabled) return null
        val offered = offeredActions()
        return ScrollActions.forDirection(direction, scrollAxis(offered)).firstOrNull { it in offered }
    }

    /** Whether a swipe in [direction] may cross this list (see [ScrollActions.movesAlong]). */
    private fun AccessibilityNodeInfo.movesAlong(direction: ScrollDirection): Boolean =
        ScrollActions.movesAlong(scrollAxis(offeredActions()), direction)

    private fun AccessibilityNodeInfo.offeredActions(): Set<Int> = actionList.mapTo(HashSet()) { it.id }

    private fun AccessibilityNodeInfo.scrollAxis(offered: Set<Int>): ScrollAxis =
        ScrollActions.axis(offered, className?.toString(), collectionInfo?.rowCount, collectionInfo?.columnCount)

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    /**
     * The list to scroll when the owner names none: in the top app window that has a list, the largest list that can
     * scroll in [direction], or else the largest that moves along that axis, or else the largest list. The snapshot
     * lists its windows from the top down.
     */
    private fun LiveSnapshot.scrollableFor(direction: ScrollDirection): ScreenElement? {
        for (window in snapshot.windows.filter { it.kind == WindowKind.APPLICATION }) {
            val lists = snapshot.elements.filter { it.scrollable && it.enabled && it.windowId == window.id }
            if (lists.isEmpty()) continue
            val canScroll = lists.filter { node(it.key)?.scrollAction(direction) != null }
            val alongTheAxis = lists.filter { node(it.key)?.movesAlong(direction) == true }
            return canScroll.ifEmpty { alongTheAxis }.ifEmpty { lists }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        }
        return null
    }

    /** The bounds of the window with [windowId], or of the top app window when there is no such window. */
    private fun ScreenSnapshot.windowBounds(windowId: Int?): IntRect? =
        (windows.firstOrNull { it.id == windowId } ?: windows.firstOrNull { it.kind == WindowKind.APPLICATION })?.bounds

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
        val GONE = ActionResult.Failed("The element is no longer on the screen")
        const val INPUT_START_MILLIS = 2_000L
        const val READ_BACK_TRIES = 5
        const val POLL_MILLIS = 50L
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` with:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }
}
```

- [ ] **Step 6: Run the device tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 30 test cases (`TypeTest` 6 and `ScrollTest` 7) and no failures.

- [ ] **Step 7: Run the checks**

Run: `scripts\gradle.ps1 check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```powershell
git add core/screen app/src/androidTest testapp
git commit -m "feat(screen): type and scroll with fallbacks" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: Gestures, zoom, global actions and intents

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/act/GlobalAction.kt`
- Create: `core/screen/src/main/AndroidManifest.xml` (lets Kestrel see the apps that have a launcher screen)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt` (add `GLOBAL_ACTION`, `INTENT` and `MAGNIFICATION`), `GestureRunner.kt` (gestures in several parts), `Gestures.kt` (drag and pinch), `ScreenActions.kt` (the new actions)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/GestureTest.kt`, `SystemActionsTest.kt`

**Interfaces:**
- Consumes: `ScreenActions`, `GestureRunner`, `Gestures` and `ScreenPoint` from Tasks 6 and 7.
- Produces: `GestureRunner.perform(vararg parts: GestureDescription): GestureOutcome`, which runs the parts of one gesture in order and stops at a failed part.
- Produces: on `ScreenActions`: `swipe(fromX, fromY, toX, toY)`, `drag(fromX, fromY, toX, toY)`, `pinch(centerX, centerY, startSpan, endSpan)`, `zoomScale: Float`, `zoom(scale: Float, centerX, centerY)`, `resetZoom()`, `global(action: GlobalAction)`, `openApp(packageName: String)` and `openUri(uri: String)`, all returning `ActionResult` except `zoomScale`.
- Produces: `enum class GlobalAction { BACK, HOME, RECENT_APPS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT, POWER_MENU, SPLIT_SCREEN, PLAY_PAUSE }` and `ActionMethod.GLOBAL_ACTION`, `INTENT` and `MAGNIFICATION`.

A drag is a long press that continues into a move, so it is dispatched in two parts with Android's `continueStroke`. Android ends a continued stroke at its last change of position, so the press drifts slowly by 10 pixels, less than a touch slop, to last its whole 800 ms. Zoom uses the magnification controller with `MagnificationConfig`, because `setScale` and `setCenter` are deprecated from API 33. `openApp` starts an app's launcher screen; Android shows Kestrel only the apps that its manifest asks to see, so `:core:screen`'s manifest asks for apps with a launcher activity.

- [ ] **Step 1: Write the failing tests**

`app/src/androidTest/kotlin/dev/kestrel/app/screen/GestureTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions
    private val done = ActionResult.Done(ActionMethod.GESTURE)

    private fun openPad(): ScreenElement {
        screen.open("GesturesActivity", "Gestures fixture")
        return runBlocking { screen.awaitElement { it.label == "Gesture pad" } }
    }

    private suspend fun padReports(gesture: String) = screen.awaitElement { it.label == gesture }

    @Test
    fun tapAndLongPressAtAPoint() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.tapAt(pad.centerX, pad.centerY))
        padReports("Tap")
        assertEquals(done, actions.longPressAt(pad.centerX, pad.centerY))
        padReports("Long press")
        Unit
    }

    @Test
    fun swipeAndDragAreToldApart() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.swipe(pad.centerX, pad.centerY + 300, pad.centerX, pad.centerY - 300))
        padReports("Swipe up")
        assertEquals(done, actions.drag(pad.centerX - 300, pad.centerY, pad.centerX + 300, pad.centerY))
        padReports("Drag right")
        Unit
    }

    @Test
    fun pinchOutAndIn() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.pinch(pad.centerX, pad.centerY, startSpan = 200, endSpan = 700))
        padReports("Pinch out")
        assertEquals(done, actions.pinch(pad.centerX, pad.centerY, startSpan = 700, endSpan = 200))
        padReports("Pinch in")
        Unit
    }

    @Test
    fun zoomMagnifiesTheScreenAndResetZoomUndoesIt() = runBlocking {
        val pad = openPad().bounds

        assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), actions.zoom(2f, pad.centerX, pad.centerY))
        assertEquals(2f, actions.zoomScale)
        assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), actions.resetZoom())
        assertEquals(1f, actions.zoomScale)
    }
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/SystemActionsTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemActionsTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    /** Waits until no app window of the fixture app is on the screen. */
    private suspend fun fixturesAreGone() = withTimeout(5_000) {
        while (screen.session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES }) {
            delay(100)
        }
    }

    /**
     * Waits for the first button of the fixture app's launcher screen. The screen's title is also the app's name, and an
     * icon on the home screen can show that name too.
     */
    private suspend fun launcherScreenIsShown() {
        screen.awaitElement { it.label == "VIEWS" }
    }

    @Test
    fun backClosesADialog() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        actions.tap(screen.awaitElement { it.label == "SHOW DIALOG" }.key)
        screen.awaitElement { it.label == "Delete draft?" }

        assertEquals(ActionResult.Done(ActionMethod.GLOBAL_ACTION), actions.global(GlobalAction.BACK))
        screen.awaitElement { it.label == "Views fixture" }
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Delete draft?" })
    }

    @Test
    fun homeLeavesTheApp() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.GLOBAL_ACTION), actions.global(GlobalAction.HOME))
        fixturesAreGone()
    }

    @Test
    fun openAppStartsAnAppAtItsLauncherScreen() = runBlocking {
        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()

        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openApp(FIXTURES))
        launcherScreenIsShown()
    }

    @Test
    fun openUriOpensTheAppForALink() = runBlocking {
        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()

        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openUri("kestrel-fixtures://start"))
        launcherScreenIsShown()
    }

    @Test
    fun openAppFailsForAnAppThatIsNotInstalled() = runBlocking {
        assertTrue(actions.openApp("dev.kestrel.missing") is ActionResult.Failed)
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD FAILED`, with `Unresolved reference` errors such as `'drag'` and `'GlobalAction'` from `:app:compileDebugAndroidTestKotlin`.

- [ ] **Step 3: Write the new actions**

`core/screen/src/main/kotlin/dev/kestrel/screen/act/GlobalAction.kt`:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService

/** The global actions that Kestrel offers (design spec, section 9.3). */
enum class GlobalAction(internal val id: Int) {
    BACK(AccessibilityService.GLOBAL_ACTION_BACK),
    HOME(AccessibilityService.GLOBAL_ACTION_HOME),
    RECENT_APPS(AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    LOCK_SCREEN(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    SCREENSHOT(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
    POWER_MENU(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG),
    SPLIT_SCREEN(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),

    /** Plays or pauses media, like the button on a headset. */
    PLAY_PAUSE(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK),
}
```

`core/screen/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Opening an app needs to see its launcher activity (design spec, section 13.6). -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>
</manifest>
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt` with:

```kotlin
package dev.kestrel.screen.act

/** How Kestrel performed an action (design spec, section 9.3). */
enum class ActionMethod {
    /** The element's own accessibility action. */
    ACTION,

    /** The action of the element's nearest parent that offers it. */
    PARENT_ACTION,

    /** The element's action in a fresh snapshot, after its node had gone or refused the action (design spec, section 14.3). */
    RETRY_ACTION,
    GESTURE,

    /** Kestrel's accessibility input method, for apps that refuse the set-text action (design spec, section 9.7). */
    INPUT_METHOD,
    GLOBAL_ACTION,
    INTENT,
    MAGNIFICATION,
}

sealed interface ActionResult {
    data class Done(val method: ActionMethod) : ActionResult

    data class Failed(val reason: String) : ActionResult
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/GestureRunner.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What became of a gesture that Kestrel dispatched. */
enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED }

/** Runs Kestrel's own gestures one at a time (design spec, section 9.3). */
class GestureRunner(private val service: AccessibilityService) {
    private val mutex = Mutex()

    /** Runs the [parts] of one gesture in order, such as the hold and the move of a drag, and stops at a failed part. */
    suspend fun perform(vararg parts: GestureDescription): GestureOutcome = mutex.withLock {
        var outcome = GestureOutcome.COMPLETED
        for (part in parts) {
            outcome = dispatch(part)
            if (outcome != GestureOutcome.COMPLETED) break
        }
        outcome
    }

    private suspend fun dispatch(gesture: GestureDescription): GestureOutcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
            }
            if (!service.dispatchGesture(gesture, callback, null)) continuation.resume(GestureOutcome.REJECTED)
        }
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/Gestures.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path

/** Builds Kestrel's gestures. Coordinates are screen pixels (design spec, section 9.3). */
internal object Gestures {
    private const val TAP_MILLIS = 50L
    private const val LONG_PRESS_MILLIS = 800L
    private const val SWIPE_MILLIS = 500L
    private const val DRAG_MOVE_MILLIS = 600L
    private const val PINCH_MILLIS = 600L
    private const val DRAG_DRIFT_PIXELS = 10

    fun tap(x: Int, y: Int): GestureDescription = gesture(StrokeDescription(point(x, y), 0, TAP_MILLIS))

    fun longPress(x: Int, y: Int): GestureDescription = gesture(StrokeDescription(point(x, y), 0, LONG_PRESS_MILLIS))

    fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long = SWIPE_MILLIS): GestureDescription =
        gesture(StrokeDescription(line(from, to), 0, durationMillis))

    /**
     * A drag is a long press that continues into a move, so it is dispatched in two parts. Android ends a
     * continuing stroke at its last change of position, so the press drifts slowly by a few pixels, less than a
     * touch slop, to last its whole time.
     */
    fun drag(from: ScreenPoint, to: ScreenPoint): Array<GestureDescription> {
        val pressed = ScreenPoint(from.x + DRAG_DRIFT_PIXELS, from.y)
        val hold = StrokeDescription(line(from, pressed), 0, LONG_PRESS_MILLIS, true)
        val move = hold.continueStroke(line(pressed, to), 0, DRAG_MOVE_MILLIS, false)
        return arrayOf(gesture(hold), gesture(move))
    }

    /** Two fingers that move apart ([endSpan] larger than [startSpan]) or together, side by side around [center]. */
    fun pinch(center: ScreenPoint, startSpan: Int, endSpan: Int): GestureDescription {
        fun finger(side: Int) = StrokeDescription(
            line(ScreenPoint(center.x + side * startSpan / 2, center.y), ScreenPoint(center.x + side * endSpan / 2, center.y)),
            0,
            PINCH_MILLIS,
        )
        return GestureDescription.Builder().addStroke(finger(-1)).addStroke(finger(1)).build()
    }

    private fun point(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private fun line(from: ScreenPoint, to: ScreenPoint) = Path().apply {
        moveTo(from.x.toFloat(), from.y.toFloat())
        lineTo(to.x.toFloat(), to.y.toFloat())
    }

    private fun gesture(stroke: StrokeDescription): GestureDescription = GestureDescription.Builder().addStroke(stroke).build()
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). */
class ScreenActions internal constructor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /** Apps that refused the set-text action or did not show the text afterwards (design spec, section 9.7). */
    private val inputMethodApps = ConcurrentHashMap.newKeySet<String>()

    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gesture(Gestures.tap(x, y))

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gesture(Gestures.longPress(x, y))

    /**
     * Replaces the text of the field with [key] by [text]. Kestrel uses the set-text action and reads the text
     * back. If the app refuses the action or the field does not show the text, Kestrel types through its
     * accessibility input method instead, and from then on does so for the whole app (design spec, section 9.7).
     * Kestrel does not type into a disabled field, and a field that has gone does not count as a refusal.
     */
    suspend fun setText(key: String, text: String): ActionResult = setText(key, text, retry = true)

    private suspend fun setText(key: String, text: String, retry: Boolean): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!element.editable) return ActionResult.Failed("The element is not a text field")
        if (!element.enabled) return DISABLED
        val app = element.packageName
        if (app == null || app !in inputMethodApps) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && node.shows(text, element.password)) {
                return settled(ActionMethod.ACTION)
            }
            if (!node.refresh()) {
                // The field has gone, so the app refused nothing: look for it again once (design spec, section 14.3).
                if (!retry) return GONE
                snapshots.invalidate()
                return setText(key, text, retry = false)
            }
            if (!node.isEnabled) return DISABLED
            if (app != null) inputMethodApps += app
        }
        return typeWithInputMethod(element, node, text)
    }

    /**
     * Scrolls the element with [key] or, when [key] is null, a list in the top app window that has one. Kestrel uses
     * the scroll action of the element or of its nearest parent that can scroll in [direction]. Otherwise it swipes
     * across the nearest list that moves along that axis. A sideways swipe across a row can archive a message or
     * start a call, so Kestrel swipes sideways only across a list that moves sideways. Up and down, it swipes across
     * the window when no list moves that way (design spec, section 9.3).
     */
    suspend fun scroll(direction: ScrollDirection, key: String? = null): ActionResult {
        val live = snapshots.current()
        val element = when (key) {
            null -> live.scrollableFor(direction)
            else -> live.snapshot.element(key) ?: return NOT_ON_SCREEN
        }
        val node = element?.let { live.node(it.key) ?: return NOT_ON_SCREEN }
        val target = node?.selfOrParent { it.scrollAction(direction) != null }
        val action = target?.node?.scrollAction(direction)
        if (target != null && action != null && target.node.performAction(action)) {
            return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
        }
        val window = live.snapshot.windowBounds(element?.windowId)
        val list = (target ?: node?.selfOrParent { it.isScrollable && it.movesAlong(direction) })?.node?.screenBounds()
        val area = (if (list != null && window != null) list.intersect(window) else list)
            ?: window?.takeIf { direction.isVertical }
            ?: return ActionResult.Failed("Nothing on the screen scrolls that way")
        val (from, to) = SwipePaths.forScroll(area, direction)
        return gesture(Gestures.swipe(from, to))
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult =
        gesture(Gestures.swipe(ScreenPoint(fromX, fromY), ScreenPoint(toX, toY)))

    /** Presses and holds at the first point, then moves to the second point, as when moving an icon. */
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult =
        gesture(*Gestures.drag(ScreenPoint(fromX, fromY), ScreenPoint(toX, toY)))

    /** Moves two fingers apart (when [endSpan] is larger than [startSpan]) or together around a point. */
    suspend fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int): ActionResult =
        gesture(Gestures.pinch(ScreenPoint(centerX, centerY), startSpan, endSpan))

    /** How many times Android's magnification enlarges the screen; 1 when it does not. */
    val zoomScale: Float get() = service.magnificationController.magnificationConfig?.scale ?: 1f

    /** Magnifies the whole screen [scale] times around a point, with Android's magnification (design spec, section 9.3). */
    suspend fun zoom(scale: Float, centerX: Int, centerY: Int): ActionResult {
        val config = MagnificationConfig.Builder()
            .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
            .setScale(scale)
            .setCenterX(centerX.toFloat())
            .setCenterY(centerY.toFloat())
            .build()
        val done = withContext(Dispatchers.Main) { service.magnificationController.setMagnificationConfig(config, false) }
        return if (done) ActionResult.Done(ActionMethod.MAGNIFICATION) else ActionResult.Failed("Android refused to magnify the screen")
    }

    suspend fun resetZoom(): ActionResult {
        val done = withContext(Dispatchers.Main) { service.magnificationController.resetCurrentMagnification(false) }
        return if (done) ActionResult.Done(ActionMethod.MAGNIFICATION) else ActionResult.Failed("Android refused to reset the magnification")
    }

    suspend fun global(action: GlobalAction): ActionResult {
        return if (service.performGlobalAction(action.id)) {
            settled(ActionMethod.GLOBAL_ACTION)
        } else {
            ActionResult.Failed("Android refused the action ${action.name}")
        }
    }

    /** Opens the app with [packageName] as its launcher icon would. */
    suspend fun openApp(packageName: String): ActionResult {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failed("No app with the package $packageName can be opened")
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return settled(ActionMethod.INTENT)
    }

    /** Opens a link or a deep link in the app that handles it. */
    suspend fun openUri(uri: String): ActionResult {
        return try {
            service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app can open the link")
        }
    }

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
        val node = snapshots.current().node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh()) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            return gesture(fallback(bounds.centerX, bounds.centerY))
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return GONE
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return gesture(fallback(element.bounds.centerX, element.bounds.centerY))
    }

    /** Focuses the field, waits until it connects to Kestrel's input method, and replaces its text. */
    private suspend fun typeWithInputMethod(element: ScreenElement, node: AccessibilityNodeInfo, text: String): ActionResult {
        val inputMethod = service.inputMethod ?: return ActionResult.Failed("Kestrel's input method is not available")
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = node.isFocused
        if (!wasFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val connection = withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                var ready: InputMethod.AccessibilityInputConnection? = null
                while (ready == null) {
                    val editor = inputMethod.currentInputEditorInfo
                    val started = inputMethod.currentInputStarted && editor?.packageName == element.packageName &&
                        (wasFocused || editor !== before)
                    ready = if (started) inputMethod.currentInputConnection else null
                    if (ready == null) delay(POLL_MILLIS)
                }
                ready
            }
        } ?: return ActionResult.Failed("The text field did not connect to Kestrel's input method")
        // If the app has moved the focus to another field, the input would replace that field's text instead.
        if (!(node.refresh() && node.isFocused)) return ActionResult.Failed("The text field lost the focus")
        withContext(Dispatchers.Main) {
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText(text, 1, null)
        }
        return if (node.shows(text, element.password)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            ActionResult.Failed("The text field did not take the text")
        }
    }

    private suspend fun gesture(vararg parts: GestureDescription): ActionResult =
        when (gestures.perform(*parts)) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
        }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = clock())
        return ActionResult.Done(method)
    }

    /**
     * Whether the field shows [expected] after a short wait. A password field shows only dots, so for it the
     * action's own result has to do.
     */
    private suspend fun AccessibilityNodeInfo.shows(expected: String, password: Boolean): Boolean {
        if (password) return true
        repeat(READ_BACK_TRIES) {
            if (refresh() && shownText().orEmpty() == expected) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /** This node or its nearest parent, up to [MAX_PARENT_DEPTH] levels up, that [matches]. */
    private fun AccessibilityNodeInfo.selfOrParent(matches: (AccessibilityNodeInfo) -> Boolean): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (matches(current)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * The element's node or its nearest parent that handles touches. A finger's touch stops at that view even when it
     * is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? =
        selfOrParent { it.isClickable || it.isLongClickable || it.offers(action) }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    /** The first action that scrolls this node in [direction], if the node offers one and is enabled. */
    private fun AccessibilityNodeInfo.scrollAction(direction: ScrollDirection): Int? {
        if (!isEnabled) return null
        val offered = offeredActions()
        return ScrollActions.forDirection(direction, scrollAxis(offered)).firstOrNull { it in offered }
    }

    /** Whether a swipe in [direction] may cross this list (see [ScrollActions.movesAlong]). */
    private fun AccessibilityNodeInfo.movesAlong(direction: ScrollDirection): Boolean =
        ScrollActions.movesAlong(scrollAxis(offeredActions()), direction)

    private fun AccessibilityNodeInfo.offeredActions(): Set<Int> = actionList.mapTo(HashSet()) { it.id }

    private fun AccessibilityNodeInfo.scrollAxis(offered: Set<Int>): ScrollAxis =
        ScrollActions.axis(offered, className?.toString(), collectionInfo?.rowCount, collectionInfo?.columnCount)

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    /**
     * The list to scroll when the owner names none: in the top app window that has a list, the largest list that can
     * scroll in [direction], or else the largest that moves along that axis, or else the largest list. The snapshot
     * lists its windows from the top down.
     */
    private fun LiveSnapshot.scrollableFor(direction: ScrollDirection): ScreenElement? {
        for (window in snapshot.windows.filter { it.kind == WindowKind.APPLICATION }) {
            val lists = snapshot.elements.filter { it.scrollable && it.enabled && it.windowId == window.id }
            if (lists.isEmpty()) continue
            val canScroll = lists.filter { node(it.key)?.scrollAction(direction) != null }
            val alongTheAxis = lists.filter { node(it.key)?.movesAlong(direction) == true }
            return canScroll.ifEmpty { alongTheAxis }.ifEmpty { lists }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        }
        return null
    }

    /** The bounds of the window with [windowId], or of the top app window when there is no such window. */
    private fun ScreenSnapshot.windowBounds(windowId: Int?): IntRect? =
        (windows.firstOrNull { it.id == windowId } ?: windows.firstOrNull { it.kind == WindowKind.APPLICATION })?.bounds

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
        val GONE = ActionResult.Failed("The element is no longer on the screen")
        const val INPUT_START_MILLIS = 2_000L
        const val READ_BACK_TRIES = 5
        const val POLL_MILLIS = 50L
    }
}
```

- [ ] **Step 4: Run the device tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 39 test cases (`GestureTest` 4 and `SystemActionsTest` 5) and no failures.

- [ ] **Step 5: Run the checks**

Run: `scripts\gradle.ps1 check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add core/screen app/src/androidTest
git commit -m "feat(screen): add gestures, zoom, global actions and intents" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: The numbers and labels overlays

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/Numbering.kt`
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/overlay/NumberingTest.kt`
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt`, `TagsView.kt`, `NumbersOverlay.kt`, `LabelsOverlay.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (overlays, the content watcher and `close()`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` (close the session)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` (add `showsWindow` and the constant `KESTREL`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/OverlayTest.kt`

**Interfaces:**
- Consumes: `ScreenSession`, `ScreenChangeTracker` and the model from earlier tasks.
- Produces: `enum class NumbersMode { ALL, UNLABELED }`, `data class Badge(number: Int, key: String, bounds: IntRect)` and `object Numbering` with `forScreen(snapshot, mode): List<Badge>` and `forCandidates(candidates: List<ScreenElement>): List<Badge>`.
- Produces: `class OverlayHost(service)` with `context`, `show(view, params)`, `hide(view)`, `hideAll()` and `fullScreen(title): LayoutParams`, and the internal `TagsView`, `NumbersOverlay` and `LabelsOverlay`.
- Produces: on `ScreenSession`: `numberBadges: List<Badge>`, `labelsShowing: Boolean`, `suspend showNumbers(mode = NumbersMode.ALL)`, `suspend showWhichOne(keys: Collection<String>)`, `suspend hideNumbers()`, `suspend showLabels()`, `suspend hideLabels()` and `close()`.
- Produces: `ScreenServiceRule.showsWindow(title: String): Boolean` and the constant `ScreenServiceRule.KESTREL` (`"dev.kestrel"`).

Numbers go on the actionable elements, except the keyboard's keys, in reading order: top to bottom, then left to right. In `UNLABELED` mode, only elements without a label get numbers. The session watches the screen: after each change it waits for 150 ms of quiet and compares the actionable elements' keys and bounds with the last ones. Numbers then follow the new screen, while labels and the "Which one?" numbers go away, because they belong to the screen on which they were asked for (spec section 9.4). The overlays are plain Views in full-screen accessibility overlay windows that take no touches, so touches reach the app below. Each window has a title, such as "Kestrel numbers", by which tests find it in `dumpsys window windows`. `close()` removes every overlay; the service calls it when Android disconnects it.

- [ ] **Step 1: Write the failing tests**

`core/screen/src/test/kotlin/dev/kestrel/screen/overlay/NumberingTest.kt`:

```kotlin
package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberingTest {
    private val screen = IntRect(0, 0, 1080, 2400)

    private fun element(key: String, left: Int, top: Int, label: String? = key, window: Int = 1, clickable: Boolean = true) =
        ScreenElement(key = key, windowId = window, label = label, bounds = IntRect(left, top, left + 100, top + 50), clickable = clickable)

    private val snapshot = ScreenSnapshot(
        takenAtMillis = 0,
        windows = listOf(
            WindowSummary(id = 2, kind = WindowKind.INPUT_METHOD, bounds = screen),
            WindowSummary(id = 1, kind = WindowKind.APPLICATION, bounds = screen),
        ),
        elements = listOf(
            element("send", left = 600, top = 100),
            element("back", left = 0, top = 100),
            element("title", left = 0, top = 0, clickable = false),
            element("icon", left = 0, top = 300, label = null),
            element("key q", left = 0, top = 2000, window = 2),
        ),
    )

    @Test
    fun actionableElementsAreNumberedInReadingOrderWithoutTheKeyboard() {
        val badges = Numbering.forScreen(snapshot, NumbersMode.ALL)

        assertEquals(listOf(1 to "back", 2 to "send", 3 to "icon"), badges.map { it.number to it.key })
    }

    @Test
    fun unlabeledModeNumbersOnlyElementsWithoutAName() {
        assertEquals(listOf("icon"), Numbering.forScreen(snapshot, NumbersMode.UNLABELED).map { it.key })
    }

    @Test
    fun candidatesAreNumberedFromOne() {
        val badges = Numbering.forCandidates(snapshot.elements.filter { it.key in setOf("send", "back") })

        assertEquals(listOf(1 to "back", 2 to "send"), badges.map { it.number to it.key })
    }
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/OverlayTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(100)
    }

    private suspend fun keyOf(label: String) = screen.awaitElement { it.label == label }.key

    private suspend fun showDialog() {
        session.actions.tap(keyOf("SHOW DIALOG"))
        screen.awaitElement { it.label == "Delete draft?" }
    }

    @Test
    fun numbersGoOnTheActionableElementsInReadingOrder() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers()

        val badges = session.numberBadges
        assertEquals(keyOf("SEND"), badges.first().key)
        assertEquals((1..badges.size).toList(), badges.map { it.number })
        eventually { screen.showsWindow("Kestrel numbers") }
        assertTrue(session.snapshots.current().snapshot.windows.none { it.packageName == KESTREL })
    }

    @Test
    fun unlabeledModeNumbersOnlyTheIconWithoutAName() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers(NumbersMode.UNLABELED)

        val icon = screen.awaitElement { it.viewId == "$FIXTURES:id/compose_button" }
        assertEquals(listOf(icon.key), session.numberBadges.map { it.key })
    }

    @Test
    fun touchesReachTheAppWhileNumbersAreShown() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        val target = screen.awaitElement { it.label == "Touch target" }

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), session.actions.tapAt(target.bounds.centerX, target.bounds.centerY))
        screen.awaitElement { it.label == "Touched target" }
        Unit
    }

    @Test
    fun numbersFollowTheScreenUntilTheyAreHidden() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()

        showDialog()

        val keep = keyOf("KEEP")
        eventually { session.numberBadges.any { it.key == keep } }
        session.hideNumbers()
        eventually { !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun whichOneNumbersOnlyTheCandidatesAndGoesWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = keyOf("SEND")
        val rebuild = keyOf("REBUILD")

        session.showWhichOne(listOf(rebuild, send))

        assertEquals(listOf(1 to send, 2 to rebuild), session.numberBadges.map { it.number to it.key })
        showDialog()
        eventually { session.numberBadges.isEmpty() && !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun labelsGoWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showLabels()

        assertTrue(session.labelsShowing)
        eventually { screen.showsWindow("Kestrel labels") }
        showDialog()
        eventually { !session.labelsShowing && !screen.showsWindow("Kestrel labels") }
    }

    @Test
    fun turningTheServiceOffRemovesTheOverlays() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        eventually { screen.showsWindow("Kestrel numbers") }

        screen.shell("settings delete secure enabled_accessibility_services")

        eventually { !screen.showsWindow("Kestrel numbers") }
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` with:

```kotlin
package dev.kestrel.app.screen

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * Gives each test a new screen session by turning Kestrel's accessibility service off and on again, so that
 * nothing one test leaves behind affects the next. The tests run only on the test emulator and the A71
 * (design spec, D20), where the service may stay on afterwards.
 */
class ScreenServiceRule : ExternalResource() {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    lateinit var session: ScreenSession
        private set

    val automation: UiAutomation get() = Companion.automation

    override fun before() {
        runBlocking {
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }
            shell("settings put secure enabled_accessibility_services $SERVICE")
            shell("settings put secure accessibility_enabled 1")
            session = withTimeout(10_000) { graph.screen.filterNotNull().first() }
        }
    }

    /** Runs a command as the shell user, which may change secure settings and start other apps' activities. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * Starts an activity of the fixture app and waits until its [title] is on the screen. The fixture app is
     * stopped first, because Android would otherwise only bring its existing task to the front.
     */
    fun open(activity: String, title: String, extras: String = ""): ScreenElement {
        shell("am start -W -S -n $FIXTURES/.$activity $extras")
        return runBlocking { awaitElement { it.label == title } }
    }

    /** Whether Android's window list has a window with [title], such as "Kestrel numbers". */
    fun showsWindow(title: String): Boolean = shell("dumpsys window windows").contains("$title}")

    /** Waits until an element that matches [predicate] is on the screen. */
    suspend fun awaitElement(timeoutMillis: Long = 5_000, predicate: (ScreenElement) -> Boolean): ScreenElement {
        var labels: List<String?> = emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            var found: ScreenElement? = null
            while (found == null) {
                val elements = session.snapshots.current().snapshot.elements
                labels = elements.map { it.label }
                found = elements.firstOrNull(predicate)
                if (found == null) delay(100)
            }
            found
        } ?: throw AssertionError("No matching element appeared within $timeoutMillis ms. The screen showed $labels")
    }

    companion object {
        const val FIXTURES = "dev.kestrel.testapp"
        const val KESTREL = "dev.kestrel"
        private const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"

        // Connecting UiAutomation without this flag would turn off every accessibility service, Kestrel's included.
        private val automation: UiAutomation by lazy {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }
    }
}
```

- [ ] **Step 2: Run the unit test to verify that it fails**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'Numbering'` from `:core:screen:compileDebugUnitTestKotlin`.

- [ ] **Step 3: Write the numbering and run the unit test**

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/Numbering.kt`:

```kotlin
package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind

/** Which elements get numbers, as in Voice Access (design spec, sections 9.4 and 9.8). */
enum class NumbersMode { ALL, UNLABELED }

/** A number on an element. */
data class Badge(val number: Int, val key: String, val bounds: IntRect)

object Numbering {
    /** Numbers the actionable elements, except the keyboard's keys, in reading order: top to bottom, then left to right. */
    fun forScreen(snapshot: ScreenSnapshot, mode: NumbersMode): List<Badge> {
        val keyboard = snapshot.windows.filter { it.kind == WindowKind.INPUT_METHOD }.map { it.id }.toSet()
        return number(
            snapshot.elements.filter { element ->
                element.isActionable && element.windowId !in keyboard && (mode == NumbersMode.ALL || element.label == null)
            },
        )
    }

    /** Numbers only [candidates], for "Which one?" (design spec, section 9.4). */
    fun forCandidates(candidates: List<ScreenElement>): List<Badge> = number(candidates)

    private fun number(elements: List<ScreenElement>): List<Badge> =
        elements.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .mapIndexed { index, element -> Badge(index + 1, element.key, element.bounds) }
}
```

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, and `NumberingTest` reports `tests="3"` without failures.

- [ ] **Step 4: Run the device tests to verify that they fail**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'showNumbers'` and similar errors from `:app:compileDebugAndroidTestKotlin`.

- [ ] **Step 5: Write the overlays**

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * Shows Kestrel's overlay windows. They are accessibility overlays, so Kestrel needs no "display over other apps"
 * permission, and snapshots leave them out (design spec, sections 9.2 and 9.4). Use it on the main thread.
 */
class OverlayHost(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val shown = mutableSetOf<View>()

    val context: Context get() = service

    fun show(view: View, params: LayoutParams) {
        if (shown.add(view)) windowManager.addView(view, params) else windowManager.updateViewLayout(view, params)
    }

    fun hide(view: View) {
        // Android removes the windows itself when the service disconnects.
        if (shown.remove(view) && view.isAttachedToWindow) windowManager.removeView(view)
    }

    fun hideAll() {
        shown.toList().forEach(::hide)
    }

    companion object {
        /**
         * A window over the whole screen, system bars included, that lets every touch through to the apps below.
         * The [title] names the window in Android's window list.
         */
        fun fullScreen(title: String): LayoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            setTitle(title)
        }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/TagsView.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import dev.kestrel.screen.model.IntRect

/** A short text drawn at the top-left corner of an element. */
internal data class Tag(val text: String, val bounds: IntRect)

/** Draws tags over a full-screen overlay. The element bounds are in screen coordinates. */
internal class TagsView(context: Context) : View(context) {
    var tags: List<Tag> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var fillColor: Int
        get() = fill.color
        set(value) {
            fill.color = value
            invalidate()
        }

    private val padding = dp(4f)
    private val corner = dp(4f)
    private val origin = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        for (tag in tags) {
            val left = (tag.bounds.left - origin[0]).toFloat()
            val top = (tag.bounds.top - origin[1]).toFloat()
            val right = left + text.measureText(tag.text) + 2 * padding
            val bottom = top + text.descent() - text.ascent() + 2 * padding
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, fill)
            canvas.drawText(tag.text, left + padding, top + padding - text.ascent(), text)
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/NumbersOverlay.kt`:

```kotlin
package dev.kestrel.screen.overlay

/** Numbers on elements, for "tap 5" (design spec, section 9.4). Use it on the main thread. */
internal class NumbersOverlay(private val host: OverlayHost) {
    private val view = TagsView(host.context).apply { fillColor = NUMBER_COLOR }

    /** The numbers on the screen now; empty while the overlay is hidden. */
    var badges: List<Badge> = emptyList()
        private set

    fun show(badges: List<Badge>) {
        this.badges = badges
        view.tags = badges.map { Tag(it.number.toString(), it.bounds) }
        host.show(view, OverlayHost.fullScreen("Kestrel numbers"))
    }

    fun hide() {
        badges = emptyList()
        host.hide(view)
    }

    private companion object {
        const val NUMBER_COLOR = 0xE61565C0.toInt()
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/LabelsOverlay.kt`:

```kotlin
package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.ScreenElement

/** Names next to elements: their labels, or names derived from their view IDs (design spec, section 9.4). */
internal class LabelsOverlay(private val host: OverlayHost) {
    private val view = TagsView(host.context).apply { fillColor = LABEL_COLOR }

    /** Whether the labels are on the screen now. */
    var isShowing: Boolean = false
        private set

    fun show(elements: List<ScreenElement>) {
        view.tags = elements.mapNotNull { element -> (element.label ?: element.suggestedName)?.let { Tag(it, element.bounds) } }
        host.show(view, OverlayHost.fullScreen("Kestrel labels"))
        isShowing = true
    }

    fun hide() {
        isShowing = false
        host.hide(view)
    }

    private companion object {
        const val LABEL_COLOR = 0xE6424242.toInt()
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` with:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.LabelsOverlay
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.screen.overlay.NumbersOverlay
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

    // These fields change only on the main thread.
    /** Set while "show numbers" is on: the numbers then follow the screen. */
    private var numbersMode: NumbersMode? = null

    /** The screen content when the labels appeared. They go when it changes. */
    private var labelsShownOn: List<Pair<String, IntRect>>? = null

    /** The screen content when the "Which one?" numbers appeared. They go when it changes. */
    private var whichOneShownOn: List<Pair<String, IntRect>>? = null

    /** The numbers on the screen now, for commands such as "tap 5". */
    val numberBadges: List<Badge> get() = numbers.badges

    val labelsShowing: Boolean get() = labels.isShowing

    init {
        scope.launch {
            tracker.version.collectLatest {
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collectLatest
                tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
                onScreenSettled(snapshots.current().snapshot)
            }
        }
    }

    /** Shows numbers on the actionable elements. They follow the screen until [hideNumbers] (design spec, section 9.4). */
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
        numbersMode = mode
        numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    /** Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes. */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        numbersMode = null
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        numbers.hide()
    }

    /** Shows names next to the actionable elements until the screen content changes (design spec, section 9.4). */
    suspend fun showLabels() = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        labelsShownOn = contentOf(snapshot)
        labels.show(snapshot.elements.filter { it.isActionable })
    }

    suspend fun hideLabels() = withContext(Dispatchers.Main) {
        labelsShownOn = null
        labels.hide()
    }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }

    /** Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects. */
    fun close() {
        scope.cancel()
        overlays.hideAll()
    }

    private fun onScreenSettled(snapshot: ScreenSnapshot) {
        val content = contentOf(snapshot)
        if (labelsShownOn != null && content != labelsShownOn) {
            labelsShownOn = null
            labels.hide()
        }
        if (whichOneShownOn != null && content != whichOneShownOn) {
            whichOneShownOn = null
            numbers.hide()
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) numbers.show(badges)
        }
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }
}
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` with:

```kotlin
package dev.kestrel.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reads the screen and acts on it for Kestrel (design spec, sections 7.2 and 9). */
class KestrelAccessibilityService : AccessibilityService() {
    private val graph: AppGraph get() = (application as KestrelApplication).graph
    private var session: ScreenSession? = null
    private var purging: Job? = null

    override fun onServiceConnected() {
        val connected = ScreenSession(this)
        session = connected
        graph.attachScreen(connected)
        // The service runs for days at a time, so it also keeps the logs within their 7 days (design spec, section 13.5).
        purging = graph.scope.launch {
            while (isActive) {
                graph.logs.purgeExpired()
                delay(PURGE_INTERVAL_MILLIS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        session?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        purging?.cancel()
        purging = null
        session?.let {
            graph.detachScreen(it)
            it.close()
        }
        session = null
    }

    private companion object {
        const val PURGE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
```

- [ ] **Step 6: Run the device tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 46 test cases (`OverlayTest` 7) and no failures.

- [ ] **Step 7: Run the checks**

Run: `scripts\gradle.ps1 check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```powershell
git add core/screen app
git commit -m "feat(screen): add the numbers and labels overlays" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 10: The grid, the feedback bar and the confirmation card

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/Grid.kt`
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/overlay/GridTest.kt`
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/GridOverlay.kt`, `FeedbackBar.kt`, `ConfirmationCard.kt`
- Create: `core/screen/src/main/res/values/strings.xml`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt` (add `screenBounds()`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (the new overlays)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` (add `overlayButton`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/GridBarAndCardTest.kt`

**Interfaces:**
- Consumes: `OverlayHost`, `TagsView` and `ScreenSession` from Task 9.
- Produces: `data class Grid(area: IntRect, columns: Int)` with `rows`, `cellCount`, `cell(number): IntRect`, `more()`, `fewer()` and `Grid.over(area)` (4 columns); `Grid.COLUMN_STEPS` is 2, 3, 4, 6, 8 and 12.
- Produces: `OverlayHost.screenBounds(): IntRect`.
- Produces: on `ScreenSession`: `grid: Grid?`, `feedbackText: String?`, `suspend showGrid(grid: Grid? = null)`, `suspend hideGrid()`, `suspend showFeedback(text: String)` and `suspend confirm(question: String): Boolean`.
- Produces: `ScreenServiceRule.overlayButton(text: String): Rect?`.

The grid's cells are roughly square, numbered row by row, and together they cover the whole area. The feedback bar is a thin overlay at the top of the screen; it never takes touches, and its text fades after 4 s (spec section 8.9). The confirmation card is the only overlay that takes touches, and only on itself: touches elsewhere reach the app. `confirm` suspends until the owner taps Confirm (true) or Cancel (false); cancelling the call removes the card. The 15 s timeout of spec section 12.4 belongs to the caller, which sub-project 5 adds. Tests find the card's buttons through UiAutomation's list of interactive windows, which contains touchable accessibility overlays.

- [ ] **Step 1: Write the failing tests**

`core/screen/src/test/kotlin/dev/kestrel/screen/overlay/GridTest.kt`:

```kotlin
package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GridTest {
    private val screen = IntRect(0, 0, 1080, 2400)
    private val grid = Grid.over(screen)

    @Test
    fun cellsAreRoughlySquareAndNumberedRowByRow() {
        assertEquals(4, grid.columns)
        assertEquals(9, grid.rows)
        assertEquals(IntRect(0, 0, 270, 266), grid.cell(1))
        assertEquals(IntRect(270, 0, 540, 266), grid.cell(2))
        assertEquals(IntRect(0, 266, 270, 533), grid.cell(5))
    }

    @Test
    fun theCellsCoverTheWholeAreaWithoutGaps() {
        assertEquals(IntRect(810, 2133, 1080, 2400), grid.cell(grid.cellCount))
        for (number in 1 until grid.cellCount) {
            if (number % grid.columns != 0) assertEquals(grid.cell(number).right, grid.cell(number + 1).left)
        }
        assertThrows(IllegalArgumentException::class.java) { grid.cell(grid.cellCount + 1) }
    }

    @Test
    fun moreAndFewerSquaresStopAtTheEnds() {
        assertEquals(6, grid.more().columns)
        assertEquals(3, grid.fewer().columns)
        assertEquals(12, Grid(screen, 12).more().columns)
        assertEquals(2, Grid(screen, 2).fewer().columns)
    }
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/GridBarAndCardTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GridBarAndCardTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun eventually(timeoutMillis: Long = 5_000, check: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!check()) delay(100)
    }

    @Test
    fun theGridCoversTheScreenAndLetsTouchesThrough() = runBlocking {
        screen.open("GesturesActivity", "Gestures fixture")
        val pad = screen.awaitElement { it.label == "Gesture pad" }.bounds

        session.showGrid()

        val grid = session.grid!!
        assertEquals(4, grid.columns)
        eventually { screen.showsWindow("Kestrel grid") }
        val cell = (1..grid.cellCount).map(grid::cell).first { pad.intersect(it) == it }
        session.actions.tapAt(cell.centerX, cell.centerY)
        screen.awaitElement { it.label == "Tap" }
        session.hideGrid()
        eventually { !screen.showsWindow("Kestrel grid") }
    }

    @Test
    fun theFeedbackBarShowsTextAndFadesAway() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showFeedback("Listening")

        assertEquals("Listening", session.feedbackText)
        eventually { screen.showsWindow("Kestrel feedback bar") }
        eventually(timeoutMillis = 8_000) { session.feedbackText == null && !screen.showsWindow("Kestrel feedback bar") }
    }

    @Test
    fun theCardSaysTrueWhenTheOwnerTapsConfirm() = runBlocking { answerWith("Confirm", expected = true) }

    @Test
    fun theCardSaysFalseWhenTheOwnerTapsCancel() = runBlocking { answerWith("Cancel", expected = false) }

    @Test
    fun cancellingTheQuestionRemovesTheCard() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Delete the draft?") }
        eventually { screen.showsWindow("Kestrel confirmation") }

        answer.cancel()

        eventually { !screen.showsWindow("Kestrel confirmation") }
    }

    private suspend fun CoroutineScope.answerWith(button: String, expected: Boolean) {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Send a test message?") }
        val bounds = withTimeout(5_000) {
            var found = screen.overlayButton(button)
            while (found == null) {
                delay(100)
                found = screen.overlayButton(button)
            }
            found
        }

        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

        assertEquals(expected, withTimeout(5_000) { answer.await() })
        eventually { !screen.showsWindow("Kestrel confirmation") }
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` with:

```kotlin
package dev.kestrel.app.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * Gives each test a new screen session by turning Kestrel's accessibility service off and on again, so that
 * nothing one test leaves behind affects the next. The tests run only on the test emulator and the A71
 * (design spec, D20), where the service may stay on afterwards.
 */
class ScreenServiceRule : ExternalResource() {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    lateinit var session: ScreenSession
        private set

    val automation: UiAutomation get() = Companion.automation

    override fun before() {
        runBlocking {
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }
            shell("settings put secure enabled_accessibility_services $SERVICE")
            shell("settings put secure accessibility_enabled 1")
            session = withTimeout(10_000) { graph.screen.filterNotNull().first() }
        }
    }

    /** Runs a command as the shell user, which may change secure settings and start other apps' activities. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * Starts an activity of the fixture app and waits until its [title] is on the screen. The fixture app is
     * stopped first, because Android would otherwise only bring its existing task to the front.
     */
    fun open(activity: String, title: String, extras: String = ""): ScreenElement {
        shell("am start -W -S -n $FIXTURES/.$activity $extras")
        return runBlocking { awaitElement { it.label == title } }
    }

    /** Whether Android's window list has a window with [title], such as "Kestrel numbers". */
    fun showsWindow(title: String): Boolean = shell("dumpsys window windows").contains("$title}")

    /**
     * The bounds of a button on one of Kestrel's touchable overlays, such as the confirmation card, as another
     * accessibility client sees it. Overlays that take no touches are not in the list of interactive windows.
     */
    fun overlayButton(text: String): Rect? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val button = window.root?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: continue
            return Rect().also(button::getBoundsInScreen)
        }
        return null
    }

    /** Waits until an element that matches [predicate] is on the screen. */
    suspend fun awaitElement(timeoutMillis: Long = 5_000, predicate: (ScreenElement) -> Boolean): ScreenElement {
        var labels: List<String?> = emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            var found: ScreenElement? = null
            while (found == null) {
                val elements = session.snapshots.current().snapshot.elements
                labels = elements.map { it.label }
                found = elements.firstOrNull(predicate)
                if (found == null) delay(100)
            }
            found
        } ?: throw AssertionError("No matching element appeared within $timeoutMillis ms. The screen showed $labels")
    }

    companion object {
        const val FIXTURES = "dev.kestrel.testapp"
        const val KESTREL = "dev.kestrel"
        private const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"

        // Connecting UiAutomation without this flag would turn off every accessibility service, Kestrel's included.
        private val automation: UiAutomation by lazy {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
                serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            }
        }
    }
}
```

- [ ] **Step 2: Run the unit test to verify that it fails**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'Grid'` from `:core:screen:compileDebugUnitTestKotlin`.

- [ ] **Step 3: Write the grid and run the unit test**

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/Grid.kt`:

```kotlin
package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect

/**
 * Numbered cells over an area of the screen, for tapping and gestures where no element fits (design spec,
 * section 9.4). The cells are roughly square, and "more squares" and "fewer squares" step through [COLUMN_STEPS].
 */
data class Grid(val area: IntRect, val columns: Int) {
    val rows: Int = maxOf(1, ((columns.toLong() * area.height + area.width / 2) / area.width).toInt())

    val cellCount: Int get() = rows * columns

    /** The bounds of cell [number], counted from 1, row by row. The cells cover the whole area. */
    fun cell(number: Int): IntRect {
        require(number in 1..cellCount) { "The grid has no cell $number" }
        val row = (number - 1) / columns
        val column = (number - 1) % columns
        return IntRect(x(column), y(row), x(column + 1), y(row + 1))
    }

    fun more(): Grid = copy(columns = COLUMN_STEPS.firstOrNull { it > columns } ?: columns)

    fun fewer(): Grid = copy(columns = COLUMN_STEPS.lastOrNull { it < columns } ?: columns)

    private fun x(column: Int) = area.left + area.width * column / columns

    private fun y(row: Int) = area.top + area.height * row / rows

    companion object {
        val COLUMN_STEPS = listOf(2, 3, 4, 6, 8, 12)
        private const val DEFAULT_COLUMNS = 4

        fun over(area: IntRect) = Grid(area, DEFAULT_COLUMNS)
    }
}
```

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, and `GridTest` reports `tests="3"` without failures.

- [ ] **Step 4: Run the device tests to verify that they fail**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD FAILED`, with `Unresolved reference 'showGrid'` and similar errors from `:app:compileDebugAndroidTestKotlin`.

- [ ] **Step 5: Write the overlays**

`core/screen/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="screen_confirm">Confirm</string>
    <string name="screen_cancel">Cancel</string>
</resources>
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/GridOverlay.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/** The grid overlay (design spec, section 9.4). Use it on the main thread. */
internal class GridOverlay(private val host: OverlayHost) {
    private val view = GridView(host.context)

    /** The grid on the screen now, or null while it is hidden. */
    var grid: Grid? = null
        private set

    fun show(grid: Grid) {
        this.grid = grid
        view.grid = grid
        host.show(view, OverlayHost.fullScreen("Kestrel grid"))
    }

    fun hide() {
        grid = null
        host.hide(view)
    }
}

/** Draws the grid's lines and a number in the middle of each cell. */
internal class GridView(context: Context) : View(context) {
    var grid: Grid? = null
        set(value) {
            field = value
            invalidate()
        }

    private val origin = IntArray(2)
    private val line = Paint().apply {
        color = 0xCC1565C0.toInt()
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val grid = grid ?: return
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        for (cell in 1..grid.cellCount) {
            val bounds = grid.cell(cell)
            canvas.drawLine(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top.toFloat(), line)
            canvas.drawLine(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left.toFloat(), bounds.bottom.toFloat(), line)
            val baseline = bounds.centerY - (number.ascent() + number.descent()) / 2
            canvas.drawText(cell.toString(), bounds.centerX.toFloat(), baseline, number)
        }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/FeedbackBar.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import android.widget.TextView

/**
 * A thin bar at the top of the screen with what Kestrel heard and does. It never takes touches, and its text
 * fades after a few seconds without a new one (design spec, section 8.9). Use it on the main thread.
 */
internal class FeedbackBar(private val host: OverlayHost, private val fadeAfterMillis: Long = FADE_AFTER_MILLIS) {
    private val handler = Handler(Looper.getMainLooper())
    private val fade = Runnable { view.animate().alpha(0f).setDuration(FADE_MILLIS).withEndAction(::hide) }
    private val view = TextView(host.context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC000000.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
        setPadding(padding, padding, padding, padding)
    }

    /** The text on the bar now, or null while the bar is hidden. */
    var text: String? = null
        private set

    fun show(text: String) {
        this.text = text
        handler.removeCallbacks(fade)
        view.animate().cancel()
        view.alpha = 1f
        view.text = text
        host.show(view, params())
        handler.postDelayed(fade, fadeAfterMillis)
    }

    fun hide() {
        handler.removeCallbacks(fade)
        text = null
        host.hide(view)
    }

    private fun params() = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        setTitle("Kestrel feedback bar")
    }

    private companion object {
        const val FADE_AFTER_MILLIS = 4_000L
        const val FADE_MILLIS = 300L
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/ConfirmationCard.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.kestrel.screen.R
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A card at the bottom of the screen with a question and Confirm and Cancel buttons (design spec, section 12.4).
 * Unlike the other overlays it takes touches, but only on itself. Use it on the main thread.
 */
internal class ConfirmationCard(private val host: OverlayHost) {
    private var pending: CancellableContinuation<Boolean>? = null

    /** Shows [question] and returns true when the owner taps Confirm, or false for Cancel. */
    suspend fun ask(question: String): Boolean = suspendCancellableCoroutine { continuation ->
        dismiss()
        lateinit var card: LinearLayout
        fun answer(confirmed: Boolean) {
            host.hide(card)
            pending = null
            if (continuation.isActive) continuation.resume(confirmed)
        }
        card = build(question, onConfirm = { answer(true) }, onCancel = { answer(false) })
        // Cancellation can arrive on any thread, and the window must be removed on the main thread.
        continuation.invokeOnCancellation { card.post { host.hide(card) } }
        pending = continuation
        host.show(card, params())
    }

    /** Ends an open question without an answer; the caller's [ask] is cancelled. */
    fun dismiss() {
        pending?.cancel()
        pending = null
    }

    private fun build(question: String, onConfirm: () -> Unit, onCancel: () -> Unit): LinearLayout {
        val context = host.context
        val padding = dp(16f).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16f)
            }
            elevation = dp(8f)
            addView(TextView(context).apply {
                text = question
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(Button(context).apply {
                    setText(R.string.screen_cancel)
                    setOnClickListener { onCancel() }
                })
                addView(Button(context).apply {
                    setText(R.string.screen_confirm)
                    setOnClickListener { onConfirm() }
                })
            })
        }
    }

    private fun params() = LayoutParams(
        host.context.resources.displayMetrics.widthPixels - 2 * dp(16f).toInt(),
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touches outside the card go to the app below; Kestrel's touch watcher reports them (section 14.1).
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        fitInsetsTypes = WindowInsets.Type.systemBars()
        y = dp(16f).toInt()
        setTitle("Kestrel confirmation")
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, host.context.resources.displayMetrics)
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt` with:

```kotlin
package dev.kestrel.screen.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import dev.kestrel.screen.model.IntRect

/**
 * Shows Kestrel's overlay windows. They are accessibility overlays, so Kestrel needs no "display over other apps"
 * permission, and snapshots leave them out (design spec, sections 9.2 and 9.4). Use it on the main thread.
 */
class OverlayHost(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val shown = mutableSetOf<View>()

    val context: Context get() = service

    fun show(view: View, params: LayoutParams) {
        if (shown.add(view)) windowManager.addView(view, params) else windowManager.updateViewLayout(view, params)
    }

    fun hide(view: View) {
        // Android removes the windows itself when the service disconnects.
        if (shown.remove(view) && view.isAttachedToWindow) windowManager.removeView(view)
    }

    /** The whole screen, system bars included, in screen pixels. */
    fun screenBounds(): IntRect = windowManager.maximumWindowMetrics.bounds.let { IntRect(it.left, it.top, it.right, it.bottom) }

    fun hideAll() {
        shown.toList().forEach(::hide)
    }

    companion object {
        /**
         * A window over the whole screen, system bars included, that lets every touch through to the apps below.
         * The [title] names the window in Android's window list.
         */
        fun fullScreen(title: String): LayoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            setTitle(title)
        }
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` with:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.ConfirmationCard
import dev.kestrel.screen.overlay.FeedbackBar
import dev.kestrel.screen.overlay.Grid
import dev.kestrel.screen.overlay.GridOverlay
import dev.kestrel.screen.overlay.LabelsOverlay
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.screen.overlay.NumbersOverlay
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    private val gridOverlay = GridOverlay(overlays)
    private val feedback = FeedbackBar(overlays)
    private val card = ConfirmationCard(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

    // These fields change only on the main thread.
    /** Set while "show numbers" is on: the numbers then follow the screen. */
    private var numbersMode: NumbersMode? = null

    /** The screen content when the labels appeared. They go when it changes. */
    private var labelsShownOn: List<Pair<String, IntRect>>? = null

    /** The screen content when the "Which one?" numbers appeared. They go when it changes. */
    private var whichOneShownOn: List<Pair<String, IntRect>>? = null

    /** The numbers on the screen now, for commands such as "tap 5". */
    val numberBadges: List<Badge> get() = numbers.badges

    val labelsShowing: Boolean get() = labels.isShowing

    /** The grid on the screen now, or null. "Tap 5" taps the centre of the grid's cell 5. */
    val grid: Grid? get() = gridOverlay.grid

    /** The feedback bar's text now, or null while the bar is hidden. */
    val feedbackText: String? get() = feedback.text

    init {
        scope.launch {
            tracker.version.collectLatest {
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collectLatest
                tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
                onScreenSettled(snapshots.current().snapshot)
            }
        }
    }

    /** Shows numbers on the actionable elements. They follow the screen until [hideNumbers] (design spec, section 9.4). */
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
        numbersMode = mode
        numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    /** Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes. */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        numbersMode = null
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        numbers.hide()
    }

    /** Shows names next to the actionable elements until the screen content changes (design spec, section 9.4). */
    suspend fun showLabels() = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        labelsShownOn = contentOf(snapshot)
        labels.show(snapshot.elements.filter { it.isActionable })
    }

    suspend fun hideLabels() = withContext(Dispatchers.Main) {
        labelsShownOn = null
        labels.hide()
    }

    /** Shows [grid], by default one over the whole screen. "More squares" shows `grid.more()` (design spec, section 9.4). */
    suspend fun showGrid(grid: Grid? = null) = withContext(Dispatchers.Main) {
        gridOverlay.show(grid ?: Grid.over(overlays.screenBounds()))
    }

    suspend fun hideGrid() = withContext(Dispatchers.Main) { gridOverlay.hide() }

    /** Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). */
    suspend fun showFeedback(text: String) = withContext(Dispatchers.Main) { feedback.show(text) }

    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. A caller that also
     * listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) { card.ask(question) }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }

    /** Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects. */
    fun close() {
        scope.cancel()
        card.dismiss()
        overlays.hideAll()
    }

    private fun onScreenSettled(snapshot: ScreenSnapshot) {
        val content = contentOf(snapshot)
        if (labelsShownOn != null && content != labelsShownOn) {
            labelsShownOn = null
            labels.hide()
        }
        if (whichOneShownOn != null && content != whichOneShownOn) {
            whichOneShownOn = null
            numbers.hide()
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) numbers.show(badges)
        }
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }
}
```

- [ ] **Step 6: Run the device tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 51 test cases (`GridBarAndCardTest` 5) and no failures.

- [ ] **Step 7: Run the checks**

Run: `scripts\gradle.ps1 check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```powershell
git add core/screen app/src/androidTest
git commit -m "feat(screen): add the grid, the feedback bar and the confirmation card" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 11: The touch watcher (spike S3)

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/TouchWatcher.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/GestureRunner.kt` (remember when Kestrel's gestures run)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (the watcher)
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/screen/TestFinger.kt` (test helper)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` (add `awaitInputWindow`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/screen/TouchWatcherTest.kt`

**Interfaces:**
- Consumes: `GestureRunner`, `OverlayHost` and `ScreenSession` from earlier tasks.
- Produces: `GestureRunner.isRunning(): Boolean`, true while one of Kestrel's gestures runs and for `SETTLE_MILLIS` (100 ms) after it.
- Produces: the internal `TouchWatcher(host, gestures)`, and on `ScreenSession`: `ownerTouches: SharedFlow<Unit>`, `suspend startTouchWatcher()` and `suspend stopTouchWatcher()`.
- Produces: the test helper `TestFinger(automation: UiAutomation)`, with `suspend tap(x: Int, y: Int, holdMillis: Long = 50)` and `close()`, and `ScreenServiceRule.awaitInputWindow(title: String)`.

The touch watcher is an invisible window of one pixel with `FLAG_WATCH_OUTSIDE_TOUCH`. Android tells it about every touch that starts outside it, as `ACTION_OUTSIDE` without coordinates, unless the touch lands on a window above it, such as an overlay that Kestrel shows later. Spike S3 found how to tell the owner's touches from Kestrel's own gestures, which reach the watcher too:

- Android injects accessibility gestures from its virtual input device, ID -1, on every version from Android 13 to 17. A finger's touches come from the touchscreen, which has a positive ID. So the watcher leaves out touches from the virtual device while one of Kestrel's gestures runs.
- On Android 13 to 16, a finger's touch also cancels Kestrel's running gesture, and Android marks the finger's touch as an accessibility event. That mark therefore cannot tell the two apart.
- On Android 17 (from Android 16 QPR2), a finger's touch no longer cancels Kestrel's gesture. Only the watcher notices it.

The tests need touches that behave like a finger's. `adb shell input tap` cannot provide them: its touches come from the virtual device too, skip the accessibility input filter, and are dropped while one of Kestrel's gestures runs. `TestFinger` therefore creates a virtual touchscreen with Android's `uinput` shell tool and sends it Linux input events. The commands use numbers rather than names, because the tool on Android 13 reads only numbers. A new overlay window gets touches only after Android's input dispatcher lists it, so the tests wait for that with `awaitInputWindow`. Without that wait, the check that Kestrel's own tap is not reported could pass only because the watcher was not ready.

- [ ] **Step 1: Write the test helpers and the failing tests**

`app/src/androidTest/kotlin/dev/kestrel/app/screen/TestFinger.kt`:

```kotlin
package dev.kestrel.app.screen

import android.app.UiAutomation
import android.hardware.input.InputManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay

/**
 * A virtual touchscreen, made with Android's `uinput` shell tool, that touches the screen like the owner's finger.
 * Unlike `input tap`, its touches come from a touchscreen device and pass through Android's accessibility input
 * filter. The commands use numbers rather than names, because the tool on Android 13 reads only numbers.
 */
class TestFinger(automation: UiAutomation) : AutoCloseable {
    private val pipes = automation.executeShellCommandRw("uinput -")
    private val commands = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter()
    private var touches = 0

    init {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        send(register(screen.width() - 1, screen.height() - 1))
        val input = context.getSystemService(InputManager::class.java)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (input.inputDeviceIds.none { input.getInputDevice(it)?.name == NAME }) {
            check(SystemClock.uptimeMillis() < deadline) { "The virtual touchscreen did not appear within 5 s" }
            Thread.sleep(50)
        }
    }

    /** Touches the screen at ([x], [y]) for [holdMillis], then lifts the finger. */
    suspend fun tap(x: Int, y: Int, holdMillis: Long = 50) {
        touches++
        inject(EV_ABS, ABS_MT_SLOT, 0, EV_ABS, ABS_MT_TRACKING_ID, touches, EV_ABS, ABS_MT_POSITION_X, x, EV_ABS, ABS_MT_POSITION_Y, y,
            EV_ABS, ABS_MT_PRESSURE, 100, EV_KEY, BTN_TOUCH, 1, EV_SYN, SYN_REPORT, 0)
        delay(holdMillis)
        inject(EV_ABS, ABS_MT_TRACKING_ID, -1, EV_KEY, BTN_TOUCH, 0, EV_SYN, SYN_REPORT, 0)
        delay(LIFT_MILLIS)
    }

    /** Removes the virtual touchscreen: the tool removes its device when its input ends. */
    override fun close() {
        commands.close()
        pipes[0].close()
    }

    private fun inject(vararg events: Int) = send("""{"id":1,"command":"inject","events":[${events.joinToString(",")}]}""")

    private fun send(command: String) {
        commands.write(command)
        commands.newLine()
        commands.flush()
    }

    private companion object {
        const val NAME = "Kestrel test finger"
        const val LIFT_MILLIS = 50L

        // Linux input event codes, from linux/input-event-codes.h and linux/uinput.h.
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val BTN_TOUCH = 0x14a
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val ABS_MT_PRESSURE = 0x3a
        const val UI_SET_EVBIT = 100
        const val UI_SET_KEYBIT = 101
        const val UI_SET_ABSBIT = 103
        const val UI_SET_PROPBIT = 110
        const val INPUT_PROP_DIRECT = 1

        fun axis(code: Int, maximum: Int) =
            """{"code":$code,"info":{"value":0,"minimum":0,"maximum":$maximum,"fuzz":0,"flat":0,"resolution":0}}"""

        fun register(maxX: Int, maxY: Int): String {
            val configuration = listOf(
                """{"type":$UI_SET_EVBIT,"data":[$EV_KEY,$EV_ABS]}""",
                """{"type":$UI_SET_KEYBIT,"data":[$BTN_TOUCH]}""",
                """{"type":$UI_SET_ABSBIT,"data":[$ABS_MT_SLOT,$ABS_MT_POSITION_X,$ABS_MT_POSITION_Y,$ABS_MT_TRACKING_ID,$ABS_MT_PRESSURE]}""",
                """{"type":$UI_SET_PROPBIT,"data":[$INPUT_PROP_DIRECT]}""",
            )
            val axes = listOf(
                axis(ABS_MT_SLOT, 9),
                axis(ABS_MT_POSITION_X, maxX),
                axis(ABS_MT_POSITION_Y, maxY),
                axis(ABS_MT_TRACKING_ID, 65535),
                axis(ABS_MT_PRESSURE, 255),
            )
            return """{"id":1,"command":"register","name":"$NAME","vid":6353,"pid":43981,"bus":"usb",""" +
                """"configuration":[${configuration.joinToString(",")}],"abs_info":[${axes.joinToString(",")}]}"""
        }
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` with:

```kotlin
package dev.kestrel.app.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * Gives each test a new screen session by turning Kestrel's accessibility service off and on again, so that
 * nothing one test leaves behind affects the next. The tests run only on the test emulator and the A71
 * (design spec, D20), where the service may stay on afterwards.
 */
class ScreenServiceRule : ExternalResource() {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    lateinit var session: ScreenSession
        private set

    val automation: UiAutomation get() = Companion.automation

    override fun before() {
        runBlocking {
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }
            shell("settings put secure enabled_accessibility_services $SERVICE")
            shell("settings put secure accessibility_enabled 1")
            session = withTimeout(10_000) { graph.screen.filterNotNull().first() }
        }
    }

    /** Runs a command as the shell user, which may change secure settings and start other apps' activities. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * Starts an activity of the fixture app and waits until its [title] is on the screen. The fixture app is
     * stopped first, because Android would otherwise only bring its existing task to the front.
     */
    fun open(activity: String, title: String, extras: String = ""): ScreenElement {
        shell("am start -W -S -n $FIXTURES/.$activity $extras")
        return runBlocking { awaitElement { it.label == title } }
    }

    /** Whether Android's window list has a window with [title], such as "Kestrel numbers". */
    fun showsWindow(title: String): Boolean = shell("dumpsys window windows").contains("$title}")

    /**
     * Waits until Android's input dispatcher knows the window with [title], such as "Kestrel touch watcher". A new
     * window gets touches only from then on, a moment after it was added.
     */
    suspend fun awaitInputWindow(title: String) {
        withTimeoutOrNull(5_000) {
            while (shell("dumpsys input").lineSequence().none { title in it && "displayId=" in it }) delay(50)
        } ?: throw AssertionError("Android's input dispatcher did not list the window $title within 5 s")
    }

    /**
     * The bounds of a button on one of Kestrel's touchable overlays, such as the confirmation card, as another
     * accessibility client sees it. Overlays that take no touches are not in the list of interactive windows.
     */
    fun overlayButton(text: String): Rect? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val button = window.root?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: continue
            return Rect().also(button::getBoundsInScreen)
        }
        return null
    }

    /** Waits until an element that matches [predicate] is on the screen. */
    suspend fun awaitElement(timeoutMillis: Long = 5_000, predicate: (ScreenElement) -> Boolean): ScreenElement {
        var labels: List<String?> = emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            var found: ScreenElement? = null
            while (found == null) {
                val elements = session.snapshots.current().snapshot.elements
                labels = elements.map { it.label }
                found = elements.firstOrNull(predicate)
                if (found == null) delay(100)
            }
            found
        } ?: throw AssertionError("No matching element appeared within $timeoutMillis ms. The screen showed $labels")
    }

    companion object {
        const val FIXTURES = "dev.kestrel.testapp"
        const val KESTREL = "dev.kestrel"
        private const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"

        // Connecting UiAutomation without this flag would turn off every accessibility service, Kestrel's included.
        private val automation: UiAutomation by lazy {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
                serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            }
        }
    }
}
```

`app/src/androidTest/kotlin/dev/kestrel/app/screen/TouchWatcherTest.kt`:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchWatcherTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    @Test
    fun aFingerOnAnotherAppIsReportedAndStillReachesIt() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("ViewsActivity", "Views fixture")
            val target = screen.awaitElement { it.label == "Touch target" }.bounds
            session.startTouchWatcher()
            screen.awaitInputWindow(WATCHER)
            val touch = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { session.ownerTouches.first() } }

            finger.tap(target.centerX, target.centerY)

            touch.await()
            screen.awaitElement { it.label == "Touched target" }
        }
        Unit
    }

    @Test
    fun kestrelsOwnGesturesAreNotReported() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val target = screen.awaitElement { it.label == "Touch target" }.bounds
        session.startTouchWatcher()
        screen.awaitInputWindow(WATCHER)
        val touches = mutableListOf<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { touches += it } }

        session.actions.tapAt(target.centerX, target.centerY)
        screen.awaitElement { it.label == "Touched target" }
        delay(500)

        collector.cancel()
        assertTrue("touches: ${touches.size}", touches.isEmpty())
    }

    @Test
    fun aFingerDuringKestrelsGestureIsReported() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("GesturesActivity", "Gestures fixture")
            val pad = screen.awaitElement { it.label == "Gesture pad" }.bounds
            session.startTouchWatcher()
            screen.awaitInputWindow(WATCHER)
            val touches = Channel<Unit>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { touches.send(it) } }
            val drag = async { session.actions.drag(pad.centerX - 300, pad.centerY + 200, pad.centerX + 300, pad.centerY + 200) }
            delay(400)
            assertTrue("Kestrel's own drag was reported", touches.tryReceive().isFailure)

            finger.tap(pad.centerX, pad.centerY - 300)

            withTimeout(3_000) { touches.receive() }
            drag.await()
            collector.cancel()
        }
        Unit
    }

    private companion object {
        const val WATCHER = "Kestrel touch watcher"
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.TouchWatcherTest'`

Expected: `BUILD FAILED`, with `Unresolved reference 'startTouchWatcher'` and `Unresolved reference 'ownerTouches'` from `:app:compileDebugAndroidTestKotlin`.

- [ ] **Step 3: Write the watcher**

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/GestureRunner.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What became of a gesture that Kestrel dispatched. */
enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED }

/**
 * Runs Kestrel's own gestures one at a time (design spec, section 9.3), and remembers when they run, so that the
 * touch watcher can tell them apart from the owner's touches (section 14.1).
 */
class GestureRunner(private val service: AccessibilityService) {
    private val mutex = Mutex()

    @Volatile
    private var busyUntil = 0L

    /** Whether one of Kestrel's gestures is running, or ended less than [SETTLE_MILLIS] ago. */
    fun isRunning(): Boolean = SystemClock.uptimeMillis() < busyUntil

    /** Runs the [parts] of one gesture in order, such as the hold and the move of a drag, and stops at a failed part. */
    suspend fun perform(vararg parts: GestureDescription): GestureOutcome = mutex.withLock {
        busyUntil = Long.MAX_VALUE
        try {
            var outcome = GestureOutcome.COMPLETED
            for (part in parts) {
                outcome = dispatch(part)
                if (outcome != GestureOutcome.COMPLETED) break
            }
            outcome
        } finally {
            busyUntil = SystemClock.uptimeMillis() + SETTLE_MILLIS
        }
    }

    private suspend fun dispatch(gesture: GestureDescription): GestureOutcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
            }
            if (!service.dispatchGesture(gesture, callback, null)) continuation.resume(GestureOutcome.REJECTED)
        }
    }

    companion object {
        /** How long after one of Kestrel's gestures its touches may still arrive at the touch watcher. */
        const val SETTLE_MILLIS = 100L
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/TouchWatcher.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams
import dev.kestrel.screen.act.GestureRunner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An invisible window of one pixel that reports the owner's touches anywhere on the screen, so that a touch can
 * stop a running task (design spec, sections 9.4 and 14.1). Android tells the window about every touch that starts
 * outside it, unless the touch lands on a window above it, such as an overlay that Kestrel shows later. Use it on
 * the main thread.
 *
 * Android injects the touches of Kestrel's gestures from its virtual input device, while a finger's touches come
 * from the touchscreen, so the watcher leaves out touches from the virtual device while one of Kestrel's gestures
 * runs (spike S3). The device is the clue that works on every version: Android 13 to 16 mark a finger's touches as
 * accessibility events while a gesture service is on, and Android 17 no longer cancels Kestrel's gesture when a
 * finger touches the screen.
 */
internal class TouchWatcher(private val host: OverlayHost, private val gestures: GestureRunner) {
    private val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    // On purpose: the watcher only listens for touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    private val view = View(host.context).apply {
        setOnTouchListener { _, event ->
            val starts = event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN
            val kestrels = gestures.isRunning() && event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
            if (starts && !kestrels) touches.tryEmit(Unit)
            false
        }
    }

    /** Emits when the owner touches the screen while the watcher runs. */
    val ownerTouches: SharedFlow<Unit> = touches.asSharedFlow()

    var isWatching: Boolean = false
        private set

    fun start() {
        if (isWatching) return
        host.show(view, params())
        isWatching = true
    }

    fun stop() {
        host.hide(view)
        isWatching = false
    }

    private fun params() = LayoutParams(
        1,
        1,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        setTitle("Kestrel touch watcher")
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` with:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.ConfirmationCard
import dev.kestrel.screen.overlay.FeedbackBar
import dev.kestrel.screen.overlay.Grid
import dev.kestrel.screen.overlay.GridOverlay
import dev.kestrel.screen.overlay.LabelsOverlay
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.screen.overlay.NumbersOverlay
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.overlay.TouchWatcher
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    private val gridOverlay = GridOverlay(overlays)
    private val feedback = FeedbackBar(overlays)
    private val card = ConfirmationCard(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)
    private val watcher = TouchWatcher(overlays, gestures)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

    // These fields change only on the main thread.
    /** Set while "show numbers" is on: the numbers then follow the screen. */
    private var numbersMode: NumbersMode? = null

    /** The screen content when the labels appeared. They go when it changes. */
    private var labelsShownOn: List<Pair<String, IntRect>>? = null

    /** The screen content when the "Which one?" numbers appeared. They go when it changes. */
    private var whichOneShownOn: List<Pair<String, IntRect>>? = null

    /** The numbers on the screen now, for commands such as "tap 5". */
    val numberBadges: List<Badge> get() = numbers.badges

    val labelsShowing: Boolean get() = labels.isShowing

    /** The grid on the screen now, or null. "Tap 5" taps the centre of the grid's cell 5. */
    val grid: Grid? get() = gridOverlay.grid

    /** The feedback bar's text now, or null while the bar is hidden. */
    val feedbackText: String? get() = feedback.text

    /** Emits when the owner touches the screen while the touch watcher runs (design spec, section 14.1). */
    val ownerTouches: SharedFlow<Unit> get() = watcher.ownerTouches

    init {
        scope.launch {
            tracker.version.collectLatest {
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collectLatest
                tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
                onScreenSettled(snapshots.current().snapshot)
            }
        }
    }

    /** Shows numbers on the actionable elements. They follow the screen until [hideNumbers] (design spec, section 9.4). */
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
        numbersMode = mode
        numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    /** Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes. */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        numbersMode = null
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        numbers.hide()
    }

    /** Shows names next to the actionable elements until the screen content changes (design spec, section 9.4). */
    suspend fun showLabels() = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        labelsShownOn = contentOf(snapshot)
        labels.show(snapshot.elements.filter { it.isActionable })
    }

    suspend fun hideLabels() = withContext(Dispatchers.Main) {
        labelsShownOn = null
        labels.hide()
    }

    /** Shows [grid], by default one over the whole screen. "More squares" shows `grid.more()` (design spec, section 9.4). */
    suspend fun showGrid(grid: Grid? = null) = withContext(Dispatchers.Main) {
        gridOverlay.show(grid ?: Grid.over(overlays.screenBounds()))
    }

    suspend fun hideGrid() = withContext(Dispatchers.Main) { gridOverlay.hide() }

    /** Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). */
    suspend fun showFeedback(text: String) = withContext(Dispatchers.Main) { feedback.show(text) }

    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. A caller that also
     * listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) { card.ask(question) }

    /** Starts reporting the owner's touches in [ownerTouches], for example while a task runs. */
    suspend fun startTouchWatcher() = withContext(Dispatchers.Main) { watcher.start() }

    suspend fun stopTouchWatcher() = withContext(Dispatchers.Main) { watcher.stop() }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }

    /** Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects. */
    fun close() {
        scope.cancel()
        card.dismiss()
        overlays.hideAll()
    }

    private fun onScreenSettled(snapshot: ScreenSnapshot) {
        val content = contentOf(snapshot)
        if (labelsShownOn != null && content != labelsShownOn) {
            labelsShownOn = null
            labels.hide()
        }
        if (whichOneShownOn != null && content != whichOneShownOn) {
            whichOneShownOn = null
            numbers.hide()
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) numbers.show(badges)
        }
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }
}
```

- [ ] **Step 4: Run the touch watcher's tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.TouchWatcherTest'`

Expected: `BUILD SUCCESSFUL`, with 3 test cases and no failures. `aFingerDuringKestrelsGestureIsReported` is the result of spike S3 on the emulator: with a rule that looked only at the time of Kestrel's gestures, it would fail, because Android 17 does not cancel the gesture.

- [ ] **Step 5: Run all device tests and the checks**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 54 test cases and no failures. `GestureRunner` changed, so every gesture test runs again.

Run: `scripts\gradle.ps1 check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add core/screen app/src/androidTest
git commit -m "feat(screen): add the touch watcher" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 12: Test hooks for adb

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ManifestScanner.kt`, `CheckManifestTask.kt`
- Test: `build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/ManifestScannerTest.kt`
- Modify: `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt` (check release manifests)
- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHookReceiver.kt`, `TestHooks.kt`
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/HookProbeActivity.kt`
- Modify: `testapp/src/main/AndroidManifest.xml` (the probe, and `<queries>` for Kestrel), `testapp/src/main/kotlin/dev/kestrel/testapp/FixturesActivity.kt` (list the probe)
- Create: `scripts/hook.ps1`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/debug/TestHookTest.kt`

**Interfaces:**
- Consumes: `ScreenSession`, `ScreenActions`, `GlobalAction`, `ScrollDirection`, `NumbersMode` and `ScreenJson` from earlier tasks; `AppGraph.settings` and `SettingKeys.SAFETY` from plan 1A; `Assert-TestDevice` and `Get-Adb` from `scripts/lib/Kestrel.ps1`.
- Produces: `fun findTestHooks(manifest: String, applicationId: String): List<String>` and the task `kestrelCheckManifestRelease`, which `kestrelAudit` runs for the release build of `:app`.
- Produces: the receiver `dev.kestrel.app.debug.TestHookReceiver`, in debug builds only, for the actions `dev.kestrel.hook.STATE`, `TEST_MODE`, `SNAPSHOT`, `TAP`, `TYPE`, `SCROLL`, `GLOBAL` and `OVERLAY`. Its answer is the broadcast's result: code -1 with the answer, or code 1 with a text that starts with `error:`. A command that never ran keeps code 0.
- Produces: `scripts\hook.ps1 -Serial <serial> <command> [options]`; the comment at the top of the script lists the commands and options.

Spec section 16.1 asks for a receiver that only callers with `android.permission.DUMP` can use, which in practice means `adb shell`. In this sub-project it reads the screen layer's state, switches test mode, returns the snapshot as JSON, performs taps, typing, scrolling and global actions, and shows the overlays. Plan 1C uses it to check the overlays and actions in real apps on the A71. Later sub-projects add commands that inject a transcript or a task.

Three details matter for safety:

- The receiver is in the `debug` source set, so release builds cannot contain it. The audit also checks each release manifest: Kestrel's own components that require DUMP are test hooks, and a release build must have none. AndroidX's `ProfileInstallReceiver` requires DUMP too, so the check looks only at components under Kestrel's application ID.
- Android lets an app use its own components even when they require a permission that the app lacks. A test that sends from Kestrel's own process would therefore pass the permission check. `HookProbeActivity` in `:testapp` sends the command as another app, and the manifest's `<queries>` element lets it see Kestrel, so that only the permission check can refuse it.
- In `am broadcast -n dev.kestrel/.x.Y`, the short class name is relative to the package `dev.kestrel`, not to the namespace `dev.kestrel.app`. The tests and the script use the full class name.

- [ ] **Step 1: Write the failing audit test**

`build-logic/convention/src/test/kotlin/dev/kestrel/build/audit/ManifestScannerTest.kt`:

```kotlin
package dev.kestrel.build.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestScannerTest {
    @Test
    fun findsOnlyTheAppsOwnComponentsThatRequireDump() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kestrel">
                <application>
                    <activity android:name="dev.kestrel.app.MainActivity" android:exported="true" />
                    <service
                        android:name="dev.kestrel.app.KestrelAccessibilityService"
                        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" />
                    <receiver
                        android:name="dev.kestrel.app.debug.TestHookReceiver"
                        android:exported="true"
                        android:permission="android.permission.DUMP" />
                    <receiver
                        android:name="androidx.profileinstaller.ProfileInstallReceiver"
                        android:exported="true"
                        android:permission="android.permission.DUMP" />
                </application>
            </manifest>
        """.trimIndent()

        assertEquals(listOf("dev.kestrel.app.debug.TestHookReceiver"), findTestHooks(manifest, "dev.kestrel"))
    }
}
```

Run: `scripts\gradle.ps1 :build-logic:convention:test`

Expected: `BUILD FAILED`, with `Unresolved reference 'findTestHooks'` from `:build-logic:convention:compileTestKotlin`.

- [ ] **Step 2: Write the manifest check**

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/ManifestScanner.kt`:

```kotlin
package dev.kestrel.build.audit

import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
private const val DUMP_PERMISSION = "android.permission.DUMP"
private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

/**
 * Returns the test hooks that a merged manifest declares: the app's own components that only callers with
 * `android.permission.DUMP` may use (design spec, section 16.1). A release build must declare none. Libraries'
 * components are not test hooks, even when they require DUMP, such as AndroidX's ProfileInstallReceiver, which
 * lets adb install a startup profile.
 */
fun findTestHooks(manifest: String, applicationId: String): List<String> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    val document = factory.newDocumentBuilder().parse(manifest.byteInputStream())
    return COMPONENT_TAGS
        .flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        .filter { it.getAttributeNS(ANDROID_NAMESPACE, "permission") == DUMP_PERMISSION }
        .map { it.getAttributeNS(ANDROID_NAMESPACE, "name") }
        .filter { it.startsWith("$applicationId.") }
}
```

`build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/CheckManifestTask.kt`:

```kotlin
package dev.kestrel.build.audit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails when a release build's merged manifest declares a test hook (design spec, section 16.1). */
abstract class CheckManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val hooks = findTestHooks(manifest.get().asFile.readText(), applicationId.get())
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(if (hooks.isEmpty()) "No test hooks.\n" else "Test hooks:\n" + hooks.joinToString("\n", postfix = "\n"))
        if (hooks.isNotEmpty()) {
            throw GradleException("A release build must not contain test hooks (report: $reportFile):\n" + hooks.joinToString("\n"))
        }
    }
}
```

Replace the whole content of `build-logic/convention/src/main/kotlin/dev/kestrel/build/audit/AuditPlugin.kt` with:

```kotlin
package dev.kestrel.build.audit

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Registers `kestrelAudit` and the checks it runs (design spec, section 13.2), for every build variant,
 * and makes `check` run it. Exceptions live in `audit/rules.txt`, each with a written justification.
 * The audit also checks that release builds contain no test hooks (section 16.1).
 */
class AuditPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rules = project.rootProject.layout.projectDirectory.file("audit/rules.txt")
        val audit = project.tasks.register("kestrelAudit") {
            group = "verification"
            description = "Runs Kestrel's privacy audit for this module."
        }
        project.plugins.withType(LifecycleBasePlugin::class.java) {
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(audit) }
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
        // Debug builds are what the scripts install, so they are audited too.
        components.onVariants(components.selector().all()) { variant ->
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
                if (variant.buildType == "release") {
                    val manifestCheck = project.tasks.register("kestrelCheckManifest$suffix", CheckManifestTask::class.java) {
                        manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                        applicationId.set((variant as ApplicationVariant).applicationId)
                        report.set(reports.map { it.file("manifest-${variant.name}.txt") })
                    }
                    audit.configure { dependsOn(manifestCheck) }
                }
            }
        }
    }
}
```

Run: `scripts\gradle.ps1 :build-logic:convention:test :app:kestrelCheckManifestRelease`

Expected: `BUILD SUCCESSFUL`. `ManifestScannerTest` reports `tests="1"` without failures, and `app/build/reports/kestrel-audit/manifest-release.txt` says `No test hooks.`

- [ ] **Step 3: Check that the audit catches a test hook in a release build**

Create a temporary file `app/src/release/AndroidManifest.xml` with this content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <receiver android:name=".debug.LeakedHook" android:exported="true" android:permission="android.permission.DUMP" />
    </application>
</manifest>
```

Run: `scripts\gradle.ps1 :app:kestrelCheckManifestRelease`

Expected: `BUILD FAILED`, with `A release build must not contain test hooks` and `dev.kestrel.app.debug.LeakedHook`.

Then delete the folder `app/src/release` and run the same command again. Expected: `BUILD SUCCESSFUL`. `git status --short` must not list `app/src/release`.

- [ ] **Step 4: Write the failing device tests and the probe**

`app/src/androidTest/kotlin/dev/kestrel/app/debug/TestHookTest.kt`:

```kotlin
package dev.kestrel.app.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.screen.model.ScreenJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestHookTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    @Test
    fun adbReadsTheState() {
        assertEquals("screen=on", answer(hook("STATE")).substringBefore(' '))
    }

    @Test
    fun adbSwitchesTestMode() = runBlocking {
        val original = graph.settings.get(SettingKeys.SAFETY)
        try {
            assertEquals("testMode=off", answer(hook("TEST_MODE --ez on false")))
            assertFalse(graph.settings.get(SettingKeys.SAFETY).testMode)
            assertEquals("testMode=on", answer(hook("TEST_MODE --ez on true")))
            assertTrue(graph.settings.get(SettingKeys.SAFETY).testMode)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, original)
        }
    }

    @Test
    fun adbReadsTheScreen() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        val snapshot = ScreenJson.decode(answer(hook("SNAPSHOT")))

        assertTrue(snapshot.elements.any { it.label == "SEND" })
    }

    @Test
    fun adbShowsNumbersAndTapsOne() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" && it.clickable }
        answer(hook("OVERLAY --es show numbers"))
        val number = screen.session.numberBadges.first { it.key == send.key }.number

        assertEquals("done: action", answer(hook("TAP --ei number $number")))

        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun aFailedCommandSaysWhy() {
        val reply = hook("TYPE --es text hello")

        assertTrue(reply, reply.contains("result=1, data=\"error: add --es key"))
    }

    @Test
    fun anotherAppCannotSendCommands() = runBlocking {
        screen.open("HookProbeActivity", "Hook probe")

        screen.awaitElement { it.label == "Probe refused" }
        Unit
    }

    /** Sends a command the way scripts/hook.ps1 does. */
    private fun hook(command: String): String =
        screen.shell("am broadcast -n ${ScreenServiceRule.KESTREL}/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command")

    /** The data of a successful answer. */
    private fun answer(reply: String): String {
        assertTrue(reply, reply.contains("result=-1, data=\""))
        return reply.substringAfter("data=\"").substringBeforeLast('"')
    }
}
```

`testapp/src/main/kotlin/dev/kestrel/testapp/HookProbeActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Sends a test-hook command to Kestrel's debug build the way any other app could, and shows whether Kestrel
 * answered. Kestrel's receiver requires android.permission.DUMP, so it must not answer (design spec, section 16.1).
 */
class HookProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = fixturePage("Hook probe").addStatus()
        val command = Intent("dev.kestrel.hook.STATE").setClassName("dev.kestrel", "dev.kestrel.app.debug.TestHookReceiver")
        val answer = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                status.text = if (resultData == null) "Probe refused" else "Probe answered"
            }
        }
        sendOrderedBroadcast(command, null, answer, null, RESULT_CANCELED, null, null)
    }
}
```

Replace the whole content of `testapp/src/main/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Lets the hook probe see Kestrel, so that only Kestrel's permission check can refuse it. -->
    <queries>
        <package android:name="dev.kestrel" />
    </queries>

    <!-- Fixture screens for Kestrel's instrumented tests (design spec, section 16.3). This app is never shipped. -->
    <application
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="Kestrel fixtures"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".FixturesActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Lets tests open the app with a link, as Kestrel opens links and deep links. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="kestrel-fixtures" />
            </intent-filter>
        </activity>
        <activity
            android:name=".ViewsActivity"
            android:exported="true" />
        <activity
            android:name=".ScrollActivity"
            android:exported="true" />
        <activity
            android:name=".GesturesActivity"
            android:exported="true" />
        <activity
            android:name=".ComposeActivity"
            android:exported="true" />
        <activity
            android:name=".WebActivity"
            android:exported="true" />
        <activity
            android:name=".SecureActivity"
            android:exported="true" />
        <activity
            android:name=".HookProbeActivity"
            android:exported="true" />
    </application>
</manifest>
```

Replace the whole content of `testapp/src/main/kotlin/dev/kestrel/testapp/FixturesActivity.kt` with:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

/** The launcher screen. It lists the fixtures for manual use; tests start each fixture directly. */
class FixturesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Kestrel fixtures")
        val fixtures = listOf(
            "Views" to ViewsActivity::class.java,
            "Scroll" to ScrollActivity::class.java,
            "Gestures" to GesturesActivity::class.java,
            "Compose" to ComposeActivity::class.java,
            "Web" to WebActivity::class.java,
            "Secure" to SecureActivity::class.java,
            "Hook probe" to HookProbeActivity::class.java,
        )
        for ((name, activity) in fixtures) {
            page.addView(Button(this).apply {
                text = name
                setOnClickListener { startActivity(Intent(context, activity)) }
            })
        }
    }
}
```

- [ ] **Step 5: Run the device tests to verify that they fail**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.debug.TestHookTest'`

Expected: `There were failing tests`. Five tests fail, because every broadcast ends with `Broadcast completed: result=0`: there is no receiver yet. `anotherAppCannotSendCommands` passes already, for the same reason; Step 8 shows that it tests the permission once the receiver exists.

- [ ] **Step 6: Write the receiver**

`app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <!-- Test hooks for adb (design spec, section 16.1). Only callers that hold DUMP, in practice adb shell,
             can send commands. Release builds do not contain this receiver, and the audit checks that. -->
        <receiver
            android:name=".debug.TestHookReceiver"
            android:exported="true"
            android:permission="android.permission.DUMP" />
    </application>
</manifest>
```

`app/src/debug/kotlin/dev/kestrel/app/debug/TestHookReceiver.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.privacy.logs.describeWithoutMessages
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives test-hook commands from adb, in debug builds only (design spec, section 16.1). Android delivers them only
 * from callers that hold `android.permission.DUMP`, which in practice means `adb shell`; `scripts/hook.ps1` sends
 * them. The answer is the broadcast's result code and data, see [HookReply].
 */
class TestHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KestrelApplication).graph
        val pending = goAsync()
        graph.scope.launch {
            val reply = try {
                withTimeout(TIMEOUT_MILLIS) { TestHooks(graph).run(intent) }
            } catch (error: Exception) {
                HookReply.failed("the command failed: ${error.describeWithoutMessages()}")
            }
            pending.setResult(reply.code, reply.text, null)
            pending.finish()
        }
    }

    private companion object {
        // Android waits 60 s for a background broadcast, and an action waits at most 3 s for the screen to settle.
        const val TIMEOUT_MILLIS = 20_000L
    }
}
```

`app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`:

```kotlin
package dev.kestrel.app.debug

import android.app.Activity
import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A test hook's answer, which becomes the broadcast's result code and data. */
internal data class HookReply(val code: Int, val text: String) {
    companion object {
        /** The result code of a command that failed. A command that never ran keeps the initial code, 0. */
        const val FAILED = 1

        fun ok(text: String) = HookReply(Activity.RESULT_OK, text)

        fun failed(message: String) = HookReply(FAILED, "error: $message")
    }
}

/**
 * Runs the test-hook commands (design spec, section 16.1). The intent's action is [ACTION_PREFIX] followed by the
 * command, and its extras are the arguments; `scripts/hook.ps1` lists them.
 */
internal class TestHooks(private val graph: AppGraph) {
    suspend fun run(intent: Intent): HookReply = try {
        HookReply.ok(answer(intent))
    } catch (error: HookError) {
        HookReply.failed(error.message)
    }

    private suspend fun answer(intent: Intent): String {
        val command = intent.action.orEmpty().removePrefix(ACTION_PREFIX)
        if (command == "STATE") return state()
        if (command == "TEST_MODE") return testMode(intent)
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val actions = session.actions
        return when (command) {
            "SNAPSHOT" -> ScreenJson.encode(session.snapshots.current().snapshot)
            "TAP" -> done(tap(session, intent))
            "TYPE" -> done(actions.setText(target(session, intent), intent.requiredText()))
            "SCROLL" -> done(actions.scroll(intent.choice<ScrollDirection>(EXTRA_DIRECTION), optionalTarget(session, intent)))
            "GLOBAL" -> done(actions.global(intent.choice<GlobalAction>(EXTRA_ACTION)))
            "OVERLAY" -> {
                overlay(session, intent)
                state()
            }
            else -> throw HookError("unknown command '$command'")
        }
    }

    private suspend fun state(): String {
        val testMode = onOff(graph.settings.get(SettingKeys.SAFETY).testMode)
        val session = graph.screen.value ?: return "screen=off testMode=$testMode"
        return withContext(Dispatchers.Main) {
            "screen=on testMode=$testMode numbers=${session.numberBadges.size} labels=${onOff(session.labelsShowing)} " +
                "grid=${onOff(session.grid != null)} feedback=${onOff(session.feedbackText != null)}"
        }
    }

    private suspend fun testMode(intent: Intent): String {
        if (!intent.hasExtra(EXTRA_ON)) throw HookError("add --ez on true or --ez on false")
        val on = intent.getBooleanExtra(EXTRA_ON, true)
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
        return "testMode=${onOff(on)}"
    }

    private suspend fun tap(session: ScreenSession, intent: Intent): ActionResult {
        val long = intent.getBooleanExtra(EXTRA_LONG, false)
        if (intent.hasExtra(EXTRA_X) || intent.hasExtra(EXTRA_Y)) {
            if (!intent.hasExtra(EXTRA_X) || !intent.hasExtra(EXTRA_Y)) throw HookError("add both --ei x and --ei y")
            val x = intent.getIntExtra(EXTRA_X, 0)
            val y = intent.getIntExtra(EXTRA_Y, 0)
            return if (long) session.actions.longPressAt(x, y) else session.actions.tapAt(x, y)
        }
        val key = target(session, intent)
        return if (long) session.actions.longPress(key) else session.actions.tap(key)
    }

    private suspend fun overlay(session: ScreenSession, intent: Intent) {
        when (intent.getStringExtra(EXTRA_SHOW)) {
            "numbers" -> session.showNumbers(NumbersMode.ALL)
            "unlabeled" -> session.showNumbers(NumbersMode.UNLABELED)
            "labels" -> session.showLabels()
            "grid" -> session.showGrid()
            "feedback" -> session.showFeedback(intent.requiredText())
            "none" -> {
                session.hideNumbers()
                session.hideLabels()
                session.hideGrid()
            }
            else -> throw HookError("add --es show with numbers, unlabeled, labels, grid, feedback or none")
        }
    }

    /** The element that the extras name: by its key, or by its number while the numbers show. */
    private suspend fun target(session: ScreenSession, intent: Intent): String =
        optionalTarget(session, intent) ?: throw HookError("add --es key <element key> or --ei number <number>")

    private suspend fun optionalTarget(session: ScreenSession, intent: Intent): String? {
        intent.getStringExtra(EXTRA_KEY)?.let { return it }
        if (!intent.hasExtra(EXTRA_NUMBER)) return null
        val number = intent.getIntExtra(EXTRA_NUMBER, 0)
        val badges = withContext(Dispatchers.Main) { session.numberBadges }
        return badges.firstOrNull { it.number == number }?.key ?: throw HookError("no element shows the number $number")
    }

    private fun done(result: ActionResult): String = when (result) {
        is ActionResult.Done -> "done: ${result.method.name.lowercase()}"
        is ActionResult.Failed -> throw HookError(result.reason)
    }

    private fun Intent.requiredText(): String = getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    private inline fun <reified T : Enum<T>> Intent.choice(name: String): T {
        val value = getStringExtra(name)?.replace('-', '_')
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw HookError("add --es $name with one of ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private class HookError(override val message: String) : Exception(message)

    companion object {
        const val ACTION_PREFIX = "dev.kestrel.hook."
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_LONG = "long"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_ON = "on"
    }
}
```

- [ ] **Step 7: Run the device tests to verify that they pass**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.debug.TestHookTest'`

Expected: `BUILD SUCCESSFUL`, with 6 test cases and no failures.

- [ ] **Step 8: Check that the probe test depends on the permission**

In `app/src/debug/AndroidManifest.xml`, delete the line `android:permission="android.permission.DUMP" />` and put `/>` at the end of the line above it. Run:

`scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.debug.TestHookTest#anotherAppCannotSendCommands'`

Expected: `There were failing tests`, and the failure says that the screen showed `[Hook probe, Probe answered]`.

Then put the line back as it was, and check that the file again contains `android:permission="android.permission.DUMP"`.

- [ ] **Step 9: Add the script**

`scripts/hook.ps1`:

```powershell
# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays
#   test-mode -On true|false                switches test mode
#   snapshot [-Out file.json]               what Kestrel reads on the screen, as JSON
#   overlay -Show numbers|unlabeled|labels|grid|none
#   overlay -Show feedback -Text 'text'     shows the feedback bar
#   tap -Key key | -Number n | -X x -Y y [-Long]
#   type -Key key | -Number n -Text 'text'
#   scroll -Direction up|down|left|right [-Key key | -Number n]
#   global -Action back|home|recent-apps|notifications|quick-settings|lock-screen|screenshot|power-menu|split-screen|play-pause
# -Number picks an element by the number that the numbers overlay shows on it.
# Example: scripts\hook.ps1 -Serial emulator-5554 tap -Number 3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global')][string]$Command,
    [string]$Key,
    [int]$Number,
    [int]$X = -1,
    [int]$Y = -1,
    [switch]$Long,
    [string]$Text,
    [string]$Direction,
    [string]$Action,
    [string]$Show,
    [ValidateSet('true', 'false')][string]$On,
    [string]$Out
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

$extras = @()
if ($Key) { $extras += "--es key $(ConvertTo-ShellText $Key)" }
if ($PSBoundParameters.ContainsKey('Number')) { $extras += "--ei number $Number" }
if ($X -ge 0) { $extras += "--ei x $X" }
if ($Y -ge 0) { $extras += "--ei y $Y" }
if ($Long) { $extras += '--ez long true' }
if ($PSBoundParameters.ContainsKey('Text')) { $extras += "--es text $(ConvertTo-ShellText $Text)" }
if ($Direction) { $extras += "--es direction $(ConvertTo-ShellText $Direction)" }
if ($Action) { $extras += "--es action $(ConvertTo-ShellText $Action)" }
if ($Show) { $extras += "--es show $(ConvertTo-ShellText $Show)" }
if ($On) { $extras += "--ez on $On" }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction $($extras -join ' ')"
# Screen text can be in any language, and adb passes it on as UTF-8.
$consoleEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    $output = (& (Get-Adb) -s $Serial shell $broadcast) -join "`n"
} finally {
    [Console]::OutputEncoding = $consoleEncoding
}

$match = [regex]::Match($output, '(?s)Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$')
if (-not $match.Success) { throw "Unexpected answer from the device:`n$output" }
$data = $match.Groups[2].Value
switch ([int]$match.Groups[1].Value) {
    -1 {
        if ($Out) {
            $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
            [IO.File]::WriteAllText($path, $data, [Text.UTF8Encoding]::new($false))
            "Saved to $path"
        } else {
            $data
        }
    }
    0 { throw 'Kestrel did not answer. Install a debug build with scripts\install.ps1.' }
    default { throw $data }
}
```

- [ ] **Step 10: Try the script on the emulator**

Run:

```powershell
$serial = scripts\start-emulator.ps1
scripts\install.ps1 -Serial $serial
$adb = 'D:\AndroidVMs\SDK\platform-tools\adb.exe'
& $adb -s $serial shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
& $adb -s $serial shell am start -W -S -n dev.kestrel.testapp/.ViewsActivity
scripts\hook.ps1 -Serial $serial state
scripts\hook.ps1 -Serial $serial overlay -Show numbers
scripts\hook.ps1 -Serial $serial snapshot -Out $env:TEMP\kestrel-screen.json
scripts\hook.ps1 -Serial $serial tap -X 540 -Y 1200
scripts\hook.ps1 -Serial $serial tap
```

Expected:
- `state` prints `screen=on testMode=on numbers=0 labels=off grid=off feedback=off`. If it prints `screen=off`, Android dropped the setting because the app had just been installed: run the `settings put` command again, wait a few seconds and repeat.
- `overlay` prints the state with a number greater than 0 after `numbers=`, and the emulator shows the numbers.
- `snapshot` prints `Saved to ...`, and the file contains `"Ana Petrović"`.
- `tap -X 540 -Y 1200` prints `done: gesture`.
- `tap` without a target ends with the error `error: add --es key <element key> or --ei number <number>`.

Then turn the service off again: `& $adb -s $serial shell settings delete secure enabled_accessibility_services`, and delete `$env:TEMP\kestrel-screen.json`.

- [ ] **Step 11: Run all device tests and the checks**

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`, with 60 test cases and no failures.

Run: `scripts\gradle.ps1 :build-logic:convention:check check kestrelAudit`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 12: Commit**

```powershell
git add build-logic app testapp scripts/hook.ps1
git commit -m "feat: add adb test hooks to debug builds" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 13: Documentation, the spike result and the final checks

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (build the network gateway on first use)
- Modify: `README.md` (testing on a device, test hooks)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 14.1, 16.1 and 19.1, and Appendix B)

**Interfaces:**
- Consumes: everything above.
- Produces: `AppGraph.network` is now built on first use; its type and behaviour do not change.

While this plan was checked on a freshly booted emulator, Android stopped Kestrel's process during start-up ("failed to complete startup"), because building OkHttp's client on the main thread took too long while the CPU was busy. Nothing uses the network gateway before sub-project 4, so `AppGraph` now builds it on first use. The release build ships the same 110 libraries as at the end of plan 1A, so `THIRD_PARTY_NOTICES.md` does not change; Step 4 checks that.

- [ ] **Step 1: Build the network gateway on first use**

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` with:

```kotlin
package dev.kestrel.app

import android.content.Context
import android.util.Log
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessages
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}

/** Wires Kestrel's parts together by hand; there is no dependency-injection framework (design spec, section 17). */
class AppGraph(context: Context) {
    /**
     * Background work. A failed job is written to logcat instead of ending the app's process. Only the error's
     * classes and stack frames are written, because a message can contain screen text.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "A background task failed: ${error.describeWithoutMessages()}") },
    )

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()

    // Built on first use: building the HTTP client on the main thread made Kestrel miss Android's start-up deadline
    // on a busy emulator just after it booted.
    val network: NetworkGateway by lazy { NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } } }

    private val screens = MutableStateFlow<ScreenSession?>(null)

    /** Kestrel's screen layer while its accessibility service is connected, otherwise null. */
    val screen: StateFlow<ScreenSession?> = screens.asStateFlow()

    internal fun attachScreen(session: ScreenSession) {
        screens.value = session
    }

    internal fun detachScreen(session: ScreenSession) {
        screens.compareAndSet(session, null)
    }

    private companion object {
        const val TAG = "Kestrel"
    }
}
```

- [ ] **Step 2: Document the device tests and the test hooks in the README**

In `README.md`, after the paragraph that ends with "Auto Blocker must be off while USB debugging is in use.", add:

````markdown
The device tests of `:app` use the fixture screens of `:testapp`, a separate app that `connected-test.ps1` installs first. Each test turns Kestrel's accessibility service off and on again. The tests replace the whole list of enabled accessibility services, so they also switch off any other service on the device. After the tests, Gradle uninstalls Kestrel, which also switches its service off. Some tests touch the screen through a virtual touchscreen that Android's `uinput` tool creates, because touches from `adb shell input` do not reach Android the way a finger's touches do.

If the device tests become slow, or Android restarts while they run, restart the emulator: run `adb -s $serial emu kill`, then `scripts\start-emulator.ps1`. Restarting Android inside the emulator is not enough.

### Test hooks

Debug builds contain test hooks that only adb can use (design spec, section 16.1). `scripts\hook.ps1` sends a command and prints the answer, and the comment at the top of the script lists every command. The screen commands need Kestrel's accessibility service, which you can turn on in Settings > Accessibility, or with adb (this replaces the list of enabled services):

```powershell
adb -s $serial shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
scripts\hook.ps1 -Serial $serial state                       # is the screen layer running, and is test mode on?
scripts\hook.ps1 -Serial $serial snapshot -Out screen.json   # what Kestrel reads on the screen, as JSON
scripts\hook.ps1 -Serial $serial overlay -Show numbers       # numbers on the screen's elements
scripts\hook.ps1 -Serial $serial tap -Number 3               # taps the element with the number 3
scripts\hook.ps1 -Serial $serial test-mode -On false
```

Release builds do not contain the hooks, and the privacy audit fails a release build that does.
````

- [ ] **Step 3: Write the spike result into the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, make these five changes.

In section 14.1, replace the two bullets that start with "**Detection.**" and "If spike S3 shows" with:

```markdown
- **Detection.** The touch watcher (section 9.4) is an accessibility overlay of 1×1 pixel that asks Android to report touches outside it (`FLAG_WATCH_OUTSIDE_TOUCH`). Android injects Kestrel's own gestures from its virtual input device, while the owner's finger comes from the touchscreen. So the watcher ignores touches from the virtual device while one of Kestrel's gestures runs, and reports every other touch. On Android 13 to 16, the owner's touch also cancels Kestrel's running gesture. From Android 16 QPR2, Android lets the gesture finish, and Kestrel stops after it (section 19.1, B35).
- If the A71 shows that the watcher does not receive a real finger's touches, Kestrel uses screen events that it did not cause (clicks, scrolls and text changes) as the signal instead.
```

In section 16.1, replace the paragraph with:

```markdown
Debug builds contain a broadcast receiver that accepts commands only from callers that hold `android.permission.DUMP`, which in practice means `adb shell`. It can inject a transcript or a task, read the Orchestrator's state and switch test mode. It can also read the screen, perform actions and show the overlays, for checks on real apps. Release builds do not contain it, and the build-time audit fails a release build that does.
```

In section 19, after the table of spikes (after the row for S8), add:

```markdown

### 19.1 Results

- **S3 on the emulator (plan 1B).** The touch watcher receives a touch on another app's window as `ACTION_OUTSIDE`, without coordinates. This works for all three test sources: `adb shell input tap`, a virtual touchscreen made with Android's `uinput` tool, and the emulator's own touchscreen. The watcher receives nothing for a touch on a window above it, such as an overlay that Kestrel shows later. Kestrel's own gestures reach the watcher too, but they come from the virtual input device with ID -1, and touchscreens have positive IDs. This difference holds on every version from Android 13 to 17 (B35), so the watcher uses it. On Android 17, a finger on the touchscreen does not cancel one of Kestrel's gestures: Kestrel is told that the gesture completed, while the app receives the finger's touch. On Android 13 to 16, a real touch cancels the gesture, and Android marks the real touch as an accessibility event, so that mark cannot tell the owner from Kestrel. The check with a real finger on the A71 is still open (plan 1C).
```

In Appendix B, change "The facts below were gathered on 11–12 November 2026." to "The facts below were gathered on 11–13 November 2026.", and add this row after the row for B34:

```markdown
| B35 | On Android 13 to 16, `MotionEventInjector` cancels an accessibility service's injected gesture when a real touch arrives, and marks the real touch as an accessibility event. From Android 16 QPR2, it passes real touches on unchanged, and only the app's copy of the injected gesture is cancelled. Injected gestures come from the virtual input device -1 in every version. | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java and the same file on the `android13-release`, `android15-release` and `android16-qpr2-release` branches | ✔ |
```

- [ ] **Step 4: Check that the shipped libraries did not change**

Run: `scripts\gradle.ps1 :app:kestrelCheckDependenciesRelease`

Expected: `BUILD SUCCESSFUL`. `app/build/reports/kestrel-audit/dependencies-release.txt` lists 110 libraries, the same as at the end of plan 1A, so `THIRD_PARTY_NOTICES.md` stays as it is. If the list differs, add the new libraries to the notices with their licences before you continue.

- [ ] **Step 5: Run every check**

Run: `scripts\gradle.ps1 :build-logic:convention:check check kestrelAudit`

Expected: `BUILD SUCCESSFUL`.

Run: `scripts\connected-test.ps1 -Serial (scripts\start-emulator.ps1) :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`: 7 test cases for `:core:privacy` and 60 for `:app`, all without failures.

- [ ] **Step 6: Commit and push**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/AppGraph.kt README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs: record spike S3 and document the device tests and test hooks" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git push
```

- [ ] **Step 7: Watch CI**

```powershell
$run = gh run list --limit 1 --json databaseId | ConvertFrom-Json
gh run watch $run[0].databaseId --exit-status
```

Expected: the run ends with success. CI runs `:build-logic:convention:check check kestrelAudit`; it does not run the device tests.
