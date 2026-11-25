package dev.kestrel.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.app.providers.ProviderGraph
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProvidersSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

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
