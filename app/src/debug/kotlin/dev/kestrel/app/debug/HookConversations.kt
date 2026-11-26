package dev.kestrel.app.debug

import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ConversationId
import java.io.File

/**
 * The conversations that the debug hooks opened (plan 7F's Tasks 13 and 13a): those that `conversation-type` or a tagged
 * input's work opened, never one that a switch moved into or that was open before, which may be the owner's (review N4 of
 * plan 7F, N1 of plan 7G). A mark means that only the hooks' inputs are in the conversation: the owner's input with his
 * words or his choice, or his switch into it, clears it (`CommandTags.onConversationStep`; review M1 of Task 13a).
 * `conversation-control --es do delete-typed` deletes them and clears their marks. The marks are kept by id only, in
 * [file], a file of Kestrel's own storage in debug builds, so that they survive a restart of Kestrel; the rule that clears
 * them watches every step of every process from its start ([watch]; review I2 of plan 7F).
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

        /**
         * Starts the marks' rule for this process (`DebugKestrelApplication`, once, before any of the conversation's
         * steps): reads the marks of [dir], has [tags] mark the conversation that a hook's input's work opens and clear the
         * one that the owner's input with his words or his choice reaches, and gives [install] the watcher of the
         * conversation's steps, `AppGraph.conversationSteps`.
         */
        @Synchronized
        fun watch(dir: File, tags: CommandTags, install: ((ConversationEvent, ConversationState, MachineStep) -> Unit) -> Unit) {
            val marks = HookConversations(File(dir, FILE))
            current = marks
            tags.ownConversation = marks::own
            tags.ownerConversation = { id -> marks.forget(listOf(id)) }
            install(tags::onConversationStep)
        }

        fun all(): List<ConversationId> = current?.all().orEmpty()

        fun forget(done: Collection<ConversationId>) {
            current?.forget(done)
        }
    }
}
