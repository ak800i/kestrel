package dev.kestrel.providers.styles

/**
 * Ported from OpenClaw's rules for Claude models (`packages/llm-core/src/model-contracts/anthropic.ts` at the pinned
 * commit), which the Anthropic and Bedrock adapters need (design D5, research r33 and r38): which models take adaptive
 * thinking, which think by default, and which refuse a temperature. A model whose ID names no Claude model (MiniMax's,
 * Synthetic's) gets none of them.
 */
internal object AnthropicModels {
    private fun releases(vararg patterns: String) = Regex("(?:^|-)(?:${patterns.joinToString("|")})(?=$|[^a-z0-9])")

    private const val PRE_CLAUDE_5 = "claude-(?:3|(?:opus|sonnet|haiku)-4)"
    private val WITHOUT_ADAPTIVE = releases("claude-(?:3|(?:opus|sonnet|haiku)-4(?:-[0-5](?!\\d))?(?!-\\d(?!\\d)))")
    private val BEFORE_CLAUDE_5 = releases(PRE_CLAUDE_5)
    private val SAMPLING = releases("claude-(?:3|haiku-4|sonnet-4|opus-4(?!-[78](?!\\d)))")
    private val HAIKU_55 = Regex("(?:^|-)claude-haiku-5-5(?=$|[^a-z0-9])")

    /**
     * The Claude model that an ID names, or null: lower case, without an `anthropic/` prefix, with `.`, `_` and spaces as
     * `-`, and only the last component that starts with `claude-` (so `anthropic.claude-opus-5` on Mantle is
     * `claude-opus-5`).
     */
    fun identity(modelId: String): String? {
        val lower = modelId.trim().lowercase().removePrefix("anthropic/")
        val normalised = lower.replace(Regex("[._\\s]+"), "-")
        val match = Regex("(?:^|[-/])(claude-[^/]+)$").find(normalised)?.groupValues?.get(1) ?: normalised
        return match.takeIf { it.startsWith("claude-") }
    }

    /** Whether the model takes `thinking: {type: "adaptive"}`: every Claude model from Opus and Sonnet 4.6 on. */
    fun adaptive(modelId: String): Boolean = identity(modelId)?.let { !WITHOUT_ADAPTIVE.containsMatchIn(it) } ?: false

    /**
     * Whether the model thinks when the request does not ask (Anthropic's "Configuring thinking" table). Kestrel then sends a
     * required tool choice as `auto`, which every thinking Claude model accepts (Anthropic refuses a forced choice with
     * manual thinking, and on Opus 5.5, Sonnet 5.5 and the Fable and Mythos models from 5.1 on), and no temperature, which
     * every Claude 5 model refuses. This is OpenClaw's `defaultsClaudeAdaptiveThinking` (`packages/ai/src/providers/anthropic-model-contract.ts`
     * at e6113549): its mandatory-thinking rule or its Claude 5 request contract. Together they hold for every Claude model
     * after Claude 4.x: Sonnet 5, Opus 5, Fable 5, Mythos 5, the Mythos preview, and the later ones (OpenClaw's lists only
     * ever shrink, so a new model gets the strict rule). Bedrock's adapter uses the same rule.
     */
    fun thinksByDefault(modelId: String): Boolean = identity(modelId)?.let { !BEFORE_CLAUDE_5.containsMatchIn(it) } ?: false

    /** OpenClaw keeps a forced tool choice for Claude Haiku 5.5, which Anthropic accepts while the model thinks (`anthropic-messages.ts`). */
    fun keepsForcedToolChoice(modelId: String): Boolean = identity(modelId)?.let(HAIKU_55::containsMatchIn) ?: false

    /** Whether the model refuses a caller's temperature (Claude Opus 4.7 and later). */
    fun defaultSamplingOnly(modelId: String): Boolean = identity(modelId)?.let { !SAMPLING.containsMatchIn(it) } ?: false

    /** Anthropic's effort for an OpenClaw level: the model's map first, then OpenClaw's default (`minimal` is `low`). */
    fun effort(level: String, thinkingLevelMap: Map<String, String?>): String =
        thinkingLevelMap[level] ?: when (level) {
            "off", "minimal", "low" -> "low"
            "medium" -> "medium"
            else -> "high"
        }
}
