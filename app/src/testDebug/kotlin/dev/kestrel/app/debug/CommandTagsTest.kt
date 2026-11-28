package dev.kestrel.app.debug

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalButtons
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.NoticeGoal
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Rules
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Step
import dev.kestrel.app.orchestrator.VoiceView
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequest
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.LocalRun
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.RoundEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The tags of plan 4D's checks (hook H1), on the Orchestrator's real rules. */
class CommandTagsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tags = CommandTags()
    private var state = State()

    // Applies the rules to [event] as the Orchestrator does, and gives the step to the tags.
    private fun step(event: Event): Step {
        val before = state
        val step = Rules.next(before, event)
        state = step.state
        tags.onStep(event, before, step)
        return step
    }

    // The inputs that Listening makes of [step]'s Converse effects for the conversation, as Listening makes them, from a
    // session that heard them through the hooks' clip microphone when [clip].
    private fun inputsOf(step: Step, clip: Boolean = false): List<OwnerInput> = step.effects.filterIsInstance<Effect.Converse>().map { converse ->
        tags.input(converse.heard, OwnerInput(InputIds.next(), InputChannel.VOICE, converse.intent, 0), clip)
    }

    private fun send(text: String, tag: String? = null) {
        val event = Event.Heard(text, named = false)
        if (tag != null) assertTrue(tags.register(tag, event, VoiceHooks.readOf(text, named = false)))
        step(event)
    }

    @Test
    fun aTaggedTranscriptGetsItsOwnCommandItsAnswerAndWhatTheCommandSaidShowedAndTook() {
        step(Event.Toggle)
        val pending = Event.Heard("Tap Delete.", named = false)
        assertTrue(tags.register("ask", pending, "other"))
        assertEquals(CommandTags.Tagged(CommandTags.Role.PENDING, 0, "other"), tags.tagged("ask"))

        step(pending)
        // A stray transcript while the command runs takes nothing from the tag.
        send("Scroll down.")
        step(Event.CommandAsks(1, "Which one?"))
        send("1", tag = "answer")
        step(Event.CommandEnded(1, CommandResult(Outcome.DONE, say = "Done.", detail = "tile")))

        assertEquals(CommandTags.Tagged(CommandTags.Role.COMMAND, 1, "other"), tags.tagged("ask"))
        assertEquals(CommandTags.Role.ANSWER, tags.tagged("answer")?.role)
        assertEquals(1, tags.tagged("answer")?.command)
        val record = tags.record(1)
        assertEquals(listOf(CommandTags.hash("Which one?"), CommandTags.hash("Done.")), record.said)
        assertEquals(listOf(CommandTags.hash("Which one?")), record.shown)
        assertEquals("tile", record.detail)
        assertEquals(CommandTags.Record(), tags.record(2))
    }

    @Test
    fun aStopCancelsItsOwnCommandAndAnAnswerThatIsNoAnswerBecomesTheCommandThatItStarts() {
        step(Event.Toggle)
        send("Scroll down.", tag = "scroll")
        send("Stop.", tag = "stop")

        assertEquals(CommandTags.Tagged(CommandTags.Role.CANCEL, 1, "stop"), tags.tagged("stop"))
        assertEquals(listOf(CommandTags.hash(ListeningTexts.CANCELLED)), tags.record(1).shown)

        send("Tap Send.", tag = "send")
        step(Event.CommandAsks(2, "Which one?"))
        send("Scroll up.", tag = "other")
        assertEquals(CommandTags.Role.ANSWER, tags.tagged("other")?.role)
        step(Event.CommandEnded(2, CommandResult(Outcome.CANCELLED, passOn = "Scroll up.")))

        assertEquals(CommandTags.Tagged(CommandTags.Role.COMMAND, 3, "other"), tags.tagged("other"))

        // A later untagged answer that is passed on leaves the earlier tagged answer as it was.
        step(Event.CommandEnded(3, CommandResult(Outcome.DONE)))
        send("Tap Send.", tag = "pick")
        step(Event.CommandAsks(4, "Which one?"))
        send("1", tag = "one")
        step(Event.CommandAsks(4, "Which one?"))
        send("Scroll down.")
        step(Event.CommandEnded(4, CommandResult(Outcome.CANCELLED, passOn = "Scroll down.")))
        assertEquals(CommandTags.Role.ANSWER, tags.tagged("one")?.role)
        assertEquals(4, tags.tagged("one")?.command)
    }

    @Test
    fun aTagIsUsedOnceATranscriptWhileKestrelIsOffStartsNothingAndTheHashIsSha256() {
        send("Scroll down.", tag = "off")

        assertEquals(CommandTags.Tagged(CommandTags.Role.NONE, 0, "other"), tags.tagged("off"))
        assertFalse(tags.register("off", Event.Heard("Scroll up.", named = false), "other"))
        assertNull(tags.tagged("never"))
        // A transcript that a hook drops before the Orchestrator sees it did nothing.
        val dropped = Event.Heard("Scroll up.", named = false)
        assertTrue(tags.register("dropped", dropped, "other"))
        tags.dropped(dropped)
        assertEquals(CommandTags.Tagged(CommandTags.Role.NONE, 0, "other"), tags.tagged("dropped"))
        // The first 16 hexadecimal characters of SHA-256("abc"), as the plan's interfaces' Get-TextHash (2026-11-20-a71-checks-4d-interfaces.md) gives them.
        assertEquals("ba7816bf8f01cfea", CommandTags.hash("abc"))
    }

    @Test
    fun aTaggedTranscriptsInputCarriesItsOptionsAndTheTagGetsTheCommandThatItStarted() {
        step(Event.Toggle)
        val task = Event.Heard("Kestrel, open the maps.", named = true)
        assertTrue(tags.register("task", task, VoiceHooks.readOf(task.text, named = true), mapOf("model" to "test-model")))
        step(task)
        assertEquals(CommandTags.Tagged(CommandTags.Role.CONVERSE, 0, "task"), tags.tagged("task"))

        // Listening makes the input of the transcript; the tag's options go with it.
        val input = tags.input(task, OwnerInput(7, InputChannel.VOICE, InputIntent.Command("open the maps."), 0))
        assertEquals(mapOf("model" to "test-model"), input.options)
        // The conversation starts the goal as command 3, here on the input's own step.
        val started = ConversationEffect.StartGoal(3, 1, input, "open the maps.")
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState(), listOf(started)))
        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "task", task = 3), tags.tagged("task"))
        assertEquals(3, tags.lastGoalWords)

        // An untagged transcript's input carries nothing.
        val other = Event.Heard("Kestrel, open the settings.", named = true)
        step(other)
        val plain = OwnerInput(8, InputChannel.VOICE, InputIntent.Command("open the settings."), 0)
        assertEquals(plain, tags.input(other, plain))
    }

    @Test
    fun aTaggedInputThatStartsNothingAndWaitsForNothingDidNothing() {
        step(Event.Toggle)
        val stop = Event.Heard("Kestrel, continue", named = true)
        assertTrue(tags.register("go-on", stop, VoiceHooks.readOf(stop.text, named = true)))
        step(stop)
        val input = tags.input(stop, OwnerInput(9, InputChannel.VOICE, InputIntent.Continue, 0))

        // The conversation took it, as steering or an answer, without starting work for it.
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState()))

        assertEquals(CommandTags.Role.NONE, tags.tagged("go-on")?.role)
    }

    // Plan 7G's resume-after-death task: after a restart, "Kestrel, continue" answers the open "Continue the goal?" with
    // yes. The machine holds that answer (continueAnswer) and resumes the goal's round for it two steps later, so its tag
    // waits for the round's command.
    private fun continueAnswered(tag: String): Pair<OwnerInput, ConversationState> {
        step(Event.Toggle)
        step(Event.ConversationChanged(VoiceView(open = true, goal = GoalPhase.INTERRUPTED, asking = QuestionKind.CONTINUE_GOAL, question = 1)))
        val heard = Event.Heard("Kestrel, continue.", named = true)
        assertTrue(tags.register(tag, heard, VoiceHooks.readOf(heard.text, named = true)))
        val input = inputsOf(step(heard)).single()
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 1), input.intent)

        val asking = ConversationState(asksContinue = true)
        val held = asking.copy(continueAnswer = input)
        tags.onConversationStep(ConversationEvent.Input(input), asking, MachineStep(held, listOf(ConversationEffect.AnswerQuestion(1, ChannelAnswer.Yes))))
        assertEquals(CommandTags.Role.CONVERSE, tags.tagged(tag)?.role)
        tags.onConversationStep(ConversationEvent.Dialogue(DialogueEvent.Answered(1, ChannelAnswer.Yes)), held, MachineStep(held))
        assertEquals(CommandTags.Role.CONVERSE, tags.tagged(tag)?.role)
        return input to held
    }

    @Test
    fun theAnswerThatResumesAHeldGoalGetsTheResumedRoundsCommand() {
        val (input, held) = continueAnswered("continue")

        val resumed = ConversationEffect.ResumeGoal(1, ResumeCause.RESTART, null, input)
        tags.onConversationStep(ConversationEvent.ContinueAnswered(ChannelAnswer.Yes, tap = null), held, MachineStep(ConversationState(command = 1), listOf(resumed)))

        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "continue", task = 1), tags.tagged("continue"))
    }

    @Test
    fun aHeldInputsTagCountsNothingOnceTheMachineDropsItsWork() {
        // The goal had ended meanwhile, so the answer resumes nothing.
        val (_, held) = continueAnswered("late")
        tags.onConversationStep(ConversationEvent.ContinueAnswered(ChannelAnswer.Yes, tap = null), held, MachineStep(ConversationState()))
        assertEquals(CommandTags.Role.NONE, tags.tagged("late")?.role)

        // A command that waited for the goal's end, which "stop" then dropped with the goal.
        val waited = checkNotNull(tags.typed("waited", OwnerInput(13, InputChannel.TEXT, InputIntent.Command("and the times"), 0), "command"))
        val waiting = ConversationState(waiting = listOf(waited))
        tags.onConversationStep(ConversationEvent.Input(waited), ConversationState(), MachineStep(waiting))
        assertEquals(CommandTags.Role.CONVERSE, tags.tagged("waited")?.role)
        tags.onConversationStep(ConversationEvent.Input(OwnerInput(14, InputChannel.TEXT, InputIntent.Stop, 0)), waiting, MachineStep(ConversationState()))
        assertEquals(CommandTags.Role.NONE, tags.tagged("waited")?.role)
    }

    @Test
    fun anAnswerToAnotherQuestionStillStartsNothing() {
        step(Event.Toggle)
        step(Event.ConversationChanged(VoiceView(open = true, goal = GoalPhase.ASKING, asking = QuestionKind.CONFIRM, question = 2)))
        val heard = Event.Heard("Yes.", named = false)
        assertTrue(tags.register("yes", heard, VoiceHooks.readOf(heard.text, named = false)))
        val input = inputsOf(step(heard)).single()
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 2), input.intent)

        val asking = ConversationState(command = 1)
        tags.onConversationStep(ConversationEvent.Input(input), asking, MachineStep(asking, listOf(ConversationEffect.AnswerQuestion(2, ChannelAnswer.Yes))))

        assertEquals(CommandTags.Role.NONE, tags.tagged("yes")?.role)
    }

    // Design 14.4 of sub-project 7: conversation-type tags a typed input, which carries the tag's options.
    @Test
    fun aTypedInputsTagGetsTheCommandThatItStartedAndATagIsUsedOnce() {
        val typed = OwnerInput(11, InputChannel.TEXT, InputIntent.Command("open the maps"), 0)
        val input = checkNotNull(tags.typed("typed", typed, "command", mapOf("model" to "test-model")))
        assertEquals(mapOf("model" to "test-model"), input.options)
        assertEquals(CommandTags.Tagged(CommandTags.Role.CONVERSE, 0, "command"), tags.tagged("typed"))

        val started = ConversationEffect.StartGoal(4, 1, input, "open the maps")
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState(), listOf(started)))

        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "command", task = 4), tags.tagged("typed"))
        assertNull(tags.typed("typed", typed.copy(id = 12), "command"))
    }

    // Plan 7G's review N1: only the conversation that a tagged input's work opened is the hooks' own: never one that a switch
    // moved into, which may be the owner's past conversation, nor one that a tagged follow-up continues there, nor one
    // that an input without a tag opened.
    @Test
    fun onlyTheConversationThatATaggedInputsWorkOpenedIsMarked() {
        val own = mutableListOf<ConversationId>()
        tags.ownConversation = { own += it }
        val conversation = ConversationId("0123456789abcdef0123456789abcdef")
        val goal = checkNotNull(tags.typed("goal", OwnerInput(21, InputChannel.TEXT, InputIntent.Command("open the maps"), 0), "command"))
        val opened = ConversationState(id = conversation)
        tags.onConversationStep(ConversationEvent.Input(goal), ConversationState(), MachineStep(opened, listOf(ConversationEffect.StartGoal(5, 1, goal, "open the maps"))))

        val past = ConversationId("fedcba9876543210fedcba9876543210")
        val switch = checkNotNull(tags.typed("switch", OwnerInput(22, InputChannel.TEXT, InputIntent.Command("continue the conversation about the bus"), 0), "command"))
        val moved = ConversationState(id = past)
        tags.onConversationStep(ConversationEvent.Input(switch), opened, MachineStep(moved, listOf(ConversationEffect.SwitchTo(6, past, switch))))
        val followUp = checkNotNull(tags.typed("follow-up", OwnerInput(23, InputChannel.TEXT, InputIntent.Command("and the times"), 0), "command"))
        tags.onConversationStep(ConversationEvent.Input(followUp), moved, MachineStep(moved, listOf(ConversationEffect.StartGoal(7, 2, followUp, "and the times"))))
        val plain = OwnerInput(24, InputChannel.TEXT, InputIntent.Command("open the settings"), 0)
        val other = ConversationState(id = ConversationId("ab".repeat(16)))
        tags.onConversationStep(ConversationEvent.Input(plain), ConversationState(), MachineStep(other, listOf(ConversationEffect.StartGoal(8, 1, plain, "open the settings"))))

        assertEquals(listOf(conversation), own)
    }

    // Review low risk of plan 7F and review M1 of Task 13a: a mark means that only the hooks' acts are in the conversation.
    // The owner's own command or answer in it makes it his again, so that delete-typed leaves it; a hook's command, answer or
    // stop does not.
    @Test
    fun theOwnersOwnCommandOrAnswerInAMarkedConversationTakesItsMarkAway() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        val hooks = OwnerInput(31, InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(hooks)
        tags.onConversationStep(ConversationEvent.Input(hooks), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(9, 2, hooks, "and the times"))))
        val hooksAnswer = OwnerInput(32, InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes), 0)
        tags.untagged(hooksAnswer)
        tags.onConversationStep(ConversationEvent.Input(hooksAnswer), marked, MachineStep(marked, emptyList()))
        val stop = OwnerInput(34, InputChannel.TEXT, InputIntent.Stop, 0)
        tags.untagged(stop)
        tags.onConversationStep(ConversationEvent.Input(stop), marked, MachineStep(marked, emptyList()))
        assertEquals(emptyList<ConversationId>(), gone)

        val owners = OwnerInput(33, InputChannel.VOICE, InputIntent.Command("and the prices"), 0)
        tags.onConversationStep(ConversationEvent.Input(owners), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(10, 3, owners, "and the prices"))))
        val answer = OwnerInput(35, InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Text("the red one")), 0)
        tags.onConversationStep(ConversationEvent.Input(answer), marked, MachineStep(marked, emptyList()))
        assertEquals(listOf(marked.id, marked.id), gone)
    }

    // Review M1 of Task 13a: the owner's switch into a marked conversation, by "continue the conversation about ..." (a
    // local request whose end switches) or by the list's Continue, makes the conversation that it moves into his; a hook's
    // switch does not.
    @Test
    fun theOwnersSwitchIntoAMarkedConversationTakesItsMarkAway() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val his = ConversationState(id = ConversationId("ab".repeat(16)))
        val marked = ConversationId("0123456789abcdef0123456789abcdef")
        val about = OwnerInput(51, InputChannel.VOICE, InputIntent.ContinuePast("the bus"), 0)
        tags.onConversationStep(ConversationEvent.Input(about), his, MachineStep(his, listOf(ConversationEffect.RunLocal(14, null, LocalRequest.ContinuePast("the bus"), about))))
        gone.clear()
        tags.onConversationStep(ConversationEvent.LocalEnded(14, LocalOutcome.Switch(marked)), his, MachineStep(his, listOf(ConversationEffect.SwitchTo(14, marked, about))))
        assertEquals(listOf(marked), gone)

        gone.clear()
        val listed = OwnerInput(52, InputChannel.TEXT, InputIntent.ContinueConversation(marked), 0)
        tags.onConversationStep(ConversationEvent.Input(listed), his, MachineStep(his, listOf(ConversationEffect.SwitchTo(15, marked, listed))))
        assertEquals(listOf(marked), gone)

        gone.clear()
        val hooks = OwnerInput(53, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0)
        tags.untagged(hooks)
        // The request runs until its switch, as the machine keeps it (LocalRun), so its claim lives until then.
        val asking = his.copy(local = LocalRun(16, null, hooks))
        tags.onConversationStep(ConversationEvent.Input(hooks), his, MachineStep(asking, listOf(ConversationEffect.RunLocal(16, null, LocalRequest.ContinuePast("the bus"), hooks))))
        tags.onConversationStep(ConversationEvent.LocalEnded(16, LocalOutcome.Switch(marked)), asking, MachineStep(asking, listOf(ConversationEffect.SwitchTo(16, marked, hooks))))
        assertEquals(emptyList<ConversationId>(), gone)
    }

    // Plan 7G: Kestrel's own request that runs while no conversation is open carries the hooks' mark itself, while only the
    // hooks' inputs reached it. A hook's switch from the quiet is theirs until the owner's answer reaches it, and stays his
    // after that; the owner's own request is never theirs, nor one in an open conversation, whose mark is the conversation's,
    // nor a cancelled one.
    @Test
    fun aRequestThatRunsWhileNoConversationIsOpenIsTheHooksOnlyWhileOnlyTheirInputsReachedIt() {
        val owned = mutableListOf<Boolean>()
        tags.ownRequest = { owned += it }
        val quiet = ConversationState()
        val hooks = OwnerInput(71, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0)
        tags.untagged(hooks)
        val asking = ConversationState(local = LocalRun(19, null, hooks))
        tags.onConversationStep(ConversationEvent.Input(hooks), quiet, MachineStep(asking, listOf(ConversationEffect.RunLocal(19, null, LocalRequest.ContinuePast("the bus"), hooks))))
        val hooksAnswer = OwnerInput(72, InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Text("the red one")), 0)
        tags.untagged(hooksAnswer)
        tags.onConversationStep(ConversationEvent.Input(hooksAnswer), asking, MachineStep(asking, emptyList()))
        val owners = OwnerInput(73, InputChannel.VOICE, InputIntent.Answer(ChannelAnswer.Text("the red one")), 0)
        tags.onConversationStep(ConversationEvent.Input(owners), asking, MachineStep(asking, emptyList()))
        tags.onConversationStep(ConversationEvent.Input(hooksAnswer), asking, MachineStep(asking, emptyList()))
        tags.onConversationStep(ConversationEvent.LocalEnded(19, LocalOutcome.Quiet), asking, MachineStep(quiet, emptyList()))
        assertEquals(listOf(true, true, false, false, false), owned)

        owned.clear()
        val his = OwnerInput(74, InputChannel.VOICE, InputIntent.ContinuePast("the bus"), 0)
        val hisRequest = ConversationState(local = LocalRun(20, null, his))
        tags.onConversationStep(ConversationEvent.Input(his), quiet, MachineStep(hisRequest, listOf(ConversationEffect.RunLocal(20, null, LocalRequest.ContinuePast("the bus"), his))))
        val inOpen = OwnerInput(75, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0)
        tags.untagged(inOpen)
        val open = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"), local = LocalRun(21, null, inOpen))
        tags.onConversationStep(ConversationEvent.Input(inOpen), open, MachineStep(open, listOf(ConversationEffect.RunLocal(21, null, LocalRequest.ContinuePast("the bus"), inOpen))))
        val cancelled = OwnerInput(76, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0)
        tags.untagged(cancelled)
        tags.onConversationStep(ConversationEvent.Input(cancelled), quiet, MachineStep(ConversationState(local = LocalRun(22, null, cancelled, cancelled = true)), emptyList()))
        assertEquals(listOf(false, false, false), owned)
    }

    // Plan 7G's re-check of round 8 (Important 1): the owner's "yes" to the question of a hook's switch makes the request his,
    // and so the conversation that the switch moves into, from the quiet as in an open conversation, whose mark goes too. A
    // hook's "yes" leaves the target's mark.
    @Test
    fun theOwnersAnswerToAHooksSwitchTakesTheMarkFromTheConversationThatTheSwitchMovesInto() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val target = ConversationId("fedcba9876543210fedcba9876543210")
        val yes = OwnerInput(82, InputChannel.VOICE, InputIntent.Answer(ChannelAnswer.Yes), 0)

        val quiet = ConversationState()
        switchAfterAnswer(quiet, OwnerInput(81, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0), ConversationEvent.Input(yes), command = 23, target = target)
        assertEquals(listOf(target), gone)

        gone.clear()
        val open = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        switchAfterAnswer(open, OwnerInput(83, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0), ConversationEvent.Input(yes.copy(id = 84)), command = 24, target = target)
        assertEquals(listOf(open.id, target), gone)

        gone.clear()
        val hooksYes = OwnerInput(86, InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes), 0)
        tags.untagged(hooksYes)
        switchAfterAnswer(quiet, OwnerInput(85, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0), ConversationEvent.Input(hooksYes), command = 25, target = target)
        assertEquals(emptyList<ConversationId>(), gone)
    }

    // Plan 7G's re-check of round 9 (Important 1): the owner's tap on the card over the app reaches the conversation only as
    // the dialogue's answer with the tap's number, never as an input, and it is his choice as his spoken or typed "yes" is:
    // it takes the mark from the conversation that the hook's switch moves into, and from the open one. A press that the
    // hooks noted as theirs, and a tap that gave no answer, take nothing.
    @Test
    fun theOwnersTapOnTheCardOverTheAppTakesTheMarkAsHisSpokenAnswerDoes() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val target = ConversationId("fedcba9876543210fedcba9876543210")
        val quiet = ConversationState()

        switchAfterAnswer(quiet, OwnerInput(101, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0), tap(ChannelAnswer.Yes, 102), command = 31, target = target)
        assertEquals(listOf(target), gone)

        gone.clear()
        val open = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        switchAfterAnswer(open, OwnerInput(103, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0), tap(ChannelAnswer.Yes, 104), command = 32, target = target)
        assertEquals(listOf(open.id, target), gone)

        gone.clear()
        tags.claim(106L)
        switchAfterAnswer(quiet, OwnerInput(105, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0), tap(ChannelAnswer.Yes, 106), command = 33, target = target)
        switchAfterAnswer(quiet, OwnerInput(107, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0), tap(ChannelAnswer.Silence, 108), command = 34, target = target)
        assertEquals(emptyList<ConversationId>(), gone)
    }

    // Plan 7G's re-check of round 9 (Important 1): the owner's tap on the card of "Continue the goal?" in a marked
    // conversation takes its mark, as his spoken "yes" does; the hooks' press does not.
    @Test
    fun theOwnersTapOnTheCardOfContinueTheGoalTakesTheMark() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))

        tags.claim(111L)
        tags.onConversationStep(ConversationEvent.ContinueAnswered(ChannelAnswer.Yes, tap = 111), marked, MachineStep(marked, emptyList()))
        tags.onConversationStep(ConversationEvent.ContinueAnswered(ChannelAnswer.Yes, tap = null), marked, MachineStep(marked, emptyList()))
        assertEquals(emptyList<ConversationId>(), gone)

        tags.onConversationStep(ConversationEvent.ContinueAnswered(ChannelAnswer.Yes, tap = 112), marked, MachineStep(marked, emptyList()))
        assertEquals(listOf(marked.id), gone)
    }

    // The answer of question 1 that a tap on the card over the app gave, with the tap's number [act].
    private fun tap(answer: ChannelAnswer, act: Long) = ConversationEvent.Dialogue(DialogueEvent.Answered(1, answer, tap = act))

    // A hook's switch into [target] from [before], as the conversation's machine steps it: the request asks, [answer] gives
    // the answer (an input, or the dialogue's answer from a card's tap), and the request's end switches with the request's
    // own input.
    private fun switchAfterAnswer(before: ConversationState, hooks: OwnerInput, answer: ConversationEvent, command: Int, target: ConversationId) {
        tags.untagged(hooks)
        val asking = before.copy(command = command, local = LocalRun(command, null, hooks))
        tags.onConversationStep(ConversationEvent.Input(hooks), before, MachineStep(asking, listOf(ConversationEffect.RunLocal(command, null, LocalRequest.ContinuePast("the bus"), hooks))))
        val answering = if (answer is ConversationEvent.Input) listOf(ConversationEffect.AnswerQuestion(1, ChannelAnswer.Yes, leftScreen = false)) else emptyList()
        tags.onConversationStep(answer, asking, MachineStep(asking, answering))
        tags.onConversationStep(ConversationEvent.LocalEnded(command, LocalOutcome.Switch(target)), asking, MachineStep(asking, listOf(ConversationEffect.SwitchTo(command, target, hooks))))
    }

    // Review N1 of plan 7G: the conversation that a switch moves into may be the owner's, also when a hook's command that
    // waited for the switch starts its work there in the same step.
    @Test
    fun aHooksCommandThatWaitedForASwitchDoesNotMarkTheSwitchsTarget() {
        val own = mutableListOf<ConversationId>()
        tags.ownConversation = { own += it }
        val target = ConversationId("fedcba9876543210fedcba9876543210")
        val switch = OwnerInput(91, InputChannel.TEXT, InputIntent.ContinueConversation(target), 0)
        val waited = OwnerInput(92, InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(switch)
        tags.untagged(waited)
        val switching = ConversationState(command = 26, local = LocalRun(26, null, switch), waiting = listOf(waited))
        val snapshot = ConversationSnapshot(id = target, openedAtWall = 0, lastWall = 0, testMode = true)
        val moved = ConversationState(id = target, command = 27)
        val effects = listOf(
            ConversationEffect.EndLocal(26, EndReason.FINISHED, "Continuing.", switch),
            ConversationEffect.OpenSwitched(snapshot),
            ConversationEffect.StartGoal(27, 1, waited, "and the times"),
        )
        tags.onConversationStep(ConversationEvent.Switched(snapshot, "Continuing."), switching, MachineStep(moved, effects))
        assertEquals(emptyList<ConversationId>(), own)

        // Plan 7G's re-check of round 9 (Minor 2): a conversation that the hooks' own "new conversation" and command, which
        // both waited for the switch, open in the same step is the hooks', and only the switch's target stays unmarked.
        val again = OwnerInput(93, InputChannel.TEXT, InputIntent.ContinueConversation(target), 0)
        val fresh = OwnerInput(94, InputChannel.TEXT, InputIntent.NewConversation, 0)
        val command = OwnerInput(95, InputChannel.TEXT, InputIntent.Command("open the maps"), 0)
        listOf(again, fresh, command).forEach(tags::untagged)
        val waitingTwo = ConversationState(command = 28, local = LocalRun(28, null, again), waiting = listOf(fresh, command))
        val opened = ConversationState(id = ConversationId("ab".repeat(16)), command = 29)
        val newEffects = listOf(
            ConversationEffect.EndLocal(28, EndReason.FINISHED, "Continuing.", again),
            ConversationEffect.OpenSwitched(snapshot),
            ConversationEffect.Close(target),
            ConversationEffect.StartGoal(29, 1, command, "open the maps"),
        )
        tags.onConversationStep(ConversationEvent.Switched(snapshot, "Continuing."), waitingTwo, MachineStep(opened, newEffects))
        assertEquals(listOf(opened.id), own)
    }

    // Review M2 of Task 13a: a transcript that a hook sends without a tag, as agent-answer's spoken answer or voice heard's,
    // gives an input that is the hooks' own, never the owner's.
    @Test
    fun anUntaggedTranscriptOfAHookGivesTheHooksInput() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        step(Event.Toggle)
        val sent = Event.Heard("Kestrel, and the times.", named = true)
        tags.untagged(sent)
        step(sent)
        val hooks = tags.input(sent, OwnerInput(61, InputChannel.VOICE, InputIntent.Command("and the times."), 0))
        tags.onConversationStep(ConversationEvent.Input(hooks), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(17, 2, hooks, "and the times."))))
        assertEquals(emptyList<ConversationId>(), gone)

        // The owner's own transcript with the same words is his.
        val his = Event.Heard("Kestrel, and the times.", named = true)
        step(his)
        val owners = tags.input(his, OwnerInput(62, InputChannel.VOICE, InputIntent.Command("and the times."), 0))
        tags.onConversationStep(ConversationEvent.Input(owners), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(18, 3, owners, "and the times."))))
        assertEquals(listOf(marked.id), gone)
    }

    // Review B-I2 of plan 7F: one rule marks the hooks' conversations, the conversation's steps. An untagged command of
    // conversation-type whose work opens a conversation marks it; a hook's command that joins a conversation that the
    // owner's command opened marks nothing.
    @Test
    fun anUntaggedHookCommandThatOpensAConversationMarksItAndOneThatJoinsTheOwnersMarksNothing() {
        val own = mutableListOf<ConversationId>()
        tags.ownConversation = { own += it }
        val opened = ConversationId("0123456789abcdef0123456789abcdef")
        val hooks = OwnerInput(41, InputChannel.TEXT, InputIntent.Command("open the maps"), 0)
        tags.untagged(hooks)
        tags.onConversationStep(ConversationEvent.Input(hooks), ConversationState(), MachineStep(ConversationState(id = opened), listOf(ConversationEffect.StartGoal(11, 1, hooks, "open the maps"))))

        val his = ConversationState(id = ConversationId("ab".repeat(16)))
        val owners = OwnerInput(42, InputChannel.VOICE, InputIntent.Command("read my messages"), 0)
        tags.onConversationStep(ConversationEvent.Input(owners), ConversationState(), MachineStep(his, listOf(ConversationEffect.StartGoal(12, 1, owners, "read my messages"))))
        val joins = OwnerInput(43, InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(joins)
        tags.onConversationStep(ConversationEvent.Input(joins), his, MachineStep(his, listOf(ConversationEffect.StartGoal(13, 2, joins, "and the times"))))

        assertEquals(listOf(opened), own)
    }

    // Plan 7G's re-check of round 10 (Important 1): every Continue of the owner's is his act, whatever its channel, and takes
    // the mark of the conversation that it continues: the notification's Continue, whose number the receiver takes through
    // the seam that no hook armed, the Continue button of the conversation screen, his spoken "Kestrel, continue", which the
    // Orchestrator's rules give the conversation as Listening does, and his Continue on a hand-over's card over an app.
    @Test
    fun theOwnersContinueFromTheNotificationTheScreensButtonHisVoiceOrAHandOversCardTakesTheMark() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        val goal = NoticeGoal(checkNotNull(marked.id), 1)
        val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", null, null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE), goal)
        val buttons = ButtonClaims(folder.root.resolve(ButtonClaims.FILE), tags::claim, clock = { 0L }, boot = 1)
        val number = buttons.take(GoalAction.CONTINUE, goal)
        val notification = checkNotNull(GoalButtons.input(GoalAction.CONTINUE.broadcast, goal, held, InputChannel.VOICE, number, 0))
        val screen = OwnerInput(InputIds.next(), InputChannel.TEXT, InputIntent.Continue, 0)
        step(Event.Toggle)
        val voice = inputsOf(step(Event.Heard("Kestrel, continue.", named = true))).single()
        assertEquals(InputIntent.Continue, voice.intent)

        for (input in listOf(notification, screen, voice)) {
            tags.onConversationStep(ConversationEvent.Input(input), marked, MachineStep(marked, emptyList()))
        }
        tags.onConversationStep(ConversationEvent.Dialogue(DialogueEvent.HandOverDone(1, act = InputIds.next())), marked, MachineStep(marked, emptyList()))

        assertEquals(List(4) { marked.id }, gone)
    }

    // Plan 7G: an act is the owner's unless the hooks claimed its number before the act, on every channel and whatever it
    // means: a command, an answer, a stop, a continue or a new conversation, spoken or typed, a tap that answered a question
    // or "Continue the goal?", and a tap that ended a hand-over. The end of a wait, a withdrawn question and a hand-over that
    // ended by itself are no acts.
    @Test
    fun anActIsTheOwnersUnlessTheHooksClaimedItsNumberWhateverItsChannelAndMeaning() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        val intents = listOf(
            InputIntent.Command("and the times"),
            InputIntent.Answer(ChannelAnswer.Yes),
            InputIntent.Answer(ChannelAnswer.Text("the red one")),
            InputIntent.Stop,
            InputIntent.Continue,
            InputIntent.NewConversation,
        )
        // Each act with its number.
        fun acts(): List<Pair<ConversationEvent, Long>> = intents.flatMap { intent ->
            InputChannel.entries.map { channel ->
                val input = OwnerInput(InputIds.next(), channel, intent, 0)
                ConversationEvent.Input(input) to input.id
            }
        } + listOf(
            InputIds.next().let { ConversationEvent.Dialogue(DialogueEvent.Answered(1, ChannelAnswer.Yes, tap = it)) to it },
            InputIds.next().let { ConversationEvent.ContinueAnswered(ChannelAnswer.Yes, tap = it) to it },
            InputIds.next().let { ConversationEvent.Dialogue(DialogueEvent.HandOverDone(2, act = it)) to it },
        )
        fun give(event: ConversationEvent) = tags.onConversationStep(event, marked, MachineStep(marked, emptyList()))

        acts().forEach { (event, act) ->
            tags.claim(act)
            give(event)
        }
        assertEquals(emptyList<ConversationId>(), gone)

        val owners = acts()
        owners.forEach { (event) -> give(event) }
        assertEquals(List(owners.size) { marked.id }, gone)

        gone.clear()
        listOf(ChannelAnswer.Silence, ChannelAnswer.Interrupted).forEach { answer ->
            give(ConversationEvent.Input(OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.Answer(answer), 0)))
            give(ConversationEvent.Dialogue(DialogueEvent.Answered(3, answer, tap = null)))
        }
        give(ConversationEvent.Dialogue(DialogueEvent.HandOverDone(4, act = null)))
        assertEquals(emptyList<ConversationId>(), gone)
    }

    // Plan 7G: a hook's transcript may give the conversation more than one input, as a stop before a command, and each is
    // the hooks', so neither takes a mark; a tag goes to the last, the command, which its work then names.
    @Test
    fun everyInputOfAHooksTranscriptIsTheirsAlsoAStopBeforeACommand() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"), command = 5)
        step(Event.Toggle)
        state = state.copy(conversation = VoiceView(open = true, ownRequest = true))
        val sent = Event.Heard("Stop. Kestrel, open Viber.", named = true)
        tags.untagged(sent)
        val untagged = inputsOf(step(sent))
        assertEquals(listOf(InputIntent.Stop, InputIntent.Command("open Viber.")), untagged.map { it.intent })

        state = state.copy(conversation = VoiceView(open = true, ownRequest = true))
        val tagged = Event.Heard("Stop. Kestrel, open the maps.", named = true)
        assertTrue(tags.register("stop-first", tagged, VoiceHooks.readOf(tagged.text, named = true), mapOf("model" to "scripted")))
        val (stop, command) = inputsOf(step(tagged))
        assertEquals(emptyMap<String, String>(), stop.options)
        assertEquals(mapOf("model" to "scripted"), command.options)

        for (input in untagged + stop) tags.onConversationStep(ConversationEvent.Input(input), marked, MachineStep(marked, emptyList()))
        tags.onConversationStep(ConversationEvent.Input(command), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(6, 2, command, "open the maps."))))
        assertEquals(emptyList<ConversationId>(), gone)
        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, VoiceHooks.readOf(tagged.text, named = true), task = 6), tags.tagged("stop-first"))
    }

    // Re-check of round 11, Minor 4: a session that listens through the hooks' clip microphone hears only their clips
    // (voice-clip), so every input that it makes is theirs, a command and a stop of a transcript and the spotter's stop,
    // which has no transcript, and none takes the mark; the same words from the owner's microphone are his.
    @Test
    fun everyInputOfASessionThatHearsTheClipMicrophoneIsTheHooks() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"), command = 5)
        fun give(inputs: List<OwnerInput>) = inputs.forEach { tags.onConversationStep(ConversationEvent.Input(it), marked, MachineStep(marked, emptyList())) }
        step(Event.Toggle)
        state = state.copy(conversation = VoiceView(open = true, ownRequest = true))
        val clips = inputsOf(step(Event.Heard("Stop. Kestrel, open Viber.", named = true)), clip = true)
        assertEquals(listOf(InputIntent.Stop, InputIntent.Command("open Viber.")), clips.map { it.intent })
        val spotted = tags.input(null, OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.Stop, 0), clip = true)
        give(clips + spotted)
        assertEquals(emptyList<ConversationId>(), gone)

        state = state.copy(conversation = VoiceView(open = true, ownRequest = true))
        val his = inputsOf(step(Event.Heard("Stop. Kestrel, open Viber.", named = true)))
        give(his + tags.input(null, OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.Stop, 0)))
        assertEquals(List(3) { marked.id }, gone)
    }

    // The whole-plan review of plan 7G, M5: a claim lives as long as its act can still come up in a step, however many acts
    // the hooks claim meanwhile. A hook's command that waits for the goal's end stays theirs after a hundred newer acts of
    // theirs, so its work keeps the mark of their conversation; once its work started, the claim has gone, and the same
    // number would be the owner's.
    @Test
    fun aHooksInputThatWaitsStaysTheirsHoweverManyActsTheyClaimMeanwhile() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"), command = 30)
        val waited = OwnerInput(InputIds.next(), InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(waited)
        val waiting = marked.copy(waiting = listOf(waited))
        tags.onConversationStep(ConversationEvent.Input(waited), marked, MachineStep(waiting, emptyList()))
        repeat(100) {
            val steer = OwnerInput(InputIds.next(), InputChannel.TEXT, InputIntent.Command("slower"), 0)
            tags.untagged(steer)
            tags.onConversationStep(ConversationEvent.Input(steer), waiting, MachineStep(waiting, emptyList()))
        }
        val started = marked.copy(command = 31)
        val ended = ConversationEvent.GoalEnded(RoundEnd(30, TaskReport(EndReason.FINISHED), "Done.", "Done."))
        tags.onConversationStep(ended, waiting, MachineStep(started, listOf(ConversationEffect.StartGoal(31, 2, waited, "and the times"))))
        assertEquals(emptyList<ConversationId>(), gone)

        tags.onConversationStep(ConversationEvent.Input(waited), started, MachineStep(started, emptyList()))
        assertEquals(listOf(marked.id), gone)
    }
}