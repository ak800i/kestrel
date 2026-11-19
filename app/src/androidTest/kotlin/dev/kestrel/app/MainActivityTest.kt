package dev.kestrel.app

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks that the settings pages change Kestrel's real stores, which work on another thread than the pages. */
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = (compose.activity.application as KestrelApplication).graph

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun startsWith(prefix: String) = SemanticsMatcher("a text that starts with '$prefix'") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) }
    }

    @After
    fun turnTestModeBackOn() = runBlocking {
        graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true))
    }

    @After
    fun putTheCommandOptionsBack() = runBlocking {
        graph.settings.set(SettingKeys.COMMANDS, CommandOptions())
    }

    @Test
    fun theCommandsPageStoresTheOptions() {
        runBlocking { graph.settings.set(SettingKeys.COMMANDS, CommandOptions()) }
        compose.onNodeWithText(text(R.string.settings_commands)).performScrollTo().performClick()
        // The rows are disabled until the stored options have arrived from the settings store; a click before is lost.
        await(hasText(text(R.string.commands_require_verbs)) and isEnabled())

        compose.onNodeWithText(text(R.string.commands_require_verbs)).performClick()
        compose.onNodeWithText(text(R.string.commands_numbers_unlabeled)).performScrollTo().performClick()

        val expected = CommandOptions(requireVerbs = true, numbers = NumbersMode.UNLABELED)
        compose.waitUntil(5_000) { runBlocking { graph.settings.get(SettingKeys.COMMANDS) } == expected }
    }

    @Test
    fun theReturnFromASignInKeepsTheAddFlowOpen() {
        await(hasText(text(R.string.settings_checklist)))
        compose.runOnUiThread {
            // A copy of the activity's intent, as in anIntentWithAPageOpensThatPageInTheRunningApp.
            compose.activity.startActivity(
                Intent(compose.activity.intent)
                    .putExtra(MainActivity.EXTRA_PAGE, SettingsPage.PROVIDERS.name)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        await(hasText(text(R.string.providers_add)))
        compose.onNodeWithText(text(R.string.providers_add)).performScrollTo().performClick()
        await(hasText(text(R.string.providers_search)))

        // As SignInService does after a sign-in in the browser (design D10 of sub-project 4).
        compose.runOnUiThread {
            compose.activity.startActivity(
                Intent(compose.activity.intent)
                    .putExtra(MainActivity.EXTRA_PAGE, SettingsPage.PROVIDERS.name)
                    .putExtra(MainActivity.EXTRA_RESUME_ADD, true)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        compose.waitForIdle()

        await(hasText(text(R.string.providers_search)))
    }

    @Test
    fun anIntentWithAPageOpensThatPageInTheRunningApp() {
        await(hasText(text(R.string.settings_checklist)))

        // As "What can I say?" does while the settings app is in front: MainActivity is singleTop.
        compose.runOnUiThread {
            // A copy of the activity's intent: MainActivity takes each new intent as its own, and ActivityScenario follows the
            // activity, and closes it after the test, only while its intent has the action of the intent that started it.
            compose.activity.startActivity(
                Intent(compose.activity.intent)
                    .putExtra(MainActivity.EXTRA_PAGE, SettingsPage.COMMANDS.name)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        await(hasText(text(R.string.commands_list)))
    }

    @Test
    fun theOwnersPageStaysWhenTheActivityIsMadeAgainAfterAnIntentWithAPage() {
        await(hasText(text(R.string.settings_checklist)))
        compose.runOnUiThread {
            // A copy of the activity's intent, as in anIntentWithAPageOpensThatPageInTheRunningApp.
            compose.activity.startActivity(
                Intent(compose.activity.intent)
                    .putExtra(MainActivity.EXTRA_PAGE, SettingsPage.COMMANDS.name)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        await(hasText(text(R.string.commands_list)))
        // The owner moves on to another page.
        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()
        await(hasText(text(R.string.safety_test_mode)))

        // As after a rotation: the activity's intent still names the page of "What can I say?".
        compose.activityRule.scenario.recreate()

        await(hasText(text(R.string.safety_test_mode)))
        compose.onAllNodesWithText(text(R.string.commands_list)).assertCountEquals(0)
    }

    @Test
    fun theSafetySwitchTurnsTestModeOff() {
        runBlocking { graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true)) }
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()
        await(hasText(text(R.string.safety_test_mode)) and isOn())

        compose.onNodeWithText(text(R.string.safety_test_mode)).performClick()

        compose.waitUntil(5_000) { runBlocking { !graph.settings.get(SettingKeys.SAFETY).testMode } }
        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOff()
    }

    @Test
    fun theAboutPageShowsTheTextsThatTheBuildBundles() {
        compose.onNodeWithText(text(R.string.settings_about)).performScrollTo().performClick()
        val expected = mapOf(
            R.string.about_licence_kestrel to "GNU GENERAL PUBLIC LICENSE",
            R.string.about_licence_notices to "Kestrel's release build includes the libraries below",
            R.string.about_licence_apache to "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            R.string.about_licence_mit to "Permission is hereby granted, free of charge",
            R.string.about_licence_onnxruntime to "This software incorporates material from third parties.",
            R.string.about_licence_hclust to "Redistribution and use in source and binary forms",
            R.string.about_licence_mpl to "Mozilla Public License Version 2.0",
        )
        for ((title, start) in expected) {
            compose.onNodeWithText(text(title)).performScrollTo().performClick()
            await(hasText(start, substring = true))
            compose.onNodeWithText(text(R.string.settings_back)).performClick()
        }
    }

    @Test
    fun theChecklistShowsTheResultOfTheRealModelCheck() {
        compose.onNodeWithText(text(R.string.settings_checklist)).performClick()
        await(hasText(text(R.string.setup_models_title)))

        // The first check after a copy hashes about 740 MB of model files.
        compose.waitUntil(120_000) {
            compose.onAllNodesWithText(text(R.string.setup_models_checking)).fetchSemanticsNodes().isEmpty()
        }

        val done = hasText(text(R.string.setup_state_done)) and hasAnySibling(hasText(text(R.string.setup_models_title)))
        val problem = startsWith(compose.activity.getString(R.string.setup_models_missing, "")) or
            startsWith(compose.activity.getString(R.string.setup_models_damaged, ""))
        assertTrue(
            "Step 2 should be done, or name a missing or damaged file.",
            compose.onAllNodes(done).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(problem).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun deleteHistoryWipesBothLogs() {
        runBlocking {
            graph.logs.append(ActivityEvent(ActivityKind.NOTE, "A note from MainActivityTest"))
            graph.logs.append(NetworkEvent("GET", "example.com", "/", 0, 0, outcome = NetworkOutcome.BLOCKED))
        }
        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()
        await(hasText("A note from MainActivityTest"))

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_confirm)).performClick()

        await(hasText(text(R.string.logs_empty)))
        runBlocking {
            assertTrue(graph.logs.activity().isEmpty())
            assertTrue(graph.logs.network().isEmpty())
        }
    }
}
