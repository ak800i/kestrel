package dev.kestrel.app.goal

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The goal notification's words and buttons (design 10.6 of sub-project 7; plan 7E). */
class GoalNoticesTest {
    private val working = listOf(GoalAction.STOP, GoalAction.OPEN)
    private val id = ConversationId("0123456789abcdef0123456789abcdef")
    private val goal = NoticeGoal(id, 2)

    // The open goal of record 2 in the conversation [id], in [phase].
    private fun view(
        phase: GoalPhase,
        hold: GoalHold? = null,
        checklist: ChecklistSummary? = null,
        waitsBehindCommand: Boolean = false,
    ) = ConversationView(id = id, goal = phase, hold = hold, goalRecord = 2, checklist = checklist, waitsBehindCommand = waitsBehindCommand)

    @Test
    fun aWorkingGoalShowsItsCurrentItemAndProgressWithStopAndOpen() {
        val notice = GoalNotices.of(view(GoalPhase.ACTING, checklist = ChecklistSummary(3, 7, "Reply to Mila")))
        assertEquals(GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Reply to Mila", NoticeProgress(3, 7), working, goal), notice)
        // The item may hold a name, so the notice prints none.
        assertFalse(notice.toString().contains("Mila"))

        // Without a checklist there is neither an item nor a bar; a goal that asks or has handed a step over still works.
        val bare = GoalNotice(NoticeKind.WORKING, "Kestrel is working", null, null, working, goal)
        assertEquals(bare, GoalNotices.of(view(GoalPhase.ASKING)))
        assertEquals(bare, GoalNotices.of(view(GoalPhase.HANDED_OVER, checklist = ChecklistSummary(0, 0, null))))
        assertNull(GoalNotices.of(ConversationView()))
    }

    // Plan 7E: the bar belongs to the owner's instant command while the round waits behind it, so the notification says
    // why the goal does not move, in place of the current item; the progress and the buttons stay.
    @Test
    fun aRoundThatWaitsBehindTheOwnersCommandSaysSoInPlaceOfItsItem() {
        val waiting = view(GoalPhase.ACTING, checklist = ChecklistSummary(3, 7, "Reply to Mila"), waitsBehindCommand = true)
        assertEquals(
            GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Waiting for your command to finish", NoticeProgress(3, 7), working, goal),
            GoalNotices.of(waiting),
        )
    }

    @Test
    fun aHeldGoalAsksToContinueAndSaysHowForItsHoldWithCancelFirst() {
        // Cancel first: a tap meant as Stop, while a touch holds the goal, lands on Cancel (the owner's decision).
        val held = listOf(GoalAction.CANCEL, GoalAction.CONTINUE)
        assertEquals(
            GoalNotice(NoticeKind.HELD, "Continue the goal?", ConversationTexts.GO_ON, null, held, goal),
            GoalNotices.of(view(GoalPhase.PAUSED, GoalHold.LOCK, ChecklistSummary(3, 7, "Reply to Mila"))),
        )
        assertEquals(ConversationTexts.GO_ON, GoalNotices.of(ConversationView(goal = GoalPhase.PAUSED_AT_LIMIT, hold = GoalHold.LIMIT))?.text)
        assertEquals(ConversationTexts.LEAVE_KESTREL, GoalNotices.of(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.KESTREL_SCREEN))?.text)
        assertEquals(ConversationTexts.OPEN_AND_CONTINUE, GoalNotices.of(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED))?.text)
    }

    // An open goal always has its conversation and record; one without them could carry buttons that never count, so its
    // notice shows none (the whole-plan review's nit).
    @Test
    fun aGoalWithoutItsNameShowsNoButtons() {
        assertEquals(
            GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Reply to Mila", NoticeProgress(3, 7), emptyList()),
            GoalNotices.of(ConversationView(goal = GoalPhase.ACTING, checklist = ChecklistSummary(3, 7, "Reply to Mila"))),
        )
        assertEquals(emptyList<GoalAction>(), GoalNotices.of(ConversationView(id = id, goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH))?.actions)
    }

    @Test
    fun eachButtonGivesItsOwnInputAndOpenSendsNoBroadcast() {
        assertEquals(GoalAction.CONTINUE, GoalAction.ofBroadcast(GoalAction.CONTINUE.broadcast))
        // Design 6.4: the notification's Stop and the held goal's Cancel both stop the goal.
        assertEquals(InputIntent.Stop, GoalAction.ofBroadcast(GoalAction.CANCEL.broadcast)?.intent)
        assertEquals(InputIntent.Stop, GoalAction.ofBroadcast(GoalAction.STOP.broadcast)?.intent)
        assertNull(GoalAction.ofBroadcast(GoalAction.OPEN.broadcast))
        assertNull(GoalAction.ofBroadcast("dev.kestrel.listen.STOP"))
        assertNull(GoalAction.ofBroadcast(null))
    }

    // Plan 7E with plan 7D's ScreenTurns: a tap on a button that the notification shows is the owner's input of that
    // moment, with the number and time that the tap took on its arrival, so that a Continue that resumes the goal has an
    // instant command that he started before the tap, and waits for one that he started after it.
    @Test
    fun aShownButtonIsTheOwnersNewestInputAndAStaleContinueIsNothing() {
        val held = GoalNotices.of(view(GoalPhase.INTERRUPTED, GoalHold.TOUCH))

        assertEquals(
            OwnerInput(42, InputChannel.TEXT, InputIntent.Continue, 7),
            GoalButtons.input(GoalAction.CONTINUE.broadcast, goal, held, InputChannel.TEXT, 42, 7),
        )
        assertEquals(
            OwnerInput(43, InputChannel.VOICE, InputIntent.Stop, 8),
            GoalButtons.input(GoalAction.CANCEL.broadcast, goal, held, InputChannel.VOICE, 43, 8),
        )
        // A Continue from a notification that the working goal replaced, and Open, which sends no broadcast.
        val working = GoalNotices.of(view(GoalPhase.ACTING))
        assertNull(GoalButtons.input(GoalAction.CONTINUE.broadcast, goal, working, InputChannel.TEXT, 44, 9))
        assertNull(GoalButtons.input(GoalAction.OPEN.broadcast, goal, working, InputChannel.TEXT, 44, 9))
        assertNull(GoalButtons.input(GoalAction.STOP.broadcast, goal, null, InputChannel.TEXT, 44, 9))
    }

    // The review of plan 7E (I2): the owner's pull of the shade is a touch, which holds the goal, so his tap on Stop can
    // arrive after the held notice, which offers Cancel, replaced the working one. Any button that stops counts while the
    // goal's notice offers a way to stop; Continue needs its own button.
    @Test
    fun aStoppingButtonCountsWhileTheGoalsNoticeOffersAnyWayToStop() {
        val held = GoalNotices.of(view(GoalPhase.INTERRUPTED, GoalHold.TOUCH))
        val working = GoalNotices.of(view(GoalPhase.ACTING))

        assertEquals(InputIntent.Stop, GoalButtons.input(GoalAction.STOP.broadcast, goal, held, InputChannel.TEXT, 1, 0)?.intent)
        assertEquals(InputIntent.Stop, GoalButtons.input(GoalAction.CANCEL.broadcast, goal, working, InputChannel.TEXT, 1, 0)?.intent)
        // The notice after the goal's end offers nothing, so nothing stops.
        assertNull(GoalButtons.input(GoalAction.STOP.broadcast, null, GoalNotices.CLOSING, InputChannel.TEXT, 1, 0))
    }

    // The review of plan 7E (M2): each notice names its goal by its conversation and its record, which outlive a resume and
    // Kestrel's process, and a button counts only for that goal: a late tap meant for another goal is nothing.
    @Test
    fun aButtonCountsOnlyForTheGoalThatItsNoticeShowed() {
        val held = GoalNotices.of(view(GoalPhase.INTERRUPTED, GoalHold.TOUCH))
        assertEquals(goal, held?.goal)
        assertNull(GoalNotices.CLOSING.goal)

        assertNull(GoalButtons.input(GoalAction.CONTINUE.broadcast, NoticeGoal(id, 1), held, InputChannel.TEXT, 1, 0))
        assertNull(GoalButtons.input(GoalAction.CANCEL.broadcast, NoticeGoal(ConversationId("fedcba9876543210fedcba9876543210"), 2), held, InputChannel.TEXT, 1, 0))
        assertNull(GoalButtons.input(GoalAction.CANCEL.broadcast, null, held, InputChannel.TEXT, 1, 0))
    }

    // The review of plan 7E (M3): a tap on the held goal's notification that Kestrel posted without the service can start
    // Kestrel's process. The press waits until the conversation is restored, and decides against the restored goal's
    // notice, so the tap is neither lost nor judged against the empty view before the restore; its input keeps the number
    // and time that the tap took on its arrival, before the wait.
    @Test
    fun aTapThatStartsKestrelsProcessWaitsForTheRestoreAndCountsForTheRestoredGoal() = runTest {
        val restored = CompletableDeferred<ConversationView>()
        val pressed = async { GoalButtons.press(GoalAction.CONTINUE.broadcast, goal, { restored.await() }, 42, 7) { _, error -> throw error } }
        runCurrent()
        assertFalse(pressed.isCompleted)

        restored.complete(view(GoalPhase.INTERRUPTED, GoalHold.RESTART).copy(replyChannel = InputChannel.TEXT))

        assertEquals(OwnerInput(42, InputChannel.TEXT, InputIntent.Continue, 7), pressed.await())
    }

    // The second review of plan 7E (R2-M1): a restore that does not end in time would hold the broadcast past Android's
    // limit. The press gives up after RESTORE_WAIT_MILLIS, drops the tap and reports only its kind.
    @Test
    fun aTapWhoseRestoreDoesNotEndInTimeIsDroppedAndReportedByItsKind() = runTest {
        val reports = mutableListOf<String>()
        val pressed = async {
            GoalButtons.press(GoalAction.CANCEL.broadcast, goal, { awaitCancellation() }, 42, 7) { what, _ -> reports += what }
        }
        advanceTimeBy(GoalButtons.RESTORE_WAIT_MILLIS - 1)
        runCurrent()
        assertFalse(pressed.isCompleted)
        advanceTimeBy(2)

        assertNull(pressed.await())
        assertEquals(listOf("A goal notification's button"), reports)
    }

    // The second review of plan 7E (R2-I1): each goal has a key of its own, which its buttons' broadcasts carry as their
    // data, so that Android gives every goal's buttons PendingIntents of their own; the key names the goal again.
    @Test
    fun eachGoalHasAKeyOfItsOwnThatNamesItAgain() {
        assertEquals(goal, NoticeGoal.ofKey(goal.key))
        assertNotEquals(goal.key, NoticeGoal(id, 3).key)
        assertNotEquals(goal.key, NoticeGoal(ConversationId("fedcba9876543210fedcba9876543210"), 2).key)
        assertNull(NoticeGoal.ofKey("0123456789abcdef0123456789abcdef/0"))
        assertNull(NoticeGoal.ofKey("not a goal/2"))
        assertNull(NoticeGoal.ofKey(null))
    }
}
