package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind

/**
 * The suite's SMS, e-mail and notification tasks (design D8 of sub-project 6). sms-read runs on kestrel-api37 only,
 * whose console sends the test SMS. email-self writes the owner's own address into Kestrel's memory and test mode, and
 * confirms typing and sending, because the body holds words beyond the request; an e-mail that left anyway goes
 * through Gmail's own deletion. email-self is a known gap of this baseline: test mode blocks Kestrel's Send in Gmail
 * until Gmail's self-send rule is pinned (a reviewed follow-up), so it runs once and passes when test mode ended it with
 * no e-mail sent. notification-read needs `:testapp` on the device for its notification.
 */
object MessageTasks {
    private val BOTH = setOf(DeviceKind.EMULATOR, DeviceKind.A71)

    val SMS_READ = Task(
        name = "sms-read",
        kind = TaskKind.SUITE,
        request = Request("read my latest text message"),
        devices = setOf(DeviceKind.EMULATOR),
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(Grant("dev.kestrel", listOf("android.permission.READ_SMS")), EmuSms("+1 202 555 0177", "Kestrel test {marker}")),
        check = listOf(ReplyHas),
        dryRun = DryRun("sms-read.json"),
        rehearsal = Rehearsal.SetupOnly("its check reads Kestrel's spoken reply"),
    )

    val EMAIL_SELF = Task(
        name = "email-self",
        kind = TaskKind.SUITE,
        request = Request("email me a reminder about {marker}"),
        devices = BOTH,
        answers = Answers(confirm = setOf("typing_screen_text", "sending")),
        verdict = VerdictRule.KnownGap,
        setup = listOf(OwnAddress, OwnerEmails, MemoryAlias("my e-mail address", "{address}"), MailCleanup),
        check = listOf(NoMail),
        dryRun = DryRun("email-self.json"),
        rehearsal = Rehearsal.SetupOnly("its known gap's check reads that test mode stopped Kestrel's send, which only a task makes"),
        runsAtMost = 1,
        gap = "the follow-up that pins Gmail's self-send rule in test mode",
    )

    val NOTIFICATION_READ = Task(
        name = "notification-read",
        kind = TaskKind.SUITE,
        request = Request("read my notifications"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(NotificationAccess, Grant(Fixtures.PACKAGE, listOf("android.permission.POST_NOTIFICATIONS")), ForceStop(Fixtures.PACKAGE), TestNotification),
        check = listOf(ReplyHas),
        dryRun = DryRun("notification-read.json"),
        rehearsal = Rehearsal.SetupOnly("its check reads Kestrel's spoken reply"),
        testApp = true,
    )

    val ALL = listOf(SMS_READ, EMAIL_SELF, NOTIFICATION_READ)
}
