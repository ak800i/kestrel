package dev.kestrel.eval.task

import dev.kestrel.eval.device.FakeAdb
import kotlin.time.Duration

/**
 * Kestrel's conversation as its hooks show it, for the tests of the turns and of the multi-turn tasks (design 14.4 and
 * 15.3 of sub-project 7). An input starts a command when it is spoken after "Kestrel, ", spoken while the follow-up window
 * is open, or typed; while a command runs, such an input steers it instead. Each command follows the next [Command] of
 * [planned], or one that finishes when they ran out. [inputs] holds the inputs in their order as `spoken:<words>`,
 * `typed:<words>`, `steered:<words>`, `stop` or `unheard:<text>`, and [onInput] gets each one; [answers] holds the run's
 * answers as `<answer>` or `<answer>/<channel>`. As plan 7F's hook does, agent-answer on the text channel refuses a
 * confirmation, a hand-over and a pick, which only the card over the app answers (`channel=card`, plan 7F's Task 13a).
 * conversation-state counts the open conversation's commands (a "continue" adds none) and says whether a hook's input
 * opened it (`typed=`), conversation-control do=expire closes it while no goal is open, do=expire-typed does so only for
 * one that a hook's input opened (plan 7G), do=delete-typed deletes the closed conversations that a hook's input opened, whose marks
 * outlive a restart, as plan 7F keeps them in a file ([deleted]), never one that a switch moved into, and
 * voice-state's followUp= is open from the end of a spoken command until the next input, unless [windows] is false. The
 * hook crash answers `crashing` and ends the process: `pidof` then gives a new process, the command that ran is lost with
 * its tag, a goal that was open is held as interrupted, the fixtures' self-chat aliases are gone with the process's
 * memory, and the restore that turned Kestrel on again happens at [clock]'s now, which voice-restore reads ([restoredAt]).
 */
class FakeConversation(adb: FakeAdb, private val clock: () -> Duration = { Duration.ZERO }) {
    /**
     * How a command goes: it asks [asks] in their order, each the part of agent-state's line that names a question
     * (`asking=`, `confirming=` and `question=`, or `handOver=` for a hand-over), which the run's answer closes, or
     * Kestrel's own wait after [WAIT_READS] reads without one; its goal acts while [until] has not happened and for [reads]
     * more reads of its agent-task; then it ends with [end]. agent-state shows its [checklist] while the command is the
     * newest. A command that [continues] continues the conversation that the quiet time closed last (design 9.6), as a
     * local request without a goal.
     */
    data class Command(
        val end: String = FINISHED,
        val asks: List<String> = emptyList(),
        val until: Until = Until.NOTHING,
        val reads: Int = 0,
        val checklist: String = "none",
        val continues: Boolean = false,
    )

    /** What a command's goal acts until: nothing, a steering input, the owner's touch (goal-control do=touch), or the end of Kestrel's process. */
    enum class Until { NOTHING, STEERED, TOUCHED, CRASHED }

    val planned = ArrayDeque<Command>()
    val inputs = mutableListOf<String>()
    val answers = mutableListOf<String>()
    var onInput: (String) -> Unit = {}
    var windows = true

    /** When the restore at the newest process's start turned Kestrel on, on [clock]; null while none did. */
    var restoredAt: Duration? = null

    /** The conversations that do=delete-typed deleted, by their numbers. */
    val deleted = mutableListOf<Int>()
    var open = false
        private set
    var continued = false
        private set
    var commands = 0
        private set
    var goal = "none"
        private set
    var pid = 4100
        private set
    var aliases = 0
        private set
    /**
     * The commands of the conversation that the quiet time closed last, which a command that [Command.continues] continues;
     * a test reads a run's conversation here, because the run's last restore closes it.
     */
    var closed = 0
        private set

    /** A round's limits as Kestrel stores them, which do=limits sets and do=limits-check reads. */
    var limits = OWNERS_LIMITS
        private set
    private var conversation = 0
    private var conversations = 0
    private var closedConversation = 0
    private var followUp = false
    private var restored = "nothing"
    private var running: String? = null
    private var waiting = Until.NOTHING
    private var reads = 0
    private var unanswered = 0
    private val plans = LinkedHashMap<String, Command>()
    private val questions = mutableMapOf<String, ArrayDeque<String>>()
    private val spoken = mutableSetOf<String>()
    private val lost = mutableSetOf<String>()
    // The conversations that a hook's input opened, every command of the run being tagged, kept across a restart (plan 7F).
    private val own = mutableSetOf<Int>()

    init {
        adb.hook("VOICE") { extras ->
            heard(extras["text"].orEmpty(), extras["tag"])
            "sent"
        }
        adb.hook("CONVERSATION_TYPE") { extras ->
            typed(extras.getValue("text"), extras["tag"])
            "typed read=command" + (extras["tag"]?.let { " tag=$it" } ?: "")
        }
        adb.hookWith("AGENT_TASK") { extras -> task(extras) }
        adb.hookWith("AGENT_STATE") { extras -> state(extras.getValue("tag")) }
        adb.hookWith("AGENT_ANSWER") { extras -> answered(extras) }
        adb.hook("AGENT_SETTINGS") { extras ->
            extras["selfChatAliases"]?.let { text -> aliases = text.split(',').count { it.isNotBlank() } }
            "ownerEmails=0 viberSelfChatName=unset whatsappOwnName=unset selfChatAliases=$aliases"
        }
        adb.hook("SELFCHAT_CHECK") { "selfchat=${if (aliases > 0) "yes" else "no"} signs=telegram-title" }
        adb.hook("VOICE_STATE") { "mode=listening work=idle followUp=${if (followUp) "open" else "closed"}" }
        adb.hook("VOICE_RESTORE") { "restoredAgo=${restoredAt?.let { (clock() - it).inWholeSeconds } ?: "none"} guard=$GUARD_SECONDS" }
        adb.hook("CONVERSATION_STATE") {
            val quiet = if (open && running == null && goal == "none") "running" else "off"
            "open=${yesNo(open)} continued=${yesNo(continued)} commands=$commands goal=$goal quiet=$quiet quietLeft=none replyChannel=voice testMode=on typed=${yesNo(open && conversation in own)}"
        }
        adb.hook("CONVERSATION_CONTROL") { extras -> control(extras) }
        adb.hook("CRASH") {
            crash()
            "crashing"
        }
        adb.on("^pidof dev\\.kestrel") { "$pid" }
        adb.on("^dumpsys activity services dev\\.kestrel/\\.app\\.KestrelAccessibilityService$") { "    app=ProcessRecord{1b2c $pid:dev.kestrel/u0a234}" }
        adb.hook("GOAL_CONTROL") { extras ->
            if (extras["do"] == "touch" && waiting == Until.TOUCHED) waiting = Until.NOTHING
            "${extras["do"]}=sent"
        }
    }

    private fun heard(text: String, tag: String?) {
        val window = followUp
        followUp = false
        when {
            text == "stop" -> stop()
            running != null && text.startsWith(NAME) -> steer(text.removePrefix(NAME))
            text.startsWith(NAME) -> start(tag, "spoken", text.removePrefix(NAME))
            window -> start(tag, "spoken", text)
            else -> add("unheard:$text")
        }
    }

    private fun typed(text: String, tag: String?) {
        followUp = false
        if (running != null) steer(text) else start(tag, "typed", text)
    }

    private fun start(tag: String?, channel: String, words: String) {
        add("$channel:$words")
        val id = checkNotNull(tag) { "a command without a tag" }
        val plan = planned.removeFirstOrNull() ?: Command()
        if (plan.continues) {
            open = true
            continued = true
            commands = closed
            conversation = closedConversation
        } else {
            if (!open) {
                open = true
                continued = false
                commands = 0
                conversation = ++conversations
                own += conversation
            }
            if (words != "continue") commands++
        }
        goal = if (plan.continues) "none" else "acting"
        running = id
        waiting = plan.until
        reads = plan.reads
        unanswered = 0
        plans[id] = plan
        questions[id] = ArrayDeque(plan.asks)
        if (channel == "spoken") spoken += id
    }

    private fun steer(words: String) {
        add("steered:$words")
        if (waiting == Until.STEERED) waiting = Until.NOTHING
    }

    private fun stop() {
        add("stop")
        running = null
        waiting = Until.NOTHING
        goal = "none"
    }

    private fun crash() {
        pid++
        aliases = 0
        restoredAt = clock()
        running?.let { lost += it }
        running = null
        waiting = Until.NOTHING
        if (goal != "none") {
            goal = "interrupted"
            restored = "goal-held"
        } else {
            restored = if (open) "open" else "nothing"
        }
    }

    private fun task(extras: Map<String, String>): Pair<Int, String> {
        val tag = extras.getValue("tag")
        val plan = plans[tag]
        if (plan == null || tag in lost) return 1 to "no task has the tag $tag"
        if (extras.keys.any { it in COMPARED }) return -1 to "state=done marker=1 recipient=match digits=1"
        if (tag != running) return -1 to plan.end
        if (waiting != Until.NOTHING || questions.getValue(tag).isNotEmpty() || reads-- > 0) return -1 to "state=running"
        running = null
        goal = when {
            "end=PAUSED_AT_LIMIT" in plan.end -> "paused-at-limit"
            "end=INTERRUPTED" in plan.end -> "interrupted"
            else -> "none"
        }
        followUp = windows && tag in spoken
        return -1 to plan.end
    }

    private fun state(tag: String): Pair<Int, String> {
        val plan = plans[tag] ?: return 1 to "no task has the tag $tag"
        val phase = when {
            tag == running -> "running"
            tag == plans.keys.last() && goal != "none" -> goal
            else -> "ended"
        }
        val question = questions[tag]?.firstOrNull()?.takeIf { tag == running }
        // Kestrel's own wait ends a question that the run leaves open.
        if (question != null && ++unanswered >= WAIT_READS) {
            questions.getValue(tag).removeFirst()
            unanswered = 0
        }
        val checklist = if (phase == "ended") "none" else plan.checklist
        return -1 to "phase=$phase work=idle handOver=none ${question ?: "asking=none confirming=none question=none"} checklist=$checklist " +
            "conversation=${if (open) "open" else "closed"}"
    }

    private fun answered(extras: Map<String, String>): Pair<Int, String> {
        val tag = extras.getValue("tag")
        val channel = extras["channel"]
        val question = questions[tag]?.firstOrNull()
        if (channel == "text" && question != null && OVERLAY.containsMatchIn(question)) {
            return 1 to "only the card over the app answers this; answer with --es channel card"
        }
        questions[tag]?.removeFirstOrNull()
        unanswered = 0
        answers += extras.getValue("answer") + (channel?.let { "/$it" } ?: "")
        return -1 to "answered tag=$tag" + (channel?.let { " channel=$it" } ?: "")
    }

    private fun control(extras: Map<String, String>): String = when (extras["do"]) {
        "expire" -> {
            if (open && running == null && goal == "none") {
                closed = commands
                closedConversation = conversation
                open = false
                continued = false
                commands = 0
            }
            "expire=sent"
        }
        // Plan 7G: only the hooks' own conversation, as Kestrel checks their mark when it ends the quiet time.
        "expire-typed" -> if (open && conversation in own) control(mapOf("do" to "expire")) else "expire=not-typed"
        "delete-typed" -> {
            val stillOpen = own.filter { open && it == conversation }.toSet()
            val gone = own - stillOpen
            deleted += gone
            own -= gone
            "deleted=${gone.size} open=${stillOpen.size} failed=0"
        }
        "restore-check" -> "restored=$restored"
        "limits" -> {
            val was = limits
            limits = extras["limits"] ?: SMALLEST
            "limits=$limits was=$was"
        }
        "limits-check" -> "limits=$limits"
        else -> "new=sent"
    }

    private fun add(input: String) {
        inputs += input
        onInput(input)
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    companion object {
        const val NAME = "Kestrel, "
        const val WAIT_READS = 3

        /** The owner's limits of a round before a run, and the Conversations page's smallest. */
        const val OWNERS_LIMITS = "500/60/1000"
        const val SMALLEST = "50/5/10"

        /** Kestrel's guard against a crash loop in seconds, as voice-restore answers it (`CRASH_AFTER_RESTORE_MILLIS` of `Listening.kt`, main spec 8.5). */
        const val GUARD_SECONDS = 120
        private val COMPARED = setOf("marker", "recipient", "digits")

        // The questions that only the card over the app answers: a confirmation, a pick and a hand-over (plan 7F).
        private val OVERLAY = Regex("asking=confirm|asking=pick-number|handOver=(?!none)")

        /** agent-task's line of a command's end with [end] and [confirmed] sends, calls and deletes. */
        fun line(end: String = "FINISHED", confirmed: Int = 0): String =
            "state=done end=$end steps=1 workMs=1000 credits=0.10 confirmed=$confirmed unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted in=10 out=2 cacheRead=0 cacheWrite=0"

        val FINISHED = line()
    }
}
