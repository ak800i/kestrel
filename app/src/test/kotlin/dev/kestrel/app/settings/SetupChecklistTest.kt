package dev.kestrel.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupChecklistTest {
    private val nothingSetUp = SetupStatus(
        serviceOn = false,
        microphoneAllowed = false,
        notificationsAllowed = false,
        batteryUnrestricted = false,
        voiceAccessOn = false,
    )

    @Test
    fun aNewInstallStillHasTheAndroidStepsToDo() {
        assertEquals(
            mapOf(
                SetupStep.INSTALL to StepState.DONE,
                SetupStep.COPY_MODELS to StepState.LATER,
                SetupStep.ACCESSIBILITY_SERVICE to StepState.TO_DO,
                SetupStep.MICROPHONE_AND_NOTIFICATIONS to StepState.TO_DO,
                SetupStep.BATTERY to StepState.TO_DO,
                SetupStep.DEFAULT_ASSISTANT to StepState.LATER,
                SetupStep.PROVIDER to StepState.LATER,
                SetupStep.VOICE_ACCESS to StepState.DONE,
            ),
            SetupChecklist.states(nothingSetUp),
        )
    }

    @Test
    fun thePermissionStepNeedsBothPermissions() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.MICROPHONE_AND_NOTIFICATIONS)

        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(microphoneAllowed = true)))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(notificationsAllowed = true)))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(microphoneAllowed = true, notificationsAllowed = true)))
    }

    @Test
    fun theServiceAndBatteryStepsAreDoneWhenSetUp() {
        val states = SetupChecklist.states(nothingSetUp.copy(serviceOn = true, batteryUnrestricted = true))

        assertEquals(StepState.DONE, states.getValue(SetupStep.ACCESSIBILITY_SERVICE))
        assertEquals(StepState.DONE, states.getValue(SetupStep.BATTERY))
    }

    @Test
    fun voiceAccessBesideKestrelIsAWarning() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.VOICE_ACCESS)

        assertEquals(StepState.WARNING, state(nothingSetUp.copy(voiceAccessOn = true, serviceOn = true)))
        assertEquals(StepState.OPTIONAL, state(nothingSetUp.copy(voiceAccessOn = true)))
    }
}
