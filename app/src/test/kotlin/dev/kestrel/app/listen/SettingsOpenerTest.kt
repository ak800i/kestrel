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

    private fun TestScope.timeline() = Timeline { testScheduler.currentTime }

    private fun opener(timeline: Timeline) = SettingsOpener(
        timeline,
        serviceOn = { service },
        roleHeld = { role },
        startActivity = { log += "start" },
        showSession = {
            log += "session"
            sessionReady
        },
        offer = { log += "offer" },
        report = { what, _ -> log += "report $what" },
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
}
