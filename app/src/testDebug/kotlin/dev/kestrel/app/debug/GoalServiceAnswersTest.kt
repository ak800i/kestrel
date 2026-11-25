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

/** The answer of the hook goal-service-state (plan 7E): kinds and counts, never the notification's words. */
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
        val focus = FocusResult(granted = true, speaking = true, types = setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK))

        val answer = GoalServiceAnswers.state(status, setOf(ForegroundType.SPECIAL_USE), focus)

        assertEquals(
            "service=running notice=working posted=none actions=stop,open progress=3/7 types=special-use+media-playback " +
                "applied=special-use android=running androidTypes=special-use starts=2 refusals=0 focus=granted focusBy=speak " +
                "focusTypes=special-use+media-playback",
            answer,
        )
        assertFalse(answer.contains("Mila"))
        // After a refusal: no service, the held goal posted without it, and its buttons; a control request without a service.
        val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", "Tap Continue in Kestrel's notification.", null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE))
        assertEquals(
            "service=stopped notice=none posted=held actions=cancel,continue progress=none types=none applied=none android=none " +
                "androidTypes=none starts=1 refusals=1 focus=failed focusBy=focus focusTypes=none",
            GoalServiceAnswers.state(GoalServiceStatus(ServiceState.STOPPED, null, held, null, null, 1, 1), null, FocusResult(false, false, null)),
        )
    }
}
