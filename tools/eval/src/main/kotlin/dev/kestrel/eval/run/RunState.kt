package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.task.PutBack
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A command that the run gave Kestrel (design 14.4 of sub-project 7): its [tag], the end that its turn expects
 * ([expected], null for any end), whether its words went to Kestrel ([sent]), and its end once the run read it ([answer]).
 * toString prints no words.
 */
class SentCommand(val tag: String, val expected: String?) {
    @Volatile
    var sent = false

    @Volatile
    var answer: HookAnswer? = null

    /** Whether the run has not read the command's end yet. */
    val open: Boolean get() = answer == null

    override fun toString(): String = "SentCommand(tag=$tag, expected=$expected, sent=$sent, ended=${answer != null})"
}

/** What a run met; the shutdown hook's restores read it too, for the commands' stop, the credits and the detail line. */
internal class RunState(private val putBack: PutBack) {
    var call = false

    /** The commands that the run gave Kestrel, in their order; the shutdown hook's thread reads them too. */
    val commands: MutableList<SentCommand> = CopyOnWriteArrayList()
    var timedOut = false
    var stalled = false
    var refusal: String? = null
    var refused: String? = null
    val declined = mutableSetOf<String>()

    // The commands' questions as the run met them, and its answers that reached Kestrel (T1).
    val record = AskedRecord()

    // agent-task's answer with the run's marker, read once after the end for the newest command, for the detail line.
    @Volatile
    var markers: HookAnswer? = null

    /** The command whose end the run reads next: the newest, while its end is not read. */
    val current: SentCommand? get() = commands.lastOrNull()?.takeIf { it.open }

    /** The tag of the run's newest command, which the checks of its reply read. */
    val tag: String? get() = commands.lastOrNull()?.tag

    /** The run's answer: its commands' ends as one ([Outcomes]), or null while a command's end is not read. */
    val answer: HookAnswer? get() = Outcomes.merge(commands)

    /** The run's answers that reached Kestrel, and the questions that it left open on purpose, in their order. */
    val asked: List<Asked> get() = record.asked

    /** The kinds of the questions and hand-overs whose answer by the run ended a command, each once. */
    fun cancelled(): List<String> = record.cancelled()

    /** The run's detail line, from what it met and what its restores put back so far. */
    fun detail(task: String, number: Int) = RunDetail(task, number, asked, answer, markers, putBack.applied, putBack.stored, putBack.leftChanged)
}
