package dev.kestrel.conversation.store

import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.testing.MemoryArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ArchivedConversationStoreTest {
    private val archive = MemoryArchive()
    private val notes = mutableListOf<ArchiveNote>()
    private val now = 100 * ArchivedConversationStore.DAY_MILLIS
    private lateinit var store: ArchivedConversationStore

    /**
     * Runs [body] with [store] on [archive], which has read the empty archive, so that its changes write the index at once;
     * the stores' reads of the index and their rebuilds run in the test's background scope.
     */
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        store = fresh()
        store.list()
        archive.calls.clear()
        body()
    }

    /** A new store on the same archive, as after a start of Kestrel. */
    private fun TestScope.fresh() = ArchivedConversationStore(archive, backgroundScope) { notes += it }

    private fun id(n: Int) = ConversationId(n.toString().padStart(32, '0'))

    private fun conversation(n: Int, last: Long, open: Boolean = false, request: String = "Request $n") = ConversationSnapshot(
        id = id(n),
        openedAtWall = last - 1_000,
        lastWall = last,
        open = open,
        testMode = true,
        records = listOf(CommandRecord(1, last - 1_000, InputChannel.TEXT, request)),
    )

    private fun pastIds() = archive.past()?.conversations.orEmpty().map { it.id }

    @Test
    fun aSnapshotRoundTripsAndOnlyItsHeaderIsPlain() = test {
        val snapshot = conversation(1, now, open = true)

        store.save(snapshot)

        assertEquals(ArchiveHeader(id(1).value, now - 1_000, now, open = true, commands = 1), archive.rows.getValue(id(1).value).header)
        assertTrue(archive.payload(id(1).value).contains("\"version\":1"))
        assertFalse(archive.payload(id(1).value).contains("\"open\""))
        assertEquals(snapshot, store.open())
        assertEquals(snapshot, fresh().get(id(1)))
    }

    @Test
    fun aPayloadThatFailsItsTagIsDeletedAndNotedByItsKind() = test {
        store.save(conversation(1, now, open = true))
        archive.unreadable += id(1).value
        val fresh = fresh()

        assertNull(fresh.open())
        assertFalse(archive.rows.containsKey(id(1).value))
        assertEquals(listOf(ArchiveNote.CONVERSATION_DELETED), notes)
    }

    @Test
    fun aConversationIsOpenOnlyWhenItsRowSaysSo() = test {
        store.save(conversation(1, now, open = true))
        store.list()
        val row = archive.rows.getValue(id(1).value)
        // The table closed the row (plan 7C), while its payload is an older one that still says open.
        val payload = archive.payload(id(1).value).replaceFirst("{", "{\"open\":true,")
        archive.rows[id(1).value] = ArchiveRow(row.header.copy(open = false), payload.encodeToByteArray())
        val fresh = fresh()

        assertNull(fresh.open())
        assertEquals(listOf(id(1)), fresh.list().map { it.id })
        assertFalse(fresh.get(id(1))!!.open)
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    @Test
    fun aRowInAFormatThatThisVersionCannotReadIsLeftOutKeptAndCounted() = test {
        store.save(conversation(1, now, open = true))
        store.save(conversation(2, now - 1_000))
        // A later version wrote both rows, and its index, which this version does not know.
        archive.unknown += listOf(id(1).value, id(2).value)
        archive.shards.clear()
        val fresh = fresh()

        assertNull(fresh.open())
        assertTrue(fresh.list().isEmpty())
        assertEquals(listOf(KeptRow(id(2).value, now - 1_000)), archive.past()!!.kept)

        // A new open conversation closes the newer format's open row, which the index then counts too.
        fresh.save(conversation(3, now, open = true))
        assertEquals(setOf(id(1).value, id(2).value), archive.past()!!.kept.map { it.id }.toSet())
        assertEquals(setOf(id(1).value, id(2).value, id(3).value), archive.rows.keys)
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    @Test
    fun reopenClosesTheOpenOneAndOpensTheChosenOneInOneTransaction() = test {
        store.save(conversation(1, now - 5_000))
        store.save(conversation(2, now - 1_000, open = true))
        val closing = conversation(2, now - 500, open = true)

        val opened = store.reopen(id(1), closing, now, testMode = false)!!

        assertEquals(listOf(id(2).value, id(1).value), archive.transactions.last())
        assertEquals(listOf(id(2)), pastIds())
        assertEquals(opened, store.open())
        assertFalse(opened.testMode)
        assertEquals(now, opened.continuedAtWall)
        assertEquals(now, opened.lastWall)
        assertEquals(listOf(id(2)), store.list().map { it.id })
        assertEquals(now - 500, store.get(id(2))!!.lastWall)
    }

    @Test
    fun aConversationThatIsGoneCannotBeReopened() = test {
        store.save(conversation(2, now, open = true))

        assertNull(store.reopen(id(1), store.open(), now, testMode = true))
        assertEquals(id(2), store.open()!!.id)
    }

    // Design 9.3: retention deletes by the table's own times, so a closed row that the index does not list goes too.
    @Test
    fun purgeKeepsTheOpenConversationAndDeletesOldAndFutureRows() = test {
        val day = ArchivedConversationStore.DAY_MILLIS
        store.save(conversation(1, now - 31 * day))
        store.save(conversation(2, now - 29 * day))
        store.save(conversation(3, now + 2 * day))
        store.save(conversation(4, now - 40 * day, open = true))
        store.list()
        val unlisted = conversation(5, now - 50 * day)
        archive.rows[id(5).value] = ArchiveRow(ArchiveHeader(id(5).value, unlisted.openedAtWall, unlisted.lastWall, open = false, commands = 1), "{}".encodeToByteArray())

        assertEquals(3, store.purge(30, now))

        assertEquals(listOf(id(2)), store.list().map { it.id })
        assertEquals(listOf(id(2)), pastIds())
        assertEquals(setOf(id(2).value, id(4).value), archive.rows.keys)
        assertEquals(id(4), store.open()!!.id)
    }

    @Test
    fun deleteAndDeleteAllRemoveTheRowsTheIndexAndTheKey() = test {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now, open = true))

        store.delete(id(1))
        assertNull(store.get(id(1)))
        assertEquals(setOf(id(2).value), archive.rows.keys)
        assertTrue(pastIds().isEmpty())

        store.deleteAll()
        assertNull(store.open())
        assertTrue(archive.rows.isEmpty())
        assertTrue(archive.shards.isEmpty())
        assertTrue(archive.keyErased)
    }

    // On the A71, decrypting every row took 5.2 s even in one query; the open row and the index take one decryption each,
    // and one more per shard.
    @Test
    fun theFirstUseReadsTheOpenRowAndTheIndexOnceAndABodyOnlyWhenAsked() = test {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now - 1_000))
        store.save(conversation(3, now, open = true))
        archive.calls.clear()
        val fresh = fresh()

        fresh.list()
        fresh.open()
        fresh.searchable()
        assertEquals(listOf("readOpen", "readShards", "readStamp"), archive.calls)

        assertEquals(id(1), fresh.get(id(1))!!.id)
        assertEquals(setOf(id(1), id(2), id(3)), fresh.getAll(listOf(id(1), id(2), id(3))).keys)
        assertEquals(listOf("readOpen", "readShards", "readStamp", "read", "read"), archive.calls)
    }

    // Design 4.3: a restore reads one row; the index is read in the background, and no change waits for it, also a close
    // right after a start, which the engine makes before it hears the owner's first command.
    @Test
    fun aRestoreAndEveryChangeGoOnWhileTheIndexIsReadAndTheIndexTakesThemAfterwards() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000))
        store.save(conversation(3, now - 1_000, open = true))
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val fresh = fresh()

        assertEquals(id(3), fresh.open()!!.id)
        fresh.save(conversation(3, now - 500, open = true))
        fresh.save(conversation(3, now, open = false))
        fresh.delete(id(1))
        // Retention ends between the two closed conversations: the older goes.
        assertEquals(1, fresh.purge(30, now + 30 * ArchivedConversationStore.DAY_MILLIS - 1_000))
        val listed = async { fresh.list() }
        runCurrent()
        assertFalse(listed.isCompleted)
        assertEquals(setOf(id(3).value), archive.rows.keys)

        gate.complete(Unit)

        assertEquals(listOf(id(3)), listed.await().map { it.id })
        assertEquals(listOf(id(3)), pastIds())
        assertTrue(notes.isEmpty())
    }

    // The changes made while the index was read reach its shards only once it is decoded: a start before then finds the
    // index out of step and rebuilds it.
    @Test
    fun aCloseWhileTheIndexIsReadThatTheProcessDidNotSurviveLeavesTheIndexOutOfStep() = test {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now - 1_000, open = true))
        archive.readShardsGate = CompletableDeferred()
        val ended = fresh()
        ended.open()
        runCurrent()
        ended.save(conversation(2, now, open = false))
        archive.readShardsGate = null

        val started = fresh()

        assertEquals(listOf(id(2), id(1)), started.list().map { it.id })
        assertEquals(listOf(id(2), id(1)), pastIds())
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    @Test
    fun theListShowsTheClosedConversationsNewestFirstWithTheirFirstRequestCut() = test {
        val long = "Find the restaurant that Mila sent me last week, the one near the river with the garden and the live music"
        store.save(conversation(1, now - 2_000, request = long))
        store.save(conversation(2, now - 1_000, request = "Call Ana"))
        store.save(conversation(3, now, open = true))

        assertEquals(
            listOf(
                ConversationSummary(id(2), now - 2_000, now - 1_000, 1, "Call Ana"),
                ConversationSummary(id(1), now - 3_000, now - 2_000, 1, ScreenText.cut(long, PastIndex.FIRST_REQUEST)),
            ),
            store.list(),
        )
    }

    @Test
    fun aSaveOfTheOpenConversationWritesItsRowOnlyAndItsCloseWritesTheIndexWithIt() = test {
        store.save(conversation(1, now - 1_000, open = true))
        store.save(conversation(1, now - 500, open = true))
        assertTrue(archive.shards.isEmpty())

        store.save(conversation(1, now, open = false))

        assertEquals(listOf("write", "write", "write"), archive.calls)
        assertEquals(listOf(id(1)), pastIds())
        assertNull(store.open())
    }

    // The search reads the generation before it reads the conversations, so that it never gives out a deleted one's words.
    @Test
    fun deleteAllRaisesTheGenerationBeforeItDeletesAlsoWhenItDoesNotComplete() = test {
        var seenByTheArchive = -1L
        lateinit var watched: ArchivedConversationStore
        watched = ArchivedConversationStore(
            object : RecordArchive by archive {
                override suspend fun deleteAll() {
                    seenByTheArchive = watched.generation
                    archive.deleteAll()
                }
            },
            backgroundScope,
        ) {}
        watched.save(conversation(1, now - 2_000))
        val before = watched.generation
        archive.interruptions["deleteAll"] = CancellationException("stop")

        assertTrue(runCatching { watched.deleteAll() }.exceptionOrNull() is CancellationException)

        assertEquals(before + 1, seenByTheArchive)
        assertEquals(before + 1, watched.generation)
        watched.save(conversation(2, now))
        watched.delete(id(2))
        watched.purge(30, now)
        assertEquals(before + 1, watched.generation)
    }

    // A "stop" can cancel "delete history" after the archive deleted the rows: Room's call then throws on its way back. The
    // store marks nothing, as after any "delete history" that did not complete, and shows what the archive holds.
    @Test
    fun aDeleteAllCancelledAfterTheArchiveDeletedMarksNothingAndShowsWhatTheArchiveHolds() = test {
        store.save(conversation(1, now - 2_000))
        val open = conversation(2, now - 1_000, open = true)
        store.save(open)
        archive.interruptions["deleteAll"] = CancellationException("stop")

        assertTrue(runCatching { store.deleteAll() }.exceptionOrNull() is CancellationException)

        assertTrue(store.list().isEmpty())
        assertNull(store.open())
        assertNull(store.reopen(id(1), null, now, testMode = true))
        // The owner was told that the history was not deleted, so the conversation that he goes on with is saved again.
        store.save(open.copy(lastWall = now))
        assertEquals(setOf(id(2).value), archive.rows.keys)
        assertEquals(id(2), fresh().open()!!.id)
    }

    // Design 9.7: "delete history" works on the archive alone. One that fails before the index is ready, while the store
    // has read only some of the conversations, marks none of them, and every conversation stays as it was.
    @Test
    fun aDeleteHistoryThatFailsBeforeTheIndexIsReadyLeavesEveryConversationAsItWas() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000))
        store.save(conversation(3, now - 1_000, open = true))
        archive.readShardsGate = CompletableDeferred()
        val fresh = fresh()
        assertEquals(id(3), fresh.open()!!.id)
        assertEquals(id(1), fresh.get(id(1))!!.id)
        archive.failures["deleteAll"] = IOException("the disk failed")

        assertTrue(runCatching { fresh.deleteAll() }.exceptionOrNull() is IOException)

        archive.failures.clear()
        archive.readShardsGate = null
        assertEquals(id(3), fresh.open()!!.id)
        assertEquals(listOf(id(2), id(1)), fresh.list().map { it.id })
        assertEquals(id(1), fresh.get(id(1))!!.id)
        assertEquals(setOf(id(1).value, id(2).value, id(3).value), archive.rows.keys)
        assertFalse(archive.keyErased)
        assertTrue(notes.isEmpty())
    }

    // Design 9.2 and 9.4: whatever a conversation holds, the index keeps at most SearchIndex's bounds of it: no word longer
    // than MAX_WORD_LENGTH, CONVERSATION_WORDS different words and CONVERSATION_POSITIONS positions, its first records
    // first. So its shard decodes again, and the next start trusts it and rebuilds nothing.
    @Test
    fun aHugeConversationOfLongWordsIsIndexedWithinItsBoundsAndTheNextStartTrustsItsShard() = test {
        fun letters(n: Int) = (0 until 3).map { 'a' + n / pow26(it) % 26 }.joinToString("")
        val common = List(100) { "com" + letters(it) }
        val records = List(120) { r ->
            val own = List(150) { "own" + letters(r) + letters(it) }
            val long = List(70) { "lng" + letters(r) + letters(it) + "q".repeat(2_000) }
            CommandRecord(r + 1, now - 1_000 + r, InputChannel.TEXT, (common + own).joinToString(" "), answer = long.joinToString(" "))
        }

        store.save(ConversationSnapshot(id(1), now - 1_000, now, open = false, testMode = true, records = records))

        val shard = archive.past()!!.shards.single()
        val words = shard.search.words.toSet()
        assertEquals(SearchIndex.CONVERSATION_WORDS, words.size)
        assertEquals(SearchIndex.CONVERSATION_POSITIONS, shard.positions)
        assertTrue(words.all { it.length <= SearchIndex.MAX_WORD_LENGTH })
        assertTrue("com" + letters(0) in words && "own" + letters(0) + letters(0) in words)
        assertFalse("own" + letters(119) + letters(0) in words)
        archive.calls.clear()

        val started = fresh()

        assertEquals(listOf(id(1)), started.list().map { it.id })
        assertEquals(listOf("readOpen", "readShards", "readStamp"), archive.calls)
        assertTrue(notes.isEmpty())
    }

    private fun pow26(exponent: Int): Int = (0 until exponent).fold(1) { power, _ -> power * 26 }

    @Test
    fun anOlderSnapshotOfADeletedConversationIsNeverWrittenBack() = test {
        val past = conversation(1, now - 2_000)
        store.save(past)
        store.save(conversation(2, now, open = true))

        store.delete(id(1))
        store.save(past)

        assertNull(store.get(id(1)))
        assertEquals(setOf(id(2).value), archive.rows.keys)
        assertTrue(pastIds().isEmpty())
    }

    // A change of the retention cancels a running purge (Retention's collectLatest).
    @Test
    fun aPurgeThatFailsAfterTheArchivePurgedLeavesThePurgedConversationsOut() = test {
        store.save(conversation(1, now - 31 * ArchivedConversationStore.DAY_MILLIS))
        store.save(conversation(2, now - ArchivedConversationStore.DAY_MILLIS))
        archive.interruptions["purge"] = IOException("the disk is full")

        assertTrue(runCatching { store.purge(30, now) }.exceptionOrNull() is IOException)

        assertEquals(listOf(id(2)), store.list().map { it.id })
    }

    @Test
    fun aSwitchCancelledAfterTheArchiveWroteItShowsTheSwitchAsTheArchiveHasIt() = test {
        store.save(conversation(1, now - 5_000))
        store.save(conversation(2, now - 1_000, open = true))
        archive.interruptions["write"] = CancellationException("stop")

        assertTrue(runCatching { store.reopen(id(1), conversation(2, now - 500, open = true), now, testMode = true) }.exceptionOrNull() is CancellationException)

        assertEquals(id(1), store.open()!!.id)
        assertEquals(listOf(id(2)), store.list().map { it.id })
        assertEquals(now - 500, store.get(id(2))!!.lastWall)
    }

    // The engine reports a failed save and goes on, so a conversation that failed to close can meet a new open one.
    @Test
    fun writingAnOpenConversationClosesTheOthersInMemoryAndInTheIndexAsTheTableDoes() = test {
        store.save(conversation(1, now - 1_000, open = true))

        store.save(conversation(2, now, open = true))

        assertEquals(id(2), store.open()!!.id)
        assertEquals(listOf(id(1)), store.list().map { it.id })
        assertEquals(listOf(id(1)), pastIds())
        assertEquals(listOf(id(1)), fresh().list().map { it.id })
    }

    @Test
    fun anIndexThatIsMissingUnreadableInAnotherFormatOrOutOfStepIsRebuiltAndNoted() = test {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now - 1_000))
        val name = archive.shards.keys.single()
        val good = archive.shards.getValue(name).copyOf()
        fun moved(n: Int, last: Long) = archive.rows.getValue(id(n).value).let { ArchiveRow(it.header.copy(lastMillis = last), it.payload) }
        val damages = listOf<Pair<() -> Unit, List<ConversationId>>>(
            { archive.shards.clear() } to listOf(id(2), id(1)),
            { archive.unreadableShards += name } to listOf(id(2), id(1)),
            { archive.shards[name] = byteArrayOf(2) + good.copyOfRange(1, good.size) } to listOf(id(2), id(1)),
            { archive.shards.clear(); archive.shards["first"] = good } to listOf(id(2), id(1)),
            // A row went without its index entry: the index counts one closed row more than the table.
            { archive.shards[name] = good; archive.rows -= id(2).value } to listOf(id(1)),
        )
        for ((damage, listed) in damages) {
            damage()
            notes.clear()
            val fresh = fresh()

            assertEquals(listed, fresh.list().map { it.id })
            assertEquals(listed, pastIds())
            assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
        }
    }

    // The stamp holds the count, the newest last change and their sum: a table that differs in only one of them is out of step.
    @Test
    fun aStampThatDiffersOnlyInTheNewestOrTheSumIsOutOfStep() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000))
        val good = archive.shards.toMap()
        val newer = archive.rows.getValue(id(2).value)
        val older = archive.rows.getValue(id(1).value)
        val damages = listOf(
            {
                // The same count and sum, a newer newest.
                archive.rows[id(2).value] = ArchiveRow(newer.header.copy(lastMillis = now - 1_000), newer.payload)
                archive.rows[id(1).value] = ArchiveRow(older.header.copy(lastMillis = now - 4_000), older.payload)
            },
            // The same count and newest, another sum.
            { archive.rows[id(1).value] = ArchiveRow(older.header.copy(lastMillis = now - 2_500), older.payload) },
        )
        for (damage in damages) {
            archive.shards.clear()
            archive.shards += good
            archive.rows[id(1).value] = older
            archive.rows[id(2).value] = newer
            damage()
            notes.clear()
            val fresh = fresh()

            assertEquals(listOf(id(2), id(1)), fresh.list().map { it.id })
            assertEquals(archive.stamp(), archive.past()!!.stamp)
            assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
        }
    }

    @Test
    fun whileTheIndexIsRebuiltTheOpenConversationIsSavedTheSearchSaysSoAndChangesAreReplayed() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000))
        store.save(conversation(3, now - 1_000, open = true))
        archive.shards.clear()
        val gate = CompletableDeferred<Unit>()
        archive.readGate = gate
        val fresh = fresh()

        assertEquals(id(3), fresh.open()!!.id)
        runCurrent()
        assertEquals(Searchable.Rebuilding, fresh.searchable())
        fresh.save(conversation(3, now - 500, open = true))
        fresh.delete(id(1))
        fresh.save(conversation(3, now, open = false))
        val listed = async { fresh.list() }
        runCurrent()
        assertFalse(listed.isCompleted)

        gate.complete(Unit)

        assertEquals(listOf(id(3), id(2)), listed.await().map { it.id })
        assertEquals(listOf(id(3), id(2)), pastIds())
        assertTrue(fresh.searchable() is Searchable.Ready)
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    // Design 9.2: a start between a change and the end of the rebuild must not trust the old index.
    @Test
    fun aChangeDuringARebuildLeavesNoIndexThatAStartCouldTrust() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000))
        archive.unreadableShards += archive.shards.keys
        archive.readGate = CompletableDeferred()
        val rebuilding = fresh()
        rebuilding.open()
        runCurrent()

        rebuilding.save(conversation(3, now - 1_000))

        assertTrue(archive.shards.isEmpty())
        archive.readGate = null
        notes.clear()
        val started = fresh()
        assertEquals(listOf(id(3), id(2), id(1)), started.list().map { it.id })
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    // Design 9.2: the rebuild deletes a row that fails its check, unless a change rewrote it meanwhile.
    @Test
    fun aRebuildDeletesTheRowsThatFailButNotOneThatAChangeRewroteMeanwhile() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000))
        store.save(conversation(3, now - 1_000))
        archive.shards.clear()
        archive.unreadable += listOf(id(1).value, id(2).value)
        val gate = CompletableDeferred<Unit>()
        archive.readGate = gate
        val fresh = fresh()
        fresh.open()
        runCurrent()

        fresh.save(conversation(2, now - 500, request = "Written again"))
        fresh.delete(id(3))
        gate.complete(Unit)
        runCurrent()

        assertEquals(setOf(id(2).value), archive.rows.keys)
        assertEquals(listOf(id(2)), fresh.list().map { it.id })
        assertEquals("Written again", fresh.list().single().firstRequest)
        assertEquals(listOf(ArchiveNote.CONVERSATION_DELETED, ArchiveNote.INDEX_REBUILT), notes)
    }

    // Design 9.2: a rebuild never holds more than a page of bodies.
    @Test
    fun aRebuildReadsTheClosedRowsInPages() = test {
        for (n in 1..120) store.save(conversation(n, now - n * 1_000L))
        archive.shards.clear()
        archive.calls.clear()

        assertEquals(120, fresh().list().size)

        assertEquals(listOf("readOpen", "readShards", "readStamp", "closedIds", "read", "read", "read", "write"), archive.calls)
    }

    // A failed change drops the memory and stops the rebuild, whose result would miss the change; the next call rebuilds.
    @Test
    fun aChangeThatFailsDuringARebuildStopsItAndTheNextCallRebuildsRight() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000, open = true))
        archive.shards.clear()
        val gate = CompletableDeferred<Unit>()
        archive.readGate = gate
        val fresh = fresh()
        fresh.open()
        runCurrent()
        archive.failures["write"] = IOException("the disk is full")

        assertTrue(runCatching { fresh.save(conversation(2, now - 1_000, open = false)) }.exceptionOrNull() is IOException)

        archive.failures.clear()
        archive.readGate = null
        gate.complete(Unit)
        runCurrent()
        fresh.save(conversation(2, now - 1_000, open = false))
        assertEquals(listOf(id(2), id(1)), fresh.list().map { it.id })
        assertEquals(listOf(id(2), id(1)), pastIds())
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    // A purge never waits for a rebuild; it and a switch made meanwhile are replayed onto the rebuilt index.
    @Test
    fun aPurgeAndASwitchDuringARebuildAreReplayed() = test {
        val day = ArchivedConversationStore.DAY_MILLIS
        store.save(conversation(1, now - 40 * day))
        store.save(conversation(2, now - 2 * day))
        store.save(conversation(3, now - day, open = true))
        archive.shards.clear()
        val gate = CompletableDeferred<Unit>()
        archive.closedIdsGate = gate
        val fresh = fresh()
        fresh.open()
        runCurrent()

        assertEquals(1, fresh.purge(30, now))
        archive.closedIdsGate = null
        gate.complete(Unit)
        archive.readGate = CompletableDeferred()
        runCurrent()
        val reopened = async { fresh.reopen(id(2), conversation(3, now - 500, open = true), now, testMode = true) }
        runCurrent()
        archive.readGate!!.complete(Unit)
        archive.readGate = null

        assertEquals(id(2), reopened.await()!!.id)
        assertEquals(listOf(id(3)), fresh.list().map { it.id })
        assertEquals(listOf(id(3)), pastIds())
        assertEquals(setOf(id(2).value, id(3).value), archive.rows.keys)
    }

    @Test
    fun deleteHistoryDuringARebuildStopsItAndLeavesNothing() = test {
        store.save(conversation(1, now - 1_000))
        archive.shards.clear()
        val gate = CompletableDeferred<Unit>()
        archive.readGate = gate
        val fresh = fresh()
        fresh.open()
        runCurrent()

        fresh.deleteAll()
        gate.complete(Unit)
        runCurrent()

        assertTrue(fresh.list().isEmpty())
        assertTrue(archive.rows.isEmpty())
        assertTrue(archive.shards.isEmpty())
        assertTrue(notes.isEmpty())
    }

    @Test
    fun aBodyThatFailsWhenItIsReadIsDeletedWithItsIndexEntry() = test {
        store.save(conversation(1, now - 1_000))
        store.save(conversation(2, now, open = true))
        archive.unreadable += id(1).value

        assertNull(store.get(id(1)))

        assertEquals(setOf(id(2).value), archive.rows.keys)
        assertTrue(pastIds().isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(listOf(ArchiveNote.CONVERSATION_DELETED), notes)
    }

    // Design 9.2: an index that can be neither read nor rebuilt is noted once and tried again after a minute, then after
    // twice as long; meanwhile the list says so, the open conversation is saved, a close clears the stored index, and
    // retention deletes by the table.
    @Test
    fun aRebuildThatFailsIsNotedOnceAndTriedAgainLaterNotAtEachCall() = test {
        val day = ArchivedConversationStore.DAY_MILLIS
        store.save(conversation(1, now - 40 * day))
        store.save(conversation(2, now - day))
        archive.shards.clear()
        archive.failures["closedIds"] = IOException("the disk failed")
        val fresh = fresh()

        assertTrue(runCatching { fresh.list() }.exceptionOrNull() is PastUnavailableException)
        assertEquals(Searchable.Failed, fresh.searchable())
        fresh.save(conversation(3, now - 1_000, open = true))
        fresh.save(conversation(3, now - 500, open = false))
        assertEquals(1, fresh.purge(30, now))
        assertTrue(runCatching { fresh.list() }.exceptionOrNull() is PastUnavailableException)
        assertEquals(1, archive.calls.count { it == "closedIds" })
        assertEquals(listOf(ArchiveNote.INDEX_FAILED), notes)

        advanceTimeBy(ArchivedConversationStore.FIRST_RETRY_MILLIS + 1)
        assertEquals(2, archive.calls.count { it == "closedIds" })
        assertEquals(Searchable.Failed, fresh.searchable())
        assertEquals(listOf(ArchiveNote.INDEX_FAILED), notes)

        archive.failures.clear()
        advanceTimeBy(ArchivedConversationStore.FIRST_RETRY_MILLIS + 1)
        assertEquals(2, archive.calls.count { it == "closedIds" })
        advanceTimeBy(ArchivedConversationStore.FIRST_RETRY_MILLIS)

        assertEquals(listOf(id(3), id(2)), fresh.list().map { it.id })
        assertEquals(listOf(ArchiveNote.INDEX_FAILED, ArchiveNote.INDEX_REBUILT), notes)
    }

    // The rebuild's own write can be made and still throw, as a cancelled Room call does: the index is then failed, and
    // the next try reads what the write stored.
    @Test
    fun aRebuildWhoseOwnWriteFailsIsTriedAgainLater() = test {
        store.save(conversation(1, now - 1_000))
        archive.shards.clear()
        archive.interruptions["write"] = IOException("the disk is full")
        val fresh = fresh()

        assertTrue(runCatching { fresh.list() }.exceptionOrNull() is PastUnavailableException)
        advanceTimeBy(ArchivedConversationStore.FIRST_RETRY_MILLIS + 1)

        assertEquals(listOf(id(1)), fresh.list().map { it.id })
        assertEquals(1, archive.calls.count { it == "closedIds" })
        assertEquals(listOf(ArchiveNote.INDEX_FAILED), notes)
    }

    // Design 9.7: a deletion that did not complete is made again when the store next reads the archive, before it shows
    // anything, so that neither the list nor "continue the conversation about" finds it.
    @Test
    fun aDeletionThatDidNotCompleteIsMadeAgainBeforeAnythingIsShown() = test {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now - 1_000))
        store.save(conversation(3, now, open = true))
        archive.failures["write"] = IOException("the disk is full")

        assertTrue(runCatching { store.delete(id(1)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { store.delete(id(3)) }.exceptionOrNull() is IOException)

        archive.failures.clear()
        assertEquals(setOf(id(1).value, id(2).value, id(3).value), archive.rows.keys)
        assertNull(store.open())
        assertEquals(listOf(id(2)), store.list().map { it.id })
        assertEquals(setOf(id(2).value), archive.rows.keys)
        assertEquals(listOf(id(2)), pastIds())
        assertTrue(notes.isEmpty())
    }

    @Test
    fun deleteHistoryWhileTheIndexIsReadStopsTheReadAndLeavesNothing() = test {
        store.save(conversation(1, now - 1_000))
        store.save(conversation(2, now, open = true))
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val fresh = fresh()
        fresh.open()
        runCurrent()

        fresh.deleteAll()
        gate.complete(Unit)
        runCurrent()

        assertNull(fresh.open())
        assertTrue(fresh.list().isEmpty())
        assertTrue(archive.rows.isEmpty())
        assertTrue(archive.shards.isEmpty())
        assertTrue(notes.isEmpty())
    }

    // The change was made and then failed, as a cancelled Room call does: the read of the index stops, and the next call
    // reads the archive again and finds the index out of step.
    @Test
    fun aChangeThatFailsWhileTheIndexIsReadStopsTheReadAndTheNextCallReadsAgain() = test {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now - 1_000, open = true))
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val fresh = fresh()
        fresh.open()
        runCurrent()
        archive.interruptions["write"] = IOException("the disk is full")

        assertTrue(runCatching { fresh.save(conversation(2, now, open = false)) }.exceptionOrNull() is IOException)

        archive.readShardsGate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, archive.calls.count { it == "readShards" })
        assertEquals(listOf(id(2), id(1)), fresh.list().map { it.id })
        assertEquals(listOf(id(2), id(1)), pastIds())
        assertEquals(2, archive.calls.count { it == "readShards" })
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    // The load writes the shards that the changes made meanwhile changed; when that write fails, the shards stay as they
    // were, and the next call reads the archive again and rebuilds the index.
    @Test
    fun aLoadWhoseOwnWriteFailsLeavesTheNextCallToReadTheArchiveAgain() = test {
        store.save(conversation(1, now - 2_000))
        store.save(conversation(2, now - 1_000, open = true))
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val fresh = fresh()
        fresh.open()
        runCurrent()
        fresh.save(conversation(2, now, open = false))
        archive.failures["write"] = IOException("the disk is full")

        archive.readShardsGate = null
        gate.complete(Unit)
        runCurrent()
        archive.failures.clear()

        assertEquals(listOf(id(2), id(1)), fresh.list().map { it.id })
        assertEquals(listOf(id(2), id(1)), pastIds())
        assertEquals(2, archive.calls.count { it == "readOpen" })
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
    }

    @Test
    fun aStampThatCannotBeReadFailsTheIndexUntilItsNextTry() = test {
        store.save(conversation(1, now - 1_000))
        archive.failures["readStamp"] = IOException("the disk failed")
        val fresh = fresh()

        assertTrue(runCatching { fresh.list() }.exceptionOrNull() is PastUnavailableException)
        assertEquals(Searchable.Failed, fresh.searchable())
        assertEquals(listOf(ArchiveNote.INDEX_FAILED), notes)

        archive.failures.clear()
        advanceTimeBy(ArchivedConversationStore.FIRST_RETRY_MILLIS + 1)

        assertEquals(listOf(id(1)), fresh.list().map { it.id })
        assertEquals(0, archive.calls.count { it == "closedIds" })
        assertEquals(listOf(ArchiveNote.INDEX_FAILED), notes)
    }

    // The store's scope ends with Kestrel's process; a call made after it ended, or waiting when it ended, never waits for
    // ever for a read of the index that will not run.
    @Test
    fun anIndexWhoseScopeEndedWhileItWasReadFailsAndNoCallWaitsForIt() = test {
        store.save(conversation(1, now - 1_000))
        archive.readShardsGate = CompletableDeferred()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val ended = ArchivedConversationStore(archive, scope) { notes += it }
        ended.open()
        val listed = async { runCatching { ended.list() } }
        runCurrent()
        assertFalse(listed.isCompleted)

        scope.cancel()
        runCurrent()

        assertTrue(listed.await().exceptionOrNull() is PastUnavailableException)
        assertEquals(Searchable.Failed, ended.searchable())
        ended.save(conversation(2, now, open = true))
        assertEquals(id(2), ended.open()!!.id)
        assertEquals(listOf(ArchiveNote.INDEX_FAILED), notes)
    }

    // Design 9.2: a change that fails while the index has failed keeps the failure and its next try, so that a full disk
    // does not make every save read the index again.
    @Test
    fun aChangeThatFailsWhileTheIndexHasFailedKeepsItsNextTry() = test {
        store.save(conversation(1, now - 1_000))
        archive.shards.clear()
        archive.failures["closedIds"] = IOException("the disk failed")
        val fresh = fresh()
        assertTrue(runCatching { fresh.list() }.exceptionOrNull() is PastUnavailableException)
        archive.failures["write"] = IOException("the disk is full")

        repeat(3) { assertTrue(runCatching { fresh.save(conversation(2, now, open = true)) }.exceptionOrNull() is IOException) }

        assertEquals(Searchable.Failed, fresh.searchable())
        assertEquals(1, archive.calls.count { it == "readShards" })
        assertEquals(1, archive.calls.count { it == "closedIds" })
        archive.failures.clear()
        advanceTimeBy(ArchivedConversationStore.FIRST_RETRY_MILLIS + 1)
        assertEquals(listOf(id(1)), fresh.list().map { it.id })
        assertEquals(listOf(ArchiveNote.INDEX_FAILED, ArchiveNote.INDEX_REBUILT), notes)
    }

    // An id that is not one of Kestrel's can come only from a write from outside Kestrel; its row fails as a row that does
    // not decode fails, also when it is in a format that this version cannot read, so that the index never keeps its id.
    @Test
    fun aRowWhoseIdIsNotAConversationsIdIsDeletedAndNotedAsARowThatFailed() = test {
        store.save(conversation(1, now - 1_000))
        val payload = "{}".encodeToByteArray()
        archive.rows["not an id"] = ArchiveRow(ArchiveHeader("not an id", now - 3_000, now - 2_000, open = false, commands = 1), payload)
        archive.rows["open, not an id"] = ArchiveRow(ArchiveHeader("open, not an id", now - 3_000, now - 500, open = true, commands = 1), payload)
        val later = "later, not an id " + "x".repeat(10_000)
        archive.rows[later] = ArchiveRow(ArchiveHeader(later, now - 3_000, now - 1_500, open = false, commands = 1), payload)
        archive.unknown += later
        val fresh = fresh()

        assertNull(fresh.open())
        assertEquals(listOf(id(1)), fresh.list().map { it.id })

        assertEquals(setOf(id(1).value), archive.rows.keys)
        assertEquals(listOf(id(1)), pastIds())
        assertTrue(archive.past()!!.kept.isEmpty())
        assertEquals(List(3) { ArchiveNote.CONVERSATION_DELETED } + ArchiveNote.INDEX_REBUILT, notes)
    }
}
