package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.http.Hosts
import okhttp3.HttpUrl

/**
 * The Chat Completions compat flags that a model's `compat` block leaves open, derived from the provider and its base
 * URL as OpenClaw's `detectOpenAICompletionsCompat` derives them (design D5, research r39): ported from
 * `packages/ai/src/transports/openai-completions-compat.ts` at the pinned commit, with the endpoint classes of the
 * manifests' `providerEndpoints` and the families and streaming-usage flags of their `providerRequest` blocks. Kestrel
 * always has a base URL, so OpenClaw's rules for a provider without one are left out; a local host is one that
 * [Hosts.isLocal] accepts.
 */
internal object CompatDefaults {
    /** The model's flags (or the target's replacement, Copilot's) with every open flag filled in. */
    fun resolve(target: ModelTarget): CompatFlags {
        val own = target.options.compat ?: target.model.compat
        val d = defaults(target.providerId, target.baseUrl, target.sentModelId, target.model.reasoning)
        return own.copy(
            maxTokensField = own.maxTokensField ?: d.maxTokensField,
            supportsTools = own.supportsTools ?: true,
            supportsStrictMode = own.supportsStrictMode ?: d.supportsStrictMode,
            supportsStore = own.supportsStore ?: d.supportsStore,
            supportsDeveloperRole = own.supportsDeveloperRole ?: d.supportsDeveloperRole,
            supportsReasoningEffort = own.supportsReasoningEffort ?: d.supportsReasoningEffort,
            thinkingFormat = own.thinkingFormat ?: d.thinkingFormat,
            requiresReasoningContentOnAssistantMessages = own.requiresReasoningContentOnAssistantMessages ?: d.requiresReasoningContentOnAssistantMessages,
            requiresStringContent = own.requiresStringContent ?: false,
            requiresToolResultName = own.requiresToolResultName ?: false,
            requiresAssistantAfterToolResult = own.requiresAssistantAfterToolResult ?: false,
            requiresThinkingAsText = own.requiresThinkingAsText ?: false,
            supportsUsageInStreaming = own.supportsUsageInStreaming ?: d.supportsUsageInStreaming,
            supportsJsonSchemaResponseFormat = own.supportsJsonSchemaResponseFormat ?: d.supportsJsonSchemaResponseFormat,
            cacheControlFormat = own.cacheControlFormat ?: d.cacheControlFormat,
        )
    }

    /** OpenClaw's derived defaults for a provider at [baseUrl]. */
    fun defaults(providerId: String, baseUrl: HttpUrl, modelId: String, reasoning: Boolean): CompatFlags {
        val endpoint = endpointClass(baseUrl)
        val family = FAMILIES[providerId] ?: providerId
        val url = baseUrl.toString().lowercase()
        val proxyLike = endpoint !in setOf("openai-public", "openai", "azure-openai")
        val configuredNonOpenAI = endpoint != "openai-public"
        val moonshot = family == "moonshot" || endpoint == "moonshot-native"
        val modelStudio = family == "modelstudio" || endpoint == "modelstudio-native"
        val zai = endpoint == "zai-native"
        val deepSeek = endpoint == "deepseek-native"
        val together = family == "together" || "api.together.ai" in url || "api.together.xyz" in url
        val xiaomi = endpoint == "xiaomi-native"
        val mistral = family == "mistral" || endpoint == "mistral-public"
        val openRouter = providerId == "openrouter" || endpoint == "openrouter"
        val nonStandard = endpoint in NON_STANDARD || xiaomi || zai
        val compatibleMode = modelStudio && Regex("(?:^|\\.)(?:dashscope(?:-[^.]+)?|maas)\\.aliyuncs\\.com$").containsMatchIn(baseUrl.host) &&
            baseUrl.encodedPath.lowercase().let { it == "/compatible-mode" || it.startsWith("/compatible-mode/") }
        val maxTokens = endpoint == "chutes-native" || endpoint == "novita-native" || mistral || moonshot || zai || together || compatibleMode
        val routeModel = modelId.lowercase()
        return CompatFlags(
            maxTokensField = if (maxTokens) "max_tokens" else "max_completion_tokens",
            supportsStrictMode = !zai && (!configuredNonOpenAI || endpoint == "openrouter"),
            supportsStore = !nonStandard && !mistral && !proxyLike,
            supportsDeveloperRole = !nonStandard && !(moonshot || modelStudio) && !configuredNonOpenAI,
            supportsReasoningEffort = !zai && !together && !mistral && endpoint != "xai-native" && (!proxyLike || reasoning),
            thinkingFormat = when {
                deepSeek || xiaomi -> "deepseek"
                zai -> "zai"
                together -> "together"
                openRouter -> "openrouter"
                else -> "openai"
            },
            requiresReasoningContentOnAssistantMessages = deepSeek || xiaomi,
            supportsUsageInStreaming = providerId in STREAMING_USAGE ||
                (!nonStandard && (endpoint == "local" || !configuredNonOpenAI || endpoint == "moonshot-native" || endpoint == "modelstudio-native")),
            supportsJsonSchemaResponseFormat = endpoint == "openai-public" && knownJsonSchemaModel(modelId),
            cacheControlFormat = when {
                openRouter && (routeModel.startsWith("google/gemini") || routeModel.startsWith("anthropic/")) -> "anthropic"
                modelStudio && endpoint != "custom" -> "anthropic"
                providerId == "deepinfra" && routeModel.startsWith("anthropic/") -> "anthropic"
                else -> null
            },
        )
    }

    /** The endpoint class of a base URL, from the manifests' `providerEndpoints` at the pinned commit. */
    fun endpointClass(baseUrl: HttpUrl): String {
        val host = baseUrl.host.lowercase()
        val base = baseUrl.toString().lowercase().trimEnd('/')
        HOSTS[host]?.let { return it }
        BASE_URLS[base]?.let { return it }
        return when {
            host.endsWith(".githubcopilot.com") -> "github-copilot-native"
            host.endsWith(".api.openai.com") -> "openai-public"
            host.endsWith(".openai.azure.com") -> "azure-openai"
            host == "openrouter.ai" || host.endsWith(".openrouter.ai") -> "openrouter"
            host.endsWith("-aiplatform.googleapis.com") -> "google-vertex"
            Hosts.isLocal(host) -> "local"
            else -> "custom"
        }
    }

    /** OpenClaw's `isKnownOpenAIJsonSchemaModelId`. */
    fun knownJsonSchemaModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        if (Regex("^gpt-5(?:[.-]|$)").containsMatchIn(id) || Regex("^gpt-4\\.1(?:-|$)").containsMatchIn(id)) return true
        Regex("^gpt-4o(-mini)?(?:-(\\d{4}-\\d{2}-\\d{2}))?$").matchEntire(id)?.let { match ->
            val snapshot = match.groupValues[2]
            return snapshot.isEmpty() || snapshot >= (if (match.groupValues[1].isNotEmpty()) "2024-07-18" else "2024-08-06")
        }
        return Regex("^(?:o1|o3(?:-mini|-pro)?|o4-mini)(?:-\\d{4}-\\d{2}-\\d{2})?$").matches(id)
    }

    private val NON_STANDARD = setOf(
        "cerebras-native", "chutes-native", "deepseek-native", "mistral-public", "opencode-native", "opencode-go-native", "xai-native",
    )

    private val HOSTS = mapOf(
        "api.anthropic.com" to "anthropic-public",
        "inference.celeris.ai" to "celeris-native",
        "api.cerebras.ai" to "cerebras-native",
        "llm.chutes.ai" to "chutes-native",
        "api.deepinfra.com" to "deepinfra-native",
        "api.deepseek.com" to "deepseek-native",
        "api.gmi-serving.com" to "gmi-native",
        "generativelanguage.googleapis.com" to "google-generative-ai",
        "aiplatform.googleapis.com" to "google-vertex",
        "aiplatform.eu.rep.googleapis.com" to "google-vertex",
        "aiplatform.us.rep.googleapis.com" to "google-vertex",
        "api.groq.com" to "groq-native",
        "api.liquid.ai" to "liquid-ai-native",
        "api.meta.ai" to "meta-native",
        "api.minimax.io" to "minimax-native",
        "api.minimaxi.com" to "minimax-native",
        "api.mistral.ai" to "mistral-public",
        "console.nace.ai" to "nace-native",
        "api.novita.ai" to "novita-native",
        "integrate.api.nvidia.com" to "nvidia-native",
        "api.openai.com" to "openai-public",
        "chatgpt.com" to "openai",
        "api.parallel.ai" to "parallel-native",
        "api.particle.ai" to "particle-native",
        "ai-gateway.vercel.sh" to "vercel-ai-gateway",
        "api.x.ai" to "xai-native",
        "api.xiaomimimo.com" to "xiaomi-native",
        "token-plan-ams.xiaomimimo.com" to "xiaomi-native",
        "token-plan-cn.xiaomimimo.com" to "xiaomi-native",
        "token-plan-sgp.xiaomimimo.com" to "xiaomi-native",
        "api.z.ai" to "zai-native",
        "open.bigmodel.cn" to "zai-native",
    )

    private val BASE_URLS = mapOf(
        "https://api.moonshot.ai/v1" to "moonshot-native",
        "https://api.moonshot.cn/v1" to "moonshot-native",
        "https://opencode.ai/zen/go" to "opencode-go-native",
        "https://opencode.ai/zen/go/v1" to "opencode-go-native",
        "https://opencode.ai/inference/go/openai/v1" to "opencode-go-native",
        "https://opencode.ai/inference/go/anthropic" to "opencode-go-native",
        "https://opencode.ai/inference/go/anthropic/v1" to "opencode-go-native",
        "https://opencode.ai/inference/go/google/v1beta" to "opencode-go-native",
        "https://opencode.ai/zen" to "opencode-native",
        "https://opencode.ai/zen/v1" to "opencode-native",
        "https://opencode.ai/inference/openai/v1" to "opencode-native",
        "https://opencode.ai/inference/anthropic/v1" to "opencode-native",
        "https://opencode.ai/inference/google/v1beta" to "opencode-native",
        "https://coding-intl.dashscope.aliyuncs.com/v1" to "modelstudio-native",
        "https://coding.dashscope.aliyuncs.com/v1" to "modelstudio-native",
        "https://dashscope.aliyuncs.com/compatible-mode/v1" to "modelstudio-native",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1" to "modelstudio-native",
        "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1" to "modelstudio-native",
        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1" to "modelstudio-native",
        "https://cli-chat-proxy.grok.com/v1" to "custom",
    )

    /** The `providerRequest` families of the manifests that differ from the provider's ID. */
    private val FAMILIES = mapOf(
        "openai" to "openai-family",
        "gmi-cloud" to "gmi",
        "gmicloud" to "gmi",
        "kimi" to "moonshot",
        "kimi-coding" to "moonshot",
        "novita-ai" to "novita",
        "novitaai" to "novita",
        "qwen" to "modelstudio",
        "qwencloud" to "modelstudio",
        "modelstudio" to "modelstudio",
        "dashscope" to "modelstudio",
        "qwen-token-plan" to "modelstudio",
        "bailian-token-plan" to "modelstudio",
        "opencode-go" to "opencode",
    )

    /** The providers whose manifests say `openAICompletions.supportsStreamingUsage: true`. */
    private val STREAMING_USAGE = setOf(
        "baseten", "byteplus", "byteplus-plan", "llama-cpp", "lmstudio", "omlx", "sglang", "vllm", "volcengine", "volcengine-plan",
    )
}
