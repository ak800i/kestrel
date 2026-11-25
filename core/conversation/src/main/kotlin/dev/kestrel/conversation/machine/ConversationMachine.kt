package dev.kestrel.conversation.machine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequest
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.GoalEnding
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.ProcessEnd
import dev.kestrel.conversation.model.RecordRef
import dev.kestrel.conversation.model.RoundEnd

/**
 * The conversation's rules (design 6 of sub-project 7): one function from a state, an event and the wall clock's time
 * [now] to the new state and its effects, as `Rules.next` is for the voice path. It never calls a channel, the store or a
 * goal; `ConversationEngine` performs the effects in order. [ids] makes a new conversation's id.
 *
 * After every event the quiet clock settles ([QuietRules]), and the snapshot is saved ([ConversationEffect.Persist]) when
 * a stored part changed.
 */
class ConversationMachine(private val ids: () -> ConversationId) {
    fun next(state: ConversationState, event: ConversationEvent, now: Long): MachineStep {
        val step = when (event) {
            is ConversationEvent.Input -> input(state.copy(replyChannel = event.input.channel), event.input, now)
            is ConversationEvent.Dialogue -> GoalRules.dialogue(state, event.event)
            is ConversationEvent.GoalEnded -> goalEnded(state, event.end, now)
            ConversationEvent.Touched -> GoalRules.touched(state)
            is ConversationEvent.ReplyShown -> MachineStep(if (state.replying == event.command) state.copy(replying = null) else state)
            is ConversationEvent.TimerEnded ->
                if (event.timer == Timer.QUIET) quietEnded(state, event.number) else GoalRules.timerEnded(state, event.timer, event.number)
            is ConversationEvent.Composing -> MachineStep(state.copy(composing = event.active))
            is ConversationEvent.Awaiting -> MachineStep(state.copy(awaiting = event.active))
            is ConversationEvent.Voice -> GoalRules.voice(state, event.on)
            is ConversationEvent.ReplyOpened -> GoalRules.replyOpened(state, event.question)
            is ConversationEvent.Signal -> GoalRules.signal(state, event.signal, now)
            is ConversationEvent.SettingsChanged -> settingsChanged(state, event, now)
            is ConversationEvent.Restored -> restored(state, event.snapshot, event.previous, now)
            is ConversationEvent.ContinueAnswered -> GoalRules.continueAnswered(state, event.answer, now)
            is ConversationEvent.LocalEnded -> localEnded(state, event, now)
            is ConversationEvent.Switched -> switched(state, event, now)
            ConversationEvent.EndQuietTime -> if (state.quiet is QuietClock.Running) close(state) else MachineStep(state)
        }
        return persisted(QuietRules.settle(step, now), state)
    }

    // Design 6.4: an open question takes the owner's answer first; a local request lets a command wait for it.
    private fun input(state: ConversationState, input: OwnerInput, now: Long): MachineStep {
        state.question?.let { open -> answer(state, open, input)?.let { return it } }
        state.local?.let { run -> return whileLocal(state, run, input) }
        return when (val intent = input.intent) {
            is InputIntent.Command -> command(state, input, intent.text, now)
            is InputIntent.Answer -> MachineStep(state)
            InputIntent.Stop -> GoalRules.stop(state)
            InputIntent.Continue -> continued(state, input, now)
            InputIntent.NewConversation -> withoutGoal(state, input) { newConversation(it, input) }
            is InputIntent.ContinuePast -> withoutGoal(state, input) { local(it, LocalRequest.ContinuePast(intent.topic), input) }
            is InputIntent.ContinueConversation -> withoutGoal(state, input) { switch(it, intent.id, input) }
            // Design 9.7: while a goal is open, "delete history" is ignored, as today.
            InputIntent.DeleteHistory ->
                if (state.goal != null && state.goal.ending != GoalEnding.STOPPING) MachineStep(state) else withoutGoal(state, input) { local(it, LocalRequest.DeleteHistory, input) }
            is InputIntent.Remember -> withoutGoal(state, input) { memory(it, input, LocalRequest.Remember(intent.key, intent.value), now) }
            is InputIntent.Forget -> withoutGoal(state, input) { memory(it, input, LocalRequest.Forget(intent.key), now) }
            is InputIntent.SaveRoutine -> withoutGoal(state, input) { memory(it, input, LocalRequest.SaveRoutine(intent.name), now) }
        }
    }

    // The owner's answer, or a command's words, answer the open question; "Kestrel, continue" answers "Continue the goal?"
    // with yes and is ignored by the other questions. A command while "Continue the goal?" waits stays a command.
    // The input that answers "Continue the goal?" is kept for the round that the answer resumes.
    private fun answer(state: ConversationState, open: OpenQuestion, input: OwnerInput): MachineStep? {
        val continuing = open.kind == QuestionKind.CONTINUE_GOAL
        val answering = if (continuing) state.copy(continueAnswer = input) else state
        val typed = input.channel == InputChannel.TEXT
        return when (val intent = input.intent) {
            // A screen button that names another question came late: the question that it answered has gone (plan 7F).
            is InputIntent.Answer ->
                if (intent.question != null && intent.question != open.number) {
                    MachineStep(state)
                } else {
                    MachineStep(answering, listOf(ConversationEffect.AnswerQuestion(open.number, intent.answer, leftScreen = typed)))
                }
            is InputIntent.Command ->
                if (continuing) {
                    null
                } else {
                    MachineStep(state, listOf(ConversationEffect.AnswerQuestion(open.number, ChannelAnswer.Text(intent.text), leftScreen = typed)))
                }
            InputIntent.Continue ->
                if (continuing) MachineStep(answering, listOf(ConversationEffect.AnswerQuestion(open.number, ChannelAnswer.Yes, leftScreen = typed))) else MachineStep(state)
            else -> null
        }
    }

    // A command waits for the request in its turn; "stop" cancels the request, a switch too, and drops what waits.
    private fun whileLocal(state: ConversationState, run: LocalRun, input: OwnerInput): MachineStep = when (input.intent) {
        is InputIntent.Command -> MachineStep(state.copy(waiting = state.waiting + input))
        InputIntent.Stop -> MachineStep(state.copy(waiting = emptyList(), local = run.copy(cancelled = true)), listOf(ConversationEffect.CancelLocal))
        else -> MachineStep(state)
    }

    private fun command(state: ConversationState, input: OwnerInput, text: String, now: Long): MachineStep {
        val goal = state.goal ?: return startGoal(open(state, now), input, text, now)
        if (goal.ending != null) return MachineStep(state.copy(waiting = state.waiting + input))
        return when (goal.phase) {
            GoalPhase.ACTING, GoalPhase.ASKING -> MachineStep(state, listOf(ConversationEffect.SteerGoal(text, input.channel)))
            GoalPhase.HANDED_OVER -> refuse(state, ConversationTexts.finishHandOverFirst(input.channel))
            GoalPhase.PAUSED -> refuse(state, ConversationTexts.ANSWER_CONTINUE_FIRST)
            GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> GoalRules.resume(state, goal, input, text, now)
        }
    }

    private fun startGoal(state: ConversationState, input: OwnerInput, text: String, now: Long): MachineStep {
        val record = state.commands + 1
        val command = state.command + 1
        val goal = GoalState(record = record, phase = GoalPhase.ACTING, changedAtWall = now, command = command)
        return MachineStep(
            state.copy(commands = record, command = command, goal = goal),
            listOf(ConversationEffect.StartGoal(command, record, input, text)),
        )
    }

    private fun continued(state: ConversationState, input: OwnerInput, now: Long): MachineStep {
        val goal = state.goal ?: return refuse(state, ConversationTexts.NOTHING_TO_CONTINUE)
        val channel = input.channel
        if (goal.ending != null) return MachineStep(state)
        return when (goal.phase) {
            GoalPhase.ACTING, GoalPhase.ASKING -> MachineStep(state)
            GoalPhase.HANDED_OVER -> MachineStep(
                state,
                listOfNotNull(
                    goal.handOver?.let { ConversationEffect.EndHandOver(it, HandOverEnd.CONTINUED) },
                    ConversationEffect.AddLine(state.openId, goal.record, LineKind.OWNER_CONTINUED, ConversationTexts.CONTINUE_WORD, channel),
                ),
            )
            GoalPhase.PAUSED -> GoalRules.resumePaused(state, goal, channel, now)
            GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> GoalRules.resume(state, goal, input, steer = null, now = now)
        }
    }

    // "New conversation" numbers a command, which ends at once and names its input, as every numbered command does.
    private fun newConversation(state: ConversationState, input: OwnerInput): MachineStep {
        val command = state.command + 1
        return close(state.copy(command = command))
            .then { MachineStep(it, listOf(ConversationEffect.EndLocal(command, EndReason.FINISHED, ConversationTexts.NEW_CONVERSATION, input))) }
            .then { reply(it, command, ConversationTexts.NEW_CONVERSATION) }
    }

    private fun local(state: ConversationState, request: LocalRequest, input: OwnerInput): MachineStep {
        val command = state.command + 1
        return MachineStep(state.copy(command = command, local = LocalRun(command, null, input)), listOf(ConversationEffect.RunLocal(command, null, request, input)))
    }

    // A memory request is a command of the conversation: it opens one if none is open, and it gets a record (design 3).
    private fun memory(state: ConversationState, input: OwnerInput, request: LocalRequest, now: Long): MachineStep {
        val opened = open(state, now)
        val record = opened.commands + 1
        val command = opened.command + 1
        return MachineStep(
            opened.copy(commands = record, command = command, local = LocalRun(command, RecordRef(opened.openId, record), input)),
            listOf(ConversationEffect.RunLocal(command, record, request, input)),
        )
    }

    private fun switch(state: ConversationState, id: ConversationId, input: OwnerInput): MachineStep {
        val command = state.command + 1
        return MachineStep(state.copy(command = command, local = LocalRun(command, null, input)), listOf(ConversationEffect.SwitchTo(command, id, input)))
    }

    // The machine alone decides whether a round's end holds the goal (GoalHold.of): a goal that is stopping ("stop", a
    // change of test mode, the call backstop, the end of a hold) is not held, but ends as a held goal with its first stop's
    // reason and sentence, its partial report and the steering that waited named in its final words (EndHeldGoal; review
    // of plan 7B: I1 and n1).
    private fun goalEnded(state: ConversationState, round: RoundEnd, now: Long): MachineStep {
        val goal = state.goal?.takeIf { it.command == round.command } ?: return MachineStep(state)
        val hold = GoalHold.of(round.report)
        if (hold != null) {
            // The bar's step counts on across the goal's rounds (design 10.5).
            val counted = goal.copy(stepsBefore = goal.stepsBefore + round.report.steps)
            val stop = goal.stop ?: return GoalRules.held(state, counted, hold, round.reply, now).then { runWaiting(it, now) }
            val stopping = counted.copy(phase = hold.phase, hold = hold, changedAtWall = now, handOver = null)
            return MachineStep(state.copy(goal = stopping, question = null), listOf(ConversationEffect.EndHeldGoal(stop.reason, stop.sentence)))
        }
        val recorded = endGoal(state, goal, round.report.end, round.answer)
        val sentence = round.reply
        return when {
            // A command that waited for the stop runs in a new conversation, under the new test mode.
            state.closeAfterWork -> recorded.then { if (sentence != null) finalReply(it, goal.command, sentence) else MachineStep(it) }
                .then { close(it) }
                .then { runWaiting(it, now) }
            // "Stop. Kestrel, …": the old goal's report is shown, and the waiting commands run (design 6.4).
            state.waiting.isNotEmpty() -> recorded.then { MachineStep(it, listOfNotNull(sentence?.let { s -> ConversationEffect.Output(ChannelOutput.Shown(s)) })) }
                .then { runWaiting(it, now) }
            sentence == null -> recorded
            else -> recorded.then { finalReply(it, goal.command, sentence) }
        }
    }

    // A request that "stop" cancelled before it ended by itself (its end says nothing then) ended as cancelled; one whose
    // reply came first had finished. A switch that the owner said yes to and then stopped does not happen. A memory
    // request's record names its conversation, so its end never needs an open one; after a change of test mode, the
    // conversation closes once the request's end is recorded.
    private fun localEnded(state: ConversationState, event: ConversationEvent.LocalEnded, now: Long): MachineStep {
        val run = state.local?.takeIf { it.command == event.command } ?: return MachineStep(state)
        val outcome = event.outcome
        if (outcome is LocalOutcome.Switch && !run.cancelled) return MachineStep(state, listOf(ConversationEffect.SwitchTo(run.command, outcome.id, run.input)))
        val done = state.copy(local = null)
        val end = if (run.cancelled && (outcome == LocalOutcome.Quiet || outcome is LocalOutcome.Switch)) EndReason.CANCELLED else EndReason.FINISHED
        val recorded = MachineStep(
            done,
            listOfNotNull(
                run.record?.let { ConversationEffect.EndRecord(it.conversation, it.record, end, outcome.sentence) },
                ConversationEffect.EndLocal(run.command, end, outcome.sentence, run.input),
            ),
        )
        val replied = when (outcome) {
            is LocalOutcome.Reply -> recorded.then { reply(it, run.command, outcome.sentence) }
            is LocalOutcome.Deleted -> recorded.then { close(it, keep = false) }.then { reply(it, run.command, outcome.sentence) }
            else -> recorded
        }
        return replied.then(::closeIfAsked).then { runWaiting(it, now) }
    }

    // D23: the continued conversation is the open one now, with the current test mode; its reply is the request's final one.
    // A "stop" that came while the store switched cancels the switch: the conversation that was open stays as it was, and
    // the store puts back what the switch changed (review of plan 7B: M3).
    private fun switched(state: ConversationState, event: ConversationEvent.Switched, now: Long): MachineStep {
        val run = state.local ?: return MachineStep(state)
        if (run.cancelled) {
            val undo = listOfNotNull(ConversationEffect.UndoSwitch.takeIf { event.snapshot != null })
            val stopped = MachineStep(state.copy(local = null), undo + ConversationEffect.EndLocal(run.command, EndReason.CANCELLED, null, run.input))
            return stopped.then(::closeIfAsked).then { runWaiting(it, now) }
        }
        val end = if (event.failed) EndReason.KESTREL_ERROR else EndReason.FINISHED
        val done = MachineStep(state.copy(local = null), listOf(ConversationEffect.EndLocal(run.command, end, event.sentence, run.input)))
        val snapshot = event.snapshot ?: return done.then { reply(it, run.command, event.sentence) }.then(::closeIfAsked).then { runWaiting(it, now) }
        return done.then { MachineStep(it, listOf(ConversationEffect.OpenSwitched(snapshot))) }.then { current ->
            // A switch runs only without a goal (design 6.4), so the conversation that it leaves has none to end.
            val opened = current.copy(
                id = snapshot.id,
                openedAtWall = snapshot.openedAtWall,
                testMode = current.safetyTestMode,
                continuedAtWall = snapshot.continuedAtWall ?: now,
                commands = snapshot.records.size,
                asksContinue = false,
                quiet = QuietClock.Off,
                closeAfterWork = false,
            )
            reply(opened, run.command, event.sentence)
        }.then { runWaiting(it, now) }
    }

    // Design 12.4: a change of test mode closes the open conversation, after its goal ended with its report, or after its
    // local request ended by itself (a request does not act under the test mode, so it is not stopped). The close waits
    // once: another change of a setting meanwhile changes nothing of it, and a test mode that comes back to the
    // conversation's before the work ended closes nothing (a goal that the change stopped still ends). A changed quiet time
    // counts at once, also for the running clock.
    private fun settingsChanged(state: ConversationState, event: ConversationEvent.SettingsChanged, now: Long): MachineStep {
        val changed = state.copy(settings = event.settings, safetyTestMode = event.testMode)
        if (changed.id != null && event.testMode != changed.testMode) {
            return when {
                changed.closeAfterWork -> MachineStep(changed)
                changed.goal != null -> GoalRules.stop(changed.copy(closeAfterWork = true))
                changed.local != null -> MachineStep(changed.copy(closeAfterWork = true))
                else -> close(changed)
            }
        }
        val updated = changed.copy(closeAfterWork = false)
        val running = updated.quiet as? QuietClock.Running
        if (running == null || event.settings.quietMillis == state.settings.quietMillis) return MachineStep(updated)
        val left = event.settings.quietMillis - (now - running.sinceWall)
        return if (left <= 0) close(updated) else QuietRules.start(MachineStep(updated), running.sinceWall, left)
    }

    // Design 6.7: without a goal the conversation is open while its quiet time has not passed, and its clock runs on for
    // the rest; with a goal in any phase the goal is held as interrupted, unless the owner stopped Kestrel after the
    // snapshot was saved (plan 7E), which ends the goal as "stop" does. A goal that worked until the process ended is held
    // from the snapshot's `lastWall`, the last time that Kestrel saved it while it worked (the engine saves at every change
    // of the conversation, its records' traces too); one that was held already, from when its hold began.
    // Design 12.4: the engine gives the Safety page's test mode (`SettingsChanged`, kept as `safetyTestMode`) before it
    // restores; when it differs from the conversation's, the conversation closes, after its goal has ended as "stop" ends it.
    private fun restored(state: ConversationState, snapshot: ConversationSnapshot?, previous: ProcessEnd, now: Long): MachineStep {
        if (snapshot == null || !snapshot.open) return MachineStep(state)
        val otherMode = snapshot.testMode != state.safetyTestMode
        val base = state.copy(
            id = snapshot.id,
            openedAtWall = snapshot.openedAtWall,
            testMode = snapshot.testMode,
            continuedAtWall = snapshot.continuedAtWall,
            commands = snapshot.records.size,
            replyChannel = snapshot.replyChannel,
        )
        val goal = snapshot.goal ?: run {
            val since = snapshot.quietSinceWall ?: snapshot.lastWall
            val left = state.settings.quietMillis - maxOf(0L, now - since)
            return if (left <= 0 || otherMode) close(base) else QuietRules.start(MachineStep(base), since, left)
        }
        val ownerStopped = previous is ProcessEnd.OwnerStopped && previous.atWall >= snapshot.lastWall
        val heldSince = if (goal.phase in GoalState.HELD) goal.changedAtWall else snapshot.lastWall
        val end = EndReason.CANCELLED.takeIf { ownerStopped || otherMode }
        return GoalRules.restored(base.copy(closeAfterWork = otherMode), goal, heldSince, now, end)
    }

    private fun quietEnded(state: ConversationState, number: Int): MachineStep =
        if ((state.quiet as? QuietClock.Running)?.number == number) close(state) else MachineStep(state)

    // Design 12.4: the close that a change of test mode left for the end of the work.
    private fun closeIfAsked(state: ConversationState): MachineStep =
        if (state.closeAfterWork) close(state.copy(closeAfterWork = false)) else MachineStep(state)

    // The commands that waited run in their turn, oldest first; each meets the state that the one before it left, so one
    // that finds a goal working steers it and one that finds a request running waits again.
    private fun runWaiting(state: ConversationState, now: Long): MachineStep =
        state.waiting.fold(MachineStep(state.copy(waiting = emptyList()))) { step, next ->
            step.then { input(it.copy(replyChannel = next.channel), next, now) }
        }

    // Kestrel's own requests run without a goal. "Stop. Kestrel, …": one that comes while "stop" ends the goal waits for
    // the goal's end, as a command does (design 6.4); while the goal stays open, Kestrel says to stop it first.
    private inline fun withoutGoal(state: ConversationState, input: OwnerInput, run: (ConversationState) -> MachineStep): MachineStep {
        val goal = state.goal ?: return run(state)
        return if (goal.ending == GoalEnding.STOPPING) MachineStep(state.copy(waiting = state.waiting + input)) else refuse(state, ConversationTexts.STOP_GOAL_FIRST)
    }

    private fun open(state: ConversationState, now: Long): ConversationState =
        if (state.id != null) state else state.copy(id = ids(), openedAtWall = now, testMode = state.safetyTestMode, continuedAtWall = null, commands = 0)

    // The one way in which a goal leaves the state: its record gets its end and final words first, in the same step, so that
    // a step that also closes the conversation stores the record ended (review of Task 10: I1).
    private fun endGoal(state: ConversationState, goal: GoalState, end: EndReason, answer: String?): MachineStep = MachineStep(
        state.copy(goal = null, question = null, asksContinue = false, heldWords = null),
        listOf(ConversationEffect.EndRecord(state.openId, goal.record, end, answer)),
    )

    // A conversation closes only without a goal or a local request: the end of its work was recorded first (endGoal,
    // localEnded), and the closed conversation is stored with it.
    private fun close(state: ConversationState, keep: Boolean = true): MachineStep {
        val id = state.id ?: return MachineStep(state)
        check(state.goal == null && state.local == null) { "A conversation closes only after the end of its work was recorded" }
        val closed = state.copy(
            id = null,
            openedAtWall = 0,
            testMode = state.safetyTestMode,
            continuedAtWall = null,
            commands = 0,
            asksContinue = false,
            heldWords = null,
            quiet = QuietClock.Off,
            closeAfterWork = false,
        )
        return MachineStep(closed, listOf(ConversationEffect.Close(id, keep)))
    }

    // Design 6.4: a goal's final reply waits until the owner is there (the phone known to be unlocked, with no call), also
    // when the conversation closes after the goal; meanwhile it holds the quiet clock.
    private fun finalReply(state: ConversationState, command: Int, text: String): MachineStep = when {
        state.phone.present -> reply(state, command, text)
        else -> MachineStep(state.copy(heldReports = state.heldReports + ChannelOutput.Report(command, text), replying = command))
    }

    private fun reply(state: ConversationState, command: Int, text: String): MachineStep =
        MachineStep(state.copy(replying = command), listOf(ConversationEffect.Output(ChannelOutput.Report(command, text))))

    private fun refuse(state: ConversationState, text: String): MachineStep =
        MachineStep(state, listOf(ConversationEffect.Output(ChannelOutput.Said(text))))

    // The snapshot is saved whenever a part that it stores changed; the records' own changes are saved by the engine.
    private fun persisted(step: MachineStep, before: ConversationState): MachineStep {
        val after = step.state
        if (after.id == null || stored(after) == stored(before) || ConversationEffect.Persist in step.effects) return step
        return MachineStep(after, step.effects + ConversationEffect.Persist)
    }

    private fun stored(state: ConversationState): List<Any?> = listOf(
        state.id,
        state.openedAtWall,
        state.testMode,
        state.continuedAtWall,
        state.commands,
        state.goal?.copy(command = 0, ending = null, handOver = null, stop = null),
        (state.quiet as? QuietClock.Running)?.sinceWall,
        state.replyChannel,
    )
}
