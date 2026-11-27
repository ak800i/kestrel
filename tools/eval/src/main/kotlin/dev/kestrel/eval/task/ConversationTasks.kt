package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.run.EvalAccess

/**
 * The multi-turn tasks of design 15.3 of sub-project 7 (plan 7G), which run apart from the suite (`suite --multi-turn`),
 * with a baseline part of their own. Each starts in a conversation of its own, as every run does. Their scripts check what
 * reached the model with `requires` and `refuses`: the records block of an open conversation and its absence in a new
 * one, the marker from a search of past conversations or from a continued conversation's records.
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

    val ALL = listOf(FOLLOWUP_SETTINGS, QUIET_TIME_ENDS, TYPED_CONTACT_THEN_MESSAGE, STAND_IN_TYPED_CONTACT_THEN_MESSAGE, SEARCH_PAST, CONTINUE_PAST)
}
