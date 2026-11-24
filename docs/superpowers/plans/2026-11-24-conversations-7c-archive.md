# The Encrypted Archive of Conversations (Plan 7C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Kestrel's conversations on the phone, encrypted under a key of their own that "delete history" erases, in version 3 of Kestrel's database, behind plan 7B's archive port, and purge the old ones while Kestrel runs.

**Architecture:** `:core:privacy` gets the table `conversations` (`ConversationRow`, `ConversationHeader`, `ConversationDao`, whose writes keep at most one conversation open) in version 3 of `KestrelDatabase`, with Room's automatic migration from version 2; the erasable key `kestrel.conversations` (`ErasableKey`, `KeystoreErasableKey`); and `ConversationArchiveStore`, which knows only plain fields and bytes: it encrypts each payload bound to its conversation's id, tells a payload that fails its check from one in a format that this version cannot read, keeps and reports the latter by its kind only, and erases its key with its rows. `PrivacyStore.conversations` builds it. In `:app`, `RoomRecordArchive` implements plan 7B's `RecordArchive` on it, `AndroidConversationClock` is the conversation's clock, and `AppGraph` builds plan 7B's `ArchivedConversationStore`, which reports a deleted conversation to the activity log by its kind, and plan 7B's `Retention`, which `KestrelApplication` runs for the life of the process. The design's section 9.2 is brought in line with the result.

**Tech Stack:** Kotlin 2.4.21 on Android (minSdk 33, target 37), Room 2.8.5 with KSP 2.3.13, Android Keystore, kotlinx.coroutines 1.11.0 with `kotlinx-coroutines-test`, kotlinx.serialization 1.13.0, JUnit 4.13.2, AndroidX Test (runner 1.7.0, ext-junit 1.3.0), `androidx.room:room-testing`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. No new library.
- The owner's hard rule (AGENTS.md): no workarounds and no smallest-change fixes; clean code, good architecture and modularity, even when that means a larger change. The owner's criterion of 2026-11-24: "best = best code quality. Don't worry about time." A step that would patch a symptom at the nearest line is a fault of the plan: stop and ask the controller.
- The approved design is `docs\superpowers\specs\2026-11-24-conversations-design.md` (commit `75dd098b`, with the controller's later changes), called "the design" below; its decisions D1 to D24 bind this plan. This plan is the design's plan 7C (section 16): its sections 5.1 (what `:core:privacy` gets), 5.6 (the archive's lines of `AppGraph`), 9.1, 9.2, 9.3, the storage part of 9.7, R3 and the store's line of 15.2. The main spec is `docs\superpowers\specs\2026-11-12-kestrel-design.md`; plan 7.0 already rewrote its sections 13.4 and 13.5 for this archive.
- `:core:privacy` knows only encrypted payloads and their plain fields (design 5.1): no type of `:core:conversation` enters it, and `:core:conversation` never depends on `:core:privacy`. Only `:app` sees both.
- The archive's key is `kestrel.conversations`, an AES-256 key of Android Keystore that never leaves it and is never made in StrongBox. A payload is AES-GCM with the associated data `conversations/<id>` (design 9.1).
- Only these fields of a conversation are plain: its id, its open and last-change times, whether it is open, and its number of commands (design 9.1). At most one conversation is open (design 6.1).
- An unreadable conversation is reported to the activity log by its kind only (design 9.2): never a conversation's id, words or bytes. One whose payload fails its check is deleted by plan 7B's `ArchivedConversationStore`, which reports it; one in a format that this version cannot read is kept by this plan's `ConversationArchiveStore`, which reports it once per process. Nothing in this plan logs, prints or puts into a test's output a conversation's text; the device tests use made-up texts only.
- "Delete history" deletes every conversation row, the open one too, and then erases the key, so that no copy of the database file can be read afterwards; the next write makes a new key (design 9.7).
- Run Gradle only through `scripts\gradle.ps1`, one build at a time; when the controller's brief says that agents run in parallel, take `.superpowers\sdd\gradle.lock` in `H:\Code\kestrel` before each Gradle command as the brief says, and do not run `scripts\gradle.ps1 --stop` beside another Gradle user. Otherwise stop Gradle with `scripts\gradle.ps1 --stop` at the end of each task that builds. `scripts\gradle.ps1` refuses every task that installs, uninstalls or runs tests on a device. Install only through `scripts\install.ps1`, and run every device test only through `scripts\connected-test.ps1`, which keeps Kestrel and its data and checks the owner's data on kestrel-api37 itself.
- Device steps: Tasks 1, 2 and 4 run `:core:privacy`'s device tests on kestrel-api37; they install only the library's own test app, `dev.kestrel.privacy.test`, whose Keystore and database are not Kestrel's, and `scripts\connected-test.ps1` uninstalls it afterwards. Every `:app` device test and every install run only in Task 8, which is the controller's. On kestrel-api37 each device command starts with the emulator's check, written without the word throw: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }`. If the check fails, or no emulator runs, start it with `scripts\start-emulator.ps1`, check the serial that it prints, and use that serial. Never use the AVD `zoran`, the S23+ or the owner's Xiaomi tablet.
- kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile: no step uninstalls Kestrel there, clears its data, or signs an account in or out. Task 8's install checks before and after it that `dumpsys account` counts the same Google accounts and that `scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin` answers `profile=copilot`.
- The A71 (`R58N66RVD7Z`) is used once, in Task 8, for `:core:privacy`'s speed test only, within AGENTS.md's limits: it installs only `dev.kestrel.privacy.test`, which `scripts\connected-test.ps1` removes afterwards, and touches no account, no call, no message and no setting that the script does not put back.
- The JVM tests run on CI's Ubuntu too: no JVM test needs Windows, a device, a font or a network.
- The repository uses LF line endings.
- This plan is `.superpowers/sdd/plan-7c.md`; the controller commits it as `docs/superpowers/plans/2026-11-24-conversations-7c-archive.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

Code comments name this plan "plan 7C" and the design's sections as "design 9.2 of sub-project 7".

## The order of plans 7B and 7C

Plan 7C merges **after** plan 7B. Its Tasks 1 to 4 may run while plan 7B is carried out or reviewed; its Tasks 5 to 8 start only after plan 7B is merged.

- The port `RecordArchive` belongs to the code that uses it, `:core:conversation`'s store (design 5.2). Plan 7B defines it and tests `ArchivedConversationStore` against its fake, `MemoryArchive`; this plan implements it. That is the direction of every port in Kestrel: `:core:agent`'s ports are implemented in `:app`, after the port exists.
- Merging this plan first would need the port somewhere else: in `:core:privacy`, which may know no conversation type (design 5.1), or in a new module that holds one interface only so that a plan can merge early. Both bend the architecture to the schedule, which the owner's hard rule forbids.
- Tasks 1 to 4 neither use the port nor touch a file of plan 7B (they change only `core/privacy`), so they can run beside plan 7B, as design 16 allows ("7C can run beside 7B"). Tasks 5 and 6 are `:app`'s adapter and wiring, which need plan 7B's types; Task 7 writes the result into the design; Task 8 checks everything on the devices.

## Before you start

- Sub-project 7 starts only after plan 6B-basefix (`docs\superpowers\plans\2026-11-24-eval-6b-basefix.md`) is pushed. This plan was written against `sp3` at `a4558faf`, which holds plan 7B as committed at `9ddf3385` and changed at `a4558faf` (`docs\superpowers\plans\2026-11-24-conversations-7b-core.md`). Of the files that this plan changes, only `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` and the design may move before Task 5.
- At the start of Task 1, and again at the start of Task 5, run the plan checker against the branch's HEAD, in PowerShell 7: `$env:PLANX = 'H:\Code\kestrel-sp3\docs\superpowers\plans\2026-11-24-conversations-7c-archive.md'; $env:BASE_REF = (git -C H:\Code\kestrel-sp3 rev-parse HEAD); $env:S3A = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:OUTDIR = 'plan7c-sim'; python 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\plan3x-check.py'`. For Task 5, also set `$env:START_TASK = '5'`. Expected: an `operations:` line, a `files:` line, a `simulated files written:` line, and no line that starts with `PROBLEM:`. If a `PROBLEM:` line names a text that plan 6B-basefix, 7A or 7B moved or changed, stop and ask the controller: the controller rebases the plan (it changes the plan's old texts to the texts that the earlier plans left, keeps each change's meaning, and adjusts a new text that depends on them), runs the checker again until it is clean, and only then gives the task.
- At the start of Task 5, check that plan 7B as merged has the types of "Interfaces consumed from plan 7B" below, with these signatures: `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\store\*.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationClock.kt -Pattern 'interface RecordArchive|suspend fun read\(id: String\): ArchiveEntry\?|suspend fun write\(rows: List<ArchiveRow>\)|class Unknown\(|class ArchivedConversationStore\(|class Retention\(|suspend fun keepPurging\(days: Flow<Int>\)|interface ConversationClock'`. Expected: 8 matches. If one is missing or differs, stop and ask the controller. Also check that plan 7B's store reads `open` from the row (see "Plan 7B as committed"): `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\store\ArchivedConversationStore.kt -SimpleMatch -Pattern 'copy(open = entry.header.open)'` gives 1 match, and `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\model\ConversationSnapshot.kt -SimpleMatch -Pattern '@Transient val open'` gives 1 match. Otherwise stop and ask the controller, because Task 5's `theTablesOpenColumnDecidesWhichConversationIsOpen` tests it.
- Measure the counts before the first change of Task 1, and note them in the task's report: run `scripts\gradle.ps1 :core:privacy:testDebugUnitTest :app:testDebugUnitTest`, then `foreach ($dir in 'core\privacy\build\test-results\testDebugUnitTest', 'app\build\test-results\testDebugUnitTest') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `foreach ($dir in 'core\privacy\src\androidTest', 'app\src\androidTest') { "$dir $(@(Get-ChildItem $dir -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)" }`, then `scripts\gradle.ps1 --stop`. These are P₀ (`:core:privacy` JVM), A₀ (`:app` JVM, with `app/src/testDebug`), D₀ (`:core:privacy`'s device tests, 19 at `a4558faf`) and AD₀ (`:app`'s device tests, 443 at `a4558faf`). Each task gives its counts as "N + k", where N is the count before the task. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file as the earlier tasks left it; and run `git status --short`. The worktree may hold other agents' uncommitted files. If a text is missing, or a file that the task changes shows changes that are not the task's own, stop and ask the controller.
- A device that runs a build with Task 1 has its database at version 3. Room cannot open it with an older build (there is no migration down), and a destructive fallback would delete the owner's profile. So from Task 8 on, never install a build without this plan on kestrel-api37, and never install one on the A71 after a later plan has brought this plan there. Task 8, Step 2 writes this rule into AGENTS.md, so that work from the `main` worktree, which shares kestrel-api37, keeps it too.

## Interfaces consumed from plan 7B

Tasks 5 and 6 use these types of `:core:conversation`, as plan 7B (committed at `9ddf3385`, changed at `a4558faf`) defines them in its "Interfaces for 7C and 7D":

- package `dev.kestrel.conversation.store`:
  - `interface RecordArchive { suspend fun headers(): List<ArchiveHeader>; suspend fun read(id: String): ArchiveEntry?; suspend fun write(rows: List<ArchiveRow>); suspend fun delete(id: String); suspend fun deleteAll(); suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int }`: `headers()` lists every row, the newest `lastMillis` first; `read(id)` gives null when there is no row; `write(rows)` inserts or replaces all of them in one transaction; `deleteAll()` deletes every row, the open one too, and the key `kestrel.conversations`; `deleteOutside` deletes only closed rows whose last change is before `oldestMillis` or after `newestMillis`.
  - `data class ArchiveHeader(val id: String, val openedMillis: Long, val lastMillis: Long, val open: Boolean, val commands: Int)`; `class ArchiveRow(val header: ArchiveHeader, val payload: ByteArray)` (the payload is the snapshot's JSON in UTF-8).
  - `sealed interface ArchiveEntry { val header: ArchiveHeader }` with `class Readable(header, val payload: ByteArray)`, `class Unreadable(header)` (the payload failed its check: the store deletes the row and reports it to its `unreadable` by `KIND`) and `class Unknown(header)` (the payload is in a format that this version cannot read: the store leaves the conversation out and keeps the row; the archive has reported it).
  - `class ArchivedConversationStore(archive: RecordArchive, unreadable: (kind: String) -> Unit) : ConversationStore`, with `ArchivedConversationStore.KIND = "conversation"` and `DAY_MILLIS`; it decodes each row once per process, and takes whether a conversation is open from the row's header, the table's `open` column: `ConversationSnapshot.open` is `@Transient`, so the snapshot's JSON never holds it.
  - `class Retention(store: ConversationStore, clock: ConversationClock, report: (String, Throwable) -> Unit)` with `suspend fun purge(days: Int): Int` and `suspend fun keepPurging(days: Flow<Int>)`, which purges at once and at each change of `days`, and checks the wall clock every hour and purges once a day has passed or the clock went back; a failure goes to `report` and the job goes on.
- package `dev.kestrel.conversation.engine`: `interface ConversationClock { fun wallMillis(): Long; fun zone(): ZoneId }`.
- package `dev.kestrel.conversation.model`: `@Serializable data class ConversationSettings(val quietSeconds: Int = 60, val followUpSeconds: Int = 8, val speakTypedReplies: Boolean = false, val retentionDays: Int = 30, val limits: GoalLimits = GoalLimits())`, whose read properties `keptDays` (the retention within `RETENTION_CHOICES`, 1 to 365 days), `quietMillis`, `followUpMillis` and `roundLimits` are what Kestrel reads, never the raw fields: a damaged stored value is read within its bounds.
- Plan 7B adds no dependency of `:app` on `:core:conversation`, no setting key and no `AndroidConversationClock`; Tasks 5 and 6 add them.

## Plan 7B as committed

Plan 7B as committed at `9ddf3385` has what this plan needs from it: `ArchiveEntry.Unknown`, which its store leaves out without deleting the row; the KDoc lines that name plan 7C as the plan that runs `Retention` and makes `AndroidConversationClock`; and `Retention`'s hourly check of the wall clock.

Plan 7B's commit `a4558faf` follows this plan's review (its finding I4): the table's `open` column is the one truth about whether a conversation is open. `ConversationSnapshot.open` is `@Transient`, so the snapshot's JSON never holds it; `ArchivedConversationStore.decoded` takes it from the entry's header (`copy(open = entry.header.open)`); and plan 7B's `ArchivedConversationStoreTest.aConversationIsOpenOnlyWhenItsRowSaysSo` checks a row that is closed in its header while an older payload still says open. A later difference from plan 7B goes into `progress.md`.

## Not in this plan

- The conversation's JSON, its `version`, the search's index, `HistorySearch` and the deletion of a conversation that fails its check or does not decode (plan 7B): this archive stores bytes and never parses them.
- The owner's "delete history" on the screen and by voice (plan 7D, design 16's row "\"delete history\" extended"): the question "Delete the activity log, the network log and past conversations?", the close of the open conversation, the refusal while a goal is open, and the routing of the settings button and of "Kestrel, delete history" through plan 7B's `LocalRequests`, which calls `ArchivedConversationStore.deleteAll()` (which this plan's archive turns into the deletion of every row and the erasure of the key) and `LogStore.deleteHistory()`. Until plan 7D, nothing writes a conversation, so today's "delete history" leaves no conversation behind.
- The Conversations settings page and its "Delete all past conversations" (plan 7F). This plan adds the setting's key only, for the purge job.
- The first search over 30 days on the A71 from end to end (design 4.3 and 15.2): this plan measures the archive's share of it, the decryption through the Keystore, which is design R3's risk (Task 4 and Task 8). The whole search, with plan 7B's decoding and index, belongs to the A71 checks of design 15.2, which the controller places (see "Left open").

## Changes to the design that this plan makes

The controller accepted these on 2026-11-24; Task 7 writes them into the design's sections 9.1 and 9.2.

- **The table decides which conversation is open.** The design's `ConversationDao` is an interface with `@Upsert put`, `openOne`, `closedSince` and `closeAll()`. Here it is an abstract class whose `write(rows)` stores all the rows in one transaction and, for each open row, closes every other open row first. The table's `open` column is the one truth about whether a conversation is open: plan 7B's store takes `open` from a row's header, and the snapshot's JSON does not hold it (see "Plan 7B as committed"), and `deleteOutside` reads the same column. So "at most one conversation is open" (design 6.1) holds for everything that reads the archive, whatever its callers write, also for a row that plan 7B's store cannot read and so does not know. Its plain `@Upsert` and `UPDATE` queries are protected. `openOne`, `closedSince` and `closeAll` are not added: plan 7B's store reads every row once through `headers()` and `get`, and closes a conversation by writing its snapshot as closed.
- **What `ConversationArchiveStore` offers.** The design lists "encrypt, decrypt, put, headers, get, delete, purge". Here it offers exactly what plan 7B's port needs: `headers()`, `read(id)`, `write(conversations)`, `delete(id)`, `deleteAll()` and `deleteOutside(oldestMillis, newestMillis)`. Encryption and decryption stay inside, so no caller handles a ciphertext. The plain fields travel as the table's own `ConversationHeader`.
- **Two kinds of unreadable conversation.** `read` tells a payload that fails its check (`FailedCheck`, which plan 7B's store deletes and reports) from one in a format that this version cannot read (`UnknownFormat`, which is kept for a later version and reported once per process by the archive, as `SettingsStore` and `CredentialStore` do). The activity log's texts are "A conversation could not be read, so Kestrel deleted it" (from plan 7B's `KIND`) and "A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it".
- **The key is erasable and never in StrongBox.** `ErasableKey` (`guard`, `get()`, `erase()`) is the key's own type. `KeystoreErasableKey.of(alias)` gives one key object per alias and process, by construction: it keeps the Keystore key after its first use, as `PrivacyStore`'s `by lazy` keys do, until `erase()` deletes it from Keystore, so that no part of the process, not even a second `PrivacyStore`, keeps a key that another erased. `KeystoreKeys` gains `delete(alias)`. StrongBox's slower operations would make the first read of hundreds of conversations slow (R3), and the data key is not in StrongBox either.
- **One lock for the key.** Every function of `ConversationArchiveStore` runs under the key's own lock, `ErasableKey.guard`, which is process-wide with the key, so that "delete history" can never erase the key between the encryption of a write and its row, which would leave a row that no key opens, also not from a second `PrivacyStore` of the same process.
- **The purge job runs for the life of the process.** `KestrelApplication` starts plan 7B's `Retention` in `AppGraph.scope`. Unlike the logs' purge, which the accessibility service keeps, it runs with the service on or off, as design 9.3 says ("while the process runs").
- **The archive's speed is measured in `:core:privacy`.** The device test `ConversationArchiveSpeedTest` writes 300 closed conversations of 12 KB to a database file and reads them as plan 7B's store does at its first use (`headers()`, then `read` for each), through a new store, as after a start of Kestrel. It runs only with the instrumentation argument `kestrelArchiveSpeed=true`, so that the routine runs stay quick. It is a measurement: it fails only when a conversation does not read back, and it logs the time under the tag `KestrelArchive`. One rule decides, in Task 8: above 500 ms on the A71, the archive's share of design 4.3's 1 s, R3's fallback goes to the owner; the emulator's time is only noted. It needs no install of Kestrel, so it can run on the A71.

## File structure

| File | Responsibility |
|---|---|
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt` (new), `db/KestrelDatabase.kt`, `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json` (new, generated); tests `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/` `KestrelDatabaseTest.kt`, `KestrelDatabaseMigrationTest.kt` (new), `CredentialStoreTest.kt` | Task 1: the table `conversations` in version 3 of the database |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt`, `crypto/ErasableKey.kt` (new); test `KeystoreCipherTest.kt` | Task 2: the key `kestrel.conversations`, which "delete history" erases |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt` (new); test `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt` (new) | Task 3: the archive that knows only bytes |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt`; tests `PrivacyStoreTest.kt`, `ConversationArchiveSpeedTest.kt` (new) | Task 4: `PrivacyStore.conversations`, its report of a kept conversation, and the first read's speed |
| `app/build.gradle.kts`, `app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt` (new); test `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt` (new) | Task 5: plan 7B's port on the archive |
| `app/src/main/kotlin/dev/kestrel/app/conversation/AndroidConversationClock.kt` (new), `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`; test `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt` | Task 6: the store, its report, the clock and the purge job in the app graph |
| `docs/superpowers/specs/2026-11-24-conversations-design.md` | Task 7: design 9.1 and 9.2 as built |
| `AGENTS.md` (Step 2; the other steps are device steps) | Task 8: the rule that only builds with plan 7C go onto kestrel-api37, and the checks on kestrel-api37 and the A71 |

---

### Task 1: The table `conversations` in version 3 of the database

Kestrel's database gets the table of design 9.2, one row per conversation, with Room's automatic migration from version 2. A write of several rows is one transaction, and it keeps at most one conversation open. The migration tests move into a class of their own.

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt`
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt`
- Create (generated by Room in Step 4): `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json`
- Create: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseMigrationTest.kt`
- Modify: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt`, `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/CredentialStoreTest.kt`

**Interfaces:**
- Consumes: `KestrelDatabase` at version 2 with `AutoMigration(from = 1, to = 2)`; `MigrationTestHelper(instrumentation, KestrelDatabase::class.java)`.
- Produces: package `dev.kestrel.privacy.db`: `class ConversationRow(id: String, openedMillis: Long, lastMillis: Long, open: Boolean, commands: Int, payload: ByteArray)` (table `conversations`, columns `id`, `opened_millis`, `last_millis`, `open`, `commands`, `payload`, index on `last_millis`); `data class ConversationHeader(val id: String, val openedMillis: Long, val lastMillis: Long, val open: Boolean, val commands: Int)`; `abstract class ConversationDao` with `open suspend fun write(rows: List<ConversationRow>)` (one transaction; each open row closes every other open row first), `suspend fun headers(): List<ConversationHeader>` (newest `last_millis` first), `suspend fun get(id: String): ConversationRow?`, `suspend fun delete(id: String): Int`, `suspend fun deleteOutside(oldest: Long, newest: Long): Int` (closed rows only), `suspend fun deleteAll(): Int`, and the protected `upsert(row)` and `closeOthers(id): Int`; `KestrelDatabase.conversationDao(): ConversationDao` at version 3 with `AutoMigration(from = 2, to = 3)`. Task 3 uses the DAO; Task 3's fake DAO overrides its abstract functions and keeps its `write`.

Counts: `:core:privacy` JVM unchanged (P₀). `:core:privacy` device tests D₀ + 4 (`KestrelDatabaseTest` + 3, `KestrelDatabaseMigrationTest` 2, `CredentialStoreTest` − 1).

- [ ] **Step 1: Write the failing device tests**

Create `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseMigrationTest.kt`:

```kotlin
package dev.kestrel.privacy

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.KestrelDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database's migrations from each earlier version (design spec, section 13.4; design 9.2 of sub-project 7). Each test
 * uses a test-named database file of this library's own test app, so Kestrel's database is never touched.
 */
@RunWith(AndroidJUnit4::class)
class KestrelDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migrations = MigrationTestHelper(instrumentation, KestrelDatabase::class.java)

    @After
    fun deleteTheTestDatabase() {
        instrumentation.targetContext.deleteDatabase(MIGRATED)
    }

    @Test
    fun version1MigratesToVersion2WithItsSettingsAndAnEmptyCredentialsTable() {
        migrations.createDatabase(MIGRATED, 1).apply {
            execSQL("INSERT INTO settings (name, payload) VALUES ('test-volume', X'07')")
            close()
        }

        val migrated = migrations.runMigrationsAndValidate(MIGRATED, 2, true)

        assertArrayEquals(byteArrayOf(7), migrated.blob("SELECT payload FROM settings WHERE name = 'test-volume'"))
        assertEquals(0, migrated.count("credentials"))
    }

    @Test
    fun version2MigratesToVersion3WithItsRowsAndAnEmptyConversationsTable() {
        migrations.createDatabase(MIGRATED, 2).apply {
            execSQL("INSERT INTO settings (name, payload) VALUES ('test-volume', X'07')")
            execSQL("INSERT INTO credentials (id, payload) VALUES ('test-credential', X'08')")
            execSQL("INSERT INTO log_entries (log, timestamp_millis, payload) VALUES ('activity', 5, X'09')")
            close()
        }

        // Validation compares the migrated tables, their columns and the index on last_millis with the schema 3.json.
        val migrated = migrations.runMigrationsAndValidate(MIGRATED, 3, true)

        assertArrayEquals(byteArrayOf(7), migrated.blob("SELECT payload FROM settings WHERE name = 'test-volume'"))
        assertArrayEquals(byteArrayOf(8), migrated.blob("SELECT payload FROM credentials WHERE id = 'test-credential'"))
        assertArrayEquals(byteArrayOf(9), migrated.blob("SELECT payload FROM log_entries WHERE log = 'activity'"))
        assertEquals(0, migrated.count("conversations"))
    }

    private fun SupportSQLiteDatabase.blob(sql: String): ByteArray =
        query(sql).use { rows ->
            rows.moveToFirst()
            rows.getBlob(0)
        }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { rows ->
            rows.moveToFirst()
            rows.getInt(0)
        }

    private companion object {
        const val MIGRATED = "test-migration.db"
    }
}
```

Replace the whole `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/CredentialStoreTest.kt` with:

```kotlin
package dev.kestrel.privacy

import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKeyFactory

/**
 * The credential store with the device's Keystore (design spec, section 10.7). Every test uses its own key alias and a
 * database in memory, so the owner's credentials are never touched. KestrelDatabaseMigrationTest checks the migrations.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "kestrel.test.credentials.${System.nanoTime()}"
    private val database = Room.inMemoryDatabaseBuilder(context, KestrelDatabase::class.java).build()
    private val unreadable = mutableListOf<String>()
    private val store = CredentialStore(database.credentialsDao(), AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }, onUnreadable = { unreadable += it })

    @After
    fun cleanUp() {
        database.close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aCredentialReadsBackUnderItsIdUntilItIsDeleted() = runBlocking {
        store.put("test-a", "first".encodeToByteArray())
        store.put("test-a", "second".encodeToByteArray())
        store.put("test-b", "other".encodeToByteArray())

        assertArrayEquals("second".encodeToByteArray(), store.get("test-a"))
        assertEquals(listOf("test-a", "test-b"), store.ids())
        store.delete("test-a")
        assertNull(store.get("test-a"))
        assertEquals(listOf("test-b"), store.ids())
    }

    @Test
    fun aPayloadMovedToAnotherIdIsDeletedAndReported() = runBlocking {
        store.put("test-a", "key".encodeToByteArray())
        val sealed = database.credentialsDao().get("test-a")!!.payload
        database.credentialsDao().put(CredentialRow("test-moved", sealed))

        assertNull(store.get("test-moved"))
        assertEquals(listOf("test-moved"), unreadable)
        assertEquals(listOf("test-a"), store.ids())
    }

    @Test
    fun theCredentialsKeyTakesTheDevicesStrongBoxPath() {
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        val key = KeystoreKeys.getOrCreate(alias, strongBox)

        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (strongBox) {
            assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        } else {
            assertNotEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        }
        assertEquals(256, info.keySize)
    }
}
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt`, replace:

```kotlin
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import dev.kestrel.privacy.db.SettingRow
```

with:

```kotlin
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import dev.kestrel.privacy.db.SettingRow
```

Add these tests to `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt`, before its last closing brace:

```kotlin
    // Design 6.1 of sub-project 7: at most one conversation is open, whatever the caller writes.
    @Test
    fun writingAnOpenConversationClosesTheOpenOne() = runBlocking {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = true)))
        dao.write(listOf(conversation("b", last = 2, open = true)))

        assertEquals(listOf("b" to true, "a" to false), dao.headers().map { it.id to it.open })
    }

    // Design 9.6 of sub-project 7: a switch writes the closed and the opened conversation in one transaction.
    @Test
    fun oneWriteStoresEveryRowAndLeavesOnlyItsOpenRowOpen() = runBlocking {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = false), conversation("b", last = 2, open = true)))

        dao.write(listOf(conversation("b", last = 3, open = false), conversation("a", last = 3, open = true)))

        assertEquals(setOf("a" to true, "b" to false), dao.headers().map { it.id to it.open }.toSet())
        assertEquals(3L, dao.get("b")!!.lastMillis)
    }

    // Design 9.3 of sub-project 7: the open conversation is never purged, however old it is.
    @Test
    fun deleteOutsideKeepsTheOpenConversationAndBothLimits() = runBlocking {
        val dao = db.conversationDao()
        dao.write(
            listOf(
                conversation("before", last = 99, open = false),
                conversation("first", last = 100, open = false),
                conversation("last", last = 200, open = false),
                conversation("after", last = 201, open = false),
                conversation("open", last = 1, open = true),
            ),
        )

        assertEquals(2, dao.deleteOutside(oldest = 100, newest = 200))
        assertEquals(listOf("last", "first", "open"), dao.headers().map { it.id })
        assertEquals(ConversationHeader("last", openedMillis = 199, lastMillis = 200, open = false, commands = 2), dao.headers().first())
    }

    private fun conversation(id: String, last: Long, open: Boolean) =
        ConversationRow(id, openedMillis = last - 1, lastMillis = last, open = open, commands = 2, payload = byteArrayOf(1))
```

- [ ] **Step 2: Compile the device tests to see them fail**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports `Unresolved reference 'ConversationHeader'`, `Unresolved reference 'ConversationRow'` and `Unresolved reference 'conversationDao'`.

- [ ] **Step 3: Add the table and version 3**

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt`:

```kotlin
package dev.kestrel.privacy.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * One past or open conversation (design 9.1 and 9.2 of sub-project 7). Only the payload is sensitive, and it is encrypted
 * under the key kestrel.conversations and bound to the id. The other columns are plain, for sorting and retention.
 */
@Entity(tableName = "conversations", indices = [Index(value = ["last_millis"])])
class ConversationRow(
    /** The conversation's random 128-bit id, as text. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "opened_millis") val openedMillis: Long,
    /** The wall-clock time of the conversation's last change, which retention counts from. */
    @ColumnInfo(name = "last_millis") val lastMillis: Long,
    val open: Boolean,
    /** How many commands the conversation holds. */
    val commands: Int,
    val payload: ByteArray,
)

/**
 * The plain columns of a conversation's row, without its payload. Its [open] is the one truth about whether the
 * conversation is open (design 6.1 of sub-project 7): the conversation's store reads it from here; the payload does not hold it.
 */
data class ConversationHeader(
    val id: String,
    @ColumnInfo(name = "opened_millis") val openedMillis: Long,
    @ColumnInfo(name = "last_millis") val lastMillis: Long,
    val open: Boolean,
    val commands: Int,
)

/**
 * The table of conversations. It keeps at most one conversation open (design 6.1 of sub-project 7): [write] closes the
 * other open rows in the same transaction as it stores an open one, and nothing else opens a row.
 */
@Dao
abstract class ConversationDao {
    /**
     * Inserts or replaces [rows], all in one transaction, so that a switch to another conversation (design 9.6 of
     * sub-project 7) is never stored halfway. Before an open row is stored, every other open row closes.
     */
    @Transaction
    open suspend fun write(rows: List<ConversationRow>) {
        for (row in rows) {
            if (row.open) closeOthers(row.id)
            upsert(row)
        }
    }

    /** Every conversation's plain columns, the newest last change first. */
    @Query("SELECT id, opened_millis, last_millis, open, commands FROM conversations ORDER BY last_millis DESC")
    abstract suspend fun headers(): List<ConversationHeader>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun get(id: String): ConversationRow?

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun delete(id: String): Int

    /** Deletes the closed conversations whose last change is before [oldest] or after [newest]; the open one stays. */
    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    abstract suspend fun deleteOutside(oldest: Long, newest: Long): Int

    @Query("DELETE FROM conversations")
    abstract suspend fun deleteAll(): Int

    @Upsert
    protected abstract suspend fun upsert(row: ConversationRow)

    @Query("UPDATE conversations SET open = 0 WHERE open = 1 AND id != :id")
    protected abstract suspend fun closeOthers(id: String): Int
}
```

Replace the whole `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt` with:

```kotlin
package dev.kestrel.privacy.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 adds the table `credentials` (sub-project 4), and version 3 the table `conversations` (plan 7C of sub-project
 * 7). Room migrates each earlier version by itself.
 */
@Database(
    entities = [LogEntryRow::class, SettingRow::class, CredentialRow::class, ConversationRow::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun settingsDao(): SettingsDao

    abstract fun credentialsDao(): CredentialsDao

    abstract fun conversationDao(): ConversationDao

    companion object {
        const val FILE_NAME = "kestrel.db"

        @Volatile
        private var instance: KestrelDatabase? = null

        /**
         * The database of this process. Room tells observers only about writes made through the same
         * instance, so a second instance would hide changes from them.
         */
        fun get(context: Context): KestrelDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, KestrelDatabase::class.java, FILE_NAME)
                    .build()
                    .also { instance = it }
            }
    }
}
```

- [ ] **Step 4: Build, and check the schema that Room writes**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin :core:privacy:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, P₀ tests. Room writes `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json`.

Run: `git status --short core/privacy/schemas; $schema = Get-Content core\privacy\schemas\dev.kestrel.privacy.db.KestrelDatabase\3.json -Raw | ConvertFrom-Json; "version $($schema.database.version)"; $table = $schema.database.entities | Where-Object tableName -eq 'conversations'; "columns $(($table.fields.columnName) -join ',')"; "index $(($table.indices.name) -join ',')"`
Expected: `?? core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json` and no change to `1.json` or `2.json`; `version 3`; `columns id,opened_millis,last_millis,open,commands,payload`; `index index_conversations_last_millis`.

- [ ] **Step 5: Run the device tests to see them pass**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.KestrelDatabaseTest,dev.kestrel.privacy.KestrelDatabaseMigrationTest,dev.kestrel.privacy.CredentialStoreTest'; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL, 12 tests (7 + 2 + 3), none failed; the script ends with exit code 0 and reports no change of Kestrel's install or of the owner's data.

- [ ] **Step 6: Commit**

```powershell
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseMigrationTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/CredentialStoreTest.kt
git commit -m "feat(privacy): version 3 of Kestrel's database holds the table of conversations, whose writes are one transaction and keep one conversation open (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The key `kestrel.conversations`, which "delete history" erases

The archive's key is a key of its own (design 9.1), which "delete history" erases with the rows (crypto-shredding, design 9.7): once it is gone, no copy of the database file can be read. The next use makes a new key.

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/ErasableKey.kt`
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt`
- Test: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KeystoreCipherTest.kt`

**Interfaces:**
- Consumes: `KeystoreKeys.getOrCreate(alias: String, strongBox: Boolean = false): SecretKey`; `AesGcmFieldCipher(key: () -> SecretKey)`.
- Produces: `KeystoreKeys.CONVERSATIONS_KEY = "kestrel.conversations"`; `KeystoreKeys.delete(alias: String)`; package `dev.kestrel.privacy.crypto`: `interface ErasableKey { val guard: Mutex; fun get(): SecretKey; fun erase() }` and `class KeystoreErasableKey private constructor(alias: String) : ErasableKey` with `KeystoreErasableKey.of(alias: String): KeystoreErasableKey`, one instance per alias and process. Task 3 takes an `ErasableKey` and runs under its `guard`; Task 4 builds `KeystoreErasableKey.of(KeystoreKeys.CONVERSATIONS_KEY)`; the device tests of Tasks 4 and 5 clean up with `KeystoreErasableKey.of(alias).erase()`.

Counts: `:core:privacy` JVM unchanged. `:core:privacy` device tests N + 1 (`KeystoreCipherTest` + 1).

- [ ] **Step 1: Write the failing device test**

Replace the whole `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KeystoreCipherTest.kt` with:

```kotlin
package dev.kestrel.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.crypto.KeystoreKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.AEADBadTagException

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

    @Test
    fun valueTooShortToHoldTheTagIsRejectedAsAnUnknownFormat() {
        val cipher = AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }
        val truncated = cipher.encrypt(ByteArray(0), place).copyOf(1 + 12 + 15)

        // Without the length check, Keystore throws IllegalBlockSizeException, which the stores don't catch.
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(truncated, place) }
    }

    // Crypto-shredding (design 9.7 of sub-project 7): what the erased key encrypted fails its check under the new key.
    @Test
    fun anErasedKeyIsGoneFromKeystoreAndItsNextUseMakesANewOne() {
        val key = KeystoreErasableKey.of(alias)
        // One key object per alias and process, so that no part of Kestrel keeps a key that another part erased.
        assertSame(key, KeystoreErasableKey.of(alias))
        val sealed = AesGcmFieldCipher { key.get() }.encrypt("hello".encodeToByteArray(), place)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        key.erase()
        assertFalse(keyStore.containsAlias(alias))
        // Erasing a key that is already gone does nothing.
        key.erase()

        assertThrows(AEADBadTagException::class.java) { AesGcmFieldCipher { key.get() }.decrypt(sealed, place) }
        assertTrue(keyStore.containsAlias(alias))
    }
}
```

- [ ] **Step 2: Compile the device test to see it fail**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports `Unresolved reference 'KeystoreErasableKey'`.

- [ ] **Step 3: Add the erasable key**

Replace the whole `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt` with:

```kotlin
package dev.kestrel.privacy.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-256 keys that never leave Android Keystore (design spec, section 13.4). */
object KeystoreKeys {
    /** Encrypts logs and settings. */
    const val DATA_KEY = "kestrel.data"

    /** Encrypts the providers' credentials (design spec, section 10.7). */
    const val CREDENTIALS_KEY = "kestrel.credentials"

    /** Encrypts the past conversations; "delete history" erases it (design 9.1 and 9.7 of sub-project 7). */
    const val CONVERSATIONS_KEY = "kestrel.conversations"

    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Returns the key [alias] and makes it when it does not exist yet. With [strongBox], a new key is made in StrongBox, or
     * without it when Keystore reports that StrongBox is not available.
     */
    @Synchronized
    fun getOrCreate(alias: String, strongBox: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
        if (strongBox) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // The device announced StrongBox but cannot make this key there; it is made without StrongBox below.
            }
        }
        return generate(alias, strongBox = false)
    }

    /**
     * Deletes the key [alias] when it exists, so that nothing that it encrypted can be read again (crypto-shredding, design
     * 9.7 of sub-project 7). The next [getOrCreate] makes a new key.
     */
    @Synchronized
    fun delete(alias: String) {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build(),
        )
        return generator.generateKey()
    }
}
```

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/ErasableKey.kt`:

```kotlin
package dev.kestrel.privacy.crypto

import kotlinx.coroutines.sync.Mutex
import javax.crypto.SecretKey

/**
 * A key that can be erased (crypto-shredding): once it is erased, nothing that it encrypted can be read again, also not
 * from a copy of the database file. Its next use makes a new key (design 9.7 of sub-project 7).
 */
interface ErasableKey {
    /**
     * The lock that every store of data under this key and the key's erasure take, so that nothing is stored under a key
     * that is being erased. It belongs to the key, so every user of the key shares it.
     */
    val guard: Mutex

    /** The key; a new one when there is none. */
    fun get(): SecretKey

    /** Erases the key. Erasing a key that is already erased does nothing. */
    fun erase()
}

/**
 * An [ErasableKey] in Android Keystore (design 9.1 of sub-project 7). [of] gives one instance per alias and process, so
 * that every user of the alias shares its [guard] and none keeps a key that another one erased. It keeps the key after
 * its first use, as PrivacyStore's other keys are kept, until [erase] deletes it from Keystore. It is never made in
 * StrongBox, whose slower operations would make the first read of hundreds of conversations slow, as the data key is not.
 */
class KeystoreErasableKey private constructor(private val alias: String) : ErasableKey {
    override val guard = Mutex()

    private var key: SecretKey? = null

    @Synchronized
    override fun get(): SecretKey = key ?: KeystoreKeys.getOrCreate(alias).also { key = it }

    @Synchronized
    override fun erase() {
        key = null
        KeystoreKeys.delete(alias)
    }

    companion object {
        private val keys = HashMap<String, KeystoreErasableKey>()

        /** The key object of [alias] in this process. */
        @Synchronized
        fun of(alias: String): KeystoreErasableKey = keys.getOrPut(alias) { KeystoreErasableKey(alias) }
    }
}
```

- [ ] **Step 4: Run the device test to see it pass**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin :core:privacy:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, P₀ tests.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.KeystoreCipherTest'; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL, 3 tests, none failed; the script ends with exit code 0.

- [ ] **Step 5: Commit**

```powershell
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/ErasableKey.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KeystoreCipherTest.kt
git commit -m "feat(privacy): the key kestrel.conversations is erasable, so that nothing it encrypted can be read once it is gone (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: `ConversationArchiveStore`, the archive that knows only bytes

The store of design 9.2: it encrypts each payload under the erasable key, bound to its conversation's id, and writes several conversations in one transaction; it reads a payload back, or says whether it failed its check or is in a format that this version cannot read; it keeps the latter and reports it by its kind only; and it erases the key with the rows. It never sees what a payload holds, and it never deletes a row on its own: plan 7B's store decides that.

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt`

**Interfaces:**
- Consumes: Task 1's `ConversationDao`, `ConversationRow` and `ConversationHeader`; Task 2's `ErasableKey` with its `guard`; `AesGcmFieldCipher`, whose `decrypt` throws `AEADBadTagException` for a payload that fails its check and `IllegalArgumentException` for an unknown format.
- Produces: package `dev.kestrel.privacy.conversations`: `class PlainConversation(val header: ConversationHeader, val payload: ByteArray)`; `sealed interface StoredConversation { val header: ConversationHeader }` with `class Readable(header, val payload: ByteArray)`, `class FailedCheck(header)` and `class UnknownFormat(header)`; `class ConversationArchiveStore(dao: ConversationDao, key: ErasableKey, onUnknownFormat: suspend () -> Unit = {}, dispatcher: CoroutineDispatcher = Dispatchers.IO)` with `suspend fun headers(): List<ConversationHeader>`, `suspend fun read(id: String): StoredConversation?`, `suspend fun write(conversations: List<PlainConversation>)`, `suspend fun delete(id: String)`, `suspend fun deleteAll()` and `suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int`. Task 4 builds it in `PrivacyStore`; Task 5 wraps it in `RoomRecordArchive`.

Counts: `:core:privacy` JVM N + 9 (P₀ + 9). Device tests unchanged.

- [ ] **Step 1: Write the failing tests**

Create `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt`:

```kotlin
package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationArchiveStoreTest {
    private val dao = FakeConversationDao()
    private val key = MemoryKey()
    private var unknownFormats = 0
    private val store = ConversationArchiveStore(dao, key, onUnknownFormat = { unknownFormats++ }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun aConversationReadsBackWithItsPlainFieldsUntilItIsDeleted() = runTest {
        store.write(listOf(PlainConversation(opened("a", last = 20), "first".encodeToByteArray())))
        val changed = opened("a", last = 30).copy(commands = 3)
        store.write(listOf(PlainConversation(changed, "second".encodeToByteArray())))

        val stored = store.read("a") as StoredConversation.Readable
        assertEquals(changed, stored.header)
        assertArrayEquals("second".encodeToByteArray(), stored.payload)
        assertEquals(listOf(changed), store.headers())

        store.delete("a")
        assertNull(store.read("a"))
        assertTrue(store.headers().isEmpty())
    }

    @Test
    fun storesThePayloadEncrypted() = runTest {
        store.write(listOf(PlainConversation(closed("a", last = 1), "a secret request".encodeToByteArray())))

        assertFalse(dao.rows.getValue("a").payload.decodeToString(throwOnInvalidSequence = false).contains("secret"))
    }

    // Design 6.1 and 9.6 of sub-project 7: a switch closes one conversation and opens another in the same write.
    @Test
    fun oneWriteSwitchesTheOpenConversation() = runTest {
        store.write(listOf(PlainConversation(opened("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2))))
        val before = dao.transactions

        store.write(listOf(PlainConversation(closed("a", last = 3), byteArrayOf(1)), PlainConversation(opened("b", last = 3), byteArrayOf(2))))

        assertEquals(setOf("a" to false, "b" to true), store.headers().map { it.id to it.open }.toSet())
        assertEquals(before + 1, dao.transactions)
    }

    @Test
    fun anOpenConversationClosesAnOpenRowThatTheCallerDoesNotKnow() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = true, commands = 1, payload = laterFormat())

        store.write(listOf(PlainConversation(opened("a", last = 3), byteArrayOf(1))))

        assertEquals(listOf("a" to true, "later" to false), store.headers().map { it.id to it.open })
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes a payload that fails its check and reports it by its kind.
    @Test
    fun aPayloadMovedToAnotherConversationFailsItsCheckAndStaysForTheCallerToDelete() = runTest {
        store.write(listOf(PlainConversation(closed("a", last = 1), "words".encodeToByteArray())))
        val row = dao.rows.getValue("a")
        dao.rows["moved"] = ConversationRow("moved", row.openedMillis, row.lastMillis, open = false, commands = row.commands, payload = row.payload)

        val read = store.read("moved")

        assertTrue(read is StoredConversation.FailedCheck)
        assertEquals(closed("moved", last = 1), read!!.header)
        assertTrue("moved" in dao.rows)
        assertEquals(0, unknownFormats)
    }

    @Test
    fun aConversationInAnUnknownFormatIsKeptAndReportedOncePerProcess() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())

        assertTrue(store.read("later") is StoredConversation.UnknownFormat)
        assertTrue(store.read("later") is StoredConversation.UnknownFormat)

        assertTrue("later" in dao.rows)
        assertEquals(1, unknownFormats)
    }

    // Design 9.7 of sub-project 7: after "delete history", not even a copy of the database file can be read.
    @Test
    fun deleteAllErasesTheRowsAndTheKeySoThatACopyOfARowCannotBeRead() = runTest {
        store.write(listOf(PlainConversation(closed("a", last = 1), "words".encodeToByteArray())))
        val copy = dao.rows.getValue("a")

        store.deleteAll()

        assertTrue(dao.rows.isEmpty())
        assertEquals(1, key.erased)
        dao.rows["a"] = copy
        assertTrue(store.read("a") is StoredConversation.FailedCheck)
    }

    // Without the key's guard, the key could be erased between a write's encryption and its row, which no key would then open.
    @Test
    fun deleteAllWaitsForAWriteThatHasStartedSoThatNoRowOutlivesTheKey() = runTest {
        val gate = CompletableDeferred<Unit>()
        dao.beforeUpsert = { gate.await() }
        val write = launch { store.write(listOf(PlainConversation(closed("a", last = 1), byteArrayOf(1)))) }
        runCurrent()
        val delete = launch { store.deleteAll() }
        runCurrent()
        assertEquals(0, key.erased)

        gate.complete(Unit)
        write.join()
        delete.join()

        assertEquals(1, key.erased)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun runsTheCipherOnTheStoresDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val watched = object : ErasableKey {
            override val guard = key.guard

            override fun get(): SecretKey = key.get().also { threads += Thread.currentThread() }

            override fun erase() = key.erase()
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, watched, dispatcher = dispatcher)
            store.write(listOf(PlainConversation(closed("a", last = 1), byteArrayOf(1))))
            store.read("a")

            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    private fun opened(id: String, last: Long) = ConversationHeader(id, openedMillis = last - 1, lastMillis = last, open = true, commands = 1)

    private fun closed(id: String, last: Long) = opened(id, last).copy(open = false)

    /** A later version's ciphertext: its first byte names a format that this version does not know. */
    private fun laterFormat() = byteArrayOf(2) + ByteArray(40)
}

/** An [ErasableKey] in memory; [erase] makes a new key, as Keystore does at the next use. */
private class MemoryKey : ErasableKey {
    override val guard = Mutex()
    var erased = 0
        private set
    private var key = newKey()

    override fun get(): SecretKey = key

    override fun erase() {
        erased++
        key = newKey()
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/**
 * Mimics the SQL in [ConversationDao]; KestrelDatabaseTest checks the real queries and the transaction on a device. Its
 * write is the DAO's own, so these tests run it; [transactions] counts its calls.
 */
private class FakeConversationDao : ConversationDao() {
    val rows = linkedMapOf<String, ConversationRow>()
    var beforeUpsert: suspend () -> Unit = {}
    var transactions = 0

    override suspend fun write(rows: List<ConversationRow>) {
        transactions++
        super.write(rows)
    }

    override suspend fun headers(): List<ConversationHeader> =
        rows.values.sortedByDescending { it.lastMillis }.map { ConversationHeader(it.id, it.openedMillis, it.lastMillis, it.open, it.commands) }

    override suspend fun get(id: String): ConversationRow? = rows[id]

    override suspend fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override suspend fun deleteOutside(oldest: Long, newest: Long): Int {
        val outside = rows.values.filter { !it.open && (it.lastMillis < oldest || it.lastMillis > newest) }.map { it.id }
        outside.forEach { rows.remove(it) }
        return outside.size
    }

    override suspend fun deleteAll(): Int = rows.size.also { rows.clear() }

    override suspend fun upsert(row: ConversationRow) {
        beforeUpsert()
        rows[row.id] = row
    }

    override suspend fun closeOthers(id: String): Int {
        val others = rows.values.filter { it.open && it.id != id }
        others.forEach { rows[it.id] = ConversationRow(it.id, it.openedMillis, it.lastMillis, open = false, commands = it.commands, payload = it.payload) }
        return others.size
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest --tests=dev.kestrel.privacy.conversations.ConversationArchiveStoreTest`
Expected: FAIL, the compiler reports `Unresolved reference 'ConversationArchiveStore'`, `Unresolved reference 'PlainConversation'` and `Unresolved reference 'StoredConversation'`.

- [ ] **Step 3: Write the store**

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt`:

```kotlin
package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

/** A conversation to store: its plain fields and its payload, which the archive encrypts. toString prints only sizes. */
class PlainConversation(val header: ConversationHeader, val payload: ByteArray) {
    override fun toString(): String = "PlainConversation(header=$header, payload=${payload.size} bytes)"
}

/** A conversation as the archive reads it back. */
sealed interface StoredConversation {
    val header: ConversationHeader

    /** Its payload, decrypted. */
    class Readable(override val header: ConversationHeader, val payload: ByteArray) : StoredConversation {
        override fun toString(): String = "Readable(header=$header, payload=${payload.size} bytes)"
    }

    /** Its payload failed its check: it was moved from another row, damaged, or its key was erased. It can never be read. */
    class FailedCheck(override val header: ConversationHeader) : StoredConversation

    /** Its payload is in a format that this version of Kestrel cannot read; the row stays for a later version. */
    class UnknownFormat(override val header: ConversationHeader) : StoredConversation
}

/**
 * The archive of past conversations (design 9.1 and 9.2 of sub-project 7): one row per conversation, whose payload is
 * encrypted under its own erasable key and bound to the conversation's id, so that a payload cannot be moved to another
 * row. It knows only bytes: `:app` gives it the conversation's JSON. Only the id, the open and last-change times, whether
 * the conversation is open and its number of commands are plain, for sorting and retention.
 *
 * It deletes no row on its own: plan 7B's store deletes a conversation that fails its check, and reports it. A
 * conversation in a format that this version cannot read is kept, and [onUnknownFormat] hears of it once per process.
 *
 * Every function runs under the key's [ErasableKey.guard], which every user of the key shares, so that [deleteAll] never
 * erases the key between the encryption of a write and its row, which would leave a row that no key opens. The functions are safe to call from the main thread, because they
 * encrypt, decrypt and query on [dispatcher].
 */
class ConversationArchiveStore(
    private val dao: ConversationDao,
    private val key: ErasableKey,
    private val onUnknownFormat: suspend () -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cipher = AesGcmFieldCipher { key.get() }

    // The ids of the rows in an unknown format that were reported, so that each is reported once per process. Used only
    // under the key's guard.
    private val reportedUnknown = mutableSetOf<String>()

    /** Every conversation's plain fields, the newest last change first. */
    suspend fun headers(): List<ConversationHeader> = locked { dao.headers() }

    /** The conversation [id], or null when there is none. */
    suspend fun read(id: String): StoredConversation? = locked { dao.get(id)?.let { stored(it) } }

    /**
     * Stores [conversations], each replacing its earlier row, in one transaction. Before an open one is stored, every other
     * open conversation closes, so that at most one is open (design 6.1 of sub-project 7).
     */
    suspend fun write(conversations: List<PlainConversation>) {
        locked {
            dao.write(
                conversations.map {
                    val header = it.header
                    ConversationRow(header.id, header.openedMillis, header.lastMillis, header.open, header.commands, cipher.encrypt(it.payload, associatedData(header.id)))
                },
            )
            conversations.forEach { reportedUnknown.remove(it.header.id) }
        }
    }

    suspend fun delete(id: String) {
        locked {
            dao.delete(id)
            reportedUnknown.remove(id)
        }
    }

    /**
     * "Delete history" (design 9.7 of sub-project 7): deletes every conversation, the open one too, and then erases the key,
     * so that no copy of the database file can be read afterwards. The next write makes a new key.
     */
    suspend fun deleteAll() {
        locked {
            dao.deleteAll()
            key.erase()
            reportedUnknown.clear()
        }
    }

    /**
     * Deletes the closed conversations whose last change is before [oldestMillis] or after [newestMillis], and returns how
     * many it deleted; the open conversation stays (design 9.3 of sub-project 7).
     */
    suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int = locked { dao.deleteOutside(oldestMillis, newestMillis) }

    private suspend fun stored(row: ConversationRow): StoredConversation {
        val header = ConversationHeader(row.id, row.openedMillis, row.lastMillis, row.open, row.commands)
        return try {
            StoredConversation.Readable(header, cipher.decrypt(row.payload, associatedData(row.id)))
        } catch (e: AEADBadTagException) {
            StoredConversation.FailedCheck(header)
        } catch (e: IllegalArgumentException) {
            if (reportedUnknown.add(row.id)) onUnknownFormat()
            StoredConversation.UnknownFormat(header)
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = key.guard.withLock { withContext(dispatcher) { block() } }

    private fun associatedData(id: String) = "conversations/$id".encodeToByteArray()
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest --tests=dev.kestrel.privacy.conversations.ConversationArchiveStoreTest`
Expected: PASS, 9 tests.

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest :core:privacy:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL, P₀ + 9 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt
git commit -m "feat(privacy): the archive of conversations encrypts each payload bound to its id, writes a switch in one transaction, keeps a payload in an unknown format and erases its key with its rows (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: `PrivacyStore.conversations`, its report of a kept conversation, and the first read's speed

`PrivacyStore` builds the archive on Kestrel's database with the key `kestrel.conversations`, and writes a conversation that it keeps unread into the activity log by its kind only (design 9.2). A device test measures design R3's risk: the first read of 30 days of conversations through the Keystore, as plan 7B's store reads them.

**Files:**
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt`
- Test: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`
- Create: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/ConversationArchiveSpeedTest.kt`

**Interfaces:**
- Consumes: Task 1's `KestrelDatabase.conversationDao()`, `ConversationRow`, `ConversationHeader`; Task 2's `KeystoreErasableKey` and `KeystoreKeys.CONVERSATIONS_KEY`; Task 3's `ConversationArchiveStore`, `PlainConversation` and `StoredConversation`.
- Produces: `PrivacyStore.conversations: ConversationArchiveStore`. The activity log's text "A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it". The instrumentation argument `kestrelArchiveSpeed=true`, the log tag `KestrelArchive` and the log line `read <n> conversations of 12 KB in <ms> ms`, which Task 8 reads. Task 6 gives `PrivacyStore.conversations` to Task 5's `RoomRecordArchive`.

Counts: `:core:privacy` JVM unchanged. `:core:privacy` device tests N + 3 (`PrivacyStoreTest` + 2, `ConversationArchiveSpeedTest` 1, which is skipped without its argument).

- [ ] **Step 1: Write the failing device tests**

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`, replace:

```kotlin
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
```

with:

```kotlin
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`, replace:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
```

with:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
```

Add these tests to `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`, before its last closing brace:

```kotlin
    // Design 9.1 and 9.7 of sub-project 7, in this library's own test app, whose Keystore and database are not Kestrel's.
    @Test
    fun conversationsUseTheirOwnKeyWhichDeleteAllErasesWithTheRows() = runBlocking {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val header = ConversationHeader("test-conversation-${System.nanoTime()}", 1, 2, open = false, commands = 1)
        try {
            store.conversations.write(listOf(PlainConversation(header, "first".encodeToByteArray())))
            assertTrue(keyStore.containsAlias(KeystoreKeys.CONVERSATIONS_KEY))

            store.conversations.deleteAll()

            assertEquals(emptyList<ConversationHeader>(), store.conversations.headers())
            assertFalse(keyStore.containsAlias(KeystoreKeys.CONVERSATIONS_KEY))
            store.conversations.write(listOf(PlainConversation(header, "second".encodeToByteArray())))
            val read = store.conversations.read(header.id) as StoredConversation.Readable
            assertEquals("second", read.payload.decodeToString())
        } finally {
            store.conversations.deleteAll()
        }
    }

    // Design 9.2 of sub-project 7: the activity log names the kind, never the conversation's id or words.
    @Test
    fun aConversationInAnUnknownFormatIsKeptAndRecordedInTheActivityLogByItsKindOnly() = runBlocking {
        val id = "test-conversation-${System.nanoTime()}"
        store.logs.deleteHistory()
        val dao = KestrelDatabase.get(context).conversationDao()
        try {
            // A later version's ciphertext: its first byte names a format that this version does not know.
            dao.write(listOf(ConversationRow(id, 1, 2, open = false, commands = 1, payload = byteArrayOf(2) + ByteArray(40))))

            assertTrue(store.conversations.read(id) is StoredConversation.UnknownFormat)

            assertEquals(id, dao.get(id)?.id)
            val entry = store.logs.activity().single()
            assertEquals(ActivityKind.ERROR, entry.event.kind)
            assertEquals("A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it", entry.event.text)
        } finally {
            store.conversations.delete(id)
        }
    }
```

Create `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/ConversationArchiveSpeedTest.kt`:

```kotlin
package dev.kestrel.privacy

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the first read of 30 days of past conversations takes (design 4.3 and R3 of sub-project 7): 300 closed
 * conversations of 12 KB each are written to a database file and read back as plan 7B's store reads them at its first use
 * (every header, then each row), through a new store, as after a start of Kestrel, so that each row is decrypted once
 * through the Keystore. It is a measurement: it fails only when a conversation does not read back, and it logs the time
 * under the tag KestrelArchive. Plan 7C's Task 8 decides on the A71's time. It runs only with the instrumentation argument
 * kestrelArchiveSpeed=true. It uses a test-named database file and a test key of this library's own test app, never
 * Kestrel's.
 */
@RunWith(AndroidJUnit4::class)
class ConversationArchiveSpeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "kestrel.test.archive-speed.${System.nanoTime()}"

    @After
    fun cleanUp() {
        context.deleteDatabase(FILE)
        KeystoreErasableKey.of(alias).erase()
    }

    @Test
    fun measuresTheFirstReadOf30DaysOfConversations() = runBlocking {
        assumeTrue(
            "Runs with -Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true",
            InstrumentationRegistry.getArguments().getString("kestrelArchiveSpeed") == "true",
        )
        val payload = ByteArray(PAYLOAD_BYTES) { ('a'.code + it % 26).toByte() }
        val now = System.currentTimeMillis()
        val written = database()
        try {
            val writer = ConversationArchiveStore(written.conversationDao(), KeystoreErasableKey.of(alias))
            writer.write(
                List(CONVERSATIONS) { n ->
                    val last = now - DAYS * DAY_MILLIS * n / CONVERSATIONS
                    PlainConversation(ConversationHeader("test-$n", last - 60_000, last, open = false, commands = 3), payload)
                },
            )
        } finally {
            written.close()
        }

        val read = database()
        val (millis, readable) = try {
            val reader = ConversationArchiveStore(read.conversationDao(), KeystoreErasableKey.of(alias))
            val started = SystemClock.elapsedRealtime()
            val conversations = reader.headers().map { reader.read(it.id) }
            (SystemClock.elapsedRealtime() - started) to conversations.count { it is StoredConversation.Readable }
        } finally {
            read.close()
        }

        Log.i(TAG, "read $readable conversations of ${PAYLOAD_BYTES / 1024} KB in $millis ms")
        assertEquals(CONVERSATIONS, readable)
    }

    private fun database() = Room.databaseBuilder(context, KestrelDatabase::class.java, FILE).build()

    private companion object {
        const val TAG = "KestrelArchive"
        const val FILE = "test-archive-speed.db"
        const val CONVERSATIONS = 300
        const val PAYLOAD_BYTES = 12 * 1024
        const val DAYS = 30L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
```

- [ ] **Step 2: Compile the device tests to see them fail**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports `Unresolved reference 'conversations'`.

- [ ] **Step 3: Build the archive in `PrivacyStore`**

Replace the whole `core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt` with:

```kotlin
package dev.kestrel.privacy

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogStore
import dev.kestrel.privacy.settings.SettingsStore

/**
 * Kestrel's encrypted storage: the activity log, the network log, the settings, the providers' credentials and the past
 * conversations (design spec, sections 10.7 and 13.4; design 9 of sub-project 7). App code should get the stores from
 * `KestrelApplication.graph` instead of creating another `PrivacyStore`.
 */
class PrivacyStore(context: Context, clock: () -> Long = System::currentTimeMillis) {
    private val dataKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.DATA_KEY) }
    private val cipher = AesGcmFieldCipher { dataKey }
    private val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val credentialsKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.CREDENTIALS_KEY, strongBox) }
    private val database = KestrelDatabase.get(context)

    val logs = LogStore(database.logDao(), cipher, clock)
    val settings = SettingsStore(
        database.settingsDao(),
        cipher,
        onUnreadable = { name ->
            logs.append(ActivityEvent(ActivityKind.ERROR, "The setting '$name' could not be read, so Kestrel uses its default"))
        },
    )

    /**
     * The providers' credentials, under their own key, in StrongBox when the device has it (design spec, section 10.7). A
     * credential that cannot be read is reported only to Android's log, without its ID, and never to the activity log; its
     * profile then asks the owner to sign in again.
     */
    val credentials = CredentialStore(
        database.credentialsDao(),
        AesGcmFieldCipher { credentialsKey },
        onUnreadable = { Log.w(TAG, "A stored credential could not be read") },
    )

    /**
     * The past conversations, under their own key, which "delete history" erases with the rows, so that no copy of the
     * database file can be read afterwards (design 9.1 and 9.7 of sub-project 7). A conversation in a format that this
     * version cannot read is kept and reported to the activity log by its kind only, never by its id or its words; one that
     * fails its check is deleted and reported by plan 7B's store, which the app graph wires (design 9.2).
     */
    val conversations = ConversationArchiveStore(
        database.conversationDao(),
        KeystoreErasableKey.of(KeystoreKeys.CONVERSATIONS_KEY),
        onUnknownFormat = {
            logs.append(ActivityEvent(ActivityKind.ERROR, "A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it"))
        },
    )

    private companion object {
        const val TAG = "Kestrel"
    }
}
```

- [ ] **Step 4: Run the device tests to see them pass**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin :core:privacy:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, P₀ + 9 tests.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.PrivacyStoreTest,dev.kestrel.privacy.ConversationArchiveSpeedTest'`
Expected: BUILD SUCCESSFUL, 6 tests, none failed, 1 of them skipped (the speed test without its argument); the script ends with exit code 0.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & (Get-Adb) -s emulator-5554 logcat -c; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.ConversationArchiveSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true'; & (Get-Adb) -s emulator-5554 logcat -d -s KestrelArchive:I; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL, 1 test, none failed or skipped; the log line `read 300 conversations of 12 KB in <n> ms`. Note `<n>` in the task's report. The time on kestrel-api37 is noted, never a stop, however long it is: the emulator's Keystore is not the A71's, and the A71's time decides (Task 8).

- [ ] **Step 5: Commit**

```powershell
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/ConversationArchiveSpeedTest.kt
git commit -m "feat(privacy): PrivacyStore keeps past conversations under the key kestrel.conversations and logs a kept unreadable one by its kind only; a device test times the first read of 30 days (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: `RoomRecordArchive`, plan 7B's port on the archive

Starts only after plan 7B as committed and changed at `a4558faf` is carried out (see "Before you start"). `:app` implements `:core:conversation`'s `RecordArchive` with `:core:privacy`'s archive (design 5.1 and 9.2): the snapshot's JSON travels as the bytes that plan 7B's store gives, the plain fields as they are, and each kind of stored conversation as its kind of entry.

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt`

**Interfaces:**
- Consumes: plan 7B's `RecordArchive`, `ArchiveHeader`, `ArchiveRow`, `ArchiveEntry` (with `Unknown`), and `ArchivedConversationStore`, `ConversationSnapshot` (whose `open` is `@Transient`) and `ConversationId`, whose store takes `open` from the header (sections "Interfaces consumed from plan 7B" and "Plan 7B as committed"); Task 3's `ConversationArchiveStore`, `PlainConversation` and `StoredConversation`; Task 1's `ConversationHeader`, `ConversationRow` and `KestrelDatabase.conversationDao()`; Task 2's `KeystoreErasableKey.of`.
- Produces: `:app`'s dependency on `:core:conversation`; package `dev.kestrel.app.conversation`: `internal class RoomRecordArchive(table: ConversationArchiveStore) : RecordArchive`. Task 6 builds it on `PrivacyStore.conversations`.

Counts: `:app` JVM unchanged (A₀). `:app` device tests AD₀ + 4 (`RoomRecordArchiveTest` 4), run in Task 8.

- [ ] **Step 1: Write the failing device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveHeader
import dev.kestrel.conversation.store.ArchiveRow
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * The archive's port on the real encrypted table and the device's Keystore (design 9.1 and 9.2 of sub-project 7). It uses
 * a database in memory and a test key, never Kestrel's own, and made-up texts only.
 */
@RunWith(AndroidJUnit4::class)
class RoomRecordArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "kestrel.test.conversations.${System.nanoTime()}"
    private val database = Room.inMemoryDatabaseBuilder(context, KestrelDatabase::class.java).build()
    private val archive = RoomRecordArchive(ConversationArchiveStore(database.conversationDao(), KeystoreErasableKey.of(alias)))

    private val past = ArchiveHeader("0123456789abcdef0123456789abcdef", openedMillis = 10, lastMillis = 20, open = false, commands = 2)
    private val current = ArchiveHeader("fedcba9876543210fedcba9876543210", openedMillis = 30, lastMillis = 40, open = true, commands = 1)

    @After
    fun cleanUp() {
        database.close()
        KeystoreErasableKey.of(alias).erase()
    }

    @Test
    fun everyFieldTravelsThroughTheEncryptedTable() = runBlocking {
        archive.write(listOf(row(past, """{"request":"Пошаљи Мили поруку 🙂"}"""), row(current, """{"request":"Turn on dark mode"}""")))

        assertEquals(listOf(current, past), archive.headers())
        val read = archive.read(past.id) as ArchiveEntry.Readable
        assertEquals(past, read.header)
        assertEquals("""{"request":"Пошаљи Мили поруку 🙂"}""", read.payload.decodeToString())
        assertFalse(database.conversationDao().get(past.id)!!.payload.decodeToString(throwOnInvalidSequence = false).contains("Мили"))
        assertNull(archive.read("ffffffffffffffffffffffffffffffff"))

        // A switch (design 9.6): the open conversation closes and the past one opens, in one write.
        archive.write(listOf(row(current.copy(open = false, lastMillis = 50), "{}"), row(past.copy(open = true, lastMillis = 50), "{}")))
        assertEquals(setOf(current.id to false, past.id to true), archive.headers().map { it.id to it.open }.toSet())

        assertEquals(1, archive.deleteOutside(oldestMillis = 51, newestMillis = 60))
        archive.delete(past.id)
        assertEquals(emptyList<ArchiveHeader>(), archive.headers())
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes an Unreadable entry and leaves an Unknown one out.
    @Test
    fun eachKindOfUnreadableConversationBecomesItsKindOfEntry() = runBlocking {
        archive.write(listOf(row(past, "{}")))
        val dao = database.conversationDao()
        val sealed = dao.get(past.id)!!.payload
        dao.write(listOf(ConversationRow("moved", 1, 2, open = false, commands = 1, payload = sealed)))
        // A later version's ciphertext: its first byte names a format that this version does not know.
        dao.write(listOf(ConversationRow("later", 1, 2, open = false, commands = 1, payload = byteArrayOf(2) + ByteArray(40))))

        val moved = archive.read("moved")
        val later = archive.read("later")

        assertTrue(moved is ArchiveEntry.Unreadable)
        assertEquals(ArchiveHeader("moved", 1, 2, open = false, commands = 1), moved!!.header)
        assertTrue(later is ArchiveEntry.Unknown)
    }

    // Design 9.7 of sub-project 7: deleting through the port erases the archive's key with its rows.
    @Test
    fun deleteAllErasesTheRowsAndTheKey() = runBlocking {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        archive.write(listOf(row(current, "{}")))
        assertTrue(keyStore.containsAlias(alias))

        archive.deleteAll()

        assertEquals(emptyList<ArchiveHeader>(), archive.headers())
        assertFalse(keyStore.containsAlias(alias))
    }

    // Design 6.1 of sub-project 7: the table's open column is the one truth about which conversation is open, and the
    // snapshot's JSON does not hold it. When a second open conversation is written, the table closes the first; after a
    // restart, the store reads the first as closed.
    @Test
    fun theTablesOpenColumnDecidesWhichConversationIsOpen() = runBlocking {
        val first = ConversationSnapshot(ConversationId(past.id), openedAtWall = 10, lastWall = 20, open = true, testMode = true)
        val second = ConversationSnapshot(ConversationId(current.id), openedAtWall = 30, lastWall = 40, open = true, testMode = true)
        val writing = ArchivedConversationStore(archive) {}
        writing.save(first)
        writing.save(second)

        val restarted = ArchivedConversationStore(archive) {}

        assertEquals(listOf(current.id to true, past.id to false), archive.headers().map { it.id to it.open })
        assertEquals(second.id, restarted.open()?.id)
        assertEquals(listOf(first.id), restarted.list().map { it.id })
    }

    private fun row(header: ArchiveHeader, json: String) = ArchiveRow(header, json.encodeToByteArray())
}
```

- [ ] **Step 2: Compile the device tests to see them fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports unresolved references for the imports of `dev.kestrel.conversation.store`, which `:app` does not depend on yet, and for `RoomRecordArchive`.

- [ ] **Step 3: Add the dependency and the adapter**

In `app/build.gradle.kts`, replace:

```kotlin
    implementation(project(":core:commands"))
    implementation(project(":core:privacy"))
```

with:

```kotlin
    implementation(project(":core:commands"))
    // Sub-project 7: the conversation, whose archive port :app implements on :core:privacy (plan 7C).
    implementation(project(":core:conversation"))
    implementation(project(":core:privacy"))
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveHeader
import dev.kestrel.conversation.store.ArchiveRow
import dev.kestrel.conversation.store.RecordArchive
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.db.ConversationHeader

/**
 * :core:conversation's archive on :core:privacy's encrypted table (design 9.2 of sub-project 7). The payload, the
 * snapshot's JSON, travels as the bytes that the conversation's store gives, and the table encrypts it under the key
 * kestrel.conversations; the plain fields stay plain, for sorting and retention. A payload that fails its check becomes
 * [ArchiveEntry.Unreadable], which the conversation's store deletes and reports; one in a format that this version cannot
 * read becomes [ArchiveEntry.Unknown], which it leaves out and the table keeps.
 */
internal class RoomRecordArchive(private val table: ConversationArchiveStore) : RecordArchive {
    override suspend fun headers(): List<ArchiveHeader> = table.headers().map { it.archived() }

    override suspend fun read(id: String): ArchiveEntry? =
        when (val stored = table.read(id)) {
            null -> null
            is StoredConversation.Readable -> ArchiveEntry.Readable(stored.header.archived(), stored.payload)
            is StoredConversation.FailedCheck -> ArchiveEntry.Unreadable(stored.header.archived())
            is StoredConversation.UnknownFormat -> ArchiveEntry.Unknown(stored.header.archived())
        }

    override suspend fun write(rows: List<ArchiveRow>) {
        table.write(rows.map { PlainConversation(it.header.plain(), it.payload) })
    }

    override suspend fun delete(id: String) {
        table.delete(id)
    }

    override suspend fun deleteAll() {
        table.deleteAll()
    }

    override suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int = table.deleteOutside(oldestMillis, newestMillis)
}

private fun ConversationHeader.archived() = ArchiveHeader(id, openedMillis, lastMillis, open, commands)

private fun ArchiveHeader.plain() = ConversationHeader(id, openedMillis, lastMillis, open, commands)
```

- [ ] **Step 4: Build**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, A₀ tests. The device tests run in Task 8.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add app/build.gradle.kts app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt
git commit -m "feat(app): RoomRecordArchive implements the conversation's archive port on the encrypted table of :core:privacy (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The store, its report, the clock and the purge job in the app graph

`AppGraph` builds plan 7B's `ArchivedConversationStore` on `RoomRecordArchive` and `PrivacyStore.conversations` (one instance, whose snapshots in memory plan 7D's engine, search and local requests share), writes a conversation that the store deletes unread into the activity log by its kind only, and builds plan 7B's `Retention` with the phone's clock, `AndroidConversationClock`, and the owner's retention from the new setting `conversations` (design 5.6, 9.2, 9.3 and 13). `KestrelApplication` runs the purge job from Kestrel's start for the life of the process.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/AndroidConversationClock.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`

**Interfaces:**
- Consumes: Task 4's `PrivacyStore.conversations`; Task 5's `RoomRecordArchive`; plan 7B's `ArchivedConversationStore(archive, unreadable)` with `KIND`, `Retention(store, clock, report).keepPurging(days)`, `ConversationClock` and `ConversationSettings` with its `keptDays` (section "Interfaces consumed from plan 7B"); `AppGraph`'s `scope`, `logs`, `settings` and `reportFailure`.
- Produces: `internal object AndroidConversationClock : ConversationClock` (package `dev.kestrel.app.conversation`; `System.currentTimeMillis()` and `ZoneId.systemDefault()`); `SettingKeys.CONVERSATIONS: SettingKey<ConversationSettings>` (stored as `conversations`); `AppGraph.conversationStore: ArchivedConversationStore` (`internal`, built on first use); `internal suspend fun AppGraph.keepPurgingConversations()`, which runs `Retention.keepPurging` with the setting's `keptDays` (the retention within its choices, never the raw `retentionDays`) and never returns while the setting's flow lives; the activity log's text "A conversation could not be read, so Kestrel deleted it". Plan 7D builds its engine, `HistorySearch` and `LocalRequests` on `conversationStore` and uses `AndroidConversationClock`; plan 7F's Conversations page writes `SettingKeys.CONVERSATIONS`.

Counts: `:app` JVM unchanged. `:app` device tests N + 1 (`AppGraphTest` + 1), run in Task 8.

- [ ] **Step 1: Write the failing device test**

In `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`, replace:

```kotlin
import dev.kestrel.privacy.logs.NetworkOutcome
```

with:

```kotlin
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.NetworkOutcome
```

Add this test to `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`, before its last closing brace:

```kotlin
    // Plan 7C: the install of this build migrated Kestrel's own database to version 3, and the graph's store reads its
    // table through the archive; a read that failed would throw here. Nothing of a conversation is printed.
    @Test
    fun theGraphsConversationStoreReadsKestrelsDatabaseAtVersion3() = runBlocking {
        graph.conversationStore.list()

        assertEquals(3, KestrelDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext).openHelper.readableDatabase.version)
    }
```

- [ ] **Step 2: Compile the device test to see it fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports `Unresolved reference 'conversationStore'`.

- [ ] **Step 3: Add the clock, the store and the purge job**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/AndroidConversationClock.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.engine.ConversationClock
import java.time.ZoneId

/**
 * The phone's wall clock and zone for the conversation (design 6.2 and 6.7 of sub-project 7): stored times are wall-clock
 * times, and the zone names the days of past conversations. The zone is read at each call, so that a change of the phone's
 * zone counts at once.
 */
internal object AndroidConversationClock : ConversationClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.commands.ScreenTargets
```

with:

```kotlin
import dev.kestrel.app.commands.ScreenTargets
import dev.kestrel.app.conversation.AndroidConversationClock
import dev.kestrel.app.conversation.RoomRecordArchive
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.providers.StoredCredentials
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessagesByCause
```

with:

```kotlin
import dev.kestrel.app.providers.StoredCredentials
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.Retention
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.describeWithoutMessagesByCause
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The voice and the network that the owner chose for the download of the models (release plan). */
    val MODEL_DOWNLOAD = SettingKey("model-download", ModelDownloadSetting.serializer(), ModelDownloadSetting())
}
```

with:

```kotlin
    /** The voice and the network that the owner chose for the download of the models (release plan). */
    val MODEL_DOWNLOAD = SettingKey("model-download", ModelDownloadSetting.serializer(), ModelDownloadSetting())

    // sub-project 7: keys

    /**
     * The owner's choices for conversations (design 13 of sub-project 7): the quiet time, the follow-up window, spoken
     * replies to typed commands, how long past conversations are kept, and a goal's limits per round. Plan 7C's purge job
     * reads the retention; plan 7F's Conversations page writes them.
     */
    val CONVERSATIONS = SettingKey("conversations", ConversationSettings.serializer(), ConversationSettings())
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The providers' credentials, encrypted under their own key (design spec, section 10.7). */
    val credentialVault: CredentialVault by lazy { StoredCredentials(store.credentials) }
```

with:

```kotlin
    /** The providers' credentials, encrypted under their own key (design spec, section 10.7). */
    val credentialVault: CredentialVault by lazy { StoredCredentials(store.credentials) }

    // sub-project 7C: archive

    /**
     * The conversations as :core:conversation keeps and searches them (design 9.1 to 9.4 of sub-project 7), on
     * :core:privacy's encrypted table under its own key. There is one, so that the snapshots that it keeps in memory follow
     * every change: plan 7D's engine, search and local requests use it. A conversation that fails its check is deleted and
     * written into the activity log by its kind only (design 9.2).
     */
    internal val conversationStore: ArchivedConversationStore by lazy {
        ArchivedConversationStore(RoomRecordArchive(store.conversations)) { kind ->
            scope.launch { logs.append(ActivityEvent(ActivityKind.ERROR, "A $kind could not be read, so Kestrel deleted it")) }
        }
    }

    private val conversationRetention: Retention by lazy { Retention(conversationStore, AndroidConversationClock, reportFailure) }

    /**
     * Purges the closed conversations past the owner's retention (design 9.3 of sub-project 7): at once, once a day, and at
     * each change of the retention; a failed purge is logged by its classes only, and the job goes on. It reads keptDays,
     * the retention within its choices, so that a damaged stored value can never purge every conversation. KestrelApplication
     * runs it for the life of the process, with the accessibility service on or off.
     */
    internal suspend fun keepPurgingConversations() {
        conversationRetention.keepPurging(settings.observe(SettingKeys.CONVERSATIONS).map { it.keptDays })
    }
```

In `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`, replace:

```kotlin
        graph.scope.launch { graph.logs.purgeExpired() }
```

with:

```kotlin
        graph.scope.launch { graph.logs.purgeExpired() }
        graph.scope.launch { graph.keepPurgingConversations() }
```

- [ ] **Step 4: Build**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, A₀ tests. The device test runs in Task 8.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/conversation/AndroidConversationClock.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt
git commit -m "feat(app): the app graph builds the store of conversations on the encrypted archive, logs an unreadable one by its kind, and Kestrel purges past conversations by the owner's retention while it runs (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Design 9.1 and 9.2 as built

The design's sections 9.1 and 9.2 describe the archive as this plan and plan 7B build it: the abstract DAO whose writes keep one conversation open, the archive's functions, the two kinds of unreadable conversation, the key outside StrongBox, and plan 7B's `ConversationStore`.

**Files:**
- Modify: `docs/superpowers/specs/2026-11-24-conversations-design.md`

**Interfaces:**
- Consumes: Tasks 1 to 6; plan 7B's `ConversationStore` (section "Interfaces consumed from plan 7B").
- Produces: design 9.1 and 9.2 as built, which plans 7D to 7G read.

Counts: no test changes.

- [ ] **Step 1: Write the key's rule into design 9.1**

In `docs/superpowers/specs/2026-11-24-conversations-design.md`, replace:

```markdown
- The separate key lets "delete history" delete the key too, so that no copy of the database file can be read afterwards. The logs keep the data key, as today.
```

with:

```markdown
- The separate key lets "delete history" delete the key too, so that no copy of the database file can be read afterwards. The logs keep the data key, as today. The key is never made in StrongBox, whose slower operations would make the first read of hundreds of conversations slow (R3); the data key is not in StrongBox either.
```

- [ ] **Step 2: Write the table, the archive and the store into design 9.2**

In `docs/superpowers/specs/2026-11-24-conversations-design.md`, replace:

```markdown
@Dao interface ConversationDao {
    @Upsert suspend fun put(row: ConversationRow)
    @Query("SELECT * FROM conversations WHERE open = 1 LIMIT 1") suspend fun openOne(): ConversationRow?
    @Query("SELECT id, opened_millis, last_millis, open, commands FROM conversations ORDER BY last_millis DESC")
    suspend fun headers(): List<ConversationHeader>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun get(id: String): ConversationRow?
    @Query("SELECT * FROM conversations WHERE open = 0 AND last_millis >= :since ORDER BY last_millis DESC")
    suspend fun closedSince(since: Long): List<ConversationRow>
    @Query("UPDATE conversations SET open = 0 WHERE open = 1") suspend fun closeAll(): Int
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: String): Int
    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    suspend fun deleteOutside(oldest: Long, newest: Long): Int
    @Query("DELETE FROM conversations") suspend fun deleteAll(): Int
}
```

with:

```markdown
data class ConversationHeader(
    val id: String,
    @ColumnInfo(name = "opened_millis") val openedMillis: Long,
    @ColumnInfo(name = "last_millis") val lastMillis: Long,
    val open: Boolean,
    val commands: Int,
)

// The table keeps at most one conversation open (6.1): write closes the other open rows in the same transaction.
@Dao abstract class ConversationDao {
    @Transaction open suspend fun write(rows: List<ConversationRow>) {
        for (row in rows) { if (row.open) closeOthers(row.id); upsert(row) }
    }
    @Query("SELECT id, opened_millis, last_millis, open, commands FROM conversations ORDER BY last_millis DESC")
    abstract suspend fun headers(): List<ConversationHeader>
    @Query("SELECT * FROM conversations WHERE id = :id") abstract suspend fun get(id: String): ConversationRow?
    @Query("DELETE FROM conversations WHERE id = :id") abstract suspend fun delete(id: String): Int
    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    abstract suspend fun deleteOutside(oldest: Long, newest: Long): Int
    @Query("DELETE FROM conversations") abstract suspend fun deleteAll(): Int
    @Upsert protected abstract suspend fun upsert(row: ConversationRow)
    @Query("UPDATE conversations SET open = 0 WHERE open = 1 AND id != :id") protected abstract suspend fun closeOthers(id: String): Int
}
```

In `docs/superpowers/specs/2026-11-24-conversations-design.md`, replace:

```markdown
`:core:privacy` offers it as `ConversationArchiveStore` (encrypt, decrypt, put, headers, get, delete, purge), which knows only bytes. `:app`'s `RoomRecordArchive` implements `:core:conversation`'s `RecordArchive` port with it, and `ArchivedConversationStore` does the JSON. A payload that fails its tag is deleted and reported to the activity log by its kind only, as `SettingsStore` does with a setting.
```

with:

```markdown
`:core:privacy` offers it as `ConversationArchiveStore` (`headers`, `read`, `write`, `delete`, `deleteAll`, `deleteOutside`), which knows only bytes and keeps the encryption and decryption inside. Its `read` gives a conversation's payload, or says that the payload failed its check, or that it is in a format that this version cannot read. `:app`'s `RoomRecordArchive` implements `:core:conversation`'s `RecordArchive` port with it, and `ArchivedConversationStore` does the JSON. A payload that fails its check, or does not decode, is deleted by `ArchivedConversationStore` and reported to the activity log by its kind only. A payload in a format that this version cannot read is kept for a later version of Kestrel, left out, and reported to the activity log once per process by its kind only, as `SettingsStore` does with a setting. The table's `open` column is the one truth about which conversation is open: `ArchivedConversationStore` takes `open` from a row's header, and the snapshot's JSON does not hold it, and `deleteOutside` reads the same column. `deleteAll` deletes every row and then erases the key. Every function of `ConversationArchiveStore` runs under the key's own lock, which is process-wide with the key, so that the key is never erased between the encryption of a write and its row.
```

In `docs/superpowers/specs/2026-11-24-conversations-design.md`, replace:

```markdown
    suspend fun reopen(id: ConversationId): ConversationSnapshot?  // closes the open one and opens this one, in one transaction
    suspend fun delete(id: ConversationId)
    suspend fun deleteAll()
    suspend fun purge(retentionDays: Int, nowWall: Long): Int
}
```

with:

```markdown
    // Stores closing as closed and opens id with the current test mode, in one transaction (9.6)
    suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot?
    suspend fun delete(id: ConversationId)
    suspend fun deleteAll()
    suspend fun purge(retentionDays: Int, nowWall: Long): Int
    suspend fun closedSince(sinceWall: Long): List<ConversationSnapshot>  // for the search (9.4), newest first
}
```

- [ ] **Step 3: Check the design's words**

Run: `$spec = 'docs\superpowers\specs\2026-11-24-conversations-design.md'; foreach ($text in 'closeAll', 'openOne', 'encrypt, decrypt, put', '@Dao interface ConversationDao', 'never made in StrongBox', 'protected abstract suspend fun closeOthers', 'kept for a later version of Kestrel', 'suspend fun closedSince(sinceWall: Long)', 'the one truth about which conversation is open') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }`
Expected: `closeAll : 0`, `openOne : 0`, `encrypt, decrypt, put : 0`, `@Dao interface ConversationDao : 0`, and 1 for each of the other five.

- [ ] **Step 4: Commit**

```powershell
git add docs/superpowers/specs/2026-11-24-conversations-design.md
git commit -m "docs(spec): design 9.1 and 9.2 describe the archive as built: one open conversation kept by the table, failed and unknown payloads apart, and the key outside StrongBox (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The checks on kestrel-api37 and the A71 (the controller's steps)

The controller runs every device test of this plan, installs this build on kestrel-api37 so that Kestrel's own database migrates from version 2 to 3 with the owner's data in it (design 15.2: "the Room migration from version 2 to 3 with the real Keystore"), and measures the first read's speed on the A71 (design 4.3 and R3).

**Files:**
- Modify: `AGENTS.md` (Step 2: the rule that only builds with plan 7C go onto kestrel-api37)
- Every other step is a device step.

**Interfaces:**
- Consumes: every earlier task; `scripts\connected-test.ps1`, `scripts\install.ps1`, `scripts\hook.ps1`.
- Produces: the rule that only builds with plan 7C go onto kestrel-api37, in AGENTS.md and `.superpowers\sdd\progress.md`; the speed of the first read on kestrel-api37 and the A71, and the result of the migration, in `.superpowers\sdd\progress.md`.

Counts: `:core:privacy` device tests D₀ + 8 run, 1 of them skipped without its argument; `:app` device tests AD₀ + 5, of which this task runs `RoomRecordArchiveTest` (4) and `AppGraphTest` (its tests at `a4558faf` + 1).

- [ ] **Step 1: Every device test of `:core:privacy` on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL, D₀ + 8 tests, none failed, 1 skipped (`ConversationArchiveSpeedTest`); the script ends with exit code 0.

- [ ] **Step 2: Before the install, keep older builds off kestrel-api37**

From Step 3 on, Kestrel's database on kestrel-api37 is at version 3, which no build without this plan can open. Check the plan of work in `.superpowers\sdd\progress.md` and in the `main` worktree (`H:\Code\kestrel`), which shares kestrel-api37, and the agents that run there: from now on, nothing may install a build without plan 7C on kestrel-api37, through `scripts\install.ps1`, an `:app` connected test or `scripts\eval.ps1`. If such work is planned, run it first, or put Steps 3 to 5 off until it is done.

In `AGENTS.md`, after the bullet that starts with "kestrel-api37 holds the owner's own Google account", add this bullet:

```markdown
- From plan 7C's Task 8 of sub-project 7 on, Kestrel's database on kestrel-api37 (`kestrel.db`) is at version 3. Install there only builds that contain plan 7C, from any worktree: an older build cannot open the database, and the only way back, a destructive fallback, would delete the owner's profile and settings. The same holds for the A71 from the first plan that installs a build with plan 7C there.
```

Write the same rule into `.superpowers\sdd\progress.md`, with a note to the controller: put the same line into `main`'s AGENTS.md when sub-project 7 is pushed, or at once if work on `main` still uses kestrel-api37. Then commit on `sp3`:

```powershell
git commit -m "docs(agents): kestrel-api37's database is at version 3 from plan 7C on, so that only builds with plan 7C may be installed there" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>" -- AGENTS.md
```

- [ ] **Step 3: Install this build on kestrel-api37, which migrates Kestrel's own database**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; "google accounts before: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; & $adb -s emulator-5554 logcat -c; scripts\install.ps1 -Serial emulator-5554; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; Start-Sleep -Seconds 5; "google accounts after: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; "purge failures: $(@(& $adb -s emulator-5554 logcat -d -s Kestrel:W | Select-String 'Purging past conversations failed').Count)"; $log = & $adb -s emulator-5554 logcat -d; "database errors: $(@($log | Select-String 'A migration from \d+ to \d+|Migration didn.t properly handle|IllegalStateException: Room').Count)"; $crashes = @($log | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }); "kestrel crashes: $($crashes.Count)"; $crashes | ForEach-Object { $_.Line; $_.Context.PostContext }`
Expected: the same number of Google accounts before and after; `profile=copilot` both times (the answer after the install reads the provider setting and its credential from the migrated database, so it shows that the migration kept them and that the data key still opens them); `purge failures: 0`; `database errors: 0` (no missing migration, no failed validation of the migrated schema and no other error of Room); `kestrel crashes: 0`, which counts only a `FATAL EXCEPTION` whose next line is `Process: dev.kestrel,` (Kestrel's own process, not its test packages or another app) and prints those two lines of each, which hold no words of the owner's. Otherwise stop: the owner's profile on kestrel-api37 may need his hands (AGENTS.md), and no build without this plan may be installed to undo the change (Step 2).

- [ ] **Step 4: The `:app` device tests of this plan on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.conversation.RoomRecordArchiveTest,dev.kestrel.app.AppGraphTest'`
Expected: BUILD SUCCESSFUL, every test of the two classes passes; the script ends with exit code 0 and reports no change of Kestrel's install or of the owner's data.

- [ ] **Step 5: The first read's speed on kestrel-api37 and on the A71**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & (Get-Adb) -s emulator-5554 logcat -c; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.ConversationArchiveSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true'; & (Get-Adb) -s emulator-5554 logcat -d -s KestrelArchive:I`
Expected: BUILD SUCCESSFUL, 1 test passed; the line `read 300 conversations of 12 KB in <n> ms`.

When the A71 is on USB (otherwise at the next time it is), run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; "packages before: $(@(& $adb -s R58N66RVD7Z shell pm list packages dev.kestrel) -join ' ')"; & $adb -s R58N66RVD7Z logcat -c; scripts\connected-test.ps1 -Serial R58N66RVD7Z :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.ConversationArchiveSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true'; & $adb -s R58N66RVD7Z logcat -d -s KestrelArchive:I; "packages after: $(@(& $adb -s R58N66RVD7Z shell pm list packages dev.kestrel) -join ' ')"; scripts\gradle.ps1 --stop`
Expected: `scripts\connected-test.ps1` accepts the A71 as a test device; the line `read 300 conversations of 12 KB in <n> ms`, and the test passes; the script ends with exit code 0; the packages after are the packages before (no `dev.kestrel.privacy.test` left). This installs nothing of Kestrel's and changes no setting that the script does not put back.

Write both times into `.superpowers\sdd\progress.md`. This is the one rule that the times feed: design 4.3's 1 s covers the whole first search, and plan 7B's decoding and index come on top of this read, so when the A71's time is above 500 ms, the controller takes design R3's fallback (the search index stored encrypted in a row of its own) to the owner before plan 7D starts. The time on kestrel-api37 is only noted: the emulator's Keystore runs on the PC, so its time neither stops the plan nor calls for the fallback.

---

## Interfaces for plans 7D, 7E and 7F

- `AppGraph.conversationStore: ArchivedConversationStore` is the one store of conversations: plan 7D builds `HistorySearch`, `LocalRequests` and `ConversationEngine` on it, and never makes a second `ArchivedConversationStore` (plan 7B's wiring sketch `ArchivedConversationStore(RoomRecordArchive(…)) { kind -> … }` is this property), so that the snapshots in memory follow every change.
- `AndroidConversationClock` (package `dev.kestrel.app.conversation`) is the `ConversationClock` that plan 7D passes to `HistorySearch`, `GoalRunner`, `LocalRequests` and `ConversationEngine`.
- "Delete history" (plan 7D): `LocalRequests` calls `conversationStore.deleteAll()`, which empties the store's memory and, through `RoomRecordArchive.deleteAll()` and `ConversationArchiveStore.deleteAll()`, deletes every row and then erases the key `kestrel.conversations`, and `LogStore.deleteHistory()` for both logs. The settings button (`SettingsData.deleteHistory()`) and "Kestrel, delete history" go through `LocalRequests` from plan 7D on, never to the archive directly, because the engine must close the open conversation and refuse while a goal is open.
- The purge job runs from Kestrel's start (`KestrelApplication` calls `AppGraph.keepPurgingConversations()`); plan 7F's Conversations page changes `SettingKeys.CONVERSATIONS`, and a change of `keptDays` purges at once.
- `SettingKeys.CONVERSATIONS: SettingKey<ConversationSettings>` (stored as `conversations`) exists from this plan; plan 7D's engine reads it with `settings.observe(SettingKeys.CONVERSATIONS)` and adds no second key.
- The activity log's texts for an unreadable conversation are "A conversation could not be read, so Kestrel deleted it" (the app graph, from plan 7B's `KIND`) and "A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it" (`PrivacyStore`).
- The design's A71 checks (15.2), wherever the controller places them, may run `ConversationArchiveSpeedTest` again with `kestrelArchiveSpeed=true`.

## Counts at the end of plan 7C

- `:core:privacy` JVM: P₀ + 9.
- `:app` JVM: A₀ (unchanged).
- `:core:privacy` device tests: D₀ + 8 (27 when D₀ is 19), one of them skipped without its instrumentation argument.
- `:app` device tests: AD₀ + 5 (448 when AD₀ is 443).
- `:core:conversation`: unchanged.
- Pester: unchanged; this plan changes no script.

## Left open

- **Where the whole first search is measured on the A71.** Design 15.2 lists "the first search over a filled archive" among the A71 checks, and R3 gives plan 7C the measurement. This plan measures the archive's part without installing Kestrel; the whole search (with plan 7B's decoding and index) needs Kestrel on the A71, which the A71 checks of plan 7E do anyway. The controller decides whether plan 7E carries it.
- **No way back for a device's database.** After Task 8, kestrel-api37's database is at version 3: a build without this plan cannot open it. Task 8, Step 2 writes the rule into `sp3`'s AGENTS.md and progress.md; the controller puts it into `main`'s AGENTS.md at the push of sub-project 7, or at once if work on `main` still uses kestrel-api37. The same holds for the A71 once a later plan installs a build with this plan there.

## Self-review

- **Spec coverage.** Design 9.1: the table in `kestrel.db` (Task 1), one row per conversation with the snapshot's JSON as an encrypted payload under `kestrel.conversations` with the associated data `conversations/<id>` (Tasks 2 to 5), the separate key for "delete history" (Tasks 2, 3, 4), the plain fields (Task 1); the snapshot's `version` is plan 7B's. Design 9.2: version 3 with `AutoMigration(from = 2, to = 3)` (Task 1, checked against the real database in Task 8), `ConversationRow` and `ConversationDao` (Task 1), `ConversationArchiveStore` knowing only bytes (Task 3), `RoomRecordArchive` (Task 5), a payload that fails its tag deleted and reported by kind only (plan 7B's store, wired with its text in Task 6; told apart from an unknown format in Tasks 3 and 5), and the section's text (Task 7). Design 9.3: plan 7B's `Retention` and `ConversationStore.purge` choose the range, `deleteOutside` keeps the open conversation (Task 1), and the job runs from Kestrel's start (Task 6). Design 9.7's storage part: rows and key (Tasks 2, 3, 4, 5). Design 5.6's archive lines (Task 6). Design 15.2's store line: the migration with the real Keystore and "delete history" removing rows and key (Tasks 1, 2, 4, 8). R3: Tasks 4 and 8.
- **Placeholders.** Every step shows its code or its command; the generated `3.json` is checked by its version, its columns and its index.
- **Type consistency.** `ConversationHeader(id, openedMillis, lastMillis, open, commands)` and `ConversationRow(id, openedMillis, lastMillis, open, commands, payload)` are the same in Tasks 1, 3, 4, 5 and 7; `ConversationArchiveStore`'s six functions, `PlainConversation` and `StoredConversation`'s three kinds are those that Tasks 4 and 5 use; `ConversationDao.write` is the one that Tasks 3, 4 and 5 call; `KeystoreErasableKey.of(alias)`, its `guard` and `erase()` are those of Tasks 2, 3, 4 and 5; `RecordArchive`'s six functions, `ArchiveEntry`'s three kinds, `ArchivedConversationStore(archive, unreadable)`, `ConversationSnapshot`, `Retention(store, clock, report).keepPurging(days)` and `ConversationSettings.keptDays` in Tasks 5 and 6 are those of plan 7B as committed at `9ddf3385` and changed at `a4558faf`.

