package dev.kestrel.conversation.store

import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot

/**
 * One command as the search sees it (design 9.4 of sub-project 7): where it is, when it was given (wall clock), and its
 * words, as the ascending positions of its texts' folded words in its index's [SearchIndex.words]. toString prints only
 * counts.
 */
class IndexedRecord(val ref: RecordRef, val atWall: Long, val words: IntArray) {
    override fun toString(): String = "IndexedRecord(record=${ref.record}, words=${words.size})"
}

/**
 * The folded words of some records (design 9.2 and 9.4 of sub-project 7): [words], each folded word once and in ascending
 * order, and one [IndexedRecord] per record that holds a word, whose positions point into [words]. A record's texts are
 * folded once, when it enters an index, within the bounds of one conversation ([Builder.add]), so a search folds only its
 * query, finds the positions of the words that count for each query word once
 * per index, and then compares positions. The folded words are the owner's words and Kestrel's, so an index lives in memory
 * or encrypted in the archive, never in a log. An index never changes: [plus] and [without] give a new one. toString prints
 * only counts.
 */
class SearchIndex private constructor(val words: List<String>, val records: List<IndexedRecord>) {
    /** The position of [word] in [words], or a negative number when the index does not hold it. */
    fun position(word: String): Int = words.binarySearch(word)

    /** The positions of the words that start with [letter], which lie together because [words] are in order. */
    fun startingWith(letter: Char): IntRange {
        val first = lowerBound(letter.toString())
        val end = if (letter == Char.MAX_VALUE) words.size else lowerBound((letter + 1).toString())
        return first until end
    }

    /** The folded words of [record], one of this index's records. */
    fun wordsOf(record: IndexedRecord): List<String> = record.words.map { words[it] }

    /** The most plain bytes that this index's words and records take in a shard (`PastIndexCodec`). */
    val plainBytes: Long by lazy { words.sumOf(PastIndexCodec::wordBytes) + records.sumOf { PastIndexCodec.recordBytes(it.words.size) } }

    /** This index with [other]'s records, which replace this index's records of the same conversations. */
    operator fun plus(other: SearchIndex): SearchIndex {
        if (other.records.isEmpty()) return this
        val replaced = other.records.mapTo(HashSet()) { it.ref.conversation }
        val kept = without(replaced)
        val merged = merged(kept.words, other.words)
        return SearchIndex(merged, kept.moved(merged) + other.moved(merged))
    }

    /** This index without the records of [conversations], and without the words that only those records held. */
    fun without(conversations: Set<ConversationId>): SearchIndex {
        if (records.none { it.ref.conversation in conversations }) return this
        val kept = records.filter { it.ref.conversation !in conversations }
        val used = kept.flatMapTo(sortedSetOf()) { record -> record.words.asIterable() }.toIntArray()
        return SearchIndex(used.map { words[it] }, kept.map { IndexedRecord(it.ref, it.atWall, positions(it.words, used)) })
    }

    override fun toString(): String = "SearchIndex(words=${words.size}, records=${records.size})"

    /**
     * Builds an index one conversation at a time, as a rebuild reads them (design 9.2): each word gets a number when it
     * first comes, and [build] sorts the words once and turns the numbers into positions, so that no conversation is kept
     * after it was added. A word that [shared] already holds, from another builder, is taken from it, so that the words of
     * several indexes are kept once in memory. toString prints only counts.
     */
    class Builder(private val shared: MutableMap<String, String> = HashMap()) {
        private val numbers = HashMap<String, Int>()
        private val records = ArrayList<IndexedRecord>()

        /** The most plain bytes that the words and records added so far take in a shard, as [SearchIndex.plainBytes] counts them. */
        var plainBytes = 0L
            private set

        /**
         * Adds [conversation]'s records within the bounds of one conversation (design 9.2 and 9.4): its records in order,
         * each record's words in the order of its texts, at most [CONVERSATION_WORDS] different words and at most
         * [CONVERSATION_POSITIONS] positions in all. A word that would pass a bound is left out, and so is a record that is
         * left without words, because it can match no query. Returns how many word positions the conversation holds.
         */
        fun add(conversation: ConversationSnapshot): Int {
            val own = HashSet<String>()
            var positions = 0
            for (record in conversation.records) {
                if (positions == CONVERSATION_POSITIONS) break
                val found = ArrayList<Int>()
                for (word in folded(record)) {
                    if (positions == CONVERSATION_POSITIONS) break
                    if (word !in own) {
                        if (own.size == CONVERSATION_WORDS) continue
                        own += word
                    }
                    found += number(word)
                    positions++
                }
                if (found.isEmpty()) continue
                records += IndexedRecord(RecordRef(conversation.id, record.number), record.atWall, found.toIntArray())
                plainBytes += PastIndexCodec.recordBytes(found.size)
            }
            return positions
        }

        fun build(): SearchIndex {
            if (records.isEmpty()) return EMPTY
            val words = numbers.keys.sorted()
            val positions = IntArray(words.size)
            words.forEachIndexed { position, word -> positions[numbers.getValue(word)] = position }
            return SearchIndex(words, records.map { record -> IndexedRecord(record.ref, record.atWall, IntArray(record.words.size) { positions[record.words[it]] }.apply { sort() }) })
        }

        override fun toString(): String = "SearchIndex.Builder(words=${numbers.size}, records=${records.size})"

        private fun number(word: String): Int = numbers[word] ?: shared.getOrPut(word) { word }.let { kept ->
            plainBytes += PastIndexCodec.wordBytes(kept)
            numbers.size.also { numbers[kept] = it }
        }
    }

    // This index's records with their positions in [to], a list in order that holds every word of this index; the
    // positions keep their order, because both lists are in the same order.
    private fun moved(to: List<String>): List<IndexedRecord> {
        val moves = IntArray(words.size) { to.binarySearch(words[it]) }
        return records.map { record -> IndexedRecord(record.ref, record.atWall, IntArray(record.words.size) { moves[record.words[it]] }) }
    }

    private fun lowerBound(key: String): Int = words.binarySearch(key).let { if (it >= 0) it else -it - 1 }

    companion object {
        /**
         * The longest folded word that the index holds, in characters: no name or topic is longer, and the longer runs of
         * letters and digits are tokens, codes and numbers, of which a near miss cannot be judged anyway. A longer word is
         * not searchable (design 9.4). With [CONVERSATION_WORDS] and [CONVERSATION_POSITIONS], it bounds what one
         * conversation adds to a shard, so that every shard stays far under the bound that its decoder allows (design 9.2).
         */
        const val MAX_WORD_LENGTH = 40

        /**
         * The most different words that the index holds of one conversation: about 30 commands of heavy use (design R3).
         * The words of a longer conversation that come after them are not searchable; those that it already holds are.
         */
        const val CONVERSATION_WORDS = 10_000

        /**
         * The most word positions that the index holds of one conversation, a fifth of a shard: about 65 commands of heavy
         * use. The records after them are not searchable. Without it, a conversation continued again and again would carry
         * its shard past its decoder's bound by its records alone.
         */
        const val CONVERSATION_POSITIONS = 20_000

        val EMPTY = SearchIndex(emptyList(), emptyList())

        /** The index of the records of [conversations]: the folded words of every text that each record holds. */
        fun of(conversations: List<ConversationSnapshot>): SearchIndex {
            val builder = Builder()
            for (conversation in conversations) builder.add(conversation)
            return builder.build()
        }

        /**
         * An index read back from the archive (`PastIndexCodec`): [words] must ascend without repeats, and each record's
         * positions must ascend within them; otherwise it throws [IllegalArgumentException].
         */
        fun restored(words: List<String>, records: List<IndexedRecord>): SearchIndex {
            require((1 until words.size).all { words[it - 1] < words[it] }) { "The index's words are not in order" }
            for (record in records) {
                val positions = record.words
                require(positions.isEmpty() || (positions.first() >= 0 && positions.last() < words.size)) { "A record's word is outside the index" }
                require((1 until positions.size).all { positions[it - 1] < positions[it] }) { "A record's words are not in order" }
            }
            return SearchIndex(words, records)
        }

        /**
         * The folded words of every text that [record] holds, each once, in the order of its texts: its request and answer,
         * its apps' labels, its data notes, its lines, its ledger and its checklist's items, folded with `Normalizer.words`,
         * so that letter case, diacritics and the two Serbian scripts fold as the matcher folds them. A word longer than
         * [MAX_WORD_LENGTH] is left out.
         */
        fun folded(record: CommandRecord): Set<String> {
            val texts = listOfNotNull(record.request, record.answer) +
                record.apps.map { it.label } +
                record.data.map { it.text } +
                record.lines.map { it.text } +
                record.ledger +
                record.checklist?.items?.map { it.text }.orEmpty()
            val words = LinkedHashSet<String>()
            for (text in texts) Normalizer.words(text).filterTo(words) { it.length <= MAX_WORD_LENGTH }
            return words
        }

        // The positions of [positions] in [used], which holds all of them in order.
        private fun positions(positions: IntArray, used: IntArray): IntArray = IntArray(positions.size) { used.binarySearch(positions[it]) }

        // The words of [first] and [second], two lists in order, each word once, in order.
        private fun merged(first: List<String>, second: List<String>): List<String> {
            val merged = ArrayList<String>(first.size + second.size)
            var i = 0
            var j = 0
            while (i < first.size || j < second.size) {
                val order = if (i == first.size) 1 else if (j == second.size) -1 else first[i].compareTo(second[j])
                if (order <= 0) merged += first[i++] else merged += second[j++]
                if (order == 0) j++
            }
            return merged
        }
    }
}
