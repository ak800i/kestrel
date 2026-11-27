package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.task.PutBack
import dev.kestrel.eval.task.TurnChannel

/**
 * A command that the run gave Kestrel (design 14.4 of sub-project 7): its [tag], the end that its turns expect
 * ([expected], null for any end), whether its words went to Kestrel ([sent]), its end once the run read it ([answer]),
 * whether Kestrel's process ended under it ([lost]), which lost it with its tag, and its questions with the run's answers
 * ([record]). toString prints no words.
 */
class SentCommand(val tag: String, expected: String?) {
    /** The command's questions as the run met them, and its answers that reached Kestrel (T1); its end closes them. */
    val record = AskedRecord()

    /** The end that the command's turn expects; the [dev.kestrel.eval.task.Turn.AwaitEnd] after a command that the run followed until its goal acted names it. */
    @Volatile
    var expected: String? = expected

    @Volatile
    var sent = false

    @Volatile
    var answer: HookAnswer? = null

    @Volatile
    var lost = false

    /** Whether the run may still read the command's end: it has not, and the command was not lost. */
    val open: Boolean get() = answer == null && !lost

    override fun toString(): String = "SentCommand(tag=$tag, expected=$expected, sent=$sent, ended=${answer != null}, lost=$lost)"
}

/**
 * What a run met; the shutdown hook's restores read it too, for the commands' stop, the credits and the detail line.
 * [commands] are the commands that the run gave Kestrel, in their order, each with its questions and the run's answers
 * (T1); they are the run scope's, so that a check reads their answers too.
 */
internal class RunState(private val putBack: PutBack, val commands: MutableList<SentCommand>) {
    var call = false

    var timedOut = false
    var stalled = false
    var refusal: String? = null
    var refused: String? = null
    val declined = mutableSetOf<String>()

    /** The channel of the owner's latest input, on which the run answers Kestrel's questions (design 11 of sub-project 7). */
    var channel = TurnChannel.SPOKEN

    /** The check between turns that did not hold, which fails the run with `reason=check`; null while every one held. */
    var unmet: String? = null

    /** Whether a restart or a step between turns failed, which fails the run with `reason=setup`. */
    var stepFailed = false

    // agent-task's answer with the run's marker, read once after the end for the newest command, for the detail line.
    @Volatile
    var markers: HookAnswer? = null

    /** The command whose end the run reads next: the newest, while its end is not read and it was not lost. */
    val current: SentCommand? get() = commands.lastOrNull()?.takeIf { it.open }

    /** The tag of the run's newest command, which the checks of its reply read. */
    val tag: String? get() = commands.lastOrNull()?.tag

    /** The run's answer: its commands' ends as one ([Outcomes]), or null while a command's end is not read. */
    val answer: HookAnswer? get() = Outcomes.merge(commands)

    /** The run's answers that reached Kestrel, and the questions that it left open on purpose, command by command. */
    val asked: List<Asked> get() = commands.flatMap { it.record.asked }

    /**
     * The run's questions before the one that [command] has open now: those of its earlier commands and the command's own
     * before it ([AskedRecord.before]), so that a limit's answer counts the run's limits before it (plan 7G).
     */
    fun before(command: SentCommand): List<Asked> = commands.takeWhile { it !== command }.flatMap { it.record.asked } + command.record.before

    /**
     * The kinds of the questions and hand-overs whose answer by the run ended the command whose end the run's answer
     * carries ([Outcomes.deciding]), each once: an answer that ended an earlier command does not explain the run's end.
     */
    fun cancelled(): List<String> = Outcomes.deciding(commands)?.record?.cancelled().orEmpty()

    /** The run's detail line, from what it met and what its restores put back so far. */
    fun detail(task: String, number: Int) = RunDetail(task, number, asked, answer, markers, putBack.applied, putBack.stored, putBack.leftChanged)
}
