package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind

/**
 * The suite's SMS, e-mail and notification tasks (design D8 of sub-project 6). sms-read runs on kestrel-api37 only,
 * whose console sends the test SMS. email-self writes the owner's own address into Kestrel's memory and test mode, and
 * confirms typing and sending, because the body holds words beyond the request; its e-mail leaves through Gmail's own
 * deletion. notification-read needs `:testapp` on the device for its notification.
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
        verdict = VerdictRule.Finished,
        setup = listOf(OwnAddress, OwnerEmails, MemoryAlias("my e-mail address", "{address}"), MailCleanup),
        check = listOf(MailArrived),
        dryRun = DryRun("email-self.json"),
        rehearsal = Rehearsal.Steps(listOf(MailToSelf)),
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
