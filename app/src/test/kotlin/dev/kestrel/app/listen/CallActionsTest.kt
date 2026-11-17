package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Request
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallActionsTest {
    private val api = FakeCallApi()
    private val clicks = mutableListOf<String>()

    // The controls with these labels are on the screen now, and those of them that are on.
    private var controls = setOf("Answer", "Decline")
    private var checked = emptySet<String>()
    private var serviceOn = true
    private val actions = CallActions(api) {
        if (serviceOn) {
            CallScreen { label, ifOff ->
                if (ifOff && label in controls && label in checked) {
                    true
                } else {
                    clicks += label
                    label in controls
                }
            }
        } else {
            null
        }
    }

    @Test
    fun withThePermissionAndroidsCallApiAnswersDeclinesAndHangsUp() = runTest {
        api.permitted = true

        assertNull(actions.perform(Request.Answer(speaker = false)))
        assertNull(actions.perform(Request.Decline))
        assertNull(actions.perform(Request.HangUp))

        assertEquals(listOf("answer", "end", "end"), api.calls)
        // Without the screen, so that it works on the lock screen and with any phone app.
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun withoutThePermissionKestrelClicksTheIncomingCallControls() = runTest {
        assertNull(actions.perform(Request.Answer(speaker = false)))
        assertNull(actions.perform(Request.Decline))

        assertEquals(listOf("Answer", "Decline"), clicks)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun withNeitherThePermissionNorTheServiceKestrelSaysWhy() = runTest {
        serviceOn = false

        assertEquals(ListeningTexts.CALLS_NEED_ACCESS, actions.perform(Request.Answer(speaker = false)))
        assertEquals(ListeningTexts.CALLS_NEED_ACCESS, actions.perform(Request.Decline))
        assertEquals(ListeningTexts.HANG_UP_NEEDS_PERMISSION, actions.perform(Request.HangUp))
        assertEquals(ListeningTexts.CALLS_NEED_SERVICE, actions.perform(Request.Mute))
    }

    @Test
    fun aControlThatDoesNotShowAndACallThatHasEndedAreReported() = runTest {
        controls = emptySet()

        assertEquals(ListeningTexts.CANNOT_ANSWER, actions.perform(Request.Answer(speaker = false)))
        assertEquals(ListeningTexts.CANNOT_DECLINE, actions.perform(Request.Decline))
        // Without the permission, Kestrel says that it needs it only when it finds no "End call" control.
        assertEquals(ListeningTexts.HANG_UP_NEEDS_PERMISSION, actions.perform(Request.HangUp))
        api.permitted = true
        api.ends = false
        assertEquals(ListeningTexts.CANNOT_DECLINE, actions.perform(Request.Decline))
    }

    @Test
    fun answerOnSpeakerTurnsTheSpeakerOnOnceTheCallIsActive() = runTest {
        api.permitted = true
        api.activeAfter = 3

        assertNull(actions.perform(Request.Answer(speaker = true)))

        // The in-call screen does not come to the front after an answer through the API (prototype P3).
        assertEquals(listOf("answer", "active", "active", "active", "show in-call screen"), api.calls)
        assertEquals(listOf("Speaker"), clicks)
    }

    @Test
    fun answerOnSpeakerWithoutTheSpeakerControlSaysSo() = runTest {
        // The call never becomes active.
        api.activeAfter = Int.MAX_VALUE
        assertEquals(ListeningTexts.ANSWERED_WITHOUT_SPEAKER, actions.perform(Request.Answer(speaker = true)))
        // The in-call screen shows no speaker control.
        api.activeAfter = 0
        api.inCallControls = emptySet()
        assertEquals(ListeningTexts.ANSWERED_WITHOUT_SPEAKER, actions.perform(Request.Answer(speaker = true)))
        // The accessibility service is off, and the call API answered.
        api.permitted = true
        serviceOn = false
        assertEquals(ListeningTexts.ANSWERED_WITHOUT_SPEAKER, actions.perform(Request.Answer(speaker = true)))
    }

    @Test
    fun aSpeakerControlThatIsOnAlreadyStaysOn() = runTest {
        api.permitted = true
        // On a phone without an earpiece, such as the emulator, every call starts on the speaker.
        api.inCallChecked = setOf("Speaker")

        assertNull(actions.perform(Request.Answer(speaker = true)))
        assertNull(actions.perform(Request.Speaker))

        assertTrue(clicks.isEmpty())
        assertEquals(listOf("answer", "active", "show in-call screen", "show in-call screen"), api.calls)
    }

    @Test
    fun duringTheCallMuteAndSpeakerPressTheInCallControls() = runTest {
        assertNull(actions.perform(Request.Mute))
        assertNull(actions.perform(Request.Speaker))

        assertEquals(listOf("show in-call screen", "show in-call screen"), api.calls)
        assertEquals(listOf("Mute", "Speaker"), clicks)
        api.inCallControls = emptySet()
        assertEquals(ListeningTexts.CANNOT_MUTE, actions.perform(Request.Mute))
        assertEquals(ListeningTexts.CANNOT_SPEAKER, actions.perform(Request.Speaker))
    }

    @Test
    fun aMuteControlThatIsOnAlreadyStaysOn() = runTest {
        api.inCallChecked = setOf("Mute")

        assertNull(actions.perform(Request.Mute))

        // A second "mute" does not unmute the call.
        assertTrue(clicks.isEmpty())
        assertEquals(listOf("show in-call screen"), api.calls)
    }

    private inner class FakeCallApi : CallApi {
        val calls = mutableListOf<String>()
        var permitted = false
        var ends = true

        // The check of the call state that first finds the call active.
        var activeAfter = 0
        var inCallControls = setOf("Mute", "Speaker")
        var inCallChecked = emptySet<String>()
        private var activeChecks = 0

        override fun allowed() = permitted

        override fun answer() {
            calls += "answer"
        }

        override fun end(): Boolean {
            calls += "end"
            return ends
        }

        override fun active(): Boolean {
            calls += "active"
            return ++activeChecks >= activeAfter
        }

        override fun showInCallScreen() {
            calls += "show in-call screen"
            controls = inCallControls
            checked = inCallChecked
        }
    }
}
