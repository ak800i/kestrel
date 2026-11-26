package dev.kestrel.app.goal

import dev.kestrel.app.KestrelNotification
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.GoalEnding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a goal's notice shows (design 10.6 of sub-project 7; plan 7E): in [SERVICE], the goal service's notification in the
 * foreground, or in [POSTED], the held goal's notification that Kestrel posts without the service after a refusal, each
 * with its notification's [id]. Android colorizes only a foreground service's notification, so only [SERVICE] is built as
 * one ([foreground]); the posted one is a plain notification.
 */
internal enum class NoticePlace(private val notification: KestrelNotification, val foreground: Boolean) {
    SERVICE(KestrelNotification.GOAL_SERVICE, foreground = true),
    POSTED(KestrelNotification.GOAL_POSTED, foreground = false),
    ;

    val id: Int get() = notification.id
}

/** A goal's [notice] and the [place] where it shows. */
internal data class PlacedNotice(val notice: GoalNotice, val place: NoticePlace)

/**
 * The goal service as the hooks read it: the coordinator's [service], the [notice] that the service shows (null while it
 * shows none), the held goal's notice that Kestrel [posted] without the service, the [types] that the service should have
 * (null while it should show nothing), the types that it [applied] in the foreground (null while it is not there), and
 * the starts and refusals in this process.
 */
internal data class GoalServiceStatus(
    val service: ServiceState,
    val notice: GoalNotice?,
    val posted: GoalNotice?,
    val types: Set<ForegroundType>?,
    val applied: Set<ForegroundType>?,
    val starts: Int,
    val refusals: Int,
) {
    /**
     * The notice that shows now, and where: the service's, or else the one posted without it. The rules never show both
     * (`GoalServiceRules.posted` needs a stopped service, `shown` one that is not), so the order only names the rule once,
     * for the coordinator and the hooks alike. Whether a tap counts is not decided here: the receiver judges each tap
     * against the restored conversation's view (`GoalNotices.of` and `GoalButtons.input`), because after a restart this
     * status can lag behind that view.
     */
    val counting: PlacedNotice?
        get() = notice?.let { PlacedNotice(it, NoticePlace.SERVICE) } ?: posted?.let { PlacedNotice(it, NoticePlace.POSTED) }
}

/**
 * The goal service's coordinator (design 10.6 of sub-project 7; plan 7E). It follows the conversation's view, applies
 * [GoalServiceRules] one event at a time, asks Android to start the service through [start], which throws what
 * `startForegroundService()` throws, and calls [holdGoal] with the command whose start Android refused when that leaves a
 * goal without its service. `GoalService` follows [shown] and reports to [started], [applied], [failed] and [destroyed];
 * the app graph posts [posted]. [sdk] is Android's version. Any thread may call it; [report] gets each refusal by its
 * class.
 */
internal class GoalForeground(
    private val start: () -> Unit,
    private val holdGoal: (command: Int) -> Unit,
    private val sdk: Int,
    private val report: (String, Throwable) -> Unit,
) {
    private val lock = Any()
    private var state = GoalServiceState()
    private val shownNow = MutableStateFlow<Shown?>(null)
    private val postedNow = MutableStateFlow<GoalNotice?>(null)
    private val appliedNow = MutableStateFlow<Set<ForegroundType>?>(null)

    /** What the goal service shows now, and with which types; null while it should show nothing. */
    val shown: StateFlow<Shown?> = shownNow.asStateFlow()

    /** The held goal's notice that Kestrel posts without the service after a refusal; null otherwise. */
    val posted: StateFlow<GoalNotice?> = postedNow.asStateFlow()

    /** The notice that shows now ([GoalServiceStatus.counting]). */
    fun notice(): GoalNotice? = status().counting?.notice

    /** Follows the conversation's [views] for as long as it runs. */
    suspend fun follow(views: Flow<ConversationView>) {
        views.map { view ->
            GoalServiceEvent.Viewed(
                goal = view.goal,
                stopping = view.goalEnding == GoalEnding.STOPPING,
                // The goal's own command, so that a refusal names the goal whose start Android refused (plan 7E).
                command = view.goalCommand ?: 0,
                // The final reply is said, or kept until the owner is there (plan 7B), also after the conversation closed.
                replying = view.goal == null && (view.quiet == REPLYING || view.keptForOwner),
                notice = GoalNotices.of(view),
            )
        }
            .distinctUntilChanged()
            .collect { on(it) }
    }

    /** The service is in the foreground with [types]. */
    fun started(types: Set<ForegroundType>) {
        appliedNow.value = types
        on(GoalServiceEvent.Started)
    }

    /** The running service changed its types to [types]. */
    fun applied(types: Set<ForegroundType>) {
        appliedNow.value = types
    }

    /** The service's `startForeground()` threw [error]: Android refused the start. */
    fun failed(error: Exception) {
        report("Starting the goal service", error)
        on(GoalServiceEvent.Refused)
    }

    /** The service ended. */
    fun destroyed() {
        appliedNow.value = null
        on(GoalServiceEvent.Destroyed)
    }

    /**
     * Runs [block], a speech of Kestrel's with listening off (plan 7F's typed replies), while the service has the type
     * mediaPlayback, which design 10.6 chose for Kestrel's audio in the background. Android 17 lets an app play audio in
     * the background only from a foreground service with while-in-use (main spec B5); whether it also asks for this type
     * is spike S9's finding (Task 8). It waits up to [APPLY_MILLIS] for the service to apply the type; on Android 13, or
     * without a goal's running service, [block] runs at once.
     */
    suspend fun <T> whileSpeaking(block: suspend () -> T): T {
        on(GoalServiceEvent.Speaking(true))
        try {
            if (ForegroundType.MEDIA_PLAYBACK in shownNow.value?.types.orEmpty()) {
                withTimeoutOrNull(APPLY_MILLIS) { appliedNow.first { ForegroundType.MEDIA_PLAYBACK in it.orEmpty() } }
            }
            return block()
        } finally {
            on(GoalServiceEvent.Speaking(false))
        }
    }

    fun status(): GoalServiceStatus = synchronized(lock) {
        val shown = shownNow.value
        GoalServiceStatus(state.service, shown?.notice, postedNow.value, shown?.types, appliedNow.value, state.starts, state.refusals)
    }

    private fun on(event: GoalServiceEvent) {
        val step = synchronized(lock) {
            GoalServiceRules.next(state, event).also {
                state = it.state
                shownNow.value = GoalServiceRules.shown(it.state, sdk)
                postedNow.value = GoalServiceRules.posted(it.state)
            }
        }
        for (command in step.commands) perform(command)
    }

    private fun perform(command: GoalServiceCommand) {
        when (command) {
            GoalServiceCommand.Start -> try {
                start()
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException and the other refusals of a start in the background.
                report("Starting the goal service", e)
                on(GoalServiceEvent.Refused)
            }
            is GoalServiceCommand.HoldGoal -> holdGoal(command.command)
        }
    }

    override fun toString(): String = "GoalForeground(${status()})"

    companion object {
        /** How long a speech waits for the service to add mediaPlayback. */
        const val APPLY_MILLIS = 1_000L

        private val REPLYING = QuietClock.Held(HoldReason.REPLYING)
    }
}
