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

    // The table gives its rows in an order of its own; the port gives them in the order of [ids].
    override suspend fun read(ids: Collection<String>): List<ArchiveEntry> {
        val read = table.read(ids).associateBy { it.header.id }
        return ids.mapNotNull { read[it]?.archived() }
    }

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
