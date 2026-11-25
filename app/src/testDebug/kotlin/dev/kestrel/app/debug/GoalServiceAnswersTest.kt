package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.GoalServiceStatus
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.app.goal.NoticeProgress
import dev.kestrel.app.goal.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The answers of the hooks goal-service-state and goal-control (plan 7E): kinds, counts and flags, never the notification's words. */
class GoalServiceAnswersTest {
    @Test
    fun theAnswerHoldsKindsAndCountsOnly() {
        val notice = GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Reply to Mila", NoticeProgress(3, 7), listOf(GoalAction.STOP, GoalAction.OPEN))
        val status = GoalServiceStatus(
            ServiceState.RUNNING,
            notice,
            null,
            setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK),
            setOf(ForegroundType.SPECIAL_USE),
            2,
            0,
        )
        val focus = FocusRecord(2, 1, FocusResult(granted = true, speaking = true, types = setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK)))

        val answer = GoalServiceAnswers.state(status, setOf(ForegroundType.SPECIAL_USE), focus, refuseArmed = false)

        assertEquals(
            "service=running notice=working posted=none actions=stop,open progress=3/7 types=special-use+media-playback " +
                "applied=special-use android=running androidTypes=special-use starts=2 refusals=0 refuse=none focus=granted focusBy=speak " +
                "focusTypes=special-use+media-playback focusRequests=2 focusAnswered=1",
            answer,
        )
        assertFalse(answer.contains("Mila"))
        // After a refusal: no service, the held goal posted without it, and its buttons; a control request without a service;
        // a refusal armed for the next start.
        val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", "Tap Continue in Kestrel's notification.", null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE))
        assertEquals(
            "service=stopped notice=none posted=held actions=cancel,continue progress=none types=none applied=none android=none " +
                "androidTypes=none starts=1 refusals=1 refuse=armed focus=failed focusBy=focus focusTypes=none focusRequests=1 focusAnswered=1",
            GoalServiceAnswers.state(
                GoalServiceStatus(ServiceState.STOPPED, null, held, null, null, 1, 1),
                null,
                FocusRecord(1, 1, FocusResult(false, false, null)),
                refuseArmed = true,
            ),
        )
        // No request yet.
        assertEquals(
            "service=stopped notice=none posted=none actions=none progress=none types=none applied=none android=none " +
                "androidTypes=none starts=0 refusals=0 refuse=none focus=none focusBy=none focusTypes=none focusRequests=0 focusAnswered=0",
            GoalServiceAnswers.state(GoalServiceStatus(ServiceState.STOPPED, null, null, null, null, 0, 0), null, FocusRecord(0, 0, null), refuseArmed = false),
        )
    }

    @Test
    fun aPressInTheShadeSaysWhetherTheShadeClosedAgain() {
        assertEquals("tapped=continue shade=closed", GoalServiceAnswers.tapped(GoalAction.CONTINUE, shadeClosed = true))
        assertEquals("tapped=stop shade=open", GoalServiceAnswers.tapped(GoalAction.STOP, shadeClosed = false))
        assertEquals(
            "the goal notification's cancel is not in the shade; shade=closed",
            GoalServiceAnswers.notTapped("the goal notification's cancel is not in the shade", shadeClosed = true),
        )
        assertEquals("The element changed; shade=open", GoalServiceAnswers.notTapped("The element changed.", shadeClosed = false))
    }

    @Test
    fun aDisarmSaysWhetherARefusalWasArmed() {
        assertEquals("refuse=disarmed", GoalServiceAnswers.refuseOff(wasArmed = true))
        assertEquals("refuse=none", GoalServiceAnswers.refuseOff(wasArmed = false))
    }
}
