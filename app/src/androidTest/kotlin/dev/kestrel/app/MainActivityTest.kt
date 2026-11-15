package dev.kestrel.app

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.safety.SafetySettings
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

    @After
    fun turnTestModeBackOn() = runBlocking {
        graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true))
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
