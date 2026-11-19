package dev.kestrel.app.debug

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * A Copilot stand-in on the device for the session's dry run (design D20): a MockWebServer on the loopback address that
 * answers Copilot's three routes and its model list with copies of plan 4C's Copilot fixtures, held in code because the
 * APK has no JVM test resources. It calls only `start`, its dispatcher, `url` and `close`, so that it names no type that
 * the audit flags. Each answer fits the live cases of the hook provider-live: a short text, a call of `get_number` and
 * then a final answer with the number, the word of the test picture, and a JSON action. It reads a request's body only to
 * choose the answer, and never keeps or logs it.
 */
internal class ProviderStandIn : AutoCloseable {
    private val server = MockWebServer()

    /** The stand-in's base URL, such as `http://localhost:41234/`. */
    val base: String get() = server.url("/").toString()

    /** The host of [base], which the hook leases with NetworkPolicy.openTemporaryLocalServers. */
    val host: String get() = server.url("/").host

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                return when (request.url.encodedPath) {
                    "/models" -> json(MODELS)
                    "/responses" -> events(responses(answer(body)))
                    "/chat/completions" -> events(chatCompletions(answer(body)))
                    "/messages" -> events(anthropic(answer(body)))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    override fun close() {
        server.close()
    }

    /** What the stand-in answers: the number after a tool result, a call where tools are sent, else by the request's text. */
    private fun answer(body: String): Answer = when {
        "function_call_output" in body || "\"tool_result\"" in body || "\"role\":\"tool\"" in body -> Answer.Text("The number is $NUMBER.")
        "\"tools\"" in body && "get_number" in body -> Answer.Call
        "get_number" in body -> Answer.Text("""{"tool": "get_number", "arguments": {}}""")
        "image" in body -> Answer.Text(ProviderHooks.PICTURE_WORD)
        else -> Answer.Text("OK")
    }

    private sealed interface Answer {
        data class Text(val text: String) : Answer

        data object Call : Answer
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun events(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build()

    private fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun responses(answer: Answer): String {
        val item = when (answer) {
            is Answer.Text -> """{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":${quoted(answer.text)},"annotations":[]}]}"""
            Answer.Call -> """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_number","arguments":"{}","status":"completed"}"""
        }
        val response = """{"id":"resp_1","object":"response","status":"completed","model":"stand-in","output":[$item],""" +
            """"usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":0},"output_tokens":8,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":128}}"""
        return "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$item}\n\n" +
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$response}\n\n"
    }

    private fun chatCompletions(answer: Answer): String {
        val delta = when (answer) {
            is Answer.Text -> """{"role":"assistant","content":${quoted(answer.text)}}"""
            Answer.Call -> """{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_number","arguments":"{}"}}]}"""
        }
        val finish = if (answer == Answer.Call) "tool_calls" else "stop"
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"stand-in\",\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"stand-in\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\n" +
            "data: [DONE]\n\n"
    }

    private fun anthropic(answer: Answer): String {
        val start = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\"," +
            "\"model\":\"stand-in\",\"content\":[],\"stop_reason\":null,\"usage\":{\"input_tokens\":120,\"output_tokens\":1}}}\n\n"
        val (block, delta, stop) = when (answer) {
            is Answer.Text -> Triple("""{"type":"text","text":""}""", """{"type":"text_delta","text":${quoted(answer.text)}}""", "end_turn")
            Answer.Call -> Triple(
                """{"type":"tool_use","id":"toolu_1","name":"get_number","input":{}}""",
                """{"type":"input_json_delta","partial_json":"{}"}""",
                "tool_use",
            )
        }
        return start +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":$block}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":$delta}\n\n" +
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$stop\"},\"usage\":{\"output_tokens\":8}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
    }

    companion object {
        /** The number that `get_number` returns and the final answer must contain. */
        const val NUMBER = 4721

        /** A copy of plan 4C's fixture `copilot/models.json`, with the models of the live cases. */
        const val MODELS = """{"object":"list","data":[
{"id":"gpt-6-luna","name":"GPT-6 Luna","object":"model","vendor":"OpenAI","model_picker_enabled":true,"supported_endpoints":["/responses","/chat/completions"],"capabilities":{"type":"chat","supports":{"streaming":true,"tool_calls":true,"vision":true,"reasoning_effort":["low","medium","high"]}}},
{"id":"gpt-6.1-terra","name":"GPT-6.1 Terra","object":"model","vendor":"OpenAI","model_picker_enabled":true,"supported_endpoints":["/responses"],"capabilities":{"type":"chat","supports":{"streaming":true,"tool_calls":true,"vision":true}}},
{"id":"claude-haiku-5.5","name":"Claude Haiku 5.5","object":"model","vendor":"Anthropic","model_picker_enabled":true,"supported_endpoints":["/v1/messages"],"capabilities":{"type":"chat","supports":{"streaming":true,"tool_calls":true,"vision":true}}},
{"id":"gemini-3.8-flash","name":"Gemini 3.8 Flash","object":"model","vendor":"Google","model_picker_enabled":true,"supported_endpoints":["/chat/completions"],"capabilities":{"type":"chat","supports":{"streaming":true,"tool_calls":true,"vision":true}}}
]}"""
    }
}
