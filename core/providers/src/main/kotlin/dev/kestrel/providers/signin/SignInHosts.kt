package dev.kestrel.providers.signin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The ChatGPT plan's fixed addresses and values (design D9; research r46; OpenAI's "Registration and sign-in"). */
object ChatGptPlanAuth {
    const val ISSUER = "https://auth.openai.com"
    const val HOST = "auth.openai.com"
    const val DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"
    const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val RESOURCE = "https://api.openai.com/v1"
    const val API_HOST = "api.openai.com"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val AGENT_NAME = "Kestrel"
    const val CALLBACK_PATH = "/auth/callback"
    const val PREFERRED_PORT = 1455
}

/** xAI's fixed client and scope, the Grok CLI's (design D9 and D21; research r31). */
object XaiAuth {
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
    const val DOMAIN = "x.ai"
}

/** MiniMax's fixed client, probably OpenClaw's registration (design D9 and D21; research r31 and r44). */
object MiniMaxAuth {
    const val CLIENT_ID = "78257093-7e40-4613-99e0-527b14b39113"
    const val SCOPE = "group_id profile model.completion"

    /** Whether a MiniMax answer is a failure: `status: "error"` or a `base_resp.status_code` other than 0. */
    fun failed(body: JsonObject): Boolean {
        if (body.string("status") == "error") return true
        val code = ((body["base_resp"] as? JsonObject)?.get("status_code") as? JsonPrimitive)?.longOrNull
        return code != null && code != 0L
    }
}

/** MiniMax's two services: the account host of the sign-in and the API base of its Anthropic route (design D9). */
enum class MiniMaxRegion(val accountHost: String, val apiBase: String) {
    GLOBAL("account.minimax.io", "https://api.minimax.io/anthropic"),
    CHINA("account.minimaxi.com", "https://api.minimaxi.com/anthropic"),
    ;

    val tokenUrl: String get() = "https://$accountHost/oauth2/token"
}
