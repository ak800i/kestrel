package dev.kestrel.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.settings.SettingKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PrivacyStore(context)

    @Test
    fun aSettingChangedThroughAnotherStoreReachesTheObserver() = runBlocking {
        // A test-only name, so that the real safety settings keep test mode on during all testing (D25).
        val key = SettingKey("test.shared.${System.nanoTime()}", Int.serializer(), 0)
        val other = PrivacyStore(context)
        val values = Channel<Int>(Channel.UNLIMITED)
        val observer = launch { store.settings.observe(key).collect { values.send(it) } }
        try {
            withTimeout(5_000) {
                assertEquals(0, values.receive())
                other.settings.set(key, 7)
                assertEquals(7, values.receive())
            }
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun anUnreadableSettingIsRecordedInTheActivityLog() = runBlocking {
        val name = "test.unreadable.${System.nanoTime()}"
        store.logs.deleteHistory()
        store.settings.set(SettingKey(name, Int.serializer(), 0), 7)

        // The stored JSON is the number 7, which cannot be read as text.
        assertEquals("fallback", store.settings.get(SettingKey(name, String.serializer(), "fallback")))

        val entry = store.logs.activity().single()
        assertEquals(ActivityKind.ERROR, entry.event.kind)
        assertEquals("The setting '$name' could not be read, so Kestrel uses its default", entry.event.text)
    }
}
