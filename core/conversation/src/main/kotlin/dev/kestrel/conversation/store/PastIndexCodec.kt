package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.ConversationId
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DataFormatException
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * A shard of the index of past conversations as bytes (design 9.2 of sub-project 7), which the archive encrypts in a row of
 * its own, named by the shard's number. One byte names the format, [FORMAT]; the rest is compressed with Deflate: every
 * folded word of the shard once, in order; each conversation with its records, each record's words as positions in that
 * list, each written as its distance from the one before; and the kept rows. Every count and distance is a number of
 * variable length, so that a full shard is about 200 KB, and 30 days of heavy use make about 340 KB in all (design R3).
 * Bytes in another format, or that do not decode, throw [IllegalArgumentException], and the store rebuilds the index from
 * the conversations.
 */
object PastIndexCodec {
    const val FORMAT: Byte = 1

    fun encode(shard: IndexShard): ByteArray {
        val bytes = ByteArrayOutputStream()
        bytes.write(FORMAT.toInt())
        DataOutputStream(BufferedOutputStream(DeflaterOutputStream(bytes), BUFFER_BYTES)).use { out ->
            out.number(shard.search.words.size)
            for (word in shard.search.words) out.text(word)
            val records = shard.search.records.groupBy { it.ref.conversation }
            out.number(shard.conversations.size)
            for (conversation in shard.conversations) {
                out.text(conversation.id.value)
                out.writeLong(conversation.openedAtWall)
                out.writeLong(conversation.lastWall)
                out.optionalText(conversation.firstRequest)
                val own = records[conversation.id].orEmpty()
                out.number(own.size)
                for (record in own) {
                    out.number(record.ref.record)
                    out.writeLong(record.atWall)
                    out.number(record.words.size)
                    var previous = -1
                    for (position in record.words) {
                        out.number(position - previous - 1)
                        previous = position
                    }
                }
            }
            out.number(shard.kept.size)
            for (row in shard.kept) {
                out.text(row.id)
                out.writeLong(row.lastWall)
            }
        }
        return bytes.toByteArray()
    }

    /**
     * The shard [number] from [bytes], which the archive read from the row of that number. A word that [words] already holds,
     * from an earlier shard, is taken from it, so that the index keeps each word once in memory however many shards hold it.
     */
    fun decode(number: Int, bytes: ByteArray, words: MutableMap<String, String> = HashMap()): IndexShard {
        require(bytes.isNotEmpty() && bytes[0] == FORMAT) { "The index is in a format that this version cannot read" }
        val input = Reader(inflated(bytes))
        val list = List(input.count()) { input.text().let { word -> words.getOrPut(word) { word } } }
        val conversations = ArrayList<PastConversation>()
        val records = ArrayList<IndexedRecord>()
        repeat(input.count()) {
            val id = ConversationId(input.text())
            val openedAtWall = input.long()
            val lastWall = input.long()
            val firstRequest = input.optionalText()
            val commands = input.count()
            repeat(commands) {
                val record = input.number()
                val atWall = input.long()
                var previous = -1
                val positions = IntArray(input.count()) {
                    previous += input.number() + 1
                    previous
                }
                records += IndexedRecord(RecordRef(id, record), atWall, positions)
            }
            conversations += PastConversation(id, openedAtWall, lastWall, commands, firstRequest)
        }
        val kept = List(input.count()) { KeptRow(input.text(), input.long()) }
        require(input.atEnd) { "The index has bytes after its end" }
        return IndexShard.restored(number, conversations, SearchIndex.restored(list, records), kept)
    }

    private const val MAX_COUNT = 1 shl 24

    private const val BUFFER_BYTES = 64 * 1024

    // The bytes after the format byte, inflated whole: the compressed stream must end exactly where the bytes end.
    private fun inflated(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, 1, bytes.size - 1)
            val plain = ByteArrayOutputStream(bytes.size * 3)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val inflatedBytes = inflater.inflate(buffer)
                require(inflatedBytes > 0 || !inflater.needsInput() && !inflater.needsDictionary()) { "The index is cut short" }
                plain.write(buffer, 0, inflatedBytes)
            }
            require(inflater.remaining == 0) { "The index has bytes after its end" }
            return plain.toByteArray()
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("The index does not decode", e)
        } finally {
            inflater.end()
        }
    }

    private fun DataOutputStream.number(value: Int) {
        require(value >= 0) { "A number of the index is negative" }
        var rest = value
        while (rest >= 0x80) {
            writeByte(rest and 0x7F or 0x80)
            rest = rest ushr 7
        }
        writeByte(rest)
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.encodeToByteArray()
        number(bytes.size)
        write(bytes)
    }

    // 0 for none, else the text's length in bytes plus 1.
    private fun DataOutputStream.optionalText(value: String?) {
        if (value == null) {
            number(0)
        } else {
            val bytes = value.encodeToByteArray()
            number(bytes.size + 1)
            write(bytes)
        }
    }

    // Reads the inflated bytes directly, a number, a long or a text at a time; running past the end throws
    // IllegalArgumentException, as every damage does.
    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        val atEnd: Boolean get() = at == bytes.size

        fun number(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val byte = next()
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                require(shift < 32) { "A number of the index is too long" }
            }
            require(result >= 0) { "A number of the index is too large" }
            return result
        }

        // A count of words, records or rows, which a damaged length could make large enough to exhaust the memory.
        fun count(): Int = number().also { require(it <= MAX_COUNT) { "A count of the index is too large" } }

        fun long(): Long {
            var result = 0L
            repeat(8) { result = result shl 8 or next().toLong() }
            return result
        }

        fun text(): String = string(count())

        fun optionalText(): String? = count().let { if (it == 0) null else string(it - 1) }

        private fun string(size: Int): String {
            require(size <= bytes.size - at) { "The index is cut short" }
            return bytes.decodeToString(at, at + size).also { at += size }
        }

        private fun next(): Int {
            require(at < bytes.size) { "The index is cut short" }
            return bytes[at++].toInt() and 0xFF
        }
    }
}
