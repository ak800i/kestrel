package dev.kestrel.app.goal

import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState

/** A type of foreground service that the goal service uses (design 10.6 of sub-project 7), with the hooks' name. */
internal enum class ForegroundType(val wire: String) {
    SPECIAL_USE("special-use"),
    MEDIA_PLAYBACK("media-playback"),
}

/** The goal service as its coordinator knows it: not running, asked to start, or in the foreground. */
internal enum class ServiceState { STOPPED, STARTING, RUNNING }

/** What the running goal service shows, and with which foreground-service types. */
internal data class Shown(val notice: GoalNotice, val types: Set<ForegroundType>)

/**
 * The goal service as its rules see it: the conversation's [goal] and the number of the input that started or last
 * resumed it ([command]), whether its final reply is being said ([replying]), the goal's [notice], how many of Kestrel's
 * speeches run ([speakers]), the [service], the command whose start Kestrel asked for last ([startedFor]), whether a
 * refusal left the open goal without its service ([lost]), and, for the hooks, how many starts Kestrel asked for and how
 * many Android refused in this process.
 */
internal data class GoalServiceState(
    val goal: GoalPhase? = null,
    val command: Int = 0,
    val replying: Boolean = false,
    val notice: GoalNotice? = null,
    val speakers: Int = 0,
    val service: ServiceState = ServiceState.STOPPED,
    val startedFor: Int = 0,
    val lost: Boolean = false,
    val starts: Int = 0,
    val refusals: Int = 0,
) {
    /** The service runs for every open goal (D19), and after its end while a running service says its final reply. */
    val wanted: Boolean get() = goal != null || (replying && service != ServiceState.STOPPED)
}

/** What happens to the goal service. */
internal sealed interface GoalServiceEvent {
    /**
     * The conversation's view: the goal's phase, whether the goal is being stopped ([stopping], as after the owner's stop
     * at a restart), the goal's command, whether a final reply is being said, and the goal's notice.
     */
    data class Viewed(val goal: GoalPhase?, val stopping: Boolean, val command: Int, val replying: Boolean, val notice: GoalNotice?) : GoalServiceEvent

    /** The service is in the foreground. */
    data object Started : GoalServiceEvent

    /** Android refused a start: `startForegroundService()` or the service's `startForeground()` threw. */
    data object Refused : GoalServiceEvent

    /** The service ended. */
    data object Destroyed : GoalServiceEvent

    /** A speech of Kestrel's begins or ends. */
    data class Speaking(val active: Boolean) : GoalServiceEvent
}

/** What the coordinator does for the rules: ask Android to start the service, or hold the goal whose start was refused. */
internal sealed interface GoalServiceCommand {
    data object Start : GoalServiceCommand

    /** Holds the goal of command [command], whose start Android refused (`ConversationEngine.backgroundRefused`). */
    data class HoldGoal(val command: Int) : GoalServiceCommand
}

internal data class GoalServiceStep(val state: GoalServiceState, val commands: List<GoalServiceCommand> = emptyList())

/**
 * When the goal service runs and what it shows (design 10.6 and D19 of sub-project 7; plan 7E), as one function from a
 * state and an event to the new state and its commands. The service starts when a goal opens, also a held goal that a
 * restart restored, and again each time the goal's work begins after a hold, also while it runs, so that every owner's
 * input that starts or resumes a goal starts it from that input's context: the conversation screen in front, the voice
 * path while the listening service runs, or the notification's Continue. A goal that is being stopped starts nothing. The
 * service runs until the goal has ended and its final reply has been said. A start that Android refuses holds the goal of
 * the command that asked for it, when that goal has no running service; a stopped service shows nothing, and while the
 * goal that lost its service is held, Kestrel posts its notice without the service, so that its Continue can start the
 * service from the notification. Android's end of a service that a goal still needs starts it again. From Android 14 the
 * service is of the type specialUse, with mediaPlayback while Kestrel speaks; Android 13 checks no type, and hardens no
 * background audio, so the service has none there.
 */
internal object GoalServiceRules {
    /** The first Android version that checks a foreground service's type (Android 14). */
    const val TYPED_SDK = 34

    private val WORKING = setOf(GoalPhase.ACTING, GoalPhase.ASKING, GoalPhase.HANDED_OVER)

    fun next(state: GoalServiceState, event: GoalServiceEvent): GoalServiceStep = when (event) {
        is GoalServiceEvent.Viewed -> viewed(state, event)
        GoalServiceEvent.Started -> GoalServiceStep(state.copy(service = ServiceState.RUNNING))
        GoalServiceEvent.Refused -> refused(state)
        GoalServiceEvent.Destroyed -> destroyed(state)
        is GoalServiceEvent.Speaking ->
            GoalServiceStep(state.copy(speakers = (state.speakers + if (event.active) 1 else -1).coerceAtLeast(0)))
    }

    /** What the service shows in [state] on Android [sdk]; null when it should not run, and while it is stopped. */
    fun shown(state: GoalServiceState, sdk: Int): Shown? =
        if (state.wanted && state.service != ServiceState.STOPPED) Shown(state.notice ?: GoalNotices.CLOSING, types(sdk, state.speakers > 0)) else null

    /** The held goal's notice that Kestrel posts without the service after a refusal; null otherwise. */
    fun posted(state: GoalServiceState): GoalNotice? =
        state.notice?.takeIf { state.lost && state.service == ServiceState.STOPPED && state.goal in GoalState.HELD }

    /** The service's types on Android [sdk]: none on Android 13, specialUse from Android 14, with mediaPlayback while [speaking]. */
    fun types(sdk: Int, speaking: Boolean): Set<ForegroundType> = when {
        sdk < TYPED_SDK -> emptySet()
        speaking -> setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK)
        else -> setOf(ForegroundType.SPECIAL_USE)
    }

    private fun viewed(state: GoalServiceState, event: GoalServiceEvent.Viewed): GoalServiceStep {
        val begins = event.goal != null && !event.stopping && (state.goal == null || (event.goal in WORKING && state.goal !in WORKING))
        val seen = state.copy(
            goal = event.goal,
            command = event.command,
            replying = event.replying,
            notice = event.notice ?: GoalNotices.CLOSING.takeIf { event.replying },
            lost = state.lost && event.goal != null,
        )
        return when {
            begins -> start(seen)
            seen.wanted -> GoalServiceStep(seen)
            else -> GoalServiceStep(seen.copy(service = ServiceState.STOPPED, notice = null))
        }
    }

    // A refused new start of a service that runs leaves it running; a goal without its service is held (design 10.6).
    private fun refused(state: GoalServiceState): GoalServiceStep {
        val counted = state.copy(refusals = state.refusals + 1)
        if (state.service == ServiceState.RUNNING) return GoalServiceStep(counted)
        val stopped = counted.copy(service = ServiceState.STOPPED, lost = state.goal != null)
        return GoalServiceStep(stopped, listOfNotNull(GoalServiceCommand.HoldGoal(state.startedFor).takeIf { state.goal != null }))
    }

    // Only the end of a running service is news: a stop that the rules asked for, or a refusal, made it stopped already, and
    // a start that is on its way outlives the end of an older instance.
    private fun destroyed(state: GoalServiceState): GoalServiceStep = when {
        state.service != ServiceState.RUNNING -> GoalServiceStep(state)
        state.goal != null -> start(state.copy(service = ServiceState.STOPPED))
        else -> GoalServiceStep(state.copy(service = ServiceState.STOPPED))
    }

    private fun start(state: GoalServiceState): GoalServiceStep = GoalServiceStep(
        state.copy(
            service = if (state.service == ServiceState.STOPPED) ServiceState.STARTING else state.service,
            startedFor = state.command,
            lost = false,
            starts = state.starts + 1,
        ),
        listOf(GoalServiceCommand.Start),
    )
}
