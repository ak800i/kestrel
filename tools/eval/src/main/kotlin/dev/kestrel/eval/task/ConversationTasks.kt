package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.run.EvalAccess

/**
 * The multi-turn tasks of design 15.3 of sub-project 7 (plan 7G), which run apart from the suite (`suite --multi-turn`),
 * with a baseline part of their own. Each starts in a conversation of its own, as every run does. Their scripts check what
 * reached the model with `requires` and `refuses`: the records block of an open conversation and its absence in a new
 * one, the marker from a search of past conversations or from a continued conversation's records, the owner's steering,
 * and why a resumed round resumes. Four of them run only with the scripted model ([Task.runsLive]): they need a goal that
 * still acts at a known step when the run steers it, touches the screen or ends Kestrel's process, or replies of a known
 * cost, which a live model's timing and costs do not give.
 */
object ConversationTasks {
    private val BOTH = setOf(DeviceKind.EMULATOR, DeviceKind.A71)
    private val EMULATOR = setOf(DeviceKind.EMULATOR)
    private val A71 = setOf(DeviceKind.A71)
    private val SETTINGS = SettingsTasks.START + listOf(UiNightMode(on = false), SettingsValue("system", "screen_off_timeout", "30000"))
    private val SETTINGS_CHECKS = listOf(NightMode(on = true), SettingsIs("system", "screen_off_timeout", Wanted.Equals("120000")))
    private val SMS = listOf(KeptPermissions(listOf(EvalAccess.READ_SMS)), EmuSms("+1 202 555 0177", "Kestrel test: the code word is {marker}"))

    // Two typed commands: the second's "it" is the number that the first one found.
    private val TYPED = listOf(
        Turn.Say(Request("Find the number of {name} Test."), TurnChannel.TYPED),
        Turn.Expect(ReplyHasDigits("{number}")),
        Turn.Say(Request("Send it to me in Telegram's Saved Messages."), TurnChannel.TYPED),
    )
    private val CONTACT = listOf(KeptPermissions(listOf(EvalAccess.READ_CONTACTS)), FreeNumber, DeviceContact("{name} Test", "{number}"))

    /** Design 6.6: the second command is spoken without the name, inside the follow-up window, in the same conversation. */
    val FOLLOWUP_SETTINGS = Task(
        name = "followup-settings",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request("turn on dark mode")),
            Turn.Say(Request("and set the screen timeout to 2 minutes"), TurnChannel.FOLLOW_UP),
            Turn.Expect(ConversationIs(open = true, commands = 2)),
        ),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = SETTINGS,
        check = SETTINGS_CHECKS,
        dryRun = DryRun("followup-settings.json"),
        rehearsal = Rehearsal.Steps(listOf(SettingsTasks.NIGHT, SettingsTasks.TWO_MINUTES)),
    )

    /** Design 6.6 and 9.6: after the quiet time, the next command opens a new conversation, whose model sees no records block. */
    val QUIET_TIME_ENDS = Task(
        name = "quiet-time-ends",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request("turn on dark mode")),
            Turn.Expect(ConversationIs(open = true, commands = 1)),
            Turn.Act(EndQuietTime),
            Turn.Say(Request("set the screen timeout to 2 minutes")),
            Turn.Expect(ConversationIs(open = true, commands = 1)),
        ),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = SETTINGS,
        check = SETTINGS_CHECKS,
        dryRun = DryRun("quiet-time-ends.json"),
        rehearsal = Rehearsal.Steps(listOf(SettingsTasks.NIGHT, SettingsTasks.TWO_MINUTES)),
    )

    /**
     * Design 10.3 and 11: two typed commands, whose questions the run answers on the text channel's cards; the number
     * arrives in the owner's Telegram Saved Messages. Saved Messages keeps earlier runs' messages, so the arrival is
     * checked by the confirmed send, its recipient and the self-chat, never by a count on the screen.
     */
    val TYPED_CONTACT_THEN_MESSAGE = Task(
        name = "typed-contact-then-message",
        kind = TaskKind.CONVERSATION,
        turns = TYPED,
        devices = A71,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.Finished,
        setup = listOf(TestName("Ana", telegram = true)) + CONTACT,
        check = listOf(Confirmed(min = 1), Recipient("Saved Messages"), SelfChat, AskedInOrder(listOf("confirm/sending:confirm"))),
        dryRun = DryRun("stand-in-typed-contact-then-message.json", standIn = "stand-in-typed-contact-then-message"),
        rehearsal = Rehearsal.SetupOnly("its checks read the send that Kestrel confirmed, which only a task makes"),
    )

    /**
     * typed-contact-then-message on `:testapp`'s messenger, whose Saved Messages starts with no message of a run: its dry run
     * on kestrel-api37. Its checks of the two questions and of the screen come from its script, so it runs only with the
     * scripted model; live, the A71's task runs.
     */
    val STAND_IN_TYPED_CONTACT_THEN_MESSAGE = Task(
        name = "stand-in-typed-contact-then-message",
        kind = TaskKind.CONVERSATION,
        turns = TYPED,
        devices = EMULATOR,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.Finished,
        setup = listOf(TestName("Ana", telegram = false)) + CONTACT + listOf(FixturePresses, SelfChatAliases.TELEGRAM, FixtureScreen("MessengerActivity")),
        check = listOf(
            Confirmed(min = 1), Recipient("Saved Messages"), SelfChat, AskedInOrder(listOf("confirm/typing_screen_text:confirm", "confirm/sending:confirm")),
            Presses(listOf("send")), ScreenHas(packageName = Fixtures.PACKAGE, contains = "{number}", count = 1..1),
        ),
        dryRun = DryRun("stand-in-typed-contact-then-message.json"),
        rehearsal = Rehearsal.SetupOnly("its checks read the send that Kestrel confirmed, which only a task makes"),
        testApp = true,
        runsLive = false,
    )

    /**
     * Design 9.4: the SMS is deleted and the quiet time ends before the question, so that its answer can come only from a
     * search of past conversations.
     */
    val SEARCH_PAST = Task(
        name = "search-past",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request("read my latest text message")),
            Turn.Act(DeleteTestSms),
            Turn.Act(EndQuietTime),
            Turn.Say(Request("what was the code word in the text message that you read me before?")),
            Turn.Expect(ConversationIs(open = true, commands = 1)),
        ),
        devices = EMULATOR,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = SMS,
        check = listOf(ReplyHas),
        dryRun = DryRun("search-past.json"),
        rehearsal = Rehearsal.SetupOnly("its check reads Kestrel's spoken reply"),
    )

    /**
     * Design 9.6 and D23: after the quiet time, the owner continues the past conversation; Kestrel asks before it switches,
     * the run says yes, and the follow-up's answer comes from the continued conversation's records.
     */
    val CONTINUE_PAST = Task(
        name = "continue-past",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request("read my latest SMS")),
            Turn.Act(EndQuietTime),
            Turn.Say(Request("continue the conversation about my latest SMS")),
            Turn.Expect(ConversationIs(open = true, continued = true, commands = 1)),
            Turn.Say(Request("what was the code word in it?"), TurnChannel.FOLLOW_UP),
            Turn.Expect(ConversationIs(continued = true, commands = 2)),
        ),
        devices = EMULATOR,
        answers = Answers(switches = true),
        verdict = VerdictRule.Finished,
        setup = SMS,
        check = listOf(ReplyHas, AskedInOrder(listOf("switch:yes"))),
        dryRun = DryRun("continue-past.json"),
        rehearsal = Rehearsal.SetupOnly("its check reads Kestrel's spoken reply"),
    )

    // telegram-late's request and its stand-in's steps, checks and answers on `:testapp`'s messenger.
    private const val LATE = "send {name} a Telegram message that I'm 10 minutes late for {marker}"
    private val MESSENGER =
        listOf(TestName("Ana", telegram = false), FixturePresses, SelfChatAliases.TELEGRAM, MemoryAlias("{name}", StandIns.FIXTURE_ALIAS), FixtureScreen("MessengerActivity"))
    private val SENT = listOf(
        Confirmed(min = 1), Recipient("Saved Messages"), ScreenHas(packageName = Fixtures.PACKAGE, contains = "{marker}", count = 1..1), SelfChat, Presses(listOf("send")),
    )

    /** Design 7.3: the owner's words while the goal acts reach it at its next step, and it sets the alarm that they ask for. */
    val STEER_WHILE_RUNNING = Task(
        name = "steer-while-running",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request("set an alarm for 7:00 tomorrow morning"), until = Until.ACTING),
            Turn.Steer(Request("make it 7:30 instead")),
            Turn.AwaitEnd(),
        ),
        devices = EMULATOR,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(NoOwnerAlarms, HomeScreen, ClockFree),
        check = listOf(OnlyAlarmAt(7, 30)),
        dryRun = DryRun("steer-while-running.json"),
        rehearsal = Rehearsal.SetupOnly("the alarm's time comes from the owner's steering, which only a task gets"),
        runsLive = false,
    )

    /**
     * Design 7.4: the round reaches its credit limit with the page's smallest limits; the run says yes to the first
     * "Continue?", leaves the second to Kestrel's own wait, so that the goal pauses at its limit, and then says "continue".
     */
    val LIMIT_CONTINUE = Task(
        name = "limit-continue",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request("turn on hotspot"), ends = "PAUSED_AT_LIMIT"),
            Turn.Expect(PhaseIs("paused-at-limit")),
            Turn.Say(Request("continue")),
        ),
        devices = EMULATOR,
        answers = Answers(atLimits = listOf(AtLimit.YES, AtLimit.SILENCE)),
        verdict = VerdictRule.Finished,
        setup = listOf(SmallestLimits, FixturePresses, FixtureScreen("StandInActivity", mapOf("screen" to "hotspot"))),
        check = listOf(AskedInOrder(listOf("limit:yes", "limit:none")), ScreenHas(packageName = Fixtures.PACKAGE, label = "Wi-Fi hotspot", state = "checked", count = 1..1)),
        dryRun = DryRun("limit-continue.json"),
        rehearsal = Rehearsal.SetupOnly("its checks read the run's answers at the limit, which only a task asks"),
        testApp = true,
        runsLive = false,
    )

    /** Design 7.2 and D13: the owner's touch interrupts the goal, and "continue" resumes it from its checklist. */
    val INTERRUPT_CONTINUE = Task(
        name = "interrupt-continue",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request(LATE), until = Until.ACTING),
            Turn.Expect(ChecklistIs(1, 2)),
            Turn.Act(OwnerTouch),
            Turn.AwaitEnd(ends = "INTERRUPTED"),
            Turn.Expect(PhaseIs("interrupted")),
            Turn.Say(Request("continue")),
        ),
        devices = EMULATOR,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.Finished,
        setup = MESSENGER,
        check = SENT,
        dryRun = DryRun("interrupt-continue.json"),
        rehearsal = Rehearsal.SetupOnly("its checks read the send that Kestrel confirmed, which only a task makes"),
        testApp = true,
        runsLive = false,
    )

    /**
     * Design 6.7: Kestrel's process ends while the goal acts; the restore at the new process's start holds the goal as
     * interrupted, and "continue" resumes it in a new round from its checklist (design 6.4), with the scripted model's
     * second script and the self-chat aliases set again, because the first script and the aliases ended with the process.
     * Its first step waits until Kestrel's guard against a crash loop has passed ([CrashGuardPassed]).
     */
    val RESUME_AFTER_DEATH = Task(
        name = "resume-after-death",
        kind = TaskKind.CONVERSATION,
        turns = listOf(
            Turn.Say(Request(LATE), until = Until.ACTING),
            Turn.Expect(ChecklistIs(1, 2)),
            Turn.Restart,
            Turn.Expect(RestoredAs("goal-held")),
            Turn.Expect(ConversationIs(open = true, goal = "interrupted")),
            Turn.Say(Request("continue"), until = Until.ACTING),
            Turn.Expect(ChecklistIs(1, 2)),
            Turn.AwaitEnd(),
        ),
        devices = EMULATOR,
        answers = Answers.MESSAGING,
        verdict = VerdictRule.Finished,
        setup = listOf(CrashGuardPassed) + MESSENGER,
        check = SENT,
        dryRun = DryRun("resume-after-death.json", restarts = listOf("resume-after-death-2.json")),
        rehearsal = Rehearsal.SetupOnly("its checks read the send that Kestrel confirmed, which only a task makes"),
        testApp = true,
        runsLive = false,
    )

    val ALL = listOf(
        FOLLOWUP_SETTINGS, QUIET_TIME_ENDS, TYPED_CONTACT_THEN_MESSAGE, STAND_IN_TYPED_CONTACT_THEN_MESSAGE, SEARCH_PAST, CONTINUE_PAST, STEER_WHILE_RUNNING,
        LIMIT_CONTINUE, INTERRUPT_CONTINUE, RESUME_AFTER_DEATH,
    )
}
