package dev.kestrel.app.debug

import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.commands.text.Normalizer
import java.time.LocalDate

/**
 * The data tools of a task whose run gave a [marker], for agent-task's `markerInData=` (plan 6B-basefix): [seen] is
 * whether a result of a data tool that reads held the marker's words in order in one of its fields. It keeps only that
 * flag, never a value, and passes every result on unchanged.
 */
internal class MarkerWatch(private val data: DataPort, private val marker: String) : DataPort by data {
    @Volatile
    var seen = false
        private set

    override suspend fun findContact(name: String): DataResult = look(data.findContact(name))

    override suspend fun findMedia(from: LocalDate, until: LocalDate, kinds: MediaKinds): DataResult = look(data.findMedia(from, until, kinds))

    override suspend fun listEvents(from: LocalDate, to: LocalDate): DataResult = look(data.listEvents(from, to))

    override suspend fun listNotifications(): DataResult = look(data.listNotifications())

    override suspend fun listSms(from: String?, limit: Int): DataResult = look(data.listSms(from, limit))

    override suspend fun listCalls(limit: Int): DataResult = look(data.listCalls(limit))

    private fun look(result: DataResult): DataResult {
        if (result is DataResult.Ok && result.lines.any { line -> line.fields.any { holds(it.value, marker) } }) seen = true
        return result
    }

    override fun toString(): String = "MarkerWatch(seen=$seen)"

    companion object {
        /** Whether [text] holds [marker]'s words in order, compared as the matcher folds them (design D29 of sub-project 5). */
        fun holds(text: String, marker: String): Boolean {
            val wanted = Normalizer.words(marker)
            if (wanted.isEmpty()) return false
            val words = Normalizer.words(text)
            return (0..words.size - wanted.size).any { start -> wanted.indices.all { words[start + it] == wanted[it] } }
        }
    }
}
