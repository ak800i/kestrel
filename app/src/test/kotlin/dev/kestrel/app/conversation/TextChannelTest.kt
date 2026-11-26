package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.OwnedBar
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conversation's text channel (design 10, 11 and 12.2 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class TextChannelTest {
    private val support = FakeOwnerParts()
    private val conversation = FakeConversation()
    private val opened = mutableListOf<Int>()
    private lateinit var desk: CardDesk

    private fun TestScope.channel(): TextChannel {
        desk = CardDesk(support, backgroundScope)
        return TextChannel(OwnedBar(support, backgroundScope) { testScheduler.currentTime }, support, desk, support, support, ReplyScreen { opened += it }, conversation) { 1_000L }
    }

    @Test
    fun kestrelsWordsGoToTheBarAndNothingIsSpoken() = runTest {
        val channel = channel()
        channel.show(ChannelOutput.Said("On it."))
        channel.show(ChannelOutput.Shown("Got it."))
        channel.show(ChannelOutput.Report(2, "Done."))

        assertEquals(listOf("On it.", "Got it.", "Done."), support.bar)
    }

    @Test
    fun aQuestionShowsItsCardOnTheOverlayAndItsButtonAnswersIt() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(3, "Which account?", QuestionKind.YES_NO)) }
        runCurrent()
        val card = OwnerCard(3, "Which account?", listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), question = 3, kind = QuestionKind.YES_NO)
        assertEquals(listOf(card), support.cards)
        assertEquals(card, desk.open.value)

        support.tap(CardChoice.No)

        assertEquals(ChannelAnswer.No, asking.await().answer)
        runCurrent()
        assertNull(desk.open.value)
    }

    // Design 10.3 and D5: a button on Kestrel's own screen is the owner's input, so the goal looks afresh after it.
    @Test
    fun whileTheScreenIsInFrontTheOverlayGoesAndAScreenButtonIsTheOwnersInput() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(4, "Send it?", QuestionKind.YES_NO)) }
        runCurrent()

        desk.screenInFront(true)
        runCurrent()
        assertEquals(1, support.withdrawn)
        assertEquals(4, desk.open.value?.key)
        // A tap on another card's copy, or a choice that the card does not have, changes nothing.
        channel.choose(3, CardChoice.No)
        channel.choose(4, CardChoice.Confirm)
        channel.choose(4, CardChoice.Yes)

        assertEquals(listOf(InputIntent.Answer(ChannelAnswer.Yes, question = 4)), conversation.submitted.map { it.intent })
        assertEquals(InputChannel.TEXT, conversation.submitted.single().channel)
        // The conversation's answer withdraws the question; the screen's tap is no card's answer.
        assertFalse(asking.isCompleted)
        asking.cancel()
    }

    // Design 12.2 and 12.3: only the card over the app answers a confirmation, never Kestrel's own screen.
    @Test
    fun aConfirmationIsAnsweredOnlyOnTheOverlay() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(5, "Send it to Mila?", QuestionKind.CONFIRM)) }
        runCurrent()
        desk.screenInFront(true)
        runCurrent()

        channel.choose(5, CardChoice.Confirm)
        assertTrue(conversation.submitted.isEmpty())

        desk.screenInFront(false)
        runCurrent()
        assertEquals(2, support.cards.size)
        support.tap(CardChoice.Confirm)
        assertEquals(ChannelAnswer.Yes, asking.await().answer)
    }

    @Test
    fun replyOnTheOverlayHoldsTheQuestionAndOpensTheScreenForTheOwnersWords() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(6, "Which time?", QuestionKind.FREE)) }
        runCurrent()

        support.tap(CardChoice.Reply)
        runCurrent()
        assertEquals(listOf(6), conversation.replies)
        assertEquals(listOf(6), opened)
        desk.screenInFront(true)
        runCurrent()
        assertEquals(1, support.cards.size)
        assertEquals(6, desk.open.value?.question)

        // The typed answer reaches the conversation as an input, which then withdraws the question.
        conversation.view = ConversationView(asking = QuestionKind.FREE, question = 6)
        channel.submit(channel.read("Half past seven")!!)
        assertEquals(InputIntent.Answer(ChannelAnswer.Text("Half past seven"), question = 6), conversation.submitted.single().intent)
        asking.cancel()
        runCurrent()
        assertNull(desk.open.value)
    }

    @Test
    fun aHandOverShowsCancelAndContinueAndEndsByItselfForItsKind() = runTest {
        val channel = channel()
        val handing = async { channel.handOver(HandOver.of(HandOverKind.ACCESS)) }
        runCurrent()
        assertEquals(listOf(HandOverKind.ACCESS), support.opened)
        assertEquals(listOf(CardChoice.Cancel, CardChoice.Continue), support.cards.single().choices)
        assertTrue(support.cards.single().key < 0)
        assertNull(support.cards.single().question)

        support.endedByItself.complete(Unit)
        assertEquals(HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null), handing.await())

        val other = async { channel.handOver(HandOver.of(HandOverKind.PERMISSION)) }
        runCurrent()
        support.tap(CardChoice.Cancel)
        // The tap on Cancel carries its number, which the desk took at the tap.
        val cancelled = other.await()
        assertEquals(HandOverEnd.CANCELLED, cancelled.end)
        assertTrue(cancelled.act != null)
        assertFalse(support.cards[0].key == support.cards[1].key)
    }

    // Plan 7F: as the copy of a spoken conversation's hand-over, the card neither opens the hand-over's page nor waits for
    // its end by itself: the voice owns the hand-over, which so opens its page once, also when Kestrel turns off during it.
    @Test
    fun aHandOversCardBesideTheVoiceOpensNothing() = runTest {
        val channel = channel()
        val handing = async { channel.handOver(HandOver.of(HandOverKind.ACCESS), HandOverPart.CARD) }
        runCurrent()
        assertTrue(support.opened.isEmpty())
        support.endedByItself.complete(Unit)
        runCurrent()
        assertFalse(handing.isCompleted)

        support.tap(CardChoice.Continue)
        assertEquals(HandOverEnd.CONTINUED, handing.await().end)
    }

    @Test
    fun typedWordsBecomeTextInputsThatTheScreenHearsOf() = runTest {
        val channel = channel()
        val sent = mutableListOf<SentInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { channel.sent.collect { sent += it } }
        val asking = ConversationView(asking = QuestionKind.YES_NO, question = 2)
        conversation.view = asking
        // The conversation's view moves on as soon as it gets an input, as the engine's may before the screen hears of it.
        conversation.onSubmit = { conversation.view = ConversationView(command = conversation.submitted.size) }

        channel.submit(channel.read("yes")!!)
        channel.submit(channel.input(InputIntent.Stop))

        assertEquals(listOf(InputChannel.TEXT, InputChannel.TEXT), conversation.submitted.map { it.channel })
        assertEquals(listOf(1_000L, 1_000L), conversation.submitted.map { it.atMillis })
        assertTrue(conversation.submitted[0].id < conversation.submitted[1].id)
        assertEquals(listOf(InputIntent.Answer(ChannelAnswer.Yes, question = 2), InputIntent.Stop), sent.map { it.intent })
        // Each input carries the view from before the conversation got it (the whole-plan review's M3).
        assertEquals(listOf(asking, ConversationView(command = 1)), sent.map { it.before })
        assertNull(channel.read("  "))
        channel.composing(true)
        assertEquals(listOf(true), conversation.composings)
    }

    @Test
    fun aPermissionSaysWhyOnTheBarAndShowsAndroidsDialog() = runTest {
        val channel = channel()
        val end = channel.askPermission(listOf("android.permission.READ_CONTACTS"), "I need your contacts to find Mila.")

        assertEquals(PermissionEnd.GRANTED, end)
        assertEquals(listOf("I need your contacts to find Mila."), support.bar)
        assertEquals(listOf(listOf("android.permission.READ_CONTACTS")), support.asked)
    }

    @Test
    fun leavingKestrelsOwnScreenWaitsAtMostThreeSeconds() = runTest {
        val channel = channel()
        support.kestrelPolls = 2
        assertTrue(channel.leaveKestrel())

        support.kestrelPolls = Int.MAX_VALUE
        val start = currentTime
        assertFalse(channel.leaveKestrel())
        assertEquals(OwnerPort.KESTREL_LEAVE_MILLIS, currentTime - start)
    }

    private class FakeConversation : TypedConversation {
        var view = ConversationView()
        val submitted = mutableListOf<OwnerInput>()
        val replies = mutableListOf<Int>()
        val composings = mutableListOf<Boolean>()

        override fun view(): ConversationView = view

        var onSubmit: () -> Unit = {}

        override fun submit(input: OwnerInput) {
            submitted += input
            onSubmit()
        }

        override fun replyOpened(question: Int) {
            replies += question
        }

        override fun composing(active: Boolean) {
            composings += active
        }
    }

    private class FakeOwnerParts : FeedbackBar, OwnerScreen, HandOverSignals, PermissionDialog {
        override val connections = MutableStateFlow(1)
        val cards = mutableListOf<OwnerCard>()
        val bar = mutableListOf<String>()
        var withdrawn = 0
        var kestrelPolls = 0
        val opened = mutableListOf<HandOverKind>()
        val endedByItself = CompletableDeferred<Unit>()
        val asked = mutableListOf<List<String>>()
        private var answer = CompletableDeferred<CardChoice?>()

        fun tap(choice: CardChoice) {
            answer.complete(choice)
        }

        override fun show(text: String, keep: Boolean) {
            bar += text
        }

        override fun release() {}

        override suspend fun card(card: OwnerCard): CardChoice? {
            cards += card
            answer = CompletableDeferred()
            try {
                return answer.await()
            } catch (e: CancellationException) {
                withdrawn++
                throw e
            }
        }

        override fun locked(): Boolean = false

        override suspend fun unlocked() {}

        override suspend fun releaseBar() {}

        override suspend fun kestrelInFront(): Boolean = (kestrelPolls > 0).also { if (it) kestrelPolls-- }

        override suspend fun opened(kind: HandOverKind) {
            opened += kind
        }

        override suspend fun endedByItself(kind: HandOverKind) {
            if (kind == HandOverKind.ACCESS) endedByItself.await() else awaitCancellation()
        }

        override suspend fun ask(permissions: List<String>): PermissionEnd {
            asked += permissions
            return PermissionEnd.GRANTED
        }
    }
}
