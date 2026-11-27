package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind

/**
 * The stand-ins of the D3 tasks on `:testapp`'s fixtures (spec 16.3), which run with the scripted model on kestrel-api37,
 * as task-check.ps1's table had them. stand-in-messenger-late-reworded is the same task with the script whose reworded
 * message Kestrel asks to confirm before it types it.
 */
object StandIns {
    private val EMULATOR = setOf(DeviceKind.EMULATOR)
    const val FIXTURE_ALIAS = "the chat 'Saved Messages' in Messenger fixture"

    val MESSENGER_LATE = Task(
        name = "stand-in-messenger-late",
        kind = TaskKind.STAND_IN,
        turns = D3Tasks.TELEGRAM_LATE.turns,
        devices = EMULATOR,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.Finished,
        setup = listOf(
            TestName("Ana", telegram = false), FixturePresses, SelfChatAliases.TELEGRAM, MemoryAlias("{name}", FIXTURE_ALIAS), FixtureScreen("MessengerActivity"),
        ),
        check = listOf(
            Confirmed(min = 1), Recipient("Saved Messages"), ScreenHas(packageName = Fixtures.PACKAGE, contains = "{marker}", count = 1..1), SelfChat,
            Presses(listOf("send")),
        ),
        dryRun = DryRun("stand-in-messenger-late.json"),
        rehearsal = Rehearsal.SetupOnly("its check reads the send that Kestrel confirmed, which only a task makes"),
    )

    val MESSENGER_LATE_REWORDED = MESSENGER_LATE.copy(name = "stand-in-messenger-late-reworded", dryRun = DryRun("stand-in-messenger-late-reworded.json"))

    val MESSENGER_PHOTOS = Task(
        name = "stand-in-messenger-photos",
        kind = TaskKind.STAND_IN,
        turns = D3Tasks.TELEGRAM_PHOTOS.turns,
        devices = EMULATOR,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.KnownGap,
        setup = listOf(
            TestName("John", telegram = false), FixturePresses, Photos("yesterday", 3), KeptPermissions.MEDIA, SelfChatAliases.TELEGRAM,
            MemoryAlias("{name}", FIXTURE_ALIAS), FixtureScreen("MessengerActivity", mapOf("shareForm" to "tap")),
        ),
        check = listOf(ShareItems(0), Presses(emptyList())),
        dryRun = DryRun("stand-in-messenger-photos.json"),
        rehearsal = Rehearsal.SetupOnly("its check reads what Kestrel shared, which only a task does"),
        runsAtMost = 1,
        gap = "v1.1",
    )

    val HOTSPOT = Task(
        name = "stand-in-hotspot",
        kind = TaskKind.STAND_IN,
        turns = D3Tasks.HOTSPOT.turns,
        devices = EMULATOR,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(FixturePresses, FixtureScreen("StandInActivity", mapOf("screen" to "hotspot"))),
        check = listOf(ScreenHas(packageName = Fixtures.PACKAGE, label = "Wi-Fi hotspot", state = "checked", count = 1..1)),
        dryRun = DryRun("stand-in-hotspot.json"),
        rehearsal = Rehearsal.Steps(listOf(ScreenTap("Wi-Fi hotspot"))),
    )

    val FIREFOX = Task(
        name = "stand-in-firefox",
        kind = TaskKind.STAND_IN,
        turns = D3Tasks.FIREFOX_WIKI.turns,
        devices = EMULATOR,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(FixturePresses, FixtureScreen("StandInActivity", mapOf("screen" to "browser"))),
        check = listOf(ScreenHas(packageName = Fixtures.PACKAGE, contains = "Common kestrel")),
        dryRun = DryRun("stand-in-firefox.json"),
        rehearsal = Rehearsal.SetupOnly("the fixture shows \"Common kestrel\" only after a search with typed text, which the task's script types"),
    )

    val READ = Task(
        name = "stand-in-read",
        kind = TaskKind.STAND_IN,
        turns = D3Tasks.READ_EMAIL.turns,
        devices = EMULATOR,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(FixturePresses, FixtureScreen("StandInActivity", mapOf("screen" to "inbox", "marker" to "{marker}"))),
        check = listOf(ReplyHas, Confirmed(max = 0)),
        dryRun = DryRun("stand-in-read.json"),
        rehearsal = Rehearsal.SetupOnly("its check reads Kestrel's spoken reply"),
    )

    val ALL = listOf(MESSENGER_LATE, MESSENGER_LATE_REWORDED, MESSENGER_PHOTOS, HOTSPOT, FIREFOX, READ)
}
