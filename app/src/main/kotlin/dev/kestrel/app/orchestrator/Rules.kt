package dev.kestrel.app.orchestrator

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandParser
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition

/**
 * The listening modes (design spec, section 8.5). [RINGING]: a call rings, and Kestrel listens only for the call
 * commands, also on the lock screen. [IN_CALL]: a call is active, and Kestrel is paused.
 */
enum class Mode { OFF, LISTENING, PAUSED, RINGING, IN_CALL }

/** The phone's call state (design spec, section 8.7), as Android's call-state listener reports it. */
enum class CallState { IDLE, RINGING, ACTIVE }

/**
 * What Kestrel does when the screen comes back on and the phone is unlocked (design spec, section 8.5): it keeps the
 * mode that it had ([RESUME]), listens ([LISTEN]), or turns off ([STAY_OFF]).
 */
enum class WakeUpMode { RESUME, LISTEN, STAY_OFF }

/** What Kestrel is doing besides listening: the spec's "current activity" (section 7.3). */
enum class Work {
    IDLE,

    /** After "Kestrel" on its own: the next utterance is the command for the conversation. */
    AWAITING_TASK,

    /**
     * The follow-up window after a spoken final reply (design 6.6 of sub-project 7): the next utterance is a command for
     * the conversation without the name.
     */
    FOLLOWING_UP,

    /** An instant command runs (section 7.3): it may ask a question or count down, and "stop" or "cancel" cancel it. */
    RUNNING_COMMAND,
}

/**
 * A question of the conversation that the voice asked (design 11 of sub-project 7): its [number], as the conversation
 * numbers it, and its [kind], which says how the voice reads the answer.
 */
data class VoiceQuestion(val number: Int, val kind: QuestionKind)

/**
 * The Orchestrator's state (design spec, section 7.3). [speeches] counts the replies that Kestrel has started and not
 * finished. [wait] is the number of the latest wait for a command or an answer, so that the end of an older wait changes
 * nothing. [emptyTranscripts] counts the transcripts without words in a row, and [noWordsShown] is true once the hint about them
 * has shown since Kestrel turned on (section 14.6). [beforeCall] is the mode that a call interrupted, while the mode is
 * Ringing or In call; it comes back after the call (section 8.7). [callCommands]: the owner turned on "Commands during a
 * call", so that Kestrel listens for them during a call (section 8.7). [onWakeUp] is what Kestrel does after an unlock,
 * and [pauseAfterSilence] pauses listening after 30 s without speech (section 8.5); [silence] is the number of the
 * latest timer of that pause, so that the end of an older one changes nothing. [screenSeen]: since Kestrel turned on,
 * the running ListeningService has reported the screen on and the phone unlocked, so that the next unlock is a wake-up
 * (section 8.5). [command] is the number of the latest instant command, so that the end of an older one changes nothing;
 * [running] is the command that runs and [heard] its transcript; [asking]: it waits for the answer to its question;
 * [counting]: it counts down, as before a call. [lastCommand] and [lastHeard] are the last command that ended as
 * repeatable and its transcript, for "again" (section 9.5); off forgets them. [spottedStop]: the spotter's "stop" or
 * "cancel" cancelled the command, or stopped a reply while no command ran, so the transcript of the same word names no
 * button. It lasts until the next transcript that reaches the commands, so when the pipeline dropped that word, one
 * later "stop…" or "cancel…" is only shown. [questionEnded]: a command's question ended without an answer, so the next
 * transcript that reaches the commands, when it names a number, may be a late answer and is only shown (section 9.4). It
 * lasts 10 s at most, and a pause, a call, a goal that works or waits for the owner's return, the wait for a command,
 * and the screen turning off end it.
 * [conversation] is what the voice reads of the conversation (design 5.5 of sub-project 7): whether one is open, its
 * goal's phase, its open question, and whether a final reply waits for the owner. [voiceQuestion] is the conversation's question that the voice asked and waits on, with the
 * Orchestrator's answer waits; [voiceAnswers] is false while only its card may answer it (D32 item 17 of sub-project 5);
 * [answered] is the number of the latest question that the voice answered, so that the next transcript does not answer it
 * again before the conversation has taken the answer. [offAfterGoal]: "On screen wake-up" is "Stay off", and Kestrel
 * turns off once the goal has ended and its final reply has been spoken. [followUpMillis] is the follow-up window's length
 * from the Conversations page, and [followUp] the number of the latest window, so that the end of an older one changes
 * nothing; [finalReply]: the number of the final reply that Kestrel speaks now, whose end opens the window once Kestrel
 * has spoken all of it (design 6.6 of sub-project 7).
 */
data class State(
    val mode: Mode = Mode.OFF,
    val work: Work = Work.IDLE,
    val speeches: Int = 0,
    val wait: Int = 0,
    val screenOn: Boolean = true,
    val micBusy: Boolean = false,
    val emptyTranscripts: Int = 0,
    val noWordsShown: Boolean = false,
    val beforeCall: Mode? = null,
    val callCommands: Boolean = false,
    val onWakeUp: WakeUpMode = WakeUpMode.RESUME,
    val pauseAfterSilence: Boolean = false,
    val silence: Int = 0,
    val screenSeen: Boolean = false,
    val command: Int = 0,
    val running: Command? = null,
    val heard: String? = null,
    val asking: Boolean = false,
    val counting: Boolean = false,
    val lastCommand: Command? = null,
    val lastHeard: String? = null,
    val spottedStop: Boolean = false,
    val questionEnded: Boolean = false,
    val conversation: VoiceView = VoiceView(),
    val voiceQuestion: VoiceQuestion? = null,
    val voiceAnswers: Boolean = true,
    val answered: Int? = null,
    val offAfterGoal: Boolean = false,
    val followUpMillis: Long = ConversationSettings().followUpMillis,
    val followUp: Int = 0,
    val finalReply: Int? = null,
) {
    val speaking: Boolean get() = speeches > 0

    /** The phase of the conversation's goal, or null when it has none (design 5.3 of sub-project 7). */
    val goal: GoalPhase? get() = conversation.goal

    /** Whether the conversation's goal acts or asks, rather than waiting for the owner's hand or return. */
    val goalWorks: Boolean get() = goal == GoalPhase.ACTING || goal == GoalPhase.ASKING

    /**
     * The conversation's question that a transcript answers: the one that the voice asked, or else the one that the
     * conversation shows, as long as the voice has not answered it yet.
     */
    val openQuestion: VoiceQuestion?
        get() = (voiceQuestion ?: conversation.question?.let { number -> conversation.asking?.let { VoiceQuestion(number, it) } })
            ?.takeIf { it.number != answered }

    /**
     * Whether "stop" and "cancel" have something of the conversation's to stop (design 6.4 of sub-project 7): its goal in
     * any phase, its open question, or one of Kestrel's own requests that runs. Every form of the stop reads this one
     * rule, and only while nothing is stoppable may a plain "stop" name a button.
     */
    val stoppable: Boolean get() = goal != null || openQuestion != null || conversation.ownRequest

    // The owner's words never reach a log (section 13.5): only the commands' kinds and the transcripts' lengths.
    override fun toString() =
        "State(mode=$mode, work=$work, speeches=$speeches, wait=$wait, screenOn=$screenOn, " +
            "micBusy=$micBusy, emptyTranscripts=$emptyTranscripts, noWordsShown=$noWordsShown, beforeCall=$beforeCall, " +
            "callCommands=$callCommands, onWakeUp=$onWakeUp, pauseAfterSilence=$pauseAfterSilence, silence=$silence, " +
            "screenSeen=$screenSeen, command=$command, running=${running?.javaClass?.simpleName}, heard=${heard?.length}, " +
            "asking=$asking, counting=$counting, lastCommand=${lastCommand?.javaClass?.simpleName}, " +
            "lastHeard=${lastHeard?.length}, spottedStop=$spottedStop, questionEnded=$questionEnded, conversation=$conversation, " +
            "voiceQuestion=$voiceQuestion, voiceAnswers=$voiceAnswers, answered=$answered, offAfterGoal=$offAfterGoal, " +
            "followUpMillis=$followUpMillis, followUp=$followUp, finalReply=$finalReply)"

    /** Whether a call rings or is active (section 8.7). */
    val inCall: Boolean get() = mode == Mode.RINGING || mode == Mode.IN_CALL

    /**
     * Whether Kestrel records: while it listens or is paused, with the screen on and the phone unlocked; while a call
     * rings, also on the lock screen (section 8.5); and during a call only with [callCommands] and the phone unlocked.
     */
    val microphone: Boolean
        get() = when (mode) {
            Mode.OFF -> false
            Mode.RINGING -> true
            Mode.IN_CALL -> callCommands && screenOn
            Mode.LISTENING, Mode.PAUSED -> screenOn
        }

    /** Which utterances the pipeline transcribes, and whether it holds them (sections 8.3, 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
            // While a call rings, the call commands may come without the name (section 8.7).
            mode == Mode.RINGING -> Recognition.ALL
            // During a call with the setting, only the utterances at whose start the keyword spotter heard "Kestrel", as
            // while Kestrel is paused, so that Kestrel never transcribes the call's conversation; when the spotter misses
            // the name, the owner says the command again (section 8.7).
            mode == Mode.IN_CALL -> if (callCommands) Recognition.NAMED else Recognition.NONE
            // While Kestrel waits for a command, after "Yes?" or after an interruption with "Kestrel", the owner dictates
            // it and may pause in it.
            work == Work.AWAITING_TASK -> Recognition.ANSWER
            // The conversation's question, a goal that works and Kestrel's own request that runs ("stop" without the name),
            // and a hand-over take words without the name, also in the paused mode (design D2 of sub-project 5; design 5.5
            // and 6.4 of sub-project 7).
            work != Work.IDLE || mode == Mode.LISTENING || openQuestion != null || goalWorks || conversation.ownRequest ||
                goal == GoalPhase.HANDED_OVER -> Recognition.ALL
            else -> Recognition.NAMED
        }
}

/** What happens to Kestrel (design spec, section 7.3). */
sealed interface Event {
    /** The accessibility button or shortcut, or the Quick Settings tile (section 8.6). */
    data object Toggle : Event

    /**
     * Turns Kestrel on while it is off: listening, or paused when [paused]. While Kestrel listens or is paused it changes
     * nothing, so that a start that comes late cannot toggle anything. The restore after a restart of Kestrel's process
     * sends it (section 8.5).
     */
    data class Start(val paused: Boolean = false) : Event

    /** The notification's Pause action. */
    data object Pause : Event

    /** The notification's Resume action. */
    data object Resume : Event

    /** The notification's Stop action. */
    data object TurnOff : Event

    /**
     * A transcript. [named] is true when the keyword spotter heard "Kestrel" at its start. [spokeAt] is when the owner
     * began to say it, in `SystemClock.uptimeMillis()`, or null when it is not known; a command that names a number
     * ends when the numbers changed after it (section 9.4). [seconds] is the length of its audio, and [endedAt] the end
     * of its speech on the phone's clock (SystemClock.elapsedRealtime), or 0 when it is not known; only the A71 session's
     * counts and times read them (plan 2E). [closedAt] is when its end-of-speech pause ended, on the same clock, or 0
     * (plan 4D). [stopSpotted] is true when the keyword spotter heard "stop" at its start (plan 4E).
     */
    data class Heard(
        val text: String,
        val named: Boolean,
        val spokeAt: Long? = null,
        val seconds: Float = 0f,
        val endedAt: Long = 0L,
        val closedAt: Long = 0L,
        val stopSpotted: Boolean = false,
    ) : Event {
        override fun toString() = "Heard(${text.length} characters, named=$named)"
    }

    /**
     * The owner speaks while Kestrel recognises speech. Send it when the speech detector hears speech on a frame for
     * which recognition is on, and on the frame before it either heard none or recognition was off. Send it again when a
     * transcript arrives while the detector still hears speech. Read `hearsSpeech` on the microphone's thread, after
     * `ListeningPipeline.accept`; the pipeline's `VoiceEvent.SpeechStarted` follows this rule. An utterance that ends
     * without a transcript while recognition is on restarts the 8 s wait for a task: `VoiceSession` sends
     * [NothingHeard] for it.
     */
    data object Speaking : Event

    /** A keyword from the spotter that was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : Event

    /**
     * A reply has been spoken to its end ([spoken]), or stopped, interrupted or refused, or speaking it failed. Send it
     * once for each reply, or recognition stays off. Only a final reply that Kestrel spoke to its end opens the follow-up
     * window (design 6.6 of sub-project 7).
     */
    data class SpeechEnded(val spoken: Boolean) : Event

    /** Android started or stopped silencing Kestrel's recording. */
    data class MicBusy(val busy: Boolean) : Event

    /**
     * The screen is on and the phone unlocked ([on]), or the screen turned off or the phone locked. The conversation
     * pauses its goal through its own signals, also with listening off (design 6.5 of sub-project 7).
     */
    data class Screen(val on: Boolean) : Event

    /** Wait number [wait] for a task after "Yes?" has lasted long enough. */
    data class WaitEnded(val wait: Int) : Event

    /**
     * Kestrel could not open its settings app, because Android lets it start an activity from the background only while
     * its accessibility service is on or it holds the assistant role. Kestrel says so, because the feedback bar does not
     * exist then (section 14).
     */
    data object SettingsUnavailable : Event

    /**
     * Android did not bring Kestrel's settings to the front, although Kestrel's accessibility service is on or Kestrel
     * holds the assistant role, as Android 13 may do with only the role. Kestrel posted a notification that opens them,
     * and says so (section 14).
     */
    data object SettingsBlocked : Event

    /**
     * Starting Kestrel's settings app failed with an error, which Listening reported. The bar says so (section 14), as
     * every text that it shows comes through the Rules (fix-7d-n1 review, nit 1).
     */
    data object SettingsFailed : Event

    /**
     * An utterance ended without a transcript: the pipeline dropped it, or transcribing it failed. Like a transcript
     * without words, it gives the owner the whole wait for a task again, but it does not count towards the hint about
     * empty transcripts (section 14.6).
     */
    data object NothingHeard : Event

    /** The phone's call state changed (section 8.7). Only Listening's call-state listener sends it. */
    data class CallState(val state: dev.kestrel.app.orchestrator.CallState) : Event

    /**
     * The assistant gesture or an earbud's assistant button (section 8.6): Kestrel listens, if it was paused, and waits
     * for a task, as after "Kestrel" on its own. Listening turns Kestrel on first and sends it once a voice session is
     * open, so the Rules ignore it while Kestrel is off.
     */
    data object Assist : Event

    /**
     * The owner's Stop that did not come by voice: typed, on the conversation screen or on the goal notification (plan 7F).
     * Kestrel's speech stops as for a spoken stop.
     */
    data object StopSpeaking : Event

    /** The owner's choices for listening, as Listening read them at its start or after a change (section 15). */
    data class Options(val onWakeUp: WakeUpMode, val pauseAfterSilence: Boolean, val callCommands: Boolean) : Event

    /** Timer number [silence] of the pause after 30 s without speech has run out (section 8.5). */
    data class Silent(val silence: Int) : Event

    /** Instant command number [command] ended with [result] (section 7.3). Listening sends it for every command that ran. */
    data class CommandEnded(val command: Int, val result: CommandResult) : Event

    /** Command number [command] asks [question] and waits for the answer. */
    data class CommandAsks(val command: Int, val question: String) : Event {
        override fun toString() = "CommandAsks($command, ${question.length} characters)"
    }

    /** Command number [command] says [text], such as a call's announcement. */
    data class CommandSays(val command: Int, val text: String) : Event {
        override fun toString() = "CommandSays($command, ${text.length} characters)"
    }

    /** Command number [command] starts ([counting]) or ends a countdown, as before a call (section 13.6). */
    data class CommandCounts(val command: Int, val counting: Boolean) : Event

    /** Wait number [wait] for the answer to a command's question has lasted long enough. */
    data class AnswerWaitEnded(val wait: Int) : Event

    /** 10 s have passed since the question of command number [command] ended without an answer (section 9.4). */
    data class LateAnswersEnded(val command: Int) : Event

    /** What the voice reads of the conversation changed to [view] (design 5.5 of sub-project 7). */
    data class ConversationChanged(val view: VoiceView) : Event

    /**
     * The conversation says [text] through the voice channel (design 11 of sub-project 7). [reply] numbers it, so that
     * the channel learns when it has been spoken, or that it was only shown ([Effect.EndReply]). [final]: it is a command's
     * or a local request's final reply, whose end opens the follow-up window (design 6.6).
     */
    data class ChannelSays(val reply: Int, val text: String, val final: Boolean = false) : Event {
        override fun toString() = "ChannelSays($reply, ${text.length} characters, final=$final)"
    }

    /** The follow-up window's length from the Conversations page (design 6.6 and 13 of sub-project 7); 0 is off. */
    data class FollowUpLength(val millis: Long) : Event

    /** Follow-up window number [followUp] has lasted its length (design 6.6 of sub-project 7). */
    data class FollowUpEnded(val followUp: Int) : Event

    /** The conversation shows [text] on the bar without speaking it, as "Got it." for steering. */
    data class ChannelShows(val text: String) : Event {
        override fun toString() = "ChannelShows(${text.length} characters)"
    }

    /**
     * The voice channel asks the conversation's question number [question] of [kind], [text] being its spoken form, and
     * waits for its answer with the answer waits. With [voice] false, only its card answers it, because another app's
     * audio plays (D32 item 17 of sub-project 5); "stop", "cancel" and the end of the wait still count.
     */
    data class ChannelAsks(val question: Int, val text: String, val kind: QuestionKind, val voice: Boolean = true) : Event {
        override fun toString() = "ChannelAsks($question, ${text.length} characters, $kind, voice=$voice)"
    }

    /** The voice channel hands a step over to the owner and says [text] (design D15 of sub-project 5). */
    data class ChannelHandsOver(val text: String) : Event {
        override fun toString() = "ChannelHandsOver(${text.length} characters)"
    }

    /**
     * The voice channel's question number [question] ended: answered on its card or by voice, or withdrawn by the
     * conversation (design 11 of sub-project 7). The voice no longer waits for its answer.
     */
    data class ChannelWithdraws(val question: Int) : Event

    /**
     * A goal's round needs the screen while instant command number [command] holds it (`ScreenTurns`, the one rule for who
     * acts on the screen; design 6.5 of sub-project 7): the conversation's work goes first, so the command is cancelled,
     * as for "stop", and the round waits until its job has ended.
     */
    data class ScreenWanted(val command: Int) : Event

    /**
     * A goal's round holds the screen (`ScreenTurns`; design 6.5 of sub-project 7), so instant command number [command] did
     * not start, or, when [command] is null, the settings did not open: the round acts, as while the goal works.
     */
    data class ScreenHeld(val command: Int?) : Event
}

/** What Kestrel says. */
sealed interface Reply {
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply

    /**
     * Why Kestrel did not open its settings: its accessibility service is off, and it does not hold the assistant role
     * (section 14).
     */
    data object SettingsNeedService : Reply

    /** Android kept Kestrel's settings back, and a notification opens them (section 14). */
    data object SettingsBlocked : Reply

    /**
     * [kept]: the Rules show [text] on the bar already and keep it there, as the conversation's question or a hand-over's
     * sentence, so a reply that Kestrel cannot speak does not show it again.
     */
    data class Text(val text: String, val kept: Boolean = false) : Reply {
        override fun toString() = "Text(${text.length} characters, kept=$kept)"
    }

    /** What instant command number [command] says or asks. */
    data class Command(val command: Int, val text: String) : Reply {
        override fun toString() = "Command($command, ${text.length} characters)"
    }

    /**
     * What the conversation says through the voice channel, as its reply number [reply] (design 11 of sub-project 7);
     * the bar shows it already. Listening tells the channel when it has been spoken, stopped or refused.
     */
    data class Channel(val reply: Int, val text: String) : Reply {
        override fun toString() = "Channel($reply, ${text.length} characters)"
    }
}

/** What the feedback bar shows (design spec, section 8.9). */
sealed interface Feedback {
    data class ModeChanged(val mode: Mode) : Feedback

    data class Heard(val text: String) : Feedback {
        override fun toString() = "Heard(${text.length} characters)"
    }

    data object MicBusy : Feedback

    /**
     * Kestrel waits for the task, after "Yes?" or after an interruption with "Kestrel": the question that Kestrel asks
     * (section 8.9).
     */
    data object AwaitingTask : Feedback

    /** Three transcripts in a row had no words, so the owner may check the microphone (section 14.6). */
    data object NoWords : Feedback

    /**
     * The follow-up window opens for [seconds] after final reply number [reply] (design 6.6 of sub-project 7): the bar
     * counts them down over the reply's text.
     */
    data class FollowUp(val seconds: Int, val reply: Int) : Feedback

    /** What an instant command shows. With [keep], the text stays until the command ends or the next text. */
    data class CommandText(val text: String, val keep: Boolean = false) : Feedback {
        override fun toString() = "CommandText(${text.length} characters, keep=$keep)"
    }

    /** "Cancelled": a command was cancelled, or its question was not answered. */
    data object CommandCancelled : Feedback

    /** A command came while another ran, and was dropped (decision 3 of sub-project 3). */
    data object CommandBusy : Feedback

    /** A goal's round holds the screen, so what the owner asked for did not start (design 6.5 of sub-project 7). */
    data object GoalBusy : Feedback

    /** "Again" without a command to repeat. */
    data object NothingToRepeat : Feedback

    /**
     * A number came as the first transcript after a command's question ended without an answer, within 10 s: it may be a
     * late answer, whose number would name another element now, so it did nothing (section 9.4).
     */
    data object QuestionEnded : Feedback

    /** "Kestrel could not open its settings.": starting them failed with an error (section 14). */
    data object SettingsFailed : Feedback

    /**
     * What the conversation says, shows, asks or hands over through the voice channel (design 11 of sub-project 7). With
     * [keep], the text stays until the voice channel releases it, as a question or a hand-over's sentence does.
     * [finalReply]: the number of the final reply that it shows, over which that reply's follow-up window counts down
     * (design 6.6 of sub-project 7).
     */
    data class ConversationText(val text: String, val keep: Boolean = false, val finalReply: Int? = null) : Feedback {
        override fun toString() = "ConversationText(${text.length} characters, keep=$keep, finalReply=$finalReply)"
    }
}

/** What the Orchestrator asks of the rest of Kestrel. The microphone and recognition follow [State] instead. */
sealed interface Effect {
    data class Say(val reply: Reply) : Effect

    data object StopSaying : Effect

    /**
     * Gives the conversation the owner's [intent] (design 5.5 of sub-project 7): a command, an answer, a stop or one of
     * Kestrel's own requests. [heard] is the transcript that said it, or null for the end of an answer wait or the
     * conversation's own question that the name withdrew; the debug hooks find a tagged transcript's options by it.
     */
    data class Converse(val intent: InputIntent, val heard: Event.Heard? = null) : Effect {
        override fun toString() = "Converse($intent, heard=${heard != null})"
    }

    /** Reply number [reply] of the conversation was only shown, because Kestrel is off: its channel goes on at once. */
    data class EndReply(val reply: Int) : Effect

    /**
     * No follow-up window follows final reply number [reply] (design 6.6 of sub-project 7): it was not spoken to its end,
     * Kestrel was or turned off, a newer reply came, or no window could open. Its text no longer waits on the bar for a
     * countdown (review A-R2 of plan 7F).
     */
    data class NoFollowUp(val reply: Int) : Effect

    /**
     * Runs instant command number [command], [parsed] from the transcript [heard], which the executor needs for its
     * fallback to a bare label and for dictation. [spokeAt] is when the owner began to say it, as in [Event.Heard]. It
     * must send [Event.CommandEnded] once it ends (section 7.3).
     */
    data class RunCommand(val command: Int, val parsed: Command, val heard: String, val spokeAt: Long? = null) : Effect {
        override fun toString() = "RunCommand($command, ${parsed.javaClass.simpleName}, ${heard.length} characters)"
    }

    /** Cancels the running instant command. Nothing new may act on the screen until its job has completed. */
    data object CancelCommand : Effect

    /** The answer to the question of command number [command], or null when the wait for it ended. */
    data class AnswerCommand(val command: Int, val text: String?) : Effect {
        override fun toString() = "AnswerCommand($command, ${if (text == null) "no answer" else "${text.length} characters"})"
    }

    /** Holds the countdown of command number [command] while the owner speaks ([hold]), or lets it go on. */
    data class HoldCommand(val command: Int, val hold: Boolean) : Effect

    /**
     * Starts the timer that ends the latest wait for the answer to a command's question, in place of the one that runs.
     * [answering]: the owner has started to answer. The Orchestrator runs it itself.
     */
    data class WaitForAnswer(val answering: Boolean = false) : Effect

    data class Show(val feedback: Feedback) : Effect

    /**
     * Starts the timer that ends the latest wait for a task after "Yes?", in place of the one that runs. [answering]: the
     * owner has started to answer, so the timer also covers the answer and its transcription. The Orchestrator runs it
     * itself.
     */
    data class WaitForTask(val answering: Boolean = false) : Effect

    /** Stops the listening service. */
    data object StopService : Effect

    /** Opens Kestrel's settings app, for "Kestrel, open settings" (section 15). */
    data object OpenSettings : Effect

    /** Carries out a call command (section 8.7). */
    data class CallCommand(val command: Request.Call) : Effect

    /**
     * Starts the timer of the pause after 30 s without speech, in place of the one that runs (section 8.5). The
     * Orchestrator runs it itself.
     */
    data object TimeSilence : Effect

    /**
     * Starts the timer after which a number no longer counts as a late answer to the question of the latest command
     * ([Event.LateAnswersEnded]), in place of the one that runs. The Orchestrator runs it itself.
     */
    data object TimeLateAnswers : Effect

    /**
     * Starts the timer that ends the latest follow-up window ([Event.FollowUpEnded]), in place of the one that runs:
     * [State.followUpMillis], or with [answering], once the owner has started an utterance in it, the time to finish and
     * transcribe it. The Orchestrator runs it itself.
     */
    data class TimeFollowUp(val answering: Boolean = false) : Effect
}

/** A transition: the new state, and what to do, in order. */
data class Step(val state: State, val effects: List<Effect> = emptyList())

/** Every transition of the Orchestrator, in one place (design spec, section 7.3). */
object Rules {
    fun next(state: State, event: Event): Step =
        silenceTimer(state, event, endLateAnswers(endFollowUp(state, withoutFollowUp(state, react(state, event)))))

    private val STOP_WORDS = setOf("stop", "cancel")

    // Words that may come before "stop" or "cancel", as in "Okay, stop scrolling" or "No, cancel the drag".
    private val BEFORE_STOP = setOf("ok", "okay", "please", "now", "no", "hey", "oh", "just", "wait", "so")

    // Words that may come before or with "no" during a countdown, as in "Oh no", "Wait, no" or "Thank you, no".
    private val NO_FILLERS =
        setOf("oh", "wait", "please", "now", "ok", "okay", "hey", "just", "not", "thanks", "thank", "you", "dont")

    private fun react(state: State, event: Event): Step =
        if (state.work == Work.RUNNING_COMMAND) duringCommand(state, event) else withoutCommand(state, event)

    private fun withoutCommand(state: State, event: Event): Step = when (event) {
        Event.Toggle -> when {
            state.inCall -> afterCall(state, if (state.beforeCall == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
            else -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        }
        is Event.Start ->
            if (state.mode == Mode.OFF) switch(state, if (event.paused) Mode.PAUSED else Mode.LISTENING) else Step(state)
        Event.Pause -> when {
            state.inCall -> afterCall(state, Mode.PAUSED)
            state.mode == Mode.LISTENING -> switch(state, Mode.PAUSED)
            else -> Step(state)
        }
        // The notification exists only while Kestrel is on, so a Resume while off comes from a stale notification.
        Event.Resume -> when {
            state.inCall -> afterCall(state, Mode.LISTENING)
            state.mode == Mode.PAUSED -> switch(state, Mode.LISTENING)
            else -> Step(state)
        }
        Event.TurnOff -> if (state.mode == Mode.OFF) Step(state) else turnOff(state)
        is Event.Heard -> heard(state, event)
        // The owner has started to answer "Yes?" or the conversation's question, so the wait lasts until the answer has
        // been transcribed.
        Event.Speaking -> when {
            state.speaking -> Step(state)
            state.work == Work.AWAITING_TASK -> waitForTask(state, answering = true)
            state.work == Work.FOLLOWING_UP -> Step(state, listOf(Effect.TimeFollowUp(answering = true)))
            state.voiceQuestion != null -> waitForAnswer(state, answering = true)
            else -> Step(state)
        }
        is Event.Spotted -> spotted(state, event.keyword)
        is Event.SpeechEnded -> speechEnded(state, event.spoken)
        is Event.MicBusy -> when {
            state.mode == Mode.OFF -> Step(state)
            event.busy && !state.micBusy -> Step(state.copy(micBusy = true), listOf(Effect.Show(Feedback.MicBusy)))
            else -> Step(state.copy(micBusy = event.busy))
        }
        is Event.Screen -> screen(state, event.on)
        is Event.WaitEnded ->
            if (state.work == Work.AWAITING_TASK && event.wait == state.wait) Step(state.copy(work = Work.IDLE)) else Step(state)
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
        // Not over a ringing call: the verdict comes up to 5 s after the request (SettingsOpener.WAIT_MILLIS).
        Event.SettingsBlocked -> if (state.mode == Mode.OFF || state.inCall) Step(state) else say(state, Reply.SettingsBlocked)
        Event.SettingsFailed -> Step(state, listOf(Effect.Show(Feedback.SettingsFailed)))
        Event.NothingHeard -> when {
            state.mode != Mode.OFF && state.work == Work.AWAITING_TASK -> waitForTask(state)
            state.work == Work.FOLLOWING_UP -> Step(state.copy(work = Work.IDLE))
            state.mode != Mode.OFF && state.voiceQuestion != null -> waitForAnswer(state)
            else -> Step(state)
        }
        is Event.CallState -> call(state, event.state)
        Event.Assist -> assist(state)
        Event.StopSpeaking -> Step(state, listOfNotNull(Effect.StopSaying.takeIf { state.speaking }))
        is Event.Options -> Step(
            state.copy(onWakeUp = event.onWakeUp, pauseAfterSilence = event.pauseAfterSilence, callCommands = event.callCommands),
        )
        is Event.Silent -> silent(state, event.silence)
        is Event.LateAnswersEnded ->
            if (event.command == state.command && state.questionEnded) Step(state.copy(questionEnded = false)) else Step(state)
        is Event.ConversationChanged -> conversationChanged(state, event.view)
        is Event.FollowUpLength -> Step(state.copy(followUpMillis = event.millis))
        is Event.FollowUpEnded ->
            if (state.work == Work.FOLLOWING_UP && event.followUp == state.followUp) Step(state.copy(work = Work.IDLE)) else Step(state)
        is Event.ChannelSays -> channelSays(state, event)
        is Event.ChannelShows -> Step(state, listOf(Effect.Show(Feedback.ConversationText(event.text))))
        is Event.ChannelAsks -> channelAsks(state, event)
        is Event.ChannelHandsOver -> channelHandsOver(state, event.text)
        is Event.ChannelWithdraws -> channelWithdraws(state, event.question)
        is Event.AnswerWaitEnded -> {
            val question = state.voiceQuestion
            when {
                event.wait != state.wait || question == null -> Step(state)
                // A question that is still spoken keeps its wait: speechEnded() starts the whole wait again once it has
                // been spoken, so that a long confirmation is never declined before the owner has heard it (design D14 of
                // sub-project 5).
                state.speaking -> Step(state)
                // The conversation decides what silence means: "no" for a confirmation, the goal held for "Continue the
                // goal?" (design 6.5 and 11 of sub-project 7).
                else -> answer(state, question, ChannelAnswer.Silence, null)
            }
        }
        // A command that has ended or was cancelled may still report; nothing changes then.
        is Event.CommandEnded, is Event.CommandAsks, is Event.CommandSays, is Event.CommandCounts, is Event.ScreenWanted -> Step(state)
        // The settings did not open, because a goal's round holds the screen; an older command's report changes nothing.
        is Event.ScreenHeld -> if (event.command == null && state.mode != Mode.OFF) Step(state, listOf(Effect.Show(Feedback.GoalBusy))) else Step(state)
    }

    private fun heard(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.OFF) return Step(state)
        if (state.inCall) return callCommand(state, event)
        val awaiting = state.work == Work.AWAITING_TASK
        // Inside the follow-up window the words are read as after the name (design 6.6 of sub-project 7).
        val following = state.work == Work.FOLLOWING_UP
        // An empty transcript that finishes after the screen turned off does not count: nobody could see its hint.
        // Waiting for a command ends when the screen turns off, and any transcript ends the follow-up window.
        val request = Requests.read(event.text, event.named, afterName = awaiting || following, stopSpotted = event.stopSpotted, followUp = following)
            ?: return if (state.screenOn) withoutWords(if (following) state.copy(work = Work.IDLE) else state, awaiting) else Step(state)
        // Words start the count of empty transcripts again.
        val counted = state.copy(emptyTranscripts = 0)
        val shown = Effect.Show(Feedback.Heard(event.text))
        val stopsFirst = stopsWorkFirst(counted, request, event)
        // A transcription that was queued before the microphone stopped can finish after the screen turned off. Then
        // only the requests that stop something count, because nothing may start (section 14.4); a "Stop." before the
        // name still stops the goal or Kestrel's own request.
        if (!counted.screenOn && !stopsSomething(request)) return if (stopsFirst) stopWork(counted, shown) else Step(counted)
        if (stopsFirst) return stopFirst(counted, request, event, shown, event.spokeAt)
        return when (counted.work) {
            // Every utterance in the follow-up window is a command, and Kestrel's own requests keep their meaning; the
            // instant commands' parser is not asked (design 6.6 of sub-project 7).
            Work.AWAITING_TASK, Work.FOLLOWING_UP -> whileAwaitingTask(counted, request, event, shown)
            Work.IDLE -> idle(counted, request, event, shown, event.spokeAt)
            // duringCommand() reads the transcripts of a running command.
            Work.RUNNING_COMMAND -> Step(counted)
        }
    }

    // Section 14.6: a transcript without words. After three in a row, the bar suggests checking the microphone, once
    // until Kestrel turns off. An answer to "Yes?" without words gives the owner the full wait again.
    private fun withoutWords(state: State, awaiting: Boolean): Step {
        val count = state.emptyTranscripts + 1
        val hint = count >= EMPTY_TRANSCRIPTS_FOR_HINT && !state.noWordsShown
        val counted = state.copy(emptyTranscripts = count, noWordsShown = state.noWordsShown || hint)
        val shown = listOfNotNull(Effect.Show(Feedback.NoWords).takeIf { hint })
        return if (awaiting) waitForTask(counted, *shown.toTypedArray()) else Step(counted, shown)
    }

    private fun stopsSomething(request: Request): Boolean =
        request is Request.Stop || request == Request.PauseListening || request == Request.TurnOff

    // Design D9 of sub-project 7: while the goal acts or asks, "stop" and "cancel" without the name stop it, and only what
    // the owner says after the name reaches the conversation: a command steers the goal, Kestrel's own requests wait for
    // it. "Stop listening" and "turn off" change only the listening mode (plan 7B's decision 10). Anything else is ignored.
    private fun whileGoalWorks(state: State, request: Request, event: Event.Heard, shown: Effect): Step {
        if (request is Request.Stop) return stopWork(state, shown)
        if (request == Request.PauseListening) return changeMode(state, Mode.PAUSED, shown)
        if (request == Request.TurnOff) return turnOff(state, shown)
        val intent = Requests.intent(request) ?: return Step(state)
        return converse(state, intent, event, shown)
    }

    // What "Stop. Kestrel, [request]" stops first: whatever is stoppable (State.stoppable), the goal in every phase, the
    // open question, or Kestrel's own request while it asks or runs (remember, forget, "save that as", delete history or
    // a switch).
    private fun stopsWorkFirst(state: State, request: Request, event: Event.Heard): Boolean =
        state.stoppable && request !is Request.Stop && request != Request.TurnOff && Requests.stopsFirst(event.text, event.named)

    // Design D2 of sub-project 5: "Stop. Kestrel, [request]" stops the goal in every phase, or Kestrel's own request, as
    // the stop alone would, and the request then counts as it does without them, so that the stop is never lost: the
    // conversation takes a command or Kestrel's own request after the stop, and the settings open. "Stop" and "turn off"
    // after the name keep their own rules. The transcript shows once, before the stop.
    private fun stopFirst(state: State, request: Request, event: Event.Heard, shown: Effect, spokeAt: Long?): Step {
        val stopped = stopWork(state, shown)
        val after = whileIdle(stopped.state, request, event, shown, spokeAt)
        return Step(after.state, stopped.effects + after.effects.filter { it !== shown })
    }

    // Design D2 of sub-project 5: while the conversation asks, "stop" and "cancel" stop it in every kind of question, and
    // otherwise only the answer that it asked for counts. "Stop listening" and "turn off" change only the mode (plan 7B's
    // decision 10), and "Kestrel" on its own waits for a command; these withdraw Kestrel's own question, as they end an
    // instant command's question, while a goal's question stays. While only the card may answer (D32 item 17 of sub-project
    // 5), no spoken answer counts. A command after the name answers "Continue the goal?" as steering (design 6.5 of
    // sub-project 7).
    private fun answering(state: State, question: VoiceQuestion, request: Request, event: Event.Heard, shown: Effect): Step {
        if (request is Request.Stop || (request is Request.Other && startsWithStop(event.text))) return stopWork(state, shown)
        if (request == Request.PauseListening || request == Request.TurnOff) {
            val withdrawn = if (state.goal == null) answer(state, question, ChannelAnswer.Interrupted, null, shown) else Step(state, listOf(shown))
            return withdrawn.then { if (request == Request.TurnOff) turnOff(it) else changeMode(it, Mode.PAUSED) }
        }
        if (request == Request.Name && state.goal == null) return askForTask(state, shown)
        if (state.voiceAnswers && request is Request.Task && steers(question, request)) return converse(state, InputIntent.Command(request.text), event, shown)
        val answer = spokenAnswer(state, question, request, event) ?: return Step(state, listOf(shown))
        return answer(state, question, answer, event, shown)
    }

    // The one answer rule (design 11 of sub-project 7): the answer to [question] in a transcript, as VoiceAnswers reads it
    // for the question's kind, or null when the words are no answer: while only the card may answer, and for a command
    // after the name, which answers "Continue the goal?" as steering. Idle and while an instant command runs alike.
    private fun spokenAnswer(state: State, question: VoiceQuestion, request: Request, event: Event.Heard): ChannelAnswer? = when {
        !state.voiceAnswers || steers(question, request) -> null
        else -> VoiceAnswers.read(question.kind, event.text, event.named, request)
    }

    // A command after the name answers "Continue the goal?" as steering (design 6.5 of sub-project 7).
    private fun steers(question: VoiceQuestion, request: Request): Boolean = question.kind == QuestionKind.CONTINUE_GOAL && request is Request.Task

    // The controller's decision of 2026-11-25 (design 6.5 and 11 of sub-project 7): while an instant command runs without
    // a question of its own, the owner's answer to the conversation's open question reaches it with its number, and the
    // command goes on, because an answer needs no screen. When the answer lets a round act, as a yes to "Continue the
    // goal?" does, the round takes the screen's turn itself (ScreenTurns, Event.ScreenWanted). "Stop", "stop listening",
    // "turn off" and "Kestrel" keep their own meaning, and while the command asks, its own question takes the words; null
    // then, and for words that are no answer.
    private fun answerDuringCommand(state: State, request: Request, event: Event.Heard, shown: Effect): Step? {
        val question = state.openQuestion ?: return null
        if (state.asking || keepsItsMeaning(request)) return null
        val answer = spokenAnswer(state, question, request, event) ?: return null
        return answer(state, question, answer, event, shown)
    }

    // Kestrel's requests that keep their meaning while a question is open (design D2 of sub-project 5).
    private fun keepsItsMeaning(request: Request): Boolean =
        request is Request.Stop || request == Request.PauseListening || request == Request.TurnOff || request == Request.Name

    // The voice's answer to [question], or the end of its wait ([ChannelAnswer.Silence], without a transcript). It names
    // the question, so that an answer that comes after that question ended answers no newer one (design 11 of sub-project
    // 7).
    private fun answer(state: State, question: VoiceQuestion, answer: ChannelAnswer, event: Event.Heard?, vararg first: Effect): Step =
        converse(
            state.copy(voiceQuestion = null, voiceAnswers = true, answered = question.number),
            InputIntent.Answer(answer, question.number),
            event,
            *first,
        )

    // The answer to "Yes?" is read as the words after the name, so it is never Other. While Kestrel is paused, a near miss
    // of "wake up" ends the wait and starts nothing (plan 4E). The conversation decides what a command means while a goal
    // waits for the owner's hand or return (design 6.4 of sub-project 7). "Stop" and "cancel" stop whatever is stoppable;
    // "never mind" only ends the wait.
    private fun whileAwaitingTask(state: State, request: Request, event: Event.Heard, shown: Effect): Step {
        val idle = state.copy(work = Work.IDLE)
        return when (request) {
            is Request.Task ->
                if (request.nearWakeUp && state.mode == Mode.PAUSED) Step(idle, listOf(shown)) else converse(idle, InputIntent.Command(request.text), event, shown)
            is Request.Other -> converse(idle, InputIntent.Command(request.text), event, shown)
            Request.Name -> askForTask(state, shown)
            Request.WakeUp -> changeMode(idle, Mode.LISTENING, shown)
            is Request.Stop -> if (state.stoppable && Normalizer.words(event.text).any { it in STOP_WORDS }) stopWork(state, shown) else Step(idle, listOf(shown))
            Request.PauseListening -> changeMode(idle, Mode.PAUSED, shown)
            Request.TurnOff -> turnOff(state, shown)
            is Request.OpenSettings -> Step(idle, listOf(shown, Effect.OpenSettings))
            // Requests.read never returns a call command; only readCall does.
            is Request.Call -> Step(state)
            else -> Requests.intent(request)?.let { converse(idle, it, event, shown) } ?: Step(idle, listOf(shown))
        }
    }

    // A transcript while Kestrel is idle: the conversation's question takes its answer first, a goal that works takes
    // only what stops it or reaches it after the name, and "stop" and "cancel" stop whatever else is stoppable: a goal
    // that waits for the owner's hand or return, or Kestrel's own request that runs; then the instant commands work
    // (design D2 of sub-project 5; design 5.5 and 6.4 of sub-project 7).
    private fun idle(state: State, request: Request, event: Event.Heard, shown: Effect, spokeAt: Long? = null): Step {
        val question = state.openQuestion
        return when {
            stopsWorkFirst(state, request, event) -> stopFirst(state, request, event, shown, spokeAt)
            question != null -> answering(state, question, request, event, shown)
            state.goalWorks -> whileGoalWorks(state, request, event, shown)
            state.stoppable && request is Request.Stop -> stopWork(state, shown)
            else -> whileIdle(state, request, event, shown, spokeAt)
        }
    }

    // A near miss of "Kestrel, wake up" while Kestrel is paused starts nothing: the owner meant to wake Kestrel up (plan 4E).
    // A command and Kestrel's own requests go to the conversation (design 5.5 of sub-project 7).
    private fun whileIdle(state: State, request: Request, event: Event.Heard, shown: Effect, spokeAt: Long? = null): Step = when (request) {
        is Request.Task ->
            if (request.nearWakeUp && state.mode == Mode.PAUSED) Step(state, listOf(shown)) else converse(state, InputIntent.Command(request.text), event, shown)
        Request.Name -> askForTask(state, shown)
        Request.WakeUp -> changeMode(state, Mode.LISTENING, shown)
        Request.PauseListening -> changeMode(state, Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings -> Step(state, listOf(shown, Effect.OpenSettings))
        is Request.Stop -> if (request.named) {
            // With nothing stoppable (idle() stops the rest), "Kestrel, stop" only ends the speech; plain "stop" may name
            // a button.
            Step(state, listOfNotNull(shown, Effect.StopSaying.takeIf { state.speaking }))
        } else {
            command(state, event.text.trim(), shown, spokeAt)
        }
        is Request.Other -> command(state, request.text, shown, spokeAt)
        // Requests.read never returns a call command; only readCall does.
        is Request.Call -> Step(state)
        else -> Requests.intent(request)?.let { converse(state, it, event, shown) } ?: Step(state)
    }

    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5), and only with the screen on and the phone
    // unlocked. "Again" runs the last repeatable command again, with its transcript. During a goal's hand-over they work
    // in the paused mode too, so that a step handed over can be finished by voice (design D2 of sub-project 5).
    private fun command(state: State, text: String, shown: Effect, spokeAt: Long?): Step {
        val spotted = state.spottedStop
        val late = state.questionEnded
        val fresh = if (spotted || late) state.copy(spottedStop = false, questionEnded = false) else state
        val listens = fresh.mode == Mode.LISTENING || (fresh.mode == Mode.PAUSED && fresh.goal == GoalPhase.HANDED_OVER)
        if (!listens || !fresh.screenOn) return Step(fresh)
        // Design D2: the transcript of the "stop" or "cancel" whose keyword already cancelled the command names no button.
        if (spotted && startsWithStop(text)) return Step(fresh, listOf(shown))
        // An answer that the owner began just before the question's wait ran out arrives after the command has ended. Its
        // number would name an element of "show numbers" or a square of the grid by now (section 9.4).
        if (late && namesNumber(text)) return Step(fresh, listOf(shown, Effect.Show(Feedback.QuestionEnded)))
        return startCommand(fresh, text, shown, spokeAt)
    }

    private fun namesNumber(text: String): Boolean = SpokenNumbers.answer(text) != null || SpokenNumbers.namesNumber(CommandParser.parse(text))

    private fun startCommand(state: State, text: String, shown: Effect, spokeAt: Long?): Step {
        val parsed = CommandParser.parse(text) ?: return Step(state, listOf(shown))
        if (parsed != Command.Again) return runCommand(state, parsed, text, spokeAt, shown)
        val last = state.lastCommand
        val lastHeard = state.lastHeard
        if (last == null || lastHeard == null) return Step(state, listOf(shown, Effect.Show(Feedback.NothingToRepeat)))
        return runCommand(state, last, lastHeard, spokeAt, shown)
    }

    // Every command gets the next number, so that the end of an older one changes nothing.
    private fun runCommand(state: State, command: Command, heard: String, spokeAt: Long?, vararg first: Effect): Step {
        val number = state.command + 1
        return Step(
            state.copy(work = Work.RUNNING_COMMAND, command = number, running = command, heard = heard),
            first.toList() + Effect.RunCommand(number, command, heard, spokeAt),
        )
    }

    // Section 7.3: while an instant command runs. A step that leaves RUNNING_COMMAND other than through the command's own
    // end goes through leaveCommand(), which cancels the command's job first.
    private fun duringCommand(state: State, event: Event): Step = when (event) {
        is Event.CommandEnded -> if (event.command == state.command) commandEnded(state, event.result) else Step(state)
        is Event.CommandAsks -> if (event.command == state.command) ask(state, event.question) else Step(state)
        is Event.CommandSays -> if (event.command == state.command) {
            say(state, Reply.Command(event.command, event.text), Effect.Show(Feedback.CommandText(event.text)))
        } else {
            Step(state)
        }
        is Event.CommandCounts -> if (event.command == state.command) Step(state.copy(counting = event.counting)) else Step(state)
        // The command's own question ends with its wait; the conversation's question, which may wait beside it, ends as it
        // would without a command, on the same latest wait, and the command goes on (whole-plan review of plan 7D, M2).
        is Event.AnswerWaitEnded -> keepOrLeave(state, event).then { commandWaitEnded(it, event.wait) }
        is Event.Heard -> heardDuringCommand(state, event)
        // A goal's round needs the screen (ScreenTurns): the conversation's work goes first, as for "stop".
        is Event.ScreenWanted -> if (event.command == state.command) cancelCommand(state) else Step(state)
        // A goal's round held the screen, so the command never started: it ends at once, as any command does while the
        // goal acts (design 6.5 of sub-project 7), and the bar says that Kestrel is busy with the goal.
        is Event.ScreenHeld -> if (event.command == state.command) {
            Step(state.copy(work = Work.IDLE, running = null, heard = null, asking = false, counting = false), listOf(Effect.Show(Feedback.GoalBusy)))
        } else {
            keepOrLeave(state, event)
        }
        // The owner starts to speak: a countdown holds until the transcript decides, and an answer gets the longer wait,
        // also the conversation's.
        Event.Speaking -> when {
            state.counting -> Step(state, listOf(Effect.HoldCommand(state.command, hold = true)))
            state.asking && !state.speaking -> waitForAnswer(state, answering = true)
            else -> keepOrLeave(state, event)
        }
        // An utterance without a transcript lets a held countdown go on, and gives an answer the whole wait again.
        Event.NothingHeard -> when {
            state.counting -> Step(state, listOf(Effect.HoldCommand(state.command, hold = false)))
            state.asking -> waitForAnswer(state)
            else -> keepOrLeave(state, event)
        }
        is Event.Spotted -> spottedDuringCommand(state, event.keyword)
        // The wait for the answer starts again when the question has been spoken.
        is Event.SpeechEnded ->
            speechEnded(state, event.spoken).let { ended -> if (state.speeches == 1 && state.asking) ended.then { waitForAnswer(it) } else ended }
        // A countdown ignores the assistant gesture, as it ignores Kestrel's other requests.
        Event.Assist -> if (state.counting) Step(state) else keepOrLeave(state, event)
        // The screen that "lock screen" turned off is what the command did; its end still comes.
        is Event.Screen -> if (!event.on && !locksScreen(state.running)) leaveFor(state, event) else keepOrLeave(state, event)
        // The call that "call X" placed becomes active: the command goes on in the mode In call, and its end still counts.
        is Event.CallState -> if (event.state == CallState.ACTIVE && state.running is PhoneCommand.Call) {
            withoutCommand(state, event).let { Step(it.state.copy(work = Work.RUNNING_COMMAND), it.effects) }
        } else {
            keepOrLeave(state, event)
        }
        else -> keepOrLeave(state, event)
    }

    // An event that leaves the work and the mode alone keeps the command; any other cancels it first and then acts as it
    // would without a command.
    private fun keepOrLeave(state: State, event: Event): Step {
        val step = withoutCommand(state, event)
        return if (step.state.work == Work.RUNNING_COMMAND && step.state.mode == state.mode) step else leaveFor(state, event)
    }

    private fun leaveFor(state: State, event: Event): Step = leaveCommand(state).then { withoutCommand(it, event) }.stopSayingOnce()

    // leaveCommand() does not lower the count of replies, so the step after it may stop the same reply again.
    private fun Step.stopSayingOnce(): Step {
        val first = effects.indexOf(Effect.StopSaying)
        return if (first < 0) this else copy(effects = effects.filterIndexed { i, effect -> i == first || effect != Effect.StopSaying })
    }

    // Cancels the running command's job, and stops a question or an announcement in progress.
    private fun leaveCommand(state: State, vararg first: Effect): Step = Step(
        state.copy(work = Work.IDLE, running = null, heard = null, asking = false, counting = false),
        first.toList() + Effect.CancelCommand + listOfNotNull(Effect.StopSaying.takeIf { state.speaking }),
    )

    // "Stop" or "cancel": the command is cancelled, and the bar says so.
    private fun cancelCommand(state: State, vararg first: Effect): Step =
        leaveCommand(state, *first).let { Step(it.state, it.effects + Effect.Show(Feedback.CommandCancelled)) }

    private fun locksScreen(command: Command?): Boolean = (command as? PhoneCommand.Global)?.action == GlobalCommand.LOCK_SCREEN

    // The command's result: the bar shows it or "Cancelled", Kestrel says what it must say, a repeatable command becomes
    // the one that "again" repeats, and an answer that did not answer the question is read as a new utterance.
    private fun commandEnded(state: State, result: CommandResult): Step {
        val cancelled = result.outcome == Outcome.CANCELLED
        val repeat = result.repeatable && result.outcome == Outcome.DONE
        val ended = state.copy(
            work = Work.IDLE,
            running = null,
            heard = null,
            asking = false,
            counting = false,
            lastCommand = if (repeat) result.acted ?: state.running else state.lastCommand,
            lastHeard = if (repeat) state.heard else state.lastHeard,
        )
        // Copied, because a property of another module allows no smart cast.
        val show = result.show
        val said = result.say
        val passOn = result.passOn
        val shown = when {
            show != null -> Effect.Show(Feedback.CommandText(show))
            cancelled && passOn == null -> Effect.Show(Feedback.CommandCancelled)
            else -> null
        }
        // A touch that cancelled a question also stops it while Kestrel still says it (section 14.1).
        var step = Step(ended, listOfNotNull(shown, Effect.StopSaying.takeIf { cancelled && state.speaking }))
        if (said != null) step = step.then { say(it, Reply.Text(said)) }
        if (passOn == null) return step
        return step.then { heard(it, Event.Heard(passOn, named = false)) }
    }

    // The end of wait number [wait] ends the command's question when it is the latest: the command hears no answer, and a
    // number in the next transcript may be a late one (section 9.4).
    private fun commandWaitEnded(state: State, wait: Int): Step =
        if (state.asking && wait == state.wait) {
            Step(
                state.copy(asking = false, questionEnded = true),
                listOf(Effect.AnswerCommand(state.command, null), Effect.TimeLateAnswers),
            )
        } else {
            Step(state)
        }

    // The question stays on the bar until the command ends. The wait for the answer starts now and again when the
    // question has been spoken.
    private fun ask(state: State, question: String): Step = say(
        state.copy(asking = true),
        Reply.Command(state.command, question),
        Effect.Show(Feedback.CommandText(question, keep = true)),
    ).then { waitForAnswer(it) }

    // Every wait gets the next number, as the waits for a task do, so that the end of an older wait changes nothing.
    private fun waitForAnswer(state: State, answering: Boolean = false): Step =
        Step(state.copy(wait = state.wait + 1), listOf(Effect.WaitForAnswer(answering)))

    // Section 7.3 and design D2 of sub-project 3: a transcript while a command runs. "Stop", "cancel", and a transcript
    // that starts with either, cancel it; so does "no" during a countdown. During a countdown, only those and the changes
    // of mode count, and anything else lets it go on. The owner's answer to the conversation's open question reaches it
    // while the command asks nothing (answerDuringCommand). Kestrel's own requests cancel the command and then run. While
    // the command asks, the transcript is the answer; otherwise the new command is dropped.
    private fun heardDuringCommand(state: State, event: Event.Heard): Step {
        if (state.inCall) return callCommand(state, event)
        val request = Requests.read(event.text, event.named, stopSpotted = event.stopSpotted)
            ?: return if (state.screenOn) withoutWordsDuringCommand(state) else Step(state)
        val counted = state.copy(emptyTranscripts = 0)
        if (!counted.screenOn && !stopsSomething(request)) return Step(counted)
        val shown = Effect.Show(Feedback.Heard(event.text))
        val stops = request is Request.Stop || (request is Request.Other && startsWithStop(event.text)) ||
            (state.counting && saysNo(event.text, event.named))
        if (stops) return cancelCommand(counted, shown)
        if (state.counting && request != Request.PauseListening && request != Request.TurnOff) {
            return Step(counted, listOf(shown, Effect.HoldCommand(state.command, hold = false)))
        }
        answerDuringCommand(counted, request, event, shown)?.let { return it }
        if (request !is Request.Other) return leaveCommand(counted).then { idle(it, request, event, shown) }.stopSayingOnce()
        if (state.asking) {
            return Step(counted.copy(asking = false), listOf(shown, Effect.AnswerCommand(state.command, request.text)))
        }
        return busy(counted, request.text, shown)
    }

    private fun withoutWordsDuringCommand(state: State): Step {
        val counted = withoutWords(state, awaiting = false)
        return when {
            state.counting -> Step(counted.state, counted.effects + Effect.HoldCommand(state.command, hold = false))
            state.asking -> counted.then { waitForAnswer(it) }
            else -> counted
        }
    }

    private fun startsWithStop(text: String): Boolean = Normalizer.words(text).firstOrNull { it !in BEFORE_STOP } in STOP_WORDS

    // "No", "Kestrel, no", "oh no, wait" or "no, not her": the first word that is neither a filler nor the name is "no",
    // whatever follows it. The name may come anywhere, misspelt too: a word counts as the name when Requests reads it
    // alone as the name.
    private fun saysNo(text: String, named: Boolean): Boolean =
        Normalizer.words(text).firstOrNull { it !in NO_FILLERS && Requests.read(it, named) != Request.Name } == "no"

    // Decision 3 of sub-project 3: a command that arrives while another runs is dropped. A recognised one says so on the
    // bar; bare speech may be conversation, so the bar says so only while dictation may run (a text or a bare command).
    private fun busy(state: State, text: String, shown: Effect): Step {
        val parsed = CommandParser.parse(text)
        val dictating = state.running is TextCommand || state.running is Command.Bare
        return if (parsed != null && (parsed !is Command.Bare || dictating)) {
            Step(state, listOf(shown, Effect.Show(Feedback.CommandBusy)))
        } else {
            Step(state, listOf(shown))
        }
    }

    // The spotter's "stop" or "cancel" cancels the command while Kestrel speaks or a countdown runs, at once. Its "Kestrel"
    // over Kestrel's voice cancels the command and waits for a task, as when idle, except during a countdown.
    private fun spottedDuringCommand(state: State, keyword: Keyword): Step = when {
        state.inCall -> Step(state)
        keyword != Keyword.KESTREL ->
            if (state.counting || state.speaking) cancelCommand(state.copy(spottedStop = true)) else Step(state)
        state.speaking && !state.counting -> leaveCommand(state).then { spotted(it, keyword) }.stopSayingOnce()
        else -> Step(state)
    }


    private fun spotted(state: State, keyword: Keyword): Step {
        if (!state.speaking || state.mode == Mode.OFF || state.inCall) return Step(state)
        return when (keyword) {
            // As after a cancelled command, the transcript of the same word then names no button (design D2). The spotter's
            // "stop" while Kestrel speaks stops whatever is stoppable: the goal in every phase, also while Kestrel speaks a
            // hand-over's sentence, the conversation's own question (plan 7B's decision 10) and Kestrel's own request that
            // runs (design 6.4 of sub-project 7).
            Keyword.STOP, Keyword.CANCEL ->
                if (state.stoppable) {
                    stopWork(state.copy(spottedStop = true))
                } else {
                    Step(state.copy(work = Work.IDLE, spottedStop = true), listOf(Effect.StopSaying))
                }
            // Kestrel does not wait for a command while a goal works or asks "Continue the goal?", or the screen is off.
            Keyword.KESTREL ->
                if (state.goalWorks || (state.goal != null && state.openQuestion != null) || !state.screenOn) {
                    Step(state, listOf(Effect.StopSaying))
                } else {
                    waitForTask(state, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask))
                }
        }
    }

    // The wait starts again when "Yes?" or the conversation's question has been spoken, so that the owner has all of it
    // to start the answer. A final reply that was not spoken to its end, because the owner or a call stopped it, Android
    // refused or interrupted it, or no voice session could speak it, opens no follow-up window: the owner did not hear
    // it, and its text stays on the bar (design 6.6 of sub-project 7).
    private fun speechEnded(state: State, spoken: Boolean): Step {
        val ended = state.copy(speeches = maxOf(0, state.speeches - 1), finalReply = state.finalReply?.takeIf { spoken })
        val finalReply = ended.finalReply
        return when {
            state.speeches == 1 && state.work == Work.AWAITING_TASK -> waitForTask(ended)
            state.speeches == 1 && state.voiceQuestion != null -> waitForAnswer(ended)
            ended.speeches == 0 && finalReply != null -> stayOff(ended.copy(finalReply = null)).then { followUp(it, finalReply) }
            else -> stayOff(ended)
        }
    }

    // Design 6.6 of sub-project 7: once Kestrel has finished speaking final reply number [reply], the next utterance is a
    // command without the name, for the window's length, while Kestrel can hear it (followsUp), also in the paused mode.
    // The bar counts the seconds down over the reply's text.
    private fun followUp(state: State, reply: Int): Step {
        if (state.followUpMillis <= 0 || state.work != Work.IDLE || !followsUp(state)) return Step(state)
        val seconds = ((state.followUpMillis + 999) / 1_000).toInt()
        return Step(
            state.copy(work = Work.FOLLOWING_UP, followUp = state.followUp + 1),
            listOf(Effect.TimeFollowUp(), Effect.Show(Feedback.FollowUp(seconds, reply))),
        )
    }

    // Review A-R2 of plan 7F: the final reply that Kestrel was speaking, or had spoken, is no longer one whose end may open
    // a window, and the step opened none for it, so its text no longer waits on the bar for a countdown.
    private fun withoutFollowUp(before: State, step: Step): Step {
        val reply = before.finalReply ?: return step
        if (step.state.finalReply == reply) return step
        if (step.effects.any { it is Effect.Show && (it.feedback as? Feedback.FollowUp)?.reply == reply }) return step
        return step.copy(effects = step.effects + Effect.NoFollowUp(reply))
    }

    // Whether a follow-up window may open or stay open: the conversation is open without a goal, because steering a goal by
    // voice always needs the name (D9 of sub-project 7), and Kestrel is on and can hear the owner, with the screen on,
    // outside a call and with a microphone that Android does not block.
    private fun followsUp(state: State): Boolean =
        state.mode != Mode.OFF && !state.inCall && state.screenOn && !state.micBusy && state.conversation.open && state.goal == null

    // Design 6.6 of sub-project 7: the window holds only while Kestrel can hear a command in it and the bar counts its
    // seconds down, so that the bar and the window never disagree (re-check of plan 7D's Task 4, N1). It ends when it can
    // no longer be heard or a goal starts ([followsUp]: the screen turns off, a call rings, Kestrel turns off, Android
    // blocks the microphone, the conversation closes or its goal starts), and when anything else takes the bar: Kestrel's
    // next reply, spoken or shown, a change of mode, "Microphone blocked" or a failed start of the settings. Its timer and
    // a transcript end it where they come. The step that opens a window numbers a new one, so its own countdown never
    // counts as another text.
    private fun endFollowUp(before: State, step: Step): Step {
        val state = step.state
        if (state.work != Work.FOLLOWING_UP) return step
        val wasOpen = before.work == Work.FOLLOWING_UP && before.followUp == state.followUp
        val barTaken = wasOpen && step.effects.any { it is Effect.Say || it is Effect.Show }
        return if (barTaken || !followsUp(state)) step.copy(state = state.copy(work = Work.IDLE)) else step
    }

    // Design 5.5 of sub-project 7: what the voice reads of the conversation changed. The question that the voice answered
    // stays [State.answered] while the conversation still shows it, so that the next transcript does not answer it twice.
    private fun conversationChanged(state: State, view: VoiceView): Step {
        val changed = state.copy(
            conversation = view,
            answered = state.answered?.takeIf { it == view.question },
        )
        return stayOff(changed)
    }

    // Design 11 of sub-project 7: the voice channel's question ended, so its answer wait ends; a newer question that the
    // voice asked meanwhile stays.
    private fun channelWithdraws(state: State, question: Int): Step =
        if (state.voiceQuestion?.number == question) Step(state.copy(voiceQuestion = null, voiceAnswers = true)) else Step(state)

    // "Stay off" after an unlock waited for the goal's end and its final reply (design D2 of sub-project 5).
    private fun stayOff(state: State): Step =
        if (state.offAfterGoal && state.speeches == 0 && state.mode != Mode.OFF && !waitsForOwner(state)) turnOff(state) else Step(state)

    // The conversation's goal, or its final reply, waits for the owner (design 6.5 of sub-project 7).
    private fun waitsForOwner(state: State): Boolean =
        state.goal != null || state.conversation.replying

    // Design 11 of sub-project 7: Kestrel's words and final replies. While Kestrel is off they are only shown, and the
    // channel goes on at once.
    private fun channelSays(state: State, event: Event.ChannelSays): Step {
        val finalReply = event.reply.takeIf { event.final }
        val shown = Effect.Show(Feedback.ConversationText(event.text, finalReply = finalReply))
        if (state.mode == Mode.OFF) return Step(state, listOfNotNull(shown, Effect.EndReply(event.reply), finalReply?.let { Effect.NoFollowUp(it) }))
        return say(state, Reply.Channel(event.reply, event.text), shown, finalReply = finalReply)
    }

    // Design D2 of sub-project 5 and 11 of sub-project 7: the conversation asks one question at a time. The question stays
    // on the bar until the voice channel releases it; the wait for the answer starts now and again when it has been spoken.
    private fun channelAsks(state: State, event: Event.ChannelAsks): Step {
        if (state.mode == Mode.OFF) return Step(state)
        val asked = state.copy(voiceQuestion = VoiceQuestion(event.question, event.kind), voiceAnswers = event.voice, answered = null)
        return say(asked, Reply.Text(event.text, kept = true), Effect.Show(Feedback.ConversationText(event.text, keep = true)))
            .then { waitForAnswer(it) }
    }

    // Design D15 of sub-project 5: the owner does the step by hand. The bar keeps the sentence, and the instant commands
    // work; the conversation runs the hand-over's 3 minutes.
    private fun channelHandsOver(state: State, text: String): Step {
        val shown = Effect.Show(Feedback.ConversationText(text, keep = true))
        if (state.mode == Mode.OFF) return Step(state, listOf(shown))
        return say(state, Reply.Text(text, kept = true), shown)
    }

    // The bar shows "Yes?" at the start of the wait, also when "Yes?" cannot be spoken (section 8.9).
    private fun askForTask(state: State, vararg first: Effect): Step =
        waitForTask(state, *first, Effect.Show(Feedback.AwaitingTask)).then { say(it, Reply.Yes) }

    // Every wait gets the next number, so that the end of an older wait changes nothing. A wait for a command withdraws
    // Kestrel's own question, as "Kestrel" over a command's question ends it, so that the command that the owner then says
    // reaches the conversation (design D2 of sub-project 5); a goal's question stays.
    private fun waitForTask(state: State, vararg first: Effect, answering: Boolean = false): Step {
        val waiting = state.copy(work = Work.AWAITING_TASK, wait = state.wait + 1)
        val step = Step(waiting, first.toList() + Effect.WaitForTask(answering))
        val question = state.openQuestion
        if (state.goal != null || question == null) return step
        return step.then { answer(it, question, ChannelAnswer.Interrupted, null) }
    }

    // Design 5.5 of sub-project 7: the owner's [intent] for the conversation. It ends the time in which a number may be a
    // late answer, as a task's start did.
    private fun converse(state: State, intent: InputIntent, heard: Event.Heard?, vararg first: Effect): Step =
        Step(state.copy(questionEnded = false), first.toList() + Effect.Converse(intent, heard))

    // "Stop" and "cancel" stop whatever is stoppable (State.stoppable): the goal in every phase and Kestrel's own request,
    // and they withdraw the conversation's question: its answer wait ends, and the conversation gets Stop (plan 7B's
    // decision 10; design 6.4 of sub-project 7).
    private fun stopWork(state: State, vararg first: Effect): Step = converse(
        state.copy(work = Work.IDLE, voiceQuestion = null, voiceAnswers = true, answered = state.openQuestion?.number ?: state.answered),
        InputIntent.Stop,
        null,
        *first,
        *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
    )

    // Only a final reply opens a follow-up window; Kestrel's next reply ends the open one (endFollowUp).
    private fun say(state: State, reply: Reply, vararg first: Effect, finalReply: Int? = null): Step = Step(
        state.copy(speeches = state.speeches + 1, finalReply = finalReply),
        first.toList() + Effect.Say(reply),
    )

    private fun switch(state: State, mode: Mode, vararg first: Effect): Step =
        Step(state.copy(mode = mode), first.toList() + Effect.Show(Feedback.ModeChanged(mode)))

    // Shows a mode only when it changes.
    private fun changeMode(state: State, mode: Mode, vararg first: Effect): Step =
        if (state.mode == mode) Step(state, first.toList()) else switch(state, mode, *first)

    // Off also forgets the replies in progress and "mic busy", so that a lost SpeechEnded cannot keep recognition off,
    // and the count of empty transcripts, so that the next voice session may show the hint again. A goal goes on: the
    // conversation asks its questions on their cards while Kestrel is off (plan 7B's decision 10), and the voice asks them
    // again once Kestrel is on.
    private fun turnOff(state: State, vararg first: Effect): Step = Step(
        state.copy(
            mode = Mode.OFF,
            work = Work.IDLE,
            speeches = 0,
            micBusy = false,
            emptyTranscripts = 0,
            noWordsShown = false,
            beforeCall = null,
            // The next run's reports start the count of wake-ups again (section 8.5).
            screenSeen = false,
            // "Again" never repeats a command of an earlier run, and the owner's last words go.
            lastCommand = null,
            lastHeard = null,
            spottedStop = false,
            questionEnded = false,
            voiceQuestion = null,
            voiceAnswers = true,
            answered = null,
            offAfterGoal = false,
            finalReply = null,
        ),
        first.toList() + listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.Show(Feedback.ModeChanged(Mode.OFF)),
            Effect.StopService,
        ),
    )

    // Section 8.5: after an unlock, Kestrel follows the setting "On screen wake-up" while it listens or is paused; a call
    // keeps its mode. Waiting for a task ends when the screen turns off, and so does the follow-up window (endFollowUp).
    private fun screen(state: State, on: Boolean): Step {
        val changed = state.copy(
            screenOn = on,
            work = if (!on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work,
            // An unlock is a wake-up only after the owner has used the phone in this run: not the first report after a
            // start, not the unlock after a start on the lock screen, and not a report from a service that stops.
            screenSeen = state.mode != Mode.OFF && (on || state.screenSeen),
        )
        // The conversation pauses its goal on the lock itself, with listening on or off (design 6.5 of sub-project 7).
        if (!on || state.screenOn || !state.screenSeen || (state.mode != Mode.LISTENING && state.mode != Mode.PAUSED)) {
            return Step(changed)
        }
        return when (state.onWakeUp) {
            WakeUpMode.RESUME -> Step(changed)
            WakeUpMode.LISTEN -> changeMode(changed, Mode.LISTENING)
            // "Stay off" waits until the goal has ended, so that it never drops "Continue the goal?" or the final reply.
            WakeUpMode.STAY_OFF -> if (waitsForOwner(changed)) Step(changed.copy(offAfterGoal = true)) else turnOff(changed)
        }
    }

    // Section 8.5: 30 s without speech pause listening. A timer that ran out while Kestrel spoke, waited for a task, ran
    // one, did not record or found its microphone blocked starts again, because the owner was not silent then, or could
    // not be heard.
    private fun silent(state: State, silence: Int): Step = when {
        silence != state.silence || state.mode != Mode.LISTENING || !state.pauseAfterSilence -> Step(state)
        !hears(state) ->
            Step(state.copy(silence = state.silence + 1), listOf(Effect.TimeSilence))
        else -> switch(state, Mode.PAUSED)
    }

    // Section 8.5: while Kestrel listens with the setting on, the timer starts whenever Kestrel can hear the owner again
    // (it starts to listen, the screen comes back on, or its reply, the wait for a task or a task ends), when the setting
    // goes on, when the owner starts to speak, and when an utterance ends, so that the time counts from the end of the
    // owner's speech. Each timer gets the next number.
    private fun silenceTimer(before: State, event: Event, step: Step): Step {
        val after = step.state
        val start = after.pauseAfterSilence && hears(after) &&
            (
                !hears(before) || !before.pauseAfterSilence || event == Event.Speaking || event is Event.Heard ||
                    event == Event.NothingHeard
                )
        return if (start) Step(after.copy(silence = after.silence + 1), step.effects + Effect.TimeSilence) else step
    }

    // Section 9.4: a late answer to a question that ended without one comes within moments. A pause, a call, a goal that
    // works or is paused, the wait for a command, and the screen turning off end that time, and so does
    // Event.LateAnswersEnded 10 s after the question.
    private fun endLateAnswers(step: Step): Step {
        val state = step.state
        val over = state.mode != Mode.LISTENING || !state.screenOn || state.work == Work.AWAITING_TASK ||
            state.goalWorks || state.goal == GoalPhase.PAUSED
        return if (state.questionEnded && over) step.copy(state = state.copy(questionEnded = false)) else step
    }

    // Kestrel listens, idle, silent itself and recording, and Android does not block its microphone ("Microphone
    // blocked", section 14.6): only then does the time without speech count.
    private fun hears(state: State): Boolean =
        state.mode == Mode.LISTENING && !state.speaking && state.work == Work.IDLE && state.microphone && !state.micBusy &&
            state.openQuestion == null && !state.goalWorks

    // Section 8.6: the assistant gesture or an earbud's button. Kestrel listens, if it was paused, and waits for a task
    // as after "Kestrel" on its own; over its own voice it stops speaking and waits, as after an interruption with
    // "Kestrel". While a goal works or a call or the lock paused it, it does what "Kestrel" does then: it stops speaking. A
    // call keeps its mode, and an Assist while Kestrel is off was queued behind a turn-off, because Listening sends it only
    // to an open session.
    private fun assist(state: State): Step = when {
        state.mode == Mode.OFF || state.inCall -> Step(state)
        state.goalWorks || state.goal == GoalPhase.PAUSED -> Step(state, listOfNotNull(Effect.StopSaying.takeIf { state.speaking }))
        // Section 8.5: Kestrel does not record on the lock screen, so it asks nothing there; it hears the owner after the
        // unlock, which is therefore no wake-up, as after a start on the lock screen.
        !state.screenOn -> changeMode(
            state.copy(screenSeen = false),
            Mode.LISTENING,
            *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
        )
        state.speaking ->
            changeMode(state, Mode.LISTENING).then { waitForTask(it, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask)) }
        else -> changeMode(state, Mode.LISTENING).then { askForTask(it) }
    }

    // Section 8.7: while a call rings, only "answer", "answer on speaker" and "decline" count, also on the lock screen;
    // during the call, only "hang up", "mute" and "speaker" after the name, and only with the setting "Commands during a
    // call".
    private fun callCommand(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.IN_CALL && !state.callCommands) return Step(state)
        val command = Requests.readCall(event.text, event.named, ringing = state.mode == Mode.RINGING) ?: return Step(state)
        return Step(state, listOf(Effect.Show(Feedback.Heard(event.text)), Effect.CallCommand(command)))
    }

    // Section 8.7: a call that rings while Kestrel listens or is paused, or that is active, interrupts the mode, and the
    // mode comes back after the call. While Kestrel is off, calls change nothing.
    private fun call(state: State, call: CallState): Step = when (call) {
        CallState.RINGING ->
            if (state.mode == Mode.LISTENING || state.mode == Mode.PAUSED) enterCall(state, Mode.RINGING) else Step(state)
        CallState.ACTIVE -> when (state.mode) {
            Mode.LISTENING, Mode.PAUSED -> enterCall(state, Mode.IN_CALL)
            Mode.RINGING -> switch(state, Mode.IN_CALL)
            Mode.OFF, Mode.IN_CALL -> Step(state)
        }
        CallState.IDLE -> {
            val back = state.beforeCall
            if (!state.inCall || back == null) Step(state) else switch(state.copy(beforeCall = null), back)
        }
    }

    // Kestrel stops speaking at once, because Android refuses its audio focus during a call (prototype P3). The call ends
    // the wait for a command; the conversation pauses its goal and asks "Continue the goal?" after the call itself (design
    // 6.5 of sub-project 7).
    private fun enterCall(state: State, mode: Mode): Step = switch(
        state.copy(beforeCall = state.mode, work = Work.IDLE),
        mode,
        *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
    )

    // During a call, the toggle, the pause and the resume choose the mode that comes back after the call, and the bar
    // shows it.
    private fun afterCall(state: State, mode: Mode): Step =
        if (state.beforeCall == mode) {
            Step(state)
        } else {
            Step(state.copy(beforeCall = mode), listOf(Effect.Show(Feedback.ModeChanged(mode))))
        }

    /** This step, then the step that [next] makes from its state. */
    private fun Step.then(next: (State) -> Step): Step = next(state).let { Step(it.state, effects + it.effects) }

    // Section 14.6: how many transcripts without words in a row bring the hint.
    private const val EMPTY_TRANSCRIPTS_FOR_HINT = 3
}
