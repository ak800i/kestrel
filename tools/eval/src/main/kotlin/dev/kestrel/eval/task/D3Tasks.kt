package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import kotlin.time.Duration.Companion.seconds

/**
 * The six tasks of D3 of sub-project 5 (design D8 of sub-project 6), with task-check.ps1's requests, answers, steps and
 * checks. Telegram's tasks and Waze run on the A71 only, the hotspot on kestrel-api37 only (a limit of the A71), and
 * telegram-photos is a known gap of v1, for v1.1: test mode blocks every row of Telegram's share picker, so it runs once
 * and passes when test mode ended it with no photo shared. Without a model each runs its stand-in on `:testapp`'s
 * fixtures, and Waze, which has none, is skipped.
 */
object D3Tasks {
    private val A71 = setOf(DeviceKind.A71)
    private val EMULATOR = setOf(DeviceKind.EMULATOR)
    private val BOTH = setOf(DeviceKind.EMULATOR, DeviceKind.A71)
    const val FIREFOX = "org.mozilla.firefox"
    const val TELEGRAM_ALIAS = "the Telegram chat 'Saved Messages'"

    val TELEGRAM_LATE = Task(
        name = "telegram-late",
        kind = TaskKind.D3,
        request = Request("send {name} a Telegram message that I'm 10 minutes late for {marker}"),
        devices = A71,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.Finished,
        setup = listOf(TestName("Ana", telegram = true), MemoryAlias("{name}", TELEGRAM_ALIAS)),
        check = listOf(Confirmed(min = 1), Recipient("Saved Messages"), ScreenHas(contains = "{marker}", count = 1..1), SelfChat),
        dryRun = DryRun("stand-in-messenger-late.json", standIn = "stand-in-messenger-late"),
        rehearsal = Rehearsal.SetupOnly("its check reads the send that Kestrel confirmed, which only a task makes"),
    )

    val HOTSPOT = Task(
        name = "hotspot",
        kind = TaskKind.D3,
        request = Request("turn on hotspot"),
        devices = EMULATOR,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(HotspotOff),
        check = listOf(HotspotOn),
        dryRun = DryRun("stand-in-hotspot.json", standIn = "stand-in-hotspot"),
        rehearsal = Rehearsal.SetupOnly("Android 17 refuses the shell's cmd wifi start-softap, and the hotspot's switch is the task's own work"),
    )

    val WAZE_HOME = Task(
        name = "waze-home",
        kind = TaskKind.D3,
        request = Request("start Waze navigation to home"),
        devices = A71,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(TestLocation(), WazeHome, ForceStop(WazeHome.WAZE)),
        check = listOf(WazeNavigating),
        dryRun = null,
        rehearsal = Rehearsal.Steps(listOf(OpenUri(WazeHome.HOME, WazeHome.WAZE, settle = 20.seconds))),
    )

    val FIREFOX_WIKI = Task(
        name = "firefox-wiki",
        kind = TaskKind.D3,
        request = Request("open en.wikipedia.org in Firefox and search for common kestrel"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(ForceStop(FIREFOX)),
        check = listOf(Foreground(setOf(FIREFOX)), ScreenHas(contains = "Common kestrel")),
        dryRun = DryRun("stand-in-firefox.json", standIn = "stand-in-firefox"),
        rehearsal = Rehearsal.Steps(listOf(OpenUri("https://en.wikipedia.org/wiki/Common_kestrel", FIREFOX, settle = 10.seconds))),
    )

    val TELEGRAM_PHOTOS = Task(
        name = "telegram-photos",
        kind = TaskKind.D3,
        request = Request("send yesterday's photos to {name} on Telegram"),
        devices = A71,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.KnownGap,
        setup = listOf(TestName("John", telegram = true), Photos("yesterday", 3), Grant("dev.kestrel", Grant.MEDIA), MemoryAlias("{name}", TELEGRAM_ALIAS)),
        check = listOf(ShareItems(0)),
        dryRun = DryRun("stand-in-messenger-photos.json", standIn = "stand-in-messenger-photos"),
        rehearsal = Rehearsal.SetupOnly("its check reads what Kestrel shared, which only a task does"),
        runsAtMost = 1,
        gap = "v1.1",
    )

    val READ_EMAIL = Task(
        name = "read-email",
        kind = TaskKind.D3,
        request = Request("read my latest email back to me"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(MailToSelf),
        check = listOf(ReplyHas, Confirmed(max = 0)),
        dryRun = DryRun("stand-in-read.json", standIn = "stand-in-read"),
        rehearsal = Rehearsal.SetupOnly("its check reads Kestrel's spoken reply"),
    )

    val ALL = listOf(TELEGRAM_LATE, HOTSPOT, WAZE_HOME, FIREFOX_WIKI, TELEGRAM_PHOTOS, READ_EMAIL)
}
