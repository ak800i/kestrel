package dev.kestrel.app.voice

import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.BoostWordBudget
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.ScreenBoostWords
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.commands.CommandVocabulary
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * The labels on the screen as boost words (design spec, section 8.3): the screen gives them from its newest snapshot,
 * AppGraph keeps that snapshot current while Kestrel listens, and the recogniser keeps them and every phrase of the
 * command vocabulary. The failure messages name only the vocabulary's phrases and the fixture app's labels.
 */
class ScreenBoostWordsTest {
    // Before ScreenServiceRule, so that a run without the models changes nothing on the device.
    @get:Rule(order = 0)
    val models = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    // The voice tests after this one run without Kestrel's accessibility service, as ActivationTest explains.
    @After
    fun turnOffTheService() = VoiceTests.turnOffAccessibilityService()

    @Test
    fun theLabelsOnTheScreenBecomeBoostWordsAndStayCurrentWhileKestrelListens() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val session = screen.session
        val words = ScreenBoostWords { session }

        // ScreenServiceRule.open built a snapshot of this screen. The Views fixture's buttons show their texts in capitals
        // ("SEND", as SnapshotTest checks), so the names are compared ignoring case.
        val views = words.words().map { it.lowercase() }
        // The messages never print the list: on a phone it would hold the labels of the status bar too.
        assertTrue("A name of the Views fixture is missing", views.containsAll(listOf("send", "compose", "remember me", "message", "show dialog")))
        // A password field, an element that takes no action, a disabled field and the title give no words.
        assertFalse("A name that is no boost word is there", views.any { it in listOf("password", "touch target", "disabled field", "views fixture") })

        // Nothing builds a snapshot of a new screen while Kestrel is off,
        screen.shell("am start -W -n ${ScreenServiceRule.FIXTURES}/.CommandsActivity")
        delay(1_000)
        assertFalse("Send later" in words.words())
        // but while Kestrel listens, AppGraph keeps the session's snapshot fresh: at once, and after each later change.
        VoiceTests.grantListeningPermissions()
        // Inside the try: a start that fails must not leave Kestrel on, with the clip microphone, for the later voice tests.
        try {
            // The accessibility service lets Kestrel start its microphone service from the background, as in RefusedReplyTest.
            VoiceTests.startListening(ClipMicrophone())
            assertTrue("keepFresh is off while Kestrel listens", VoiceTests.waitUntil(5_000) { session.keepFresh })
            withTimeout(5_000) { while ("Send later" !in words.words()) delay(100) }
            // Back to the Views fixture: only the screen watcher builds a snapshot of it now.
            screen.shell("input keyevent KEYCODE_BACK")
            withTimeout(5_000) { while ("Send later" in words.words()) delay(100) }
        } finally {
            VoiceTests.stopListening()
        }
        assertTrue("keepFresh stays on after Kestrel turned off", VoiceTests.waitUntil(5_000) { !session.keepFresh })
    }

    @Test
    fun theRecognizerKeepsTheVocabularyAndTheLabels() {
        runBlocking { VoiceTests.graph.voice.recognizer() }.use { recognizer ->
            val dropped = CommandVocabulary.BOOST.filter { recognizer.keptBoostWords(listOf(it)) == 0 }
            assertEquals("sherpa-onnx drops these phrases of the command vocabulary", emptyList<String>(), dropped)

            val words = BoostWordBudget.combine(listOf(BoostWordSource.NAME), listOf("Send", "Remember me", "Show dialog"), CommandVocabulary.BOOST)
            assertEquals(words.size, recognizer.keptBoostWords(words))
        }
    }
}
