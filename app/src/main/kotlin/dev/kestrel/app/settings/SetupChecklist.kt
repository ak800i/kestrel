package dev.kestrel.app.settings

import dev.kestrel.voice.models.ModelStatus
import kotlinx.coroutines.CancellationException

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

/** What the settings pages know about the model files (design spec, section 8.10). */
sealed interface ModelCheck {
    /** Kestrel is checking them and has no result to show. */
    data object Checking : ModelCheck

    /** The last check failed. The pages say so, and the next page that opens checks again. */
    data object Failed : ModelCheck

    /** The result of the last check: one status per model, in the manifest's order. */
    data class Done(val statuses: List<ModelStatus>) : ModelCheck
}

/**
 * What Kestrel can find out about its own setup on the phone. [serviceOn]: Kestrel's accessibility service runs.
 * [models] is what Kestrel knows about the model files. [serviceEnabled]: Android's setting names the accessibility
 * service, also when Android does not run it, as after "Stop app" (prototype P2). [batteryRestricted]: the owner set
 * Kestrel's battery use to Restricted. [samsung]: the phone is a Samsung, whose battery settings also have "Never
 * sleeping apps".
 */
data class SetupStatus(
    val serviceOn: Boolean,
    val microphoneAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val voiceAccessOn: Boolean,
    val models: ModelCheck = ModelCheck.Checking,
    val serviceEnabled: Boolean = serviceOn,
    val batteryRestricted: Boolean = false,
    val samsung: Boolean = false,
)

/** Decides the state of each step of the setup checklist (design spec, section 15). */
object SetupChecklist {
    fun states(status: SetupStatus): Map<SetupStep, StepState> = SetupStep.entries.associateWith { step ->
        when (step) {
            // Kestrel is running, so it is installed.
            SetupStep.INSTALL -> StepState.DONE
            SetupStep.COPY_MODELS -> doneIf((status.models as? ModelCheck.Done)?.statuses?.all { it.ready } == true)
            SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
            SetupStep.ACCESSIBILITY_SERVICE -> when {
                status.serviceOn -> StepState.DONE
                // Android's setting names the service, but Android does not run it, as after "Stop app" (prototype P2).
                status.serviceEnabled -> StepState.WARNING
                else -> StepState.TO_DO
            }
            SetupStep.MICROPHONE_AND_NOTIFICATIONS -> doneIf(status.microphoneAllowed && status.notificationsAllowed)
            SetupStep.BATTERY -> when {
                // Android then stops listening in the background (prototype P2).
                status.batteryRestricted -> StepState.WARNING
                status.batteryUnrestricted -> StepState.DONE
                else -> StepState.TO_DO
            }
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

/**
 * Checks the model files with [check] for the settings pages. A check that throws gives [ModelCheck.Failed], so that
 * the pages do not say "Checking…" for ever, and [report] gets the error, which it must log by its classes only (design
 * spec, section 13.5). A cancellation, such as the end of the page's scope, is thrown on.
 */
internal suspend fun checkModelFiles(check: suspend () -> List<ModelStatus>, report: (Exception) -> Unit): ModelCheck =
    try {
        ModelCheck.Done(check())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
        ModelCheck.Failed
    }
