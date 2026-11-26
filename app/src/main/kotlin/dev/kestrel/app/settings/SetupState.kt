package dev.kestrel.app.settings

import android.content.Context
import dev.kestrel.app.AppGraph
import dev.kestrel.app.download.DownloadChoice
import dev.kestrel.voice.VoiceChoice
import kotlinx.coroutines.flow.first

/**
 * The setup checklist's status, assembled in one place (plan 7F): what Android says ([SetupStatusReader]), the model files'
 * check, and what the stored settings say. The settings app shows it part by part as each part arrives; the conversation
 * screen's banner reads it whole ([read]).
 */
internal object SetupState {
    /** [status] with the stored settings' part: a ready provider, the safety review, and the voice that the download wants. */
    fun stored(status: SetupStatus, providerReady: Boolean, agentFreeReviewed: Boolean, downloadChoice: DownloadChoice?): SetupStatus =
        status.copy(
            providerReady = providerReady,
            agentFreeReviewed = agentFreeReviewed,
            kokoroWanted = downloadChoice?.voice == VoiceChoice.KOKORO,
        )

    /** The model files' check. A failure goes to [report], which logs it by its classes only (section 13.5). */
    suspend fun models(graph: AppGraph, report: (Exception) -> Unit): ModelCheck = checkModelFiles({ graph.models.check() }, report)

    /** The checklist's states now, with every part read: what the conversation screen's banner shows (design 10.1 of sub-project 7). */
    suspend fun read(graph: AppGraph, context: Context, report: (Exception) -> Unit): Map<SetupStep, StepState> {
        val data = StoredSettingsData(graph, context)
        val status = SetupStatusReader(context).read().copy(models = models(graph, report))
        return SetupChecklist.states(stored(status, data.providerReady.first(), data.agentFreeReviewed.first(), data.downloadChoice.first()))
    }
}
