package dev.kestrel.app.agent

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.NoEyes
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.commands.AndroidDialer
import dev.kestrel.app.conversation.TouchWatch
import dev.kestrel.conversation.goal.GoalEnvironment
import dev.kestrel.conversation.goal.RoundPurpose
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.datatools.AndroidDataPort
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.settings.SettingsStore
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.EmergencyNumbers
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.OwnActions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * What each round of a goal needs from Kestrel's phone (design D1, D2, D12, D13, D16 and D19 of sub-project 5; design 5.5
 * and 7.1 of sub-project 7). A round of a goal needs Kestrel's accessibility service and the touch watcher: without the
 * service it cannot start, and when the watcher's window cannot be added it does not run; both end the goal as
 * [EndReason.NO_SCREEN]. "Save that as" ([RoundPurpose.ROUTINE]) needs only the model and the memory. [models] chooses
 * the model, and a round without one ends as [EndReason.NO_PROFILE]. The settings come from the encrypted store when the round starts: the mode, test mode and the
 * owner's names, the agent-free apps with the owner's changes, Android's emergency-number check and the phone apps with
 * the default one. A round of a goal forgets the call backstop's last action, gives the backstop every action of its own
 * ([CallBackstop.acted]), its owner port is [GoalOwner] over the conversation's, and its goal is [WatchedGoal], which
 * starts the watcher again after a screen turn that waited. [eyes] and the badge functions are
 * read at each round, because plan 5C sets them. Release stops the watcher and drops the round's media items.
 */
internal class AppGoalEnvironment(
    private val context: Context,
    private val session: () -> ScreenSession?,
    private val settings: SettingsStore,
    private val memory: SettingsMemoryStore,
    private val appendLog: suspend (ActivityEvent) -> Unit,
    private val models: RoundModels,
    private val watcher: TouchWatch,
    private val backstop: CallBackstop,
    private val eyes: () -> EyesPort,
    private val showBadges: suspend (ScreenSession, List<Badge>) -> Unit,
    private val hideBadges: suspend (ScreenSession) -> Unit,
    private val knownNames: suspend () -> Set<String>,
    private val debuggable: Boolean,
    private val report: (String, Throwable) -> Unit,
    private val newId: (Int) -> String = ::taskId,
) : GoalEnvironment {
    private val apps = AndroidApps(context)

    /**
     * Debug builds only: what the debug hooks read of each round (plan 6B-basefix), which they install with [witnesses].
     * Null in a release build, which records nothing that only the hooks read.
     */
    @Volatile
    internal var evidences: RoundEvidences? = null

    /** Debug builds only: the self-chat aliases that the hook `agent-settings` set (design D18); empty otherwise. */
    @Volatile
    internal var selfChatAliases: Map<String, String> = emptyMap()

    /**
     * Debug builds only: makes the witness of each round from its input's options (plan 6B-basefix), which the debug
     * hooks install. Null in a release build, whose rounds get [TaskWitness.NONE].
     */
    @Volatile
    internal var witnesses: ((options: Map<String, String>) -> TaskWitness)? = null

    /**
     * Debug builds only: prepares every round in place of the phone's ports and words its end, as the A71 session's
     * `voice-task` does (plan 2E's stand-in task, design 14.4 of sub-project 7). Null otherwise.
     */
    @Volatile
    internal var standIn: GoalEnvironment? = null

    // The provider of the newest round, which names it in an error's sentence.
    @Volatile
    private var provider = ""

    override suspend fun prepare(start: RoundStart): RoundSetup {
        standIn?.let { return it.prepare(start) }
        if (start.purpose == RoundPurpose.ROUTINE) return routine(start)
        // Kestrel's accessibility service first, as the task path checked it before plan 7D: without it no model is
        // chosen, so the owner hears at once that the service is off, also in a test build without a script.
        val screen = session() ?: return RoundSetup.Refused(AgentTexts.couldNotStart(AgentTexts.NO_SERVICE), EndReason.NO_SCREEN)
        val model = when (val choice = choose(start)) {
            is RoundModels.Choice.Refused -> return RoundSetup.Refused(choice.sentence, choice.end)
            is RoundModels.Choice.Model -> choice.port
        }
        val agentSettings = agentSettings()
        val taskId = newId(start.command)
        val log = LogTaskLog(taskId, appendLog, report)
        backstop.forget()
        val witness = witnesses?.invoke(start.options) ?: TaskWitness.NONE
        val round = evidences?.start(start.command, witness)
        val data = witness.watch(AndroidDataPort(context, agentSettings.agentFree::isAgentFree))
        val ports = AgentPorts(
            AppScreenPort.create(session, context.packageManager, showBadges, hideBadges, backstop::acted),
            eyes(),
            data,
            GoalOwner(start.owner, watcher, witness),
            model,
            apps,
            memory,
            log,
            AndroidAgentClock,
            WatchedGoal(start.goal, watcher),
            start.history,
        )
        // Read before the watcher starts, so that a failed read leaves no watcher on the screen.
        val names = knownNames()
        if (!startWatcherOrStop(screen::startTouchWatcher, screen::stopTouchWatcher)) {
            return RoundSetup.Refused(AgentTexts.NO_WATCHER, EndReason.NO_SCREEN)
        }
        val touches = screen.touches.filter { it.owner }
            .onEach { touch ->
                round?.touched(signal = touch.deviceId == OwnActions.SIGNAL_DEVICE)
                touch.sinceNodeActionMillis?.takeIf { it <= OwnActions.LATE_ECHO_MILLIS }?.let {
                    log.write(TaskLog.Kind.ACTION, "possible late echo after $it ms")
                }
            }
            .map { }
        return RoundSetup.Ready(ports, agentSettings, taskId, names, touches, offersRoutines = !debuggable) {
            try {
                screen.stopTouchWatcher()
            } finally {
                data.forgetTask()
            }
        }
    }

    // "Save that as" (design D23 of sub-project 5): a routine's round needs only the model and the memory.
    private suspend fun routine(start: RoundStart): RoundSetup {
        val model = when (val choice = choose(start)) {
            is RoundModels.Choice.Refused -> return RoundSetup.Refused(choice.sentence, choice.end)
            is RoundModels.Choice.Model -> choice.port
        }
        val agentSettings = agentSettings()
        val taskId = newId(start.command)
        val log = LogTaskLog(taskId, appendLog, report)
        val data = AndroidDataPort(context, agentSettings.agentFree::isAgentFree)
        val screen = AppScreenPort.create(session, context.packageManager, showBadges, hideBadges) {}
        val ports = AgentPorts(screen, NoEyes, data, start.owner, model, apps, memory, log, AndroidAgentClock, start.goal, start.history)
        return RoundSetup.Ready(ports, agentSettings, taskId, emptySet(), emptyFlow(), offersRoutines = false) {}
    }

    // The round's model, whose provider names it in an error's sentence.
    private suspend fun choose(start: RoundStart): RoundModels.Choice =
        models.choose(start.options).also { choice -> if (choice is RoundModels.Choice.Model) provider = choice.port.model.providerName }

    override fun sentence(report: TaskReport): String = standIn?.sentence(report) ?: AgentTexts.sentence(report, provider)

    private suspend fun agentSettings(): AgentSettings {
        val changes = settings.get(SettingKeys.AGENT_FREE_APPS)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val phoneApp = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return AgentSettings(
            safety = settings.get(SettingKeys.SAFETY),
            agentFree = AgentFreeApps(changes.added.toSet(), changes.removed.toSet()),
            emergency = EmergencyNumbers { digits -> AndroidDialer.emergency(telephony, digits) },
            phonePackages = EmergencyGuard.PHONE_PACKAGES + listOfNotNull(phoneApp),
            selfChatAliases = selfChatAliases,
        )
    }

    override fun toString(): String = "AppGoalEnvironment"

    companion object {
        /** `task-<n>-<8 hexadecimal characters>` (design D25 of sub-project 5), for the logs of command n's round. */
        fun taskId(command: Int): String = "task-$command-%08x".format(Random.nextInt())
    }
}

/**
 * Starts the touch watcher (design D19 of sub-project 5). When [start] throws, also when the round is cancelled after the
 * watcher's window was added, [stop] removes the window again before the exception goes on, because no release would.
 */
internal suspend fun startWatcherOrStop(start: suspend () -> Boolean, stop: suspend () -> Unit): Boolean = try {
    start()
} catch (e: Throwable) {
    withContext(NonCancellable) { stop() }
    throw e
}
