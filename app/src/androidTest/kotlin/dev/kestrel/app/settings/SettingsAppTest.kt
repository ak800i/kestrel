package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kestrel.app.R
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SettingsAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SetupAction>()
    private val data = FakeSettingsData()
    private var exports = 0

    private fun show(status: SetupStatus) {
        compose.setContent { MaterialTheme { SettingsApp(status, data, { actions += it }) { exports++ } } }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun progress(done: Int): String =
        compose.activity.resources.getQuantityString(R.plurals.settings_checklist_progress, 8, done, 8)

    private fun openChecklist() {
        compose.onNodeWithText(text(R.string.settings_checklist)).performClick()
        compose.onNodeWithText(text(R.string.setup_service_title)).assertExists()
    }

    @Test
    fun theHomePageCountsTheStepsDone() {
        show(NOTHING_SET_UP.copy(serviceOn = true))

        // Installing, the service, and Voice Access being off.
        compose.onNodeWithText(progress(3)).assertIsDisplayed()
    }

    @Test
    fun stepsToDoOfferTheirActions() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_allow)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_app_settings)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()

        assertEquals(
            listOf(SetupAction.PERMISSIONS, SetupAction.APP_SETTINGS, SetupAction.ACCESSIBILITY_SETTINGS),
            actions,
        )
    }

    @Test
    fun doneStepsOfferNoAction() {
        show(EVERYTHING_SET_UP)
        openChecklist()

        val labels = listOf(R.string.setup_allow, R.string.setup_open_app_settings, R.string.setup_open_accessibility)
        for (label in labels) {
            compose.onAllNodesWithText(text(label)).assertCountEquals(0)
        }
    }

    @Test
    fun voiceAccessBesideKestrelShowsTheWarning() {
        show(EVERYTHING_SET_UP.copy(voiceAccessOn = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_voice_access_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.ACCESSIBILITY_SETTINGS), actions)
    }

    @Test
    fun theBackButtonAndAndroidsBackReturnHome() {
        show(NOTHING_SET_UP)
        val progress = progress(2)

        openChecklist()
        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(progress).assertIsDisplayed()

        openChecklist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(progress).assertIsDisplayed()
    }

    @Test
    fun theSafetyPageSwitchesTestMode() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_test_mode_on)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()

        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOn().performClick()

        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOff()
        assertFalse(data.testMode.value)
    }

    @Test
    fun theLogPagesShowTheirEntries() {
        data.activity = listOf(
            LogEntry(2, 2_000, ActivityEvent(ActivityKind.ACTION, "Tapped Send", taskId = "task-1")),
            LogEntry(1, 1_000, ActivityEvent(ActivityKind.NOTE, "Started")),
        )
        data.network = listOf(
            LogEntry(3, 3_000, NetworkEvent("POST", "api.example.com", "/v1/chat", 2_048, 512, 200, NetworkOutcome.COMPLETED)),
        )
        show(NOTHING_SET_UP)

        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()
        compose.onNodeWithText("Tapped Send").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_task, "task-1")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_kind_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_back)).performClick()

        compose.onNodeWithText(text(R.string.settings_network_log)).performClick()
        compose.onNodeWithText("POST api.example.com/v1/chat").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_network_status, 200)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_outcome_completed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logs_export)).performClick()
        assertEquals(1, exports)
    }

    @Test
    fun deleteHistoryAsksFirst() {
        data.activity = listOf(LogEntry(1, 1_000, ActivityEvent(ActivityKind.NOTE, "Started")))
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_cancel)).performClick()
        compose.onNodeWithText("Started").assertIsDisplayed()
        assertEquals(0, data.deletions)

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_question)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logs_delete_confirm)).performClick()

        compose.onNodeWithText(text(R.string.logs_empty)).assertIsDisplayed()
        assertEquals(1, data.deletions)
    }

    @Test
    fun theAboutPageShowsTheVersionAndOpensALicence() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.about_version, "9.9.9 (99)")).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.about_licence_apache)).performScrollTo().performClick()

        compose.onNodeWithText("The first paragraph, wrapped.").assertIsDisplayed()
        assertEquals(listOf(Licence.APACHE), data.opened)
        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.about_version, "9.9.9 (99)")).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(progress(2)).assertIsDisplayed()
    }

    @Test
    fun theModelStepSaysWhileKestrelChecks() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_models_checking)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theModelStepNamesTheFilesThatAreMissingOrDamaged() {
        val speech = ModelStatus(SPEECH, missing = listOf("asr/encoder.onnx"), damaged = listOf("asr/tokens.txt"))
        show(NOTHING_SET_UP.copy(models = listOf(READY_VOICE, speech)))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_models_missing, "asr/encoder.onnx")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_models_damaged, "asr/tokens.txt")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAboutPageShowsEachModel() {
        val speech = ModelStatus(SPEECH, missing = listOf("asr/encoder.onnx", "asr/tokens.txt"), damaged = emptyList())
        show(NOTHING_SET_UP.copy(models = listOf(READY_VOICE, speech)))
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.about_model_ready, "Voice (MIT)")).performScrollTo().assertIsDisplayed()
        val missing = compose.activity.resources.getQuantityString(R.plurals.about_model_missing, 2, "Speech (CC-BY-4.0)", 2)
        compose.onNodeWithText(missing).performScrollTo().assertIsDisplayed()
    }

    private class FakeSettingsData : SettingsData {
        override val testMode = MutableStateFlow(true)
        override val version = "9.9.9 (99)"
        var activity = emptyList<LogEntry<ActivityEvent>>()
        var network = emptyList<LogEntry<NetworkEvent>>()
        var deletions = 0
        val opened = mutableListOf<Licence>()

        override suspend fun licenceText(licence: Licence): String {
            opened += licence
            return "The first paragraph,\nwrapped.\n\nThe second paragraph."
        }

        override suspend fun setTestMode(on: Boolean) {
            testMode.value = on
        }

        override suspend fun activityLog() = activity

        override suspend fun networkLog() = network

        override suspend fun deleteHistory() {
            deletions++
            activity = emptyList()
            network = emptyList()
        }
    }

    private companion object {
        val VOICE = Model("tts", Engine.TTS, "Voice", "MIT", "https://example.org", emptyList())
        val SPEECH = Model("asr", Engine.ASR, "Speech", "CC-BY-4.0", "https://example.org", emptyList())
        val READY_VOICE = ModelStatus(VOICE, emptyList(), emptyList())
        val NOTHING_SET_UP = SetupStatus(
            serviceOn = false,
            microphoneAllowed = false,
            notificationsAllowed = false,
            batteryUnrestricted = false,
            voiceAccessOn = false,
        )
        val EVERYTHING_SET_UP = SetupStatus(
            serviceOn = true,
            microphoneAllowed = true,
            notificationsAllowed = true,
            batteryUnrestricted = true,
            voiceAccessOn = false,
            models = listOf(READY_VOICE),
        )
    }
}
