package dev.kestrel.app.download

import dev.kestrel.voice.models.DownloadFailure
import dev.kestrel.voice.models.DownloadProgress
import dev.kestrel.voice.models.ModelDownloadException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadsTest {
    private val reported = mutableListOf<Throwable>()

    @Test
    fun aDownloadThatEndsRunsItsFollowUpAndIsDone() = runTest {
        val asked = mutableListOf<Pair<Boolean, Boolean>>()
        var followed = false
        val downloads = ModelDownloads(this, { kokoro, mobileData, progress ->
            asked += kokoro to mobileData
            progress(DownloadProgress(5, 10))
        }) { _, error -> reported += error }

        downloads.start(kokoro = true, mobileData = false) { followed = true }
        advanceUntilIdle()

        assertEquals(listOf(true to false), asked)
        assertTrue(followed)
        assertEquals(DownloadState.Done, downloads.state.value)
    }

    @Test
    fun aDownloadThatStopsSaysWhyAndOnlyAnUnexpectedFailureIsReported() = runTest {
        val metered = ModelDownloads(this, { _, _, _ -> throw ModelDownloadException(DownloadFailure.MeteredNetwork) }) { _, error ->
            reported += error
        }
        val broken = ModelDownloads(this, { _, _, _ -> throw IllegalStateException("broken") }) { _, error -> reported += error }

        metered.start(kokoro = false, mobileData = false)
        broken.start(kokoro = false, mobileData = false)
        advanceUntilIdle()

        assertEquals(DownloadState.Failed(DownloadFailure.MeteredNetwork), metered.state.value)
        assertEquals(DownloadState.Failed(DownloadFailure.Unexpected), broken.state.value)
        assertEquals(listOf("IllegalStateException"), reported.map { it.javaClass.simpleName })
    }

    @Test
    fun aSecondStartWaitsForTheFirstDownloadAndCancelStopsIt() = runTest {
        var starts = 0
        val downloads = ModelDownloads(this, { _, _, progress ->
            starts++
            progress(DownloadProgress(1, 2))
            awaitCancellation()
        }) { _, error -> reported += error }

        downloads.start(kokoro = false, mobileData = false)
        runCurrent()
        downloads.start(kokoro = true, mobileData = true)
        runCurrent()

        assertEquals(1, starts)
        assertEquals(DownloadState.Running(1, 2), downloads.state.value)
        downloads.cancel()
        advanceUntilIdle()
        assertEquals(DownloadState.Cancelled, downloads.state.value)
        assertTrue(reported.isEmpty())
    }
}
