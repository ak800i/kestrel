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
 * folded word of the shard once, in order; each conversation with its count of commands and the records that the index
 * holds of it, each record's words as positions in that
 * list, each written as its distance from the one before; and the kept rows. Every count and distance is a number of
 * variable length, so that a full shard is about 200 KB, and 30 days of heavy use make about 340 KB in all (design R3).
 * Bytes in another format, or that do not decode, throw [IllegalArgumentException], and the store rebuilds the index from
 * the conversations. So do forged or damaged bytes that would fill the memory: the decoder inflates at most
 * [MAX_PLAIN_BYTES], and allocates for a count only as many elements as the bytes that are left can hold. Every shard that
 * Kestrel writes decodes again, by construction: `SearchIndex` bounds what one conversation adds to a shard
 * ([MOST_CONVERSATION_BYTES]), a shard is full at [PastIndex.SHARD_BYTES], and [encode] refuses a shard past
 * [MAX_PLAIN_BYTES].
 */
object PastIndexCodec {
    const val FORMAT: Byte = 1

    // A non-negative Int in 7 bits per byte: 5 bytes at most, the fifth with only the 3 bits that are left (28 to 30).
    private const val MAX_NUMBER_BYTES = 5
    private const val MAX_LAST_BYTE = 0x07
    private const val LONG_BYTES = 8

    /** The most bytes of a shard's three counts: of its words, of its conversations and of its kept rows. */
    const val SHARD_HEADER_BYTES = 3L * MAX_NUMBER_BYTES

    /**
     * The most bytes that one conversation adds to a shard: its listing (its id, its two times, its first request cut at
     * [PastIndex.FIRST_REQUEST] characters, each of at most 3 bytes in UTF-8, and its two counts), at most
     * [SearchIndex.CONVERSATION_WORDS] new words of at most [SearchIndex.MAX_WORD_LENGTH] characters, and at most
     * [SearchIndex.CONVERSATION_POSITIONS] positions, each in a record of its own at most. About 1.7 MB; a kept row adds less.
     */
    const val MOST_CONVERSATION_BYTES: Long =
        4L * MAX_NUMBER_BYTES + 2 * LONG_BYTES + 32 + 3L * (PastIndex.FIRST_REQUEST + 1) +
            SearchIndex.CONVERSATION_WORDS * (MAX_NUMBER_BYTES + 3L * SearchIndex.MAX_WORD_LENGTH) +
            SearchIndex.CONVERSATION_POSITIONS * (3L * MAX_NUMBER_BYTES + LONG_BYTES)

    /**
     * The most bytes that a shard may inflate to. A shard takes conversations and kept rows, one at a time, while it is under
     * [PastIndex.SHARD_BYTES] before Deflate, so it holds at most [PastIndex.SHARD_BYTES] and [MOST_CONVERSATION_BYTES]:
     * about 3.7 MB. Four times that, about 14.8 MB, leaves a margin that only damaged or forged bytes reach, and [encode]
     * refuses a shard past it, a fault of Kestrel's code, before the archive stores it.
     */
    const val MAX_PLAIN_BYTES: Int = (4 * (PastIndex.SHARD_BYTES + MOST_CONVERSATION_BYTES)).toInt()

    /** The most bytes that [word] takes in a shard's list of words. */
    fun wordBytes(word: String): Long = MAX_NUMBER_BYTES + utf8Bytes(word)

    /** The most bytes that a record of [positions] word positions takes: its number, its time, its count and its positions. */
    fun recordBytes(positions: Int): Long = 2L * MAX_NUMBER_BYTES + LONG_BYTES + positions.toLong() * MAX_NUMBER_BYTES

    /** The most bytes that [conversation]'s listing takes, without its records. */
    fun conversationBytes(conversation: PastConversation): Long =
        4L * MAX_NUMBER_BYTES + 2 * LONG_BYTES + utf8Bytes(conversation.id.value) + utf8Bytes(conversation.firstRequest.orEmpty())

    /** The most bytes that [row] takes. */
    fun keptBytes(row: KeptRow): Long = MAX_NUMBER_BYTES + LONG_BYTES + utf8Bytes(row.id)

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
                out.number(conversation.commands)
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
            check(out.size() <= MAX_PLAIN_BYTES) { "A shard of the index is larger than its decoder allows" }
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
        val list = List(input.count(WORD_BYTES)) { input.text().let { word -> words.getOrPut(word) { word } } }
        val conversations = ArrayList<PastConversation>()
        val records = ArrayList<IndexedRecord>()
        repeat(input.count(CONVERSATION_BYTES)) {
            val id = ConversationId(input.text())
            val openedAtWall = input.long()
            val lastWall = input.long()
            val firstRequest = input.optionalText()
            val commands = input.number()
            repeat(input.count(RECORD_BYTES)) {
                val record = input.number()
                val atWall = input.long()
                var previous = -1
                val positions = IntArray(input.count(POSITION_BYTES)) {
                    previous += input.number() + 1
                    previous
                }
                records += IndexedRecord(RecordRef(id, record), atWall, positions)
            }
            conversations += PastConversation(id, openedAtWall, lastWall, commands, firstRequest)
        }
        val kept = List(input.count(KEPT_BYTES)) { KeptRow(input.text(), input.long()) }
        require(input.atEnd) { "The index has bytes after its end" }
        return IndexShard.restored(number, conversations, SearchIndex.restored(list, records), kept)
    }

    // The fewest bytes that one element of each kind takes in format 1: a word its length; a conversation its id's length,
    // its two times, its first request's length, its count of commands and its count of records; a record its number, its
    // time and its count of words; a position its distance; a kept row its id's length and its time.
    private const val WORD_BYTES = 1
    private const val CONVERSATION_BYTES = 1 + 8 + 8 + 1 + 1 + 1
    private const val RECORD_BYTES = 1 + 8 + 1
    private const val POSITION_BYTES = 1
    private const val KEPT_BYTES = 1 + 8

    private const val BUFFER_BYTES = 64 * 1024

    // The length of [text] in UTF-8, as encodeToByteArray writes it: a lone surrogate becomes a replacement character.
    private fun utf8Bytes(text: String): Long {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index]
            bytes += when {
                char.code < 0x80 -> 1L
                char.code < 0x800 -> 2L
                char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> 4L.also { index++ }
                else -> 3L
            }
            index++
        }
        return bytes
    }

    // The bytes after the format byte, inflated whole: the compressed stream must end exactly where the bytes end, and
    // inflate to at most MAX_PLAIN_BYTES, which is checked before each part is kept.
    private fun inflated(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, 1, bytes.size - 1)
            val plain = ByteArrayOutputStream(minOf(bytes.size.toLong() * 3, MAX_PLAIN_BYTES.toLong()).toInt())
            val buffer = ByteArray(BUFFER_BYTES)
            while (!inflater.finished()) {
                val inflatedBytes = inflater.inflate(buffer)
                require(inflatedBytes > 0 || !inflater.needsInput() && !inflater.needsDictionary()) { "The index is cut short" }
                require(inflatedBytes <= MAX_PLAIN_BYTES - plain.size()) { "The index inflates past its bound" }
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
            for (index in 0 until MAX_NUMBER_BYTES) {
                val byte = next()
                result = result or ((byte and 0x7F) shl (7 * index))
                if (byte and 0x80 == 0) {
                    require(index < MAX_NUMBER_BYTES - 1 || byte <= MAX_LAST_BYTE) { "A number of the index is too large" }
                    return result
                }
            }
            throw IllegalArgumentException("A number of the index is too long")
        }

        // A count of elements that each take at least [each] bytes, so that it can be no more than the bytes that are left
        // can hold: a damaged count is refused before anything is allocated for it.
        fun count(each: Int): Int = number().also { require(it <= (bytes.size - at) / each) { "A count of the index is larger than its bytes" } }

        fun long(): Long {
            var result = 0L
            repeat(8) { result = result shl 8 or next().toLong() }
            return result
        }

        fun text(): String = string(number())

        fun optionalText(): String? = number().let { if (it == 0) null else string(it - 1) }

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
