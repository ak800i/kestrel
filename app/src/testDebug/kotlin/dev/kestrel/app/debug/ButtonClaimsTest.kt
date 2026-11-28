package dev.kestrel.app.debug

import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalButtons
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.NoticeGoal
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The hooks' claims of their presses of the goal notification's buttons (plan 7G): goal-control's tap in the shade and its
 * `send-later`, whose broadcast may reach a new process, keep the hooks' mark, and the owner's own tap takes it; a claim is
 * armed when its press may have gone to SystemUI, is valid from its broadcast's due time, is used once, expires, survives
 * the end of a process and belongs to one boot of the phone; a press's claim lives only for the press's delivery, and the
 * hook settles it before it answers.
 */
class ButtonClaimsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tags = CommandTags()
    private val gone = mutableListOf<ConversationId>()
    private val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
    private val goal = NoticeGoal(checkNotNull(marked.id), 1)
    private val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", null, null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE), goal)
    private val working = GoalNotice(NoticeKind.WORKING, "Kestrel is working", null, null, listOf(GoalAction.STOP, GoalAction.OPEN), goal)
    private var now = 1_000L
    private var numbers = 0L
    private val file get() = folder.root.resolve(ButtonClaims.FILE)

    init {
        tags.ownerConversation = { gone += it }
    }

    // A process's claims in the boot [boot].
    private fun claims(boot: Int? = BOOT) = ButtonClaims(file, tags::claim, clock = { now }, boot = boot, next = { ++numbers })

    // send-later's claim of [action] for the goal, valid from [fromMillis].
    private fun ButtonClaims.later(action: GoalAction, fromMillis: Long = now): ButtonClaims.Claim =
        (armForNewProcess(action, goal, fromMillis) as ButtonClaims.LaterClaim.Armed).claim

    // A broadcast of [action]'s button reaches the receiver, which takes its number through [claims], and the conversation
    // gets its input, as GoalActionReceiver gives it.
    private fun press(claims: ButtonClaims, action: GoalAction, notice: GoalNotice = held) {
        val number = claims.take(action, goal)
        val input = checkNotNull(GoalButtons.input(action.broadcast, goal, notice, InputChannel.VOICE, number, 0))
        tags.onConversationStep(ConversationEvent.Input(input), marked, MachineStep(marked, emptyList()))
    }

    // goal-control's press in the shade as ScreenActions runs it: [dispatched] says whether the press went to SystemUI,
    // which runs the press's step first, and [answer] what the press then answers or throws.
    private fun shadePress(claims: ButtonClaims, action: GoalAction, dispatched: Boolean, answer: () -> String?): String? {
        val dispatch = claims.pressing(action, goal)
        if (dispatched) dispatch.dispatching()
        return answer()
    }

    // goal-control's tap in the shade: its Continue and its Stop are the hooks', and the owner's tap after them is his.
    @Test
    fun goalControlsTapInTheShadeKeepsTheMarkAndTheOwnersTapTakesIt() {
        val claims = claims()
        shadePress(claims, GoalAction.CONTINUE, dispatched = true) { null }
        press(claims, GoalAction.CONTINUE)
        shadePress(claims, GoalAction.STOP, dispatched = true) { null }
        press(claims, GoalAction.STOP, working)
        assertEquals(emptyList<ConversationId>(), gone)

        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)
    }

    // Re-check of round 11, Important 1: a press that went to SystemUI keeps its claim whatever it answers afterwards, as
    // "the press may not have worked" or a cancellation or an error after the click, so the hooks' broadcast is theirs; a
    // press that never went to SystemUI, refused by its checks or never shown, arms nothing, so the owner's tap is his.
    @Test
    fun aPressThatMayHaveReachedSystemUiKeepsItsClaimAndOneThatNeverWentArmsNone() {
        val claims = claims()
        assertEquals("The press may not have worked.", shadePress(claims, GoalAction.CONTINUE, dispatched = true) { "The press may not have worked." })
        press(claims, GoalAction.CONTINUE)
        val cancelled = runCatching { shadePress(claims, GoalAction.CANCEL, dispatched = true) { throw CancellationException("the hook's timeout") } }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
        press(claims, GoalAction.CANCEL)
        runCatching { shadePress(claims, GoalAction.CONTINUE, dispatched = true) { error("the settle wait failed") } }
        press(claims, GoalAction.CONTINUE)
        assertEquals(emptyList<ConversationId>(), gone)

        shadePress(claims, GoalAction.CONTINUE, dispatched = false) { "The element changed." }
        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)
        // A press that the search never made (not shown) has no step at all; the owner's tap after it is his too.
        press(claims, GoalAction.CANCEL)
        assertEquals(listOf(marked.id, marked.id), gone)
        // The press's step arms once, also when the press runs it twice.
        val twice = claims.pressing(GoalAction.CONTINUE, goal)
        twice.dispatching()
        twice.dispatching()
        press(claims, GoalAction.CONTINUE)
        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id, marked.id, marked.id), gone)
    }

    // Re-check of round 11, Minor 2: a claim is valid only from its broadcast's due time. The owner's tap before the press
    // went to SystemUI, or before send-later's alarm is due, is his, and the hooks' broadcast at its time is still theirs.
    @Test
    fun aClaimIsValidOnlyFromItsBroadcastsDueTime() {
        val claims = claims()
        val dispatch = claims.pressing(GoalAction.CONTINUE, goal)
        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)
        dispatch.dispatching()
        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)

        claims.later(GoalAction.CANCEL, fromMillis = now + 5_000)
        now += 4_999
        press(claims, GoalAction.CANCEL)
        assertEquals(listOf(marked.id, marked.id), gone)
        now += 1
        press(claims, GoalAction.CANCEL)
        assertEquals(listOf(marked.id, marked.id), gone)
    }

    // send-later's alarm reaches a new process: the claim that the file holds is the hooks' there, the file then forgets it,
    // and a later tap there, or in a third process, is the owner's.
    @Test
    fun sendLatersClaimReachesTheNewProcessOnceAndKeepsTheMark() {
        claims().later(GoalAction.CANCEL, fromMillis = now + 5_000)
        assertTrue(file.isFile)

        now += 6_000
        val restarted = claims()
        press(restarted, GoalAction.CANCEL)
        assertEquals(emptyList<ConversationId>(), gone)
        assertFalse(file.exists())

        press(restarted, GoalAction.CANCEL)
        assertEquals(listOf(marked.id), gone)
        press(claims(), GoalAction.CANCEL)
        assertEquals(listOf(marked.id, marked.id), gone)
    }

    // Re-check of round 11, Minor 3: a process that read the file and ended before the broadcast, as in a crash, leaves the
    // claim to the next one, which uses it once; a claim of another boot of the phone is dropped, also when its time is near;
    // the file is replaced in one step and leaves no temporary file.
    @Test
    fun theFileKeepsAClaimUntilItIsUsedOrExpiresAndOnlyForItsBoot() {
        claims().later(GoalAction.CANCEL, fromMillis = now + 5_000)
        now += 1_000
        // This process reads the file and ends before the broadcast.
        claims()
        now += 5_000
        val third = claims()
        press(third, GoalAction.CANCEL)
        assertEquals(emptyList<ConversationId>(), gone)
        press(claims(), GoalAction.CANCEL)
        assertEquals(listOf(marked.id), gone)

        // After a restart of the phone, the elapsed realtime starts again, so the same time names another moment.
        claims().later(GoalAction.CANCEL, fromMillis = now)
        val rebooted = claims(boot = BOOT + 1)
        assertFalse(file.exists())
        press(rebooted, GoalAction.CANCEL)
        assertEquals(listOf(marked.id, marked.id), gone)

        val claims = claims()
        val takenBack = claims.later(GoalAction.CONTINUE)
        claims.later(GoalAction.CANCEL)
        claims.disarm(takenBack)
        assertEquals(listOf(ButtonClaims.FILE), folder.root.list()?.toList())
        now += ButtonClaims.LATER_MILLIS + 1
        press(claims(), GoalAction.CANCEL)
        assertFalse(file.exists())
        assertEquals(List(3) { marked.id }, gone)
    }

    // Re-check of round 12, Minor A: a gesture that Android refused reached nothing, so its claim is taken back and the
    // owner's tap after it is his; a press whose node action answered no keeps its claim also when its gesture after that
    // is refused, because the app may have acted on the action.
    @Test
    fun aRefusedGestureTakesItsClaimBackUnlessAnEarlierHandOverMayHaveReachedSystemUi() {
        val claims = claims()
        val refused = claims.pressing(GoalAction.CONTINUE, goal)
        refused.dispatching()
        refused.notDispatched()
        assertFalse(file.exists())
        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)

        val actionThenGesture = claims.pressing(GoalAction.CONTINUE, goal)
        actionThenGesture.dispatching()
        actionThenGesture.dispatching()
        actionThenGesture.notDispatched()
        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)
    }

    // Re-check of round 12, Important B: without a boot count, no claim of the file is trusted, as one that an earlier
    // process wrote, and the file is deleted; claims stay in this process's memory, where a broadcast still uses them, and
    // reach no new process.
    @Test
    fun withoutABootCountClaimsLiveInTheProcessOnly() {
        claims().later(GoalAction.CANCEL)
        assertTrue(file.isFile)

        val unbooted = claims(boot = null)
        assertFalse(file.exists())
        press(unbooted, GoalAction.CANCEL)
        assertEquals(listOf(marked.id), gone)

        unbooted.pressing(GoalAction.CONTINUE, goal).dispatching()
        assertFalse(file.exists())
        press(unbooted, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)
        unbooted.pressing(GoalAction.CANCEL, goal).dispatching()
        assertFalse(file.exists())
        press(claims(boot = null), GoalAction.CANCEL)
        assertEquals(listOf(marked.id, marked.id), gone)
    }

    // Re-check of round 13, Minor 2: a claim for a new process, as send-later's, needs the boot count. Without one, the
    // arming refuses by itself and arms nothing, so the alarm's broadcast would count as the owner's and the hook refuses
    // first; with one, it arms the claim from its due time in the file.
    @Test
    fun aClaimForANewProcessIsRefusedWithoutABootCount() {
        val unbooted = claims(boot = null)
        assertEquals(ButtonClaims.LaterClaim.NoBootCount, unbooted.armForNewProcess(GoalAction.CANCEL, goal, fromMillis = now))
        assertFalse(file.exists())
        press(unbooted, GoalAction.CANCEL)
        assertEquals(listOf(marked.id), gone)

        val armed = claims().armForNewProcess(GoalAction.CANCEL, goal, fromMillis = now + 5_000)
        assertEquals(ButtonClaims.LaterClaim.Armed(ButtonClaims.Claim(GoalAction.CANCEL, goal, BOOT, now + 5_000, now + 5_000 + ButtonClaims.LATER_MILLIS)), armed)
        assertTrue(file.isFile)
        now += 5_000
        press(claims(), GoalAction.CANCEL)
        assertEquals(listOf(marked.id), gone)
    }

    // A claim counts only for its button and its goal, is used once, and expires.
    @Test
    fun aClaimIsUsedOnceForItsButtonAndGoalAndItExpires() {
        val claimed = mutableListOf<Long>()
        val claims = ButtonClaims(file, { claimed += it }, clock = { now }, boot = BOOT, next = { ++numbers })
        claims.later(GoalAction.CONTINUE)
        assertEquals(1L, claims.take(GoalAction.CANCEL, goal))
        assertEquals(2L, claims.take(GoalAction.CONTINUE, NoticeGoal(checkNotNull(marked.id), 2)))
        assertEquals(3L, claims.take(GoalAction.CONTINUE, goal))
        assertEquals(4L, claims.take(GoalAction.CONTINUE, goal))
        assertEquals(listOf(3L), claimed)

        claims.later(GoalAction.CONTINUE)
        now += ButtonClaims.LATER_MILLIS + 1
        claims.take(GoalAction.CONTINUE, goal)
        assertFalse(file.exists())
        assertEquals(listOf(3L), claimed)
    }

    // The whole-plan review of plan 7G, I1: a press whose broadcast never came, as an uncertain press that SystemUI did not
    // act on, keeps no claim past the hook's answer. tap settles it before it answers and drops it, so the owner's own tap
    // 3 s later, or at once, is his, and the file holds no claim of it.
    @Test
    fun aPressThatNoBroadcastUsedIsDroppedBeforeTheHookAnswers() = runTest {
        val claims = claims()
        val hooksPress = claims.pressing(GoalAction.CONTINUE, goal)
        hooksPress.dispatching()
        assertEquals(ButtonClaims.Settled.DROPPED, hooksPress.settle())
        assertEquals(ButtonClaims.PRESS_MILLIS, currentTime)
        assertFalse(file.exists())

        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)
        now += 3_000
        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id, marked.id), gone)
    }

    // I1 too: the hook waits for the broadcast only until a broadcast uses the claim, and answers used; a press that armed
    // none, never handed over or whose only gesture Android refused, answers none at once.
    @Test
    fun aPressSettlesAsUsedOnceItsBroadcastComesAndAsNoneWhenItArmedNone() = runTest {
        val claims = claims()
        val hooksPress = claims.pressing(GoalAction.STOP, goal)
        hooksPress.dispatching()
        val settled = async { hooksPress.settle() }
        advanceTimeBy(800)
        runCurrent()
        assertFalse(settled.isCompleted)
        press(claims, GoalAction.STOP, working)
        assertEquals(ButtonClaims.Settled.USED, settled.await())
        assertEquals(800L, currentTime)
        assertEquals(emptyList<ConversationId>(), gone)

        assertEquals(ButtonClaims.Settled.NONE, claims.pressing(GoalAction.CONTINUE, goal).settle())
        val refused = claims.pressing(GoalAction.CONTINUE, goal)
        refused.dispatching()
        refused.notDispatched()
        assertEquals(ButtonClaims.Settled.NONE, refused.settle())
        assertEquals(800L, currentTime)
    }

    // I1 too: a press's claim lives PRESS_MILLIS after the press's last hand-over, also in the file that a new process
    // reads after a crash, and no longer: a node's action and then a gesture 2 s later keep it 3 s past the gesture.
    @Test
    fun aPressesClaimEndsPressMillisAfterItsLastHandOver() {
        val claims = claims()
        val hooksPress = claims.pressing(GoalAction.CANCEL, goal)
        hooksPress.dispatching()
        now += 2_000
        hooksPress.dispatching()
        now += ButtonClaims.PRESS_MILLIS
        // The process ended before the broadcast; the next one reads the claim with its end.
        press(claims(), GoalAction.CANCEL)
        assertEquals(emptyList<ConversationId>(), gone)

        claims().pressing(GoalAction.CANCEL, goal).dispatching()
        now += ButtonClaims.PRESS_MILLIS + 1
        press(claims(), GoalAction.CANCEL)
        assertEquals(listOf(marked.id), gone)
        assertFalse(file.exists())
    }

    private companion object {
        const val BOOT = 7
    }
}