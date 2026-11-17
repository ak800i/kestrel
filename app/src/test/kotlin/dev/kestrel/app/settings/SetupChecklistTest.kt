package dev.kestrel.app.settings

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelFile
import dev.kestrel.voice.models.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                SetupStep.COPY_MODELS to StepState.TO_DO,
                SetupStep.ACCESSIBILITY_SERVICE to StepState.TO_DO,
                SetupStep.MICROPHONE_AND_NOTIFICATIONS to StepState.TO_DO,
                SetupStep.BATTERY to StepState.TO_DO,
                SetupStep.DEFAULT_ASSISTANT to StepState.OPTIONAL,
                SetupStep.CALLS to StepState.OPTIONAL,
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
    fun aServiceThatIsOnButDoesNotRunNeedsAttention() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.ACCESSIBILITY_SERVICE)

        // As after "Stop app": Android's setting names the service, but Android does not run it (prototype P2).
        assertEquals(StepState.WARNING, state(nothingSetUp.copy(serviceEnabled = true)))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(serviceOn = true, serviceEnabled = true)))
        assertEquals(StepState.TO_DO, state(nothingSetUp))
    }

    @Test
    fun restrictedBatteryUseNeedsAttention() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.BATTERY)

        // Android then stops listening in the background, also when Kestrel ignores the battery optimisations.
        assertEquals(StepState.WARNING, state(nothingSetUp.copy(batteryRestricted = true)))
        assertEquals(StepState.WARNING, state(nothingSetUp.copy(batteryRestricted = true, batteryUnrestricted = true)))
        assertEquals(StepState.TO_DO, state(nothingSetUp))
    }

    @Test
    fun voiceAccessBesideKestrelIsAWarning() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.VOICE_ACCESS)

        assertEquals(StepState.WARNING, state(nothingSetUp.copy(voiceAccessOn = true, serviceOn = true)))
        assertEquals(StepState.OPTIONAL, state(nothingSetUp.copy(voiceAccessOn = true)))
    }

    @Test
    fun theCallStepIsDoneWithBothPhonePermissions() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.CALLS)

        assertEquals(StepState.OPTIONAL, state(nothingSetUp))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(callsAllowed = true)))
    }

    @Test
    fun theAssistantStepFollowsTheRole() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.DEFAULT_ASSISTANT)

        assertEquals(StepState.OPTIONAL, state(nothingSetUp))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(assistantRole = true)))
    }

    @Test
    fun theModelStepIsDoneOnlyWhenEveryModelIsReady() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.COPY_MODELS)
        val voice = Model("tts", Engine.TTS, "Voice", "MIT", "https://example.org", emptyList())
        val speech = Model("asr", Engine.ASR, "Speech", "CC-BY-4.0", "https://example.org", emptyList())
        val ready = ModelStatus(voice, emptyList(), emptyList())

        // Kestrel has not checked the files yet, or could not check them.
        assertEquals(StepState.TO_DO, state(nothingSetUp))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(models = ModelCheck.Failed)))
        assertEquals(
            StepState.TO_DO,
            state(nothingSetUp.copy(models = ModelCheck.Done(listOf(ready, ModelStatus(speech, listOf("a"), emptyList()))))),
        )
        assertEquals(
            StepState.TO_DO,
            state(nothingSetUp.copy(models = ModelCheck.Done(listOf(ready, ModelStatus(speech, emptyList(), listOf("a")))))),
        )
        assertEquals(
            StepState.DONE,
            state(nothingSetUp.copy(models = ModelCheck.Done(listOf(ready, ModelStatus(speech, emptyList(), emptyList()))))),
        )
    }

    @Test
    fun aModelCheckThatFailsIsReportedAndSaysSo() = runTest {
        val failure = IllegalStateException("The manifest cannot be read")
        val reported = mutableListOf<Exception>()

        val result = checkModelFiles({ throw failure }) { reported += it }

        assertEquals(ModelCheck.Failed, result)
        assertEquals(listOf<Exception>(failure), reported)
    }

    @Test
    fun aCancelledModelCheckIsNotAFailure() = runTest {
        val reported = mutableListOf<Exception>()

        // As when the owner leaves the page while the files are hashed.
        val thrown = runCatching { checkModelFiles({ throw CancellationException("The page closed") }) { reported += it } }

        assertTrue(thrown.exceptionOrNull() is CancellationException)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun anOptionalModelThatIsNotOnThePhoneLeavesTheModelStepDone() {
        val voice = Model("tts", Engine.TTS, "Voice", "MIT", "https://example.org", listOf(ModelFile("tts/a.onnx", 1, "0".repeat(64))))
        val kokoro = Model(
            "tts-kokoro", Engine.TTS, "Kokoro", "Apache-2.0", "https://example.org",
            listOf(ModelFile("tts-kokoro/model.onnx", 1, "0".repeat(64))), optional = true,
        )
        val absent = ModelStatus(kokoro, missing = listOf("tts-kokoro/model.onnx"), damaged = emptyList())
        val status = SetupStatus(
            serviceOn = false,
            microphoneAllowed = false,
            notificationsAllowed = false,
            batteryUnrestricted = false,
            voiceAccessOn = false,
            models = ModelCheck.Done(listOf(ModelStatus(voice, emptyList(), emptyList()), absent)),
        )

        assertEquals(StepState.DONE, SetupChecklist.states(status).getValue(SetupStep.COPY_MODELS))
    }
}
