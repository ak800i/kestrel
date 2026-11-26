package dev.kestrel.app.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.conversation.screen.PAST_SEARCH_TAG
import dev.kestrel.app.conversation.screen.pastRowTag
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.model.ConversationId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * Past conversations on kestrel-api37 (design 9.5, 9.6 and 15.2 of sub-project 7): the list of closed conversations, its
 * search, a transcript and "Continue this conversation", and the deletion from the list and from a transcript, each after
 * its question. The owner's own past conversations stay in the list: each test reads only the rows of the conversations
 * that it made, which it finds by their tags and through the search field with the run's marker, and deletes only those.
 */
@RunWith(AndroidJUnit4::class)
class PastConversationsTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val graph get() = TypedTests.graph
    private val conversations get() = graph.conversations
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Before
    fun start() {
        TypedTests.prepare(ScriptedModel(listOf(fail, fail)))
    }

    @After
    fun stop() {
        try {
            TypedTests.finish()
        } finally {
            screen.shell("am force-stop $FIXTURES")
        }
    }

    @Test
    fun theListShowsTheClosedConversationsItsSearchFiltersThemAndOneIsContinued() {
        val restaurant = closed(RESTAURANT)
        val fixture = closed(FIXTURE)

        openList(TypedTests.marker)
        awaitRow(restaurant)
        awaitRow(fixture)
        // The newest first: the fixture's row is above the restaurant's.
        assertTrue(top(fixture) < top(restaurant))
        compose.onNodeWithTag(PAST_SEARCH_TAG).performTextClearance()
        compose.onNodeWithTag(PAST_SEARCH_TAG).performTextInput("restaurant")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(pastRowTag(fixture)).fetchSemanticsNodes().isEmpty() }
        awaitRow(restaurant)
        compose.onNodeWithText(TypedTests.marked(RESTAURANT)).performClick()
        compose.onNodeWithText(TypedTests.text(R.string.past_continue)).performClick()

        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == restaurant }) { "The conversation was not continued" }
        val continued = TypedTests.text(R.string.conversation_continued, ConversationTexts.date(checkNotNull(conversations.view.value.continuedAtWall), ZoneId.systemDefault()))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(continued)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(FIELD_TAG).assertIsDisplayed()
    }

    @Test
    fun aPastConversationIsDeletedAfterItsQuestionFromTheListAndFromItsTranscript() {
        val restaurant = closed(RESTAURANT)
        val fixture = closed(FIXTURE)

        openList(TypedTests.marker)
        awaitRow(fixture)
        compose.onNode(hasText(TypedTests.text(R.string.past_delete)) and hasAnyAncestor(hasTestTag(pastRowTag(fixture)))).performClick()
        confirmDelete()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(pastRowTag(fixture)).fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { graph.conversationStore.list() }.none { it.id == fixture })

        awaitRow(restaurant)
        compose.onNodeWithText(TypedTests.marked(RESTAURANT)).performClick()
        compose.onNodeWithText(TypedTests.text(R.string.past_continue)).assertIsDisplayed()
        compose.onNodeWithText(TypedTests.text(R.string.past_delete)).performClick()
        confirmDelete()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(PAST_SEARCH_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(pastRowTag(restaurant)).fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { graph.conversationStore.list() }.none { it.id == restaurant })
    }

    // A closed conversation whose one command was [words] with the run's marker, typed on the screen over the fixture.
    private fun closed(words: String): ConversationId {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        AgentTests.ended(TypedTests.send(compose, words))
        val id = checkNotNull(conversations.view.value.id)
        TypedTests.bringScreen(screen)
        compose.onNodeWithText(TypedTests.text(R.string.conversation_new)).performClick()
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
        return id
    }

    // The list with [query] in its search field, which keeps the owner's own conversations out of the way.
    private fun openList(query: String) {
        compose.onNodeWithText(TypedTests.text(R.string.conversation_more)).performClick()
        compose.onNodeWithText(TypedTests.text(R.string.past_title)).performClick()
        compose.onNodeWithTag(PAST_SEARCH_TAG).performTextInput(query)
    }

    private fun awaitRow(id: ConversationId) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(pastRowTag(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun top(id: ConversationId): Float = compose.onNodeWithTag(pastRowTag(id)).fetchSemanticsNode().boundsInRoot.top

    private fun confirmDelete() {
        compose.onNodeWithText(TypedTests.text(R.string.past_delete_question)).assertIsDisplayed()
        compose.onNode(hasText(TypedTests.text(R.string.past_delete)) and hasAnyAncestor(isDialog())).performClick()
    }

    private companion object {
        const val RESTAURANT = "Find the restaurant Mila sent me"
        const val FIXTURE = "Look at the fixture"
    }
}
