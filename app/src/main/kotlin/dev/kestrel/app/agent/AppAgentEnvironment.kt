package dev.kestrel.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.agent.port.ModelInfo
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.port.NoEyes
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.port.RoleModel
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.commands.AndroidDialer
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.providers.ProviderGraph
import dev.kestrel.datatools.AndroidDataPort
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.settings.SettingsStore
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.EmergencyNumbers
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.OwnActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * Builds what each task needs from Kestrel's parts (design D1, D2, D12, D13, D16 and D19 of sub-project 5). A task needs
 * the accessibility service and the touch watcher: without the service it cannot start ("I couldn't start the task:
 * Kestrel's accessibility service is off."), and when the watcher's window cannot be added it does not run
 * ([AgentTexts.NO_WATCHER]). Kestrel's own memory requests ([TaskStart.local]) need neither. The settings come from the
 * encrypted store when the task starts: the mode, test mode and the owner's names, the agent-free apps with the owner's
 * changes, Android's emergency-number check and the phone apps with the default one. [eyes] and the badge functions are
 * read at each task, because plan 5C sets them. Release stops the watcher and drops the task's media items.
 */
internal class AppAgentEnvironment(
    private val context: Context,
    private val session: () -> ScreenSession?,
    private val settings: SettingsStore,
    private val memory: SettingsMemoryStore,
    private val appendLog: suspend (ActivityEvent) -> Unit,
    private val models: AppModels,
    private val send: (Event) -> Unit,
    private val states: Flow<State>,
    private val eyes: () -> EyesPort,
    private val showBadges: suspend (ScreenSession, List<Badge>) -> Unit,
    private val hideBadges: suspend (ScreenSession) -> Unit,
    private val askAndroid: suspend (List<String>) -> PermissionEnd,
    private val knownNames: suspend () -> Set<String>,
    private val report: (String, Throwable) -> Unit,
) : AgentEnvironment {
    private val apps = AndroidApps(context)

    override suspend fun prepare(start: TaskStart): Prepared {
        val screen = session()
        if (screen == null && !start.local) return Prepared.Refused(AgentTexts.couldNotStart(AgentTexts.NO_SERVICE))
        val model = when (val choice = start.model) {
            null -> NoModel
            is ModelChoice.Override -> choice.port
            is ModelChoice.Profile -> models.port(choice.lightweight) ?: return Prepared.Refused(AgentTexts.NO_PROFILE)
        }
        val agentSettings = agentSettings(start)
        val open: suspend (Intent) -> Boolean = { intent -> session()?.actions?.openIntent(intent) is ActionResult.Done }
        val data = AndroidDataPort(context, open, agentSettings.agentFree::isAgentFree)
        val owner = AppOwnerPort(
            start.task,
            send,
            SessionOwnerScreen(session),
            AndroidOtherAudio(context),
            AppHandOverSignals(context, open, states),
            askAndroid,
            { Permissions.granted(context, it) },
        )
        val ports = AgentPorts(
            AppScreenPort(session, context.packageManager, showBadges, hideBadges, start.acted),
            if (start.local) NoEyes else eyes(),
            data,
            owner,
            model,
            apps,
            memory,
            LogTaskLog(start.id, appendLog, report),
            AndroidAgentClock,
        )
        val provider = model.model.providerName
        if (start.local || screen == null) return Prepared.Ready(ports, agentSettings, owner, emptySet(), emptyFlow(), provider) {}
        if (!screen.startTouchWatcher()) return Prepared.Refused(AgentTexts.NO_WATCHER)
        val touches = screen.touches.filter { it.owner }.map { touch ->
            OwnerTouch(touch.sinceNodeActionMillis?.takeIf { it <= OwnActions.LATE_ECHO_MILLIS })
        }
        return Prepared.Ready(ports, agentSettings, owner, knownNames(), touches, provider) {
            screen.stopTouchWatcher()
            data.forgetTask()
        }
    }

    private suspend fun agentSettings(start: TaskStart): AgentSettings {
        val changes = settings.get(SettingKeys.AGENT_FREE_APPS)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val phoneApp = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return AgentSettings(
            safety = settings.get(SettingKeys.SAFETY),
            agentFree = AgentFreeApps(changes.added.toSet(), changes.removed.toSet()),
            emergency = EmergencyNumbers { digits -> AndroidDialer.emergency(telephony, digits) },
            phonePackages = EmergencyGuard.PHONE_PACKAGES + listOfNotNull(phoneApp),
            selfChatAliases = start.selfChatAliases,
        )
    }

    override fun toString(): String = "AppAgentEnvironment"

    // Kestrel's own memory requests call no model (design D2 of sub-project 5).
    private object NoModel : ModelPort {
        private val none = RoleModel("none", "none", RequestStyle.CHAT_COMPLETIONS, null, takesImages = false, signedThinking = false)

        override val model = ModelInfo("Kestrel", "kestrel", countsCredits = false, localOllama = false, lightweight = none, versatile = none)

        override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse =
            throw IllegalStateException("Kestrel's own memory requests call no model.")
    }
}

/**
 * The default profile's models for a task (design D1, D5, D7 and D28 of sub-project 5): it waits up to 5 s for the store
 * to answer, because just after the process starts the repository answers null although a profile exists, and then
 * gives null only when no default profile is set up. A task's [port] with a lightweight model ID of its own (the debug
 * hook's extra `model`) gets a client that resolves that ID for the lightweight role and the profile's model for the
 * versatile one.
 */
internal class AppModels(
    private val providers: () -> ProviderGraph,
    private val applied: StateFlow<ProvidersSetting?>,
    private val discovered: suspend (profileId: String) -> List<DiscoveredModel>,
    private val cache: CacheState,
    private val report: (String, Throwable) -> Unit,
) {
    suspend fun port(lightweight: String?): ModelPort? {
        val graph = providers()
        withTimeoutOrNull(STORED_WAIT_MILLIS) { graph.stored.first { it != null } }
        val client = graph.repository.defaultClient() ?: return null
        val profile = client.profile
        val info = ModelInfos.of(profile, graph.catalog.provider(profile.providerId), discovered(profile.id), lightweight)
        val chosen = if (lightweight == null) client else withLightweight(graph, profile, lightweight)
        return AppModelPort(info, cache, chosen::complete)
    }

    private fun withLightweight(graph: ProviderGraph, profile: Profile, lightweight: String) = ProfileClient(
        profile,
        graph.catalog,
        applied,
        { current, role ->
            val resolved = if (role == ModelRole.LIGHTWEIGHT) current.copy(lightweight = current.lightweight.copy(id = lightweight)) else current
            graph.resolver.resolve(resolved, role)
        },
        graph::adapter,
        graph.localNetwork,
        graph::addSpending,
        NO_LIMITS,
        report = report,
    )

    override fun toString(): String = "AppModels"

    private companion object {
        const val STORED_WAIT_MILLIS = 5_000L

        // A comparison run of the debug hook does not mark a ChatGPT plan's limit.
        val NO_LIMITS = object : PlanLimits {
            override fun reached(profileId: String) = Unit

            override fun cleared(profileId: String) = Unit
        }
    }
}

/** The launcher apps of the owner's profile (design D11 of sub-project 5), read with the `<queries>` of spec 13.6. */
internal class AndroidApps(private val context: Context) : AppsPort {
    override suspend fun launcherApps(): List<LauncherApp> = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packages.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { info ->
                val app = info.activityInfo.packageName
                LauncherApp(info.loadLabel(packages).toString(), app, version(packages, app))
            }
            .distinctBy { it.packageName }
    }

    override suspend fun label(packageName: String): String? = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        try {
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun version(packages: PackageManager, app: String): String? = try {
        packages.getPackageInfo(app, PackageManager.PackageInfoFlags.of(0)).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

/** Kestrel's working time, which a sleeping phone does not stop, and the local time of the task message. */
internal object AndroidAgentClock : AgentClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun now(): ZonedDateTime = ZonedDateTime.now()
}
