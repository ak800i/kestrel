package dev.kestrel.agent.port

import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import java.time.ZonedDateTime

/**
 * The default profile's two models (design D1, D7 of sub-project 5). `:app`'s `AppModelPort` (plan 5B) implements it
 * with `ProvidersRepository.defaultClient()`; the tests use the fixtures' `ScriptedModel`. [step] is the task's step,
 * from 1, which the port uses for its cache check (design D5).
 */
interface ModelPort {
    val model: ModelInfo

    suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse
}

/** What the agent knows of the profile: no key, no token. */
data class ModelInfo(
    val providerName: String,
    val providerId: String,
    val countsCredits: Boolean,
    val localOllama: Boolean,
    val lightweight: RoleModel,
    val versatile: RoleModel,
)

/**
 * One role's model: [signedThinking] for the Claude models that keep their signed thinking outside Copilot (design D32
 * item 9), and [unusable] the sentence of design D32 items 36 and 37 when the profile check refuses the role.
 */
data class RoleModel(
    val name: String,
    val id: String,
    val style: RequestStyle,
    val contextWindow: Int?,
    val takesImages: Boolean,
    val signedThinking: Boolean,
    val unusable: String? = null,
)

/** The launcher apps of the owner's profile (design D11). */
interface AppsPort {
    suspend fun launcherApps(): List<LauncherApp>

    suspend fun label(packageName: String): String?
}

data class LauncherApp(val label: String, val packageName: String, val versionName: String?) {
    override fun toString(): String = "LauncherApp(label=${label.length} chars, package=${packageName.length} chars)"
}

/** The task's entries in the activity log (design D25): `:app`'s `LogTaskLog` writes them, already redacted. */
interface TaskLog {
    suspend fun write(kind: Kind, text: String)

    enum class Kind { START, PROMPT, REPLY, SAFETY, ACTION, END, ERROR }
}

/** Kestrel's working time (design D7) and the local time of the task message (design D3). */
interface AgentClock {
    fun elapsedMillis(): Long

    fun now(): ZonedDateTime
}
