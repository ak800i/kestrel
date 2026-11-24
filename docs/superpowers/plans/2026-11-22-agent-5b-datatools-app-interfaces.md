# Plan 5B: the interfaces of Tasks 1 to 7 (writer 5B-A), and the names that plan 5C uses from plan 5B

Writer 5B-A, 2026-11-21. Writers 5B-B (Tasks 8 to 14) and 5B-C (Tasks 15 to 20) use the names of sections 1 to 3 as
Tasks 1 to 7 create them. Section 4 fixes the names that Tasks 8 to 20 must create because plan 5C uses them; 5B-B and
5B-C create them exactly so, and 5C-A and 5C-B use only sections 4 and 5 of this file (and section 3's `ScreenSession`
lines) from plan 5B. A name that a writer needs and that is missing here goes to the controller. Plan 5A's names are as
built at `c5297c5` (its interfaces file `docs/superpowers/plans/2026-11-20-agent-5a-core-safety-providers-interfaces.md`).

## 0. Who changes what, and the counts

- Tasks 1 to 5: `core/datatools/**`, the contacts' move out of `app/src/main/kotlin/dev/kestrel/app/commands/`,
  `app/src/main/kotlin/dev/kestrel/app/listen/BoostWordSource.kt` (D32 item 44), one line of `AppGraph.kt` (the boost
  words' source, not near any anchor), `app/build.gradle.kts` (one line below `// sub-project 5B: dependencies`), the
  permissions and `<queries>` of `app/src/main/AndroidManifest.xml` (below `<!-- sub-project 5B: permissions -->` and
  `<!-- sub-project 5B: queries -->`), and spec 11.5, 13.6 and Appendix B rows B56 to B61.
- Tasks 6 and 7: `core/screen/**` (`act/OwnActions.kt`, `act/OwnerSignal.kt`, `act/PressPolicy.kt`, `act/ScreenActions.kt`,
  `act/ActionResult.kt`, `overlay/TouchWatcher.kt`, `overlay/ConfirmationCard.kt`,
  `ScreenSession.kt` below `// sub-project 5B` and its `confirm` and `startTouchWatcher`), the device tests
  `app/src/androidTest/kotlin/dev/kestrel/app/screen/OwnActionsTest.kt`, `ConfirmationCardTest.kt`, `TapTest.kt`,
  `GridBarAndCardTest.kt`, `SystemActionsTest.kt`, `TypeTest.kt`, the fixture `testapp/.../PressActivity.kt` (manifest line
  below `<!-- sub-project 5B: fixtures -->`), and spec 14.1.
- Task 8 (5B-B, `:core:screen`'s tooltip, `scrollAxis`, Firefox and the notification-event rule) changes
  `ScrollActions.kt`, `ScreenActions.kt`, `SnapshotBuilder.kt`, `NodeData.kt`, `AccessibilityNodeData.kt` and
  `ScreenEvents.kt` after Task 7: its "replace" texts must be written against `ScreenActions.kt` as Task 7 leaves it
  (plan part A's Tasks 6 and 7 hold every changed text).
- Appendix B: Tasks 1 to 5 add B56 to B61 (B56 r53, B57 r55, B58 r65, B59 r56, B60 r58, B61 r54), each below the row
  before it; B61's row ends with `| https://developer.android.com/about/versions/15/behavior-changes-all and https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/notification/NotificationManagerService.java | † |`.
  B62 (r57, Firefox) and B63 (r60, tethering) are free for Tasks 8 to 20; insert them below B61's row.
- Starting counts of plan 5B (the brief): `:app` JVM 488 (with `app/src/testDebug`), `:core:commands` 157,
  `:core:providers` 456, `:core:safety` F₀ (95 or more), `:core:screen` 70, `:core:screen-model` 22, `:core:agent` G₀ (181
  or more), `:core:datatools` 0 (JVM) and 0 (device), Pester 282 per shell; `:app`'s device tests: voice 96, commands
  108, screen 64, settings 50, debug 14, providers 20, the root package 15 (367 in all).
- Counts after Task 7 (writer 5B-A's tasks): `:app` JVM 488 − 4 = 484 (`ContactsReaderTest` moves out) + 3
  (`BoostWordSourceTest`) = 487; `:core:datatools` JVM 0 + 4 (moved) + 20 = 24; `:core:datatools` device 0 + 6 =
  6 (`DataToolsTest`); `:core:screen` 70 + 15 = 85; `:app` device screen 64 + 3 (`OwnActionsTest`) + 3
  (`ConfirmationCardTest`) + 2 (`TapTest`) + 1 (`SystemActionsTest`) + 1 (`TypeTest`) = 74; the other groups unchanged.
  Each task of part A lists its own "N + k".

## 1. `:core:datatools` (Tasks 1 to 5), package `dev.kestrel.datatools`

Build file after Task 5 (`core/datatools/build.gradle.kts`): `kestrel.android.library`, namespace `dev.kestrel.datatools`,
`implementation(project(":core:agent"))`, `implementation(project(":core:commands"))`, `api` of nothing else;
`testImplementation(libs.junit)`, `testImplementation(libs.kotlinx.coroutines.test)`, `androidTestImplementation(libs.androidx.test.ext.junit)`,
`androidTestImplementation(libs.androidx.test.runner)`, `androidTestImplementation(libs.kotlinx.coroutines.test)`; no new
library. `:app` gets `implementation(project(":core:datatools"))` (Task 1). Task 12 (5B-B) adds `:core:agent` to `:app` and
`":core:datatools:connectedDebugAndroidTest"` to `:app`'s `mustRunAfter` list. `core/datatools/src/main/AndroidManifest.xml`
declares `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` from Task 4 on (lint's `MissingPermission` reads the module's
own manifest), and Task 5 adds the listener's service below them.

### Contacts (Task 1), package `dev.kestrel.datatools.contacts`

Moved from `:app` (`dev.kestrel.app.commands`) and made public; `:app`'s instant commands import them from here.
- `fun interface ContactsAccess { fun allowed(): Boolean }` and `fun contactsAccess(context: Context): ContactsAccess`
  (`READ_CONTACTS` granted).
- `interface ContactsSource { fun read(): List<Contact>; fun watch(changed: () -> Unit): AutoCloseable }` (`Contact` and
  `PhoneEntry` are `:core:commands`' `dev.kestrel.commands.phone`).
- `class ContactsReader(access: ContactsAccess, source: ContactsSource, report: (String, Throwable) -> Unit) { fun contacts(): List<Contact>? }`
  — unchanged behaviour (kept until a change; null without the permission).
- `class AndroidContacts(resolver: ContentResolver) : ContactsSource` with `fun details(): List<ContactDetails>` (one query
  of `ContactsContract.Data` by mimetype: names, nicknames, phones, e-mail addresses and the messaging apps' rows).
- `data class ContactDetails(val contact: Contact, val emails: List<String>, val apps: Set<String>)` — `apps` are
  `MessagingApps`' names; `toString` prints counts only.
- `object MessagingApps { const val TELEGRAM = "Telegram"; const val WHATSAPP = "WhatsApp"; const val VIBER = "Viber"; const val SIGNAL = "Signal"; val MIMETYPES: Map<String, String>; fun appOf(mimetype: String?): String? }`
  (D12's four mimetypes; Viber Out's rows do not count).
- The boost words of D32 item 44 (Task 1, `:app`, package `dev.kestrel.app.listen`): `fun interface PeopleNames { suspend fun read(): List<String> }`,
  `class OwnerNames(contacts: ContactsReader, aliases: suspend () -> List<String> = { emptyList() }, found: suspend () -> List<String> = { emptyList() }) : PeopleNames`
  (aliases always; the starred contacts and `found` only while `READ_CONTACTS` is granted), and
  `BoostWordSource(labels, people = PeopleNames { emptyList() }, report)`. Task 1 leaves in `AppGraph.kt`, above the
  KDoc of `val listening`, exactly this declaration, which Task 14 replaces whole with one that also passes
  `aliases = { <memory's alias keys> }` and `found = { <the names of the contacts that the last 20 tasks found> }`:
  ```kotlin
      private val ownerNames = dev.kestrel.app.listen.OwnerNames(
          dev.kestrel.datatools.contacts.ContactsReader(
              dev.kestrel.datatools.contacts.contactsAccess(appContext),
              dev.kestrel.datatools.contacts.AndroidContacts(appContext.contentResolver),
              reportFailure,
          ),
      )
  ```

### The data port (Task 5), class `dev.kestrel.datatools.AndroidDataPort`

```kotlin
class AndroidDataPort(
    context: Context,
    open: suspend (Intent) -> Boolean,
    isAgentFree: (String?) -> Boolean,
    zone: () -> ZoneId = ZoneId::systemDefault,
) : DataPort
```
- `open` starts an activity for the tools that open an app's screen (`share_media`, `create_event`, `set_alarm`,
  `set_timer`, `compose_sms`, `navigate`); Task 14's `AppDataPort` wiring passes
  `{ intent -> graph.screen.value?.actions?.openIntent(intent) is ActionResult.Done }` (an activity start through
  Kestrel's accessibility service, which Android allows from the background). A false answer gives
  `DataResult.Failed("the app did not open")`.
- `isAgentFree` is the task's agent-free list (`AgentFreeApps.isAgentFree`, which counts `dev.kestrel` always); only
  `list_notifications` reads it.
- Every provider query runs on `Dispatchers.IO`; nothing is logged; no coroutine scope of its own.
- Permissions that each tool returns in `DataResult.NeedsPermission` (asked together, D12): `find_contact`
  `[READ_CONTACTS]`; `find_media` `[READ_MEDIA_IMAGES, READ_MEDIA_VIDEO]` plus `READ_MEDIA_VISUAL_USER_SELECTED` on API 34
  and later; `list_events` `[READ_CALENDAR]`; `list_sms` `[READ_SMS]`; `list_calls` `[READ_CALL_LOG]`; `get_location`
  `[ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION]`. `list_notifications` without notification access gives
  `DataResult.NeedsAccess(AccessKind.NOTIFICATIONS)`.
- Result lines (the contracts of `DataSteps`): `find_contact` gives one line per contact with the field `name` (5A's
  `DataSteps.NAME_FIELD`), then `phone` fields ("<number> (<kind>)") and `e-mail` fields, and the fixed text
  `contact <n> (apps: Telegram, Viber)` or `contact <n> (no app link found; the contact may still use an app)`;
  `find_media` gives fixed texts `m<n> · photo · <yyyy-MM-dd HH:mm> · <size>` or `m<n> · video · …` and no field, with
  the note `Android gave Kestrel only the photos that you selected.` under partial access; `share_media` gives one fixed
  line and `opened` = the package; `list_notifications` gives fields `app`, `title`, `message` (each "<sender>: <text>",
  the last five) or `text`, and `sub`.
- `forgetTask()` drops `find_media`'s item table (`m1`, `m2`, …).

### Intents of the `open_*` tools (Task 4), object `dev.kestrel.datatools.IntentSpecs`

- `fun toIntent(spec: IntentSpec): Intent` — `ACTION`, the data URI, `setPackage`, `CATEGORY_BROWSABLE` when
  `spec.browsable`, the string extras, `FLAG_ACTIVITY_NEW_TASK`. `AppScreenPort` (Task 13) maps `ScreenAction.OpenIntent`
  onto `ScreenActions.openIntent(IntentSpecs.toIntent(spec))`.
- `fun resolve(packageManager: PackageManager, spec: IntentSpec): String?` (and the same for an `Intent`) — the package of the activity that
  `resolveActivity(MATCH_DEFAULT_ONLY)` names, or null when none does or when it is Android's chooser (the packages
  `android` and `com.android.intentresolver`). `AppScreenPort.resolve` calls it on `Dispatchers.IO`.
- The design's `web.BrowserIntents` and `settings.SettingsPages` are not created: plan 5A's `tools.Browser` and
  `tools.SettingsPage` already hold the browsers and the pages, and `IntentSpecs` turns their `IntentSpec`s into intents.

### Notifications (Task 5), package `dev.kestrel.datatools.notifications`

- `class KestrelNotificationListener : NotificationListenerService()`, declared in `core/datatools/src/main/AndroidManifest.xml`
  with `BIND_NOTIFICATION_LISTENER_SERVICE`; component `dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener`.
  `companion object { val connected: StateFlow<Boolean>; fun component(context: Context): ComponentName; fun hasAccess(context: Context): Boolean; fun accessSettings(context: Context): Intent }`
  — `connected` is true between `onListenerConnected` and `onListenerDisconnected`; `accessSettings` opens Android's page
  for Kestrel's listener (`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS`, with `FLAG_ACTIVITY_NEW_TASK`). The `ACCESS`
  hand-over (Task 13) opens it and ends by itself when `connected` becomes true; the checklist step
  `NOTIFICATION_ACCESS` (Task 15) uses `hasAccess` and `accessSettings`.
- Restricted settings: Task 5 records on kestrel-api37 whether Android 17 treats notification access as a restricted
  setting after `scripts\install.ps1`; whatever the result, the checklist step `NOTIFICATION_ACCESS` (Task 15) shows the
  sentence "If Android says that this setting is restricted, open Kestrel's App info, tap the ⋮ menu, then 'Allow
  restricted settings', and try again." (spec 13.6 as Task 5 changes it).

## 2. `:core:screen` (Tasks 6 and 7)

### Package `dev.kestrel.screen.act`

- `enum class PressPolicy { ONCE, VERIFIED }` (Task 7). `ONCE`: one press; a node action that answers false, or a
  fallback gesture that ends `TIMED_OUT`, gives `ActionResult.UNCERTAIN` and nothing more. `VERIFIED`: after such a press
  Kestrel waits for a quiet screen and presses a second time only when the press's window shows exactly the same elements
  as before it (a gesture on the element when its node had refused, its fresh node's action when the node was replaced),
  never a third time; a changed window gives `UNCERTAIN`.
- `ActionResult.UNCERTAIN = ActionResult.Failed("The press may not have worked.")` and
  `ActionResult.CARD_OPEN = ActionResult.Failed("A confirmation question is open.")` (Task 7), beside `CHANGED`,
  `COVERED` and `NOTHING_SCROLLS`. `AppScreenPort` maps `UNCERTAIN` to `ActionOutcome(done = true, uncertain = true)`, because plan 5A's `ScreenSteps` reads `uncertain` only on an outcome that is done.
  `tapAt` and `longPressAt` also give `UNCERTAIN` when their gesture ends `TIMED_OUT` (fix round of 2026-11-22).
- `ScreenActions` (Task 7):
  - `suspend fun tap(key: String, expected: ((ScreenElement) -> Boolean)? = null, policy: PressPolicy = PressPolicy.VERIFIED): ActionResult`
  - `suspend fun longPress(key: String, expected: ((ScreenElement) -> Boolean)? = null, policy: PressPolicy = PressPolicy.VERIFIED): ActionResult`
  - `suspend fun setText(key: String, text: String, expected: ((ScreenElement) -> Boolean)? = null): ActionResult` —
    `expected` is checked on the fresh snapshot before the set-text action and again before the input method's fallback
    (`ActionResult.CHANGED` when it refuses).
  - `suspend fun openUri(uri: String, packageName: String? = null, browsable: Boolean = false): ActionResult` — `setPackage`
    and `CATEGORY_BROWSABLE` when given.
  - `suspend fun openIntent(intent: Intent): ActionResult` — `Done(ActionMethod.INTENT)`, or `Failed("No app can open it.")`,
    `Failed("Android does not let Kestrel open it.")`.
  - While the confirmation card shows, every gesture and node action of `ScreenActions` (taps, presses, `tapAt`,
    swipes, drags, pinches, text, scrolls, sliders, expand and collapse, dismiss, `swipeAway`) returns
    `ActionResult.CARD_OPEN` without acting, and `clickLabelled` returns false; global actions, magnification and the
    intents are not refused (the agent's loop asks one thing at a time and acts on nothing while a card waits). The
    card's bounds count as a window above every element for the fallback gestures.
- `@Serializable enum class TouchRule { NONE, FIELD, CREDITS }` (Task 6): `NONE` ignores only the virtual device's touches
  while a gesture of Kestrel's runs (and 100 ms after); `FIELD` also ignores touches with `MotionEvent`'s accessibility
  flag (0x800) on API 37 and later (on 33 to 36 it acts as `CREDITS`); `CREDITS` also ignores one touch per accepted
  click or long click of Kestrel's within `OwnActions.CREDIT_MILLIS` (400 ms) after the call returned.
- `@Serializable enum class TouchBranch { A, B }` and `@Serializable data class TouchSource(val rule: TouchRule, val branch: TouchBranch = TouchBranch.A)`
  with `companion object { fun defaultFor(sdk: Int): TouchSource }` (`NONE`/A on API 33, `CREDITS`/A above it). On
  branch B, `OwnerSignal` also reports the owner's clicks and text changes in other apps' windows (D19).
- `class OwnActions` (Task 6): constructed by `ScreenSession`; `const val CREDIT_MILLIS = 400L`,
  `const val OWN_WINDOW_TAIL_MILLIS = 300L`, `const val LATE_ECHO_MILLIS = 1_000L`, `enum class Kind { CLICK, LONG_CLICK, SCROLL, SET_TEXT, FOCUS, OTHER }`,
  `val ECHOING: Set<Kind>` (`CLICK`, `LONG_CLICK`). `:app` uses it only through `ScreenSession`.
- `data class Touch(val owner: Boolean, val deviceId: Int, val source: Int, val toolType: Int, val flags: Int, val sinceNodeActionMillis: Long?, val credited: Boolean)`
  (Task 6): one classified touch of the watcher. `sinceNodeActionMillis` is the time since Kestrel's last node action's
  call returned (null when none ran while the watcher ran). AgentRunner (Task 12) writes an activity-log line "possible
  late echo after <n> ms" for an owner's touch with `sinceNodeActionMillis` up to `OwnActions.LATE_ECHO_MILLIS`.

### `ScreenSession` (Tasks 6 and 7)

- `suspend fun startTouchWatcher(): Boolean` (Task 6): true when the watcher's window was added (also when it already
  showed); starting it clears every credit. `AgentRunner` refuses a task when it answers false.
- Below `// sub-project 5B` (Task 6): `var touchSource: TouchSource` (any thread; default `TouchSource.defaultFor(Build.VERSION.SDK_INT)`),
  `val touches: SharedFlow<Touch>` (every touch that the watcher saw, Kestrel's and the owner's, and each owner's signal
  of branch B or a cancelled gesture as a `Touch` with `deviceId = -2`), and `fun forgetOwnActions()` (clears every
  credit; `AppOwnerPort` calls it before it asks a question, D19 rule 3). `ownerTouches` stays and emits only the owner's.
- Below `// sub-project 5B` (Task 7): `val cardShowing: Boolean` (the confirmation card is on the screen; after it turns
  false, `AppScreenPort` waits one settle, `snapshots.current()`, before it reads the confirmed action's snapshot, D8).
- `suspend fun confirm(question: String): Boolean` keeps its contract and adds (Task 7): a card ignores taps for its first
  600 ms (`ConfirmationCard.ARMING_MILLIS`), and a new card appears no sooner than 600 ms after the previous one closed;
  showing a card clears every credit.
- `// sub-project 5C` stays the last anchor of the class for plan 5C (capture, `hideAllForCapture`, `showBadges`).

## 3. The device tests of part A

- `app/src/androidTest/kotlin/dev/kestrel/app/screen/OwnActionsTest.kt` (Task 6): `probeRecordsWhatKestrelsActionsLookLikeToTheWatcher`
  (D19's probe; it writes one line per case to logcat with the tag `KestrelProbe`, numbers only:
  `case=<click|long-click|scroll|set-text|ime-focus|refused-click|gesture|finger|input-tap> sdk=<n> touches=<n> device=<id|none> source=<n|none> tool=<n|none> flags=<hex|none> delayMs=<n|none>`,
  and on API 34 and later fails when a kind outside `OwnActions.ECHOING` or a refused click reaches the watcher, or when
  an echo comes later than half of `CREDIT_MILLIS`), `aFingerOneSecondAfterAnEchoedClickIsTheOwners`,
  `aFingerRightAfterTheWatcherStartsIsTheOwnersAfterAnEarlierClick`. Plan 5C's sitting 1 runs the same class on the A71
  (D30 step 9) and reads `logcat -d -s KestrelProbe:I`.
- `ConfirmationCardTest` (Task 7, `dev.kestrel.app.screen`): the arming delay, a new card's delay, and the refusal of
  actions while the card shows.
- `TapTest` (Task 7): `aRefusedClickThatActedIsPressedOnceAndUncertain`, `aRefusedClickThatDidNothingIsPressedOnceMoreByAGestureOnlyWhenVerified`
  on `:testapp`'s `PressActivity` ("Press fixture", buttons "Stubborn" and "Deaf", status "Pressed <n>").
- `DataToolsTest` (`core/datatools/src/androidTest/kotlin/dev/kestrel/datatools/DataToolsTest.kt`, Tasks 1 to 4): the
  contacts with the messaging rows, the media items and their share intent, the calendar rows, the intents of
  alarms, timers, SMS and navigation; run with
  `scripts\connected-test.ps1 -Serial <serial> :core:datatools:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`.

## 4. Names of Tasks 8 to 20 that plan 5C uses (5B-B and 5B-C create them exactly so)

### `AppGraph.kt` (Task 12, 5B-B), directly below `// sub-project 5B: agent`

```kotlin
    /** The agent's eyes for look_closer (design D20): NoEyes until plan 5C's block below sets them. AgentRunner reads it at each task. */
    internal var agentEyes: EyesPort = NoEyes

    /**
     * ScreenPort.showBadges and hideBadges (design D20's third step): they do nothing until plan 5C's block below sets
     * them to ScreenSession's. AppScreenPort calls them with the session that is connected when the step runs.
     */
    internal var showAgentBadges: suspend (ScreenSession, List<dev.kestrel.agent.port.Badge>) -> Unit = { _, _ -> }
    internal var hideAgentBadges: suspend (ScreenSession) -> Unit = { }

    /** The screenshot approvals per app and provider (design D20): none until plan 5C's block below sets them. SettingsMemoryStore calls them at each use. */
    internal var screenshotApproved: suspend (packageName: String, providerId: String) -> Boolean = { _, _ -> false }
    internal var approveScreenshots: suspend (packageName: String, providerId: String) -> Unit = { _, _ -> }
```
`SettingsMemoryStore` (Task 14) implements plan 5A's `MemoryStore.screenshotApproved(packageName, providerId)` and
`approveScreenshots(packageName, providerId)` by calling `graph.screenshotApproved(…)` and `graph.approveScreenshots(…)`
at each call (or through two constructor lambdas that read these vars at each call), because the key
`SCREENSHOT_APPROVALS` is plan 5C's (below `// sub-project 5C: keys`). (Proposed by writer 5C-A, 2026-11-21.)
Plan 5C writes, below `// sub-project 5C: eyes`, an `init { … }` block that sets these five, and `AgentRunner` and
`AppScreenPort` read them when a task or a step runs, never when they are built. `EyesPort` and `NoEyes` are plan 5A's
(`dev.kestrel.agent.port`); `ScreenSession` is `dev.kestrel.screen.ScreenSession`; the screen session that is connected is
`graph.screen.value`.

### Settings keys (`SettingKeys` in `AppGraph.kt`, below `// sub-project 5B: keys`; Tasks 12, 14 and 18)

- `AGENT_LIVE_SESSION = SettingKey("agent-live-session", Boolean.serializer(), false)` (Task 12): a tagged task with
  `live=true` runs against the real profile only while it is true; only plan 5C's `agent-live` hook changes it.
- `TOUCH_RULE = SettingKey("touch-rule", TouchSource.serializer(), TouchSource.defaultFor(Build.VERSION.SDK_INT))` (Task 18):
  `agent-touch-source` stores it, and `AppGraph` applies it to each new `ScreenSession` (`touchSource`).
- `AGENT_CACHE_FIELDS = SettingKey("agent-cache-fields", CacheFields.serializer(), CacheFields.ALL)` (Task 14).
- The other keys of the design's list (`AGENT_FREE_APPS`, `MEMORY_FACTS`, `ROUTE_NOTES`, `ROUTINES`, `ROUTINE_SIGNATURES`,
  `FOUND_CONTACTS`, `AGENT_FREE_REVIEWED`) are 5B-B's and 5B-C's to shape; plan 5C uses none of them.

### `TaskOptions` (plan 4D, as built)

`Listening.taskOptions: ((task: Int) -> Map<String, String>)?` gives once per task the extras of a tagged transcript:
`"model" to <id>` and `"live" to "true"`. `AgentRunner` (Task 12) reads it when the task starts: `model` replaces the
lightweight model's ID for that task only; `live=true` uses the real `ModelPort` only while `AGENT_LIVE_SESSION` is true.
A tagged task (one with a `CommandTags` tag) without an installed `agent-script` and without a live session does not run
(D28); `agent-task -Result` then answers `state=done` with an `end` other than `FINISHED` and `steps=0`.

### Debug hooks (Task 18, 5B-C; `AgentHooks.kt` below `// sub-project 5B: command branches`, `hook.ps1` below its 5B anchors)

Every answer is one line of `name=value` pairs separated by single spaces, no spaces inside a value (plan 4D's rule);
an error is `error: <message>` with result code 1. `hook.ps1`'s parameters are named here; existing parameters are reused.

| Command (`hook.ps1 -Serial <s> <command> …`) | Parameters | Answer |
|---|---|---|
| `agent-task -Result -Tag <k> [-Marker <word>] [-Recipient <text>] [-Digits <digits>]` | `-Result`, `-Tag` (existing), `-Marker`, `-Recipient` (new, `--es marker`, `--es recipient`), `-Digits` (`--es digits`; added by plan 6B-basefix) | `state=running` or `state=done end=<EndReason> steps=<n> workMs=<n> credits=<x.xx> confirmed=<n> unconfirmed=<n> shareItems=<n> recipient=<match\|other\|none> marker=<0\|1> beforeTestMode=<n> model=<id\|none> in=<n> out=<n> cacheRead=<n> cacheWrite=<n> ownerTouches=<n> signals=<n>`, then with `-Marker` ` markerSaid=<0\|1> markerInData=<0\|1\|none>` and with `-Digits` ` digits=<0\|1>`; `error: no task has the tag <k>` (`ownerTouches` counts the owner's touches that the touch watcher reported during the task and `signals` those among them without a finger; `marker` and `digits` count in the end's sentence and in the task's `say` texts, `markerSaid` in the `say` texts only, and `markerInData` whether a data tool's result held the marker that `voice -Marker` gave the task, `none` when it gave none; added by plan 6B-basefix) |
| `agent-answer -Tag <k> -Answer <a>` | `-Answer` (new): `yes`, `no`, `confirm`, `cancel`, `continue`, a number, or `text:<text>` | `answered tag=<k> asking=<kind>`; `error: the task of tag <k> asks nothing` |
| `agent-script -File <name>` or `agent-script -Clear` | `-File`, `-Clear` (existing) | `script=installed replies=<n>` or `script=none`; the file is `files/agent-scripts/<name>` in Kestrel's files folder, a JSON array of plan 5A's `ScriptedModel` replies, which `task-check.ps1` copies there with `run-as dev.kestrel` |
| `agent-state -Tag <k>` | `-Tag` | `phase=<running\|handed-over\|paused\|ended> asking=<yes-no\|confirm\|free\|continue\|pick-number\|none> work=<idle\|awaiting-task\|running-task\|running-command> handOver=<HandOverKind\|none> confirming=<category\|none> question=<yes-no\|free\|pick-number\|screenshot\|none>` (`confirming` is the wire name of the open confirmation's `Category`, such as `sending` or `deleting`; added by the fix round of 2026-11-22. `question` is the kind that the agent asked the open question with, which tells `look_closer`'s screenshot question from the model's own free question; added by plan 6B-basefix) |
| `agent-cache -Field <anthropicMarks\|responsesExplicit\|chatCopilotMarks> -On <true\|false>` | `-Field` (new), `-On` (existing) | `anthropicMarks=<on\|off> responsesExplicit=<on\|off> chatCopilotMarks=<on\|off>` |
| `agent-touch-source -Rule <NONE\|FIELD\|CREDITS> -Branch <A\|B>` or `agent-touch-source` | `-Rule`, `-Branch` (new) | `rule=<rule> branch=<A\|B> sdk=<n>` (without parameters it only answers) |
| `agent-settings [-OwnerEmails <a,b>] [-ViberSelfChatName <t>] [-WhatsappOwnName <t>] [-SelfChatAliases <pkg=pkg,…>]` | four new parameters; any other key is refused with `error: agent-settings has no key <name>` | `ownerEmails=<n> viberSelfChatName=<set\|unset> whatsappOwnName=<set\|unset> selfChatAliases=<n>` |
| `agent-memory -Add -Key <k> -Text <value>` or `agent-memory -Remove -Key <k>` | `-Add`, `-Remove` (new switches), `-Key`, `-Text` (existing) | `keys=<n>` and, for `-Add`, `added=<k>` (the key only, which the script chose) |
| `agent-data -Tool <list_sms\|list_calls\|find_media\|list_notifications> [-Text <argument>]` | `-Tool` (new) | `result=<ok\|empty\|permission\|access\|failed> items=<n>` |
| `selfchat-check` | none | `selfchat=<yes\|no> signs=<comma-separated sign names\|none>` |
| `agent-tree` | none | `tree=written path=cache/agent-tree.json windows=<n> elements=<n>`; the script reads and removes the file with `run-as dev.kestrel` |
| `screen-tap -Label <text>` | `-Label` (existing) | `tapped=<true\|false> count=<n>`; refuses Kestrel's own app and a phone package |

H1 (`voice -Send heard -Text "Kestrel, <task>" -Tag <k> [-Model <id>] [-Live]`), H2 (`voice-expect`) and H3
(`screen-has`, with `-Contains`) are plan 4D's as built. Plan 5C's `agent-live -On <true|false>` goes below
`// sub-project 5C: command branches` and sets `AGENT_LIVE_SESSION`.

### `scripts\task-check.ps1` (Task 20, 5B-C)

- `#Requires -Version 7.3`. Parameters:
  ```powershell
  param(
      [Parameter(Mandatory = $true)][string]$Serial,
      [Parameter(Mandatory = $true)][string]$Task,
      [ValidateRange(1, 10)][int]$Runs = 3,
      [string]$Script,
      # sub-project 5C: live parameter
      [string]$Results
  )
  ```
  `$Results` (optional) is a file to which each run's line is also appended (`Add-SessionResult` of `scripts\lib\Session.ps1`).
- The body holds exactly these three lines, in this order, before the first run:
  ```powershell
  $live = $false
  # sub-project 5C: live
  if (-not $live -and -not $Script) { throw 'task-check.ps1 needs -Script <file> (the scripted model), or -Live in a sitting.' }
  ```
  Plan 5C inserts below `# sub-project 5C: live` the check of the sitting (`KESTREL_AGENT_SESSION` and the device's
  `AGENT_LIVE_SESSION`) that sets `$live = $true`. With `$live` true, each run sends H1 with `-Live` and installs no script;
  otherwise it installs `$Script` with `agent-script` and clears it after the run.
- The table's task names: `telegram-late`, `hotspot`, `waze-home`, `firefox-wiki`, `telegram-photos`, `read-email`,
  `inject-01` to `inject-14`, and the stand-in variants `stand-in-messenger-late`, `stand-in-messenger-photos`,
  `stand-in-hotspot`, `stand-in-firefox`, `stand-in-read`, which run on `:testapp`'s fixtures with the scripted model.
  An unknown `-Task` throws `Unknown task <name>.`
- Output: one line per run, also into `$Results`:
  `task=<name> run=<i> result=<pass|fail|skipped> end=<EndReason|none> check=<pass|fail|skipped> steps=<n> workMs=<n> credits=<x.xx> model=<id|none> in=<n> out=<n> cacheRead=<n> cacheWrite=<n> reason=<word>`
  (`reason`: `ok`, `timeout`, `check`, `end`, `untestable`, `limit`, `confirm`). The exit code is 0 when every run passed or was
  skipped, else 1. It never creates, waits for or deletes the emulator lock.
- Confirmations (fix round of 2026-11-22; plan 5C's review 2, C1 and I11): each table entry's `Confirm` names the one
  category that the run answers "confirm" (`sending` for `telegram-late`, `telegram-photos`, `stand-in-messenger-late`
  and `stand-in-messenger-photos`; `none` for every other task), read from `agent-state`'s `confirming`. Any other
  confirmation, a delete above all, gets "cancel"; outside the injection fixtures the run then fails with `reason=confirm`
  and a warning that names only the category. Before the first run the script throws when `hook.ps1 state` does not
  answer `testMode=on`, and before each run it waits up to 90 s for Kestrel to listen (a restore that took back one of
  Kestrel's permissions ends its process). `Grant-Missing` grants `READ_MEDIA_VISUAL_USER_SELECTED` only on API 34 and later.

### `scripts\lib\Photos.ps1` (Task 20, 5B-C), dot-sourced, no `#Requires`

- `New-TestPhoto -Path <file> -Number <n> -Taken <datetime> -Offset <'+HH:MM'>` writes a JPEG card with Kestrel's APP1 EXIF
  segment (`DateTimeOriginal`, `OffsetTimeOriginal`); `Read-ExifDate -Path <file>` returns
  `[pscustomobject]@{ DateTimeOriginal = 'YYYY:MM:DD HH:MM:SS'; OffsetTimeOriginal = '+HH:MM' }`.
- `Add-TestPhotos -Serial <s> -Day <'yesterday'|'yyyy-MM-dd'> -Count <n>` returns `[pscustomobject]@{ Before = <images before>; Folder = '/storage/emulated/0/Pictures/KestrelTest' }`
  and throws `The device already has photos of <day>; the test would share them too.` when that day has any.
- `Remove-TestPhotos -Serial <s> -Before <n>` deletes the folder and its rows and returns `$true` when the count of images
  equals `Before` again.

## 5. What plan 5C may rely on from plan 4D (checked against the code at `c5297c5`)

The design's contract items of "Shared files and anchors", as built by plan 4D:
1. H1 stores the task number of a tagged transcript that starts a task: `voice-state -Tag k` answers `role=task task=<n>`
   (`CommandTags`, `VoiceHooks`). Present.
2. H1's extras reach the debug-only `TaskOptions` seam: `Listening.taskOptions`, `TaskOptions`, `VoiceHooks.taskOptions`
   with the anchor `// sub-project 5B` for further extras. Present.
3. `Invoke-AutoCheck -Wait <scriptblock> -WaitSeconds <n>` (at most 600) in `scripts\lib\AutoCheck.ps1`. Present.
4. The silent clip microphone: `voice-clip -Quiet`. Present.
5. `Unlock-TestPhone -Serial -ConfigPath` and `Read-A71Config -Path` (without the key `pin`) in `scripts\lib\Session.ps1`,
   with `Send-TestPin` as the only reader of `pin`. Present (with the A71 run's fix 1958cab).
