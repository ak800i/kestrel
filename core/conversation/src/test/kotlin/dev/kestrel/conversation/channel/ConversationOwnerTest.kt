package dev.kestrel.conversation.channel

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.testing.FakeChannel
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationOwnerTest {
    private val voice = FakeChannel(InputChannel.VOICE)
    private val text = FakeChannel(InputChannel.TEXT)
    private var route = ConversationOwner.Routing(InputChannel.VOICE, speakTyped = false)
    private var granted = PermissionEnd.GRANTED
    private val kestrelOn = MutableStateFlow(true)
    private val events = mutableListOf<DialogueEvent>()
    private val owner = ConversationOwner(mapOf(InputChannel.VOICE to voice, InputChannel.TEXT to text), { granted }, kestrelOn, { route }) { events += it }

    private val confirmation = Confirmation(
        question = "Send \"I'm late\" to Ana on Viber?",
        spoken = "Send it to Ana on Viber? Say confirm or cancel.",
        category = Category.SENDING,
        recipients = listOf("Ana"),
        appLabel = "Viber",
        content = "I'm late",
        targetKey = null,
        targetBounds = null,
    )

    private fun typed(speak: Boolean = false) {
        route = ConversationOwner.Routing(InputChannel.TEXT, speak)
    }

    @Test
    fun theOwnersLatestChannelGetsKestrelsWords() = runTest {
        owner.say("Looking.")
        typed()
        owner.take(ChannelOutput.Report(3, "Done.")).show()

        assertEquals(listOf(ChannelOutput.Said("Looking.")), voice.shown)
        assertEquals(listOf(ChannelOutput.Report(3, "Done.")), text.shown)
    }

    @Test
    fun typedRepliesAreSpokenTooWithTheSettingAndASpokenAnswerCounts() = runTest {
        typed(speak = true)
        owner.say("Looking.")

        val answer = async { owner.ask("Which one?", AnswerKind.FREE) }
        runCurrent()
        owner.answer(text.asked.single().number, ChannelAnswer.Text("The first"))

        assertEquals(Answer.Text("The first"), answer.await())
        assertEquals(listOf(ChannelOutput.Said("Looking.")), text.shown)
        assertEquals(listOf(ChannelOutput.Said("Looking.", typedCopy = true), ChannelOutput.Said("Which one?", typedCopy = true, question = text.asked.single().number)), voice.shown)
        assertTrue(voice.asked.isEmpty())
    }

    @Test
    fun whileKestrelIsOffTheVoiceOnlyShowsAndEveryQuestionGetsTheCard() = runTest {
        kestrelOn.value = false
        owner.say("Looking.")
        owner.take(ChannelOutput.Report(2, "Done.")).show()
        assertEquals(listOf(ChannelOutput.Shown("Looking."), ChannelOutput.Shown("Done.")), voice.shown)

        val answer = async { owner.ask("Which one?", AnswerKind.FREE) }
        runCurrent()
        assertEquals(QuestionKind.FREE, text.asked.single().kind)
        owner.answer(text.asked.single().number, ChannelAnswer.Text("The first"))
        assertEquals(Answer.Text("The first"), answer.await())

        // A typed conversation's replies are still spoken with the setting on (design 11), by Kestrel's own voice (plan 7F).
        typed(speak = true)
        owner.take(ChannelOutput.Report(3, "Sent.")).show()
        assertEquals(ChannelOutput.Said("Sent.", typedCopy = true), voice.shown.last())
    }

    @Test
    fun turningKestrelOffAddsTheCardToAnOpenQuestionOrHandOver() = runTest {
        val answer = async { owner.ask("Which one?", AnswerKind.FREE) }
        runCurrent()
        assertTrue(text.asked.isEmpty())
        kestrelOn.value = false
        runCurrent()
        owner.answer(text.asked.single().number, ChannelAnswer.Text("The first"))
        assertEquals(Answer.Text("The first"), answer.await())

        kestrelOn.value = true
        text.handOverEnds += HandOverEnd.CONTINUED
        val handing = async { owner.handOver(HandOver.of(HandOverKind.PAYMENT)) }
        runCurrent()
        assertEquals(1, voice.handOvers.size)
        assertTrue(text.handOvers.isEmpty())
        kestrelOn.value = false
        assertEquals(HandOverEnd.CONTINUED, handing.await())
        assertEquals(1, text.handOvers.size)

        // A permission request's card is a hand-over's: Android's dialog is not shown again, and Continue checks the grant.
        kestrelOn.value = true
        granted = PermissionEnd.PARTLY
        text.handOverEnds += HandOverEnd.CONTINUED
        val asking = async { owner.askPermission(listOf("android.permission.READ_CALENDAR"), "Kestrel needs to read your calendar.") }
        runCurrent()
        kestrelOn.value = false
        assertEquals(PermissionEnd.PARTLY, asking.await())
        assertEquals(HandOverKind.PERMISSION, text.handOvers.last().kind)
    }

    @Test
    fun aTypedReplyThatIsSpokenTooReachesTheVoiceAsWordsOnly() = runTest {
        typed(speak = true)

        owner.take(ChannelOutput.Report(4, "Done.")).show()

        assertEquals(listOf(ChannelOutput.Report(4, "Done.")), text.shown)
        assertEquals(listOf(ChannelOutput.Said("Done.", typedCopy = true)), voice.shown)
    }

    @Test
    fun aQuestionGoesToTheChannelAndTheFirstAnswerWins() = runTest {
        voice.answers += ChannelAnswer.Yes

        assertEquals(Answer.Yes, owner.ask("Send it?", AnswerKind.YES_NO))
        assertEquals(listOf(DialogueEvent.Asked(1, QuestionKind.YES_NO), DialogueEvent.Answered(1, ChannelAnswer.Yes, tap = 0)), events)
        assertNull(owner.asking)
        assertTrue(text.asked.isEmpty())
    }

    // Plan 7G: every answer reaches the engine with its source. A card's answer closes its question with the number of the
    // owner's tap, which the card over the app gives once Kestrel is off; an input's answer, which reached the engine as its
    // own event, closes it without a tap; a withdrawn question closes with its withdrawal and a cancelled one with none.
    @Test
    fun aQuestionClosesWithItsAnswerAndTheNumberOfTheTapThatGaveIt() = runTest {
        kestrelOn.value = false
        text.taps = { 41L }
        text.answers += ChannelAnswer.Yes
        assertEquals(ChannelAnswer.Yes, owner.question("Continue the conversation about the bus?", QuestionKind.SWITCH))
        assertEquals(DialogueEvent.Answered(1, ChannelAnswer.Yes, tap = 41L), events.last())

        val typed = async { owner.question("Delete the history?", QuestionKind.DELETE_HISTORY) }
        runCurrent()
        owner.answer(2, ChannelAnswer.No)
        assertEquals(ChannelAnswer.No, typed.await())
        assertEquals(DialogueEvent.Answered(2, ChannelAnswer.No, tap = null), events.last())

        val withdrawn = async { owner.ask("Which one?", AnswerKind.FREE) }
        runCurrent()
        owner.pause()
        assertEquals(Answer.Interrupted, withdrawn.await())
        assertEquals(DialogueEvent.Answered(3, ChannelAnswer.Interrupted, tap = null), events.last())
        owner.resume()

        val cancelled = async { owner.question("Which one?", QuestionKind.FREE) }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertEquals(DialogueEvent.Answered(4, answer = null, tap = null), events.last())
    }

    @Test
    fun anInputsAnswerCompletesTheOpenQuestionAndAnOlderOneChangesNothing() = runTest {
        typed()
        val answer = async { owner.ask("Which number should I use?", AnswerKind.PICK_NUMBER) }
        runCurrent()
        val number = text.asked.single().number

        assertEquals(QuestionKind.PICK, owner.asking)
        owner.answer(number - 1, ChannelAnswer.Pick(9))
        runCurrent()
        assertFalse(answer.isCompleted)
        owner.answer(number, ChannelAnswer.Pick(3))
        assertEquals(Answer.Number(3), answer.await())
    }

    // Plan 7F: a pick's choices and its highest number reach the channel's question, and a tapped choice answers its number.
    @Test
    fun aPicksChoicesReachTheChannelAndAChoiceAnswersItsNumber() = runTest {
        typed()
        val answer = async { owner.ask("Which number should I use?", AnswerKind.PICK_NUMBER, listOf("1", "2", "3"), highest = 3) }
        runCurrent()
        val asked = text.asked.single()

        assertEquals(listOf("1", "2", "3"), asked.choices)
        assertEquals(3, asked.highest)
        owner.answer(asked.number, ChannelAnswer.Pick(2))
        assertEquals(Answer.Number(2), answer.await())
    }

    @Test
    fun aPauseWithdrawsTheGoalsQuestionAndClosesTheGateUntilTheResume() = runTest {
        val answer = async { owner.ask("Which one?", AnswerKind.FREE) }
        runCurrent()

        owner.pause()

        assertEquals(Answer.Interrupted, answer.await())
        assertEquals(Answer.Interrupted, owner.ask("And now?", AnswerKind.YES_NO))
        val gate = async { owner.gate() }
        runCurrent()
        assertFalse(gate.isCompleted)
        owner.resume()
        assertEquals(Gate.RESUMED, gate.await())
        assertEquals(Gate.GO, owner.gate())

        // A pause that came after its round had ended is not left for the next work.
        owner.pause()
        owner.startWork()
        assertEquals(Gate.GO, owner.gate())
        voice.answers += ChannelAnswer.Yes
        assertEquals(Answer.Yes, owner.ask("Send it?", AnswerKind.YES_NO))
    }

    @Test
    fun aTypedAnswerOnKestrelsScreenGivesTheGoalOneFreshLook() = runTest {
        typed()
        val answer = async { owner.ask("Who?", AnswerKind.FREE) }
        runCurrent()
        owner.answer(text.asked.single().number, ChannelAnswer.Text("Mila"), leftScreen = true)

        assertEquals(Answer.Text("Mila"), answer.await())
        assertEquals(Gate.RESUMED, owner.gate())
        assertEquals(Gate.GO, owner.gate())

        // A pause that meets the fresh look gives it once, with its own resume.
        val again = async { owner.ask("Who else?", AnswerKind.FREE) }
        runCurrent()
        owner.answer(text.asked.last().number, ChannelAnswer.Text("Ana"), leftScreen = true)
        again.await()
        owner.pause()
        val gate = async { owner.gate() }
        runCurrent()
        owner.resume()
        assertEquals(Gate.RESUMED, gate.await())
        assertEquals(Gate.GO, owner.gate())

        // Work that starts drops a fresh look that earlier work left.
        val last = async { owner.ask("And?", AnswerKind.FREE) }
        runCurrent()
        owner.answer(text.asked.last().number, ChannelAnswer.Text("Iva"), leftScreen = true)
        last.await()
        owner.startWork()
        assertEquals(Gate.GO, owner.gate())
    }

    @Test
    fun everyKindOfAnswerTypedOnKestrelsScreenGivesTheFreshLookAndACardsButtonNone() = runTest {
        typed()
        val yes = async { owner.ask("Send it?", AnswerKind.YES_NO) }
        runCurrent()
        owner.answer(text.asked.last().number, ChannelAnswer.Yes, leftScreen = true)
        assertEquals(Answer.Yes, yes.await())
        assertEquals(Gate.RESUMED, owner.gate())

        val pick = async { owner.ask("Which number should I use?", AnswerKind.PICK_NUMBER) }
        runCurrent()
        owner.answer(text.asked.last().number, ChannelAnswer.Pick(2), leftScreen = true)
        assertEquals(Answer.Number(2), pick.await())
        assertEquals(Gate.RESUMED, owner.gate())

        // A card's button acts over the app, and a spoken answer leaves no screen of Kestrel's.
        text.answers += ChannelAnswer.No
        assertEquals(Answer.No, owner.ask("Send it?", AnswerKind.YES_NO))
        assertEquals(Gate.GO, owner.gate())
        route = ConversationOwner.Routing(InputChannel.VOICE, speakTyped = false)
        val spoken = async { owner.ask("Send it?", AnswerKind.YES_NO) }
        runCurrent()
        owner.answer(voice.asked.last().number, ChannelAnswer.Yes)
        assertEquals(Answer.Yes, spoken.await())
        assertEquals(Gate.GO, owner.gate())
    }

    @Test
    fun aConfirmationMapsItsAnswers() = runTest {
        voice.answers += listOf(ChannelAnswer.Yes, ChannelAnswer.Text("maybe"), ChannelAnswer.Silence, ChannelAnswer.Interrupted)

        assertEquals(
            listOf(ConfirmEnd.YES, ConfirmEnd.NO, ConfirmEnd.TIMEOUT, ConfirmEnd.INTERRUPTED),
            List(4) { owner.confirm(confirmation) },
        )
        assertEquals(QuestionKind.CONFIRM, voice.asked.first().kind)
        assertEquals(confirmation.spoken, voice.asked.first().spoken)
        assertEquals(confirmation, voice.asked.first().confirmation)

        // The confirmation is open only once it is asked, after the question before it.
        val before = async { owner.question("Continue the goal?", QuestionKind.CONTINUE_GOAL) }
        runCurrent()
        val pending = async { owner.confirm(confirmation) }
        runCurrent()
        assertNull(owner.confirming)
        owner.answer(voice.asked.last().number, ChannelAnswer.Yes)
        assertEquals(ChannelAnswer.Yes, before.await())
        runCurrent()
        assertEquals(Category.SENDING, owner.confirming)
        owner.answer(voice.asked.last().number, ChannelAnswer.No)
        assertEquals(ConfirmEnd.NO, pending.await())
        assertNull(owner.confirming)
    }

    @Test
    fun aHandOverEndsByItsChannelOrByTheEngine() = runTest {
        typed()
        text.handOverEnds += HandOverEnd.DONE_BY_ITSELF
        assertEquals(HandOverEnd.DONE_BY_ITSELF, owner.handOver(HandOver.of(HandOverKind.PAYMENT)))

        val pending = async { owner.handOver(HandOver.of(HandOverKind.CREDENTIALS)) }
        runCurrent()
        val number = (events.last() as DialogueEvent.HandedOver).number

        assertEquals(HandOverKind.CREDENTIALS, owner.handOverKind)
        owner.endHandOver(number, HandOverEnd.CONTINUED)
        assertEquals(HandOverEnd.CONTINUED, pending.await())
        assertNull(owner.handOverKind)
        assertEquals(DialogueEvent.HandOverDone(number), events.last())
    }

    @Test
    fun aHandOverThatWaitsForTheResumeLetsContinueTheGoalBeAsked() = runTest {
        owner.pause()
        val handing = async { owner.handOver(HandOver.of(HandOverKind.PAYMENT)) }
        runCurrent()
        assertTrue(voice.handOvers.isEmpty())

        val answer = async { owner.question("Continue the goal?", QuestionKind.CONTINUE_GOAL) }
        runCurrent()
        assertEquals(QuestionKind.CONTINUE_GOAL, voice.asked.single().kind)
        owner.answer(voice.asked.single().number, ChannelAnswer.Yes)
        assertEquals(ChannelAnswer.Yes, answer.await())

        owner.resume()
        runCurrent()
        assertEquals(1, voice.handOvers.size)
        owner.endHandOver((events.last() as DialogueEvent.HandedOver).number, HandOverEnd.CONTINUED)
        assertEquals(HandOverEnd.CONTINUED, handing.await())
    }

    @Test
    fun aPermissionHandOverChecksThePermissionsAfterContinue() = runTest {
        val permissions = listOf("android.permission.READ_CALENDAR")
        val continued = async { owner.askPermission(permissions, "Kestrel needs to read your calendar.") }
        runCurrent()
        granted = PermissionEnd.PARTLY
        owner.endHandOver((events.last() as DialogueEvent.HandedOver).number, HandOverEnd.CONTINUED)
        assertEquals(PermissionEnd.PARTLY, continued.await())

        val expired = async { owner.askPermission(permissions, "Again.") }
        runCurrent()
        owner.endHandOver((events.last() as DialogueEvent.HandedOver).number, HandOverEnd.EXPIRED)
        assertEquals(PermissionEnd.REFUSED, expired.await())

        voice.permissionEnds += PermissionEnd.GRANTED
        assertEquals(PermissionEnd.GRANTED, owner.askPermission(permissions, "Once more."))
    }

    @Test
    fun continueTheGoalIsAlsoACardInASpokenConversationAndNoPauseWithdrawsIt() = runTest {
        val answer = async { owner.question("Continue the goal?", QuestionKind.CONTINUE_GOAL) }
        runCurrent()

        owner.pause()
        runCurrent()

        assertFalse(answer.isCompleted)
        assertEquals(QuestionKind.CONTINUE_GOAL, voice.asked.single().kind)
        assertEquals(QuestionKind.CONTINUE_GOAL, text.asked.single().kind)
        owner.answer(voice.asked.single().number, ChannelAnswer.Yes)
        assertEquals(ChannelAnswer.Yes, answer.await())
    }

    @Test
    fun leaveKestrelAsksTheLatestChannel() = runTest {
        text.leaves = false

        assertTrue(owner.leaveKestrel())
        typed()
        assertFalse(owner.leaveKestrel())

        // With no channel for the latest input, nothing can take Kestrel's screen away.
        val voiceOnly = ConversationOwner(mapOf(InputChannel.VOICE to voice), { granted }, kestrelOn, { route }) {}
        assertFalse(voiceOnly.leaveKestrel())
    }

    // Plan 7F: one hand-over opens its page once. In a spoken conversation the voice owns the hand-over, and the text
    // channel's card, which comes once Kestrel is off, also when it turns off during the hand-over, is only a copy of its card.
    @Test
    fun aSpokenConversationsHandOverIsTheVoicesAndTheTextChannelShowsOnlyItsCard() = runTest {
        val handing = async { owner.handOver(HandOver.of(HandOverKind.ACCESS)) }
        runCurrent()
        assertEquals(listOf(HandOverPart.OWN), voice.handOverParts)
        assertTrue(text.handOvers.isEmpty())

        kestrelOn.value = false
        runCurrent()
        assertEquals(listOf(HandOverPart.CARD), text.handOverParts)
        owner.endHandOver((events.last() as DialogueEvent.HandedOver).number, HandOverEnd.CONTINUED)
        assertEquals(HandOverEnd.CONTINUED, handing.await())
    }

    // Plan 7F: the voice's copy of a typed conversation's words says only that it is one, and the voice channel picks the
    // voice at its turn; a spoken conversation's words while Kestrel is off are only shown (design 11).
    @Test
    fun theVoicesCopyOfATypedConversationsWordsIsMarkedAsOne() = runTest {
        typed(speak = true)
        owner.say("On it.")
        kestrelOn.value = false
        owner.say("Done.")
        route = ConversationOwner.Routing(InputChannel.VOICE, speakTyped = false)
        owner.say("Looking.")

        assertEquals(
            listOf(ChannelOutput.Said("On it.", typedCopy = true), ChannelOutput.Said("Done.", typedCopy = true), ChannelOutput.Shown("Looking.")),
            voice.shown,
        )
    }

    // Plan 7F: an output takes its places on the channels when the conversation emits it, and is routed as the mode is when
    // its turn comes: turning Kestrel off or on while it waits is a switch of voice, so it reaches the voice that fits.
    @Test
    fun anOutputIsRoutedAsTheModeIsWhenItsTurnComes() = runTest {
        val report = owner.take(ChannelOutput.Report(2, "Done."))
        val words = owner.take(ChannelOutput.Said("Sent."))
        assertEquals(2, voice.takes)

        kestrelOn.value = false
        report.show()
        kestrelOn.value = true
        words.show()

        assertEquals(listOf(ChannelOutput.Shown("Done."), ChannelOutput.Said("Sent.")), voice.shown)
    }
}
