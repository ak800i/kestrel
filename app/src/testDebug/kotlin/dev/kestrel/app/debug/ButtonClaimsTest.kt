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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The hooks' claims of their presses of the goal notification's buttons (plan 7G): goal-control's tap in the shade and its
 * `send-later`, whose broadcast may reach a new process, keep the hooks' mark, and the owner's own tap takes it; a claim is
 * used once, and it expires.
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

    private fun claims() = ButtonClaims(file, tags::claim, clock = { now }, next = { ++numbers })

    // A broadcast of [action]'s button reaches the receiver, which takes its number through [claims], and the conversation
    // gets its input, as GoalActionReceiver gives it.
    private fun press(claims: ButtonClaims, action: GoalAction, notice: GoalNotice = held) {
        val number = claims.take(action, goal)
        val input = checkNotNull(GoalButtons.input(action.broadcast, goal, notice, InputChannel.VOICE, number, 0))
        tags.onConversationStep(ConversationEvent.Input(input), marked, MachineStep(marked, emptyList()))
    }

    // goal-control's tap in the shade: its Continue and its Stop are the hooks', and the owner's tap after them is his.
    @Test
    fun goalControlsTapInTheShadeKeepsTheMarkAndTheOwnersTapTakesIt() {
        val claims = claims()
        claims.arm(GoalAction.CONTINUE, goal)
        press(claims, GoalAction.CONTINUE)
        claims.arm(GoalAction.STOP, goal)
        press(claims, GoalAction.STOP, working)
        assertEquals(emptyList<ConversationId>(), gone)

        press(claims, GoalAction.CONTINUE)
        assertEquals(listOf(marked.id), gone)
    }

    // send-later's alarm reaches a new process: the claim that it saved is read there once, the file goes, and the press is
    // the hooks'; a later tap there is the owner's.
    @Test
    fun sendLatersClaimReachesTheNewProcessOnceAndKeepsTheMark() {
        claims().armSaved(GoalAction.CANCEL, goal, dueMillis = 5_000)
        assertTrue(file.isFile)

        now += 6_000
        val restarted = claims()
        assertFalse(file.exists())
        press(restarted, GoalAction.CANCEL)
        assertEquals(emptyList<ConversationId>(), gone)

        press(restarted, GoalAction.CANCEL)
        assertEquals(listOf(marked.id), gone)
        assertFalse(file.exists())
        // A third process finds nothing.
        press(claims(), GoalAction.CANCEL)
        assertEquals(listOf(marked.id, marked.id), gone)
    }

    // A claim counts only for its button and its goal, is used once, expires, and a press that never happened takes it back.
    @Test
    fun aClaimIsUsedOnceForItsButtonAndGoalAndItExpires() {
        val claimed = mutableListOf<Long>()
        fun claims() = ButtonClaims(file, { claimed += it }, clock = { now }, next = { ++numbers })
        val claims = claims()
        claims.arm(GoalAction.CONTINUE, goal)
        assertEquals(1L, claims.take(GoalAction.CANCEL, goal))
        assertEquals(2L, claims.take(GoalAction.CONTINUE, NoticeGoal(checkNotNull(marked.id), 2)))
        assertEquals(3L, claims.take(GoalAction.CONTINUE, goal))
        assertEquals(4L, claims.take(GoalAction.CONTINUE, goal))
        assertEquals(listOf(3L), claimed)

        claims.arm(GoalAction.CONTINUE, goal)
        now += ButtonClaims.LIFE_MILLIS + 1
        claims.take(GoalAction.CONTINUE, goal)

        val armed = claims.arm(GoalAction.CONTINUE, goal)
        claims.disarm(armed)
        claims.take(GoalAction.CONTINUE, goal)

        val saved = claims.armSaved(GoalAction.CONTINUE, goal, dueMillis = 5_000)
        claims.disarm(saved)
        assertFalse(file.exists())
        claims.armSaved(GoalAction.CONTINUE, goal, dueMillis = 5_000)
        now += 5_000 + ButtonClaims.LIFE_MILLIS + 1
        claims().take(GoalAction.CONTINUE, goal)
        assertFalse(file.exists())
        assertEquals(listOf(3L), claimed)
    }
}