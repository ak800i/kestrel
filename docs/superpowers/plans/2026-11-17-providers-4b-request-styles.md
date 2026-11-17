# Providers 4B: the Request Styles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Kestrel's internal request format its eight request styles (Chat Completions, Responses, Anthropic Messages, Gemini, Ollama, Bedrock Converse, Vertex, and Azure OpenAI with Microsoft Foundry), each of which sends a request with tools, images, reasoning state and limits, and reads one complete reply with its usage and errors; JSON actions for models that handle tool calls badly; and model discovery for the local servers and the API-key providers. All of it is plain Kotlin in `:core:providers`, tested against MockWebServer fixtures.

**Architecture:** Each style is an internal `ChatAdapter` in `dev.kestrel.providers.styles` that turns a `ChatRequest` and a `ModelTarget` into one HTTP request through plan 4A's `ProviderHttp`, and gathers the provider's server-sent events (or one body, for Bedrock, Ollama and Claude on Vertex) into one `ChatResponse`. A shared `Wire` object and a `ReplyBuilder` hold what every style needs: the request's `accept` and target headers, the replay of tool history as text, the reasoning level of design D5, the usage estimate of design D6, the check of each tool call, and the mapping of an error inside a stream. `VERTEX` and `AZURE` are platforms whose adapters choose a wire format per model and reuse the Gemini, Anthropic, Responses and Chat Completions adapters. `Adapters.forStyle` is the one entry point for plan 4C; `JsonActions` wraps any adapter; `Discoveries` builds a profile's model list from its provider's entry or its local server's kind.

**Tech Stack:** Kotlin 2.4.21, kotlinx.serialization 1.13.0 (`JsonObject` builders), kotlinx.coroutines 1.11.0, OkHttp 5.5.1 (`HttpUrl` only; requests go through plan 4A's `ProviderHttp` and the gateway's `Call.Factory`), `java.security` and `javax.crypto` for SigV4 and the service account's RS256 signature, JUnit 4 with `mockwebserver3` and plan 4A's `RedirectingCalls`, and AWS's SigV4 test-suite vectors.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. No new dependency that ships in the release app: `:core:providers` uses OkHttp, kotlinx-serialization and coroutines, which the app already ships; the Custom Tab is built by hand without `androidx.browser`. `mockwebserver3` is added only as a `debugImplementation` of `:app` (plan 4C, for the stand-in) and `room-testing` only as an `androidTestImplementation` of `:core:privacy` (plan 4A); both must pass the audit's licence list. `:tools:catalog-sync` ships nothing.
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`. Every Android call that the A71 (Android 13, API 33) lacks, such as `ACCESS_LOCAL_NETWORK`, has a check of the API level; `ClipDescription.EXTRA_IS_SENSITIVE` exists from API 33 and needs none.
- No dependency-injection framework; `AppGraph` wires by hand.
- New permissions: `FOREGROUND_SERVICE_DATA_SYNC` (normal, at install) and `ACCESS_LOCAL_NETWORK` (runtime, asked when the owner checks a LAN server; loopback needs no permission, D11). Kestrel never requests a permission on spec 13.6's "never" list.
- All network traffic goes through `NetworkGateway`; no code outside `:core:privacy`'s `network` package builds an HTTP client, opens a socket, or names a type or calls a method that the audit flags, and `audit/rules.txt` gets no new exception. The one exception is D20's debug-only stand-in in `:app`'s debug source set: its MockWebServer listens on a loopback port, and the stand-in calls only `start`, its dispatcher, `url` and `close` (no method whose signature names a `javax.net` type), while `provider-standin` holds an `openTemporaryLocalServers` lease for the host of the stand-in's `url("/")` (D20). `LoopbackServer` (plan 4C) lives in `:core:privacy`'s `network` package.
- No request to any provider's API outside the session with the owner (D20): not in a plan's step, a test or a script. The catalog sync fetches only OpenClaw's Git repository, and the Chrome checks use postman-echo.com, which is not a provider.
- Never log, print or put into a test's output a prompt, a reply, a tool's arguments, screen text, a device code, a token, a key, an issued client ID, an account ID, a project ID or an e-mail address; `toString` of anything that carries them prints only kinds, counts or lengths (D22). Failures are logged by their classes and stack frames only. Device tests never touch the owner's profiles, credentials or spending (D15).
- Test only on `kestrel-api37` (check it with `adb -s <serial> emu avd name` in the same command as each device command); never on the S23+ or the AVD `zoran`; the A71 only in the session with the owner (D20), which is the one session of sub-project 4. Follow `.superpowers/sdd/progress.md`'s process rule 8: (a) before any device command, wait until `.superpowers/sdd/emulator.lock` does not exist (checking every 2 minutes, up to 60 minutes, then report BLOCKED), create it with the task's name on one line, and delete it as soon as the device steps are done, also when they fail; (b) run only one Gradle build at a time on this computer. Gradle only through `scripts\gradle.ps1`, stopped with `--stop` at the end of each task; device tests only through `scripts\connected-test.ps1` (with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) or the scripts that a plan names; installs only through `scripts\install.ps1`.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7, or declare `#Requires -Version 7.3` as `hook.ps1` does; `provider-check.ps1` and `a71-providers.ps1` call `hook.ps1`, so they declare it too. `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan files are named with the day they are written (`Get-Date -Format yyyy-MM-dd`), today `2026-11-17`: `docs/superpowers/plans/2026-11-17-providers-4a-core-catalog.md`, `2026-11-17-providers-4b-request-styles.md` and `2026-11-17-providers-4c-signin-copilot-app.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

D-numbers refer to the design `.superpowers/sdd/plan-4-design.md`. Code comments call it "design D…", as the code of sub-project 3 does.

## Before you start

- Plan 4A is complete; its last commit is in `.superpowers/sdd/progress.md`. This plan was written against the code as plan 4A leaves it. The "Changes from the plan" entries of plan 4A that touch this plan's files must be kept; this plan replaces only the texts that it quotes.
- The counts at the start of this plan are those at the end of plan 4A. The controller gives their values in each task's brief, because later fix commits change them: `:core:providers` JVM R₀ (84 in plan 4A's plan), `:tools:catalog-sync` JVM, `:app` JVM, `:core:privacy` JVM, `:core:commands` JVM, `:core:voice` JVM, `:core:screen` JVM, `:core:safety` JVM, the device tests and Pester. Only `:core:providers` changes in this plan: after Task 1 it has R₀ + 19 tests, after Task 2 R₀ + 33, after Task 3 R₀ + 44, after Task 4 R₀ + 51, after Task 5 R₀ + 61, after Task 6 R₀ + 76, after Task 7 R₀ + 87, after Task 8 R₀ + 94 and after Task 9 R₀ + 106. Every other count stays as plan 4A left it. Before each task, compare the counts with the ones that the earlier tasks left. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file. Only Task 9 replaces texts. If a text is missing, stop and ask the controller.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task. Only one Gradle build may run on this computer at a time (process rule 8b); if another agent's build runs, wait for it to end.
- No task of this plan uses a device or the emulator, so no task takes the emulator lock.
- No step of this plan sends a request to any provider's API. Every test reaches a local MockWebServer through `RedirectingCalls` (plan 4A, Task 2), which keeps each adapter's fixed `https://` address and records it.
- The fixtures under `core/providers/src/test/resources/fixtures/` are files of server-sent events (`.sse`) or JSON. Create each with exactly the lines of its block and LF line endings; the tests read them as text, so a missing blank line at the end does not matter.
- The expected test results say how many tests `:core:providers` runs. Gradle prints no count by itself; count them in the XML files under `core\providers\build\test-results\test\` (the `tests` attribute of each `testsuite`), for example with `(Get-ChildItem core\providers\build\test-results\test\*.xml | ForEach-Object { [int]([xml](Get-Content $_)).testsuite.tests } | Measure-Object -Sum).Sum`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 4A (done before this plan): the module, the internal request format, `ProviderHttp` and its classification of failures, the catalog and `overrides.json`, cost, profiles, credentials as data, and the allowlist in `AppGraph`.

Plan 4C (sign-in, Copilot, the settings app and the session): `CredentialManager` and its refreshers, the sign-in flows, `TargetResolver` (which builds each `ModelTarget` with its base URL, key header, `StyleOptions` and headers, as "Shared names" says), `ProfileClient` with its time limit and retries, `ProvidersRepository`, the Copilot package (discovery, model routing by `supported_endpoints`, headers, allowance), `ChatGptPlanModels` and `XaiModels`, the offered rows per choice, the settings pages, the debug hooks and the session with the owner. Plan 4C reaches this plan's adapters only through `Adapters.forStyle`, `JsonActions`, `Discoveries`, `SigV4Auth`, `ServiceAccountAuth`, `MantleRouting`, `VertexUrls` and `AzureRouting`.

Sub-project 5: the agent's use of providers, including the choice of a reasoning level, `RequestMeta.agentStep`, the answer to the extra tool calls of a reply ("Not run: Kestrel runs one action per step"), and the reaction to an `InvalidToolCall`; and, while a Claude model gets its signed thinking back, a history that only grows: the system prompt, the tools and every earlier message stay exactly as they were sent (Anthropic's prefix check).

Left out on purpose (design, "Not in sub-project 4"): Bedrock's ConverseStream, Claude's `streamRawPredict` on Vertex, Google's Interactions API, Anthropic's manual thinking budget, one-hour and twelve-hour cache lifetimes, OpenAI-compatible routing hints, DeepSeek's DSML text tool calls, Mantle tokens minted from IAM keys, and a per-project model list for Vertex.

## Changes to the design that this plan makes

- **Shared helpers in `Adapters.kt`.** The design's file list has no file for what every adapter shares, so Task 1 creates `styles/Adapters.kt` with `internal object Wire` (JSON helpers, the request, the reasoning level, the estimate, the stream's error mapping, the text replay) and `internal class ReplyBuilder` (one reply with its tool-call checks); Task 9 adds `object Adapters` to the same file. The test sources get one shared bench, `styles/AdapterHarness.kt`.
- **Additions to "Shared names"**, none of which changes a name there: `MantleRouting.modelsBase(region)` (Mantle's list address), `VertexUrls.logPath(url)` (the network log's path with `{project}`), `SigV4Auth.encode(value, keepSlash)` (AWS's URI encoding, also used for a model ID in Bedrock's path), `ServiceAccountAuth.TOKEN_URL` and `SCOPE`, `ServiceAccountAuth`'s fourth parameter `tokens: ServiceAccountTokens = ServiceAccountTokens.SHARED` (see "Vertex's token" below), `VertexUrls.validProject(projectId)`, `JsonActions.instruction(tools)` and `JsonActions.schema(names)`, and `GeminiAdapter.SKIP_SIGNATURE`. `SigV4Auth.rejected` is `SignInExpired("amazon-bedrock")` and `ServiceAccountAuth.rejected` is `SignInExpired("google-vertex")`, because a refused key of either kind needs new keys (design D9); plan 4C may wrap them.
- **A tool call that Kestrel cannot use is left out of the message.** `ChatResponse.invalidToolCall` (design D3) carries its kind and its raw arguments; the message keeps the valid calls only, so that the next request needs no result for a call that the agent never runs. A reply with a valid call stops with `TOOL_CALL` even when the provider said `stop`, as Gemini and Ollama do.
- **Local endpoints in `CompatDefaults`.** OpenClaw treats only `localhost`, `127.0.0.1`, `::1`, `*.localhost`, `*.local` and `*.internal` as local endpoints; Kestrel uses `Hosts.isLocal`, so a server on the owner's LAN (a private IPv4 address) also counts as local and is asked for usage in its stream, while `*.localhost` and `*.internal` names do not count. OpenClaw's rules for a provider without a base URL are left out, because every Kestrel target has one, and so is its Cloudflare AI Gateway rule (`max_tokens` for `cloudflare-ai-gateway` or a `gateway.ai.cloudflare.com` base), because `overrides.json` leaves that provider out.
- **OpenAI's reasoning with tools.** On OpenAI's and Azure's own endpoints, a Chat Completions request with tools sends `reasoning_effort: "none"` to GPT-5.6 and no `reasoning_effort` to GPT-5.4 mini and GPT-5.5, as OpenClaw's `applyOpenAICompletionsToolReasoning` does, because OpenAI refuses the other combinations.
- **Chat Completions' final event.** A stream ends correctly with `data: [DONE]` or with a chunk that has a `finish_reason`, because some OpenAI-compatible servers send no `[DONE]`; a stream with neither is `Malformed` (design D4).
- **Responses' system prompt.** Only the ChatGPT plan's requests send it as `instructions` (OpenAI's "Preview limitations"), and only when it is not empty; every other Responses request sends it as the first `input` item with the role `system`, because not every Responses provider (xAI, Meta, Copilot) documents `instructions`.
- **Responses' details.** `include: ["reasoning.encrypted_content"]` goes only to a reasoning model and to the ChatGPT plan, because OpenAI refuses it for other models ("Encrypted content is not supported with this model"). A closing `data: [DONE]` is ignored, as OpenAI's SDK ignores it, and an event without `type` takes the name of its SSE `event:` line.
- **The ChatGPT plan's 429.** The plan's adapter maps every `RateLimited` to the plan's limit (design D17). A 429 from `auth.openai.com`'s token endpoint, which a refresh inside `RequestAuth.headers` reports as `RateLimited`, therefore also looks like the plan's limit; the adapter cannot tell the two apart. Plan 4C's refresher can avoid this by reporting such a 429 as `ServerError(503)`.
- **Claude models that think by default.** `AnthropicModels.thinksByDefault` is OpenClaw's `defaultsClaudeAdaptiveThinking` at e6113549: every Claude model after Claude 4.x (Sonnet 5, Opus 5, Fable 5, Mythos 5, the Mythos preview and later). The Anthropic and the Bedrock adapters use it for such a model even when the request does not ask for thinking: a required tool choice goes as `auto`, as OpenClaw sends it (Anthropic accepts a forced choice from most of these models, but refuses it on Opus 5.5, Sonnet 5.5 and the Fable and Mythos models from 5.1 on), except for Claude Haiku 5.5, for which OpenClaw keeps it; no temperature is sent, because every Claude 5 model refuses one; and its signed reasoning is kept and replayed. On Copilot's route, which strips thinking (design D12), such a model also gets `auto` and no temperature, but its thinking blocks are still stripped, as OpenClaw's Copilot replay policy does for every Claude model (`extensions/github-copilot/replay-policy.ts`).
- **Blank text.** Anthropic, Gemini and Converse refuse blank text blocks, so their adapters leave out a blank system prompt and blank texts, and Bedrock sends `(no output)` for a blank tool result, as OpenClaw does.
- **Tool-call IDs.** `ReplyBuilder` gives a call whose ID is blank or repeats another call's ID of the same reply a fresh `call_<n>`. The IDs that Gemini and Ollama calls get when they come without one (`gemini-call-<n>`, `ollama-call-<n>`) are numbered after the highest such ID in the request, so that a task's history never repeats one. Copilot's Claude route gives two IDs that its rewrite makes equal `_2`, `_3` and so on, as OpenClaw does.
- **Bedrock** sends no reasoning settings (Converse needs model-specific `additionalModelRequestFields` for them, which design D5 does not give), and it sends a failed tool result's `status: "error"` only to Claude and Nova models, the only ones that AWS documents it for (research r34). Design D5 says that Bedrock keeps no reasoning state, but Claude on Bedrock can think without being asked, so a Claude model's `reasoningContent` blocks go back unchanged to the same provider and model, as in OpenClaw's `convertMessages`, but only from and in a request with tools (Kestrel's rule): without tools the history becomes text, which changes the prefix that Anthropic checks. `toolChoice` is sent only for a required choice (`auto` is Converse's default, and OpenClaw sends none otherwise). AWS's token errors (403 with `ExpiredTokenException`, `UnrecognizedClientException` or `InvalidSignatureException`) become the credential's own `rejected` instead of always `SignInExpired`: IAM keys still get `SignInExpired`, and a refused Bedrock API key gets `Unauthorized`, as its 401 does. The design asks the writer to confirm in AWS's Converse reference that Converse wants alternating roles: the pages "Converse", "Message" and "Use the Converse API" do not say so. Merging adjacent turns of one role is harmless either way, so Task 6 keeps it.
- **Gemini's thinking level** is sent as the enum names that OpenClaw's working path sends (`LOW`, `HIGH`, …, through Google's SDK), and a reply that stops with `MALFORMED_FUNCTION_CALL` gets the invalid tool call `BAD_JSON`.
- **Ollama** always goes to the origin of its base URL (`<origin>/api/chat`), as discovery does, so an address that the owner typed with `/v1` works for both. A local server (not Ollama Cloud) gets `options.num_ctx` of the model's context window when it is known, at most 32,768 tokens (OpenClaw's `OLLAMA_LOCAL_CONTEXT_TOKENS`), because Ollama's own default is small and a full window can use up the memory of the owner's computer, and it gets `truncate: false` and `shift: false`, as OpenClaw sends them, so that it refuses a prompt that is too long instead of cutting it. Ollama Cloud keeps its own context, as in OpenClaw. Sub-project 5 should budget a local Ollama model's history at `min(window, 32,768)`, because the window that discovery reports is the model's maximum. Ollama has no tool choice, so `NONE` leaves out the tools. Ollama's discovery leaves out a model whose capabilities lack `completion` (an embedding model).
- **JSON actions' kinds** (design D7 names four): no JSON object in the reply is `NO_ACTION`; an object that does not parse, or whose `tool` is not a string or whose `arguments` is not an object, is `BAD_JSON`; a tool that the request lacks is `UNKNOWN_TOOL`; a required key that `arguments` lacks is `MISSING_ARGUMENT`.
- **Azure's endpoint** may also be on `*.cognitiveservices.azure.com` (OpenClaw's `isAzureOpenAICompatibleHost`), and `AzureRouting.normalise` drops a query that the owner pasted with a legacy deployment URL, so that no `api-version` or key in a query is kept. A deployment goes to Anthropic Messages when its name starts with `claude` (OpenClaw's `isAnthropicFoundryDeployment`). A Chat Completions deployment without its own `maxTokensField` gets `max_completion_tokens` only when its name starts with `gpt-5` or a later GPT, or with `o` and a digit, and `max_tokens` otherwise. This is OpenClaw's `requiresFoundryMaxCompletionTokens` (`gpt-5`, `o1`, `o3`, `o4`), widened so that a later GPT or o-series deployment that the owner moves to Chat Completions keeps the field that OpenAI's reasoning models need.
- **Vertex's token.** Design D9 reuses the token until 60 s before it expires, but plan 4C builds a new `ServiceAccountAuth` for every request. So the token lives in a `ServiceAccountTokens` cache that every instance shares by default: in memory only, keyed by the service account's e-mail, the key (its `private_key_id`, or a SHA-256 of the key when the file has none), the scope and the token URI, at most four tokens, and with a `toString` that shows only their number. `VertexUrls.base` also refuses a project ID that does not have Google's form, so that a broken key file cannot build another path (for example with `..`).

## Threads

| Call | Thread | Notes |
|---|---|---|
| `ChatAdapter.complete` of every adapter, `JsonActions.complete` | The caller's coroutine (plan 4C's `ProfileClient`) | `ProviderHttp` runs each call on `Dispatchers.IO` and cancels it when the caller is cancelled; `onEvent` of a stream runs on that I/O thread, in order |
| `Gather` and `ReplyBuilder` of each adapter | One request's coroutine | Each request makes its own; they are never shared |
| `SigV4Auth.headers` | `ProviderHttp`'s coroutine | No state; it reads the clock for each attempt |
| `ServiceAccountAuth.headers` and `refresh` | `ProviderHttp`'s coroutine | The `Mutex` of its `ServiceAccountTokens` (by default the one of the process) guards the tokens, so one exchange runs at a time and a waiting `headers()` uses its result; `refresh()` always exchanges again, so two requests that get a 401 at the same time make two exchanges |
| `ModelDiscovery.list` of `Discoveries` and `BedrockModels` | The caller's coroutine | Sequential requests through `ProviderHttp`; paginated lists read one page after the other |

## File structure

| File | Responsibility |
|---|---|
| `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt` | Task 1: `Wire` and `ReplyBuilder`, shared by every adapter; Task 9: `Adapters.forStyle` |
| `styles/ChatCompletionsAdapter.kt`, `styles/CompatDefaults.kt` | Task 1: OpenAI's Chat Completions style and OpenClaw's derived compat flags (D3 to D7) |
| `styles/ResponsesAdapter.kt` | Task 2: OpenAI's Responses style with the ChatGPT plan's form and errors (D4 to D6, D17) |
| `styles/AnthropicAdapter.kt`, `styles/AnthropicModels.kt` | Task 3: Anthropic Messages, Claude's adaptive thinking rules, Copilot's options and Vertex's form (D4 to D6) |
| `styles/GeminiAdapter.kt` | Task 4: Gemini's native style with thought signatures and its thinking settings (D3 to D7) |
| `styles/OllamaAdapter.kt`, `discovery/LocalServerDiscovery.kt`, `discovery/Discoveries.kt` | Task 5: Ollama's native style, the local servers' lists and `Discoveries.forLocalServer` (D11); Task 9: `Discoveries.forProvider` |
| `styles/BedrockAdapter.kt`, `styles/SigV4Auth.kt`, `styles/MantleRouting.kt`, `discovery/BedrockModels.kt` | Task 6: Bedrock Converse, SigV4, Mantle's routing and Bedrock's model list (D4, D9, D16) |
| `styles/VertexAdapter.kt`, `styles/VertexUrls.kt`, `styles/ServiceAccountAuth.kt` | Task 7: Vertex's Gemini and Claude, its addresses and the service account's token (D4, D9, D16) |
| `styles/AzureAdapter.kt`, `styles/AzureRouting.kt` | Task 8: Azure OpenAI and Foundry (D4) |
| `styles/JsonActions.kt` | Task 9: JSON actions (D7) |
| `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AdapterHarness.kt` | Task 1: the shared test bench (MockWebServer, fixtures, request builders) |
| `core/providers/src/test/kotlin/dev/kestrel/providers/styles/*Test.kt`, `discovery/LocalServerDiscoveryTest.kt`, `BedrockModelsTest.kt`, `DiscoveriesTest.kt` | The tests of each task, one class per main class |
| `core/providers/src/test/resources/fixtures/chat-completions/`, `responses/`, `anthropic/`, `gemini/`, `ollama/`, `bedrock/`, `vertex/`, `azure/`, `local-servers/` | The fixtures of each style (D19) |
| `README.md` (the 4B marker), `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 10.3) | Task 9: the documents (D23's 4B part) |

Paths in the table without a folder are under `core/providers/src/main/kotlin/dev/kestrel/providers/`.

---

### Task 1: Chat Completions

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt`, `ChatCompletionsAdapter.kt`, `CompatDefaults.kt`
- Create (test bench): `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AdapterHarness.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/chat-completions/` (14 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/ChatCompletionsAdapterTest.kt`, `CompatDefaultsTest.kt`

**Interfaces:**
- Consumes (plan 4A): `ChatRequest`, `ChatRequests.check`, `ChatMessage` with `User`, `Assistant` (with `toolCalls`) and `ToolResult`, `Part.Text`, `Part.Image`, `ImageType.mime`, `AssistantPart.Text`, `ToolCall` and `Reasoning`, `ReasoningState`, `ReasoningLevel`, `ToolChoice`, `ToolSpec`, `ChatResponse`, `StopReason`, `InvalidKind`, `InvalidToolCall`, `Usage`, `RequestStyle`, `ModelTarget(providerId, style, baseUrl, model, sentModelId, auth, headers, options)`, `StyleOptions`, `RequestHeaders.headers(request)`, `ChatAdapter`; `ProviderHttp.send` and `stream`, `HttpRequest`, `RequestMeta.copy(logPath = …)`, `RequestAuth`, `RefreshResult`, `NoAuth`, `ApiKeyAuth`, `KeyHeader`, `ProviderError`, `ProviderException`, `Hosts.isLocal`; `ModelEntry`, `CompatFlags`; the test helper `RedirectingCalls(server)` with `requests`.
- Produces (package `dev.kestrel.providers.styles`): `internal object Wire` with `json`, `post(url, body, target, request, stream, extra = emptyMap(), logPath = null): HttpRequest`, `parse(text): JsonObject` (Malformed when it is no JSON object), `base64(bytes)`, `code(value)`, `streamError(status: Int?, rawCode: String?, target): ProviderError`, `callText(call)`, `resultText(result)`, `assistantText(message, callsAsText)`, `nextSyntheticNumber(request, prefix): Int`, `level(request, model, compat): String?`, `effort(level, model, compat): String?`, `estimate(request, replyChars, reasoningChars, reasoningModel): Usage`, `minus(total, vararg parts)`, and the extensions `JsonObject.obj`, `arr`, `str`, `long`, `bool` and `JsonElement?.objects()`; `internal class ReplyBuilder(request)` with `text`, `reasoning`, `call(id, name, arguments: String)`, `call(id, name, arguments: JsonElement?)`, `invalid(kind, raw)`, `replyChars`, `hasCalls` and `response(stop, usage, modelId)`; `internal class ChatCompletionsAdapter(http: ProviderHttp) : ChatAdapter` with `companion fun replaysReasoning(modelId): Boolean`; `internal object CompatDefaults` with `resolve(target): CompatFlags`, `defaults(providerId, baseUrl, modelId, reasoning): CompatFlags`, `endpointClass(baseUrl): String` and `knownJsonSchemaModel(modelId)`. Test bench (test sources): `internal class AdapterHarness : AutoCloseable` with `server`, `calls`, `http`, `fixture(path)`, `reply(path)`, `status(code, body, vararg headers)`, `take()`, `body(recorded)`, `assertBody(path, recorded)`, the nested `RefreshingAuth`, and in its companion `OPEN_APP`, `GET_NUMBER`, `SCREENSHOT`, `json`, `obj`, `user`, `target(…)`, `request(…)` and `errorOf(adapter, request, target)`. Every later task uses these names.

Design D3 to D7, D17 and D19. Chat Completions is the style of most providers of the catalog, of Copilot's Gemini models, of Mantle's open models and of Azure's other deployments. The adapter appends `chat/completions` to `ModelTarget.baseUrl` as path segments (plan 4A notes that some synced bases end with `/`, which `addPathSegments` replaces), streams with server-sent events, and gathers the chunks: text, tool-call deltas by their `index` (or, from a server that sends none, by their `id`; a later piece of the same delta without either starts a new call, as in OpenClaw), the reasoning text of the first non-empty field of `reasoning_content`, `reasoning` and `reasoning_text` in each delta, Gemini's thought signature on a tool call (in `extra_content.google.thought_signature`, `function.thought_signature` or `thought_signature`, the three places that OpenClaw reads), the finish reason (`tool_call` too, as OpenClaw allows it) and the usage, on the chunk or on its choice. A stream ends correctly with `[DONE]` or a finish reason; without either it is `Malformed`, and a chunk with `error` is mapped by its status or code like an HTTP error (design D4). When the stream has no usage, `Wire.estimate` counts it (design D6) and marks it as estimated.

The request follows the model's compat flags. `CompatDefaults.resolve` takes the model's `compat` (or the target's replacement, Copilot's `StyleOptions.compat`) and fills every flag that it leaves open with the defaults of OpenClaw's `detectOpenAICompletionsCompat` (`packages/ai/src/transports/openai-completions-compat.ts` at the pinned commit), whose endpoint classes come from the manifests' `providerEndpoints` and whose families and streaming-usage flags come from their `providerRequest` blocks. The flags decide the system prompt's role (`developer` only on OpenAI's own endpoint for a reasoning model), the token-limit field, `strict: false` on tools, `store: false`, `stream_options`, `response_format` for JSON actions (design D7), Anthropic's cache marker on the system prompt for the providers that OpenClaw gives it, and the reasoning format (design D5): `reasoning_effort`, OpenRouter's `reasoning.effort`, DeepSeek's and Together's switches, Z.AI's `thinking`, and Qwen's `enable_thinking` or `chat_template_kwargs`. On OpenAI's and Azure's own endpoints, a request with tools sends `reasoning_effort: "none"` to GPT-5.6 and none to GPT-5.4 mini and GPT-5.5, as OpenClaw's `applyOpenAICompletionsToolReasoning` does. `Wire.level` and `Wire.effort` map Kestrel's four levels through the model's `thinkingLevels`, `thinkingLevelMap`, `supportedReasoningEfforts` and `reasoningEffortMap`, moving a level that the model lacks to the nearest one it has. Tools go only with `parallel_tool_calls: false` (design D3). Without tools (JSON actions, or a model whose compat says `supportsTools: false`), earlier tool calls and results go back as text (design D5).

The reasoning state (design D5): the plain reasoning text is kept as a `ReasoningState` of style `CHAT_COMPLETIONS` and goes back to the same provider, whatever the model, when the compat flag asks for it, when the thinking format is `deepseek` or `zai`, when the model is in OpenClaw's list (`openai-completions-reasoning-replay.ts`), or when the model reasons and the provider is not `openai` (except OpenRouter's `anthropic/` and `x-ai/` models, which OpenClaw's `shouldPreserveOpenRouterReasoningReplay` leaves out). With `requiresReasoningContentOnAssistantMessages`, it goes back as `reasoning_content`, and an assistant message without it gets `reasoning_content: ""`. Gemini's thought signatures are a second state, keyed by the call's ID, which goes back only to the same provider and model.

`ReplyBuilder` checks each tool call against the request (design D3): a call to a tool that the request lacks gets `UNKNOWN_TOOL`, arguments that are no JSON object get `ARGUMENTS_NOT_OBJECT`; such a call is left out of the message and only the first one is reported. A call whose ID is blank or repeats another call's ID of the same reply gets a fresh `call_<n>`, so that the next request has one result per ID. `Wire.nextSyntheticNumber` numbers the IDs that a style makes up for calls without one after the highest such ID in the request. Every later adapter uses `Wire` and `ReplyBuilder` the same way.

- [ ] **Step 1: Write the fixtures**

These are the shapes of OpenAI's streamed chat completion chunks (`chat.completion.chunk`, with `usage` on a last chunk when `stream_options.include_usage` is set, research r41; `two-calls-without-index.sse` is a server that sends no `index`, `repeated-ids.sse` one that gives two calls the same ID, and `truncated-call.sse` a call that the token limit cut off), DeepSeek's `reasoning_content` (research r38), OpenRouter's error inside a stream, and Copilot's Gemini models with `extra_content.google.thought_signature` (research r38; only OpenClaw's code shows that field, and the session of design D20 checks it live).

Create `core/providers/src/test/resources/fixtures/chat-completions/copilot-gemini.sse`:

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_g1","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"},"extra_content":{"google":{"thought_signature":"c2lnbmF0dXJlLW9uZQ=="}}}]},"finish_reason":null}],"model":"gemini-3.8-flash"}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"model":"gemini-3.8-flash"}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/deepseek-escalation.request.json`:

```json
{
  "model": "deepseek-v4-pro",
  "messages": [
    {
      "role": "system",
      "content": "You are Kestrel."
    },
    {
      "role": "user",
      "content": "Hi"
    },
    {
      "role": "assistant",
      "content": "Hello.",
      "reasoning_content": ""
    },
    {
      "role": "user",
      "content": "Open the camera."
    },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_cam",
          "type": "function",
          "function": {
            "name": "open_app",
            "arguments": "{\"name\":\"Camera\"}"
          }
        }
      ],
      "reasoning_content": "The owner wants the camera."
    },
    {
      "role": "tool",
      "tool_call_id": "call_cam",
      "content": "Opened."
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "open_app",
        "description": "Opens an app by its name.",
        "parameters": {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "The app's name."
            }
          },
          "required": [
            "name"
          ]
        }
      }
    }
  ],
  "tool_choice": "auto",
  "parallel_tool_calls": false,
  "max_tokens": 256,
  "thinking": {
    "type": "enabled"
  },
  "reasoning_effort": "low",
  "stream": true,
  "stream_options": {
    "include_usage": true
  }
}
```

Create `core/providers/src/test/resources/fixtures/chat-completions/deepseek-text.sse`:

```text
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"role":"assistant","content":"The camera is open."},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":420,"completion_tokens":12,"total_tokens":432}}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/deepseek-tool-call.sse`:

```text
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"The owner wants ","reasoning":"The owner wants "},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"reasoning_content":"the camera."},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_cam","type":"function","function":{"name":"open_app","arguments":""}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"name\":"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Camera\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":300,"completion_tokens":30,"total_tokens":330,"prompt_tokens_details":{"cached_tokens":0}}}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/ends-early.sse`:

```text
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-5.5-2026-08-01","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-5.5-2026-08-01","choices":[{"index":0,"delta":{"content":", own"},"finish_reason":null}]}
```

Create `core/providers/src/test/resources/fixtures/chat-completions/invalid-calls.sse`:

```text
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"close_app","arguments":"{\"name\":\"Camera\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{},"finish_reason":"tool_call"}]}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/malformed.sse`:

```text
data: {"choices":[{"delta":
```

Create `core/providers/src/test/resources/fixtures/chat-completions/repeated-ids.sse`:

```text
data: {"id":"chatcmpl-3","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Camera\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-3","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_x","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-3","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/stream-error.sse`:

```text
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"role":"assistant","content":"Let me"},"finish_reason":null}]}

data: {"error":{"code":502,"message":"Upstream provider returned an error"}}
```

Create `core/providers/src/test/resources/fixtures/chat-completions/text.request.json`:

```json
{
  "model": "gpt-5.5",
  "messages": [
    {
      "role": "developer",
      "content": "You are Kestrel."
    },
    {
      "role": "user",
      "content": "Hi"
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "open_app",
        "description": "Opens an app by its name.",
        "parameters": {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "The app's name."
            }
          },
          "required": [
            "name"
          ]
        },
        "strict": false
      }
    }
  ],
  "tool_choice": "auto",
  "parallel_tool_calls": false,
  "max_completion_tokens": 256,
  "store": false,
  "stream": true,
  "stream_options": {
    "include_usage": true
  }
}
```

Create `core/providers/src/test/resources/fixtures/chat-completions/text.sse`:

```text
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-5.5-2026-08-01","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}],"error":null}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-5.5-2026-08-01","choices":[{"index":0,"delta":{"content":", owner."},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-5.5-2026-08-01","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-5.5-2026-08-01","choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":40,"total_tokens":1240,"prompt_tokens_details":{"cached_tokens":800,"cache_write_tokens":300},"completion_tokens_details":{"reasoning_tokens":25}}}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/truncated-call.sse`:

```text
data: {"id":"chatcmpl-2","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_t","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Cam"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/two-calls-without-index.sse`:

```text
data: {"id":"chatcmpl-2","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"id":"call_a","type":"function","function":{"name":"open_app","arguments":"{\"name\":","thought_signature":"c2lnLWE="}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"tool_calls":[{"function":{"arguments":"\"Camera\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"tool_calls":[{"id":"call_b","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"},"thought_signature":"c2lnLWI="}]},"finish_reason":null}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/chat-completions/two-calls.sse`:

```text
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Camera\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls","usage":{"prompt_tokens":300,"completion_tokens":20,"total_tokens":320}}]}

data: [DONE]
```

- [ ] **Step 2: Write the test bench and the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AdapterHarness.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.chat.ToolSpec
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * The shared test bench of the request styles (design D19): a MockWebServer behind [RedirectingCalls], so that each
 * adapter keeps its fixed `https://` address, and the fixtures under `src/test/resources/fixtures/<style>/`.
 */
internal class AdapterHarness : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val calls = RedirectingCalls(server)
    val http = ProviderHttp(calls)

    fun fixture(path: String): String =
        AdapterHarness::class.java.getResource("/fixtures/$path")?.readText()?.replace("\r\n", "\n")
            ?: throw AssertionError("missing fixture $path")

    /** A 200 reply whose body is the fixture [path]: server-sent events for `.sse`, JSON otherwise. */
    fun reply(path: String) {
        val type = if (path.endsWith(".sse")) "text/event-stream" else "application/json"
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", type).body(fixture(path)).build())
    }

    fun status(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build())
    }

    fun take(): RecordedRequest = server.takeRequest()

    fun body(recorded: RecordedRequest): JsonObject = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject

    /** Compares a request body with the fixture [path] as JSON, so that the order of keys does not matter. */
    fun assertBody(path: String, recorded: RecordedRequest) {
        assertEquals(Json.parseToJsonElement(fixture(path)), body(recorded))
    }

    override fun close() {
        server.close()
    }

    companion object {
        val OPEN_APP = ToolSpec(
            "open_app",
            "Opens an app by its name.",
            Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string","description":"The app's name."}},"required":["name"]}""") as JsonObject,
        )
        val GET_NUMBER = ToolSpec("get_number", "Returns the test number.", Json.parseToJsonElement("""{"type":"object"}""") as JsonObject)

        /** Eight bytes that stand for a screenshot; adapters only encode them. */
        val SCREENSHOT = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun json(text: String): JsonElement = Json.parseToJsonElement(text)

        fun obj(text: String): JsonObject = json(text) as JsonObject

        fun user(text: String): ChatMessage.User = ChatMessage.User(listOf(Part.Text(text)))

        fun target(
            providerId: String,
            style: RequestStyle,
            baseUrl: String,
            model: ModelEntry,
            auth: RequestAuth = NoAuth,
            options: StyleOptions = StyleOptions(),
            sentModelId: String = model.id,
        ) = ModelTarget(providerId, style, baseUrl.toHttpUrl(), model, sentModelId, auth, options = options)

        fun request(vararg messages: ChatMessage, tools: List<ToolSpec> = listOf(OPEN_APP), block: ChatRequest.() -> ChatRequest = { this }) =
            ChatRequest(system = "You are Kestrel.", messages = messages.toList(), tools = tools, maxOutputTokens = 256).block()

        fun errorOf(adapter: ChatAdapter, request: ChatRequest, target: ModelTarget): ProviderError =
            try {
                runBlocking { adapter.complete(request, target) }
                fail("expected a ProviderException")
                throw AssertionError()
            } catch (e: ProviderException) {
                e.error
            }
    }

    /** A credential that is refreshed once, as ProviderHttp's rule of one refresh and one retry needs (design D9). */
    class RefreshingAuth(private val header: String = "Authorization") : RequestAuth {
        var token = "old-token"
        var refreshes = 0

        override suspend fun headers(request: HttpRequest) = mapOf(header to "Bearer $token")

        override suspend fun refresh(): RefreshResult {
            refreshes++
            token = "new-token"
            return RefreshResult.Refreshed
        }
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/ChatCompletionsAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.OPEN_APP
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = ChatCompletionsAdapter(bench.http)

    private val gpt = ModelEntry("gpt-5.5", "GPT-5.5", reasoning = true)
    private val openAi = target("openai", RequestStyle.CHAT_COMPLETIONS, "https://api.openai.com/v1", gpt)
    private val gptOss = target("groq", RequestStyle.CHAT_COMPLETIONS, "https://api.groq.com/openai/v1", ModelEntry("openai/gpt-oss-120b", "GPT OSS 120B"))
    private val deepSeekCompat = CompatFlags(maxTokensField = "max_tokens", supportsReasoningEffort = true, requiresReasoningContentOnAssistantMessages = true, supportsUsageInStreaming = true)
    private fun deepSeek(id: String) = target(
        "deepseek", RequestStyle.CHAT_COMPLETIONS, "https://api.deepseek.com",
        ModelEntry(id, id, reasoning = true, compat = deepSeekCompat, thinkingLevelMap = mapOf("xhigh" to "high", "max" to "max")),
    )

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndTheRequestFollowsTheCompatFlags() = runBlocking {
        // The body has no reasoning_effort: OpenAI refuses it for GPT-5.5 with tools (OpenClaw's applyOpenAICompletionsToolReasoning).
        bench.reply("chat-completions/text.sse")

        val response = adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.LOW) }, openAi)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 100, output = 40, cacheRead = 800, cacheWrite = 300, reasoning = 25), response.usage)
        assertEquals("gpt-5.5-2026-08-01", response.modelId)
        assertEquals(listOf("https://api.openai.com/v1/chat/completions".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("text/event-stream", recorded.headers["accept"])
        bench.assertBody("chat-completions/text.request.json", recorded)
    }

    @Test
    fun deepSeeksReasoningGoesBackWithTheToolCallAfterAnEscalation() = runBlocking {
        bench.reply("chat-completions/deepseek-tool-call.sse")
        bench.reply("chat-completions/deepseek-text.sse")
        val earlier = listOf(user("Hi"), ChatMessage.Assistant(listOf(AssistantPart.Text("Hello."))), user("Open the camera."))

        val first = adapter.complete(request(*earlier.toTypedArray()) { copy(reasoning = ReasoningLevel.LOW) }, deepSeek("deepseek-v4-flash"))

        assertEquals(StopReason.TOOL_CALL, first.stop)
        val state = ReasoningState(RequestStyle.CHAT_COMPLETIONS, "deepseek", "deepseek-v4-flash", obj("""{"field":"reasoning_content","text":"The owner wants the camera."}"""))
        assertEquals(
            listOf(AssistantPart.Reasoning(state), AssistantPart.ToolCall("call_cam", "open_app", obj("""{"name":"Camera"}"""))),
            first.message.parts,
        )
        val next = request(*(earlier + first.message + ChatMessage.ToolResult("call_cam", "open_app", "Opened.")).toTypedArray()) {
            copy(reasoning = ReasoningLevel.LOW)
        }
        val second = adapter.complete(next, deepSeek("deepseek-v4-pro"))

        assertEquals(listOf(AssistantPart.Text("The camera is open.")), second.message.parts)
        bench.take()
        bench.assertBody("chat-completions/deepseek-escalation.request.json", bench.take())

        // OpenClaw's shouldPreserveOpenRouterReasoningReplay: OpenRouter's Claude routes get no plain reasoning back.
        bench.reply("chat-completions/text.sse")
        val claude = target("openrouter", RequestStyle.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", ModelEntry("anthropic/claude-sonnet-5", "Claude Sonnet 5", reasoning = true))
        val thought = ReasoningState(RequestStyle.CHAT_COMPLETIONS, "openrouter", "anthropic/claude-sonnet-5", obj("""{"field":"reasoning","text":"Say hello."}"""))
        adapter.complete(request(user("Hi"), ChatMessage.Assistant(listOf(AssistantPart.Reasoning(thought), AssistantPart.Text("Hello."))), user("Again.")), claude)
        assertNull(bench.body(bench.take())["messages"]!!.jsonArray[2].jsonObject["reasoning"])
    }

    @Test
    fun aReplyWithTwoToolCallsKeepsBothInOrderAndTheNextRequestAnswersBoth() = runBlocking {
        bench.reply("chat-completions/two-calls-without-index.sse")
        bench.reply("chat-completions/repeated-ids.sse")
        bench.reply("chat-completions/two-calls.sse")
        bench.reply("chat-completions/deepseek-text.sse")

        // A server that sends no `index`: a new `id` starts a new call. Its signatures sit in `function` and on the piece.
        val withoutIndex = adapter.complete(request(user("Open the camera and the clock.")), gptOss)
        // A server that repeats an ID: the second call gets a fresh one, so that each result has its own.
        val repeated = adapter.complete(request(user("Open the camera and the clock.")), gptOss)
        val first = adapter.complete(request(user("Open the camera and the clock.")), gptOss)

        for (reply in listOf(withoutIndex, first)) {
            assertEquals(listOf("call_a", "call_b"), reply.message.toolCalls.map { it.id })
            assertEquals(listOf(obj("""{"name":"Camera"}"""), obj("""{"name":"Clock"}""")), reply.message.toolCalls.map { it.arguments })
        }
        val signatures = withoutIndex.message.parts.filterIsInstance<AssistantPart.Reasoning>().single().state.payload as JsonObject
        assertEquals(json("""{"call_a":"c2lnLWE=","call_b":"c2lnLWI="}"""), signatures["thoughtSignatures"])
        assertEquals(listOf("call_x", "call_2"), repeated.message.toolCalls.map { it.id })
        // Some compatible servers put the usage on the choice.
        assertEquals(Usage(input = 300, output = 20), first.usage)
        val next = request(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("call_a", "open_app", "Opened."),
            ChatMessage.ToolResult("call_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        adapter.complete(next, gptOss)

        bench.take()
        bench.take()
        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("call_a", "call_b"), messages[2].jsonObject["tool_calls"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("call_b"), messages[4].jsonObject["tool_call_id"])

        // Without streaming, two calls without `index` and `id` in one body stay two calls.
        bench.status(200, """{"model":"openai/gpt-oss-120b","choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Camera\"}"}},{"type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"}}]},"finish_reason":"tool_calls"}]}""")
        val plain = adapter.complete(request(user("Open the camera and the clock.")), gptOss.copy(options = StyleOptions(stream = false)))
        assertEquals(listOf(obj("""{"name":"Camera"}"""), obj("""{"name":"Clock"}""")), plain.message.toolCalls.map { it.arguments })
    }

    @Test
    fun anImageGoesAsADataUrlBesideTheText() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val screen = ChatMessage.User(listOf(Part.Text("What is on the screen?"), Part.Image(ImageType.PNG, SCREENSHOT)))

        adapter.complete(request(screen), openAi)

        val content = bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"]
        assertEquals(
            json("""[{"type":"text","text":"What is on the screen?"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}]"""),
            content,
        )
    }

    @Test
    fun copilotsGeminiKeepsItsThoughtSignatureAndAStreamWithoutUsageIsEstimated() = runBlocking {
        bench.reply("chat-completions/copilot-gemini.sse")
        bench.reply("chat-completions/deepseek-text.sse")
        val copilot = target(
            "github-copilot", RequestStyle.CHAT_COMPLETIONS, "https://api.individual.githubcopilot.com",
            ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", reasoning = true),
            options = StyleOptions(compat = CompatFlags(maxTokensField = "max_tokens", supportsStore = false, supportsDeveloperRole = false, supportsUsageInStreaming = false)),
        )

        val first = adapter.complete(request(user("What time is it?")), copilot)

        assertTrue(first.usage.estimated)
        assertTrue(first.usage.input > 0)
        assertEquals(listOf("call_g1"), first.message.toolCalls.map { it.id })
        adapter.complete(request(user("What time is it?"), first.message, ChatMessage.ToolResult("call_g1", "open_app", "Opened.")), copilot)

        val firstBody = bench.body(bench.take())
        assertNull(firstBody["stream_options"])
        assertNull(firstBody["store"])
        assertEquals(JsonPrimitive(256), firstBody["max_tokens"])
        val call = bench.body(bench.take())["messages"]!!.jsonArray[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals(json("""{"google":{"thought_signature":"c2lnbmF0dXJlLW9uZQ=="}}"""), call["extra_content"])
    }

    @Test
    fun withoutToolsTheHistoryIsTextAndTheActionSchemaGoesIntoTheResponseFormat() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val call = AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}"""))
        val schema = obj("""{"type":"object","properties":{"tool":{"type":"string","enum":["open_app"]},"arguments":{"type":"object"}},"required":["tool","arguments"]}""")
        val jsonRequest = request(
            user("Open the camera."),
            ChatMessage.Assistant(listOf(call)),
            ChatMessage.ToolResult("call_1", "open_app", "Opened."),
            tools = emptyList(),
        ) { copy(jsonSchema = schema) }

        adapter.complete(jsonRequest, openAi)

        val body = bench.body(bench.take())
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(json("""{"role":"assistant","content":"[tool call id=call_1 name=open_app] {\"name\":\"Camera\"}"}"""), messages[2])
        assertEquals(json("""{"role":"user","content":"[tool result id=call_1 name=open_app] Opened."}"""), messages[3])
        assertEquals(json("""{"type":"json_schema","json_schema":{"name":"kestrel_action","schema":$schema}}"""), body["response_format"])
    }

    @Test
    fun aCallThatKestrelCannotUseIsAnInvalidToolCall() = runBlocking {
        bench.reply("chat-completions/invalid-calls.sse")
        bench.reply("chat-completions/truncated-call.sse")

        val unknown = adapter.complete(request(user("Close the camera.")), gptOss)
        val truncated = adapter.complete(request(user("Open the camera.")), gptOss)

        assertEquals(InvalidKind.UNKNOWN_TOOL, unknown.invalidToolCall?.kind)
        assertTrue(unknown.message.toolCalls.isEmpty())
        // The finish reason `tool_call` (singular) is a tool call, as OpenClaw reads it.
        assertEquals(StopReason.TOOL_CALL, unknown.stop)
        assertEquals(InvalidKind.ARGUMENTS_NOT_OBJECT, truncated.invalidToolCall?.kind)
        assertEquals(StopReason.MAX_TOKENS, truncated.stop)
        assertTrue(truncated.message.toolCalls.isEmpty())
    }

    @Test
    fun a401IsRefreshedOnceAndSentAgain() = runBlocking {
        bench.status(401)
        bench.reply("chat-completions/text.sse")
        val auth = AdapterHarness.RefreshingAuth()

        adapter.complete(request(user("Hi")), openAi.copy(auth = auth))

        assertEquals(1, auth.refreshes)
        assertEquals("Bearer old-token", bench.take().headers["Authorization"])
        assertEquals("Bearer new-token", bench.take().headers["Authorization"])
    }

    @Test
    fun a429KeepsItsRetryAfterAndCodeAndA500IsAServerError() {
        bench.status(429, """{"error":{"code":"rate_limit_exceeded","message":"Slow down."}}""", "Retry-After" to "2")
        bench.status(500, """{"error":{"message":"oops"}}""")

        assertEquals(ProviderError.RateLimited(2_000, "rate_limit_exceeded"), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun anErrorInsideTheStreamIsMappedLikeAnHttpError() {
        bench.reply("chat-completions/stream-error.sse")

        assertEquals(ProviderError.ServerError(502), errorOf(adapter, request(user("Hi")), gptOss))
    }

    @Test
    fun aMalformedChunkAndAStreamThatEndsEarlyAreMalformed() {
        bench.reply("chat-completions/malformed.sse")
        bench.reply("chat-completions/ends-early.sse")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun theReasoningListOfOpenClawMatchesTheLastSegmentAndTierSuffixes() {
        assertTrue(ChatCompletionsAdapter.replaysReasoning("moonshotai/kimi-k3-free"))
        assertTrue(ChatCompletionsAdapter.replaysReasoning("deepseek-v4-pro:cloud"))
        assertFalse(ChatCompletionsAdapter.replaysReasoning("gpt-5.5"))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/CompatDefaultsTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The derived compat flags of OpenClaw's `detectOpenAICompletionsCompat` (design D5, research r39). */
class CompatDefaultsTest {
    private fun defaults(provider: String, base: String, model: String = "m", reasoning: Boolean = false) =
        CompatDefaults.defaults(provider, base.toHttpUrl(), model, reasoning)

    @Test
    fun openAisOwnEndpointGetsEveryOpenAiFeature() {
        val flags = defaults("openai", "https://api.openai.com/v1", "gpt-5.5", reasoning = true)

        assertEquals("max_completion_tokens", flags.maxTokensField)
        assertEquals(true, flags.supportsStore)
        assertEquals(true, flags.supportsDeveloperRole)
        assertEquals(true, flags.supportsStrictMode)
        assertEquals(true, flags.supportsUsageInStreaming)
        assertEquals(true, flags.supportsJsonSchemaResponseFormat)
        assertEquals("openai", flags.thinkingFormat)
        assertFalse(defaults("openai", "https://api.openai.com/v1", "gpt-3.5-turbo").supportsJsonSchemaResponseFormat!!)
    }

    @Test
    fun deepSeekAndXiaomiNeedTheirReasoningTextBack() {
        for (base in listOf("https://api.deepseek.com", "https://token-plan-sgp.xiaomimimo.com/v1")) {
            val flags = defaults("any", base)
            assertEquals("deepseek", flags.thinkingFormat)
            assertEquals(true, flags.requiresReasoningContentOnAssistantMessages)
            assertEquals(false, flags.supportsStore)
            assertEquals(false, flags.supportsUsageInStreaming)
        }
    }

    @Test
    fun theProvidersThatWantMaxTokensGetIt() {
        val maxTokens = listOf(
            defaults("zai", "https://api.z.ai/api/paas/v4"),
            defaults("moonshot", "https://api.moonshot.ai/v1"),
            defaults("together", "https://api.together.xyz/v1"),
            defaults("mistral", "https://api.mistral.ai/v1"),
            defaults("chutes", "https://llm.chutes.ai/v1"),
            defaults("novita", "https://api.novita.ai/openai/v1"),
            defaults("qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
        )
        assertTrue(maxTokens.all { it.maxTokensField == "max_tokens" })
        assertEquals("max_completion_tokens", defaults("qwen", "https://coding-intl.dashscope.aliyuncs.com/v1").maxTokensField)
        assertEquals("zai", defaults("zai", "https://open.bigmodel.cn/api/paas/v4").thinkingFormat)
        assertEquals("together", defaults("together", "https://api.together.xyz/v1").thinkingFormat)
        assertEquals(true, defaults("moonshot", "https://api.moonshot.cn/v1").supportsUsageInStreaming)
    }

    @Test
    fun openRouterUsesItsReasoningFormatAndAnthropicCacheMarkersForClaudeAndGemini() {
        val claude = defaults("openrouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5")

        assertEquals("openrouter", claude.thinkingFormat)
        assertEquals(true, claude.supportsStrictMode)
        assertEquals("anthropic", claude.cacheControlFormat)
        assertEquals("anthropic", defaults("openrouter", "https://openrouter.ai/api/v1", "google/gemini-3.8-flash").cacheControlFormat)
        assertNull(defaults("openrouter", "https://openrouter.ai/api/v1", "openai/gpt-5.5").cacheControlFormat)
        assertEquals("anthropic", defaults("qwen", "https://coding-intl.dashscope.aliyuncs.com/v1").cacheControlFormat)
    }

    @Test
    fun localServersReportUsageInTheirStreamsAndAPublicProxyDoesNot() {
        // A LAN address counts as local (Kestrel's Hosts.isLocal), so a provider outside OpenClaw's streaming-usage list gets usage too.
        assertEquals(true, defaults("litellm", "http://192.168.1.20:4000/v1").supportsUsageInStreaming)
        assertEquals(true, defaults("vllm", "http://192.168.1.20:8000/v1").supportsUsageInStreaming)
        assertEquals(true, defaults("litellm", "http://127.0.0.1:4000/v1").supportsUsageInStreaming)
        assertEquals(false, defaults("litellm", "https://litellm.example.com/v1").supportsUsageInStreaming)
        assertEquals("local", CompatDefaults.endpointClass("http://10.0.0.5:8080/v1".toHttpUrl()))
    }

    @Test
    fun copilotAndAzureAreNotOpenAisOwnEndpoint() {
        val copilot = defaults("github-copilot", "https://api.individual.githubcopilot.com")
        val azure = defaults("microsoft-foundry", "https://kestrel.openai.azure.com/openai/v1")

        assertEquals("github-copilot-native", CompatDefaults.endpointClass("https://api.individual.githubcopilot.com".toHttpUrl()))
        assertEquals(false, copilot.supportsStrictMode)
        assertEquals(false, copilot.supportsDeveloperRole)
        assertEquals(true, azure.supportsStore)
        assertEquals(false, azure.supportsDeveloperRole)
    }

    @Test
    fun theModelsFlagsWinAndCopilotsFlagsReplaceThem() {
        val model = ModelEntry("m", "M", compat = CompatFlags(maxTokensField = "max_tokens", supportsUsageInStreaming = true))
        val target = target("openai", RequestStyle.CHAT_COMPLETIONS, "https://api.openai.com/v1", model)

        assertEquals("max_tokens", CompatDefaults.resolve(target).maxTokensField)
        assertEquals(true, CompatDefaults.resolve(target).supportsTools)
        val replaced = CompatDefaults.resolve(target.copy(options = StyleOptions(compat = CompatFlags(supportsUsageInStreaming = false))))
        assertEquals("max_completion_tokens", replaced.maxTokensField)
        assertEquals(false, replaced.supportsUsageInStreaming)
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ChatCompletionsAdapter'" and "Unresolved reference 'CompatDefaults'".

- [ ] **Step 4: Write what every adapter shares**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.InvalidToolCall
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import java.util.Base64

/**
 * What every adapter shares (design D3 to D6): building and reading JSON, the replay of tool history as text, the
 * reasoning level of design D5, the estimate of design D6, and the mapping of an error inside a stream (design D4).
 */
internal object Wire {
    val json = Json { ignoreUnknownKeys = true }

    private val CODE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    private val EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    private val SERVER_CODES = setOf("server_error", "api_error", "overloaded_error", "internal_error", "internal", "unavailable", "service_unavailable")
    private val POLICY_CODES = setOf("permission_error", "billing_error", "permission_denied", "insufficient_quota")

    /**
     * A JSON request to [url]. The adapter adds only `accept` and its [extra] headers; the target's headers (design
     * "Shared names") come last, and the credential's header comes from RequestAuth in ProviderHttp.
     */
    fun post(url: HttpUrl, body: JsonObject, target: ModelTarget, request: ChatRequest, stream: Boolean, extra: Map<String, String> = emptyMap(), logPath: String? = null): HttpRequest =
        HttpRequest(
            method = "POST",
            url = url,
            headers = mapOf("accept" to if (stream) "text/event-stream" else "application/json") + extra + target.headers.headers(request),
            body = body.toString().encodeToByteArray(),
            contentType = "application/json",
            meta = if (logPath == null) request.meta else request.meta.copy(logPath = logPath),
        )

    /** Parses a JSON object, or fails with Malformed: a reply that Kestrel cannot read (design D17). */
    fun parse(text: String): JsonObject =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Keeps a provider's machine code only when it is one (design D17). */
    fun code(value: String?): String? = value?.takeIf(CODE::matches)

    /**
     * Maps an error that a stream reports with status 200 like an HTTP error (design D4): a rate limit, a server error or
     * an overloaded server, a refused credential, a refused permission or payment, and otherwise a bad request.
     */
    fun streamError(status: Int?, rawCode: String?, target: ModelTarget): ProviderError {
        val code = code(rawCode)
        val name = code?.lowercase().orEmpty()
        return when {
            status == 429 || "rate_limit" in name || name == "resource_exhausted" || name == "too_many_requests" ->
                ProviderError.RateLimited(null, code)
            (status != null && status >= 500) || name in SERVER_CODES -> ProviderError.ServerError(status?.takeIf { it >= 500 } ?: 500)
            status == 401 || name == "authentication_error" || name == "unauthenticated" -> target.auth.rejected
            status == 402 || status == 403 || name in POLICY_CODES -> ProviderError.QuotaOrPolicy(status ?: 403, code)
            else -> ProviderError.BadRequest(status ?: 400, code)
        }
    }

    /** How a tool call goes back as text when the request sends no tools (design D5, research r39). */
    fun callText(call: AssistantPart.ToolCall): String = "[tool call id=${call.id} name=${call.name}] ${call.arguments}"

    fun resultText(result: ChatMessage.ToolResult): String = "[tool result id=${result.callId} name=${result.name}] ${result.text}"

    /** The text of an assistant message, with its tool calls as text when [callsAsText] is true. */
    fun assistantText(message: ChatMessage.Assistant, callsAsText: Boolean): String {
        val texts = message.parts.filterIsInstance<AssistantPart.Text>().map { it.text }
        val calls = if (callsAsText) message.toolCalls.map(::callText) else emptyList()
        return (texts + calls).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * The number after the highest made-up call ID with [prefix] in the request's history, or 0: a style whose provider
     * gives no call IDs numbers its calls from here, so that IDs do not repeat across a task's steps.
     */
    fun nextSyntheticNumber(request: ChatRequest, prefix: String): Int =
        request.messages.filterIsInstance<ChatMessage.Assistant>().flatMap { it.toolCalls }
            .mapNotNull { call -> call.id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 0

    /**
     * The reasoning level that a request gets (design D5), as OpenClaw names it (`minimal` to `max`), or null when the
     * request asks for none, the model does not reason, or the model's only level is `off`. A level that the model's
     * `thinkingLevels` lacks, or that its `thinkingLevelMap` maps to null, moves to the nearest allowed one, upwards first.
     */
    fun level(request: ChatRequest, model: ModelEntry, compat: CompatFlags): String? {
        val asked = request.reasoning ?: return null
        if (!model.reasoning) return null
        val allowed = (compat.thinkingLevels ?: LEVELS).filter { it != "off" && !(model.thinkingLevelMap.containsKey(it) && model.thinkingLevelMap[it] == null) }
        if (allowed.isEmpty()) return null
        return nearest(asked.name.lowercase(), allowed, LEVELS)
    }

    /**
     * The provider's effort word for [level] (design D5): the model's `thinkingLevelMap`, then the nearest of its
     * `supportedReasoningEfforts`, then its `reasoningEffortMap`. Null when the effort would be `none`.
     */
    fun effort(level: String, model: ModelEntry, compat: CompatFlags): String? {
        val mapped = model.thinkingLevelMap[level] ?: level
        val supported = compat.supportedReasoningEfforts?.filter { it != "none" }
        val chosen = when {
            mapped == "none" -> return null
            supported == null || mapped in supported -> mapped
            supported.isEmpty() -> return null
            else -> nearest(mapped, supported, EFFORTS)
        }
        return compat.reasoningEffortMap?.get(chosen) ?: chosen
    }

    private fun nearest(wanted: String, allowed: List<String>, order: List<String>): String {
        if (wanted in allowed) return wanted
        val index = order.indexOf(wanted)
        if (index < 0) return allowed.first()
        for (step in 1 until order.size) {
            order.getOrNull(index + step)?.takeIf { it in allowed }?.let { return it }
            order.getOrNull(index - step)?.takeIf { it in allowed }?.let { return it }
        }
        return allowed.first()
    }

    /**
     * Counts tokens when a reply has no usage (design D6): a quarter of the request's characters plus 1,000 per image as
     * input, and a quarter of the reply's and of the streamed reasoning's characters as output, doubled for a reasoning
     * model whose stream showed no reasoning, because hidden reasoning is billed as output.
     */
    fun estimate(request: ChatRequest, replyChars: Int, reasoningChars: Int, reasoningModel: Boolean): Usage {
        var chars = request.system.length.toLong()
        var images = 0L
        for (tool in request.tools) chars += tool.name.length + tool.description.length + tool.parameters.toString().length
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> message.parts.forEach { part ->
                    when (part) {
                        is Part.Text -> chars += part.text.length
                        is Part.Image -> images++
                    }
                }
                is ChatMessage.Assistant -> message.parts.forEach { part ->
                    when (part) {
                        is AssistantPart.Text -> chars += part.text.length
                        is AssistantPart.ToolCall -> chars += part.name.length + part.arguments.toString().length
                        is AssistantPart.Reasoning -> Unit
                    }
                }
                is ChatMessage.ToolResult -> chars += message.text.length
            }
        }
        val reasoning = reasoningChars / 4L
        var output = replyChars / 4L + reasoning
        if (reasoningModel && reasoningChars == 0) output *= 2
        return Usage(input = chars / 4 + images * 1_000, output = output, reasoning = reasoning, estimated = true)
    }

    /** Subtracts without going below zero, as OpenClaw does (design D6). */
    fun minus(total: Long, vararg parts: Long): Long = (total - parts.sum()).coerceAtLeast(0)

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
}

/**
 * Gathers one assistant reply in the order that the provider gave its parts, checks each tool call against the request
 * (design D3), and builds the [ChatResponse]. A call to a tool that the request does not have, or whose arguments are no
 * JSON object, is left out of the message and reported as [InvalidToolCall] with its raw arguments as text. A call whose
 * ID is blank or repeats an earlier call's ID of the same reply gets a fresh `call_<n>`.
 */
internal class ReplyBuilder(request: ChatRequest) {
    private val parts = mutableListOf<AssistantPart>()
    private var invalid: InvalidToolCall? = null
    private val tools = request.tools.map { it.name }.toSet()
    var replyChars = 0
        private set

    val hasCalls: Boolean get() = parts.any { it is AssistantPart.ToolCall }

    fun text(text: String) {
        if (text.isEmpty()) return
        replyChars += text.length
        val last = parts.lastOrNull()
        if (last is AssistantPart.Text) parts[parts.size - 1] = AssistantPart.Text(last.text + text) else parts += AssistantPart.Text(text)
    }

    fun reasoning(state: ReasoningState) {
        parts += AssistantPart.Reasoning(state)
    }

    /** A call whose arguments came as text, as in Chat Completions and Responses; a blank text means no arguments. */
    fun call(id: String, name: String, arguments: String) {
        replyChars += name.length + arguments.length
        val parsed = if (arguments.isBlank()) JsonObject(emptyMap()) else try {
            Wire.json.parseToJsonElement(arguments)
        } catch (e: SerializationException) {
            null
        }
        add(id, name, parsed, arguments)
    }

    /** A call whose arguments came as JSON, as in Anthropic Messages, Gemini, Bedrock and Ollama. */
    fun call(id: String, name: String, arguments: JsonElement?) {
        val text = arguments?.toString().orEmpty()
        replyChars += name.length + text.length
        add(id, name, arguments ?: JsonObject(emptyMap()), text)
    }

    /** A call that the provider itself reports as unusable, such as Gemini's MALFORMED_FUNCTION_CALL. */
    fun invalid(kind: InvalidKind, raw: String) {
        invalid = invalid ?: InvalidToolCall(kind, raw)
    }

    private fun add(id: String, name: String, arguments: JsonElement?, raw: String) {
        when {
            name !in tools -> invalid = invalid ?: InvalidToolCall(InvalidKind.UNKNOWN_TOOL, raw)
            arguments !is JsonObject -> invalid = invalid ?: InvalidToolCall(InvalidKind.ARGUMENTS_NOT_OBJECT, raw)
            else -> parts += AssistantPart.ToolCall(unique(id), name, arguments)
        }
    }

    /** A blank or repeated ID gets a fresh one, because the next request needs one result per ID (design D3). */
    private fun unique(id: String): String {
        val taken = parts.filterIsInstance<AssistantPart.ToolCall>().map { it.id }.toSet()
        if (id.isNotBlank() && id !in taken) return id
        var n = taken.size + 1
        while ("call_$n" in taken) n++
        return "call_$n"
    }

    /** A reply that holds a valid tool call stops with TOOL_CALL, whatever the provider's stop reason said. */
    fun response(stop: StopReason, usage: Usage, modelId: String): ChatResponse =
        ChatResponse(
            message = ChatMessage.Assistant(parts.toList()),
            stop = if (hasCalls && stop == StopReason.END) StopReason.TOOL_CALL else stop,
            usage = usage,
            modelId = modelId,
            invalidToolCall = invalid,
        )
}
```

- [ ] **Step 5: Write the compat defaults**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/CompatDefaults.kt`:

```kotlin
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
```

- [ ] **Step 6: Write the adapter**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/ChatCompletionsAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's Chat Completions style (spec 10.3, design D3 to D7), used by most providers of the catalog, by Copilot's
 * Gemini models, by Mantle's open models and by Azure's other deployments. It streams by default and gathers the stream
 * into one reply: text, tool-call deltas by index, the reasoning text fields, Gemini's thought signatures and the usage.
 * The model's compat flags, completed by [CompatDefaults], decide the request's quirks.
 */
internal class ChatCompletionsAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val compat = CompatDefaults.resolve(target)
        val stream = target.options.stream ?: true
        val url = target.baseUrl.newBuilder().addPathSegments("chat/completions").build()
        val body = body(request, target, compat, stream)
        val gather = Gather(request, target)
        if (stream) {
            http.stream(Wire.post(url, body, target, request, stream = true), target.auth) { event ->
                if (event.data.trim() == "[DONE]") gather.done = true else gather.chunk(Wire.parse(event.data), "delta")
            }
        } else {
            gather.chunk(Wire.parse(http.send(Wire.post(url, body, target, request, stream = false), target.auth).text()), "message")
            gather.done = true
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget, compat: CompatFlags, stream: Boolean): JsonObject {
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        val built = fields(request, target, compat, stream, tools)
        return when (openAiToolReasoning(target, tools)) {
            "none" -> JsonObject(built + ("reasoning_effort" to JsonPrimitive("none")))
            "omit" -> JsonObject(built - "reasoning_effort")
            else -> built
        }
    }

    /**
     * OpenClaw's `applyOpenAICompletionsToolReasoning` (`packages/ai/src/providers/openai-reasoning-effort.ts`) on OpenAI's
     * and Azure's own endpoints: with tools, GPT-5.6 needs `reasoning_effort: "none"`, and GPT-5.4 mini and GPT-5.5 refuse
     * the field.
     */
    private fun openAiToolReasoning(target: ModelTarget, tools: Boolean): String? {
        val host = target.baseUrl.host
        val ownEndpoint = CompatDefaults.endpointClass(target.baseUrl) in setOf("openai-public", "azure-openai") ||
            host.endsWith(".services.ai.azure.com") || host.endsWith(".cognitiveservices.azure.com")
        if (!tools || !ownEndpoint) return null
        val date = Regex("-\\d{4}-\\d{2}-\\d{2}$")
        val id = target.sentModelId.lowercase().replace(date, "")
        val name = target.model.name.lowercase().replace(date, "")
        return when {
            Regex("^gpt-5\\.6(?:-|$)").containsMatchIn(id) || Regex("^gpt-5\\.6(?:\\s|\\(|-|$)").containsMatchIn(name) -> "none"
            Regex("^gpt-5\\.4-mini(?:-|$)").containsMatchIn(id) || Regex("^gpt-5\\.5(?:-|$)").containsMatchIn(id) ||
                Regex("^gpt-5\\.5(?:\\s|\\(|-|$)").containsMatchIn(name) -> "omit"
            else -> null
        }
    }

    private fun fields(request: ChatRequest, target: ModelTarget, compat: CompatFlags, stream: Boolean, tools: Boolean): JsonObject = buildJsonObject {
        put("model", target.sentModelId)
        put("messages", messages(request, target, compat, tools))
        if (tools) {
            putJsonArray("tools") {
                for (tool in request.tools) addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                        if (compat.supportsStrictMode == true) put("strict", false)
                    }
                }
            }
            put("tool_choice", when (request.toolChoice) {
                ToolChoice.AUTO -> "auto"
                ToolChoice.REQUIRED -> "required"
                ToolChoice.NONE -> "none"
            })
            put("parallel_tool_calls", false)
        }
        put(compat.maxTokensField ?: "max_completion_tokens", request.maxOutputTokens)
        if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
        reasoning(request, target, compat)
        val schema = request.jsonSchema
        if (schema != null && compat.supportsJsonSchemaResponseFormat == true) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "kestrel_action")
                    put("schema", schema)
                }
            }
        }
        if (compat.supportsStore == true) put("store", false)
        put("stream", stream)
        if (stream && compat.supportsUsageInStreaming == true) putJsonObject("stream_options") { put("include_usage", true) }
    }

    /** Design D5: the level goes into the field that the model's thinking format names. */
    private fun JsonObjectBuilder.reasoning(request: ChatRequest, target: ModelTarget, compat: CompatFlags) {
        val level = Wire.level(request, target.model, compat) ?: return
        val effort = Wire.effort(level, target.model, compat)
        val scalar = effort != null && compat.supportsReasoningEffort == true
        when (compat.thinkingFormat) {
            "openrouter" -> if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
            "zai" -> putJsonObject("thinking") {
                put("type", "enabled")
                put("clear_thinking", false)
            }
            "qwen" -> put("enable_thinking", true)
            "qwen-chat-template" -> putJsonObject("chat_template_kwargs") {
                put("enable_thinking", true)
                if (effort != null && !compat.supportedReasoningEfforts.isNullOrEmpty()) put("reasoning_effort", effort)
            }
            "deepseek" -> {
                putJsonObject("thinking") { put("type", "enabled") }
                if (scalar) put("reasoning_effort", effort)
            }
            "together" -> {
                putJsonObject("reasoning") { put("enabled", true) }
                if (scalar) put("reasoning_effort", effort)
            }
            else -> if (scalar) put("reasoning_effort", effort)
        }
    }

    private fun messages(request: ChatRequest, target: ModelTarget, compat: CompatFlags, tools: Boolean): JsonArray = buildJsonArray {
        if (request.system.isNotEmpty()) addJsonObject {
            put("role", if (compat.supportsDeveloperRole == true && target.model.reasoning) "developer" else "system")
            if (compat.cacheControlFormat == "anthropic") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", request.system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            } else {
                put("content", request.system)
            }
        }
        val list = request.messages
        for ((index, message) in list.withIndex()) {
            when (message) {
                is ChatMessage.User -> add(user(message, compat))
                is ChatMessage.Assistant -> add(assistant(message, target, compat, tools))
                is ChatMessage.ToolResult -> {
                    if (tools) {
                        addJsonObject {
                            put("role", "tool")
                            put("tool_call_id", message.callId)
                            if (compat.requiresToolResultName == true) put("name", message.name)
                            put("content", message.text)
                        }
                        if (compat.requiresAssistantAfterToolResult == true && list.getOrNull(index + 1) is ChatMessage.User) addJsonObject {
                            put("role", "assistant")
                            put("content", "OK.")
                        }
                    } else {
                        addJsonObject {
                            put("role", "user")
                            put("content", Wire.resultText(message))
                        }
                    }
                }
            }
        }
    }

    private fun user(message: ChatMessage.User, compat: CompatFlags): JsonObject = buildJsonObject {
        put("role", "user")
        val texts = message.parts.filterIsInstance<Part.Text>()
        val images = message.parts.filterIsInstance<Part.Image>()
        if (compat.requiresStringContent == true || (images.isEmpty() && texts.size == 1)) {
            put("content", texts.joinToString("\n") { it.text })
        } else {
            putJsonArray("content") {
                for (part in message.parts) addJsonObject {
                    when (part) {
                        is Part.Text -> {
                            put("type", "text")
                            put("text", part.text)
                        }
                        is Part.Image -> {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${part.type.mime};base64,${Wire.base64(part.bytes)}") }
                        }
                    }
                }
            }
        }
    }

    private fun assistant(message: ChatMessage.Assistant, target: ModelTarget, compat: CompatFlags, tools: Boolean): JsonObject = buildJsonObject {
        put("role", "assistant")
        val text = Wire.assistantText(message, callsAsText = !tools)
        val calls = if (tools) message.toolCalls else emptyList()
        val states = message.parts.filterIsInstance<AssistantPart.Reasoning>().map { it.state }
            .filter { it.style == RequestStyle.CHAT_COMPLETIONS && it.providerId == target.providerId }
        val signatures = states.filter { it.modelId == target.sentModelId }
            .mapNotNull { (it.payload as? JsonObject)?.obj("thoughtSignatures") }
            .flatMap { it.entries }.associate { it.key to it.value }
        when {
            text.isNotEmpty() -> put("content", text)
            calls.isEmpty() || compat.requiresStringContent == true -> put("content", "")
            else -> put("content", JsonNull)
        }
        if (calls.isNotEmpty()) putJsonArray("tool_calls") {
            for (call in calls) addJsonObject {
                put("id", call.id)
                put("type", "function")
                putJsonObject("function") {
                    put("name", call.name)
                    put("arguments", call.arguments.toString())
                }
                signatures[call.id]?.let { signature ->
                    putJsonObject("extra_content") { putJsonObject("google") { put("thought_signature", signature) } }
                }
            }
        }
        val reasoning = states.mapNotNull { it.payload as? JsonObject }.firstOrNull { it.str("text") != null }
            ?.takeIf { keepsReasoning(compat, target) }
        val required = compat.requiresReasoningContentOnAssistantMessages == true
        when {
            reasoning != null -> put(if (required) "reasoning_content" else reasoning.str("field") ?: "reasoning_content", reasoning.str("text"))
            required -> put("reasoning_content", "")
        }
    }

    /** Design D5: the plain reasoning text goes back to the same provider when one of OpenClaw's rules asks for it. */
    private fun keepsReasoning(compat: CompatFlags, target: ModelTarget): Boolean {
        val id = target.sentModelId.trim().lowercase()
        // OpenClaw's shouldPreserveOpenRouterReasoningReplay: OpenRouter's Claude and Grok routes get no plain reasoning back.
        val openRouterSigned = target.providerId == "openrouter" && (id.startsWith("anthropic/") || id.startsWith("x-ai/"))
        return compat.requiresReasoningContentOnAssistantMessages == true ||
            compat.thinkingFormat == "deepseek" || compat.thinkingFormat == "zai" ||
            replaysReasoning(target.sentModelId) ||
            (target.model.reasoning && target.providerId != "openai" && !openRouterSigned)
    }

    /** One reply, gathered from the chunks of a stream or from one complete body. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private class Call {
            var id = ""
            var name = ""
            val arguments = StringBuilder()
            var signature: String? = null
        }

        private val text = StringBuilder()
        private val reasoning = StringBuilder()
        private var reasoningField: String? = null
        private val calls = sortedMapOf<Int, Call>()
        private var finishReason: String? = null
        private var usage: JsonObject? = null
        private var modelId: String? = null
        private var lastKey = 0
        var done = false

        fun chunk(chunk: JsonObject, key: String) {
            chunk["error"]?.takeIf { it !is JsonNull }?.let { error ->
                val details = error as? JsonObject
                val status = details?.long("code")?.toInt() ?: details?.long("status")?.toInt()
                throw ProviderException(Wire.streamError(status, details?.str("code") ?: details?.str("type"), target))
            }
            chunk.str("model")?.let { modelId = it }
            chunk.obj("usage")?.let { usage = it }
            val choice = chunk.arr("choices").objects().firstOrNull() ?: return
            // OpenClaw: some compatible providers attach the usage to the choice instead of the chunk.
            choice.obj("usage")?.let { usage = it }
            choice.str("finish_reason")?.let { finishReason = it }
            val delta = choice.obj(key) ?: return
            delta.str("content")?.let { text.append(it) }
            // One field per delta: a server that fills two of them with the same text would double it.
            REASONING_FIELDS.firstOrNull { !delta.str(it).isNullOrEmpty() }?.let { field ->
                if (reasoningField == null) reasoningField = field
                reasoning.append(delta.str(field))
            }
            delta.arr("tool_calls").objects().forEachIndexed { position, piece ->
                val id = piece.str("id")?.takeIf { it.isNotEmpty() }
                // Some servers send no `index`: a new `id`, or a later piece of the same delta, starts a new call, and the
                // first piece of a delta without either continues the last call.
                val key = piece.long("index")?.toInt()
                    ?: id?.let { known -> calls.entries.firstOrNull { it.value.id == known }?.key }
                    ?: if (id != null || calls.isEmpty() || position > 0) (calls.keys.maxOrNull()?.plus(1) ?: position) else lastKey
                lastKey = key
                val call = calls.getOrPut(key) { Call() }
                if (id != null && call.id.isEmpty()) call.id = id
                piece.obj("function")?.let { function ->
                    function.str("name")?.let { if (call.name.isEmpty()) call.name = it }
                    function.str("arguments")?.let { call.arguments.append(it) }
                }
                // OpenClaw's extractToolCallThoughtSignature reads these three places.
                (piece.obj("extra_content")?.obj("google")?.str("thought_signature")
                    ?: piece.obj("function")?.str("thought_signature")
                    ?: piece.str("thought_signature"))?.takeIf { it.isNotEmpty() }?.let { call.signature = it }
            }
        }

        fun finish(): ChatResponse {
            if (!done && finishReason == null) throw ProviderException(ProviderError.Malformed)
            val reply = ReplyBuilder(request)
            val field = reasoningField
            if (field != null) {
                reply.reasoning(ReasoningState(RequestStyle.CHAT_COMPLETIONS, target.providerId, target.sentModelId, buildJsonObject {
                    put("field", field)
                    put("text", reasoning.toString())
                }))
            }
            reply.text(text.toString())
            val signatures = linkedMapOf<String, String>()
            calls.values.forEachIndexed { index, call ->
                val id = call.id.ifEmpty { "call_$index" }
                reply.call(id, call.name, call.arguments.toString())
                call.signature?.let { signatures[id] = it }
            }
            if (signatures.isNotEmpty()) {
                reply.reasoning(ReasoningState(RequestStyle.CHAT_COMPLETIONS, target.providerId, target.sentModelId, buildJsonObject {
                    putJsonObject("thoughtSignatures") { signatures.forEach { (id, signature) -> put(id, signature) } }
                }))
            }
            val stop = when (finishReason) {
                null, "stop" -> StopReason.END
                "tool_calls", "tool_call", "function_call" -> StopReason.TOOL_CALL
                "length" -> StopReason.MAX_TOKENS
                "content_filter" -> StopReason.FILTERED
                else -> StopReason.OTHER
            }
            val counted = usage?.let(::usageOf) ?: Wire.estimate(request, reply.replyChars, reasoning.length, target.model.reasoning)
            return reply.response(stop, counted, modelId ?: target.sentModelId)
        }

        /** Design D6, research r41. */
        private fun usageOf(usage: JsonObject): Usage {
            val prompt = usage.long("prompt_tokens") ?: 0
            val details = usage.obj("prompt_tokens_details")
            val cached = details?.long("cached_tokens") ?: 0
            val written = details?.long("cache_write_tokens") ?: 0
            return Usage(
                input = Wire.minus(prompt, cached, written),
                output = usage.long("completion_tokens") ?: 0,
                cacheRead = cached,
                cacheWrite = written,
                reasoning = usage.obj("completion_tokens_details")?.long("reasoning_tokens") ?: 0,
            )
        }
    }

    companion object {
        private val REASONING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

        /**
         * OpenClaw's list of models that need their reasoning text back (`openai-completions-reasoning-replay.ts` at the
         * pinned commit, research r38): the last path segment of the ID, also the parts around a `:`, with the tier
         * suffixes `-free`, `-paid` and `-trial` removed.
         */
        private val REPLAY_MODELS = setOf(
            "deepseek-v4-flash", "deepseek-v4-pro", "kimi-for-coding", "kimi-k2.5", "kimi-k2.6", "kimi-k2.7-code",
            "kimi-k2.7-code-highspeed", "kimi-k3", "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "kimi-k2-thinking",
            "kimi-k2-thinking-turbo", "mimo-v2-pro", "mimo-v2-omni", "mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.6-flash",
            "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed",
        )

        fun replaysReasoning(modelId: String): Boolean {
            val last = modelId.trim().lowercase().split('/').lastOrNull { it.isNotEmpty() } ?: return false
            val colon = last.split(':').filter { it.isNotEmpty() }
            val candidates = listOf(last) + if (colon.size > 1) listOf(colon.first(), colon.last()) else emptyList()
            return candidates.any { candidate ->
                candidate in REPLAY_MODELS || listOf("-free", "-paid", "-trial").any { suffix ->
                    candidate.length > suffix.length && candidate.endsWith(suffix) && candidate.removeSuffix(suffix) in REPLAY_MODELS
                }
            }
        }
    }
}
```

- [ ] **Step 7: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 19 tests in `:core:providers`: `ChatCompletionsAdapterTest` 12 and `CompatDefaultsTest` 7.

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 19; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit, whose report `core\providers\build\reports\kestrel-audit\network-apis.txt` lists no finding.

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/ChatCompletionsAdapter.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/CompatDefaults.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/AdapterHarness.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/ChatCompletionsAdapterTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/CompatDefaultsTest.kt core/providers/src/test/resources/fixtures/chat-completions
git commit -m "feat(providers): add the Chat Completions style with OpenClaw's compat defaults" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Responses

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/ResponsesAdapter.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/responses/` (14 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/ResponsesAdapterTest.kt`

**Interfaces:**
- Consumes: Task 1's `Wire`, `ReplyBuilder` and test bench `AdapterHarness`; plan 4A's format, `ModelTarget`, `StyleOptions.chatgptPlan` and `stream`, `ProviderHttp.stream` and `send`, `ProviderError` (`RateLimited`, `QuotaOrPolicy`, `BadRequest`, `SignInExpired`, `ServerError`, `Malformed`).
- Produces: `internal class ResponsesAdapter(http: ProviderHttp) : ChatAdapter` with `companion object { const val NAMESPACE = "kestrel"; fun planError(code: String?, target: ModelTarget): ProviderError? }`. Task 8's Azure adapter and Task 9's `Adapters.forStyle` use it.

Design D4 to D6, D17 and D19; research r38, r41 and r46. Responses is the style of OpenAI's API keys, the ChatGPT plan, xAI (its API keys and its sign-in's proxy), Meta, ClawRouter, Copilot's GPT models, Mantle's GPT models and Azure's GPT deployments. The adapter appends `responses` to the base, streams, and takes the reply from the final event `response.completed`, or from the `response.output_item.done` items when the final event's `output` is empty. `response.incomplete` is not an error: it is a reply with the output and usage that it has, and its stop reason comes from `incomplete_details.reason` (`max_output_tokens` → `MAX_TOKENS`, `content_filter` → `FILTERED`, anything else → `OTHER`), as OpenAI's "Models and inference" page asks (review A, round 3, R3-M6; Copilot's Responses models too). `response.failed` and the `error` event are mapped by their code (design D4). A stream without one of the three final events is `Malformed`. A closing `data: [DONE]`, which some servers send after `response.completed`, is ignored, as OpenAI's SDK ignores it, and an event whose data has no `type` takes the name of its SSE `event:` line.

The request always sends `store: false`, and `include: ["reasoning.encrypted_content"]` for a model that reasons and for the ChatGPT plan; OpenAI refuses that `include` for other models ("Encrypted content is not supported with this model"). Each output item of type `reasoning` whose `encrypted_content` is a string becomes a `ReasoningState` of style `RESPONSES` with the item minus its `id`, and goes back unchanged, in its place before the function calls, only to the same provider and model (design D5). When the provider answers 400 with the code `invalid_encrypted_content` or `thinking_signature_invalid`, the adapter sends the request once more without reasoning items; OpenClaw also reads the error's message (research r38 §2), Kestrel reads only the code. `prompt_cache_key` goes from `ChatRequest.cacheKey` only to the provider `openai` without the ChatGPT plan (design D4; research r39's `supportsPromptCacheKey`).

With `StyleOptions.chatgptPlan` (design D4, OpenAI's "Preview limitations", research r46): the system prompt goes as `instructions`, unless it is empty; `input` is always an array; `stream: true`; and none of the fields that the preview rejects are sent (`max_output_tokens`, `temperature` and `prompt_cache_key` among those that Kestrel would send otherwise; Kestrel never sends `background`, `conversation`, `max_tool_calls`, `metadata`, `moderation`, `multi_agent`, `prompt`, `prompt_cache_retention`, `safety_identifier`, `top_logprobs`, `top_p`, `truncation`, `user` or `previous_response_id`). Kestrel's function tools go in one namespace, `{"type": "namespace", "name": "kestrel", "description": "Kestrel's phone actions.", "tools": [ … ]}`, the shape of the section "Defining namespaces" of OpenAI's function-calling guide, and never as top-level function tools. How a call from a namespace comes back is shown in OpenAI's tool-search guide (`https://developers.openai.com/api/docs/guides/tools-tool-search`, section "Hosted tool search"): a `function_call` item with `"name": "<function>"` and `"namespace": "<namespace>"`. So the adapter maps a call whose `namespace` is `kestrel` (or absent) to the tool of that name, and replays Kestrel's earlier calls with `"namespace": "kestrel"`. A request without tools (JSON actions) sends no namespace.

The ChatGPT plan's error codes (design D17, OpenAI's "Errors and recovery"), both in `response.failed` inside the stream and in the `ProviderException` that `ProviderHttp` throws before the stream: `subscription_sharing_usage_limit_exceeded` → `QuotaOrPolicy(429)`; `subscription_sharing_user_not_eligible`, `subscription_sharing_route_not_supported`, `chatpass_v2_scope_not_authorized` and `chatpass_v2_invalid_authorization_context` → `QuotaOrPolicy(403)`; `subscription_sharing_invalid_user` → `SignInExpired`; `subscription_sharing_usage_unavailable` and `subscription_sharing_user_unavailable` → `ServerError(503)`; `subscription_sharing_unsupported_capability` → `BadRequest(400)`. Every 429 before the stream is `QuotaOrPolicy(429, code)`, also one without a code (`{"detail": "…"}`), because a plan's limit does not end within a retry and plan 4C's `ProfileClient` never retries a `QuotaOrPolicy`. This also covers a 429 that the plan's refresher meets at `auth.openai.com` and reports as `RateLimited`, which the adapter cannot tell apart ("Changes to the design").

- [ ] **Step 1: Write the fixtures**

These follow OpenAI's Responses streaming events (`https://developers.openai.com/api/reference/resources/responses/streaming-events`: `response.created`, `response.output_text.delta`, `response.output_item.done`, `response.completed`, `response.incomplete`, `response.failed` and `error`, each with its `type` repeated in the data; `done-line.sse` has an event without `type` and a closing `data: [DONE]`), the reasoning item with `encrypted_content` of research r38, and the usage fields of research r41. The ChatGPT plan's error events are built in the test from the table of design D17.

Create `core/providers/src/test/resources/fixtures/responses/done-line.sse`:

```text
event: response.output_item.done
data: {"sequence_number":1,"output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello, owner.","annotations":[]}]}}

event: response.completed
data: {"type":"response.completed","sequence_number":2,"response":{"id":"resp_1","object":"response","model":"gpt-4.1-2025-04-14","status":"completed","output":[]}}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/responses/ends-early.sse`:

```text
event: response.created
data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"in_progress","output":[]}}

event: response.output_text.delta
data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hello"}
```

Create `core/providers/src/test/resources/fixtures/responses/error-event.sse`:

```text
event: error
data: {"type":"error","sequence_number":1,"code":"rate_limit_exceeded","message":"Rate limit reached.","param":null}
```

Create `core/providers/src/test/resources/fixtures/responses/failed.sse`:

```text
event: response.created
data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"in_progress","output":[]}}

event: response.failed
data: {"type":"response.failed","sequence_number":1,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"failed","output":[],"error":{"code":"server_error","message":"The server had an error."}}}
```

Create `core/providers/src/test/resources/fixtures/responses/incomplete-filter.sse`:

```text
event: response.incomplete
data: {"type":"response.incomplete","sequence_number":1,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"incomplete","output":[],"usage":{"input_tokens":100,"output_tokens":3},"incomplete_details":{"reason":"content_filter"}}}
```

Create `core/providers/src/test/resources/fixtures/responses/incomplete-max-tokens.sse`:

```text
event: response.incomplete
data: {"type":"response.incomplete","sequence_number":1,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"incomplete","output":[{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"The list is long, so I","annotations":[]}]}],"usage":{"input_tokens":100,"output_tokens":256},"incomplete_details":{"reason":"max_output_tokens"}}}
```

Create `core/providers/src/test/resources/fixtures/responses/malformed.sse`:

```text
event: response.completed
data: {"type":"response.completed","response":
```

Create `core/providers/src/test/resources/fixtures/responses/no-usage.sse`:

```text
event: response.output_item.done
data: {"type":"response.output_item.done","sequence_number":0,"output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":null}}

event: response.output_item.done
data: {"type":"response.output_item.done","sequence_number":1,"output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello, owner.","annotations":[]}]}}

event: response.completed
data: {"type":"response.completed","sequence_number":2,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"completed","output":[]}}
```

Create `core/providers/src/test/resources/fixtures/responses/plan-tool-call.sse`:

```text
event: response.output_item.done
data: {"type":"response.output_item.done","sequence_number":1,"output_index":0,"item":{"id":"fc_call_p","type":"function_call","status":"completed","call_id":"call_p","name":"open_app","arguments":"{\"name\":\"Camera\"}","namespace":"kestrel"}}

event: response.completed
data: {"type":"response.completed","sequence_number":2,"response":{"id":"resp_1","object":"response","model":"gpt-6-luna","status":"completed","output":[{"id":"fc_call_p","type":"function_call","status":"completed","call_id":"call_p","name":"open_app","arguments":"{\"name\":\"Camera\"}","namespace":"kestrel"}],"usage":{"input_tokens":500,"output_tokens":20}}}
```

Create `core/providers/src/test/resources/fixtures/responses/plan.request.json`:

```json
{
  "model": "gpt-6-luna",
  "instructions": "You are Kestrel.",
  "input": [
    {
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "Open the camera."
        }
      ]
    }
  ],
  "tools": [
    {
      "type": "namespace",
      "name": "kestrel",
      "description": "Kestrel's phone actions.",
      "tools": [
        {
          "type": "function",
          "name": "open_app",
          "description": "Opens an app by its name.",
          "parameters": {
            "type": "object",
            "properties": {
              "name": {
                "type": "string",
                "description": "The app's name."
              }
            },
            "required": [
              "name"
            ]
          },
          "strict": false
        }
      ]
    }
  ],
  "tool_choice": "auto",
  "parallel_tool_calls": false,
  "reasoning": {
    "effort": "low"
  },
  "store": false,
  "include": [
    "reasoning.encrypted_content"
  ],
  "stream": true
}
```

Create `core/providers/src/test/resources/fixtures/responses/text.request.json`:

```json
{
  "model": "gpt-5.5",
  "input": [
    {
      "role": "system",
      "content": "You are Kestrel."
    },
    {
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "Hi"
        }
      ]
    }
  ],
  "tools": [
    {
      "type": "function",
      "name": "open_app",
      "description": "Opens an app by its name.",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {
            "type": "string",
            "description": "The app's name."
          }
        },
        "required": [
          "name"
        ]
      },
      "strict": false
    }
  ],
  "tool_choice": "auto",
  "parallel_tool_calls": false,
  "reasoning": {
    "effort": "low"
  },
  "max_output_tokens": 256,
  "prompt_cache_key": "kestrel-agent-v1",
  "store": false,
  "include": [
    "reasoning.encrypted_content"
  ],
  "stream": true
}
```

Create `core/providers/src/test/resources/fixtures/responses/text.sse`:

```text
event: response.created
data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"in_progress","output":[]}}

event: response.output_text.delta
data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hello, owner."}

event: response.output_item.done
data: {"type":"response.output_item.done","sequence_number":2,"output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello, owner.","annotations":[]}]}}

event: response.completed
data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"completed","output":[{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello, owner.","annotations":[]}]}],"usage":{"input_tokens":2000,"input_tokens_details":{"cached_tokens":1500,"cache_write_tokens":200},"output_tokens":50,"output_tokens_details":{"reasoning_tokens":30},"total_tokens":2050}}}
```

Create `core/providers/src/test/resources/fixtures/responses/tool-call.sse`:

```text
event: response.output_item.done
data: {"type":"response.output_item.done","sequence_number":1,"output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"gAAAAABpZXhhbXBsZS1lbmNyeXB0ZWQtcmVhc29uaW5n"}}

event: response.output_item.done
data: {"type":"response.output_item.done","sequence_number":2,"output_index":1,"item":{"id":"fc_call_1","type":"function_call","status":"completed","call_id":"call_1","name":"open_app","arguments":"{\"name\":\"Camera\"}"}}

event: response.completed
data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"completed","output":[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"gAAAAABpZXhhbXBsZS1lbmNyeXB0ZWQtcmVhc29uaW5n"},{"id":"fc_call_1","type":"function_call","status":"completed","call_id":"call_1","name":"open_app","arguments":"{\"name\":\"Camera\"}"}],"usage":{"input_tokens":400,"output_tokens":60,"output_tokens_details":{"reasoning_tokens":40}}}}
```

Create `core/providers/src/test/resources/fixtures/responses/two-calls.sse`:

```text
event: response.completed
data: {"type":"response.completed","sequence_number":1,"response":{"id":"resp_1","object":"response","model":"grok-4.3","status":"completed","output":[{"id":"fc_call_a","type":"function_call","status":"completed","call_id":"call_a","name":"open_app","arguments":"{\"name\":\"Camera\"}"},{"id":"fc_call_b","type":"function_call","status":"completed","call_id":"call_b","name":"open_app","arguments":"{\"name\":\"Clock\"}"}],"usage":{"input_tokens":300,"output_tokens":30}}}
```

- [ ] **Step 2: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/ResponsesAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = ResponsesAdapter(bench.http)

    private val openAi = target("openai", RequestStyle.RESPONSES, "https://api.openai.com/v1", ModelEntry("gpt-5.5", "GPT-5.5", reasoning = true))
    private val xai = target("xai", RequestStyle.RESPONSES, "https://api.x.ai/v1", ModelEntry("grok-4.3", "Grok 4.3", reasoning = true))
    private val plan = target(
        "openai", RequestStyle.RESPONSES, "https://api.openai.com/v1",
        ModelEntry(
            "gpt-6-luna", "GPT-6 Luna", reasoning = true,
            compat = CompatFlags(supportsReasoningEffort = true, supportedReasoningEfforts = listOf("none", "low", "medium", "high", "xhigh", "max"), supportsTemperature = false),
            thinkingLevelMap = mapOf("off" to "none", "minimal" to "low", "xhigh" to "xhigh", "max" to "max"),
        ),
        options = StyleOptions(chatgptPlan = true),
    )

    @After
    fun close() = bench.close()

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndOpenAiGetsThePromptCacheKey() = runBlocking {
        bench.reply("responses/text.sse")

        val response = adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.LOW, cacheKey = "kestrel-agent-v1") }, openAi)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 300, output = 50, cacheRead = 1500, cacheWrite = 200, reasoning = 30), response.usage)
        assertEquals("gpt-5.5-2026-08-01", response.modelId)
        assertEquals(listOf("https://api.openai.com/v1/responses".toHttpUrl()), bench.calls.requests)
        bench.assertBody("responses/text.request.json", bench.take())
    }

    @Test
    fun theEncryptedReasoningGoesBackWithoutItsIdBeforeTheCallAndItsOutput() = runBlocking {
        bench.reply("responses/tool-call.sse")
        bench.reply("responses/text.sse")

        val first = adapter.complete(request(user("Open the camera.")), openAi)

        val item = obj("""{"type":"reasoning","summary":[],"encrypted_content":"gAAAAABpZXhhbXBsZS1lbmNyeXB0ZWQtcmVhc29uaW5n"}""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.RESPONSES, "openai", "gpt-5.5", item)),
                AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("call_1", "open_app", "Opened.")), openAi)

        bench.take()
        val input = bench.body(bench.take())["input"]!!.jsonArray
        assertEquals(item, input[2])
        assertEquals(json("""{"type":"function_call","call_id":"call_1","name":"open_app","arguments":"{\"name\":\"Camera\"}"}"""), input[3])
        assertEquals(json("""{"type":"function_call_output","call_id":"call_1","output":"Opened."}"""), input[4])
    }

    @Test
    fun anotherModelGetsNoReasoningItemsAndXaiGetsNoCacheKey() = runBlocking {
        bench.reply("responses/two-calls.sse")
        bench.reply("responses/text.sse")

        val first = adapter.complete(request(user("Open the camera and the clock.")) { copy(cacheKey = "kestrel-agent-v1") }, xai)

        assertEquals(listOf("call_a", "call_b"), first.message.toolCalls.map { it.id })
        val history = listOf(
            user("Open the camera and the clock."),
            ChatMessage.Assistant(listOf(AssistantPart.Reasoning(ReasoningState(RequestStyle.RESPONSES, "xai", "grok-4.5", obj("""{"type":"reasoning"}""")))) + first.message.parts),
            ChatMessage.ToolResult("call_a", "open_app", "Opened."),
            ChatMessage.ToolResult("call_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        adapter.complete(request(*history.toTypedArray()), xai)

        assertNull(bench.body(bench.take())["prompt_cache_key"])
        val input = bench.body(bench.take())["input"]!!.jsonArray
        assertEquals(listOf("system", "user"), input.take(2).map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(
            listOf("function_call", "function_call", "function_call_output", "function_call_output"),
            input.drop(2).map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun anImageGoesAsAnInputImage() = runBlocking {
        bench.reply("responses/text.sse")

        adapter.complete(request(ChatMessage.User(listOf(Part.Text("Read this."), Part.Image(ImageType.JPEG, SCREENSHOT)))), openAi)

        val content = bench.body(bench.take())["input"]!!.jsonArray[1].jsonObject["content"]
        assertEquals(json("""[{"type":"input_text","text":"Read this."},{"type":"input_image","image_url":"data:image/jpeg;base64,iVBORw0KGgo="}]"""), content)
    }

    @Test
    fun aReplyWithoutUsageIsEstimatedFromItsItems() = runBlocking {
        bench.reply("responses/no-usage.sse")

        val response = adapter.complete(request(user("Hi")), openAi)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertTrue(response.usage.estimated)
    }

    @Test
    fun aClosingDoneLineAndAnEventNamedOnlyInItsSseLineAreReadAndAModelThatDoesNotReasonGetsNoInclude() = runBlocking {
        bench.reply("responses/done-line.sse")
        val gpt41 = target("openai", RequestStyle.RESPONSES, "https://api.openai.com/v1", ModelEntry("gpt-4.1", "GPT-4.1"))

        val response = adapter.complete(request(user("Hi")), gpt41)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertNull(bench.body(bench.take())["include"])
    }

    @Test
    fun anIncompleteResponseKeepsItsOutputAndStopsByItsReason() = runBlocking {
        bench.reply("responses/incomplete-max-tokens.sse")
        bench.reply("responses/incomplete-filter.sse")

        val long = adapter.complete(request(user("List everything.")), openAi)
        val filtered = adapter.complete(request(user("Something filtered.")), openAi)

        assertEquals(StopReason.MAX_TOKENS, long.stop)
        assertEquals(listOf(AssistantPart.Text("The list is long, so I")), long.message.parts)
        assertEquals(256L, long.usage.output)
        assertEquals(StopReason.FILTERED, filtered.stop)
    }

    @Test
    fun aRefusedEncryptedReasoningIsSentOnceMoreWithoutReasoningItems() = runBlocking {
        bench.status(400, """{"error":{"code":"invalid_encrypted_content","message":"could not decrypt"}}""")
        bench.reply("responses/text.sse")
        val state = ReasoningState(RequestStyle.RESPONSES, "openai", "gpt-5.5", obj("""{"type":"reasoning","encrypted_content":"x"}"""))
        val call = AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}"""))
        val history = request(user("Open the camera."), ChatMessage.Assistant(listOf(AssistantPart.Reasoning(state), call)), ChatMessage.ToolResult("call_1", "open_app", "Opened."))

        val response = adapter.complete(history, openAi)

        assertEquals(StopReason.END, response.stop)
        assertEquals(5, bench.body(bench.take())["input"]!!.jsonArray.size)
        assertEquals(4, bench.body(bench.take())["input"]!!.jsonArray.size)
    }

    @Test
    fun theStatusErrorsAndTheErrorsInsideTheStreamAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"error":{"code":"rate_limit_exceeded"}}""", "Retry-After" to "3")
        bench.status(500)
        bench.reply("responses/failed.sse")
        bench.reply("responses/error-event.sse")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), openAi.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(3_000, "rate_limit_exceeded"), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.RateLimited(null, "rate_limit_exceeded"), errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun aMalformedEventAndAStreamWithoutItsFinalEventAreMalformed() {
        bench.reply("responses/malformed.sse")
        bench.reply("responses/ends-early.sse")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun theChatGptPlanSendsItsInstructionsAndTheToolsInTheKestrelNamespaceAndNoRejectedField() = runBlocking {
        bench.reply("responses/plan-tool-call.sse")

        val response = adapter.complete(
            request(user("Open the camera.")) { copy(reasoning = ReasoningLevel.LOW, temperature = 0.2, cacheKey = "kestrel-agent-v1") },
            plan,
        )

        assertEquals(listOf(AssistantPart.ToolCall("call_p", "open_app", obj("""{"name":"Camera"}"""))), response.message.parts)
        bench.assertBody("responses/plan.request.json", bench.take())
    }

    @Test
    fun theChatGptPlanReplaysANamespacedCallWithItsNamespace() = runBlocking {
        bench.reply("responses/text.sse")
        val call = AssistantPart.ToolCall("call_p", "open_app", obj("""{"name":"Camera"}"""))

        adapter.complete(
            request(user("Open the camera."), ChatMessage.Assistant(listOf(call)), ChatMessage.ToolResult("call_p", "open_app", "Opened.")) { copy(system = "") },
            plan,
        )

        val body = bench.body(bench.take())
        assertNull(body["instructions"])
        val input = body["input"]!!.jsonArray
        assertEquals(json("""{"type":"function_call","call_id":"call_p","name":"open_app","namespace":"kestrel","arguments":"{\"name\":\"Camera\"}"}"""), input[1])
    }

    @Test
    fun eachChatGptPlanCodeInsideTheStreamMapsAsDesignD17Says() {
        val expected = mapOf(
            "subscription_sharing_usage_limit_exceeded" to ProviderError.QuotaOrPolicy(429, "subscription_sharing_usage_limit_exceeded"),
            "subscription_sharing_user_not_eligible" to ProviderError.QuotaOrPolicy(403, "subscription_sharing_user_not_eligible"),
            "subscription_sharing_route_not_supported" to ProviderError.QuotaOrPolicy(403, "subscription_sharing_route_not_supported"),
            "chatpass_v2_scope_not_authorized" to ProviderError.QuotaOrPolicy(403, "chatpass_v2_scope_not_authorized"),
            "chatpass_v2_invalid_authorization_context" to ProviderError.QuotaOrPolicy(403, "chatpass_v2_invalid_authorization_context"),
            "subscription_sharing_invalid_user" to ProviderError.SignInExpired("openai"),
            "subscription_sharing_usage_unavailable" to ProviderError.ServerError(503),
            "subscription_sharing_user_unavailable" to ProviderError.ServerError(503),
            "subscription_sharing_unsupported_capability" to ProviderError.BadRequest(400, "subscription_sharing_unsupported_capability"),
        )
        for ((code, error) in expected) {
            bench.server.enqueue(
                MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                    .body(sse("""{"type":"response.failed","response":{"status":"failed","error":{"code":"$code","message":"Usage limit."}}}""")).build(),
            )
            assertEquals(code, error, errorOf(adapter, request(user("Hi")), plan))
        }
    }

    @Test
    fun everyChatGptPlan429BeforeTheStreamIsAPlanLimitWithOrWithoutACode() {
        bench.status(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Limit."}}""")
        bench.status(429, """{"detail":"Usage limit reached."}""")
        bench.status(403, """{"error":{"code":"subscription_sharing_user_not_eligible"}}""")

        assertEquals(ProviderError.QuotaOrPolicy(429, "subscription_sharing_usage_limit_exceeded"), errorOf(adapter, request(user("Hi")), plan))
        assertEquals(ProviderError.QuotaOrPolicy(429, null), errorOf(adapter, request(user("Hi")), plan))
        assertEquals(ProviderError.QuotaOrPolicy(403, "subscription_sharing_user_not_eligible"), errorOf(adapter, request(user("Hi")), plan))
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ResponsesAdapter'".

- [ ] **Step 4: Write the adapter**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/ResponsesAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's Responses style (spec 10.3, design D4, D5, D6 and D17), used by OpenAI's API keys, the ChatGPT plan, xAI,
 * Meta, Copilot's GPT models, Mantle's GPT models and Azure's GPT deployments. Requests send `store: false` and ask for
 * the encrypted reasoning, which goes back unchanged (without its `id`) to the same provider and model. The reply comes
 * from the stream's `response.completed` (its `output_item.done` items when the final event has no output);
 * `response.incomplete` is a reply that stopped early. A 400 with `invalid_encrypted_content` or
 * `thinking_signature_invalid` is sent once more without reasoning items.
 *
 * With [dev.kestrel.providers.chat.StyleOptions.chatgptPlan] the request follows the ChatGPT plan's preview limits (OpenAI's
 * "Preview limitations", research r46): `instructions`, `input` as an array, `store: false`, `stream: true`, none of the
 * fields that the preview rejects, and Kestrel's function tools in one namespace `kestrel`. Its error codes map as design
 * D17 says, inside the stream and before it, and every 429 is QuotaOrPolicy, because a plan's limit does not end within a
 * retry.
 */
internal class ResponsesAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        return try {
            send(request, target)
        } catch (e: ProviderException) {
            val error = e.error
            if (error is ProviderError.BadRequest && error.code in STALE_REASONING && hasReasoning(request)) send(withoutReasoning(request), target) else throw e
        }
    }

    private suspend fun send(request: ChatRequest, target: ModelTarget): ChatResponse {
        val plan = target.options.chatgptPlan
        val stream = plan || target.options.stream != false
        val url = target.baseUrl.newBuilder().addPathSegment("responses").build()
        val gather = Gather(request, target)
        try {
            val post = Wire.post(url, body(request, target, stream), target, request, stream)
            if (stream) {
                http.stream(post, target.auth) { event ->
                    // OpenAI's SDK ignores a closing `data: [DONE]`, which some servers send after response.completed.
                    if (event.data.trim() != "[DONE]") gather.event(Wire.parse(event.data), event.event)
                }
            } else {
                gather.final(Wire.parse(http.send(post, target.auth).text()))
            }
        } catch (e: ProviderException) {
            if (!plan) throw e
            val error = e.error
            throw ProviderException(
                when (error) {
                    is ProviderError.RateLimited -> ProviderError.QuotaOrPolicy(429, error.code)
                    is ProviderError.QuotaOrPolicy -> planError(error.code, target) ?: error
                    is ProviderError.BadRequest -> planError(error.code, target) ?: error
                    else -> error
                },
            )
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget, stream: Boolean): JsonObject = buildJsonObject {
        val plan = target.options.chatgptPlan
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        put("model", target.sentModelId)
        if (plan && request.system.isNotEmpty()) put("instructions", request.system)
        put("input", input(request, target, tools))
        if (tools) {
            val functions = buildJsonArray {
                for (tool in request.tools) addJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                    if (compat.supportsStrictMode != false) put("strict", false)
                }
            }
            if (plan) {
                putJsonArray("tools") {
                    addJsonObject {
                        put("type", "namespace")
                        put("name", NAMESPACE)
                        put("description", "Kestrel's phone actions.")
                        put("tools", functions)
                    }
                }
            } else {
                put("tools", functions)
            }
            put("tool_choice", when (request.toolChoice) {
                ToolChoice.AUTO -> "auto"
                ToolChoice.REQUIRED -> "required"
                ToolChoice.NONE -> "none"
            })
            put("parallel_tool_calls", false)
        }
        val level = Wire.level(request, target.model, compat)
        val effort = level?.let { Wire.effort(it, target.model, compat) }
        if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
        if (!plan) {
            put("max_output_tokens", request.maxOutputTokens)
            if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
            val cacheKey = request.cacheKey
            if (cacheKey != null && target.providerId == "openai" && compat.supportsPromptCacheKey != false) put("prompt_cache_key", cacheKey)
        }
        put("store", false)
        // OpenAI refuses this include for a model that does not reason ("Encrypted content is not supported with this model").
        if (target.model.reasoning || plan) putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
        put("stream", stream)
    }

    private fun input(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray = buildJsonArray {
        val plan = target.options.chatgptPlan
        if (!plan && request.system.isNotEmpty()) addJsonObject {
            put("role", "system")
            put("content", request.system)
        }
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        for (part in message.parts) addJsonObject {
                            when (part) {
                                is Part.Text -> {
                                    put("type", "input_text")
                                    put("text", part.text)
                                }
                                is Part.Image -> {
                                    put("type", "input_image")
                                    put("image_url", "data:${part.type.mime};base64,${Wire.base64(part.bytes)}")
                                }
                            }
                        }
                    }
                }
                is ChatMessage.Assistant -> assistant(message, target, tools)
                is ChatMessage.ToolResult -> if (tools) {
                    addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message.callId)
                        put("output", message.text)
                    }
                } else {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "input_text")
                                put("text", Wire.resultText(message))
                            }
                        }
                    }
                }
            }
        }
    }

    /** The assistant's items in their order: its reasoning items, its text as one message, and its function calls. */
    private fun JsonArrayBuilder.assistant(message: ChatMessage.Assistant, target: ModelTarget, tools: Boolean) {
        for (part in message.parts) {
            if (part !is AssistantPart.Reasoning) continue
            val state = part.state
            if (state.style == RequestStyle.RESPONSES && state.providerId == target.providerId && state.modelId == target.sentModelId) {
                (state.payload as? JsonObject)?.let { add(it) }
            }
        }
        val text = Wire.assistantText(message, callsAsText = !tools)
        if (text.isNotEmpty()) addJsonObject {
            put("type", "message")
            put("role", "assistant")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "output_text")
                    put("text", text)
                }
            }
        }
        if (tools) for (call in message.toolCalls) addJsonObject {
            put("type", "function_call")
            put("call_id", call.id)
            put("name", call.name)
            if (target.options.chatgptPlan) put("namespace", NAMESPACE)
            put("arguments", call.arguments.toString())
        }
    }

    private fun hasReasoning(request: ChatRequest): Boolean =
        request.messages.any { it is ChatMessage.Assistant && it.parts.any { part -> part is AssistantPart.Reasoning } }

    private fun withoutReasoning(request: ChatRequest): ChatRequest =
        request.copy(messages = request.messages.map { message ->
            if (message is ChatMessage.Assistant) ChatMessage.Assistant(message.parts.filterNot { it is AssistantPart.Reasoning }) else message
        })

    /** One reply, gathered from the stream's events or from one complete response object. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private val items = mutableListOf<JsonObject>()
        private var response: JsonObject? = null
        private var stop: StopReason? = null
        private var reasoningChars = 0

        /** One event; a server that leaves out `type` names the event in the SSE `event:` line instead. */
        fun event(event: JsonObject, name: String? = null) {
            when (event.str("type") ?: name) {
                "response.output_item.done" -> event.obj("item")?.let { items += it }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> reasoningChars += event.str("delta")?.length ?: 0
                "response.completed" -> final(event.obj("response") ?: JsonObject(emptyMap()))
                "response.incomplete" -> final(event.obj("response") ?: JsonObject(emptyMap()), incomplete = true)
                "response.failed" -> {
                    val error = event.obj("response")?.obj("error")
                    throw ProviderException(failure(error?.str("code")))
                }
                "error" -> throw ProviderException(failure(event.str("code") ?: event.obj("error")?.str("code")))
            }
        }

        /** A final reply; an incomplete one keeps its output and usage and stops by its reason (OpenAI's "Models and inference"). */
        fun final(reply: JsonObject, incomplete: Boolean = reply.str("status") == "incomplete") {
            response = reply
            stop = if (!incomplete) StopReason.END else when (reply.obj("incomplete_details")?.str("reason")) {
                "max_output_tokens" -> StopReason.MAX_TOKENS
                "content_filter" -> StopReason.FILTERED
                else -> StopReason.OTHER
            }
        }

        private fun failure(code: String?): ProviderError =
            (if (target.options.chatgptPlan) planError(code, target) else null) ?: Wire.streamError(null, code, target)

        fun finish(): ChatResponse {
            val reply = response ?: throw ProviderException(ProviderError.Malformed)
            val output = reply.arr("output").objects().ifEmpty { items }
            val builder = ReplyBuilder(request)
            for (item in output) {
                when (item.str("type")) {
                    "reasoning" -> if (item.str("encrypted_content") != null) {
                        builder.reasoning(ReasoningState(RequestStyle.RESPONSES, target.providerId, target.sentModelId, JsonObject(item - "id")))
                        reasoningChars += item.arr("summary").objects().sumOf { it.str("text")?.length ?: 0 }
                    }
                    "message" -> for (content in item.arr("content").objects()) {
                        content.str("text")?.let(builder::text)
                        content.str("refusal")?.let(builder::text)
                    }
                    "function_call" -> {
                        val namespace = item.str("namespace")
                        val name = item.str("name").orEmpty()
                        builder.call(item.str("call_id") ?: item.str("id").orEmpty(), if (namespace == null || namespace == NAMESPACE) name else "$namespace.$name", item.str("arguments").orEmpty())
                    }
                }
            }
            val usage = reply.obj("usage")?.let(::usageOf) ?: Wire.estimate(request, builder.replyChars, reasoningChars, target.model.reasoning)
            return builder.response(stop ?: StopReason.END, usage, reply.str("model") ?: target.sentModelId)
        }

        /** Design D6, research r41. */
        private fun usageOf(usage: JsonObject): Usage {
            val input = usage.long("input_tokens") ?: 0
            val details = usage.obj("input_tokens_details")
            val cached = details?.long("cached_tokens") ?: 0
            val written = details?.long("cache_write_tokens") ?: 0
            return Usage(
                input = Wire.minus(input, cached, written),
                output = usage.long("output_tokens") ?: 0,
                cacheRead = cached,
                cacheWrite = written,
                reasoning = usage.obj("output_tokens_details")?.long("reasoning_tokens") ?: 0,
            )
        }
    }

    companion object {
        const val NAMESPACE = "kestrel"

        private val STALE_REASONING = setOf("invalid_encrypted_content", "thinking_signature_invalid")

        /** The ChatGPT plan's error codes (design D17; OpenAI's "Errors and recovery", research r46), or null for another code. */
        fun planError(code: String?, target: ModelTarget): ProviderError? = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> ProviderError.QuotaOrPolicy(429, code)
            "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported",
            "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> ProviderError.QuotaOrPolicy(403, code)
            "subscription_sharing_invalid_user" -> ProviderError.SignInExpired(target.providerId)
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ProviderError.ServerError(503)
            "subscription_sharing_unsupported_capability" -> ProviderError.BadRequest(400, code)
            else -> null
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 33 tests in `:core:providers`: Task 1's 19 and `ResponsesAdapterTest` 14.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 33; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit without a finding.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/ResponsesAdapter.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/ResponsesAdapterTest.kt core/providers/src/test/resources/fixtures/responses
git commit -m "feat(providers): add the Responses style with encrypted reasoning and the ChatGPT plan's form and errors" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Anthropic Messages

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/AnthropicAdapter.kt`, `AnthropicModels.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/anthropic/` (11 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AnthropicAdapterTest.kt`

**Interfaces:**
- Consumes: Task 1's `Wire`, `ReplyBuilder` and `AdapterHarness`; plan 4A's format, `StyleOptions.stripThinking`, `sanitizeToolCallIds`, `messagesPath` and `stream`, `ApiKeyAuth` with `KeyHeader.X_API_KEY`.
- Produces: `internal class AnthropicAdapter(http: ProviderHttp) : ChatAdapter` with `suspend fun complete(request, target, url: HttpUrl, vertex: Boolean, logPath: String?): ChatResponse` (the Vertex form, for Task 7) and `companion object { const val VERSION = "2023-06-01"; const val VERTEX_VERSION = "vertex-2023-10-16" }`; `internal object AnthropicModels` with `identity(modelId): String?`, `adaptive(modelId)`, `thinksByDefault(modelId)`, `keepsForcedToolChoice(modelId)`, `defaultSamplingOnly(modelId)` and `effort(level, thinkingLevelMap)`. Tasks 6, 7, 8 and 9 use them.

Design D4 to D6 and D19; research r33, r38 and r41. Anthropic Messages is the style of Anthropic, MiniMax, Synthetic, Vercel's gateway, Copilot's Claude models, Mantle's Claude models, Claude on Vertex and Claude deployments on Foundry. The adapter appends `StyleOptions.messagesPath` to the base (`/v1/messages` by default, `/messages` for Copilot, design D12), sends `anthropic-version: 2023-06-01` (design D4 keeps the value of OpenClaw's working paths), streams, and gathers Anthropic's events: `message_start` (the model and the input usage), `content_block_start`, `content_block_delta` (`text_delta`, `thinking_delta`, `signature_delta`, `input_json_delta`), `message_delta` (the stop reason and the usage, which is cumulative and laid over that of `message_start`, because some compatible providers report their input only there), `message_stop` (the final event) and `error`. A stream without `message_stop` is `Malformed`, and a proxy's closing `data: [DONE]` or anything else after `message_stop` is ignored; a Vertex message without a `content` array is `Malformed` too; the `error` event is mapped by its `type`, with `overloaded_error` as `ServerError(529)` (design D4).

Every `thinking` block with its `signature` and every `redacted_thinking` block with its `data` becomes a `ReasoningState` of style `ANTHROPIC`, kept in its order, and goes back unchanged before the `tool_use` blocks of the same assistant message, only to the same provider and model (design D5, research r38 §1). With `StyleOptions.stripThinking` (Copilot, design D12) the adapter asks for no thinking and keeps no blocks, also for a model that thinks by default, as OpenClaw's Copilot replay policy does (`extensions/github-copilot/replay-policy.ts`); adaptive thinking does not need them back. Tool results go back as `tool_result` blocks; consecutive results and the next user message become one user turn, results first. Blank text blocks are left out, because Anthropic refuses them. `sanitizeToolCallIds` rewrites every tool-use ID in the request to `^[a-zA-Z0-9_-]{1,64}$`, and two IDs that become the same get `_2`, `_3` and so on, as OpenClaw's `normalizeCopilotAnthropicToolIds` does for Copilot.

Reasoning (design D5): when the model reasons, the request asks for a level, and `AnthropicModels.adaptive` says that the model takes adaptive thinking, the request sends `thinking: {type: "adaptive"}` and `output_config: {effort}`, the effort coming from the model's `thinkingLevelMap` or OpenClaw's default (`minimal` and `low` are `low`). `AnthropicModels` ports OpenClaw's rules (`packages/llm-core/src/model-contracts/anthropic.ts` at the pinned commit, which `packages/ai/src/transports/anthropic-messages.ts` uses): every Claude model from Opus and Sonnet 4.6 on takes adaptive thinking. `AnthropicModels.thinksByDefault` is OpenClaw's `defaultsClaudeAdaptiveThinking` (`packages/ai/src/providers/anthropic-model-contract.ts` at e6113549, its mandatory-thinking rule or its Claude 5 request contract): every Claude model after Claude 4.x, which names Sonnet 5, Opus 5, Fable 5, Mythos 5, the Mythos preview and the later ones; they think even when the request does not ask, also on Copilot's route. Other models get no thinking, because Kestrel does not use the manual budget, which Claude 4.7 and later refuse (research r38). While the model thinks, and always for a model that thinks by default, `ToolChoice.REQUIRED` goes as `auto`, as OpenClaw sends it: Anthropic refuses a forced choice with manual thinking and on Opus 5.5, Sonnet 5.5 and the Fable and Mythos models from 5.1 on (research r33 and Anthropic's "Thinking" page). Claude Haiku 5.5 keeps `any` (`AnthropicModels.keepsForcedToolChoice`), as OpenClaw keeps it. No temperature is sent then, because every Claude 5 model refuses one. Otherwise a temperature goes only to models that still take one (OpenClaw's `requiresClaudeDefaultSampling`: not Opus 4.7 and later). `disable_parallel_tool_use: true` goes with `auto` and `any` (design D3).

Cache markers (design D4): the system prompt and the last tool carry `cache_control: {type: "ephemeral"}`, the provider's default lifetime. Usage (design D6): Anthropic's `input_tokens` already excludes the cache, so it is `input`, with `cache_read_input_tokens` and `cache_creation_input_tokens` beside it.

The Vertex form (design D4, research r35) is `complete(request, target, url, vertex = true, logPath)`: no streaming, `anthropic_version: "vertex-2023-10-16"` in the body instead of the model, and no `anthropic-version` header. Task 7's Vertex adapter gives it the `rawPredict` address and the log path.

- [ ] **Step 1: Write the fixtures**

These follow Anthropic's streaming events (`https://platform.claude.com/docs/en/build-with-claude/streaming`; `late-usage.sse` reports its input only in `message_delta` and ends with a proxy's `data: [DONE]`), the thinking and redacted-thinking blocks of research r38 §1, the usage fields of research r41, and a complete message as Vertex's `rawPredict` returns it (research r35 §5).

Create `core/providers/src/test/resources/fixtures/anthropic/copilot-tool-call.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4.5","content":[],"stop_reason":null,"usage":{"input_tokens":200,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"c2ln"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_c","name":"open_app","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"name\":\"Camera\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":20}}

event: message_stop
data: {"type":"message_stop"}
```

Create `core/providers/src/test/resources/fixtures/anthropic/ends-early.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],"stop_reason":null,"usage":{"input_tokens":10,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
```

Create `core/providers/src/test/resources/fixtures/anthropic/late-usage.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"MiniMax-M3","content":[]}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, owner."}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":120,"cache_read_input_tokens":800,"cache_creation_input_tokens":null,"output_tokens":9}}

event: message_stop
data: {"type":"message_stop"}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/anthropic/malformed.sse`:

```text
event: message_start
data: {"type":"message_start","message":{
```

Create `core/providers/src/test/resources/fixtures/anthropic/no-usage.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"MiniMax-M3","content":[]}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, owner."}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"sensitive"}}

event: message_stop
data: {"type":"message_stop"}
```

Create `core/providers/src/test/resources/fixtures/anthropic/overloaded.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],"stop_reason":null,"usage":{"input_tokens":10,"output_tokens":1}}}

event: error
data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
```

Create `core/providers/src/test/resources/fixtures/anthropic/text.request.json`:

```json
{
  "model": "claude-sonnet-5",
  "max_tokens": 256,
  "system": [
    {
      "type": "text",
      "text": "You are Kestrel.",
      "cache_control": {
        "type": "ephemeral"
      }
    }
  ],
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "Hi"
        }
      ]
    }
  ],
  "tools": [
    {
      "name": "open_app",
      "description": "Opens an app by its name.",
      "input_schema": {
        "type": "object",
        "properties": {
          "name": {
            "type": "string",
            "description": "The app's name."
          }
        },
        "required": [
          "name"
        ]
      },
      "cache_control": {
        "type": "ephemeral"
      }
    }
  ],
  "tool_choice": {
    "type": "auto",
    "disable_parallel_tool_use": true
  },
  "thinking": {
    "type": "adaptive"
  },
  "output_config": {
    "effort": "low"
  },
  "stream": true
}
```

Create `core/providers/src/test/resources/fixtures/anthropic/text.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],"stop_reason":null,"usage":{"input_tokens":50,"cache_creation_input_tokens":1000,"cache_read_input_tokens":2000,"output_tokens":1}}}

event: ping
data: {"type":"ping"}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, "}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"owner."}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":42}}

event: message_stop
data: {"type":"message_stop"}
```

Create `core/providers/src/test/resources/fixtures/anthropic/thinking-tool-call.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],"stop_reason":null,"usage":{"input_tokens":400,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"The owner wants the camera."}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EosnCkYICxIMMb3LzNrMu"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"EmwKAhgBEgy3va3pzix"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: content_block_start
data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_01","name":"open_app","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"name\":"}}

event: content_block_delta
data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":" \"Camera\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":2}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":80}}

event: message_stop
data: {"type":"message_stop"}
```

Create `core/providers/src/test/resources/fixtures/anthropic/two-calls.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],"stop_reason":null,"usage":{"input_tokens":300,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_a","name":"open_app","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"name\":\"Camera\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_b","name":"open_app","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"name\":\"Clock\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":40}}

event: message_stop
data: {"type":"message_stop"}
```

Create `core/providers/src/test/resources/fixtures/anthropic/vertex-message.json`:

```json
{
  "id": "msg_v",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-6",
  "content": [
    {
      "type": "text",
      "text": "Hello from Vertex."
    }
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 30,
    "cache_read_input_tokens": 0,
    "cache_creation_input_tokens": 0,
    "output_tokens": 6
  }
}
```

- [ ] **Step 2: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AnthropicAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = AnthropicAdapter(bench.http)

    private val sonnet = ModelEntry("claude-sonnet-5", "Claude Sonnet 5", reasoning = true, thinkingLevelMap = mapOf("xhigh" to "xhigh", "max" to "max"))
    private val anthropic = target("anthropic", RequestStyle.ANTHROPIC, "https://api.anthropic.com", sonnet, ApiKeyAuth("test-key", KeyHeader.X_API_KEY))

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndTheRequestHasThinkingAndCacheMarkers() = runBlocking {
        bench.reply("anthropic/text.sse")

        val response = adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.LOW) }, anthropic)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 50, output = 42, cacheRead = 2000, cacheWrite = 1000), response.usage)
        assertEquals(listOf("https://api.anthropic.com/v1/messages".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        assertEquals("test-key", recorded.headers["x-api-key"])
        bench.assertBody("anthropic/text.request.json", recorded)
    }

    @Test
    fun thinkingBlocksGoBackUnchangedAndInOrderBeforeTheToolUse() = runBlocking {
        bench.reply("anthropic/thinking-tool-call.sse")
        bench.reply("anthropic/text.sse")

        val first = adapter.complete(request(user("Open the camera.")) { copy(reasoning = ReasoningLevel.LOW) }, anthropic)

        val thinking = obj("""{"type":"thinking","thinking":"The owner wants the camera.","signature":"EosnCkYICxIMMb3LzNrMu"}""")
        val redacted = obj("""{"type":"redacted_thinking","data":"EmwKAhgBEgy3va3pzix"}""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-sonnet-5", thinking)),
                AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-sonnet-5", redacted)),
                AssistantPart.ToolCall("toolu_01", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("toolu_01", "open_app", "Opened.")), anthropic)

        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(
            json("""[$thinking,$redacted,{"type":"tool_use","id":"toolu_01","name":"open_app","input":{"name":"Camera"}}]"""),
            messages[1].jsonObject["content"],
        )
        assertEquals(json("""[{"type":"tool_result","tool_use_id":"toolu_01","content":"Opened."}]"""), messages[2].jsonObject["content"])
    }

    @Test
    fun twoToolResultsAndTheNextScreenBecomeOneUserTurn() = runBlocking {
        bench.reply("anthropic/two-calls.sse")
        bench.reply("anthropic/text.sse")

        val first = adapter.complete(request(user("Open the camera and the clock.")), anthropic)

        assertEquals(listOf("toolu_a", "toolu_b"), first.message.toolCalls.map { it.id })
        val next = request(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("toolu_a", "open_app", "Opened."),
            ChatMessage.ToolResult("toolu_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
            ChatMessage.User(listOf(Part.Text("The screen now."), Part.Text(" \n"), Part.Image(ImageType.PNG, SCREENSHOT))),
        ) { copy(system = " ") }
        adapter.complete(next, anthropic)

        bench.take()
        val body = bench.body(bench.take())
        // Anthropic refuses blank text blocks, so the blank system prompt and the blank text are left out.
        assertNull(body["system"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals(
            json(
                """[{"type":"tool_result","tool_use_id":"toolu_a","content":"Opened."},
                {"type":"tool_result","tool_use_id":"toolu_b","content":"Not run: Kestrel runs one action per step","is_error":true},
                {"type":"text","text":"The screen now."},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}]""",
            ),
            messages[2].jsonObject["content"],
        )
    }

    @Test
    fun aRequiredToolBecomesAutoAndNoTemperatureIsSentWhileTheModelThinks() = runBlocking {
        repeat(5) { bench.reply("anthropic/text.sse") }
        val sonnet46 = anthropic.copy(model = ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", reasoning = true), sentModelId = "claude-sonnet-4-6")
        val opus48 = anthropic.copy(model = ModelEntry("claude-opus-4-8", "Claude Opus 4.8", reasoning = true), sentModelId = "claude-opus-4-8")
        val haiku55 = anthropic.copy(model = ModelEntry("claude-haiku-5.5", "Claude Haiku 5.5", reasoning = true), sentModelId = "claude-haiku-5.5")

        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, sonnet46)
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2, reasoning = ReasoningLevel.HIGH) }, sonnet46)
        // Claude Sonnet 5 thinks by default, so also a request that does not ask for thinking gets `auto` and no temperature.
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, anthropic)
        adapter.complete(request(user("Hi")) { copy(temperature = 0.2) }, opus48)
        // OpenClaw keeps a forced tool choice for Claude Haiku 5.5, which Anthropic accepts while it thinks.
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, haiku55)

        val plain = bench.body(bench.take())
        assertEquals(json("""{"type":"any","disable_parallel_tool_use":true}"""), plain["tool_choice"])
        assertEquals(json("0.2"), plain["temperature"])
        val thinking = bench.body(bench.take())
        assertEquals(json("""{"type":"auto","disable_parallel_tool_use":true}"""), thinking["tool_choice"])
        assertNull(thinking["temperature"])
        val byDefault = bench.body(bench.take())
        assertEquals(json("""{"type":"auto","disable_parallel_tool_use":true}"""), byDefault["tool_choice"])
        assertNull(byDefault["thinking"])
        assertNull(byDefault["temperature"])
        assertNull(bench.body(bench.take())["temperature"])
        val haiku = bench.body(bench.take())
        assertEquals(json("""{"type":"any","disable_parallel_tool_use":true}"""), haiku["tool_choice"])
        assertNull(haiku["temperature"])
    }

    @Test
    fun copilotStripsThinkingRewritesIdsAndUsesItsMessagesPath() = runBlocking {
        bench.reply("anthropic/copilot-tool-call.sse")
        bench.reply("anthropic/text.sse")
        val copilot = target(
            "github-copilot", RequestStyle.ANTHROPIC, "https://api.individual.githubcopilot.com",
            ModelEntry("claude-haiku-4.5", "Claude Haiku 4.5", reasoning = true),
            options = StyleOptions(stripThinking = true, sanitizeToolCallIds = true, messagesPath = "/messages"),
        )

        val first = adapter.complete(request(user("Open the camera.")) { copy(reasoning = ReasoningLevel.LOW) }, copilot)

        assertEquals(listOf(AssistantPart.ToolCall("toolu_c", "open_app", obj("""{"name":"Camera"}"""))), first.message.parts)
        val history = request(
            user("Open the camera and the clock."),
            ChatMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("call.1:x", "open_app", obj("""{"name":"Camera"}""")),
                    AssistantPart.ToolCall("call:1.x", "open_app", obj("""{"name":"Clock"}""")),
                ),
            ),
            ChatMessage.ToolResult("call.1:x", "open_app", "Opened."),
            ChatMessage.ToolResult("call:1.x", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        adapter.complete(history, copilot)

        assertEquals("https://api.individual.githubcopilot.com/messages".toHttpUrl(), bench.calls.requests.first())
        val firstBody = bench.body(bench.take())
        assertNull(firstBody["thinking"])
        assertNull(firstBody["output_config"])
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        // Two IDs that become the same get `_2`, as in OpenClaw's normalizeCopilotAnthropicToolIds.
        val calls = messages[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject["id"] }
        val results = messages[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject["tool_use_id"] }
        assertEquals(listOf(json(""""call_1_x""""), json(""""call_1_x_2"""")), calls)
        assertEquals(calls, results)
    }

    @Test
    fun copilotSendsAutoAndNoTemperatureToAClaudeModelThatThinksByDefaultButStillStripsItsThinking() = runBlocking {
        bench.reply("anthropic/copilot-tool-call.sse")
        bench.reply("anthropic/text.sse")
        val copilot = target(
            "github-copilot", RequestStyle.ANTHROPIC, "https://api.individual.githubcopilot.com",
            ModelEntry("claude-sonnet-5", "Claude Sonnet 5", reasoning = true),
            options = StyleOptions(stripThinking = true, sanitizeToolCallIds = true, messagesPath = "/messages"),
        )

        val first = adapter.complete(request(user("Open the camera.")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, copilot)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("toolu_c", "open_app", "Opened.")), copilot)

        // OpenClaw's Copilot replay policy strips replayed thinking for every Claude model, Claude 5 included.
        assertEquals(listOf(AssistantPart.ToolCall("toolu_c", "open_app", obj("""{"name":"Camera"}"""))), first.message.parts)
        val firstBody = bench.body(bench.take())
        assertNull(firstBody["thinking"])
        assertNull(firstBody["temperature"])
        assertEquals(json("""{"type":"auto","disable_parallel_tool_use":true}"""), firstBody["tool_choice"])
        val replayed = bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, replayed.size)
        assertEquals(json(""""tool_use""""), replayed[0].jsonObject["type"])
    }

    @Test
    fun theVertexFormSendsTheVersionInTheBodyAndDoesNotStream() = runBlocking {
        bench.reply("anthropic/vertex-message.json")
        val vertex = target("google-vertex", RequestStyle.VERTEX, "https://aiplatform.googleapis.com/v1/projects/p/locations/global", ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", style = RequestStyle.ANTHROPIC))
        val url = "https://aiplatform.googleapis.com/v1/projects/p/locations/global/publishers/anthropic/models/claude-sonnet-4-6:rawPredict".toHttpUrl()

        val response = adapter.complete(request(user("Hi"), tools = emptyList()), vertex, url, vertex = true, logPath = "/v1/projects/{project}/x")

        assertEquals(listOf(AssistantPart.Text("Hello from Vertex.")), response.message.parts)
        assertEquals(Usage(input = 30, output = 6), response.usage)
        val recorded = bench.take()
        assertNull(recorded.headers["anthropic-version"])
        assertEquals("application/json", recorded.headers["accept"])
        val body = bench.body(recorded)
        assertEquals(json(""""vertex-2023-10-16""""), body["anthropic_version"])
        assertNull(body["model"])
        assertEquals(json("false"), body["stream"])
    }

    @Test
    fun aStreamWithoutUsageIsEstimatedAndUsageThatComesOnlyAtTheEndIsRead() = runBlocking {
        bench.reply("anthropic/no-usage.sse")
        bench.reply("anthropic/late-usage.sse")

        val response = adapter.complete(request(user("Hi")), anthropic)
        // late-usage.sse ends with a proxy's `data: [DONE]` after message_stop.
        val late = adapter.complete(request(user("Hi")), anthropic)

        assertTrue(response.usage.estimated)
        assertEquals("MiniMax-M3", response.modelId)
        // OpenClaw's mapAnthropicStopReason treats `sensitive` like `refusal`.
        assertEquals(StopReason.FILTERED, response.stop)
        assertEquals(Usage(input = 120, output = 9, cacheRead = 800), late.usage)
    }

    @Test
    fun theStatusErrorsAndTheStreamsErrorEventAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"type":"error","error":{"type":"rate_limit_error","message":"Slow down."}}""", "Retry-After" to "5")
        bench.status(500)
        bench.reply("anthropic/overloaded.sse")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), anthropic.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(5_000, "rate_limit_error"), errorOf(adapter, request(user("Hi")), anthropic))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), anthropic))
        assertEquals(ProviderError.ServerError(529), errorOf(adapter, request(user("Hi")), anthropic))
    }

    @Test
    fun aMalformedEventAStreamWithoutMessageStopAndAMessageWithoutContentAreMalformed() {
        bench.reply("anthropic/malformed.sse")
        bench.reply("anthropic/ends-early.sse")
        bench.status(200, """{"type":"message","role":"assistant","model":"claude-sonnet-4-6"}""")
        val vertex = target("google-vertex", RequestStyle.VERTEX, "https://aiplatform.googleapis.com/v1/projects/p/locations/global", ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", style = RequestStyle.ANTHROPIC))
        val url = "https://aiplatform.googleapis.com/v1/projects/p/locations/global/publishers/anthropic/models/claude-sonnet-4-6:rawPredict".toHttpUrl()

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), anthropic))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), anthropic))
        val error = try {
            runBlocking { adapter.complete(request(user("Hi")), vertex, url, vertex = true, logPath = null) }
            null
        } catch (e: ProviderException) {
            e.error
        }
        assertEquals(ProviderError.Malformed, error)
    }

    @Test
    fun openClawsClaudeRulesNameTheAdaptiveModels() {
        assertTrue(AnthropicModels.adaptive("claude-sonnet-4-6"))
        assertTrue(AnthropicModels.adaptive("anthropic.claude-opus-5"))
        assertTrue(AnthropicModels.adaptive("claude-haiku-5.5"))
        assertFalse(AnthropicModels.adaptive("claude-sonnet-4-5"))
        assertFalse(AnthropicModels.adaptive("claude-haiku-4.5"))
        assertFalse(AnthropicModels.adaptive("MiniMax-M3"))
        for (id in listOf("claude-sonnet-5", "claude-opus-5", "claude-fable-5", "claude-mythos-5", "claude-mythos-preview", "us.anthropic.claude-sonnet-5-v1:0")) {
            assertTrue(id, AnthropicModels.thinksByDefault(id))
        }
        for (id in listOf("claude-sonnet-4-6", "claude-haiku-4.5", "anthropic.claude-opus-4-8-v1:0", "MiniMax-M3")) {
            assertFalse(id, AnthropicModels.thinksByDefault(id))
        }
        assertTrue(AnthropicModels.defaultSamplingOnly("claude-opus-4-8"))
        assertFalse(AnthropicModels.defaultSamplingOnly("claude-sonnet-4-6"))
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'AnthropicAdapter'" and "Unresolved reference 'AnthropicModels'".

- [ ] **Step 4: Write Claude's rules**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/AnthropicModels.kt`:

```kotlin
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
```

- [ ] **Step 5: Write the adapter**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/AnthropicAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl

/**
 * Anthropic's Messages style (spec 10.3, design D4 to D6), used by Anthropic, MiniMax, Synthetic, Vercel's gateway,
 * Copilot's Claude models, Mantle's Claude models, Claude on Vertex and Claude deployments on Foundry. It streams and
 * gathers the stream; the thinking and redacted-thinking blocks go back unchanged, in their order, to the same provider
 * and model. Adaptive thinking (`output_config.effort`) goes only to the Claude models that take it. While thinking is on,
 * and always for a model that thinks by default ([AnthropicModels.thinksByDefault]), a forced tool choice becomes `auto`
 * (except for Claude Haiku 5.5, [AnthropicModels.keepsForcedToolChoice]), as OpenClaw sends it, and no temperature is
 * sent. A route that strips thinking (Copilot's) keeps no thinking blocks, also for a model that thinks by default, as
 * OpenClaw's Copilot replay policy does. The end of the system prompt and
 * the last tool carry Anthropic's default cache marker (design D4). [complete] with `vertex` is the form of Claude on
 * Vertex (design D4, research r35): no streaming, `anthropic_version` in the body and the model in the URL.
 */
internal class AnthropicAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val url = target.baseUrl.newBuilder().addPathSegments(target.options.messagesPath.trim('/')).build()
        return complete(request, target, url, vertex = false, logPath = null)
    }

    suspend fun complete(request: ChatRequest, target: ModelTarget, url: HttpUrl, vertex: Boolean, logPath: String?): ChatResponse {
        ChatRequests.check(request)
        val stream = !vertex && target.options.stream != false
        val body = body(request, target, vertex, stream)
        val headers = if (vertex) emptyMap() else mapOf("anthropic-version" to VERSION)
        val gather = Gather(request, target)
        val post = Wire.post(url, body, target, request, stream, headers, logPath)
        if (stream) {
            http.stream(post, target.auth) { event ->
                // A proxy's closing `data: [DONE]` and anything after message_stop are not part of the reply.
                if (!gather.stopped && event.data.trim() != "[DONE]") gather.event(Wire.parse(event.data))
            }
        } else {
            gather.message(Wire.parse(http.send(post, target.auth).text()))
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget, vertex: Boolean, stream: Boolean): JsonObject = buildJsonObject {
        val strip = target.options.stripThinking
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        val level = if (strip) null else Wire.level(request, target.model, compat)
        val thinks = level != null && AnthropicModels.adaptive(target.sentModelId)
        val thinking = thinks || AnthropicModels.thinksByDefault(target.sentModelId)
        if (vertex) put("anthropic_version", VERTEX_VERSION) else put("model", target.sentModelId)
        put("max_tokens", request.maxOutputTokens)
        if (request.system.isNotBlank()) putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", request.system)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        put("messages", messages(request, target, tools))
        if (tools) {
            putJsonArray("tools") {
                request.tools.forEachIndexed { index, tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                        if (index == request.tools.lastIndex) putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            }
            putJsonObject("tool_choice") {
                when (request.toolChoice) {
                    ToolChoice.NONE -> put("type", "none")
                    ToolChoice.REQUIRED -> put("type", if (thinking && !AnthropicModels.keepsForcedToolChoice(target.sentModelId)) "auto" else "any")
                    ToolChoice.AUTO -> put("type", "auto")
                }
                if (request.toolChoice != ToolChoice.NONE) put("disable_parallel_tool_use", true)
            }
        }
        if (thinks) {
            putJsonObject("thinking") { put("type", "adaptive") }
            putJsonObject("output_config") { put("effort", AnthropicModels.effort(level, target.model.thinkingLevelMap)) }
        }
        val temperature = request.temperature
        if (temperature != null && !thinking && !AnthropicModels.defaultSamplingOnly(target.sentModelId) && compat.supportsTemperature != false) {
            put("temperature", temperature)
        }
        put("stream", stream)
    }

    /** Consecutive tool results and the user message after them become one user turn, results first. */
    private fun messages(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray = buildJsonArray {
        val ids = ToolIds(target.options.sanitizeToolCallIds)
        val pending = mutableListOf<JsonObject>()
        fun flush() {
            if (pending.isEmpty()) return
            addJsonObject {
                put("role", "user")
                put("content", JsonArray(pending.toList()))
            }
            pending.clear()
        }
        for (message in request.messages) {
            when (message) {
                is ChatMessage.ToolResult -> pending += if (tools) buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", ids.of(message.callId))
                    put("content", message.text)
                    if (message.failed) put("is_error", true)
                } else text(Wire.resultText(message))
                is ChatMessage.User -> {
                    for (part in message.parts) pending += when (part) {
                        is Part.Text -> if (part.text.isBlank()) continue else text(part.text)
                        is Part.Image -> buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", part.type.mime)
                                put("data", Wire.base64(part.bytes))
                            }
                        }
                    }
                    flush()
                }
                is ChatMessage.Assistant -> {
                    flush()
                    assistant(message, target, tools, ids)
                }
            }
        }
        flush()
    }

    private fun JsonArrayBuilder.assistant(message: ChatMessage.Assistant, target: ModelTarget, tools: Boolean, ids: ToolIds) {
        val keep = keepsThinking(target)
        val blocks = buildJsonArray {
            for (part in message.parts) {
                when (part) {
                    is AssistantPart.Reasoning -> {
                        val state = part.state
                        if (keep && state.style == RequestStyle.ANTHROPIC && state.providerId == target.providerId && state.modelId == target.sentModelId) {
                            (state.payload as? JsonObject)?.let { add(it) }
                        }
                    }
                    is AssistantPart.Text -> if (part.text.isNotBlank()) add(text(part.text))
                    is AssistantPart.ToolCall -> if (tools) addJsonObject {
                        put("type", "tool_use")
                        put("id", ids.of(part.id))
                        put("name", part.name)
                        put("input", part.arguments)
                    } else {
                        add(text(Wire.callText(part)))
                    }
                }
            }
        }
        if (blocks.isEmpty()) return
        addJsonObject {
            put("role", "assistant")
            put("content", blocks)
        }
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    /**
     * Copilot's Claude models take only IDs of `^[a-zA-Z0-9_-]{1,64}$` (design D12), as OpenClaw rewrites them. Two IDs
     * that become the same get `_2`, `_3` and so on, as in OpenClaw's `normalizeCopilotAnthropicToolIds`.
     */
    private class ToolIds(private val sanitize: Boolean) {
        private val mapped = mutableMapOf<String, String>()

        fun of(value: String): String {
            if (!sanitize) return value
            return mapped.getOrPut(value) {
                val base = value.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifEmpty { "_" }
                var candidate = base
                var n = 2
                while (candidate in mapped.values) {
                    val suffix = "_$n"
                    candidate = base.take(64 - suffix.length) + suffix
                    n++
                }
                candidate
            }
        }
    }

    /** One reply, from the stream's events or from one complete message. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private class Block(val type: String, val start: JsonObject) {
            val text = StringBuilder()
            val signature = StringBuilder()
            val input = StringBuilder()
        }

        private val blocks = sortedMapOf<Int, Block>()
        private var usage: JsonObject? = null
        private var stopReason: String? = null
        private var modelId: String? = null
        var stopped = false
            private set
        private var message: JsonObject? = null

        fun event(event: JsonObject) {
            when (event.str("type")) {
                "message_start" -> event.obj("message")?.let { start ->
                    modelId = start.str("model")
                    usage = start.obj("usage")
                }
                "content_block_start" -> {
                    val block = event.obj("content_block") ?: return
                    blocks[event.long("index")?.toInt() ?: blocks.size] = Block(block.str("type").orEmpty(), block)
                }
                "content_block_delta" -> {
                    val block = blocks[event.long("index")?.toInt() ?: return] ?: return
                    val delta = event.obj("delta") ?: return
                    when (delta.str("type")) {
                        "text_delta" -> block.text.append(delta.str("text").orEmpty())
                        "thinking_delta" -> block.text.append(delta.str("thinking").orEmpty())
                        "signature_delta" -> block.signature.append(delta.str("signature").orEmpty())
                        "input_json_delta" -> block.input.append(delta.str("partial_json").orEmpty())
                    }
                }
                "message_delta" -> {
                    event.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                    // Anthropic: the usage of message_delta is cumulative and may also hold the input and cache counts.
                    event.obj("usage")?.let { delta -> usage = JsonObject((usage ?: JsonObject(emptyMap())) + delta.filterValues { it !is JsonNull }) }
                }
                "message_stop" -> stopped = true
                "error" -> {
                    val error = event.obj("error")
                    throw ProviderException(Wire.streamError(if (error?.str("type") == "overloaded_error") 529 else null, error?.str("type"), target))
                }
            }
        }

        fun message(body: JsonObject) {
            message = body
            stopped = true
        }

        fun finish(): ChatResponse {
            if (!stopped) throw ProviderException(ProviderError.Malformed)
            val whole = message
            if (whole != null && whole["content"] !is JsonArray) throw ProviderException(ProviderError.Malformed)
            val reply = ReplyBuilder(request)
            val keep = keepsThinking(target)
            var reasoningChars = 0
            val contents = if (whole != null) whole.arr("content").objects() else blocks.values.map(::complete)
            for (block in contents) {
                when (block.str("type")) {
                    "text" -> block.str("text")?.takeIf { it.isNotBlank() }?.let(reply::text)
                    "thinking", "redacted_thinking" -> {
                        reasoningChars += block.str("thinking")?.length ?: 0
                        if (keep) {
                            reply.reasoning(ReasoningState(RequestStyle.ANTHROPIC, target.providerId, target.sentModelId, block))
                        }
                    }
                    "tool_use" -> reply.call(block.str("id").orEmpty(), block.str("name").orEmpty(), block["input"])
                }
            }
            val counted = (whole?.obj("usage") ?: usage)?.takeIf { it.long("input_tokens") != null }?.let(::usageOf)
                ?: Wire.estimate(request, reply.replyChars, reasoningChars, target.model.reasoning)
            val stop = when (whole?.str("stop_reason") ?: stopReason) {
                null, "end_turn", "stop_sequence", "pause_turn" -> StopReason.END
                "tool_use" -> StopReason.TOOL_CALL
                "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
                "refusal", "sensitive" -> StopReason.FILTERED
                else -> StopReason.OTHER
            }
            return reply.response(stop, counted, whole?.str("model") ?: modelId ?: target.sentModelId)
        }

        /** A streamed block as Anthropic's complete block: text, thinking with its signature, or tool use with its input. */
        private fun complete(block: Block): JsonObject = when (block.type) {
            "text" -> buildJsonObject {
                put("type", "text")
                put("text", block.text.toString())
            }
            "thinking" -> buildJsonObject {
                put("type", "thinking")
                put("thinking", block.text.toString())
                put("signature", block.signature.toString().ifEmpty { block.start.str("signature").orEmpty() })
            }
            "tool_use" -> buildJsonObject {
                put("type", "tool_use")
                put("id", block.start.str("id").orEmpty())
                put("name", block.start.str("name").orEmpty())
                put("input", if (block.input.isBlank()) block.start["input"] ?: JsonObject(emptyMap()) else parseInput(block.input.toString()))
            }
            else -> block.start
        }

        private fun parseInput(text: String): JsonElement =
            try {
                Wire.json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                JsonPrimitive(text)
            }

        /** Design D6: Anthropic's input already excludes the cache. */
        private fun usageOf(usage: JsonObject): Usage = Usage(
            input = usage.long("input_tokens") ?: 0,
            output = usage.long("output_tokens") ?: 0,
            cacheRead = usage.long("cache_read_input_tokens") ?: 0,
            cacheWrite = usage.long("cache_creation_input_tokens") ?: 0,
        )
    }

    companion object {
        /** Whether thinking blocks are kept and replayed: never on a route that strips thinking (Copilot's, design D12). */
        private fun keepsThinking(target: ModelTarget): Boolean = !target.options.stripThinking

        /** The version that OpenClaw's working paths send (design D4). */
        const val VERSION = "2023-06-01"

        const val VERTEX_VERSION = "vertex-2023-10-16"
    }
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 44 tests in `:core:providers`: Task 2's 33 and `AnthropicAdapterTest` 11.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 44; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit without a finding.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/AnthropicAdapter.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/AnthropicModels.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/AnthropicAdapterTest.kt core/providers/src/test/resources/fixtures/anthropic
git commit -m "feat(providers): add the Anthropic Messages style with thinking blocks, adaptive thinking and Vertex's form" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Gemini

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/GeminiAdapter.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/gemini/` (11 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/GeminiAdapterTest.kt`

**Interfaces:**
- Consumes: Task 1's `Wire` (with `nextSyntheticNumber`), `ReplyBuilder` (with `invalid`) and `AdapterHarness`; plan 4A's format and `KeyHeader.X_GOOG_API_KEY`.
- Produces: `internal class GeminiAdapter(http: ProviderHttp) : ChatAdapter` with `suspend fun complete(request, target, url: HttpUrl, stream: Boolean, logPath: String?): ChatResponse` (for Task 7) and `companion object { const val SKIP_SIGNATURE = "skip_thought_signature_validator"; const val SYNTHETIC_ID = "gemini-call-"; fun url(base: HttpUrl, collection: String, modelId: String, stream: Boolean): HttpUrl; fun thinking(level: String, modelId: String): JsonObject? }`.

Design D3 to D7 and D19; research r38 and r41. The Gemini adapter serves Gemini API keys (`x-goog-api-key` from the target's `RequestAuth`) and, through Task 7, Gemini on Vertex. It posts to `<base>/models/<id>:streamGenerateContent?alt=sse`, gathers the chunks (text, `thought` text, `functionCall` parts with their `thoughtSignature`, the finish reason, `usageMetadata` and `modelVersion`), and treats a chunk with a `finishReason` (or a `promptFeedback.blockReason`) as the final one; a stream without it is `Malformed`, and a chunk with `error` is mapped by its `code` and `status` (design D4).

Thought signatures (design D5, research r38 §3): the adapter keeps the model's parts in their order, adjacent text joined and each `thoughtSignature` on its part, as a `ReasoningState` of style `GEMINI` when any part has a signature and every call names a tool of the request, and sends those parts back unchanged to the same provider and model. A reply with a call that Kestrel leaves out keeps no parts, because Gemini wants one response for each call that the model turn holds. For another model (after an escalation) or such a reply, the adapter rebuilds the parts from the message and, for Gemini 3 and later, puts Google's placeholder `skip_thought_signature_validator` on the first function call of each rebuilt assistant message, as OpenClaw does (research r38 §3); Google checks only the current turn, so this is safe however Gemini counts the turns. A function call without an `id` gets one that starts with `gemini-call-`, numbered after the highest such ID in the request, which is never sent back; Gemini matches results by name and order.

Tools (design D3): one `functionDeclarations` entry per tool, without `parameters` for a tool whose top-level object has no properties, because Gemini refuses an empty object. `toolConfig.functionCallingConfig.mode` is `AUTO`, `ANY` or `NONE`. Blank text parts are left out, because Gemini refuses a part without data. All results of a step go back as `functionResponse` parts (`output`, or `error` for a failed result) in one user turn together with the next screen, because Gemini wants all calls of a step before their results (research r38). JSON actions (design D7) set `responseMimeType: "application/json"` without a schema (review A, round 2, R2-M6).

Thinking (design D5): `GeminiAdapter.thinking` is ported from OpenClaw's rules (`packages/ai/src/providers/google-shared.ts` at the pinned commit): a `thinkingBudget` for Gemini 2.5 Pro and Flash (128 to 32,768 tokens by level), a `thinkingLevel` for Gemini 3 and later (only `LOW` and `HIGH` on Gemini 3 Pro; `MINIMAL` becomes `LOW` on Gemini 4 and on Gemini 3.7 Flash and later), and nothing for older models. Usage (design D6): `input = promptTokenCount − cachedContentTokenCount + toolUsePromptTokenCount`, `cacheRead = cachedContentTokenCount`, `output = candidatesTokenCount + thoughtsTokenCount`, `reasoning = thoughtsTokenCount`. A reply that stops with `MALFORMED_FUNCTION_CALL` gets the invalid tool call `BAD_JSON`, and a prompt that Gemini blocks (`promptFeedback.blockReason`) stops with `FILTERED`.

- [ ] **Step 1: Write the fixtures**

These follow Gemini's `streamGenerateContent` chunks with `alt=sse` (`https://ai.google.dev/api/generate-content`), the thought signatures of research r38 §3 (the signature on the first `functionCall` part of a step only) and the usage fields of research r41; `unknown-call.sse` holds a call to a tool that the request lacks, `malformed-call.sse` stops with `MALFORMED_FUNCTION_CALL` and `blocked.sse` has only a `promptFeedback.blockReason`.

Create `core/providers/src/test/resources/fixtures/gemini/blocked.sse`:

```text
data: {"promptFeedback":{"blockReason":"SAFETY"},"modelVersion":"gemini-3.8-flash","usageMetadata":{"promptTokenCount":300,"totalTokenCount":300}}
```

Create `core/providers/src/test/resources/fixtures/gemini/ends-early.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"index":0}],"modelVersion":"gemini-3.8-flash"}
```

Create `core/providers/src/test/resources/fixtures/gemini/malformed-call.sse`:

```text
data: {"candidates":[{"content":{"parts":[],"role":"model"},"index":0,"finishReason":"MALFORMED_FUNCTION_CALL","finishMessage":"Malformed function call: open_app(name=Camera"}],"modelVersion":"gemini-3.8-flash","usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":0,"totalTokenCount":300}}
```

Create `core/providers/src/test/resources/fixtures/gemini/malformed.sse`:

```text
data: {"candidates":[{"content":
```

Create `core/providers/src/test/resources/fixtures/gemini/no-usage.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"text":"Hello, owner."}],"role":"model"},"index":0,"finishReason":"STOP"}],"modelVersion":"gemini-3.8-flash"}
```

Create `core/providers/src/test/resources/fixtures/gemini/stream-error.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"},"index":0}],"modelVersion":"gemini-3.8-flash"}

data: {"error":{"code":429,"message":"Resource has been exhausted.","status":"RESOURCE_EXHAUSTED"}}
```

Create `core/providers/src/test/resources/fixtures/gemini/text.request.json`:

```json
{
  "systemInstruction": {
    "parts": [
      {
        "text": "You are Kestrel."
      }
    ]
  },
  "contents": [
    {
      "role": "user",
      "parts": [
        {
          "text": "Hi"
        }
      ]
    }
  ],
  "tools": [
    {
      "functionDeclarations": [
        {
          "name": "open_app",
          "description": "Opens an app by its name.",
          "parameters": {
            "type": "object",
            "properties": {
              "name": {
                "type": "string",
                "description": "The app's name."
              }
            },
            "required": [
              "name"
            ]
          }
        },
        {
          "name": "get_number",
          "description": "Returns the test number."
        }
      ]
    }
  ],
  "toolConfig": {
    "functionCallingConfig": {
      "mode": "AUTO"
    }
  },
  "generationConfig": {
    "maxOutputTokens": 256,
    "thinkingConfig": {
      "thinkingLevel": "LOW"
    }
  }
}
```

Create `core/providers/src/test/resources/fixtures/gemini/text.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"text":"Hello, "}],"role":"model"},"index":0}],"modelVersion":"gemini-3.8-flash"}

data: {"candidates":[{"content":{"parts":[{"text":"owner."}],"role":"model"},"index":0,"finishReason":"STOP"}],"modelVersion":"gemini-3.8-flash","usageMetadata":{"promptTokenCount":1000,"cachedContentTokenCount":600,"toolUsePromptTokenCount":10,"candidatesTokenCount":30,"thoughtsTokenCount":20,"totalTokenCount":1060}}
```

Create `core/providers/src/test/resources/fixtures/gemini/tool-call.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"open_app","args":{"name":"Camera"}},"thoughtSignature":"c2lnLW9uZQ=="}],"role":"model"},"index":0,"finishReason":"STOP"}],"modelVersion":"gemini-3.8-flash","usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":12,"thoughtsTokenCount":40,"totalTokenCount":352}}
```

Create `core/providers/src/test/resources/fixtures/gemini/two-calls.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"open_app","args":{"name":"Camera"}},"thoughtSignature":"c2lnLXR3bw=="},{"functionCall":{"name":"open_app","args":{"name":"Clock"}}}],"role":"model"},"index":0,"finishReason":"STOP"}],"modelVersion":"gemini-3.8-flash","usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":20,"totalTokenCount":320}}
```

Create `core/providers/src/test/resources/fixtures/gemini/unknown-call.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"open_app","args":{"name":"Camera"}},"thoughtSignature":"c2lnLXRocmVl"},{"functionCall":{"name":"close_app","args":{"name":"Clock"}}}],"role":"model"},"index":0,"finishReason":"STOP"}],"modelVersion":"gemini-3.8-flash","usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":20,"totalTokenCount":320}}
```

- [ ] **Step 2: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/GeminiAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.GET_NUMBER
import dev.kestrel.providers.styles.AdapterHarness.Companion.OPEN_APP
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = GeminiAdapter(bench.http)

    private val flash = ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", reasoning = true, thinkingLevelMap = mapOf("minimal" to null))
    private val google = target("google", RequestStyle.GEMINI, "https://generativelanguage.googleapis.com/v1beta", flash, ApiKeyAuth("test-key", KeyHeader.X_GOOG_API_KEY))

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndAToolWithoutPropertiesHasNoParameters() = runBlocking {
        bench.reply("gemini/text.sse")

        val response = adapter.complete(request(user("Hi"), tools = listOf(OPEN_APP, GET_NUMBER)) { copy(reasoning = ReasoningLevel.LOW) }, google)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 410, output = 50, cacheRead = 600, reasoning = 20), response.usage)
        assertEquals(
            listOf("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse".toHttpUrl()),
            bench.calls.requests,
        )
        val recorded = bench.take()
        assertEquals("test-key", recorded.headers["x-goog-api-key"])
        bench.assertBody("gemini/text.request.json", recorded)
    }

    @Test
    fun theModelsPartsGoBackWithTheirSignatureAndARebuiltMessageGetsThePlaceholder() = runBlocking {
        bench.reply("gemini/tool-call.sse")
        bench.reply("gemini/text.sse")
        bench.reply("gemini/tool-call.sse")
        bench.reply("gemini/text.sse")
        bench.reply("gemini/unknown-call.sse")
        bench.reply("gemini/text.sse")

        val first = adapter.complete(request(user("Open the camera.")), google)

        val parts = json("""[{"functionCall":{"name":"open_app","args":{"name":"Camera"}},"thoughtSignature":"c2lnLW9uZQ=="}]""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.GEMINI, "google", "gemini-3.8-flash", obj("""{"parts":$parts}"""))),
                AssistantPart.ToolCall("gemini-call-0", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        val next = request(user("Open the camera."), first.message, ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."))
        adapter.complete(next, google)
        // The agent's form: after the results comes the next screen, also as a user message.
        val argon = google.copy(model = ModelEntry("gemini-4-argon", "Gemini 4 Argon", reasoning = true), sentModelId = "gemini-4-argon")
        val withScreen = request(user("Open the camera."), first.message, ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."), user("The screen now."))
        val second = adapter.complete(withScreen, argon)
        val pro25 = google.copy(model = ModelEntry("gemini-2.5-pro", "Gemini 2.5 Pro", reasoning = true), sentModelId = "gemini-2.5-pro")
        adapter.complete(next, pro25)
        // A reply that holds a call that Kestrel leaves out is rebuilt, so that every call has its response.
        val partial = adapter.complete(request(user("Open the camera.")), google)
        adapter.complete(request(user("Open the camera."), partial.message, ChatMessage.ToolResult(partial.message.toolCalls.single().id, "open_app", "Opened.")), google)

        bench.take()
        val same = bench.body(bench.take())["contents"]!!.jsonArray
        assertEquals(json("""{"role":"model","parts":$parts}"""), same[1])
        assertEquals(json("""{"role":"user","parts":[{"functionResponse":{"name":"open_app","response":{"output":"Opened."}}}]}"""), same[2])
        val placeholder = json("""{"role":"model","parts":[{"functionCall":{"name":"open_app","args":{"name":"Camera"}},"thoughtSignature":"skip_thought_signature_validator"}]}""")
        assertEquals(placeholder, bench.body(bench.take())["contents"]!!.jsonArray[1])
        // A made-up ID is numbered after the highest one in the history.
        assertEquals("gemini-call-1", second.message.toolCalls.single().id)
        assertEquals(json("""{"role":"model","parts":[{"functionCall":{"name":"open_app","args":{"name":"Camera"}}}]}"""), bench.body(bench.take())["contents"]!!.jsonArray[1])
        bench.take()
        assertEquals(InvalidKind.UNKNOWN_TOOL, partial.invalidToolCall?.kind)
        assertEquals(placeholder, bench.body(bench.take())["contents"]!!.jsonArray[1])
    }

    @Test
    fun allResultsOfAStepGoTogetherWithTheNextScreen() = runBlocking {
        bench.reply("gemini/two-calls.sse")
        bench.reply("gemini/text.sse")

        val first = adapter.complete(request(user("Open the camera and the clock.")), google)

        assertEquals(listOf("gemini-call-0", "gemini-call-1"), first.message.toolCalls.map { it.id })
        val next = request(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."),
            ChatMessage.ToolResult("gemini-call-1", "open_app", "Not run: Kestrel runs one action per step", failed = true),
            ChatMessage.User(listOf(Part.Text("The screen now."), Part.Text(" "), Part.Image(ImageType.PNG, SCREENSHOT))),
        ) { copy(system = "\n") }
        adapter.complete(next, google)

        bench.take()
        val body = bench.body(bench.take())
        // Gemini refuses a part without data, so the blank system prompt and the blank text are left out.
        assertNull(body["systemInstruction"])
        val contents = body["contents"]!!.jsonArray
        assertEquals(3, contents.size)
        assertEquals(
            json(
                """[{"functionResponse":{"name":"open_app","response":{"output":"Opened."}}},
                {"functionResponse":{"name":"open_app","response":{"error":"Not run: Kestrel runs one action per step"}}},
                {"text":"The screen now."},{"inlineData":{"mimeType":"image/png","data":"iVBORw0KGgo="}}]""",
            ),
            contents[2].jsonObject["parts"],
        )
    }

    @Test
    fun jsonActionsAskForJsonWithoutASchemaAndTheHistoryIsText() = runBlocking {
        bench.reply("gemini/no-usage.sse")
        val call = AssistantPart.ToolCall("gemini-call-0", "open_app", obj("""{"name":"Camera"}"""))
        val history = request(user("Open the camera."), ChatMessage.Assistant(listOf(call)), ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."), tools = emptyList()) {
            copy(jsonSchema = obj("""{"type":"object"}"""))
        }

        val response = adapter.complete(history, google)

        assertTrue(response.usage.estimated)
        val body = bench.body(bench.take())
        assertNull(body["tools"])
        assertEquals(json(""""application/json""""), body["generationConfig"]!!.jsonObject["responseMimeType"])
        assertEquals(
            json("""[{"role":"user","parts":[{"text":"Open the camera."}]},{"role":"model","parts":[{"text":"[tool call id=gemini-call-0 name=open_app] {\"name\":\"Camera\"}"}]},{"role":"user","parts":[{"text":"[tool result id=gemini-call-0 name=open_app] Opened."}]}]"""),
            body["contents"],
        )
    }

    @Test
    fun theStatusErrorsAndTheStreamsErrorAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"error":{"code":429,"message":"Quota.","status":"RESOURCE_EXHAUSTED"}}""", "Retry-After" to "7")
        bench.status(500)
        bench.reply("gemini/stream-error.sse")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), google.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(7_000, "RESOURCE_EXHAUSTED"), errorOf(adapter, request(user("Hi")), google))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), google))
        assertEquals(ProviderError.RateLimited(null, "RESOURCE_EXHAUSTED"), errorOf(adapter, request(user("Hi")), google))
    }

    @Test
    fun aMalformedChunkAndAStreamWithoutAFinishReasonAreMalformedAndGeminisOwnStopsAreMapped() = runBlocking {
        bench.reply("gemini/malformed.sse")
        bench.reply("gemini/ends-early.sse")
        bench.reply("gemini/malformed-call.sse")
        bench.reply("gemini/blocked.sse")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), google))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), google))
        assertEquals(InvalidKind.BAD_JSON, adapter.complete(request(user("Open the camera.")), google).invalidToolCall?.kind)
        assertEquals(StopReason.FILTERED, adapter.complete(request(user("Hi")), google).stop)
    }

    @Test
    fun theThinkingSettingsFollowOpenClawsRulesPerGeneration() {
        assertEquals(obj("""{"thinkingBudget":32768}"""), GeminiAdapter.thinking("high", "gemini-2.5-pro"))
        assertEquals(obj("""{"thinkingBudget":512}"""), GeminiAdapter.thinking("minimal", "gemini-2.5-flash-lite"))
        assertNull(GeminiAdapter.thinking("high", "gemini-2.0-flash"))
        assertEquals(obj("""{"thinkingLevel":"LOW"}"""), GeminiAdapter.thinking("minimal", "gemini-3.1-pro-preview"))
        assertEquals(obj("""{"thinkingLevel":"HIGH"}"""), GeminiAdapter.thinking("medium", "gemini-3.1-pro-preview"))
        assertEquals(obj("""{"thinkingLevel":"MINIMAL"}"""), GeminiAdapter.thinking("minimal", "gemini-3.6-flash"))
        assertEquals(obj("""{"thinkingLevel":"LOW"}"""), GeminiAdapter.thinking("minimal", "gemini-3.7-flash"))
        assertEquals(obj("""{"thinkingLevel":"LOW"}"""), GeminiAdapter.thinking("minimal", "gemini-4-argon"))
        assertEquals(obj("""{"thinkingLevel":"HIGH"}"""), GeminiAdapter.thinking("max", "gemini-3.8-flash"))
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'GeminiAdapter'".

- [ ] **Step 4: Write the adapter**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/GeminiAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.bool
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl

/**
 * Google's Gemini style (spec 10.3, design D3 to D7), for Gemini API keys and, through VertexAdapter, Gemini on Vertex.
 * It streams `streamGenerateContent?alt=sse` and gathers the stream. The model's content goes back unchanged, with every
 * `thoughtSignature` on its part, to the same provider and model, unless the reply held a call that Kestrel left out. An
 * assistant message that is rebuilt instead (after an escalation, for example) gets Google's placeholder
 * `skip_thought_signature_validator` on its first function call, for Gemini 3 and later, as OpenClaw does (research r38).
 * Tool results go back as `functionResponse` parts, all of a step together with the next screen in one user turn. JSON
 * actions set `responseMimeType` without a schema (design D7).
 */
internal class GeminiAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val stream = target.options.stream != false
        return complete(request, target, url(target.baseUrl, "models", target.sentModelId, stream), stream, logPath = null)
    }

    suspend fun complete(request: ChatRequest, target: ModelTarget, url: HttpUrl, stream: Boolean, logPath: String?): ChatResponse {
        ChatRequests.check(request)
        val gather = Gather(request, target)
        val post = Wire.post(url, body(request, target), target, request, stream, logPath = logPath)
        if (stream) {
            http.stream(post, target.auth) { event -> gather.chunk(Wire.parse(event.data)) }
        } else {
            gather.chunk(Wire.parse(http.send(post, target.auth).text()))
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget): JsonObject = buildJsonObject {
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        if (request.system.isNotBlank()) putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", request.system) } }
        }
        put("contents", contents(request, target, tools))
        if (tools) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        for (tool in request.tools) addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            if ((tool.parameters["properties"] as? JsonObject)?.isNotEmpty() == true) put("parameters", tool.parameters)
                        }
                    }
                }
            }
            putJsonObject("toolConfig") {
                putJsonObject("functionCallingConfig") {
                    put("mode", when (request.toolChoice) {
                        ToolChoice.AUTO -> "AUTO"
                        ToolChoice.REQUIRED -> "ANY"
                        ToolChoice.NONE -> "NONE"
                    })
                }
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxOutputTokens)
            if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
            Wire.level(request, target.model, compat)?.let { thinking(it, target.sentModelId) }?.let { put("thinkingConfig", it) }
            if (request.jsonSchema != null) put("responseMimeType", "application/json")
        }
    }

    private fun contents(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray = buildJsonArray {
        val pending = mutableListOf<JsonObject>()
        fun flush() {
            if (pending.isEmpty()) return
            addJsonObject {
                put("role", "user")
                put("parts", JsonArray(pending.toList()))
            }
            pending.clear()
        }
        val placeholder = needsSignature(target.sentModelId)
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> {
                    for (part in message.parts) pending += when (part) {
                        is Part.Text -> if (part.text.isBlank()) continue else buildJsonObject { put("text", part.text) }
                        is Part.Image -> buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", part.type.mime)
                                put("data", Wire.base64(part.bytes))
                            }
                        }
                    }
                    flush()
                }
                is ChatMessage.ToolResult -> pending += if (tools) buildJsonObject {
                    putJsonObject("functionResponse") {
                        if (!message.callId.startsWith(SYNTHETIC_ID)) put("id", message.callId)
                        put("name", message.name)
                        putJsonObject("response") { put(if (message.failed) "error" else "output", message.text) }
                    }
                } else buildJsonObject { put("text", Wire.resultText(message)) }
                is ChatMessage.Assistant -> {
                    flush()
                    val parts = modelParts(message, target, tools, placeholder)
                    if (parts.isNotEmpty()) addJsonObject {
                        put("role", "model")
                        put("parts", parts)
                    }
                }
            }
        }
        flush()
    }

    /** The model's own parts when they belong to this provider and model, else parts rebuilt from the message. */
    private fun modelParts(message: ChatMessage.Assistant, target: ModelTarget, tools: Boolean, placeholder: Boolean): JsonArray {
        val own = message.parts.filterIsInstance<AssistantPart.Reasoning>().map { it.state }.firstOrNull {
            it.style == RequestStyle.GEMINI && it.providerId == target.providerId && it.modelId == target.sentModelId
        }
        val raw = (own?.payload as? JsonObject)?.arr("parts")
        if (tools && raw != null) return raw
        return buildJsonArray {
            val text = Wire.assistantText(message, callsAsText = !tools)
            if (text.isNotBlank()) addJsonObject { put("text", text) }
            if (tools) message.toolCalls.forEachIndexed { index, call ->
                addJsonObject {
                    putJsonObject("functionCall") {
                        if (!call.id.startsWith(SYNTHETIC_ID)) put("id", call.id)
                        put("name", call.name)
                        put("args", call.arguments)
                    }
                    if (placeholder && index == 0) put("thoughtSignature", SKIP_SIGNATURE)
                }
            }
        }
    }

    /** One reply, gathered from the chunks of a stream or from one complete body. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private val raw = mutableListOf<MutableMap<String, JsonElement>>()
        private val text = StringBuilder()
        private var thoughtChars = 0
        private val calls = mutableListOf<JsonObject>()
        private var usage: JsonObject? = null
        private var modelId: String? = null
        private var finishReason: String? = null

        fun chunk(chunk: JsonObject) {
            chunk.obj("error")?.let { error ->
                throw ProviderException(Wire.streamError(error.long("code")?.toInt(), error.str("status"), target))
            }
            chunk.obj("usageMetadata")?.let { usage = it }
            chunk.str("modelVersion")?.let { modelId = it }
            chunk.obj("promptFeedback")?.str("blockReason")?.let { finishReason = "BLOCKED" }
            val candidate = chunk.arr("candidates").objects().firstOrNull() ?: return
            for (part in candidate.obj("content")?.arr("parts").objects()) {
                val signature = part["thoughtSignature"]
                val call = part.obj("functionCall")
                val piece = part.str("text")
                when {
                    call != null -> {
                        calls += call
                        raw += part.toMutableMap()
                    }
                    piece != null -> {
                        val thought = part.bool("thought") == true
                        if (thought) thoughtChars += piece.length else text.append(piece)
                        val last = raw.lastOrNull()
                        val lastThought = (last?.get("thought") as? JsonPrimitive)?.content == "true"
                        if (last != null && last["text"] is JsonPrimitive && last["thoughtSignature"] == null && lastThought == thought) {
                            last["text"] = JsonPrimitive((last["text"] as JsonPrimitive).content + piece)
                            signature?.let { last["thoughtSignature"] = it }
                        } else {
                            raw += part.toMutableMap()
                        }
                    }
                    signature != null -> raw.lastOrNull()?.set("thoughtSignature", signature)
                }
            }
            candidate.str("finishReason")?.let { finishReason = it }
        }

        fun finish(): ChatResponse {
            val reason = finishReason ?: throw ProviderException(ProviderError.Malformed)
            val reply = ReplyBuilder(request)
            // The raw parts go back only when they hold no call that ReplyBuilder leaves out, because Gemini wants one
            // response for every call. Gemini's `args` is always an object, so only an unknown name drops a call.
            val names = request.tools.map { it.name }.toSet()
            if (raw.any { it["thoughtSignature"] != null } && calls.all { it.str("name").orEmpty() in names }) {
                reply.reasoning(ReasoningState(RequestStyle.GEMINI, target.providerId, target.sentModelId, buildJsonObject {
                    put("parts", JsonArray(raw.map { JsonObject(it) }))
                }))
            }
            reply.text(text.toString())
            val first = Wire.nextSyntheticNumber(request, SYNTHETIC_ID)
            calls.forEachIndexed { index, call ->
                reply.call(call.str("id") ?: "$SYNTHETIC_ID${first + index}", call.str("name").orEmpty(), call["args"])
            }
            val stop = when (reason) {
                "STOP" -> StopReason.END
                "MAX_TOKENS" -> StopReason.MAX_TOKENS
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "LANGUAGE", "BLOCKED" -> StopReason.FILTERED
                "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> {
                    reply.invalid(InvalidKind.BAD_JSON, text.toString())
                    StopReason.OTHER
                }
                else -> StopReason.OTHER
            }
            val counted = usage?.let(::usageOf) ?: Wire.estimate(request, reply.replyChars, thoughtChars, target.model.reasoning)
            return reply.response(stop, counted, modelId ?: target.sentModelId)
        }

        /** Design D6: Gemini's prompt count holds the cached part, and thoughts are output besides the candidates. */
        private fun usageOf(usage: JsonObject): Usage {
            val cached = usage.long("cachedContentTokenCount") ?: 0
            val thoughts = usage.long("thoughtsTokenCount") ?: 0
            return Usage(
                input = Wire.minus(usage.long("promptTokenCount") ?: 0, cached) + (usage.long("toolUsePromptTokenCount") ?: 0),
                output = (usage.long("candidatesTokenCount") ?: 0) + thoughts,
                cacheRead = cached,
                reasoning = thoughts,
            )
        }
    }

    companion object {
        /** Google's documented value for history whose signature Kestrel does not have (research r38). */
        const val SKIP_SIGNATURE = "skip_thought_signature_validator"

        /**
         * The prefix of the IDs that Kestrel gives calls that came without one; they are never sent back. They are numbered
         * after the highest one in the request, so that a history stays free of repeated IDs for another style.
         */
        const val SYNTHETIC_ID = "gemini-call-"

        /** Gemini 3 and later refuse a call of the current turn without its signature (research r38 §3); OpenClaw's `requiresToolCallSignature`. */
        private fun needsSignature(modelId: String): Boolean =
            (Regex("gemini-(\\d+)").find(modelId.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 3

        /** `<base>/<collection>/<model>:streamGenerateContent?alt=sse`, or `:generateContent` without streaming. */
        fun url(base: HttpUrl, collection: String, modelId: String, stream: Boolean): HttpUrl =
            base.newBuilder()
                .addPathSegments(collection)
                .addPathSegment(modelId.removePrefix("models/") + if (stream) ":streamGenerateContent" else ":generateContent")
                .apply { if (stream) addQueryParameter("alt", "sse") }
                .build()

        /**
         * Ported from OpenClaw's thinking settings for Gemini (`packages/ai/src/providers/google-shared.ts` at the pinned commit): a
         * budget for Gemini 2.5, a level for Gemini 3 and later (only LOW and HIGH on Gemini 3 Pro; no MINIMAL on Gemini 4 or
         * on Gemini 3.7 Flash and later), and nothing for older models.
         */
        fun thinking(level: String, modelId: String): JsonObject? {
            val id = modelId.lowercase().substringAfterLast('/')
            val effort = when (level) {
                "xhigh", "max" -> "high"
                else -> level
            }
            val major = Regex("gemini-(\\d+)").find(id)?.groupValues?.get(1)?.toIntOrNull()
            if (major == null || major < 3) {
                val pro = "2.5-pro" in id
                if (!pro && "2.5-flash" !in id) return null
                val budget = when (effort) {
                    "minimal" -> if (!pro && "2.5-flash-lite" in id) 512 else 128
                    "low" -> 2048
                    "medium" -> 8192
                    else -> if (pro) 32768 else 24576
                }
                return buildJsonObject { put("thinkingBudget", budget) }
            }
            val low = effort == "minimal" || effort == "low"
            val value = when {
                Regex("gemini-3(?:\\.\\d+)?-pro").containsMatchIn(id) -> if (low) "LOW" else "HIGH"
                effort == "minimal" -> if (major >= 4 || (Regex("gemini-3\\.(\\d+)-flash").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 7) "LOW" else "MINIMAL"
                else -> effort.uppercase()
            }
            return buildJsonObject { put("thinkingLevel", value) }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 51 tests in `:core:providers`: Task 3's 44 and `GeminiAdapterTest` 7.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 51; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit without a finding.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/GeminiAdapter.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/GeminiAdapterTest.kt core/providers/src/test/resources/fixtures/gemini
git commit -m "feat(providers): add the Gemini style with thought signatures and OpenClaw's thinking settings" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Ollama and the local servers

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/OllamaAdapter.kt`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/LocalServerDiscovery.kt`, `Discoveries.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/ollama/` (7 files), `core/providers/src/test/resources/fixtures/local-servers/` (8 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/OllamaAdapterTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/LocalServerDiscoveryTest.kt`

**Interfaces:**
- Consumes: Task 1's `Wire` (with `nextSyntheticNumber`), `ReplyBuilder` and `AdapterHarness`; plan 4A's `LocalServerKind`, `DiscoveredModel`, `ModelDiscovery`, `InputKind`, `ProviderHttp.send`.
- Produces: `internal class OllamaAdapter(http: ProviderHttp) : ChatAdapter` with `companion object { const val SYNTHETIC_ID = "ollama-call-"; const val MAX_CONTEXT = 32_768; fun origin(baseUrl: HttpUrl): HttpUrl }`; `internal class LocalServerDiscovery(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp) : ModelDiscovery`; `object Discoveries { fun forLocalServer(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp): ModelDiscovery }` ("Shared names"; Task 9 adds `forProvider`).

Design D4 to D7, D11 and D19; research r40 and r41. Ollama's native style serves an Ollama server and Ollama Cloud. The adapter posts to `<origin>/api/chat` with `"stream": false`, because Ollama's stream is newline-delimited JSON, not server-sent events (design D4): the origin of the base URL, so that an address with `/v1` works as it does for discovery. Images go as base64 strings in the user message's `images`, tools as `{"type": "function", "function": …}`, earlier calls with their `arguments` as JSON objects, results with `role: "tool"` and `tool_name`, and JSON actions pass the action schema as `format` (design D7). A reasoning model asked for a level gets `think: true`; the output limit and the temperature go into `options`. A local server (not Ollama Cloud) also gets `num_ctx` there when the model's context window is known (that window, at most `MAX_CONTEXT`, OpenClaw's `OLLAMA_LOCAL_CONTEXT_TOKENS`, because Ollama's own default is small and a full window can use up the memory of the owner's computer), and `truncate: false` and `shift: false`, as OpenClaw sends them (`extensions/ollama/src/stream.runtime.ts`), so that it refuses a prompt that is too long instead of cutting it. Ollama Cloud keeps its own context, as in OpenClaw. Ollama has no tool choice, so `ToolChoice.NONE` leaves out the tools and `REQUIRED` acts as `AUTO`. A call without an `id` gets one that starts with `ollama-call-`, numbered after the highest such ID in the request. The reply must say `"done": true`, or it is `Malformed`. Usage (design D6): `input = prompt_eval_count − prompt_eval_cached_count`, `cacheRead = prompt_eval_cached_count`, `output = eval_count`. Ollama keeps no reasoning state (design D5).

Discovery (design D11, research r40) reads each server's list from the address's origin, whatever path the owner typed: Ollama's `GET /api/tags` and `POST /api/show` for each model (`capabilities` with `vision`, `tools` and `thinking`, and the `model_info` key that ends with `.context_length`); LM Studio's `GET /api/v1/models` (only `type == "llm"`, with `capabilities.vision` and `trained_for_tool_use`); llama.cpp's `GET /v1/models` (`architecture.input_modalities`) and `GET /props` (`chat_template_caps.supports_tool_calls`, `modalities.vision`, `default_generation_settings.n_ctx`); and `GET /v1/models` for vLLM, SGLang, oMLX and LiteLLM, which report no capabilities. A model that reports no tool support gets `tools = false`, so that it uses JSON actions (design D7); where a server reports nothing, `tools` stays null and plan 4C's profile page asks the owner. A failed `/api/show` or `/props` leaves the capabilities unknown; OpenClaw then assumes tool support, but Kestrel lets the owner decide. An Ollama model whose capabilities lack `completion` (an embedding model) is left out, as OpenClaw leaves out embedding models. `LocalServerDiscovery` takes the origin from `OllamaAdapter.origin`, so that design D11's rule lives in one place.

- [ ] **Step 1: Write the fixtures**

These follow Ollama's API reference (`https://raw.githubusercontent.com/ollama/ollama/main/docs/api.md`: `/api/chat` without streaming, `/api/tags`, `/api/show`), LM Studio's "List your models" (`https://lmstudio.ai/docs/developer/rest/list`), llama.cpp's server README (`/v1/models`, `/props`) and vLLM's `/v1/models`, as research r40 quotes them; `ollama-show-embed.json` is an embedding model's `/api/show`.

Create `core/providers/src/test/resources/fixtures/ollama/malformed.json`:

```json
{"model":"qwen3.6","message":
```

Create `core/providers/src/test/resources/fixtures/ollama/no-usage.json`:

```json
{
  "model": "qwen3.6",
  "created_at": "2026-11-17T18:00:00Z",
  "done": true,
  "done_reason": "stop",
  "total_duration": 812000000,
  "load_duration": 1000000,
  "prompt_eval_duration": 90000000,
  "eval_duration": 600000000,
  "message": {
    "role": "assistant",
    "content": "Hello, owner."
  }
}
```

Create `core/providers/src/test/resources/fixtures/ollama/not-done.json`:

```json
{
  "model": "qwen3.6",
  "created_at": "2026-11-17T18:00:00Z",
  "done": false,
  "done_reason": "stop",
  "total_duration": 812000000,
  "load_duration": 1000000,
  "prompt_eval_duration": 90000000,
  "eval_duration": 600000000,
  "message": {
    "role": "assistant",
    "content": "Hel"
  }
}
```

Create `core/providers/src/test/resources/fixtures/ollama/text.json`:

```json
{
  "model": "qwen3.6",
  "created_at": "2026-11-17T18:00:00Z",
  "done": true,
  "done_reason": "stop",
  "total_duration": 812000000,
  "load_duration": 1000000,
  "prompt_eval_duration": 90000000,
  "eval_duration": 600000000,
  "message": {
    "role": "assistant",
    "content": "Hello, owner.",
    "thinking": "Greet the owner."
  },
  "prompt_eval_count": 120,
  "prompt_eval_cached_count": 100,
  "eval_count": 8
}
```

Create `core/providers/src/test/resources/fixtures/ollama/text.request.json`:

```json
{
  "model": "qwen3.6",
  "messages": [
    {
      "role": "system",
      "content": "You are Kestrel."
    },
    {
      "role": "user",
      "content": "What is on the screen?",
      "images": [
        "iVBORw0KGgo="
      ]
    }
  ],
  "stream": false,
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "open_app",
        "description": "Opens an app by its name.",
        "parameters": {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "The app's name."
            }
          },
          "required": [
            "name"
          ]
        }
      }
    }
  ],
  "think": true,
  "truncate": false,
  "shift": false,
  "options": {
    "num_predict": 256,
    "num_ctx": 32768
  }
}
```

Create `core/providers/src/test/resources/fixtures/ollama/tool-call.json`:

```json
{
  "model": "qwen3.6",
  "created_at": "2026-11-17T18:00:00Z",
  "done": true,
  "done_reason": "stop",
  "total_duration": 812000000,
  "load_duration": 1000000,
  "prompt_eval_duration": 90000000,
  "eval_duration": 600000000,
  "message": {
    "role": "assistant",
    "content": "",
    "tool_calls": [
      {
        "function": {
          "name": "open_app",
          "arguments": {
            "name": "Camera"
          }
        }
      }
    ]
  },
  "prompt_eval_count": 300,
  "eval_count": 20
}
```

Create `core/providers/src/test/resources/fixtures/ollama/two-calls.json`:

```json
{
  "model": "qwen3.6",
  "created_at": "2026-11-17T18:00:00Z",
  "done": true,
  "done_reason": "stop",
  "total_duration": 812000000,
  "load_duration": 1000000,
  "prompt_eval_duration": 90000000,
  "eval_duration": 600000000,
  "message": {
    "role": "assistant",
    "content": "",
    "tool_calls": [
      {
        "function": {
          "name": "open_app",
          "arguments": {
            "name": "Camera"
          }
        }
      },
      {
        "function": {
          "name": "open_app",
          "arguments": {
            "name": "Clock"
          }
        }
      }
    ]
  },
  "prompt_eval_count": 300,
  "eval_count": 30
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/llamacpp-models.json`:

```json
{
  "object": "list",
  "data": [
    {
      "id": "gemma-4-31b-q4",
      "object": "model",
      "owned_by": "llamacpp",
      "architecture": {
        "input_modalities": [
          "text",
          "image"
        ],
        "output_modalities": [
          "text"
        ]
      }
    }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/llamacpp-props.json`:

```json
{
  "default_generation_settings": {
    "n_ctx": 32768
  },
  "chat_template_caps": {
    "supports_tool_calls": true
  },
  "modalities": {
    "vision": true
  }
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/lmstudio-models.json`:

```json
{
  "models": [
    {
      "type": "llm",
      "key": "qwen/qwen3.6-27b",
      "display_name": "Qwen3.6 27B",
      "max_context_length": 262144,
      "format": "gguf",
      "loaded_instances": [],
      "capabilities": {
        "vision": true,
        "trained_for_tool_use": true,
        "reasoning": {
          "allowed_options": [
            "off",
            "on"
          ],
          "default": "on"
        }
      }
    },
    {
      "type": "embedding",
      "key": "text-embedding-nomic-embed-text-v1.5",
      "display_name": "Nomic Embed Text v1.5",
      "max_context_length": 2048
    },
    {
      "type": "llm",
      "key": "google/gemma-4-e4b",
      "display_name": "Gemma 4 E4B",
      "max_context_length": 131072,
      "capabilities": {
        "vision": false,
        "trained_for_tool_use": false
      }
    }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/ollama-show-embed.json`:

```json
{
  "capabilities": [
    "embedding"
  ],
  "model_info": {
    "nomic-bert.context_length": 2048
  }
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/ollama-show-llama.json`:

```json
{
  "capabilities": [
    "completion"
  ],
  "model_info": {
    "general.architecture": "llama",
    "llama.context_length": 131072
  }
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/ollama-show-qwen.json`:

```json
{
  "capabilities": [
    "completion",
    "vision",
    "tools",
    "thinking"
  ],
  "model_info": {
    "general.architecture": "qwen3",
    "qwen3.context_length": 262144
  }
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/ollama-tags.json`:

```json
{
  "models": [
    {
      "name": "qwen3.6:27b",
      "model": "qwen3.6:27b",
      "size": 17000000000,
      "details": {
        "family": "qwen3"
      }
    },
    {
      "name": "llama3.2:3b",
      "model": "llama3.2:3b",
      "size": 2000000000,
      "details": {
        "family": "llama"
      }
    },
    {
      "name": "nomic-embed-text:v1.5",
      "model": "nomic-embed-text:v1.5",
      "size": 274000000,
      "details": {
        "family": "nomic-bert"
      }
    },
    {
      "name": "mistral:7b",
      "model": "mistral:7b",
      "size": 4100000000,
      "details": {
        "family": "llama"
      }
    }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/local-servers/vllm-models.json`:

```json
{
  "object": "list",
  "data": [
    {
      "id": "Qwen/Qwen3.6-27B",
      "object": "model",
      "owned_by": "vllm",
      "max_model_len": 131072
    }
  ]
}
```

- [ ] **Step 2: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/OllamaAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = OllamaAdapter(bench.http)

    private val ollama = target(
        "ollama", RequestStyle.OLLAMA, "http://127.0.0.1:11434/v1",
        ModelEntry("qwen3.6", "Qwen3.6", input = setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 262_144),
    )

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerWithAnImageGoesToTheServersOriginWithoutStreaming() = runBlocking {
        // The request asks for a context of at most 32,768 tokens and, on a local server, for no truncation.
        bench.reply("ollama/text.json")
        val screen = ChatMessage.User(listOf(Part.Text("What is on the screen?"), Part.Image(ImageType.PNG, SCREENSHOT)))

        val response = adapter.complete(request(screen) { copy(reasoning = ReasoningLevel.LOW) }, ollama)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 20, output = 8, cacheRead = 100), response.usage)
        assertEquals(listOf("http://127.0.0.1:11434/api/chat".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("application/json", recorded.headers["accept"])
        bench.assertBody("ollama/text.request.json", recorded)
    }

    @Test
    fun aToolCallAndItsResultGoBackWithTheToolsName() = runBlocking {
        bench.reply("ollama/tool-call.json")
        bench.reply("ollama/text.json")

        val first = adapter.complete(request(user("Open the camera.")), ollama)

        assertEquals(listOf(AssistantPart.ToolCall("ollama-call-0", "open_app", obj("""{"name":"Camera"}"""))), first.message.parts)
        assertEquals(StopReason.TOOL_CALL, first.stop)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("ollama-call-0", "open_app", "Opened.")), ollama)

        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(json("""{"role":"assistant","content":"","tool_calls":[{"function":{"name":"open_app","arguments":{"name":"Camera"}}}]}"""), messages[2])
        assertEquals(json("""{"role":"tool","tool_name":"open_app","content":"Opened."}"""), messages[3])
    }

    @Test
    fun aReplyWithTwoCallsKeepsBothAndTheNextRequestAnswersBoth() = runBlocking {
        bench.reply("ollama/two-calls.json")
        bench.reply("ollama/tool-call.json")
        bench.reply("ollama/text.json")

        val first = adapter.complete(request(user("Open the camera and the clock.")), ollama)

        assertEquals(listOf("ollama-call-0", "ollama-call-1"), first.message.toolCalls.map { it.id })
        val history = arrayOf(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("ollama-call-0", "open_app", "Opened."),
            ChatMessage.ToolResult("ollama-call-1", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        val second = adapter.complete(request(*history), ollama)
        // Ollama has no tool choice, so NONE leaves out the tools.
        adapter.complete(request(*history) { copy(toolChoice = ToolChoice.NONE) }, ollama)

        // A made-up ID is numbered after the highest one in the history.
        assertEquals("ollama-call-2", second.message.toolCalls.single().id)
        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(5, messages.size)
        assertEquals(json("""{"role":"tool","tool_name":"open_app","content":"Opened."}"""), messages[3])
        assertEquals(json("""{"role":"tool","tool_name":"open_app","content":"Not run: Kestrel runs one action per step"}"""), messages[4])
        assertNull(bench.body(bench.take())["tools"])
    }

    @Test
    fun jsonActionsPassTheSchemaAsFormatAndAReplyWithoutCountsIsEstimated() = runBlocking {
        bench.reply("ollama/no-usage.json")
        val schema = obj("""{"type":"object","properties":{"tool":{"type":"string"},"arguments":{"type":"object"}},"required":["tool","arguments"]}""")
        val cloud = target("ollama-cloud", RequestStyle.OLLAMA, "https://ollama.com", ModelEntry("gpt-oss:120b", "gpt-oss 120B", contextWindow = 131_072))

        val response = adapter.complete(request(user("Hi"), tools = emptyList()) { copy(jsonSchema = schema) }, cloud)

        assertTrue(response.usage.estimated)
        val body = bench.body(bench.take())
        assertEquals(schema, body["format"])
        assertNull(body["tools"])
        // Ollama Cloud is not a local server: no truncate and no num_ctx.
        assertNull(body["truncate"])
        assertNull(body["options"]!!.jsonObject["num_ctx"])
    }

    @Test
    fun theStatusErrorsAnUnfinishedReplyAndAMalformedBodyAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"error":"too many requests"}""", "Retry-After" to "1")
        bench.status(500, """{"error":"model runner has unexpectedly stopped"}""")
        bench.reply("ollama/not-done.json")
        bench.reply("ollama/malformed.json")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), ollama.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(1_000, null), errorOf(adapter, request(user("Hi")), ollama))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), ollama))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), ollama))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), ollama))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/LocalServerDiscoveryTest.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.styles.AdapterHarness
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalServerDiscoveryTest {
    private val bench = AdapterHarness()

    @After
    fun close() = bench.close()

    private fun list(kind: LocalServerKind, address: String) =
        runBlocking { Discoveries.forLocalServer(kind, address.toHttpUrl(), NoAuth, bench.http).list() }

    @Test
    fun ollamaListsItsTagsAndAsksEachModelForItsCapabilitiesAtTheOrigin() {
        bench.reply("local-servers/ollama-tags.json")
        bench.reply("local-servers/ollama-show-qwen.json")
        bench.reply("local-servers/ollama-show-llama.json")
        bench.reply("local-servers/ollama-show-embed.json")
        bench.status(500, """{"error":"model runner has unexpectedly stopped"}""")

        val models = list(LocalServerKind.OLLAMA, "http://192.168.1.20:11434/v1")

        // The embedding model is left out, and a model whose /api/show fails keeps unknown capabilities.
        assertEquals(
            listOf(
                DiscoveredModel("qwen3.6:27b", "qwen3.6:27b", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 262144, tools = true),
                DiscoveredModel("llama3.2:3b", "llama3.2:3b", setOf(InputKind.TEXT), contextWindow = 131072, tools = false),
                DiscoveredModel("mistral:7b", "mistral:7b", setOf(InputKind.TEXT)),
            ),
            models,
        )
        assertEquals(
            listOf("http://192.168.1.20:11434/api/tags".toHttpUrl()) + List(4) { "http://192.168.1.20:11434/api/show".toHttpUrl() },
            bench.calls.requests,
        )
        bench.take()
        assertEquals("""{"model":"qwen3.6:27b"}""", bench.take().body!!.utf8())
    }

    @Test
    fun lmStudioListsOnlyItsLanguageModelsWithTheirVisionAndToolFlags() {
        bench.reply("local-servers/lmstudio-models.json")

        val models = list(LocalServerKind.LM_STUDIO, "http://127.0.0.1:1234/v1")

        assertEquals(
            listOf(
                DiscoveredModel("qwen/qwen3.6-27b", "Qwen3.6 27B", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 262144, tools = true),
                DiscoveredModel("google/gemma-4-e4b", "Gemma 4 E4B", setOf(InputKind.TEXT), contextWindow = 131072, tools = false),
            ),
            models,
        )
        assertEquals(listOf("http://127.0.0.1:1234/api/v1/models".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun llamaCppReadsItsModelAndItsProps() {
        bench.reply("local-servers/llamacpp-models.json")
        bench.reply("local-servers/llamacpp-props.json")

        val models = list(LocalServerKind.LLAMA_CPP, "http://10.0.0.5:8080/v1")

        assertEquals(listOf(DiscoveredModel("gemma-4-31b-q4", "gemma-4-31b-q4", setOf(InputKind.TEXT, InputKind.IMAGE), contextWindow = 32768, tools = true)), models)
        assertEquals(listOf("http://10.0.0.5:8080/v1/models", "http://10.0.0.5:8080/props").map { it.toHttpUrl() }, bench.calls.requests)
    }

    @Test
    fun theOtherServersListTheirModelsAndReportNoCapabilities() {
        bench.reply("local-servers/vllm-models.json")

        val models = list(LocalServerKind.VLLM, "http://127.0.0.1:8000/v1")

        assertEquals(listOf(DiscoveredModel("Qwen/Qwen3.6-27B", "Qwen/Qwen3.6-27B", contextWindow = 131072)), models)
        assertEquals(listOf("http://127.0.0.1:8000/v1/models".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun aServerThatAnswersWithoutJsonIsMalformed() {
        bench.status(200, "<html>Not an API</html>")

        try {
            list(LocalServerKind.SGLANG, "http://127.0.0.1:30000/v1")
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.Malformed, e.error)
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'OllamaAdapter'" and "Unresolved reference 'Discoveries'".

- [ ] **Step 4: Write the adapter**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/OllamaAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.bool
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl

/**
 * Ollama's native style (spec 10.3, design D4, D6, D7 and D11), for an Ollama server and Ollama Cloud: `POST
 * <origin>/api/chat` with `"stream": false`, because Ollama's stream is newline-delimited JSON, not server-sent events
 * (design D4). Images go as base64 strings beside the user's text, tool calls carry their arguments as JSON objects, and
 * JSON actions pass the action schema as `format`. Ollama keeps no reasoning state (design D5). Ollama has no tool
 * choice, so [ToolChoice.NONE] leaves out the tools and [ToolChoice.REQUIRED] acts as AUTO. A local server (not Ollama
 * Cloud) gets the model's window up to [MAX_CONTEXT] as its context and is asked to refuse a longer prompt instead of
 * cutting it, as OpenClaw does (`extensions/ollama/src/stream.runtime.ts`); Ollama Cloud keeps its own context.
 */
internal class OllamaAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val url = origin(target.baseUrl).newBuilder().addPathSegments("api/chat").build()
        val reply = http.send(Wire.post(url, body(request, target), target, request, stream = false), target.auth)
        return read(Wire.parse(reply.text()), request, target)
    }

    private fun body(request: ChatRequest, target: ModelTarget): JsonObject = buildJsonObject {
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        put("model", target.sentModelId)
        put("messages", messages(request, tools))
        put("stream", false)
        if (tools && request.toolChoice != ToolChoice.NONE) putJsonArray("tools") {
            for (tool in request.tools) addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                }
            }
        }
        request.jsonSchema?.let { put("format", it) }
        if (Wire.level(request, target.model, compat) != null) put("think", true)
        if (local(target)) {
            put("truncate", false)
            put("shift", false)
        }
        putJsonObject("options") {
            put("num_predict", request.maxOutputTokens)
            // OpenClaw caps only a local server's context, whose memory the window allocates; Ollama Cloud keeps its own.
            if (local(target)) target.model.contextWindow?.takeIf { it > 0 }?.let { put("num_ctx", minOf(it, MAX_CONTEXT)) }
            if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
        }
    }

    /** OpenClaw's `localModel`: neither Ollama Cloud nor a cloud model that a local server passes on. */
    private fun local(target: ModelTarget): Boolean {
        val host = target.baseUrl.host
        val id = target.sentModelId.lowercase()
        return target.providerId != "ollama-cloud" && host != "ollama.com" && !host.endsWith(".ollama.com") &&
            !id.endsWith(":cloud") && !id.endsWith("-cloud")
    }

    private fun messages(request: ChatRequest, tools: Boolean): JsonArray = buildJsonArray {
        if (request.system.isNotBlank()) addJsonObject {
            put("role", "system")
            put("content", request.system)
        }
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> addJsonObject {
                    put("role", "user")
                    put("content", message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text })
                    val images = message.parts.filterIsInstance<Part.Image>()
                    if (images.isNotEmpty()) putJsonArray("images") { images.forEach { add(JsonPrimitive(Wire.base64(it.bytes))) } }
                }
                is ChatMessage.Assistant -> addJsonObject {
                    put("role", "assistant")
                    put("content", Wire.assistantText(message, callsAsText = !tools))
                    if (tools && message.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                        for (call in message.toolCalls) addJsonObject {
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            }
                        }
                    }
                }
                is ChatMessage.ToolResult -> addJsonObject {
                    if (tools) {
                        put("role", "tool")
                        put("tool_name", message.name)
                        put("content", message.text)
                    } else {
                        put("role", "user")
                        put("content", Wire.resultText(message))
                    }
                }
            }
        }
    }

    private fun read(body: JsonObject, request: ChatRequest, target: ModelTarget): ChatResponse {
        body.str("error")?.let { throw ProviderException(Wire.streamError(null, null, target)) }
        if (body.bool("done") != true) throw ProviderException(ProviderError.Malformed)
        val message = body.obj("message") ?: throw ProviderException(ProviderError.Malformed)
        val reply = ReplyBuilder(request)
        reply.text(message.str("content").orEmpty())
        val first = Wire.nextSyntheticNumber(request, SYNTHETIC_ID)
        message.arr("tool_calls").objects().forEachIndexed { index, call ->
            val function = call.obj("function")
            reply.call(call.str("id") ?: "$SYNTHETIC_ID${first + index}", function?.str("name").orEmpty(), function?.get("arguments"))
        }
        val stop = when (body.str("done_reason")) {
            null, "stop" -> StopReason.END
            "length" -> StopReason.MAX_TOKENS
            else -> StopReason.OTHER
        }
        val prompt = body.long("prompt_eval_count")
        val output = body.long("eval_count")
        val usage = if (prompt == null && output == null) {
            Wire.estimate(request, reply.replyChars, message.str("thinking")?.length ?: 0, target.model.reasoning)
        } else {
            val cached = body.long("prompt_eval_cached_count") ?: 0
            Usage(input = Wire.minus(prompt ?: 0, cached), output = output ?: 0, cacheRead = cached)
        }
        return reply.response(stop, usage, body.str("model") ?: target.sentModelId)
    }

    companion object {
        /** The prefix of the IDs that Kestrel gives calls; they are numbered after the highest one in the request. */
        const val SYNTHETIC_ID = "ollama-call-"

        /**
         * The largest `num_ctx` that Kestrel asks for. A model's full window (262,144 tokens for some) can use up the memory
         * of the owner's computer, and an agent step needs far less.
         */
        const val MAX_CONTEXT = 32_768

        /** The server's origin: Ollama's paths start there, whatever path the owner typed (design D11). */
        fun origin(baseUrl: HttpUrl): HttpUrl = baseUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }
}
```

- [ ] **Step 5: Write the local servers' discovery**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/LocalServerDiscovery.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.styles.OllamaAdapter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * The model lists of the local servers (design D11, research r40), read from the address's origin whatever path the
 * owner typed: Ollama's `/api/tags` and `/api/show` (its `capabilities` and `model_info`), LM Studio's `/api/v1/models`
 * (`capabilities.vision` and `trained_for_tool_use`), llama.cpp's `/v1/models` and `/props`, and `/v1/models` for vLLM,
 * SGLang, oMLX and LiteLLM. Where a server reports nothing, [DiscoveredModel.tools] stays null and the profile page asks
 * the owner. A model that reports no tool support gets `tools = false`, so that it uses JSON actions (design D7). An
 * Ollama model whose capabilities lack `completion` (an embedding model) is left out, as OpenClaw does.
 */
internal class LocalServerDiscovery(
    private val kind: LocalServerKind,
    baseUrl: HttpUrl,
    private val auth: RequestAuth,
    private val http: ProviderHttp,
) : ModelDiscovery {
    private val origin = OllamaAdapter.origin(baseUrl)

    override suspend fun list(): List<DiscoveredModel> = when (kind) {
        LocalServerKind.OLLAMA -> ollama()
        LocalServerKind.LM_STUDIO -> lmStudio()
        LocalServerKind.LLAMA_CPP -> llamaCpp()
        LocalServerKind.VLLM, LocalServerKind.SGLANG, LocalServerKind.OMLX, LocalServerKind.LITELLM -> openAiList()
    }

    private suspend fun ollama(): List<DiscoveredModel> =
        objects(get("api/tags")["models"]).mapNotNull { row ->
            val name = row.string("name") ?: row.string("model") ?: return@mapNotNull null
            val show = try {
                read(http.send(request("POST", "api/show", buildJsonObject { put("model", name) }), auth).text())
            } catch (e: ProviderException) {
                null
            }
            val capabilities = (show?.get("capabilities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            if (capabilities != null && "completion" !in capabilities) return@mapNotNull null
            val context = (show?.get("model_info") as? JsonObject)?.entries
                ?.firstOrNull { it.key.endsWith(".context_length") }?.value?.let { (it as? JsonPrimitive)?.longOrNull?.toInt() }
            DiscoveredModel(
                id = name,
                name = name,
                input = if (capabilities?.contains("vision") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = capabilities?.contains("thinking") == true,
                contextWindow = context,
                tools = capabilities?.contains("tools"),
            )
        }

    private suspend fun lmStudio(): List<DiscoveredModel> =
        objects(get("api/v1/models")["models"]).filter { it.string("type") == "llm" }.mapNotNull { row ->
            val id = row.string("key") ?: return@mapNotNull null
            val capabilities = row["capabilities"] as? JsonObject
            DiscoveredModel(
                id = id,
                name = row.string("display_name") ?: id,
                input = if (capabilities?.boolean("vision") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = capabilities?.get("reasoning") is JsonObject,
                contextWindow = row.number("max_context_length"),
                tools = capabilities?.boolean("trained_for_tool_use"),
            )
        }

    private suspend fun llamaCpp(): List<DiscoveredModel> {
        val rows = objects(get("v1/models")["data"])
        val props = try {
            get("props")
        } catch (e: ProviderException) {
            null
        }
        val tools = (props?.get("chat_template_caps") as? JsonObject)?.boolean("supports_tool_calls")
        val vision = (props?.get("modalities") as? JsonObject)?.boolean("vision") == true
        val context = (props?.get("default_generation_settings") as? JsonObject)?.number("n_ctx")
        return rows.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val modalities = ((row["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            DiscoveredModel(
                id = id,
                name = id,
                input = if (vision || modalities?.contains("image") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                contextWindow = context,
                tools = tools,
            )
        }
    }

    private suspend fun openAiList(): List<DiscoveredModel> =
        objects(get("v1/models")["data"]).mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            DiscoveredModel(
                id = id,
                name = id,
                contextWindow = CONTEXT_FIELDS.firstNotNullOfOrNull { row.number(it) },
            )
        }

    private suspend fun get(path: String): JsonObject = read(http.send(request("GET", path, null), auth).text())

    private fun request(method: String, path: String, body: JsonObject?): HttpRequest =
        HttpRequest(
            method = method,
            url = origin.newBuilder().addPathSegments(path).build(),
            headers = mapOf("accept" to "application/json"),
            body = body?.toString()?.encodeToByteArray(),
            contentType = body?.let { "application/json" },
        )

    private fun read(text: String): JsonObject =
        try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)

    private fun objects(element: JsonElement?): List<JsonObject> = (element as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val CONTEXT_FIELDS = listOf("max_model_len", "context_length", "context_window", "context_size")
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/Discoveries.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl

/** Where a profile's model list comes from (design D13). It runs only when a profile is created or the owner taps "Refresh models". */
object Discoveries {
    /** The list of a local server of [kind] at [baseUrl], whose paths start at the address's origin (design D11). */
    fun forLocalServer(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp): ModelDiscovery =
        LocalServerDiscovery(kind, baseUrl, auth, http)
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 61 tests in `:core:providers`: Task 4's 51, `OllamaAdapterTest` 5 and `LocalServerDiscoveryTest` 5.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 61; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit without a finding.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/OllamaAdapter.kt core/providers/src/main/kotlin/dev/kestrel/providers/discovery/LocalServerDiscovery.kt core/providers/src/main/kotlin/dev/kestrel/providers/discovery/Discoveries.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/OllamaAdapterTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/discovery/LocalServerDiscoveryTest.kt core/providers/src/test/resources/fixtures/ollama core/providers/src/test/resources/fixtures/local-servers
git commit -m "feat(providers): add Ollama's style and the local servers' model lists" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Bedrock and Mantle

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/BedrockAdapter.kt`, `SigV4Auth.kt`, `MantleRouting.kt`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/BedrockModels.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/bedrock/` (10 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/SigV4AuthTest.kt`, `BedrockAdapterTest.kt`, `MantleRoutingTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/BedrockModelsTest.kt`

**Interfaces:**
- Consumes: Task 1's `Wire`, `ReplyBuilder` and `AdapterHarness`; Task 3's `AnthropicModels.identity`, `thinksByDefault`, `keepsForcedToolChoice` and `defaultSamplingOnly`; plan 4A's `Credential.AwsAccessKeys`, `RequestAuth`, `ProviderHttp` with its `decorator`, `RequestMeta.logPath`, `ErrorCodes` (which reads `x-amzn-ErrorType`), `DiscoveredModel`, `ModelDiscovery`.
- Produces: `internal class BedrockAdapter(http: ProviderHttp) : ChatAdapter` with `companion object { const val LOG_PATH = "/model/{model}/converse" }`; `class SigV4Auth(keys: Credential.AwsAccessKeys, region: String, service: String = "bedrock", clock: () -> Long = System::currentTimeMillis) : RequestAuth` with `rejected = SignInExpired("amazon-bedrock")`, `internal fun sign(method, url, body): Map<String, String>`, `internal fun canonicalRequest(method, url, body, amzDate): String` and `companion fun encode(value: String, keepSlash: Boolean = false): String`; `data class MantleRoute(val style: RequestStyle, val baseUrl: HttpUrl)` and `object MantleRouting { fun route(modelId: String, region: String): MantleRoute; fun modelsBase(region: String): HttpUrl }`; `internal class BedrockModels(region: String, auth: RequestAuth, http: ProviderHttp) : ModelDiscovery`.

Design D4, D6, D9, D13, D16 and D19; research r34. The Bedrock adapter calls Converse without streaming (`POST <runtime>/model/<model ID>/converse`), because ConverseStream is a binary AWS event stream (design D4). The model ID goes into the path as one segment in AWS's URI encoding (`SigV4Auth.encode`: only `A-Z a-z 0-9 - _ . ~` stay), as the AWS SDK's marshaller writes path labels, so `:` becomes `%3A` and the `/` of an inference-profile ARN `%2F`. Each request carries `RequestMeta.logPath = "/model/{model}/converse"`, so that the network log never records a model ID that can hold the AWS account ID (design D16).

Converse wants alternating roles (review A, round 1, M6): the adapter merges adjacent turns of one role, so a tool result and the next screen become one user turn. Converse refuses `toolUse` and `toolResult` blocks without a `toolConfig` (research r34), so a request without tools, or with `ToolChoice.NONE`, sends its tool history as text (design D5). `toolChoice` goes only for `ToolChoice.REQUIRED`, as `{"any": {}}`, because `auto` is Converse's default and OpenClaw's `convertToolConfig` sends none otherwise. A failed result gets `status: "error"` only for Claude and Nova, the models that AWS documents it for. Converse refuses a blank text block, so blank texts and a blank system prompt are left out and a blank tool result goes as `(no output)`, as OpenClaw's `createBedrockToolResultContent` sends it. Images go as `{"image": {"format", "source": {"bytes": <base64>}}}` in user turns, which is where Converse takes them. Usage (design D6): `inputTokens`, `outputTokens`, `cacheReadInputTokens` and `cacheWriteInputTokens` as they are. AWS's answers for expired or invalid keys (`ExpiredTokenException`, `UnrecognizedClientException`, `InvalidSignatureException`, which arrive as 403 with `x-amzn-ErrorType`) become the credential's own refusal, `target.auth.rejected`: `SignInExpired` for IAM keys, so that the owner enters new keys (design D9), and `Unauthorized` for a Bedrock API key, as its 401 is ("Changes to the design").

Claude's reasoning (design D5 and "Changes to the design"): Claude on Bedrock can think without being asked, and Anthropic then wants its signed reasoning back. So each `reasoningContent` block of a Claude model's reply (`reasoningText` with its `signature`, or `redactedContent`) becomes a `ReasoningState` of style `BEDROCK` and goes back unchanged, in its place before the `toolUse` blocks, only to the same provider and model, as OpenClaw's `convertMessages` (`extensions/amazon-bedrock/stream.runtime.ts`) sends it, but only from and only in a request with tools (Kestrel's rule; OpenClaw sends the blocks also without tools): without tools the history becomes text, which changes the prefix that Anthropic checks for a replayed block. Other models keep no reasoning state. For a model that thinks by default (Task 3's `AnthropicModels.thinksByDefault`, the rule that the Anthropic adapter uses too), a required tool choice goes as `{"auto": {}}`, as OpenClaw's `normalizeAdaptiveClaudeToolChoice` does (except for Claude Haiku 5.5, `keepsForcedToolChoice`), and no temperature is sent; Claude models that take only their default sampling (`defaultSamplingOnly`) get no temperature either.

`SigV4Auth` signs a request for IAM access keys (design D9): the signing name is `bedrock` for both hosts, the region is the host's, and it signs `host`, `x-amz-date` and, for temporary keys, `x-amz-security-token`. The canonical path encodes the request's encoded path once more, as the AWS SDK for Java v2 does for every service but S3 (`core/http-auth-aws/…/internal/signer/V4CanonicalRequest.java`, `getCanonicalUri` with `doubleUrlEncode`; design D19): a model ID that the URL carries as `%3A` is signed as `%253A`. AWS's SigV4 test-suite vectors (`awslabs/aws-c-auth`, `tests/aws-signing-test-suite/v4/`) check the rest. No live Bedrock test exists, so the encoding of a real model ID on the wire stays unchecked (design D19).

`MantleRouting` routes a Bedrock Mantle model as OpenClaw does (research r34): `openai.gpt-<n>…` to Responses at `https://bedrock-mantle.<region>.api.aws/openai/v1`, Claude to Anthropic Messages at `…/anthropic`, and the rest to Chat Completions at `…/v1`; the region must be a plain name, because it becomes part of a host. `BedrockModels` reads Bedrock's lists from `https://bedrock.<region>.amazonaws.com` with OpenClaw's filters (design D13, research r34 §4), which leave the Mythos preview out of both the foundation models and the inference profiles (`extensions/amazon-bedrock/discovery.ts`).

- [ ] **Step 1: Write the fixtures**

These follow AWS's Converse reference (`https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html`: `output.message.content` with `text`, `toolUse` and `reasoningContent`, `stopReason`, `usage` with the cache counts) and the references of `ListFoundationModels` and `ListInferenceProfiles`, as research r34 quotes them. `thinking-tool-call.json` holds a signed `reasoningText` and a `redactedContent` before a `toolUse`, in the shape of AWS's `ReasoningContentBlock`. `mantle-models.json` belongs to Task 9.

Create `core/providers/src/test/resources/fixtures/bedrock/foundation-models.json`:

```json
{
  "modelSummaries": [
    {
      "modelId": "anthropic.claude-sonnet-5-v1:0",
      "modelName": "Claude Sonnet 5",
      "providerName": "Anthropic",
      "inputModalities": [
        "TEXT",
        "IMAGE"
      ],
      "outputModalities": [
        "TEXT"
      ],
      "responseStreamingSupported": true,
      "inferenceTypesSupported": [
        "INFERENCE_PROFILE"
      ],
      "modelLifecycle": {
        "status": "ACTIVE"
      }
    },
    {
      "modelId": "amazon.nova-pro-v1:0",
      "modelName": "Nova Pro",
      "providerName": "Amazon",
      "inputModalities": [
        "TEXT",
        "IMAGE"
      ],
      "outputModalities": [
        "TEXT"
      ],
      "responseStreamingSupported": true,
      "inferenceTypesSupported": [
        "ON_DEMAND"
      ],
      "modelLifecycle": {
        "status": "ACTIVE"
      }
    },
    {
      "modelId": "meta.llama3-8b-instruct-v1:0",
      "modelName": "Llama 3 8B Instruct",
      "providerName": "Meta",
      "inputModalities": [
        "TEXT"
      ],
      "outputModalities": [
        "TEXT"
      ],
      "responseStreamingSupported": true,
      "inferenceTypesSupported": [
        "ON_DEMAND"
      ],
      "modelLifecycle": {
        "status": "LEGACY"
      }
    },
    {
      "modelId": "amazon.titan-embed-text-v2:0",
      "modelName": "Titan Text Embeddings V2",
      "providerName": "Amazon",
      "inputModalities": [
        "TEXT"
      ],
      "outputModalities": [
        "EMBEDDING"
      ],
      "responseStreamingSupported": false,
      "inferenceTypesSupported": [
        "ON_DEMAND"
      ],
      "modelLifecycle": {
        "status": "ACTIVE"
      }
    },
    {
      "modelId": "anthropic.claude-mythos-preview-v1:0",
      "modelName": "Claude Mythos Preview",
      "providerName": "Anthropic",
      "inputModalities": [
        "TEXT",
        "IMAGE"
      ],
      "outputModalities": [
        "TEXT"
      ],
      "responseStreamingSupported": true,
      "inferenceTypesSupported": [
        "ON_DEMAND"
      ],
      "modelLifecycle": {
        "status": "ACTIVE"
      }
    }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/inference-profiles-1.json`:

```json
{
  "inferenceProfileSummaries": [
    {
      "inferenceProfileId": "us.anthropic.claude-sonnet-5-v1:0",
      "inferenceProfileName": "US Claude Sonnet 5",
      "inferenceProfileArn": "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-sonnet-5-v1:0",
      "status": "ACTIVE",
      "type": "SYSTEM_DEFINED",
      "models": [
        {
          "modelArn": "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-5-v1:0"
        }
      ]
    },
    {
      "inferenceProfileId": "global.anthropic.claude-mythos-preview-v1:0",
      "inferenceProfileName": "Global Claude Mythos Preview",
      "inferenceProfileArn": "arn:aws:bedrock:us-east-1:123456789012:inference-profile/global.anthropic.claude-mythos-preview-v1:0",
      "status": "ACTIVE",
      "type": "SYSTEM_DEFINED",
      "models": [
        {
          "modelArn": "arn:aws:bedrock::foundation-model/anthropic.claude-mythos-preview-v1:0"
        }
      ]
    },
    {
      "inferenceProfileId": "eu.amazon.nova-pro-v1:0",
      "inferenceProfileName": "EU Nova Pro",
      "status": "DISABLED",
      "type": "SYSTEM_DEFINED",
      "models": []
    }
  ],
  "nextToken": "page-2"
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/inference-profiles-2.json`:

```json
{
  "inferenceProfileSummaries": [
    {
      "inferenceProfileId": "global.anthropic.claude-sonnet-5-v1:0",
      "inferenceProfileName": "Global Claude Sonnet 5",
      "status": "ACTIVE",
      "type": "SYSTEM_DEFINED",
      "models": []
    }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/malformed.json`:

```json
{"output":{"message":
```

Create `core/providers/src/test/resources/fixtures/bedrock/no-usage.json`:

```json
{
  "output": {
    "message": {
      "role": "assistant",
      "content": [
        {
          "text": "Hello, owner."
        }
      ]
    }
  },
  "stopReason": "end_turn",
  "metrics": {
    "latencyMs": 800
  }
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/text.json`:

```json
{
  "output": {
    "message": {
      "role": "assistant",
      "content": [
        {
          "text": "Hello, owner."
        }
      ]
    }
  },
  "stopReason": "end_turn",
  "metrics": {
    "latencyMs": 800
  },
  "usage": {
    "inputTokens": 100,
    "outputTokens": 12,
    "totalTokens": 1062,
    "cacheReadInputTokens": 900,
    "cacheWriteInputTokens": 50
  }
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/thinking-tool-call.json`:

```json
{
  "output": {
    "message": {
      "role": "assistant",
      "content": [
        {
          "reasoningContent": {
            "reasoningText": {
              "text": "The owner wants the camera.",
              "signature": "RXF3U2lnbmF0dXJl"
            }
          }
        },
        {
          "reasoningContent": {
            "redactedContent": "UmVkYWN0ZWQ="
          }
        },
        {
          "toolUse": {
            "toolUseId": "tooluse_t",
            "name": "open_app",
            "input": {
              "name": "Camera"
            }
          }
        }
      ]
    }
  },
  "stopReason": "tool_use",
  "metrics": {
    "latencyMs": 900
  },
  "usage": {
    "inputTokens": 300,
    "outputTokens": 60,
    "totalTokens": 360
  }
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/tool-call.json`:

```json
{
  "output": {
    "message": {
      "role": "assistant",
      "content": [
        {
          "text": "I will open it."
        },
        {
          "toolUse": {
            "toolUseId": "tooluse_1",
            "name": "open_app",
            "input": {
              "name": "Camera"
            }
          }
        }
      ]
    }
  },
  "stopReason": "tool_use",
  "metrics": {
    "latencyMs": 800
  },
  "usage": {
    "inputTokens": 300,
    "outputTokens": 25,
    "totalTokens": 325
  }
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/tool-result.request.json`:

```json
{
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "text": "Open the camera."
        }
      ]
    },
    {
      "role": "assistant",
      "content": [
        {
          "text": "I will open it."
        },
        {
          "toolUse": {
            "toolUseId": "tooluse_1",
            "name": "open_app",
            "input": {
              "name": "Camera"
            }
          }
        }
      ]
    },
    {
      "role": "user",
      "content": [
        {
          "toolResult": {
            "toolUseId": "tooluse_1",
            "content": [
              {
                "text": "Opened."
              }
            ]
          }
        },
        {
          "text": "The screen now."
        },
        {
          "image": {
            "format": "png",
            "source": {
              "bytes": "iVBORw0KGgo="
            }
          }
        }
      ]
    }
  ],
  "system": [
    {
      "text": "You are Kestrel."
    }
  ],
  "inferenceConfig": {
    "maxTokens": 256
  },
  "toolConfig": {
    "tools": [
      {
        "toolSpec": {
          "name": "open_app",
          "description": "Opens an app by its name.",
          "inputSchema": {
            "json": {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "The app's name."
                }
              },
              "required": [
                "name"
              ]
            }
          }
        }
      }
    ]
  }
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/two-calls.json`:

```json
{
  "output": {
    "message": {
      "role": "assistant",
      "content": [
        {
          "toolUse": {
            "toolUseId": "tooluse_a",
            "name": "open_app",
            "input": {
              "name": "Camera"
            }
          }
        },
        {
          "toolUse": {
            "toolUseId": "tooluse_b",
            "name": "open_app",
            "input": {
              "name": "Clock"
            }
          }
        }
      ]
    }
  },
  "stopReason": "tool_use",
  "metrics": {
    "latencyMs": 800
  },
  "usage": {
    "inputTokens": 300,
    "outputTokens": 30,
    "totalTokens": 330
  }
}
```

- [ ] **Step 2: Write the failing tests**

The four SigV4 vectors are AWS's `get-vanilla`, `post-vanilla`, `get-vanilla-query-order-key-case` and `post-sts-header-before` (service `service`, region `us-east-1`, the time 2015-08-30T12:36:00Z, the example keys).

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/SigV4AuthTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * AWS's SigV4 test-suite vectors (`awslabs/aws-c-auth`, `tests/aws-signing-test-suite/v4/<case>/`: `context.json`,
 * `request.txt`, `header-canonical-request.txt` and `header-signed-request.txt`), and the double encoding of a model ID
 * in the canonical path that the AWS SDK for Java v2 uses (design D19).
 */
class SigV4AuthTest {
    private val clock = { Instant.parse("2015-08-30T12:36:00Z").toEpochMilli() }
    private val keys = Credential.AwsAccessKeys("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
    private val signer = SigV4Auth(keys, "us-east-1", "service", clock)

    private fun authorization(signed: String, signature: String) =
        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=$signed, Signature=$signature"

    @Test
    fun getVanilla() {
        val headers = signer.sign("GET", "https://example.amazonaws.com/".toHttpUrl(), ByteArray(0))

        assertEquals("20150830T123600Z", headers["x-amz-date"])
        assertEquals(authorization("host;x-amz-date", "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"), headers["Authorization"])
    }

    @Test
    fun postVanilla() {
        val headers = signer.sign("POST", "https://example.amazonaws.com/".toHttpUrl(), ByteArray(0))

        assertEquals(authorization("host;x-amz-date", "5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b"), headers["Authorization"])
    }

    @Test
    fun getVanillaQueryOrderKeyCaseSortsTheQuery() {
        val url = "https://example.amazonaws.com/?Param2=value2&Param1=value1".toHttpUrl()

        assertEquals(
            "GET\n/\nParam1=value1&Param2=value2\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            signer.canonicalRequest("GET", url, ByteArray(0), "20150830T123600Z"),
        )
        assertEquals(authorization("host;x-amz-date", "b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500"), signer.sign("GET", url, ByteArray(0))["Authorization"])
    }

    @Test
    fun postStsHeaderBeforeSignsTheSessionToken() {
        val token = "AQoDYXdzEPT//////////wEXAMPLEtc764bNrC9SAPBSM22wDOk4x4HIZ8j4FZTwdQWLWsKWHGBuFqwAeMicRXmxfpSPfIeoIYRqTflfKD8YUuwthAx7mSEI/" +
            "qkPpKPi/kMcGdQrmGdeehM4IC1NtBmUpp2wUE8phUZampKsburEDy0KPkyQDYwT7WZ0wq5VSXDvp75YU9HFvlRd8Tx6q6fE8YQcHNVXAkiY9q6d+xo0rKwT38xVqr7ZD0u0iPPk" +
            "UL64lIZbqBAz+scqKmlzm8FDrypNC9Yjc8fPOLn9FX9KSYvKTr4rvx3iSIlTJabIQwj2ICCR/oLxBA=="
        val withToken = SigV4Auth(keys.copy(sessionToken = token), "us-east-1", "service", clock)

        val headers = withToken.sign("POST", "https://example.amazonaws.com/".toHttpUrl(), ByteArray(0))

        assertEquals(token, headers["x-amz-security-token"])
        assertEquals(authorization("host;x-amz-date;x-amz-security-token", "85d96828115b5dc0cfc3bd16ad9e210dd772bbebba041836c64533a82be05ead"), headers["Authorization"])
    }

    @Test
    fun aModelIdIsEncodedInTheUrlAndOnceMoreInTheCanonicalPath() {
        val arn = "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-sonnet-5-v1:0"
        val url = "https://bedrock-runtime.us-east-1.amazonaws.com".toHttpUrl().newBuilder()
            .addPathSegment("model").addEncodedPathSegment(SigV4Auth.encode(arn)).addPathSegment("converse").build()

        assertEquals("/model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile%2Fus.anthropic.claude-sonnet-5-v1%3A0/converse", url.encodedPath)
        val canonicalPath = signer.canonicalRequest("POST", url, ByteArray(0), "20150830T123600Z").lines()[1]
        assertEquals("/model/arn%253Aaws%253Abedrock%253Aus-east-1%253A123456789012%253Ainference-profile%252Fus.anthropic.claude-sonnet-5-v1%253A0/converse", canonicalPath)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/BedrockAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures follow AWS's Converse API reference (`Converse`, `ContentBlock`, `ReasoningContentBlock`, `ToolChoice` and
 * `TokenUsage`), as research r34 read them, because no request went to Bedrock.
 */
class BedrockAdapterTest {
    private val bench = AdapterHarness()
    private val logPaths = mutableListOf<String?>()
    private val adapter = BedrockAdapter(ProviderHttp(bench.calls, decorator = { _, meta -> logPaths += meta.logPath }))
    private val bedrock = target(
        "amazon-bedrock", RequestStyle.BEDROCK, "https://bedrock-runtime.us-east-1.amazonaws.com",
        ModelEntry("anthropic.claude-sonnet-5-v1:0", "Claude Sonnet 5"),
    )

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsReadWithItsUsageAndTheLogSeesNoModelId() = runBlocking {
        bench.reply("bedrock/text.json")

        val response = adapter.complete(request(user("Hi")), bedrock)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 100, output = 12, cacheRead = 900, cacheWrite = 50), response.usage)
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-sonnet-5-v1%3A0/converse", bench.calls.requests.single().toString())
        assertEquals(listOf<String?>("/model/{model}/converse"), logPaths)
        assertEquals("application/json", bench.take().headers["accept"])
    }

    @Test
    fun theToolResultAndTheNextScreenBecomeOneUserTurn() = runBlocking {
        bench.reply("bedrock/tool-call.json")
        bench.reply("bedrock/text.json")

        val first = adapter.complete(request(user("Open the camera.")), bedrock)

        assertEquals(
            listOf(AssistantPart.Text("I will open it."), AssistantPart.ToolCall("tooluse_1", "open_app", obj("""{"name":"Camera"}"""))),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        val next = request(
            user("Open the camera."),
            first.message,
            ChatMessage.ToolResult("tooluse_1", "open_app", "Opened."),
            ChatMessage.User(listOf(Part.Text("The screen now."), Part.Image(ImageType.PNG, SCREENSHOT))),
        )
        adapter.complete(next, bedrock)

        bench.take()
        bench.assertBody("bedrock/tool-result.request.json", bench.take())
    }

    @Test
    fun twoCallsAreKeptAndTheirResultsShareOneTurnWithTheFailedStatusForClaude() = runBlocking {
        bench.reply("bedrock/two-calls.json")
        bench.reply("bedrock/text.json")

        val first = adapter.complete(request(user("Open the camera and the clock.")), bedrock)

        assertEquals(listOf("tooluse_a", "tooluse_b"), first.message.toolCalls.map { it.id })
        adapter.complete(
            request(
                user("Open the camera and the clock."),
                first.message,
                ChatMessage.ToolResult("tooluse_a", "open_app", " "),
                ChatMessage.ToolResult("tooluse_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
            ) { copy(system = " ") },
            bedrock,
        )

        bench.take()
        val body = bench.body(bench.take())
        // Converse refuses blank text: no system block, and a blank tool result says "(no output)".
        assertNull(body["system"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals(
            json("""[{"toolResult":{"toolUseId":"tooluse_a","content":[{"text":"(no output)"}]}},{"toolResult":{"toolUseId":"tooluse_b","content":[{"text":"Not run: Kestrel runs one action per step"}],"status":"error"}}]"""),
            messages[2].jsonObject["content"],
        )
    }

    @Test
    fun claudesSignedReasoningGoesBackAndAModelThatThinksByDefaultGetsNoForcedChoiceOrTemperature() = runBlocking {
        bench.reply("bedrock/thinking-tool-call.json")
        bench.reply("bedrock/text.json")
        bench.reply("bedrock/text.json")
        val sonnet46 = bedrock.copy(model = ModelEntry("anthropic.claude-sonnet-4-6", "Claude Sonnet 4.6"), sentModelId = "anthropic.claude-sonnet-4-6")

        val first = adapter.complete(request(user("Open the camera.")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, bedrock)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("tooluse_t", "open_app", "Opened.")), bedrock)
        adapter.complete(request(user("Open the camera.")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, sonnet46)

        val signed = obj("""{"reasoningContent":{"reasoningText":{"text":"The owner wants the camera.","signature":"RXF3U2lnbmF0dXJl"}}}""")
        val redacted = obj("""{"reasoningContent":{"redactedContent":"UmVkYWN0ZWQ="}}""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.BEDROCK, "amazon-bedrock", "anthropic.claude-sonnet-5-v1:0", signed)),
                AssistantPart.Reasoning(ReasoningState(RequestStyle.BEDROCK, "amazon-bedrock", "anthropic.claude-sonnet-5-v1:0", redacted)),
                AssistantPart.ToolCall("tooluse_t", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        val forced = bench.body(bench.take())
        assertEquals(json("""{"auto":{}}"""), forced["toolConfig"]!!.jsonObject["toolChoice"])
        assertNull(forced["inferenceConfig"]!!.jsonObject["temperature"])
        val replayed = bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf(signed, redacted), replayed.take(2))
        val plain = bench.body(bench.take())
        assertEquals(json("""{"any":{}}"""), plain["toolConfig"]!!.jsonObject["toolChoice"])
        assertEquals(json("0.2"), plain["inferenceConfig"]!!.jsonObject["temperature"])

        // Without tools the history is text, so no reasoning goes back, and the reply to such a request keeps none.
        bench.reply("bedrock/thinking-tool-call.json")
        val withoutTools = adapter.complete(
            request(user("Open the camera."), first.message, ChatMessage.ToolResult("tooluse_t", "open_app", "Opened.")) { copy(toolChoice = ToolChoice.NONE) },
            bedrock,
        )
        val textOnly = bench.body(bench.take())
        assertNull(textOnly["toolConfig"])
        assertEquals(
            json("""[{"text":"[tool call id=tooluse_t name=open_app] {\"name\":\"Camera\"}"}]"""),
            textOnly["messages"]!!.jsonArray[1].jsonObject["content"],
        )
        assertTrue(withoutTools.message.parts.none { it is AssistantPart.Reasoning })
        // OpenClaw keeps a forced tool choice for Claude Haiku 5.5.
        bench.reply("bedrock/text.json")
        val haiku55 = bedrock.copy(model = ModelEntry("anthropic.claude-haiku-5-5-v1:0", "Claude Haiku 5.5"), sentModelId = "anthropic.claude-haiku-5-5-v1:0")
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, haiku55)
        val haiku = bench.body(bench.take())
        assertEquals(json("""{"any":{}}"""), haiku["toolConfig"]!!.jsonObject["toolChoice"])
        assertNull(haiku["inferenceConfig"]!!.jsonObject["temperature"])
    }

    @Test
    fun withoutToolsTheHistoryIsTextAndThereIsNoToolConfig() = runBlocking {
        bench.reply("bedrock/no-usage.json")
        val call = AssistantPart.ToolCall("tooluse_1", "open_app", obj("""{"name":"Camera"}"""))

        val response = adapter.complete(
            request(user("Open the camera."), ChatMessage.Assistant(listOf(call)), ChatMessage.ToolResult("tooluse_1", "open_app", "Opened."), tools = emptyList()),
            bedrock,
        )

        assertTrue(response.usage.estimated)
        val body = bench.body(bench.take())
        assertNull(body["toolConfig"])
        assertEquals(
            json("""[{"role":"user","content":[{"text":"Open the camera."}]},{"role":"assistant","content":[{"text":"[tool call id=tooluse_1 name=open_app] {\"name\":\"Camera\"}"}]},{"role":"user","content":[{"text":"[tool result id=tooluse_1 name=open_app] Opened."}]}]"""),
            body["messages"],
        )
    }

    @Test
    fun iamKeysSignTheRequestAndAwsTokenErrorsAreTheCredentialsOwnRefusal() = runBlocking {
        val unrecognized = "UnrecognizedClientException:http://internal.amazon.com/coral/com.amazon.coral.service/"
        bench.status(403, """{"message":"The security token included in the request is invalid."}""", "x-amzn-ErrorType" to unrecognized)
        bench.status(403, """{"message":"The security token included in the request is invalid."}""", "x-amzn-ErrorType" to unrecognized)
        val keys = Credential.AwsAccessKeys("AKIDEXAMPLE", "secret", "session-token")
        val signed = bedrock.copy(auth = SigV4Auth(keys, "us-east-1", clock = { 0L }))

        assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(adapter, request(user("Hi")), signed))
        // An API key that AWS refuses is a refused key, as its 401 is.
        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), bedrock))
        val recorded = bench.take()
        assertTrue(recorded.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/19700101/us-east-1/bedrock/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature="))
        assertEquals("session-token", recorded.headers["x-amz-security-token"])
        assertEquals("19700101T000000Z", recorded.headers["x-amz-date"])
    }

    @Test
    fun theOtherStatusErrorsAndAMalformedBodyAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(403, """{"message":"denied"}""", "x-amzn-ErrorType" to "AccessDeniedException")
        bench.status(429, """{"message":"slow"}""", "x-amzn-ErrorType" to "ThrottlingException", "Retry-After" to "2")
        bench.status(500)
        bench.reply("bedrock/malformed.json")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), bedrock.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.QuotaOrPolicy(403, "AccessDeniedException"), errorOf(adapter, request(user("Hi")), bedrock))
        assertEquals(ProviderError.RateLimited(2_000, "ThrottlingException"), errorOf(adapter, request(user("Hi")), bedrock))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), bedrock))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), bedrock))
    }

}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/MantleRoutingTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MantleRoutingTest {
    @Test
    fun eachModelGoesToItsApi() {
        assertEquals(MantleRoute(RequestStyle.RESPONSES, "https://bedrock-mantle.us-east-1.api.aws/openai/v1".toHttpUrl()), MantleRouting.route("openai.gpt-6-luna", "us-east-1"))
        assertEquals(MantleRoute(RequestStyle.ANTHROPIC, "https://bedrock-mantle.eu-west-1.api.aws/anthropic".toHttpUrl()), MantleRouting.route("anthropic.claude-opus-5", "eu-west-1"))
        assertEquals(MantleRoute(RequestStyle.CHAT_COMPLETIONS, "https://bedrock-mantle.us-east-1.api.aws/v1".toHttpUrl()), MantleRouting.route("openai.gpt-oss-120b", "us-east-1"))
        assertEquals("https://bedrock-mantle.us-west-2.api.aws/v1".toHttpUrl(), MantleRouting.modelsBase("us-west-2"))
    }

    @Test
    fun aRegionThatIsNoPlainNameIsRefused() {
        val refused = runCatching { MantleRouting.route("qwen.qwen3-32b", "us-east-1.evil.example/") }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/BedrockModelsTest.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.styles.AdapterHarness
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BedrockModelsTest {
    private val bench = AdapterHarness()

    @After
    fun close() = bench.close()

    @Test
    fun theListKeepsActiveOnDemandModelsAndActiveProfilesWithGlobalProfilesFirst() {
        bench.reply("bedrock/foundation-models.json")
        bench.reply("bedrock/inference-profiles-1.json")
        bench.reply("bedrock/inference-profiles-2.json")

        val models = runBlocking { BedrockModels("us-east-1", NoAuth, bench.http).list() }

        val both = setOf(InputKind.TEXT, InputKind.IMAGE)
        assertEquals(
            listOf(
                DiscoveredModel("global.anthropic.claude-sonnet-5-v1:0", "Global Claude Sonnet 5", both),
                DiscoveredModel("us.anthropic.claude-sonnet-5-v1:0", "US Claude Sonnet 5", both),
                DiscoveredModel("amazon.nova-pro-v1:0", "Nova Pro", both),
            ),
            models,
        )
        assertEquals(
            listOf(
                "https://bedrock.us-east-1.amazonaws.com/foundation-models",
                "https://bedrock.us-east-1.amazonaws.com/inference-profiles?maxResults=1000",
                "https://bedrock.us-east-1.amazonaws.com/inference-profiles?maxResults=1000&nextToken=page-2",
            ).map { it.toHttpUrl() },
            bench.calls.requests,
        )
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'SigV4Auth'", "Unresolved reference 'BedrockAdapter'", "Unresolved reference 'MantleRouting'" and "Unresolved reference 'BedrockModels'".

- [ ] **Step 4: Write the signer**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/SigV4Auth.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs a request with AWS Signature Version 4 for IAM access keys (design D9, research r34): Bedrock's runtime and its
 * control plane both use the signing name `bedrock`, and the region is the host's. It signs `host`, `x-amz-date` and, for
 * temporary keys, `x-amz-security-token`. The canonical path encodes the request's already encoded path once more, as the
 * AWS SDK for Java v2 does for every service but S3 (`V4CanonicalRequest.getCanonicalUri` with `doubleUrlEncode`), so a
 * model ID that the URL carries as `%3A` is signed as `%253A`. AWS's SigV4 test-suite vectors check the rest; no live
 * Bedrock test checks a real model ID's encoding (design D19).
 */
class SigV4Auth(
    private val keys: Credential.AwsAccessKeys,
    private val region: String,
    private val service: String = "bedrock",
    private val clock: () -> Long = System::currentTimeMillis,
) : RequestAuth {
    /** Refused keys need new keys, so a refusal is a sign-in that the owner must renew (design D9). */
    override val rejected: ProviderError get() = ProviderError.SignInExpired("amazon-bedrock")

    override suspend fun headers(request: HttpRequest): Map<String, String> = sign(request.method, request.url, request.body ?: ByteArray(0))

    internal fun sign(method: String, url: HttpUrl, body: ByteArray): Map<String, String> {
        val amzDate = DATE_TIME.format(Instant.ofEpochMilli(clock()))
        val date = amzDate.take(8)
        val signed = signedHeaders(url, amzDate)
        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest(method, url, body, amzDate).encodeToByteArray()))).joinToString("\n")
        var key = hmac("AWS4${keys.secretAccessKey}".encodeToByteArray(), date)
        for (part in listOf(region, service, "aws4_request")) key = hmac(key, part)
        val signature = hex(hmac(key, stringToSign))
        val headers = linkedMapOf(
            "x-amz-date" to amzDate,
            "Authorization" to "AWS4-HMAC-SHA256 Credential=${keys.accessKeyId}/$scope, SignedHeaders=${signed.keys.joinToString(";")}, Signature=$signature",
        )
        keys.sessionToken?.let { headers["x-amz-security-token"] = it }
        return headers
    }

    /** The six lines of SigV4's canonical request. */
    internal fun canonicalRequest(method: String, url: HttpUrl, body: ByteArray, amzDate: String): String {
        val headers = signedHeaders(url, amzDate)
        val query = (0 until url.querySize)
            .map { encode(url.queryParameterName(it)) to encode(url.queryParameterValue(it).orEmpty()) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        return listOf(
            method,
            encode(url.encodedPath.ifEmpty { "/" }, keepSlash = true),
            query,
            headers.entries.joinToString("") { "${it.key}:${it.value}\n" },
            headers.keys.joinToString(";"),
            hex(sha256(body)),
        ).joinToString("\n")
    }

    private fun signedHeaders(url: HttpUrl, amzDate: String): Map<String, String> {
        val headers = sortedMapOf("host" to if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}", "x-amz-date" to amzDate)
        keys.sessionToken?.let { headers["x-amz-security-token"] = it.trim() }
        return headers
    }

    override fun toString(): String = "SigV4Auth(region=$region, service=$service, keys=$keys)"

    companion object {
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** AWS's URI encoding (RFC 3986): only `A-Z a-z 0-9 - _ . ~` stay, and `/` too when [keepSlash] is true. */
        fun encode(value: String, keepSlash: Boolean = false): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val char = (byte.toInt() and 0xff).toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~" || (keepSlash && char == '/')) {
                    append(char)
                } else {
                    append('%').append("%02X".format(byte.toInt() and 0xff))
                }
            }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.encodeToByteArray())

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
```

- [ ] **Step 5: Write the adapter and Mantle's routing**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/BedrockAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Amazon Bedrock's Converse style (spec 10.3, design D4, D6, D9 and D16, research r34): `POST
 * <runtime>/model/<model ID>/converse`, not streamed, because ConverseStream is a binary AWS event stream. Converse wants
 * alternating roles, so tool results and the next user message become one user turn; it refuses `toolUse` and
 * `toolResult` blocks without a `toolConfig`, so a request without tools sends its tool history as text. The network log
 * records `/model/{model}/converse`, because a model ID can be an inference-profile ARN with the AWS account ID. AWS's
 * answers for expired or invalid keys (403 with `ExpiredTokenException`, `UnrecognizedClientException` or
 * `InvalidSignatureException`) are the credential's own refusal (`rejected`): new keys for IAM keys, and a new key for
 * an API key. A Claude model's `reasoningContent` blocks (signed reasoning text or redacted content) go back unchanged,
 * in their order, to the same provider and model, as in OpenClaw's `convertMessages`
 * (`extensions/amazon-bedrock/stream.runtime.ts`); other models keep no reasoning state (design D5). A Claude model that
 * thinks by default ([AnthropicModels.thinksByDefault]) gets a required tool choice as `auto` (except Claude Haiku 5.5), as
 * in OpenClaw's `normalizeAdaptiveClaudeToolChoice`, and no temperature, which every Claude 5 model refuses. Without tools
 * the history becomes text, so reasoning blocks are neither kept from nor sent in such a request (Anthropic checks the
 * prefix that produced them).
 */
internal class BedrockAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val url = target.baseUrl.newBuilder()
            .addPathSegment("model")
            .addEncodedPathSegment(SigV4Auth.encode(target.sentModelId))
            .addPathSegment("converse")
            .build()
        val reply = try {
            http.send(Wire.post(url, body(request, target), target, request, stream = false, logPath = LOG_PATH), target.auth)
        } catch (e: ProviderException) {
            val code = when (val error = e.error) {
                is ProviderError.QuotaOrPolicy -> error.code
                is ProviderError.BadRequest -> error.code
                else -> null
            }
            if (code in TOKEN_ERRORS) throw ProviderException(target.auth.rejected)
            throw e
        }
        return read(Wire.parse(reply.text()), request, target)
    }

    private fun body(request: ChatRequest, target: ModelTarget): JsonObject = buildJsonObject {
        val tools = sendsTools(request, target)
        val thinks = AnthropicModels.thinksByDefault(target.sentModelId)
        val sampling = !thinks && !AnthropicModels.defaultSamplingOnly(target.sentModelId) && target.model.compat.supportsTemperature != false
        put("messages", messages(request, target, tools))
        if (request.system.isNotBlank()) putJsonArray("system") { addJsonObject { put("text", request.system) } }
        putJsonObject("inferenceConfig") {
            put("maxTokens", request.maxOutputTokens)
            if (request.temperature != null && sampling) put("temperature", request.temperature)
        }
        if (tools) putJsonObject("toolConfig") {
            putJsonArray("tools") {
                for (tool in request.tools) addJsonObject {
                    putJsonObject("toolSpec") {
                        put("name", tool.name)
                        put("description", tool.description)
                        putJsonObject("inputSchema") { put("json", tool.parameters) }
                    }
                }
            }
            // `auto` is Converse's default, so only a required choice is sent, as OpenClaw's convertToolConfig does.
            if (request.toolChoice == ToolChoice.REQUIRED) putJsonObject("toolChoice") { putJsonObject(if (thinks && !AnthropicModels.keepsForcedToolChoice(target.sentModelId)) "auto" else "any") {} }
        }
    }

    /** Adjacent turns of one role become one turn, so that the roles alternate. */
    private fun messages(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray {
        val turns = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun add(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else turns += role to blocks.toMutableList()
        }
        val statusAllowed = Regex("anthropic|claude|nova").containsMatchIn(target.sentModelId.lowercase())
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> add("user", message.parts.mapNotNull { part ->
                    when (part) {
                        is Part.Text -> part.text.takeIf { it.isNotBlank() }?.let(::text)
                        is Part.Image -> buildJsonObject {
                            putJsonObject("image") {
                                put("format", if (part.type == ImageType.PNG) "png" else "jpeg")
                                putJsonObject("source") { put("bytes", Wire.base64(part.bytes)) }
                            }
                        }
                    }
                })
                is ChatMessage.Assistant -> add("assistant", message.parts.mapNotNull { part ->
                    when (part) {
                        is AssistantPart.Text -> part.text.takeIf { it.isNotBlank() }?.let(::text)
                        is AssistantPart.ToolCall -> if (tools) buildJsonObject {
                            putJsonObject("toolUse") {
                                put("toolUseId", part.id)
                                put("name", part.name)
                                put("input", part.arguments)
                            }
                        } else text(Wire.callText(part))
                        is AssistantPart.Reasoning -> part.state
                            // Without tools the history becomes text, which changes the prefix that Anthropic checks for a replayed block.
                            .takeIf { tools && it.style == RequestStyle.BEDROCK && it.providerId == target.providerId && it.modelId == target.sentModelId }
                            ?.payload as? JsonObject
                    }
                })
                is ChatMessage.ToolResult -> add("user", listOf(if (tools) buildJsonObject {
                    putJsonObject("toolResult") {
                        put("toolUseId", message.callId)
                        // Converse refuses a blank text block; OpenClaw's createBedrockToolResultContent sends this instead.
                        putJsonArray("content") { addJsonObject { put("text", message.text.ifBlank { "(no output)" }) } }
                        if (message.failed && statusAllowed) put("status", "error")
                    }
                } else text(Wire.resultText(message))))
            }
        }
        return buildJsonArray {
            for ((role, blocks) in turns) addJsonObject {
                put("role", role)
                put("content", JsonArray(blocks))
            }
        }
    }

    /** Whether the request sends its tools: Converse has no "none" choice and refuses tool blocks without a toolConfig. */
    private fun sendsTools(request: ChatRequest, target: ModelTarget): Boolean =
        request.tools.isNotEmpty() && request.toolChoice != ToolChoice.NONE && target.model.compat.supportsTools != false

    private fun text(value: String): JsonObject = buildJsonObject { put("text", value) }

    private fun read(body: JsonObject, request: ChatRequest, target: ModelTarget): ChatResponse {
        val message = body.obj("output")?.obj("message") ?: throw ProviderException(ProviderError.Malformed)
        val reply = ReplyBuilder(request)
        val claude = AnthropicModels.identity(target.sentModelId) != null
        var reasoningChars = 0
        for (block in message.arr("content").objects()) {
            block.str("text")?.let(reply::text)
            block.obj("toolUse")?.let { use -> reply.call(use.str("toolUseId").orEmpty(), use.str("name").orEmpty(), use["input"]) }
            block.obj("reasoningContent")?.let { reasoning ->
                reasoningChars += reasoning.obj("reasoningText")?.str("text")?.length ?: 0
                // A block from a request without tools would go back later after another prefix (Anthropic's prefix check).
                if (claude && sendsTools(request, target)) reply.reasoning(ReasoningState(RequestStyle.BEDROCK, target.providerId, target.sentModelId, block))
            }
        }
        val stop = when (body.str("stopReason")) {
            null, "end_turn", "stop_sequence" -> StopReason.END
            "tool_use" -> StopReason.TOOL_CALL
            "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
            "guardrail_intervened", "content_filtered" -> StopReason.FILTERED
            "malformed_model_output", "malformed_tool_use" -> {
                reply.invalid(InvalidKind.BAD_JSON, "")
                StopReason.OTHER
            }
            else -> StopReason.OTHER
        }
        val usage = body.obj("usage")?.let {
            Usage(
                input = it.long("inputTokens") ?: 0,
                output = it.long("outputTokens") ?: 0,
                cacheRead = it.long("cacheReadInputTokens") ?: 0,
                cacheWrite = it.long("cacheWriteInputTokens") ?: 0,
            )
        } ?: Wire.estimate(request, reply.replyChars, reasoningChars, target.model.reasoning)
        return reply.response(stop, usage, target.sentModelId)
    }

    companion object {
        const val LOG_PATH = "/model/{model}/converse"

        private val TOKEN_ERRORS = setOf("ExpiredTokenException", "UnrecognizedClientException", "InvalidSignatureException")
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/MantleRouting.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The style and API base of a model on Amazon Bedrock Mantle (design D4, research r34). */
data class MantleRoute(val style: RequestStyle, val baseUrl: HttpUrl)

/**
 * Bedrock Mantle serves each model through one of three APIs, as OpenClaw routes them (research r34): OpenAI's GPT models
 * (`openai.gpt-<n>…`) through Responses at `/openai/v1`, Claude through Anthropic Messages at `/anthropic`, and every other
 * model through Chat Completions at `/v1`, all at `https://bedrock-mantle.<region>.api.aws`.
 */
object MantleRouting {
    private val REGION = Regex("^[a-z0-9-]+$")
    private val GPT = Regex("^openai\\.gpt-\\d")

    /** Throws IllegalArgumentException for a region that is not a plain region name, because it becomes part of a host. */
    fun route(modelId: String, region: String): MantleRoute {
        require(REGION.matches(region)) { "A region must match ${REGION.pattern}" }
        val root = "https://bedrock-mantle.$region.api.aws"
        return when {
            GPT.containsMatchIn(modelId.lowercase()) -> MantleRoute(RequestStyle.RESPONSES, "$root/openai/v1".toHttpUrl())
            AnthropicModels.identity(modelId) != null || modelId.lowercase().startsWith("anthropic.") ->
                MantleRoute(RequestStyle.ANTHROPIC, "$root/anthropic".toHttpUrl())
            else -> MantleRoute(RequestStyle.CHAT_COMPLETIONS, "$root/v1".toHttpUrl())
        }
    }

    /** Mantle's model list, at `/v1/models` of the region's host whatever base the profile has (design D13). */
    fun modelsBase(region: String): HttpUrl = route("", region).baseUrl
}
```

- [ ] **Step 6: Write Bedrock's model list**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/BedrockModels.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Bedrock's model list from its control plane `https://bedrock.<region>.amazonaws.com` (design D13, research r34), with
 * OpenClaw's filters: a foundation model is offered when it streams, writes text, is ACTIVE, is not the Mythos preview,
 * and can run on demand; an inference profile when it is ACTIVE and not for the Mythos preview (OpenClaw's
 * `extensions/amazon-bedrock/discovery.ts`), with the input kinds of its base model. Profiles are
 * read page by page through `nextToken`, and `global.` profiles come first.
 */
internal class BedrockModels(region: String, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    private val base: HttpUrl

    init {
        require(Regex("^[a-z0-9-]+$").matches(region)) { "A region must match ^[a-z0-9-]+$" }
        base = "https://bedrock.$region.amazonaws.com".toHttpUrl()
    }

    override suspend fun list(): List<DiscoveredModel> {
        val foundations = objects(get(base.newBuilder().addPathSegment("foundation-models").build())["modelSummaries"])
        val inputs = foundations.associate { it.string("modelId").orEmpty() to it.inputs() }
        val models = foundations.filter { model ->
            model.boolean("responseStreamingSupported") == true &&
                model.strings("outputModalities").any { it.equals("TEXT", ignoreCase = true) } &&
                (model["modelLifecycle"] as? JsonObject)?.string("status").equals("ACTIVE", ignoreCase = true) &&
                model.string("modelId")?.contains(MYTHOS_PREVIEW) != true &&
                model.strings("inferenceTypesSupported").let { it.isEmpty() || "ON_DEMAND" in it }
        }.mapNotNull { model ->
            val id = model.string("modelId") ?: return@mapNotNull null
            DiscoveredModel(id = id, name = model.string("modelName") ?: id, input = model.inputs())
        }
        val profiles = mutableListOf<DiscoveredModel>()
        var token: String? = null
        do {
            val url = base.newBuilder().addPathSegment("inference-profiles").addQueryParameter("maxResults", "1000")
                .apply { token?.let { addQueryParameter("nextToken", it) } }.build()
            val page = get(url)
            for (profile in objects(page["inferenceProfileSummaries"])) {
                if (profile.string("status") != "ACTIVE") continue
                val id = profile.string("inferenceProfileId") ?: continue
                val baseModel = objects(profile["models"]).firstOrNull()?.string("modelArn")
                    ?.let { Regex("foundation-model/(.+)$").find(it)?.groupValues?.get(1) }
                    ?: id.replace(Regex("^(?:us|eu|ap|apac|au|jp|global)\\."), "")
                if (MYTHOS_PREVIEW in baseModel || MYTHOS_PREVIEW in id) continue
                profiles += DiscoveredModel(id = id, name = profile.string("inferenceProfileName") ?: id, input = inputs[baseModel] ?: setOf(InputKind.TEXT))
            }
            token = page.string("nextToken")?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return profiles.sortedBy { if (it.id.startsWith("global.")) 0 else 1 } + models
    }

    private suspend fun get(url: HttpUrl): JsonObject {
        val text = http.send(HttpRequest("GET", url, mapOf("accept" to "application/json")), auth).text()
        return try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)
    }

    private fun JsonObject.inputs(): Set<InputKind> =
        if (strings("inputModalities").any { it.equals("IMAGE", ignoreCase = true) }) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT)

    private fun objects(element: Any?): List<JsonObject> = (element as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    override fun toString(): String = "BedrockModels"

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val MYTHOS_PREVIEW = "claude-mythos-preview"
    }
}
```

- [ ] **Step 7: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 76 tests in `:core:providers`: Task 5's 61, `SigV4AuthTest` 5, `BedrockAdapterTest` 7, `MantleRoutingTest` 2 and `BedrockModelsTest` 1.

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 76; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit without a finding: `SigV4Auth` uses only `java.security.MessageDigest` and `javax.crypto.Mac`, which the audit does not flag.

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/BedrockAdapter.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/SigV4Auth.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/MantleRouting.kt core/providers/src/main/kotlin/dev/kestrel/providers/discovery/BedrockModels.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/SigV4AuthTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/BedrockAdapterTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/MantleRoutingTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/discovery/BedrockModelsTest.kt core/providers/src/test/resources/fixtures/bedrock
git commit -m "feat(providers): add Bedrock Converse with SigV4, Mantle's routing and Bedrock's model list" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Vertex

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/VertexAdapter.kt`, `VertexUrls.kt`, `ServiceAccountAuth.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/vertex/` (4 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/ServiceAccountAuthTest.kt`, `VertexAdapterTest.kt`, `VertexUrlsTest.kt`

**Interfaces:**
- Consumes: Task 3's `AnthropicAdapter.complete(request, target, url, vertex, logPath)` and `AnthropicModels.identity`; Task 4's `GeminiAdapter.complete(request, target, url, stream, logPath)` and `GeminiAdapter.url`; Task 6's `SigV4Auth.encode` (for the form); plan 4A's `Credential.ServiceAccountKey`, `ProviderHttp.exchange`, `RefreshResult`.
- Produces: `class ServiceAccountAuth(key: Credential.ServiceAccountKey, http: ProviderHttp, clock: () -> Long = System::currentTimeMillis, tokens: ServiceAccountTokens = ServiceAccountTokens.SHARED) : RequestAuth` with `rejected = SignInExpired("google-vertex")` and `companion object { const val TOKEN_URL; const val SCOPE }`; `class ServiceAccountTokens(capacity: Int = 4)` with `size` and `companion object { val SHARED }`; `object VertexUrls { fun base(location: String, projectId: String): HttpUrl; fun validLocation(location: String): Boolean; fun validProject(projectId: String): Boolean; fun logPath(url: HttpUrl): String }`; `internal class VertexAdapter(http: ProviderHttp) : ChatAdapter`.

Design D4, D9, D16 and D19; research r35. Vertex is one provider for Gemini and Claude. `ModelTarget.baseUrl` is `VertexUrls.base(location, project)`, `https://<host>/v1/projects/<project>/locations/<location>`, where `global` uses `aiplatform.googleapis.com`, `us` and `eu` use `aiplatform.<location>.rep.googleapis.com`, and every other location `<location>-aiplatform.googleapis.com`; the location must match `^[a-z0-9-]+$`, because it becomes part of the host that receives the token (research r35 §6), and the project ID must have Google's form, `^(?:[a-z0-9.-]+:)?[a-z][a-z0-9-]{4,28}[a-z0-9]$` (with the optional `domain:` prefix of older projects), because OkHttp would treat a project ID such as `..` as a dot segment and build another path on Google's host. The model's wire is `StyleOptions.wire`, else its catalog row's style (plan 4A's Vertex rows carry `GEMINI` or `ANTHROPIC`), else Claude for an ID that names a Claude model and Gemini otherwise. Gemini goes through Task 4's adapter at `publishers/google/models/<id>:streamGenerateContent?alt=sse`; Claude through Task 3's Vertex form at `publishers/anthropic/models/<id>:rawPredict`, not streamed, because research r35 did not read `streamRawPredict`'s format (design D4). The network log records the path with `{project}` instead of the project (design D16).

`ServiceAccountAuth` (design D9, research r35): a JWT with the header `{"alg":"RS256","typ":"JWT"}` (and `kid` when the key file has `private_key_id`) and the claims `iss` (the service account's e-mail address), `scope` (`https://www.googleapis.com/auth/cloud-platform`), `aud` (`https://oauth2.googleapis.com/token`), `iat` 60 s in the past, so that a phone whose clock runs fast is not refused, and `exp = iat + 3600`, signed with `SHA256withRSA` over the PKCS#8 key; it is exchanged as a form `POST` (`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<JWT>`) at `https://oauth2.googleapis.com/token`, and the token is reused until 60 s before it expires. Plan 4C builds a new `ServiceAccountAuth` for every request, so the token does not live in the instance: it lives in a `ServiceAccountTokens` cache, by default the one of the process (`ServiceAccountTokens.SHARED`), keyed by the service account's e-mail, the key (its `private_key_id`, or a SHA-256 of the key when the file has none), the scope and the token URI, so that a token that one key file earned never answers for another. The cache keeps tokens only in memory, drops one that has expired, holds at most four (the least recently used goes first), and its `toString` shows only how many it holds; the tests pass their own cache. The cache's `Mutex` makes one exchange at a time, and a waiting `headers()` uses its token. `refresh()` always exchanges again (design D9's rule of one refresh after a 401). Google's `invalid_grant` (a deleted or disabled key), any other refusal, and a key that does not parse are `Expired`, so the owner imports a new file and `ProviderHttp` fails the request with `rejected`; no network, a timeout, a 429 or a 5xx is `FailedForNow`, which keeps the key.

- [ ] **Step 1: Write the fixtures**

These follow Google's token response of research r35 §3 (`access_token`, `expires_in`, `token_type`), its OAuth error for a refused JWT, a Gemini chunk, and a Claude message from `rawPredict` (research r35 §5).

Create `core/providers/src/test/resources/fixtures/vertex/claude-message.json`:

```json
{
  "id": "msg_v",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-6",
  "content": [
    {
      "type": "tool_use",
      "id": "toolu_v",
      "name": "open_app",
      "input": {
        "name": "Camera"
      }
    }
  ],
  "stop_reason": "tool_use",
  "usage": {
    "input_tokens": 60,
    "cache_read_input_tokens": 0,
    "cache_creation_input_tokens": 0,
    "output_tokens": 15
  }
}
```

Create `core/providers/src/test/resources/fixtures/vertex/gemini-text.sse`:

```text
data: {"candidates":[{"content":{"parts":[{"text":"Hello from Gemini on Vertex."}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":8,"totalTokenCount":48},"modelVersion":"gemini-3.8-flash"}
```

Create `core/providers/src/test/resources/fixtures/vertex/token-invalid-grant.json`:

```json
{
  "error": "invalid_grant",
  "error_description": "Invalid JWT Signature."
}
```

Create `core/providers/src/test/resources/fixtures/vertex/token.json`:

```json
{
  "access_token": "ya29.test-token-1",
  "expires_in": 3599,
  "token_type": "Bearer"
}
```

- [ ] **Step 2: Write the failing tests**

The tests make their own RSA key pair, so no key file of Google's is needed.

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/ServiceAccountAuthTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.RefreshResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ServiceAccountAuthTest {
    private val bench = AdapterHarness()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
    private val key = Credential.ServiceAccountKey("kestrel@test-project.iam.gserviceaccount.com", pem, "key-1", "test-project")
    private var now = 1_800_000_000_000L
    private val tokens = ServiceAccountTokens()
    private val auth = ServiceAccountAuth(key, bench.http, { now }, tokens)
    private val anyRequest = HttpRequest("GET", "https://aiplatform.googleapis.com/v1/x".toHttpUrl())

    @After
    fun close() = bench.close()

    private fun decode(part: String): JsonObject = Json.parseToJsonElement(Base64.getUrlDecoder().decode(part).decodeToString()) as JsonObject

    @Test
    fun theSignedJwtIsExchangedOnceAndTheTokenReusedUntilAMinuteBeforeItEnds() = runBlocking {
        bench.reply("vertex/token.json")
        bench.reply("vertex/token.json")

        assertEquals(mapOf("Authorization" to "Bearer ya29.test-token-1"), auth.headers(anyRequest))
        now += 3_000_000
        auth.headers(anyRequest)
        assertEquals(1, bench.server.requestCount)
        now += 540_000
        auth.headers(anyRequest)
        assertEquals(2, bench.server.requestCount)

        assertEquals(listOf("https://oauth2.googleapis.com/token".toHttpUrl(), "https://oauth2.googleapis.com/token".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("application/x-www-form-urlencoded", recorded.headers["Content-Type"])
        val form = recorded.body!!.utf8()
        assertTrue(form.startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="))
        val (header, claims, signature) = form.substringAfter("assertion=").split('.')
        assertEquals(Json.parseToJsonElement("""{"alg":"RS256","typ":"JWT","kid":"key-1"}"""), decode(header))
        val body = decode(claims)
        assertEquals("kestrel@test-project.iam.gserviceaccount.com", body["iss"]!!.jsonPrimitive.content)
        assertEquals("https://www.googleapis.com/auth/cloud-platform", body["scope"]!!.jsonPrimitive.content)
        assertEquals("https://oauth2.googleapis.com/token", body["aud"]!!.jsonPrimitive.content)
        assertEquals(1_800_000_000L - 60, body["iat"]!!.jsonPrimitive.long)
        assertEquals(1_800_000_000L - 60 + 3600, body["exp"]!!.jsonPrimitive.long)
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keys.public)
            update("$header.$claims".encodeToByteArray())
        }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)))
    }

    @Test
    fun aNewInstanceForTheSameAccountReusesTheTokenAndTheCacheKeepsAFewTokensWithoutPrintingThem() = runBlocking {
        repeat(6) { bench.reply("vertex/token.json") }

        auth.headers(anyRequest)
        // Plan 4C builds a new instance for each request.
        assertEquals(mapOf("Authorization" to "Bearer ya29.test-token-1"), ServiceAccountAuth(key, bench.http, { now }, tokens).headers(anyRequest))
        assertEquals(1, bench.server.requestCount)
        // Another key of the same account gets its own token.
        ServiceAccountAuth(key.copy(privateKeyId = "key-2"), bench.http, { now }, tokens).headers(anyRequest)
        assertEquals(2, bench.server.requestCount)
        for (n in 2..5) ServiceAccountAuth(key.copy(clientEmail = "other-$n@test-project.iam.gserviceaccount.com"), bench.http, { now }, tokens).headers(anyRequest)
        assertEquals(4, tokens.size)
        assertEquals("ServiceAccountTokens(4 kept)", tokens.toString())
        assertTrue("ya29" !in auth.toString())
    }

    @Test
    fun aRefreshFetchesANewToken() = runBlocking {
        bench.reply("vertex/token.json")
        bench.reply("vertex/token.json")

        auth.headers(anyRequest)
        assertEquals(RefreshResult.Refreshed, auth.refresh())
        assertEquals(2, bench.server.requestCount)
    }

    @Test
    fun invalidGrantMeansANewKeyFileAndAServerErrorKeepsTheKey() = runBlocking {
        bench.status(400, bench.fixture("vertex/token-invalid-grant.json"))
        bench.status(503)
        bench.status(429, "", "Retry-After" to "4")

        assertEquals(RefreshResult.Expired, auth.refresh())
        assertEquals(RefreshResult.FailedForNow(ProviderError.ServerError(503)), auth.refresh())
        assertEquals(RefreshResult.FailedForNow(ProviderError.RateLimited(4_000)), auth.refresh())
    }

    @Test
    fun aRefusedKeyFailsTheRequestAsAnExpiredSignIn() {
        bench.status(400, bench.fixture("vertex/token-invalid-grant.json"))

        try {
            runBlocking { auth.headers(anyRequest) }
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.SignInExpired("google-vertex"), e.error)
        }
    }

    @Test
    fun aKeyThatIsNoKeyIsRefusedWithoutARequest() = runBlocking {
        val broken = ServiceAccountAuth(key.copy(privateKeyPem = "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----"), bench.http, { now }, tokens)

        assertEquals(RefreshResult.Expired, broken.refresh())
        assertEquals(0, bench.server.requestCount)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/VertexAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexAdapterTest {
    private val bench = AdapterHarness()
    private val logPaths = mutableListOf<String?>()
    private val adapter = VertexAdapter(ProviderHttp(bench.calls, decorator = { _, meta -> logPaths += meta.logPath }))
    private val token = object : RequestAuth {
        override suspend fun headers(request: HttpRequest) = mapOf("Authorization" to "Bearer ya29.test")
    }
    private val base = VertexUrls.base("europe-west4", "kestrel-test-project")

    @After
    fun close() = bench.close()

    private fun vertex(model: ModelEntry) = AdapterHarness.target("google-vertex", RequestStyle.VERTEX, base.toString(), model, token)

    @Test
    fun aGeminiModelStreamsThroughTheGeminiAdapterAndTheLogHidesTheProject() = runBlocking {
        bench.reply("vertex/gemini-text.sse")

        val response = adapter.complete(request(user("Hi")), vertex(ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", style = RequestStyle.GEMINI)))

        assertEquals(listOf(AssistantPart.Text("Hello from Gemini on Vertex.")), response.message.parts)
        assertEquals(
            "https://europe-west4-aiplatform.googleapis.com/v1/projects/kestrel-test-project/locations/europe-west4/publishers/google/models/gemini-3.8-flash:streamGenerateContent?alt=sse".toHttpUrl(),
            bench.calls.requests.single(),
        )
        assertEquals(listOf<String?>("/v1/projects/{project}/locations/europe-west4/publishers/google/models/gemini-3.8-flash:streamGenerateContent"), logPaths)
        val recorded = bench.take()
        assertEquals("Bearer ya29.test", recorded.headers["Authorization"])
        assertNull(recorded.headers["x-goog-api-key"])
    }

    @Test
    fun aClaudeModelUsesRawPredictWithTheVertexVersion() = runBlocking {
        bench.reply("vertex/claude-message.json")

        val response = adapter.complete(request(user("Open the camera.")), vertex(ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", style = RequestStyle.ANTHROPIC)))

        assertEquals(listOf(AssistantPart.ToolCall("toolu_v", "open_app", obj("""{"name":"Camera"}"""))), response.message.parts)
        assertEquals(StopReason.TOOL_CALL, response.stop)
        assertEquals(
            "https://europe-west4-aiplatform.googleapis.com/v1/projects/kestrel-test-project/locations/europe-west4/publishers/anthropic/models/claude-sonnet-4-6:rawPredict".toHttpUrl(),
            bench.calls.requests.single(),
        )
        assertEquals(listOf<String?>("/v1/projects/{project}/locations/europe-west4/publishers/anthropic/models/claude-sonnet-4-6:rawPredict"), logPaths)
        val body = bench.body(bench.take())
        assertEquals(json(""""vertex-2023-10-16""""), body["anthropic_version"])
        assertEquals(json("false"), body["stream"])
    }

    @Test
    fun aModelWithoutAStyleIsClaudeByItsIdAndGeminiOtherwise() = runBlocking {
        bench.reply("vertex/claude-message.json")
        bench.reply("vertex/gemini-text.sse")

        adapter.complete(request(user("Hi")), vertex(ModelEntry("claude-opus-5", "Claude Opus 5")))
        adapter.complete(request(user("Hi"), ChatMessage.Assistant(listOf(AssistantPart.Text("Hello.")))), vertex(ModelEntry("gemini-4-argon", "Gemini 4 Argon")))

        assertTrue(bench.calls.requests[0].encodedPath.endsWith("/publishers/anthropic/models/claude-opus-5:rawPredict"))
        assertTrue(bench.calls.requests[1].encodedPath.endsWith("/publishers/google/models/gemini-4-argon:streamGenerateContent"))
    }

}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/VertexUrlsTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexUrlsTest {
    @Test
    fun theLocationDecidesTheHostAndTheLocationAndTheProjectMustBePlainNames() {
        assertEquals("https://aiplatform.googleapis.com/v1/projects/my-project/locations/global".toHttpUrl(), VertexUrls.base("global", "my-project"))
        assertEquals("https://aiplatform.us.rep.googleapis.com/v1/projects/my-project/locations/us".toHttpUrl(), VertexUrls.base("us", "my-project"))
        assertEquals("https://aiplatform.eu.rep.googleapis.com/v1/projects/my-project/locations/eu".toHttpUrl(), VertexUrls.base("eu", "my-project"))
        assertEquals("https://us-east5-aiplatform.googleapis.com/v1/projects/my-project/locations/us-east5".toHttpUrl(), VertexUrls.base("us-east5", "my-project"))
        assertFalse(VertexUrls.validLocation("us-east5.evil.example"))
        assertFalse(VertexUrls.validLocation("US"))
        assertTrue(runCatching { VertexUrls.base("evil.example/x", "my-project") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(VertexUrls.validProject("example.com:my-project"))
        for (project in listOf("", "p", "..", "my-project/..", "My-Project")) {
            assertTrue(project, runCatching { VertexUrls.base("global", project) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun theLogPathHidesTheProject() {
        val url = "https://aiplatform.googleapis.com/v1/projects/my-project/locations/global/publishers/google/models/g:generateContent".toHttpUrl()

        assertEquals("/v1/projects/{project}/locations/global/publishers/google/models/g:generateContent", VertexUrls.logPath(url))
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ServiceAccountAuth'", "Unresolved reference 'VertexAdapter'" and "Unresolved reference 'VertexUrls'".

- [ ] **Step 4: Write Vertex's addresses and the service account's token**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/VertexUrls.kt`:

```kotlin
package dev.kestrel.providers.styles

import okhttp3.HttpUrl

/**
 * The addresses of Vertex AI (design D4 and D9, research r35): `global` uses `aiplatform.googleapis.com`, `us` and `eu`
 * use `aiplatform.<location>.rep.googleapis.com`, and every other location `<location>-aiplatform.googleapis.com`. The
 * location becomes part of the host that receives the service account's token, so it must be a plain name, and the
 * project ID must have Google's form (with an optional `domain:` prefix), so that a broken key file cannot build another
 * path, for example through `..`.
 */
object VertexUrls {
    private val LOCATION = Regex("^[a-z0-9-]+$")
    private val PROJECT = Regex("^(?:[a-z0-9.-]+:)?[a-z][a-z0-9-]{4,28}[a-z0-9]$")

    fun validLocation(location: String): Boolean = LOCATION.matches(location)

    /** Whether [projectId] has the form of a Google Cloud project ID. */
    fun validProject(projectId: String): Boolean = PROJECT.matches(projectId)

    /** `https://<host>/v1/projects/<project>/locations/<location>`; throws IllegalArgumentException for a bad location or project. */
    fun base(location: String, projectId: String): HttpUrl {
        require(validLocation(location)) { "A location must match ${LOCATION.pattern}" }
        require(validProject(projectId)) { "A project ID must match ${PROJECT.pattern}" }
        val host = when (location) {
            "global" -> "aiplatform.googleapis.com"
            "us", "eu" -> "aiplatform.$location.rep.googleapis.com"
            else -> "$location-aiplatform.googleapis.com"
        }
        return HttpUrl.Builder().scheme("https").host(host)
            .addPathSegment("v1").addPathSegment("projects").addPathSegment(projectId)
            .addPathSegment("locations").addPathSegment(location)
            .build()
    }

    /** The path that the network log records: the project, which names the owner's account, becomes `{project}` (design D16). */
    fun logPath(url: HttpUrl): String {
        val segments = url.encodedPathSegments
        return "/" + segments.mapIndexed { index, segment -> if (index == 2 && segments.getOrNull(1) == "projects") "{project}" else segment }.joinToString("/")
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/ServiceAccountAuth.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Vertex's credential (design D9, research r35): a JWT signed with RS256 by the service account's key, exchanged at
 * `https://oauth2.googleapis.com/token` for a token of one hour, which is reused until 60 s before it expires. The token
 * lives in [tokens], which by default all instances of the process share, so that a new instance for each request (as
 * plan 4C builds them) still reuses it. The JWT's `iat` lies 60 s in the past, so that a phone whose clock runs fast is
 * not refused, and its header names the key's `kid` when the file has one. Google's `invalid_grant` (a deleted or
 * disabled key) and any other refusal mean that the owner imports a new file; a network failure, a 429 or a 5xx keeps the
 * key (design D9's FailedForNow).
 */
class ServiceAccountAuth(
    private val key: Credential.ServiceAccountKey,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokens: ServiceAccountTokens = ServiceAccountTokens.SHARED,
) : RequestAuth {
    private val identity = "${key.clientEmail}\n${key.privateKeyId ?: sha256(key.privateKeyPem)}\n$SCOPE\n$TOKEN_URL"
    private var issued: String? = null

    override val rejected: ProviderError get() = ProviderError.SignInExpired(PROVIDER)

    override suspend fun headers(request: HttpRequest): Map<String, String> {
        val current = tokens.mutex.withLock {
            tokens.get(identity, clock() + EARLY_MILLIS) ?: when (val result = exchange()) {
                RefreshResult.Refreshed -> issued ?: throw ProviderException(ProviderError.Malformed)
                is RefreshResult.FailedForNow -> throw ProviderException(result.error)
                RefreshResult.Expired, RefreshResult.NotPossible -> throw ProviderException(rejected)
            }
        }
        return mapOf("Authorization" to "Bearer $current")
    }

    /** Always exchanges again, because the provider refused the token that [headers] gave. */
    override suspend fun refresh(): RefreshResult = tokens.mutex.withLock {
        tokens.remove(identity)
        exchange()
    }

    /** Called with the cache's mutex held. */
    private suspend fun exchange(): RefreshResult {
        issued = null
        val assertion = try {
            jwt()
        } catch (e: GeneralSecurityException) {
            return RefreshResult.Expired
        } catch (e: IllegalArgumentException) {
            return RefreshResult.Expired
        }
        val form = "grant_type=${SigV4Auth.encode(GRANT_TYPE)}&assertion=${SigV4Auth.encode(assertion)}"
        val reply = try {
            http.exchange(HttpRequest("POST", TOKEN_URL.toHttpUrl(), mapOf("accept" to "application/json"), form.encodeToByteArray(), "application/x-www-form-urlencoded"))
        } catch (e: ProviderException) {
            return RefreshResult.FailedForNow(e.error)
        }
        return when (reply.status) {
            in 200..299 -> {
                val body = try {
                    JSON.parseToJsonElement(reply.text()) as? JsonObject
                } catch (e: SerializationException) {
                    null
                }
                val access = (body?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return RefreshResult.FailedForNow(ProviderError.Malformed)
                val seconds = (body["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
                issued = access
                tokens.put(identity, access, clock() + seconds * 1000)
                RefreshResult.Refreshed
            }
            429 -> RefreshResult.FailedForNow(ProviderError.RateLimited(reply.headers["Retry-After"]?.toLongOrNull()?.times(1000)))
            in 500..599 -> RefreshResult.FailedForNow(ProviderError.ServerError(reply.status))
            else -> RefreshResult.Expired
        }
    }

    private fun jwt(): String {
        val issuedAt = clock() / 1000 - 60
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
            key.privateKeyId?.let { put("kid", it) }
        }
        val claims = buildJsonObject {
            put("iss", key.clientEmail)
            put("scope", SCOPE)
            put("aud", TOKEN_URL)
            put("iat", issuedAt)
            put("exp", issuedAt + 3600)
        }
        val input = "${base64Url(header.toString().encodeToByteArray())}.${base64Url(claims.toString().encodeToByteArray())}"
        val der = Base64.getMimeDecoder().decode(key.privateKeyPem.replace(Regex("-----[A-Z ]+-----"), ""))
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(input.encodeToByteArray())
        }.sign()
        return "$input.${base64Url(signature)}"
    }

    private fun sha256(text: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    override fun toString(): String = "ServiceAccountAuth(key=$key, tokens=$tokens)"

    companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        private const val PROVIDER = "google-vertex"
        private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        private const val EARLY_MILLIS = 60_000L
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The access tokens of service accounts (design D9), kept only in memory, keyed by the account's e-mail, the key (its
 * `private_key_id`, or a SHA-256 of the key when the file has no ID), the scope and the token URI, and dropped when they
 * expire. At most [capacity] tokens are kept; the least recently used goes first.
 * One [mutex] lets one exchange run at a time, so that callers who wait use its token. Nothing of a token is printed.
 */
class ServiceAccountTokens(private val capacity: Int = 4) {
    private class Entry(val token: String, val expiresAtMillis: Long)

    private val entries = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }

    internal val mutex = Mutex()

    /** The token for [identity] that is still valid at [atMillis], or null. Called with [mutex] held. */
    internal fun get(identity: String, atMillis: Long): String? {
        val entry = entries[identity] ?: return null
        if (atMillis < entry.expiresAtMillis) return entry.token
        entries.remove(identity)
        return null
    }

    internal fun put(identity: String, token: String, expiresAtMillis: Long) {
        entries[identity] = Entry(token, expiresAtMillis)
    }

    internal fun remove(identity: String) {
        entries.remove(identity)
    }

    /** The number of tokens kept. */
    val size: Int get() = entries.size

    override fun toString(): String = "ServiceAccountTokens(${entries.size} kept)"

    companion object {
        /** The cache of the process, which every [ServiceAccountAuth] uses unless it is given another. */
        val SHARED = ServiceAccountTokens()
    }
}
```

- [ ] **Step 5: Write the adapter**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/VertexAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ProviderHttp

/**
 * Google Vertex AI (spec 10.3, design D4 and D16, research r35): one provider for Gemini and Claude. A model's wire is
 * [dev.kestrel.providers.chat.StyleOptions.wire], else its catalog row's style, else Claude for an ID that names a Claude
 * model and Gemini otherwise. Gemini goes through the Gemini adapter at `publishers/google/models/<id>:streamGenerateContent`;
 * Claude through the Anthropic adapter's Vertex form at `publishers/anthropic/models/<id>:rawPredict`. [ModelTarget.baseUrl]
 * is `VertexUrls.base(location, project)`, the token is the service account's (ServiceAccountAuth), and the network log
 * records `{project}` instead of the project.
 */
internal class VertexAdapter(http: ProviderHttp) : ChatAdapter {
    private val gemini = GeminiAdapter(http)
    private val anthropic = AnthropicAdapter(http)

    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val wire = target.options.wire
            ?: target.model.style?.takeIf { it == RequestStyle.GEMINI || it == RequestStyle.ANTHROPIC }
            ?: if (AnthropicModels.identity(target.sentModelId) != null) RequestStyle.ANTHROPIC else RequestStyle.GEMINI
        return if (wire == RequestStyle.ANTHROPIC) {
            val url = target.baseUrl.newBuilder()
                .addPathSegments("publishers/anthropic/models")
                .addPathSegment("${target.sentModelId}:rawPredict")
                .build()
            anthropic.complete(request, target, url, vertex = true, logPath = VertexUrls.logPath(url))
        } else {
            val stream = target.options.stream != false
            val url = GeminiAdapter.url(target.baseUrl, "publishers/google/models", target.sentModelId, stream)
            gemini.complete(request, target, url, stream, logPath = VertexUrls.logPath(url))
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 87 tests in `:core:providers`: Task 6's 76, `ServiceAccountAuthTest` 6, `VertexAdapterTest` 3 and `VertexUrlsTest` 2.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 87; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit without a finding.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/VertexAdapter.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/VertexUrls.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/ServiceAccountAuth.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/ServiceAccountAuthTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/VertexAdapterTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/VertexUrlsTest.kt core/providers/src/test/resources/fixtures/vertex
git commit -m "feat(providers): add Vertex for Gemini and Claude with the service account's token" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: Azure OpenAI and Microsoft Foundry

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/AzureAdapter.kt`, `AzureRouting.kt`
- Create (fixtures): `core/providers/src/test/resources/fixtures/azure/` (3 files)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AzureAdapterTest.kt`, `AzureRoutingTest.kt`

**Interfaces:**
- Consumes: Tasks 1 to 3's `ChatCompletionsAdapter`, `ResponsesAdapter` and `AnthropicAdapter`; Task 1's `AdapterHarness`; plan 4A's `KeyHeader.API_KEY` and `X_API_KEY`, `StyleOptions.wire` and `messagesPath`.
- Produces: `object AzureRouting { fun styleFor(deployment: String): RequestStyle; fun normalise(endpoint: String): HttpUrl? }`; `internal class AzureAdapter(http: ProviderHttp) : ChatAdapter`.

Design D4 and D19; research r36. `ModelTarget.baseUrl` is the owner's endpoint and `ModelTarget.sentModelId` the deployment's name, which goes into the request as the model. The wire is `StyleOptions.wire` when the owner chose another API on the profile page, else `AzureRouting.styleFor`, OpenClaw's rule (`extensions/microsoft-foundry/shared.ts`, research r36): a deployment whose name starts with `claude` (OpenClaw's `isAnthropicFoundryDeployment`, whatever the case) uses Anthropic Messages at `<endpoint>/anthropic/v1/messages`; a name that starts with `gpt-`, `o1`, `o3`, `o4` or `deepseek-v4`, or is `computer-use-preview`, uses Responses at `<endpoint>/openai/v1/responses`; every other name uses Chat Completions at `<endpoint>/openai/v1/chat/completions`. The v1 API needs no `api-version` (research r36). A Chat Completions deployment has no catalog compat, so unless the owner's compat names a `maxTokensField`, the adapter sends `max_completion_tokens` only to a name that starts with `gpt-5` or a later GPT, or with `o` and a digit, and `max_tokens` to every other (OpenClaw's `requiresFoundryMaxCompletionTokens`, which names `gpt-5`, `o1`, `o3` and `o4`, widened to OpenAI's later reasoning models), because research does not say whether Foundry's other models, such as Mistral, DeepSeek or Llama, take `max_completion_tokens`. The key's header comes with the target's `RequestAuth`: plan 4C's `TargetResolver` gives `api-key` to the OpenAI wire and `x-api-key` to Claude, by the same rule.

`AzureRouting.normalise` turns the endpoint that the owner typed into `https://<resource>.<host>/`: it must be HTTPS on `*.openai.azure.com`, `*.services.ai.azure.com` or `*.cognitiveservices.azure.com` (OpenClaw's `isAzureOpenAICompatibleHost`), without user information or a fragment, and its path may only be an `/openai…` or `/anthropic…` suffix, which is dropped together with any query, so that a legacy deployment URL pasted with `?api-version=…` works and no query is kept. It returns null for any other address, so that plan 4C's add flow can refuse it.

- [ ] **Step 1: Write the fixtures**

These are one reply of each wire, as Task 1 to 3's fixtures shape them. `chat-text.sse` starts with the chunk that Azure OpenAI sends first, with empty `choices` and its `prompt_filter_results`.

Create `core/providers/src/test/resources/fixtures/azure/anthropic-text.sse`:

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],"usage":{"input_tokens":20,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello from Claude on Foundry."}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

event: message_stop
data: {"type":"message_stop"}
```

Create `core/providers/src/test/resources/fixtures/azure/chat-text.sse`:

```text
data: {"choices":[],"created":0,"id":"","model":"","object":"","prompt_filter_results":[{"prompt_index":0,"content_filter_results":{"hate":{"filtered":false,"severity":"safe"},"self_harm":{"filtered":false,"severity":"safe"},"sexual":{"filtered":false,"severity":"safe"},"violence":{"filtered":false,"severity":"safe"}}}]}

data: {"id":"c1","object":"chat.completion.chunk","model":"Mistral-Large-3","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello from Foundry."},"finish_reason":null}]}

data: {"id":"c1","object":"chat.completion.chunk","model":"Mistral-Large-3","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}

data: [DONE]
```

Create `core/providers/src/test/resources/fixtures/azure/responses-text.sse`:

```text
event: response.completed
data: {"type":"response.completed","sequence_number":1,"response":{"id":"resp_1","object":"response","model":"gpt-6-luna","status":"completed","output":[{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello from Azure.","annotations":[]}]}],"usage":{"input_tokens":20,"output_tokens":5}}}
```

- [ ] **Step 2: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AzureAdapterTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AzureAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = AzureAdapter(bench.http)

    @After
    fun close() = bench.close()

    private fun deployment(name: String, endpoint: String, header: KeyHeader, options: StyleOptions = StyleOptions()) =
        target("microsoft-foundry", RequestStyle.AZURE, endpoint, ModelEntry(name, name), ApiKeyAuth("test-key", header), options)

    @Test
    fun aGptDeploymentUsesResponsesAtTheV1BaseWithTheApiKeyHeader() = runBlocking {
        bench.reply("azure/responses-text.sse")

        val response = adapter.complete(request(user("Hi")), deployment("gpt-6-luna", "https://kestrel.openai.azure.com/", KeyHeader.API_KEY))

        assertEquals(listOf(AssistantPart.Text("Hello from Azure.")), response.message.parts)
        assertEquals(listOf("https://kestrel.openai.azure.com/openai/v1/responses".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("test-key", recorded.headers["api-key"])
        assertNull(recorded.headers["Authorization"])
        assertEquals(json(""""gpt-6-luna""""), bench.body(recorded)["model"])
    }

    @Test
    fun anotherDeploymentUsesChatCompletionsWithoutAnApiVersion() = runBlocking {
        bench.reply("azure/chat-text.sse")

        val response = adapter.complete(request(user("Hi")), deployment("Mistral-Large-3", "https://kestrel.services.ai.azure.com/openai/v1/", KeyHeader.API_KEY))

        assertEquals(listOf(AssistantPart.Text("Hello from Foundry.")), response.message.parts)
        assertEquals(listOf("https://kestrel.services.ai.azure.com/openai/v1/chat/completions".toHttpUrl()), bench.calls.requests)
        val body = bench.body(bench.take())
        assertEquals(json(""""Mistral-Large-3""""), body["model"])
        // OpenClaw's requiresFoundryMaxCompletionTokens: only gpt-5, o1, o3 and o4 deployments get max_completion_tokens.
        assertEquals(json("256"), body["max_tokens"])
        assertNull(body["max_completion_tokens"])
    }

    @Test
    fun aClaudeDeploymentUsesFoundrysAnthropicRouteWithXApiKey() = runBlocking {
        bench.reply("azure/anthropic-text.sse")

        val response = adapter.complete(request(user("Hi")), deployment("claude-sonnet-5", "https://kestrel.services.ai.azure.com", KeyHeader.X_API_KEY))

        assertEquals(listOf(AssistantPart.Text("Hello from Claude on Foundry.")), response.message.parts)
        assertEquals(listOf("https://kestrel.services.ai.azure.com/anthropic/v1/messages".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("test-key", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
    }

    @Test
    fun theOwnersChoiceOfApiWinsOverTheName() = runBlocking {
        bench.reply("azure/chat-text.sse")

        adapter.complete(request(user("Hi")), deployment("gpt-6-luna", "https://kestrel.openai.azure.com", KeyHeader.API_KEY, StyleOptions(wire = RequestStyle.CHAT_COMPLETIONS)))

        assertEquals(listOf("https://kestrel.openai.azure.com/openai/v1/chat/completions".toHttpUrl()), bench.calls.requests)
        assertEquals(json("256"), bench.body(bench.take())["max_completion_tokens"])
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AzureRoutingTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AzureRoutingTest {
    @Test
    fun theDeploymentsNameChoosesTheApi() {
        assertEquals(RequestStyle.ANTHROPIC, AzureRouting.styleFor("claude-opus-5"))
        assertEquals(RequestStyle.ANTHROPIC, AzureRouting.styleFor("Claude-Opus-5"))
        // OpenClaw's isAnthropicFoundryDeployment: the name must start with `claude`.
        assertEquals(RequestStyle.CHAT_COMPLETIONS, AzureRouting.styleFor("my-claude-deployment"))
        for (name in listOf("gpt-6-luna", "o1-mini", "o3", "o4-mini", "deepseek-v4-pro", "computer-use-preview")) {
            assertEquals(name, RequestStyle.RESPONSES, AzureRouting.styleFor(name))
        }
        assertEquals(RequestStyle.CHAT_COMPLETIONS, AzureRouting.styleFor("DeepSeek-R1"))
        assertEquals(RequestStyle.CHAT_COMPLETIONS, AzureRouting.styleFor("grok-4.5"))
    }

    @Test
    fun anEndpointIsReducedToItsOriginOnAnAzureHost() {
        val root = "https://kestrel.openai.azure.com/".toHttpUrl()
        assertEquals(root, AzureRouting.normalise("https://kestrel.openai.azure.com"))
        assertEquals(root, AzureRouting.normalise(" https://kestrel.openai.azure.com/openai/v1/ "))
        assertEquals(root, AzureRouting.normalise("https://kestrel.openai.azure.com/openai/deployments/gpt-6-luna/chat/completions?api-version=2024-10-21"))
        assertEquals("https://kestrel.services.ai.azure.com/".toHttpUrl(), AzureRouting.normalise("https://kestrel.services.ai.azure.com/anthropic"))
    }

    @Test
    fun anAddressThatIsNotAnAzureEndpointIsRefused() {
        assertNull(AzureRouting.normalise("http://kestrel.openai.azure.com"))
        assertNull(AzureRouting.normalise("https://kestrel.openai.azure.com.evil.example"))
        assertNull(AzureRouting.normalise("https://user:pass@kestrel.openai.azure.com"))
        assertNull(AzureRouting.normalise("https://kestrel.openai.azure.com/other/path"))
        assertNull(AzureRouting.normalise("not an address"))
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'AzureAdapter'" and "Unresolved reference 'AzureRouting'".

- [ ] **Step 4: Write the routing and the adapter**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/AzureRouting.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Azure OpenAI and Microsoft Foundry (design D4, research r36): the API of a deployment follows its name as OpenClaw
 * chooses it (`extensions/microsoft-foundry/shared.ts`): deployments whose name starts with `claude` use Anthropic Messages at
 * `<endpoint>/anthropic`; deployments named `gpt-*`, `o1*`, `o3*`, `o4*`, `deepseek-v4*` or `computer-use-preview` use
 * Responses at `<endpoint>/openai/v1`; the rest Chat Completions there too. The v1 API needs no `api-version`.
 */
object AzureRouting {
    private val HOSTS = listOf(".openai.azure.com", ".services.ai.azure.com", ".cognitiveservices.azure.com")

    fun styleFor(deployment: String): RequestStyle {
        val name = deployment.trim().lowercase()
        return when {
            name.startsWith("claude") -> RequestStyle.ANTHROPIC
            name.startsWith("gpt-") || name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4") ||
                name.startsWith("deepseek-v4") || name == "computer-use-preview" -> RequestStyle.RESPONSES
            else -> RequestStyle.CHAT_COMPLETIONS
        }
    }

    /**
     * The endpoint that the owner typed, reduced to `https://<resource>.<Azure host>/`: it must be HTTPS on an Azure OpenAI
     * or Foundry host, without user information or a fragment, and its path may only be an `/openai…` or `/anthropic…`
     * suffix, which is dropped together with any query. Null when the address is none of these.
     */
    fun normalise(endpoint: String): HttpUrl? {
        val url = endpoint.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        if (HOSTS.none { url.host.endsWith(it) }) return null
        val path = url.encodedPath.trimEnd('/')
        if (path.isNotEmpty() && path != "/openai" && !path.startsWith("/openai/") && path != "/anthropic" && !path.startsWith("/anthropic/")) return null
        return url.newBuilder().encodedPath("/").query(null).build()
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/AzureAdapter.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ProviderHttp

/**
 * Azure OpenAI and Microsoft Foundry (spec 10.3, design D4, research r36). [ModelTarget.baseUrl] is the owner's endpoint
 * and [ModelTarget.sentModelId] the deployment's name, which goes into the request as the model. The wire is
 * [dev.kestrel.providers.chat.StyleOptions.wire] when the owner chose another API on the profile page, else
 * [AzureRouting.styleFor]: Anthropic Messages at `<endpoint>/anthropic/v1/messages`, or Responses or Chat Completions at
 * `<endpoint>/openai/v1`. The key's header (`api-key`, or `x-api-key` for Claude) comes with the target's RequestAuth.
 * A Chat Completions deployment without its own `maxTokensField` gets `max_completion_tokens` only when its name starts
 * with `gpt-5` or a later GPT, or with `o` and a digit, and `max_tokens` otherwise: OpenClaw's
 * `requiresFoundryMaxCompletionTokens` (`extensions/microsoft-foundry/shared.ts`), widened to OpenAI's later reasoning models.
 */
internal class AzureAdapter(http: ProviderHttp) : ChatAdapter {
    private val chat = ChatCompletionsAdapter(http)
    private val responses = ResponsesAdapter(http)
    private val anthropic = AnthropicAdapter(http)

    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val root = AzureRouting.normalise(target.baseUrl.toString()) ?: target.baseUrl
        return when (target.options.wire ?: AzureRouting.styleFor(target.sentModelId)) {
            RequestStyle.ANTHROPIC -> anthropic.complete(
                request,
                target.copy(style = RequestStyle.ANTHROPIC, baseUrl = root.newBuilder().addPathSegment("anthropic").build(), options = target.options.copy(messagesPath = "/v1/messages")),
            )
            RequestStyle.RESPONSES -> responses.complete(
                request,
                target.copy(style = RequestStyle.RESPONSES, baseUrl = root.newBuilder().addPathSegments("openai/v1").build()),
            )
            else -> {
                val own = target.options.compat ?: target.model.compat
                val field = own.maxTokensField
                    ?: if (MAX_COMPLETION_TOKENS.containsMatchIn(target.sentModelId.trim().lowercase())) "max_completion_tokens" else "max_tokens"
                chat.complete(
                    request,
                    target.copy(
                        style = RequestStyle.CHAT_COMPLETIONS,
                        baseUrl = root.newBuilder().addPathSegments("openai/v1").build(),
                        options = target.options.copy(compat = own.copy(maxTokensField = field)),
                    ),
                )
            }
        }
    }

    private companion object {
        val MAX_COMPLETION_TOKENS = Regex("^(?:gpt-(?:[5-9]|\\d{2})|o\\d)")
    }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 94 tests in `:core:providers`: Task 7's 87, `AzureAdapterTest` 4 and `AzureRoutingTest` 3.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 94; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit without a finding.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/AzureAdapter.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/AzureRouting.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/AzureAdapterTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/AzureRoutingTest.kt core/providers/src/test/resources/fixtures/azure
git commit -m "feat(providers): add Azure OpenAI and Microsoft Foundry with the API chosen per deployment" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: JSON actions, the entry points and the documents

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/JsonActions.kt`
- Modify: `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt` (its imports, and `object Adapters` at its end), `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/Discoveries.kt` (whole file)
- Create (fixtures): `core/providers/src/test/resources/fixtures/chat-completions/models.json`, `anthropic/models-1.json`, `anthropic/models-2.json`, `gemini/models.json`, `bedrock/mantle-models.json`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/styles/JsonActionsTest.kt`, `AdaptersTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/DiscoveriesTest.kt`
- Modify: `README.md` (the marker `<!-- Plan 4B: request styles -->`), `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 10.3)

**Interfaces:**
- Consumes: every adapter of Tasks 1 to 8; Task 3's `AnthropicAdapter.VERSION`; Task 5's `LocalServerDiscovery`; Task 6's `BedrockModels` and `MantleRouting.modelsBase`; plan 4A's `ProviderEntry` (`id`, `style`, `discovery`), `Discovery`, `CatalogLoader.load`, and the repository's `catalog/` folder, which `:core:providers`' tests find through the system property `kestrel.catalog`.
- Produces ("Shared names"): `object Adapters { fun forStyle(style: RequestStyle, http: ProviderHttp): ChatAdapter }`; `class JsonActions(inner: ChatAdapter) : ChatAdapter` with `companion object { fun instruction(tools: List<ToolSpec>): String; fun schema(names: List<String>): JsonObject }`; `object Discoveries { fun forProvider(provider: ProviderEntry, baseUrl: HttpUrl, region: String?, auth: RequestAuth, http: ProviderHttp): ModelDiscovery?; fun forLocalServer(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp): ModelDiscovery }`. Plan 4C builds `ProfileClient`, `TargetResolver`, the add flow's "Check" and the stand-in on them.

Design D6, D7, D13, D19 and D23. `JsonActions` (design D7) wraps any adapter: it sends no tool definitions, adds a fixed instruction to the end of the system prompt that lists each tool's name, description and parameter schema and asks for exactly one JSON object `{"tool": "<name>", "arguments": {…}}`, and sets `ChatRequest.jsonSchema` to the action schema `{"type": "object", "properties": {"tool": {"type": "string", "enum": [<tool names>]}, "arguments": {"type": "object"}}, "required": ["tool", "arguments"]}`, which each adapter passes on where its style allows (Chat Completions' `response_format` when `supportsJsonSchemaResponseFormat`, Ollama's `format`, Gemini's `responseMimeType`; otherwise only the instruction). It strips a code fence from the reply, takes the first complete JSON object (strings and escapes skipped), and checks that `tool` names a tool of the request and that `arguments` is an object with the schema's required keys. A good reply becomes a tool call with the ID `json-<n>`, counted over the conversation's earlier JSON actions, and the stop reason `TOOL_CALL`; the reply's reasoning parts stay. A bad one keeps its text and gets `InvalidToolCall` (`NO_ACTION`, `BAD_JSON`, `UNKNOWN_TOOL` or `MISSING_ARGUMENT`), so that the agent can ask once more (spec 14.2). Earlier calls and results go back as text, because every adapter replays tool history as text when a request has no tools (design D5).

`Adapters.forStyle` gives each of the eight styles its adapter; for `VERTEX` and `AZURE` it is the platform's adapter, which chooses the wire per model. `Discoveries.forProvider` (design D13) builds a provider's list from its catalog entry: Bedrock through its control plane in the profile's region (Task 6); Mantle at `https://bedrock-mantle.<region>.api.aws/v1/models` whatever base it gets (review A R2-I2, review B R2-M3); none for a `STATIC` provider, for Vertex and Azure (the owner types or picks the model) or for a Bedrock or Mantle profile without a region; and otherwise by style: `GET <base>/models` for Chat Completions and Responses (with the fields that OpenRouter adds: `supported_parameters` with `tools` and `reasoning`, `architecture.input_modalities`, `context_length`), `GET <base>/v1/models` for Anthropic Messages (with `anthropic-version`, page by page with `after_id`), `GET <base>/models` for Gemini (only the models that can `generateContent`, page by page with `pageToken`), and Ollama's tags for Ollama Cloud. Mantle's five Claude rows stay in the catalog beside the discovered list, because `/v1/models` lacks them (research r34); plan 4C offers both. Plan 4C never calls `forProvider` for Copilot, the ChatGPT plan or xAI's proxy, whose lists it builds itself.

`AdaptersTest` also checks design D6's usage table across all eight styles with each style's fixture.

- [ ] **Step 1: Write the fixtures of the model lists**

These follow OpenAI's and OpenRouter's `/models`, Anthropic's "List Models" reference (`https://platform.claude.com/docs/en/api/models/list`: `data[]` with `id`, `display_name`, `max_input_tokens`, `max_tokens` and `capabilities`, and `has_more` with `last_id`), Google's `models.list` reference (`https://ai.google.dev/api/models#method:-models.list`: `models[]` with `name`, `displayName`, `inputTokenLimit`, `outputTokenLimit`, `supportedGenerationMethods` and `thinking`, and `nextPageToken`) and Mantle's OpenAI-shaped list (research r34). Research did not read Anthropic's and Gemini's list formats (design D13), so the writer read these two pages; a mismatch would only hide discovered models, because the catalog's rows remain.

Create `core/providers/src/test/resources/fixtures/chat-completions/models.json`:

```json
{
  "data": [
    {
      "id": "anthropic/claude-sonnet-5",
      "name": "Anthropic: Claude Sonnet 5",
      "context_length": 1000000,
      "architecture": {
        "input_modalities": [
          "text",
          "image"
        ],
        "output_modalities": [
          "text"
        ]
      },
      "top_provider": {
        "max_completion_tokens": 128000
      },
      "supported_parameters": [
        "tools",
        "tool_choice",
        "reasoning",
        "max_tokens"
      ]
    },
    {
      "id": "mistralai/mistral-small",
      "name": "Mistral: Mistral Small",
      "context_length": 32768,
      "architecture": {
        "input_modalities": [
          "text"
        ]
      },
      "supported_parameters": [
        "max_tokens",
        "temperature"
      ]
    },
    {
      "id": "plain-model",
      "object": "model",
      "owned_by": "someone"
    }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/anthropic/models-1.json`:

```json
{
  "data": [
    {
      "type": "model",
      "id": "claude-sonnet-5",
      "display_name": "Claude Sonnet 5",
      "created_at": "2026-05-01T00:00:00Z",
      "max_input_tokens": 1000000,
      "max_tokens": 128000,
      "capabilities": {
        "image_input": {
          "supported": true
        },
        "thinking": {
          "supported": true,
          "types": {
            "adaptive": {
              "supported": true
            }
          }
        },
        "effort": {
          "supported": true,
          "low": {
            "supported": true
          },
          "medium": {
            "supported": true
          },
          "high": {
            "supported": true
          },
          "xhigh": {
            "supported": true
          },
          "max": {
            "supported": true
          }
        }
      }
    }
  ],
  "has_more": true,
  "first_id": "claude-sonnet-5",
  "last_id": "claude-sonnet-5"
}
```

Create `core/providers/src/test/resources/fixtures/anthropic/models-2.json`:

```json
{
  "data": [
    {
      "type": "model",
      "id": "claude-haiku-4-5",
      "display_name": "Claude Haiku 4.5",
      "created_at": "2025-10-01T00:00:00Z",
      "max_input_tokens": 200000,
      "max_tokens": 64000,
      "capabilities": {
        "image_input": {
          "supported": true
        },
        "thinking": {
          "supported": true
        },
        "effort": {
          "supported": false
        }
      }
    }
  ],
  "has_more": false,
  "first_id": "claude-haiku-4-5",
  "last_id": "claude-haiku-4-5"
}
```

Create `core/providers/src/test/resources/fixtures/gemini/models.json`:

```json
{
  "models": [
    {
      "name": "models/gemini-3.8-flash",
      "displayName": "Gemini 3.8 Flash",
      "inputTokenLimit": 1048576,
      "outputTokenLimit": 65536,
      "supportedGenerationMethods": [
        "generateContent",
        "countTokens",
        "createCachedContent"
      ],
      "thinking": true
    },
    {
      "name": "models/gemini-embedding-001",
      "displayName": "Gemini Embedding 001",
      "inputTokenLimit": 2048,
      "outputTokenLimit": 1,
      "supportedGenerationMethods": [
        "embedContent",
        "countTextTokens"
      ]
    }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/bedrock/mantle-models.json`:

```json
{
  "object": "list",
  "data": [
    {
      "id": "openai.gpt-oss-120b",
      "object": "model",
      "owned_by": "openai"
    },
    {
      "id": "qwen.qwen3-235b-a22b",
      "object": "model",
      "owned_by": "qwen"
    }
  ]
}
```

- [ ] **Step 2: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/JsonActionsTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.styles.AdapterHarness.Companion.GET_NUMBER
import dev.kestrel.providers.styles.AdapterHarness.Companion.OPEN_APP
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonActionsTest {
    /** An adapter that answers with [reply] and keeps the request it got. */
    private class Scripted(private val reply: String, private val extra: List<AssistantPart> = emptyList()) : ChatAdapter {
        var sent: ChatRequest? = null

        override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
            sent = request
            return ChatResponse(ChatMessage.Assistant(extra + AssistantPart.Text(reply)), StopReason.END, Usage(10, 5), "m")
        }
    }

    private val model = target("ollama", RequestStyle.OLLAMA, "http://127.0.0.1:11434", ModelEntry("m", "M"))

    private fun run(reply: String, vararg history: ChatMessage, extra: List<AssistantPart> = emptyList()): Pair<ChatResponse, ChatRequest> {
        val inner = Scripted(reply, extra)
        val messages = if (history.isEmpty()) arrayOf<ChatMessage>(user("Open the camera.")) else arrayOf(*history)
        val response = runBlocking { JsonActions(inner).complete(request(*messages, tools = listOf(OPEN_APP, GET_NUMBER)) { copy(toolChoice = ToolChoice.REQUIRED) }, model) }
        return response to inner.sent!!
    }

    @Test
    fun theInnerAdapterGetsNoToolsTheInstructionAndTheActionSchema() {
        val (_, sent) = run("""{"tool":"get_number","arguments":{}}""")

        assertTrue(sent.tools.isEmpty())
        assertEquals(ToolChoice.AUTO, sent.toolChoice)
        assertTrue(sent.system.startsWith("You are Kestrel.\n\nYou act by choosing one of these actions."))
        assertTrue(sent.system.contains("- open_app: Opens an app by its name. Parameters: ${OPEN_APP.parameters}"))
        assertEquals(
            obj("""{"type":"object","properties":{"tool":{"type":"string","enum":["open_app","get_number"]},"arguments":{"type":"object"}},"required":["tool","arguments"]}"""),
            sent.jsonSchema,
        )
    }

    @Test
    fun anActionInACodeFenceBecomesAToolCallAndKeepsTheReasoning() {
        val thinking = AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "ollama", "m", obj("""{"type":"thinking"}""")))

        val (response, _) = run("Sure.\n```json\n{\"tool\": \"open_app\", \"arguments\": {\"name\": \"Camera\"}}\n```", extra = listOf(thinking))

        assertEquals(listOf(thinking, AssistantPart.ToolCall("json-1", "open_app", obj("""{"name":"Camera"}"""))), response.message.parts)
        assertEquals(StopReason.TOOL_CALL, response.stop)
        assertNull(response.invalidToolCall)
    }

    @Test
    fun theIdCountsTheEarlierJsonActions() {
        val earlier = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("json-1", "get_number", obj("{}"))))

        val (response, _) = run(
            """{"tool":"open_app","arguments":{"name":"Clock"}} and more text""",
            user("Open the clock."), earlier, ChatMessage.ToolResult("json-1", "get_number", "7"),
        )

        assertEquals(listOf("json-2"), response.message.toolCalls.map { it.id })
    }

    @Test
    fun aReplyWithoutAValidActionKeepsItsTextAndSaysWhy() {
        val cases = mapOf(
            "I opened it." to InvalidKind.NO_ACTION,
            """{"tool": "open_app", "arguments": {"name": "Camera"}""" to InvalidKind.BAD_JSON,
            """{"tool": "open_app", "arguments": "Camera"}""" to InvalidKind.BAD_JSON,
            """{"tool": "close_app", "arguments": {}}""" to InvalidKind.UNKNOWN_TOOL,
            """{"tool": "open_app", "arguments": {}}""" to InvalidKind.MISSING_ARGUMENT,
        )
        for ((reply, kind) in cases) {
            val (response, _) = run(reply)
            assertEquals(reply, kind, response.invalidToolCall?.kind)
            assertEquals(reply, response.invalidToolCall?.text)
            assertEquals(listOf(AssistantPart.Text(reply)), response.message.parts)
        }
    }

    @Test
    fun aBraceInsideAStringDoesNotEndTheAction() {
        val (response, _) = run("""{"tool":"open_app","arguments":{"name":"Notes {draft}"}}""")

        assertEquals(obj("""{"name":"Notes {draft}"}"""), response.message.toolCalls.single().arguments)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/styles/AdaptersTest.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptersTest {
    private val bench = AdapterHarness()

    @After
    fun close() = bench.close()

    @Test
    fun eachStyleHasItsAdapter() {
        val expected = mapOf(
            RequestStyle.CHAT_COMPLETIONS to ChatCompletionsAdapter::class,
            RequestStyle.RESPONSES to ResponsesAdapter::class,
            RequestStyle.ANTHROPIC to AnthropicAdapter::class,
            RequestStyle.GEMINI to GeminiAdapter::class,
            RequestStyle.OLLAMA to OllamaAdapter::class,
            RequestStyle.BEDROCK to BedrockAdapter::class,
            RequestStyle.VERTEX to VertexAdapter::class,
            RequestStyle.AZURE to AzureAdapter::class,
        )
        for (style in RequestStyle.entries) assertEquals(expected[style], Adapters.forStyle(style, bench.http)::class)
    }

    /** Design D6: each style's own usage fields, normalised to uncached input, output, cache reads and writes, and reasoning. */
    @Test
    fun theUsageOfEveryStyleIsNormalisedAsDesignD6Says() = runBlocking {
        val cases = listOf(
            Triple(RequestStyle.CHAT_COMPLETIONS, "chat-completions/text.sse", Usage(input = 100, output = 40, cacheRead = 800, cacheWrite = 300, reasoning = 25)),
            Triple(RequestStyle.RESPONSES, "responses/text.sse", Usage(input = 300, output = 50, cacheRead = 1500, cacheWrite = 200, reasoning = 30)),
            Triple(RequestStyle.ANTHROPIC, "anthropic/text.sse", Usage(input = 50, output = 42, cacheRead = 2000, cacheWrite = 1000)),
            Triple(RequestStyle.GEMINI, "gemini/text.sse", Usage(input = 410, output = 50, cacheRead = 600, reasoning = 20)),
            Triple(RequestStyle.OLLAMA, "ollama/text.json", Usage(input = 20, output = 8, cacheRead = 100)),
            Triple(RequestStyle.BEDROCK, "bedrock/text.json", Usage(input = 100, output = 12, cacheRead = 900, cacheWrite = 50)),
            Triple(RequestStyle.VERTEX, "vertex/gemini-text.sse", Usage(input = 40, output = 8)),
            Triple(RequestStyle.AZURE, "azure/chat-text.sse", Usage(input = 20, output = 5)),
        )
        val bases = mapOf(
            RequestStyle.CHAT_COMPLETIONS to "https://api.openai.com/v1",
            RequestStyle.RESPONSES to "https://api.openai.com/v1",
            RequestStyle.ANTHROPIC to "https://api.anthropic.com",
            RequestStyle.GEMINI to "https://generativelanguage.googleapis.com/v1beta",
            RequestStyle.OLLAMA to "http://127.0.0.1:11434",
            RequestStyle.BEDROCK to "https://bedrock-runtime.us-east-1.amazonaws.com",
            RequestStyle.VERTEX to VertexUrls.base("global", "kestrel-test-project").toString(),
            RequestStyle.AZURE to "https://kestrel.openai.azure.com",
        )
        for ((style, fixture, usage) in cases) {
            bench.reply(fixture)
            val model = if (style == RequestStyle.AZURE) ModelEntry("Mistral-Large-3", "Mistral Large 3") else ModelEntry("m", "M")
            val response = Adapters.forStyle(style, bench.http).complete(request(user("Hi")), target("test-provider", style, bases.getValue(style), model))
            assertEquals(style.name, usage, response.usage)
        }
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/DiscoveriesTest.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.styles.AdapterHarness
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs against the repository's catalog, so that each provider's entry decides its list as it will in the app. The model
 * lists follow the providers' documented formats, because no request went to a provider: OpenRouter's "List all models",
 * Anthropic's "List Models" (`GET /v1/models`, paged by `after_id`), Google's `models.list` (paged by `pageToken`) and
 * Mantle's OpenAI-compatible `/v1/models`.
 */
class DiscoveriesTest {
    private val bench = AdapterHarness()
    private val catalog: Catalog = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog")).let {
        CatalogLoader.load(it.resolve("providers.json").readText(), it.resolve("overrides.json").readText())
    }

    @After
    fun close() = bench.close()

    private fun list(provider: String, base: String, region: String? = null): List<DiscoveredModel>? = runBlocking {
        Discoveries.forProvider(catalog.provider(provider)!!, base.toHttpUrl(), region, NoAuth, bench.http)?.list()
    }

    @Test
    fun aChatCompletionsProviderListsItsModelsWithWhatTheListReports() {
        bench.reply("chat-completions/models.json")

        val models = list("openrouter", "https://openrouter.ai/api/v1")!!

        assertEquals(
            listOf(
                DiscoveredModel("anthropic/claude-sonnet-5", "Anthropic: Claude Sonnet 5", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 1000000, maxTokens = 128000, tools = true),
                DiscoveredModel("mistralai/mistral-small", "Mistral: Mistral Small", contextWindow = 32768, tools = false),
                DiscoveredModel("plain-model", "plain-model"),
            ),
            models,
        )
        assertEquals(listOf("https://openrouter.ai/api/v1/models".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun anthropicsListIsReadPageByPageWithItsVersionHeader() {
        bench.reply("anthropic/models-1.json")
        bench.reply("anthropic/models-2.json")

        val models = list("anthropic", "https://api.anthropic.com")!!

        assertEquals(listOf("claude-sonnet-5", "claude-haiku-4-5"), models.map { it.id })
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), models[0].reasoningEfforts)
        assertNull(models[1].reasoningEfforts)
        assertEquals(setOf(InputKind.TEXT, InputKind.IMAGE), models[0].input)
        assertEquals(
            listOf("https://api.anthropic.com/v1/models?limit=1000", "https://api.anthropic.com/v1/models?limit=1000&after_id=claude-sonnet-5").map { it.toHttpUrl() },
            bench.calls.requests,
        )
        assertEquals("2023-06-01", bench.take().headers["anthropic-version"])
    }

    @Test
    fun geminiListsOnlyTheModelsThatGenerateContent() {
        bench.reply("gemini/models.json")

        val models = list("google", "https://generativelanguage.googleapis.com/v1beta")!!

        assertEquals(listOf(DiscoveredModel("gemini-3.8-flash", "Gemini 3.8 Flash", reasoning = true, contextWindow = 1048576, maxTokens = 65536)), models)
        assertEquals(listOf("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun mantleListsAtItsRegionsV1ModelsAndItsClaudeRowsStayInTheCatalog() {
        bench.reply("bedrock/mantle-models.json")

        val models = list("amazon-bedrock-mantle", "https://bedrock-mantle.eu-west-1.api.aws/anthropic", region = "eu-west-1")!!

        assertEquals(listOf("openai.gpt-oss-120b", "qwen.qwen3-235b-a22b"), models.map { it.id })
        assertEquals(listOf("https://bedrock-mantle.eu-west-1.api.aws/v1/models".toHttpUrl()), bench.calls.requests)
        val claude = catalog.provider("amazon-bedrock-mantle")!!.models.filter { it.style == RequestStyle.ANTHROPIC }.map { it.id }
        assertEquals(
            listOf("anthropic.claude-mythos-5", "anthropic.claude-mythos-preview", "anthropic.claude-opus-4-7", "anthropic.claude-opus-5", "anthropic.claude-sonnet-5"),
            claude,
        )
        assertTrue(claude.none { id -> models.any { it.id == id } })
    }

    @Test
    fun providersWithoutAListGetNone() {
        assertNull(Discoveries.forProvider(catalog.provider("synthetic")!!, "https://api.synthetic.new/anthropic".toHttpUrl(), null, NoAuth, bench.http))
        assertNull(Discoveries.forProvider(catalog.provider("google-vertex")!!, "https://aiplatform.googleapis.com".toHttpUrl(), "global", NoAuth, bench.http))
        assertNull(Discoveries.forProvider(catalog.provider("microsoft-foundry")!!, "https://kestrel.openai.azure.com".toHttpUrl(), null, NoAuth, bench.http))
        assertNull(Discoveries.forProvider(catalog.provider("amazon-bedrock")!!, "https://bedrock-runtime.us-east-1.amazonaws.com".toHttpUrl(), null, NoAuth, bench.http))
        assertEquals(0, bench.server.requestCount)
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'JsonActions'", "Unresolved reference 'Adapters'" and "Unresolved reference 'forProvider'".

- [ ] **Step 4: Write JSON actions**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/styles/JsonActions.kt`:

```kotlin
package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.InvalidToolCall
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.ToolSpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON actions for models that handle tool calls badly (design D7). It wraps any adapter: it sends no tool definitions,
 * adds a fixed instruction with each tool's name, description and parameter schema to the end of the system prompt, and
 * sets [ChatRequest.jsonSchema] to the action schema, which each adapter passes on where its style allows. The reply's
 * first JSON object, after any code fence, must name a tool of the request and give an `arguments` object with the
 * schema's required keys; it becomes a normal tool call with the ID `json-<n>`. Any other reply keeps its text and gets
 * an [InvalidToolCall], so that the agent can ask once more (spec 14.2). Earlier calls and results go back as text.
 */
class JsonActions(private val inner: ChatAdapter) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val names = request.tools.map { it.name }
        val sent = request.copy(
            system = listOf(request.system, instruction(request.tools)).filter { it.isNotEmpty() }.joinToString("\n\n"),
            tools = emptyList(),
            toolChoice = ToolChoice.AUTO,
            jsonSchema = schema(names),
        )
        val response = inner.complete(sent, target)
        val text = response.message.parts.filterIsInstance<AssistantPart.Text>().joinToString("") { it.text }
        val action = read(text, request.tools)
        if (action is Action.Bad) {
            return response.copy(invalidToolCall = InvalidToolCall(action.kind, text))
        }
        action as Action.Good
        val id = "json-${request.messages.sumOf { message -> (message as? ChatMessage.Assistant)?.toolCalls?.count { it.id.startsWith("json-") } ?: 0 } + 1}"
        val parts = response.message.parts.filterNot { it is AssistantPart.Text || it is AssistantPart.ToolCall } +
            AssistantPart.ToolCall(id, action.tool, action.arguments)
        return response.copy(message = ChatMessage.Assistant(parts), stop = StopReason.TOOL_CALL, invalidToolCall = null)
    }

    private sealed interface Action {
        class Good(val tool: String, val arguments: JsonObject) : Action

        class Bad(val kind: InvalidKind) : Action
    }

    private fun read(text: String, tools: List<ToolSpec>): Action {
        val unfenced = FENCE.find(text)?.groupValues?.get(1) ?: text
        val start = unfenced.indexOf('{')
        if (start < 0) return Action.Bad(InvalidKind.NO_ACTION)
        val end = objectEnd(unfenced, start) ?: return Action.Bad(InvalidKind.BAD_JSON)
        val action = try {
            Wire.json.parseToJsonElement(unfenced.substring(start, end + 1)) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return Action.Bad(InvalidKind.BAD_JSON)
        val name = (action["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Action.Bad(InvalidKind.BAD_JSON)
        val tool = tools.firstOrNull { it.name == name } ?: return Action.Bad(InvalidKind.UNKNOWN_TOOL)
        val arguments = action["arguments"] as? JsonObject ?: return Action.Bad(InvalidKind.BAD_JSON)
        val required = (tool.parameters["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (required.any { it !in arguments }) return Action.Bad(InvalidKind.MISSING_ARGUMENT)
        return Action.Good(name, arguments)
    }

    /** The index of the brace that closes the object opened at [start], with strings and escapes skipped. */
    private fun objectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    companion object {
        private val FENCE = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")

        /** The fixed instruction of design D7. */
        fun instruction(tools: List<ToolSpec>): String = buildString {
            append("You act by choosing one of these actions. Reply with exactly one JSON object of the form ")
            append("{\"tool\": \"<name>\", \"arguments\": {...}} and nothing else.\n")
            append("Actions:")
            for (tool in tools) append("\n- ${tool.name}: ${tool.description} Parameters: ${tool.parameters}")
        }

        /** The action schema of design D7: `tool` is one of the names, `arguments` any object. */
        fun schema(names: List<String>): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("tool") {
                    put("type", "string")
                    putJsonArray("enum") { names.forEach { add(it) } }
                }
                putJsonObject("arguments") { put("type", "object") }
            }
            putJsonArray("required") {
                add("tool")
                add("arguments")
            }
        }
    }
}
```

- [ ] **Step 5: Add the adapters' entry point**

In `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt`, replace

```kotlin
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
```

with

```kotlin
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
```

In `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt`, replace

```kotlin
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.StopReason
```

with

```kotlin
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
```

In `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt`, replace

```kotlin
import dev.kestrel.providers.http.ProviderException
import kotlinx.serialization.SerializationException
```

with

```kotlin
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.serialization.SerializationException
```

In `core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt`, add at the end of the file, after a blank line:

```kotlin
/**
 * The adapter of each request style (spec 10.3, design D4). For VERTEX and AZURE it returns the platform's adapter, which
 * chooses the wire format per model (StyleOptions.wire). Plan 4C reaches the adapters only through this object.
 */
object Adapters {
    fun forStyle(style: RequestStyle, http: ProviderHttp): ChatAdapter = when (style) {
        RequestStyle.CHAT_COMPLETIONS -> ChatCompletionsAdapter(http)
        RequestStyle.RESPONSES -> ResponsesAdapter(http)
        RequestStyle.ANTHROPIC -> AnthropicAdapter(http)
        RequestStyle.GEMINI -> GeminiAdapter(http)
        RequestStyle.OLLAMA -> OllamaAdapter(http)
        RequestStyle.BEDROCK -> BedrockAdapter(http)
        RequestStyle.VERTEX -> VertexAdapter(http)
        RequestStyle.AZURE -> AzureAdapter(http)
    }
}
```

- [ ] **Step 6: Add the providers' lists**

Replace the whole file `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/Discoveries.kt` with:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.styles.AnthropicAdapter
import dev.kestrel.providers.styles.MantleRouting
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl

/** Where a profile's model list comes from (design D13). It runs only when a profile is created or the owner taps "Refresh models". */
object Discoveries {
    /**
     * The list of a provider whose catalog entry says REFRESHABLE or RUNTIME, by its style: `GET <base>/models` for Chat
     * Completions and Responses, `GET <base>/v1/models` for Anthropic Messages, `GET <base>/models` for Gemini (the models
     * that can `generateContent`), and Ollama's tags for Ollama Cloud. Bedrock lists its models through its control plane
     * in [region], and Mantle at `https://bedrock-mantle.<region>.api.aws/v1/models` whatever base it gets. Null for a
     * STATIC provider, for Vertex and Azure (the owner types the model), and for a Bedrock or Mantle profile without a
     * region. Plan 4C never calls it for Copilot, the ChatGPT plan or xAI's proxy, whose lists it builds itself.
     */
    fun forProvider(provider: ProviderEntry, baseUrl: HttpUrl, region: String?, auth: RequestAuth, http: ProviderHttp): ModelDiscovery? =
        when {
            provider.id == BEDROCK -> region?.let { BedrockModels(it, auth, http) }
            provider.id == MANTLE -> region?.let { OpenAiModels(MantleRouting.modelsBase(it), auth, http) }
            provider.discovery == Discovery.STATIC -> null
            else -> when (provider.style) {
                RequestStyle.CHAT_COMPLETIONS, RequestStyle.RESPONSES -> OpenAiModels(baseUrl, auth, http)
                RequestStyle.ANTHROPIC -> AnthropicModelList(baseUrl, auth, http)
                RequestStyle.GEMINI -> GeminiModelList(baseUrl, auth, http)
                RequestStyle.OLLAMA -> LocalServerDiscovery(LocalServerKind.OLLAMA, baseUrl, auth, http)
                else -> null
            }
        }

    /** The list of a local server of [kind] at [baseUrl], whose paths start at the address's origin (design D11). */
    fun forLocalServer(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp): ModelDiscovery =
        LocalServerDiscovery(kind, baseUrl, auth, http)

    private const val BEDROCK = "amazon-bedrock"
    private const val MANTLE = "amazon-bedrock-mantle"
}

private val LIST_JSON = Json { ignoreUnknownKeys = true }

/** Reads one page of a list, or fails with Malformed. */
private suspend fun ProviderHttp.listPage(url: HttpUrl, auth: RequestAuth, headers: Map<String, String> = emptyMap()): JsonObject {
    val text = send(HttpRequest("GET", url, mapOf("accept" to "application/json") + headers), auth).text()
    return try {
        LIST_JSON.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw ProviderException(ProviderError.Malformed)
}

private fun JsonElement?.rows(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.whole(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.texts(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }

/**
 * OpenAI's `GET <base>/models` (`data[].id`), with the fields that OpenRouter-like lists add: `name`, `context_length`,
 * `architecture.input_modalities`, `supported_parameters` (`tools`, `reasoning`) and `top_provider.max_completion_tokens`.
 */
private class OpenAiModels(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> =
        http.listPage(base.newBuilder().addPathSegment("models").build(), auth)["data"].rows().mapNotNull { row ->
            val id = row.text("id") ?: return@mapNotNull null
            val parameters = row.texts("supported_parameters")
            val modalities = (row["architecture"] as? JsonObject)?.texts("input_modalities")
            DiscoveredModel(
                id = id,
                name = row.text("name") ?: id,
                input = if (modalities?.contains("image") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = parameters?.contains("reasoning") == true,
                contextWindow = row.whole("context_length") ?: row.whole("context_window"),
                maxTokens = (row["top_provider"] as? JsonObject)?.whole("max_completion_tokens"),
                tools = parameters?.contains("tools"),
            )
        }
}

/**
 * Anthropic's `GET <base>/v1/models` (Anthropic's "List Models" reference): `data[]` with `id`, `display_name`,
 * `max_input_tokens`, `max_tokens` and `capabilities` (`image_input`, `thinking`, `effort`), read page by page with
 * `after_id` while `has_more` is true.
 */
private class AnthropicModelList(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> {
        val models = mutableListOf<DiscoveredModel>()
        var after: String? = null
        do {
            val url = base.newBuilder().addPathSegments("v1/models").addQueryParameter("limit", "1000")
                .apply { after?.let { addQueryParameter("after_id", it) } }.build()
            val page = http.listPage(url, auth, mapOf("anthropic-version" to AnthropicAdapter.VERSION))
            for (row in page["data"].rows()) {
                val id = row.text("id") ?: continue
                val capabilities = row["capabilities"] as? JsonObject
                val effort = (capabilities?.get("effort") as? JsonObject)?.takeIf { it.flag("supported") == true }
                models += DiscoveredModel(
                    id = id,
                    name = row.text("display_name") ?: id,
                    input = if (capabilities.supported("image_input")) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                    reasoning = capabilities.supported("thinking"),
                    reasoningEfforts = effort?.let { levels -> EFFORTS.filter { levels.supported(it) } },
                    contextWindow = row.whole("max_input_tokens"),
                    maxTokens = row.whole("max_tokens"),
                )
            }
            after = page.text("last_id")?.takeIf { page.flag("has_more") == true }
        } while (after != null)
        return models
    }

    private fun JsonObject?.supported(name: String): Boolean = (this?.get(name) as? JsonObject)?.flag("supported") == true

    private companion object {
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}

/**
 * Gemini's `GET <base>/models` (Google's `models.list` reference): `models[]` with `name` (`models/<id>`), `displayName`,
 * `inputTokenLimit`, `outputTokenLimit`, `supportedGenerationMethods` and `thinking`, read page by page with `pageToken`.
 * Only the models that can `generateContent` are listed.
 */
private class GeminiModelList(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> {
        val models = mutableListOf<DiscoveredModel>()
        var token: String? = null
        do {
            val url = base.newBuilder().addPathSegment("models").addQueryParameter("pageSize", "1000")
                .apply { token?.let { addQueryParameter("pageToken", it) } }.build()
            val page = http.listPage(url, auth)
            for (row in page["models"].rows()) {
                if (row.texts("supportedGenerationMethods")?.contains("generateContent") != true) continue
                val id = row.text("name")?.removePrefix("models/") ?: continue
                models += DiscoveredModel(
                    id = id,
                    name = row.text("displayName") ?: id,
                    reasoning = row.flag("thinking") == true,
                    contextWindow = row.whole("inputTokenLimit"),
                    maxTokens = row.whole("outputTokenLimit"),
                )
            }
            token = page.text("nextPageToken")?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return models
    }
}
```

- [ ] **Step 7: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 106 tests in `:core:providers`: Task 8's 94, `JsonActionsTest` 5, `AdaptersTest` 2 and `DiscoveriesTest` 5.

- [ ] **Step 8: Add the README's paragraph**

In `README.md`, replace

```markdown
<!-- Plan 4B: request styles -->
```

with

```markdown
Kestrel sends each profile's requests in its provider's request style, one of eight (design spec, section 10.3): OpenAI's Chat Completions and Responses, Anthropic Messages, Gemini, Ollama, Bedrock Converse, Vertex, and Azure OpenAI with Microsoft Foundry. It streams the answer from most providers and calls Bedrock, Ollama and Claude on Vertex without streaming, and it always works with one complete answer, keeping the reasoning data that a provider needs on the next request. For a model that handles tool calls badly, it asks for a JSON action instead of a tool call. The tests of the request styles use answers written from each provider's documented format, under `core/providers/src/test/resources/fixtures/`, and a local mock server, so no test sends a request to a provider.

<!-- Plan 4B: request styles -->
```

- [ ] **Step 9: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
### 10.3 Request styles

Kestrel uses one internal request format (messages, tools, images, reasoning settings and limits) and eight adapters:

1. **OpenAI Chat Completions**, with per-provider quirk flags: tool support, JSON mode, reasoning parameters, the name of the token-limit field, and Gemini thought signatures when Gemini is used through this style.
2. **OpenAI Responses**, for OpenAI, Copilot, xAI, Meta and ClawRouter, and for the ChatGPT/Codex backend with `store: false`.
3. **Anthropic Messages**, for Anthropic, Kimi Coding, Vercel AI Gateway, and any other provider whose OpenClaw code uses this style.
4. **Gemini**, in its native format.
5. **Ollama**, in its native format.
6. **Bedrock Converse**, with AWS SigV4 request signing.
7. **Vertex**, for Gemini and Anthropic models on Vertex, with service-account tokens.
8. **Azure OpenAI and Microsoft Foundry**, with deployment URLs and the API-key header.

- An adapter may use a streaming transport when a provider requires it, but it always returns one complete response to the agent.
- Adapters keep the reasoning data that a provider needs on the next request, such as Gemini thought signatures and Anthropic thinking blocks on the latest tool call.
- For models that handle tool calls badly, an adapter can leave out the tool definitions and ask for a JSON action that matches a schema instead.
```

with

```markdown
### 10.3 Request styles

Kestrel uses one internal request format (messages, tools, images, reasoning settings and limits) and eight adapters:

1. **OpenAI Chat Completions**, for most providers of the catalog, Copilot's Gemini models, Bedrock Mantle's open models and Azure's other deployments. Each model's compat flags come from the catalog; where they are silent, Kestrel derives them from the provider and its base URL as OpenClaw does: tool support, JSON mode, the reasoning parameter and its format, the name of the token-limit field, the reasoning text that some providers want back, and Gemini thought signatures when Gemini is used through this style.
2. **OpenAI Responses**, for OpenAI's API keys and the ChatGPT plan at `api.openai.com`, xAI's API keys and the proxy of xAI's sign-in, Meta, ClawRouter, Copilot's other models, Mantle's GPT models, OpenCode's GPT models and Azure's GPT deployments, always with `store: false`. The ChatGPT plan's requests leave out every field that its preview rejects and put Kestrel's tools into one namespace.
3. **Anthropic Messages**, for Anthropic, MiniMax, Synthetic, Vercel AI Gateway, Copilot's Claude models, Mantle's Claude models, OpenCode's Claude and Qwen models and Claude deployments on Microsoft Foundry. MiniMax, Copilot and Mantle send the key as a bearer token.
4. **Gemini**, in its native format, for Google's API keys and OpenCode's Gemini models.
5. **Ollama**, in its native format, for an Ollama server and Ollama Cloud.
6. **Bedrock Converse**, with a Bedrock API key as a bearer token or IAM access keys signed with AWS SigV4.
7. **Vertex**, with a service account's token: Gemini models through `generateContent` and Claude models through `rawPredict`.
8. **Azure OpenAI and Microsoft Foundry**, at the v1 base URL with the deployment name as the model and the `api-key` header, and Claude deployments through Foundry's Anthropic route with `x-api-key`.

- Every adapter streams the answer as server-sent events and gathers it, except Bedrock, Ollama and Claude on Vertex, which use their documented calls without streaming. It always returns one complete response to the agent. An error inside a stream is handled like an HTTP error, and a stream that ends without its final event is a response that Kestrel cannot read. When a response reports no token usage, Kestrel estimates it and marks the estimate.
- Adapters keep the reasoning data that a provider needs on the next request. Signed or encrypted state goes back only to the same provider and model: Anthropic's thinking and redacted-thinking blocks, unchanged and in their order, the reasoning blocks of Claude on Bedrock, the encrypted reasoning items of Responses, and Gemini's thought signatures. The plain reasoning text of Chat Completions, such as DeepSeek's `reasoning_content`, goes back to the same provider. Ollama keeps none.
- Tool parameters stay within a JSON Schema subset that every style accepts: the types object, string, integer, number, boolean and array, with properties, required properties, array items, enums on strings and descriptions. Every tool call in a request has its result before the next message, and where a style has the switch, Kestrel asks for at most one tool call per step.
- For models that handle tool calls badly, Kestrel leaves out the tool definitions and asks for a JSON action that matches a schema instead. A model uses JSON actions when the catalog says that it has no tool support, when the overrides mark it, when a local server reports that it has no tool support, or when the owner turns them on for it.
```

- [ ] **Step 10: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 106; every other module as plan 4A left it), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit, whose report `core\providers\build\reports\kestrel-audit\network-apis.txt` lists no finding.

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/styles/JsonActions.kt core/providers/src/main/kotlin/dev/kestrel/providers/styles/Adapters.kt core/providers/src/main/kotlin/dev/kestrel/providers/discovery/Discoveries.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/JsonActionsTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/styles/AdaptersTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/discovery/DiscoveriesTest.kt core/providers/src/test/resources/fixtures/chat-completions/models.json core/providers/src/test/resources/fixtures/anthropic/models-1.json core/providers/src/test/resources/fixtures/anthropic/models-2.json core/providers/src/test/resources/fixtures/gemini/models.json core/providers/src/test/resources/fixtures/bedrock/mantle-models.json README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(providers): add JSON actions, Adapters.forStyle and the providers' model lists; describe the request styles" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Counts at the end of this plan

`:core:providers` JVM R₀ + 106 (84 + 106 = 190 by plan 4A's plan): `ChatCompletionsAdapterTest` 12, `CompatDefaultsTest` 7, `ResponsesAdapterTest` 14, `AnthropicAdapterTest` 11, `GeminiAdapterTest` 7, `OllamaAdapterTest` 5, `LocalServerDiscoveryTest` 5, `SigV4AuthTest` 5, `BedrockAdapterTest` 7, `MantleRoutingTest` 2, `BedrockModelsTest` 1, `ServiceAccountAuthTest` 6, `VertexAdapterTest` 3, `VertexUrlsTest` 2, `AzureAdapterTest` 4, `AzureRoutingTest` 3, `JsonActionsTest` 5, `AdaptersTest` 2 and `DiscoveriesTest` 5. Every other count stays as plan 4A left it: `:tools:catalog-sync`, `:app`, `:core:privacy`, `:core:commands`, `:core:voice`, `:core:screen` and `:core:safety` JVM, the device tests and Pester.

## Self-review

**Design coverage.** Design "The plans", plan 4B: Task 1 is Chat Completions with `CompatDefaults` ported from OpenClaw's derivation table, the stream's gathering (text, tool-call deltas by index, the reasoning fields, usage), `reasoning_content` per provider with its empty value, the Gemini thought-signature field, `response_format` from `jsonSchema`, `strict`, and the text replay without tools (D3 to D7). Task 2 is Responses with `response.completed` and its items as the fallback, the stream's error events, encrypted reasoning items and the one resend without them, `response.incomplete` with its stop reasons, and `StyleOptions.chatgptPlan` (`instructions`, `input` as an array, `store: false`, `stream: true`, `include`, none of the rejected fields, the `kestrel` namespace and the replay of its calls, the plan's codes inside the stream and before it, every 429 as `QuotaOrPolicy`) (D4 to D6, D17; R3-I1, R3-I2, R3-M6). Task 3 is Anthropic Messages with thinking and redacted-thinking blocks, adaptive thinking with `output_config.effort` for the models that support it, `REQUIRED` as `auto` and no temperature while the model thinks or when it thinks by default, the cache markers, the stream's `error` event, `stripThinking`, `sanitizeToolCallIds`, `messagesPath`, and the Vertex form. Task 4 is Gemini with `streamGenerateContent?alt=sse`, `x-goog-api-key`, thought signatures and the placeholder, `inlineData`, `functionDeclarations` without `parameters` for an empty object, `responseMimeType` for JSON actions, and usage. Task 5 is Ollama (`/api/chat` without streaming, `images`, `tools`, `format`, usage) and `Discoveries.forLocalServer` with paths from the origin (D11). Task 6 is Bedrock (Converse, the `toolConfig` rule, merged user turns, images, usage, the log path, AWS's token errors, Claude's reasoning blocks and the rule for Claude models that think by default), `SigV4Auth` with AWS's test-suite vectors, `BedrockModels` with research r34's filters, and `MantleRouting`. Task 7 is Vertex (`ServiceAccountAuth` with the JWT, the exchange, the cache that instances share and `invalid_grant` as `Expired`; `VertexAdapter`; `VertexUrls` with the location's host and the checks of the location and the project; the log path). Task 8 is Azure and Foundry (`AzureAdapter`, `AzureRouting`). Task 9 is `JsonActions`, `Adapters.forStyle`, `Discoveries.forProvider` with the Mantle case, the usage table across all styles, the README at the 4B marker and spec 10.3 (D23's 4B part). D19's fixture list for each style: a text answer, a tool call with its result and the reasoning state, a reply with two tool calls and the request after it, an image, usage with cache reads and writes, a missing usage, a 401 with one refresh and one retry, a 429 with `Retry-After`, a 500, an error inside the stream, a malformed body, and a stream that ends early for the streaming styles; Vertex and Azure reuse the adapters that carry these cases and test their own routing.

**Placeholder scan.** Every step has its complete code, fixture or command; no step says "similar to", "add error handling" or "TBD". The only symbol is R₀ (the count that plan 4A leaves), whose value the controller gives in each task's brief.

**Type consistency.** The names that plan 4C uses are those of "Shared names": `Adapters.forStyle(style, http)`, `JsonActions(inner)`, `Discoveries.forProvider(provider, baseUrl, region, auth, http)` and `forLocalServer(kind, baseUrl, auth, http)`, `SigV4Auth(keys, region, service, clock)`, `ServiceAccountAuth(key, http, clock)` (with a fourth parameter `tokens` whose default is the process's `ServiceAccountTokens.SHARED`, so plan 4C's three-argument call stays as it is), `MantleRouting.route(modelId, region)` with `MantleRoute(style, baseUrl)`, `VertexUrls.base(location, projectId)`, `validLocation` and `validProject`, `AzureRouting.styleFor(deployment)` and `normalise(endpoint)`; every adapter class is `internal`. Each style appends its path to `ModelTarget.baseUrl` as "Shared names" says, adds only `accept`, the JSON content type and, on the Anthropic wire, `anthropic-version`, and takes every other header from `ModelTarget.headers` and the credential's header from `RequestAuth`. Every test was compiled and run with kotlinc 2.4.21 against plan 4A's code before this plan was written.
