package dev.kestrel.conversation.model

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.ItemState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val id = ConversationId("0123456789abcdef0123456789abcdef")

    @Test
    fun aSnapshotRoundTripsThroughJsonWithItsVersion() {
        val record = CommandRecord(
            number = 1,
            atWall = 1_000,
            channel = InputChannel.TEXT,
            request = "Find the restaurant Mila sent me",
            lines = listOf(RecordLine(LineKind.OWNER_STEERED, "and Ana too", InputChannel.VOICE)),
            answer = "It's Ambar.",
            end = EndReason.FINISHED,
            data = listOf(DataNote("list_sms", 2, "from: \"Mila\"")),
            apps = listOf(AppUse("Viber", "com.viber.voip")),
            ledger = listOf("opened Viber"),
            checklist = Checklist(listOf(ChecklistItem("c1", "Open Viber", ItemState.DONE, step = 1))),
            allowance = setOf("sending"),
        )
        val snapshot = ConversationSnapshot(
            id = id,
            openedAtWall = 1_000,
            lastWall = 2_000,
            open = true,
            testMode = true,
            records = listOf(record),
            goal = GoalState(1, GoalPhase.PAUSED_AT_LIMIT, 1_500, GoalHold.LIMIT, rounds = 2, stepsBefore = 12),
            steering = listOf(PendingSteer("also Ana", InputChannel.TEXT)),
        )

        val text = json.encodeToString(ConversationSnapshot.serializer(), snapshot)

        assertTrue(text.contains("\"version\":1"))
        assertFalse(text.contains("\"open\""))
        assertEquals(snapshot.copy(open = false), json.decodeFromString(ConversationSnapshot.serializer(), text))
    }

    @Test
    fun theProcessesOwnPartsOfAGoalAreNotStored() {
        val goal = GoalState(2, GoalPhase.HANDED_OVER, 5, command = 7, ending = GoalEnding.STOPPING, handOver = 3)

        val decoded = json.decodeFromString(GoalState.serializer(), json.encodeToString(GoalState.serializer(), goal))

        assertEquals(GoalState(2, GoalPhase.HANDED_OVER, 5), decoded)
    }

    @Test
    fun aConversationIdIs32HexadecimalCharactersAndRandom() {
        val first = ConversationId.random()

        assertTrue(Regex("[0-9a-f]{32}").matches(first.value))
        assertNotEquals(first, ConversationId.random())
        assertThrows(IllegalArgumentException::class.java) { ConversationId("not an id") }
    }

    @Test
    fun aGoalIsHeldExactlyInAHeldPhase() {
        assertEquals(GoalPhase.INTERRUPTED, GoalState(1, GoalPhase.INTERRUPTED, 0, GoalHold.RESTART).phase)
        assertThrows(IllegalArgumentException::class.java) { GoalState(1, GoalPhase.ACTING, 0, GoalHold.LOCK) }
        assertThrows(IllegalArgumentException::class.java) { GoalState(1, GoalPhase.PAUSED, 0) }
        assertThrows(IllegalArgumentException::class.java) { GoalState(1, GoalPhase.PAUSED, 0, GoalHold.LIMIT) }
    }

    @Test
    fun theFollowUpWindowIsNeverLongerThanTheQuietTime() {
        assertEquals(60_000L, ConversationSettings().quietMillis)
        assertEquals(8_000L, ConversationSettings().followUpMillis)
        assertEquals(30_000L, ConversationSettings(quietSeconds = 30, followUpSeconds = 45).followUpMillis)
        assertEquals(30_000L, ConversationSettings(quietSeconds = 5).quietMillis)
        assertEquals(0L, ConversationSettings(followUpSeconds = 0).followUpMillis)
    }

    @Test
    fun aStoredValueOutsideThePageIsReadWithinItsBounds() {
        val stored = ConversationSettings(retentionDays = 1_000, limits = GoalLimits(replies = 2, workMinutes = 600, credits = 1))

        assertEquals(365, stored.keptDays)
        assertEquals(GoalLimits(replies = 50, workMinutes = 240, credits = 10), stored.roundLimits)
        assertEquals(GoalLimits(), ConversationSettings().roundLimits)
        assertEquals(30, ConversationSettings().keptDays)
    }
}
