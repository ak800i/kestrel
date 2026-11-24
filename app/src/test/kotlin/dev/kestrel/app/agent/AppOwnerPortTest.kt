package dev.kestrel.app.agent

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppOwnerPortTest {
    private val sent = mutableListOf<Event>()
    private val screen = object : OwnerScreen {
        val cards = mutableListOf<String>()
        var answer: CompletableDeferred<Boolean?>? = null
        var cardClosed = false
        var forgotten = 0
        var released = 0
        var watched = 0

        override suspend fun card(question: String): Boolean? {
            val shown = answer ?: return null
            cards += question
            try {
                return shown.await()
            } finally {
                cardClosed = true
            }
        }

        override fun forgetOwnActions() {
            forgotten++
        }

        override suspend fun releaseBar() {
            // As the real bar's switch to the main thread, it does nothing in a cancelled coroutine.
            currentCoroutineContext().ensureActive()
            released++
        }

        override suspend fun watch() {
            watched++
        }
    }
    private val audio = object : OtherAudio {
        var plays = false
        var stopsWhenPaused = true

        override fun playing() = plays

        override suspend fun pause() {
            if (stopsWhenPaused) plays = false
        }
    }
    private val ended = CompletableDeferred<Unit>()
    private val opened = mutableListOf<HandOverKind>()
    private val signals = object : HandOverSignals {
        override suspend fun opened(kind: HandOverKind) {
            opened += kind
        }

        override suspend fun endedByItself(kind: HandOverKind) {
            if (kind == HandOverKind.ACCESS) ended.await() else awaitCancellation()
        }
    }
    private val android = CompletableDeferred<PermissionEnd>()
    private var androidAsked = 0
    private val owner = AppOwnerPort(1, { sent += it }, screen, audio, signals, { androidAsked++; android.await() }, { PermissionEnd.PARTLY })

    private val send = Confirmation("Send 'Hi' to Ana on Viber?", "Send to Ana on Viber: 'Hi'? Say confirm or cancel.", Category.SENDING, listOf("Ana"), "Viber", "Hi", "1", null)

    private fun <T> TestScope.started(block: suspend () -> T) = async { block() }.also { runCurrent() }

    @Test
    fun aQuestionGoesThroughTheOrchestratorAndItsAnswerComesBack() = runTest {
        val asked = started { owner.ask("Which chat?", AnswerKind.FREE) }

        assertEquals(listOf<Event>(Event.TaskAsks(1, "Which chat?", TaskAsk.FREE)), sent)
        assertEquals(1, screen.forgotten)
        owner.answered("the second one")
        assertEquals(Answer.Text("the second one"), asked.await())
        assertEquals(1, screen.released)

        val number = started { owner.ask("Which one?", AnswerKind.PICK_NUMBER) }
        owner.answered("3")
        assertEquals(Answer.Number(3), number.await())

        val yesNo = started { owner.ask("Open Viber?", AnswerKind.YES_NO) }
        owner.answered(null)
        assertEquals(Answer.Silence, yesNo.await())
    }

    @Test
    fun aPauseWithdrawsTheQuestionAndHoldsTheGateUntilTheResume() = runTest {
        val asked = started { owner.ask("Open Viber?", AnswerKind.YES_NO) }

        owner.paused()
        assertEquals(Answer.Interrupted, asked.await())
        assertEquals(Answer.Interrupted, owner.ask("Open Viber?", AnswerKind.YES_NO))
        val gate = started { owner.gate() }
        assertFalse(gate.isCompleted)

        owner.resumed()
        assertEquals(Gate.RESUMED, gate.await())
        assertEquals(Gate.GO, owner.gate())
    }

    @Test
    fun theCardAndTheVoiceAnswerAConfirmationAndTheFirstAnswerDecides() = runTest {
        screen.answer = CompletableDeferred()
        val byCard = started { owner.confirm(send) }
        assertEquals(listOf("Send 'Hi' to Ana on Viber?"), screen.cards)
        assertEquals(Event.TaskAsks(1, send.spoken, TaskAsk.CONFIRM, voice = true), sent.single())

        screen.answer!!.complete(true)
        assertEquals(ConfirmEnd.YES, byCard.await())
        assertEquals(Event.TaskBusy(1), sent.last())

        screen.answer = CompletableDeferred()
        screen.cardClosed = false
        val byVoice = started { owner.confirm(send) }
        owner.answered("no")
        assertEquals(ConfirmEnd.NO, byVoice.await())
        assertTrue(screen.cardClosed)

        screen.answer = CompletableDeferred()
        val silent = started { owner.confirm(send) }
        owner.answered(null)
        assertEquals(ConfirmEnd.TIMEOUT, silent.await())
    }

    @Test
    fun anotherAppsAudioLeavesOnlyTheCardUnlessKestrelPausedIt() = runTest {
        screen.answer = CompletableDeferred()
        audio.plays = true
        val paused = started { owner.confirm(send) }
        testScheduler.advanceUntilIdle()
        assertEquals(Event.TaskAsks(1, "${AppOwnerPort.AUDIO_PAUSED} ${send.spoken}", TaskAsk.CONFIRM, voice = true), sent.last())
        owner.answered("yes")
        assertEquals(ConfirmEnd.YES, paused.await())

        audio.plays = true
        audio.stopsWhenPaused = false
        screen.answer = CompletableDeferred()
        val cardOnly = started { owner.confirm(send) }
        testScheduler.advanceUntilIdle()
        assertEquals(Event.TaskAsks(1, send.spoken, TaskAsk.CONFIRM, voice = false), sent.last())
        screen.answer!!.complete(false)
        assertEquals(ConfirmEnd.NO, cardOnly.await())
    }

    @Test
    fun aHandOverEndsWithContinueItsTimeOrByItself() = runTest {
        val continued = started { owner.handOver(HandOver.of(HandOverKind.PAYMENT)) }
        assertEquals(Event.TaskHandsOver(1, HandOver.of(HandOverKind.PAYMENT).text), sent.single())
        assertEquals(HandOverKind.PAYMENT, owner.handOverKind)
        owner.continued()
        assertEquals(HandOverEnd.CONTINUED, continued.await())
        assertNull(owner.handOverKind)

        val expired = started { owner.handOver(HandOver.of(HandOverKind.CREDENTIALS)) }
        owner.handOverExpired()
        assertEquals(HandOverEnd.EXPIRED, expired.await())

        val access = started { owner.handOver(HandOver.of(HandOverKind.ACCESS)) }
        assertEquals(listOf(HandOverKind.PAYMENT, HandOverKind.CREDENTIALS, HandOverKind.ACCESS), opened)
        ended.complete(Unit)
        assertEquals(HandOverEnd.DONE_BY_ITSELF, access.await())
        assertEquals(Event.TaskBusy(1), sent.last())
    }

    @Test
    fun kestrelsOwnPermissionIsAHandOverThatAndroidsAnswerEnds() = runTest {
        val asked = started { owner.askPermission(listOf("android.permission.READ_CONTACTS"), "To find Ana, Kestrel needs your contacts.") }
        assertEquals(Event.TaskHandsOver(1, "To find Ana, Kestrel needs your contacts."), sent.single())
        assertEquals(HandOverKind.PERMISSION, owner.handOverKind)

        android.complete(PermissionEnd.GRANTED)
        assertEquals(PermissionEnd.GRANTED, asked.await())
        assertEquals(Event.TaskBusy(1), sent.last())

        val continued = started { AppOwnerPort(2, { sent += it }, screen, audio, signals, { awaitCancellation() }, { PermissionEnd.PARTLY }).let { other ->
            val result = async { other.askPermission(listOf("android.permission.READ_CONTACTS"), "Why") }
            runCurrent()
            other.continued()
            result.await()
        } }
        assertEquals(PermissionEnd.PARTLY, continued.await())
    }

    @Test
    fun aCancelledQuestionStillLetsTheBarGo() = runTest {
        val asked = started { owner.ask("Which chat?", AnswerKind.FREE) }

        asked.cancel()
        runCurrent()

        assertEquals(1, screen.released)
    }

    @Test
    fun aHandOverThatAPauseMetBeforeItBeganBeginsAfterTheResume() = runTest {
        owner.paused()
        val handedOver = started { owner.handOver(HandOver.of(HandOverKind.PAYMENT)) }
        assertFalse(handedOver.isCompleted)

        owner.resumed()
        runCurrent()
        assertEquals(Event.TaskHandsOver(1, HandOver.of(HandOverKind.PAYMENT).text), sent.single())
        owner.continued()
        assertEquals(HandOverEnd.CONTINUED, handedOver.await())
        // The watcher starts again after the pause and after the hand-over, which an instant command may have stopped.
        assertEquals(2, screen.watched)
    }

    @Test
    fun aPermissionRequestThatAPauseMetBeforeItBeganAsksAfterTheResume() = runTest {
        owner.paused()
        val asked = started { owner.askPermission(listOf("android.permission.READ_CONTACTS"), "Why") }
        assertFalse(asked.isCompleted)
        assertTrue(sent.isEmpty())
        assertEquals(0, androidAsked)

        owner.resumed()
        runCurrent()
        assertEquals(Event.TaskHandsOver(1, "Why"), sent.single())
        assertEquals(1, androidAsked)
        android.complete(PermissionEnd.GRANTED)
        assertEquals(PermissionEnd.GRANTED, asked.await())
    }

    @Test
    fun theAccessPageOpensOnlyAfterThePauseThatMetItsHandOver() = runTest {
        owner.paused()
        val handedOver = started { owner.handOver(HandOver.of(HandOverKind.ACCESS)) }
        assertTrue(opened.isEmpty())

        owner.resumed()
        runCurrent()
        assertEquals(listOf(HandOverKind.ACCESS), opened)
        ended.complete(Unit)
        assertEquals(HandOverEnd.DONE_BY_ITSELF, handedOver.await())
    }

    @Test
    fun theScreenshotQuestionSaysFirstThatKestrelPausedTheOtherAppsAudio() = runTest {
        audio.plays = true
        val asked = started { owner.ask("May I take a screenshot?", AnswerKind.SCREENSHOT) }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<Event>(Event.TaskAsks(1, "${AppOwnerPort.AUDIO_PAUSED} May I take a screenshot?", TaskAsk.FREE)), sent)
        owner.answered("yes")
        asked.await()

        val quiet = started { owner.ask("May I take a screenshot?", AnswerKind.SCREENSHOT) }
        assertEquals(Event.TaskAsks(1, "May I take a screenshot?", TaskAsk.FREE), sent.last())
        owner.answered("yes")
        quiet.await()
    }

    @Test
    fun anOpenConfirmationTellsItsCategory() = runTest {
        val asked = started { owner.confirm(send) }
        assertEquals(Category.SENDING, owner.confirming)

        owner.answered("yes")
        assertEquals(ConfirmEnd.YES, asked.await())
        assertNull(owner.confirming)
    }

    @Test
    fun theAccessHandOverEndsByItselfOnlyOnceAndroidsPageHasLeftTheFront() = runTest {
        val connected = MutableStateFlow(false)
        var front: String? = SETTINGS
        val ended = async { awaitAccessGiven(connected, SETTINGS, { front }) }

        runCurrent()
        assertFalse(ended.isCompleted)
        // The owner turned access on by touch; his Back or Home on the page must not reach a running task.
        connected.value = true
        advanceTimeBy(10_000)
        assertFalse(ended.isCompleted)

        front = "com.android.launcher3"
        advanceTimeBy(1_000)
        assertTrue(ended.isCompleted)
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
    }

    @Test
    fun anOpenQuestionTellsItsKindUntilItEnds() = runTest {
        val asked = started { owner.ask("May I send a screenshot of Settings to Copilot?", AnswerKind.SCREENSHOT) }
        assertEquals(AnswerKind.SCREENSHOT, owner.asking)

        owner.answered("no")
        assertEquals(Answer.Text("no"), asked.await())
        assertNull(owner.asking)

        val number = started { owner.ask("Which number should I use?", AnswerKind.PICK_NUMBER) }
        assertEquals(AnswerKind.PICK_NUMBER, owner.asking)
        owner.answered(null)
        assertEquals(Answer.Silence, number.await())
        assertNull(owner.asking)
    }

    @Test
    fun whatKestrelSaysGoesToTheTasksWitnessToo() = runTest {
        val heard = mutableListOf<String>()
        val witness = object : TaskWitness {
            override fun said(text: String) {
                heard += text
            }

            override fun watch(data: DataPort): DataPort = data

            override val evidence: TaskEvidence get() = TaskEvidence(heard.toList())
        }
        val witnessed = AppOwnerPort(1, { sent += it }, screen, audio, signals, { android.await() }, { PermissionEnd.PARTLY }, witness = witness)

        witnessed.say("Your battery is at 87 percent.")

        assertEquals(listOf<Event>(Event.TaskSays(1, "Your battery is at 87 percent.")), sent)
        assertEquals(listOf("Your battery is at 87 percent."), heard)
    }
}
