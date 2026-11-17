package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kestrel.app.R
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.commands.CommandHelp
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.app.listen.VoiceOptionsSetting
import dev.kestrel.app.orchestrator.WakeUpMode
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.Model
import dev.kestrel.voice.models.ModelFile
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
    private var checks = 0

    private fun show(status: SetupStatus) {
        compose.setContent { MaterialTheme { SettingsApp(status, data, { actions += it }, { checks++ }) { exports++ } } }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun progress(done: Int): String =
        compose.activity.resources.getQuantityString(R.plurals.settings_checklist_progress, 9, done, 9)

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
        compose.onNodeWithText(text(R.string.setup_battery_detail)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.setup_battery_detail_samsung)).assertCountEquals(0)

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

        val labels = listOf(
            R.string.setup_allow,
            R.string.setup_open_app_settings,
            R.string.setup_open_accessibility,
            R.string.setup_allow_calls,
        )
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
    fun stepsThatNeedAttentionSayWhy() {
        // On a Samsung, the battery step's warning keeps "Never sleeping apps".
        show(NOTHING_SET_UP.copy(serviceEnabled = true, batteryRestricted = true, samsung = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_service_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_battery_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_battery_never_sleeping)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theBatteryStepNamesNeverSleepingAppsOnlyOnASamsung() {
        show(NOTHING_SET_UP.copy(samsung = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_battery_detail_samsung)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.setup_battery_detail)).assertCountEquals(0)
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
    fun bothPagesSayWhenTheModelCheckFailed() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Failed))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_models_check_failed)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.about_models_check_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theModelStepNamesTheFilesThatAreMissingOrDamaged() {
        val speech = ModelStatus(SPEECH, missing = listOf("asr/encoder.onnx"), damaged = listOf("asr/tokens.txt"))
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, speech))))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_models_missing, "asr/encoder.onnx")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_models_damaged, "asr/tokens.txt")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAboutPageShowsEachModel() {
        val speech = ModelStatus(SPEECH, missing = listOf("asr/encoder.onnx", "asr/tokens.txt"), damaged = listOf("asr/joiner.onnx"))
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, speech))))
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.about_model_ready, "Voice (MIT)")).performScrollTo().assertIsDisplayed()
        val missing = compose.activity.resources.getQuantityString(R.plurals.about_model_missing, 2, "Speech (CC-BY-4.0)", 2)
        compose.onNodeWithText(missing).performScrollTo().assertIsDisplayed()
        val damaged = compose.activity.resources.getQuantityString(R.plurals.about_model_damaged, 1, "Speech (CC-BY-4.0)", 1)
        compose.onNodeWithText(damaged).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theChecklistAndTheAboutPageCheckTheModelsWhenTheyOpen() {
        show(NOTHING_SET_UP)
        compose.waitForIdle()
        assertEquals(0, checks)

        openChecklist()
        compose.waitForIdle()
        assertEquals(1, checks)

        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(progress(2)).assertIsDisplayed()
        assertEquals(1, checks)

        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.about_version, "9.9.9 (99)")).assertIsDisplayed()
        assertEquals(2, checks)
    }

    @Test
    fun theAboutPageChecksTheModelsAgainAfterALicenceText() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.about_version, "9.9.9 (99)")).assertIsDisplayed()
        assertEquals(1, checks)

        compose.onNodeWithText(text(R.string.about_licence_apache)).performScrollTo().performClick()
        compose.onNodeWithText("The first paragraph, wrapped.").assertIsDisplayed()
        assertEquals(1, checks)

        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.about_version, "9.9.9 (99)")).assertIsDisplayed()
        assertEquals(2, checks)
    }

    @Test
    fun theCallStepAsksForThePhonePermissions() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_calls_detail)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_allow_calls)).performScrollTo().performClick()

        assertEquals(listOf(SetupAction.CALL_PERMISSIONS), actions)
    }

    @Test
    fun theAssistantStepOpensTheDefaultApps() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_open_default_apps)).performScrollTo().performClick()

        assertEquals(listOf(SetupAction.DEFAULT_APPS), actions)
    }

    @Test
    fun theListeningPageChangesTheOptions() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_listening)).performClick()

        compose.onNodeWithText(text(R.string.listening_wake_up_resume)).assertIsSelected()
        compose.onNodeWithText(text(R.string.listening_wake_up_stay_off)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.listening_pause_after_silence)).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.listening_call_commands)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.listening_earbud_microphone)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.listening_earbud_microphone)).assertIsOn()
        compose.onNodeWithText(text(R.string.listening_wake_up_stay_off)).assertIsSelected()
        assertEquals(
            ListeningOptions(WakeUpMode.STAY_OFF, pauseAfterSilence = true, callCommands = true, earbudMicrophone = true),
            data.listening.value,
        )
    }

    @Test
    fun theListeningPageShowsTheWaysToStartKestrel() {
        show(NOTHING_SET_UP.copy(serviceOn = true, shortcutTarget = true))
        compose.onNodeWithText(text(R.string.settings_listening)).performClick()

        compose.onNodeWithText(text(R.string.listening_service_on)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_shortcut_set)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_button_not_set)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_tile)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_assistant_not_held)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_default_apps)).performScrollTo().performClick()

        assertEquals(listOf(SetupAction.DEFAULT_APPS), actions)
    }

    @Test
    fun theCommandsPageChangesTheOptionsAndListsTheCommands() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_commands)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.commands_numbers_all)).assertIsSelected()
        compose.onNodeWithText(text(R.string.commands_require_verbs)).assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.commands_numbers_unlabeled)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.commands_require_verbs)).assertIsOn()
        compose.onNodeWithText(text(R.string.commands_numbers_unlabeled)).assertIsSelected()
        assertEquals(CommandOptions(requireVerbs = true, numbers = NumbersMode.UNLABELED), data.commandOptions.value)
        // The list that "What can I say?" opens: each group of commands with its examples.
        val group = CommandHelp.groups.first()
        compose.onAllNodesWithText(group.title).onFirst().performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(group.examples.first()).onFirst().performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRequestedPageOpensAndTheSameRequestAgainSwitchesBackToIt() {
        var request by mutableStateOf<PageRequest?>(PageRequest(SettingsPage.COMMANDS))
        compose.setContent { MaterialTheme { SettingsApp(NOTHING_SET_UP, data, { actions += it }, { checks++ }, request) { exports++ } } }
        compose.onNodeWithText(text(R.string.commands_list)).assertExists()

        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.settings_checklist)).assertIsDisplayed()
        // A second "What can I say?" while the app shows another page.
        request = PageRequest(SettingsPage.COMMANDS)

        compose.onNodeWithText(text(R.string.commands_list)).assertExists()
    }

    private fun openVoicePage() {
        compose.onNodeWithText(text(R.string.settings_voice)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.voice_choice)).assertIsDisplayed()
    }

    @Test
    fun kokoroCanBeChosenWhenItsFilesAreReady() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, READY_KOKORO))))
        openVoicePage()
        // The page checks the models when it opens, so that it offers Kokoro only while its files are ready.
        compose.waitForIdle()
        assertEquals(1, checks)

        compose.onNodeWithText(text(R.string.voice_piper)).assertIsSelected()
        compose.onNodeWithText(text(R.string.voice_kokoro)).performClick()

        compose.onNodeWithText(text(R.string.voice_kokoro)).assertIsSelected()
        assertEquals(VoiceChoice.KOKORO, data.voiceOptions.value.voice)
        compose.onAllNodesWithText(text(R.string.voice_kokoro_missing)).assertCountEquals(0)
    }

    @Test
    fun kokoroCannotBeChosenWithoutItsFiles() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, ABSENT_KOKORO))))
        openVoicePage()

        compose.onNodeWithText(text(R.string.voice_kokoro)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.voice_kokoro_missing)).assertIsDisplayed()
        assertEquals(VoiceChoice.PIPER, data.voiceOptions.value.voice)
    }

    @Test
    fun theSpeedChangesInStepsAndStopsAtItsLimit() {
        show(NOTHING_SET_UP)
        openVoicePage()

        repeat(4) { compose.onNodeWithText(text(R.string.voice_faster)).performClick() }

        compose.onNodeWithText(text(R.string.voice_speed, "1.4")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_faster)).assertIsNotEnabled()
        assertEquals(1.4f, data.voiceOptions.value.speed)
        compose.onNodeWithText(text(R.string.voice_slower)).performClick()
        compose.onNodeWithText(text(R.string.voice_speed, "1.3")).assertIsDisplayed()
    }

    @Test
    fun tryItSpeaksWithTheChoiceOnThePage() {
        data.voiceOptions.value = VoiceOptionsSetting(VoiceChoice.PIPER, speed = 1.2f)
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_voice_summary, text(R.string.voice_piper_short), "1.2")).assertIsDisplayed()
        openVoicePage()

        compose.onNodeWithText(text(R.string.voice_try)).performScrollTo().performClick()

        compose.waitUntil(5_000) { data.tried.isNotEmpty() }
        assertEquals(listOf(VoiceOptionsSetting(VoiceChoice.PIPER, speed = 1.2f)), data.tried)
    }

    @Test
    fun aFailedTryGoesWhenTheChoiceChanges() {
        data.tryFailure = IllegalStateException("Android refused the audio")
        show(NOTHING_SET_UP)
        openVoicePage()

        val failed = text(R.string.voice_try_failed)
        compose.onNodeWithText(text(R.string.voice_try)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.voice_faster)).performScrollTo().performClick()

        compose.onAllNodesWithText(failed).assertCountEquals(0)
    }

    @Test
    fun theVoicePageSaysWhenKokoroDidNotLoad() {
        data.voiceOptions.value = VoiceOptionsSetting(VoiceChoice.KOKORO)
        data.kokoroFailed.value = true
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, READY_KOKORO))))
        openVoicePage()

        compose.onNodeWithText(text(R.string.voice_kokoro_failed)).assertIsDisplayed()
        // With Piper chosen, the page has nothing to say about Kokoro.
        compose.onNodeWithText(text(R.string.voice_piper)).performClick()
        compose.onAllNodesWithText(text(R.string.voice_kokoro_failed)).assertCountEquals(0)
    }

    @Test
    fun theAboutPageSaysThatAnOptionalModelIsNotOnThePhone() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, ABSENT_KOKORO))))
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.about_model_absent, "Kokoro (Apache-2.0)")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theChecklistDoesNotNameTheFilesOfAnOptionalModelThatIsNotOnThePhone() {
        show(NOTHING_SET_UP.copy(models = ModelCheck.Done(listOf(READY_VOICE, ABSENT_KOKORO))))
        openChecklist()

        compose.onAllNodesWithText(text(R.string.setup_models_missing, "tts-kokoro/model.onnx")).assertCountEquals(0)
    }

    private class FakeSettingsData : SettingsData {
        override val testMode = MutableStateFlow(true)
        override val listening = MutableStateFlow(ListeningOptions())
        override val commandOptions = MutableStateFlow(CommandOptions())
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

        override val voiceOptions = MutableStateFlow(VoiceOptionsSetting())
        val tried = mutableListOf<VoiceOptionsSetting>()

        override suspend fun setVoiceOptions(options: VoiceOptionsSetting) {
            voiceOptions.value = options
        }

        var tryFailure: Exception? = null

        override suspend fun tryVoice(options: VoiceOptionsSetting) {
            tried += options
            tryFailure?.let { throw it }
        }

        override val kokoroFailed = MutableStateFlow(false)

        override suspend fun setListening(options: ListeningOptions) {
            listening.value = options
        }

        override suspend fun setCommandOptions(options: CommandOptions) {
            commandOptions.value = options
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
        private val KOKORO = Model(
            "tts-kokoro", Engine.TTS, "Kokoro", "Apache-2.0", "https://example.org",
            listOf(ModelFile("tts-kokoro/model.onnx", 1, "0".repeat(64))), optional = true,
        )
        val READY_KOKORO = ModelStatus(KOKORO, emptyList(), emptyList())
        val ABSENT_KOKORO = ModelStatus(KOKORO, listOf("tts-kokoro/model.onnx"), emptyList())
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
            models = ModelCheck.Done(listOf(READY_VOICE)),
            callsAllowed = true,
        )
    }
}
