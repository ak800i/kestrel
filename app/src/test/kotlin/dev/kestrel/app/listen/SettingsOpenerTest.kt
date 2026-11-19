package dev.kestrel.app.listen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsOpenerTest {
    private val log = mutableListOf<String>()
    private var service = false
    private var role = false
    private var sessionReady = true
    private var sessionFailure: RuntimeException? = null

    private fun TestScope.timeline() = Timeline { testScheduler.currentTime }

    private fun opener(timeline: Timeline) = SettingsOpener(
        timeline,
        serviceOn = { service },
        roleHeld = { role },
        startActivity = { log += "start" },
        showSession = {
            log += "session"
            sessionFailure?.let { throw it }
            sessionReady
        },
        offer = { log += "offer" },
        // AppGraph's reportFailure logs only the error's class (design spec, section 13.5).
        report = { what, error -> log += "report $what: ${error.javaClass.simpleName}" },
    )

    @Test
    fun settingsInFrontCountAsOpenedAndNothingStarts() = runTest {
        val timeline = timeline()
        timeline.settingsResumed()

        assertEquals(SettingsOpening.OPENED, opener(timeline).open())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun withoutTheServiceAndTheRoleKestrelDoesNotTry() = runTest {
        assertEquals(SettingsOpening.NEED_SERVICE, opener(timeline()).open())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun withOnlyTheRoleSettingsThatComeWithinTwoSecondsAreOpened() = runTest {
        role = true
        val timeline = timeline()
        val opening = async { opener(timeline).open() }
        runCurrent()
        advanceTimeBy(1_500)
        timeline.settingsResumed()
        runCurrent()

        assertEquals(SettingsOpening.OPENED, opening.await())
        assertEquals(listOf("session"), log)
    }

    @Test
    fun withOnlyTheRoleSettingsThatDoNotComeAreOfferedAfterTwoSeconds() = runTest {
        role = true
        val opening = async { opener(timeline()).open() }
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(opening.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(SettingsOpening.BLOCKED, opening.await())
        assertEquals(listOf("session", "offer"), log)
    }

    @Test
    fun withoutAReadyAssistantServiceTheSettingsAreOfferedAtOnce() = runTest {
        role = true
        sessionReady = false

        assertEquals(SettingsOpening.BLOCKED, opener(timeline()).open())
        assertEquals(listOf("session", "offer"), log)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun withTheServiceTheSettingsStartDirectlyAlsoWhileKestrelHoldsTheRole() = runTest {
        // Android lets the accessibility service start an activity from the background (prototype P1), so the role's
        // session is not needed.
        service = true
        role = true
        val timeline = timeline()
        val opening = async { opener(timeline).open() }
        runCurrent()
        advanceTimeBy(500)
        timeline.settingsResumed()
        runCurrent()

        assertEquals(SettingsOpening.OPENED, opening.await())
        assertEquals(listOf("start"), log)
    }

    @Test
    fun withTheServiceSettingsThatDoNotComeAreOfferedAfterTwoSeconds() = runTest {
        service = true
        val opening = async { opener(timeline()).open() }
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(opening.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(SettingsOpening.BLOCKED, opening.await())
        assertEquals(listOf("start", "offer"), log)
    }

    @Test
    fun aSessionThatAndroidRefusesIsReportedByItsClassAndTheSettingsAreOffered() = runTest {
        role = true
        sessionFailure = SecurityException("not the active voice interaction service")

        // BLOCKED makes Kestrel say why (ListeningTexts.SETTINGS_BLOCKED).
        assertEquals(SettingsOpening.BLOCKED, opener(timeline()).open())
        assertEquals(
            listOf("session", "report Opening the settings through the assistant: SecurityException", "offer"),
            log,
        )
        assertEquals(0L, testScheduler.currentTime)
    }
}
