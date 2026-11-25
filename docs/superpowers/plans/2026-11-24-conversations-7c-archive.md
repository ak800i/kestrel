# The Encrypted Archive of Conversations (Plan 7C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Kestrel's conversations on the phone, encrypted under a key of their own that "delete history" erases, in version 3 of Kestrel's database, behind plan 7B's archive port, and purge the old ones while Kestrel runs.

**Architecture:** `:core:privacy` gets the table `conversations` (`ConversationRow`, `ConversationHeader`, `ConversationDao`, whose writes keep at most one conversation open) and, from Task 4b, the table `conversation_index` with the index of past conversations (`ConversationIndexRow`) in version 3 of `KestrelDatabase`, with Room's automatic migration from version 2; the erasable key `kestrel.conversations` (`ErasableKey`, `KeystoreErasableKey`); and `ConversationArchiveStore`, which knows only plain fields and bytes: it encrypts each payload bound to its conversation's id and the index bound to its name, reads the index, the open conversation and the stamp of the closed rows in one transaction and bodies by id, decrypts each read on the thread that ran its query (design R3: a Keystore operation after a hop costs about twice as much on the A71), tells a payload that fails its check from one in a format that this version cannot read, keeps and reports the latter by its kind only, and erases its key with its rows and its index. `PrivacyStore.conversations` builds it. In `:app`, `RoomRecordArchive` implements plan 7B's `RecordArchive` on it, `AndroidConversationClock` is the conversation's clock, and `AppGraph` builds plan 7B's `ArchivedConversationStore` with the app's scope, for a rebuild of the index, and writes what it notes (a deleted conversation, a rebuilt index) into the activity log by its kind, and plan 7B's `Retention`, which `KestrelApplication` runs for the life of the process. The design's section 9.2 is brought in line with the result.

**Tech Stack:** Kotlin 2.4.21 on Android (minSdk 33, target 37), Room 2.8.5 with KSP 2.3.13, Android Keystore, kotlinx.coroutines 1.11.0 with `kotlinx-coroutines-test`, kotlinx.serialization 1.13.0, JUnit 4.13.2, AndroidX Test (runner 1.7.0, ext-junit 1.3.0), `androidx.room:room-testing`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. No new library.
- The owner's hard rule (AGENTS.md): no workarounds and no smallest-change fixes; clean code, good architecture and modularity, even when that means a larger change. The owner's criterion of 2026-11-24: "best = best code quality. Don't worry about time." A step that would patch a symptom at the nearest line is a fault of the plan: stop and ask the controller.
- The approved design is `docs\superpowers\specs\2026-11-24-conversations-design.md` (commit `75dd098b`, with the controller's later changes), called "the design" below; its decisions D1 to D24 bind this plan. This plan is the design's plan 7C (section 16): its sections 5.1 (what `:core:privacy` gets), 5.6 (the archive's lines of `AppGraph`), 9.1, 9.2, 9.3, the storage part of 9.7, R3 and the store's line of 15.2. The main spec is `docs\superpowers\specs\2026-11-12-kestrel-design.md`; plan 7.0 already rewrote its sections 13.4 and 13.5 for this archive.
- `:core:privacy` knows only encrypted payloads and their plain fields (design 5.1): no type of `:core:conversation` enters it, and `:core:conversation` never depends on `:core:privacy`. Only `:app` sees both.
- The archive's key is `kestrel.conversations`, an AES-256 key of Android Keystore that never leaves it and is never made in StrongBox. A payload is AES-GCM with the associated data `conversations/<id>` (design 9.1).
- Only these fields of a conversation are plain: its id, its open and last-change times, whether it is open, and its number of commands (design 9.1). At most one conversation is open (design 6.1). The index of past conversations (design 9.2) is the one row of the table `conversation_index`, encrypted under the same key with the associated data `conversation_index/past`; it has no plain field.
- An unreadable conversation is reported to the activity log by its kind only (design 9.2): never a conversation's id, words or bytes. One whose payload fails its check is deleted by plan 7B's `ArchivedConversationStore`, which reports it; one in a format that this version cannot read is kept by this plan's `ConversationArchiveStore`, which reports it once per process. Nothing in this plan logs, prints or puts into a test's output a conversation's text; the device tests use made-up texts only.
- "Delete history" deletes every conversation row, the open one too, and the index, and then erases the key, so that no copy of the database file can be read afterwards; the next write makes a new key (design 9.7).
- Run Gradle only through `scripts\gradle.ps1`, one build at a time; when the controller's brief says that agents run in parallel, take `.superpowers\sdd\gradle.lock` in `H:\Code\kestrel` before each Gradle command as the brief says, and do not run `scripts\gradle.ps1 --stop` beside another Gradle user. Otherwise stop Gradle with `scripts\gradle.ps1 --stop` at the end of each task that builds. `scripts\gradle.ps1` refuses every task that installs, uninstalls or runs tests on a device. Install only through `scripts\install.ps1`, and run every device test only through `scripts\connected-test.ps1`, which keeps Kestrel and its data and checks the owner's data on kestrel-api37 itself.
- Device steps: Tasks 1, 2, 4 and 4b run `:core:privacy`'s device tests on kestrel-api37; they install only the library's own test app, `dev.kestrel.privacy.test`, whose Keystore and database are not Kestrel's, and `scripts\connected-test.ps1` uninstalls it afterwards. Every `:app` device test and every install run only in Task 8, which is the controller's. On kestrel-api37 each device command starts with the emulator's check, written without the word throw: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }`. If the check fails, or no emulator runs, start it with `scripts\start-emulator.ps1`, check the serial that it prints, and use that serial. Never use the AVD `zoran`, the S23+ or the owner's Xiaomi tablet.
- kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile: no step uninstalls Kestrel there, clears its data, or signs an account in or out. Task 8's install checks before and after it that `dumpsys account` counts the same Google accounts and that `scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin` answers `profile=copilot`.
- The A71 (`R58N66RVD7Z`) is used once, in Task 8, for `:core:privacy`'s speed test only, within AGENTS.md's limits: it installs only `dev.kestrel.privacy.test`, which `scripts\connected-test.ps1` removes afterwards, and touches no account, no call, no message and no setting that the script does not put back.
- The JVM tests run on CI's Ubuntu too: no JVM test needs Windows, a device, a font or a network.
- The repository uses LF line endings.
- This plan is `.superpowers/sdd/plan-7c.md`; the controller commits it as `docs/superpowers/plans/2026-11-24-conversations-7c-archive.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

Code comments name this plan "plan 7C" and the design's sections as "design 9.2 of sub-project 7".

## The order of plans 7B and 7C

Plan 7C merges **after** plan 7B. Its Tasks 1 to 4b may run while plan 7B is carried out or reviewed; its Tasks 5 to 8 start only after plan 7B is merged, with its Tasks 5a to 5d.

- The port `RecordArchive` belongs to the code that uses it, `:core:conversation`'s store (design 5.2). Plan 7B defines it and tests `ArchivedConversationStore` against its fake, `MemoryArchive`; this plan implements it. That is the direction of every port in Kestrel: `:core:agent`'s ports are implemented in `:app`, after the port exists.
- Merging this plan first would need the port somewhere else: in `:core:privacy`, which may know no conversation type (design 5.1), or in a new module that holds one interface only so that a plan can merge early. Both bend the architecture to the schedule, which the owner's hard rule forbids.
- Tasks 1 to 4b neither use the port nor touch a file of plan 7B (they change only `core/privacy`), so they can run beside plan 7B, as design 16 allows ("7C can run beside 7B"). Tasks 5 and 6 are `:app`'s adapter and wiring, which need plan 7B's types; Task 7 writes the result into the design; Task 8 checks everything on the devices.

## Before you start

- Sub-project 7 starts only after plan 6B-basefix (`docs\superpowers\plans\2026-11-24-eval-6b-basefix.md`) is pushed. This plan was written against `sp3` at `a4558faf`, which holds plan 7B as committed at `9ddf3385` and changed at `a4558faf` (`docs\superpowers\plans\2026-11-24-conversations-7b-core.md`); plan 7B's port changed again at `26737eb7` (see "Plan 7B as committed"). Of the files that this plan changes, only `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` and the design may move before Task 5.
- At the start of Task 1, and again at the start of Task 5, run the plan checker against the branch's HEAD, in PowerShell 7: `$env:PLANX = 'H:\Code\kestrel-sp3\docs\superpowers\plans\2026-11-24-conversations-7c-archive.md'; $env:BASE_REF = (git -C H:\Code\kestrel-sp3 rev-parse HEAD); $env:S3A = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:OUTDIR = 'plan7c-sim'; python 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\plan3x-check.py'`. For Task 5, also set `$env:START_TASK = '5'`. Expected: an `operations:` line, a `files:` line, a `simulated files written:` line, and no line that starts with `PROBLEM:`. If a `PROBLEM:` line names a text that plan 6B-basefix, 7A or 7B moved or changed, stop and ask the controller: the controller rebases the plan (it changes the plan's old texts to the texts that the earlier plans left, keeps each change's meaning, and adjusts a new text that depends on them), runs the checker again until it is clean, and only then gives the task.
- At the start of Task 5, check that plan 7B as merged has the types of "Interfaces consumed from plan 7B" below, with these signatures: `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\store\*.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationClock.kt -Pattern 'interface RecordArchive|suspend fun readShards\(\): List<ShardRead>|suspend fun write\(change: ArchiveChange\)|class Unknown\(|class ArchivedConversationStore\(|class Retention\(|suspend fun keepPurging\(days: Flow<Int>\)|interface ConversationClock'`. Expected: 8 matches. If one is missing or differs, stop and ask the controller. Also check that plan 7B's store reads `open` from the row (see "Plan 7B as committed"): `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\store\ArchivedConversationStore.kt -SimpleMatch -Pattern 'copy(open = header.open, openedAtWall = header.openedMillis, lastWall = header.lastMillis)'` gives 1 match, and `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\model\ConversationSnapshot.kt -SimpleMatch -Pattern '@Transient val open'` gives 1 match. Otherwise stop and ask the controller, because Task 5's `theTablesOpenColumnDecidesWhichConversationIsOpen` tests it.
- Measure the counts before the first change of Task 1, and note them in the task's report: run `scripts\gradle.ps1 :core:privacy:testDebugUnitTest :app:testDebugUnitTest`, then `foreach ($dir in 'core\privacy\build\test-results\testDebugUnitTest', 'app\build\test-results\testDebugUnitTest') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `foreach ($dir in 'core\privacy\src\androidTest', 'app\src\androidTest') { "$dir $(@(Get-ChildItem $dir -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)" }`, then `scripts\gradle.ps1 --stop`. These are P₀ (`:core:privacy` JVM), A₀ (`:app` JVM, with `app/src/testDebug`), D₀ (`:core:privacy`'s device tests, 19 at `a4558faf`) and AD₀ (`:app`'s device tests, 443 at `a4558faf`). Each task gives its counts as "N + k", where N is the count before the task. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file as the earlier tasks left it; and run `git status --short`. The worktree may hold other agents' uncommitted files. If a text is missing, or a file that the task changes shows changes that are not the task's own, stop and ask the controller.
- A device that runs a build with Task 1 has its database at version 3. Room cannot open it with an older build (there is no migration down), and a destructive fallback would delete the owner's profile. So from Task 8 on, never install a build without this plan on kestrel-api37, and never install one on the A71 after a later plan has brought this plan there. Task 8, Step 2 writes this rule into AGENTS.md, so that work from the `main` worktree, which shares kestrel-api37, keeps it too.

## Interfaces consumed from plan 7B

Tasks 5 and 6 use these types of `:core:conversation`, as plan 7B (committed at `9ddf3385`, changed at `a4558faf`) defines them in its "Interfaces for 7C and 7D":

- package `dev.kestrel.conversation.store`:
  - `interface RecordArchive { suspend fun readOpen(): ArchiveEntry?; suspend fun readShards(): List<ShardRead>; suspend fun readStamp(): IndexStamp; suspend fun read(ids: Collection<String>): List<ArchiveEntry>; suspend fun closedIds(): List<String>; suspend fun write(change: ArchiveChange); suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int; suspend fun deleteAll() }` (plan 7B's Task 5d): `readOpen()` gives the open conversation's row as its kind of entry, or null; `readShards()` gives the index's shards (`ShardRead.Readable(name, bytes)` or `ShardRead.Unreadable(name)`, none when there is no index); `readStamp()` gives the stamp of the closed rows, `IndexStamp(closed, newest, total)`; `read(ids)` gives the rows that exist; `closedIds()` gives the closed rows' ids, the oldest `lastMillis` first, for a rebuild that reads them in pages; `write(ArchiveChange(rows, deleted, index))` deletes, inserts or replaces, and keeps (`IndexWrite.Keep`), changes (`IndexWrite.Update(put, deleted)`) or replaces (`IndexWrite.Replace(shards)`) the shards, each by its name, all in one transaction; `purge(oldest, newest, index)` deletes the closed rows outside the window by the table's own times, with the shards' change, in one transaction, and gives how many went; `deleteAll()` erases the key `kestrel.conversations` and then deletes every row, the open one too, and every shard.
  - `data class ArchiveHeader(val id: String, val openedMillis: Long, val lastMillis: Long, val open: Boolean, val commands: Int)`; `class ArchiveRow(val header: ArchiveHeader, val payload: ByteArray)` (the payload is the snapshot's JSON in UTF-8).
  - `sealed interface ArchiveEntry { val header: ArchiveHeader }` with `class Readable(header, val payload: ByteArray)`, `class Unreadable(header)` (the payload failed its check: the store deletes the row and its index entry and notes `ArchiveNote.CONVERSATION_DELETED`) and `class Unknown(header)` (the payload is in a format that this version cannot read: the store leaves the conversation out, keeps the row and counts it in the index; the archive has reported it).
  - `class ArchivedConversationStore(archive: RecordArchive, scope: CoroutineScope, noted: (ArchiveNote) -> Unit) : ConversationStore`, with `DAY_MILLIS` and `enum class ArchiveNote { CONVERSATION_DELETED, INDEX_REBUILT, INDEX_FAILED }`; at its first use it reads the open row alone and starts reading the index in `scope`, keeps the index and the open conversation in memory, reads a past conversation's body only when it is asked for, rebuilds a missing, unreadable, unknown or out-of-step index page by page in `scope`, tries a failed one again later, and takes whether a conversation is open, and its times, from the row's header, the table's columns: `ConversationSnapshot.open` is `@Transient`, so the snapshot's JSON never holds it.
  - `class Retention(store: ConversationStore, clock: ConversationClock, report: (String, Throwable) -> Unit)` with `suspend fun purge(days: Int): Int` and `suspend fun keepPurging(days: Flow<Int>)`, which purges at once and at each change of `days`, and checks the wall clock every hour and purges once a day has passed or the clock went back; a failure goes to `report` and the job goes on.
- package `dev.kestrel.conversation.engine`: `interface ConversationClock { fun wallMillis(): Long; fun zone(): ZoneId }`.
- package `dev.kestrel.conversation.model`: `@Serializable data class ConversationSettings(val quietSeconds: Int = 60, val followUpSeconds: Int = 8, val speakTypedReplies: Boolean = false, val retentionDays: Int = 30, val limits: GoalLimits = GoalLimits())`, whose read properties `keptDays` (the retention within `RETENTION_CHOICES`, 1 to 365 days), `quietMillis`, `followUpMillis` and `roundLimits` are what Kestrel reads, never the raw fields: a damaged stored value is read within its bounds.
- Plan 7B adds no dependency of `:app` on `:core:conversation`, no setting key and no `AndroidConversationClock`; Tasks 5 and 6 add them.

## Plan 7B as committed

Plan 7B as committed at `9ddf3385` has what this plan needs from it: `ArchiveEntry.Unknown`, which its store leaves out without deleting the row; the KDoc lines that name plan 7C as the plan that runs `Retention` and makes `AndroidConversationClock`; and `Retention`'s hourly check of the wall clock.

Plan 7B's Tasks 5a to 5d (2026-11-25 and 2026-11-26, design 9.2 and R3) give the port and the store the index of past conversations in shards that this plan's Task 4b stores, with `readOpen`, `readShards`, `readStamp`, `read(ids)`, `closedIds`, `write(ArchiveChange)` and `purge`; the port's `delete`, `deleteOutside` and `readAll` and the store's `KIND` go. Plan 7B's commit `a4558faf` follows this plan's review (its finding I4): the table's `open` column is the one truth about whether a conversation is open. `ConversationSnapshot.open` is `@Transient`, so the snapshot's JSON never holds it; `ArchivedConversationStore.decoded` takes it from the entry's header (`copy(open = entry.header.open)`); and plan 7B's `ArchivedConversationStoreTest.aConversationIsOpenOnlyWhenItsRowSaysSo` checks a row that is closed in its header while an older payload still says open.

Plan 7B's commit `26737eb7` makes the store's first use one call to the archive: the A71 read 300 conversations of 12 KB in 14.9 s, of which 300 queries of one row each took 1.8 s, where one query of the same rows took 23 ms. `RecordArchive.readAll()` replaced `headers()` and `read(id)`, which had no other caller; this plan's `ConversationDao.all()` and `ConversationArchiveStore.readAll()` replaced their `headers()` and `get`/`read` for the same reason, and `ConversationArchiveSpeedTest` measured `readAll` in a new store. Task 4b replaces them in turn by `closedIds()` and reads in pages, because a rebuild must never hold every body at once (365 days of retention would be about 150 MB). The Keystore's decryption of each row is the owner's later decision; this change leaves the cipher as it is. A later difference from plan 7B goes into `progress.md`.

## Not in this plan

- The conversation's JSON, its `version`, the index of past conversations' content and format (`PastIndex`, `PastIndexCodec`), its rebuild, `HistorySearch` and the deletion of a conversation that fails its check or does not decode (plan 7B): this archive stores bytes and never parses them.
- The owner's "delete history" on the screen and by voice (plan 7D, design 16's row "\"delete history\" extended"): the question "Delete the activity log, the network log and past conversations?", the close of the open conversation, the refusal while a goal is open, and the routing of the settings button and of "Kestrel, delete history" through plan 7B's `LocalRequests`, which calls `ArchivedConversationStore.deleteAll()` (which this plan's archive turns into the deletion of every row and the erasure of the key) and `LogStore.deleteHistory()`. Until plan 7D, nothing writes a conversation, so today's "delete history" leaves no conversation behind.
- The Conversations settings page and its "Delete all past conversations" (plan 7F). This plan adds the setting's key only, for the purge job.
- The first search over 30 days on the A71 from end to end (design 4.3 and 15.2): this plan measures the archive's share of it, the decryption through the Keystore, which is design R3's risk (Task 4 and Task 8). The whole search, with plan 7B's decoding and index, belongs to the A71 checks of design 15.2, which the controller places (see "Left open").

## Changes to the design that this plan makes

The controller accepted these on 2026-11-24; Task 7 writes them into the design's sections 9.1 and 9.2.

- **The table decides which conversation is open.** The design's `ConversationDao` is an interface with `@Upsert put`, `openOne`, `closedSince` and `closeAll()`. Here it is an abstract class whose `write` stores all the rows in one transaction and, for each open row, closes every other open row first. The table's `open` column is the one truth about whether a conversation is open: plan 7B's store takes `open` from a row's header, and the snapshot's JSON does not hold it (see "Plan 7B as committed"). So "at most one conversation is open" (design 6.1) holds for everything that reads the archive, whatever its callers write, also for a row that plan 7B's store cannot read and so does not know. Its plain `@Upsert` and `UPDATE` queries are protected. `closedSince` and `closeAll` are not added: plan 7B's store keeps the closed conversations in its index and closes a conversation by writing its snapshot as closed.
- **The index of past conversations in a table of its own (Task 4b, design 9.2 and R3 as amended on 2026-11-25 and 2026-11-26).** On the A71, reading and decrypting every conversation at the store's first use took 13.8 s, and still 5.2 s in one query. So version 3 also holds the table `conversation_index`, whose rows are the index's shards, each encrypted under the same key and bound to its name; a table of its own keeps every query of `conversations` plain. The DAO's functions block, so that `ConversationArchiveStore` runs a query and the decryption of its rows on one thread: `open()` (a restore), `shards()`, `stamp()` (of the closed rows), `rows(ids)`, `closedIds()` (for a rebuild in pages), `write(rows, deleted, index)`, `purge(oldest, newest, index)` and `deleteAll()`. A restore is one Keystore operation, the index one per shard, and each body that a caller asks for one more.
- **What `ConversationArchiveStore` offers.** The design lists "encrypt, decrypt, put, headers, get, delete, purge". Here it offers exactly what plan 7B's port needs: `open()`, `shards()`, `stamp()`, `read(ids)`, `closedIds()`, `write(ConversationChange)`, `purge(oldest, newest, IndexUpdate)` and `deleteAll()`. Encryption and decryption stay inside, so no caller handles a ciphertext. The plain fields travel as the table's own `ConversationHeader`, and the stamp as `ConversationStamp`. Its reads take no lock, because they store nothing, and stop between two rows when their caller is cancelled; its writes, its purge and `deleteAll` take the key's guard. `deleteAll` erases the key first and then deletes the rows, so that a deletion cut short leaves nothing that a key opens.
- **Two kinds of unreadable conversation.** Every read tells a payload that fails its check (`FailedCheck`, which plan 7B's store deletes and reports) from one in a format that this version cannot read (`UnknownFormat`, which is kept for a later version and reported once per process by the archive, as `SettingsStore` and `CredentialStore` do). The activity log's texts are "A conversation could not be read, so Kestrel deleted it" (from plan 7B's `ArchiveNote.CONVERSATION_DELETED`) and "A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it".
- **The key is erasable and never in StrongBox.** `ErasableKey` (`guard`, `get()`, `withExisting`, `erase()`) is the key's own type: only a write's `get()` makes the key, a read decrypts with `withExisting`, which never makes it, and `erase()` waits for a read that decrypts with it. `KeystoreErasableKey.of(alias)` gives one key object per alias and process, by construction: it keeps the Keystore key after its first use, as `PrivacyStore`'s `by lazy` keys do, until `erase()` deletes it from Keystore, so that no part of the process, not even a second `PrivacyStore`, keeps a key that another erased. `KeystoreKeys` gains `delete(alias)`. StrongBox's slower operations would make the first read of hundreds of conversations slow (R3), and the data key is not in StrongBox either.
- **One lock for the key.** Every write of `ConversationArchiveStore` and its `deleteAll` run under the key's own lock, `ErasableKey.guard`, which is process-wide with the key, so that "delete history" can never erase the key between the encryption of a write and its row, which would leave a row that no key opens, also not from a second `PrivacyStore` of the same process. From Task 4b on, its reads take no lock, because they store nothing: a rebuild's read of every row, about 5 s on the A71 for 30 days, never holds up a save. A read that overlaps "delete history" never fails because the key went and never makes a new one: it sees a row as it was, or, once the key is erased, as failing its check (a shard as unreadable), or, once the rows are deleted, not at all; plan 7B's store orders the two, and drops a rebuild that "delete history" overtook.
- **The purge job runs for the life of the process.** `KestrelApplication` starts plan 7B's `Retention` in `AppGraph.scope`. Unlike the logs' purge, which the accessibility service keeps, it runs with the service on or off, as design 9.3 says ("while the process runs").
- **The archive's speed is measured in `:core:privacy`.** The device test `ConversationArchiveSpeedTest` writes 300 closed conversations of 12 KB, an open one and an index in two shards of 240 and 210 KB to a database file, and times, each through a new store as after a start of Kestrel, what plan 7B's store does: the restore (`open()`), the restore and the index (`shards()` and `stamp()`), those and the bodies of a search's 5 best matches (`read(ids)`), every closed conversation in pages of 50, as a rebuild reads them (`closedIds()` and `read(page)`), and a close (`write` of a conversation and one shard). It runs only with the instrumentation argument `kestrelArchiveSpeed=true`, so that the routine runs stay quick. It is a measurement: it fails only when something does not read back, and it logs the times under the tag `KestrelArchive`. Two rules decide, in Task 8: the restore, the index and 5 bodies above 500 ms on the A71, the archive's share of design 4.3's 1 s, go to the owner, and so does a close above 500 ms; the emulator's times and the rebuild's read are only noted. It needs no install of Kestrel, so it can run on the A71.

## File structure

| File | Responsibility |
|---|---|
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt` (new), `db/KestrelDatabase.kt`, `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json` (new, generated); tests `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/` `KestrelDatabaseTest.kt`, `KestrelDatabaseMigrationTest.kt` (new), `CredentialStoreTest.kt` | Task 1: the table `conversations` in version 3 of the database |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt`, `crypto/ErasableKey.kt` (new), `crypto/FieldCipher.kt`; tests `KeystoreCipherTest.kt`, `AesGcmFieldCipherTest.kt` | Task 2: the key `kestrel.conversations`, which "delete history" erases |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt` (new); test `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt` (new) | Task 3: the archive that knows only bytes |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt`; tests `PrivacyStoreTest.kt`, `ConversationArchiveSpeedTest.kt` (new) | Task 4: `PrivacyStore.conversations`, its report of a kept conversation, and the first read's speed |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt`, `db/KestrelDatabase.kt`, `conversations/ConversationArchiveStore.kt`, `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json` (generated again); tests `ConversationArchiveStoreTest.kt`, `KestrelDatabaseTest.kt`, `KestrelDatabaseMigrationTest.kt`, `PrivacyStoreTest.kt`, `ConversationArchiveSpeedTest.kt` | Task 4b: the index of past conversations in shards of a table of its own, and reads that decrypt on one thread |
| `app/build.gradle.kts`, `app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt` (new); test `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt` (new) | Task 5: plan 7B's port on the archive |
| `app/src/main/kotlin/dev/kestrel/app/conversation/AndroidConversationClock.kt` (new), `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`; test `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt` | Task 6: the store, its report, the clock and the purge job in the app graph |
| `docs/superpowers/specs/2026-11-24-conversations-design.md` (a check; the amendment of 2026-11-25 wrote it) | Task 7: design 9.1 and 9.2 as built |
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
- Produces: package `dev.kestrel.privacy.db`: `class ConversationRow(id: String, openedMillis: Long, lastMillis: Long, open: Boolean, commands: Int, payload: ByteArray)` (table `conversations`, columns `id`, `opened_millis`, `last_millis`, `open`, `commands`, `payload`, index on `last_millis`); `data class ConversationHeader(val id: String, val openedMillis: Long, val lastMillis: Long, val open: Boolean, val commands: Int)` (a row's plain fields, which the archive takes and gives; no query reads it, because the first read needs whole rows); `abstract class ConversationDao` with `open suspend fun write(rows: List<ConversationRow>)` (one transaction; each open row closes every other open row first), `suspend fun all(): List<ConversationRow>` (every row in one query, newest `last_millis` first), `suspend fun delete(id: String): Int`, `suspend fun deleteOutside(oldest: Long, newest: Long): Int` (closed rows only), `suspend fun deleteAll(): Int`, and the protected `upsert(row)` and `closeOthers(id): Int`; `KestrelDatabase.conversationDao(): ConversationDao` at version 3 with `AutoMigration(from = 2, to = 3)`. Task 3 uses the DAO; Task 3's fake DAO overrides its abstract functions and keeps its `write`.

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
import java.io.File

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
        val context = instrumentation.targetContext
        context.deleteDatabase(MIGRATED)
        // Room's lock file beside the database, which deleteDatabase leaves.
        File("${context.getDatabasePath(MIGRATED).path}.lck").delete()
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

        assertEquals(listOf("b" to true, "a" to false), dao.all().map { it.id to it.open })
    }

    // Design 9.6 of sub-project 7: a switch writes the closed and the opened conversation in one transaction.
    @Test
    fun oneWriteStoresEveryRowAndLeavesOnlyItsOpenRowOpen() = runBlocking {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = false), conversation("b", last = 2, open = true)))

        dao.write(listOf(conversation("b", last = 3, open = false), conversation("a", last = 3, open = true)))

        val rows = dao.all()
        assertEquals(setOf("a" to true, "b" to false), rows.map { it.id to it.open }.toSet())
        assertEquals(3L, rows.single { it.id == "b" }.lastMillis)
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
        val rows = dao.all()
        assertEquals(listOf("last", "first", "open"), rows.map { it.id })
        assertEquals(listOf<Any>(199L, 200L, false, 2), rows.first().let { listOf(it.openedMillis, it.lastMillis, it.open, it.commands) })
    }

    private fun conversation(id: String, last: Long, open: Boolean) =
        ConversationRow(id, openedMillis = last - 1, lastMillis = last, open = open, commands = 2, payload = byteArrayOf(1))
```

- [ ] **Step 2: Compile the device tests to see them fail**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports `Unresolved reference 'ConversationRow'` and `Unresolved reference 'conversationDao'`.

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
 * The plain fields of a conversation's row, without its payload, as the archive takes and gives them. Its [open] is the
 * one truth about whether the conversation is open (design 6.1 of sub-project 7): the conversation's store reads it from
 * here; the payload does not hold it.
 */
data class ConversationHeader(
    val id: String,
    val openedMillis: Long,
    val lastMillis: Long,
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

    /**
     * Every conversation's row, the newest last change first, in one query: the first read of the archive (design 4.3 and
     * R3 of sub-project 7), which one query per row would make many times slower.
     */
    @Query("SELECT * FROM conversations ORDER BY last_millis DESC")
    abstract suspend fun all(): List<ConversationRow>

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

The archive's key is a key of its own (design 9.1), which "delete history" erases with the rows (crypto-shredding, design 9.7): once it is gone, no copy of the database file can be read. The next write makes a new key. A read never makes one: it decrypts with the key as it is (`ErasableKey.withExisting`), and the key's erasure waits for a decryption that has started, so that a read after "delete history" leaves no key behind and a read beside it never meets its key deleted in Keystore. `AesGcmFieldCipher` takes a key that may be missing: a value in the known format then fails its check, as under any other wrong key.

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/ErasableKey.kt`
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt`, `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/FieldCipher.kt`
- Test: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KeystoreCipherTest.kt`, `core/privacy/src/test/kotlin/dev/kestrel/privacy/crypto/AesGcmFieldCipherTest.kt`

**Interfaces:**
- Consumes: `KeystoreKeys.getOrCreate(alias: String, strongBox: Boolean = false): SecretKey`; `AesGcmFieldCipher(key: () -> SecretKey)`.
- Produces: `KeystoreKeys.CONVERSATIONS_KEY = "kestrel.conversations"`; `KeystoreKeys.find(alias: String): SecretKey?`, which never makes a key; `KeystoreKeys.delete(alias: String)`; `AesGcmFieldCipher(key: () -> SecretKey?)`, whose `decrypt` throws `AEADBadTagException` when there is no key (after its check of the format, so that a later format still throws `IllegalArgumentException`) and whose `encrypt` throws `IllegalStateException` then; package `dev.kestrel.privacy.crypto`: `interface ErasableKey { val guard: Mutex; fun get(): SecretKey; fun <T> withExisting(block: (SecretKey?) -> T): T; fun erase() }`, where `get()` makes the key for a write and `withExisting` runs a read's decryption with the key or null, never makes one, and holds off `erase()` until it returns, and `class KeystoreErasableKey private constructor(alias: String) : ErasableKey` with `KeystoreErasableKey.of(alias: String): KeystoreErasableKey`, one instance per alias and process. Task 3 takes an `ErasableKey` and runs under its `guard`; Task 4 builds `KeystoreErasableKey.of(KeystoreKeys.CONVERSATIONS_KEY)`; the device tests of Tasks 4 and 5 clean up with `KeystoreErasableKey.of(alias).erase()`.

Counts: `:core:privacy` JVM N + 1 (`AesGcmFieldCipherTest` + 1). `:core:privacy` device tests N + 3 (`KeystoreCipherTest` + 3).

- [ ] **Step 1: Write the failing tests**

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    // Design 9.7 of sub-project 7: only a write makes the key, so that a read after "delete history" leaves no key behind.
    @Test
    fun aReadUsesTheKeyOnlyWhenItExistsAndNeverMakesIt() {
        val key = KeystoreErasableKey.of(alias)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        assertNull(key.withExisting { it })
        assertFalse(keyStore.containsAlias(alias))
        key.get()
        assertNotNull(key.withExisting { it })
        key.erase()

        assertNull(key.withExisting { it })
        assertFalse(keyStore.containsAlias(alias))
    }

    // Without the wait, the key could be deleted from Keystore while a read decrypts with it, which would then fail with
    // Keystore's exception instead of reading the value. The pause can only let a wrong order pass, never fail a right one.
    @Test
    fun anErasureWaitsForAReadThatUsesTheKey() {
        val key = KeystoreErasableKey.of(alias)
        val sealed = AesGcmFieldCipher { key.get() }.encrypt("hello".encodeToByteArray(), place)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val reading = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<String> {
                key.withExisting { secret ->
                    reading.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                    AesGcmFieldCipher { secret }.decrypt(sealed, place).decodeToString()
                }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val erasing = Thread { key.erase() }.apply { start() }
            erasing.join(200)
            assertTrue(erasing.isAlive)
            assertTrue(keyStore.containsAlias(alias))

            gate.countDown()

            assertEquals("hello", read.get(5, TimeUnit.SECONDS))
            erasing.join(5_000)
            assertFalse(erasing.isAlive)
            assertFalse(keyStore.containsAlias(alias))
        } finally {
            gate.countDown()
            executor.shutdown()
        }
    }
}
```

In `core/privacy/src/test/kotlin/dev/kestrel/privacy/crypto/AesGcmFieldCipherTest.kt`, replace:

```kotlin
    @Test
    fun emptyValueRoundTrips() {
```

with:

```kotlin
    // Design 9.7 of sub-project 7: once a key is erased, a value fails its check, and a later format is still told apart.
    @Test
    fun withoutAKeyAValueFailsItsCheckAndNothingIsEncrypted() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        val keyless = AesGcmFieldCipher { null }

        assertThrows(AEADBadTagException::class.java) { keyless.decrypt(sealed, place) }
        sealed[0] = 2
        assertThrows(IllegalArgumentException::class.java) { keyless.decrypt(sealed, place) }
        assertThrows(IllegalStateException::class.java) { keyless.encrypt("hello".encodeToByteArray(), place) }
    }

    @Test
    fun emptyValueRoundTrips() {
```

- [ ] **Step 2: Compile the tests to see them fail**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`
Expected: FAIL, the compiler reports `Null cannot be a value of a non-null type 'SecretKey'`.

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports `Unresolved reference 'KeystoreErasableKey'`.

- [ ] **Step 3: Add the erasable key and a cipher that takes a missing key**

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
        find(alias)?.let { return it }
        if (strongBox) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // The device announced StrongBox but cannot make this key there; it is made without StrongBox below.
            }
        }
        return generate(alias, strongBox = false)
    }

    /** Returns the key [alias], or null when it does not exist; never makes one. */
    @Synchronized
    fun find(alias: String): SecretKey? = KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as SecretKey?

    /**
     * Deletes the key [alias] when it exists, so that nothing that it encrypted can be read again (crypto-shredding, design
     * 9.7 of sub-project 7). The next [getOrCreate] makes a new key; [find] finds none.
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
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.SecretKey
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A key that can be erased (crypto-shredding): once it is erased, nothing that it encrypted can be read again, also not
 * from a copy of the database file. Only a store of data makes a new key, at its next use (design 9.7 of sub-project 7);
 * a read never makes one, so that a read after the erasure leaves no key behind.
 */
interface ErasableKey {
    /**
     * The lock that every store of data under this key and the key's erasure take, so that nothing is stored under a key
     * that is being erased. It belongs to the key, so every user of the key shares it.
     */
    val guard: Mutex

    /** The key to store data with, under [guard]; a new one when there is none. */
    fun get(): SecretKey

    /**
     * Runs [block] with the key, or with null when there is none, and never makes one: what a read decrypts with. [erase]
     * waits until [block] has returned, so that the key is never deleted while [block] uses it. [block] must not call
     * [get] or [erase].
     */
    fun <T> withExisting(block: (SecretKey?) -> T): T

    /** Erases the key, once no [withExisting] uses it. Erasing a key that is already erased does nothing. */
    fun erase()
}

/**
 * An [ErasableKey] in Android Keystore (design 9.1 of sub-project 7). [of] gives one instance per alias and process, so
 * that every user of the alias shares its [guard] and none keeps a key that another one erased. It keeps the key after
 * its first use, as PrivacyStore's other keys are kept, until [erase] deletes it from Keystore. It is never made in
 * StrongBox, whose slower operations would make the first read of hundreds of conversations slow, as the data key is not.
 *
 * Reads share [use]'s read lock, so that many decrypt at once; [get] and [erase] take its write lock, so that the key is
 * never deleted from Keystore while a read decrypts with it, which would fail with Keystore's exception.
 */
class KeystoreErasableKey private constructor(private val alias: String) : ErasableKey {
    override val guard = Mutex()

    private val use = ReentrantReadWriteLock()

    // Written under use's write lock, or under its read lock with the key that Keystore holds then, which no erasure can
    // delete meanwhile.
    @Volatile
    private var key: SecretKey? = null

    override fun get(): SecretKey = use.write { key ?: KeystoreKeys.getOrCreate(alias).also { key = it } }

    override fun <T> withExisting(block: (SecretKey?) -> T): T = use.read { block(key ?: KeystoreKeys.find(alias)?.also { key = it }) }

    override fun erase() {
        use.write {
            key = null
            KeystoreKeys.delete(alias)
        }
    }

    companion object {
        private val keys = HashMap<String, KeystoreErasableKey>()

        /** The key object of [alias] in this process. */
        @Synchronized
        fun of(alias: String): KeystoreErasableKey = keys.getOrPut(alias) { KeystoreErasableKey(alias) }
    }
}
```

Replace the whole `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/FieldCipher.kt` with:

```kotlin
package dev.kestrel.privacy.crypto

import javax.crypto.AEADBadTagException
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
 *
 * [key] gives the key at each use, or null when there is none, such as an erased key (design 9.7 of
 * sub-project 7): then a value in the known format fails its check, as under any other wrong key, and
 * nothing can be encrypted.
 */
class AesGcmFieldCipher(private val key: () -> SecretKey?) : FieldCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore keys refuse caller-chosen IVs, so the cipher picks a random one.
        cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key()) { "No key to encrypt with" })
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        // Keystore would report a too-short value as IllegalBlockSizeException, which also signals temporary failures.
        require(ciphertext.size >= HEADER_BYTES + TAG_BYTES && ciphertext[0] == VERSION) { "Unknown ciphertext format" }
        val key = key() ?: throw AEADBadTagException("No key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 1, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, HEADER_BYTES, ciphertext.size - HEADER_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val HEADER_BYTES = 1 + IV_BYTES
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :core:privacy:compileDebugAndroidTestKotlin :core:privacy:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, P₀ + 1 tests.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.KeystoreCipherTest'; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL, 5 tests, none failed; the script ends with exit code 0.

- [ ] **Step 5: Commit**

```powershell
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/ErasableKey.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/FieldCipher.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KeystoreCipherTest.kt core/privacy/src/test/kotlin/dev/kestrel/privacy/crypto/AesGcmFieldCipherTest.kt
git commit -m "feat(privacy): the key kestrel.conversations is erasable, so that nothing it encrypted can be read once it is gone (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: `ConversationArchiveStore`, the archive that knows only bytes

The store of design 9.2: it encrypts each payload under the erasable key, bound to its conversation's id, and writes several conversations in one transaction; it reads every conversation back in one query, each one's payload, or the mark that it failed its check or is in a format that this version cannot read; it keeps the latter and reports it by its kind only; and it erases the key with the rows. It never sees what a payload holds, and it never deletes a row on its own: plan 7B's store decides that.

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt`

**Interfaces:**
- Consumes: Task 1's `ConversationDao`, `ConversationRow` and `ConversationHeader`; Task 2's `ErasableKey` with its `guard`; `AesGcmFieldCipher`, whose `decrypt` throws `AEADBadTagException` for a payload that fails its check and `IllegalArgumentException` for an unknown format.
- Produces: package `dev.kestrel.privacy.conversations`: `class PlainConversation(val header: ConversationHeader, val payload: ByteArray)`; `sealed interface StoredConversation { val header: ConversationHeader }` with `class Readable(header, val payload: ByteArray)`, `class FailedCheck(header)` and `class UnknownFormat(header)`; `class ConversationArchiveStore(dao: ConversationDao, key: ErasableKey, onUnknownFormat: suspend () -> Unit = {}, dispatcher: CoroutineDispatcher = Dispatchers.IO)` with `suspend fun readAll(): List<StoredConversation>` (every conversation, the newest last change first, read in one query and decrypted under the key's guard in one `withContext`), `suspend fun write(conversations: List<PlainConversation>)`, `suspend fun delete(id: String)`, `suspend fun deleteAll()` and `suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int`. Task 4 builds it in `PrivacyStore`; Task 5 wraps it in `RoomRecordArchive`.

Counts: `:core:privacy` JVM N + 10 (P₀ + 11). Device tests unchanged.

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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
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
    fun everyConversationReadsBackNewestFirstWithItsPlainFieldsUntilItIsDeleted() = runTest {
        store.write(listOf(PlainConversation(closed("b", last = 25), "other".encodeToByteArray())))
        store.write(listOf(PlainConversation(opened("a", last = 20), "first".encodeToByteArray())))
        val changed = opened("a", last = 30).copy(commands = 3)
        store.write(listOf(PlainConversation(changed, "second".encodeToByteArray())))

        val stored = store.readAll().map { it as StoredConversation.Readable }
        assertEquals(listOf(changed, closed("b", last = 25)), stored.map { it.header })
        assertArrayEquals("second".encodeToByteArray(), stored[0].payload)
        assertArrayEquals("other".encodeToByteArray(), stored[1].payload)

        store.delete("a")
        assertEquals(listOf("b"), store.readAll().map { it.header.id })
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

        assertEquals(setOf("a" to false, "b" to true), store.readAll().map { it.header.id to it.header.open }.toSet())
        assertEquals(before + 1, dao.transactions)
    }

    @Test
    fun anOpenConversationClosesAnOpenRowThatTheCallerDoesNotKnow() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = true, commands = 1, payload = laterFormat())

        store.write(listOf(PlainConversation(opened("a", last = 3), byteArrayOf(1))))

        assertEquals(listOf("a" to true, "later" to false), store.readAll().map { it.header.id to it.header.open })
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes a payload that fails its check and reports it by its kind.
    @Test
    fun aPayloadMovedToAnotherConversationFailsItsCheckAndStaysForTheCallerToDelete() = runTest {
        store.write(listOf(PlainConversation(closed("a", last = 1), "words".encodeToByteArray())))
        val row = dao.rows.getValue("a")
        dao.rows["moved"] = ConversationRow("moved", row.openedMillis, row.lastMillis, open = false, commands = row.commands, payload = row.payload)

        val read = store.readAll().associateBy { it.header.id }

        assertTrue(read.getValue("a") is StoredConversation.Readable)
        assertTrue(read.getValue("moved") is StoredConversation.FailedCheck)
        assertEquals(closed("moved", last = 1), read.getValue("moved").header)
        assertTrue("moved" in dao.rows)
        assertEquals(0, unknownFormats)
    }

    @Test
    fun aConversationInAnUnknownFormatIsKeptAndReportedOncePerProcess() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())

        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)
        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)

        assertTrue("later" in dao.rows)
        assertEquals(1, unknownFormats)
    }

    @Test
    fun aReportOfAnUnknownFormatThatFailsIsMadeAgainAtTheNextRead() = runTest {
        var reports = 0
        val store = ConversationArchiveStore(dao, key, onUnknownFormat = { if (++reports == 1) throw IOException("the log is unavailable") }, dispatcher = Dispatchers.Unconfined)
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())

        assertTrue(runCatching { store.readAll() }.exceptionOrNull() is IOException)
        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)
        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)

        assertEquals(2, reports)
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
        assertTrue(store.readAll().single() is StoredConversation.FailedCheck)
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

            override fun <T> withExisting(block: (SecretKey?) -> T): T = key.withExisting(block)

            override fun erase() = key.erase()
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, watched, dispatcher = dispatcher)
            store.write(listOf(PlainConversation(closed("a", last = 1), byteArrayOf(1))))
            store.readAll()

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

    override fun <T> withExisting(block: (SecretKey?) -> T): T = block(key)

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

    override suspend fun all(): List<ConversationRow> = rows.values.sortedByDescending { it.lastMillis }

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
 * conversation in a format that this version cannot read is kept, and [onUnknownFormat] hears of it once per process; when
 * [onUnknownFormat] throws, the read fails, and the next read of the conversation reports it again.
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

    // The ids of the rows in an unknown format whose report was made, so that each is reported once per process; a report
    // that failed or was cancelled is made again at the next read. Used only under the key's guard.
    private val reportedUnknown = mutableSetOf<String>()

    /**
     * Every conversation, the newest last change first, read in one query and decrypted in one pass on [dispatcher]: each
     * one's payload, or the mark that it failed its check or is in a format that this version cannot read.
     */
    suspend fun readAll(): List<StoredConversation> = locked { dao.all().map { stored(it) } }

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
            if (row.id !in reportedUnknown) {
                onUnknownFormat()
                reportedUnknown += row.id
            }
            StoredConversation.UnknownFormat(header)
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = key.guard.withLock { withContext(dispatcher) { block() } }

    private fun associatedData(id: String) = "conversations/$id".encodeToByteArray()
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest --tests=dev.kestrel.privacy.conversations.ConversationArchiveStoreTest`
Expected: PASS, 10 tests.

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest :core:privacy:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL, P₀ + 11 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt
git commit -m "feat(privacy): the archive of conversations encrypts each payload bound to its id, writes a switch in one transaction, keeps a payload in an unknown format and erases its key with its rows (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: `PrivacyStore.conversations`, its report of a kept conversation, and the first read's speed

`PrivacyStore` builds the archive on Kestrel's database with the key `kestrel.conversations`, and writes a conversation that it keeps unread into the activity log by its kind only (design 9.2). A device test measures design R3's risk: the first read of 30 days of conversations through the Keystore, as plan 7B's store reads them: every row in one `readAll()`, in a new store, as after a start of Kestrel.

**Files:**
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt`
- Test: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`
- Create: `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/ConversationArchiveSpeedTest.kt`

**Interfaces:**
- Consumes: Task 1's `KestrelDatabase.conversationDao()` (with `all()`), `ConversationRow`, `ConversationHeader`; Task 2's `KeystoreErasableKey` and `KeystoreKeys.CONVERSATIONS_KEY`; Task 3's `ConversationArchiveStore` (with `readAll()`), `PlainConversation` and `StoredConversation`.
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

            assertTrue(store.conversations.readAll().isEmpty())
            assertFalse(keyStore.containsAlias(KeystoreKeys.CONVERSATIONS_KEY))
            store.conversations.write(listOf(PlainConversation(header, "second".encodeToByteArray())))
            val read = store.conversations.readAll().single() as StoredConversation.Readable
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

            assertTrue(store.conversations.readAll().single { it.header.id == id } is StoredConversation.UnknownFormat)

            assertTrue(dao.all().any { it.id == id })
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
 * (every row in one query, `readAll`), through a new store, as after a start of Kestrel, so that each row is decrypted once
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
            val conversations = reader.readAll()
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
Expected: BUILD SUCCESSFUL, P₀ + 11 tests.

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

### Task 4b: The index of past conversations in shards of a table of its own, and reads that decrypt on one thread

Design 9.1, 9.2, 9.3, 9.7 and R3 of sub-project 7, as amended on 2026-11-25 and 2026-11-26. On the A71, the first read of 300 conversations of 12 KB took 13.8 s, and still 5.2 s in one query, because each Keystore decryption costs about 17 ms there, and about twice as much right after a hop to another thread. Plan 7B's store therefore keeps an index of past conversations in shards of bounded size, each encrypted in a row of its own, reads the open conversation alone at a restore and the index in the background, and reads a conversation's body only when it needs it (plan 7B's Tasks 5a to 5d). This task gives the archive those rows and those reads.

Version 3 of the database is on no real install yet: it exists only in the tests and in this library's test app, whose database is deleted after each run. So version 3 gains the table `conversation_index` (`ConversationIndexRow(name, payload)`, one row per shard, named by the shard's number), and `3.json` is generated again, rather than a version 4. A table of its own keeps every query of `conversations` plain: rows of the index in `conversations` would have to be left out of the stamp, the open row, the purge and the rule that closes the other open rows.

`ConversationDao`'s functions now block: `ConversationArchiveStore` calls them on its own dispatcher, so that a query and the decryption of its rows run on one thread, with no hop between them.
- `open()` reads the open conversation's row; `shards()` reads every shard's row; `stamp()` reads the stamp of the closed rows (how many, the newest `last_millis` and their sum). Plan 7B's store reads the shards first and the stamp later, under its own lock, after the changes made meanwhile, so the two need no common transaction.
- `rows(ids)` reads some rows; `closedIds()` lists the closed rows' ids, the oldest last change first, without their payloads, for a rebuild that reads them in pages.
- `write(rows, deleted, index)` stores rows, deletes rows and changes the shards' rows (`IndexRowUpdate.Keep`, `Update(put, deleted)` or `Replace(rows)`) in one transaction.
- `purge(oldest, newest, index)` deletes, in one transaction with the shards' change, every closed row whose `last_millis` is outside retention, by the table's own times, so that retention holds also while the index is rebuilt or cannot be read, and also for a row that the index does not list.
- `deleteAll()` empties both tables.

`ConversationArchiveStore` offers `open()`, `shards()`, `stamp()`, `read(ids)`, `closedIds()`, `write(ConversationChange)`, `purge(oldest, newest, IndexUpdate)` and `deleteAll()`. It encrypts each shard under the key `kestrel.conversations` with the associated data `conversation_index/<name>`, so that no shard opens under another shard's name or a conversation's. Its reads take no lock, because they store nothing, so that a rebuild's long read never holds up a write, and they stop between two rows when their caller is cancelled, so that "delete history" stops a rebuild's read at once. They decrypt with Task 2's `ErasableKey.withExisting`, so that a read never makes the key and never meets it deleted: a read beside "delete history" sees each row as it was, or, once the key is gone, a conversation as `FailedCheck` (a later format stays `UnknownFormat`) and a shard as `Unreadable`, or, once the rows are deleted, not at all. Its writes, its purge and `deleteAll` keep the key's guard. `deleteAll` now erases the key first and deletes the rows afterwards: a crash or a failure between the two leaves only rows that no key opens, which plan 7B's store deletes as unreadable. A conversation in an unknown format is still reported once per process, after its read.

Android's SQLite is built with `SQLITE_SECURE_DELETE`, so a delete overwrites the pages that it frees: an older copy of a shard, which every close replaces, or of a deleted conversation is not left in the database file's free pages. A device test checks that on the connection that writes. Copies can still stay in the write-ahead log until SQLite checkpoints it and writes over them (design 9.1 says so).

`first()`, `readAll()`, `all()`, `FirstRows`, `FirstRead`, `StoredIndex`'s old kinds and the single row `past` go, because plan 7B's store reads the open row, the shards and the stamp apart and rebuilds page by page. The speed test measures the reads and the close that plan 7B's store makes.

**Files:**
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt` (whole file), `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt`, `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt` (whole file)
- Modify (generated by Room in Step 4): `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json`
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt` (whole file), `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt` (whole file), `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseMigrationTest.kt`, `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`, `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/ConversationArchiveSpeedTest.kt` (whole file)

**Interfaces:**
- Consumes: Tasks 1 to 4 as built and as the bulk read changed them (`c5124a0a`): `ConversationRow`, `ConversationHeader`, `ConversationDao`, `KestrelDatabase` at version 3, `ErasableKey` with its `guard`, `KeystoreErasableKey.of`, `AesGcmFieldCipher`, `ConversationArchiveStore(dao, key, onUnknownFormat, dispatcher)`, `PlainConversation`, `StoredConversation`, `PrivacyStore.conversations`.
- Produces (package `dev.kestrel.privacy.db`): `class ConversationIndexRow(name: String, payload: ByteArray)` (table `conversation_index`); `data class ConversationStamp(val closed: Int, val newest: Long, val total: Long)`; `sealed interface IndexRowUpdate { Keep; class Update(put: List<ConversationIndexRow>, deleted: List<String>); class Replace(rows: List<ConversationIndexRow>) }`; `abstract class ConversationDao` with `fun open(): ConversationRow?`, `fun shards(): List<ConversationIndexRow>`, `fun stamp(): ConversationStamp`, `fun rows(ids: List<String>): List<ConversationRow>`, `fun closedIds(): List<String>`, `open fun write(rows, deleted, index: IndexRowUpdate)`, `open fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int` and `open fun deleteAll()`, all blocking; `KestrelDatabase` at version 3 with both tables. Package `dev.kestrel.privacy.conversations`: `sealed interface StoredShard { val name: String; class Readable(name, val payload: ByteArray); class Unreadable(name) }`; `sealed interface IndexUpdate { data object Keep; class Update(val put: Map<String, ByteArray>, val deleted: Set<String>); class Replace(val shards: Map<String, ByteArray>) }`; `class ConversationChange(val conversations: List<PlainConversation>, val deleted: Collection<String>, val index: IndexUpdate)`; `class ConversationArchiveStore` with `suspend fun open(): StoredConversation?`, `suspend fun shards(): List<StoredShard>`, `suspend fun stamp(): ConversationStamp`, `suspend fun read(ids: Collection<String>): List<StoredConversation>`, `suspend fun closedIds(): List<String>`, `suspend fun write(change: ConversationChange)`, `suspend fun purge(oldest: Long, newest: Long, index: IndexUpdate): Int` and `suspend fun deleteAll()`. The speed test's five log lines (Step 5), which Task 8 reads. Task 5's `RoomRecordArchive` implements plan 7B's port on them.

Counts: `:core:privacy` JVM N + 7 (`ConversationArchiveStoreTest` 10 → 17; 77 + 7 = 84 with Task 2's test of the cipher). `:core:privacy` device tests N + 3 (`KestrelDatabaseTest` 7 → 10; the other classes keep their counts).

- [ ] **Step 1: Write the failing tests**

Replace the whole `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt` with:

```kotlin
package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.ConversationStamp
import dev.kestrel.privacy.db.IndexRowUpdate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationArchiveStoreTest {
    private val dao = FakeConversationDao()
    private val key = MemoryKey()
    private var unknownFormats = 0
    private val store = ConversationArchiveStore(dao, key, onUnknownFormat = { unknownFormats++ }, dispatcher = Dispatchers.Unconfined)

    private suspend fun write(vararg conversations: PlainConversation, deleted: List<String> = emptyList(), index: IndexUpdate = IndexUpdate.Keep) =
        store.write(ConversationChange(conversations.toList(), deleted, index))

    @Test
    fun everyConversationReadsBackWithItsPlainFieldsUntilItIsDeleted() = runTest {
        write(PlainConversation(closed("b", last = 25), "other".encodeToByteArray()))
        write(PlainConversation(opened("a", last = 20), "first".encodeToByteArray()))
        val changed = opened("a", last = 30).copy(commands = 3)
        write(PlainConversation(changed, "second".encodeToByteArray()), PlainConversation(closed("c", last = 5), "older".encodeToByteArray()))

        val stored = store.read(listOf("a", "b")).associateBy { it.header.id }.mapValues { it.value as StoredConversation.Readable }
        assertEquals(changed, stored.getValue("a").header)
        assertEquals(closed("b", last = 25), stored.getValue("b").header)
        assertArrayEquals("second".encodeToByteArray(), stored.getValue("a").payload)
        assertArrayEquals("other".encodeToByteArray(), stored.getValue("b").payload)
        assertEquals(listOf("c", "b"), store.closedIds())

        write(deleted = listOf("a"))
        assertTrue(store.read(listOf("a")).isEmpty())
        assertEquals(null, store.open())
    }

    @Test
    fun storesThePayloadsAndTheIndexEncrypted() = runTest {
        write(PlainConversation(closed("a", last = 1), "a secret request".encodeToByteArray()), index = IndexUpdate.Update(mapOf("0" to "a secret word".encodeToByteArray()), emptySet()))

        assertFalse(dao.rows.getValue("a").payload.decodeToString(throwOnInvalidSequence = false).contains("secret"))
        assertFalse(dao.shards.getValue("0").payload.decodeToString(throwOnInvalidSequence = false).contains("secret"))
    }

    // Design 9.2 of sub-project 7: a restore reads the open conversation alone; the shards and the stamp are read apart.
    @Test
    fun theOpenConversationTheShardsAndTheStampOfTheClosedRowsAreReadApart() = runTest {
        assertTrue(store.shards().isEmpty())

        write(
            PlainConversation(closed("a", last = 10), byteArrayOf(1)),
            PlainConversation(closed("b", last = 30), byteArrayOf(2)),
            PlainConversation(opened("c", last = 40), "open".encodeToByteArray()),
            index = IndexUpdate.Update(mapOf("0" to "zero".encodeToByteArray(), "1" to "one".encodeToByteArray()), emptySet()),
        )
        assertEquals("open", (store.open() as StoredConversation.Readable).payload.decodeToString())
        assertEquals(mapOf("0" to "zero", "1" to "one"), store.shards().associate { it.name to (it as StoredShard.Readable).payload.decodeToString() })
        assertEquals(ConversationStamp(closed = 2, newest = 30, total = 40), store.stamp())

        write(index = IndexUpdate.Update(mapOf("1" to "new".encodeToByteArray()), setOf("0")))
        assertEquals(mapOf("1" to "new"), store.shards().associate { it.name to (it as StoredShard.Readable).payload.decodeToString() })
        write(index = IndexUpdate.Replace(emptyMap()))
        assertTrue(store.shards().isEmpty())
    }

    @Test
    fun aReadGivesOnlyTheConversationsThatItAsksFor() = runTest {
        write(PlainConversation(closed("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2)), PlainConversation(closed("c", last = 3), byteArrayOf(3)))

        assertEquals(setOf("a", "c"), store.read(listOf("a", "c", "gone")).map { it.header.id }.toSet())
    }

    // Design 6.1 and 9.6 of sub-project 7: a switch closes one conversation and opens another in the same write.
    @Test
    fun oneWriteSwitchesTheOpenConversationAndReplacesTheIndex() = runTest {
        write(PlainConversation(opened("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2)), index = IndexUpdate.Replace(mapOf("0" to byteArrayOf(5), "1" to byteArrayOf(6))))
        val before = dao.transactions

        write(PlainConversation(closed("a", last = 3), byteArrayOf(1)), PlainConversation(opened("b", last = 3), byteArrayOf(2)), index = IndexUpdate.Replace(mapOf("2" to byteArrayOf(7))))

        assertEquals(setOf("a" to false, "b" to true), store.read(listOf("a", "b")).map { it.header.id to it.header.open }.toSet())
        assertEquals(listOf("2"), store.shards().map { it.name })
        assertArrayEquals(byteArrayOf(7), (store.shards().single() as StoredShard.Readable).payload)
        assertEquals(before + 1, dao.transactions)
    }

    // Design 9.3 of sub-project 7: retention deletes by the table's own times, in one transaction with the index.
    @Test
    fun purgeDeletesTheClosedRowsOutsideTheWindowWithTheIndexInOneTransaction() = runTest {
        write(
            PlainConversation(closed("old", last = 5), byteArrayOf(1)),
            PlainConversation(closed("kept", last = 50), byteArrayOf(2)),
            PlainConversation(closed("ahead", last = 500), byteArrayOf(3)),
            PlainConversation(opened("open", last = 1), byteArrayOf(4)),
            index = IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet()),
        )
        val before = dao.transactions

        assertEquals(2, store.purge(oldest = 10, newest = 100, index = IndexUpdate.Update(mapOf("0" to byteArrayOf(2)), emptySet())))

        assertEquals(setOf("kept", "open"), dao.rows.keys)
        assertArrayEquals(byteArrayOf(2), (store.shards().single() as StoredShard.Readable).payload)
        assertEquals(before + 1, dao.transactions)
    }

    @Test
    fun anOpenConversationClosesAnOpenRowThatTheCallerDoesNotKnow() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = true, commands = 1, payload = laterFormat())

        write(PlainConversation(opened("a", last = 3), byteArrayOf(1)))

        assertEquals(listOf("a" to true, "later" to false), store.read(listOf("a", "later")).map { it.header.id to it.header.open })
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes a payload that fails its check and reports it by its kind.
    @Test
    fun aPayloadMovedToAnotherConversationFailsItsCheckAndStaysForTheCallerToDelete() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()))
        val row = dao.rows.getValue("a")
        dao.rows["moved"] = ConversationRow("moved", row.openedMillis, row.lastMillis, open = false, commands = row.commands, payload = row.payload)

        val read = store.read(listOf("a", "moved")).associateBy { it.header.id }

        assertTrue(read.getValue("a") is StoredConversation.Readable)
        assertTrue(read.getValue("moved") is StoredConversation.FailedCheck)
        assertEquals(closed("moved", last = 1), read.getValue("moved").header)
        assertTrue("moved" in dao.rows)
        assertEquals(0, unknownFormats)
    }

    // The shards and the conversations are bound to their places: no payload opens in another's.
    @Test
    fun aShardInAnotherPlaceOrInAnotherFormatCannotBeRead() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()), index = IndexUpdate.Update(mapOf("0" to "zero".encodeToByteArray(), "1" to "one".encodeToByteArray()), emptySet()))
        val zero = dao.shards.getValue("0").payload
        val one = dao.shards.getValue("1").payload
        val conversation = dao.rows.getValue("a").payload

        dao.rows["a"] = ConversationRow("a", 0, 1, open = false, commands = 1, payload = zero)
        dao.shards["0"] = ConversationIndexRow("0", one)
        dao.shards["1"] = ConversationIndexRow("1", conversation)
        assertTrue(store.read(listOf("a")).single() is StoredConversation.FailedCheck)
        assertTrue(store.shards().all { it is StoredShard.Unreadable })

        dao.shards["0"] = ConversationIndexRow("0", laterFormat())
        assertTrue(store.shards().first { it.name == "0" } is StoredShard.Unreadable)
        assertEquals(0, unknownFormats)
    }

    @Test
    fun aConversationInAnUnknownFormatIsKeptAndReportedOncePerProcess() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())

        assertTrue(store.read(listOf("later")).single() is StoredConversation.UnknownFormat)
        assertTrue(store.read(listOf("later")).single() is StoredConversation.UnknownFormat)

        assertTrue("later" in dao.rows)
        assertEquals(1, unknownFormats)
    }

    @Test
    fun aReportOfAnUnknownFormatThatFailsIsMadeAgainAtTheNextRead() = runTest {
        var reports = 0
        val store = ConversationArchiveStore(dao, key, onUnknownFormat = { if (++reports == 1) throw IOException("the log is unavailable") }, dispatcher = Dispatchers.Unconfined)
        dao.rows["later"] = ConversationRow("later", 1, 2, open = true, commands = 1, payload = laterFormat())

        assertTrue(runCatching { store.open() }.exceptionOrNull() is IOException)
        assertTrue(store.open() is StoredConversation.UnknownFormat)
        assertTrue(store.read(listOf("later")).single() is StoredConversation.UnknownFormat)

        assertEquals(2, reports)
    }

    // Design 9.7 of sub-project 7: after "delete history", not even a copy of the database file can be read. The key goes
    // first, so that a deletion cut short leaves only rows that no key opens.
    @Test
    fun deleteAllErasesTheKeyFirstAndThenTheRowsAndTheIndexSoThatACopyCannotBeRead() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()), index = IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet()))
        val copy = dao.rows.getValue("a")
        val shard = dao.shards.getValue("0")
        var erasedBeforeTheRowsWent = false
        dao.beforeDeleteAll = { erasedBeforeTheRowsWent = key.erased == 1 }

        store.deleteAll()

        assertTrue(erasedBeforeTheRowsWent)
        assertTrue(dao.rows.isEmpty())
        assertTrue(dao.shards.isEmpty())
        dao.rows["a"] = copy
        dao.shards["0"] = shard
        assertTrue(store.read(listOf("a")).single() is StoredConversation.FailedCheck)
        assertTrue(store.shards().single() is StoredShard.Unreadable)
    }

    // Without the key's guard, the key could be erased between a write's encryption and its row, which no key would then
    // open. The pause can only let a wrong order pass, never fail a right one.
    @Test
    fun deleteAllWaitsForAWriteThatHasStartedSoThatNoRowOutlivesTheKey() = runTest {
        val written = CountDownLatch(1)
        val gate = CountDownLatch(1)
        dao.beforeUpsert = { written.countDown(); gate.await(5, TimeUnit.SECONDS) }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, key, dispatcher = dispatcher)
            val write = launch(Dispatchers.Default) { store.write(ConversationChange(listOf(PlainConversation(closed("a", last = 1), byteArrayOf(1))), emptyList(), IndexUpdate.Keep)) }
            assertTrue(written.await(5, TimeUnit.SECONDS))
            val delete = launch(Dispatchers.Default) { store.deleteAll() }
            Thread.sleep(100)
            assertEquals(0, key.erased)

            gate.countDown()
            write.join()
            delete.join()
        }

        assertEquals(1, key.erased)
        assertTrue(dao.rows.isEmpty())
    }

    // Design R3 of sub-project 7: a write that holds the key's guard never holds up a read, such as a rebuild's.
    @Test
    fun aReadDoesNotWaitForTheKeysGuard() = runTest {
        write(PlainConversation(closed("a", last = 1), byteArrayOf(1)))
        key.guard.lock()
        try {
            withTimeout(1_000) {
                assertEquals(listOf("a"), store.read(listOf("a")).map { it.header.id })
                assertTrue(store.shards().isEmpty())
                assertEquals(listOf("a"), store.closedIds())
            }
        } finally {
            key.guard.unlock()
        }
    }

    // Design 9.2 of sub-project 7: "delete history" stops a rebuild, whose read then decrypts no more rows.
    @Test
    fun aCancelledReadStopsBetweenTwoRows() = runTest {
        write(PlainConversation(closed("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2)), PlainConversation(closed("c", last = 3), byteArrayOf(3)))
        var decrypted = 0
        lateinit var reading: Job
        val watched = object : ErasableKey {
            override val guard = key.guard

            override fun get(): SecretKey = key.get()

            override fun <T> withExisting(block: (SecretKey?) -> T): T = key.withExisting(block).also { if (++decrypted == 1) reading.cancel() }

            override fun erase() = key.erase()
        }
        val store = ConversationArchiveStore(dao, watched, dispatcher = Dispatchers.Unconfined)
        reading = launch(start = CoroutineStart.LAZY) { store.read(listOf("a", "b", "c")) }

        reading.join()

        assertTrue(reading.isCancelled)
        assertEquals(1, decrypted)
    }

    // Design R3 of sub-project 7: a Keystore operation right after a hop to another thread costs about twice as much.
    @Test
    fun eachReadRunsItsQueryAndItsDecryptionOnOneThreadOfTheStoresDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val watched = object : ErasableKey {
            override val guard = key.guard

            override fun get(): SecretKey = key.get()

            override fun <T> withExisting(block: (SecretKey?) -> T): T = key.withExisting(block).also { threads += Thread.currentThread() }

            override fun erase() = key.erase()
        }
        dao.onQuery = { threads += Thread.currentThread() }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, watched, dispatcher = dispatcher)
            store.write(ConversationChange(listOf(PlainConversation(opened("a", last = 1), byteArrayOf(1))), emptyList(), IndexUpdate.Update(mapOf("0" to byteArrayOf(2)), emptySet())))
            threads.clear()
            store.open()
            store.shards()
            store.read(listOf("a"))

            assertEquals(6, threads.size)
            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    // Design 9.7 of sub-project 7: a read never makes the key. Between the key's erasure and the rows' deletion, a read that
    // overlaps "delete history" finds every row failing its check, no readable shard, and a later format still unknown.
    @Test
    fun aReadWithoutTheKeyMakesNoKeyAndFindsTheRowsUnreadable() = runTest {
        write(
            PlainConversation(opened("a", last = 2), byteArrayOf(1)),
            PlainConversation(closed("b", last = 1), byteArrayOf(2)),
            index = IndexUpdate.Update(mapOf("0" to byteArrayOf(3)), emptySet()),
        )
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())
        key.erase()

        assertTrue(store.open() is StoredConversation.FailedCheck)
        assertEquals(
            mapOf("a" to StoredConversation.FailedCheck::class, "b" to StoredConversation.FailedCheck::class, "later" to StoredConversation.UnknownFormat::class),
            store.read(listOf("a", "b", "later")).associate { it.header.id to it::class },
        )
        assertTrue(store.shards().single() is StoredShard.Unreadable)
        assertEquals(1, key.made)
        assertFalse(key.exists)
    }

    private fun opened(id: String, last: Long) = ConversationHeader(id, openedMillis = last - 1, lastMillis = last, open = true, commands = 1)

    private fun closed(id: String, last: Long) = opened(id, last).copy(open = false)

    /** A later version's ciphertext: its first byte names a format that this version does not know. */
    private fun laterFormat() = byteArrayOf(2) + ByteArray(40)
}

/** An [ErasableKey] in memory, which [get] makes when there is none, as Keystore does, and [erase] deletes. */
private class MemoryKey : ErasableKey {
    override val guard = Mutex()

    @Volatile
    var erased = 0
        private set

    @Volatile
    var made = 0
        private set
    private var key: SecretKey? = null

    val exists: Boolean
        @Synchronized get() = key != null

    @Synchronized
    override fun get(): SecretKey = key ?: newKey().also {
        key = it
        made++
    }

    @Synchronized
    override fun <T> withExisting(block: (SecretKey?) -> T): T = block(key)

    @Synchronized
    override fun erase() {
        erased++
        key = null
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/**
 * Mimics the SQL in [ConversationDao]; KestrelDatabaseTest checks the real queries and the transactions on a device. Its
 * write, purge and deleteAll are the DAO's own, so these tests run them; [transactions] counts the writes and the
 * purges, and [onQuery] hears each query that reads rows.
 */
private class FakeConversationDao : ConversationDao() {
    val rows = linkedMapOf<String, ConversationRow>()
    val shards = linkedMapOf<String, ConversationIndexRow>()
    var beforeUpsert: () -> Unit = {}
    var beforeDeleteAll: () -> Unit = {}
    var onQuery: () -> Unit = {}
    var transactions = 0

    override fun write(rows: List<ConversationRow>, deleted: List<String>, index: IndexRowUpdate) {
        transactions++
        super.write(rows, deleted, index)
    }

    override fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int {
        transactions++
        return super.purge(oldest, newest, index)
    }

    override fun deleteAll() {
        beforeDeleteAll()
        super.deleteAll()
    }

    override fun open(): ConversationRow? = rows.values.firstOrNull { it.open }.also { onQuery() }

    override fun rows(ids: List<String>): List<ConversationRow> = ids.mapNotNull { rows[it] }.also { onQuery() }

    override fun closedIds(): List<String> = rows.values.filter { !it.open }.sortedBy { it.lastMillis }.map { it.id }

    override fun shards(): List<ConversationIndexRow> = shards.values.toList().also { onQuery() }

    override fun stamp(): ConversationStamp {
        val closed = rows.values.filter { !it.open }.map { it.lastMillis }
        return ConversationStamp(closed.size, closed.maxOrNull() ?: 0, closed.sum())
    }

    override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override fun deleteClosedOutside(oldest: Long, newest: Long): Int {
        val gone = rows.values.filter { !it.open && (it.lastMillis < oldest || it.lastMillis > newest) }.map { it.id }
        gone.forEach { rows.remove(it) }
        return gone.size
    }

    override fun upsert(row: ConversationRow) {
        beforeUpsert()
        rows[row.id] = row
    }

    override fun closeOthers(id: String): Int {
        val others = rows.values.filter { it.open && it.id != id }
        others.forEach { rows[it.id] = ConversationRow(it.id, it.openedMillis, it.lastMillis, open = false, commands = it.commands, payload = it.payload) }
        return others.size
    }

    override fun upsertIndex(row: ConversationIndexRow) {
        shards[row.name] = row
    }

    override fun deleteIndex(name: String): Int = if (shards.remove(name) != null) 1 else 0

    override fun deleteConversations(): Int = rows.size.also { rows.clear() }

    override fun deleteIndexes(): Int = shards.size.also { shards.clear() }
}
```

Replace the whole `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt` with:

```kotlin
package dev.kestrel.privacy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.ConversationStamp
import dev.kestrel.privacy.db.IndexRowUpdate
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import dev.kestrel.privacy.db.SettingRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun deleteOutsideKeepsEntriesAtBothLimits() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 99, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 100, payload = byteArrayOf(2)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 200, payload = byteArrayOf(3)))
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 201, payload = byteArrayOf(4)))

        assertEquals(2, dao.deleteOutside(oldestMillis = 100, newestMillis = 200))
        assertEquals(listOf(200L, 100L), dao.newest("network", 10).map { it.timestampMillis })
        assertEquals(emptyList<Long>(), dao.newest("activity", 10).map { it.timestampMillis })
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


    @Test
    fun oldestAfterReturnsOneLogOldestFirstFromAPlace() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 2, payload = byteArrayOf(2)))
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 1, payload = byteArrayOf(1)))
        val sameTime = dao.insert(LogEntryRow(log = "activity", timestampMillis = 2, payload = byteArrayOf(3)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 1, payload = byteArrayOf(4)))

        val first = dao.oldestAfter("activity", Long.MIN_VALUE, Long.MIN_VALUE, 2)
        assertEquals(listOf(1, 2), first.map { it.payload.single().toInt() })
        val rest = dao.oldestAfter("activity", first.last().timestampMillis, first.last().id, 2)
        assertEquals(listOf(sameTime), rest.map { it.id })
    }

    // Design 6.1 of sub-project 7: at most one conversation is open, whatever the caller writes.
    @Test
    fun writingAnOpenConversationClosesTheOpenOne() {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = true)), emptyList(), IndexRowUpdate.Keep)
        dao.write(listOf(conversation("b", last = 2, open = true)), emptyList(), IndexRowUpdate.Keep)

        assertEquals(setOf("b" to true, "a" to false), dao.rows(listOf("a", "b")).map { it.id to it.open }.toSet())
        assertEquals("b", dao.open()!!.id)
    }

    // Design 9.2 and 9.6 of sub-project 7: a switch writes the closed and the opened conversation and the shards in one transaction.
    @Test
    fun oneWriteStoresItsRowsDeletesItsRowsAndChangesTheShards() {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = false), conversation("b", last = 2, open = true), conversation("c", last = 2, open = false)), emptyList(), IndexRowUpdate.Replace(listOf(shard("0", 5), shard("1", 6))))

        dao.write(
            listOf(conversation("b", last = 3, open = false), conversation("a", last = 3, open = true)),
            listOf("c"),
            IndexRowUpdate.Update(listOf(shard("1", 7), shard("2", 8)), listOf("0")),
        )

        val rows = dao.rows(listOf("a", "b", "c"))
        assertEquals(setOf("a" to true, "b" to false), rows.map { it.id to it.open }.toSet())
        assertEquals(3L, rows.single { it.id == "b" }.lastMillis)
        assertEquals(mapOf("1" to 7.toByte(), "2" to 8.toByte()), dao.shards().associate { it.name to it.payload.single() })
        dao.write(emptyList(), emptyList(), IndexRowUpdate.Replace(emptyList()))
        assertTrue(dao.shards().isEmpty())
    }

    // Design 9.2 and R3 of sub-project 7: a restore reads the open row alone; the shards and the closed rows' stamp apart.
    @Test
    fun theOpenRowTheShardsAndTheStampOfTheClosedRowsAreReadApart() {
        val dao = db.conversationDao()
        assertEquals(ConversationStamp(0, 0, 0), dao.stamp())
        dao.write(
            listOf(conversation("b", last = 300, open = false), conversation("a", last = 100, open = false), conversation("c", last = 500, open = true)),
            emptyList(),
            IndexRowUpdate.Update(listOf(shard("0", 9)), emptyList()),
        )

        assertArrayEquals(byteArrayOf(9), dao.shards().single().payload)
        assertEquals(ConversationStamp(closed = 2, newest = 300, total = 400), dao.stamp())
        assertEquals(listOf<Any>("c", 499L, 500L, true, 2), dao.open()!!.let { listOf(it.id, it.openedMillis, it.lastMillis, it.open, it.commands) })
        assertEquals(listOf("a", "b"), dao.closedIds())
    }

    // Design 9.3 of sub-project 7: retention deletes by the table's own times, keeps the rows at both limits and the open
    // row, and changes the shards in one transaction.
    @Test
    fun purgeDeletesTheClosedRowsOutsideTheWindowAndChangesTheShards() {
        val dao = db.conversationDao()
        dao.write(
            listOf(
                conversation("before", last = 9, open = false),
                conversation("oldest", last = 10, open = false),
                conversation("newest", last = 100, open = false),
                conversation("after", last = 101, open = false),
                conversation("open", last = 1, open = true),
            ),
            emptyList(),
            IndexRowUpdate.Update(listOf(shard("0", 1)), emptyList()),
        )

        assertEquals(2, dao.purge(oldest = 10, newest = 100, index = IndexRowUpdate.Update(listOf(shard("0", 2)), emptyList())))

        assertEquals(setOf("oldest", "newest", "open"), dao.rows(listOf("before", "oldest", "newest", "after", "open")).map { it.id }.toSet())
        assertArrayEquals(byteArrayOf(2), dao.shards().single().payload)
    }

    @Test
    fun rowsReadsOnlyTheAskedRowsAndDeleteAllEmptiesBothTables() {
        val dao = db.conversationDao()
        dao.write(
            listOf(conversation("a", last = 1, open = false), conversation("b", last = 2, open = false), conversation("c", last = 3, open = true)),
            emptyList(),
            IndexRowUpdate.Update(listOf(shard("0", 1)), emptyList()),
        )

        assertEquals(setOf("a", "c"), dao.rows(listOf("a", "c", "gone")).map { it.id }.toSet())

        dao.deleteAll()
        assertTrue(dao.rows(listOf("a", "b", "c")).isEmpty())
        assertTrue(dao.shards().isEmpty())
    }

    // Design 9.5 and 9.7 of sub-project 7: Android's SQLite overwrites what a delete frees, so that an older copy of a shard
    // or of a deleted conversation is not left in the file's free pages. The connection that writes is the one that deletes.
    @Test
    fun theConnectionThatWritesOverwritesWhatItDeletes() {
        val database = db.openHelper.writableDatabase
        database.beginTransaction()
        try {
            database.query("PRAGMA secure_delete").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            database.endTransaction()
        }
    }

    private fun conversation(id: String, last: Long, open: Boolean) =
        ConversationRow(id, openedMillis = last - 1, lastMillis = last, open = open, commands = 2, payload = byteArrayOf(1))

    private fun shard(name: String, payload: Int) = ConversationIndexRow(name, byteArrayOf(payload.toByte()))
}
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseMigrationTest.kt`, replace:

```kotlin
    }

    @Test
    fun version2MigratesToVersion3WithItsRowsAndAnEmptyConversationsTable() {
        migrations.createDatabase(MIGRATED, 2).apply {
            execSQL("INSERT INTO settings (name, payload) VALUES ('test-volume', X'07')")
            execSQL("INSERT INTO credentials (id, payload) VALUES ('test-credential', X'08')")
```

with:

```kotlin
    }

    @Test
    fun version2MigratesToVersion3WithItsRowsAndEmptyTablesOfConversationsAndTheirIndex() {
        migrations.createDatabase(MIGRATED, 2).apply {
            execSQL("INSERT INTO settings (name, payload) VALUES ('test-volume', X'07')")
            execSQL("INSERT INTO credentials (id, payload) VALUES ('test-credential', X'08')")
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseMigrationTest.kt`, replace:

```kotlin
        assertArrayEquals(byteArrayOf(8), migrated.blob("SELECT payload FROM credentials WHERE id = 'test-credential'"))
        assertArrayEquals(byteArrayOf(9), migrated.blob("SELECT payload FROM log_entries WHERE log = 'activity'"))
        assertEquals(0, migrated.count("conversations"))
    }

    private fun SupportSQLiteDatabase.blob(sql: String): ByteArray =
```

with:

```kotlin
        assertArrayEquals(byteArrayOf(8), migrated.blob("SELECT payload FROM credentials WHERE id = 'test-credential'"))
        assertArrayEquals(byteArrayOf(9), migrated.blob("SELECT payload FROM log_entries WHERE log = 'activity'"))
        assertEquals(0, migrated.count("conversations"))
        assertEquals(0, migrated.count("conversation_index"))
    }

    private fun SupportSQLiteDatabase.blob(sql: String): ByteArray =
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`, replace:

```kotlin
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
```

with:

```kotlin
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.conversations.ConversationChange
import dev.kestrel.privacy.conversations.IndexUpdate
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.IndexRowUpdate
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`, replace:

```kotlin
        val header = ConversationHeader("test-conversation-${System.nanoTime()}", 1, 2, open = false, commands = 1)
        try {
            store.conversations.write(listOf(PlainConversation(header, "first".encodeToByteArray())))
            assertTrue(keyStore.containsAlias(KeystoreKeys.CONVERSATIONS_KEY))

            store.conversations.deleteAll()

            assertTrue(store.conversations.readAll().isEmpty())
            assertFalse(keyStore.containsAlias(KeystoreKeys.CONVERSATIONS_KEY))
            store.conversations.write(listOf(PlainConversation(header, "second".encodeToByteArray())))
            val read = store.conversations.readAll().single() as StoredConversation.Readable
            assertEquals("second", read.payload.decodeToString())
        } finally {
```

with:

```kotlin
        val header = ConversationHeader("test-conversation-${System.nanoTime()}", 1, 2, open = false, commands = 1)
        try {
            store.conversations.write(ConversationChange(listOf(PlainConversation(header, "first".encodeToByteArray())), emptyList(), IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet())))
            assertTrue(keyStore.containsAlias(KeystoreKeys.CONVERSATIONS_KEY))

            store.conversations.deleteAll()

            assertTrue(store.conversations.read(listOf(header.id)).isEmpty())
            assertTrue(store.conversations.shards().isEmpty())
            assertFalse(keyStore.containsAlias(KeystoreKeys.CONVERSATIONS_KEY))
            store.conversations.write(ConversationChange(listOf(PlainConversation(header, "second".encodeToByteArray())), emptyList(), IndexUpdate.Keep))
            val read = store.conversations.read(listOf(header.id)).single() as StoredConversation.Readable
            assertEquals("second", read.payload.decodeToString())
        } finally {
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt`, replace:

```kotlin
        try {
            // A later version's ciphertext: its first byte names a format that this version does not know.
            dao.write(listOf(ConversationRow(id, 1, 2, open = false, commands = 1, payload = byteArrayOf(2) + ByteArray(40))))

            assertTrue(store.conversations.readAll().single { it.header.id == id } is StoredConversation.UnknownFormat)

            assertTrue(dao.all().any { it.id == id })
            val entry = store.logs.activity().single()
            assertEquals(ActivityKind.ERROR, entry.event.kind)
            assertEquals("A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it", entry.event.text)
        } finally {
            store.conversations.delete(id)
        }
    }
```

with:

```kotlin
        try {
            // A later version's ciphertext: its first byte names a format that this version does not know.
            dao.write(listOf(ConversationRow(id, 1, 2, open = false, commands = 1, payload = byteArrayOf(2) + ByteArray(40))), emptyList(), IndexRowUpdate.Keep)

            assertTrue(store.conversations.read(listOf(id)).single() is StoredConversation.UnknownFormat)

            assertEquals(listOf(id), dao.rows(listOf(id)).map { it.id })
            val entry = store.logs.activity().single()
            assertEquals(ActivityKind.ERROR, entry.event.kind)
            assertEquals("A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it", entry.event.text)
        } finally {
            store.conversations.write(ConversationChange(emptyList(), listOf(id), IndexUpdate.Keep))
        }
    }
```

Replace the whole `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/ConversationArchiveSpeedTest.kt` with:

```kotlin
package dev.kestrel.privacy

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.conversations.ConversationChange
import dev.kestrel.privacy.conversations.IndexUpdate
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.conversations.StoredShard
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the archive's reads and a close of 30 days of past conversations take (design 4.3 and R3 of sub-project 7): 300
 * closed conversations of 12 KB, an open one and an index in two shards of 240 and 210 KB, the size that plan 7B's test
 * fixture of 30 heavy days makes, are written to a database file, and read back through a new store each time, as after a
 * start of Kestrel: the open conversation (a restore), the index's shards and the stamp, the bodies of a search's 5 best matches in
 * one call, every closed conversation in pages of 50, as a rebuild of the index reads them, and one close, which writes a
 * conversation and its shard again. It is a measurement: it fails only when something does not read back, and it logs the
 * times under the tag KestrelArchive. Plan 7C's Task 8 decides on the A71's times. It runs only with the instrumentation
 * argument kestrelArchiveSpeed=true. It uses a test-named database file and a test key of this library's own test app,
 * never Kestrel's.
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
    fun measuresTheReadsAndACloseOf30DaysOfConversations() = runBlocking {
        assumeTrue(
            "Runs with -Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true",
            InstrumentationRegistry.getArguments().getString("kestrelArchiveSpeed") == "true",
        )
        val payload = ByteArray(PAYLOAD_BYTES) { ('a'.code + it % 26).toByte() }
        val now = System.currentTimeMillis()
        val conversations = List(CONVERSATIONS) { n ->
            val last = now - DAYS * DAY_MILLIS * (n + 1) / CONVERSATIONS
            PlainConversation(ConversationHeader("test-$n", last - 60_000, last, open = false, commands = 3), payload)
        } + PlainConversation(ConversationHeader("test-open", now - 60_000, now, open = true, commands = 3), payload)
        val shards = SHARD_BYTES.withIndex().associate { (n, size) -> n.toString() to ByteArray(size) { (it % 251).toByte() } }
        timed { it.write(ConversationChange(conversations, emptyList(), IndexUpdate.Replace(shards))) }

        val (open, openMillis) = timed { it.open() }
        val (index, indexMillis) = timed { store -> store.open(); store.shards() to store.stamp() }
        val (bodies, bodiesMillis) = timed { store -> store.open(); store.shards(); store.stamp(); store.read(List(MATCHES) { "test-${it * 7}" }) }
        val (rebuilt, rebuildMillis) = timed { store -> store.closedIds().chunked(PAGE).sumOf { page -> store.read(page).count { it is StoredConversation.Readable } } }
        val closing = PlainConversation(ConversationHeader("test-open", now - 60_000, now + 1, open = false, commands = 3), payload)
        val (_, closeMillis) = timed { it.write(ConversationChange(listOf(closing), emptyList(), IndexUpdate.Update(mapOf("1" to shards.getValue("1")), emptySet()))) }

        Log.i(TAG, "restore: the open conversation of ${PAYLOAD_BYTES / 1024} KB in $openMillis ms")
        Log.i(TAG, "restore and the index in ${SHARD_BYTES.size} shards of ${SHARD_BYTES.map { it / 1024 }} KB in $indexMillis ms")
        Log.i(TAG, "restore, the index and then $MATCHES conversations of ${PAYLOAD_BYTES / 1024} KB in $bodiesMillis ms")
        Log.i(TAG, "a rebuild's read of $rebuilt conversations of ${PAYLOAD_BYTES / 1024} KB in pages of $PAGE in $rebuildMillis ms")
        Log.i(TAG, "a close with a shard of ${SHARD_BYTES[1] / 1024} KB in $closeMillis ms")
        assertTrue(open is StoredConversation.Readable)
        assertEquals(SHARD_BYTES, index.first.sortedBy { it.name }.map { (it as StoredShard.Readable).payload.size })
        assertEquals(CONVERSATIONS, index.second.closed)
        assertEquals(MATCHES, bodies.count { it is StoredConversation.Readable })
        assertEquals(CONVERSATIONS, rebuilt)
    }

    /** [block]'s result and its time in ms, on a new database instance and a new store, as after a start of Kestrel. */
    private suspend fun <T> timed(block: suspend (ConversationArchiveStore) -> T): Pair<T, Long> {
        val database = Room.databaseBuilder(context, KestrelDatabase::class.java, FILE).build()
        try {
            val store = ConversationArchiveStore(database.conversationDao(), KeystoreErasableKey.of(alias))
            val started = SystemClock.elapsedRealtime()
            val result = block(store)
            return result to SystemClock.elapsedRealtime() - started
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TAG = "KestrelArchive"
        const val FILE = "test-archive-speed.db"
        const val CONVERSATIONS = 300
        const val PAYLOAD_BYTES = 12 * 1024
        val SHARD_BYTES = listOf(240 * 1024, 210 * 1024)
        const val PAGE = 50
        const val MATCHES = 5
        const val DAYS = 30L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
```

- [ ] **Step 2: Compile the tests to see them fail**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`
Expected: FAIL, the compiler reports `Unresolved reference 'StoredShard'`, `'IndexUpdate'`, `'closedIds'` and `'purge'`.

- [ ] **Step 3: The index's table, the reads, the purge and the change in one transaction**

Replace the whole `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt` with:

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
 * A shard of the index of past conversations (design 9.2 of sub-project 7): one row per shard, named by the shard's number,
 * whose payload plan 7B's store encodes and the archive encrypts under the key kestrel.conversations, bound to its name. The
 * shards have a table of their own, so that every query of the conversations' table reads conversations only.
 */
@Entity(tableName = "conversation_index")
class ConversationIndexRow(@PrimaryKey val name: String, val payload: ByteArray)

/**
 * The plain fields of a conversation's row, without its payload, as the archive takes and gives them. Its [open] is the
 * one truth about whether the conversation is open (design 6.1 of sub-project 7): the conversation's store reads it from
 * here; the payload does not hold it.
 */
data class ConversationHeader(
    val id: String,
    val openedMillis: Long,
    val lastMillis: Long,
    val open: Boolean,
    val commands: Int,
)

/** What the table holds of closed rows: how many, their newest last change and the sum of their last changes, 0 without rows. */
data class ConversationStamp(val closed: Int, val newest: Long, val total: Long)

/** What a write does to the shards' rows: keeps them; puts [Update.put] and deletes [Update.deleted]; or replaces them all with [Replace.rows]. */
sealed interface IndexRowUpdate {
    data object Keep : IndexRowUpdate

    class Update(val put: List<ConversationIndexRow>, val deleted: List<String>) : IndexRowUpdate

    class Replace(val rows: List<ConversationIndexRow>) : IndexRowUpdate
}

/**
 * The tables of conversations and of their index. Its functions block: `ConversationArchiveStore` calls them on its own
 * dispatcher, so that a query and the decryption of its rows run on one thread, with no hop between them (design R3 of
 * sub-project 7). It keeps at most one conversation open (design 6.1): [write] closes the other open rows in the same
 * transaction as it stores an open one, and nothing else opens a row.
 */
@Dao
abstract class ConversationDao {
    /** The open conversation's row, or null: what a restore reads. */
    @Query("SELECT * FROM conversations WHERE open = 1 LIMIT 1")
    abstract fun open(): ConversationRow?

    /** Every shard's row of the index. */
    @Query("SELECT * FROM conversation_index")
    abstract fun shards(): List<ConversationIndexRow>

    /** What the table holds of closed rows: how many, their newest last change and the sum of their last changes. */
    @Query("SELECT COUNT(*) AS closed, COALESCE(MAX(last_millis), 0) AS newest, COALESCE(SUM(last_millis), 0) AS total FROM conversations WHERE open = 0")
    abstract fun stamp(): ConversationStamp

    /** The conversations [ids] that exist, in one query (design 9.2 of sub-project 7): a search's matches, a transcript, a rebuild's page. */
    @Query("SELECT * FROM conversations WHERE id IN (:ids)")
    abstract fun rows(ids: List<String>): List<ConversationRow>

    /** The ids of the closed rows, the oldest last change first, without their payloads: what a rebuild reads in pages. */
    @Query("SELECT id FROM conversations WHERE open = 0 ORDER BY last_millis ASC")
    abstract fun closedIds(): List<String>

    /**
     * One transaction, so that a switch to another conversation (design 9.6 of sub-project 7) and the index that goes with
     * a change are never stored halfway: deletes [deleted], stores [rows], closing every other open row before an open one,
     * and changes the shards' rows as [index] says.
     */
    @Transaction
    open fun write(rows: List<ConversationRow>, deleted: List<String>, index: IndexRowUpdate) {
        for (id in deleted) delete(id)
        for (row in rows) {
            if (row.open) closeOthers(row.id)
            upsert(row)
        }
        change(index)
    }

    /**
     * Retention (design 9.3 of sub-project 7), in one transaction: deletes every closed row whose last change is before
     * [oldest] or after [newest], by the table's own times, and changes the shards' rows as [index] says; returns how many
     * rows went.
     */
    @Transaction
    open fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int = deleteClosedOutside(oldest, newest).also { change(index) }

    /** Deletes every conversation, the open one too, and the index, in one transaction. */
    @Transaction
    open fun deleteAll() {
        deleteConversations()
        deleteIndexes()
    }

    private fun change(index: IndexRowUpdate) {
        when (index) {
            IndexRowUpdate.Keep -> Unit
            is IndexRowUpdate.Update -> {
                for (name in index.deleted) deleteIndex(name)
                for (row in index.put) upsertIndex(row)
            }
            is IndexRowUpdate.Replace -> {
                deleteIndexes()
                for (row in index.rows) upsertIndex(row)
            }
        }
    }

    @Query("DELETE FROM conversations WHERE id = :id")
    protected abstract fun delete(id: String): Int

    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    protected abstract fun deleteClosedOutside(oldest: Long, newest: Long): Int

    @Upsert
    protected abstract fun upsert(row: ConversationRow)

    @Query("UPDATE conversations SET open = 0 WHERE open = 1 AND id != :id")
    protected abstract fun closeOthers(id: String): Int

    @Upsert
    protected abstract fun upsertIndex(row: ConversationIndexRow)

    @Query("DELETE FROM conversation_index WHERE name = :name")
    protected abstract fun deleteIndex(name: String): Int

    @Query("DELETE FROM conversations")
    protected abstract fun deleteConversations(): Int

    @Query("DELETE FROM conversation_index")
    protected abstract fun deleteIndexes(): Int
}
```

In `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt`, replace:

```kotlin
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
```

with:

```kotlin
import androidx.room.RoomDatabase

/**
 * Version 2 adds the table `credentials` (sub-project 4), and version 3 the tables `conversations` and `conversation_index`
 * (plan 7C of sub-project 7). Room migrates each earlier version by itself.
 */
@Database(
    entities = [LogEntryRow::class, SettingRow::class, CredentialRow::class, ConversationRow::class, ConversationIndexRow::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
```

Replace the whole `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt` with:

```kotlin
package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.ConversationStamp
import dev.kestrel.privacy.db.IndexRowUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
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
 * A shard of the index as the archive reads it back, by its row's name: its payload, decrypted, or the mark that it failed
 * its check or is in a cipher's format that this version cannot read, which plan 7B's store treats alike, by rebuilding
 * the index.
 */
sealed interface StoredShard {
    val name: String

    class Readable(override val name: String, val payload: ByteArray) : StoredShard {
        override fun toString(): String = "Readable(${payload.size} bytes)"
    }

    class Unreadable(override val name: String) : StoredShard {
        override fun toString(): String = "Unreadable"
    }
}

/**
 * What a change does to the index's shards, each a plain payload by its name that the archive encrypts: keeps them;
 * puts [Update.put] and deletes [Update.deleted]; or deletes them all and stores [Replace.shards].
 */
sealed interface IndexUpdate {
    data object Keep : IndexUpdate

    class Update(val put: Map<String, ByteArray>, val deleted: Set<String>) : IndexUpdate {
        override fun toString(): String = "Update(put=${put.size}, deleted=${deleted.size})"
    }

    class Replace(val shards: Map<String, ByteArray>) : IndexUpdate {
        override fun toString(): String = "Replace(${shards.size} shards)"
    }
}

/** One change, in one transaction: [conversations] stored, [deleted] deleted, and the index as [index] says. */
class ConversationChange(val conversations: List<PlainConversation>, val deleted: Collection<String>, val index: IndexUpdate) {
    override fun toString(): String = "ConversationChange(conversations=${conversations.size}, deleted=${deleted.size}, index=$index)"
}

/**
 * The archive of past conversations (design 9.1 and 9.2 of sub-project 7): one row per conversation, whose payload is
 * encrypted under its own erasable key and bound to the conversation's id, and the index of past conversations in rows of
 * their own, one per shard, encrypted under the same key and each bound to its name, so that no payload can be moved to
 * another row. It knows only bytes: `:app` gives it the conversation's JSON and plan 7B's encoded shards. Only the id, the
 * open and last-change times, whether the conversation is open and its number of commands are plain, for sorting and
 * retention.
 *
 * Every read runs its query and decrypts its rows on [dispatcher], on one thread, with no hop between the two, because a
 * Keystore operation after a hop costs about twice as much on the A71 (design R3): a restore is one Keystore operation,
 * the index one per shard, and a body one more. The stamp is a plain query. A read stops between two rows when its caller
 * is cancelled. Reads take no guard, because they store nothing, so that a rebuild's long read never holds up a write.
 * Writes and [deleteAll] run under the key's [ErasableKey.guard], which every user of the key shares, so that [deleteAll]
 * never erases the key between the encryption of a write and its row. The functions are safe to call from the main thread.
 *
 * Only a write makes the key: a read decrypts each row with the key as it is ([ErasableKey.withExisting]), and the key's
 * erasure waits for a decryption that has started. So a read that overlaps [deleteAll] ("delete history") never fails
 * because the key went, and never makes a new key. It sees each row as one of three: as it was, before the erasure; once
 * the key is gone and until the rows are deleted, a conversation as [StoredConversation.FailedCheck] (one in a format that
 * this version cannot read stays [StoredConversation.UnknownFormat]) and a shard as [StoredShard.Unreadable]; and
 * afterwards not at all. Its caller orders the two (plan 7B's store does). A read with no key at all sees the same as in
 * the second case.
 *
 * It deletes no row on its own: plan 7B's store deletes a conversation that fails its check, and reports it. A
 * conversation in a format that this version cannot read is kept, and [onUnknownFormat] hears of it once per process; when
 * [onUnknownFormat] throws, the read fails, and the next read of the conversation reports it again.
 */
class ConversationArchiveStore(
    private val dao: ConversationDao,
    private val key: ErasableKey,
    private val onUnknownFormat: suspend () -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Writes encrypt with this cipher, which makes the key when there is none; reads decrypt with the key as it is.
    private val sealing = AesGcmFieldCipher { key.get() }

    // The ids of the rows in an unknown format whose report was made, so that each is reported once per process; a report
    // that failed or was cancelled is made again at the next read. Used only under [reports].
    private val reports = Mutex()
    private val reportedUnknown = mutableSetOf<String>()

    /** The open conversation, or null: what plan 7B's store reads first, so that a restore waits for one decryption. */
    suspend fun open(): StoredConversation? {
        val read = withContext(dispatcher) { dao.open()?.let(::stored) }
        report(listOfNotNull(read))
        return read
    }

    /** The index's shards, none when there is no index, each decrypted right after the query, on the same thread. */
    suspend fun shards(): List<StoredShard> = withContext(dispatcher) { dao.shards().map { row -> ensureActive(); shard(row) } }

    /** The stamp of the closed rows (how many, their newest last change and the sum of their last changes), in one query. */
    suspend fun stamp(): ConversationStamp = withContext(dispatcher) { dao.stamp() }

    /** The conversations [ids] that exist, in one query, each decrypted on the same thread: a search's matches, a transcript, a rebuild's page. */
    suspend fun read(ids: Collection<String>): List<StoredConversation> {
        val stored = withContext(dispatcher) { dao.rows(ids.toList()).map { row -> ensureActive(); stored(row) } }
        report(stored)
        return stored
    }

    /** The ids of the closed conversations, the oldest last change first, without their payloads: what a rebuild reads in pages. */
    suspend fun closedIds(): List<String> = withContext(dispatcher) { dao.closedIds() }

    /**
     * Stores [change] in one transaction: its conversations, each replacing its earlier row, its deletions, and its index.
     * Before an open conversation is stored, every other open one closes, so that at most one is open (design 6.1 of
     * sub-project 7). Every payload and shard is encrypted on one thread, right before the transaction.
     */
    suspend fun write(change: ConversationChange) {
        key.guard.withLock {
            withContext(dispatcher) {
                val rows = change.conversations.map {
                    val header = it.header
                    ConversationRow(header.id, header.openedMillis, header.lastMillis, header.open, header.commands, sealing.encrypt(it.payload, associatedData(header.id)))
                }
                dao.write(rows, change.deleted.toList(), encrypted(change.index))
            }
        }
        reports.withLock {
            reportedUnknown -= change.conversations.map { it.header.id }.toSet()
            reportedUnknown -= change.deleted.toSet()
        }
    }

    /**
     * Retention (design 9.3 of sub-project 7), in one transaction: deletes every closed conversation whose last change is
     * before [oldest] or after [newest], by the table's own times, and changes the index as [index] says; returns how many
     * conversations went.
     */
    suspend fun purge(oldest: Long, newest: Long, index: IndexUpdate): Int =
        key.guard.withLock { withContext(dispatcher) { dao.purge(oldest, newest, encrypted(index)) } }

    /**
     * "Delete history" (design 9.7 of sub-project 7): erases the key first, so that nothing stored can be decrypted any more,
     * also when the deletion that follows does not complete, and then deletes every conversation, the open one too, and the
     * index. Plan 7B's store deletes a row that fails its check, so a deletion cut short leaves nothing that can be read.
     * The next write makes a new key.
     */
    suspend fun deleteAll() {
        key.guard.withLock {
            withContext(dispatcher) {
                key.erase()
                dao.deleteAll()
            }
        }
        reports.withLock { reportedUnknown.clear() }
    }

    private fun encrypted(update: IndexUpdate): IndexRowUpdate = when (update) {
        IndexUpdate.Keep -> IndexRowUpdate.Keep
        is IndexUpdate.Update -> IndexRowUpdate.Update(update.put.map { (name, payload) -> shardRow(name, payload) }, update.deleted.toList())
        is IndexUpdate.Replace -> IndexRowUpdate.Replace(update.shards.map { (name, payload) -> shardRow(name, payload) })
    }

    private fun shardRow(name: String, payload: ByteArray) = ConversationIndexRow(name, sealing.encrypt(payload, indexData(name)))

    // Each conversation in an unknown format that has not been reported in this process yet, once its read has ended.
    private suspend fun report(conversations: List<StoredConversation>) {
        val unknown = conversations.filterIsInstance<StoredConversation.UnknownFormat>()
        if (unknown.isEmpty()) return
        reports.withLock {
            for (conversation in unknown) {
                if (conversation.header.id in reportedUnknown) continue
                onUnknownFormat()
                reportedUnknown += conversation.header.id
            }
        }
    }

    private fun stored(row: ConversationRow): StoredConversation {
        val header = ConversationHeader(row.id, row.openedMillis, row.lastMillis, row.open, row.commands)
        return try {
            StoredConversation.Readable(header, opened(row.payload, associatedData(row.id)))
        } catch (e: AEADBadTagException) {
            StoredConversation.FailedCheck(header)
        } catch (e: IllegalArgumentException) {
            StoredConversation.UnknownFormat(header)
        }
    }

    private fun shard(row: ConversationIndexRow): StoredShard = try {
        StoredShard.Readable(row.name, opened(row.payload, indexData(row.name)))
    } catch (e: AEADBadTagException) {
        StoredShard.Unreadable(row.name)
    } catch (e: IllegalArgumentException) {
        StoredShard.Unreadable(row.name)
    }

    // Decrypts with the key as it is, or fails the check when there is none, so that a read never makes the key.
    private fun opened(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        key.withExisting { existing -> AesGcmFieldCipher { existing }.decrypt(ciphertext, associatedData) }

    private fun associatedData(id: String) = "conversations/$id".encodeToByteArray()

    // A shard's associated data, which no conversation's can be, because the prefixes differ.
    private fun indexData(name: String) = "conversation_index/$name".encodeToByteArray()
}
```

- [ ] **Step 4: Build, which generates version 3's schema again**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest :core:privacy:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL, N + 7 tests (84).

Run: `(Select-String -Path core\privacy\schemas\dev.kestrel.privacy.db.KestrelDatabase\3.json -SimpleMatch -Pattern '"tableName": "conversation_index"').Count; git status --short core/privacy/schemas`
Expected: `1`, and only `3.json` modified: Room wrote version 3's schema again with the new table and a new identity hash. `1.json` and `2.json` are unchanged.

- [ ] **Step 5: The device tests on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL, every test of `:core:privacy` passes (N + 3, one of them skipped: the speed test without its argument); the script ends with exit code 0. Afterwards `run-as dev.kestrel.privacy.test ls databases` lists nothing. The migration from version 2 to 3 validates against the new `3.json`, with an empty `conversation_index`, and `theConnectionThatWritesOverwritesWhatItDeletes` finds `secure_delete` on.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & (Get-Adb) -s emulator-5554 logcat -c; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.ConversationArchiveSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true'; & (Get-Adb) -s emulator-5554 logcat -d -s KestrelArchive:I; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL, 1 test, none failed or skipped; the five log lines `restore: the open conversation of 12 KB in <n> ms`, `restore and the index in 2 shards of [240, 210] KB in <n> ms`, `restore, the index and then 5 conversations of 12 KB in <n> ms`, `a rebuild's read of 300 conversations of 12 KB in pages of 50 in <n> ms` and `a close with a shard of 210 KB in <n> ms`. Note the five times in the task's report; on kestrel-api37 they are only noted, and the A71's decide (Task 8).

- [ ] **Step 6: Commit**

```powershell
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/3.json core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseMigrationTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/PrivacyStoreTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/ConversationArchiveSpeedTest.kt
git commit -m "feat(privacy): the index of past conversations in shards of a table of its own under the archive's key, a restore that reads the open row alone, the shards and the stamp read apart, reads by id that stop when cancelled, a purge by the table's own times, and delete history that erases the key first (plan 7C, design R3)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: `RoomRecordArchive`, plan 7B's port on the archive

Starts only after plan 7B's Tasks 5a to 5d are carried out (see "Before you start"). `:app` implements `:core:conversation`'s `RecordArchive` with `:core:privacy`'s archive (design 5.1 and 9.2): the snapshot's JSON and the index's shards travel as the bytes that plan 7B's store gives, the plain fields and the stamp as they are, and each kind of stored conversation and of stored shard as its kind of entry.

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt`

**Interfaces:**
- Consumes: plan 7B's `RecordArchive` (`readOpen`, `readShards`, `readStamp`, `read`, `closedIds`, `write(ArchiveChange)`, `purge(oldest, newest, IndexWrite)`, `deleteAll`), `ShardRead`, `IndexStamp`, `ArchiveChange`, `IndexWrite` (`Keep`, `Update`, `Replace`), `ArchiveHeader`, `ArchiveRow`, `ArchiveEntry` (with `Unknown`), `ArchiveNote`, and `ArchivedConversationStore(archive, scope, noted)`, `ConversationSnapshot` (whose `open` is `@Transient`), `CommandRecord` and `ConversationId`, whose store takes `open` and the times from the header (sections "Interfaces consumed from plan 7B" and "Plan 7B as committed"); Task 4b's `ConversationArchiveStore` (`open`, `shards`, `stamp`, `read`, `closedIds`, `write(ConversationChange)`, `purge`, `deleteAll`), `PlainConversation`, `StoredConversation`, `StoredShard`, `ConversationChange` and `IndexUpdate`; Task 4b's `ConversationHeader`, `ConversationStamp`, `ConversationRow`, `ConversationIndexRow`, `IndexRowUpdate` and `KestrelDatabase.conversationDao()`; Task 2's `KeystoreErasableKey.of`.
- Produces: `:app`'s dependency on `:core:conversation`; package `dev.kestrel.app.conversation`: `internal class RoomRecordArchive(table: ConversationArchiveStore) : RecordArchive`. Task 6 builds it on `PrivacyStore.conversations`.

Counts: `:app` JVM unchanged (A₀). `:app` device tests AD₀ + 6 (`RoomRecordArchiveTest` 6), run in Task 8.

- [ ] **Step 1: Write the failing device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.store.ArchiveChange
import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveHeader
import dev.kestrel.conversation.store.ArchiveNote
import dev.kestrel.conversation.store.ArchiveRow
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.IndexStamp
import dev.kestrel.conversation.store.IndexWrite
import dev.kestrel.conversation.store.ShardRead
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.IndexRowUpdate
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
 * The archive's port on the real encrypted tables and the device's Keystore (design 9.1 and 9.2 of sub-project 7). It uses
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
        archive.write(ArchiveChange(rows = listOf(row(past, """{"request":"Пошаљи Мили поруку 🙂"}"""), row(current, """{"request":"Turn on dark mode"}"""))))

        val read = archive.read(listOf(past.id, current.id, "gone")).associateBy { it.header.id }
        assertEquals(setOf(past, current), read.values.map { it.header }.toSet())
        assertEquals("""{"request":"Пошаљи Мили поруку 🙂"}""", (read.getValue(past.id) as ArchiveEntry.Readable).payload.decodeToString())
        assertEquals(current, archive.readOpen()!!.header)
        assertEquals("""{"request":"Turn on dark mode"}""", (archive.readOpen() as ArchiveEntry.Readable).payload.decodeToString())
        assertEquals(listOf(past.id), archive.closedIds())
        assertFalse(database.conversationDao().rows(listOf(past.id)).single().payload.decodeToString(throwOnInvalidSequence = false).contains("Мили"))

        // A switch (design 9.6): the open conversation closes and the past one opens, in one write.
        archive.write(ArchiveChange(rows = listOf(row(current.copy(open = false, lastMillis = 50), "{}"), row(past.copy(open = true, lastMillis = 50), "{}"))))
        assertEquals(setOf(current.id to false, past.id to true), archive.read(listOf(past.id, current.id)).map { it.header.id to it.header.open }.toSet())

        // Retention (design 9.3) deletes the closed rows outside its window, by the table's times, never the open one.
        assertEquals(1, archive.purge(oldest = 60, newest = 100, index = IndexWrite.Keep))
        assertEquals(listOf(past.id), archive.read(listOf(past.id, current.id)).map { it.header.id })

        archive.write(ArchiveChange(deleted = setOf(past.id)))
        assertNull(archive.readOpen())
    }

    // Design 9.2 of sub-project 7: the shards travel encrypted, each under its name, and the closed rows' stamp is read apart.
    @Test
    fun theShardsTravelEncryptedAndTheStampCountsTheClosedRows() = runBlocking {
        assertTrue(archive.readShards().isEmpty())

        archive.write(ArchiveChange(rows = listOf(row(past, "{}"), row(current, "{}")), index = IndexWrite.Replace(mapOf("0" to "Мила Ambar".encodeToByteArray(), "1" to "Viber".encodeToByteArray()))))

        assertEquals(mapOf("0" to "Мила Ambar", "1" to "Viber"), archive.readShards().associate { it.name to (it as ShardRead.Readable).bytes.decodeToString() })
        assertEquals(IndexStamp(closed = 1, newest = 20, total = 20), archive.readStamp())
        assertFalse(database.conversationDao().shards().any { it.payload.decodeToString(throwOnInvalidSequence = false).contains("Ambar") })

        archive.write(ArchiveChange(index = IndexWrite.Update(mapOf("1" to "Signal".encodeToByteArray()), setOf("0"))))
        assertEquals(mapOf("1" to "Signal"), archive.readShards().associate { it.name to (it as ShardRead.Readable).bytes.decodeToString() })
        archive.write(ArchiveChange(index = IndexWrite.Replace(emptyMap())))
        assertTrue(archive.readShards().isEmpty())
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes an Unreadable entry, leaves an Unknown one out and rebuilds an unreadable index.
    @Test
    fun eachKindOfUnreadableRowBecomesItsKindOfEntry() = runBlocking {
        archive.write(ArchiveChange(rows = listOf(row(past, "{}")), index = IndexWrite.Replace(mapOf("0" to byteArrayOf(1)))))
        val dao = database.conversationDao()
        val sealed = dao.rows(listOf(past.id)).single().payload
        // A later version's ciphertext: its first byte names a format that this version does not know.
        val later = byteArrayOf(2) + ByteArray(40)
        dao.write(
            listOf(ConversationRow("moved", 1, 2, open = false, commands = 1, payload = sealed), ConversationRow("later", 1, 2, open = false, commands = 1, payload = later)),
            emptyList(),
            IndexRowUpdate.Update(listOf(ConversationIndexRow("0", sealed)), emptyList()),
        )

        val read = archive.read(listOf(past.id, "moved", "later")).associateBy { it.header.id }

        assertTrue(read.getValue(past.id) is ArchiveEntry.Readable)
        assertTrue(read.getValue("moved") is ArchiveEntry.Unreadable)
        assertEquals(ArchiveHeader("moved", 1, 2, open = false, commands = 1), read.getValue("moved").header)
        assertTrue(read.getValue("later") is ArchiveEntry.Unknown)
        assertTrue(archive.readShards().single() is ShardRead.Unreadable)
    }

    // Design 9.7 of sub-project 7: deleting through the port erases the archive's key with its rows and its index.
    @Test
    fun deleteAllErasesTheRowsTheIndexAndTheKey() = runBlocking {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        archive.write(ArchiveChange(rows = listOf(row(current, "{}")), index = IndexWrite.Replace(mapOf("0" to byteArrayOf(1)))))
        assertTrue(keyStore.containsAlias(alias))

        archive.deleteAll()

        assertNull(archive.readOpen())
        assertTrue(archive.readShards().isEmpty())
        assertFalse(keyStore.containsAlias(alias))
    }

    // Design 6.1 of sub-project 7: the table's open column is the one truth about which conversation is open, and the
    // snapshot's JSON does not hold it. When a second open conversation is written, the table closes the first, and the
    // index takes it in the same transaction; after a restart, the store reads the first as closed, and the index that it
    // reads is in step with the table's stamp, so that nothing is rebuilt.
    @Test
    fun theTablesOpenColumnDecidesWhichConversationIsOpen() = runBlocking {
        val first = ConversationSnapshot(ConversationId(past.id), openedAtWall = 10, lastWall = 20, open = true, testMode = true)
        val second = ConversationSnapshot(ConversationId(current.id), openedAtWall = 30, lastWall = 40, open = true, testMode = true)
        val writing = ArchivedConversationStore(archive, this) {}
        writing.save(first)
        writing.save(second)
        val notes = mutableListOf<ArchiveNote>()

        val restarted = ArchivedConversationStore(archive, this) { notes += it }

        assertEquals(listOf(current.id to true, past.id to false), archive.read(listOf(current.id, past.id)).map { it.header.id to it.header.open })
        assertEquals(second.id, restarted.open()?.id)
        assertEquals(listOf(first.id), restarted.list().map { it.id })
        assertTrue(notes.isEmpty())
    }

    // Design 9.2 of sub-project 7: a store on the real archive rebuilds a lost index from the encrypted bodies.
    @Test
    fun aStoreOnTheEncryptedArchiveRebuildsALostIndex() = runBlocking {
        val writing = ArchivedConversationStore(archive, this) {}
        for (n in 1..3) {
            val at = n * 1_000L
            writing.save(ConversationSnapshot(ConversationId("$n".padStart(32, '0')), at, at, open = false, testMode = true, records = listOf(CommandRecord(1, at, InputChannel.TEXT, "Request $n"))))
        }
        val listed = writing.list()
        archive.write(ArchiveChange(index = IndexWrite.Replace(emptyMap())))
        val notes = mutableListOf<ArchiveNote>()

        val restarted = ArchivedConversationStore(archive, this) { notes += it }

        assertEquals(listed, restarted.list())
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
        assertTrue(archive.readShards().isNotEmpty())
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

import dev.kestrel.conversation.store.ArchiveChange
import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveHeader
import dev.kestrel.conversation.store.IndexStamp
import dev.kestrel.conversation.store.IndexWrite
import dev.kestrel.conversation.store.RecordArchive
import dev.kestrel.conversation.store.ShardRead
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.conversations.ConversationChange
import dev.kestrel.privacy.conversations.IndexUpdate
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.conversations.StoredShard
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationStamp

/**
 * :core:conversation's archive on :core:privacy's encrypted tables (design 9.2 of sub-project 7). The payload, the
 * snapshot's JSON, and the shards of the index of past conversations travel as the bytes that the conversation's store
 * gives, and the tables encrypt them under the key kestrel.conversations; the plain fields stay plain, for sorting and
 * retention. A payload that fails its check becomes [ArchiveEntry.Unreadable], which the conversation's store deletes and
 * reports; one in a format that this version cannot read becomes [ArchiveEntry.Unknown], which it leaves out and the table
 * keeps; a shard that cannot be read becomes [ShardRead.Unreadable], and the store rebuilds the index.
 */
internal class RoomRecordArchive(private val table: ConversationArchiveStore) : RecordArchive {
    override suspend fun readOpen(): ArchiveEntry? = table.open()?.archived()

    override suspend fun readShards(): List<ShardRead> = table.shards().map { it.archived() }

    override suspend fun readStamp(): IndexStamp = table.stamp().archived()

    override suspend fun read(ids: Collection<String>): List<ArchiveEntry> = table.read(ids).map { it.archived() }

    override suspend fun closedIds(): List<String> = table.closedIds()

    override suspend fun write(change: ArchiveChange) {
        table.write(ConversationChange(change.rows.map { PlainConversation(it.header.plain(), it.payload) }, change.deleted, change.index.plain()))
    }

    override suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int = table.purge(oldest, newest, index.plain())

    override suspend fun deleteAll() {
        table.deleteAll()
    }
}

private fun StoredConversation.archived(): ArchiveEntry =
    when (this) {
        is StoredConversation.Readable -> ArchiveEntry.Readable(header.archived(), payload)
        is StoredConversation.FailedCheck -> ArchiveEntry.Unreadable(header.archived())
        is StoredConversation.UnknownFormat -> ArchiveEntry.Unknown(header.archived())
    }

private fun StoredShard.archived(): ShardRead =
    when (this) {
        is StoredShard.Readable -> ShardRead.Readable(name, payload)
        is StoredShard.Unreadable -> ShardRead.Unreadable(name)
    }

private fun IndexWrite.plain(): IndexUpdate =
    when (this) {
        IndexWrite.Keep -> IndexUpdate.Keep
        is IndexWrite.Update -> IndexUpdate.Update(put, deleted)
        is IndexWrite.Replace -> IndexUpdate.Replace(shards)
    }

private fun ConversationHeader.archived() = ArchiveHeader(id, openedMillis, lastMillis, open, commands)

private fun ConversationStamp.archived() = IndexStamp(closed, newest, total)

private fun ArchiveHeader.plain() = ConversationHeader(id, openedMillis, lastMillis, open, commands)
```

- [ ] **Step 4: Build**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, A₀ tests. The device tests run in Task 8.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add app/build.gradle.kts app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt
git commit -m "feat(app): RoomRecordArchive implements the conversation's archive port on the encrypted tables of :core:privacy (plan 7C)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The store, its report, the clock and the purge job in the app graph

`AppGraph` builds plan 7B's `ArchivedConversationStore` on `RoomRecordArchive` and `PrivacyStore.conversations` (one instance, whose index and open conversation in memory plan 7D's engine, search and local requests share, and which rebuilds a lost index in `AppGraph.scope`), writes what the store notes into the activity log by its kind only (a conversation that it deleted unread, an index of past conversations that it rebuilt, and an index that it could not read or rebuild), and builds plan 7B's `Retention` with the phone's clock, `AndroidConversationClock`, and the owner's retention from the new setting `conversations` (design 5.6, 9.2, 9.3 and 13). `KestrelApplication` runs the purge job from Kestrel's start for the life of the process.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/AndroidConversationClock.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`

**Interfaces:**
- Consumes: Task 4's `PrivacyStore.conversations`; Task 5's `RoomRecordArchive`; plan 7B's `ArchivedConversationStore(archive, scope, noted)` with `ArchiveNote`, `Retention(store, clock, report).keepPurging(days)`, `ConversationClock` and `ConversationSettings` with its `keptDays` (section "Interfaces consumed from plan 7B"); `AppGraph`'s `scope`, `logs`, `settings` and `reportFailure`.
- Produces: `internal object AndroidConversationClock : ConversationClock` (package `dev.kestrel.app.conversation`; `System.currentTimeMillis()` and `ZoneId.systemDefault()`); `SettingKeys.CONVERSATIONS: SettingKey<ConversationSettings>` (stored as `conversations`); `AppGraph.conversationStore: ArchivedConversationStore` (`internal`, built on first use); `internal suspend fun AppGraph.keepPurgingConversations()`, which runs `Retention.keepPurging` with the setting's `keptDays` (the retention within its choices, never the raw `retentionDays`) and never returns while the setting's flow lives; the activity log's texts "A conversation could not be read, so Kestrel deleted it" (an error), "The index of past conversations could not be used, so Kestrel rebuilt it from the conversations" (a note) and "Kestrel could not read the index of past conversations and tries again later" (an error). Plan 7D builds its engine, `HistorySearch` and `LocalRequests` on `conversationStore` and uses `AndroidConversationClock`; plan 7F's Conversations page writes `SettingKeys.CONVERSATIONS`.

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
import dev.kestrel.conversation.store.ArchiveNote
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
     * :core:privacy's encrypted tables under their own key. There is one, so that the index of past conversations and the
     * open conversation that it keeps in memory follow every change: plan 7D's engine, search and local requests use it. It
     * rebuilds a lost index in [scope]. What it notes goes into the activity log by its kind only (design 9.2).
     */
    internal val conversationStore: ArchivedConversationStore by lazy {
        ArchivedConversationStore(RoomRecordArchive(store.conversations), scope) { note -> scope.launch { logs.append(archiveEntry(note)) } }
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

    /** The activity log's entry for what the store of conversations noted, which names no conversation (design 9.2 of sub-project 7). */
    private fun archiveEntry(note: ArchiveNote): ActivityEvent =
        when (note) {
            ArchiveNote.CONVERSATION_DELETED -> ActivityEvent(ActivityKind.ERROR, "A conversation could not be read, so Kestrel deleted it")
            ArchiveNote.INDEX_REBUILT -> ActivityEvent(ActivityKind.NOTE, "The index of past conversations could not be used, so Kestrel rebuilt it from the conversations")
            ArchiveNote.INDEX_FAILED -> ActivityEvent(ActivityKind.ERROR, "Kestrel could not read the index of past conversations and tries again later")
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

The design's amendments of 2026-11-25 and 2026-11-26 (commits of `docs(spec)`, the archive's index) already describe the archive as this plan builds it with Task 4b and plan 7B with its Tasks 5a to 5d: the abstract DAO whose writes keep one conversation open, the table of the index's shards, the restore that reads the open row alone, the shards and the stamp read apart, the reads by id, the change and the purge in one transaction, the two kinds of unreadable conversation, the key outside StrongBox and erased first, and plan 7B's `ConversationStore`. This task checks that the design and the code still say the same; it changes nothing when they do.

**Files:**
- None, unless a check fails (then stop and ask the controller).

**Interfaces:**
- Consumes: Tasks 1 to 6; the design's sections 9.1 and 9.2.
- Produces: nothing new.

Counts: no test changes.

- [ ] **Step 1: Check the design's words**

Run: `$spec = 'docs\superpowers\specs\2026-11-24-conversations-design.md'; foreach ($text in 'closeAll', 'openOne', 'encrypt, decrypt, put', '@Dao interface ConversationDao', 'fun headers()', 'deleteOutside', 'conversation_index/past', 'fun first()', 'never made in StrongBox', '@Dao abstract class ConversationDao', 'class ConversationIndexRow', 'conversation_index/<number>', 'abstract fun shards(): List<ConversationIndexRow>', '@Transaction open fun purge(', 'suspend fun searchable(): Searchable', 'the one truth about which conversation is open', 'Why a table of its own', 'SQLITE_SECURE_DELETE') { "$text : $((Select-String -Path $spec -SimpleMatch -CaseSensitive -Pattern $text).Count)" }`
Expected: `closeAll : 0`, `openOne : 0`, `encrypt, decrypt, put : 0`, `@Dao interface ConversationDao : 0`, `fun headers() : 0`, `deleteOutside : 0`, `conversation_index/past : 0`, `fun first() : 0`, `never made in StrongBox : 1`, `@Dao abstract class ConversationDao : 1`, `class ConversationIndexRow : 1`, `conversation_index/<number> : 1`, `abstract fun shards(): List<ConversationIndexRow> : 1`, `@Transaction open fun purge( : 1`, `suspend fun searchable(): Searchable : 1`, `the one truth about which conversation is open : 1`, `Why a table of its own : 1`, `SQLITE_SECURE_DELETE : 1`. A different count means that the code or the design moved: stop and ask the controller, who brings them back in line in one commit.

---

### Task 8: The checks on kestrel-api37 and the A71 (the controller's steps)

The controller runs every device test of this plan, installs this build on kestrel-api37 so that Kestrel's own database migrates from version 2 to 3 with the owner's data in it (design 15.2: "the Room migration from version 2 to 3 with the real Keystore"), and measures the archive's speed on the A71 (design 4.3 and R3).

**Files:**
- Modify: `AGENTS.md` (Step 2: the rule that only builds with plan 7C go onto kestrel-api37)
- Every other step is a device step.

**Interfaces:**
- Consumes: every earlier task; `scripts\connected-test.ps1`, `scripts\install.ps1`, `scripts\hook.ps1`.
- Produces: the rule that only builds with plan 7C go onto kestrel-api37, in AGENTS.md and `.superpowers\sdd\progress.md`; the archive's speed on kestrel-api37 and the A71, and the result of the migration, in `.superpowers\sdd\progress.md`.

Counts: `:core:privacy` device tests D₀ + 13 run, 1 of them skipped without its argument; `:app` device tests AD₀ + 7, of which this task runs `RoomRecordArchiveTest` (6) and `AppGraphTest` (its tests at `a4558faf` + 1).

- [ ] **Step 1: Every device test of `:core:privacy` on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL, D₀ + 13 tests, none failed, 1 skipped (`ConversationArchiveSpeedTest`); the script ends with exit code 0.

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

- [ ] **Step 5: The archive's speed on kestrel-api37 and on the A71**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & (Get-Adb) -s emulator-5554 logcat -c; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.ConversationArchiveSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true'; & (Get-Adb) -s emulator-5554 logcat -d -s KestrelArchive:I`
Expected: BUILD SUCCESSFUL, 1 test passed; the five lines `restore: the open conversation of 12 KB in <n> ms`, `restore and the index in 2 shards of [240, 210] KB in <n> ms`, `restore, the index and then 5 conversations of 12 KB in <n> ms`, `a rebuild's read of 300 conversations of 12 KB in pages of 50 in <n> ms` and `a close with a shard of 210 KB in <n> ms`.

When the A71 is on USB (otherwise at the next time it is), run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; "packages before: $(@(& $adb -s R58N66RVD7Z shell pm list packages dev.kestrel) -join ' ')"; & $adb -s R58N66RVD7Z logcat -c; scripts\connected-test.ps1 -Serial R58N66RVD7Z :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.ConversationArchiveSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true'; & $adb -s R58N66RVD7Z logcat -d -s KestrelArchive:I; "packages after: $(@(& $adb -s R58N66RVD7Z shell pm list packages dev.kestrel) -join ' ')"; scripts\gradle.ps1 --stop`
Expected: `scripts\connected-test.ps1` accepts the A71 as a test device; the five lines of the speed test, and the test passes; the script ends with exit code 0; the packages after are the packages before (no `dev.kestrel.privacy.test` left). This installs nothing of Kestrel's and changes no setting that the script does not put back.

Write the times of both devices into `.superpowers\sdd\progress.md`. These are the two rules that the times feed. The third line, the restore, the index and the bodies of a search's 5 best matches, is the archive's share of design 4.3's first search, which must be at most 500 ms on the A71 (about 0.35 s is expected: about 0.07 s for the restore, 0.12 s for the index and 0.15 s for the bodies, R3). The fifth line, a close, must be at most 500 ms on the A71 (design 4.3; about 0.1 s is expected for the archive's part, without plan 7B's encoding). Above either, the controller stops before plan 7D and takes the times to the owner. The first two lines are only noted (about 0.07 s and 0.2 s expected), and so is the fourth, what a rebuild reads (about 5 s expected on the A71, design 9.2). The times on kestrel-api37 are only noted: the emulator's Keystore runs on the PC, so its times neither stop the plan nor call for anything.

---

## Interfaces for plans 7D, 7E and 7F

- `AppGraph.conversationStore: ArchivedConversationStore` is the one store of conversations: plan 7D builds `HistorySearch`, `LocalRequests` and `ConversationEngine` on it, and never makes a second `ArchivedConversationStore` (plan 7B's wiring sketch `ArchivedConversationStore(RoomRecordArchive(…), scope) { note -> … }` is this property), so that the index of past conversations and the open conversation in memory follow every change. It reads the index, rebuilds a lost one and tries a failed one again in `AppGraph.scope`. Its decoding and encoding run on the caller's thread, so a caller from the main thread (plan 7F's screen) calls it from a background dispatcher.
- `AndroidConversationClock` (package `dev.kestrel.app.conversation`) is the `ConversationClock` that plan 7D passes to `HistorySearch`, `GoalRunner`, `LocalRequests` and `ConversationEngine`.
- "Delete history" (plan 7D): `LocalRequests` calls `conversationStore.deleteAll()`, which empties the store's memory and, through `RoomRecordArchive.deleteAll()` and `ConversationArchiveStore.deleteAll()`, erases the key `kestrel.conversations` and then deletes every row and every shard of the index of past conversations, and `LogStore.deleteHistory()` for both logs. The settings button (`SettingsData.deleteHistory()`) and "Kestrel, delete history" go through `LocalRequests` from plan 7D on, never to the archive directly, because the engine must close the open conversation and refuse while a goal is open.
- The purge job runs from Kestrel's start (`KestrelApplication` calls `AppGraph.keepPurgingConversations()`); plan 7F's Conversations page changes `SettingKeys.CONVERSATIONS`, and a change of `keptDays` purges at once.
- `SettingKeys.CONVERSATIONS: SettingKey<ConversationSettings>` (stored as `conversations`) exists from this plan; plan 7D's engine reads it with `settings.observe(SettingKeys.CONVERSATIONS)` and adds no second key.
- The activity log's texts for the archive are "A conversation could not be read, so Kestrel deleted it", "The index of past conversations could not be used, so Kestrel rebuilt it from the conversations" and "Kestrel could not read the index of past conversations and tries again later" (the app graph, from plan 7B's `ArchiveNote`), and "A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it" (`PrivacyStore`).
- The design's A71 checks (15.2), wherever the controller places them, may run `ConversationArchiveSpeedTest` again with `kestrelArchiveSpeed=true`.

## Counts at the end of plan 7C

- `:core:privacy` JVM: P₀ + 18 (Task 2: 1, Task 3: 10, Task 4b: 7).
- `:app` JVM: A₀ (unchanged).
- `:core:privacy` device tests: D₀ + 13 (32 when D₀ is 19), one of them skipped without its instrumentation argument.
- `:app` device tests: AD₀ + 7 (450 when AD₀ is 443).
- `:core:conversation`: unchanged.
- Pester: unchanged; this plan changes no script.

## Left open

- **Where the whole first search is measured on the A71.** Design 15.2 lists "the first search over a filled archive" among the A71 checks, and R3 gives plan 7C the measurement. This plan measures the archive's part without installing Kestrel; the whole search (with plan 7B's decoding of the index, about 0.13 s expected for 30 days, its ranking, about 0.17 s, and its decoding of 5 bodies) needs Kestrel on the A71, which plan 7E's Task 7 measures. Its data must be realistic: about 25,000 different words, about 300 in each command, so that the index is about the size of 30 heavy days and the ranking has its real work. Design R3 asks it to time a rebuild and a close too, and the first search with 365 days of retention, whose wait right after a start is a stated limit.
- **No way back for a device's database.** After Task 8, kestrel-api37's database is at version 3: a build without this plan cannot open it. Task 8, Step 2 writes the rule into `sp3`'s AGENTS.md and progress.md; the controller puts it into `main`'s AGENTS.md at the push of sub-project 7, or at once if work on `main` still uses kestrel-api37. The same holds for the A71 once a later plan installs a build with this plan there.

## Self-review

- **Spec coverage.** Design 9.1: the table in `kestrel.db` (Task 1), one row per conversation with the snapshot's JSON as an encrypted payload under `kestrel.conversations` with the associated data `conversations/<id>` (Tasks 2 to 5), the separate key for "delete history" (Tasks 2, 3, 4), the plain fields (Task 1); the snapshot's `version` is plan 7B's. Design 9.2: version 3 with `AutoMigration(from = 2, to = 3)` (Task 1, checked against the real database in Task 8), `ConversationRow` and `ConversationDao` (Task 1), `ConversationArchiveStore` knowing only bytes (Task 3), `RoomRecordArchive` (Task 5), a payload that fails its tag deleted and reported by kind only (plan 7B's store, wired with its text in Task 6; told apart from an unknown format in Tasks 3 and 5), and the section's text (Task 7). Design 9.2's index of past conversations (amended on 2026-11-25 and 2026-11-26): its table of shards, the restore that reads the open row alone, the shards and the stamp read apart, the reads by id that stop when cancelled, the closed rows listed for a rebuild in pages, and the change in one transaction (Task 4b), on plan 7B's Tasks 5a to 5d, and the port's new form (Task 5). Design 9.1's secure deletion: a device test (Task 4b). Design 9.3: plan 7B's `Retention` and `ConversationStore.purge` delete the closed rows outside retention by the table's own times, with the index's change, in one transaction (Task 4b's `purge`), and the job runs from Kestrel's start (Task 6). Design 9.7's storage part: the key first, then the rows and the index (Tasks 2, 3, 4, 4b, 5). Design 5.6's archive lines (Task 6). Design 15.2's store line: the migration with the real Keystore and "delete history" removing rows and key (Tasks 1, 2, 4, 8). R3: Tasks 4, 4b and 8.
- **Placeholders.** Every step shows its code or its command; the generated `3.json` is checked by its version, its columns and its index.
- **Type consistency.** `ConversationHeader(id, openedMillis, lastMillis, open, commands)` and `ConversationRow(id, openedMillis, lastMillis, open, commands, payload)` are the same in Tasks 1, 3, 4, 5 and 7; `ConversationArchiveStore`'s eight functions (from Task 4b: `open`, `shards`, `stamp`, `read`, `closedIds`, `write`, `purge`, `deleteAll`), `PlainConversation`, `ConversationChange`, `IndexUpdate`, `StoredShard` and `StoredConversation`'s three kinds are those that Tasks 4b and 5 use; `ConversationDao.write` is the one that Tasks 3, 4, 4b and 5 call, and from Task 4b on `ConversationDao.rows(ids)` is the one query that reads rows by id, in the archive and in the tests of Tasks 4b and 5; `KeystoreErasableKey.of(alias)`, its `guard` and `erase()` are those of Tasks 2, 3, 4 and 5; `RecordArchive`'s eight functions (`readOpen`, `readShards`, `readStamp`, `read`, `closedIds`, `write`, `purge`, `deleteAll`), `ArchiveEntry`'s three kinds, `ShardRead`, `IndexWrite`, `IndexStamp`, `ArchivedConversationStore(archive, scope, noted)` with `ArchiveNote`, `ConversationSnapshot`, `Retention(store, clock, report).keepPurging(days)` and `ConversationSettings.keptDays` in Tasks 5 and 6 are those of plan 7B as committed at `9ddf3385` and changed at `a4558faf` and `26737eb7`, and by its Tasks 5a to 5d.

