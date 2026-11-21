package dev.kestrel.agent.loop

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Ledger
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.memory.Recording
import dev.kestrel.agent.memory.RoutineMatcher
import dev.kestrel.agent.memory.RoutineReplayer
import dev.kestrel.agent.memory.RoutineStep
import dev.kestrel.agent.memory.TaskMemory
import dev.kestrel.agent.observe.Observation
import dev.kestrel.agent.observe.ObservationBuilder
import dev.kestrel.agent.observe.ObservationInput
import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.GlobalKind
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.RoleModel
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.agent.prompt.SystemPrompt
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.Tools
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.Assessment
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.Decision
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.PlayServices
import dev.kestrel.safety.Proposal
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.SafetyCheck
import dev.kestrel.safety.ShareFlow
import dev.kestrel.safety.TaskAllowance
import dev.kestrel.safety.TaskSafety
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.Signature
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ends a task from anywhere in its loop with its reason; it carries no stack trace and no text of the screen. */
internal class Ended(
    val reason: EndReason,
    val detail: String? = null,
    val error: ProviderError? = null,
    val summary: String? = null,
) : Exception(null, null, false, false)

/**
 * What one executed call did (design D6): its [outcome] on its [target] (for `StepResult`), or Kestrel's own result
 * [text] with its [summary] and [last] line; the snapshot [after] it, from which the next observation is built; a data
 * tool's [data] lines; an [extra] text below the result line; a [screenshot]; the [ocr] lines that `look_closer` read;
 * whether it made progress when the step decides that itself ([progress]); and whether it ran at all ([executed] is
 * false when a pause withdrew it).
 */
internal class Done(
    val tool: String,
    val outcome: Outcome? = null,
    val target: TableEntry? = null,
    val detail: String? = null,
    val after: ScreenSnapshot? = null,
    val data: Redacted? = null,
    val extra: Redacted? = null,
    val text: String? = null,
    val summary: String? = null,
    val last: String? = null,
    val screenshot: Part.Image? = null,
    val ocr: List<OcrLine> = emptyList(),
    val progress: Boolean? = null,
    val invalid: Boolean = false,
    val executed: Boolean = true,
)

/** A send that the owner confirmed (design D14), kept in memory for the completion check; never logged. */
internal data class ConfirmedSend(val recipients: List<String>, val content: String?, val share: Boolean) {
    override fun toString(): String = "ConfirmedSend(recipients=${recipients.size}, content=${content?.length}, share=$share)"
}
/** What the safety check lets a step do (design D13 and D14). */
internal sealed interface Clearance {
    /**
     * Go ahead; [confirmation] is the owner's yes when one was asked, or the earlier yes that [covered] this tap of the
     * same share flow (D14).
     */
    data class Go(val category: Category, val confirmation: Confirmation?, val covered: Boolean = false) : Clearance

    /** The step ends with [done] (a refusal or a hand-over that the owner finished). */
    data class Stop(val done: Done) : Clearance

    /** A pause withdrew the question: observe again and decide again (D2). */
    data object Again : Clearance
}

/**
 * One task's run (design D1 to D7, D13 to D16, D21 and D24): the loop of observation, model call, tool-call rules, safety
 * check, action and result, with the pause gates of D2, the limits and the escalation of D7, and the report of D24.
 */
internal class TaskRun(val ports: AgentPorts, val settings: AgentSettings, val task: TaskRequest) {
    val vault = Vault()
    val redactor = Redactor(vault)
    val ledger = Ledger()
    val limits = Limits(ports.clock, ports.model.model.countsCredits)
    val progress = Progress()
    val escalation = Escalation(ports.model.model)
    private val builder = ObservationBuilder(redactor, settings.agentFree)
    private val choices = ToolChoices()
    private val screenSteps = ScreenSteps(this)
    private val dataSteps = DataSteps(this)
    private val looks = LookCloser(this)
    val memory = TaskMemory(ports.memory, ports.apps, ports.clock, redactor) { settings.closed(it) }
    private val recorded = mutableListOf<RoutineStep>()
    private var typedSecret = false
    private var routineNote: String? = null
    private var finished: Recording? = null
    private var offerRoutine = false
    private var pausedAtFinish = false

    var safety = TaskSafety(TaskAllowance.NONE)
    lateinit var conversation: Conversation
    lateinit var observation: Observation
    lateinit var snapshot: ScreenSnapshot
    var replies = 0
        private set

    // What the report and the completion check need.
    val sends = mutableListOf<String>()
    val recipients = mutableListOf<String>()
    val confirmedSends = mutableListOf<ConfirmedSend>()
    val okDataSteps = mutableSetOf<Int>()
    private val ownerSteps = mutableSetOf<Int>()
    val lastChecked = mutableMapOf<String, Boolean>()
    var confirmed = 0
    var unconfirmed = 0
    var beforeTestMode = 0
    var shareItems = 0
    private var appStopped = false
    private var declined = false
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L
    private var pendingPrompt = ""

    suspend fun run(): TaskReport = try {
        start()
        while (true) turn()
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    } catch (e: Ended) {
        if (e.reason in STRIKING && !pausedAtFinish) remembering { memory.strike() }
        report(e.reason, e.detail, e.error, e.summary)
    }

    /** The report of a task that was cancelled with [reason], from what it had done so far (D24, D32 item 4). */
    fun cancelled(reason: EndReason): TaskReport = report(reason, null, null, null)

    private suspend fun start() {
        log(TaskLog.Kind.START, "request: ${redactor.redact(task.text)}")
        val first = checkFront(fresh())
        memory.load()
        val context = memory.context(task.text)
        safety = TaskSafety(
            allowance = TaskAllowance.from(task.text, task.knownNames + memory.aliasKeys),
            ownerWords = Normalizer.words(task.text).toSet(),
            memoryValues = memory.usedValues,
        )
        observation = observe(first, last = null, targetKey = null)
        RoutineMatcher.match(task.text, ports.memory.routines(), memory.aliasKeys, task.knownNames)?.let { match ->
            log(TaskLog.Kind.START, "routine matched")
            routineNote = RoutineReplayer(this).replay(match)
                ?: throw Ended(EndReason.FINISHED, summary = "I ran the routine '${match.routine.name}'.")
            observation = observe(fresh(), last = null, targetKey = null)
        }
        ports.model.model.lightweight.unusable?.let { throw Ended(EndReason.MODEL_UNUSABLE, it) }
        val model = ports.model.model
        conversation = Conversation(
            system = SystemPrompt.TEXT,
            tools = Tools.ALL,
            context = contextText(context),
            task = taskMessage(),
            first = observation,
            taskId = task.id,
            signedThinking = model.lightweight.signedThinking || model.versatile.signedThinking,
            budget = Conversation.budget(model.localOllama, model.lightweight.contextWindow),
        )
        pendingPrompt = observation.text
    }

    // The task message (D3): the owner's words, the local date and time, what the task may do, and a failed routine's
    // note (D23); it never changes.
    private fun taskMessage(): Redacted {
        val now = ports.clock.now()
        val date = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(now)
        // Only the owner's words are redacted here, so that the year is never read as a code; a routine's note is built
        // from parts that were redacted already.
        val owner = Redacted.of(redactor, "The owner's task: ${task.text}").text
        val lines = listOfNotNull(owner, "Today is $date.", safety.allowance.describe(), routineNote)
        return Redacted.kestrel(lines.joinToString("\n"))
    }

    // The per-task context (D3): the memory that the task needs and the test-mode note; it never names the owner's
    // agent-free apps (review B M6).
    private fun contextText(memoryText: String?): Redacted? {
        val parts = listOfNotNull(memoryText, TEST_MODE_NOTE.takeIf { settings.safety.testMode })
        return parts.takeIf { it.isNotEmpty() }?.let { Redacted.of(redactor, it.joinToString("\n\n")) }
    }

    private suspend fun turn() {
        if (gate() || resumePending) resumed()
        checkLimits()
        // A resume during the cost question is observed before this turn's model call too (D2).
        if (resumePending) resumed()
        val priced = callModel()
        replies++
        if (priced == null) {
            handle(escalation.invalid(progress))
            return
        }
        account(priced)
        val message = priced.response.message
        when (val reply = ToolCalls.read(priced.response, observation.table, vault)) {
            is Reply.Invalid -> {
                conversation.add(Step(replies, message, reply.results, reprompt = reply.reprompt))
                pendingPrompt = (reply.results.map { it.text.text } + listOfNotNull(reply.reprompt?.text)).joinToString("\n")
                handle(escalation.invalid(progress))
            }
            is Reply.Act -> act(message, reply)
        }
    }

    private suspend fun act(message: ChatMessage.Assistant, reply: Reply.Act) {
        val signature = observation.signature
        val started = ports.clock.elapsedMillis()
        val target = when (val call = reply.call) {
            is AgentCall.Tap -> observation.table.entry(call.target)
            is AgentCall.LongPress -> observation.table.entry(call.target)
            is AgentCall.TypeText -> observation.table.entry(call.target)
            is AgentCall.Scroll -> call.target?.let(observation.table::entry)
            is AgentCall.Swipe -> call.target?.let(observation.table::entry)
            else -> null
        }
        val done = execute(reply.call)
        val first = StepResult(replies, done.tool, done.outcome ?: Outcome.Answered, done.target, observation.appLabel, done.detail)
        val next = done.after?.let { observe(it, done.last ?: first.lastLine(), done.target?.key, done.ocr) }
        // `Changes` leaves another screen's title to the loop, which takes it from the new observation, redacted there.
        val outcome = if (done.outcome is Outcome.AnotherScreen) Outcome.AnotherScreen(next?.title) else done.outcome
        val result = first.copy(outcome = outcome ?: Outcome.Answered)
        var line = done.text ?: result.resultLine()
        // A data tool that opened an app (navigate, share_media) brings both: its lines go with the result line.
        val data = done.data?.takeIf { next == null }
        if (next != null) done.data?.let { line += "\n" + it.text }
        done.extra?.let { line += "\n" + it.text }
        if (appStopped) {
            line += "\n" + APP_STOPPED
            appStopped = false
        }
        val failed = done.outcome is Outcome.Failed || done.outcome is Outcome.Refused || done.outcome is Outcome.CheckFailed || done.invalid
        conversation.add(Step(
            number = replies,
            reply = message,
            results = listOf(ResultText(reply.callId, reply.name, Redacted.kestrel(line), failed)) + reply.notRun,
            observedCall = if (next != null || data != null) reply.callId else null,
            observation = next,
            data = data,
            summary = Redacted.kestrel(done.summary ?: result.summary()),
            screenshot = done.screenshot,
        ))
        pendingPrompt = listOfNotNull(line, next?.text, data?.text).joinToString("\n")
        log(TaskLog.Kind.ACTION, "${done.tool}: $line (${ports.clock.elapsedMillis() - started} ms)")
        if (done.outcome is Outcome.DataOk) okDataSteps += replies
        if (done.text == OWNER_DID_IT_RESULT) ownerSteps += replies
        if (done.invalid) {
            handle(escalation.invalid(progress))
            return
        }
        if (done.executed) record(message, reply, target, signature, failed)
        progress.record(done.tool, done.outcome, done.target?.key, signature, done.data?.text, added = done.progress == true)
        ledger(done, outcome)
        handle(escalation.afterStep(progress))
    }

    // The task's record for routines (D23) and the labels that its steps acted on, for route notes (D22).
    private fun record(message: ChatMessage.Assistant, reply: Reply.Act, target: TableEntry?, before: Signature, failed: Boolean) {
        val call = reply.call
        if (call is AgentCall.TypeText && Vault.PLACEHOLDER.containsMatchIn(call.text)) typedSecret = true
        if (failed || reply.name in UNRECORDED) return
        memory.acted(target?.packageName, target?.label)
        val arguments = message.toolCalls.firstOrNull { it.id == reply.callId }?.arguments
        recorded += RoutineStep(
            tool = reply.name,
            key = target?.key,
            label = target?.label,
            className = target?.className,
            packageName = target?.packageName,
            category = (arguments?.get("category") as? JsonPrimitive)?.content,
            before = before,
            after = observation.signature,
            title = observation.title,
            text = (call as? AgentCall.TypeText)?.text,
            arguments = arguments,
        )
    }

    /** A step of a routine's replay (D23): its observation, its `ACTION` entry and its ledger line, as a model's step has. */
    internal suspend fun replayed(done: Done, started: Long) {
        val first = StepResult(replies, done.tool, done.outcome ?: Outcome.Answered, done.target, observation.appLabel, done.detail)
        val next = done.after?.let { observe(it, done.last ?: first.lastLine(), done.target?.key, done.ocr) }
        val outcome = if (done.outcome is Outcome.AnotherScreen) Outcome.AnotherScreen(next?.title) else done.outcome
        val line = done.text ?: first.copy(outcome = outcome ?: Outcome.Answered).resultLine()
        log(TaskLog.Kind.ACTION, "routine ${done.tool}: $line (${ports.clock.elapsedMillis() - started} ms)")
        if (done.executed) ledger(done, outcome)
    }

    internal suspend fun execute(call: AgentCall): Done = when (call) {
        is AgentCall.Tap, is AgentCall.LongPress, is AgentCall.TypeText, is AgentCall.Scroll, is AgentCall.Swipe,
        is AgentCall.Back, is AgentCall.Home -> screenSteps.element(call)
        is AgentCall.OpenApp -> screenSteps.openApp(call)
        is AgentCall.OpenUrl, is AgentCall.OpenDeepLink -> screenSteps.openLink(call)
        is AgentCall.OpenSettings -> screenSteps.openSettings(call)
        is AgentCall.Wait -> screenSteps.wait(call)
        is AgentCall.LookCloser -> looks.step(call)
        is AgentCall.AskUser -> askUser(call)
        is AgentCall.Say -> say(call)
        is AgentCall.Finish -> finish(call)
        is AgentCall.Fail -> throw Ended(EndReason.FAILED_BY_MODEL, detail = spoken(call.reason))
        is AgentCall.Remember -> remember(call)
        is AgentCall.Forget -> forget(call)
        else -> dataSteps.run(call)
    }

    // --- The model ---

    private suspend fun callModel(): PricedResponse? {
        while (true) {
            val role = escalation.role
            val current = escalation.current
            val reasoning = if (role == ModelRole.VERSATILE) ReasoningLevel.LOW else null
            val choice = choices.choice(current.id, reasoning)
            // A local Ollama model's history fits the window of the model that gets the request, also after a switch.
            conversation.budget = Conversation.budget(ports.model.model.localOllama, current.contextWindow)
            val request = conversation.request(choice, reasoning, replies + 1) ?: throw Ended(EndReason.CONTEXT_TOO_SMALL)
            log(TaskLog.Kind.PROMPT, "model=${current.id} role=$role new: $pendingPrompt")
            try {
                return ports.model.call(role, request, replies + 1)
            } catch (e: ProviderException) {
                val error = e.error
                log(TaskLog.Kind.ERROR, "provider: $error")
                when {
                    choices.fallBack(current.id, choice, error) -> continue
                    error is ProviderError.BadRequest && current.signedThinking && !conversation.growsOnly -> conversation.growOnly()
                    error == ProviderError.Malformed -> return null
                    escalation.switchesFor(error, progress) -> continue
                    else -> throw Ended(EndReason.PROVIDER, error = error)
                }
            }
        }
    }

    private suspend fun account(priced: PricedResponse) {
        val usage = priced.response.usage
        limits.spent(priced.cost.credits)
        inputTokens += usage.input
        outputTokens += usage.output
        cacheRead += usage.cacheRead
        cacheWrite += usage.cacheWrite
        val parts = priced.response.message.parts.joinToString("; ") { part ->
            when (part) {
                is AssistantPart.ToolCall -> "${part.name} ${redactor.redact(part.arguments.toString())}"
                is AssistantPart.Text -> "text ${redactor.redact(part.text)}"
                is AssistantPart.Reasoning -> "reasoning"
            }
        }
        log(
            TaskLog.Kind.REPLY,
            "$parts; in=${usage.input} out=${usage.output} cacheRead=${usage.cacheRead} cacheWrite=${usage.cacheWrite} credits=${priced.cost.credits}",
        )
    }

    private suspend fun checkLimits() {
        if (limits.repliesUsed(replies)) throw Ended(EndReason.STEPS)
        if (limits.timeUsed()) throw Ended(EndReason.TIME)
        val question = limits.creditQuestion() ?: return
        if (askYesNo(question)) limits.allowMoreCredits() else throw Ended(EndReason.COST)
    }

    private suspend fun handle(escalate: Escalate) {
        when (escalate) {
            Escalate.NOTHING -> Unit
            Escalate.SWITCHED -> log(TaskLog.Kind.ACTION, "switched to the versatile model")
            Escalate.ASK_STUCK -> if (askYesNo(Limits.STUCK_QUESTION)) escalation.keepTrying(progress) else throw Ended(EndReason.STUCK)
            Escalate.END_INVALID -> throw Ended(EndReason.INVALID_CALLS)
            Escalate.END_STUCK -> throw Ended(EndReason.STUCK, detail = ports.model.model.versatile.unusable)
        }
    }

    // --- The screen ---

    suspend fun fresh(): ScreenSnapshot = ports.screen.snapshot() ?: throw Ended(EndReason.NO_SCREEN)

    /** The front app's launcher label, which the observation and the safety check name (D8, D13). */
    suspend fun label(packageName: String?): String? = packageName?.let { ports.apps.label(it) }

    /** The model that the task uses now (D7), whose `takesImages` decides `look_closer`'s screenshot (D20). */
    val currentModel: RoleModel get() = escalation.current

    /**
     * Checks the app in front of [snapshot] before it is observed (D16, D24): an agent-free app or Kestrel ends the task;
     * a Play services screen is handed over; a crashed app is closed with back. Returns the snapshot to observe.
     */
    suspend fun checkFront(snapshot: ScreenSnapshot): ScreenSnapshot {
        var current = snapshot
        repeat(FRONT_CHECKS) {
            val front = ScreenTree.frontWindow(current)?.packageName
            if (settings.agentFree.isAgentFree(front)) throw Ended(EndReason.AGENT_FREE, detail = label(front) ?: front)
            when {
                PlayServices.handsOver(current) -> handOverOnly(HandOver.of(HandOverKind.PLAY_SERVICES))
                crashed(current) -> {
                    ports.screen.act(ScreenAction.Global(GlobalKind.BACK))
                    appStopped = true
                }
                else -> return current
            }
            current = fresh()
        }
        // The screen after the last round is checked too: it is never observed while it shows one of these.
        val front = ScreenTree.frontWindow(current)?.packageName
        if (settings.agentFree.isAgentFree(front)) throw Ended(EndReason.AGENT_FREE, detail = label(front) ?: front)
        if (PlayServices.handsOver(current) || crashed(current)) throw Ended(EndReason.STUCK)
        return current
    }

    private fun crashed(snapshot: ScreenSnapshot): Boolean {
        val system = snapshot.windows.filter { it.packageName == ANDROID }.map { it.id }.toSet()
        val texts = snapshot.windows.filter { it.id in system }.mapNotNull { it.title } +
            snapshot.elements.filter { it.windowId in system }.mapNotNull { it.label }
        return texts.any { CRASHED.containsMatchIn(it) }
    }

    /** The observation of [after], which becomes the newest (D8). */
    suspend fun observe(after: ScreenSnapshot, last: String?, targetKey: String?, ocr: List<OcrLine> = emptyList()): Observation {
        val checked = checkFront(after)
        val front = ScreenTree.frontWindow(checked)?.packageName
        val previous = if (::observation.isInitialized) observation else null
        val next = builder.build(ObservationInput(replies + 1, checked, label(front), last, previous, targetKey, ocr))
        snapshot = checked
        observation = next
        resumePending = false
        return next
    }

    /** The table of an observation of [snapshot] that the model never sees, for `look_closer`'s masks (D20). */
    suspend fun table(snapshot: ScreenSnapshot, ocr: List<OcrLine>): ObservationTable {
        val front = ScreenTree.frontWindow(snapshot)?.packageName
        return builder.build(ObservationInput(replies + 1, snapshot, label(front), ocr = ocr)).table
    }

    /** After a resume (D2): the agent observes the screen again before it acts, because the owner may have changed it. */
    private suspend fun resumed() {
        appStopped = false
        val next = observe(fresh(), last = null, targetKey = null)
        conversation.addObservation(replies, Redacted.kestrel(RESUMED), next)
        pendingPrompt = RESUMED + "\n" + next.text
    }

    // --- The owner ---

    /**
     * The pause gate before a model call, an action or a question (D2); true when the task was paused and resumed. A
     * resume that no observation has shown yet makes the next turn observe the screen again before its model call.
     */
    suspend fun gate(): Boolean = (limits.waiting { ports.owner.gate() } == Gate.RESUMED).also {
        if (it) {
            interruptions = 0
            resumePending = true
        }
    }

    // A resume that a gate inside a step saw (in `say`, `ask_user` or a yes/no question), until an observation shows it.
    private var resumePending = false

    // A question that a touch or the wake word interrupted is asked again after the pause gate; the third interruption in
    // a row without a pause counts as no answer, so that the loop never asks forever.
    private var interruptions = 0

    private fun interrupted(): Boolean = ++interruptions >= MAX_INTERRUPTIONS

    suspend fun askYesNo(question: String): Boolean {
        while (true) {
            gate()
            when (limits.waiting { ports.owner.ask(question, AnswerKind.YES_NO) }) {
                Answer.Yes -> return true.also { interruptions = 0 }
                Answer.Interrupted -> if (interrupted()) return false else continue
                else -> return false.also { interruptions = 0 }
            }
        }
    }

    /** Asks [confirmation] (D14); the time waiting for the owner does not count as work. */
    suspend fun confirm(confirmation: Confirmation): ConfirmEnd {
        val end = limits.waiting { ports.owner.confirm(confirmation) }
        log(TaskLog.Kind.SAFETY, "question: ${redactor.redact(confirmation.question)} answer: $end")
        if (end == ConfirmEnd.NO || end == ConfirmEnd.TIMEOUT) {
            declined = true
            throw Ended(EndReason.DECLINED)
        }
        return end
    }

    /** A hand-over (D15): the owner does the step and says "Kestrel, continue", or it ends the task. */
    suspend fun handOver(handOver: HandOver, tool: String): Done {
        handOverOnly(handOver)
        return Done(tool, text = OWNER_DID_IT_RESULT, summary = "Step $replies: $OWNER_DID_IT.", last = OWNER_DID_IT, after = fresh(), progress = true)
    }

    private suspend fun handOverOnly(handOver: HandOver) {
        gate()
        when (limits.waiting { ports.owner.handOver(handOver) }) {
            HandOverEnd.CONTINUED -> Unit
            HandOverEnd.DONE_BY_ITSELF -> if (handOver.kind == HandOverKind.CALL) {
                // D15 names the person, by the title of the screen that the owner called from.
                throw Ended(EndReason.FINISHED, summary = observation.title?.let { spoken("You placed the call to $it.") } ?: CALL_PLACED)
            }
            HandOverEnd.CANCELLED -> throw Ended(EndReason.CANCELLED)
            HandOverEnd.EXPIRED -> throw Ended(EndReason.HAND_OVER_EXPIRED)
        }
    }

    /** The safety check of one proposal (D13), logged with the decision before test mode and the final one (D25, D27). */
    suspend fun assess(proposal: Proposal, screen: ScreenSnapshot): Assessment {
        val assessment = SafetyCheck.assess(proposal, screen, safety, settings.safety, settings.environment)
        val before = assessment.beforeTestMode
        if (before == Decision.Run && assessment.category in WATCHED) beforeTestMode++
        log(
            TaskLog.Kind.SAFETY,
            "${proposal.tool}: category=${assessment.category.wire} sources=${assessment.sources.sorted()} " +
                "beforeTestMode=${kind(before)} decision=${kind(assessment.decision)}",
        )
        return assessment
    }

    // The share flow at the owner's last yes for a share's tap, by its serial, and the folded names of the recipients that
    // the yes named: one yes covers the following taps of that flow that send to the same recipients, in any order; a new
    // flow, other recipients or a question of another kind asks again (D14).
    private var confirmedSerial: Int? = null
    private var confirmedRecipients: Set<String> = emptySet()

    /** The share flow, by its serial, and the recipients whose send the report already holds (D14, D24). */
    var countedShare: Pair<Int, List<String>>? = null

    // The shares whose taps the owner confirmed and which have not been sent yet, by their flows' serials (D24).
    private val pendingShares = mutableMapOf<Int, Confirmation>()

    // The Cc and Bcc expanders that the task tapped, each only once (D14).
    private val expanded = mutableSetOf<String>()

    /** The safety check and its question or hand-over; a confirmation that the owner declines ends the task (D13, D14). */
    suspend fun decide(proposal: Proposal, screen: ScreenSnapshot, tool: String, target: TableEntry?): Clearance {
        val assessment = assess(proposal, screen)
        return when (val decision = assessment.decision) {
            Decision.Run -> Clearance.Go(assessment.category, null)
            is Decision.Block -> Clearance.Stop(blocked(assessment, decision.reason, tool, target, screen))
            is Decision.HandOver -> Clearance.Stop(handOver(decision.handOver, tool))
            is Decision.Confirm -> {
                if (decision.confirmation.category == Category.SENDING && expandCopies(screen)) return Clearance.Again
                val flow = safety.shareFlow
                // Only a share's own taps that send to chats are covered: a press that sends and names its recipients.
                val shareTap = flow != null && proposal.kind in PRESSING && decision.confirmation.category == Category.SENDING &&
                    decision.confirmation.recipients.isNotEmpty()
                val chats = chats(decision.confirmation.recipients)
                if (shareTap && flow.serial == confirmedSerial && chats == confirmedRecipients) {
                    return Clearance.Go(assessment.category, decision.confirmation, covered = true)
                }
                if (gate()) return Clearance.Again
                if (confirm(decision.confirmation) != ConfirmEnd.YES) {
                    if (!interrupted()) return Clearance.Again
                    declined = true
                    throw Ended(EndReason.DECLINED)
                }
                interruptions = 0
                confirmed++
                confirmedSerial = flow?.serial?.takeIf { shareTap }
                confirmedRecipients = if (shareTap) chats else emptySet()
                Clearance.Go(assessment.category, decision.confirmation)
            }
        }
    }

    // D14: an e-mail's collapsed Cc and Bcc are opened once before its question, so that the question is built on the
    // screen that names every recipient. The tap passes the safety check as a tap that only reads.
    private suspend fun expandCopies(screen: ScreenSnapshot): Boolean {
        val tap = EmailCopies.expand(screen)?.takeIf { it.key !in expanded } ?: return false
        expanded += tap.key
        val proposal = Proposal(Tools.TAP, ActionKind.TAP, Category.READING, targetKey = tap.key, frontAppLabel = observation.appLabel)
        if (assess(proposal, screen).decision != Decision.Run) return false
        val outcome = ports.screen.act(tap)
        log(TaskLog.Kind.ACTION, "tap: opened Cc and Bcc before the question (${if (outcome.done) "done" else "failed"})")
        return outcome.done
    }

    /**
     * The screen changed after a yes, or a press after it may not have worked, so a later send of the share flow needs a
     * new yes (D14, D32 item 29).
     */
    fun forgetConfirmedFlow() {
        confirmedSerial = null
    }

    /** A blocked action: test mode's block ends the task (D18); any other block is the model's to read. */
    fun blocked(assessment: Assessment, reason: String, tool: String, target: TableEntry?, after: ScreenSnapshot): Done {
        if (assessment.beforeTestMode !is Decision.Block) throw Ended(EndReason.TEST_MODE, detail = reason)
        return Done(tool, Outcome.Refused(reason), target, after = after)
    }

    /**
     * A send that the owner confirmed and Kestrel pressed (D14, D24). A tap that an earlier yes [covered] is the same send
     * (a row and then the app's own Send), so it is counted once. A confirmation without recipients (an app's own Share
     * button, whose chat comes next) sends nothing yet.
     */
    fun sent(confirmation: Confirmation, share: Boolean, items: Int, covered: Boolean) {
        if (covered) return
        if (confirmation.category == Category.DELETING) ledger.add("deleted ${deleted(confirmation.content)}")
        if (confirmation.category != Category.SENDING || confirmation.recipients.isEmpty()) return
        confirmedSends += ConfirmedSend(confirmation.recipients, confirmation.content, share)
        recipients += confirmation.recipients
        val to = confirmation.recipients.joinToString(" and ")
        val app = confirmation.appLabel?.let { " on $it" }.orEmpty()
        sends += "Sent to $to$app."
        if (share) {
            shareItems += items
            ledger.add("shared $items ${if (items == 1) "item" else "items"} with $to")
        } else {
            ledger.add("sent the message to $to")
        }
    }

    /**
     * A confirmed tap of share flow [flow] that worked (D14, D24). The share is sent when the flow ended with the tap
     * ([ended]: the app shows the chat that it went to, or it left the flow's apps), as after the app's own Send or a row
     * that sends at once; it is counted once per flow and recipients ([countedShare]). A tap that only picked a chat leaves
     * the share pending: the completion check asks for its evidence, and the report says that it was tried, never sent.
     */
    fun shareTapped(flow: ShareFlow, confirmation: Confirmation, ended: Boolean) {
        if (confirmation.category != Category.SENDING || confirmation.recipients.isEmpty()) return
        if (!ended) {
            pendingShares[flow.serial] = confirmation
            return
        }
        pendingShares -= flow.serial
        val share = flow.serial to chats(confirmation.recipients).sorted()
        if (countedShare == share) return
        countedShare = share
        sent(confirmation, share = true, items = flow.count?.let { it.photos + it.videos } ?: 0, covered = false)
    }

    /**
     * A confirmed press that may not have worked (D32 item 29): it does not count as done, so it adds no "Sent to …" line
     * and no recipient, and the ledger says that the tap may not have worked (D24). A send stays among the confirmed
     * sends, so that the completion check still asks for its evidence (D21); a press without recipients sent nothing.
     */
    fun unsure(confirmation: Confirmation, share: Boolean) {
        val to = confirmation.recipients.joinToString(" and ")
        val what = when {
            confirmation.category == Category.DELETING -> "delete ${deleted(confirmation.content)}"
            confirmation.category != Category.SENDING || confirmation.recipients.isEmpty() -> return
            share -> "share the items with $to"
            else -> "send the message to $to"
        }
        if (confirmation.category == Category.SENDING) {
            confirmedSends += ConfirmedSend(confirmation.recipients, confirmation.content, share)
            // This line already says that the share was tried.
            if (share) safety.shareFlow?.serial?.let { pendingShares -= it }
        }
        ledger.add("tried to $what, but the tap may not have worked")
    }

    // The shares that were confirmed but not seen to be sent, as the completion check needs them (D21).
    private fun pendingSends(): List<ConfirmedSend> = pendingShares.values.map { ConfirmedSend(it.recipients, it.content, share = true) }

    // A share that was confirmed but never seen to be sent is reported as tried (D24).
    private fun pendingLines(): List<String> =
        pendingShares.values.map { "tried to share the items with ${it.recipients.joinToString(" and ")}, but I couldn't see that they were sent" }

    // The folded names of [recipients], which a share's yes compares as a set (D14).
    private fun chats(recipients: List<String>): Set<String> = recipients.map(Normalizer::normalize).toSet()

    // A delete's content is a count for a selection ("3") or the title of what it deletes.
    private fun deleted(content: String?): String = when {
        content == null -> "an item"
        content.all(Char::isDigit) -> if (content == "1") "1 item" else "$content items"
        else -> content
    }

    // --- Dialogue ---

    private suspend fun askUser(call: AgentCall.AskUser): Done {
        while (true) {
            gate()
            val answer = limits.waiting { ports.owner.ask(spoken(call.question), AnswerKind.FREE) }
            val text = when (answer) {
                is Answer.Text -> answer.text
                is Answer.Number -> answer.n.toString()
                Answer.Yes -> "yes"
                Answer.Interrupted -> if (interrupted()) null else continue
                Answer.No, Answer.Silence -> null
            }
            interruptions = 0
            if (text == null) return Done(call.tool, Outcome.NoAnswer)
            safety = safety.withOwnerWords(text)
            val said = Redacted.of(redactor, text)
            return Done(call.tool, Outcome.Answered, extra = Redacted.kestrel("The owner said: " + ScreenText.quote(said.text, ScreenText.VALUE_LIMIT)))
        }
    }

    private suspend fun say(call: AgentCall.Say): Done {
        gate()
        limits.waiting { ports.owner.say(spoken(call.text)) }
        return Done(call.tool, text = "Result: said.", summary = "Step $replies: said something to the owner.", last = "said", progress = false)
    }

    private suspend fun finish(call: AgentCall.Finish): Done {
        // A lock or a call while the model answered: the check cannot run on the screen that the model saw (D21).
        if (gate()) {
            // The pause says nothing about the route notes, so it gives them no strike (D22).
            pausedAtFinish = true
            throw Ended(EndReason.UNVERIFIED, summary = spoken(call.summary))
        }
        val fresh = ports.screen.snapshot()
        val front = fresh?.let { ScreenTree.frontWindow(it)?.packageName }
        if (fresh == null || settings.agentFree.isAgentFree(front) || PlayServices.handsOver(fresh)) {
            throw Ended(EndReason.UNVERIFIED, summary = spoken(call.summary))
        }
        when (val verdict = CompletionCheck.check(call, observation.table, fresh, label(front), okDataSteps + ownerSteps, confirmedSends + pendingSends(), lastChecked)) {
            Verdict.Passed -> Unit
            Verdict.Unverifiable -> throw Ended(EndReason.UNVERIFIED, summary = spoken(call.summary))
            is Verdict.Missing -> {
                val what = "finish failed: ${verdict.what}"
                return Done(call.tool, text = "Result: $what.", summary = "Step $replies: $what.", last = what, after = fresh, invalid = true)
            }
        }
        remembering { memory.admit(call.notes) }
        val signature = call.signature
        val recording = recording(signature)
        finished = recording
        // `:app` offers to save the task after it has spoken the report (D23).
        offerRoutine = recording != null && signature != null && ports.memory.countSignature(signature) == OFFER_AT
        throw Ended(EndReason.FINISHED, summary = spoken(call.summary))
    }

    /** The task's steps for "Kestrel, save that as <name>", or null when it typed a vault value or did nothing (D23). */
    private fun recording(signature: String?): Recording? =
        if (typedSecret || recorded.isEmpty()) null
        else Recording(redactor.redact(task.text), recorded.toList(), signature, ports.clock.elapsedMillis())

    // The `remember` and `forget` tools always ask the owner first (spec 11.4; D22).
    private suspend fun remember(call: AgentCall.Remember): Done {
        val stored = askYesNo("Remember that ${spoken(call.key)} is ${spoken(call.value)}?")
        if (stored) ports.memory.saveFact(Fact(call.key.trim(), call.value.trim(), FactKind.FACT, ports.clock.now().toInstant().toEpochMilli()))
        val words = if (stored) "stored" else "the owner said no"
        return Done(call.tool, text = "Result: $words.", summary = "Step $replies: remember → $words.", last = "remember → $words", progress = stored)
    }

    private suspend fun forget(call: AgentCall.Forget): Done {
        val fact = ports.memory.facts().firstOrNull { Normalizer.normalize(it.key) == Normalizer.normalize(call.key) }
        val words = when {
            fact == null -> "no such fact"
            askYesNo("Forget ${spoken(fact.key)}?") -> if (ports.memory.deleteFact(fact.key)) "deleted" else "no such fact"
            else -> "the owner said no"
        }
        return Done(call.tool, text = "Result: $words.", summary = "Step $replies: forget → $words.", last = "forget → $words", progress = words == "deleted")
    }

    /** Model text that Kestrel speaks (D24): placeholders as their kind's words, at most 600 characters. */
    fun spoken(text: String): String = vault.spoken(text).let { if (it.length > SPOKEN_LIMIT) it.take(SPOKEN_LIMIT - 1) + "…" else it }

    // --- The ledger and the report ---

    private suspend fun ledger(done: Done, outcome: Outcome?) {
        val label = done.target?.shownLabel
        val line = when {
            done.tool == Tools.OPEN_APP && outcome !is Outcome.Failed && outcome !is Outcome.Refused && done.detail != null -> "opened ${done.detail}"
            done.tool == Tools.OPEN_URL && done.after != null && outcome !is Outcome.Failed && outcome !is Outcome.Refused -> "opened a web page"
            done.tool == Tools.OPEN_SETTINGS && done.after != null && outcome !is Outcome.Failed -> "opened the settings page ${done.detail.orEmpty()}".trim()
            outcome == Outcome.TargetChanged && done.target?.checkable == true -> {
                val checked = done.target.key?.let { snapshot.element(it)?.checked }
                checked?.let { now ->
                    done.target.key?.let { lastChecked[it] = now }
                    "turned ${if (now) "on" else "off"} $label"
                }
            }
            outcome == Outcome.TargetChanged && done.tool == Tools.TYPE_TEXT -> "typed into $label"
            outcome is Outcome.AnotherScreen && done.tool == Tools.TAP -> outcome.title?.let { "opened ${ScreenText.quote(it, 40)}" } ?: "opened another screen"
            else -> dataSteps.ledgerLine(done)
        }
        line?.let(ledger::add)
    }

    private fun report(reason: EndReason, detail: String?, error: ProviderError?, summary: String?): TaskReport = TaskReport(
        end = reason,
        detail = detail?.let(vault::spoken),
        summary = if (declined) null else summary,
        error = error,
        ledger = (ledger.lines + pendingLines()).map(vault::spoken),
        sends = sends.toList(),
        recipients = recipients.toList(),
        steps = replies,
        workMillis = limits.workMillis,
        credits = limits.credits,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        confirmed = confirmed,
        unconfirmed = unconfirmed,
        beforeTestMode = beforeTestMode,
        shareItems = shareItems,
        modelId = ports.model.model.lightweight.id,
        recording = finished.takeIf { reason == EndReason.FINISHED },
        offerRoutine = offerRoutine && reason == EndReason.FINISHED,
    )

    /** Writes [text] to the activity log; a failure of the log never ends the task. */
    suspend fun log(kind: TaskLog.Kind, text: String) {
        try {
            ports.log.write(kind, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The log is advisory for the task; its own store reports its failure.
        }
    }

    /** Writes to memory, which is only advice (D22): a failure of its store is logged by class and never ends the task. */
    suspend fun remembering(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TaskLog.Kind.ERROR, "memory: ${e.javaClass.name}")
        }
    }

    suspend fun logEnd(report: TaskReport) {
        log(
            TaskLog.Kind.END,
            "end=${report.end} steps=${report.steps} workMs=${report.workMillis} credits=${report.credits} " +
                "in=${report.inputTokens} out=${report.outputTokens} cacheRead=${report.cacheRead} cacheWrite=${report.cacheWrite} " +
                "summary: ${report.summary?.let(redactor::redact).orEmpty()} ledger: ${report.ledger.joinToString("; ") { redactor.redact(it) }}",
        )
    }

    fun close() {
        vault.clear()
        ports.data.forgetTask()
    }

    private fun kind(decision: Decision): String = when (decision) {
        Decision.Run -> "Run"
        is Decision.Confirm -> "Confirm"
        is Decision.HandOver -> "HandOver(${decision.handOver.kind})"
        is Decision.Block -> "Block"
    }

    override fun toString(): String = "TaskRun(replies=$replies)"

    companion object {
        const val SPOKEN_LIMIT = 600
        const val TEST_MODE_NOTE = "Test mode is on: Kestrel sends only to the owner's own chats, and it never calls anyone."
        const val TEST_MODE_BLOCK = "Test mode: Kestrel only sends to your own chats."
        const val RESUMED = "The task was paused and has resumed; this is the screen now. Check it before you act."
        const val APP_STOPPED = "The app stopped; Kestrel went back."
        const val OWNER_DID_IT = "the owner did this step by hand"
        const val OWNER_DID_IT_RESULT = "Result: The owner did this step by hand."
        const val CALL_PLACED = "You placed the call."
        private const val ANDROID = "android"
        private const val FRONT_CHECKS = 3
        private const val MAX_INTERRUPTIONS = 3
        private val PRESSING = setOf(ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.TAP_AT)
        private val CRASHED = Regex("""(isn't responding|keeps stopping)$""")
        private const val OFFER_AT = 2
        private val WATCHED = setOf(Category.SENDING, Category.CALLS, Category.DELETING)
        private val STRIKING = setOf(EndReason.FAILED_BY_MODEL, EndReason.UNVERIFIED, EndReason.STUCK)
        private val UNRECORDED = setOf(Tools.ASK_USER, Tools.SAY, Tools.FINISH, Tools.FAIL, Tools.REMEMBER, Tools.FORGET, Tools.LOOK_CLOSER)
    }
}
