package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.GoalNotification
import dev.kestrel.app.goal.GoalServiceStatus
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.app.goal.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The button's PendingIntent that the hook goal-control do=send-later hands to AlarmManager (plan 7E; the second review's
 * R4-M1): the one on the notification that shows the button now, as Android holds it posted, never one of another of
 * Kestrel's notifications with the same label.
 */
class NoticeIntentsTest {
    @Test
    fun theIntentIsTheButtonsOnTheNotificationThatShowsItNow() {
        val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", "Tap Continue in Kestrel's notification.", null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE))
        val working = GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Reply to Mila", null, listOf(GoalAction.STOP, GoalAction.OPEN))
        val posted = GoalServiceStatus(ServiceState.STOPPED, null, held, null, null, 1, 1)
        val running = GoalServiceStatus(ServiceState.RUNNING, working, null, setOf(ForegroundType.SPECIAL_USE), setOf(ForegroundType.SPECIAL_USE), 1, 0)
        val shown = listOf(
            NoticeIntents.Posted(OTHER_ID, listOf(GoalAction.CANCEL.label to "other-cancel")),
            NoticeIntents.Posted(GoalNotification.POSTED_ID, listOf(GoalAction.CANCEL.label to "held-cancel", GoalAction.CONTINUE.label to "held-continue")),
            NoticeIntents.Posted(GoalNotification.SERVICE_ID, listOf(GoalAction.STOP.label to "working-stop")),
        )

        // The held goal posted without the service: its Cancel, not the Cancel of another of Kestrel's notifications.
        val heldId = NoticeIntents.idOf(posted, GoalAction.CANCEL)
        assertEquals(GoalNotification.POSTED_ID, heldId)
        assertEquals("held-cancel", NoticeIntents.intentOf(shown, checkNotNull(heldId), GoalAction.CANCEL.label))
        // The working goal in the service's notification: its Stop; it offers no Cancel.
        assertEquals("working-stop", NoticeIntents.intentOf(shown, checkNotNull(NoticeIntents.idOf(running, GoalAction.STOP)), GoalAction.STOP.label))
        assertNull(NoticeIntents.idOf(running, GoalAction.CANCEL))
        // A notification that Android no longer shows, or a button that it does not carry, has no intent.
        assertNull(NoticeIntents.intentOf(shown.take(1), GoalNotification.POSTED_ID, GoalAction.CANCEL.label))
        assertNull(NoticeIntents.intentOf(shown, GoalNotification.SERVICE_ID, GoalAction.OPEN.label))
    }

    private companion object {
        const val OTHER_ID = 7_001
    }
}
