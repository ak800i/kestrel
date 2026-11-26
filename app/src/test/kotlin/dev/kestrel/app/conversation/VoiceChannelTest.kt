package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    private var taps = 0L

    private val offSpoken = mutableListOf<String>()
    private val offAsked = mutableListOf<Long>()
    private var offStops = 0
    private var offEnds = 0
    private var offHeld: CompletableDeferred<Unit>? = null
    private val offVoice = object : OffVoice {
        override val stops: Long get() = offStops.toLong()

        // As AppOffVoice: words that a stop came after are never spoken.
        override suspend fun speak(text: String, asked: Long) {
            offAsked += asked
            if (asked != stops) return
            offSpoken += text
            offHeld?.await()
        }

        override fun stop() {
            offStops++
        }

        // As AppOffVoice: turning Kestrel on ends the words that it speaks now.
        override fun endSpeech() {
            offEnds++
            offHeld?.complete(Unit)
        }
    }

    // The voice's cards go through the real desk, which numbers each tap with the next of the owner's inputs.
    private fun TestScope.channel(): VoiceChannel {
        val desk = CardDesk(support, backgroundScope, inputIds = { ++taps })
        return VoiceChannel(voice, support, desk, support, support, support, offVoice, audioSettleMillis = AUDIO_SETTLE_MILLIS)
    }

    // As the engine: it hands each output over as it emits it ([VoiceChannel.take]), and shows them one after another.
    private fun TestScope.emitted(channel: VoiceChannel, vararg outputs: ChannelOutput): Job {
        val slots = outputs.map { it to channel.take() }
        return launch { for ((output, slot) in slots) slot.show(output) }
    }

    @Test
    fun kestrelsWordsAndItsFinalReplyAreSpokenAndShownWordsOnlyShown() = runTest {
        val channel = channel()
        channel.show(ChannelOutput.Said("Android gave Kestrel only the photos that you selected."))
        channel.show(ChannelOutput.Report(3, "Done."))
        channel.show(ChannelOutput.Shown("Got it."))

        assertEquals(listOf("Android gave Kestrel only the photos that you selected.", "Done."), voice.said)
        // Only the final reply opens the follow-up window (design 6.6 of sub-project 7).
        assertEquals(listOf(false, true), voice.finals)
        assertEquals(listOf("Got it."), voice.shown)
    }

    // The engine says "I got 'X' too late" at once, also while a goal's final words still play (plan 7B): each spoken
    // output waits until the one before it has ended, in the order in which they came.
    @Test
    fun spokenOutputsPlayOneAfterAnotherInTheOrderInWhichTheyCame() = runTest {
        val channel = channel()
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

    // Decision 10: a stop ends Kestrel's speech at once, also the spoken outputs that wait for their turn in the engine,
    // such as the "too late" sentence behind a goal's final words; only the outputs that come after the stop play.
    @Test
    fun aStopDropsTheSpokenOutputsThatWaitAndOnlyLaterOnesPlay() = runTest {
        val channel = channel()
        val tooLate = ConversationTexts.cameTooLate(SteeringMessage.of("and to Ana", InputChannel.VOICE))
        voice.holds = true
        val shown = emitted(channel, ChannelOutput.Report(3, "Done."), ChannelOutput.Said(tooLate))
        runCurrent()

        voice.stop()
        runCurrent()
        assertTrue(shown.isCompleted)
        assertEquals(listOf("Done."), voice.said)

        val after = emitted(channel, ChannelOutput.Said("Stopped."))
        runCurrent()
        assertEquals(listOf("Done.", "Stopped."), voice.said)
        voice.ends.removeFirst().complete(Unit)
        runCurrent()
        assertTrue(after.isCompleted)
    }

    // Decision 10: outputs that the engine emits after a stop all play.
    @Test
    fun outputsThatCameAfterAStopAllPlay() = runTest {
        val channel = channel()
        channel.stopSpeech()
        emitted(channel, ChannelOutput.Said("One."), ChannelOutput.Report(3, "Two."))
        runCurrent()

        assertEquals(listOf("One.", "Two."), voice.said)
    }

    @Test
    fun aQuestionIsAskedWheneverAVoiceSessionOpensAndLetsTheBarGoOnceWithdrawn() = runTest {
        val channel = channel()
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
        val channel = channel()
        val confirmation = sending()
        val asking = async { channel.ask(ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)) }
        runCurrent()
        assertEquals(listOf(Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true)), voice.asked)
        assertEquals(listOf(OwnerCard(5, "Send it to Mila?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 5, kind = QuestionKind.CONFIRM)), support.cards)

        support.tap(CardChoice.Confirm)
        runCurrent()
        // The card's answer carries the number of the owner's tap, which the desk took (plan 7F).
        assertEquals(CardAnswer(ChannelAnswer.Yes, act = 1), asking.await())
        assertEquals(1, support.releases)
    }

    @Test
    fun anotherAppsAudioLeavesOnlyTheCardUnlessKestrelPausedIt() = runTest {
        val channel = channel()
        val confirmation = sending()
        val question = ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)

        // The audio goes on although Kestrel pressed pause: only the card's button counts.
        support.playing = true
        support.pauseStops = false
        val cardOnly = async { channel.ask(question) }
        afterTheAudioSettled()
        assertEquals(Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = false), voice.asked.last())
        support.tap(CardChoice.Cancel)
        assertEquals(CardAnswer(ChannelAnswer.No, act = 1), cardOnly.await())

        // Kestrel paused it: the voice counts again, and Kestrel says so first.
        support.pauseStops = true
        val voiced = async { channel.ask(question) }
        afterTheAudioSettled()
        assertEquals(Asked(5, "${VoiceChannel.AUDIO_PAUSED} Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true), voice.asked.last())
        voiced.cancel()

        // The screenshot question does the same, and has no card while the voice may answer.
        support.playing = true
        val screenshot = async { channel.ask(ChannelQuestion(6, "May I take a screenshot?", QuestionKind.SCREENSHOT)) }
        afterTheAudioSettled()
        assertEquals(Asked(6, "${VoiceChannel.AUDIO_PAUSED} May I take a screenshot?", QuestionKind.SCREENSHOT, voice = true), voice.asked.last())
        assertEquals(2, support.cards.size)
        screenshot.cancel()
    }

    @Test
    fun aHandOverSaysItsSentenceOpensItsPageAndEndsByItselfOnlyForItsKind() = runTest {
        val channel = channel()
        val handing = async { channel.handOver(HandOver.of(HandOverKind.ACCESS)) }
        runCurrent()
        assertEquals(listOf(HandOver.of(HandOverKind.ACCESS).text), voice.handOvers)
        assertEquals(listOf(HandOverKind.ACCESS), support.opened)
        assertFalse(handing.isCompleted)

        support.endedByItself.complete(Unit)
        assertEquals(HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null), handing.await())
        assertEquals(1, support.releases)
    }

    @Test
    fun kestrelsOwnPermissionSaysWhyAndAndroidsDialogAnswersIt() = runTest {
        val channel = channel()
        support.android = PermissionEnd.PARTLY

        val end = channel.askPermission(listOf("android.permission.READ_CONTACTS"), "I need your contacts to find Mila.")

        assertEquals(PermissionEnd.PARTLY, end)
        assertEquals(listOf("I need your contacts to find Mila."), voice.handOvers)
        assertEquals(listOf(listOf("android.permission.READ_CONTACTS")), support.asked)
        assertEquals(1, support.releases)
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

    // Design 11 of sub-project 7: Kestrel's own voice speaks only a typed conversation's words, and only while Kestrel is
    // off at their turn; a spoken conversation's words go to the voice session, whose Rules only show them while Kestrel is
    // off (plan 7F).
    @Test
    fun onlyATypedConversationsWordsWhileKestrelIsOffAreSpokenByItsOwnVoice() = runTest {
        val channel = channel()
        channel.show(ChannelOutput.Said("Done.", typedCopy = true))
        assertEquals(listOf("Done."), offSpoken)
        assertTrue(voice.said.isEmpty())

        channel.show(ChannelOutput.Said("Sent."))
        assertEquals(listOf("Sent."), voice.said)
        voice.kestrelOn = true
        channel.show(ChannelOutput.Said("Opened.", typedCopy = true))
        assertEquals(listOf("Sent.", "Opened."), voice.said)
        assertEquals(1, offSpoken.size)
    }

    // Plan 7F: the owner's Stop on the screen or on the notification is the same stop as a spoken one: the voice session's
    // speech stops, Kestrel's own voice stops, and the words that wait are not spoken.
    @Test
    fun aStopStopsKestrelsSpeechAndDropsItsWaitingWords() = runTest {
        val channel = channel()
        val held = CompletableDeferred<Unit>()
        offHeld = held
        emitted(channel, ChannelOutput.Said("One.", typedCopy = true), ChannelOutput.Said("Two.", typedCopy = true))
        runCurrent()

        channel.stopSpeech()
        held.complete(Unit)
        runCurrent()

        assertEquals(listOf("One."), offSpoken)
        assertEquals(1, offStops)
        assertEquals(listOf("came 1", "came 2", "stop"), voice.order)
    }

    // Plan 7F: turning Kestrel on is a switch of voice, not a stop: Kestrel's own voice ends what it speaks, so that only
    // one voice speaks, and the typed conversation's words that wait go to the voice session.
    @Test
    fun turningKestrelOnEndsTheOwnVoiceAndTheWaitingWordsGoToTheSession() = runTest {
        val channel = channel()
        val held = CompletableDeferred<Unit>()
        offHeld = held
        val shown = emitted(channel, ChannelOutput.Said("One.", typedCopy = true), ChannelOutput.Said("Two.", typedCopy = true))
        runCurrent()
        assertEquals(listOf("One."), offSpoken)

        voice.turnOn()
        runCurrent()

        assertTrue(shown.isCompleted)
        assertEquals(1, offEnds)
        assertEquals(0, offStops)
        assertEquals(listOf("One."), offSpoken)
        assertEquals(listOf("Two."), voice.said)
    }

    // Plan 7F: Kestrel's own voice counts every stop of Kestrel's speech, as the voice session does: also the
    // Orchestrator's own, as the wake word's.
    @Test
    fun kestrelsOwnVoiceCountsEveryStopOfKestrelsSpeech() = runTest {
        val channel = channel()
        val held = CompletableDeferred<Unit>()
        offHeld = held
        emitted(channel, ChannelOutput.Said("One.", typedCopy = true), ChannelOutput.Said("Two.", typedCopy = true))
        runCurrent()

        voice.stop()
        held.complete(Unit)
        runCurrent()

        assertEquals(listOf("One."), offSpoken)
        assertEquals(1, offStops)
    }

    // Plan 7F: the count of the owner's Stops for Kestrel's own voice is that voice's own; the channel hands it the count
    // that held when the words came, so that a Stop after the channel's check still keeps them unspoken.
    @Test
    fun kestrelsOwnVoiceGetsTheStopCountThatHeldWhenTheWordsCame() = runTest {
        val channel = channel()
        channel.show(ChannelOutput.Said("One.", typedCopy = true))
        channel.stopSpeech()
        channel.show(ChannelOutput.Said("Two.", typedCopy = true))

        assertEquals(listOf("One.", "Two."), offSpoken)
        assertEquals(listOf(0L, 1L), offAsked)
    }

    // Plan 7F: the same rule for the voice session: the words take their arrival when the engine emits them, before they
    // wait for their turn, so that the Orchestrator learns of them before a stop that comes while they wait.
    @Test
    fun theVoiceSessionsWordsTakeTheirArrivalWhenTheyCome() = runTest {
        val channel = channel()
        voice.holds = true
        val waiting = emitted(channel, ChannelOutput.Said("One."), ChannelOutput.Report(3, "Two."))
        runCurrent()

        channel.stopSpeech()
        runCurrent()
        voice.holds = false
        emitted(channel, ChannelOutput.Said("Three."))
        runCurrent()

        assertTrue(waiting.isCompleted)
        assertEquals(listOf("came 1", "came 2", "says 1", "stop", "says 2", "came 3", "says 3"), voice.order)
        assertEquals(listOf("One.", "Three."), voice.said)
    }

    // Plan 7F: the owner's typed answer, with Kestrel on, cancels the typed question's spoken copy, and the channel
    // withdraws that question, which stops its speech; the words after it, and words that ended by themselves, withdraw
    // nothing.
    @Test
    fun aCancelledQuestionsCopyWithdrawsItsQuestionAlone() = runTest {
        val channel = channel()
        voice.holds = true
        val copy = launch { channel.show(ChannelOutput.Said("Which one?", question = 5)) }
        runCurrent()
        assertEquals(listOf<Int?>(5), voice.questions)

        copy.cancel()
        runCurrent()
        assertEquals(listOf(5), voice.withdrawn)

        voice.holds = false
        channel.show(ChannelOutput.Said("Done."))
        channel.show(ChannelOutput.Said("Which one?", question = 6))
        assertEquals(listOf<Int?>(5, null, 6), voice.questions)
        assertEquals(listOf(5), voice.withdrawn)
    }

    // Without a card, as without a screen session, the voice still answers (plan 7F): the question stays spoken, also
    // when a voice session opens again, and the spoken answer, which withdraws the question, ends it.
    @Test
    fun aCardThatGivesNoAnswerLeavesTheQuestionToTheVoice() = runTest {
        val channel = channel()
        val confirmation = sending()
        val asking = async { channel.ask(ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)) }
        runCurrent()
        support.noAnswer()
        runCurrent()
        assertEquals(1, support.cards.size)
        assertFalse(asking.isCompleted)

        voice.ready.value = false
        runCurrent()
        voice.ready.value = true
        runCurrent()
        val spoken = Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true)
        assertEquals(listOf(spoken, spoken), voice.asked)
        assertFalse(asking.isCompleted)
        assertEquals(0, support.releases)
        assertEquals(emptyList<Int>(), voice.withdrawn)

        asking.cancel()
        runCurrent()
        assertEquals(listOf(5), voice.withdrawn)
        assertEquals(1, support.releases)
    }

    // The desk shows its card from the test's background scope, which advanceUntilIdle leaves alone; so the test lets the
    // audio's settling delay pass and then runs everything that is due, the desk's card too.
    private fun TestScope.afterTheAudioSettled() {
        advanceTimeBy(AUDIO_SETTLE_MILLIS)
        runCurrent()
    }

    private fun sending() = Confirmation("Send it to Mila?", "Shall I send it to Mila?", Category.SENDING, listOf("Mila"), "Viber", null, null, null)

    private data class Asked(val number: Int, val text: String, val kind: QuestionKind, val voice: Boolean)

    private class FakeVoice : VoicePath {
        override val ready = MutableStateFlow(true)
        val said = mutableListOf<String>()
        val finals = mutableListOf<Boolean>()
        val shown = mutableListOf<String>()
        val asked = mutableListOf<Asked>()
        val handOvers = mutableListOf<String>()
        val withdrawn = mutableListOf<Int>()

        // While [holds], each speech lasts until the test ends it, through [ends] in order.
        var holds = false
        val ends = ArrayDeque<CompletableDeferred<Unit>>()

        // What reaches the Orchestrator, in its order: the words' arrivals, the stops and the says. As in the Rules, a stop
        // keeps the words that arrived before it unspoken, whenever they are said.
        val order = mutableListOf<String>()
        private var arrived = 0L
        private var stoppedThrough = 0L

        override fun came(): Long = (++arrived).also { order += "came $it" }

        // As the Orchestrator's own stop of Kestrel's speech, as the wake word's: the words that came before it are not
        // spoken, the speech that plays ends, and Kestrel's own voice stops too (onSpeechStopped).
        fun stop() {
            stopsArrivals()
            sayingStopped()
        }

        private fun stopsArrivals() {
            order += "stop"
            stoppedThrough = arrived
        }

        private var speechStopped: () -> Unit = {}

        override fun onSpeechStopped(action: () -> Unit) {
            speechStopped = action
        }

        override var kestrelOn = false
        private var turnedOn: () -> Unit = {}

        override fun onKestrelOn(action: () -> Unit) {
            turnedOn = action
        }

        // As the Orchestrator when Kestrel turns on: Effect.EndOwnVoice, a switch of voice, not a stop.
        fun turnOn() {
            kestrelOn = true
            turnedOn()
        }

        private fun sayingStopped() {
            ends.removeFirstOrNull()?.complete(Unit)
            speechStopped()
        }

        val questions = mutableListOf<Int?>()

        override suspend fun say(text: String, arrival: Long, final: Boolean, question: Int?) {
            order += "says $arrival"
            if (arrival <= stoppedThrough) return
            said += text
            finals += final
            questions += question
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

        // As Listening with the Rules: the owner's Stop keeps the words that came before it unspoken also while Kestrel
        // does not speak, and the Orchestrator stops Kestrel's speech only while it speaks.
        override fun stopSpeaking() {
            stopsArrivals()
            if (ends.isNotEmpty()) sayingStopped()
        }
    }

    private class FakeSupport : OwnerScreen, OtherAudio, HandOverSignals, PermissionDialog {
        private var answer = CompletableDeferred<CardChoice?>()
        val cards = mutableListOf<OwnerCard>()
        override val connections = MutableStateFlow(1)
        var releases = 0
        var playing = false
        var pauseStops = true
        var kestrelPolls = 0
        val opened = mutableListOf<HandOverKind>()
        val endedByItself = CompletableDeferred<Unit>()
        var android = PermissionEnd.GRANTED
        val asked = mutableListOf<List<String>>()

        fun tap(choice: CardChoice) {
            answer.complete(choice)
        }

        // The card's screen session went before an answer: the card gives none.
        fun noAnswer() {
            answer.complete(null)
        }

        override suspend fun card(card: OwnerCard): CardChoice? {
            cards += card
            answer = CompletableDeferred()
            return answer.await()
        }

        override fun locked(): Boolean = false

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

    private companion object {
        const val AUDIO_SETTLE_MILLIS = 500L
    }
}
