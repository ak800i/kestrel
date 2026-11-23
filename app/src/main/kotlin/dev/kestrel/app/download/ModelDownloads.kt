package dev.kestrel.app.download

import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.models.DownloadFailure
import dev.kestrel.voice.models.DownloadProgress
import dev.kestrel.voice.models.ModelDownloadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The voice and the network that the owner chose for the download of the models (release plan), stored encrypted like
 * every setting. A [voice] of null means the one that suits the phone ([VoiceSuggestion]).
 */
@Serializable
data class ModelDownloadSetting(val voice: VoiceChoice? = null, val mobileData: Boolean = false)

/** What step 2 of the checklist shows: the [voice] to download, the [suggested] one for this phone and [mobileData]. */
data class DownloadChoice(val voice: VoiceChoice, val suggested: VoiceChoice, val mobileData: Boolean)

/** Where the download of the models stands (release plan). */
sealed interface DownloadState {
    data object Idle : DownloadState

    data class Running(val doneBytes: Long, val totalBytes: Long) : DownloadState

    data object Done : DownloadState

    data object Cancelled : DownloadState

    data class Failed(val failure: DownloadFailure) : DownloadState
}

/**
 * Runs one download of the models at a time in [scope], which outlives the settings pages (release plan). [download]
 * fetches the files for Kokoro or not and for mobile data or not, and reports its progress; a failure other than
 * [ModelDownloadException] goes to [report], which logs it by its classes only (design spec, section 13.5).
 */
class ModelDownloads(
    private val scope: CoroutineScope,
    private val download: suspend (kokoro: Boolean, mobileData: Boolean, progress: (DownloadProgress) -> Unit) -> Unit,
    private val report: (String, Throwable) -> Unit,
) {
    private val states = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** Where the download stands; the end of the last download stays until the next one starts. */
    val state: StateFlow<DownloadState> = states.asStateFlow()

    private var job: Job? = null

    /**
     * Starts a download, unless one has not ended yet: a cancelled download may still write its last bytes. [onDone]
     * runs after a download that brought every file.
     */
    @Synchronized
    fun start(kokoro: Boolean, mobileData: Boolean, onDone: suspend () -> Unit = {}) {
        if (job?.isCompleted == false) return
        states.value = DownloadState.Running(0, 0)
        job = scope.launch {
            try {
                download(kokoro, mobileData) { states.value = DownloadState.Running(it.doneBytes, it.totalBytes) }
                onDone()
                states.value = DownloadState.Done
            } catch (e: CancellationException) {
                states.value = DownloadState.Cancelled
                throw e
            } catch (e: ModelDownloadException) {
                states.value = DownloadState.Failed(e.failure)
            } catch (e: Exception) {
                report("Downloading the models", e)
                states.value = DownloadState.Failed(DownloadFailure.Unexpected)
            }
        }
    }

    /** Stops the download; its part files stay for the next one. */
    @Synchronized
    fun cancel() {
        job?.cancel()
    }
}
