package dev.kestrel.app.debug

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hooks with which the A71 sessions put back what the owner changed at their end (design D20 and plan 4C's final
 * fix wave): the owner's options "Require verbs" and "Use the earbud microphone", Kestrel's notices about problems, and
 * the owner's Copilot profile on the shared emulator. Each test puts back the settings that it changed.
 */
@RunWith(AndroidJUnit4::class)
class OwnerHooksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun theOwnersOptionsAreStoredAndAnswered() = runBlocking {
        val commands = graph.settings.get(SettingKeys.COMMANDS)
        val listening = graph.settings.get(SettingKeys.LISTENING_OPTIONS)
        try {
            assertEquals("requireVerbs=on earbudMicrophone=on", hook("OWNER_OPTIONS", "requireVerbs" to true, "earbudMicrophone" to true))
            assertEquals(true, graph.settings.get(SettingKeys.COMMANDS).requireVerbs)
            assertEquals("requireVerbs=off earbudMicrophone=on", hook("OWNER_OPTIONS", "requireVerbs" to false))
            assertEquals("requireVerbs=off earbudMicrophone=off", hook("OWNER_OPTIONS", "earbudMicrophone" to false))
            assertEquals(false, graph.settings.get(SettingKeys.LISTENING_OPTIONS).earbudMicrophone)
        } finally {
            graph.settings.set(SettingKeys.COMMANDS, commands)
            graph.settings.set(SettingKeys.LISTENING_OPTIONS, listening)
        }
    }

    @Test
    fun kestrelsNoticesAboutProblemsGo() {
        VoiceTests.grantListeningPermissions()
        hook("NOTICES", "clear" to true)

        ProblemNotifications(context).accessibilityServiceOff()

        // Android lists a new notification a moment after it was posted.
        assertTrue("the notice did not show", VoiceTests.waitUntil(5_000) { hook("NOTICES") == "notices=1 kinds=accessibility" })
        assertEquals("notices=0 kinds=none", hook("NOTICES", "clear" to true))
    }

    @Test
    fun everyCopilotProfileIsDeletedAndTheOthersStay() = runBlocking {
        val before = graph.settings.get(SettingKeys.PROVIDERS)
        // Never on a device that holds a Copilot profile of the owner's.
        assumeTrue(before.profiles.none { it.providerId == CatalogLoader.COPILOT })
        val copilot = Profile(
            "test-hook-copilot", "Copilot", CatalogLoader.COPILOT, "github-copilot", AuthMethod.DEVICE_CODE, null,
            lightweight = ProfileModel("test-model"), versatile = ProfileModel("test-model"),
        )
        val other = copilot.copy(id = "test-hook-key", providerId = "groq", choiceId = "groq-api-key", method = AuthMethod.API_KEY)
        graph.settings.set(SettingKeys.PROVIDERS, before.copy(profiles = before.profiles + copilot + other))
        try {
            assertEquals("deleted=1 failed=0 copilot=0", hook("PROVIDER_DELETE"))
            assertEquals(listOf(other.id), graph.settings.get(SettingKeys.PROVIDERS).profiles.map { it.id }.filter { it.startsWith("test-hook-") })
        } finally {
            graph.settings.set(SettingKeys.PROVIDERS, before)
        }
    }

    /** Runs a hook command as TestHookProvider does, with boolean extras, and answers its text after checking its code. */
    private fun hook(command: String, vararg flags: Pair<String, Boolean>): String {
        val arguments = Bundle()
        for ((key, value) in flags) arguments.putBoolean(key, value)
        val reply = runBlocking { TestHooks(graph, context).run(command, arguments) }
        assertEquals(reply.text, HookReply.OK, reply.code)
        return reply.text
    }
}
