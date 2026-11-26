package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.QuestionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** One card per question number, whichever channel asks it (design 10.3 of sub-project 7; plan 7D's interfaces). */
@OptIn(ExperimentalCoroutinesApi::class)
class CardDeskTest {
    private val overlay = FakeOverlay()
    private var taps = 0L
    private val notices = mutableListOf<String>()

    private fun TestScope.desk() = CardDesk(overlay, backgroundScope, notice = { notices += it }, replyOpenMillis = 5_000, inputIds = { ++taps })

    private val voiceCard = OwnerCard(6, "May I take a screenshot?", listOf(CardChoice.Yes, CardChoice.No), question = 6, kind = QuestionKind.SCREENSHOT)
    private val textCard = voiceCard.copy(choices = listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply))

    @Test
    fun twoChannelsOfOneQuestionShareOneCardWithTheMostChoicesAndBothGetTheTap() = runTest {
        val desk = desk()
        val voice = async { desk.ask(voiceCard) }
        val text = async { desk.ask(textCard) }
        runCurrent()

        assertEquals(textCard, overlay.shown.last())
        assertEquals(textCard, desk.open.value)
        overlay.tap(CardChoice.Yes)
        runCurrent()

        // One tap is one act of the owner's: both callers get its number, which the desk took as the tap reached it.
        assertEquals(CardTap(CardChoice.Yes, 1), voice.await())
        assertEquals(CardTap(CardChoice.Yes, 1), text.await())
        assertNull(desk.open.value)
    }

    @Test
    fun replyAnswersOnlyTheCallerThatHasItAndHidesTheCardUntilTheScreenCameOrItsWaitEnded() = runTest {
        val desk = desk()
        var replies = 0
        val voice = async { desk.ask(voiceCard) }
        val text = async { desk.ask(textCard) { replies++ } }
        runCurrent()

        overlay.tap(CardChoice.Reply)
        runCurrent()
        assertEquals(1, replies)
        assertFalse(voice.isCompleted || text.isCompleted)
        val shown = overlay.shown.size
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(shown, overlay.shown.size)

        // The screen did not come: the card shows again after its wait.
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(shown + 1, overlay.shown.size)
        voice.cancel()
        text.cancel()
    }

    @Test
    fun theScreenInFrontTakesTheCardFromTheOverlayAndGivesItBack() = runTest {
        val desk = desk()
        val asking = async { desk.ask(textCard) }
        runCurrent()

        desk.screenInFront(true)
        runCurrent()
        assertEquals(1, overlay.withdrawn)
        assertEquals(textCard, desk.open.value)

        desk.screenInFront(false)
        runCurrent()
        assertEquals(2, overlay.shown.size)
        asking.cancel()
        runCurrent()
        assertNull(desk.open.value)
        assertEquals(2, overlay.withdrawn)
    }

    @Test
    fun aNewerQuestionTakesTheCardAndTheOlderComesBackWhenItEnds() = runTest {
        val desk = desk()
        val older = async { desk.ask(voiceCard) }
        runCurrent()
        val handOver = OwnerCard.handOver(-1, "Sign in yourself, then tap Continue.")
        val newer = async { desk.ask(handOver) }
        runCurrent()
        assertEquals(handOver, overlay.shown.last())

        overlay.tap(CardChoice.Cancel)
        runCurrent()
        assertEquals(CardChoice.Cancel, newer.await().choice)
        assertEquals(voiceCard, overlay.shown.last())
        older.cancel()
    }

    private val heldGoal = OwnerCard(9, "Continue the goal?", listOf(CardChoice.Stop, CardChoice.Continue), question = 9, kind = QuestionKind.CONTINUE_GOAL)

    // Plan 7E and decision 27 of plan 7F (the whole-plan review's M2): a Continue tapped while the phone is locked waits for
    // the owner's unlock, as the goal notification's Continue does, and then answers with the number of its own tap.
    @Test
    fun aContinueTappedWhileThePhoneIsLockedAnswersAfterTheUnlockWithItsTapsNumber() = runTest {
        val desk = desk()
        val asking = async { desk.ask(heldGoal) }
        runCurrent()

        overlay.screenUnlocked.value = false
        overlay.tap(CardChoice.Continue)
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(2, overlay.shown.size)
        assertEquals(listOf(CardDesk.UNLOCK_TO_CONTINUE), notices)

        overlay.screenUnlocked.value = true
        runCurrent()
        assertEquals(CardTap(CardChoice.Continue, 1), asking.await())
        assertNull(desk.open.value)
    }

    // The card shows again while the Continue waits, so the owner's Stop answers in its place, and the unlock then answers
    // nothing.
    @Test
    fun aStopTappedWhileAContinueWaitsForTheUnlockAnswersInItsPlace() = runTest {
        val desk = desk()
        val asking = async { desk.ask(heldGoal) }
        runCurrent()
        overlay.screenUnlocked.value = false
        overlay.tap(CardChoice.Continue)
        runCurrent()

        overlay.tap(CardChoice.Stop)
        runCurrent()
        assertEquals(CardTap(CardChoice.Stop, 2), asking.await())
        val later = async { desk.ask(heldGoal.copy(key = 10, question = 10)) }
        runCurrent()
        overlay.screenUnlocked.value = true
        runCurrent()

        assertFalse(later.isCompleted)
        later.cancel()
    }

    // Review N1 of plan 7F: the keypad's number answers its pick within the grid's range, and a number outside it does not.
    @Test
    fun aKeypadsNumberWithinTheRangeAnswersThePickAndOneOutsideItDoesNot() = runTest {
        val desk = desk()
        val pick = OwnerCard(7, "Which number should I use?", listOf(CardChoice.Cancel), question = 7, kind = QuestionKind.PICK, numberPad = true, highest = 20)
        val asking = async { desk.ask(pick) }
        runCurrent()

        overlay.tap(CardChoice.Pick(21))
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(2, overlay.shown.size)
        overlay.tap(CardChoice.Pick(12))
        runCurrent()

        assertEquals(CardChoice.Pick(12), asking.await().choice)
    }

    // Plan 7F: a card that got no answer, because no screen session was connected or its session closed, shows again when a
    // screen session connects, and its callers keep waiting meanwhile.
    @Test
    fun aCardThatGotNoAnswerShowsAgainWhenAScreenSessionConnects() = runTest {
        val desk = desk()
        val asking = async { desk.ask(textCard) }
        runCurrent()
        overlay.noAnswer()
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(1, overlay.shown.size)

        overlay.connections.value = 2
        runCurrent()
        assertEquals(2, overlay.shown.size)
        assertEquals(textCard, overlay.shown.last())
        asking.cancel()
    }
    // Task 13a (plan 7G's F1, review R3 of plan 7F): the debug hook sees the card on the overlay, and for how long the desk
    // has given it to the screen, under the desk's lock, only while it shows, and its answer answers that card's question.
    @Test
    fun theHookSeesTheOverlaysCardWhileItShowsAndItsAnswerAnswersTheQuestion() = runTest {
        val desk = CardDesk(overlay, backgroundScope, replyOpenMillis = 5_000, clock = { testScheduler.currentTime })
        val confirmation = OwnerCard(4, "Send the message?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 4, kind = QuestionKind.CONFIRM)
        val asking = async { desk.ask(confirmation) }
        runCurrent()
        advanceTimeBy(1_200)

        assertEquals(confirmation to 1_200L, desk.withShown { shown, _ -> shown?.let { it.card to it.shownMillis } })
        // Kestrel's screen in front takes the card from the overlay at once, before the overlay has withdrawn it.
        desk.screenInFront(true)
        assertNull(desk.withShown { shown, _ -> shown })
        desk.screenInFront(false)
        runCurrent()
        desk.withShown { shown, answer -> if (shown != null) answer(CardChoice.Confirm) }
        runCurrent()

        assertEquals(CardChoice.Confirm, asking.await().choice)
        assertNull(desk.withShown { shown, _ -> shown })
        assertNull(desk.open.value)
    }

    // Plan 7G's review N2: once a newer question took the overlay, the hook no longer sees the card that it read, even in
    // the moment before the overlay has withdrawn it.
    @Test
    fun aCardThatANewerQuestionReplacedIsNotTheOneThatTheHookSees() = runTest {
        val desk = CardDesk(overlay, backgroundScope, replyOpenMillis = 5_000, clock = { testScheduler.currentTime })
        val read = OwnerCard(4, "Send the message?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 4, kind = QuestionKind.CONFIRM)
        val older = async { desk.ask(read) }
        runCurrent()
        assertEquals(4, desk.withShown { shown, _ -> shown?.card?.key })

        val newer = async(start = CoroutineStart.UNDISPATCHED) { desk.ask(read.copy(key = 5, text = "Delete the chat?", question = 5)) }
        assertNull(desk.withShown { shown, _ -> shown })
        runCurrent()
        assertEquals(5, desk.withShown { shown, _ -> shown?.card?.key })
        assertFalse(older.isCompleted || newer.isCompleted)
        older.cancel()
        newer.cancel()
    }

    private class FakeOverlay : OwnerScreen {
        val shown = mutableListOf<OwnerCard>()
        var withdrawn = 0
        val screenUnlocked = MutableStateFlow(true)
        override val connections = MutableStateFlow(1)
        private var tapped = CompletableDeferred<CardChoice?>()

        fun tap(choice: CardChoice) {
            tapped.complete(choice)
        }

        // The card's session went before an answer: the card gives none.
        fun noAnswer() {
            tapped.complete(null)
        }

        override suspend fun card(card: OwnerCard): CardChoice? {
            shown += card
            tapped = CompletableDeferred()
            try {
                return tapped.await()
            } catch (e: CancellationException) {
                withdrawn++
                throw e
            }
        }

        override fun locked(): Boolean = !screenUnlocked.value

        override suspend fun unlocked() {
            screenUnlocked.first { it }
        }

        override suspend fun releaseBar() {}

        override suspend fun kestrelInFront(): Boolean = false
    }
}
