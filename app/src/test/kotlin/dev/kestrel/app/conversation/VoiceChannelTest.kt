package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.context.SteeringMessage
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conversation's voice (design 11 of sub-project 7; design D2, D14, D15 and D32 items 17 and 18 of sub-project 5). */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChannelTest {
    private val voice = FakeVoice()
    private val support = FakeSupport()
    private val channel = VoiceChannel(voice, support, support, support, support, audioSettleMillis = 500)

    @Test
    fun kestrelsWordsAndItsFinalReplyAreSpokenAndShownWordsOnlyShown() = runTest {
        channel.show(ChannelOutput.Said("Android gave Kestrel only the photos that you selected."))
        channel.show(ChannelOutput.Report(3, "Done."))
        channel.show(ChannelOutput.Shown("Got it."))

        assertEquals(listOf("Android gave Kestrel only the photos that you selected.", "Done."), voice.said)
        assertEquals(listOf("Got it."), voice.shown)
    }

    // The engine says "I got 'X' too late" at once, also while a goal's final words still play (plan 7B): each spoken
    // output waits until the one before it has ended, in the order in which they came.
    @Test
    fun spokenOutputsPlayOneAfterAnotherInTheOrderInWhichTheyCame() = runTest {
        val tooLate = ConversationTexts.cameTooLate(SteeringMessage.of("and to Ana", InputChannel.VOICE))
        voice.holds = true
        val report = async { channel.show(ChannelOutput.Report(3, "Done.")) }
        runCurrent()
        val late = async { channel.show(ChannelOutput.Said(tooLate)) }
        runCurrent()
        assertEquals(listOf("Done."), voice.said)

        voice.ends.removeFirst().complete(Unit)
        runCurrent()
        assertTrue(report.isCompleted)
        assertEquals(listOf("Done.", tooLate), voice.said)
        assertFalse(late.isCompleted)

        voice.ends.removeFirst().complete(Unit)
        runCurrent()
        assertTrue(late.isCompleted)
    }

    // Decision 10: a stop ends Kestrel's speech at once, also the spoken outputs that wait for their turn, such as the "too
    // late" sentence behind a goal's final words; only the outputs that come after the stop play.
    @Test
    fun aStopDropsTheSpokenOutputsThatWaitAndOnlyLaterOnesPlay() = runTest {
        val tooLate = ConversationTexts.cameTooLate(SteeringMessage.of("and to Ana", InputChannel.VOICE))
        voice.holds = true
        val report = async { channel.show(ChannelOutput.Report(3, "Done.")) }
        runCurrent()
        val late = async { channel.show(ChannelOutput.Said(tooLate)) }
        runCurrent()

        voice.stop()
        runCurrent()
        assertTrue(report.isCompleted)
        assertTrue(late.isCompleted)
        assertEquals(listOf("Done."), voice.said)

        val after = async { channel.show(ChannelOutput.Said("Stopped.")) }
        runCurrent()
        assertEquals(listOf("Done.", "Stopped."), voice.said)
        voice.ends.removeFirst().complete(Unit)
        runCurrent()
        assertTrue(after.isCompleted)
    }

    @Test
    fun aQuestionIsAskedWheneverAVoiceSessionOpensAndLetsTheBarGoOnceWithdrawn() = runTest {
        val asking = async { channel.ask(ChannelQuestion(4, "Which account?", QuestionKind.FREE)) }
        runCurrent()
        assertEquals(listOf(Asked(4, "Which account?", QuestionKind.FREE, voice = true)), voice.asked)

        // Kestrel turned off and on again: the question is spoken again once a session is open.
        voice.ready.value = false
        runCurrent()
        voice.ready.value = true
        runCurrent()
        assertEquals(2, voice.asked.size)
        assertFalse(asking.isCompleted)
        assertEquals(0, support.releases)

        // The spoken answer reaches the conversation as an input, which then withdraws the question: the voice's answer
        // wait ends.
        asking.cancel()
        runCurrent()
        assertEquals(1, support.releases)
        assertEquals(listOf(4), voice.withdrawn)
    }

    @Test
    fun aConfirmationShowsItsCardWhileKestrelSpeaksAndTheCardAnswersIt() = runTest {
        val confirmation = sending()
        val asking = async { channel.ask(ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)) }
        runCurrent()
        assertEquals(listOf(Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true)), voice.asked)
        assertEquals(listOf("Send it to Mila?"), support.cards)

        support.card.complete(true)
        runCurrent()
        assertEquals(ChannelAnswer.Yes, asking.await())
        assertEquals(1, support.releases)
    }

    @Test
    fun anotherAppsAudioLeavesOnlyTheCardUnlessKestrelPausedIt() = runTest {
        val confirmation = sending()
        val question = ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)

        // The audio goes on although Kestrel pressed pause: only the card's button counts.
        support.playing = true
        support.pauseStops = false
        val cardOnly = async { channel.ask(question) }
        advanceUntilIdle()
        assertEquals(Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = false), voice.asked.last())
        support.card.complete(false)
        assertEquals(ChannelAnswer.No, cardOnly.await())

        // Kestrel paused it: the voice counts again, and Kestrel says so first.
        support.card = CompletableDeferred()
        support.pauseStops = true
        val voiced = async { channel.ask(question) }
        advanceUntilIdle()
        assertEquals(Asked(5, "${VoiceChannel.AUDIO_PAUSED} Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true), voice.asked.last())
        voiced.cancel()

        // The screenshot question does the same, and has no card while the voice may answer.
        support.playing = true
        val screenshot = async { channel.ask(ChannelQuestion(6, "May I take a screenshot?", QuestionKind.SCREENSHOT)) }
        advanceUntilIdle()
        assertEquals(Asked(6, "${VoiceChannel.AUDIO_PAUSED} May I take a screenshot?", QuestionKind.SCREENSHOT, voice = true), voice.asked.last())
        assertEquals(2, support.cards.size)
        screenshot.cancel()
    }

    @Test
    fun aHandOverSaysItsSentenceOpensItsPageAndEndsByItselfOnlyForItsKind() = runTest {
        val handing = async { channel.handOver(HandOver.of(HandOverKind.ACCESS)) }
        runCurrent()
        assertEquals(listOf(HandOver.of(HandOverKind.ACCESS).text), voice.handOvers)
        assertEquals(listOf(HandOverKind.ACCESS), support.opened)
        assertFalse(handing.isCompleted)

        support.endedByItself.complete(Unit)
        assertEquals(HandOverEnd.DONE_BY_ITSELF, handing.await())
        assertEquals(1, support.releases)
    }

    @Test
    fun kestrelsOwnPermissionSaysWhyAndAndroidsDialogAnswersIt() = runTest {
        support.android = PermissionEnd.PARTLY

        val end = channel.askPermission(listOf("android.permission.READ_CONTACTS"), "I need your contacts to find Mila.")

        assertEquals(PermissionEnd.PARTLY, end)
        assertEquals(listOf("I need your contacts to find Mila."), voice.handOvers)
        assertEquals(listOf(listOf("android.permission.READ_CONTACTS")), support.asked)
        assertEquals(1, support.releases)
    }

    @Test
    fun leavingKestrelsOwnScreenWaitsAtMostThreeSeconds() = runTest {
        support.kestrelPolls = 2
        assertTrue(channel.leaveKestrel())

        support.kestrelPolls = Int.MAX_VALUE
        val start = currentTime
        assertFalse(channel.leaveKestrel())
        assertEquals(OwnerPort.KESTREL_LEAVE_MILLIS, currentTime - start)
    }

    private fun sending() = Confirmation("Send it to Mila?", "Shall I send it to Mila?", Category.SENDING, listOf("Mila"), "Viber", null, null, null)

    private data class Asked(val number: Int, val text: String, val kind: QuestionKind, val voice: Boolean)

    private class FakeVoice : VoicePath {
        override val ready = MutableStateFlow(true)
        val said = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val asked = mutableListOf<Asked>()
        val handOvers = mutableListOf<String>()
        val withdrawn = mutableListOf<Int>()

        // While [holds], each speech lasts until the test ends it, through [ends] in order.
        var holds = false
        val ends = ArrayDeque<CompletableDeferred<Unit>>()
        override var speechStops = 0L

        // As Listening on the Orchestrator's StopSaying: the speech that plays ends, and the count goes up.
        fun stop() {
            speechStops++
            ends.removeFirstOrNull()?.complete(Unit)
        }

        override suspend fun say(text: String) {
            said += text
            if (holds) CompletableDeferred<Unit>().also(ends::addLast).await()
        }

        override fun show(text: String) {
            shown += text
        }

        override fun ask(question: Int, text: String, kind: QuestionKind, voice: Boolean) {
            asked += Asked(question, text, kind, voice)
        }

        override fun handOver(text: String) {
            handOvers += text
        }

        override fun withdraw(question: Int) {
            withdrawn += question
        }
    }

    private class FakeSupport : OwnerScreen, OtherAudio, HandOverSignals, PermissionDialog {
        var card = CompletableDeferred<Boolean?>()
        val cards = mutableListOf<String>()
        var releases = 0
        var playing = false
        var pauseStops = true
        var kestrelPolls = 0
        val opened = mutableListOf<HandOverKind>()
        val endedByItself = CompletableDeferred<Unit>()
        var android = PermissionEnd.GRANTED
        val asked = mutableListOf<List<String>>()

        override suspend fun card(question: String): Boolean? {
            cards += question
            return card.await()
        }

        override suspend fun releaseBar() {
            releases++
        }

        override suspend fun kestrelInFront(): Boolean = (kestrelPolls > 0).also { if (it) kestrelPolls-- }

        override fun playing(): Boolean = playing

        override suspend fun pause() {
            if (pauseStops) playing = false
        }

        override suspend fun opened(kind: HandOverKind) {
            opened += kind
        }

        override suspend fun endedByItself(kind: HandOverKind) {
            if (kind == HandOverKind.ACCESS) endedByItself.await() else awaitCancellation()
        }

        override suspend fun ask(permissions: List<String>): PermissionEnd {
            asked += permissions
            return android
        }
    }
}
