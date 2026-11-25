package dev.kestrel.agent.goal

/**
 * What a task run needs from the goal around it (design 5.4 of sub-project 7): `:core:conversation`'s `RunningGoal`
 * implements it for each goal's round, and `GoalRunner`'s "save that as" round passes [NoGoal]. The run drains the
 * owner's steering before each model call (design 7.3), says when each step starts (design 10.5), reports each checklist
 * that the rules accepted (design 7.2), and traces what the command's record needs as it happens, so that the record
 * survives the end of Kestrel's process (design 8.1).
 */
interface GoalPort {
    /** The owner's steering since the last call, oldest first; the loop drains it before each model call. */
    fun takeSteering(): List<Steer>

    /**
     * Step [step] of the run starts: its waits are over, its steering is drained, and its model call comes next. The goal
     * shows the number, as the bar's "Step 12 · 3 of 7" (design 10.5); it must not block.
     */
    fun stepStarted(step: Int)

    /** The model's checked checklist; the goal stores and shows it. */
    suspend fun checklistChanged(checklist: Checklist)

    /** Something that the command's record needs, as it happens. */
    suspend fun traced(entry: TraceEntry)

    /**
     * Takes the screen's turn as a wait for the owner ends (design 6.5 of sub-project 7; `TaskRun.waitForOwner`): returns
     * once no instant command holds the screen; true when it waited, because the screen may have changed meanwhile. A run
     * without a goal around it, as "save that as", acts on no screen and takes nothing.
     */
    suspend fun takeScreen(): Boolean = false

    /**
     * Lets go of the screen's turn as a wait for the owner starts (design 6.5 of sub-project 7; `TaskRun.waitForOwner`), so
     * that his instant commands can run while he has the phone. A run without a goal around it holds no turn.
     */
    fun releaseScreen() = Unit
}

/** How the owner gave a steering message (design 7.3 and D9 of sub-project 7): spoken after "Kestrel", or typed. */
enum class SteerChannel(val wire: String) { SPOKEN("spoken"), TYPED("typed") }

/**
 * One steering message of the owner's while the goal acts (design 7.3 of sub-project 7): his own words, which count as his
 * and extend the run's allowance. The loop names the step after which it came. toString prints only the length.
 */
data class Steer(val text: String, val channel: SteerChannel) {
    override fun toString(): String = "Steer(text=${text.length} chars, channel=$channel)"
}

/**
 * What a run traces for the command's record (design 8.1 of sub-project 7): never an observation, a screenshot, an OCR
 * line or a tool's arguments (D3), and never a vault value, which every text names by its kind's words. Every text was
 * redacted once, when it entered the run. toString prints only kinds and lengths.
 */
sealed interface TraceEntry {
    /** The owner's request of a run that is no resume. */
    data class Requested(val text: String) : TraceEntry {
        override fun toString(): String = "Requested(${text.length} chars)"
    }

    /** What Kestrel said: a `say`, a hand-over's sentence or a permission request's reason. */
    data class Said(val text: String) : TraceEntry {
        override fun toString(): String = "Said(${text.length} chars)"
    }

    /** A question or a confirmation that Kestrel asked. */
    data class Asked(val text: String) : TraceEntry {
        override fun toString(): String = "Asked(${text.length} chars)"
    }

    /** The owner's answer: his words, a number, yes or no. */
    data class Answered(val text: String) : TraceEntry {
        override fun toString(): String = "Answered(${text.length} chars)"
    }

    /**
     * A data tool's result of step [step], as the model saw it: quoted and escaped. [items] is how many items it held, as
     * the step's outcome counted them; the result's note below them, such as "3 contacts match", is none.
     */
    data class Data(val tool: String, val step: Int, val text: String, val items: Int) : TraceEntry {
        override fun toString(): String = "Data(tool=$tool, step=$step, items=$items, text=${text.length} chars)"
    }

    /** An app that an executed step acted in or opened, by its launcher [label], redacted and cut as a label on the screen is. */
    data class AppUsed(val label: String, val packageName: String) : TraceEntry {
        override fun toString(): String = "AppUsed(label=${label.length} chars, package=${packageName.length} chars)"
    }

    /** A line of the ledger: what Kestrel did, in its own words (design D24 of sub-project 5). */
    data class Did(val line: String) : TraceEntry {
        override fun toString(): String = "Did(${line.length} chars)"
    }
}

/** The goal of a round that serves no goal, as "save that as" (plan 7D): no steering, and nothing kept or shown. */
object NoGoal : GoalPort {
    override fun takeSteering(): List<Steer> = emptyList()

    override fun stepStarted(step: Int) = Unit

    override suspend fun checklistChanged(checklist: Checklist) = Unit

    override suspend fun traced(entry: TraceEntry) = Unit
}
