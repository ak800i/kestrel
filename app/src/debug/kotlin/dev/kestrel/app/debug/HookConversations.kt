package dev.kestrel.app.debug

import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ConversationId
import java.io.File

/** What watches the conversation's steps: `AppGraph.conversationSteps`'s type. */
internal typealias StepWatcher = (ConversationEvent, ConversationState, MachineStep) -> Unit

/**
 * The conversations that the debug hooks opened (plan 7F's Tasks 13 and 13a): those that `conversation-type` or a
 * tagged input's work opened, never one that a switch moved into or that was open before, which may be the owner's
 * (review N4 of plan 7F, N1 of plan 7G). A mark means that only the hooks' inputs are in the conversation: the owner's
 * input with his words or his choice, or his switch into it, clears it, also a hook's switch whose request his input
 * reached, as his "yes" to its question (`CommandTags.onConversationStep`; review M1 of Task 13a, plan 7G). `conversation-control --es do delete-typed` deletes them and clears their marks. The marks are kept by id
 * only, in [file], a file of Kestrel's own storage in debug builds, so that they survive a restart of Kestrel; the rule
 * that clears them watches every step of every process from its start ([watch]; review I2 of plan 7F). Kestrel's own
 * request that runs while no conversation is open, as a switch that asks before it moves into a past conversation, carries
 * the mark itself while only the hooks' inputs reached it ([ownsWork]; plan 7G); that mark is kept in memory only, because
 * no request outlives its process.
 */
internal class HookConversations(private val file: File) {
    private val ids = LinkedHashSet<ConversationId>()

    @Volatile
    private var request = false

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

    /** Whether [id] is one of the hooks' own conversations, a mark that no input of the owner's has cleared. */
    @Synchronized
    fun owns(id: ConversationId?): Boolean = id != null && id in ids

    @Synchronized
    fun forget(done: Collection<ConversationId>) {
        if (ids.removeAll(done.toSet())) save()
    }

    /** Notes whether Kestrel's own request that runs while no conversation is open is the hooks'. */
    fun requestOwned(owned: Boolean) {
        request = owned
    }

    /**
     * Whether the work that a stop would end now is the hooks' own: the mark of [open], the open conversation, or while none
     * is open, the mark of Kestrel's own request that runs.
     */
    fun ownsWork(open: ConversationId?): Boolean = if (open != null) owns(open) else request

    private fun save() = file.writeText(ids.joinToString("\n") { it.value })

    override fun toString(): String = "HookConversations(${ids.size})"

    companion object {
        /** The marks' file in Kestrel's `noBackupFilesDir`. */
        const val FILE = "hook-conversations.txt"

        @Volatile
        private var current: HookConversations? = null

        /** Clears the process's marks, so that a JVM test leaves none behind for the next. */
        internal fun reset() {
            current = null
        }

        /**
         * Starts the marks' rule for this process (`DebugKestrelApplication`, once, before any of the conversation's
         * steps): reads the marks of [dir], has [tags] mark the conversation that a hook's input's work opens and clear
         * the one that the owner's input with his words or his choice reaches, and gives [install] the watcher of the
         * conversation's steps, `AppGraph.conversationSteps`.
         */
        @Synchronized
        fun watch(dir: File, tags: CommandTags, install: (StepWatcher) -> Unit) {
            val marks = HookConversations(File(dir, FILE))
            current = marks
            tags.ownConversation = marks::own
            tags.ownerConversation = { id -> marks.forget(listOf(id)) }
            tags.ownRequest = marks::requestOwned
            install(tags::onConversationStep)
        }

        fun all(): List<ConversationId> = current?.all().orEmpty()

        /** Whether [id] carries the hooks' mark in this process (plan 7G's `expire-typed`). */
        fun owns(id: ConversationId?): Boolean = current?.owns(id) == true

        /**
         * Whether the work that a stop would end is the hooks' own in this process (plan 7G's `typed=` and `stop-typed`): the
         * mark of [open], the open conversation, or while none is open (null), the mark of Kestrel's own request that runs.
         */
        fun ownsWork(open: ConversationId?): Boolean = current?.ownsWork(open) == true

        fun forget(done: Collection<ConversationId>) {
            current?.forget(done)
        }
    }
}
