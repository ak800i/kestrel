package dev.kestrel.app.debug

import dev.kestrel.conversation.model.ConversationId
import java.io.File

/**
 * The conversations that the debug hooks opened (plan 7F's Tasks 13 and 13a): those that `conversation-type` or a tagged
 * input's work opened, never one that a switch moved into or that was open before, which may be the owner's (review N4 of
 * plan 7F, N1 of plan 7G). `conversation-control --es do delete-typed` deletes them and clears their marks. The marks are
 * kept by id only, in [file], a file of Kestrel's own storage in debug builds, so that they survive a restart of Kestrel.
 */
internal class HookConversations(private val file: File) {
    private val ids = LinkedHashSet<ConversationId>()

    init {
        // A line that is no conversation's id is no mark.
        if (file.isFile) file.readLines().mapNotNullTo(ids) { line -> runCatching { ConversationId(line.trim()) }.getOrNull() }
    }

    @Synchronized
    fun own(id: ConversationId) {
        if (ids.add(id)) save()
    }

    @Synchronized
    fun all(): List<ConversationId> = ids.toList()

    @Synchronized
    fun forget(done: Collection<ConversationId>) {
        if (ids.removeAll(done.toSet())) save()
    }

    private fun save() = file.writeText(ids.joinToString("\n") { it.value })

    override fun toString(): String = "HookConversations(${ids.size})"

    companion object {
        /** The marks' file in Kestrel's `noBackupFilesDir`. */
        const val FILE = "hook-conversations.txt"

        @Volatile
        private var current: HookConversations? = null

        /** Reads the marks of [dir] once in this process; every hook call attaches them (TestHooks). */
        @Synchronized
        fun attach(dir: File) {
            if (current == null) current = HookConversations(File(dir, FILE))
        }

        fun own(id: ConversationId) {
            current?.own(id)
        }

        fun all(): List<ConversationId> = current?.all().orEmpty()

        fun forget(done: Collection<ConversationId>) {
            current?.forget(done)
        }
    }
}
