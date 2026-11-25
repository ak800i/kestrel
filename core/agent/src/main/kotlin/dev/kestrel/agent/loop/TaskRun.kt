package dev.kestrel.agent.loop

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.Ledger
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
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
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.AgentFreeApps
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
    val interruption: Interruption? = null,
) : Exception(null, null, false, false)

/**
 * What one executed call did (design D6): its [outcome] on its [target] (for `StepResult`), or Kestrel's own result
 * [text] with its [summary] and [last] line; the snapshot [after] it, from which the next observation is built; a data
 * tool's [data] lines; an [extra] text below the result line; a [screenshot]; the [ocr] lines that `look_closer` read;
 * whether it made progress when the step decides that itself ([progress]); whether it ran at all ([executed] is
 * false when a pause withdrew it); whether a data tool that worked changed nothing ([unchanged]), so that the ledger names
 * nothing; and whether the step counts for progress at all ([counted]: `update_checklist` neither makes progress nor counts
 * as a step without progress, design 7.2 of sub-project 7).
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
    val unchanged: Boolean = false,
    val counted: Boolean = true,
) {
    /** Whether the step failed: its action failed, was refused or failed its check, or its call was not valid. */
    val failed: Boolean get() = outcome is Outcome.Failed || outcome is Outcome.Refused || outcome is Outcome.CheckFailed || invalid

    /** How many items the [data] held, as its outcome counted them; the data's note is none. */
    val items: Int get() = (outcome as? Outcome.DataOk)?.count ?: 0
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
 * check, action and result, with the pause gates of D2, the limits and the escalation of D7, and the report of D24. It
 * tells the goal around it what the command's record needs through [trace] (plan 7A; design 8.1 of sub-project 7).
 */
internal class TaskRun(given: AgentPorts, val settings: AgentSettings, val task: TaskRequest) {
    val vault = Vault()
    val redactor = Redactor(vault)

    /** What the run tells the goal around it for the command's record (plan 7A). */
    val trace = RunTrace(given.goal, given.apps, vault, redactor) { kind, text -> log(kind, text) }

    /** The given ports with the run's own owner port, which traces the dialogue (plan 7A). */
    val ports: AgentPorts = given.withOwner(TracingOwner(given.owner, trace))
    val ledger = Ledger()
    val limits = Limits(ports.clock, ports.model.model.countsCredits, task.limits)
    val progress = Progress()
    val escalation = Escalation(ports.model.model)
    private val builder = ObservationBuilder(redactor, settings.agentFree)
    private val choices = ToolChoices()
    val launches = Launches(this)
    private val screenSteps = ScreenSteps(this)
    private val dataSteps = DataSteps(this)
    private val looks = LookCloser(this)
    private val dialogue = DialogueSteps(this)
    private val checklists = ChecklistSteps(this, task.checklist)
    private val searches = HistorySteps(this)

    /** What the run says to the owner and asks him, whose questions share one count of interruptions (D2). */
    val talk = OwnerTalk(this)
    val memory = TaskMemory(ports.memory, ports.apps, ports.clock, redactor) { settings.closed(it) }
    private val recorded = mutableListOf<RoutineStep>()
    private var typedSecret = false
    private var routineNote: String? = null
    private var finished: Recording? = null
    private var offerRoutine = false
    private var pausedAtFinish = false

    var safety = TaskSafety(TaskAllowance.NONE)

    // The names that the allowance reads: the request's known contact names and memory's aliases (D13).
    private var names: Set<String> = emptySet()
    lateinit var conversation: Conversation
    lateinit var observation: Observation
    lateinit var snapshot: ScreenSnapshot
    var replies = 0
        private set

    /** What the run sent, shared and deleted after the owner's yes, for the report and the completion check (D14, D24). */
    val sends = Sends(ledger) { safety.shareFlow }
    val okDataSteps = mutableSetOf<Int>()
    private val ownerSteps = mutableSetOf<Int>()

    // The steps that acted on the screen without failing; with the data steps that gave a result and the steps that the
    // owner did, they are the steps that a done item of the checklist may name (design 7.2 of sub-project 7).
    private val actedSteps = mutableSetOf<Int>()
    val lastChecked = mutableMapOf<String, Boolean>()

    /**
     * The percent that each slider showed after the task's last set_slider on it, or else the percent asked for, by key
     * (design D19 of sub-project 6).
     */
    val lastSlider = mutableMapOf<String, Int>()
    var confirmed = 0
    var unconfirmed = 0
    var beforeTestMode = 0
    private var appStopped = false
    private var declined = false
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L
    private var pendingPrompt = ""

    // The owner's words of the request, redacted once (design 8.2 of sub-project 7).
    private lateinit var request: Redacted

    suspend fun run(): TaskReport = try {
        start()
        while (true) turn()
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    } catch (e: Ended) {
        if (e.reason in STRIKING && !pausedAtFinish) remembering { memory.strike() }
        report(e.reason, e.detail, e.error, e.summary, e.interruption)
    }

    /**
     * The report of a task that was cancelled with [reason], and [interruption] when a touch interrupted it, from what it
     * had done so far (D24, D32 item 4).
     */
    fun cancelled(reason: EndReason, interruption: Interruption? = null): TaskReport = report(reason, null, null, null, interruption)

    private suspend fun start() {
        request = TaskText.request(task, redactor)
        log(TaskLog.Kind.START, "request: ${request.text}")
        // A resumed goal's request is in its record already (design 7.6 of sub-project 7).
        if (task.resume == null) trace.requested(request)
        val first = checkFront(fresh())
        remembering { memory.load() }
        val context = recalled(null) { memory.context(task.text) }
        names = task.knownNames + memory.aliasKeys
        safety = OwnerWords.seeded(task, names, memory.usedValues)
        observation = observe(first, last = null, targetKey = null)
        // A resumed goal replays no routine: it continues from its checklist (design 7.6 of sub-project 7).
        if (task.resume == null) RoutineMatcher.match(task.text, recalled(emptyList()) { ports.memory.routines() }, memory.aliasKeys, task.knownNames)?.let { match ->
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
            context = TaskText.context(task, settings.safety.testMode, context, redactor),
            task = TaskText.message(task, request, today(), safety.allowance, routineNote),
            first = observation,
            taskId = task.id,
            signedThinking = model.lightweight.signedThinking || model.versatile.signedThinking,
            budget = Conversation.budget(model.localOllama, model.lightweight.contextWindow),
        )
        pendingPrompt = observation.text
    }

    // The local date and time of the task message (D3).
    private fun today(): String = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(ports.clock.now())

    private suspend fun turn() {
        if (gate() || resumePending) resumed()
        checkLimits()
        // A resume during the limit's question is observed before this turn's model call too (D2).
        if (resumePending) resumed()
        steer()
        // The step's number for the goal, once the waits before the step are over (design 10.5 of sub-project 7).
        trace.stepStarted(replies + 1)
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

    // The owner's steering since the last turn (design 7.3 of sub-project 7), each after the last step, which ran to its
    // end before it.
    private fun steer() {
        for (steer in ports.goal.takeSteering()) {
            val before = safety.allowance
            safety = OwnerWords.steered(safety, steer.text, names)
            val message = TaskText.steered(steer, replies, safety.allowance.describe().takeIf { safety.allowance != before }, redactor)
            conversation.addOwnerMessage(replies, message)
            pendingPrompt += "\n" + message.text
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
            is AgentCall.SetSlider -> observation.table.entry(call.target)
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
        val failed = done.failed
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
        done.data?.let { trace.data(done.tool, replies, it, done.items) }
        if (done.outcome is Outcome.DataOk) okDataSteps += replies
        if (done.text == OWNER_DID_IT_RESULT) ownerSteps += replies
        if (done.executed && !failed && done.tool in Tools.ACTING) actedSteps += replies
        checklists.stepped(done.tool)
        if (done.invalid) {
            handle(escalation.invalid(progress))
            return
        }
        if (done.executed) record(message, reply, target, signature, failed)
        // update_checklist neither makes progress nor counts as a step without progress (design 7.2 of sub-project 7).
        if (!done.counted) return
        progress.record(done.tool, done.outcome, done.target?.key, signature, done.data?.text, added = done.progress == true)
        ledger(done, outcome)
        trace.ledger(ledger.lines)
        handle(escalation.afterStep(progress))
    }

    // The task's record for routines (D23), the labels that its steps acted on, for route notes (D22), and the apps that
    // they acted in, for the command's record (design 8.1 of sub-project 7).
    private suspend fun record(message: ChatMessage.Assistant, reply: Reply.Act, target: TableEntry?, before: Signature, failed: Boolean) {
        val call = reply.call
        if (call is AgentCall.TypeText && Vault.PLACEHOLDER.containsMatchIn(call.text)) typedSecret = true
        if (failed || reply.name in UNRECORDED) return
        memory.acted(target?.packageName, target?.label)
        target?.packageName?.let { trace.acted(it) }
        val arguments = message.toolCalls.firstOrNull { it.id == reply.callId }?.arguments
        recorded += RoutineStep(
            tool = reply.name,
            key = target?.key,
            // As the observation showed it, redacted with its neighbours and the task's vault, which is gone when the
            // owner saves the routine (D17, D23).
            label = target?.shownLabel,
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

    /**
     * A step of a routine's replay (D23): its observation, its `ACTION` entry and its ledger line, as a model's step has,
     * and for the command's record its data and the app that it acted in (design 8.1 of sub-project 7). A replay runs
     * before the model's first step, so the record names its data by the routine's own [step] number.
     */
    internal suspend fun replayed(done: Done, started: Long, step: Int) {
        val first = StepResult(replies, done.tool, done.outcome ?: Outcome.Answered, done.target, observation.appLabel, done.detail)
        val next = done.after?.let { observe(it, done.last ?: first.lastLine(), done.target?.key, done.ocr) }
        val outcome = if (done.outcome is Outcome.AnotherScreen) Outcome.AnotherScreen(next?.title) else done.outcome
        val line = done.text ?: first.copy(outcome = outcome ?: Outcome.Answered).resultLine()
        log(TaskLog.Kind.ACTION, "routine ${done.tool}: $line (${ports.clock.elapsedMillis() - started} ms)")
        done.data?.let { trace.data(done.tool, step, it, done.items) }
        if (done.executed && !done.failed) done.target?.packageName?.let { trace.acted(it) }
        if (done.executed) ledger(done, outcome)
        trace.ledger(ledger.lines)
    }

    internal suspend fun execute(call: AgentCall): Done = when (call) {
        is AgentCall.Tap, is AgentCall.LongPress, is AgentCall.TypeText, is AgentCall.Scroll, is AgentCall.Swipe, is AgentCall.SetSlider,
        is AgentCall.Back, is AgentCall.Home -> screenSteps.element(call)
        is AgentCall.OpenApp -> screenSteps.openApp(call)
        is AgentCall.OpenUrl, is AgentCall.OpenDeepLink -> screenSteps.openLink(call)
        is AgentCall.OpenSettings -> screenSteps.openSettings(call)
        is AgentCall.Wait -> screenSteps.wait(call)
        is AgentCall.LookCloser -> looks.step(call)
        is AgentCall.AskUser -> dialogue.askUser(call)
        is AgentCall.Say -> dialogue.say(call)
        is AgentCall.Finish -> finish(call)
        is AgentCall.Fail -> throw Ended(EndReason.FAILED_BY_MODEL, detail = spoken(call.reason))
        is AgentCall.Remember -> dialogue.remember(call)
        is AgentCall.Forget -> dialogue.forget(call)
        is AgentCall.UpdateChecklist -> checklists.update(call, actedSteps + okDataSteps + ownerSteps)
        is AgentCall.SearchConversations -> searches.search(call)
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

    // At a round's first limit the owner is asked "Continue?" (design 7.4 of sub-project 7): yes gives the same again, no
    // stops the goal, and no answer pauses it. The question is built by code, never by the model.
    private suspend fun checkLimits() {
        val limit = limits.reached(replies) ?: return
        val question = LimitQuestion.text(limit, replies, limits.workMillis, limits.credits, checklists.checklist, ledger.lines.map(vault::spoken))
        // The third interruption in a row counts as no answer, which keeps a goal open.
        when (talk.ask(question, AnswerKind.LIMIT)) {
            Answer.Yes -> limits.nextRound(replies)
            Answer.Silence -> throw Ended(EndReason.PAUSED_AT_LIMIT)
            else -> throw Ended(EndReason.STOPPED_AT_LIMIT)
        }
    }

    private suspend fun handle(escalate: Escalate) {
        when (escalate) {
            Escalate.NOTHING -> Unit
            Escalate.SWITCHED -> log(TaskLog.Kind.ACTION, "switched to the versatile model")
            Escalate.ASK_STUCK -> if (talk.yes(Limits.STUCK_QUESTION)) escalation.keepTrying(progress) else throw Ended(EndReason.STUCK)
            Escalate.END_INVALID -> throw Ended(EndReason.INVALID_CALLS)
            Escalate.END_STUCK -> throw Ended(EndReason.STUCK, detail = ports.model.model.versatile.unusable)
        }
    }

    // --- The screen ---

    suspend fun fresh(): ScreenSnapshot = ports.screen.snapshot() ?: throw Ended(EndReason.NO_SCREEN)

    /** The front app's launcher label, which the observation and the safety check name (D8, D13). */
    suspend fun label(packageName: String?): String? = packageName?.let { ports.apps.label(it) }

    /**
     * Whether Kestrel may capture [snapshot] (D16, D20): never while a Play services window is on screen or an agent-free
     * app is in front. The eyes refuse the recent-apps and home screens themselves.
     */
    fun capturable(snapshot: ScreenSnapshot): Boolean {
        if (snapshot.windows.any { it.packageName == AgentFreeApps.PLAY_SERVICES }) return false
        val front = ScreenTree.frontWindow(snapshot)?.packageName ?: return false
        return !settings.agentFree.isAgentFree(front)
    }

    /** The model that the task uses now (D7), whose `takesImages` decides `look_closer`'s screenshot (D20). */
    val currentModel: RoleModel get() = escalation.current

    /**
     * Checks the app in front of [snapshot] before it is observed (D16, D24): Kestrel's own screen is waited for through
     * [pastKestrel]; another agent-free app ends the task; a Play services screen is handed over; a crashed app is closed
     * with back. Returns the snapshot to observe.
     */
    suspend fun checkFront(snapshot: ScreenSnapshot): ScreenSnapshot {
        var current = snapshot
        repeat(FRONT_CHECKS) {
            current = pastKestrel(current) { fresh() }
            val front = ScreenTree.frontWindow(current)?.packageName
            when {
                settings.agentFree.isAgentFree(front) -> throw Ended(EndReason.AGENT_FREE, detail = label(front) ?: front)
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
        current = pastKestrel(current) { fresh() }
        val front = ScreenTree.frontWindow(current)?.packageName
        if (settings.agentFree.isAgentFree(front)) throw Ended(EndReason.AGENT_FREE, detail = label(front) ?: front)
        if (PlayServices.handsOver(current) || crashed(current)) throw Ended(EndReason.STUCK)
        return current
    }

    /**
     * The screen behind Kestrel's own (design 10.2 of sub-project 7), the one rule for every look at the screen: [snapshot]
     * when Kestrel's own screen is not in front of it; otherwise the owner port waits until that screen has left, a wait
     * that does not count as work, and [read] reads the screen again, at most [FRONT_CHECKS] times, since the screen may
     * come back. The task ends as interrupted when Kestrel's own screen stays through a wait or is still in front after
     * the last one. A null screen, which [read] may give, is returned as it is.
     */
    private suspend fun <S : ScreenSnapshot?> pastKestrel(snapshot: S, read: suspend () -> S): S {
        var current = snapshot
        repeat(FRONT_CHECKS) {
            if (current?.let(AgentFreeApps::kestrelInFront) != true) return current
            if (!limits.waiting { ports.owner.leaveKestrel() }) throw kestrelStayed()
            current = read()
        }
        if (current?.let(AgentFreeApps::kestrelInFront) == true) throw kestrelStayed()
        return current
    }

    private fun kestrelStayed(): Ended = Ended(EndReason.INTERRUPTED, interruption = Interruption.KESTREL_SCREEN)

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
            talk.countAgain()
            resumePending = true
        }
    }

    // A resume that a gate inside a step saw (in `say`, `ask_user` or a yes/no question), until an observation shows it.
    private var resumePending = false

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
                val chats = Sends.chats(decision.confirmation.recipients)
                if (shareTap && flow.serial == confirmedSerial && chats == confirmedRecipients) {
                    return Clearance.Go(assessment.category, decision.confirmation, covered = true)
                }
                if (gate()) return Clearance.Again
                if (confirm(decision.confirmation) != ConfirmEnd.YES) {
                    if (!talk.interrupted()) return Clearance.Again
                    declined = true
                    throw Ended(EndReason.DECLINED)
                }
                talk.countAgain()
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

    // --- Finishing ---

    private suspend fun finish(call: AgentCall.Finish): Done {
        // An open item of the checklist refuses the finish before anything else, so that a pause cannot end the run as
        // unverified while an item is open (design 7.2 of sub-project 7).
        checklists.finishRefusal()?.let { return finishFailed(call, it, after = null) }
        // A lock or a call while the model answered: the check cannot run on the screen that the model saw (D21).
        if (gate()) {
            // The pause says nothing about the route notes, so it gives them no strike (D22).
            pausedAtFinish = true
            throw Ended(EndReason.UNVERIFIED, summary = spoken(call.summary))
        }
        // Kestrel's own screen in front is waited for as before every observation; when it stays, the task ends as
        // interrupted, which is no failure of the route notes and gives them no strike (design 10.2 of sub-project 7).
        val fresh = pastKestrel(ports.screen.snapshot()) { ports.screen.snapshot() }
        val front = fresh?.let { ScreenTree.frontWindow(it)?.packageName }
        if (fresh == null || settings.agentFree.isAgentFree(front) || PlayServices.handsOver(fresh)) {
            throw Ended(EndReason.UNVERIFIED, summary = spoken(call.summary))
        }
        when (val verdict = CompletionCheck.check(call, observation.table, fresh, label(front), okDataSteps + ownerSteps, sends.confirmed, lastChecked, lastSlider, safety::hide)) {
            Verdict.Passed -> Unit
            Verdict.Unverifiable -> throw Ended(EndReason.UNVERIFIED, summary = spoken(call.summary))
            is Verdict.Missing -> return finishFailed(call, verdict.what, after = fresh)
        }
        remembering { memory.admit(call.notes) }
        val signature = call.signature
        val recording = recording(signature)
        finished = recording
        // `:app` offers to save the task after it has spoken the report (D23); a store that cannot count makes no offer.
        offerRoutine = recording != null && signature != null && recalled(0) { ports.memory.countSignature(signature) } == OFFER_AT
        throw Ended(EndReason.FINISHED, summary = spoken(call.summary))
    }

    // A finish that the run refuses is an invalid call, whose result says what is missing; [after] is the screen that the
    // check read, if it read one.
    private fun finishFailed(call: AgentCall.Finish, reason: String, after: ScreenSnapshot?): Done {
        val what = "finish failed: $reason"
        return Done(call.tool, text = "Result: $what.", summary = "Step $replies: $what.", last = what, after = after, invalid = true)
    }

    /** The task's steps for "Kestrel, save that as <name>", or null when it typed a vault value or did nothing (D23). */
    private fun recording(signature: String?): Recording? =
        if (typedSecret || recorded.isEmpty()) null
        else Recording(request.text, recorded.toList(), signature, ports.clock.elapsedMillis())

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
            outcome is Outcome.SliderAt -> outcome.percent?.let { "set $label to $it %" }
            outcome is Outcome.AnotherScreen && done.tool == Tools.TAP -> outcome.title?.let { "opened ${ScreenText.quote(it, 40)}" } ?: "opened another screen"
            else -> dataSteps.ledgerLine(done)
        }
        line?.let(ledger::add)
    }

    private fun report(reason: EndReason, detail: String?, error: ProviderError?, summary: String?, interruption: Interruption?): TaskReport = TaskReport(
        end = reason,
        interruption = interruption,
        detail = detail?.let(vault::spoken),
        summary = if (declined) null else summary,
        error = error,
        ledger = (ledger.lines + sends.pendingLines).map(vault::spoken),
        sends = sends.lines,
        recipients = sends.recipients,
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
        shareItems = sends.shareItems,
        modelId = ports.model.model.lightweight.id,
        recording = finished.takeIf { reason == EndReason.FINISHED },
        offerRoutine = offerRoutine && reason == EndReason.FINISHED,
        checklist = checklists.checklist,
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
        recalled(Unit, write)
    }

    /** Reads memory, which is only advice (D22): a failure of its store is logged by class, and the task goes on with [fallback]. */
    suspend fun <T> recalled(fallback: T, read: suspend () -> T): T =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TaskLog.Kind.ERROR, "memory: ${e.javaClass.name}")
            fallback
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
        const val TEST_MODE_BLOCK = "Test mode: Kestrel only sends to your own chats."
        const val RESUMED = "The task was paused and has resumed; this is the screen now. Check it before you act."
        const val APP_STOPPED = "The app stopped; Kestrel went back."
        const val OWNER_DID_IT = "the owner did this step by hand"
        const val OWNER_DID_IT_RESULT = "Result: The owner did this step by hand."
        const val CALL_PLACED = "You placed the call."
        private const val ANDROID = "android"
        private const val FRONT_CHECKS = 3
        private val PRESSING = setOf(ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.TAP_AT)
        private val CRASHED = Regex("""(isn't responding|keeps stopping)$""")
        private const val OFFER_AT = 2
        private val WATCHED = setOf(Category.SENDING, Category.CALLS, Category.DELETING)
        private val STRIKING = setOf(EndReason.FAILED_BY_MODEL, EndReason.UNVERIFIED, EndReason.STUCK)
        private val UNRECORDED = setOf(
            Tools.ASK_USER, Tools.SAY, Tools.FINISH, Tools.FAIL, Tools.REMEMBER, Tools.FORGET, Tools.LOOK_CLOSER, Tools.UPDATE_CHECKLIST,
            Tools.SEARCH_CONVERSATIONS,
        )
    }
}
