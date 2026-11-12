package dev.kestrel.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.settings.SettingKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyStoreTest {
    private val store = PrivacyStore(InstrumentationRegistry.getInstrumentation().targetContext)

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
