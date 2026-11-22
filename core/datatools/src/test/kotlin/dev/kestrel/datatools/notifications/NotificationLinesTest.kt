package dev.kestrel.datatools.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NotificationLinesTest {
    private val belgrade = ZoneId.of("Europe/Belgrade")

    private fun at(time: String) = LocalDateTime.parse("2026-11-20T$time").atZone(belgrade).toInstant().toEpochMilli()

    private fun facts(
        packageName: String = "org.telegram.messenger",
        postTime: Long = at("10:20"),
        title: String? = "Ana Petrović",
        messages: List<Pair<String?, String?>> = emptyList(),
        bigText: String? = null,
        textLines: List<String> = emptyList(),
        text: String? = null,
        subText: String? = null,
    ) = NotificationFacts(packageName, "Telegram", postTime, title, messages, bigText, textLines, text, subText)

    private fun fields(facts: NotificationFacts, sdk: Int = 37) =
        NotificationLines.lines(listOf(facts), "dev.kestrel", { false }, sdk, belgrade).single().fields.map { it.name to it.value }

    @Test
    fun theTextIsTheLastFiveMessagesElseTheBigTextElseTheLinesElseTheText() {
        val messages = (1..7).map { "Ana" to "message $it" } + (null to "from me")
        assertEquals(
            listOf("app" to "Telegram", "title" to "Ana Petrović") + (4..7).map { "message" to "Ana: message $it" } + ("message" to "from me") + ("sub" to "2 chats"),
            fields(facts(messages = messages, bigText = "big", text = "short", subText = "2 chats")),
        )
        assertEquals(listOf("app" to "Telegram", "title" to "Ana Petrović", "text" to "big"), fields(facts(bigText = "big", textLines = listOf("one"), text = "short")))
        assertEquals("one\ntwo", fields(facts(textLines = listOf("one", "two"), text = "short")).last().second)
        assertEquals("text" to "short", fields(facts(text = "short")).last())
    }

    @Test
    fun kestrelsOwnAndTheAgentFreeAppsNotificationsAreLeftOutAndTheNewestComeFirst() {
        val all = (0 until 25).map { facts(postTime = at("10:00") + it * 60_000L, text = "n$it") } +
            facts(packageName = "dev.kestrel", postTime = at("11:00"), text = "kestrel") +
            facts(packageName = "rs.bank.mobile", postTime = at("11:00"), text = "code")

        val lines = NotificationLines.lines(all, "dev.kestrel", { it == "rs.bank.mobile" }, 37, belgrade)

        assertEquals(NotificationLines.MAX, lines.size)
        assertEquals("notification · 2026-11-20 10:24", lines.first().fixed)
        assertEquals("n24", lines.first().fields.last().value)
        assertEquals("n5", lines.last().fields.last().value)
    }

    @Test
    fun aHiddenNotificationIsPassedOnWithANoteFromAndroid15On() {
        val hidden = facts(text = NotificationLines.HIDDEN_TEXT)

        assertEquals("notification · 2026-11-20 10:20 ${NotificationLines.HIDDEN_NOTE}", NotificationLines.lines(listOf(hidden), "dev.kestrel", { false }, 35, belgrade).single().fixed)
        // Android 13 hides nothing, so the same words there are the app's own text.
        assertEquals("notification · 2026-11-20 10:20", NotificationLines.lines(listOf(hidden), "dev.kestrel", { false }, 33, belgrade).single().fixed)
    }
}
