package dev.kestrel.app.conversation

import dev.kestrel.app.phone.ScreenReading
import dev.kestrel.app.phone.ScreenStatus
import dev.kestrel.conversation.channel.QuestionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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

    // A lock meets the cards that stay open across it: a hand-over's, and a limit's "Continue?" ("Continue the goal?"
    // is withdrawn by the lock).
    private val handOver = OwnerCard.handOver(-1, "Sign in yourself, then tap Continue.")

    // Plan 7E's rule for Continue and decision 27 of plan 7F: a Continue tapped on the lock screen waits for the
    // owner's unlock within the same lock, and then answers with the number of its own tap, not one taken at the
    // unlock.
    @Test
    fun aContinueTappedOnTheLockScreenAnswersAfterTheUnlockWithItsOwnTapsNumber() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()

        overlay.lock()
        overlay.tap(CardChoice.Continue)
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(2, overlay.shown.size)
        assertEquals(listOf(CardDesk.UNLOCK_TO_CONTINUE), notices)
        // Later acts of the owner's take later numbers meanwhile.
        taps = 10

        overlay.unlock()
        runCurrent()
        assertEquals(CardTap(CardChoice.Continue, 1), asking.await())
        assertNull(desk.open.value)
    }

    // The re-check's N-I1: the held Continue lives only within the lock that it was tapped in. The screen going off
    // drops it, so a later unlock answers nothing; the card stays for a new tap, which on an unlocked phone answers at
    // once and shows no notice.
    @Test
    fun aContinueHeldOnTheLockScreenIsDroppedWhenTheScreenGoesOffAndTheNextUnlockAnswersNothing() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()
        overlay.lock()
        overlay.tap(CardChoice.Continue)
        runCurrent()

        overlay.screenOff()
        runCurrent()
        overlay.lock()
        runCurrent()
        overlay.unlock()
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(handOver, desk.open.value)

        overlay.tap(CardChoice.Continue)
        runCurrent()
        assertEquals(CardTap(CardChoice.Continue, 2), asking.await())
        assertEquals(listOf(CardDesk.UNLOCK_TO_CONTINUE), notices)
    }

    // The second re-check's N2-M2: the tap carries the screen source's count of screen-offs of its moment, so a
    // screen-off and a new lock between the tap and the desk's handling of it drop the tap, although the desk then
    // reads the lock screen, and the next unlock answers nothing.
    @Test
    fun aScreenOffBetweenTheTapAndItsHandlingDropsTheContinue() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()
        overlay.lock()
        runCurrent()

        overlay.tap(CardChoice.Continue)
        overlay.screenOff()
        overlay.lock()
        runCurrent()
        overlay.unlock()
        runCurrent()

        assertFalse(asking.isCompleted)
        assertEquals(handOver, desk.open.value)
        asking.cancel()
    }

    // A Continue that meets the screen off within its own count is dropped at once, without the notice, and the card
    // shows again for a new tap.
    @Test
    fun aContinueTappedWhileTheScreenIsOffIsDroppedAndTheCardShowsAgain() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()
        overlay.screenOff()
        runCurrent()

        overlay.tap(CardChoice.Continue)
        runCurrent()

        assertFalse(asking.isCompleted)
        assertEquals(2, overlay.shown.size)
        assertEquals(emptyList<String>(), notices)
        asking.cancel()
    }

    // Another choice answers in the held Continue's place and drops it, and the unlock then answers nothing.
    @Test
    fun aCancelTappedWhileAContinueWaitsForTheUnlockAnswersInItsPlaceAndDropsIt() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()
        overlay.lock()
        overlay.tap(CardChoice.Continue)
        runCurrent()

        overlay.tap(CardChoice.Cancel)
        runCurrent()
        assertEquals(CardTap(CardChoice.Cancel, 2), asking.await())
        val later = async { desk.ask(handOver.copy(key = -2)) }
        runCurrent()
        overlay.unlock()
        runCurrent()
        assertFalse(later.isCompleted)
        later.cancel()
    }

    // The end of its question while the phone is locked drops the held Continue: the unlock answers no caller.
    @Test
    fun theEndOfItsQuestionDropsAHeldContinue() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()
        overlay.lock()
        overlay.tap(CardChoice.Continue)
        runCurrent()

        asking.cancel()
        runCurrent()
        val again = async { desk.ask(handOver) }
        runCurrent()
        overlay.unlock()
        runCurrent()
        assertFalse(again.isCompleted)
        again.cancel()
    }

    // A second Continue on the same question replaces the first, and answers with its own number.
    @Test
    fun aSecondContinueOnTheLockScreenReplacesTheFirstAndAnswersWithItsOwnNumber() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()
        overlay.lock()
        overlay.tap(CardChoice.Continue)
        runCurrent()
        overlay.tap(CardChoice.Continue)
        runCurrent()

        overlay.unlock()
        runCurrent()
        assertEquals(CardTap(CardChoice.Continue, 2), asking.await())
    }

    // The second re-check's N2-N1: each question holds its own Continue, so a Continue on a newer question leaves the
    // older question's held Continue as it is, and the unlock answers both with their own numbers.
    @Test
    fun aContinueOnAnotherQuestionLeavesTheHeldContinueOfTheFirst() = runTest {
        val desk = desk()
        val first = async { desk.ask(handOver) }
        runCurrent()
        overlay.lock()
        overlay.tap(CardChoice.Continue)
        runCurrent()
        val second = async { desk.ask(handOver.copy(key = -2)) }
        runCurrent()
        overlay.tap(CardChoice.Continue)
        runCurrent()

        overlay.unlock()
        runCurrent()
        assertEquals(CardTap(CardChoice.Continue, 1), first.await())
        assertEquals(CardTap(CardChoice.Continue, 2), second.await())
    }

    // The second re-check's N2-M1: the debug hook's press goes through the desk's one entry for taps, so its Continue
    // on the lock screen is held as the owner's is, says so, and answers with its own number at the unlock.
    @Test
    fun theHooksContinueOnTheLockScreenIsHeldAsTheOwnersIs() = runTest {
        val desk = desk()
        val asking = async { desk.ask(handOver) }
        runCurrent()
        overlay.lock()
        runCurrent()

        val end = desk.withShown { shown, answer -> checkNotNull(shown).let { answer(CardChoice.Continue) } }
        runCurrent()
        assertEquals(TapEnd.HELD, end.await())
        assertEquals(listOf(CardDesk.UNLOCK_TO_CONTINUE), notices)
        taps = 10

        overlay.unlock()
        runCurrent()
        assertEquals(CardTap(CardChoice.Continue, 1), asking.await())
    }

    // Review N1 of plan 7F: the keypad's number answers its pick within the grid's range, and a number outside it does
    // not.
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
        override val screens = MutableStateFlow(ScreenStatus(ScreenReading.UNLOCKED, offs = 0))

        // The screen source's count of screen-offs, which rises at the screen-off, before its reading reaches the desk.
        private var offs = 0L

        override fun screenOffs(): Long = offs

        fun lock() {
            screens.value = ScreenStatus(ScreenReading.LOCKED, offs)
        }

        fun unlock() {
            screens.value = ScreenStatus(ScreenReading.UNLOCKED, offs)
        }

        fun screenOff() {
            offs++
            screens.value = ScreenStatus(ScreenReading.OFF, offs)
        }
        override val connections = MutableStateFlow(1)
        private var tapped = CompletableDeferred<ScreenTap?>()

        // The tap carries the source's count of its moment, as the screen session stamps it on the main thread.
        fun tap(choice: CardChoice) {
            tapped.complete(ScreenTap(choice, offs))
        }

        // The card's session went before an answer: the card gives none.
        fun noAnswer() {
            tapped.complete(null)
        }

        override suspend fun card(card: OwnerCard): ScreenTap? {
            shown += card
            tapped = CompletableDeferred()
            try {
                return tapped.await()
            } catch (e: CancellationException) {
                withdrawn++
                throw e
            }
        }

        override suspend fun releaseBar() {}

        override suspend fun kestrelInFront(): Boolean = false
    }
}
