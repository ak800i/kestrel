package dev.kestrel.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppGraphTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    @Test
    fun blockedRequestIsWrittenToTheEncryptedNetworkLog() = runBlocking {
        graph.logs.deleteHistory()
        val request = Request.Builder().url("https://tracker.example.net/collect").build()

        assertThrows(BlockedRequestException::class.java) { graph.network.callFactory.newCall(request).execute() }

        val entry = withTimeout(5_000) {
            var entries = graph.logs.network()
            while (entries.isEmpty()) {
                delay(50)
                entries = graph.logs.network()
            }
            entries.single()
        }
        assertEquals(NetworkOutcome.BLOCKED, entry.event.outcome)
        assertEquals("tracker.example.net", entry.event.host)
    }

    @Test
    fun settingsSurviveTheEncryptedStore() = runBlocking {
        // A test-only key, so that the real safety settings keep test mode on during all testing (D25).
        val key = SettingKey("test.safety", SafetySettings.serializer(), SafetySettings())

        graph.settings.set(key, SafetySettings(testMode = false))

        assertEquals(SafetySettings(testMode = false), graph.settings.get(key))
    }
}
