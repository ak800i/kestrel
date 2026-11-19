package dev.kestrel.app.debug

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.RequestHeaders
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.ToolSpec
import dev.kestrel.providers.client.ModelEntries
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.copilot.CopilotAllowance
import dev.kestrel.providers.copilot.CopilotHeaders
import dev.kestrel.providers.copilot.CopilotModels
import dev.kestrel.providers.copilot.CopilotTargets
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The live tests of sub-project 4 (design D20), in debug builds only. `provider-live` starts one case for one Copilot
 * model, taken by name, of the default profile, which must be a Copilot profile: Copilot is the only provider of the live
 * tests (spec D9). It answers at once, because a hook ends after 20 s, and `provider-live -Result` answers `running` until
 * the case has ended. `provider-standin` starts or ends a Copilot stand-in on the device; while it runs, `provider-live`
 * sends its cases there, with targets that it builds itself, and makes no request off the device. The answers hold only
 * outcomes, the style, token counts, credits, whether the price was known, and times measured on the device; never a
 * prompt, a reply, a code, a token or a key.
 */
internal class ProviderHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "PROVIDER_LIVE" -> if (intent.getBooleanExtra(EXTRA_RESULT, false)) result() else live(intent)
        "PROVIDER_STANDIN" -> standIn(intent)
        else -> throw HookError("unknown command '$command'")
    }

    /** Starts or ends the stand-in with `--ez on`, and answers whether it runs and what the default profile is. */
    private suspend fun standIn(intent: Intent): String {
        if (intent.hasExtra(EXTRA_ON)) switchStandIn(intent.getBooleanExtra(EXTRA_ON, false))
        val profile = stored().defaultProfile
        val kind = when {
            profile == null -> "none"
            profile.providerId == CatalogLoader.COPILOT -> "copilot"
            else -> "other"
        }
        return "standin=${if (standIn != null) "on" else "off"} profile=$kind"
    }

    private fun switchStandIn(on: Boolean) {
        synchronized(lock) {
            standIn?.close()
            standInLease?.close()
            standIn = null
            standInLease = null
            if (on) {
                val server = ProviderStandIn().also { it.start() }
                // MockWebServer names its host as `localhost`, and NetworkPolicy compares host names as strings.
                standInLease = graph.networkPolicy.openTemporaryLocalServers(setOf(server.host))
                standIn = server
            }
        }
    }

    private fun live(intent: Intent): String {
        val case = intent.getStringExtra(EXTRA_CASE) ?: throw HookError("add --es case with one of ${CASES.joinToString()}")
        if (case !in CASES) throw HookError("add --es case with one of ${CASES.joinToString()}")
        val model = intent.getStringExtra(EXTRA_MODEL)
        if (model == null && case != "allowance") throw HookError("add --es model <model name>")
        val headers = intent.getStringExtra(EXTRA_HEADERS)?.let(::headers).orEmpty()
        val messagesPath = intent.getStringExtra(EXTRA_MESSAGES_PATH)
        synchronized(lock) {
            if (running) throw HookError("a case runs; ask for its answer with --ez result true")
            running = true
            outcome = null
        }
        graph.scope.launch {
            val answer = try {
                runCase(case, model, headers, messagesPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                "case=$case outcome=failed error=${e.error.toString().replace(" ", "")}"
            } catch (e: HookError) {
                "case=$case outcome=failed error=${e.message.replace(' ', '-')}"
            } catch (e: Exception) {
                // Only the class, never a message (spec 13.5).
                "case=$case outcome=failed error=${e.javaClass.simpleName}"
            }
            synchronized(lock) {
                outcome = answer
                running = false
            }
        }
        return "started"
    }

    private fun result(): String = synchronized(lock) {
        when {
            running -> "running"
            outcome != null -> outcome!!.also { outcome = null }
            else -> throw HookError("no case; start one with --es case and --es model")
        }
    }

    private suspend fun runCase(case: String, name: String?, headers: Map<String, String>, messagesPath: String?): String {
        val server = standIn
        val catalog = graph.catalog
        if (case == "allowance") {
            if (server != null) return "case=allowance outcome=skipped standin=on"
            val profile = copilotProfile()
            val token = profile.credentialId?.let { graph.providers.credentials.read(it) } as? Credential.GitHubToken
                ?: throw HookError("the Copilot profile has no sign-in")
            val start = SystemClock.elapsedRealtime()
            val quotas = CopilotAllowance(graph.providerHttp, catalog.overrides.copilot.ideHeaders).check(token)
            val numbers = quotas.joinToString(" ") { "${it.label}=${String.format(Locale.ROOT, "%.0f", it.percentRemaining)}" }
            return "case=allowance outcome=ok quotas=${quotas.size} $numbers millis=${SystemClock.elapsedRealtime() - start}".trim()
        }
        val profile = if (server != null) standInProfile() else copilotProfile()
        val rows = if (server != null) standInModels() else graph.providers.models(profile.id).models
        val wanted = CatalogLoader.normalise(name!!)
        val listed = rows.firstOrNull { CatalogLoader.normalise(it.id) == wanted || CatalogLoader.normalise(it.name) == wanted }
            ?: throw HookError("the subscription does not offer this model")
        val chosen = profile.copy(lightweight = ProfileModel(listed.id, jsonActions = case == "json"))
        var target = if (server != null) standInTarget(server, chosen, listed) else graph.providers.resolver.resolve(chosen, ModelRole.LIGHTWEIGHT)
        if (headers.isNotEmpty()) {
            val inner = target.headers
            target = target.copy(headers = RequestHeaders { inner.headers(it) + headers })
        }
        if (messagesPath != null) target = target.copy(options = target.options.copy(messagesPath = messagesPath))
        if (case == "json") target = target.copy(model = target.model.copy(toolMode = ToolMode.JSON))
        val fixed = target
        // The stand-in's cases cost nothing; the owner's real cases count in the profile's spending.
        val spend: suspend (String, String, Double, Long) -> Unit = if (server != null) { _, _, _, _ -> } else graph.providers::addSpending
        val client = ProfileClient(
            chosen, catalog, MutableStateFlow(ProvidersSetting(listOf(chosen))), { _, _ -> fixed }, graph.providers::adapter,
            graph.providers.localNetwork, spend, NoLimits,
        )
        val start = SystemClock.elapsedRealtime()
        val (priced, matched) = when (case) {
            "text" -> ask(client, listOf(user("Reply with the word OK and nothing else."))).let { it to text(it).contains("OK", ignoreCase = true) }
            "image" -> ask(client, listOf(ChatMessage.User(listOf(Part.Text("Which word is written in the picture? Reply with the word only."), Part.Image(ImageType.PNG, picture())))))
                .let { it to text(it).contains(PICTURE_WORD, ignoreCase = true) }
            else -> toolCase(client, case == "json")
        }
        val millis = SystemClock.elapsedRealtime() - start
        val usage = priced.response.usage
        return "case=$case outcome=ok matched=$matched style=${priced.style} input=${usage.totalInput} output=${usage.output} " +
            "estimated=${usage.estimated} credits=${String.format(Locale.ROOT, "%.3f", priced.cost.credits)} priceKnown=${priced.cost.priceKnown} " +
            "millis=$millis${if (server != null) " standin=on" else ""}"
    }

    /** A call of the test tool `get_number`, its result sent back, and a final answer that must hold the number. */
    private suspend fun toolCase(client: ProfileClient, json: Boolean): Pair<PricedResponse, Boolean> {
        val ask = user("Call the tool get_number, then tell me the number that it returns.")
        val first = ask(client, listOf(ask), listOf(GET_NUMBER), ToolChoice.REQUIRED)
        val call = first.response.message.toolCalls.firstOrNull() ?: return first to false
        if (call.name != GET_NUMBER.name) return first to false
        // A JSON action is the case's whole check (design D7): its tool and arguments came back as a tool call.
        if (json) return first to true
        val history = listOf(ask, first.response.message, ChatMessage.ToolResult(call.id, call.name, ProviderStandIn.NUMBER.toString()))
        val last = ask(client, history, listOf(GET_NUMBER), ToolChoice.AUTO)
        return last to text(last).contains(ProviderStandIn.NUMBER.toString())
    }

    private suspend fun ask(client: ProfileClient, messages: List<ChatMessage>, tools: List<ToolSpec> = emptyList(), choice: ToolChoice = ToolChoice.AUTO) =
        client.complete(
            ModelRole.LIGHTWEIGHT,
            // Reasoning tokens count toward the limit on Responses, so a reasoning model needs room to answer (design D20).
            ChatRequest("You are a test of Kestrel's connection.", messages, tools, choice, ReasoningLevel.LOW, maxOutputTokens = 1_024),
        )

    private fun user(text: String) = ChatMessage.User(listOf(Part.Text(text)))

    private fun text(priced: PricedResponse): String =
        priced.response.message.parts.filterIsInstance<AssistantPart.Text>().joinToString(" ") { it.text }

    /**
     * The stored provider setting. A new process, as after `am crash`, reads it from the store first, so the hook waits for
     * it, up to 5 s.
     */
    private suspend fun stored(): ProvidersSetting =
        withTimeoutOrNull(SETTING_WAIT_MILLIS) { graph.providers.stored.filterNotNull().first() }
            ?: throw HookError("the provider setting did not load")

    /** The default profile, which must be a Copilot profile (spec D9). */
    private suspend fun copilotProfile(): Profile {
        val profile = stored().defaultProfile ?: throw HookError("there is no default profile")
        if (profile.providerId != CatalogLoader.COPILOT) throw HookError("the default profile is not a Copilot profile")
        return profile
    }

    private fun standInProfile() = Profile(
        "test-standin", "Stand-in", CatalogLoader.COPILOT, "github-copilot", dev.kestrel.providers.catalog.AuthMethod.DEVICE_CODE, null,
        lightweight = ProfileModel("gpt-6-luna"), versatile = ProfileModel("gpt-6-luna"),
    )

    private fun standInModels(): List<DiscoveredModel> =
        (Json.parseToJsonElement(ProviderStandIn.MODELS) as JsonObject)["data"].let { it as JsonArray }
            .mapNotNull { (it as? JsonObject)?.let(CopilotModels::model) }

    /** A target at the stand-in, built as CopilotTargets builds one, over the real ProviderHttp (design D20). */
    private fun standInTarget(server: ProviderStandIn, profile: Profile, listed: DiscoveredModel): ModelTarget {
        val entry = ModelEntries.withOwnerChoices(ModelEntries.entryFor(graph.catalog.provider(CatalogLoader.COPILOT), listed.id, listOf(listed)), profile.lightweight)
        val style = listed.style ?: CopilotModels.styleFor(listed.id, null, emptyList())
        return ModelTarget(
            providerId = CatalogLoader.COPILOT,
            style = style,
            baseUrl = server.base.toHttpUrl(),
            model = entry,
            auth = ApiKeyAuth("test-standin", KeyHeader.BEARER),
            headers = CopilotHeaders.forRequests(graph.catalog.overrides.copilot.runtimeHeaders),
            options = CopilotTargets.options(style, entry.compat),
        )
    }

    private fun headers(text: String): Map<String, String> =
        text.split(';').filter { it.isNotBlank() }.associate { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty() || '=' !in pair) throw HookError("give --es headers as 'name=value;name=value'")
            name to pair.substringAfter('=').trim()
        }

    private object NoLimits : PlanLimits {
        override fun reached(profileId: String) = Unit

        override fun cleared(profileId: String) = Unit
    }

    companion object {
        val COMMANDS = setOf("PROVIDER_LIVE", "PROVIDER_STANDIN")
        val CASES = listOf("text", "tool", "image", "json", "allowance")

        /** The word of the test picture, which the image case's model must read. */
        const val PICTURE_WORD = "ORANGE"

        private val GET_NUMBER = ToolSpec(
            "get_number",
            "Returns the number to use.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        )

        /**
         * The test picture: [PICTURE_WORD] in black on white, drawn at run time, so that no binary file is needed in the
         * repository.
         */
        fun picture(): ByteArray {
            val bitmap = createBitmap(480, 160, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 96f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(PICTURE_WORD, 240f, 115f, paint)
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }

        private val lock = Any()
        private var running = false
        private var outcome: String? = null
        private var standIn: ProviderStandIn? = null
        private var standInLease: AutoCloseable? = null
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_ON = "on"
        private const val EXTRA_CASE = "case"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_MESSAGES_PATH = "messagesPath"
        private const val SETTING_WAIT_MILLIS = 5_000L
    }
}
