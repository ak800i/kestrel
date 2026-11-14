package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kestrel.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SetupAction>()

    private fun show(status: SetupStatus) {
        compose.setContent { MaterialTheme { SettingsApp(status) { actions += it } } }
    }

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

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

    private companion object {
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
        )
    }
}
