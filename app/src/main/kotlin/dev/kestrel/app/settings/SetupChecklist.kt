package dev.kestrel.app.settings

/** The steps of the setup checklist, in order (design spec, section 15). */
enum class SetupStep {
    INSTALL,
    COPY_MODELS,
    ACCESSIBILITY_SERVICE,
    MICROPHONE_AND_NOTIFICATIONS,
    BATTERY,
    DEFAULT_ASSISTANT,
    PROVIDER,
    VOICE_ACCESS,
}

/** Where a step of the setup checklist stands. */
enum class StepState {
    DONE,

    /** The owner still has to do the step. */
    TO_DO,

    /** A later sub-project adds what the step sets up. */
    LATER,

    /** The step is optional and not done. */
    OPTIONAL,

    /** Something needs the owner's attention, such as Voice Access running beside Kestrel. */
    WARNING,
}

/** What Kestrel can find out about its own setup on the phone. */
data class SetupStatus(
    val serviceOn: Boolean,
    val microphoneAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val voiceAccessOn: Boolean,
)

/** Decides the state of each step of the setup checklist (design spec, section 15). */
object SetupChecklist {
    fun states(status: SetupStatus): Map<SetupStep, StepState> = SetupStep.entries.associateWith { step ->
        when (step) {
            // Kestrel is running, so it is installed.
            SetupStep.INSTALL -> StepState.DONE
            SetupStep.COPY_MODELS, SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
            SetupStep.ACCESSIBILITY_SERVICE -> doneIf(status.serviceOn)
            SetupStep.MICROPHONE_AND_NOTIFICATIONS -> doneIf(status.microphoneAllowed && status.notificationsAllowed)
            SetupStep.BATTERY -> doneIf(status.batteryUnrestricted)
            // Both apps would react to the same words.
            SetupStep.VOICE_ACCESS -> when {
                !status.voiceAccessOn -> StepState.DONE
                status.serviceOn -> StepState.WARNING
                else -> StepState.OPTIONAL
            }
        }
    }

    private fun doneIf(done: Boolean) = if (done) StepState.DONE else StepState.TO_DO
}
