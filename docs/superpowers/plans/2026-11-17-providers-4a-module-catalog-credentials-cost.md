# Providers 4A: the Module, the Catalog, Credentials and Cost Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Kestrel the base of its providers: the plain Kotlin module `:core:providers` with the internal request format, the HTTP seam over the network gateway, the provider catalog generated from OpenClaw at the pinned commit and merged with hand-written overrides, profiles, credentials as data, cost estimation, encrypted credential storage in `:core:privacy`, and an allowlist that follows the stored profiles; and leave plans 4B and 4C their files, markers and the complete `catalog/overrides.json`.

**Architecture:** `:core:providers` (plain Kotlin) sends every request through OkHttp's `Call.Factory`, which `:app` takes from `NetworkGateway`, so it never builds an HTTP client and needs no exception in the audit; `ProviderHttp` adds cancellation, the order of failure classification and the rule of one refresh and one retry. `:tools:catalog-sync` reads OpenClaw's manifests from a checkout that `scripts\sync-catalog.ps1` makes and writes `catalog/providers.json`; `CatalogLoader` merges it at runtime with `catalog/overrides.json`, which completes the providers that OpenClaw defines in code, adds Kestrel's own sign-in choices and holds the prices that the manifests lack. `:core:privacy` gets a credentials key (in StrongBox when the device has it), a Room table `credentials` (database version 2) and a lease for local servers; `AppGraph` keeps the allowlist in step with the stored profiles and publishes what it applied.

**Tech Stack:** Kotlin 2.4.21, kotlinx.serialization 1.13.0 (with JSON5 settings for the manifests), kotlinx.coroutines 1.11.0, OkHttp 5.5.1 with `mockwebserver3` for the JVM tests, JUnit 4, Room 2.8.5 with `room-testing` for the migration's device test, Android Keystore, Pester 6.2.0 for the scripts, and Git for the sparse checkout of OpenClaw at commit `e6113549fe8800b4c736c5e8a131b47f2e015143`.

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

- Sub-project 3 (plans 3A, 3B and 3C) and plan 2E, which runs after plan 3C, are complete; their last commits are in `.superpowers/sdd/progress.md`. This plan was written against the code as sub-project 3 leaves it, and it runs on the code as plan 2E leaves it (after sub-project 3). The "Changes from the plan" entries of sub-project 3 and of plan 2E that touch this plan's files must be kept; this plan replaces only the texts that it quotes.
- The counts at the start of this plan are those at the end of plan 2E. The controller gives their values in each task's brief, because later fix commits change them: `:app` JVM A₀, `:core:privacy` JVM P₀, `:core:commands` JVM C₀, `:core:voice` JVM V₀, `:core:screen` JVM S₀, `:core:safety` JVM F₀, the command device tests K₀, the other device tests of `:app` O₀, the voice device tests W₀, the device tests of `:core:privacy` Q₀, and Pester T₀ in each shell. `:core:providers` and `:tools:catalog-sync` do not exist yet. Each task gives its counts as these values plus what it adds; before each task, compare the counts with the ones that the earlier tasks left. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file. If a text is missing, stop and ask the controller.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task. Only one Gradle build may run on this computer at a time (process rule 8b); if another agent's build runs, wait for it to end.
- Only Task 3 (the credential store's device tests) and Task 9 (`AppGraphTest` and one start of the app) use the emulator. Before their device steps, wait for the emulator lock and take it (process rule 8a): run `$deadline = (Get-Date).AddMinutes(60); while (Test-Path .superpowers\sdd\emulator.lock) { if ((Get-Date) -gt $deadline) { throw 'BLOCKED: the emulator lock still exists after 60 minutes' }; Start-Sleep -Seconds 120 }`, and report BLOCKED to the controller if it throws; then `Set-Content .superpowers\sdd\emulator.lock 'plan 4A task <n>'`; delete it with `Remove-Item .superpowers\sdd\emulator.lock` as soon as the device steps are done, also when they fail. Every device command starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. Never send a command to a connected phone.
- No step of this plan sends a request to any provider's API. Task 5's script fetches OpenClaw's Git repository from GitHub, which is not a provider.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 4B (the request styles): the adapters of the eight request styles in `dev.kestrel.providers.styles` (`ChatCompletionsAdapter` with `CompatDefaults`, `ResponsesAdapter` with the ChatGPT plan's form, `AnthropicAdapter`, `GeminiAdapter`, `OllamaAdapter`, `BedrockAdapter` with `SigV4Auth` and `MantleRouting`, `VertexAdapter` with `VertexUrls` and `ServiceAccountAuth`, `AzureAdapter` with `AzureRouting`), `JsonActions`, `Adapters.forStyle`, `Discoveries` with the local servers' discovery and `BedrockModels`, their fixtures, the README at the marker `<!-- Plan 4B: request styles -->`, and spec 10.3. Spec 10.3's style 3 names Kimi Coding, which Task 6 excludes, so plan 4B's text of 10.3 leaves it out. Three synced base URLs end with `/` (`kimi` before its exclusion, `kilocode` `https://api.kilo.ai/api/gateway/` and `requesty` `https://router.requesty.ai/v1/`), and `ProfileHosts.baseUrl` keeps the slash, so plan 4B appends its paths as path segments (`HttpUrl.Builder.addPathSegments`), not by string concatenation, which would give `//`, and not with `resolve`, which would drop the last segment of a base without a slash such as MiniMax's `…/anthropic`.

Plan 4C (sign-in, Copilot, the settings app and the session): `CredentialManager` and its refreshers, `SignInRegistry`, the device-code and browser flows (`GitHubDeviceFlow`, `XaiDeviceFlow`, `MiniMaxDeviceFlow`, `ChatGptPlanFlow`, `OpenRouterBrowserFlow`, `Pkce`, `IdTokens`), `LoopbackServer` in `:core:privacy`, the Copilot package (discovery, models, headers, allowance), `ChatGptPlanModels` and `XaiModels`, `TargetResolver`, `ProfileClient`, `ProvidersRepository`, `LocalNetwork`, `ProviderGraph` at the marker `    // Plan 4C: provider graph` in `AppGraph`, `SignInService`, `CustomTabs`, the Providers page and the setup checklist's step, the debug hooks `provider-live` and `provider-standin`, `scripts\provider-check.ps1`, `scripts\a71-providers.ps1`, the README at the marker `<!-- Plan 4C: sign-in, Copilot and the session -->`, and the rest of the spec's changes (D23's 4C part). Plan 4C owns `app/src/main/res/values/strings.xml`, so it also adds OpenClaw's catalog to the About page's summary of the MIT text (`about_licence_mit_summary`, which names only ONNX Runtime, piper-phonemize, JSON for Modern C++ and some speech models).

Sub-project 5: the agent's use of providers (the loop, escalation from the lightweight to the versatile model, the cap of 10 credits per task, re-prompting after an invalid tool call, `RequestMeta.agentStep`, the activity log's record of each call, redaction before a request, and speaking the errors of `ProviderTexts`).

## Changes to the design that this plan makes

- Tasks 7 and 8 come in the opposite order of the design's list ("profiles, credentials and discovery" first, then "cost"), because `PriceTable.priceFor` reads `Profile`.
- `ModelOverride` gains `pricesCopiedOn` and `pricesSource`, so that the Gemini prices carry the day and the page that they were copied from (D14), and `ProviderOverride` gains `defaultModel` and `utilityModel`, so that the providers that the overrides complete (MiniMax, Vertex) have default models (D13). These are additions to "Shared names", as are `AuthChoice.offered`, `ProvidersSetting.usable`, `ProvidersSetting.defaultProfile`, `Profile.model(role)`, `ChatMessage.Assistant.toolCalls`, `Usage.totalInput`, `HttpReply.text()`, `KeyHeader.header`, `CatalogFile`, `Skipped`, `CatalogLoader.copilotPrice`, `normalise` and `hostOf`, `Spending.monthOf`, `CatalogSync.sync` with `SyncResult`, `CatalogLoader.COPILOT`, `ProviderHttp`'s optional `clock` (for a `Retry-After` given as a date), and the test helper `RedirectingCalls`'s optional `readTimeoutMillis`; nothing that "Shared names" names changes.
- The manifests at the pinned commit are all plain JSON, so `CatalogSync` needs no pre-pass; its JSON5 settings stay, and a fixture with a comment and trailing commas checks them (D2).
- The sync also leaves out the decision models that OpenClaw marks with `inference.chat: false` (in `google`, `openrouter`, `baseten` and `clawrouter`), with the reason "not a chat model", as spec 10.1 excludes decision models.
- Where a manifest splits a provider's regions into sign-in choices (MiniMax, Moonshot, Qwen, StepFun, Xiaomi's Token Plans, Z.AI), each region's base URL is its choice's `baseUrl` in `overrides.json`; the providers whose region the owner chooses (Bedrock, Bedrock Mantle, Vertex) have `regions`. OpenClaw has no region list for Bedrock, so Bedrock gets the twelve regions of Mantle's list (`MANTLE_SUPPORTED_REGIONS`), in each of which Bedrock runs too.
- The pasted Bedrock and Mantle API keys (`bedrock-api-key`, `mantle-api-key`) are `AuthMethod.API_KEY` choices with the key header `BEARER`; `bedrock-iam-keys` and `vertex-service-account` are `IMPORTED`.
- Besides the three plan sign-ins of D14, the API-key choices of the providers' subscription plans (Qwen's Coding Plan and Token Plan, StepFun's Step Plan, Xiaomi's Token Plans, Z.AI's Coding Plans, BytePlus' and Volcano Engine's Coding Plans) have `planUsage: true`, because their plans pay for the requests; OpenClaw prices only a few of their models. Tencent's TokenPlan key (`tokenplan-api-key`) counts credits, as decision 7 of the design's decisions says: OpenClaw prices its models `hy4` and `hy4-preview` at TokenHub's pay-as-you-go prices, and nothing shows that TokenPlan is a flat-rate subscription.
- Vertex's Claude rows carry OpenClaw's prices (`extensions/anthropic-vertex/provider-catalog.ts`); for Claude Opus 5 and Claude Sonnet 5, whose price depends on the location, they carry the higher multi-region price, so the cap errs on the safe side.
- Kimi Code (the provider `kimi`) is excluded, as decision 6 of the design's decisions says: its only choice would send its manifest's `User-Agent: claude-code/0.1.0`, which Kimi forbids (research r47). So D21's table gains no row, and no Kimi choice is a borrowed identity; Moonshot's API keys stay and send Kestrel's own `User-Agent`. Kestrel never sends Claude Code's `User-Agent` or any other client identity that a provider forbids. Decision 1's borrowed sign-ins keep their headers: Copilot's `User-Agent: GitHubCopilotChat/0.35.0` and its other headers by D4, D12 and D21, and xAI's and MiniMax's only when the owner turns them on.
- Cloudflare AI Gateway is excluded, because its base URL holds the owner's Cloudflare account ID and gateway ID, which the network log would record (spec 13.5), and no plan sets a log path for it. Synthetic gets OpenClaw's seven rows, its default model and `STATIC` discovery, because OpenClaw lists its models at an address that plan 4B's Anthropic discovery would not call.
- The network log drops the query of a request's `LogPathTag` (D16), so that a tag that an adapter builds with its query cannot put the query into the log.
- `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt` joins plan 4A's files (Task 9): its local-server request uses Task 3's lease instead of `NetworkPolicy.setLocalServers`, because from Task 9 on the allowlist job replaces the local servers at start and after each change of the profiles. Plans 4B and 4C do not change the file.
- `core/privacy/src/test/kotlin/dev/kestrel/privacy/credentials/CredentialStoreFormatTest.kt` joins plan 4A's files (Task 3): a JVM test of the store's rule for a row in an unknown format, named apart from the device test `CredentialStoreTest`.
- A provider's hosts in the catalog no longer hold a host with a placeholder that only its base URL names (Vertex's `{location}-aiplatform.googleapis.com`), and they never hold its regions' hosts: `ProfileHosts` derives the host of the effective base URL itself, so a profile allows only its own region (D16).

## File structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts`, `core/providers/build.gradle.kts`, `app/build.gradle.kts` | Task 1: the module `:core:providers` and `:app`'s dependency on it; Task 5: `:tools:catalog-sync`; Task 9: the catalog in the app's assets |
| `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatRequest.kt`, `ChatMessage.kt`, `ToolSpec.kt`, `ChatRequests.kt`, `ChatResponse.kt`, `Usage.kt`, `http/HttpRequest.kt` | Task 1: the internal request format (D3), `RequestMeta`, `HttpRequest`, `HttpReply` and `SseEvent` |
| `core/providers/src/main/kotlin/dev/kestrel/providers/http/ProviderHttp.kt`, `RequestAuth.kt`, `SseReader.kt`, `ProviderError.kt`, `ErrorCodes.kt`, `Hosts.kt`; the test helper `testing/RedirectingCalls.kt` | Task 2: the HTTP seam (D1, D9, D17) |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt`, `credentials/CredentialStore.kt`, `db/CredentialRow.kt`, `db/KestrelDatabase.kt`, `PrivacyStore.kt`, `network/NetworkPolicy.kt`, `network/NetworkGateway.kt`, `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/2.json`, `core/privacy/build.gradle.kts`, `gradle/libs.versions.toml` | Task 3: credentials in `:core:privacy`, the lease for local servers, the log's path tag and failure detail (D11, D15, D16) |
| `core/providers/src/main/kotlin/dev/kestrel/providers/catalog/Catalog.kt`, `Overrides.kt`, `CatalogLoader.kt`, `cost/Price.kt`, `chat/ModelTarget.kt`, `chat/ChatAdapter.kt` | Task 4: the catalog model, its merge, and the types that join the format to the catalog (D2, D4, D7) |
| `tools/catalog-sync/build.gradle.kts`, `tools/catalog-sync/src/main/kotlin/dev/kestrel/catalogsync/CatalogSync.kt`, `Main.kt`, the fixtures and the snapshot; `scripts/sync-catalog.ps1`, `scripts/tests/SyncCatalog.Tests.ps1`, `fake-git.cmd`, `fake-git.ps1`; `catalog/providers.json`, `catalog/openclaw.lock`; `THIRD_PARTY_NOTICES.md`, `licences/MIT.txt` | Task 5: the generator, the script, the generated catalog and OpenClaw's notice (D2) |
| `catalog/overrides.json` | Task 6: the overrides for all three plans (D2, D4, D7 to D12, D14, D18, D21) |
| `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt`, `ProvidersSetting.kt`, `ProfileHosts.kt`, `credentials/Credential.kt`, `CredentialCodec.kt`, `CredentialVault.kt`, `ApiKeys.kt`, `discovery/ModelDiscovery.kt`, `ModelList.kt` | Task 7: profiles, the effective base URL and the hosts, credentials as data, discovered models (D13, D15, D16, D22) |
| `core/providers/src/main/kotlin/dev/kestrel/providers/cost/PriceTable.kt`, `CostMeter.kt`, `Spending.kt` | Task 8: cost (D12, D14) |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `providers/StoredCredentials.kt`, `providers/ProviderAllowlist.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`, `README.md` | Task 9: the setting keys, the seam's wiring, the catalog, the vault, the allowlist job and `appliedProviders`, the marker for plan 4C, and the README's section with its two markers (D2, D4, D13, D16) |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md` | D23's 4A part, each in the task that changes the behaviour: 7.4's `:core:providers` row (Task 1); 10.7, 13.4 and 13.5 (Task 3); 7.4's `:tools:catalog-sync` row, 10.2, 16.2 and 17 (Task 5); 10.1 and Appendix B (Task 6); 10.6 (Task 8); 13.1 (Task 9) |

Tests: `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatRequestsTest.kt`, `ChatMessageTest.kt`, `ChatResponseTest.kt`, `ToolSpecTest.kt` (Task 1); `http/ProviderHttpTest.kt`, `SseReaderTest.kt`, `ErrorCodesTest.kt`, `HostsTest.kt`, `RequestAuthTest.kt` (Task 2); `core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkGatewayTest.kt` and `NetworkPolicyTest.kt` (changed), `credentials/CredentialStoreFormatTest.kt` and the device test `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/CredentialStoreTest.kt` (Task 3); `catalog/CatalogLoaderTest.kt` and `chat/ModelTargetTest.kt` (Task 4); `tools/catalog-sync/src/test/kotlin/dev/kestrel/catalogsync/CatalogSyncTest.kt`, `CatalogFilesTest.kt` and `scripts/tests/SyncCatalog.Tests.ps1` (Task 5); `OverridesTest.kt` (Task 6); `profiles/ProfileHostsTest.kt`, `ProvidersSettingTest.kt`, `credentials/CredentialCodecTest.kt` and `discovery/ModelListTest.kt` (Task 7); `cost/PriceTableTest.kt`, `CostMeterTest.kt` and `SpendingTest.kt` (Task 8); `app/src/test/kotlin/dev/kestrel/app/providers/ProviderAllowlistTest.kt` and `HostsAgreementTest.kt`, and the changed device test `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt` (Task 9).

---

### Task 1: `:core:providers` and the internal request format

**Files:**
- Modify: `settings.gradle.kts` (`include(":core:providers")`), `app/build.gradle.kts` (the dependency)
- Create: `core/providers/build.gradle.kts`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatRequest.kt`, `ChatMessage.kt`, `ToolSpec.kt`, `ChatRequests.kt`, `ChatResponse.kt`, `Usage.kt`, and `core/providers/src/main/kotlin/dev/kestrel/providers/http/HttpRequest.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 7.4, the row of `:core:providers`)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatRequestsTest.kt`, `ChatMessageTest.kt`, `ChatResponseTest.kt`, `ToolSpecTest.kt`

**Interfaces:**
- Consumes: the convention plugin `kestrel.jvm.library` and the serialization plugin, as `:core:safety` uses them; the version catalog's `okhttp`, `kotlinx-serialization-json`, `kotlinx-coroutines-core`, `junit`, `kotlinx-coroutines-test` and `okhttp-mockwebserver3`.
- Produces (package `dev.kestrel.providers.chat`): `data class ChatRequest(val system: String, val messages: List<ChatMessage>, val tools: List<ToolSpec> = emptyList(), val toolChoice: ToolChoice = ToolChoice.AUTO, val reasoning: ReasoningLevel? = null, val maxOutputTokens: Int, val temperature: Double? = null, val cacheKey: String? = null, val jsonSchema: JsonObject? = null, val meta: RequestMeta = RequestMeta())`; `enum class ToolChoice { AUTO, REQUIRED, NONE }`; `enum class ReasoningLevel { MINIMAL, LOW, MEDIUM, HIGH }`; `@Serializable enum class RequestStyle { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC, GEMINI, OLLAMA, BEDROCK, VERTEX, AZURE }`; `sealed interface ChatMessage { data class User(val parts: List<Part>); data class Assistant(val parts: List<AssistantPart>) { val toolCalls: List<AssistantPart.ToolCall> }; data class ToolResult(val callId: String, val name: String, val text: String, val failed: Boolean = false) }`; `sealed interface Part { data class Text(val text: String); class Image(val type: ImageType, val bytes: ByteArray) }` (an `Image` equals another with the same type and bytes); `enum class ImageType(val mime: String) { PNG("image/png"), JPEG("image/jpeg") }`; `sealed interface AssistantPart { data class Text(val text: String); data class ToolCall(val id: String, val name: String, val arguments: JsonObject); data class Reasoning(val state: ReasoningState) }`; `data class ReasoningState(val style: RequestStyle, val providerId: String, val modelId: String, val payload: JsonElement)`; `data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)` (its `init` checks the name against `^[a-zA-Z0-9_-]{1,64}$` and calls `ToolSchemas.check`); `object ToolSchemas { fun check(schema: JsonObject) }`; `object ChatRequests { fun check(request: ChatRequest) }`; `data class ChatResponse(val message: ChatMessage.Assistant, val stop: StopReason, val usage: Usage, val modelId: String, val invalidToolCall: InvalidToolCall? = null)`; `enum class StopReason { END, TOOL_CALL, MAX_TOKENS, FILTERED, OTHER }`; `data class InvalidToolCall(val kind: InvalidKind, val text: String)`; `enum class InvalidKind { ARGUMENTS_NOT_OBJECT, UNKNOWN_TOOL, MISSING_ARGUMENT, NO_ACTION, BAD_JSON }`; `data class Usage(val input: Long, val output: Long, val cacheRead: Long = 0, val cacheWrite: Long = 0, val reasoning: Long = 0, val estimated: Boolean = false)` with `val totalInput: Long`. Package `dev.kestrel.providers.http`: `data class RequestMeta(val taskId: String? = null, val logPath: String? = null, val agentStep: Boolean = false)`; `fun interface RequestDecorator { fun decorate(builder: Request.Builder, meta: RequestMeta) }`; `class HttpRequest(val method: String, val url: HttpUrl, val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null, val contentType: String? = null, val meta: RequestMeta = RequestMeta())`; `class HttpReply(val status: Int, val headers: Headers, val body: ByteArray)` with `fun text(): String`; `data class SseEvent(val event: String?, val data: String)`. Every type of this list that can carry text of the owner, a model or a provider prints only kinds, counts and lengths (D22).

Design D1, D3 and D22. `:core:providers` is a plain Kotlin module like `:core:safety`, so its tests run on the PC. Its build file is complete for all three plans: plans 4B and 4C add no dependency. Its test task passes the repository's `catalog` folder as the system property `kestrel.catalog`, which the tests of Tasks 5 to 7 read. The request format keeps every text part separate, so that sub-project 5 can redact each one before it builds a request (spec 12.6). `ChatRequests.check` refuses a request in which a tool call of an assistant message has no result with its ID before the next user or assistant message, because every style refuses such a request (D3). `ToolSchemas.check` refuses every schema outside the subset that every style accepts, so that no adapter ever rewrites a schema (D3).

- [ ] **Step 1: Add the module**

In `settings.gradle.kts`, replace

```kotlin
include(":core:privacy")
```

with

```kotlin
include(":core:privacy")
include(":core:providers")
```

Create `core/providers/build.gradle.kts`:

```kotlin
plugins {
    id("kestrel.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
}

tasks.test {
    // CatalogFilesTest, OverridesTest and ProfileHostsTest read the repository's catalog files (design spec, section 17).
    val catalog = rootProject.layout.projectDirectory.dir("catalog")
    inputs.files(fileTree(catalog)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("catalog")
    systemProperty("kestrel.catalog", catalog.asFile.absolutePath)
}
```

In `app/build.gradle.kts`, replace

```kotlin
    implementation(project(":core:privacy"))
```

with

```kotlin
    implementation(project(":core:privacy"))
    implementation(project(":core:providers"))
```

- [ ] **Step 2: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatRequestsTest.kt`:

```kotlin
package dev.kestrel.providers.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatRequestsTest {
    private val call = AssistantPart.ToolCall("call-1", "tap", buildJsonObject { put("label", "Send") })
    private val other = AssistantPart.ToolCall("call-2", "scroll", buildJsonObject { put("direction", "down") })

    private fun request(vararg messages: ChatMessage) =
        ChatRequest(system = "You drive the phone.", messages = messages.toList(), maxOutputTokens = 100)

    @Test
    fun aToolCallWithItsResultPasses() {
        ChatRequests.check(
            request(
                ChatMessage.User(listOf(Part.Text("Open the mail."))),
                ChatMessage.Assistant(listOf(call, other)),
                ChatMessage.ToolResult("call-2", "scroll", "Scrolled."),
                ChatMessage.ToolResult("call-1", "tap", "Tapped."),
                ChatMessage.User(listOf(Part.Text("The screen now shows the inbox."))),
            ),
        )
    }

    @Test
    fun aToolCallWithoutItsResultBeforeTheNextMessageFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ChatRequests.check(
                request(
                    ChatMessage.Assistant(listOf(call, other)),
                    ChatMessage.ToolResult("call-1", "tap", "Tapped."),
                    ChatMessage.User(listOf(Part.Text("Next."))),
                    ChatMessage.ToolResult("call-2", "scroll", "Too late."),
                ),
            )
        }

        assertEquals("Message 0 has 1 tool calls without a result", error.message)
    }

    @Test
    fun aToolCallAtTheEndWithoutAResultFails() {
        assertThrows(IllegalArgumentException::class.java) { ChatRequests.check(request(ChatMessage.Assistant(listOf(call)))) }
    }

    @Test
    fun twoCallsWithOneIdAndANonPositiveLimitFail() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatRequests.check(
                request(
                    ChatMessage.Assistant(listOf(call, call.copy(name = "scroll"))),
                    ChatMessage.ToolResult("call-1", "tap", "Tapped."),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatRequests.check(request(ChatMessage.User(listOf(Part.Text("Hi.")))).copy(maxOutputTokens = 0))
        }
    }

    @Test
    fun aRequestPrintsOnlyCountsAndLengths() {
        val request = request(ChatMessage.User(listOf(Part.Text("Hi.")))).copy(
            system = "secret system prompt",
            cacheKey = "secret key",
            temperature = 0.2,
            reasoning = ReasoningLevel.LOW,
        )

        val printed = request.toString()

        assertFalse(printed, printed.contains("secret"))
        assertEquals(
            "ChatRequest(systemLength=20, messages=1, tools=0, toolChoice=AUTO, reasoning=LOW, maxOutputTokens=100, " +
                "temperature=set, cacheKey=set, jsonSchema=none)",
            printed,
        )
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatMessageTest.kt`:

```kotlin
package dev.kestrel.providers.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatMessageTest {
    @Test
    fun messagesPrintNoTextNoArgumentsAndNoReasoning() {
        val secret = "the owner's PIN is 4711"
        val messages = listOf(
            ChatMessage.User(listOf(Part.Text(secret), Part.Image(ImageType.PNG, byteArrayOf(1, 2, 3)))),
            ChatMessage.Assistant(
                listOf(
                    AssistantPart.Text(secret),
                    AssistantPart.ToolCall("id-$secret", "type_text", buildJsonObject { put("text", secret) }),
                    AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-$secret", JsonPrimitive(secret))),
                ),
            ),
            ChatMessage.ToolResult("id-$secret", "type_text", secret),
        )

        val printed = messages.toString()

        assertFalse(printed, printed.contains("4711"))
        assertEquals(
            "[User(parts=[Text(length=23), Image(type=PNG, bytes=3)]), " +
                "Assistant(parts=[Text(length=23), ToolCall(nameLength=9, idLength=26, arguments=1), " +
                "Reasoning(ReasoningState(style=ANTHROPIC, provider=anthropic, modelIdLength=30, payloadLength=25))]), " +
                "ToolResult(nameLength=9, callIdLength=26, textLength=23, failed=false)]",
            printed,
        )
    }

    @Test
    fun imagesCompareByTheirBytes() {
        assertEquals(Part.Image(ImageType.JPEG, byteArrayOf(9, 8)), Part.Image(ImageType.JPEG, byteArrayOf(9, 8)))
        assertNotEquals(Part.Image(ImageType.JPEG, byteArrayOf(9, 8)), Part.Image(ImageType.PNG, byteArrayOf(9, 8)))
        assertEquals("image/jpeg", ImageType.JPEG.mime)
    }

    @Test
    fun anAssistantMessageListsItsToolCallsInOrder() {
        val first = AssistantPart.ToolCall("a", "tap", buildJsonObject { })
        val second = AssistantPart.ToolCall("b", "scroll", buildJsonObject { })

        assertEquals(listOf(first, second), ChatMessage.Assistant(listOf(AssistantPart.Text("x"), first, second)).toolCalls)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatResponseTest.kt`:

```kotlin
package dev.kestrel.providers.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatResponseTest {
    @Test
    fun aResponsePrintsNoReplyNoModelIdAndNoInvalidText() {
        val response = ChatResponse(
            message = ChatMessage.Assistant(listOf(AssistantPart.Text("secret reply"))),
            stop = StopReason.END,
            usage = Usage(input = 10, output = 2),
            modelId = "arn:aws:bedrock:eu-west-1:123456789012:inference-profile/x",
            invalidToolCall = InvalidToolCall(InvalidKind.BAD_JSON, "secret reply"),
        )

        val printed = response.toString()

        assertFalse(printed, printed.contains("secret") || printed.contains("123456789012"))
        assertEquals(
            "ChatResponse(stop=END, message=Assistant(parts=[Text(length=12)]), " +
                "usage=Usage(input=10, output=2, cacheRead=0, cacheWrite=0, reasoning=0, estimated=false), " +
                "modelIdLength=58, invalidToolCall=InvalidToolCall(kind=BAD_JSON, textLength=12))",
            printed,
        )
    }

    @Test
    fun theWholeInputCountsTheCacheToo() {
        assertEquals(12, Usage(input = 5, output = 1, cacheRead = 4, cacheWrite = 3).totalInput)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ToolSpecTest.kt`:

```kotlin
package dev.kestrel.providers.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolSpecTest {
    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theSubsetIsAccepted() {
        val tool = ToolSpec(
            name = "tap",
            description = "Taps an element.",
            parameters = schema(
                """
                {"type": "object", "description": "The element.",
                 "properties": {
                   "label": {"type": "string", "description": "Its label."},
                   "kind": {"type": "string", "enum": ["button", "link"]},
                   "index": {"type": "integer"}, "weight": {"type": "number"}, "long": {"type": "boolean"},
                   "path": {"type": "array", "items": {"type": "object", "properties": {"x": {"type": "number"}}, "required": ["x"]}}
                 },
                 "required": ["label"]}
                """,
            ),
        )

        assertEquals("tap", tool.name)
    }

    @Test
    fun aTopLevelObjectMayHaveNoProperties() {
        ToolSpec("go_home", "Goes to the home screen.", schema("""{"type": "object"}"""))
    }

    @Test
    fun everythingOutsideTheSubsetIsRefused() {
        val refused = listOf(
            """{"type": "string"}""",
            """{"type": "object", "additionalProperties": false}""",
            """{"type": "object", "properties": {"n": {"type": "integer", "enum": [1, 2]}}}""",
            """{"type": "object", "properties": {"inner": {"type": "object"}}}""",
            """{"type": "object", "properties": {"list": {"type": "array"}}}""",
            """{"type": "object", "properties": {"x": {"type": ["string", "null"]}}}""",
            """{"type": "object", "properties": {"x": {"type": "string"}}, "required": ["y"]}""",
            """{"type": "object", "properties": {"x": {"type": "string", "format": "date"}}}""",
            """{"type": "object", "properties": {"x": {"type": "string", "items": {"type": "string"}}}}""",
        )

        for (text in refused) {
            assertThrows(text, IllegalArgumentException::class.java) { ToolSpec("t", "d", schema(text)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ToolSpec("two words", "d", schema("""{"type": "object"}""")) }
    }

    @Test
    fun aToolPrintsItsNameAndTheSizesOnly() {
        val tool = ToolSpec("tap", "secret description", schema("""{"type": "object", "properties": {"secret": {"type": "string"}}}"""))

        val printed = tool.toString()

        assertFalse(printed, printed.contains("secret"))
        assertEquals("ToolSpec(name=tap, descriptionLength=18, schemaLength=59)", printed)
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ChatRequest'", "Unresolved reference 'ToolSpec'" and the other types of this task.

- [ ] **Step 4: Write the format**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/http/HttpRequest.kt`:

```kotlin
package dev.kestrel.providers.http

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * What the network log and Copilot's headers need to know about a request (design D3, D12 and D16): the task that caused
 * it, the path that the log records instead of the real one, and whether it is an agent's step after the first request.
 */
data class RequestMeta(val taskId: String? = null, val logPath: String? = null, val agentStep: Boolean = false)

/**
 * Adds what the privacy module needs to each OkHttp request. `:app`'s decorator adds the task's and the log path's tags
 * and Kestrel's User-Agent when no other is set (design D1, D4 and D16).
 */
fun interface RequestDecorator {
    fun decorate(builder: Request.Builder, meta: RequestMeta)
}

/** A request that ProviderHttp sends. Its headers and body can hold keys and prompts, so toString prints only sizes. */
class HttpRequest(
    val method: String,
    val url: HttpUrl,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val meta: RequestMeta = RequestMeta(),
) {
    override fun toString(): String = "HttpRequest(method=$method, headers=${headers.size}, bodyBytes=${body?.size ?: 0})"
}

/** A reply with its whole body in memory. */
class HttpReply(val status: Int, val headers: Headers, val body: ByteArray) {
    fun text(): String = body.decodeToString()

    override fun toString(): String = "HttpReply(status=$status, headers=${headers.size}, bodyBytes=${body.size})"
}

/** One server-sent event. Event names are the protocol's own; the data can hold a reply, so only its length is printed. */
data class SseEvent(val event: String?, val data: String) {
    override fun toString(): String = "SseEvent(event=$event, dataLength=${data.length})"
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatRequest.kt`:

```kotlin
package dev.kestrel.providers.chat

import dev.kestrel.providers.http.RequestMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One request to a model in Kestrel's own format (design D3). The adapters turn it into each provider's request style.
 * Sub-project 5 redacts the text before it builds a request (spec 12.6); the adapters send what they get.
 */
data class ChatRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.AUTO,
    val reasoning: ReasoningLevel? = null,
    val maxOutputTokens: Int,
    /** Null leaves the provider's default. */
    val temperature: Double? = null,
    /** Names the conversation's fixed part for the providers' prompt caches (spec 11.2). */
    val cacheKey: String? = null,
    /** The schema of a JSON reply, which JsonActions sets (design D7). */
    val jsonSchema: JsonObject? = null,
    val meta: RequestMeta = RequestMeta(),
) {
    override fun toString(): String =
        "ChatRequest(systemLength=${system.length}, messages=${messages.size}, tools=${tools.size}, " +
            "toolChoice=$toolChoice, reasoning=$reasoning, maxOutputTokens=$maxOutputTokens, " +
            "temperature=${if (temperature == null) "default" else "set"}, cacheKey=${if (cacheKey == null) "none" else "set"}, " +
            "jsonSchema=${if (jsonSchema == null) "none" else "set"})"
}

enum class ToolChoice { AUTO, REQUIRED, NONE }

enum class ReasoningLevel { MINIMAL, LOW, MEDIUM, HIGH }

/**
 * The eight request styles of spec 10.3. The first six are wire formats; VERTEX and AZURE are hosted platforms whose
 * adapters reuse a wire format per model (design D4).
 */
@Serializable
enum class RequestStyle { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC, GEMINI, OLLAMA, BEDROCK, VERTEX, AZURE }
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatMessage.kt`:

```kotlin
package dev.kestrel.providers.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A message of a conversation (design D3). Their toString prints only kinds, counts and lengths (design D22). */
sealed interface ChatMessage {
    /** The owner's turn and the agent's observations; the only place for images, which every style accepts there. */
    data class User(val parts: List<Part>) : ChatMessage {
        override fun toString(): String = "User(parts=$parts)"
    }

    data class Assistant(val parts: List<AssistantPart>) : ChatMessage {
        val toolCalls: List<AssistantPart.ToolCall> get() = parts.filterIsInstance<AssistantPart.ToolCall>()

        override fun toString(): String = "Assistant(parts=$parts)"
    }

    /** The result of the tool call [callId]; ChatRequests.check requires one for every call (design D3). */
    data class ToolResult(val callId: String, val name: String, val text: String, val failed: Boolean = false) : ChatMessage {
        override fun toString(): String =
            "ToolResult(nameLength=${name.length}, callIdLength=${callId.length}, textLength=${text.length}, failed=$failed)"
    }
}

sealed interface Part {
    data class Text(val text: String) : Part {
        override fun toString(): String = "Text(length=${text.length})"
    }

    /** A PNG or JPEG picture, such as the agent's screenshot (spec 11.6). */
    class Image(val type: ImageType, val bytes: ByteArray) : Part {
        override fun equals(other: Any?): Boolean = other is Image && other.type == type && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * type.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "Image(type=$type, bytes=${bytes.size})"
    }
}

enum class ImageType(val mime: String) { PNG("image/png"), JPEG("image/jpeg") }

sealed interface AssistantPart {
    data class Text(val text: String) : AssistantPart {
        override fun toString(): String = "Text(length=${text.length})"
    }

    /** A model can name a tool that Kestrel does not have (design D3's UNKNOWN_TOOL), so the name is only measured. */
    data class ToolCall(val id: String, val name: String, val arguments: JsonObject) : AssistantPart {
        override fun toString(): String = "ToolCall(nameLength=${name.length}, idLength=${id.length}, arguments=${arguments.size})"
    }

    data class Reasoning(val state: ReasoningState) : AssistantPart {
        override fun toString(): String = "Reasoning($state)"
    }
}

/**
 * Reasoning that a provider wants back on the next request, kept opaque (design D3 and D5). Signed or encrypted state goes
 * back only to the same style, provider and model; plain reasoning text of Chat Completions goes back to the same provider.
 */
data class ReasoningState(val style: RequestStyle, val providerId: String, val modelId: String, val payload: JsonElement) {
    override fun toString(): String =
        "ReasoningState(style=$style, provider=$providerId, modelIdLength=${modelId.length}, payloadLength=${payload.toString().length})"
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ToolSpec.kt`:

```kotlin
package dev.kestrel.providers.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A tool that the model may call. Its parameters stay within the schema subset that every style accepts (design D3). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    init {
        require(NAME.matches(name)) { "A tool name must match ${NAME.pattern}" }
        ToolSchemas.check(parameters)
    }

    override fun toString(): String =
        "ToolSpec(name=$name, descriptionLength=${description.length}, schemaLength=${parameters.toString().length})"

    private companion object {
        val NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")
    }
}

/**
 * The JSON Schema subset of design D3: the types object, string, integer, number, boolean and array, with `properties`,
 * `required`, `items`, `enum` (on strings only, because Gemini accepts no other) and `description`. A nested object needs
 * at least one property; the top-level object may have none. The adapters never rewrite a schema.
 */
object ToolSchemas {
    private val TYPES = setOf("object", "string", "integer", "number", "boolean", "array")
    private val KEYS = setOf("type", "properties", "required", "items", "enum", "description")

    /** Throws IllegalArgumentException when [schema] leaves the subset. */
    fun check(schema: JsonObject) {
        require(schema.string("type") == "object") { "A tool's parameters must be an object" }
        checkNode(schema, path = "parameters", topLevel = true)
    }

    private fun checkNode(node: JsonObject, path: String, topLevel: Boolean) {
        val unknown = node.keys - KEYS
        require(unknown.isEmpty()) { "$path uses keywords outside the subset: ${unknown.sorted()}" }
        val type = node.string("type")
        require(type in TYPES) { "$path has no supported type" }
        node["description"]?.let { require(it.isString()) { "$path.description must be a string" } }
        if ("enum" in node) {
            require(type == "string") { "$path has an enum, which only strings may have" }
            val values = node["enum"] as? JsonArray
            require(values != null && values.isNotEmpty() && values.all { it.isString() }) { "$path.enum must list strings" }
        }
        require(type == "object" || ("properties" !in node && "required" !in node)) { "$path has properties but is no object" }
        require(type == "array" || "items" !in node) { "$path has items but is no array" }
        when (type) {
            "object" -> checkObject(node, path, topLevel)
            "array" -> {
                val items = node["items"] as? JsonObject
                require(items != null) { "$path is an array without items" }
                checkNode(items, "$path.items", topLevel = false)
            }
        }
    }

    private fun checkObject(node: JsonObject, path: String, topLevel: Boolean) {
        val properties = node["properties"]?.let { it as? JsonObject ?: throw IllegalArgumentException("$path.properties must be an object") }
            ?: JsonObject(emptyMap())
        require(topLevel || properties.isNotEmpty()) { "$path is a nested object without properties" }
        for ((name, value) in properties) {
            val child = value as? JsonObject ?: throw IllegalArgumentException("$path.$name must be a schema")
            checkNode(child, "$path.$name", topLevel = false)
        }
        node["required"]?.let { required ->
            require(required is JsonArray && required.all { it.isString() }) { "$path.required must list names" }
            val missing = required.map { (it as JsonPrimitive).content }.filter { it !in properties }
            require(missing.isEmpty()) { "$path.required names unknown properties: $missing" }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.isString(): Boolean = this is JsonPrimitive && isString
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatRequests.kt`:

```kotlin
package dev.kestrel.providers.chat

/** Checks a request before any adapter runs (design D3). */
object ChatRequests {
    /**
     * Throws IllegalArgumentException when a tool call of an assistant message has no result with its ID before the next
     * user or assistant message, when two calls of one message share an ID, or when maxOutputTokens is not positive. Every
     * style refuses a call without a result (OpenAI, Anthropic, Bedrock; Gemini wants all calls of a step before their
     * results).
     */
    fun check(request: ChatRequest) {
        require(request.maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        val messages = request.messages
        for ((index, message) in messages.withIndex()) {
            if (message !is ChatMessage.Assistant) continue
            val calls = message.toolCalls
            if (calls.isEmpty()) continue
            val ids = calls.map { it.id }
            require(ids.toSet().size == ids.size) { "Message $index has two tool calls with one ID" }
            val results = messages.drop(index + 1)
                .takeWhile { it is ChatMessage.ToolResult }
                .map { (it as ChatMessage.ToolResult).callId }
                .toSet()
            val missing = ids.count { it !in results }
            require(missing == 0) { "Message $index has $missing tool calls without a result" }
        }
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatResponse.kt`:

```kotlin
package dev.kestrel.providers.chat

/** One complete answer of a model (design D3), whichever transport the adapter used (spec 10.3). */
data class ChatResponse(
    val message: ChatMessage.Assistant,
    val stop: StopReason,
    val usage: Usage,
    /** The model that the provider reports, which may be a Bedrock ARN with an account ID, so only its length is printed. */
    val modelId: String,
    /** Set when the model asked for a tool in a form that Kestrel cannot use (design D3 and D7). */
    val invalidToolCall: InvalidToolCall? = null,
) {
    override fun toString(): String =
        "ChatResponse(stop=$stop, message=$message, usage=$usage, modelIdLength=${modelId.length}, invalidToolCall=$invalidToolCall)"
}

enum class StopReason { END, TOOL_CALL, MAX_TOKENS, FILTERED, OTHER }

/** A tool call that Kestrel cannot use. Its text is the model's reply, so only its length is printed. */
data class InvalidToolCall(val kind: InvalidKind, val text: String) {
    override fun toString(): String = "InvalidToolCall(kind=$kind, textLength=${text.length})"
}

enum class InvalidKind { ARGUMENTS_NOT_OBJECT, UNKNOWN_TOOL, MISSING_ARGUMENT, NO_ACTION, BAD_JSON }
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/Usage.kt`:

```kotlin
package dev.kestrel.providers.chat

/**
 * Token counts, normalised across the styles (design D6): [input] is the input that was neither read from nor written to
 * a cache, [output] includes reasoning, and [reasoning] is the part of [output] that was reasoning (shown, never priced on
 * its own). [estimated] is true when the provider reported nothing and Kestrel counted.
 */
data class Usage(
    val input: Long,
    val output: Long,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
    val estimated: Boolean = false,
) {
    /** The whole input, which decides a long-context price tier (design D14). */
    val totalInput: Long get() = input + cacheRead + cacheWrite
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with 14 tests in `:core:providers`: `ChatRequestsTest` 5, `ChatMessageTest` 3, `ChatResponseTest` 2 and `ToolSpecTest` 4.

- [ ] **Step 6: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| `:core:providers` | The generated catalog, the request adapters and the sign-in methods (uses OkHttp) | Yes |
```

with

```markdown
| `:core:providers` | The generated catalog, the internal request format, the request adapters, the sign-in methods, model discovery, profiles, credentials as data and cost estimation; it uses OkHttp only through the network gateway's `Call.Factory` | Yes |
```

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` 14; `:app` A₀, `:core:privacy` P₀, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit, which now also covers `:core:providers` (`core\providers\build\reports\kestrel-audit\network-apis.txt` lists no finding).

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add settings.gradle.kts app/build.gradle.kts core/providers/build.gradle.kts core/providers/src/main/kotlin/dev/kestrel/providers/http/HttpRequest.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatRequest.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatMessage.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/ToolSpec.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatRequests.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatResponse.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/Usage.kt core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatRequestsTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatMessageTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/chat/ChatResponseTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/chat/ToolSpecTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(providers): add the module :core:providers with the internal request format" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The HTTP seam

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/http/ProviderError.kt`, `RequestAuth.kt`, `ErrorCodes.kt`, `Hosts.kt`, `SseReader.kt`, `ProviderHttp.kt`
- Create (test helper): `core/providers/src/test/kotlin/dev/kestrel/providers/testing/RedirectingCalls.kt`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/http/ProviderHttpTest.kt`, `SseReaderTest.kt`, `ErrorCodesTest.kt`, `HostsTest.kt`, `RequestAuthTest.kt`

**Interfaces:**
- Consumes: Task 1's `HttpRequest`, `HttpReply`, `SseEvent`, `RequestMeta` and `RequestDecorator`.
- Produces (package `dev.kestrel.providers.http`): `sealed interface ProviderError` with `data object Unauthorized`, `data class SignInExpired(val providerId: String)`, `data class SignInSwitchedOff(val providerId: String)`, `data class RateLimited(val retryAfterMillis: Long?, val code: String? = null)`, `data class ServerError(val status: Int)`, `data object Timeout`, `data object Unreachable`, `data object NotAllowed`, `data object LocalNetworkNotAllowed`, `data class QuotaOrPolicy(val status: Int, val code: String? = null)`, `data class BadRequest(val status: Int, val code: String?)`, `data object BadCredential`, `data object Malformed`; `class ProviderException(val error: ProviderError) : IOException(error.toString())`; `interface RequestAuth { suspend fun headers(request: HttpRequest): Map<String, String>; suspend fun refresh(): RefreshResult = RefreshResult.NotPossible; val rejected: ProviderError get() = ProviderError.Unauthorized }`; `sealed interface RefreshResult { data object Refreshed; data class FailedForNow(val error: ProviderError); data object Expired; data object NotPossible }`; `@Serializable enum class KeyHeader(val header: String) { BEARER("Authorization"), X_API_KEY("x-api-key"), X_GOOG_API_KEY("x-goog-api-key"), API_KEY("api-key") }`; `class ApiKeyAuth(key: String, header: KeyHeader) : RequestAuth` (BEARER sends `Bearer <key>`, the others the key itself); `object NoAuth : RequestAuth`; `object ErrorCodes { fun from(reply: HttpReply): String? }`; `object Hosts { fun isLocal(host: String): Boolean; fun isLoopback(host: String): Boolean }`; `object SseReader { suspend fun read(source: BufferedSource, onEvent: suspend (SseEvent) -> Unit) }`; `class ProviderHttp(calls: Call.Factory, decorator: RequestDecorator = RequestDecorator { _, _ -> }, classify: (IOException) -> ProviderError? = { null }, clock: () -> Long = System::currentTimeMillis)` with `suspend fun send(request: HttpRequest, auth: RequestAuth): HttpReply`, `suspend fun exchange(request: HttpRequest, auth: RequestAuth = NoAuth): HttpReply` and `suspend fun stream(request: HttpRequest, auth: RequestAuth, onEvent: suspend (SseEvent) -> Unit)`. Test helper (package `dev.kestrel.providers.testing`, test sources of `:core:providers`): `class RedirectingCalls(server: MockWebServer, readTimeoutMillis: Long = 10_000) : Call.Factory` with `val requests: List<HttpUrl>`, the original URLs. Plans 4B and 4C build every adapter, sign-in flow and refresher on these names.

Design D1, D9, D17 and D22. `ProviderHttp` takes OkHttp's `Call.Factory`, which `:app` takes from the network gateway (Task 9), so `:core:providers` never builds an HTTP client and needs no exception in `audit/rules.txt`. It names no type and calls no method that the audit flags: no `javax.net` type, no `InetAddress`, no socket. Each call runs on `Dispatchers.IO`, and a child coroutine cancels the OkHttp call as soon as the caller's coroutine is cancelled, also while a blocking read of a stream waits, so that "stop" ends a request at once. Failures are classified in D1's order: a coroutine that is no longer active gets its `CancellationException` unchanged; a `ProviderException` passes through (an adapter throws one from `onEvent`); then `classify` (the gateway's `BlockedRequestException` becomes `NotAllowed` in Task 9); then `InterruptedIOException` (which `SocketTimeoutException` extends) is `Timeout`; any other `IOException` is `Unreachable`. A header value that OkHttp refuses throws `IllegalArgumentException` with the value in its message, so it becomes `BadCredential` without that message. `send` and `stream` refresh a 401 once through `RequestAuth.refresh()` and send once more (`Refreshed`); `FailedForNow` fails with its error, and `Expired`, `NotPossible` and a second 401 fail with `RequestAuth.rejected`. A 403 never refreshes. Every error status keeps the machine code that `ErrorCodes` reads: a 402 or 403 is `QuotaOrPolicy`, a 429 `RateLimited` with its `Retry-After` (seconds or an HTTP date), a 5xx `ServerError` and another 4xx `BadRequest`; a 1xx or 3xx is `Malformed`, because the gateway follows no redirects. `exchange` returns every status with its body, for the sign-in flows and refreshers, which read OAuth's error answers (RFC 6749 §5.2, RFC 8628 §3.5). `SseReader` follows the WHATWG rules for server-sent events and also delivers an event that a body ends without a blank line; each adapter of plan 4B decides whether its stream's final event came. `Hosts` copies `:core:privacy`'s `LocalHosts` rules as strings; Task 9's `HostsAgreementTest` keeps the two equal. The tests reach MockWebServer through `RedirectingCalls`, so the code under test keeps its fixed `https://` addresses and every host check stays as it is.

- [ ] **Step 1: Write the test helper and the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/testing/RedirectingCalls.kt`:

```kotlin
package dev.kestrel.providers.testing

import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * A Call.Factory for JVM tests that sends every request to [server] and records the original URL (design D1). So the
 * adapters and the sign-in flows keep their fixed `https://` addresses and host checks in the tests, and nobody loosens a
 * check to make a test pass. Test sources are not audited, so this test helper may build an OkHttpClient.
 */
class RedirectingCalls(private val server: MockWebServer, readTimeoutMillis: Long = 10_000) : Call.Factory {
    private val recorded = Collections.synchronizedList(mutableListOf<HttpUrl>())

    /** The URLs that the code under test asked for, in order. */
    val requests: List<HttpUrl> get() = synchronized(recorded) { recorded.toList() }

    private val client = OkHttpClient.Builder()
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .addInterceptor { chain ->
            val original = chain.request().url
            recorded += original
            val target = server.url(original.encodedPath).newBuilder().encodedQuery(original.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(target).build())
        }
        .build()

    override fun newCall(request: Request): Call = client.newCall(request)
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/http/ProviderHttpTest.kt`:

```kotlin
package dev.kestrel.providers.http

import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class ProviderHttpTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private val url = "https://api.example.com/v1/chat/completions?api-version=1".toHttpUrl()

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun post(body: String = """{"model":"m"}""") =
        HttpRequest("POST", url, mapOf("content-type" to "application/json"), body.encodeToByteArray(), "application/json")

    private fun reply(code: Int, body: String = "", vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build()

    /** A token that the test refreshes, with the result that [refreshResult] gives. */
    private class TestAuth(
        var token: String = "old-token",
        private val refreshResult: RefreshResult = RefreshResult.Refreshed,
        override val rejected: ProviderError = ProviderError.SignInExpired("test-provider"),
    ) : RequestAuth {
        var refreshes = 0

        override suspend fun headers(request: HttpRequest) = mapOf("Authorization" to "Bearer $token")

        override suspend fun refresh(): RefreshResult {
            refreshes++
            if (refreshResult == RefreshResult.Refreshed) token = "new-token"
            return refreshResult
        }
    }

    private fun errorOf(block: suspend () -> Unit): ProviderError =
        try {
            runBlocking { block() }
            fail("expected a ProviderException")
            throw AssertionError()
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun sendReturnsTheReplyAndKeepsTheFixedAddress() = runBlocking {
        server.enqueue(reply(200, "ok"))

        val reply = ProviderHttp(calls).send(post(), ApiKeyAuth("test-key", KeyHeader.BEARER))

        assertEquals(200, reply.status)
        assertEquals("ok", reply.text())
        assertEquals(listOf(url), calls.requests)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("Bearer test-key", recorded.headers["Authorization"])
        assertEquals("""{"model":"m"}""", recorded.body?.utf8())
        assertEquals("/v1/chat/completions?api-version=1", recorded.target)
    }

    @Test
    fun theDecoratorSeesEveryRequestWithItsMeta() = runBlocking {
        server.enqueue(reply(200))
        val http = ProviderHttp(calls, decorator = { builder, meta -> builder.header("X-Task", meta.taskId ?: "none") })
        val request = HttpRequest("GET", url, meta = RequestMeta(taskId = "task-7"))

        http.send(request, NoAuth)

        assertEquals("task-7", server.takeRequest().headers["X-Task"])
    }

    @Test
    fun a401IsRefreshedOnceAndSentOnceMore() = runBlocking {
        server.enqueue(reply(401))
        server.enqueue(reply(200, "second"))
        val auth = TestAuth()

        val reply = ProviderHttp(calls).send(post(), auth)

        assertEquals("second", reply.text())
        assertEquals(1, auth.refreshes)
        assertEquals("Bearer old-token", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer new-token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aSecond401GivesTheRejectedError() {
        server.enqueue(reply(401))
        server.enqueue(reply(401))
        val auth = TestAuth()

        assertEquals(ProviderError.SignInExpired("test-provider"), errorOf { ProviderHttp(calls).send(post(), auth) })
        assertEquals(1, auth.refreshes)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRefreshThatFailsForNowGivesItsErrorWithItsRetryAfter() {
        server.enqueue(reply(401))
        val auth = TestAuth(refreshResult = RefreshResult.FailedForNow(ProviderError.RateLimited(2_000, "slow_down")))

        assertEquals(ProviderError.RateLimited(2_000, "slow_down"), errorOf { ProviderHttp(calls).send(post(), auth) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anExpiredRefreshOrAKeyGivesTheRejectedError() {
        server.enqueue(reply(401))
        server.enqueue(reply(401))

        assertEquals(
            ProviderError.SignInExpired("test-provider"),
            errorOf { ProviderHttp(calls).send(post(), TestAuth(refreshResult = RefreshResult.Expired)) },
        )
        assertEquals(ProviderError.Unauthorized, errorOf { ProviderHttp(calls).send(post(), ApiKeyAuth("test-key", KeyHeader.X_API_KEY)) })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a403NeverRefreshesAndKeepsItsCode() {
        server.enqueue(reply(403, """{"error": {"code": "model_not_allowed", "message": "Not for you"}}"""))
        val auth = TestAuth()

        assertEquals(ProviderError.QuotaOrPolicy(403, "model_not_allowed"), errorOf { ProviderHttp(calls).send(post(), auth) })
        assertEquals(0, auth.refreshes)
    }

    @Test
    fun a429KeepsItsCodeAndItsRetryAfterInSecondsOrAsADate() {
        server.enqueue(reply(429, """{"type": "error", "error": {"type": "rate_limit_error"}}""", "Retry-After" to "7"))
        server.enqueue(reply(429, "", "Retry-After" to "Tue, 17 Nov 2026 10:00:05 GMT"))
        val now = java.time.Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        val http = ProviderHttp(calls, clock = { now })

        assertEquals(ProviderError.RateLimited(7_000, "rate_limit_error"), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.RateLimited(5_000, null), errorOf { http.send(post(), NoAuth) })
    }

    @Test
    fun otherStatusesKeepTheirStatusAndCode() {
        server.enqueue(reply(402, """{"error": {"type": "insufficient_quota"}}"""))
        server.enqueue(reply(500, """{"error": {"code": "internal"}}"""))
        server.enqueue(reply(400, """{"error": {"type": "invalid_request_error"}}"""))
        server.enqueue(reply(302, "", "Location" to "https://elsewhere.example.com/"))
        val http = ProviderHttp(calls)

        assertEquals(ProviderError.QuotaOrPolicy(402, "insufficient_quota"), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.ServerError(500), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.BadRequest(400, "invalid_request_error"), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.Malformed, errorOf { http.send(post(), NoAuth) })
    }

    @Test
    fun exchangeReturnsAnErrorStatusWithItsBody() = runBlocking {
        server.enqueue(reply(400, """{"error": "authorization_pending"}"""))

        val reply = ProviderHttp(calls).exchange(HttpRequest("POST", "https://github.com/login/oauth/access_token".toHttpUrl()))

        assertEquals(400, reply.status)
        assertEquals("authorization_pending", ErrorCodes.from(reply))
    }

    @Test
    fun aKeyWithAControlCharacterGivesBadCredentialWithoutTheKey() {
        val error = try {
            runBlocking { ProviderHttp(calls).send(post(), ApiKeyAuth("secret\nkey", KeyHeader.X_API_KEY)) }
            null
        } catch (e: ProviderException) {
            e
        }

        assertEquals(ProviderError.BadCredential, error?.error)
        assertFalse(error.toString(), error.toString().contains("secret"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aStreamDeliversEachEventAndIsRetriedAfterARefresh() = runBlocking {
        server.enqueue(reply(401))
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("event: delta\ndata: {\"text\":\"Hel\"}\n\ndata: [DONE]\n\n").build(),
        )
        val events = mutableListOf<SseEvent>()
        val auth = TestAuth()

        ProviderHttp(calls).stream(post(), auth) { events += it }

        assertEquals(listOf(SseEvent("delta", """{"text":"Hel"}"""), SseEvent(null, "[DONE]")), events)
        assertEquals(1, auth.refreshes)
    }

    @Test
    fun aProviderExceptionFromOnEventPassesThroughUnchanged() {
        server.enqueue(MockResponse.Builder().body("event: error\ndata: {\"type\":\"overloaded_error\"}\n\n").build())
        val thrown = ProviderException(ProviderError.ServerError(529))

        val caught = try {
            runBlocking { ProviderHttp(calls).stream(post(), NoAuth) { throw thrown } }
            null
        } catch (e: ProviderException) {
            e
        }

        assertTrue(caught === thrown)
    }

    @Test
    fun aCancelledCallerGetsACancellationExceptionAtOnce() = runBlocking {
        server.enqueue(MockResponse.Builder().body("data: never\n\n").headersDelay(30, TimeUnit.SECONDS).build())
        val ended = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.Default) {
            try {
                ProviderHttp(calls).stream(post(), NoAuth) { }
                ended.complete(null)
            } catch (e: Throwable) {
                ended.complete(e)
                throw e
            }
        }
        server.takeRequest(5, TimeUnit.SECONDS)
        val started = System.nanoTime()

        job.cancel()
        val thrown = withTimeout(5_000) { ended.await() }

        assertTrue(thrown.toString(), thrown is CancellationException)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun aServerThatAnswersTooLateIsATimeoutAndAClosedOneUnreachable() {
        server.enqueue(MockResponse.Builder().body("late").headersDelay(2, TimeUnit.SECONDS).build())

        assertEquals(ProviderError.Timeout, errorOf { ProviderHttp(RedirectingCalls(server, readTimeoutMillis = 300)).send(post(), NoAuth) })

        val closed = MockWebServer().apply { start() }
        val closedUrl = closed.url("/")
        closed.close()
        val toClosedPort = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(closedUrl).build()) }.build()
        assertEquals(ProviderError.Unreachable, errorOf { ProviderHttp(toClosedPort).send(post(), NoAuth) })
    }

    @Test
    fun classifyTurnsTheGatewaysOwnExceptionIntoItsError() {
        class Refused : IOException("api.example.com is not on the allowlist")
        val refusing = OkHttpClient.Builder().addInterceptor { throw Refused() }.build()
        val http = ProviderHttp(refusing, classify = { if (it is Refused) ProviderError.NotAllowed else null })

        assertEquals(ProviderError.NotAllowed, errorOf { http.send(post(), NoAuth) })
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/http/SseReaderTest.kt`:

```kotlin
package dev.kestrel.providers.http

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SseReaderTest {
    private fun read(text: String): List<SseEvent> = runBlocking {
        val events = mutableListOf<SseEvent>()
        SseReader.read(Buffer().writeUtf8(text)) { events += it }
        events
    }

    @Test
    fun joinsDataLinesAndNamesEvents() {
        assertEquals(
            listOf(SseEvent("message_start", "{\"a\":1}\n{\"b\":2}"), SseEvent(null, "[DONE]")),
            read("event: message_start\ndata: {\"a\":1}\ndata: {\"b\":2}\n\ndata: [DONE]\n\n"),
        )
    }

    @Test
    fun skipsCommentsIdsRetriesAndEventsWithoutData() {
        assertEquals(
            listOf(SseEvent(null, "x")),
            read(": keep-alive\n\nid: 7\nretry: 1000\nevent: ping\n\ndata:x\n\n"),
        )
    }

    @Test
    fun readsCrLfLinesAndAnEventThatTheBodyEndsWithoutABlankLine() {
        assertEquals(listOf(SseEvent("a", "1"), SseEvent(null, "2")), read("event: a\r\ndata: 1\r\n\r\ndata: 2"))
    }

    @Test
    fun keepsAllButOneLeadingSpaceOfAValue() {
        assertEquals(listOf(SseEvent(null, "  two spaces")), read("data:   two spaces\n\n"))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/http/ErrorCodesTest.kt`:

```kotlin
package dev.kestrel.providers.http

import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorCodesTest {
    private fun code(body: String, vararg headers: String) = ErrorCodes.from(HttpReply(400, headersOf(*headers), body.encodeToByteArray()))

    @Test
    fun readsTheCodesOfEachStyleInOrder() {
        assertEquals("rate_limit_exceeded", code("""{"error": {"code": "rate_limit_exceeded", "type": "requests"}}"""))
        assertEquals("insufficient_quota", code("""{"error": {"code": null, "type": "insufficient_quota"}}"""))
        assertEquals("overloaded_error", code("""{"type": "error", "error": {"type": "overloaded_error", "message": "Busy"}}"""))
        assertEquals("invalid_grant", code("""{"error": "invalid_grant", "error_description": "Bad code"}"""))
        assertEquals("RESOURCE_EXHAUSTED", code("""{"error": {"code": 429, "message": "Quota", "status": "RESOURCE_EXHAUSTED"}}"""))
        assertEquals("ValidationException", code("""{"__type": "com.amazon.coral.validate#ValidationException", "message": "Bad"}"""))
    }

    @Test
    fun awssHeaderWinsAndLosesItsNamespace() {
        assertEquals(
            "ExpiredTokenException",
            code("""{"error": {"code": "other"}}""", "x-amzn-ErrorType", "ExpiredTokenException:http://internal.amazon.com/coral/com.amazon.coral.service/"),
        )
    }

    @Test
    fun aMessageNeverPassesAsACode() {
        assertEquals("fallback", code("""{"error": {"code": "Rate limit exceeded, try later", "type": "fallback"}}"""))
        assertNull(code("""{"detail": "Usage limit reached"}"""))
        assertNull(code("<html>Bad gateway</html>"))
        assertNull(code("""{"error": {"code": "${"x".repeat(65)}"}}"""))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/http/HostsTest.kt`:

```kotlin
package dev.kestrel.providers.http

import org.junit.Assert.assertEquals
import org.junit.Test

class HostsTest {
    @Test
    fun localAndLoopbackHosts() {
        val table = listOf(
            // host, local, loopback
            Triple("localhost", true, true),
            Triple("127.0.0.1", true, true),
            Triple("127.8.9.10", true, true),
            Triple("[::1]", true, true),
            Triple("192.168.1.20", true, false),
            Triple("10.0.0.2", true, false),
            Triple("172.16.0.1", true, false),
            Triple("172.32.0.1", false, false),
            Triple("169.254.3.4", true, false),
            Triple("fd12::1", true, false),
            Triple("fe80::1", true, false),
            Triple("studio.local", true, false),
            Triple("api.openai.com", false, false),
            Triple("8.8.8.8", false, false),
            Triple("256.1.1.1", false, false),
        )

        for ((host, local, loopback) in table) {
            assertEquals(host, local, Hosts.isLocal(host))
            assertEquals(host, loopback, Hosts.isLoopback(host))
        }
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/http/RequestAuthTest.kt`:

```kotlin
package dev.kestrel.providers.http

import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestAuthTest {
    private val request = HttpRequest("GET", "https://api.example.com/v1/models".toHttpUrl())

    @Test
    fun eachKeyHeaderCarriesTheKey() = runBlocking {
        assertEquals(mapOf("Authorization" to "Bearer test-key"), ApiKeyAuth("test-key", KeyHeader.BEARER).headers(request))
        assertEquals(mapOf("x-api-key" to "test-key"), ApiKeyAuth("test-key", KeyHeader.X_API_KEY).headers(request))
        assertEquals(mapOf("x-goog-api-key" to "test-key"), ApiKeyAuth("test-key", KeyHeader.X_GOOG_API_KEY).headers(request))
        assertEquals(mapOf("api-key" to "test-key"), ApiKeyAuth("test-key", KeyHeader.API_KEY).headers(request))
        assertEquals(emptyMap<String, String>(), NoAuth.headers(request))
    }

    @Test
    fun aKeyCannotBeRefreshedAndIsRejectedAsUnauthorized() = runBlocking {
        val auth = ApiKeyAuth("test-key", KeyHeader.BEARER)

        assertEquals(RefreshResult.NotPossible, auth.refresh())
        assertEquals(ProviderError.Unauthorized, auth.rejected)
    }

    @Test
    fun nothingThatCarriesAKeyOrABodyPrintsIt() {
        val printed = listOf(
            ApiKeyAuth("secret-key", KeyHeader.BEARER),
            HttpRequest("POST", request.url, mapOf("Authorization" to "Bearer secret-key"), "secret prompt".encodeToByteArray()),
            HttpReply(200, headersOf("Set-Cookie", "secret"), "secret reply".encodeToByteArray()),
            SseEvent("response.completed", "secret reply"),
            ProviderException(ProviderError.QuotaOrPolicy(403, "secret_code_is_fine")),
        ).joinToString()

        assertFalse(printed, Regex("secret(-key| prompt| reply)|Cookie").containsMatchIn(printed))
        assertEquals(
            "ApiKeyAuth(header=BEARER, keyLength=10), HttpRequest(method=POST, headers=1, bodyBytes=13), " +
                "HttpReply(status=200, headers=1, bodyBytes=12), SseEvent(event=response.completed, dataLength=12), " +
                "dev.kestrel.providers.http.ProviderException: QuotaOrPolicy(status=403, code=secret_code_is_fine)",
            printed,
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ProviderHttp'", "Unresolved reference 'ApiKeyAuth'", "Unresolved reference 'SseReader'", "Unresolved reference 'ErrorCodes'" and "Unresolved reference 'Hosts'".

- [ ] **Step 3: Write the errors, the authentication types, the codes and the hosts**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/http/ProviderError.kt`:

```kotlin
package dev.kestrel.providers.http

import java.io.IOException

/**
 * Why a provider call failed (design D17). Each kind carries only a status and, where it helps, a machine code that
 * matches `^[A-Za-z0-9_.:-]{1,64}$`; never a body, a provider's message, a URL with a query, a token or a key.
 */
sealed interface ProviderError {
    /** An API key that the provider refused. */
    data object Unauthorized : ProviderError

    /** A sign-in that the provider refused for good, or a credential that is missing or unreadable. */
    data class SignInExpired(val providerId: String) : ProviderError

    data class SignInSwitchedOff(val providerId: String) : ProviderError

    data class RateLimited(val retryAfterMillis: Long?, val code: String? = null) : ProviderError

    data class ServerError(val status: Int) : ProviderError

    data object Timeout : ProviderError

    /** No network, a DNS failure, a refused connection, a TLS failure or a reset (spec D10 and 11.1). */
    data object Unreachable : ProviderError

    /** The allowlist refused the host: a configuration error (spec 14.2). */
    data object NotAllowed : ProviderError

    data object LocalNetworkNotAllowed : ProviderError

    /** 402, or a 403 that is not a sign-in problem: credits exhausted, or a policy blocks the model. */
    data class QuotaOrPolicy(val status: Int, val code: String? = null) : ProviderError

    data class BadRequest(val status: Int, val code: String?) : ProviderError

    /** A key that OkHttp cannot send as a header. */
    data object BadCredential : ProviderError

    /** A response that Kestrel cannot read. */
    data object Malformed : ProviderError
}

/** Carries a [ProviderError]; its message is the error's kind, status and code only. */
class ProviderException(val error: ProviderError) : IOException(error.toString())
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/http/RequestAuth.kt`:

```kotlin
package dev.kestrel.providers.http

import kotlinx.serialization.Serializable

/**
 * The credential's part of a request (design D9). [headers] returns fresh headers for each attempt; [refresh] is called
 * once when a request fails with 401; [rejected] is the error for a credential that the provider refused for good.
 */
interface RequestAuth {
    suspend fun headers(request: HttpRequest): Map<String, String>

    suspend fun refresh(): RefreshResult = RefreshResult.NotPossible

    val rejected: ProviderError get() = ProviderError.Unauthorized
}

/** What a refresh achieved (design D9). */
sealed interface RefreshResult {
    /** The new token is stored; the request is sent once more. */
    data object Refreshed : RefreshResult

    /** No network, a timeout, a 429 or a 5xx: the credential stays, and the request fails with [error]. */
    data class FailedForNow(val error: ProviderError) : RefreshResult

    /** The provider refused the refresh for good. */
    data object Expired : RefreshResult

    /** The credential cannot be refreshed. */
    data object NotPossible : RefreshResult
}

/** The header that carries an API key (design D4). */
@Serializable
enum class KeyHeader(val header: String) {
    BEARER("Authorization"),
    X_API_KEY("x-api-key"),
    X_GOOG_API_KEY("x-goog-api-key"),
    API_KEY("api-key"),
}

/** Sends an API key in a header, never in a URL, so that no key can reach a log (design D4). */
class ApiKeyAuth(private val key: String, private val header: KeyHeader) : RequestAuth {
    override suspend fun headers(request: HttpRequest): Map<String, String> =
        mapOf(header.header to if (header == KeyHeader.BEARER) "Bearer $key" else key)

    override fun toString(): String = "ApiKeyAuth(header=$header, keyLength=${key.length})"
}

/** For requests without a credential, such as a local server without a key. */
object NoAuth : RequestAuth {
    override suspend fun headers(request: HttpRequest): Map<String, String> = emptyMap()

    override fun toString(): String = "NoAuth"
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/http/ErrorCodes.kt`:

```kotlin
package dev.kestrel.providers.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the machine code of an error reply (design D17): AWS's `x-amzn-ErrorType` header, then `error.code`, `error.type`,
 * a top-level string `error` (OAuth, RFC 6749 §5.2), Gemini's `error.status` and AWS's `__type`. AWS's values lose a
 * namespace before `#` and everything from `:` on, as AWS's SDKs read them. A code is kept only when it matches
 * `^[A-Za-z0-9_.:-]{1,64}$`, so no message or other text of the provider can pass as a code.
 */
object ErrorCodes {
    private val CODE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val json = Json { isLenient = true }

    fun from(reply: HttpReply): String? {
        reply.headers["x-amzn-ErrorType"]?.let { aws(it) }?.let { return it }
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val error = body["error"]
        val candidates = listOf(
            (error as? JsonObject)?.string("code"),
            (error as? JsonObject)?.string("type"),
            error.stringValue(),
            (error as? JsonObject)?.string("status"),
            body.string("__type")?.let(::aws),
        )
        return candidates.firstNotNullOfOrNull { it?.takeIf(CODE::matches) }
    }

    private fun aws(value: String): String? = value.substringAfter('#').substringBefore(':').trim().takeIf(CODE::matches)

    private fun JsonObject.string(key: String): String? = this[key].stringValue()

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/http/Hosts.kt`:

```kotlin
package dev.kestrel.providers.http

/**
 * Tells local hosts from public ones by the same string rules as `:core:privacy`'s LocalHosts, because this module must
 * not use InetAddress (design D1). `:app`'s HostsAgreementTest runs both over one table, so the two cannot drift.
 */
object Hosts {
    /** localhost, `*.local`, 127.0.0.0/8, ::1, the private IPv4 ranges, 169.254/16, fc00::/7 and fe80::. */
    fun isLocal(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        if (name == "localhost" || name.endsWith(".local")) return true
        if (':' in name) return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe80:")
        val octets = ipv4Octets(name) ?: return false
        val (first, second) = octets
        return first == 127 || first == 10 || (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) || (first == 169 && second == 254)
    }

    /** localhost, 127.0.0.0/8 and ::1: the phone itself, for which Android 17 needs no permission (design D11). */
    fun isLoopback(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        if (name == "localhost" || name == "::1") return true
        return ipv4Octets(name)?.first() == 127
    }

    private fun ipv4Octets(name: String): List<Int>? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { all -> all.all { it in 0..255 } }
    }
}
```

- [ ] **Step 4: Write the reader of server-sent events and ProviderHttp**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/http/SseReader.kt`:

```kotlin
package dev.kestrel.providers.http

import okio.BufferedSource

/**
 * Reads a body of server-sent events (the WHATWG rules, except that a bare CR does not end a line): `data:` lines are
 * joined with a newline, `event:` names the event, a blank line ends it, lines that start with `:` are comments, and
 * `id:` and `retry:` are ignored. An event that the body ends without a blank line is still delivered, because some
 * servers end that way; an adapter decides whether its final event came (design D4).
 */
object SseReader {
    suspend fun read(source: BufferedSource, onEvent: suspend (SseEvent) -> Unit) {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (hasData) onEvent(SseEvent(event, data.toString()))
                event = null
                data.setLength(0)
                hasData = false
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
        if (hasData) onEvent(SseEvent(event, data.toString()))
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/http/ProviderHttp.kt`:

```kotlin
package dev.kestrel.providers.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends the providers' requests through OkHttp's [Call.Factory], which `:app` takes from the network gateway (design D1),
 * so that every request passes the allowlist and reaches the network log. Every function cancels its OkHttp call when its
 * coroutine is cancelled, so that "stop" ends a running request at once.
 *
 * Failures are classified in this order: (1) a coroutine that is no longer active gets its CancellationException
 * unchanged; (2) a ProviderException passes through; (3) [classify], which `:app` uses for the gateway's
 * BlockedRequestException; (4) an InterruptedIOException is a Timeout; (5) any other IOException is Unreachable. A header
 * value that OkHttp refuses becomes BadCredential without its message, because the message can hold a key.
 */
class ProviderHttp(
    private val calls: Call.Factory,
    private val decorator: RequestDecorator = RequestDecorator { _, _ -> },
    private val classify: (IOException) -> ProviderError? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns a 2xx reply, or throws ProviderException; a 401 is refreshed and retried once (design D9). */
    suspend fun send(request: HttpRequest, auth: RequestAuth): HttpReply {
        var reply = transport { fetch(request, auth.headers(request)) }
        if (reply.status == 401) {
            refreshOrThrow(auth)
            reply = transport { fetch(request, auth.headers(request)) }
            if (reply.status == 401) throw ProviderException(auth.rejected)
        }
        if (reply.status !in 200..299) throw ProviderException(errorFor(reply))
        return reply
    }

    /** Returns any status with its headers and body, for the sign-in flows and refreshers; only transport failures throw. */
    suspend fun exchange(request: HttpRequest, auth: RequestAuth = NoAuth): HttpReply =
        transport { fetch(request, auth.headers(request)) }

    /**
     * Reads a 2xx body of server-sent events and calls [onEvent] once per event. An error status throws ProviderException
     * as [send] does; an exception that [onEvent] throws ends the stream and reaches the caller.
     */
    suspend fun stream(request: HttpRequest, auth: RequestAuth, onEvent: suspend (SseEvent) -> Unit) {
        var failed = transport { open(request, auth.headers(request), onEvent) }
        if (failed?.status == 401) {
            refreshOrThrow(auth)
            failed = transport { open(request, auth.headers(request), onEvent) }
            if (failed?.status == 401) throw ProviderException(auth.rejected)
        }
        if (failed != null) throw ProviderException(errorFor(failed))
    }

    private suspend fun refreshOrThrow(auth: RequestAuth) {
        when (val result = transport { auth.refresh() }) {
            RefreshResult.Refreshed -> Unit
            is RefreshResult.FailedForNow -> throw ProviderException(result.error)
            RefreshResult.Expired, RefreshResult.NotPossible -> throw ProviderException(auth.rejected)
        }
    }

    private suspend fun fetch(request: HttpRequest, headers: Map<String, String>): HttpReply =
        call(request, headers) { response -> HttpReply(response.code, response.headers, response.body.bytes()) }

    /** Reads the events of a 2xx reply and returns null, or returns the error reply. */
    private suspend fun open(request: HttpRequest, headers: Map<String, String>, onEvent: suspend (SseEvent) -> Unit): HttpReply? =
        call(request, headers) { response ->
            if (response.code in 200..299) {
                SseReader.read(response.body.source(), onEvent)
                null
            } else {
                HttpReply(response.code, response.headers, response.body.bytes())
            }
        }

    private suspend fun <T> call(request: HttpRequest, headers: Map<String, String>, read: suspend (Response) -> T): T {
        val call = calls.newCall(build(request, headers))
        return coroutineScope {
            // Cancels the call when the caller's coroutine is cancelled, also while a blocking read waits. A DNS lookup
            // that has already started is not interrupted, so a stop during a slow lookup ends when the lookup ends.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { call.execute().use { read(it) } }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun build(request: HttpRequest, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(request.url)
        try {
            for ((name, value) in request.headers + headers) builder.header(name, value)
        } catch (e: IllegalArgumentException) {
            // OkHttp's message contains the value, which can be a key.
            throw ProviderException(ProviderError.BadCredential)
        }
        val body = request.body?.toRequestBody(request.contentType?.toMediaTypeOrNull())
            ?: if (request.method in BODY_METHODS) ByteArray(0).toRequestBody(null) else null
        builder.method(request.method, body)
        decorator.decorate(builder, request.meta)
        return builder.build()
    }

    private suspend fun <T> transport(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ProviderException(classify(e) ?: if (e is InterruptedIOException) ProviderError.Timeout else ProviderError.Unreachable)
        }

    private fun errorFor(reply: HttpReply): ProviderError {
        val code = ErrorCodes.from(reply)
        return when (reply.status) {
            402, 403 -> ProviderError.QuotaOrPolicy(reply.status, code)
            429 -> ProviderError.RateLimited(retryAfterMillis(reply.headers["Retry-After"]), code)
            in 500..599 -> ProviderError.ServerError(reply.status)
            in 400..499 -> ProviderError.BadRequest(reply.status, code)
            else -> ProviderError.Malformed
        }
    }

    /** `Retry-After` in seconds or as an HTTP date (RFC 9110 §10.2.3). */
    private fun retryAfterMillis(value: String?): Long? {
        val text = value?.trim() ?: return null
        text.toLongOrNull()?.let { return if (it >= 0) it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 else null }
        return try {
            (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock()).coerceAtLeast(0)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with 41 tests in `:core:providers` (14 + 27): the 14 of Task 1, `ProviderHttpTest` 16, `SseReaderTest` 4, `ErrorCodesTest` 3, `HostsTest` 1 and `RequestAuthTest` 3. `aCancelledCallerGetsACancellationExceptionAtOnce` ends within 5 seconds although its server answers only after 30.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` 41; `:app` A₀, `:core:privacy` P₀, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit, whose report `core\providers\build\reports\kestrel-audit\network-apis.txt` lists no finding: `ProviderHttp` names only `Call.Factory`, `Call`, `Request`, `RequestBody`, `MediaType`, `Response` and `Headers`.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/http/ProviderError.kt core/providers/src/main/kotlin/dev/kestrel/providers/http/RequestAuth.kt core/providers/src/main/kotlin/dev/kestrel/providers/http/ErrorCodes.kt core/providers/src/main/kotlin/dev/kestrel/providers/http/Hosts.kt core/providers/src/main/kotlin/dev/kestrel/providers/http/SseReader.kt core/providers/src/main/kotlin/dev/kestrel/providers/http/ProviderHttp.kt core/providers/src/test/kotlin/dev/kestrel/providers/testing/RedirectingCalls.kt core/providers/src/test/kotlin/dev/kestrel/providers/http/ProviderHttpTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/http/SseReaderTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/http/ErrorCodesTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/http/HostsTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/http/RequestAuthTest.kt
git commit -m "feat(providers): add ProviderHttp over the gateway's call factory, with cancellation, one refresh and the provider errors" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Credentials, the local-server lease and the network log in `:core:privacy`

**Files:**
- Modify: `gradle/libs.versions.toml` (`androidx-room-testing`), `core/privacy/build.gradle.kts` (its `androidTestImplementation`)
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt` (whole file), `network/NetworkPolicy.kt` (whole file), `network/NetworkGateway.kt`, `db/KestrelDatabase.kt`, `PrivacyStore.kt`
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/credentials/CredentialStore.kt`, `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/CredentialRow.kt`
- Create (generated by Room in Step 9): `core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/2.json`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 10.7, 13.4 and 13.5)
- Test: `core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkGatewayTest.kt`, `NetworkPolicyTest.kt` (JVM, changed); `core/privacy/src/test/kotlin/dev/kestrel/privacy/credentials/CredentialStoreFormatTest.kt` (JVM, new); `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/CredentialStoreTest.kt` (device, new)

**Interfaces:**
- Consumes: `AesGcmFieldCipher`, `FieldCipher`, `KestrelDatabase.get`, `LocalHosts.isLocal` and the existing `NetworkGateway`, `NetworkPolicy` and `PrivacyStore`.
- Produces: `KeystoreKeys.CREDENTIALS_KEY = "kestrel.credentials"` and `KeystoreKeys.getOrCreate(alias: String, strongBox: Boolean = false): SecretKey`; package `dev.kestrel.privacy.credentials`: `class CredentialStore(dao: CredentialsDao, cipher: FieldCipher, onUnreadable: suspend (id: String) -> Unit = {}, dispatcher: CoroutineDispatcher = Dispatchers.IO)` with `suspend fun get(id: String): ByteArray?`, `suspend fun put(id: String, payload: ByteArray)`, `suspend fun delete(id: String)` and `suspend fun ids(): List<String>`; package `dev.kestrel.privacy.db`: `class CredentialRow(@PrimaryKey val id: String, val payload: ByteArray)` (table `credentials`), `interface CredentialsDao` (`get`, `put`, `delete`, `ids`) and `KestrelDatabase.credentialsDao()`; `PrivacyStore.credentials: CredentialStore`; package `dev.kestrel.privacy.network`: `data class LogPathTag(val path: String)` and `NetworkPolicy.openTemporaryLocalServers(hosts: Collection<String>): AutoCloseable`. Task 9 builds `StoredCredentials` on `PrivacyStore.credentials` and tags requests with `LogPathTag`; plan 4B's Vertex and Bedrock adapters set `RequestMeta.logPath`; plan 4C opens the lease for local servers while the owner checks one and for the stand-in.

Design D11, D15, D16 and D22. Credentials get their own Keystore key, made in StrongBox when the device has `FEATURE_STRONGBOX_KEYSTORE` and without it when it has none or Keystore throws `StrongBoxUnavailableException` (spec 10.7), and their own Room table: each row is encrypted with the associated data `credentials/<id>`, so a row moved to another ID does not decrypt. A row that fails authentication is deleted, as `SettingsStore` does, and reported only to Android's log, without its ID; a row in a format that this version cannot read is kept for a later version and reported once per process until a new credential replaces it, as `SettingsStore` reports an unreadable setting once; nothing about a credential goes to the activity log (D15, D22). `KestrelDatabase` goes to version 2 with a Room auto-migration, which only adds the table; Room's Gradle plugin writes the exported schema `2.json` when `:core:privacy` compiles and puts the schemas into the device tests' assets, where `MigrationTestHelper` reads them. `NetworkPolicy.openTemporaryLocalServers` allows plain HTTP to local hosts until its lease is closed, so that the owner can check a local server before its profile is saved (D11); it refuses a host that is not local, as `setLocalServers` does. The network log records the path of a request's `LogPathTag` without a query instead of the real one, so that a tag that an adapter builds with its query (such as Vertex's `?alt=sse`) cannot put a query into the log, and a failed request's detail becomes the exception's class name, because OkHttp's messages can hold a URL; a request that the allowlist refused keeps its message, which names only the host and the rule (D16).

- [ ] **Step 1: Add `room-testing` for the migration's device test**

In `gradle/libs.versions.toml`, replace

```toml
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
```

with

```toml
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
```

In `core/privacy/build.gradle.kts`, replace

```kotlin
    androidTestImplementation(libs.androidx.test.runner)
```

with

```kotlin
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
```

- [ ] **Step 2: Write the failing JVM tests**

In `core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkGatewayTest.kt`, replace

```kotlin
import org.junit.Test
import java.net.InetAddress
```

with

```kotlin
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
```

In `core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkGatewayTest.kt`, add these tests before its last closing brace:

```kotlin
    @Test
    fun logsTheTaggedPathWithoutItsQueryInsteadOfTheRealOne() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        val request = Request.Builder()
            .url(serverUrl("/v1/projects/test-project/locations/global/publishers/google/models/m:streamGenerateContent?alt=sse"))
            .tag(LogPathTag::class.java, LogPathTag("/v1/projects/{project}/locations/global/publishers/google/models/m:streamGenerateContent?alt=sse"))
            .build()

        execute(request)

        assertEquals("/v1/projects/{project}/locations/global/publishers/google/models/m:streamGenerateContent", events.single().path)
    }

    @Test
    fun aFailedRequestIsLoggedByTheClassOfItsExceptionNotByItsMessage() {
        policy.setLocalServers(listOf("127.0.0.1"))
        val url = serverUrl("/v1/models?key=secret")
        server.close()

        assertThrows(IOException::class.java) { execute(Request.Builder().url(url).build()) }

        val event = events.single()
        assertEquals(NetworkOutcome.FAILED, event.outcome)
        assertEquals("ConnectException", event.detail)
    }
```

In `core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkPolicyTest.kt`, add these tests before its last closing brace:

```kotlin
    @Test
    fun temporaryLocalServersAllowPlainHttpUntilEveryLeaseCloses() {
        val url = "http://192.168.1.30:8000/v1/models".toHttpUrl()
        val first = policy.openTemporaryLocalServers(listOf("192.168.1.30"))
        val second = policy.openTemporaryLocalServers(listOf("192.168.1.30"))

        assertEquals(PolicyDecision.Allowed, policy.check(url))
        first.close()
        first.close()
        assertEquals(PolicyDecision.Allowed, policy.check(url))

        second.close()
        assertEquals(PolicyDecision.Blocked("HTTPS is required for 192.168.1.30"), policy.check(url))
    }

    @Test
    fun temporaryLocalServersRefusePublicHosts() {
        val error = assertThrows(IllegalArgumentException::class.java) { policy.openTemporaryLocalServers(listOf("example.com")) }

        assertEquals("example.com is not a local-network or localhost address", error.message)
        assertEquals(PolicyDecision.Blocked("example.com is not on the allowlist"), policy.check("https://example.com/".toHttpUrl()))
    }
```

- [ ] **Step 3: Run the JVM tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'LogPathTag'" and "Unresolved reference 'openTemporaryLocalServers'".

- [ ] **Step 4: Write the lease and the log's changes**

Replace the whole file `core/privacy/src/main/kotlin/dev/kestrel/privacy/network/NetworkPolicy.kt` with:

```kotlin
package dev.kestrel.privacy.network

import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    data class Blocked(val reason: String) : PolicyDecision
}

/** The host allowlist (design spec, section 13.1). */
class NetworkPolicy {
    @Volatile
    private var profileHosts: Set<String> = emptySet()

    @Volatile
    private var localServers: Set<String> = emptySet()

    private val temporaryHosts = mutableMapOf<String, Int>()

    private val temporaryLocalServers = mutableMapOf<String, Int>()

    /** The hosts of the configured profiles, including the hosts they need for token refresh. */
    fun setProfileHosts(hosts: Collection<String>) {
        profileHosts = hosts.map(::normalize).toSet()
    }

    /** Local-network or localhost servers that the owner added. Only these may use plain HTTP. */
    fun setLocalServers(hosts: Collection<String>) {
        localServers = localOnly(hosts)
    }

    /** Allows [hosts] until the returned lease is closed, for example while a sign-in runs. */
    fun openTemporaryHosts(hosts: Collection<String>): AutoCloseable = lease(temporaryHosts, hosts.map(::normalize).toSet())

    /**
     * Allows the local [hosts], also over plain HTTP, until the returned lease is closed, for example while the owner checks
     * a local server before saving its profile (design D11 of sub-project 4). It refuses hosts that are not local, as
     * [setLocalServers] does.
     */
    fun openTemporaryLocalServers(hosts: Collection<String>): AutoCloseable = lease(temporaryLocalServers, localOnly(hosts))

    fun check(url: HttpUrl): PolicyDecision {
        val host = normalize(url.host)
        if (host in localServers || synchronized(temporaryLocalServers) { host in temporaryLocalServers }) return PolicyDecision.Allowed
        if (!url.isHttps) return PolicyDecision.Blocked("HTTPS is required for $host")
        val temporary = synchronized(temporaryHosts) { host in temporaryHosts }
        return if (host in profileHosts || temporary) {
            PolicyDecision.Allowed
        } else {
            PolicyDecision.Blocked("$host is not on the allowlist")
        }
    }

    private fun localOnly(hosts: Collection<String>): Set<String> {
        val normalized = hosts.map(::normalize)
        normalized.firstOrNull { !LocalHosts.isLocal(it) }?.let {
            throw IllegalArgumentException("$it is not a local-network or localhost address")
        }
        return normalized.toSet()
    }

    /** Counts each host in [leases] until the returned lease is closed; closing it twice has no effect. */
    private fun lease(leases: MutableMap<String, Int>, hosts: Set<String>): AutoCloseable {
        synchronized(leases) {
            hosts.forEach { leases[it] = (leases[it] ?: 0) + 1 }
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(leases) {
                    hosts.forEach { host ->
                        val remaining = leases.getValue(host) - 1
                        if (remaining == 0) leases.remove(host) else leases[host] = remaining
                    }
                }
            }
        }
    }

    private fun normalize(host: String): String = host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
}
```

In `core/privacy/src/main/kotlin/dev/kestrel/privacy/network/NetworkGateway.kt`, replace

```kotlin
/** Tags a request with the task that caused it, so that the network log can show it. */
data class TaskTag(val taskId: String)
```

with

```kotlin
/** Tags a request with the task that caused it, so that the network log can show it. */
data class TaskTag(val taskId: String)

/**
 * Tags a request with the path that the network log records instead of the real one, because a path can name the owner's
 * project or account, such as Vertex's `/v1/projects/<project>/…` or a Bedrock inference-profile ARN (design spec, section
 * 13.5).
 */
data class LogPathTag(val path: String)
```

and replace

```kotlin
    override fun callFailed(call: Call, ioe: IOException) {
        val outcome = if (ioe is BlockedRequestException) NetworkOutcome.BLOCKED else NetworkOutcome.FAILED
        write(call, outcome, ioe.message ?: ioe.javaClass.simpleName)
    }
```

with

```kotlin
    override fun callFailed(call: Call, ioe: IOException) {
        val blocked = ioe is BlockedRequestException
        val outcome = if (blocked) NetworkOutcome.BLOCKED else NetworkOutcome.FAILED
        // OkHttp's messages can hold a URL, so a failure is logged by its class; the allowlist's own message names only the
        // host and tells "not on the allowlist" from "HTTPS is required".
        write(call, outcome, if (blocked) ioe.message else ioe.javaClass.simpleName)
    }
```

and replace

```kotlin
                path = request.url.encodedPath,
```

with

```kotlin
                path = request.tag(LogPathTag::class.java)?.path?.substringBefore('?') ?: request.url.encodedPath,
```

- [ ] **Step 5: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with P₀ + 4 tests in `:core:privacy`: `NetworkGatewayTest` and `NetworkPolicyTest` have 2 more each. `blocksHostsOutsideTheAllowlistBeforeConnecting` still finds "tracker.example.net is not on the allowlist" as the blocked request's detail.

- [ ] **Step 6: Write the failing tests of the store**

The device test checks the Keystore key, a moved row and the migration; the JVM test checks that a row in an unknown format is kept and reported once.

Create `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/CredentialStoreTest.kt`:

```kotlin
package dev.kestrel.privacy

import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKeyFactory

/**
 * The credential store with the device's Keystore (design spec, section 10.7). Every test uses its own key alias and an
 * in-memory or test-named database, so the owner's credentials are never touched.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val alias = "kestrel.test.credentials.${System.nanoTime()}"
    private val database = Room.inMemoryDatabaseBuilder(context, KestrelDatabase::class.java).build()
    private val unreadable = mutableListOf<String>()
    private val store = CredentialStore(database.credentialsDao(), AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }, onUnreadable = { unreadable += it })

    @get:Rule
    val migrations = MigrationTestHelper(instrumentation, KestrelDatabase::class.java)

    @After
    fun cleanUp() {
        database.close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        context.deleteDatabase(MIGRATED)
    }

    @Test
    fun aCredentialReadsBackUnderItsIdUntilItIsDeleted() = runBlocking {
        store.put("test-a", "first".encodeToByteArray())
        store.put("test-a", "second".encodeToByteArray())
        store.put("test-b", "other".encodeToByteArray())

        assertArrayEquals("second".encodeToByteArray(), store.get("test-a"))
        assertEquals(listOf("test-a", "test-b"), store.ids())
        store.delete("test-a")
        assertNull(store.get("test-a"))
        assertEquals(listOf("test-b"), store.ids())
    }

    @Test
    fun aPayloadMovedToAnotherIdIsDeletedAndReported() = runBlocking {
        store.put("test-a", "key".encodeToByteArray())
        val sealed = database.credentialsDao().get("test-a")!!.payload
        database.credentialsDao().put(CredentialRow("test-moved", sealed))

        assertNull(store.get("test-moved"))
        assertEquals(listOf("test-moved"), unreadable)
        assertEquals(listOf("test-a"), store.ids())
    }

    @Test
    fun theCredentialsKeyTakesTheDevicesStrongBoxPath() {
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        val key = KeystoreKeys.getOrCreate(alias, strongBox)

        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (strongBox) {
            assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        } else {
            assertNotEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        }
        assertEquals(256, info.keySize)
    }

    @Test
    fun version1MigratesToVersion2WithItsSettingsAndAnEmptyCredentialsTable() {
        migrations.createDatabase(MIGRATED, 1).apply {
            execSQL("INSERT INTO settings (name, payload) VALUES ('test-volume', X'07')")
            close()
        }

        val migrated = migrations.runMigrationsAndValidate(MIGRATED, 2, true)

        migrated.query("SELECT payload FROM settings WHERE name = 'test-volume'").use { rows ->
            rows.moveToFirst()
            assertArrayEquals(byteArrayOf(7), rows.getBlob(0))
        }
        migrated.query("SELECT COUNT(*) FROM credentials").use { rows ->
            rows.moveToFirst()
            assertEquals(0, rows.getInt(0))
        }
    }

    private companion object {
        const val MIGRATED = "test-migration.db"
    }
}
```

Create `core/privacy/src/test/kotlin/dev/kestrel/privacy/credentials/CredentialStoreFormatTest.kt`:

```kotlin
package dev.kestrel.privacy.credentials

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.CredentialsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.KeyGenerator

class CredentialStoreFormatTest {
    private val dao = MapCredentialsDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val unreadable = mutableListOf<String>()
    private val store = CredentialStore(
        dao,
        AesGcmFieldCipher { key },
        onUnreadable = { unreadable += it },
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun keepsACredentialInAnUnknownFormatAndReportsItOnceUntilItIsReplaced() = runTest {
        store.put("profile-1", byteArrayOf(1, 2, 3))
        val payload = dao.rows.getValue("profile-1").payload
        // A later version's format: AesGcmFieldCipher reads only version 1.
        dao.rows["profile-1"] = CredentialRow("profile-1", byteArrayOf(2) + payload.copyOfRange(1, payload.size))

        assertNull(store.get("profile-1"))
        assertNull(store.get("profile-1"))
        assertEquals(listOf("profile-1"), dao.ids())
        assertEquals(listOf("profile-1"), unreadable)

        store.put("profile-1", byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), store.get("profile-1"))
        dao.rows["profile-1"] = CredentialRow("profile-1", byteArrayOf(9))
        assertNull(store.get("profile-1"))
        assertEquals(listOf("profile-1", "profile-1"), unreadable)
    }

    class MapCredentialsDao : CredentialsDao {
        val rows = sortedMapOf<String, CredentialRow>()

        override suspend fun get(id: String): CredentialRow? = rows[id]

        override suspend fun put(row: CredentialRow) {
            rows[row.id] = row
        }

        override suspend fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

        override suspend fun ids(): List<String> = rows.keys.toList()
    }
}
```

- [ ] **Step 7: Run the compilation of the tests to verify that it fails**

Run: `scripts\gradle.ps1 :core:privacy:assembleDebugAndroidTest`
Expected: `BUILD FAILED`: "Unresolved reference 'CredentialStore'", "Unresolved reference 'credentialsDao'", "Unresolved reference 'CredentialRow'" and "Too many arguments" for `KeystoreKeys.getOrCreate(alias, strongBox)`.

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`
Expected: `BUILD FAILED`, because the JVM test sources do not compile: "Unresolved reference 'CredentialStore'", "Unresolved reference 'CredentialRow'" and "Unresolved reference 'CredentialsDao'".

- [ ] **Step 8: Write the key, the table and the store**

Replace the whole file `core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt` with:

```kotlin
package dev.kestrel.privacy.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-256 keys that never leave Android Keystore (design spec, section 13.4). */
object KeystoreKeys {
    /** Encrypts logs and settings. */
    const val DATA_KEY = "kestrel.data"

    /** Encrypts the providers' credentials (design spec, section 10.7). */
    const val CREDENTIALS_KEY = "kestrel.credentials"

    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Returns the key [alias] and makes it when it does not exist yet. With [strongBox], a new key is made in StrongBox, or
     * without it when Keystore reports that StrongBox is not available.
     */
    @Synchronized
    fun getOrCreate(alias: String, strongBox: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
        if (strongBox) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // The device announced StrongBox but cannot make this key there; it is made without StrongBox below.
            }
        }
        return generate(alias, strongBox = false)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build(),
        )
        return generator.generateKey()
    }
}
```

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/CredentialRow.kt`:

```kotlin
package dev.kestrel.privacy.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** One provider credential, encrypted with the credentials key (design spec, section 10.7). */
@Entity(tableName = "credentials")
class CredentialRow(
    @PrimaryKey val id: String,
    val payload: ByteArray,
)

@Dao
interface CredentialsDao {
    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun get(id: String): CredentialRow?

    @Upsert
    suspend fun put(row: CredentialRow)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT id FROM credentials ORDER BY id")
    suspend fun ids(): List<String>
}
```

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/credentials/CredentialStore.kt`:

```kotlin
package dev.kestrel.privacy.credentials

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.CredentialsDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException

/**
 * The providers' credentials, each one encrypted row bound to its ID (design spec, section 10.7): a row that is moved to
 * another ID does not decrypt. `:core:providers` gives the bytes (its CredentialCodec); this store never sees what they
 * hold. Its functions are safe to call from the main thread, because they encrypt, decrypt and query on [dispatcher].
 */
class CredentialStore(
    private val dao: CredentialsDao,
    private val cipher: FieldCipher,
    /** Called when a stored credential cannot be read. It gets only the ID, never the bytes. */
    private val onUnreadable: suspend (id: String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // The IDs of kept rows that were reported: a row in an unknown format is reported once per process, not at every read.
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /**
     * Returns the credential's bytes, or null when there is none or it cannot be read. A row that fails authentication can
     * never be read again, so it is deleted; a row in a format that this version cannot read is kept for a later version,
     * and it is reported once until a new credential replaces it.
     */
    suspend fun get(id: String): ByteArray? =
        withContext(dispatcher) {
            val row = dao.get(id) ?: return@withContext null
            try {
                cipher.decrypt(row.payload, associatedData(id))
            } catch (e: AEADBadTagException) {
                dao.delete(id)
                onUnreadable(id)
                null
            } catch (e: IllegalArgumentException) {
                if (reported.add(id)) onUnreadable(id)
                null
            }
        }

    /** Stores [payload] under [id], replacing an earlier credential with the same ID. */
    suspend fun put(id: String, payload: ByteArray) {
        withContext(dispatcher) { dao.put(CredentialRow(id, cipher.encrypt(payload, associatedData(id)))) }
        reported.remove(id)
    }

    suspend fun delete(id: String) {
        withContext(dispatcher) { dao.delete(id) }
    }

    suspend fun ids(): List<String> = withContext(dispatcher) { dao.ids() }

    private fun associatedData(id: String) = "credentials/$id".encodeToByteArray()
}
```

In `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt`, replace

```kotlin
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LogEntryRow::class, SettingRow::class], version = 1, exportSchema = true)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun settingsDao(): SettingsDao
```

with

```kotlin
import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Version 2 adds the table `credentials` (sub-project 4); Room migrates version 1 by itself. */
@Database(
    entities = [LogEntryRow::class, SettingRow::class, CredentialRow::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun settingsDao(): SettingsDao

    abstract fun credentialsDao(): CredentialsDao
```

In `core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt`, replace

```kotlin
import android.content.Context
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogStore
import dev.kestrel.privacy.settings.SettingsStore

/**
 * Kestrel's encrypted storage: the activity log, the network log and the settings (design spec, section 13.4).
 * App code should get the stores from `KestrelApplication.graph` instead of creating another `PrivacyStore`.
 */
class PrivacyStore(context: Context, clock: () -> Long = System::currentTimeMillis) {
    private val dataKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.DATA_KEY) }
    private val cipher = AesGcmFieldCipher { dataKey }
    private val database = KestrelDatabase.get(context)
```

with

```kotlin
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogStore
import dev.kestrel.privacy.settings.SettingsStore

/**
 * Kestrel's encrypted storage: the activity log, the network log, the settings and the providers' credentials (design
 * spec, sections 10.7 and 13.4). App code should get the stores from `KestrelApplication.graph` instead of creating another
 * `PrivacyStore`.
 */
class PrivacyStore(context: Context, clock: () -> Long = System::currentTimeMillis) {
    private val dataKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.DATA_KEY) }
    private val cipher = AesGcmFieldCipher { dataKey }
    private val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val credentialsKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.CREDENTIALS_KEY, strongBox) }
    private val database = KestrelDatabase.get(context)
```

and replace

```kotlin
        onUnreadable = { name ->
            logs.append(ActivityEvent(ActivityKind.ERROR, "The setting '$name' could not be read, so Kestrel uses its default"))
        },
    )
}
```

with

```kotlin
        onUnreadable = { name ->
            logs.append(ActivityEvent(ActivityKind.ERROR, "The setting '$name' could not be read, so Kestrel uses its default"))
        },
    )

    /**
     * The providers' credentials, under their own key, in StrongBox when the device has it (design spec, section 10.7). A
     * credential that cannot be read is reported only to Android's log, without its ID, and never to the activity log; its
     * profile then asks the owner to sign in again.
     */
    val credentials = CredentialStore(
        database.credentialsDao(),
        AesGcmFieldCipher { credentialsKey },
        onUnreadable = { Log.w(TAG, "A stored credential could not be read") },
    )

    private companion object {
        const val TAG = "Kestrel"
    }
}
```

- [ ] **Step 9: Build the module and its device tests, and find the new schema**

Run: `scripts\gradle.ps1 :core:privacy:assembleDebug :core:privacy:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, and Room's Gradle plugin has written `core\privacy\schemas\dev.kestrel.privacy.db.KestrelDatabase\2.json`.

Run: `Select-String -Path core\privacy\schemas\dev.kestrel.privacy.db.KestrelDatabase\2.json -Pattern '"version": 2','"tableName": "credentials"' | Measure-Object | Select-Object -ExpandProperty Count`
Expected: `2`. The file `1.json` is unchanged (`git status --short core/privacy/schemas` shows only `?? core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/2.json`).

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with P₀ + 5 tests in `:core:privacy`: the 4 of Step 5 and `CredentialStoreFormatTest`'s 1.

- [ ] **Step 10: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
- Credentials are encrypted with AES-GCM using a non-exportable Android Keystore key, kept in StrongBox when the device has it.
- They are never logged, never shown in full after entry, and never included in an export.
```

with

```markdown
- Credentials are encrypted with AES-GCM using a non-exportable Android Keystore key of their own (`kestrel.credentials`), made in StrongBox when the device has it and without StrongBox otherwise. They are kept in their own table, each row bound to its ID, so that a row moved to another ID cannot be read. A credential that fails this check is deleted, and one in a format that this version cannot read is kept for a later version; in both cases its profile asks the owner to sign in again.
- They are never logged, never shown in full after entry (a key shows only its last four characters, a sign-in only its date), and never included in an export or a backup. Kestrel keeps the credentials that it has read in memory while its process runs, because StrongBox is slow.
```

and replace

```markdown
- Kestrel stores its data in a Room database. Sensitive fields are encrypted with AES-GCM using Android Keystore keys, and credentials use a separate key.
```

with

```markdown
- Kestrel stores its data in a Room database. Sensitive fields are encrypted with AES-GCM using Android Keystore keys, and credentials use a separate key (`kestrel.credentials`) and their own table.
```

and replace

```markdown
| Network log | Time, host, path, request and response size, status, and the task it belongs to | 7 days |
```

with

```markdown
| Network log | Time, host, path, request and response size, status, and the task it belongs to. The path is the request's path with the parts that name the owner's project or account replaced, such as Vertex's project or a Bedrock model; a failed request's detail is the error's class, except for a request that the allowlist refused, whose detail names the host and the rule | 7 days |
```

- [ ] **Step 11: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:privacy` P₀ + 5; `:core:providers` 41, `:app` A₀, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit. `room-testing` is only a device-test dependency, so the dependency report `app\build\reports\kestrel-audit\dependencies-release.txt` does not list it.

- [ ] **Step 12: Run the device tests of `:core:privacy`**

Wait for the emulator lock and take it:

Run: `$deadline = (Get-Date).AddMinutes(60); while (Test-Path .superpowers\sdd\emulator.lock) { if ((Get-Date) -gt $deadline) { throw 'BLOCKED: the emulator lock still exists after 60 minutes' }; Start-Sleep -Seconds 120 }; Set-Content .superpowers\sdd\emulator.lock 'plan 4A task 3'`
Expected: no output. If it throws, report BLOCKED to the controller.

Then run:

`. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`

Expected: `BUILD SUCCESSFUL`, and the result files in `core\privacy\build\outputs\androidTest-results\connected\debug\` show Q₀ + 4 tests (the 4 of `CredentialStoreTest`) and 0 failures. kestrel-api37 has no StrongBox, so `theCredentialsKeyTakesTheDevicesStrongBoxPath` checks the path without it. Delete the lock afterwards, also when the tests fail: `Remove-Item .superpowers\sdd\emulator.lock`.

- [ ] **Step 13: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 14: Commit**

```powershell
git add gradle/libs.versions.toml core/privacy/build.gradle.kts core/privacy/src/main/kotlin/dev/kestrel/privacy/crypto/KeystoreKeys.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/network/NetworkPolicy.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/network/NetworkGateway.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/db/KestrelDatabase.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/db/CredentialRow.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/credentials/CredentialStore.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/PrivacyStore.kt core/privacy/schemas/dev.kestrel.privacy.db.KestrelDatabase/2.json core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkGatewayTest.kt core/privacy/src/test/kotlin/dev/kestrel/privacy/network/NetworkPolicyTest.kt core/privacy/src/test/kotlin/dev/kestrel/privacy/credentials/CredentialStoreFormatTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/CredentialStoreTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(privacy): store credentials under their own key and table, lease local servers, and log tagged paths and failure classes" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The catalog model and its merge

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/cost/Price.kt`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/catalog/Catalog.kt`, `Overrides.kt`, `CatalogLoader.kt`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ModelTarget.kt`, `ChatAdapter.kt`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/catalog/CatalogLoaderTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ModelTargetTest.kt`

**Interfaces:**
- Consumes: Task 1's `RequestStyle` and `ChatRequest`, Task 2's `KeyHeader` and `RequestAuth`.
- Produces (package `dev.kestrel.providers.cost`): `@Serializable data class Price(val input: Double, val output: Double, val cacheRead: Double? = null, val cacheWrite: Double? = null, val tiers: List<PriceTier> = emptyList(), val validFrom: String? = null, val validUntil: String? = null)` with `companion object { val FREE: Price }`; `@Serializable data class PriceTier(val aboveInputTokens: Long, val input: Double, val output: Double, val cacheRead: Double? = null, val cacheWrite: Double? = null)`. Package `dev.kestrel.providers.catalog`, all serializable: `data class Catalog(val openclawCommit: String, val providers: List<ProviderEntry>, val localServers: List<LocalServer>, val overrides: Overrides)` with `fun provider(id: String): ProviderEntry?` and `fun choice(providerId: String, choiceId: String): AuthChoice?`; `data class CatalogFile(val schema: Int = 1, val openclawCommit: String, val providers: List<ProviderEntry>, val skipped: List<Skipped> = emptyList())` (the content of `providers.json`); `data class Skipped(val plugin: String, val provider: String? = null, val model: String? = null, val reason: String)`; `data class ProviderEntry(val id: String, val plugin: String, val label: String, val style: RequestStyle?, val baseUrl: String?, val hosts: List<String>, val regions: List<Region> = emptyList(), val headers: Map<String, String> = emptyMap(), val defaultModel: String? = null, val utilityModel: String? = null, val discovery: Discovery = Discovery.STATIC, val authChoices: List<AuthChoice>, val models: List<ModelEntry>)`; `data class Region(val id: String, val label: String, val baseUrl: String)`; `enum class Discovery { STATIC, REFRESHABLE, RUNTIME }`; `data class AuthChoice(val choiceId: String, val method: String, val label: String, val hint: String? = null, val settings: ChoiceOverride? = null)` with `val offered: Boolean` (`settings?.offered == true`); `enum class AuthMethod { API_KEY, DEVICE_CODE, BROWSER, IMPORTED, SERVER_ADDRESS }`; `data class ModelEntry(val id: String, val name: String, val style: RequestStyle? = null, val baseUrl: String? = null, val input: Set<InputKind> = setOf(InputKind.TEXT), val reasoning: Boolean = false, val contextWindow: Int? = null, val maxTokens: Int? = null, val prices: List<Price> = emptyList(), val compat: CompatFlags = CompatFlags(), val thinkingLevelMap: Map<String, String?> = emptyMap(), val status: ModelStatus = ModelStatus.AVAILABLE, val toolMode: ToolMode = ToolMode.NATIVE)`; `enum class InputKind { TEXT, IMAGE }`; `enum class ModelStatus { AVAILABLE, PREVIEW, DEPRECATED }`; `enum class ToolMode { NATIVE, JSON }`; `data class CompatFlags(...)` with the nullable flags `maxTokensField: String?`, `supportsTools: Boolean?`, `supportsStrictMode: Boolean?`, `supportsStore: Boolean?`, `supportsDeveloperRole: Boolean?`, `supportsReasoningEffort: Boolean?`, `supportedReasoningEfforts: List<String>?`, `reasoningEffortMap: Map<String, String>?`, `supportsTemperature: Boolean?`, `supportsPromptCacheKey: Boolean?`, `thinkingFormat: String?`, `thinkingLevels: List<String>?`, `defaultThinkingLevel: String?`, `requiresReasoningContentOnAssistantMessages: Boolean?`, `requiresStringContent: Boolean?`, `strictMessageKeys: Boolean?`, `requiresToolResultName: Boolean?`, `requiresAssistantAfterToolResult: Boolean?`, `requiresThinkingAsText: Boolean?`, `supportsUsageInStreaming: Boolean?`, `supportsJsonSchemaResponseFormat: Boolean?`, `cacheControlFormat: String?`, all `= null`; `data class LocalServer(val id: String, val label: String, val kind: LocalServerKind, val style: RequestStyle, val defaultUrl: String)`; `enum class LocalServerKind { OLLAMA, LM_STUDIO, VLLM, SGLANG, LLAMA_CPP, OMLX, LITELLM }`; `data class Overrides(val schema: Int, val excludeProviders: List<Exclusion>, val excludeAuthChoices: List<AuthExclusion>, val choices: List<ChoiceOverride>, val providers: List<ProviderOverride>, val localServers: List<LocalServer>, val models: List<ModelOverride>, val freeModels: List<ModelRef>, val unknownPrice: Price, val copilot: CopilotOverrides)`; `data class Exclusion(val id: String, val reason: String)`; `data class AuthExclusion(val provider: String, val choiceId: String, val reason: String)`; `data class ModelRef(val provider: String, val model: String)`; `data class TermsLink(val label: String, val url: String)`; `data class ChoiceOverride(val provider: String, val choiceId: String, val method: AuthMethod, val label: String? = null, val baseUrl: String? = null, val style: RequestStyle? = null, val chatgptPlan: Boolean = false, val planUsage: Boolean = false, val keyHeader: KeyHeader? = null, val identityHeaders: Map<String, String> = emptyMap(), val refreshHosts: List<String> = emptyList(), val customBaseUrl: Boolean = false, val borrowedIdentity: String? = null, val offered: Boolean = true, val defaultOn: Boolean = true, val termsLinks: List<TermsLink> = emptyList())`; `data class ProviderOverride(val id: String, val label: String? = null, val plugin: String? = null, val style: RequestStyle? = null, val baseUrl: String? = null, val hosts: List<String> = emptyList(), val regions: List<Region> = emptyList(), val discovery: Discovery? = null, val modelsFrom: String? = null, val models: List<ModelEntry> = emptyList(), val defaultModel: String? = null, val utilityModel: String? = null)`; `data class ModelOverride(val provider: String, val model: String, val style: RequestStyle? = null, val toolMode: ToolMode? = null, val prices: List<Price>? = null, val pricesCopiedOn: String? = null, val pricesSource: String? = null)`; `data class CopilotOverrides(val pricesCopiedOn: String, val pricesSource: String, val models: List<CopilotPrice>, val lightweight: List<String>, val versatile: List<String>, val runtimeHeaders: Map<String, String>, val ideHeaders: Map<String, String>)`; `data class CopilotPrice(val name: String, val id: String? = null, val prices: List<Price>)`; `object CatalogLoader { const val COPILOT = "github-copilot"; fun load(providersJson: String, overridesJson: String): Catalog; fun copilotPrice(copilot: CopilotOverrides, modelId: String, modelName: String?): CopilotPrice?; fun normalise(name: String): String; fun hostOf(url: String?): String? }`. Package `dev.kestrel.providers.chat`: `data class ModelTarget(val providerId: String, val style: RequestStyle, val baseUrl: HttpUrl, val model: ModelEntry, val sentModelId: String = model.id, val auth: RequestAuth, val headers: RequestHeaders = RequestHeaders.NONE, val options: StyleOptions = StyleOptions())`; `data class StyleOptions(val chatgptPlan: Boolean = false, val stripThinking: Boolean = false, val sanitizeToolCallIds: Boolean = false, val messagesPath: String = "/v1/messages", val wire: RequestStyle? = null, val compat: CompatFlags? = null, val stream: Boolean? = null)`; `fun interface RequestHeaders { fun headers(request: ChatRequest): Map<String, String>; companion object { val NONE: RequestHeaders } }`; `interface ChatAdapter { suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse }`.

Design D2, D4, D7, D12 and "Shared names". `CatalogLoader.load` merges `providers.json` and `overrides.json` at runtime, so that a change of the overrides needs no new sync, in this order: it drops excluded providers and choices; applies the per-model corrections and prices to the synced rows, and gives a row whose compat says `supportsTools: false` JSON actions (D7); completes and adds the providers of the overrides (a provider's hosts gain the override's hosts and its base URL's host when that has no placeholder); copies rows with `modelsFrom` after the prices, so that Vertex's Gemini rows carry the Gemini prices, with the source's style as their wire and without the source's base URL; attaches each choice's settings and adds Kestrel's own choices with the method `kestrel`; and puts `overrides.copilot`'s prices on the `github-copilot` rows by ID, else by normalised name, and no price on the others, because the manifest's prices are not GitHub's. Providers and rows come out sorted by ID. A Copilot model is matched by ID first and then by its normalised name (lower case, without spaces, hyphens and underscores), because research r28 did not map GitHub's display names to IDs (D12). `ModelTarget` is what an adapter of plan 4B needs for one model; `StyleOptions` documents the meaning of each switch for plans 4B and 4C.

- [ ] **Step 1: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/catalog/CatalogLoaderTest.kt`:

```kotlin
package dev.kestrel.providers.catalog

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.cost.PriceTier
import dev.kestrel.providers.http.KeyHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLoaderTest {
    private val providers = """
        {"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
          {"id": "acme", "plugin": "acme", "label": "Acme", "style": "CHAT_COMPLETIONS", "baseUrl": "https://api.acme.test/v1",
           "hosts": ["api.acme.test"], "discovery": "REFRESHABLE",
           "authChoices": [{"choiceId": "acme-api-key", "method": "api-key", "label": "Acme API key"},
                           {"choiceId": "acme-cli", "method": "cli", "label": "Acme CLI"}],
           "models": [{"id": "fast", "name": "Fast", "prices": [{"input": 1.0, "output": 2.0}]},
                      {"id": "plain", "name": "Plain", "compat": {"supportsTools": false}},
                      {"id": "zero", "name": "Zero"}]},
          {"id": "gone", "plugin": "gone", "label": "Gone", "style": null, "baseUrl": null, "hosts": [], "discovery": "STATIC",
           "authChoices": [{"choiceId": "gone-local", "method": "local", "label": "Gone"}], "models": []},
          {"id": "gemini", "plugin": "gemini", "label": "Gemini", "style": "GEMINI", "baseUrl": "https://gemini.test/v1beta",
           "hosts": ["gemini.test"], "discovery": "RUNTIME",
           "authChoices": [{"choiceId": "gemini-api-key", "method": "api-key", "label": "Gemini API key"}],
           "models": [{"id": "g-1", "name": "G 1", "input": ["TEXT", "IMAGE"], "baseUrl": "https://gemini.test/v1beta"}]},
          {"id": "github-copilot", "plugin": "github-copilot", "label": "Copilot", "style": "RESPONSES",
           "baseUrl": "https://api.individual.githubcopilot.com", "hosts": ["api.individual.githubcopilot.com"], "discovery": "REFRESHABLE",
           "authChoices": [{"choiceId": "github-copilot", "method": "device", "label": "GitHub Copilot"}],
           "models": [{"id": "claude-sonnet-5.5", "name": "Claude Sonnet 5.5", "prices": [{"input": 9.0, "output": 9.0}]},
                      {"id": "gpt-6.1-terra", "name": "GPT-6.1 Terra"},
                      {"id": "unlisted", "name": "Unlisted", "prices": [{"input": 9.0, "output": 9.0}]}]}
        ], "skipped": []}
    """.trimIndent()

    private val overrides = """
        {"schema": 1,
         "excludeProviders": [{"id": "gone", "reason": "A desktop program."}],
         "excludeAuthChoices": [{"provider": "acme", "choiceId": "acme-cli", "reason": "A desktop program."}],
         "choices": [
           {"provider": "acme", "choiceId": "acme-api-key", "method": "API_KEY", "label": "Acme key"},
           {"provider": "gemini", "choiceId": "gemini-api-key", "method": "API_KEY", "offered": false},
           {"provider": "cloud", "choiceId": "cloud-keys", "method": "IMPORTED", "keyHeader": "BEARER", "refreshHosts": ["oauth.cloud.test"]},
           {"provider": "github-copilot", "choiceId": "github-copilot", "method": "DEVICE_CODE", "borrowedIdentity": "copilot"}
         ],
         "providers": [
           {"id": "acme", "baseUrl": "https://api2.acme.test/v1", "hosts": ["files.acme.test"], "regions": [{"id": "eu", "label": "Europe", "baseUrl": "https://eu.acme.test/v1"}]},
           {"id": "cloud", "plugin": "gemini", "label": "Cloud", "style": "VERTEX", "baseUrl": "https://{location}-cloud.test",
            "discovery": "STATIC", "modelsFrom": "gemini", "defaultModel": "claude-x", "utilityModel": "g-1",
            "models": [{"id": "claude-x", "name": "Claude X", "style": "ANTHROPIC", "prices": [{"input": 3.0, "output": 15.0}]}]}
         ],
         "localServers": [{"id": "ollama", "label": "Ollama", "kind": "OLLAMA", "style": "OLLAMA", "defaultUrl": "http://127.0.0.1:11434"}],
         "models": [
           {"provider": "acme", "model": "zero", "toolMode": "JSON"},
           {"provider": "gemini", "model": "g-1", "prices": [{"input": 0.5, "output": 3.0, "cacheRead": 0.05}]}
         ],
         "freeModels": [],
         "unknownPrice": {"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25},
         "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/",
           "models": [
             {"name": "Claude Sonnet 5.5", "id": "claude-sonnet-5.5", "prices": [{"input": 2.0, "output": 10.0}]},
             {"name": "GPT-6.1 Terra", "prices": [{"input": 1.0, "output": 5.0, "tiers": [{"aboveInputTokens": 272000, "input": 2.0, "output": 7.5}]}]}
           ],
           "lightweight": ["GPT-6 Luna"], "versatile": ["GPT-6.1 Terra"],
           "runtimeHeaders": {"Editor-Version": "vscode/1.107.0"}, "ideHeaders": {"Editor-Version": "vscode/1.107.0"}}}
    """.trimIndent()

    private val catalog = CatalogLoader.load(providers, overrides)

    @Test
    fun excludedProvidersAndChoicesDisappear() {
        assertNull(catalog.provider("gone"))
        assertEquals(listOf("acme-api-key"), catalog.provider("acme")!!.authChoices.map { it.choiceId })
        assertEquals(listOf("acme", "cloud", "gemini", "github-copilot"), catalog.providers.map { it.id })
        assertEquals("e6113549fe8800b4c736c5e8a131b47f2e015143", catalog.openclawCommit)
        assertEquals(LocalServerKind.OLLAMA, catalog.localServers.single().kind)
    }

    @Test
    fun choicesGetTheirSettingsAndKestrelsOwnChoicesAreAdded() {
        val key = catalog.choice("acme", "acme-api-key")!!
        assertEquals("Acme key", key.label)
        assertEquals(AuthMethod.API_KEY, key.settings!!.method)
        assertTrue(key.offered)
        assertFalse(catalog.choice("gemini", "gemini-api-key")!!.offered)

        val settings = catalog.choice("cloud", "cloud-keys")!!.let { assertEquals("kestrel", it.method); it.settings!! }
        assertEquals(KeyHeader.BEARER, settings.keyHeader)
        assertEquals(listOf("oauth.cloud.test"), settings.refreshHosts)
    }

    @Test
    fun correctionsApplyToTheSyncedRows() {
        val acme = catalog.provider("acme")!!
        assertEquals(ToolMode.JSON, acme.models.single { it.id == "zero" }.toolMode)
        assertEquals(ToolMode.JSON, acme.models.single { it.id == "plain" }.toolMode)
        assertEquals(ToolMode.NATIVE, acme.models.single { it.id == "fast" }.toolMode)
        assertEquals(listOf(Price(1.0, 2.0)), acme.models.single { it.id == "fast" }.prices)
        assertEquals(listOf("api.acme.test", "files.acme.test", "api2.acme.test"), acme.hosts)
        assertEquals("https://eu.acme.test/v1", acme.regions.single().baseUrl)
        assertEquals(Discovery.REFRESHABLE, acme.discovery)
    }

    @Test
    fun anAddedProviderCopiesItsRowsAfterThePricesWithTheSourcesStyle() {
        val cloud = catalog.provider("cloud")!!

        assertEquals("gemini", cloud.plugin)
        assertEquals(RequestStyle.VERTEX, cloud.style)
        assertEquals(emptyList<String>(), cloud.hosts)
        assertEquals(listOf("claude-x", "g-1"), cloud.models.map { it.id })
        val gemini = cloud.models.single { it.id == "g-1" }
        assertEquals(RequestStyle.GEMINI, gemini.style)
        assertEquals(listOf(Price(0.5, 3.0, cacheRead = 0.05)), gemini.prices)
        assertEquals(setOf(InputKind.TEXT, InputKind.IMAGE), gemini.input)
        assertNull(gemini.baseUrl)
        assertEquals("claude-x", cloud.defaultModel)
        assertEquals("g-1", cloud.utilityModel)
        assertEquals(RequestStyle.ANTHROPIC, cloud.models.single { it.id == "claude-x" }.style)
    }

    @Test
    fun copilotsRowsGetTheOverridesPricesByIdOrByNameAndNoOthers() {
        val rows = catalog.provider("github-copilot")!!.models.associateBy { it.id }

        assertEquals(listOf(Price(2.0, 10.0)), rows.getValue("claude-sonnet-5.5").prices)
        assertEquals(listOf(Price(1.0, 5.0, tiers = listOf(PriceTier(272_000, 2.0, 7.5)))), rows.getValue("gpt-6.1-terra").prices)
        assertEquals(emptyList<Price>(), rows.getValue("unlisted").prices)
    }

    @Test
    fun aCopilotModelIsMatchedByIdThenByItsNormalisedIdOrName() {
        val copilot = catalog.overrides.copilot

        assertEquals("Claude Sonnet 5.5", CatalogLoader.copilotPrice(copilot, "claude-sonnet-5.5", null)?.name)
        assertEquals("GPT-6.1 Terra", CatalogLoader.copilotPrice(copilot, "gpt-6.1-terra", null)?.name)
        assertEquals("GPT-6.1 Terra", CatalogLoader.copilotPrice(copilot, "terra-2026", "GPT 6.1_Terra")?.name)
        assertNull(CatalogLoader.copilotPrice(copilot, "gpt-6-luna", "GPT-6 Luna"))
        assertEquals("gpt6.1terra", CatalogLoader.normalise("GPT-6.1 Terra"))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/chat/ModelTargetTest.kt`:

```kotlin
package dev.kestrel.providers.chat

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.http.NoAuth
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelTargetTest {
    @Test
    fun aTargetPrintsNeitherItsBaseUrlNorItsModelId() {
        val target = ModelTarget(
            providerId = "google-vertex",
            style = RequestStyle.VERTEX,
            baseUrl = "https://aiplatform.googleapis.com/v1/projects/secret-project/locations/global".toHttpUrl(),
            model = ModelEntry(id = "arn:aws:bedrock:us-east-1:123456789012:x", name = "X"),
            auth = NoAuth,
            options = StyleOptions(wire = RequestStyle.ANTHROPIC),
        )

        val printed = target.toString()

        assertFalse(printed, printed.contains("secret-project") || printed.contains("123456789012"))
        assertEquals(
            "ModelTarget(provider=google-vertex, style=VERTEX, modelIdLength=40, options=StyleOptions(chatgptPlan=false, " +
                "stripThinking=false, sanitizeToolCallIds=false, messagesPath=/v1/messages, wire=ANTHROPIC, compat=null, stream=null))",
            printed,
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'CatalogLoader'", "Unresolved reference 'Price'", "Unresolved reference 'ModelTarget'" and "Unresolved reference 'StyleOptions'".

- [ ] **Step 3: Write the price and the catalog's types**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/cost/Price.kt`:

```kotlin
package dev.kestrel.providers.cost

import kotlinx.serialization.Serializable

/**
 * A price in US dollars per million tokens (design D14). A missing cache price counts those tokens at the input price.
 * [tiers] apply when the request's whole input is above their threshold; [validFrom] and [validUntil] are UTC days
 * (`yyyy-MM-dd`), and [validUntil] is exclusive.
 */
@Serializable
data class Price(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    val tiers: List<PriceTier> = emptyList(),
    val validFrom: String? = null,
    val validUntil: String? = null,
) {
    companion object {
        /** A local server on the owner's own network, or a model that overrides.json lists as free. */
        val FREE = Price(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0)
    }
}

/** A long-context price that applies above [aboveInputTokens] input tokens (cache reads and writes included). */
@Serializable
data class PriceTier(
    val aboveInputTokens: Long,
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
)
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/catalog/Catalog.kt`:

```kotlin
package dev.kestrel.providers.catalog

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The provider catalog that Kestrel offers (spec 10.1 and 10.2): `catalog/providers.json`, which `:tools:catalog-sync`
 * generates from OpenClaw's manifests, merged at runtime with the hand-written `catalog/overrides.json` (design D2).
 */
@Serializable
data class Catalog(
    val openclawCommit: String,
    val providers: List<ProviderEntry>,
    val localServers: List<LocalServer>,
    val overrides: Overrides,
) {
    fun provider(id: String): ProviderEntry? = providers.firstOrNull { it.id == id }

    fun choice(providerId: String, choiceId: String): AuthChoice? =
        provider(providerId)?.authChoices?.firstOrNull { it.choiceId == choiceId }
}

/** The content of `catalog/providers.json` (schema 1), which the sync writes and the loader reads. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CatalogFile(
    @EncodeDefault val schema: Int = 1,
    val openclawCommit: String,
    val providers: List<ProviderEntry>,
    val skipped: List<Skipped> = emptyList(),
)

/** A plugin, provider or model that the sync left out, and why. */
@Serializable
data class Skipped(val plugin: String, val provider: String? = null, val model: String? = null, val reason: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProviderEntry(
    val id: String,
    val plugin: String,
    val label: String,
    /** Null where the manifest names no style; overrides.json completes such providers (design D4). */
    val style: RequestStyle?,
    /** May hold `{region}` or `{location}`, which the profile fills in (design D13). */
    val baseUrl: String?,
    /** The hosts of the base URL and of the models' base URLs; a host may hold `{region}` too. */
    val hosts: List<String>,
    val regions: List<Region> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val defaultModel: String? = null,
    val utilityModel: String? = null,
    @EncodeDefault val discovery: Discovery = Discovery.STATIC,
    val authChoices: List<AuthChoice>,
    val models: List<ModelEntry>,
)

/** A region or location that the owner chooses, with its own base URL. */
@Serializable
data class Region(val id: String, val label: String, val baseUrl: String)

/** Whether a provider's models come only from the catalog, may be refreshed from its list, or come only from its list. */
@Serializable
enum class Discovery { STATIC, REFRESHABLE, RUNTIME }

/** A sign-in choice. [settings] is null for a choice that Kestrel does not offer. */
@Serializable
data class AuthChoice(
    val choiceId: String,
    /** OpenClaw's method name, or `kestrel` for a choice that only overrides.json has. */
    val method: String,
    val label: String,
    val hint: String? = null,
    val settings: ChoiceOverride? = null,
) {
    /** Whether Kestrel offers this choice for new profiles (before the borrowed identity's switch). */
    val offered: Boolean get() = settings?.offered == true
}

/** How the owner signs in with a choice (design D9). */
@Serializable
enum class AuthMethod { API_KEY, DEVICE_CODE, BROWSER, IMPORTED, SERVER_ADDRESS }

@Serializable
data class ModelEntry(
    val id: String,
    val name: String,
    /** Set when the model uses another style than its provider; for a Vertex model, its wire (GEMINI or ANTHROPIC). */
    val style: RequestStyle? = null,
    val baseUrl: String? = null,
    val input: Set<InputKind> = setOf(InputKind.TEXT),
    val reasoning: Boolean = false,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    /** Empty when Kestrel knows no price: PriceTable then uses the unknown price (design D14). */
    val prices: List<Price> = emptyList(),
    val compat: CompatFlags = CompatFlags(),
    val thinkingLevelMap: Map<String, String?> = emptyMap(),
    val status: ModelStatus = ModelStatus.AVAILABLE,
    val toolMode: ToolMode = ToolMode.NATIVE,
)

@Serializable
enum class InputKind { TEXT, IMAGE }

@Serializable
enum class ModelStatus { AVAILABLE, PREVIEW, DEPRECATED }

/** NATIVE sends tool definitions; JSON asks for a JSON action instead (design D7). */
@Serializable
enum class ToolMode { NATIVE, JSON }

/**
 * OpenClaw's compatibility flags of a model, named as in OpenClaw (design D5, research r39). Null means "not stated":
 * plan 4B's CompatDefaults then derives the value from the provider and its host.
 */
@Serializable
data class CompatFlags(
    val maxTokensField: String? = null,
    val supportsTools: Boolean? = null,
    val supportsStrictMode: Boolean? = null,
    val supportsStore: Boolean? = null,
    val supportsDeveloperRole: Boolean? = null,
    val supportsReasoningEffort: Boolean? = null,
    val supportedReasoningEfforts: List<String>? = null,
    val reasoningEffortMap: Map<String, String>? = null,
    val supportsTemperature: Boolean? = null,
    val supportsPromptCacheKey: Boolean? = null,
    val thinkingFormat: String? = null,
    val thinkingLevels: List<String>? = null,
    val defaultThinkingLevel: String? = null,
    val requiresReasoningContentOnAssistantMessages: Boolean? = null,
    val requiresStringContent: Boolean? = null,
    val strictMessageKeys: Boolean? = null,
    val requiresToolResultName: Boolean? = null,
    val requiresAssistantAfterToolResult: Boolean? = null,
    val requiresThinkingAsText: Boolean? = null,
    val supportsUsageInStreaming: Boolean? = null,
    val supportsJsonSchemaResponseFormat: Boolean? = null,
    val cacheControlFormat: String? = null,
)

/** A server that the owner runs and Kestrel connects to by its address (design D11). [id] is its provider's ID. */
@Serializable
data class LocalServer(val id: String, val label: String, val kind: LocalServerKind, val style: RequestStyle, val defaultUrl: String)

@Serializable
enum class LocalServerKind { OLLAMA, LM_STUDIO, VLLM, SGLANG, LLAMA_CPP, OMLX, LITELLM }
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/catalog/Overrides.kt`:

```kotlin
package dev.kestrel.providers.catalog

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.http.KeyHeader
import kotlinx.serialization.Serializable

/** The content of `catalog/overrides.json` (schema 1), written by hand (design D2 and "Shared names"). */
@Serializable
data class Overrides(
    val schema: Int,
    val excludeProviders: List<Exclusion>,
    val excludeAuthChoices: List<AuthExclusion>,
    val choices: List<ChoiceOverride>,
    val providers: List<ProviderOverride>,
    val localServers: List<LocalServer>,
    val models: List<ModelOverride>,
    val freeModels: List<ModelRef>,
    val unknownPrice: Price,
    val copilot: CopilotOverrides,
)

@Serializable
data class Exclusion(val id: String, val reason: String)

@Serializable
data class AuthExclusion(val provider: String, val choiceId: String, val reason: String)

@Serializable
data class ModelRef(val provider: String, val model: String)

@Serializable
data class TermsLink(val label: String, val url: String)

/**
 * The settings that depend on the sign-in rather than on the provider (design D9, D18 and D21). A choice that only
 * Kestrel has (for example `chatgpt-plan` or `bedrock-api-key`) is a ChoiceOverride whose [choiceId] no manifest has.
 * [keyHeader] null means the style's default (design D4). [borrowedIdentity] names the switch of a borrowed sign-in;
 * [defaultOn] is its state until the owner changes it.
 */
@Serializable
data class ChoiceOverride(
    val provider: String,
    val choiceId: String,
    val method: AuthMethod,
    val label: String? = null,
    val baseUrl: String? = null,
    val style: RequestStyle? = null,
    val chatgptPlan: Boolean = false,
    val planUsage: Boolean = false,
    val keyHeader: KeyHeader? = null,
    val identityHeaders: Map<String, String> = emptyMap(),
    val refreshHosts: List<String> = emptyList(),
    val customBaseUrl: Boolean = false,
    val borrowedIdentity: String? = null,
    val offered: Boolean = true,
    val defaultOn: Boolean = true,
    val termsLinks: List<TermsLink> = emptyList(),
)

/**
 * Completes a provider that the sync wrote, or adds one that it did not ([plugin] then names the plugin). [baseUrl] and
 * the regions' URLs may hold `{region}` or `{location}`; [modelsFrom] copies another provider's rows (Vertex from
 * `google`); [models] are added beside the synced and discovered rows, and replace a synced row with the same ID;
 * [defaultModel] and [utilityModel] name the default models of a provider that the overrides complete.
 */
@Serializable
data class ProviderOverride(
    val id: String,
    val label: String? = null,
    val plugin: String? = null,
    val style: RequestStyle? = null,
    val baseUrl: String? = null,
    val hosts: List<String> = emptyList(),
    val regions: List<Region> = emptyList(),
    val discovery: Discovery? = null,
    val modelsFrom: String? = null,
    val models: List<ModelEntry> = emptyList(),
    val defaultModel: String? = null,
    val utilityModel: String? = null,
)

/** A correction of one synced model: its style, its tool mode (design D7) or its prices (design D14), with where and when the prices were copied. */
@Serializable
data class ModelOverride(
    val provider: String,
    val model: String,
    val style: RequestStyle? = null,
    val toolMode: ToolMode? = null,
    val prices: List<Price>? = null,
    val pricesCopiedOn: String? = null,
    val pricesSource: String? = null,
)

/**
 * GitHub Copilot's prices, copied from GitHub's pricing page on [pricesCopiedOn] (design D12), its default models by name
 * in order of preference, and its two header sets.
 */
@Serializable
data class CopilotOverrides(
    val pricesCopiedOn: String,
    val pricesSource: String,
    val models: List<CopilotPrice>,
    val lightweight: List<String>,
    val versatile: List<String>,
    val runtimeHeaders: Map<String, String>,
    val ideHeaders: Map<String, String>,
)

/** A Copilot model's prices by display name, and by ID where the synced manifest has it. */
@Serializable
data class CopilotPrice(val name: String, val id: String? = null, val prices: List<Price>)
```

- [ ] **Step 4: Write the loader and the types that join the format to the catalog**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/catalog/CatalogLoader.kt`:

```kotlin
package dev.kestrel.providers.catalog

import kotlinx.serialization.json.Json

/**
 * Merges `catalog/providers.json` with `catalog/overrides.json` into one [Catalog] at runtime (design D2), so that a
 * change of the overrides needs no new sync. In order: it drops excluded providers and choices; applies the per-model
 * corrections and prices to the synced rows (a row whose compat says `supportsTools: false` gets JSON actions, design D7);
 * completes and adds the providers of the overrides; copies rows with `modelsFrom`, after the prices, so that Vertex's
 * Gemini rows carry Gemini's prices; attaches each choice's settings and adds Kestrel's own choices; and puts Copilot's
 * prices on the `github-copilot` rows.
 */
object CatalogLoader {
    const val COPILOT = "github-copilot"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(providersJson: String, overridesJson: String): Catalog {
        val file = json.decodeFromString(CatalogFile.serializer(), providersJson)
        val overrides = json.decodeFromString(Overrides.serializer(), overridesJson)
        require(file.schema == 1 && overrides.schema == 1) { "Unknown catalog schema" }
        val excluded = overrides.excludeProviders.map { it.id }.toSet()
        val excludedChoices = overrides.excludeAuthChoices.map { it.provider to it.choiceId }.toSet()
        val corrections = overrides.models.associateBy { it.provider to it.model }
        val byId = linkedMapOf<String, ProviderEntry>()

        for (provider in file.providers) {
            if (provider.id in excluded) continue
            byId[provider.id] = provider.copy(
                authChoices = provider.authChoices.filter { (provider.id to it.choiceId) !in excludedChoices },
                models = provider.models.map { correct(it, corrections[provider.id to it.id]) },
            )
        }
        for (change in overrides.providers) {
            if (change.id in excluded) continue
            val base = byId[change.id] ?: ProviderEntry(
                id = change.id,
                plugin = change.plugin ?: change.id,
                label = change.label ?: change.id,
                style = null,
                baseUrl = null,
                hosts = emptyList(),
                authChoices = emptyList(),
                models = emptyList(),
            )
            val added = change.models.map { correct(it, corrections[change.id to it.id]) }
            byId[change.id] = base.copy(
                label = change.label ?: base.label,
                plugin = change.plugin ?: base.plugin,
                style = change.style ?: base.style,
                baseUrl = change.baseUrl ?: base.baseUrl,
                hosts = (base.hosts + change.hosts + listOfNotNull(hostOf(change.baseUrl)?.takeIf { '{' !in it })).distinct(),
                regions = change.regions.ifEmpty { base.regions },
                discovery = change.discovery ?: base.discovery,
                defaultModel = change.defaultModel ?: base.defaultModel,
                utilityModel = change.utilityModel ?: base.utilityModel,
                models = base.models.filter { row -> added.none { it.id == row.id } } + added,
            )
        }
        for (change in overrides.providers) {
            val source = change.modelsFrom?.let { byId[it] } ?: continue
            val target = byId[change.id] ?: continue
            val copied = source.models
                .filter { row -> target.models.none { it.id == row.id } }
                .map { it.copy(style = it.style ?: source.style, baseUrl = null) }
            byId[change.id] = target.copy(models = target.models + copied)
        }
        val settings = overrides.choices.groupBy { it.provider }
        for ((id, provider) in byId.entries.toList()) {
            val own = settings[id].orEmpty()
            val attached = provider.authChoices.map { choice ->
                val match = own.firstOrNull { it.choiceId == choice.choiceId }
                choice.copy(label = match?.label ?: choice.label, settings = match)
            }
            val added = own.filter { s -> provider.authChoices.none { it.choiceId == s.choiceId } }
                .map { AuthChoice(choiceId = it.choiceId, method = KESTREL_METHOD, label = it.label ?: it.choiceId, settings = it) }
            byId[id] = provider.copy(authChoices = attached + added)
        }
        byId[COPILOT]?.let { copilot ->
            byId[COPILOT] = copilot.copy(
                models = copilot.models.map { row -> row.copy(prices = copilotPrice(overrides.copilot, row.id, row.name)?.prices.orEmpty()) },
            )
        }
        return Catalog(
            openclawCommit = file.openclawCommit,
            providers = byId.values.map { it.copy(models = it.models.sortedBy(ModelEntry::id)) }.sortedBy(ProviderEntry::id),
            localServers = overrides.localServers,
            overrides = overrides,
        )
    }

    /** The Copilot price of a model, by its ID first and then by its normalised name (design D12). */
    fun copilotPrice(copilot: CopilotOverrides, modelId: String, modelName: String?): CopilotPrice? =
        copilot.models.firstOrNull { it.id == modelId }
            ?: copilot.models.firstOrNull { entry ->
                val name = normalise(entry.name)
                name == normalise(modelId) || (modelName != null && name == normalise(modelName))
            }

    /** Lower case, with spaces, hyphens and underscores removed: "GPT-6.1 Terra" and "gpt-6.1-terra" both give "gpt6.1terra". */
    fun normalise(name: String): String = name.lowercase().filterNot { it == ' ' || it == '-' || it == '_' }

    /** The host of a URL that may hold `{region}`, which HttpUrl cannot parse. */
    fun hostOf(url: String?): String? =
        url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringBefore(':')?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    private fun correct(model: ModelEntry, change: ModelOverride?): ModelEntry =
        model.copy(
            style = change?.style ?: model.style,
            toolMode = change?.toolMode ?: if (model.compat.supportsTools == false) ToolMode.JSON else model.toolMode,
            prices = change?.prices ?: model.prices,
        )

    private const val KESTREL_METHOD = "kestrel"
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ModelTarget.kt`:

```kotlin
package dev.kestrel.providers.chat

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl

/**
 * Everything an adapter needs to send a request to one model (design D4 and "Shared names"): [baseUrl] holds the style's
 * base, to which the adapter appends its path; [sentModelId] is what goes into the request or the URL (a model ID, an
 * Azure deployment name, or a Bedrock model or inference-profile ID); [headers] holds every header that the adapter does
 * not add itself. The base URL can hold a Vertex project or an Azure resource, and the model ID a Bedrock ARN, so toString
 * prints neither.
 */
data class ModelTarget(
    val providerId: String,
    val style: RequestStyle,
    val baseUrl: HttpUrl,
    val model: ModelEntry,
    val sentModelId: String = model.id,
    val auth: RequestAuth,
    val headers: RequestHeaders = RequestHeaders.NONE,
    val options: StyleOptions = StyleOptions(),
) {
    override fun toString(): String =
        "ModelTarget(provider=$providerId, style=$style, modelIdLength=${sentModelId.length}, options=$options)"
}

/**
 * Per-target switches of the adapters (design D4, D5, D12 and D17): [chatgptPlan] makes the Responses adapter follow the
 * ChatGPT plan's preview limits and map its error codes; [stripThinking], [sanitizeToolCallIds] and [messagesPath] act in
 * the Anthropic adapter (Copilot sets `"/messages"`); [wire] chooses the wire format of a VERTEX or AZURE model (null: the
 * adapter's own rule); [compat] replaces the model's compat flags for Chat Completions (Copilot's); [stream] overrides the
 * streaming rule of design D4 (null: the rule).
 */
data class StyleOptions(
    val chatgptPlan: Boolean = false,
    val stripThinking: Boolean = false,
    val sanitizeToolCallIds: Boolean = false,
    val messagesPath: String = "/v1/messages",
    val wire: RequestStyle? = null,
    val compat: CompatFlags? = null,
    val stream: Boolean? = null,
)

/** The headers of a target that depend on the request, such as Copilot's `x-initiator` (design D12). */
fun interface RequestHeaders {
    fun headers(request: ChatRequest): Map<String, String>

    companion object {
        val NONE: RequestHeaders = RequestHeaders { emptyMap() }
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatAdapter.kt`:

```kotlin
package dev.kestrel.providers.chat

/** Turns a [ChatRequest] into one request style and its answer into one complete [ChatResponse] (spec 10.3). */
interface ChatAdapter {
    suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with 48 tests in `:core:providers` (41 + 7): `CatalogLoaderTest` 6 and `ModelTargetTest` 1.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` 48; `:app` A₀, `:core:privacy` P₀ + 5, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/cost/Price.kt core/providers/src/main/kotlin/dev/kestrel/providers/catalog/Catalog.kt core/providers/src/main/kotlin/dev/kestrel/providers/catalog/Overrides.kt core/providers/src/main/kotlin/dev/kestrel/providers/catalog/CatalogLoader.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/ModelTarget.kt core/providers/src/main/kotlin/dev/kestrel/providers/chat/ChatAdapter.kt core/providers/src/test/kotlin/dev/kestrel/providers/catalog/CatalogLoaderTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/chat/ModelTargetTest.kt
git commit -m "feat(providers): add the catalog model and the loader that merges providers.json with the overrides" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The catalog sync, its script and the generated catalog

**Files:**
- Modify: `settings.gradle.kts` (`include(":tools:catalog-sync")`)
- Create: `tools/catalog-sync/build.gradle.kts`, `tools/catalog-sync/src/main/kotlin/dev/kestrel/catalogsync/CatalogSync.kt`, `Main.kt`
- Create (test resources): `tools/catalog-sync/src/test/resources/openclaw/extensions/groq/openclaw.plugin.json`, `anthropic/openclaw.plugin.json`, `google/openclaw.plugin.json`, `mixed/openclaw.plugin.json`, `pi-gateway/openclaw.plugin.json`, `tools-only/openclaw.plugin.json`, `json5-forms/openclaw.plugin.json`, and `tools/catalog-sync/src/test/resources/expected/providers.json`
- Create: `scripts/sync-catalog.ps1`, `scripts/tests/SyncCatalog.Tests.ps1`, `scripts/tests/fake-git.cmd`, `scripts/tests/fake-git.ps1`
- Create (generated by the script in Step 13): `catalog/providers.json`, `catalog/openclaw.lock`
- Modify: `THIRD_PARTY_NOTICES.md`, `licences/MIT.txt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 7.4, 10.2, 16.2 and 17)
- Test: `tools/catalog-sync/src/test/kotlin/dev/kestrel/catalogsync/CatalogSyncTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/CatalogFilesTest.kt`

**Interfaces:**
- Consumes: Task 4's `CatalogFile`, `ProviderEntry`, `AuthChoice`, `ModelEntry`, `CompatFlags`, `Skipped`, `Price` and `PriceTier`, and Task 1's `RequestStyle`.
- Produces: package `dev.kestrel.catalogsync` of `:tools:catalog-sync`: `object CatalogSync { fun generate(checkout: File, commit: String): String; fun sync(checkout: File, commit: String): SyncResult }` with `class SyncResult(val json: String, val summary: List<String>)`, and `fun main(args: Array<String>)` (`<checkout> <commit> <providers.json>`); the Gradle task `:tools:catalog-sync:syncCatalog` (`-Pcheckout=<absolute folder> -Pcommit=<sha>`), which writes the repository's `catalog/providers.json`; `scripts\sync-catalog.ps1 [-Commit <sha>]`; the files `catalog/providers.json` (72 providers and 282 models at the pinned commit) and `catalog/openclaw.lock`. Task 6's overrides and every later task's tests read these files.

Design D2. The generator reads every `extensions/<plugin>/openclaw.plugin.json` of the folder that it is given, so its tests use fixture manifests instead of the network: the `groq`, `anthropic` and `google` excerpts of research r26 and small made-up manifests, one for each rule. It parses JSON5 with kotlinx-serialization's lenient settings with comments and trailing commas. The writer of this plan read all 186 manifests at the pinned commit: every one is plain JSON, so no pre-pass is needed, and the fixture `json5-forms` checks the settings. A manifest that does not parse stops the sync with its path, and so do two manifests that define the same provider ID, because `CatalogLoader` would keep only the last of them. The sync keeps the plugins whose `categories` hold `"models"` (68 at the pinned commit) and writes one entry per provider ID of `providers` that has a `modelCatalog.providers.<id>` entry or auth choices. A provider's label is its first auth choice's `groupLabel`, else the manifest's `name`, else its ID; its hosts are its base URL's host and its models' base URL hosts, never those of `providerEndpoints`, which belong to the plugin (D2). A cost whose input and output are both 0 becomes no price, because OpenClaw writes zeros where it has no price; a tier whose `range` starts at `a > 0` applies above `a − 1` input tokens. The sync leaves out, in `skipped`, a provider or a model with an `api` that Kestrel does not support (`pi-messages`, `google-interactions`), a model with `status: "disabled"`, and a decision model, which OpenClaw marks with `inference.chat: false`. It reads no overrides. The output is sorted by provider and model, pretty-printed and ends with a line feed, so that a new sync gives a readable diff. `scripts\sync-catalog.ps1` makes the sparse checkout in `build\openclaw\<commit>` (a second run reuses it), runs the Gradle task, which prints the counts, and only then writes the lock: one line with the hash and a line feed, in UTF-8 without a byte order mark, also in Windows PowerShell 5.1. Its tests use `fake-git.cmd` and a fake Gradle script in the test's folder. A run that cannot set up the repository (`git init` or `git remote add` fails) removes the half-made `.git` folder, so that the next run starts again; if `git remote set-url` fails, the error names the folder to delete. `CatalogFilesTest` is spec 17's check that `providers.json` matches `openclaw.lock`; it runs with `check`, without network.

- [ ] **Step 1: Add the module**

In `settings.gradle.kts`, replace

```kotlin
include(":testapp")
```

with

```kotlin
include(":testapp")
include(":tools:catalog-sync")
```

Create the file `tools/catalog-sync/build.gradle.kts`:

```kotlin
plugins {
    id("kestrel.jvm.library")
}

dependencies {
    implementation(project(":core:providers"))
    testImplementation(libs.junit)
}

// Writes catalog/providers.json from the OpenClaw checkout that scripts/sync-catalog.ps1 makes (design spec, section 10.2):
// scripts\gradle.ps1 :tools:catalog-sync:syncCatalog -Pcheckout=<absolute folder> -Pcommit=<40-character hash>
tasks.register<JavaExec>("syncCatalog") {
    group = "kestrel"
    description = "Writes catalog/providers.json from an OpenClaw checkout."
    classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("dev.kestrel.catalogsync.MainKt")
    // Local values, so that the configuration cache stores the arguments and not this script.
    val checkout = project.providers.gradleProperty("checkout")
    val commit = project.providers.gradleProperty("commit")
    val catalogFile = project.rootProject.layout.projectDirectory.file("catalog/providers.json").asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider { listOf(checkout.get(), commit.get(), catalogFile) })
}
```

- [ ] **Step 2: Write the fixture manifests and the snapshot**

Create the file `tools/catalog-sync/src/test/resources/openclaw/extensions/groq/openclaw.plugin.json`:

```json
{
  "id": "groq",
  "categories": ["models"],
  "activation": {
    "onStartup": false
  },
  "enabledByDefault": true,
  "providerCatalogEntry": "./provider-discovery.ts",
  "providers": ["groq"],
  "providerEndpoints": [
    {
      "endpointClass": "groq-native",
      "hosts": ["api.groq.com"]
    }
  ],
  "setup": {
    "providers": [
      {
        "id": "groq",
        "authMethods": ["api-key"],
        "envVars": ["GROQ_API_KEY"]
      }
    ]
  },
  "providerAuthChoices": [
    {
      "provider": "groq",
      "method": "api-key",
      "choiceId": "groq-api-key",
      "appGuidedSecret": true,
      "choiceLabel": "Groq API key",
      "choiceHint": "Fast OpenAI-compatible inference",
      "groupId": "groq",
      "groupLabel": "Groq",
      "optionKey": "groqApiKey"
    }
  ],
  "modelCatalog": {
    "modelsDev": {
      "groq": "groq"
    },
    "providers": {
      "groq": {
        "baseUrl": "https://api.groq.com/openai/v1",
        "api": "openai-completions",
        "defaultModel": "openai/gpt-oss-120b",
        "models": [
          {
            "id": "openai/gpt-oss-120b",
            "name": "GPT OSS 120B",
            "reasoning": true,
            "input": ["text"],
            "contextWindow": 131072,
            "maxTokens": 65536,
            "cost": {
              "input": 0.15,
              "output": 0.6,
              "cacheRead": 0.075,
              "cacheWrite": 0
            },
            "compat": {
              "supportedReasoningEfforts": ["low", "medium", "high"]
            }
          }
        ]
      }
    },
    "discovery": {
      "groq": "refreshable"
    }
  },
  "configSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {}
  }
}
```

Create the file `tools/catalog-sync/src/test/resources/openclaw/extensions/anthropic/openclaw.plugin.json`:

```json
{
  "id": "anthropic",
  "categories": ["models"],
  "name": "Anthropic",
  "description": "Anthropic models, Claude CLI, and native Claude session catalog.",
  "enabledByDefault": true,
  "providers": ["anthropic"],
  "modelCatalog": {
    "modelsDev": { "anthropic": "anthropic" },
    "providers": {
      "claude-cli": { "models": [] },
      "anthropic": {
        "baseUrl": "https://api.anthropic.com",
        "api": "anthropic-messages",
        "defaultUtilityModel": "claude-haiku-5-5",
        "family": "anthropic",
        "models": [
          {
            "id": "claude-haiku-5-5",
            "name": "Claude Haiku 5.5",
            "reasoning": true,
            "input": ["text", "image"],
            "mediaInput": {
              "image": { "maxSidePx": 2576, "preferredSidePx": 2576, "tokenMode": "provider" }
            },
            "cost": {
              "input": 0.1, "output": 0.5, "cacheRead": 0.01, "cacheWrite": 0.125,
              "tieredPricing": [
                { "range": [0, 100001], "input": 0.1, "output": 0.5, "cacheRead": 0.01, "cacheWrite": 0.125 },
                { "range": [100001], "input": 0.5, "output": 2.5, "cacheRead": 0.05, "cacheWrite": 0.625 }
              ]
            },
            "contextWindow": 1000000,
            "maxTokens": 128000,
            "thinkingLevelMap": { "minimal": "low", "xhigh": "xhigh", "max": "max" },
            "compat": { "codeMode": "capable" }
          },
          {
            "id": "claude-opus-4-8",
            "name": "Claude Opus 4.8",
            "status": "deprecated",
            "replacedBy": "claude-opus-5",
            "input": ["text", "image"]
          }
        ]
      }
    },
    "discovery": { "claude-cli": "refreshable", "anthropic": "refreshable" }
  },
  "providerEndpoints": [ { "endpointClass": "anthropic-public", "hosts": ["api.anthropic.com"] } ],
  "providerAuthChoices": [
    { "provider": "anthropic", "method": "cli", "choiceId": "anthropic-cli", "choiceLabel": "Anthropic Claude CLI",
      "choiceHint": "Keep using an existing Claude Code CLI login on this host", "groupId": "anthropic", "groupLabel": "Anthropic" },
    { "provider": "anthropic", "method": "setup-token", "choiceId": "setup-token", "choiceLabel": "Anthropic setup-token",
      "choiceHint": "Token created by running 'claude setup-token' in your terminal", "groupId": "anthropic", "groupLabel": "Anthropic" },
    { "provider": "anthropic", "method": "api-key", "choiceId": "apiKey", "choiceLabel": "Anthropic API key",
      "groupId": "anthropic", "groupLabel": "Anthropic" }
  ],
  "configSchema": { "type": "object", "additionalProperties": false, "properties": {} }
}
```

Create the file `tools/catalog-sync/src/test/resources/openclaw/extensions/google/openclaw.plugin.json`:

```json
{
  "id": "google",
  "categories": ["models"],
  "enabledByDefault": true,
  "providers": ["google", "google-gemini-cli", "google-vertex"],
  "modelCatalog": {
    "modelsDev": { "google": "google" },
    "discovery": { "google": "runtime" },
    "providers": {
      "google": {
        "api": "google-generative-ai",
        "baseUrl": "https://generativelanguage.googleapis.com/v1beta",
        "family": "google",
        "models": [
          { "id": "gemini-4-argon", "name": "Gemini 4 Argon", "reasoning": true,
            "input": ["text", "image"],
            "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
            "contextWindow": 1048576, "maxTokens": 65536,
            "thinkingLevelMap": { "minimal": null },
            "compat": { "codeMode": "preferred" } },
          { "id": "gemini-decision-1", "name": "Gemini Decision 1",
            "inference": { "chat": false, "decision": { "protocol": "gemini-decisions" } } }
        ]
      }
    }
  },
  "providerEndpoints": [
    { "endpointClass": "google-generative-ai", "hosts": ["generativelanguage.googleapis.com"] },
    { "endpointClass": "google-vertex", "hosts": ["aiplatform.googleapis.com"], "googleVertexRegion": "global" },
    { "endpointClass": "google-vertex", "hosts": ["aiplatform.eu.rep.googleapis.com"], "googleVertexRegion": "eu" }
  ],
  "providerAuthChoices": [
    { "provider": "google", "method": "api-key", "choiceId": "gemini-api-key",
      "choiceLabel": "Google AI Studio API key", "choiceHint": "Supported API-key access from aistudio.google.com/apikey",
      "groupId": "google", "groupLabel": "Google" }
  ],
  "configSchema": { "type": "object", "additionalProperties": false, "properties": {} }
}
```

Create the file `tools/catalog-sync/src/test/resources/openclaw/extensions/mixed/openclaw.plugin.json`:

```json
{
  "id": "mixed",
  "categories": ["models"],
  "name": "Mixed Cloud",
  "providers": ["mixed", "mixed-code", "mixed-alias"],
  "modelCatalog": {
    "providers": {
      "mixed": {
        "baseUrl": "https://api.mixed.test/v1",
        "api": "openai-completions",
        "headers": { "X-Client": "openclaw" },
        "defaultModel": "mixed-large",
        "defaultUtilityModel": "mixed-small",
        "models": [
          { "id": "mixed-small", "name": "Mixed Small", "input": ["text", "video"], "status": "preview",
            "cost": { "input": 0.2, "output": 0.8 },
            "compat": { "maxTokensField": "max_tokens", "supportsTools": false, "thinkingLevels": ["off", "low"], "defaultThinkingLevel": "low",
                        "zaiToolStream": true } },
          { "id": "mixed-large", "name": "Mixed Large", "api": "anthropic-messages", "baseUrl": "https://anthropic.mixed.test",
            "reasoning": true, "input": ["text", "image"], "thinkingLevelMap": { "off": null, "high": "max" } },
          { "id": "mixed-live", "name": "Mixed Live", "api": "google-interactions" },
          { "id": "mixed-old", "name": "Mixed Old", "status": "disabled" }
        ]
      }
    },
    "discovery": { "mixed": "static", "mixed-code": "runtime" }
  },
  "providerAuthChoices": [
    { "provider": "mixed", "method": "api-key", "choiceId": "mixed-key", "choiceLabel": "Mixed API key" },
    { "provider": "mixed-code", "method": "device-code", "choiceId": "mixed-code-device", "choiceLabel": "Mixed Code sign-in",
      "groupLabel": "Mixed Code" }
  ],
  "configSchema": { "type": "object" }
}
```

Create the file `tools/catalog-sync/src/test/resources/openclaw/extensions/pi-gateway/openclaw.plugin.json`:

```json
{
  "id": "pi-gateway",
  "categories": ["models"],
  "providers": ["pi-gateway"],
  "modelCatalog": {
    "providers": {
      "pi-gateway": {
        "baseUrl": "https://gateway.pi.test",
        "api": "pi-messages",
        "models": [{ "id": "pi-1", "name": "Pi 1" }]
      }
    }
  },
  "providerAuthChoices": [
    { "provider": "pi-gateway", "method": "oauth", "choiceId": "pi-gateway", "choiceLabel": "Pi gateway" }
  ],
  "configSchema": { "type": "object" }
}
```

Create the file `tools/catalog-sync/src/test/resources/openclaw/extensions/tools-only/openclaw.plugin.json`:

```json
{
  "id": "tools-only",
  "categories": ["developer-tools"],
  "providers": ["tools-only"],
  "providerAuthChoices": [
    { "provider": "tools-only", "method": "api-key", "choiceId": "tools-only-key", "choiceLabel": "Tools key" }
  ],
  "configSchema": { "type": "object" }
}
```

Create the file `tools/catalog-sync/src/test/resources/openclaw/extensions/json5-forms/openclaw.plugin.json`:

```json
// OpenClaw reads its manifests as JSON5, so the sync accepts comments and trailing commas.
{
  "id": "json5-forms",
  "categories": ["models",],
  "providers": ["json5-forms"],
  "modelCatalog": {
    "providers": {
      "json5-forms": {
        "baseUrl": "https://ollama.json5.test",
        "api": "ollama", // Ollama's native API
        "models": [
          { "id": "llama-9", "name": "Llama 9", "cost": { "input": 0, "output": 0 }, },
        ],
      },
    },
  },
  "providerAuthChoices": [
    { "provider": "json5-forms", "method": "api-key", "choiceId": "json5-key", "choiceLabel": "JSON5 key", },
  ],
  "configSchema": { "type": "object" },
}
```

Create the file `tools/catalog-sync/src/test/resources/expected/providers.json`:

```json
{
    "schema": 1,
    "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143",
    "providers": [
        {
            "id": "anthropic",
            "plugin": "anthropic",
            "label": "Anthropic",
            "style": "ANTHROPIC",
            "baseUrl": "https://api.anthropic.com",
            "hosts": [
                "api.anthropic.com"
            ],
            "utilityModel": "claude-haiku-5-5",
            "discovery": "REFRESHABLE",
            "authChoices": [
                {
                    "choiceId": "anthropic-cli",
                    "method": "cli",
                    "label": "Anthropic Claude CLI",
                    "hint": "Keep using an existing Claude Code CLI login on this host"
                },
                {
                    "choiceId": "setup-token",
                    "method": "setup-token",
                    "label": "Anthropic setup-token",
                    "hint": "Token created by running 'claude setup-token' in your terminal"
                },
                {
                    "choiceId": "apiKey",
                    "method": "api-key",
                    "label": "Anthropic API key"
                }
            ],
            "models": [
                {
                    "id": "claude-haiku-5-5",
                    "name": "Claude Haiku 5.5",
                    "input": [
                        "TEXT",
                        "IMAGE"
                    ],
                    "reasoning": true,
                    "contextWindow": 1000000,
                    "maxTokens": 128000,
                    "prices": [
                        {
                            "input": 0.1,
                            "output": 0.5,
                            "cacheRead": 0.01,
                            "cacheWrite": 0.125,
                            "tiers": [
                                {
                                    "aboveInputTokens": 100000,
                                    "input": 0.5,
                                    "output": 2.5,
                                    "cacheRead": 0.05,
                                    "cacheWrite": 0.625
                                }
                            ]
                        }
                    ],
                    "thinkingLevelMap": {
                        "minimal": "low",
                        "xhigh": "xhigh",
                        "max": "max"
                    }
                },
                {
                    "id": "claude-opus-4-8",
                    "name": "Claude Opus 4.8",
                    "input": [
                        "TEXT",
                        "IMAGE"
                    ],
                    "status": "DEPRECATED"
                }
            ]
        },
        {
            "id": "google",
            "plugin": "google",
            "label": "Google",
            "style": "GEMINI",
            "baseUrl": "https://generativelanguage.googleapis.com/v1beta",
            "hosts": [
                "generativelanguage.googleapis.com"
            ],
            "discovery": "RUNTIME",
            "authChoices": [
                {
                    "choiceId": "gemini-api-key",
                    "method": "api-key",
                    "label": "Google AI Studio API key",
                    "hint": "Supported API-key access from aistudio.google.com/apikey"
                }
            ],
            "models": [
                {
                    "id": "gemini-4-argon",
                    "name": "Gemini 4 Argon",
                    "input": [
                        "TEXT",
                        "IMAGE"
                    ],
                    "reasoning": true,
                    "contextWindow": 1048576,
                    "maxTokens": 65536,
                    "thinkingLevelMap": {
                        "minimal": null
                    }
                }
            ]
        },
        {
            "id": "groq",
            "plugin": "groq",
            "label": "Groq",
            "style": "CHAT_COMPLETIONS",
            "baseUrl": "https://api.groq.com/openai/v1",
            "hosts": [
                "api.groq.com"
            ],
            "defaultModel": "openai/gpt-oss-120b",
            "discovery": "REFRESHABLE",
            "authChoices": [
                {
                    "choiceId": "groq-api-key",
                    "method": "api-key",
                    "label": "Groq API key",
                    "hint": "Fast OpenAI-compatible inference"
                }
            ],
            "models": [
                {
                    "id": "openai/gpt-oss-120b",
                    "name": "GPT OSS 120B",
                    "reasoning": true,
                    "contextWindow": 131072,
                    "maxTokens": 65536,
                    "prices": [
                        {
                            "input": 0.15,
                            "output": 0.6,
                            "cacheRead": 0.075,
                            "cacheWrite": 0.0
                        }
                    ],
                    "compat": {
                        "supportedReasoningEfforts": [
                            "low",
                            "medium",
                            "high"
                        ]
                    }
                }
            ]
        },
        {
            "id": "json5-forms",
            "plugin": "json5-forms",
            "label": "json5-forms",
            "style": "OLLAMA",
            "baseUrl": "https://ollama.json5.test",
            "hosts": [
                "ollama.json5.test"
            ],
            "discovery": "STATIC",
            "authChoices": [
                {
                    "choiceId": "json5-key",
                    "method": "api-key",
                    "label": "JSON5 key"
                }
            ],
            "models": [
                {
                    "id": "llama-9",
                    "name": "Llama 9"
                }
            ]
        },
        {
            "id": "mixed",
            "plugin": "mixed",
            "label": "Mixed Cloud",
            "style": "CHAT_COMPLETIONS",
            "baseUrl": "https://api.mixed.test/v1",
            "hosts": [
                "api.mixed.test",
                "anthropic.mixed.test"
            ],
            "headers": {
                "X-Client": "openclaw"
            },
            "defaultModel": "mixed-large",
            "utilityModel": "mixed-small",
            "discovery": "STATIC",
            "authChoices": [
                {
                    "choiceId": "mixed-key",
                    "method": "api-key",
                    "label": "Mixed API key"
                }
            ],
            "models": [
                {
                    "id": "mixed-large",
                    "name": "Mixed Large",
                    "style": "ANTHROPIC",
                    "baseUrl": "https://anthropic.mixed.test",
                    "input": [
                        "TEXT",
                        "IMAGE"
                    ],
                    "reasoning": true,
                    "thinkingLevelMap": {
                        "off": null,
                        "high": "max"
                    }
                },
                {
                    "id": "mixed-small",
                    "name": "Mixed Small",
                    "prices": [
                        {
                            "input": 0.2,
                            "output": 0.8
                        }
                    ],
                    "compat": {
                        "maxTokensField": "max_tokens",
                        "supportsTools": false,
                        "thinkingLevels": [
                            "off",
                            "low"
                        ],
                        "defaultThinkingLevel": "low"
                    },
                    "status": "PREVIEW"
                }
            ]
        },
        {
            "id": "mixed-code",
            "plugin": "mixed",
            "label": "Mixed Code",
            "style": null,
            "baseUrl": null,
            "hosts": [],
            "discovery": "RUNTIME",
            "authChoices": [
                {
                    "choiceId": "mixed-code-device",
                    "method": "device-code",
                    "label": "Mixed Code sign-in"
                }
            ],
            "models": []
        }
    ],
    "skipped": [
        {
            "plugin": "google",
            "provider": "google",
            "model": "gemini-decision-1",
            "reason": "not a chat model"
        },
        {
            "plugin": "mixed",
            "provider": "mixed",
            "model": "mixed-live",
            "reason": "unsupported style google-interactions"
        },
        {
            "plugin": "mixed",
            "provider": "mixed",
            "model": "mixed-old",
            "reason": "disabled"
        },
        {
            "plugin": "pi-gateway",
            "provider": "pi-gateway",
            "reason": "unsupported style pi-messages"
        }
    ]
}
```

- [ ] **Step 3: Write the failing tests**

Create the file `tools/catalog-sync/src/test/kotlin/dev/kestrel/catalogsync/CatalogSyncTest.kt`:

```kotlin
package dev.kestrel.catalogsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogSyncTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val commit = "e6113549fe8800b4c736c5e8a131b47f2e015143"
    private val checkout = File(javaClass.getResource("/openclaw/extensions")!!.toURI()).parentFile

    @Test
    fun theFixtureManifestsGiveTheSnapshot() {
        val expected = javaClass.getResource("/expected/providers.json")!!.readText()

        assertEquals(expected, CatalogSync.generate(checkout, commit))
    }

    @Test
    fun theSummaryCountsWhatTheSyncRead() {
        assertEquals(
            listOf(
                "Manifests read: 7",
                "Models plugins: 6",
                "Providers: 6",
                "Models: 7",
                "Providers per style: ANTHROPIC 1, CHAT_COMPLETIONS 2, GEMINI 1, OLLAMA 1, none 1",
                "Models whose all-zero cost became no price: 2",
                "Skipped: 4",
            ),
            CatalogSync.sync(checkout, commit).summary,
        )
    }

    @Test
    fun providerEndpointsAreNotRead() {
        val json = CatalogSync.generate(checkout, commit)

        assertFalse(json.contains("aiplatform"))
        assertFalse(json.contains("endpointClass"))
        assertTrue(json.contains("\"generativelanguage.googleapis.com\""))
    }

    @Test
    fun aManifestThatDoesNotParseStopsTheSyncWithItsPath() {
        val broken = temporary.newFolder("openclaw", "extensions", "broken")
        File(broken, "openclaw.plugin.json").writeText("""{"id": "broken", "categories": ["models"] "providers": []}""")

        val error = assertThrows(IllegalStateException::class.java) { CatalogSync.generate(broken.parentFile.parentFile, commit) }

        assertEquals("Cannot parse extensions/broken/openclaw.plugin.json", error.message)
    }

    @Test
    fun twoManifestsThatDefineOneProviderStopTheSync() {
        val groq = File(checkout, "extensions/groq/openclaw.plugin.json")
        for (folder in listOf("groq", "groq-copy")) {
            groq.copyTo(File(temporary.newFolder("openclaw", "extensions", folder), "openclaw.plugin.json"))
        }

        val error = assertThrows(IllegalStateException::class.java) { CatalogSync.generate(temporary.root.resolve("openclaw"), commit) }

        assertEquals("Two manifests define the provider groq", error.message)
    }

    @Test
    fun aShortCommitOrAFolderWithoutManifestsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { CatalogSync.generate(checkout, "e6113549") }
        assertThrows(IllegalArgumentException::class.java) { CatalogSync.generate(temporary.newFolder("empty"), commit) }
    }
}
```

- [ ] **Step 4: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :tools:catalog-sync:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'CatalogSync'".

- [ ] **Step 5: Write the generator**

Create the file `tools/catalog-sync/src/main/kotlin/dev/kestrel/catalogsync/CatalogSync.kt`:

```kotlin
package dev.kestrel.catalogsync

import dev.kestrel.providers.catalog.CatalogFile
import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ModelStatus
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.catalog.Skipped
import dev.kestrel.providers.catalog.AuthChoice
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.cost.PriceTier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/** What one run of the sync produced: the JSON of `catalog/providers.json` and the counts that the script prints. */
class SyncResult(val json: String, val summary: List<String>)

/**
 * Generates `catalog/providers.json` from a checkout of OpenClaw (spec 10.2, design D2). It reads every
 * `extensions/<plugin>/openclaw.plugin.json` of [checkout] as JSON5, keeps the plugins whose `categories` hold "models",
 * and writes one entry per provider ID of `providers` that has a `modelCatalog.providers.<id>` entry or auth choices. It
 * reads no overrides and no `providerEndpoints`, so its result depends only on the checkout.
 */
object CatalogSync {
    private val COMMIT = Regex("^[0-9a-f]{40}$")

    /** OpenClaw's `api` values that Kestrel's eight styles cover (design D4); `pi-messages` and `google-interactions` are not. */
    private val STYLES = mapOf(
        "openai-completions" to RequestStyle.CHAT_COMPLETIONS,
        "openai-responses" to RequestStyle.RESPONSES,
        "openai-chatgpt-responses" to RequestStyle.RESPONSES,
        "github-copilot" to RequestStyle.RESPONSES,
        "anthropic-messages" to RequestStyle.ANTHROPIC,
        "google-generative-ai" to RequestStyle.GEMINI,
        "ollama" to RequestStyle.OLLAMA,
        "bedrock-converse-stream" to RequestStyle.BEDROCK,
        "google-vertex" to RequestStyle.VERTEX,
        "azure-openai-responses" to RequestStyle.AZURE,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val manifestJson = Json {
        isLenient = true
        allowComments = true
        allowTrailingComma = true
        ignoreUnknownKeys = true
    }

    private val outputJson = Json { prettyPrint = true }

    fun generate(checkout: File, commit: String): String = sync(checkout, commit).json

    fun sync(checkout: File, commit: String): SyncResult {
        require(COMMIT.matches(commit)) { "The commit must be a 40-character hash" }
        val manifests = checkout.resolve("extensions").listFiles().orEmpty()
            .map { it.resolve("openclaw.plugin.json") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        require(manifests.isNotEmpty()) { "No manifests in ${checkout.resolve("extensions")}" }
        val providers = mutableListOf<ProviderEntry>()
        val skipped = mutableListOf<Skipped>()
        var modelsPlugins = 0
        var noPrice = 0
        for (file in manifests) {
            val manifest = parse(file)
            if (manifest.strings("categories").none { it == "models" }) continue
            modelsPlugins++
            val result = read(manifest, file.parentFile.name)
            providers += result.providers
            skipped += result.skipped
            noPrice += result.noPrice
        }
        // CatalogLoader would keep only the last of two entries with one ID, so a duplicate stops the sync instead.
        val duplicate = providers.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()
        check(duplicate == null) { "Two manifests define the provider $duplicate" }
        val catalog = CatalogFile(
            openclawCommit = commit,
            providers = providers.sortedBy { it.id }.map { it.copy(models = it.models.sortedBy(ModelEntry::id)) },
            skipped = skipped.sortedWith(compareBy({ it.plugin }, { it.provider.orEmpty() }, { it.model.orEmpty() })),
        )
        val json = outputJson.encodeToString(CatalogFile.serializer(), catalog) + "\n"
        val perStyle = catalog.providers.groupingBy { it.style?.name ?: "none" }.eachCount().toSortedMap()
        val summary = listOf(
            "Manifests read: ${manifests.size}",
            "Models plugins: $modelsPlugins",
            "Providers: ${catalog.providers.size}",
            "Models: ${catalog.providers.sumOf { it.models.size }}",
            "Providers per style: " + perStyle.entries.joinToString(", ") { "${it.key} ${it.value}" },
            "Models whose all-zero cost became no price: $noPrice",
            "Skipped: ${catalog.skipped.size}",
        )
        return SyncResult(json, summary)
    }

    private class Read(val providers: List<ProviderEntry>, val skipped: List<Skipped>, val noPrice: Int)

    private fun parse(file: File): JsonObject =
        try {
            manifestJson.parseToJsonElement(file.readText()) as? JsonObject
                ?: throw IllegalStateException("extensions/${file.parentFile.name}/${file.name} is not a JSON object")
        } catch (e: SerializationException) {
            throw IllegalStateException("Cannot parse extensions/${file.parentFile.name}/${file.name}", e)
        }

    private fun read(manifest: JsonObject, folder: String): Read {
        val plugin = manifest.string("id") ?: folder
        val catalog = manifest.obj("modelCatalog")
        val entries = catalog?.obj("providers")
        val discovery = catalog?.obj("discovery")
        val choices = manifest.array("providerAuthChoices").filterIsInstance<JsonObject>()
        val providers = mutableListOf<ProviderEntry>()
        val skipped = mutableListOf<Skipped>()
        var noPrice = 0
        for (id in manifest.strings("providers")) {
            val entry = entries?.obj(id)
            val own = choices.filter { it.string("provider") == id }
            if (entry == null && own.isEmpty()) continue
            val api = entry?.string("api")
            val style = api?.let { STYLES[it] }
            if (api != null && style == null) {
                skipped += Skipped(plugin, id, reason = "unsupported style $api")
                continue
            }
            val models = mutableListOf<ModelEntry>()
            for (row in entry?.array("models").orEmpty().filterIsInstance<JsonObject>()) {
                val modelId = row.string("id") ?: continue
                val rowApi = row.string("api")
                val reason = when {
                    row.obj("inference")?.boolean("chat") == false -> "not a chat model"
                    row.string("status") == "disabled" -> "disabled"
                    rowApi != null && rowApi !in STYLES -> "unsupported style $rowApi"
                    else -> null
                }
                if (reason != null) {
                    skipped += Skipped(plugin, id, modelId, reason)
                    continue
                }
                val cost = row.obj("cost")
                val prices = prices(cost)
                if (cost != null && prices.isEmpty()) noPrice++
                models += ModelEntry(
                    id = modelId,
                    name = row.string("name") ?: modelId,
                    style = rowApi?.let { STYLES.getValue(it) },
                    baseUrl = row.string("baseUrl"),
                    input = inputKinds(row.strings("input")),
                    reasoning = row.boolean("reasoning") ?: false,
                    contextWindow = row.int("contextWindow"),
                    maxTokens = row.int("maxTokens"),
                    prices = prices,
                    compat = row.obj("compat")?.let { manifestJson.decodeFromJsonElement(CompatFlags.serializer(), it) } ?: CompatFlags(),
                    thinkingLevelMap = row.obj("thinkingLevelMap")?.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content }
                        .orEmpty(),
                    status = when (row.string("status")) {
                        "preview" -> ModelStatus.PREVIEW
                        "deprecated" -> ModelStatus.DEPRECATED
                        else -> ModelStatus.AVAILABLE
                    },
                )
            }
            val baseUrl = entry?.string("baseUrl")
            providers += ProviderEntry(
                id = id,
                plugin = plugin,
                label = own.firstOrNull()?.string("groupLabel") ?: manifest.string("name") ?: id,
                style = style,
                baseUrl = baseUrl,
                hosts = (listOf(baseUrl) + models.map { it.baseUrl }).mapNotNull(::hostOf).distinct(),
                headers = entry?.obj("headers")?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }?.toMap()
                    .orEmpty(),
                defaultModel = entry?.string("defaultModel"),
                utilityModel = entry?.string("defaultUtilityModel"),
                discovery = when (discovery?.string(id)) {
                    "refreshable" -> Discovery.REFRESHABLE
                    "runtime" -> Discovery.RUNTIME
                    else -> Discovery.STATIC
                },
                authChoices = own.mapNotNull { choice ->
                    val choiceId = choice.string("choiceId") ?: return@mapNotNull null
                    AuthChoice(
                        choiceId = choiceId,
                        method = choice.string("method") ?: "",
                        label = choice.string("choiceLabel") ?: choiceId,
                        hint = choice.string("choiceHint"),
                    )
                },
                models = models,
            )
        }
        return Read(providers, skipped, noPrice)
    }

    /** OpenClaw writes all-zero costs where it has no price (research r26, r28), so those become no price at all. */
    private fun prices(cost: JsonObject?): List<Price> {
        if (cost == null) return emptyList()
        val input = cost.double("input") ?: 0.0
        val output = cost.double("output") ?: 0.0
        if (input == 0.0 && output == 0.0) return emptyList()
        // A tier's range [a, b) that starts at a > 0 applies above a - 1 input tokens; the tier from 0 is the base price.
        val tiers = cost.array("tieredPricing").filterIsInstance<JsonObject>().mapNotNull { tier ->
            val from = (tier.array("range").firstOrNull() as? JsonPrimitive)?.doubleOrNull?.toLong() ?: return@mapNotNull null
            if (from <= 0) return@mapNotNull null
            PriceTier(
                aboveInputTokens = from - 1,
                input = tier.double("input") ?: input,
                output = tier.double("output") ?: output,
                cacheRead = tier.double("cacheRead"),
                cacheWrite = tier.double("cacheWrite"),
            )
        }
        return listOf(Price(input, output, cost.double("cacheRead"), cost.double("cacheWrite"), tiers))
    }

    private fun inputKinds(values: List<String>): Set<InputKind> {
        val kinds = values.mapNotNull {
            when (it) {
                "text" -> InputKind.TEXT
                "image" -> InputKind.IMAGE
                else -> null
            }
        }.toSet()
        return kinds.ifEmpty { setOf(InputKind.TEXT) }
    }

    private fun hostOf(url: String?): String? =
        url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringBefore(':')?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strings(key: String): List<String> =
        array(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
```

Create the file `tools/catalog-sync/src/main/kotlin/dev/kestrel/catalogsync/Main.kt`:

```kotlin
package dev.kestrel.catalogsync

import java.io.File

/**
 * Runs the sync: `<checkout> <commit> <output>`. The Gradle task `syncCatalog` passes the checkout that
 * `scripts/sync-catalog.ps1` made, the commit, and the repository's `catalog/providers.json`.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: <OpenClaw checkout> <commit> <providers.json to write>" }
    val result = CatalogSync.sync(File(args[0]), args[1])
    val output = File(args[2])
    output.parentFile.mkdirs()
    output.writeText(result.json)
    println("Wrote $output")
    result.summary.forEach(::println)
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :tools:catalog-sync:test`
Expected: `BUILD SUCCESSFUL`, with 6 tests in `:tools:catalog-sync` (`CatalogSyncTest` 6).

- [ ] **Step 7: Write the script's failing tests**

Create `scripts/tests/fake-git.cmd`:

```bat
@echo off
rem A stand-in for git.exe in the tests of scripts\sync-catalog.ps1. It runs fake-git.ps1 in a process of its own, as git
rem runs, so that its error lines reach PowerShell as the error output of a native program.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-git.ps1" %*
exit /b %ERRORLEVEL%
```

Create `scripts/tests/fake-git.ps1`:

```powershell
# A stand-in for git in the tests of scripts\sync-catalog.ps1; fake-git.cmd runs it in its own process, as git runs. It
# understands 'git -C <folder> <command> ...' and keeps its state in the JSON file whose path is in the environment variable
# KESTREL_FAKE_GIT:
#   fail   the command that fails with exit code 128 and an error line, as git fails; empty for none
#   calls  every call's arguments after the folder, joined with spaces, in order
# 'init' makes the folder's .git folder, so that a second run sees a repository, and 'fetch' writes a progress line to
# standard error, as git does.
$statePath = $env:KESTREL_FAKE_GIT
if (-not $statePath -or -not (Test-Path -LiteralPath $statePath)) {
    [Console]::Error.WriteLine('fake git: KESTREL_FAKE_GIT must name an existing state file')
    exit 1
}
if ($args.Count -lt 3 -or $args[0] -ne '-C') {
    [Console]::Error.WriteLine("fake git: unsupported arguments: $($args -join ' ')")
    exit 1
}
$folder = $args[1]
$command = $args[2]
$state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
$calls = @(@($state.calls) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })
$calls += ($args[2..($args.Count - 1)] -join ' ')
$json = ConvertTo-Json -InputObject ([ordered]@{ fail = [string]$state.fail; calls = [string[]]$calls })
[IO.File]::WriteAllText($statePath, $json)
if ($command -eq [string]$state.fail) {
    [Console]::Error.WriteLine("fatal: $command failed")
    exit 128
}
if ($command -eq 'init') { New-Item -ItemType Directory -Force -Path (Join-Path $folder '.git') | Out-Null }
if ($command -eq 'fetch') { [Console]::Error.WriteLine('From https://github.com/openclaw/openclaw') }
exit 0
```

Create `scripts/tests/SyncCatalog.Tests.ps1`:

```powershell
# Tests of scripts\sync-catalog.ps1. git is fake-git.cmd and Gradle is a fake script in the test's folder, so the tests
# need no network and no build. scripts\test-scripts.ps1 runs them in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'sync-catalog.ps1' {
    BeforeAll {
        $sync = Join-Path $PSScriptRoot '..\sync-catalog.ps1'
        $fakeGit = Join-Path $PSScriptRoot 'fake-git.cmd'
        $commit = 'e6113549fe8800b4c736c5e8a131b47f2e015143'
        $repository = 'https://github.com/openclaw/openclaw.git'

        # Makes the fake git fail at the command -Fail, and forgets its calls.
        function Set-FakeGit([string]$Fail = '') {
            [IO.File]::WriteAllText($env:KESTREL_FAKE_GIT, (ConvertTo-Json -InputObject ([ordered]@{ fail = $Fail; calls = @() })))
        }

        function Get-GitCall {
            @(([IO.File]::ReadAllText($env:KESTREL_FAKE_GIT) | ConvertFrom-Json).calls)
        }

        # A fake scripts\gradle.ps1 for the folder -Root: it records its arguments, prints a count as the sync does, writes
        # the catalog and ends with -ExitCode.
        function New-FakeGradle([string]$Root, [int]$ExitCode = 0) {
            $path = Join-Path $TestDrive 'fake-gradle.ps1'
            $arguments = Join-Path $TestDrive 'gradle-arguments.txt'
            $catalog = Join-Path $Root 'catalog'
            Set-Content -LiteralPath $path -Value @(
                "[IO.File]::WriteAllText('$arguments', (`$args -join [char]10))"
                "if ($ExitCode -ne 0) { exit $ExitCode }"
                "'Providers: 72'"
                "New-Item -ItemType Directory -Force -Path '$catalog' | Out-Null"
                "[IO.File]::WriteAllText('$(Join-Path $catalog 'providers.json')', '{}')"
                'exit 0'
            )
            $path
        }

        function Get-GradleArgument {
            [IO.File]::ReadAllText((Join-Path $TestDrive 'gradle-arguments.txt')) -split "`n"
        }
    }

    BeforeEach {
        $root = Join-Path $TestDrive ([guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $root | Out-Null
        $lock = Join-Path $root 'catalog\openclaw.lock'
        $env:KESTREL_FAKE_GIT = Join-Path $TestDrive 'git.json'
        Set-FakeGit
        Remove-Item -LiteralPath (Join-Path $TestDrive 'gradle-arguments.txt') -ErrorAction SilentlyContinue
    }

    It 'makes the sparse checkout of the commit, runs the sync and writes the lock' {
        $gradle = New-FakeGradle -Root $root

        $output = @(& $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null)

        Get-GitCall | Should -Be @(
            'init --quiet'
            "remote add origin $repository"
            'sparse-checkout set --no-cone extensions/*/openclaw.plugin.json'
            "fetch --depth 1 --filter=blob:none origin $commit"
            'checkout --force FETCH_HEAD'
        )
        Get-GradleArgument | Should -Be @(':tools:catalog-sync:syncCatalog', "-Pcheckout=$(Join-Path $root "build\openclaw\$commit")", "-Pcommit=$commit")
        $output | Should -Contain 'Providers: 72'
        [IO.File]::ReadAllBytes($lock) | Should -Be ([Text.Encoding]::ASCII.GetBytes("$commit`n"))
    }

    It 'reuses the checkout on a second run and reads the commit from the lock' {
        $gradle = New-FakeGradle -Root $root
        $null = & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null
        Set-FakeGit

        $null = & $sync -Git $fakeGit -Gradle $gradle -Root $root 6>$null

        (Get-GitCall)[0] | Should -Be "remote set-url origin $repository"
        (Get-GitCall).Count | Should -Be 4
        Get-GradleArgument | Should -Contain "-Pcommit=$commit"
    }

    It 'refuses a commit that is not a 40-character hash, before any git call' {
        $gradle = New-FakeGradle -Root $root

        { & $sync -Commit 'e6113549' -Git $fakeGit -Gradle $gradle -Root $root 6>$null } |
            Should -Throw -ExpectedMessage "The commit must be a 40-character hash, not 'e6113549'."
        @(Get-GitCall).Count | Should -Be 0
    }

    It 'stops when git fails, and neither syncs nor writes the lock' {
        $gradle = New-FakeGradle -Root $root
        Set-FakeGit -Fail 'fetch'

        { & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null } | Should -Throw -ExpectedMessage 'git fetch failed with exit code 128.'
        Test-Path -LiteralPath (Join-Path $TestDrive 'gradle-arguments.txt') | Should -BeFalse
        Test-Path -LiteralPath $lock | Should -BeFalse
    }

    It 'removes a repository that it could not set up, so that the next run starts again' {
        $gradle = New-FakeGradle -Root $root
        Set-FakeGit -Fail 'remote'

        { & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null } | Should -Throw -ExpectedMessage 'git remote failed with exit code 128.'
        Test-Path -LiteralPath (Join-Path $root "build\openclaw\$commit\.git") | Should -BeFalse
        Test-Path -LiteralPath $lock | Should -BeFalse
    }

    It 'stops when the sync fails, and does not write the lock' {
        $gradle = New-FakeGradle -Root $root -ExitCode 1

        { & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null } | Should -Throw -ExpectedMessage 'The sync failed with exit code 1*'
        Test-Path -LiteralPath $lock | Should -BeFalse
    }
}
```

- [ ] **Step 8: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: exit code 1 and "The script tests or checks failed.": in each shell the 6 tests of `SyncCatalog.Tests.ps1` fail because `sync-catalog.ps1` does not exist ("The term '…\scripts\tests\..\sync-catalog.ps1' is not recognized"), and the other T₀ tests pass.

- [ ] **Step 9: Write the script**

Create `scripts/sync-catalog.ps1`:

```powershell
# Makes Kestrel's provider catalog from OpenClaw's manifests (design spec, section 10.2): a shallow, sparse checkout of
# OpenClaw's Git repository at one commit in build\openclaw\<commit>, then the Gradle task :tools:catalog-sync:syncCatalog,
# which writes catalog\providers.json and prints its counts, and last the commit in catalog\openclaw.lock. Without -Commit it
# uses the commit in catalog\openclaw.lock. A second run in the same folder reuses the checkout. It fetches only OpenClaw's
# repository and sends no request to any provider. -Git, -Gradle and -Root exist for its tests (scripts\tests).
# Example: scripts\sync-catalog.ps1 -Commit e6113549fe8800b4c736c5e8a131b47f2e015143
param(
    [string]$Commit,
    [string]$Git = 'git',
    [string]$Gradle = (Join-Path $PSScriptRoot 'gradle.ps1'),
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)
$ErrorActionPreference = 'Stop'
$repository = 'https://github.com/openclaw/openclaw.git'
$lock = Join-Path $Root 'catalog\openclaw.lock'
if (-not $Commit) {
    if (-not (Test-Path -LiteralPath $lock)) { throw "Give -Commit, because $lock does not exist." }
    $Commit = [IO.File]::ReadAllText($lock).Trim()
}
if ($Commit -cnotmatch '^[0-9a-f]{40}$') { throw "The commit must be a 40-character hash, not '$Commit'." }
$checkout = Join-Path $Root "build\openclaw\$Commit"
$gitProgram = $Git

function Invoke-Git {
    # git writes its progress to standard error, which Windows PowerShell 5.1 would turn into errors under 'Stop'.
    $ErrorActionPreference = 'Continue'
    $output = @(& $gitProgram -C $checkout @args 2>&1 | ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    foreach ($line in $output) { Write-Host $line }
    if ($code -ne 0) { throw "git $($args[0]) failed with exit code $code." }
}

New-Item -ItemType Directory -Force -Path $checkout | Out-Null
$gitFolder = Join-Path $checkout '.git'
if (Test-Path -LiteralPath $gitFolder) {
    try {
        Invoke-Git remote set-url origin $repository
    } catch {
        throw "git remote set-url failed in $checkout. If an earlier run stopped before the checkout was set up, delete that folder and run the script again."
    }
} else {
    try {
        Invoke-Git init --quiet
        Invoke-Git remote add origin $repository
    } catch {
        # Without this, every later run would find the half-made repository and fail at 'remote set-url'.
        Remove-Item -LiteralPath $gitFolder -Recurse -Force -ErrorAction SilentlyContinue
        throw
    }
}
Invoke-Git sparse-checkout set --no-cone 'extensions/*/openclaw.plugin.json'
Invoke-Git fetch --depth 1 --filter=blob:none origin $Commit
Invoke-Git checkout --force FETCH_HEAD

& $Gradle ':tools:catalog-sync:syncCatalog' "-Pcheckout=$checkout" "-Pcommit=$Commit"
if ($LASTEXITCODE -ne 0) { throw "The sync failed with exit code $LASTEXITCODE, so $lock keeps its commit." }

# One line with the hash and a line feed, in UTF-8 without a byte order mark, also in Windows PowerShell 5.1.
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lock) | Out-Null
[IO.File]::WriteAllText($lock, "$Commit`n", (New-Object System.Text.UTF8Encoding $false))
"Wrote $lock."
```

- [ ] **Step 10: Run the script tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.", with T₀ + 6 Pester tests in each shell and "PSScriptAnalyzer found 0 problems."

- [ ] **Step 11: Write the failing test of the catalog files**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/CatalogFilesTest.kt`:

```kotlin
package dev.kestrel.providers

import dev.kestrel.providers.catalog.CatalogFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spec 17's check that `providers.json` matches `openclaw.lock`, as a unit test that runs with `check` (design D2). */
class CatalogFilesTest {
    private val folder = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog"))
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun providersJsonNamesTheCommitOfTheLock() {
        val lock = folder.resolve("openclaw.lock").readText()
        val catalog = json.decodeFromString(CatalogFile.serializer(), folder.resolve("providers.json").readText())

        assertTrue("openclaw.lock must hold one 40-character hash and a line feed", Regex("^[0-9a-f]{40}\n$").matches(lock))
        assertEquals(lock.trim(), catalog.openclawCommit)
        assertTrue(catalog.providers.isNotEmpty())
    }
}
```

Run: `scripts\gradle.ps1 :core:providers:test --tests dev.kestrel.providers.CatalogFilesTest`
Expected: `BUILD FAILED`: `providersJsonNamesTheCommitOfTheLock` fails with a `FileNotFoundException` for `catalog\openclaw.lock`, because the catalog does not exist yet.

- [ ] **Step 12: Stop Gradle before the sync**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running."). The sync starts its own build.

- [ ] **Step 13: Generate the catalog at the pinned commit**

Run: `scripts\sync-catalog.ps1 -Commit e6113549fe8800b4c736c5e8a131b47f2e015143`
Expected: git's lines for the fetch and "HEAD is now at e611354 fix(telegram): keep reply text when delivery-time media loading fails (#186751)"; then Gradle's `> Task :tools:catalog-sync:syncCatalog` with these lines:

```text
Manifests read: 186
Models plugins: 68
Providers: 72
Models: 282
Providers per style: ANTHROPIC 3, CHAT_COMPLETIONS 38, GEMINI 1, OLLAMA 1, RESPONSES 5, none 24
Models whose all-zero cost became no price: 48
Skipped: 17
```

and `BUILD SUCCESSFUL`; last, "Wrote H:\Code\kestrel\catalog\openclaw.lock." (with the repository's own folder).

Run: `(Get-FileHash catalog\providers.json -Algorithm SHA256).Hash.ToLower(); Get-Content catalog\openclaw.lock`
Expected: `e6b71de16f88c1902807da8ada9587f36feb8b7e4c7dc7f76ece60e55a9929c8` (the writer of this plan generated the same file from the same commit; the file has 275,133 bytes and LF line endings) and `e6113549fe8800b4c736c5e8a131b47f2e015143`. If the hash differs, stop and report the hash, the byte count and the seven summary lines to the controller, because the overrides of Task 6 name the IDs of this file. The controller compares the file with the reference copy that review B made, whose path is in the controller's notes and which has the expected SHA-256: `git diff --no-index --stat <reference copy> catalog\providers.json`.

The 24 providers without a style are those that OpenClaw defines in code (Task 6 completes or excludes each one, and it also excludes `kimi`, which has a style): `apple-fm`, `arcee`, `bifrost`, `celeris`, `cloudflare-ai-gateway`, `copilot-proxy`, `fastino`, `levanto`, `liquid-ai`, `litellm`, `llama-cpp`, `lmstudio`, `microsoft-foundry`, `minimax`, `minimax-portal`, `nace`, `ollama`, `omlx`, `openrouter`, `perplexity-decisions`, `radius`, `sglang`, `synthetic` and `vllm`. The 17 skipped rows are decision models ("not a chat model") of `baseten` (3), `celeris`, `clawrouter`, `fastino`, `google`, `levanto`, `liquid-ai` (2), `nace`, `openrouter` (5) and `perplexity-decisions`.

- [ ] **Step 14: Run the test of the catalog files to verify that it passes**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with 49 tests in `:core:providers` (48 + `CatalogFilesTest` 1).

- [ ] **Step 15: Add OpenClaw's notice**

In `THIRD_PARTY_NOTICES.md`, replace

```markdown
## The parts of sherpa-onnx's library
```

with

```markdown
## OpenClaw

Kestrel's provider catalog, `catalog/providers.json`, which the app contains, is generated from the provider manifests of OpenClaw at the commit in `catalog/openclaw.lock`. The tests of `:tools:catalog-sync` contain excerpts of those manifests, and parts of Kestrel's provider code port OpenClaw's code, as the comments in that code say. `catalog/overrides.json`, which the app also contains, holds model rows, prices, regions and addresses that were copied from OpenClaw's provider code at that commit. OpenClaw is under the MIT License, whose text and OpenClaw's copyright line are in `licences/MIT.txt`.

| Component | Licence | Project |
|---|---|---|
| OpenClaw, Copyright (c) 2026 OpenClaw Foundation: the provider data in `catalog/providers.json`, the data in `catalog/overrides.json` that is copied from OpenClaw's code, the manifest excerpts in `tools/catalog-sync`'s tests, and the provider code that Kestrel ports | MIT | https://github.com/openclaw/openclaw |

## The parts of sherpa-onnx's library
```

In `licences/MIT.txt`, replace

```text
- Piper's voices: the Piper voices repository states the MIT License, without a copyright line
```

with

```text
- Piper's voices: the Piper voices repository states the MIT License, without a copyright line
- OpenClaw: Copyright (c) 2026 OpenClaw Foundation
```

- [ ] **Step 16: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| `:tools:catalog-sync` | Runs on the PC and generates the provider catalog | Yes |
```

with

```markdown
| `:tools:catalog-sync` | Runs on the PC and generates the provider catalog from a checkout of OpenClaw that `scripts/sync-catalog.ps1` makes | Yes |
```

and replace

```markdown
- `:tools:catalog-sync` runs on the PC. It makes a shallow, sparse checkout of OpenClaw at a pinned commit and reads every `openclaw.plugin.json` in the "models" category. It writes `catalog/providers.json`, which the app bundles, and stores the commit in `catalog/openclaw.lock`. On 12 November 2026, OpenClaw's main branch was at commit `e6113549fe8800b4c736c5e8a131b47f2e015143`. The counts in section 10.1 come from the manifests as they were on 11 November.
- Each catalog entry records the provider's ID and label, its sign-in choices (`providerAuthChoices`), its base URL and regional variants, its request style, its hosts, its default model, and its models with context size, image support and price where the manifest has them.
- `catalog/overrides.json` is kept by hand. It holds the exclusions from section 10.1, the local servers that Kestrel discovers instead of listing (LM Studio, Ollama, vLLM, SGLang, llama.cpp, oMLX and LiteLLM), and GitHub Copilot's per-token prices together with the date they were copied from GitHub's pricing page.
```

with

```markdown
- `scripts\sync-catalog.ps1` makes a shallow, sparse checkout of OpenClaw at the commit in `catalog/openclaw.lock`, or at the one that `-Commit` names, in `build/openclaw/<commit>`. Then `:tools:catalog-sync`, which runs on the PC, reads every `openclaw.plugin.json` of the checkout as JSON5, keeps the plugins whose `categories` hold "models", and writes one entry for each of their providers that has catalog rows or sign-in choices to `catalog/providers.json`, which the app bundles; last, the script stores the commit in `catalog/openclaw.lock`. On 12 November 2026, OpenClaw's main branch was at commit `e6113549fe8800b4c736c5e8a131b47f2e015143`, where 68 plugins are in the "models" category. The counts in section 10.1 come from the manifests as they were on 11 November.
- `providers.json` is Kestrel's own format, sorted by provider and model so that a new sync gives a readable diff. Each entry records the provider's ID, plugin and label, its request style where the manifest names one, its base URL and hosts, its headers, its default and utility models, how its model list is found (static, refreshable or at runtime), its sign-in choices (`providerAuthChoices`), and its models with their style and base URL where they differ, input kinds, reasoning, context size, output limit, prices in US dollars per million tokens (with cache reads, cache writes and long-context tiers), compatibility flags, thinking levels and status. A cost whose input and output are both 0, which OpenClaw writes where it has no price, becomes no price. The sync reads no `providerEndpoints`, because they belong to a plugin rather than a provider, and no overrides. Its `skipped` list names each provider or model that it left out and why: a request style that Kestrel does not support (`pi-messages`, `google-interactions`), a decision model (`inference.chat` false) or a disabled model.
- `catalog/overrides.json` is kept by hand. It holds the exclusions from section 10.1, providers and sign-in choices each with a reason; the settings of every sign-in choice that Kestrel offers (its method, base URL, plan usage, key header, identity headers, refresh hosts, the borrowed identity with the default of its switch, and the links to the provider's terms), including Kestrel's own choices that no manifest has; the providers that OpenClaw defines in code, completed with their style, base URL, regions and rows (Bedrock, Bedrock Mantle with five Claude rows, Vertex with Gemini and Claude rows, Microsoft Foundry, MiniMax, OpenRouter and others); the regional base URLs of the manifests, as sign-in choices or regions; the local servers that Kestrel connects to by their address (LM Studio, Ollama, vLLM, SGLang, llama.cpp, oMLX and LiteLLM); per-model corrections and prices, such as Google's Gemini prices with the day they were copied; free models; the price that Kestrel counts when it knows none; and GitHub Copilot's per-token prices together with the date they were copied from GitHub's pricing page (with long-context tiers, cache reads, cache writes and promotions), its default models and its two header sets.
- The app merges the two files from its assets into one catalog when it first needs it, so a change of the overrides needs no new sync. A unit test checks that `providers.json` names the commit in `openclaw.lock`, and another that every name in `overrides.json` exists, that every offered sign-in choice has a method, and that every request style has a provider.
```

and replace

```markdown
- the catalog generator, as a snapshot test at the pinned commit;
```

with

```markdown
- the catalog generator, as a snapshot test of fixture manifests copied from the pinned commit, and a test that `providers.json` names the commit in `openclaw.lock`;
```

and replace

```markdown
- **CI:** GitHub Actions runs the unit tests, Android lint, the build-time audit and a check that `providers.json` matches `openclaw.lock`. It does not run emulator tests at first.
```

with

```markdown
- **CI:** GitHub Actions runs the unit tests, Android lint and the build-time audit; the check that `providers.json` matches `openclaw.lock` is a unit test that runs with them. It does not run emulator tests at first.
```

- [ ] **Step 17: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` 49, `:tools:catalog-sync` 6; `:app` A₀, `:core:privacy` P₀ + 5, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit, which now also covers `:tools:catalog-sync`.

- [ ] **Step 18: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 19: Commit**

The checkout in `build\openclaw\` stays out of Git, because `.gitignore` ignores `build/`.

```powershell
git add settings.gradle.kts tools/catalog-sync scripts/sync-catalog.ps1 scripts/tests/SyncCatalog.Tests.ps1 scripts/tests/fake-git.cmd scripts/tests/fake-git.ps1 catalog/providers.json catalog/openclaw.lock core/providers/src/test/kotlin/dev/kestrel/providers/CatalogFilesTest.kt THIRD_PARTY_NOTICES.md licences/MIT.txt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(catalog): generate the provider catalog from OpenClaw's manifests at the pinned commit" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: `catalog/overrides.json` for all three plans

**Files:**
- Create: `catalog/overrides.json`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 10.1 and Appendix B)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/OverridesTest.kt`

**Interfaces:**
- Consumes: Task 4's `Overrides` and its parts, `CatalogLoader`, `CatalogFile`; Task 5's `catalog/providers.json`; Task 2's `Hosts`.
- Produces: the complete `catalog/overrides.json`, which plans 4B and 4C do not change (a change that they need goes through the controller). Its content, by name: the borrowed identities `copilot` (`defaultOn: true`), `xai` and `minimax` (`defaultOn: false`); the choices `github-copilot/github-copilot` and `github-copilot/github-copilot-enterprise` (`DEVICE_CODE`, key header `BEARER`); `openai/chatgpt-plan` (`BROWSER`, "Continue with ChatGPT", base `https://api.openai.com/v1`, `RESPONSES`, `chatgptPlan`, `planUsage`, refresh host `auth.openai.com`); `openai/openai-api-key`; `xai/xai-oauth` (`DEVICE_CODE`, base `https://cli-chat-proxy.grok.com/v1`, identity headers `X-XAI-Token-Auth: xai-grok-cli` and `x-grok-client-version: 1.0.13`, `planUsage`); `xai/xai-api-key`; `minimax-portal/minimax-global-oauth` and `minimax-portal/minimax-cn-oauth` (`DEVICE_CODE`, bases `https://api.minimax.io/anthropic` and `https://api.minimaxi.com/anthropic`, `BEARER`, `planUsage`); `minimax/minimax-global-api` and `minimax/minimax-cn-api` (`BEARER`); `openrouter/openrouter-oauth` (`BROWSER`) and `openrouter/openrouter-api-key`; `amazon-bedrock/bedrock-api-key` (`API_KEY`, `BEARER`) and `amazon-bedrock/bedrock-iam-keys` (`IMPORTED`); `amazon-bedrock-mantle/mantle-api-key` (`API_KEY`, `BEARER`); `google-vertex/vertex-service-account` (`IMPORTED`, refresh host `oauth2.googleapis.com`); `microsoft-foundry/microsoft-foundry-apikey` (`customBaseUrl`); the server-address choices `ollama/ollama-server`, `lmstudio/lmstudio-server`, `vllm/vllm-server`, `sglang/sglang-server`, `llama-cpp/llama-cpp-server`, `omlx/omlx-server` and `litellm/litellm-server`; and every other provider's API-key choice. Plan 4C's `SignInRegistry` keys its flows by these provider and choice IDs.

Design D2, D4, D7 to D12, D14, D18 and D21; "Shared names" gives the schema. The writer of this plan read the manifests and the provider code at the pinned commit and copied these values from them:
- **Exclusions.** Providers: `apple-fm` and `copilot-proxy` (programs on the owner's computer, spec 10.1); `celeris`, `fastino`, `levanto`, `liquid-ai`, `nace` and `perplexity-decisions` (decision models only); `radius` (only `pi-messages`, D8); `kimi` (Kimi Code), whose only choice would send the Claude Code `User-Agent` of its manifest, which Kimi's rules forbid (decision 6 of the design's decisions, research r47; Kimi's models stay available through Moonshot's API keys, which send Kestrel's own `User-Agent`); and `cloudflare-ai-gateway`, whose base URL holds the owner's Cloudflare account ID and gateway ID, which the network log would record (spec 13.5). `anthropic-vertex`, `amazon-bedrock`, `amazon-bedrock-mantle`, `google-vertex` and `rerank` have neither catalog rows nor sign-in choices, so the sync writes no entry for them and they need no exclusion; `google-vertex`, `amazon-bedrock` and `amazon-bedrock-mantle` are added below. Choices: `anthropic-cli`, `setup-token` (never, D21), OpenClaw's Codex choices `openai` and `openai-device-code`, `openai-token-sharing`, `xai-device-code`, `muse-code`, `microsoft-foundry-entra`, Chutes' OAuth choice `chutes`, and the manifests' own choices of the local servers (`ollama`, `lmstudio`, `vllm`, `sglang`, `omlx`, `llama-cpp`, `llama-cpp-existing-server`, `litellm-api-key`), which Kestrel replaces with its server-address choices (D11).
- **Regions as choices.** The manifests split these providers' regions into sign-in choices, so each region's base URL is its choice's: MiniMax (`api.minimax.io` and `api.minimaxi.com`, `extensions/minimax/model-definitions.ts`), Moonshot (`.ai` and `.cn`, its `providerEndpoints`), Qwen's standard keys, Coding Plan and Token Plan (`extensions/qwen/models.ts`), StepFun's standard keys and Step Plan (`api.stepfun.ai` and `api.stepfun.com`), Xiaomi's Token Plans (Amsterdam, China and Singapore) and Z.AI's four endpoints (`extensions/zai/detect.ts`: `api.z.ai` and `open.bigmodel.cn`, each with and without `/coding`). Arcee's two choices go to `https://api.arcee.ai/api/v1` and to OpenRouter (`extensions/arcee`).
- **Providers that OpenClaw defines in code.** `amazon-bedrock` (`BEDROCK`, base `https://bedrock-runtime.{region}.amazonaws.com`, hosts for `bedrock-runtime` and `bedrock` in the region, the twelve regions of Mantle's `MANTLE_SUPPORTED_REGIONS`, because OpenClaw lists none for Bedrock); `amazon-bedrock-mantle` (`CHAT_COMPLETIONS` at `https://bedrock-mantle.{region}.api.aws/v1`, the same twelve regions, and the five Claude rows of `extensions/amazon-bedrock-mantle/discovery.ts` with their style `ANTHROPIC`, base `…/anthropic`, a context of 1,000,000 tokens, 128,000 output tokens and OpenClaw's prices, Sonnet 5's with its price change on 2026-09-01); `google-vertex` (plugin `google`, `VERTEX`, base `https://{location}-aiplatform.googleapis.com`, the regions `global`, `us` and `eu` of the `google` manifest's endpoints, the Gemini rows copied from `google` with `modelsFrom`, and the seven Claude rows of `extensions/anthropic-vertex/provider-catalog.ts` with their prices, the multi-region price for Opus 5 and Sonnet 5); `microsoft-foundry` (`AZURE`, the owner's endpoint); `minimax` and `minimax-portal` (`ANTHROPIC` at `https://api.minimax.io/anthropic`, the five rows of `extensions/minimax/provider-models.ts` with the prices of `model-definitions.ts`, default `MiniMax-M3`; the portal copies them, with the default `MiniMax-M3.1-Flash`, which MiniMax's M Plan FAQ says that every M Plan tier supports); `openrouter` (`CHAT_COMPLETIONS`, `https://openrouter.ai/api/v1`); `arcee`, `bifrost`, `litellm`, `llama-cpp`, `lmstudio`, `omlx`, `sglang` and `vllm` (`CHAT_COMPLETIONS`); `ollama` (`OLLAMA`); `synthetic` (`ANTHROPIC`, `https://api.synthetic.new/anthropic`, the seven rows of `extensions/synthetic/models.ts` without prices, because OpenClaw's are zero, the default `hf:MiniMaxAI/MiniMax-M3` and `STATIC` discovery, because OpenClaw lists Synthetic's models at an OpenAI-compatible address that is not its Anthropic base); and labels for `byteplus-plan`, `volcengine-plan` and `llama-cpp`, whose manifests give none. `byteplus-plan` and `volcengine-plan` share their provider's key in OpenClaw (`providerAuthAliases`), so each gets Kestrel's own API-key choice.
- **Plan usage.** The ChatGPT plan, xAI's and MiniMax's sign-ins (D14), and the providers' subscription keys (Qwen's Coding Plan and Token Plan, StepFun's Step Plan, Xiaomi's Token Plans, Z.AI's Coding Plans, BytePlus' and Volcano Engine's Coding Plans), whose plans pay for the requests; OpenClaw prices only a few of their models. Tencent's TokenPlan key counts credits (decision 7), because OpenClaw prices `hy4` and `hy4-preview` at TokenHub's pay-as-you-go prices and nothing shows a flat-rate subscription.
- **Prices.** Google's Gemini prices from https://ai.google.dev/gemini-api/docs/pricing, copied on 2026-11-17 (the paid tier's standard prices; context caching as the cache-read price; Gemini 4 Argon, 3.6 Flash and 3.8 Flash with their dated prices; 2.5 Pro and 3.1 Pro Preview with their tier above 200,000 input tokens). The page has no price for `gemini-3.5-flash` and `gemini-3.7-flash`, which keep no price. `freeModels` is empty: after the sync left out OpenRouter's decision models, no synced row is free. The unknown price is Claude Opus 4.8's Copilot price (D14). Copilot's table is research r28's of 2026-11-17, with two rows that GitHub's page has and r28 lacks (Claude Sonnet 4, and Claude Opus 4.8 in fast mode, copied from the same page on the same day), and with the IDs that the synced `github-copilot` manifest has (`claude-sonnet-5.5`, `claude-opus-5.5`, `claude-sonnet-5`, `claude-opus-4.8`, `claude-sonnet-4.6`, `claude-haiku-4.5`, `claude-fable-5`, `gpt-5.6-luna`, `gpt-5.6-sol`, `gpt-5.3-codex`); GPT-6 Luna, GPT-6.1 Terra, Claude Haiku 5.5 and Gemini 3.8 Flash are not in the manifest, so they are matched by name (D12). Gemini 3.8 Flash's promotion runs until 2027-01-01 (exclusive); Gemini 4 Argon's ends on 2026-12-01 with no later price; Gemini 4 Carbon costs $4.00, $0.20 and $20.00 from 2026-12-01.
- **Terms links** (D18, D21): GitHub's three pages (r45), OpenAI's Terms of Use, Usage Policies and Sign in with ChatGPT for open-source apps (r42, r46), xAI's three pages (r43), and MiniMax's global terms, China user agreement and M Plan FAQ (r44, the FAQ's page for people rather than its Markdown export).
- **Copilot's headers** (D12, r27): the runtime headers and the IDE headers of the usage call.

`OverridesTest` decodes the file strictly, unlike `CatalogLoader`, so that a misspelt key fails instead of falling back to its default, and checks it against `providers.json`: no offered provider sends a header of its manifest other than those that the test lists as decided (none at the pinned commit); every provider, choice, model and Copilot ID that it names exists (or the provider is added with a plugin), every choice of the merged catalog has settings (so a manifest's new choice must be offered or excluded), every provider with an offered choice has a style, every one of the eight styles has an offered provider, the borrowed identities and the ChatGPT plan's choice have HTTPS terms links, every price says where and when it was copied, and every local server suggests a local address and offers only its server-address choice.

- [ ] **Step 1: Write the failing test**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/OverridesTest.kt`:

```kotlin
package dev.kestrel.providers

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogFile
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.ModelOverride
import dev.kestrel.providers.catalog.Overrides
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.Hosts
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the hand-written `catalog/overrides.json` against the synced `catalog/providers.json` (design D2), so that a typo,
 * an upstream rename or a style without a provider fails the build.
 */
class OverridesTest {
    private val folder = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog"))
    private val providersJson = folder.resolve("providers.json").readText()
    private val overridesJson = folder.resolve("overrides.json").readText()
    private val json = Json { ignoreUnknownKeys = true }
    private val synced: CatalogFile = json.decodeFromString(CatalogFile.serializer(), providersJson)
    // Strict, unlike CatalogLoader: a misspelt key in the hand-written file must fail here instead of falling back to its default.
    private val overrides: Overrides = Json.decodeFromString(Overrides.serializer(), overridesJson)
    private val catalog: Catalog = CatalogLoader.load(providersJson, overridesJson)

    // The manifest headers that offered providers send, by provider, as decided (design D4, decision 6 of the design's
    // decisions). None at the pinned commit: a header that a later sync brings, such as a borrowed User-Agent, fails the
    // build until it is decided and written here, or its provider or choice is excluded.
    private val allowedHeaders: Map<String, Map<String, String>> = emptyMap()

    private val syncedIds = synced.providers.map { it.id }.toSet()
    private val addedIds = overrides.providers.filter { it.id !in syncedIds }.map { it.id }.toSet()

    @Test
    fun everyNameThatTheOverridesUseExists() {
        val problems = mutableListOf<String>()
        for (exclusion in overrides.excludeProviders) {
            if (exclusion.id !in syncedIds) problems += "excluded provider ${exclusion.id}"
        }
        for (exclusion in overrides.excludeAuthChoices) {
            val choices = synced.providers.firstOrNull { it.id == exclusion.provider }?.authChoices.orEmpty()
            if (choices.none { it.choiceId == exclusion.choiceId }) problems += "excluded choice ${exclusion.provider}/${exclusion.choiceId}"
        }
        for (change in overrides.providers) {
            if (change.id !in syncedIds && change.plugin == null) problems += "added provider ${change.id} without a plugin"
            change.modelsFrom?.let { if (it !in syncedIds) problems += "modelsFrom $it" }
        }
        for (choice in overrides.choices) {
            if (choice.provider !in syncedIds && choice.provider !in addedIds) problems += "choice of provider ${choice.provider}"
        }
        for (model in overrides.models + overrides.freeModels.map { ModelOverride(it.provider, it.model) }) {
            val rows = synced.providers.firstOrNull { it.id == model.provider }?.models.orEmpty()
            if (rows.none { it.id == model.model }) problems += "model ${model.provider}/${model.model}"
        }
        val copilotRows = synced.providers.single { it.id == CatalogLoader.COPILOT }.models.map { it.id }
        for (price in overrides.copilot.models) {
            price.id?.let { if (it !in copilotRows) problems += "Copilot model ID $it" }
        }
        for (server in overrides.localServers) {
            if (server.id !in syncedIds) problems += "local server ${server.id}"
        }

        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun everyChoiceIsOfferedOrExcludedAndEveryOfferedProviderHasAStyle() {
        val unsettled = catalog.providers.flatMap { provider ->
            provider.authChoices.filter { it.settings == null }.map { "${provider.id}/${it.choiceId}" }
        }
        val withoutStyle = catalog.providers
            .filter { provider -> provider.authChoices.any { it.offered } }
            .filter { provider -> provider.style == null && (provider.models.isEmpty() || provider.models.any { it.style == null }) }
            .map { it.id }
        val headers = catalog.providers
            .filter { provider -> provider.authChoices.any { it.offered } && provider.headers.isNotEmpty() }
            .associate { it.id to it.headers }

        assertEquals(emptyList<String>(), unsettled)
        assertEquals(emptyList<String>(), withoutStyle)
        assertEquals(allowedHeaders, headers)
    }

    @Test
    fun everyStyleHasAnOfferedProvider() {
        val styles = catalog.providers
            .filter { provider -> provider.authChoices.any { it.offered } }
            .flatMap { provider -> listOfNotNull(provider.style) + provider.models.mapNotNull { it.style } }
            .toSet()

        assertEquals(RequestStyle.entries.toSet(), styles)
    }

    @Test
    fun borrowedAndPlanSignInsLinkToTheirProvidersTerms() {
        val signIns = catalog.providers.flatMap { it.authChoices }.mapNotNull { it.settings }
            .filter { it.borrowedIdentity != null || it.chatgptPlan }

        assertEquals(
            mapOf("copilot" to true, "minimax" to false, "xai" to false),
            signIns.filter { it.borrowedIdentity != null }.associate { it.borrowedIdentity!! to it.defaultOn }.toSortedMap(),
        )
        assertEquals(listOf("chatgpt-plan"), signIns.filter { it.chatgptPlan }.map { it.choiceId })
        for (choice in signIns) {
            assertTrue(choice.choiceId, choice.termsLinks.isNotEmpty())
            assertTrue(choice.choiceId, choice.termsLinks.all { it.url.toHttpUrl().isHttps && it.label.isNotBlank() })
        }
    }

    @Test
    fun pricesSayWhereAndWhenTheyWereCopied() {
        for (model in overrides.models.filter { it.prices != null }) {
            assertTrue(model.model, model.pricesCopiedOn != null && model.pricesSource!!.startsWith("https://"))
        }
        assertEquals("2026-11-17", overrides.copilot.pricesCopiedOn)
        val names = overrides.copilot.models.map { it.name }.toSet()
        assertTrue(names.containsAll(overrides.copilot.lightweight + overrides.copilot.versatile))
    }

    @Test
    fun localServersSuggestLocalAddressesAndHaveAServerAddressChoice() {
        for (server in catalog.localServers) {
            assertTrue(server.id, Hosts.isLocal(server.defaultUrl.toHttpUrl().host))
            val methods = catalog.provider(server.id)!!.authChoices.filter { it.offered }.map { it.settings!!.method }
            assertEquals(server.id, listOf(AuthMethod.SERVER_ADDRESS), methods)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:providers:test --tests dev.kestrel.providers.OverridesTest`
Expected: `BUILD FAILED`: the 6 tests of `OverridesTest` fail with a `FileNotFoundException` for `catalog\overrides.json`.

- [ ] **Step 3: Write the overrides**

Create the file `catalog/overrides.json`:

```json
{
  "schema": 1,
  "excludeProviders": [
    {"id": "apple-fm", "reason": "Apple Foundation Models run only on Apple's own devices (spec 10.1)."},
    {"id": "celeris", "reason": "A decision model, not a chat model (spec 10.1)."},
    {"id": "cloudflare-ai-gateway", "reason": "Its base URL holds the owner's Cloudflare account ID and gateway ID, which the network log would record (spec 13.5)."},
    {"id": "copilot-proxy", "reason": "The Copilot Proxy extension for VS Code is a desktop program (spec 10.1)."},
    {"id": "fastino", "reason": "A decision model, not a chat model (spec 10.1)."},
    {"id": "kimi", "reason": "Its only choice would send the Claude Code User-Agent of OpenClaw's manifest, and Kimi forbids altering the client identifier; Kimi Code is also only for interactive use in coding tools (B51). Kimi's models stay available through Moonshot's API keys."},
    {"id": "levanto", "reason": "A decision model, not a chat model (spec 10.1)."},
    {"id": "liquid-ai", "reason": "A decision model, not a chat model (spec 10.1)."},
    {"id": "nace", "reason": "A decision model, not a chat model (spec 10.1)."},
    {"id": "perplexity-decisions", "reason": "A decision model, not a chat model (spec 10.1)."},
    {"id": "radius", "reason": "Its only request style is OpenClaw's own pi-messages, which is none of Kestrel's eight (design D8)."}
  ],
  "excludeAuthChoices": [
    {"provider": "anthropic", "choiceId": "anthropic-cli", "reason": "Claude CLI reuse needs the desktop program (spec 10.1)."},
    {"provider": "anthropic", "choiceId": "setup-token", "reason": "Never offered: Anthropic's terms forbid third parties to use subscription credentials (design D21)."},
    {"provider": "openai", "choiceId": "openai", "reason": "The Codex CLI's browser sign-in borrows its client; Kestrel uses OpenAI's own Sign in with ChatGPT instead (design D8)."},
    {"provider": "openai", "choiceId": "openai-device-code", "reason": "The Codex CLI's device sign-in borrows its client; Kestrel uses OpenAI's own Sign in with ChatGPT instead (design D8)."},
    {"provider": "openai", "choiceId": "openai-token-sharing", "reason": "It registers OpenClaw as the app; Kestrel's own choice chatgpt-plan registers Kestrel (design D8)."},
    {"provider": "xai", "choiceId": "xai-device-code", "reason": "An alias of xai-oauth (design D8)."},
    {"provider": "meta", "choiceId": "muse-code", "reason": "It runs Meta's closed muse program, and Meta's terms limit the credential to its coding harness (design D8)."},
    {"provider": "microsoft-foundry", "choiceId": "microsoft-foundry-entra", "reason": "It runs the Azure CLI (design D8)."},
    {"provider": "chutes", "choiceId": "chutes", "reason": "It needs the owner's own Chutes OAuth app and client ID (design D8)."},
    {"provider": "ollama", "choiceId": "ollama", "reason": "Kestrel connects to a running server through its own choice ollama-server (design D11)."},
    {"provider": "lmstudio", "choiceId": "lmstudio", "reason": "Kestrel connects to a running server through its own choice lmstudio-server (design D11)."},
    {"provider": "vllm", "choiceId": "vllm", "reason": "Kestrel connects to a running server through its own choice vllm-server (design D11)."},
    {"provider": "sglang", "choiceId": "sglang", "reason": "Kestrel connects to a running server through its own choice sglang-server (design D11)."},
    {"provider": "omlx", "choiceId": "omlx", "reason": "Kestrel connects to a running server through its own choice omlx-server (design D11)."},
    {"provider": "llama-cpp", "choiceId": "llama-cpp", "reason": "OpenClaw starts and manages this server itself (spec 10.1)."},
    {"provider": "llama-cpp", "choiceId": "llama-cpp-existing-server", "reason": "Kestrel connects to a running server through its own choice llama-cpp-server (design D11)."},
    {"provider": "litellm", "choiceId": "litellm-api-key", "reason": "Kestrel connects to a running proxy through its own choice litellm-server (design D11)."}
  ],
  "choices": [
    {"provider": "abliteration", "choiceId": "abliteration-api-key", "method": "API_KEY"},
    {"provider": "amazon-bedrock", "choiceId": "bedrock-api-key", "method": "API_KEY", "label": "Bedrock API key", "keyHeader": "BEARER"},
    {"provider": "amazon-bedrock", "choiceId": "bedrock-iam-keys", "method": "IMPORTED", "label": "AWS access keys"},
    {"provider": "amazon-bedrock-mantle", "choiceId": "mantle-api-key", "method": "API_KEY", "label": "Bedrock API key", "keyHeader": "BEARER"},
    {"provider": "anthropic", "choiceId": "apiKey", "method": "API_KEY"},
    {"provider": "arcee", "choiceId": "arceeai-api-key", "method": "API_KEY", "baseUrl": "https://api.arcee.ai/api/v1"},
    {"provider": "arcee", "choiceId": "arceeai-openrouter", "method": "API_KEY", "baseUrl": "https://openrouter.ai/api/v1"},
    {"provider": "baseten", "choiceId": "baseten-api-key", "method": "API_KEY"},
    {"provider": "bifrost", "choiceId": "bifrost-api-key", "method": "API_KEY", "customBaseUrl": true},
    {"provider": "byteplus", "choiceId": "byteplus-api-key", "method": "API_KEY"},
    {"provider": "byteplus-plan", "choiceId": "byteplus-plan-api-key", "method": "API_KEY", "label": "BytePlus Coding Plan API key", "planUsage": true},
    {"provider": "cerebras", "choiceId": "cerebras-api-key", "method": "API_KEY"},
    {"provider": "chutes", "choiceId": "chutes-api-key", "method": "API_KEY"},
    {"provider": "clawrouter", "choiceId": "clawrouter-api-key", "method": "API_KEY"},
    {"provider": "cohere", "choiceId": "cohere-api-key", "method": "API_KEY"},
    {"provider": "deepinfra", "choiceId": "deepinfra-api-key", "method": "API_KEY"},
    {"provider": "deepseek", "choiceId": "deepseek-api-key", "method": "API_KEY"},
    {"provider": "featherless", "choiceId": "featherless-api-key", "method": "API_KEY"},
    {"provider": "fireworks", "choiceId": "fireworks-api-key", "method": "API_KEY"},
    {
      "provider": "github-copilot", "choiceId": "github-copilot", "method": "DEVICE_CODE", "keyHeader": "BEARER",
      "borrowedIdentity": "copilot", "defaultOn": true,
      "termsLinks": [
        {"label": "Terms of Service", "url": "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"},
        {"label": "Acceptable Use Policies", "url": "https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies"},
        {"label": "Terms for Additional Products and Features", "url": "https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features"}
      ]
    },
    {
      "provider": "github-copilot", "choiceId": "github-copilot-enterprise", "method": "DEVICE_CODE", "keyHeader": "BEARER",
      "borrowedIdentity": "copilot", "defaultOn": true,
      "termsLinks": [
        {"label": "Terms of Service", "url": "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"},
        {"label": "Acceptable Use Policies", "url": "https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies"},
        {"label": "Terms for Additional Products and Features", "url": "https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features"}
      ]
    },
    {"provider": "gmi", "choiceId": "gmi-api-key", "method": "API_KEY"},
    {"provider": "google", "choiceId": "gemini-api-key", "method": "API_KEY"},
    {"provider": "google-vertex", "choiceId": "vertex-service-account", "method": "IMPORTED", "label": "Service account key file", "refreshHosts": ["oauth2.googleapis.com"]},
    {"provider": "groq", "choiceId": "groq-api-key", "method": "API_KEY"},
    {"provider": "huggingface", "choiceId": "huggingface-api-key", "method": "API_KEY"},
    {"provider": "kilocode", "choiceId": "kilocode-api-key", "method": "API_KEY"},
    {"provider": "litellm", "choiceId": "litellm-server", "method": "SERVER_ADDRESS", "label": "Server address"},
    {"provider": "llama-cpp", "choiceId": "llama-cpp-server", "method": "SERVER_ADDRESS", "label": "Server address"},
    {"provider": "lmstudio", "choiceId": "lmstudio-server", "method": "SERVER_ADDRESS", "label": "Server address"},
    {"provider": "longcat", "choiceId": "longcat-api-key", "method": "API_KEY"},
    {"provider": "meta", "choiceId": "meta-api-key", "method": "API_KEY"},
    {"provider": "microsoft-foundry", "choiceId": "microsoft-foundry-apikey", "method": "API_KEY", "customBaseUrl": true},
    {"provider": "minimax", "choiceId": "minimax-global-api", "method": "API_KEY", "baseUrl": "https://api.minimax.io/anthropic", "keyHeader": "BEARER"},
    {"provider": "minimax", "choiceId": "minimax-cn-api", "method": "API_KEY", "baseUrl": "https://api.minimaxi.com/anthropic", "keyHeader": "BEARER"},
    {
      "provider": "minimax-portal", "choiceId": "minimax-global-oauth", "method": "DEVICE_CODE", "baseUrl": "https://api.minimax.io/anthropic",
      "keyHeader": "BEARER", "planUsage": true, "borrowedIdentity": "minimax", "defaultOn": false,
      "termsLinks": [
        {"label": "Terms of Service (global)", "url": "https://platform.minimax.io/protocol/terms-of-service"},
        {"label": "User agreement (China)", "url": "https://platform.minimax.cn/protocol/user-agreement"},
        {"label": "M Plan FAQ", "url": "https://platform.minimax.io/docs/m-plan/faq"}
      ]
    },
    {
      "provider": "minimax-portal", "choiceId": "minimax-cn-oauth", "method": "DEVICE_CODE", "baseUrl": "https://api.minimaxi.com/anthropic",
      "keyHeader": "BEARER", "planUsage": true, "borrowedIdentity": "minimax", "defaultOn": false,
      "termsLinks": [
        {"label": "Terms of Service (global)", "url": "https://platform.minimax.io/protocol/terms-of-service"},
        {"label": "User agreement (China)", "url": "https://platform.minimax.cn/protocol/user-agreement"},
        {"label": "M Plan FAQ", "url": "https://platform.minimax.io/docs/m-plan/faq"}
      ]
    },
    {"provider": "mistral", "choiceId": "mistral-api-key", "method": "API_KEY"},
    {"provider": "moonshot", "choiceId": "moonshot-api-key", "method": "API_KEY", "baseUrl": "https://api.moonshot.ai/v1"},
    {"provider": "moonshot", "choiceId": "moonshot-api-key-cn", "method": "API_KEY", "baseUrl": "https://api.moonshot.cn/v1"},
    {"provider": "novita", "choiceId": "novita-api-key", "method": "API_KEY"},
    {"provider": "nvidia", "choiceId": "nvidia-api-key", "method": "API_KEY"},
    {"provider": "ollama", "choiceId": "ollama-server", "method": "SERVER_ADDRESS", "label": "Server address"},
    {"provider": "ollama-cloud", "choiceId": "ollama-cloud", "method": "API_KEY"},
    {"provider": "omlx", "choiceId": "omlx-server", "method": "SERVER_ADDRESS", "label": "Server address"},
    {
      "provider": "openai", "choiceId": "chatgpt-plan", "method": "BROWSER", "label": "Continue with ChatGPT",
      "baseUrl": "https://api.openai.com/v1", "style": "RESPONSES", "chatgptPlan": true, "planUsage": true,
      "refreshHosts": ["auth.openai.com"],
      "termsLinks": [
        {"label": "Terms of Use", "url": "https://openai.com/policies/terms-of-use/"},
        {"label": "Usage Policies", "url": "https://openai.com/policies/usage-policies/"},
        {"label": "Sign in with ChatGPT for open-source apps", "url": "https://developers.openai.com/siwc/token-sharing-open-source/"}
      ]
    },
    {"provider": "openai", "choiceId": "openai-api-key", "method": "API_KEY"},
    {"provider": "opencode", "choiceId": "opencode-zen", "method": "API_KEY"},
    {"provider": "opencode-go", "choiceId": "opencode-go", "method": "API_KEY"},
    {"provider": "openrouter", "choiceId": "openrouter-api-key", "method": "API_KEY"},
    {"provider": "openrouter", "choiceId": "openrouter-oauth", "method": "BROWSER"},
    {"provider": "particle", "choiceId": "particle-api-key", "method": "API_KEY"},
    {"provider": "qianfan", "choiceId": "qianfan-api-key", "method": "API_KEY"},
    {"provider": "qwen", "choiceId": "qwen-standard-api-key-cn", "method": "API_KEY", "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1"},
    {"provider": "qwen", "choiceId": "qwen-standard-api-key", "method": "API_KEY", "baseUrl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"},
    {"provider": "qwen", "choiceId": "qwen-api-key-cn", "method": "API_KEY", "baseUrl": "https://coding.dashscope.aliyuncs.com/v1", "planUsage": true},
    {"provider": "qwen", "choiceId": "qwen-api-key", "method": "API_KEY", "baseUrl": "https://coding-intl.dashscope.aliyuncs.com/v1", "planUsage": true},
    {"provider": "qwen-token-plan", "choiceId": "qwen-token-plan", "method": "API_KEY", "baseUrl": "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1", "planUsage": true},
    {"provider": "qwen-token-plan", "choiceId": "qwen-token-plan-cn", "method": "API_KEY", "baseUrl": "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", "planUsage": true},
    {"provider": "requesty", "choiceId": "requesty-api-key", "method": "API_KEY"},
    {"provider": "sglang", "choiceId": "sglang-server", "method": "SERVER_ADDRESS", "label": "Server address"},
    {"provider": "stepfun", "choiceId": "stepfun-standard-api-key-cn", "method": "API_KEY", "baseUrl": "https://api.stepfun.com/v1"},
    {"provider": "stepfun", "choiceId": "stepfun-standard-api-key-intl", "method": "API_KEY", "baseUrl": "https://api.stepfun.ai/v1"},
    {"provider": "stepfun-plan", "choiceId": "stepfun-plan-api-key-cn", "method": "API_KEY", "baseUrl": "https://api.stepfun.com/step_plan/v1", "planUsage": true},
    {"provider": "stepfun-plan", "choiceId": "stepfun-plan-api-key-intl", "method": "API_KEY", "baseUrl": "https://api.stepfun.ai/step_plan/v1", "planUsage": true},
    {"provider": "synthetic", "choiceId": "synthetic-api-key", "method": "API_KEY"},
    {"provider": "tencent-tokenhub", "choiceId": "tokenhub-api-key", "method": "API_KEY"},
    {"provider": "tencent-tokenplan", "choiceId": "tokenplan-api-key", "method": "API_KEY"},
    {"provider": "together", "choiceId": "together-api-key", "method": "API_KEY"},
    {"provider": "venice", "choiceId": "venice-api-key", "method": "API_KEY"},
    {"provider": "vercel-ai-gateway", "choiceId": "ai-gateway-api-key", "method": "API_KEY"},
    {"provider": "vllm", "choiceId": "vllm-server", "method": "SERVER_ADDRESS", "label": "Server address"},
    {"provider": "volcengine", "choiceId": "volcengine-api-key", "method": "API_KEY"},
    {"provider": "volcengine-plan", "choiceId": "volcengine-plan-api-key", "method": "API_KEY", "label": "Volcano Engine Coding Plan API key", "planUsage": true},
    {"provider": "xai", "choiceId": "xai-api-key", "method": "API_KEY"},
    {
      "provider": "xai", "choiceId": "xai-oauth", "method": "DEVICE_CODE", "baseUrl": "https://cli-chat-proxy.grok.com/v1",
      "keyHeader": "BEARER", "planUsage": true,
      "identityHeaders": {"X-XAI-Token-Auth": "xai-grok-cli", "x-grok-client-version": "1.0.13"},
      "borrowedIdentity": "xai", "defaultOn": false,
      "termsLinks": [
        {"label": "X-Subscription Terms of Service", "url": "https://x.ai/legal/x-subscription-terms-of-service"},
        {"label": "Acceptable Use Policy", "url": "https://x.ai/legal/acceptable-use-policy"},
        {"label": "Bundled Subscription Terms", "url": "https://x.ai/legal/bundled-subscription-terms"}
      ]
    },
    {"provider": "xiaomi", "choiceId": "xiaomi-api-key", "method": "API_KEY"},
    {"provider": "xiaomi-token-plan", "choiceId": "xiaomi-token-plan-ams", "method": "API_KEY", "baseUrl": "https://token-plan-ams.xiaomimimo.com/v1", "planUsage": true},
    {"provider": "xiaomi-token-plan", "choiceId": "xiaomi-token-plan-cn", "method": "API_KEY", "baseUrl": "https://token-plan-cn.xiaomimimo.com/v1", "planUsage": true},
    {"provider": "xiaomi-token-plan", "choiceId": "xiaomi-token-plan-sgp", "method": "API_KEY", "baseUrl": "https://token-plan-sgp.xiaomimimo.com/v1", "planUsage": true},
    {"provider": "zai", "choiceId": "zai-api-key", "method": "API_KEY", "baseUrl": "https://api.z.ai/api/paas/v4"},
    {"provider": "zai", "choiceId": "zai-coding-global", "method": "API_KEY", "baseUrl": "https://api.z.ai/api/coding/paas/v4", "planUsage": true},
    {"provider": "zai", "choiceId": "zai-coding-cn", "method": "API_KEY", "baseUrl": "https://open.bigmodel.cn/api/coding/paas/v4", "planUsage": true},
    {"provider": "zai", "choiceId": "zai-global", "method": "API_KEY", "baseUrl": "https://api.z.ai/api/paas/v4"},
    {"provider": "zai", "choiceId": "zai-cn", "method": "API_KEY", "baseUrl": "https://open.bigmodel.cn/api/paas/v4"}
  ],
  "providers": [
    {
      "id": "amazon-bedrock", "plugin": "amazon-bedrock", "label": "Amazon Bedrock", "style": "BEDROCK",
      "baseUrl": "https://bedrock-runtime.{region}.amazonaws.com",
      "hosts": ["bedrock-runtime.{region}.amazonaws.com", "bedrock.{region}.amazonaws.com"],
      "discovery": "REFRESHABLE",
      "regions": [
        {"id": "us-east-1", "label": "US East (N. Virginia)", "baseUrl": "https://bedrock-runtime.us-east-1.amazonaws.com"},
        {"id": "us-east-2", "label": "US East (Ohio)", "baseUrl": "https://bedrock-runtime.us-east-2.amazonaws.com"},
        {"id": "us-west-2", "label": "US West (Oregon)", "baseUrl": "https://bedrock-runtime.us-west-2.amazonaws.com"},
        {"id": "ap-northeast-1", "label": "Asia Pacific (Tokyo)", "baseUrl": "https://bedrock-runtime.ap-northeast-1.amazonaws.com"},
        {"id": "ap-south-1", "label": "Asia Pacific (Mumbai)", "baseUrl": "https://bedrock-runtime.ap-south-1.amazonaws.com"},
        {"id": "ap-southeast-3", "label": "Asia Pacific (Jakarta)", "baseUrl": "https://bedrock-runtime.ap-southeast-3.amazonaws.com"},
        {"id": "eu-central-1", "label": "Europe (Frankfurt)", "baseUrl": "https://bedrock-runtime.eu-central-1.amazonaws.com"},
        {"id": "eu-west-1", "label": "Europe (Ireland)", "baseUrl": "https://bedrock-runtime.eu-west-1.amazonaws.com"},
        {"id": "eu-west-2", "label": "Europe (London)", "baseUrl": "https://bedrock-runtime.eu-west-2.amazonaws.com"},
        {"id": "eu-south-1", "label": "Europe (Milan)", "baseUrl": "https://bedrock-runtime.eu-south-1.amazonaws.com"},
        {"id": "eu-north-1", "label": "Europe (Stockholm)", "baseUrl": "https://bedrock-runtime.eu-north-1.amazonaws.com"},
        {"id": "sa-east-1", "label": "South America (São Paulo)", "baseUrl": "https://bedrock-runtime.sa-east-1.amazonaws.com"}
      ]
    },
    {
      "id": "amazon-bedrock-mantle", "plugin": "amazon-bedrock-mantle", "label": "Amazon Bedrock Mantle", "style": "CHAT_COMPLETIONS",
      "baseUrl": "https://bedrock-mantle.{region}.api.aws/v1",
      "hosts": ["bedrock-mantle.{region}.api.aws"],
      "discovery": "REFRESHABLE",
      "regions": [
        {"id": "us-east-1", "label": "US East (N. Virginia)", "baseUrl": "https://bedrock-mantle.us-east-1.api.aws/v1"},
        {"id": "us-east-2", "label": "US East (Ohio)", "baseUrl": "https://bedrock-mantle.us-east-2.api.aws/v1"},
        {"id": "us-west-2", "label": "US West (Oregon)", "baseUrl": "https://bedrock-mantle.us-west-2.api.aws/v1"},
        {"id": "ap-northeast-1", "label": "Asia Pacific (Tokyo)", "baseUrl": "https://bedrock-mantle.ap-northeast-1.api.aws/v1"},
        {"id": "ap-south-1", "label": "Asia Pacific (Mumbai)", "baseUrl": "https://bedrock-mantle.ap-south-1.api.aws/v1"},
        {"id": "ap-southeast-3", "label": "Asia Pacific (Jakarta)", "baseUrl": "https://bedrock-mantle.ap-southeast-3.api.aws/v1"},
        {"id": "eu-central-1", "label": "Europe (Frankfurt)", "baseUrl": "https://bedrock-mantle.eu-central-1.api.aws/v1"},
        {"id": "eu-west-1", "label": "Europe (Ireland)", "baseUrl": "https://bedrock-mantle.eu-west-1.api.aws/v1"},
        {"id": "eu-west-2", "label": "Europe (London)", "baseUrl": "https://bedrock-mantle.eu-west-2.api.aws/v1"},
        {"id": "eu-south-1", "label": "Europe (Milan)", "baseUrl": "https://bedrock-mantle.eu-south-1.api.aws/v1"},
        {"id": "eu-north-1", "label": "Europe (Stockholm)", "baseUrl": "https://bedrock-mantle.eu-north-1.api.aws/v1"},
        {"id": "sa-east-1", "label": "South America (São Paulo)", "baseUrl": "https://bedrock-mantle.sa-east-1.api.aws/v1"}
      ],
      "models": [
        {
          "id": "anthropic.claude-mythos-5", "name": "Claude Mythos 5", "style": "ANTHROPIC", "baseUrl": "https://bedrock-mantle.{region}.api.aws/anthropic",
          "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 10.0, "output": 50.0, "cacheRead": 1.0, "cacheWrite": 12.5}],
          "thinkingLevelMap": {"off": "low", "minimal": "low", "xhigh": "xhigh", "max": "max"}
        },
        {
          "id": "anthropic.claude-mythos-preview", "name": "Claude Mythos Preview", "style": "ANTHROPIC", "baseUrl": "https://bedrock-mantle.{region}.api.aws/anthropic",
          "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 1000000, "maxTokens": 128000
        },
        {
          "id": "anthropic.claude-opus-4-7", "name": "Claude Opus 4.7", "style": "ANTHROPIC", "baseUrl": "https://bedrock-mantle.{region}.api.aws/anthropic",
          "input": ["TEXT", "IMAGE"], "reasoning": false, "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25}]
        },
        {
          "id": "anthropic.claude-opus-5", "name": "Claude Opus 5", "style": "ANTHROPIC", "baseUrl": "https://bedrock-mantle.{region}.api.aws/anthropic",
          "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25}],
          "thinkingLevelMap": {"xhigh": "xhigh", "max": "max"}
        },
        {
          "id": "anthropic.claude-sonnet-5", "name": "Claude Sonnet 5", "style": "ANTHROPIC", "baseUrl": "https://bedrock-mantle.{region}.api.aws/anthropic",
          "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [
            {"input": 2.0, "output": 10.0, "cacheRead": 0.2, "cacheWrite": 2.5, "validUntil": "2026-09-01"},
            {"input": 3.0, "output": 15.0, "cacheRead": 0.3, "cacheWrite": 3.75, "validFrom": "2026-09-01"}
          ],
          "thinkingLevelMap": {"off": "low", "minimal": "low", "xhigh": "xhigh", "max": "max"}
        }
      ]
    },
    {"id": "arcee", "style": "CHAT_COMPLETIONS", "baseUrl": "https://api.arcee.ai/api/v1"},
    {"id": "bifrost", "style": "CHAT_COMPLETIONS"},
    {"id": "byteplus-plan", "label": "BytePlus Coding Plan"},
    {
      "id": "google-vertex", "plugin": "google", "label": "Google Vertex AI", "style": "VERTEX",
      "baseUrl": "https://{location}-aiplatform.googleapis.com",
      "discovery": "STATIC", "modelsFrom": "google", "defaultModel": "claude-sonnet-4-6",
      "regions": [
        {"id": "global", "label": "Global", "baseUrl": "https://aiplatform.googleapis.com"},
        {"id": "us", "label": "United States (multi-region)", "baseUrl": "https://aiplatform.us.rep.googleapis.com"},
        {"id": "eu", "label": "Europe (multi-region)", "baseUrl": "https://aiplatform.eu.rep.googleapis.com"}
      ],
      "models": [
        {
          "id": "claude-fable-5", "name": "Claude Fable 5", "style": "ANTHROPIC", "input": ["TEXT", "IMAGE"], "reasoning": true,
          "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 10.0, "output": 50.0, "cacheRead": 1.0, "cacheWrite": 12.5}],
          "thinkingLevelMap": {"off": "low", "minimal": "low", "xhigh": "xhigh", "max": "max"}
        },
        {
          "id": "claude-mythos-5", "name": "Claude Mythos 5", "style": "ANTHROPIC", "input": ["TEXT", "IMAGE"], "reasoning": true,
          "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 10.0, "output": 50.0, "cacheRead": 1.0, "cacheWrite": 12.5}],
          "thinkingLevelMap": {"off": "low", "minimal": "low", "xhigh": "xhigh", "max": "max"}
        },
        {
          "id": "claude-opus-4-6", "name": "Claude Opus 4.6", "style": "ANTHROPIC", "input": ["TEXT", "IMAGE"], "reasoning": true,
          "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25}],
          "thinkingLevelMap": {"xhigh": null, "max": "max"}
        },
        {
          "id": "claude-opus-4-8", "name": "Claude Opus 4.8", "style": "ANTHROPIC", "input": ["TEXT", "IMAGE"], "reasoning": true,
          "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25}],
          "thinkingLevelMap": {"xhigh": "xhigh", "max": "max"}
        },
        {
          "id": "claude-opus-5", "name": "Claude Opus 5", "style": "ANTHROPIC", "input": ["TEXT", "IMAGE"], "reasoning": true,
          "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 5.5, "output": 27.5, "cacheRead": 0.55, "cacheWrite": 6.875}],
          "thinkingLevelMap": {"xhigh": "xhigh", "max": "max"}
        },
        {
          "id": "claude-sonnet-4-6", "name": "Claude Sonnet 4.6", "style": "ANTHROPIC", "input": ["TEXT", "IMAGE"], "reasoning": true,
          "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 3.0, "output": 15.0, "cacheRead": 0.3, "cacheWrite": 3.75}],
          "thinkingLevelMap": {"xhigh": null, "max": "max"}
        },
        {
          "id": "claude-sonnet-5", "name": "Claude Sonnet 5", "style": "ANTHROPIC", "input": ["TEXT", "IMAGE"], "reasoning": true,
          "contextWindow": 1000000, "maxTokens": 128000,
          "prices": [{"input": 2.2, "output": 11.0, "cacheRead": 0.22, "cacheWrite": 2.75}],
          "thinkingLevelMap": {"xhigh": "xhigh", "max": "max"}
        }
      ]
    },
    {"id": "litellm", "style": "CHAT_COMPLETIONS"},
    {"id": "llama-cpp", "label": "llama.cpp", "style": "CHAT_COMPLETIONS"},
    {"id": "lmstudio", "style": "CHAT_COMPLETIONS"},
    {"id": "microsoft-foundry", "label": "Microsoft Foundry", "style": "AZURE"},
    {
      "id": "minimax", "style": "ANTHROPIC", "baseUrl": "https://api.minimax.io/anthropic", "defaultModel": "MiniMax-M3",
      "models": [
        {
          "id": "MiniMax-M2.7", "name": "MiniMax M2.7", "reasoning": true, "contextWindow": 204800, "maxTokens": 131072,
          "prices": [{"input": 0.3, "output": 1.2, "cacheRead": 0.06, "cacheWrite": 0.375}]
        },
        {
          "id": "MiniMax-M2.7-highspeed", "name": "MiniMax M2.7 Highspeed", "reasoning": true, "contextWindow": 204800, "maxTokens": 131072,
          "prices": [{"input": 0.6, "output": 2.4, "cacheRead": 0.06, "cacheWrite": 0.375}]
        },
        {
          "id": "MiniMax-M3", "name": "MiniMax M3", "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 1000000, "maxTokens": 131072,
          "prices": [{"input": 0.6, "output": 2.4, "cacheRead": 0.12, "cacheWrite": 0.0}],
          "compat": {"supportsTemperature": false}
        },
        {
          "id": "MiniMax-M3.1-Flash", "name": "MiniMax M3.1 Flash", "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 1000000, "maxTokens": 131072,
          "prices": [{"input": 0.15, "output": 0.6, "cacheRead": 0.015, "cacheWrite": 0.0}],
          "compat": {"supportsTemperature": false}
        },
        {
          "id": "MiniMax-M3.1-Flash-Preview", "name": "MiniMax M3.1 Flash Preview", "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 1000000,
          "maxTokens": 131072, "compat": {"supportsTemperature": false}
        }
      ]
    },
    {"id": "minimax-portal", "label": "MiniMax M Plan", "style": "ANTHROPIC", "baseUrl": "https://api.minimax.io/anthropic", "modelsFrom": "minimax", "defaultModel": "MiniMax-M3.1-Flash"},
    {"id": "ollama", "style": "OLLAMA"},
    {"id": "omlx", "style": "CHAT_COMPLETIONS"},
    {"id": "openrouter", "style": "CHAT_COMPLETIONS", "baseUrl": "https://openrouter.ai/api/v1"},
    {"id": "sglang", "style": "CHAT_COMPLETIONS"},
    {
      "id": "synthetic", "style": "ANTHROPIC", "baseUrl": "https://api.synthetic.new/anthropic", "discovery": "STATIC",
      "defaultModel": "hf:MiniMaxAI/MiniMax-M3",
      "models": [
        {"id": "hf:MiniMaxAI/MiniMax-M3", "name": "MiniMax M3", "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 262144, "maxTokens": 65536},
        {"id": "hf:moonshotai/Kimi-K2.7-Code", "name": "Kimi K2.7 Code", "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 262144, "maxTokens": 8192},
        {"id": "hf:nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-NVFP4", "name": "NVIDIA Nemotron 3 Super 120B A12B", "reasoning": true, "contextWindow": 262144, "maxTokens": 8192},
        {"id": "hf:openai/gpt-oss-120b", "name": "GPT OSS 120B", "reasoning": true, "contextWindow": 131072, "maxTokens": 8192},
        {"id": "hf:Qwen/Qwen3.6-27B", "name": "Qwen3.6 27B", "input": ["TEXT", "IMAGE"], "reasoning": true, "contextWindow": 262144, "maxTokens": 81920},
        {"id": "hf:zai-org/GLM-4.7-Flash", "name": "GLM-4.7 Flash", "reasoning": true, "contextWindow": 196608, "maxTokens": 131072},
        {"id": "hf:zai-org/GLM-5.2", "name": "GLM-5.2", "reasoning": true, "contextWindow": 524288, "maxTokens": 131072}
      ]
    },
    {"id": "vllm", "style": "CHAT_COMPLETIONS"},
    {"id": "volcengine-plan", "label": "Volcano Engine Coding Plan"}
  ],
  "localServers": [
    {"id": "ollama", "label": "Ollama", "kind": "OLLAMA", "style": "OLLAMA", "defaultUrl": "http://127.0.0.1:11434"},
    {"id": "lmstudio", "label": "LM Studio", "kind": "LM_STUDIO", "style": "CHAT_COMPLETIONS", "defaultUrl": "http://127.0.0.1:1234/v1"},
    {"id": "vllm", "label": "vLLM", "kind": "VLLM", "style": "CHAT_COMPLETIONS", "defaultUrl": "http://127.0.0.1:8000/v1"},
    {"id": "sglang", "label": "SGLang", "kind": "SGLANG", "style": "CHAT_COMPLETIONS", "defaultUrl": "http://127.0.0.1:30000/v1"},
    {"id": "llama-cpp", "label": "llama.cpp", "kind": "LLAMA_CPP", "style": "CHAT_COMPLETIONS", "defaultUrl": "http://127.0.0.1:8080/v1"},
    {"id": "omlx", "label": "oMLX", "kind": "OMLX", "style": "CHAT_COMPLETIONS", "defaultUrl": "http://127.0.0.1:8000/v1"},
    {"id": "litellm", "label": "LiteLLM", "kind": "LITELLM", "style": "CHAT_COMPLETIONS", "defaultUrl": "http://127.0.0.1:4000/v1"}
  ],
  "models": [
    {
      "provider": "google", "model": "gemini-2.5-flash", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [{"input": 0.3, "output": 2.5, "cacheRead": 0.03}]
    },
    {
      "provider": "google", "model": "gemini-2.5-flash-lite", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [{"input": 0.1, "output": 0.4, "cacheRead": 0.01}]
    },
    {
      "provider": "google", "model": "gemini-2.5-pro", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [{"input": 1.25, "output": 10.0, "cacheRead": 0.125, "tiers": [{"aboveInputTokens": 200000, "input": 2.5, "output": 15.0, "cacheRead": 0.25}]}]
    },
    {
      "provider": "google", "model": "gemini-3-flash-preview", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [{"input": 0.5, "output": 3.0, "cacheRead": 0.05}]
    },
    {
      "provider": "google", "model": "gemini-3.1-flash-lite", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [{"input": 0.25, "output": 1.5, "cacheRead": 0.025}]
    },
    {
      "provider": "google", "model": "gemini-3.1-pro-preview", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [{"input": 2.0, "output": 12.0, "cacheRead": 0.2, "tiers": [{"aboveInputTokens": 200000, "input": 4.0, "output": 18.0, "cacheRead": 0.4}]}]
    },
    {
      "provider": "google", "model": "gemini-3.5-flash-lite", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [{"input": 0.3, "output": 2.5, "cacheRead": 0.03}]
    },
    {
      "provider": "google", "model": "gemini-3.6-flash", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [
        {"input": 0.75, "output": 3.75, "cacheRead": 0.075, "validUntil": "2027-01-01"},
        {"input": 1.5, "output": 7.5, "cacheRead": 0.15, "validFrom": "2027-01-01"}
      ]
    },
    {
      "provider": "google", "model": "gemini-3.8-flash", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [
        {"input": 0.75, "output": 3.75, "cacheRead": 0.075, "validUntil": "2027-01-01"},
        {"input": 1.5, "output": 7.5, "cacheRead": 0.15, "validFrom": "2027-01-01"}
      ]
    },
    {
      "provider": "google", "model": "gemini-4-argon", "pricesCopiedOn": "2026-11-17", "pricesSource": "https://ai.google.dev/gemini-api/docs/pricing",
      "prices": [
        {"input": 2.0, "output": 10.0, "cacheRead": 0.1, "validUntil": "2026-12-01"},
        {"input": 4.0, "output": 20.0, "cacheRead": 0.2, "validFrom": "2026-12-01"}
      ]
    }
  ],
  "freeModels": [],
  "unknownPrice": {"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25},
  "copilot": {
    "pricesCopiedOn": "2026-11-17",
    "pricesSource": "https://docs.github.com/en/copilot/reference/copilot-billing/models-and-pricing",
    "models": [
      {"name": "GPT-6 Luna", "prices": [{"input": 0.1, "output": 0.5, "cacheRead": 0.01, "cacheWrite": 0.125, "tiers": [{"aboveInputTokens": 272000, "input": 0.2, "output": 0.75, "cacheRead": 0.02, "cacheWrite": 0.25}]}]},
      {"name": "GPT-6.1 Terra", "prices": [{"input": 1.0, "output": 5.0, "cacheRead": 0.05, "cacheWrite": 1.25, "tiers": [{"aboveInputTokens": 272000, "input": 2.0, "output": 7.5, "cacheRead": 0.1, "cacheWrite": 2.5}]}]},
      {"name": "GPT-6.1 Sol", "prices": [{"input": 2.0, "output": 10.0, "cacheRead": 0.1, "cacheWrite": 2.5, "tiers": [{"aboveInputTokens": 272000, "input": 4.0, "output": 15.0, "cacheRead": 0.2, "cacheWrite": 5.0}]}]},
      {"name": "GPT-6 Sol", "prices": [{"input": 2.0, "output": 10.0, "cacheRead": 0.2, "cacheWrite": 2.5, "tiers": [{"aboveInputTokens": 272000, "input": 4.0, "output": 15.0, "cacheRead": 0.4, "cacheWrite": 5.0}]}]},
      {"name": "GPT-6 Astra", "prices": [{"input": 10.0, "output": 50.0, "cacheRead": 1.0, "cacheWrite": 12.5, "tiers": [{"aboveInputTokens": 272000, "input": 20.0, "output": 75.0, "cacheRead": 2.0, "cacheWrite": 25.0}]}]},
      {"name": "GPT-5.6 Luna", "id": "gpt-5.6-luna", "prices": [{"input": 0.2, "output": 1.2, "cacheRead": 0.02, "cacheWrite": 0.25, "tiers": [{"aboveInputTokens": 200000, "input": 0.4, "output": 1.8, "cacheRead": 0.04, "cacheWrite": 0.5}]}]},
      {"name": "GPT-5.6 Sol", "id": "gpt-5.6-sol", "prices": [{"input": 4.0, "output": 20.0, "cacheRead": 0.4, "cacheWrite": 5.0, "tiers": [{"aboveInputTokens": 272000, "input": 8.0, "output": 30.0, "cacheRead": 0.8, "cacheWrite": 10.0}]}]},
      {"name": "GPT-5.3-Codex", "id": "gpt-5.3-codex", "prices": [{"input": 1.75, "output": 14.0, "cacheRead": 0.175}]},
      {"name": "GPT-5.4 nano", "prices": [{"input": 0.2, "output": 1.25, "cacheRead": 0.02}]},
      {"name": "Claude Haiku 5.5", "prices": [{"input": 0.1, "output": 0.5, "cacheRead": 0.01, "cacheWrite": 0.125, "tiers": [{"aboveInputTokens": 100000, "input": 0.5, "output": 2.5, "cacheRead": 0.05, "cacheWrite": 0.625}]}]},
      {"name": "Claude Sonnet 5.5", "id": "claude-sonnet-5.5", "prices": [{"input": 2.0, "output": 10.0, "cacheRead": 0.1, "cacheWrite": 2.5}]},
      {"name": "Claude Opus 5.5", "id": "claude-opus-5.5", "prices": [{"input": 4.0, "output": 20.0, "cacheRead": 0.2, "cacheWrite": 5.0}]},
      {"name": "Claude Sonnet 5", "id": "claude-sonnet-5", "prices": [{"input": 2.0, "output": 10.0, "cacheRead": 0.2, "cacheWrite": 2.5}]},
      {"name": "Claude Opus 4.8", "id": "claude-opus-4.8", "prices": [{"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25}]},
      {"name": "Claude Opus 4.8 (fast mode) (preview)", "prices": [{"input": 10.0, "output": 50.0, "cacheRead": 1.0, "cacheWrite": 12.5}]},
      {"name": "Claude Sonnet 4.6", "id": "claude-sonnet-4.6", "prices": [{"input": 3.0, "output": 15.0, "cacheRead": 0.3, "cacheWrite": 3.75}]},
      {"name": "Claude Sonnet 4", "prices": [{"input": 3.0, "output": 15.0, "cacheRead": 0.3, "cacheWrite": 3.75}]},
      {"name": "Claude Haiku 4.5", "id": "claude-haiku-4.5", "prices": [{"input": 1.0, "output": 5.0, "cacheRead": 0.1, "cacheWrite": 1.25}]},
      {"name": "Claude Fable 5", "id": "claude-fable-5", "prices": [{"input": 10.0, "output": 50.0, "cacheRead": 1.0, "cacheWrite": 12.5}]},
      {"name": "Claude Fable 5.1", "prices": [{"input": 10.0, "output": 50.0, "cacheRead": 0.25, "cacheWrite": 12.5}]},
      {"name": "Claude Fable 5.2", "prices": [{"input": 8.0, "output": 40.0, "cacheRead": 0.2, "cacheWrite": 10.0}]},
      {"name": "Gemini 3.8 Flash", "prices": [{"input": 0.75, "output": 3.75, "cacheRead": 0.075, "validUntil": "2027-01-01"}]},
      {"name": "Gemini 4 Hydrogen", "prices": [{"input": 1.5, "output": 7.5, "cacheRead": 0.075}]},
      {"name": "Gemini 4 Argon", "prices": [{"input": 2.0, "output": 10.0, "cacheRead": 0.1, "validUntil": "2026-12-01"}]},
      {"name": "Gemini 4 Carbon", "prices": [
        {"input": 2.0, "output": 10.0, "cacheRead": 0.1, "validUntil": "2026-12-01"},
        {"input": 4.0, "output": 20.0, "cacheRead": 0.2, "validFrom": "2026-12-01"}
      ]},
      {"name": "Grok 4.5", "prices": [{"input": 2.0, "output": 6.0, "cacheRead": 0.5, "tiers": [{"aboveInputTokens": 200000, "input": 4.0, "output": 12.0, "cacheRead": 1.0}]}]},
      {"name": "Grok 4.6", "prices": [{"input": 2.0, "output": 6.0, "cacheRead": 0.5, "tiers": [{"aboveInputTokens": 200000, "input": 4.0, "output": 12.0, "cacheRead": 1.0}]}]},
      {"name": "Grok 4.7", "prices": [{"input": 2.0, "output": 6.0, "cacheRead": 0.5, "tiers": [{"aboveInputTokens": 200000, "input": 4.0, "output": 12.0, "cacheRead": 1.0}]}]},
      {"name": "Kimi K3", "prices": [{"input": 3.0, "output": 15.0, "cacheRead": 0.3}]},
      {"name": "MAI-Code-1.1-Flash", "prices": [{"input": 0.2, "output": 1.2, "cacheRead": 0.02}]}
    ],
    "lightweight": ["GPT-6 Luna", "Claude Haiku 5.5"],
    "versatile": ["GPT-6.1 Terra"],
    "runtimeHeaders": {
      "Editor-Version": "vscode/1.107.0",
      "Editor-Plugin-Version": "copilot-chat/0.35.0",
      "User-Agent": "GitHubCopilotChat/0.35.0",
      "Accept-Encoding": "identity",
      "Openai-Organization": "github-copilot",
      "Copilot-Integration-Id": "copilot-developer-cli"
    },
    "ideHeaders": {
      "Accept-Encoding": "identity",
      "Editor-Version": "vscode/1.107.0",
      "Editor-Plugin-Version": "copilot-chat/0.35.0",
      "User-Agent": "GitHubCopilotChat/0.35.0"
    }
  }
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with 55 tests in `:core:providers` (49 + `OverridesTest` 6).

- [ ] **Step 5: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
Kestrel supports OpenClaw's chat providers (D9). On 11 November 2026, OpenClaw's main branch had 64 plugin manifests in the "models" category (B26). The manifests that name a request style use five of them: 32 providers use OpenAI Chat Completions, 5 use OpenAI Responses, 3 use Anthropic Messages, 1 uses Gemini and 1 uses Ollama. The other providers define their request style in code. Examples are Bedrock, Vertex, Azure/Foundry and the local servers.
```

with

```markdown
Kestrel supports OpenClaw's chat providers (D9). On 11 November 2026, OpenClaw's main branch had 64 plugin manifests in the "models" category (B26). The manifests that name a request style use five of them: 32 providers use OpenAI Chat Completions, 5 use OpenAI Responses, 3 use Anthropic Messages, 1 uses Gemini and 1 uses Ollama. The other providers define their request style in code. Examples are Bedrock, Vertex, Azure/Foundry and the local servers; `catalog/overrides.json` completes them (section 10.2).
```

and replace

```markdown
These are excluded, because they need a desktop program or are not chat models:
```

with

```markdown
These are excluded. Most of them need a desktop program or are not chat models, and the others give their reason:
```

and replace

```markdown
- OpenAI's "Sign in with ChatGPT (Beta)", which authorises OpenClaw itself as an app. Codex sign-in covers the same subscription.
- Levanto, Liquid AI's decision models, Rerank, and all image, video, music and speech providers.
```

with

```markdown
- OpenClaw's own "Sign in with ChatGPT (Beta)" (the choice `openai-token-sharing`), which authorises OpenClaw itself as an app. Kestrel registers itself instead, through OpenAI's official Sign in with ChatGPT for open-source apps (B49). Kestrel does not use the Codex CLI's sign-ins either, because they borrow the Codex CLI's client (B48).
- Levanto, the decision models of Liquid AI, Celeris, Fastino, Nace and Perplexity, the decision models in other providers' catalogs, Rerank, and all image, video, music and speech providers.
- Radius, whose only request style is OpenClaw's own `pi-messages` format.
- Chutes' browser sign-in, which needs the owner's own OAuth app at Chutes; Chutes keeps its API keys.
- Anthropic's setup-token, which Kestrel never offers: Anthropic's terms forbid third parties to route requests through subscription credentials (B31).
- Kimi Code, whose only choice would send the Claude Code `User-Agent` of OpenClaw's manifest, while Kimi forbids altering the client identifier and limits Kimi Code to interactive use in coding tools (B51). Kestrel never sends Claude Code's User-Agent or any other client identity that a provider forbids; the borrowed sign-ins that the owner chose (section 10.4) keep their headers. Kimi's models stay available through Moonshot's API keys.
- Cloudflare AI Gateway, whose base URL holds the owner's Cloudflare account ID and gateway ID, which the network log would record (section 13.5).
```

and replace

```markdown
Microsoft Foundry's Entra ID sign-in and Meta's "Muse Code" sign-in are included only if OpenClaw's implementation works without a desktop tool. Otherwise those providers use API keys. Sub-project 4 decides this.
```

with

```markdown
Microsoft Foundry's Entra ID sign-in runs the Azure CLI (B42), and Meta's "Muse Code" sign-in runs Meta's closed `muse` program, whose credential Meta's terms limit to Meta's coding harness (B41). Both need a desktop program, so both providers use API keys.
```

and replace

```markdown
| B29 | Copilot prices per million tokens: GPT-6 Luna $0.10 input and $0.50 output; Claude Haiku 5.5 $0.10 and $0.50 up to 100K tokens of context; GPT-6.1 Terra $1 and $5; GPT-6.1 Sol $2 and $10. | https://docs.github.com/en/copilot/reference/copilot-billing/models-and-pricing | ✔ |
```

with

```markdown
| B29 | Copilot prices per million tokens on 17 November 2026, as input, cached input, cache write and output: GPT-6 Luna $0.10, $0.01, $0.125 and $0.50, and $0.20, $0.02, $0.25 and $0.75 above 272K tokens of input; Claude Haiku 5.5 $0.10, $0.01, $0.125 and $0.50, and $0.50, $0.05, $0.625 and $2.50 above 100K; GPT-6.1 Terra $1, $0.05, $1.25 and $5, and $2, $0.10, $2.50 and $7.50 above 272K; GPT-6.1 Sol $2, $0.10, $2.50 and $10, and $4, $0.20, $5 and $15 above 272K. Gemini 3.8 Flash's promotional price ($0.75 and $3.75) runs through 31 December 2026, and Gemini 4 Argon's and Carbon's through 30 November 2026. `catalog/overrides.json` holds the whole table. | https://docs.github.com/en/copilot/reference/copilot-billing/models-and-pricing | ✔ |
```

and replace

```markdown
| B31 | Anthropic setup-token requests identify as the Claude Code client. Since Anthropic's update of 15 June 2026, third-party app usage draws from the subscription's usage limits. | OpenClaw `docs/providers/anthropic.md` and `docs/providers/claude-max-api-proxy.md` (secondary sources) | ✔ |
```

with

```markdown
| B31 | Anthropic setup-token requests identify as the Claude Code client. Since Anthropic's update of 15 June 2026, third-party app usage draws from the subscription's usage limits. Anthropic's terms say that it "does not permit third-party developers to offer Claude.ai login into their own applications, or to route requests through Free, Pro, Max, or Ultra plan credentials on behalf of their users". | OpenClaw `docs/providers/anthropic.md` and `docs/providers/claude-max-api-proxy.md` (secondary sources); https://code.claude.com/docs/en/legal-and-compliance | ✔ |
```

and replace

```markdown
| B39 | For NeMo transducers such as Parakeet-TDT, sherpa-onnx applies boost words only with `modified_beam_search`, `modelingUnit = "bpe"` and a `bpeVocab` file, which the Parakeet package lacks; a vocabulary with one line `<piece>\t-1.0` for each line of `tokens.txt` works. With boost words and an empty `modelingUnit`, sherpa-onnx ends the process. `OfflineRecognizer.createStream(hotwords)` takes the boost words of one utterance, separated by `/`. | `sherpa-onnx/csrc/offline-recognizer-transducer-nemo-impl.h` and `utils.cc` at tag v2.0.1, https://github.com/k2-fsa/sherpa-onnx/pull/3077, and Kestrel's tests in plan 2A | ✔ |
```

with

```markdown
| B39 | For NeMo transducers such as Parakeet-TDT, sherpa-onnx applies boost words only with `modified_beam_search`, `modelingUnit = "bpe"` and a `bpeVocab` file, which the Parakeet package lacks; a vocabulary with one line `<piece>\t-1.0` for each line of `tokens.txt` works. With boost words and an empty `modelingUnit`, sherpa-onnx ends the process. `OfflineRecognizer.createStream(hotwords)` takes the boost words of one utterance, separated by `/`. | `sherpa-onnx/csrc/offline-recognizer-transducer-nemo-impl.h` and `utils.cc` at tag v2.0.1, https://github.com/k2-fsa/sherpa-onnx/pull/3077, and Kestrel's tests in plan 2A | ✔ |
| B40 | xAI's OAuth sign-in in OpenClaw is a device code (RFC 8628) through OIDC discovery at `https://auth.x.ai`, with the Grok CLI's client `b1a00492-073a-47ea-816f-4c329264a828`. Its requests go to `https://cli-chat-proxy.grok.com/v1` with `X-XAI-Token-Auth: xai-grok-cli`, `x-grok-client-version: 1.0.13` and `x-grok-model-override: <model ID>`. xAI's API keys use `https://api.x.ai/v1`. | OpenClaw `extensions/xai/xai-oauth.ts` and `stream.ts` at the pinned commit | † |
| B41 | Meta's "Muse Code" sign-in runs Meta's closed `muse` program, and Meta's terms limit that credential to Meta's coding harness. Meta's API keys use `https://api.meta.ai/v1` with OpenAI Responses. | OpenClaw `extensions/meta/muse-login.ts`; https://dev.meta.ai/docs/muse-code/auth and https://dev.meta.ai/legal/terms-of-service | † |
| B42 | Microsoft Foundry's Entra ID sign-in in OpenClaw runs the Azure CLI. Azure OpenAI's v1 API (`<endpoint>/openai/v1`, without `api-version`) takes the deployment name as the model and the `api-key` header; Claude on Foundry uses `<endpoint>/anthropic` with `x-api-key`. | OpenClaw `extensions/microsoft-foundry/auth.ts` and `cli.ts`; https://learn.microsoft.com/en-us/azure/foundry/openai/api-version-lifecycle and https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-claude | † |
| B43 | A native app that receives an OAuth redirect on loopback should listen only on the loopback interface, on both `127.0.0.1` and `::1` for `localhost`, and close the listener after the response; the authorization server must accept any port. | RFC 8252, sections 7.3 and 8.3: https://www.rfc-editor.org/rfc/rfc8252.txt | † |
| B44 | Bedrock's Converse API is a plain HTTPS call. A Bedrock API key is sent as a bearer token, and IAM access keys sign with SigV4 under the signing name `bedrock`. Bedrock Mantle is OpenAI- and Anthropic-compatible with a bearer token and lists its models at `{base}/v1/models`; OpenClaw adds five Claude rows that the list lacks. | https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html, https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html; OpenClaw `extensions/amazon-bedrock-mantle/discovery.ts` | † |
| B45 | Vertex accepts a service account's JWT, signed with RS256 and exchanged at `https://oauth2.googleapis.com/token` for an access token that holds for one hour. Gemini uses `generateContent`; Claude uses `rawPredict` with `anthropic_version: vertex-2023-10-16`. | https://developers.google.com/identity/protocols/oauth2/service-account and https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/claude/use-claude | † |
| B46 | After a tool call, providers need their reasoning state back: Anthropic's thinking blocks unchanged, OpenAI Responses' reasoning items with `encrypted_content` when `store` is false, Gemini's `thoughtSignature` on the first function call of each step, and DeepSeek's and Kimi's `reasoning_content` on earlier turns when tools are sent. | https://platform.claude.com/docs/en/build-with-claude/thinking, https://developers.openai.com/api/docs/guides/reasoning, https://ai.google.dev/gemini-api/docs/thought-signatures and https://api-docs.deepseek.com/guides/thinking_mode | † |
| B47 | Each request style reports usage in its own fields. Normalised, the priced counts are uncached input, output (with reasoning), cache reads and cache writes; Anthropic's input count already leaves out the cache, while OpenAI's and Gemini's include it. | https://developers.openai.com/api/docs/guides/prompt-caching.md, https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md, https://ai.google.dev/api/generate-content.md.txt and https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_TokenUsage.html | † |
| B48 | OpenAI's, xAI's, MiniMax's and GitHub's published terms neither allow nor forbid a third-party app's reuse of the Codex CLI's, the Grok CLI's, OpenClaw's MiniMax and GitHub's Copilot OAuth clients. xAI's Acceptable Use Policy bars services that "appear to originate from" xAI, and GitHub may suspend an account at any time. | https://openai.com/policies/terms-of-use/, https://x.ai/legal/acceptable-use-policy, https://platform.minimax.io/protocol/terms-of-service and https://docs.github.com/en/site-policy/github-terms/github-terms-of-service | † |
| B49 | OpenAI's "Sign in with ChatGPT" offers ChatGPT plan usage to open-source and locally hosted apps. The first authorization registers the app (`client_id=dynamic_agent_client`) and returns an issued client ID; the redirect must be a loopback address on `127.0.0.1`, with PKCE; and the app then calls `https://api.openai.com/v1/responses` with `store: false` and `stream: true`. | https://developers.openai.com/siwc/token-sharing-open-source.md and https://developers.openai.com/siwc/llms-full.txt | † |
| B50 | Spike S6: on Android 17 with Chrome 145, a redirect from a public HTTPS page to `http://127.0.0.1:<port>/…` or `http://localhost:<port>/…` in a Custom Tab reaches the app's loopback server without a prompt, and `ACCESS_LOCAL_NETWORK` does not apply to loopback. Without a foreground service, the app is frozen about 72 seconds after the tab opens; ports 1455, 1456 and 3000 bind on both loopback addresses; and a foreground service may bring the app back to the front while the tab is in the app's task. | Kestrel's prototype P5 on kestrel-api37 (Chrome 145.0.7632.218), sub-project 4 | ✔ |
| B51 | Kimi Code's documentation says that tampering with the client identifier (User-Agent) is a violation that may suspend the membership's benefits, and its Community Guidelines forbid spoofing or altering client identity information and limit Kimi Code to interactive use in coding tools. OpenClaw's Kimi Code manifest sends `User-Agent: claude-code/0.1.0`, which a Kimi Code profile would therefore send. Kimi's Open Platform keys (`api.moonshot.ai`) are a separate system, used with Kestrel's own User-Agent. | https://www.kimi.com/code/docs/en/ and https://www.kimi.com/code/docs/en/kimi-code/community-guidelines.html; OpenClaw `extensions/kimi-coding/openclaw.plugin.json` at the pinned commit | † |
```

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` 55, `:tools:catalog-sync` 6; `:app` A₀, `:core:privacy` P₀ + 5, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add catalog/overrides.json core/providers/src/test/kotlin/dev/kestrel/providers/OverridesTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(catalog): add the overrides: exclusions, sign-in choices, providers defined in code, regions, local servers, prices and Copilot's table" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Profiles, credentials as data and discovered models

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt`, `ProvidersSetting.kt`, `ProfileHosts.kt`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/credentials/Credential.kt`, `CredentialCodec.kt`, `CredentialVault.kt`, `ApiKeys.kt`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/ModelDiscovery.kt`, `ModelList.kt`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/profiles/ProfileHostsTest.kt`, `ProvidersSettingTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/credentials/CredentialCodecTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/ModelListTest.kt`

**Interfaces:**
- Consumes: Task 4's `Catalog`, `AuthMethod`, `LocalServerKind`, `InputKind`, `Price` and `CatalogLoader.hostOf`; Task 2's `Hosts`; Task 1's `RequestStyle`; Tasks 5 and 6's catalog files (in `ProfileHostsTest`).
- Produces (package `dev.kestrel.providers.profiles`, serializable): `data class Profile(val id: String, val name: String, val providerId: String, val choiceId: String, val method: AuthMethod, val credentialId: String?, val baseUrl: String? = null, val region: String? = null, val localServer: LocalServerKind? = null, val lightweight: ProfileModel, val versatile: ProfileModel, val discoveredHosts: List<String> = emptyList(), val signedInAt: Long? = null)` with `fun model(role: ModelRole): ProfileModel`; `data class ProfileModel(val id: String, val style: RequestStyle? = null, val jsonActions: Boolean = false, val price: Price? = null, val images: Boolean? = null, val tools: Boolean? = null)`; `enum class ModelRole { LIGHTWEIGHT, VERSATILE }`; `data class ProvidersSetting(val profiles: List<Profile> = emptyList(), val defaultProfileId: String? = null, val signInSwitches: Map<String, Boolean> = emptyMap(), val chatgptHostId: String? = null, val chatgptRegistration: ChatGptRegistration? = null)` with `val defaultProfile: Profile?`, `fun switchedOn(identity: String, catalog: Catalog): Boolean` and `fun usable(profile: Profile, catalog: Catalog): Boolean`; `data class ChatGptRegistration(val clientId: String, val subject: String?)`; `object ProfileHosts { fun baseUrl(profile: Profile, catalog: Catalog): HttpUrl?; fun hosts(profile: Profile, catalog: Catalog): Set<String>; fun localServers(profile: Profile): Set<String> }`. Package `dev.kestrel.providers.credentials`: `@Serializable sealed interface Credential` with `data class ApiKey(val key: String)` (`"kind": "api-key"`), `data class OAuthToken(val access: String, val refresh: String?, val expiresAtMillis: Long?, val earliestRefreshAtMillis: Long? = null, val clientId: String? = null, val tokenEndpoint: String? = null, val region: String? = null)` (`oauth-token`), `data class GitHubToken(val token: String, val domain: String)` (`github-token`), `data class AwsAccessKeys(val accessKeyId: String, val secretAccessKey: String, val sessionToken: String? = null)` (`aws-access-keys`), `data class ServiceAccountKey(val clientEmail: String, val privateKeyPem: String, val privateKeyId: String?, val projectId: String)` (`service-account-key`); `object CredentialCodec { fun encode(credential: Credential): ByteArray; fun decode(bytes: ByteArray): Credential }` (decode throws `IllegalArgumentException("Not a stored credential")`); `interface CredentialVault { suspend fun read(id: String): Credential?; suspend fun write(id: String, credential: Credential); suspend fun delete(id: String) }`; `object ApiKeys { fun acceptable(key: String): Boolean }`. Package `dev.kestrel.providers.discovery`: `@Serializable data class DiscoveredModel(val id: String, val name: String, val input: Set<InputKind> = setOf(InputKind.TEXT), val reasoning: Boolean = false, val reasoningEfforts: List<String>? = null, val contextWindow: Int? = null, val maxTokens: Int? = null, val tools: Boolean? = null, val streaming: Boolean? = null, val style: RequestStyle? = null)`; `@Serializable data class ModelList(val models: List<DiscoveredModel> = emptyList(), val refreshedAt: Long? = null)`; `fun interface ModelDiscovery { suspend fun list(): List<DiscoveredModel> }`. Plan 4C's `TargetResolver` uses `ProfileHosts.baseUrl` as the one rule for the effective base URL, and its `CredentialManager` builds on `CredentialVault` and `Credential`.

Design D9, D13, D15, D16 and D22. A profile stores the choice that made it, so the choice's base URL, identity headers and hosts never reach a profile of the same provider that uses an API key (D9). `ProfileHosts.baseUrl` is D13's effective base URL: the profile's own base URL, else the choice's, else the base URL of the region that the profile chose when the provider lists that region, else the provider's; `{region}` and `{location}` are filled in from the profile, and only when the region matches `^[a-z0-9-]+$`, because it becomes part of a host (research r35). `ProfileHosts.hosts` gives D16's hosts of a saved profile: its base URL's host; the provider's hosts and its models' base URL hosts with the region filled in, but only when neither the profile nor the choice sets a base URL (so an xAI sign-in allows `cli-chat-proxy.grok.com` and not `api.x.ai`); the choice's refresh hosts; and the hosts that the sign-in and discovery accepted. Local hosts are left out of it, because they go to `NetworkPolicy.setLocalServers` through `localServers`, which takes a profile's own address only when it is local (D11). A borrowed identity's switch is the owner's choice, else the choice's `defaultOn` (D9). `CredentialCodec` writes the credential's kind as `"kind"`, and its error message never holds the bytes. `ApiKeys.acceptable` takes a trimmed key that is not empty and has only printable ASCII without spaces, because OkHttp refuses other header characters with an exception whose message holds the value (D9). Every type of this task that can carry a key, a token, an account, a project, a model name of the owner's own or an address prints only its kind, counts and lengths (D22).

- [ ] **Step 1: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/profiles/ProfileHostsTest.kt`:

```kotlin
package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.LocalServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Runs against the repository's catalog, so that it also checks the base URLs and hosts that overrides.json gives. */
class ProfileHostsTest {
    private val catalog: Catalog = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog")).let {
        CatalogLoader.load(it.resolve("providers.json").readText(), it.resolve("overrides.json").readText())
    }

    private fun profile(provider: String, choice: String, method: AuthMethod, baseUrl: String? = null, region: String? = null, discovered: List<String> = emptyList()) =
        Profile(
            id = "test-1",
            name = "Test",
            providerId = provider,
            choiceId = choice,
            method = method,
            credentialId = "test-credential",
            baseUrl = baseUrl,
            region = region,
            lightweight = ProfileModel("a"),
            versatile = ProfileModel("b"),
            discoveredHosts = discovered,
        )

    @Test
    fun anXaiSignInAllowsTheProxyAndNotTheApiWhileAKeyAllowsTheApi() {
        val signIn = profile("xai", "xai-oauth", AuthMethod.DEVICE_CODE, discovered = listOf("auth.x.ai"))
        val key = profile("xai", "xai-api-key", AuthMethod.API_KEY)

        assertEquals("https://cli-chat-proxy.grok.com/v1", ProfileHosts.baseUrl(signIn, catalog).toString())
        assertEquals(setOf("auth.x.ai", "cli-chat-proxy.grok.com"), ProfileHosts.hosts(signIn, catalog))
        assertEquals(setOf("api.x.ai"), ProfileHosts.hosts(key, catalog))
    }

    @Test
    fun aChatGptPlanProfileAllowsTheApiAndTheSignInHost() {
        val plan = profile("openai", "chatgpt-plan", AuthMethod.BROWSER)

        assertEquals("https://api.openai.com/v1", ProfileHosts.baseUrl(plan, catalog).toString())
        assertEquals(setOf("api.openai.com", "auth.openai.com"), ProfileHosts.hosts(plan, catalog))
    }

    @Test
    fun regionsFillTheBedrockMantleAndVertexHosts() {
        val bedrock = profile("amazon-bedrock", "bedrock-iam-keys", AuthMethod.IMPORTED, region = "eu-west-1")
        val mantle = profile("amazon-bedrock-mantle", "mantle-api-key", AuthMethod.API_KEY, region = "us-east-1")
        val vertexGlobal = profile("google-vertex", "vertex-service-account", AuthMethod.IMPORTED, region = "global")
        val vertexLocation = vertexGlobal.copy(region = "europe-west4")

        assertEquals("https://bedrock-runtime.eu-west-1.amazonaws.com/", ProfileHosts.baseUrl(bedrock, catalog).toString())
        assertEquals(setOf("bedrock-runtime.eu-west-1.amazonaws.com", "bedrock.eu-west-1.amazonaws.com"), ProfileHosts.hosts(bedrock, catalog))
        assertEquals(setOf("bedrock-mantle.us-east-1.api.aws"), ProfileHosts.hosts(mantle, catalog))
        assertEquals(setOf("aiplatform.googleapis.com", "oauth2.googleapis.com"), ProfileHosts.hosts(vertexGlobal, catalog))
        assertEquals(setOf("europe-west4-aiplatform.googleapis.com", "oauth2.googleapis.com"), ProfileHosts.hosts(vertexLocation, catalog))
    }

    @Test
    fun aMissingOrUnsafeRegionGivesNoBaseUrlAndNoTemplateHost() {
        val noRegion = profile("amazon-bedrock", "bedrock-api-key", AuthMethod.API_KEY)
        val unsafe = noRegion.copy(region = "evil.example.com/x")

        assertNull(ProfileHosts.baseUrl(noRegion, catalog))
        assertEquals(emptySet<String>(), ProfileHosts.hosts(noRegion, catalog))
        assertNull(ProfileHosts.baseUrl(unsafe, catalog))
        assertEquals(emptySet<String>(), ProfileHosts.hosts(unsafe, catalog))
    }

    @Test
    fun theProfilesOwnBaseUrlWinsAndItsDiscoveredHostsCount() {
        val minimax = profile(
            "minimax-portal", "minimax-global-oauth", AuthMethod.DEVICE_CODE,
            baseUrl = "https://api-eu.minimax.io/anthropic", discovered = listOf("account.minimax.io"),
        )

        assertEquals(setOf("account.minimax.io", "api-eu.minimax.io"), ProfileHosts.hosts(minimax, catalog))
    }

    @Test
    fun localServersGoToTheLocalListAndPublicAddressesToTheHosts() {
        val lan = profile("ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, baseUrl = "http://192.168.1.20:11434").copy(localServer = LocalServerKind.OLLAMA)
        val phone = lan.copy(baseUrl = "http://127.0.0.1:11434")
        val proxy = profile("litellm", "litellm-server", AuthMethod.SERVER_ADDRESS, baseUrl = "https://llm.example.com/v1")

        assertEquals(emptySet<String>(), ProfileHosts.hosts(lan, catalog))
        assertEquals(setOf("192.168.1.20"), ProfileHosts.localServers(lan))
        assertEquals(setOf("127.0.0.1"), ProfileHosts.localServers(phone))
        assertEquals(setOf("llm.example.com"), ProfileHosts.hosts(proxy, catalog))
        assertEquals(emptySet<String>(), ProfileHosts.localServers(proxy))
    }

    @Test
    fun copilotAllowsItsDefaultApiAndTheHostsThatItsSignInFound() {
        val copilot = profile("github-copilot", "github-copilot", AuthMethod.DEVICE_CODE, discovered = listOf("api.github.com", "api.business.githubcopilot.com"))

        assertEquals(
            setOf("api.business.githubcopilot.com", "api.github.com", "api.individual.githubcopilot.com"),
            ProfileHosts.hosts(copilot, catalog),
        )
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/profiles/ProvidersSettingTest.kt`:

```kotlin
package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.cost.Price
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersSettingTest {
    private val catalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
             {"id": "acme", "plugin": "acme", "label": "Acme", "style": "RESPONSES", "baseUrl": "https://api.acme.test/v1", "hosts": ["api.acme.test"],
              "authChoices": [{"choiceId": "acme-key", "method": "api-key", "label": "Key"}, {"choiceId": "acme-borrowed", "method": "oauth", "label": "Sign in"},
                              {"choiceId": "acme-on", "method": "oauth", "label": "Sign in"}],
              "models": []}]}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [],
            "choices": [{"provider": "acme", "choiceId": "acme-key", "method": "API_KEY"},
                        {"provider": "acme", "choiceId": "acme-borrowed", "method": "DEVICE_CODE", "borrowedIdentity": "acme-cli", "defaultOn": false},
                        {"provider": "acme", "choiceId": "acme-on", "method": "DEVICE_CODE", "borrowedIdentity": "acme-ide", "defaultOn": true}],
            "providers": [], "localServers": [], "models": [], "freeModels": [],
            "unknownPrice": {"input": 5.0, "output": 25.0},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/", "models": [], "lightweight": [], "versatile": [],
                        "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )

    private fun profile(id: String, choice: String) =
        Profile(id, "Name of $id", "acme", choice, AuthMethod.DEVICE_CODE, "cred-$id", lightweight = ProfileModel("a"), versatile = ProfileModel("b"))

    @Test
    fun aSwitchFollowsTheOwnersChoiceElseTheChoicesDefault() {
        val setting = ProvidersSetting()

        assertFalse(setting.switchedOn("acme-cli", catalog))
        assertTrue(setting.switchedOn("acme-ide", catalog))
        assertTrue(setting.copy(signInSwitches = mapOf("acme-cli" to true)).switchedOn("acme-cli", catalog))
        assertFalse(setting.copy(signInSwitches = mapOf("acme-ide" to false)).switchedOn("acme-ide", catalog))
    }

    @Test
    fun aProfileIsUsableUnlessItsBorrowedIdentityIsSwitchedOff() {
        val setting = ProvidersSetting(profiles = listOf(profile("p1", "acme-key"), profile("p2", "acme-borrowed")), defaultProfileId = "p2")

        assertTrue(setting.usable(setting.profiles[0], catalog))
        assertFalse(setting.usable(setting.profiles[1], catalog))
        assertEquals("p2", setting.defaultProfile?.id)
    }

    @Test
    fun theSettingSurvivesJsonAndPrintsNoNamesClientIdsOrAccounts() {
        val setting = ProvidersSetting(
            profiles = listOf(profile("p1", "acme-key").copy(lightweight = ProfileModel("secret-model", price = Price(1.0, 2.0)))),
            chatgptHostId = "urn:uuid:00000000-0000-4000-8000-000000000000",
            chatgptRegistration = ChatGptRegistration("oaiapp_secret", "secret-subject"),
        )

        val json = Json.encodeToString(ProvidersSetting.serializer(), setting)
        val printed = setting.toString() + setting.profiles.single().lightweight.toString()

        assertEquals(setting, Json.decodeFromString(ProvidersSetting.serializer(), json))
        assertFalse(printed, Regex("secret|Name of|urn:uuid").containsMatchIn(printed))
        assertEquals(
            "ProvidersSetting(profiles=[Profile(id=p1, provider=acme)], defaultProfileId=null, signInSwitches={}, chatgptHostId=set, " +
                "chatgptRegistration=ChatGptRegistration(clientIdLength=13, subject=set))",
            setting.toString(),
        )
    }

    @Test
    fun aProfileGivesTheModelOfEachRole() {
        val profile = profile("p1", "acme-key")

        assertEquals("a", profile.model(ModelRole.LIGHTWEIGHT).id)
        assertEquals("b", profile.model(ModelRole.VERSATILE).id)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/credentials/CredentialCodecTest.kt`:

```kotlin
package dev.kestrel.providers.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialCodecTest {
    private val all = listOf(
        Credential.ApiKey("hidden4711-key"),
        Credential.OAuthToken("hidden4711-access", "hidden4711-refresh", 1_800_000_000_000, 1_799_999_000_000, "oaiapp_hidden4711", "https://auth.example.com/token", "global"),
        Credential.GitHubToken("gho_hidden4711", "hidden4711.ghe.com"),
        Credential.AwsAccessKeys("AKIAHIDDEN4711", "hidden4711-access-key", "hidden4711-session"),
        Credential.ServiceAccountKey("hidden4711@project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----\nhidden4711\n", "hidden4711-id", "hidden4711-project"),
    )

    @Test
    fun everyKindSurvivesTheBytes() {
        for (credential in all) {
            assertEquals(credential, CredentialCodec.decode(CredentialCodec.encode(credential)))
        }
        assertEquals("""{"kind":"api-key","key":"k"}""", CredentialCodec.encode(Credential.ApiKey("k")).decodeToString())
    }

    @Test
    fun bytesThatHoldNoCredentialFailWithoutThemInTheMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) { CredentialCodec.decode("""{"kind":"other","key":"hidden4711"}""".encodeToByteArray()) }

        assertEquals("Not a stored credential", error.message)
    }

    @Test
    fun noCredentialPrintsItsSecrets() {
        val printed = all.joinToString()

        assertFalse(printed, printed.contains("4711") || printed.contains("auth.example"))
    }

    @Test
    fun aKeyMustBePrintableAsciiWithoutSpaces() {
        assertEquals(true, ApiKeys.acceptable("  sk-test_ABC.123~  "))
        for (bad in listOf("", "   ", "sk test", "sk-tést", "sk-\u0000x", "sk\t1")) {
            assertEquals(bad, false, ApiKeys.acceptable(bad))
        }
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/ModelListTest.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelListTest {
    @Test
    fun aListSurvivesJsonAndPrintsNoModelNames() {
        val list = ModelList(
            listOf(DiscoveredModel("my-private-finetune", "My private finetune", input = setOf(InputKind.TEXT, InputKind.IMAGE), tools = false)),
            refreshedAt = 1_700_000_000_000,
        )

        val printed = list.toString() + list.models.single().toString()

        assertEquals(list, Json.decodeFromString(ModelList.serializer(), Json.encodeToString(ModelList.serializer(), list)))
        assertFalse(printed, printed.contains("private"))
        assertEquals("ModelList(models=1, refreshedAt=1700000000000)", list.toString())
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'Profile'", "Unresolved reference 'ProfileHosts'", "Unresolved reference 'ProvidersSetting'", "Unresolved reference 'Credential'", "Unresolved reference 'ApiKeys'" and "Unresolved reference 'ModelList'".

- [ ] **Step 3: Write the profiles**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt`:

```kotlin
package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import kotlinx.serialization.Serializable

/**
 * A provider, the sign-in choice that made it, a credential and two models (spec 10.6, design D13). [baseUrl] is set only
 * when it is not the choice's or the catalog's (a custom address, an Azure endpoint, a local server, MiniMax's
 * `resource_url`); [region] is Bedrock's or Mantle's region or Vertex's location; [discoveredHosts] are the hosts that the
 * sign-in and discovery accepted (design D16). The ID and the provider are printed; nothing else, because names and
 * addresses can be the owner's own.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val providerId: String,
    val choiceId: String,
    val method: AuthMethod,
    val credentialId: String?,
    val baseUrl: String? = null,
    val region: String? = null,
    val localServer: LocalServerKind? = null,
    val lightweight: ProfileModel,
    val versatile: ProfileModel,
    val discoveredHosts: List<String> = emptyList(),
    val signedInAt: Long? = null,
) {
    fun model(role: ModelRole): ProfileModel = if (role == ModelRole.LIGHTWEIGHT) lightweight else versatile

    override fun toString(): String = "Profile(id=$id, provider=$providerId)"
}

/**
 * A profile's model for one role: [style] is a style that the owner chose (an Azure deployment's API), [jsonActions] turns
 * on JSON actions (design D7), [price] is a price that the owner typed (design D14), and [images] and [tools] are the
 * owner's switches for a local server's model (design D11). A local server's model names may be the owner's own, so only
 * the ID's length is printed.
 */
@Serializable
data class ProfileModel(
    val id: String,
    val style: RequestStyle? = null,
    val jsonActions: Boolean = false,
    val price: Price? = null,
    val images: Boolean? = null,
    val tools: Boolean? = null,
) {
    override fun toString(): String =
        "ProfileModel(idLength=${id.length}, style=$style, jsonActions=$jsonActions, price=${if (price == null) "none" else "typed"})"
}

/** The lightweight model does most steps; the versatile one takes over when a task needs it (spec 10.6, 11.7). */
@Serializable
enum class ModelRole { LIGHTWEIGHT, VERSATILE }
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/ProvidersSetting.kt`:

```kotlin
package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.Catalog
import kotlinx.serialization.Serializable

/**
 * Every provider setting that Kestrel stores as one encrypted setting (design D13): the profiles, the default profile, the
 * switches of the borrowed sign-ins keyed by identity (design D9; a missing key means the choice's `defaultOn`), the
 * ChatGPT plan's host ID and its kept registration (design D9). Credentials and model lists are stored elsewhere.
 */
@Serializable
data class ProvidersSetting(
    val profiles: List<Profile> = emptyList(),
    val defaultProfileId: String? = null,
    val signInSwitches: Map<String, Boolean> = emptyMap(),
    val chatgptHostId: String? = null,
    val chatgptRegistration: ChatGptRegistration? = null,
) {
    val defaultProfile: Profile? get() = profiles.firstOrNull { it.id == defaultProfileId }

    /** Whether the borrowed sign-in [identity] may be used: the owner's switch, else the choice's `defaultOn`. */
    fun switchedOn(identity: String, catalog: Catalog): Boolean =
        signInSwitches[identity]
            ?: catalog.providers.asSequence().flatMap { it.authChoices }.mapNotNull { it.settings }
                .firstOrNull { it.borrowedIdentity == identity }?.defaultOn
            ?: true

    /** Whether [profile] may be used now: its borrowed identity, if it has one, is switched on. */
    fun usable(profile: Profile, catalog: Catalog): Boolean {
        val identity = catalog.choice(profile.providerId, profile.choiceId)?.settings?.borrowedIdentity ?: return true
        return switchedOn(identity, catalog)
    }

    override fun toString(): String =
        "ProvidersSetting(profiles=$profiles, defaultProfileId=$defaultProfileId, signInSwitches=$signInSwitches, " +
            "chatgptHostId=${if (chatgptHostId == null) "none" else "set"}, chatgptRegistration=$chatgptRegistration)"
}

/**
 * The ChatGPT plan's registration of Kestrel (design D9): the issued client ID (`oaiapp_…`), which is bound to the owner's
 * account, and the ID token's `sub`. It is kept after its profile is deleted. toString prints neither.
 */
@Serializable
data class ChatGptRegistration(val clientId: String, val subject: String?) {
    override fun toString(): String =
        "ChatGptRegistration(clientIdLength=${clientId.length}, subject=${if (subject == null) "none" else "set"})"
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/ProfileHosts.kt`:

```kotlin
package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.http.Hosts
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The one rule for a profile's base URL and for the hosts that a saved profile adds to the allowlist (design D13 and D16),
 * so that the allowlist and the requests cannot disagree.
 */
object ProfileHosts {
    private val REGION = Regex("^[a-z0-9-]+$")

    /**
     * The effective base URL: the profile's own base URL, else the choice's, else the base URL of the region that the
     * profile chose when the provider lists it, else the provider's; with `{region}` and `{location}` filled in from the
     * profile. Null when there is none or a placeholder stays empty.
     */
    fun baseUrl(profile: Profile, catalog: Catalog): HttpUrl? {
        val provider = catalog.provider(profile.providerId)
        val raw = profile.baseUrl
            ?: catalog.choice(profile.providerId, profile.choiceId)?.settings?.baseUrl
            ?: provider?.regions?.firstOrNull { it.id == profile.region }?.baseUrl
            ?: provider?.baseUrl
            ?: return null
        return fill(raw, profile.region)?.toHttpUrlOrNull()
    }

    /**
     * The public hosts that [profile] needs: its base URL's host; when neither the profile nor the choice sets a base URL,
     * the provider's hosts and its models' base URL hosts, with the region filled in; the choice's refresh hosts; and the
     * hosts that the sign-in and discovery accepted. Local hosts are left out, because they go to [localServers].
     */
    fun hosts(profile: Profile, catalog: Catalog): Set<String> {
        val provider = catalog.provider(profile.providerId)
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        val hosts = sortedSetOf<String>()
        baseUrl(profile, catalog)?.let { hosts += it.host }
        if (provider != null && profile.baseUrl == null && choice?.baseUrl == null) {
            for (host in provider.hosts + provider.models.mapNotNull { CatalogLoader.hostOf(it.baseUrl) }) {
                fill(host, profile.region)?.let { hosts += it }
            }
        }
        choice?.refreshHosts?.forEach { hosts += it }
        profile.discoveredHosts.forEach { hosts += it }
        return hosts.map { it.lowercase() }.filterNot(Hosts::isLocal).toSortedSet()
    }

    /** The host of a profile whose own address is local, for NetworkPolicy.setLocalServers (design D11). */
    fun localServers(profile: Profile): Set<String> =
        profile.baseUrl?.toHttpUrlOrNull()?.host?.takeIf(Hosts::isLocal)?.let { setOf(it) } ?: emptySet()

    private fun fill(text: String, region: String?): String? {
        if ("{region}" !in text && "{location}" !in text) return text
        if (region == null || !REGION.matches(region)) return null
        return text.replace("{region}", region).replace("{location}", region)
    }
}
```

- [ ] **Step 4: Write the credentials and the discovered models**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/credentials/Credential.kt`:

```kotlin
package dev.kestrel.providers.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A credential as data (design D15). `:core:privacy`'s CredentialStore keeps it encrypted; its toString prints only its
 * kind and lengths, never a key, a token, a client ID or a project.
 */
@Serializable
sealed interface Credential {
    @Serializable
    @SerialName("api-key")
    data class ApiKey(val key: String) : Credential {
        override fun toString(): String = "ApiKey(length=${key.length})"
    }

    /** An OAuth sign-in; [clientId] is the ChatGPT plan's issued `oaiapp_…` ID (design D9). */
    @Serializable
    @SerialName("oauth-token")
    data class OAuthToken(
        val access: String,
        val refresh: String?,
        val expiresAtMillis: Long?,
        val earliestRefreshAtMillis: Long? = null,
        val clientId: String? = null,
        val tokenEndpoint: String? = null,
        val region: String? = null,
    ) : Credential {
        override fun toString(): String =
            "OAuthToken(accessLength=${access.length}, refresh=${if (refresh == null) "none" else "set"}, " +
                "expiresAtMillis=$expiresAtMillis, earliestRefreshAtMillis=$earliestRefreshAtMillis, " +
                "clientId=${if (clientId == null) "none" else "set"}, tokenEndpoint=${if (tokenEndpoint == null) "none" else "set"}, region=$region)"
    }

    /**
     * Copilot's GitHub token, which is the bearer of every Copilot call and never expires on its own (design D12). A GHE.com
     * tenant's domain names the owner's organisation, so toString says only whether the domain is a tenant's.
     */
    @Serializable
    @SerialName("github-token")
    data class GitHubToken(val token: String, val domain: String) : Credential {
        override fun toString(): String = "GitHubToken(length=${token.length}, tenant=${domain != "github.com"})"
    }

    @Serializable
    @SerialName("aws-access-keys")
    data class AwsAccessKeys(val accessKeyId: String, val secretAccessKey: String, val sessionToken: String? = null) : Credential {
        override fun toString(): String =
            "AwsAccessKeys(accessKeyIdLength=${accessKeyId.length}, secretLength=${secretAccessKey.length}, " +
                "sessionToken=${if (sessionToken == null) "none" else "set"})"
    }

    /** The four fields that Kestrel keeps of a Google service-account key file (design D9). */
    @Serializable
    @SerialName("service-account-key")
    data class ServiceAccountKey(val clientEmail: String, val privateKeyPem: String, val privateKeyId: String?, val projectId: String) : Credential {
        override fun toString(): String =
            "ServiceAccountKey(clientEmailLength=${clientEmail.length}, privateKeyLength=${privateKeyPem.length}, " +
                "privateKeyId=${if (privateKeyId == null) "none" else "set"}, projectIdLength=${projectId.length})"
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/credentials/CredentialCodec.kt`:

```kotlin
package dev.kestrel.providers.credentials

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Turns a [Credential] into the bytes that `:app`'s StoredCredentials encrypts, and back (design D15). */
object CredentialCodec {
    private val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
    }

    fun encode(credential: Credential): ByteArray = json.encodeToString(Credential.serializer(), credential).encodeToByteArray()

    /** Throws IllegalArgumentException, without the bytes in its message, when [bytes] hold no credential. */
    fun decode(bytes: ByteArray): Credential =
        try {
            json.decodeFromString(Credential.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not a stored credential")
        }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/credentials/CredentialVault.kt`:

```kotlin
package dev.kestrel.providers.credentials

/** Where credentials are kept: `:app` implements it over `:core:privacy`'s CredentialStore (design D1 and D15). */
interface CredentialVault {
    /** Null when there is no credential with [id] or it could not be read. */
    suspend fun read(id: String): Credential?

    /** Stores [credential] under [id], replacing an earlier one. */
    suspend fun write(id: String, credential: Credential)

    suspend fun delete(id: String)
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/credentials/ApiKeys.kt`:

```kotlin
package dev.kestrel.providers.credentials

/** The rule for a pasted API key (design D9). */
object ApiKeys {
    /**
     * Whether [key], trimmed, can be sent as a header: not empty, and only printable ASCII without spaces (0x21 to 0x7e).
     * OkHttp refuses other characters with an exception whose message holds the value.
     */
    fun acceptable(key: String): Boolean {
        val trimmed = key.trim()
        return trimmed.isNotEmpty() && trimmed.all { it in '!'..'~' }
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/ModelDiscovery.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import kotlinx.serialization.Serializable

/**
 * A model that a provider's own list reports (design D13). A local server's model names may be the owner's own, so
 * toString prints only lengths and kinds.
 */
@Serializable
data class DiscoveredModel(
    val id: String,
    val name: String,
    val input: Set<InputKind> = setOf(InputKind.TEXT),
    val reasoning: Boolean = false,
    val reasoningEfforts: List<String>? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val tools: Boolean? = null,
    val streaming: Boolean? = null,
    val style: RequestStyle? = null,
) {
    override fun toString(): String =
        "DiscoveredModel(idLength=${id.length}, input=$input, reasoning=$reasoning, tools=$tools, style=$style)"
}

/** Reads a provider's model list; it runs only when a profile is created or the owner taps "Refresh models". */
fun interface ModelDiscovery {
    suspend fun list(): List<DiscoveredModel>
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/ModelList.kt`:

```kotlin
package dev.kestrel.providers.discovery

import kotlinx.serialization.Serializable

/**
 * A profile's last model list with its time (design D13), kept in a setting of its own (`provider-models-<profile ID>`),
 * because lists of hundreds of rows should not be rewritten at every change of a profile. Deleting a profile writes an
 * empty list.
 */
@Serializable
data class ModelList(val models: List<DiscoveredModel> = emptyList(), val refreshedAt: Long? = null) {
    override fun toString(): String = "ModelList(models=${models.size}, refreshedAt=$refreshedAt)"
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with 71 tests in `:core:providers` (55 + 16): `ProfileHostsTest` 7, `ProvidersSettingTest` 4, `CredentialCodecTest` 4 and `ModelListTest` 1.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` 71, `:tools:catalog-sync` 6; `:app` A₀, `:core:privacy` P₀ + 5, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt core/providers/src/main/kotlin/dev/kestrel/providers/profiles/ProvidersSetting.kt core/providers/src/main/kotlin/dev/kestrel/providers/profiles/ProfileHosts.kt core/providers/src/main/kotlin/dev/kestrel/providers/credentials/Credential.kt core/providers/src/main/kotlin/dev/kestrel/providers/credentials/CredentialCodec.kt core/providers/src/main/kotlin/dev/kestrel/providers/credentials/CredentialVault.kt core/providers/src/main/kotlin/dev/kestrel/providers/credentials/ApiKeys.kt core/providers/src/main/kotlin/dev/kestrel/providers/discovery/ModelDiscovery.kt core/providers/src/main/kotlin/dev/kestrel/providers/discovery/ModelList.kt core/providers/src/test/kotlin/dev/kestrel/providers/profiles/ProfileHostsTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/profiles/ProvidersSettingTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/credentials/CredentialCodecTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/discovery/ModelListTest.kt
git commit -m "feat(providers): add profiles with their base URL and hosts, credentials as data and discovered models" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: Cost

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/cost/PriceTable.kt`, `CostMeter.kt`, `Spending.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 10.6)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/cost/PriceTableTest.kt`, `CostMeterTest.kt`, `SpendingTest.kt`

**Interfaces:**
- Consumes: Task 4's `Price`, `PriceTier`, `Catalog`, `CatalogLoader.COPILOT` and `CatalogLoader.copilotPrice`; Task 7's `Profile` and `ProfileModel`; Task 1's `Usage`; Task 2's `Hosts`.
- Produces (package `dev.kestrel.providers.cost`): `data class PriceChoice(val price: Price, val known: Boolean, val planUsage: Boolean = false)`; `class PriceTable(catalog: Catalog) { fun priceFor(profile: Profile, modelId: String, modelName: String?, day: LocalDate): PriceChoice }`; `data class Cost(val usd: Double, val credits: Double, val estimated: Boolean, val priceKnown: Boolean, val planUsage: Boolean)`; `object CostMeter { fun cost(usage: Usage, choice: PriceChoice): Cost }`; `@Serializable data class MonthUse(val credits: Double = 0.0, val tokens: Long = 0)`; `@Serializable data class Spending(val months: Map<String, Map<String, MonthUse>> = emptyMap())` with `fun add(profileId: String, month: String, credits: Double, tokens: Long): Spending`, `fun of(profileId: String, month: String): MonthUse` and `companion object { fun monthOf(epochMillis: Long): String }`. Plan 4C's `ProfileClient` prices each call with these and adds it to `SettingKeys.SPENDING`; its pages show the month's credits or tokens.

Design D12 and D14. `PriceTable.priceFor` chooses, in this order: for a choice with `planUsage`, no per-token price (`Price.FREE`, `known`, `planUsage`), because the owner's plan pays and Kestrel cannot see what a call costs it; the price that the owner typed for the model on the profile page; the catalog's price that holds on the day (UTC; `validFrom` inclusive, `validUntil` exclusive), for Copilot from `overrides.copilot` by ID or normalised name, so that a model that only Copilot's list knows still finds its price; `Price.FREE` for a local server at a local address and for a model of `freeModels` (a local-server kind at a public address may forward to paid models, so it is not free); and otherwise the unknown price of the overrides with `known = false`. `CostMeter.cost` takes the long-context tier with the highest threshold below the request's whole input (cache reads and writes included, "> 272K" with K = 1,000), counts a missing cache price at the input price and reasoning as output, and gives credits as US dollars × 100 (1 AI Credit = $0.01, B28). A call that a plan pays for costs 0 credits. `Spending` keeps each profile's credits and tokens per calendar month in UTC, for this month and the one before, because Copilot's allowance resets at 00:00 UTC on the 1st (research r28).

- [ ] **Step 1: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/cost/PriceTableTest.kt`:

```kotlin
package dev.kestrel.providers.cost

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PriceTableTest {
    private val catalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
             {"id": "acme", "plugin": "acme", "label": "Acme", "style": "CHAT_COMPLETIONS", "baseUrl": "https://api.acme.test/v1", "hosts": ["api.acme.test"],
              "authChoices": [{"choiceId": "acme-key", "method": "api-key", "label": "Key"}, {"choiceId": "acme-plan", "method": "oauth", "label": "Plan"}],
              "models": [{"id": "dated", "name": "Dated", "prices": [{"input": 1.0, "output": 2.0, "validUntil": "2026-12-01"}, {"input": 3.0, "output": 4.0, "validFrom": "2026-12-01"}]},
                         {"id": "ended", "name": "Ended", "prices": [{"input": 1.0, "output": 2.0, "validUntil": "2026-12-01"}]},
                         {"id": "zero", "name": "Zero"}, {"id": "gift", "name": "Gift"}]},
             {"id": "github-copilot", "plugin": "github-copilot", "label": "Copilot", "style": "RESPONSES", "baseUrl": "https://api.individual.githubcopilot.com",
              "hosts": [], "authChoices": [{"choiceId": "github-copilot", "method": "device", "label": "Copilot"}], "models": []},
             {"id": "ollama", "plugin": "ollama", "label": "Ollama", "style": "OLLAMA", "baseUrl": null, "hosts": [],
              "authChoices": [], "models": []}]}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [],
            "choices": [{"provider": "acme", "choiceId": "acme-key", "method": "API_KEY"},
                        {"provider": "acme", "choiceId": "acme-plan", "method": "DEVICE_CODE", "planUsage": true},
                        {"provider": "github-copilot", "choiceId": "github-copilot", "method": "DEVICE_CODE"},
                        {"provider": "ollama", "choiceId": "ollama-server", "method": "SERVER_ADDRESS"}],
            "providers": [], "localServers": [], "models": [], "freeModels": [{"provider": "acme", "model": "gift"}],
            "unknownPrice": {"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/",
                        "models": [{"name": "GPT-6.1 Terra", "prices": [{"input": 1.0, "output": 5.0}]},
                                   {"name": "Claude Sonnet 5.5", "id": "claude-sonnet-5.5", "prices": [{"input": 2.0, "output": 10.0}]}],
                        "lightweight": [], "versatile": [], "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )
    private val table = PriceTable(catalog)
    private val day = LocalDate.parse("2026-11-17")
    private val unknown = PriceChoice(Price(5.0, 25.0, 0.5, 6.25), known = false)

    private fun profile(provider: String, choice: String, typed: Price? = null, baseUrl: String? = null, server: LocalServerKind? = null) =
        Profile("test-1", "Test", provider, choice, AuthMethod.API_KEY, null, baseUrl = baseUrl, localServer = server,
            lightweight = ProfileModel("dated", price = typed), versatile = ProfileModel("other"))

    @Test
    fun aPlanSignInHasNoPerTokenPrice() {
        assertEquals(PriceChoice(Price.FREE, known = true, planUsage = true), table.priceFor(profile("acme", "acme-plan"), "dated", null, day))
    }

    @Test
    fun theOwnersPriceWinsOverTheCatalogs() {
        val typed = Price(0.5, 0.5)

        assertEquals(PriceChoice(typed, known = true), table.priceFor(profile("acme", "acme-key", typed = typed), "dated", null, day))
        assertEquals(unknown, table.priceFor(profile("acme", "acme-key", typed = typed), "zero", null, day))
    }

    @Test
    fun theCatalogsPriceIsTheOneOfTheDay() {
        val key = profile("acme", "acme-key")

        assertEquals(1.0, table.priceFor(key, "dated", null, day).price.input, 0.0)
        assertEquals(1.0, table.priceFor(key, "dated", null, LocalDate.parse("2026-11-30")).price.input, 0.0)
        assertEquals(3.0, table.priceFor(key, "dated", null, LocalDate.parse("2026-12-01")).price.input, 0.0)
        assertEquals(unknown, table.priceFor(key, "ended", null, LocalDate.parse("2026-12-01")))
    }

    @Test
    fun copilotMatchesByIdOrNormalisedNameAndElseUsesTheUnknownPrice() {
        val copilot = profile("github-copilot", "github-copilot")

        assertEquals(PriceChoice(Price(1.0, 5.0), known = true), table.priceFor(copilot, "gpt-6.1-terra", "GPT-6.1 Terra", day))
        assertEquals(PriceChoice(Price(2.0, 10.0), known = true), table.priceFor(copilot, "claude-sonnet-5.5", null, day))
        assertEquals(unknown, table.priceFor(copilot, "gpt-7", "GPT-7", day))
    }

    @Test
    fun aLocalServerAtALocalAddressAndAListedModelAreFreeButAPublicServerIsNot() {
        val lan = profile("ollama", "ollama-server", baseUrl = "http://192.168.1.20:11434", server = LocalServerKind.OLLAMA)
        val ipv6 = lan.copy(baseUrl = "http://[::1]:11434")
        val public = lan.copy(baseUrl = "https://llm.example.com")

        assertEquals(PriceChoice(Price.FREE, known = true), table.priceFor(lan, "llama", null, day))
        assertEquals(PriceChoice(Price.FREE, known = true), table.priceFor(ipv6, "llama", null, day))
        assertEquals(unknown, table.priceFor(public, "llama", null, day))
        assertEquals(PriceChoice(Price.FREE, known = true), table.priceFor(profile("acme", "acme-key"), "gift", null, day))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/cost/CostMeterTest.kt`:

```kotlin
package dev.kestrel.providers.cost

import dev.kestrel.providers.chat.Usage
import org.junit.Assert.assertEquals
import org.junit.Test

class CostMeterTest {
    private val luna = Price(
        input = 0.10, output = 0.50, cacheRead = 0.01, cacheWrite = 0.125,
        tiers = listOf(PriceTier(aboveInputTokens = 272_000, input = 0.20, output = 0.75, cacheRead = 0.02, cacheWrite = 0.25)),
    )

    @Test
    fun eachBucketHasItsPriceAndCreditsAreCents() {
        val cost = CostMeter.cost(Usage(input = 100_000, output = 2_000, cacheRead = 50_000, cacheWrite = 10_000), PriceChoice(luna, known = true))

        // 100,000 × 0.10 + 2,000 × 0.50 + 50,000 × 0.01 + 10,000 × 0.125 = 12,750 per million, so $0.01275.
        assertEquals(0.01275, cost.usd, 1e-12)
        assertEquals(1.275, cost.credits, 1e-9)
        assertEquals(Cost(cost.usd, cost.credits, estimated = false, priceKnown = true, planUsage = false), cost)
    }

    @Test
    fun theTierAppliesWhenTheWholeInputIsAboveItsThreshold() {
        val below = CostMeter.cost(Usage(input = 200_000, output = 0, cacheRead = 72_000), PriceChoice(luna, known = true))
        val above = CostMeter.cost(Usage(input = 200_000, output = 0, cacheRead = 72_001), PriceChoice(luna, known = true))

        assertEquals((200_000 * 0.10 + 72_000 * 0.01) / 1e6, below.usd, 1e-12)
        assertEquals((200_000 * 0.20 + 72_001 * 0.02) / 1e6, above.usd, 1e-12)
    }

    @Test
    fun aMissingCachePriceCountsAtTheInputPrice() {
        val gemini = Price(input = 1.5, output = 7.5, cacheRead = 0.075)

        val cost = CostMeter.cost(Usage(input = 0, output = 0, cacheRead = 1_000_000, cacheWrite = 1_000_000), PriceChoice(gemini, known = true))

        assertEquals(0.075 + 1.5, cost.usd, 1e-12)
    }

    @Test
    fun theUnknownPriceReachesTenCreditsWithTwentyThousandInputTokens() {
        val cost = CostMeter.cost(Usage(input = 20_000, output = 0, estimated = true), PriceChoice(Price(5.0, 25.0, 0.5, 6.25), known = false))

        assertEquals(10.0, cost.credits, 1e-9)
        assertEquals(false, cost.priceKnown)
        assertEquals(true, cost.estimated)
    }

    @Test
    fun aPlanPaysSoTheCallCostsNoCredits() {
        val cost = CostMeter.cost(Usage(input = 1_000_000, output = 1_000_000), PriceChoice(Price.FREE, known = true, planUsage = true))

        assertEquals(Cost(0.0, 0.0, estimated = false, priceKnown = true, planUsage = true), cost)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/cost/SpendingTest.kt`:

```kotlin
package dev.kestrel.providers.cost

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SpendingTest {
    @Test
    fun callsAddUpPerProfileAndMonth() {
        val spending = Spending()
            .add("p1", "2026-11", credits = 1.5, tokens = 1_000)
            .add("p1", "2026-11", credits = 0.5, tokens = 500)
            .add("p2", "2026-11", credits = 2.0, tokens = 10)

        assertEquals(MonthUse(2.0, 1_500), spending.of("p1", "2026-11"))
        assertEquals(MonthUse(2.0, 10), spending.of("p2", "2026-11"))
        assertEquals(MonthUse(), spending.of("p3", "2026-11"))
    }

    @Test
    fun onlyThisMonthAndTheOneBeforeAreKept() {
        val spending = Spending()
            .add("p1", "2026-10", 1.0, 1)
            .add("p1", "2026-11", 1.0, 1)
            .add("p1", "2026-12", 1.0, 1)
            .add("p1", "2027-01", 1.0, 1)

        assertEquals(setOf("2026-12", "2027-01"), spending.months.keys)
        assertEquals(spending, Json.decodeFromString(Spending.serializer(), Json.encodeToString(Spending.serializer(), spending)))
    }

    @Test
    fun theMonthIsTheUtcMonth() {
        assertEquals("2026-11", Spending.monthOf(Instant.parse("2026-11-30T23:59:59Z").toEpochMilli()))
        assertEquals("2026-12", Spending.monthOf(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()))
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'PriceTable'", "Unresolved reference 'PriceChoice'", "Unresolved reference 'CostMeter'", "Unresolved reference 'Cost'", "Unresolved reference 'Spending'" and "Unresolved reference 'MonthUse'".

- [ ] **Step 3: Write the price table, the meter and the spending**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/cost/PriceTable.kt`:

```kotlin
package dev.kestrel.providers.cost

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.http.Hosts
import dev.kestrel.providers.profiles.Profile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate

/**
 * The price that a call is counted at, whether Kestrel knows it, and whether the owner's plan pays for the call instead
 * (design D14).
 */
data class PriceChoice(val price: Price, val known: Boolean, val planUsage: Boolean = false)

/**
 * Chooses the price of a model for a profile (design D14), in this order: a plan sign-in (no per-token price); the price
 * that the owner typed; the catalog's price for the day (Copilot's from `overrides.copilot` by ID or normalised name);
 * free for a local server at a local address and for the models that overrides.json lists as free; otherwise the unknown
 * price, which errs on the safe side.
 */
class PriceTable(private val catalog: Catalog) {
    fun priceFor(profile: Profile, modelId: String, modelName: String?, day: LocalDate): PriceChoice {
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        if (choice?.planUsage == true) return PriceChoice(Price.FREE, known = true, planUsage = true)
        listOf(profile.lightweight, profile.versatile).firstOrNull { it.id == modelId && it.price != null }?.let {
            return PriceChoice(it.price!!, known = true)
        }
        val prices = if (profile.providerId == CatalogLoader.COPILOT) {
            CatalogLoader.copilotPrice(catalog.overrides.copilot, modelId, modelName)?.prices
        } else {
            catalog.provider(profile.providerId)?.models?.firstOrNull { it.id == modelId }?.prices
        }
        on(prices.orEmpty(), day)?.let { return PriceChoice(it, known = true) }
        val localHost = profile.localServer != null && profile.baseUrl?.toHttpUrlOrNull()?.host?.let(Hosts::isLocal) == true
        val free = catalog.overrides.freeModels.any { it.provider == profile.providerId && it.model == modelId }
        if (localHost || free) return PriceChoice(Price.FREE, known = true)
        return PriceChoice(catalog.overrides.unknownPrice, known = false)
    }

    /** The price that holds on [day] (UTC): from its `validFrom` on, and before its `validUntil`. */
    private fun on(prices: List<Price>, day: LocalDate): Price? =
        prices.firstOrNull { price ->
            (price.validFrom == null || !day.isBefore(LocalDate.parse(price.validFrom))) &&
                (price.validUntil == null || day.isBefore(LocalDate.parse(price.validUntil)))
        }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/cost/CostMeter.kt`:

```kotlin
package dev.kestrel.providers.cost

import dev.kestrel.providers.chat.Usage

/**
 * The cost of one call (design D14): [credits] are GitHub AI Credits, where 1 credit is $0.01 (B28). A call that a plan
 * pays for costs 0 credits and has [planUsage]; [estimated] says that the provider reported no usage.
 */
data class Cost(val usd: Double, val credits: Double, val estimated: Boolean, val priceKnown: Boolean, val planUsage: Boolean)

object CostMeter {
    /**
     * Prices [usage] at [choice]: the long-context tier applies when the whole input (cache reads and writes included) is
     * above its threshold, a missing cache price counts those tokens at the input price, and reasoning is part of output.
     */
    fun cost(usage: Usage, choice: PriceChoice): Cost {
        if (choice.planUsage) return Cost(0.0, 0.0, usage.estimated, priceKnown = true, planUsage = true)
        val price = choice.price
        val tier = price.tiers.filter { usage.totalInput > it.aboveInputTokens }.maxByOrNull { it.aboveInputTokens }
        val input = tier?.input ?: price.input
        val output = tier?.output ?: price.output
        val cacheRead = (if (tier != null) tier.cacheRead else price.cacheRead) ?: input
        val cacheWrite = (if (tier != null) tier.cacheWrite else price.cacheWrite) ?: input
        val usd = (usage.input * input + usage.output * output + usage.cacheRead * cacheRead + usage.cacheWrite * cacheWrite) / 1_000_000.0
        return Cost(usd, credits = usd * 100, estimated = usage.estimated, priceKnown = choice.known, planUsage = false)
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/cost/Spending.kt`:

```kotlin
package dev.kestrel.providers.cost

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** The credits and the tokens of one profile in one month (design D14). */
@Serializable
data class MonthUse(val credits: Double = 0.0, val tokens: Long = 0)

/**
 * The spending of each profile per calendar month in UTC (month `yyyy-MM` → profile ID → use), for the current month and
 * the one before, because Copilot's allowance resets at 00:00 UTC on the 1st (research r28). Kept in one encrypted setting.
 */
@Serializable
data class Spending(val months: Map<String, Map<String, MonthUse>> = emptyMap()) {
    /** Adds one call and keeps only [month] and the month before it. */
    fun add(profileId: String, month: String, credits: Double, tokens: Long): Spending {
        val before = YearMonth.parse(month).minusMonths(1).toString()
        val current = months[month].orEmpty()
        val use = current[profileId] ?: MonthUse()
        val updated = months + (month to current + (profileId to MonthUse(use.credits + credits, use.tokens + tokens)))
        return Spending(updated.filterKeys { it >= before })
    }

    fun of(profileId: String, month: String): MonthUse = months[month]?.get(profileId) ?: MonthUse()

    companion object {
        /** The UTC month of [epochMillis], as `yyyy-MM`. */
        fun monthOf(epochMillis: Long): String = YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)).toString()
    }
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with 84 tests in `:core:providers` (71 + 13): `PriceTableTest` 5, `CostMeterTest` 5 and `SpendingTest` 3.

- [ ] **Step 5: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
- A profile combines a provider, a credential, a lightweight model and a versatile model. The owner can create several profiles and choose a default.
```

with

```markdown
- A profile combines a provider, the sign-in choice that made it, a credential, a lightweight model and a versatile model, and where needed a region, a base URL of its own and the hosts that its sign-in found. The owner can create several profiles and choose a default. A profile's base URL is its own, else its sign-in choice's, else that of its region, else the provider's.
```

and replace

```markdown
- Kestrel estimates the cost locally from token counts and prices, per task and per month, and shows both. Copilot costs are shown in AI Credits. Other providers' costs are shown in US dollars and converted at 1 credit = $0.01, so that one cap works for every provider.
```

with

```markdown
- Kestrel estimates the cost locally from token counts and prices, per task and per month, and shows both. A price may have a long-context tier, which applies when the whole input of a request, cache reads and writes included, is above its threshold; a cache-read and a cache-write price, and a missing one counts those tokens at the input price; and days from and until which it holds. A price that the owner typed for a model wins over the catalog's. A local server at a local address and a model that `catalog/overrides.json` lists as free cost nothing, and a model whose price Kestrel does not know is counted at $5 per million input tokens and $25 per million output tokens, so that the cap errs on the safe side. Copilot costs are shown in AI Credits. Other providers' costs are shown in US dollars and converted at 1 credit = $0.01, so that one cap works for every provider. Sign-ins and keys whose subscription pays for the requests (the ChatGPT plan, xAI's and MiniMax's sign-ins and the providers' coding plans) count tokens but no credits, so the cap does not cover them. The spending is kept per profile and calendar month in UTC.
```

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` 84, `:tools:catalog-sync` 6; `:app` A₀, `:core:privacy` P₀ + 5, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/providers/src/main/kotlin/dev/kestrel/providers/cost/PriceTable.kt core/providers/src/main/kotlin/dev/kestrel/providers/cost/CostMeter.kt core/providers/src/main/kotlin/dev/kestrel/providers/cost/Spending.kt core/providers/src/test/kotlin/dev/kestrel/providers/cost/PriceTableTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/cost/CostMeterTest.kt core/providers/src/test/kotlin/dev/kestrel/providers/cost/SpendingTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(providers): price calls in AI Credits with tiers, dates, plan usage and the unknown price, and keep the month's spending" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: The catalog, the vault and the allowlist in `:app`

**Files:**
- Modify: `app/build.gradle.kts` (the catalog in the assets), `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/providers/StoredCredentials.kt`, `ProviderAllowlist.kt`
- Modify: `README.md` (the section `## Providers` with its two markers), `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 13.1)
- Test: `app/src/test/kotlin/dev/kestrel/app/providers/ProviderAllowlistTest.kt`, `HostsAgreementTest.kt`; `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt` (device, changed)

**Interfaces:**
- Consumes: Task 3's `PrivacyStore.credentials`, `CredentialStore`, `LogPathTag`, `NetworkPolicy.setProfileHosts`, `setLocalServers` and `openTemporaryLocalServers`; the gateway's `TaskTag`, `BlockedRequestException` and `callFactory`; Task 2's `ProviderHttp`, `RequestDecorator` and `ProviderError.NotAllowed`; Task 4's `Catalog` and `CatalogLoader`; Task 7's `ProvidersSetting`, `ProfileHosts`, `CredentialVault`, `CredentialCodec` and `ModelList`; Task 8's `Spending`; `LocalHosts.isLocal`.
- Produces (package `dev.kestrel.app`): `SettingKeys.PROVIDERS = SettingKey("providers", ProvidersSetting.serializer(), ProvidersSetting())`, `SettingKeys.SPENDING = SettingKey("spending", Spending.serializer(), Spending())`, `fun SettingKeys.providerModels(profileId: String): SettingKey<ModelList>` (name `provider-models-<id>`); `AppGraph.providerHttp: ProviderHttp`, `AppGraph.catalog: Catalog` (lazy, from `assets/catalog/providers.json` and `assets/catalog/overrides.json`), `AppGraph.credentialVault: CredentialVault`, `AppGraph.appliedProviders: StateFlow<ProvidersSetting?>`, and the marker `    // Plan 4C: provider graph` in the class body after them. Package `dev.kestrel.app.providers`: `internal class StoredCredentials(store: CredentialStore) : CredentialVault`; `internal object ProviderAllowlist { fun profileHosts(setting: ProvidersSetting, catalog: Catalog): Set<String>; fun localServers(setting: ProvidersSetting): Set<String> }`. README markers: `<!-- Plan 4B: request styles -->` and `<!-- Plan 4C: sign-in, Copilot and the session -->`. Plan 4C builds `ProviderGraph` at the marker on `providerHttp`, `catalog`, `credentialVault` and `appliedProviders`.

Design D1, D2, D4, D13, D15 and D16. `providerHttp` passes the gateway's call factory to `ProviderHttp` with a decorator that tags each request with its task (`TaskTag`) and the path for the network log (`LogPathTag`) and sets `User-Agent: Kestrel/<versionName>` when no other is set, so that Copilot's own `User-Agent` wins (D4); its `classify` turns the gateway's `BlockedRequestException` into `ProviderError.NotAllowed` (spec 14.2). `catalog` reads the two files that `app/build.gradle.kts` copies into the assets, on first use, off the main thread. `credentialVault` stores each credential's JSON in `:core:privacy`'s store; a credential that it cannot decode reads as missing, so its profile asks the owner to sign in again (D15). The allowlist job starts with `AppGraph`: at start and after each change of `SettingKeys.PROVIDERS`, it gives `NetworkPolicy` the hosts of every profile whose borrowed sign-in is on and the local servers, and then publishes the setting as `appliedProviders`, which plan 4C's `ProfileClient` waits for before a request (D13, D16). So a host leaves the allowlist when the last profile that needs it is deleted or its borrowed sign-in is switched off, and hosts of providers without a profile are never on it. A failure of one update is logged by its classes only, and the next change tries again; if reading the setting fails, the job stops updating until the process restarts, and the allowlist keeps the hosts of the last update. Because the job replaces the local servers, the existing device test `AppGraphTest` leases its local server with `openTemporaryLocalServers` instead of setting it, and this task runs that test, in a process where the job has started. `HostsAgreementTest` runs `:core:privacy`'s `LocalHosts` and `:core:providers`' `Hosts` over one table, so that the two copies of the rule cannot drift (D1).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/providers/ProviderAllowlistTest.kt`:

```kotlin
package dev.kestrel.app.providers

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAllowlistTest {
    private val catalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
             {"id": "acme", "plugin": "acme", "label": "Acme", "style": "RESPONSES", "baseUrl": "https://api.acme.test/v1", "hosts": ["api.acme.test"],
              "authChoices": [{"choiceId": "acme-key", "method": "api-key", "label": "Key"}, {"choiceId": "acme-cli", "method": "oauth", "label": "CLI"}],
              "models": []},
             {"id": "ollama", "plugin": "ollama", "label": "Ollama", "style": "OLLAMA", "baseUrl": null, "hosts": [], "authChoices": [], "models": []}]}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [],
            "choices": [{"provider": "acme", "choiceId": "acme-key", "method": "API_KEY"},
                        {"provider": "acme", "choiceId": "acme-cli", "method": "DEVICE_CODE", "baseUrl": "https://proxy.acme.test/v1",
                         "refreshHosts": ["auth.acme.test"], "borrowedIdentity": "acme-cli", "defaultOn": false},
                        {"provider": "ollama", "choiceId": "ollama-server", "method": "SERVER_ADDRESS"}],
            "providers": [], "localServers": [], "models": [], "freeModels": [],
            "unknownPrice": {"input": 5.0, "output": 25.0},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/", "models": [], "lightweight": [], "versatile": [],
                        "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )

    private fun profile(id: String, provider: String, choice: String, method: AuthMethod, baseUrl: String? = null) =
        Profile(id, "Test", provider, choice, method, "test-$id", baseUrl = baseUrl, lightweight = ProfileModel("a"), versatile = ProfileModel("b"))

    private val key = profile("test-1", "acme", "acme-key", AuthMethod.API_KEY)
    private val secondKey = profile("test-2", "acme", "acme-key", AuthMethod.API_KEY)
    private val signIn = profile("test-3", "acme", "acme-cli", AuthMethod.DEVICE_CODE).copy(discoveredHosts = listOf("tenant.acme.test"))
    private val lan = profile("test-4", "ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, "http://192.168.1.20:11434").copy(localServer = LocalServerKind.OLLAMA)

    @Test
    fun theHostsOfEveryUsableProfileAreAllowedOnce() {
        val setting = ProvidersSetting(profiles = listOf(key, secondKey, signIn, lan))

        assertEquals(setOf("api.acme.test"), ProviderAllowlist.profileHosts(setting, catalog))
    }

    @Test
    fun aBorrowedSignInAddsItsHostsOnlyWhileItsSwitchIsOn() {
        val on = ProvidersSetting(profiles = listOf(key, signIn), signInSwitches = mapOf("acme-cli" to true))

        assertEquals(
            setOf("api.acme.test", "auth.acme.test", "proxy.acme.test", "tenant.acme.test"),
            ProviderAllowlist.profileHosts(on, catalog),
        )
        assertEquals(setOf("api.acme.test"), ProviderAllowlist.profileHosts(on.copy(signInSwitches = mapOf("acme-cli" to false)), catalog))
    }

    @Test
    fun localServersGoToTheirOwnListAndNothingIsAllowedWithoutProfiles() {
        assertEquals(setOf("192.168.1.20"), ProviderAllowlist.localServers(ProvidersSetting(profiles = listOf(key, lan))))
        assertEquals(emptySet<String>(), ProviderAllowlist.profileHosts(ProvidersSetting(), catalog))
        assertEquals(emptySet<String>(), ProviderAllowlist.localServers(ProvidersSetting()))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/providers/HostsAgreementTest.kt`:

```kotlin
package dev.kestrel.app.providers

import dev.kestrel.privacy.network.LocalHosts
import dev.kestrel.providers.http.Hosts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `:core:providers` copies `:core:privacy`'s LocalHosts rules, because it may not use InetAddress (design spec, section
 * 13.2). This table keeps the two from drifting: a host that one calls local and the other public would be allowed over
 * plain HTTP by one and refused by the other.
 */
class HostsAgreementTest {
    @Test
    fun bothRulesAgreeOnEveryHost() {
        val hosts = listOf(
            "localhost", "LOCALHOST", "printer.local", "local", "example.local.net",
            "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.0.1", "172.15.0.1", "172.16.0.1", "172.31.255.255", "172.32.0.1",
            "192.168.0.1", "192.169.0.1", "169.254.1.1", "169.255.1.1", "8.8.8.8", "256.0.0.1", "1.2.3", "1.2.3.4.5", "01.2.3.4",
            "::1", "[::1]", "fc00::1", "fd12:3456::1", "fe80::1", "fe90::1", "2001:db8::1", "[2001:db8::1]",
            "api.openai.com", "cli-chat-proxy.grok.com", "openrouter.ai",
        )

        for (host in hosts) {
            assertEquals(host, LocalHosts.isLocal(host), Hosts.isLocal(host))
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests 'dev.kestrel.app.providers.*'`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ProviderAllowlist'". (`HostsAgreementTest` alone would pass, because both rules exist.)

- [ ] **Step 3: Write the vault and the allowlist's rule**

Create `app/src/main/kotlin/dev/kestrel/app/providers/StoredCredentials.kt`:

```kotlin
package dev.kestrel.app.providers

import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialCodec
import dev.kestrel.providers.credentials.CredentialVault

/**
 * The providers' credentials in `:core:privacy`'s encrypted store (design spec, section 10.7): `:core:providers` gives a
 * credential as data, and the store keeps its bytes under the credentials key. A credential that cannot be read is null,
 * so that its profile asks the owner to sign in again.
 */
internal class StoredCredentials(private val store: CredentialStore) : CredentialVault {
    override suspend fun read(id: String): Credential? {
        val bytes = store.get(id) ?: return null
        return try {
            CredentialCodec.decode(bytes)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun write(id: String, credential: Credential) {
        store.put(id, CredentialCodec.encode(credential))
    }

    override suspend fun delete(id: String) {
        store.delete(id)
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/providers/ProviderAllowlist.kt`:

```kotlin
package dev.kestrel.app.providers

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProvidersSetting

/**
 * What the stored profiles add to the network allowlist (design spec, section 13.1): the public hosts of every profile
 * whose borrowed sign-in is not switched off, and the local servers, which alone may use plain HTTP. Hosts of providers
 * without a profile are never on it.
 */
internal object ProviderAllowlist {
    fun profileHosts(setting: ProvidersSetting, catalog: Catalog): Set<String> =
        setting.profiles.filter { setting.usable(it, catalog) }.flatMapTo(sortedSetOf()) { ProfileHosts.hosts(it, catalog) }

    fun localServers(setting: ProvidersSetting): Set<String> =
        setting.profiles.flatMapTo(sortedSetOf()) { ProfileHosts.localServers(it) }
}
```

- [ ] **Step 4: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests 'dev.kestrel.app.providers.*'`
Expected: `BUILD SUCCESSFUL`, with 4 tests: `ProviderAllowlistTest` 3 and `HostsAgreementTest` 1.

- [ ] **Step 5: Copy the catalog into the app's assets**

In `app/build.gradle.kts`, replace

```kotlin
        variant.sources.assets?.addGeneratedSourceDirectory(licences, CopyAssetsTask::outputDirectory)
    }
}
```

with

```kotlin
        variant.sources.assets?.addGeneratedSourceDirectory(licences, CopyAssetsTask::outputDirectory)
        // The provider catalog and its overrides, which AppGraph merges on first use (design spec, section 10.2).
        val catalog = tasks.register<CopyAssetsTask>("${variant.name}CatalogAssets") {
            val root = rootProject.layout.projectDirectory
            files.from(root.file("catalog/providers.json"), root.file("catalog/overrides.json"))
            folder.set("catalog")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(catalog, CopyAssetsTask::outputDirectory)
    }
}
```

- [ ] **Step 6: Wire the providers into `AppGraph`**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.privacy.PrivacyStore
```

with

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.providers.ProviderAllowlist
import dev.kestrel.app.providers.StoredCredentials
import dev.kestrel.privacy.PrivacyStore
```

and replace

```kotlin
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
```

with

```kotlin
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.network.LogPathTag
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.network.TaskTag
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.discovery.ModelList
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.safety.SafetySettings
```

and replace

```kotlin
import java.io.File
```

with

```kotlin
import java.io.File
import java.io.IOException
```

and replace

```kotlin
    /** The options of the instant commands: "Require verbs" and where the numbers go (design spec, sections 9.8 and 15). */
    val COMMANDS = SettingKey("commands", CommandOptions.serializer(), CommandOptions())
}
```

with

```kotlin
    /** The options of the instant commands: "Require verbs" and where the numbers go (design spec, sections 9.8 and 15). */
    val COMMANDS = SettingKey("commands", CommandOptions.serializer(), CommandOptions())

    /**
     * The provider profiles, the default profile, the switches of the borrowed sign-ins and the ChatGPT plan's registration
     * (design spec, section 10.6). Credentials are not in it.
     */
    val PROVIDERS = SettingKey("providers", ProvidersSetting.serializer(), ProvidersSetting())

    /** Each profile's credits and tokens of this month and the one before (design spec, section 10.6). */
    val SPENDING = SettingKey("spending", Spending.serializer(), Spending())
}

/**
 * The last model list of the profile [profileId], in a setting of its own, because lists of hundreds of rows should not be
 * rewritten at every change of a profile. Deleting a profile writes an empty list (design D13 of sub-project 4).
 */
fun SettingKeys.providerModels(profileId: String): SettingKey<ModelList> =
    SettingKey("provider-models-$profileId", ModelList.serializer(), ModelList())
```

and replace

```kotlin
    // Built on first use: building the HTTP client on the main thread made Kestrel miss Android's start-up deadline
    // on a busy emulator just after it booted.
    val network: NetworkGateway by lazy { NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } } }
```

with

```kotlin
    // Built on first use: building the HTTP client on the main thread made Kestrel miss Android's start-up deadline
    // on a busy emulator just after it booted.
    val network: NetworkGateway by lazy { NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } } }

    /**
     * The providers' requests (design spec, sections 7.4 and 13.1): each one goes through the network gateway, carries its
     * task and the path for the network log as tags and Kestrel's User-Agent when it names no other, and a request that the
     * allowlist refuses becomes ProviderError.NotAllowed. Built on first use, like the gateway.
     */
    val providerHttp: ProviderHttp by lazy {
        val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
        ProviderHttp(
            calls = network.callFactory,
            decorator = { builder, meta ->
                meta.taskId?.let { builder.tag(TaskTag::class.java, TaskTag(it)) }
                meta.logPath?.let { builder.tag(LogPathTag::class.java, LogPathTag(it)) }
                if (builder.build().header("User-Agent") == null) builder.header("User-Agent", "Kestrel/$version")
            },
            classify = { error -> if (error is BlockedRequestException) ProviderError.NotAllowed else null },
        )
    }

    /** The provider catalog that the app bundles (design spec, section 10.2), read from the assets on first use. */
    val catalog: Catalog by lazy {
        fun asset(name: String) = appContext.assets.open("catalog/$name").use { it.readBytes().decodeToString() }
        CatalogLoader.load(asset("providers.json"), asset("overrides.json"))
    }

    /** The providers' credentials, encrypted under their own key (design spec, section 10.7). */
    val credentialVault: CredentialVault by lazy { StoredCredentials(store.credentials) }

    private val appliedSetting = MutableStateFlow<ProvidersSetting?>(null)

    /**
     * The provider setting whose hosts the network allowlist holds, null until the first update after the start. Plan 4C's
     * ProfileClient waits for it before a request, so that a new profile's hosts are allowed first.
     */
    val appliedProviders: StateFlow<ProvidersSetting?> = appliedSetting.asStateFlow()

    init {
        keepProviderHosts()
    }

    /**
     * Keeps the allowlist in step with the stored profiles (design spec, section 13.1): at start and after each change, the
     * hosts of every profile whose borrowed sign-in is on go to the allowlist and the local servers to its list of local
     * servers, and then the applied setting is published. A failed update is logged by its classes only, and the next
     * change tries again. If reading the setting fails, the flow ends: the allowlist keeps the hosts of the last update
     * until the process restarts.
     */
    private fun keepProviderHosts() {
        scope.launch {
            settings.observe(SettingKeys.PROVIDERS)
                .catch { reportFailure("Reading the provider setting", it) }
                .collect { setting ->
                    try {
                        networkPolicy.setProfileHosts(ProviderAllowlist.profileHosts(setting, catalog))
                        networkPolicy.setLocalServers(ProviderAllowlist.localServers(setting))
                        appliedSetting.value = setting
                    } catch (e: IllegalArgumentException) {
                        reportFailure("Updating the allowlist", e)
                    } catch (e: IOException) {
                        reportFailure("Updating the allowlist", e)
                    }
                }
        }
    }

    // Plan 4C: provider graph
```

- [ ] **Step 7: Let `AppGraphTest` lease its local server**

From now on, the allowlist job calls `setLocalServers` at start, after it reads the setting and the catalog. If that came between the test's own `setLocalServers` and its request, the request would be blocked; the job never touches the leases.

In `app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt`, replace

```kotlin
    private suspend fun requestLocalServerOverPlainHttp(host: String) {
        graph.logs.deleteHistory()
        val server = MockWebServer()
        server.start(InetAddress.getByName(host), 0)
        try {
            graph.networkPolicy.setLocalServers(listOf(host))
            server.enqueue(MockResponse.Builder().body("hello").build())
            val request = Request.Builder().url("http://$host:${server.port}/v1/models?key=secret").build()

            val response = graph.network.callFactory.newCall(request).execute()

            assertEquals(200 to "hello", response.use { it.code to it.body.string() })
            val entry = withTimeout(5_000) {
                var local = graph.logs.network().firstOrNull { it.event.host == host }
                while (local == null) {
                    delay(50)
                    local = graph.logs.network().firstOrNull { it.event.host == host }
                }
                local
            }
            assertEquals(NetworkOutcome.COMPLETED, entry.event.outcome)
            assertEquals(200, entry.event.status)
            assertEquals("/v1/models", entry.event.path)
        } finally {
            graph.networkPolicy.setLocalServers(emptyList())
            server.close()
        }
    }
```

with

```kotlin
    private suspend fun requestLocalServerOverPlainHttp(host: String) {
        graph.logs.deleteHistory()
        val server = MockWebServer()
        server.start(InetAddress.getByName(host), 0)
        try {
            // A lease, because AppGraph's allowlist job replaces the local servers with those of the provider profiles at
            // start and after each change of the profiles (design D11 and D16 of sub-project 4).
            graph.networkPolicy.openTemporaryLocalServers(listOf(host)).use {
                server.enqueue(MockResponse.Builder().body("hello").build())
                val request = Request.Builder().url("http://$host:${server.port}/v1/models?key=secret").build()

                val response = graph.network.callFactory.newCall(request).execute()

                assertEquals(200 to "hello", response.use { it.code to it.body.string() })
            }
            val entry = withTimeout(5_000) {
                var local = graph.logs.network().firstOrNull { it.event.host == host }
                while (local == null) {
                    delay(50)
                    local = graph.logs.network().firstOrNull { it.event.host == host }
                }
                local
            }
            assertEquals(NetworkOutcome.COMPLETED, entry.event.outcome)
            assertEquals(200, entry.event.status)
            assertEquals("/v1/models", entry.event.path)
        } finally {
            server.close()
        }
    }
```

- [ ] **Step 8: Add the README's section**

In `README.md`, replace

```markdown
## Licence
```

with

```markdown
## Providers

Kestrel sends its requests to the LLM provider of a profile that you set up (design spec, section 10). The providers that it offers come from OpenClaw's provider manifests at the commit in `catalog/openclaw.lock` (MIT License; see `THIRD_PARTY_NOTICES.md`). `scripts\sync-catalog.ps1` makes a shallow, sparse checkout of OpenClaw's repository at that commit in `build\openclaw\<commit>` and runs the Gradle task `:tools:catalog-sync:syncCatalog`, which writes `catalog/providers.json` and prints how many manifests, providers and models it read; `-Commit <hash>` moves the catalog to another commit and writes that commit into the lock. The script fetches only OpenClaw's repository. `catalog/overrides.json` is kept by hand: the providers and sign-in choices that Kestrel leaves out, each with the reason; the settings of each sign-in choice that Kestrel offers, among them the borrowed sign-ins of GitHub Copilot, xAI and MiniMax, of which only Copilot's is on until you turn the others on, and the links to their providers' terms; the providers that OpenClaw defines in code, such as Bedrock, Vertex and Microsoft Foundry; the local servers; and the prices that the manifests lack, among them Copilot's per-token prices and Google's Gemini prices, each with the day it was copied. The app merges both files from its assets, so a change of the overrides needs no new sync. A unit test checks that `providers.json` names the commit in the lock, and another that every name in the overrides exists.

Kestrel counts the cost of each request on the phone from the tokens that the provider reports and these prices, in GitHub AI Credits, where one credit is one US cent. It counts a model whose price it does not know at $5 per million input tokens and $25 per million output tokens until you type the model's price. For a sign-in or key whose subscription pays for the requests, such as xAI's or MiniMax's sign-in, it counts tokens only.

Credentials are encrypted with their own Android Keystore key, in StrongBox when the phone has it, and are never logged or exported. Only the hosts of your profiles are on the network allowlist; a sign-in's other hosts are allowed only while the sign-in runs.

<!-- Plan 4B: request styles -->
<!-- Plan 4C: sign-in, Copilot and the session -->

## Licence
```

- [ ] **Step 9: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
- Its allowlist contains the hosts of the configured profiles, including the hosts they need for token refresh. A provider's other sign-in hosts are added only while a sign-in runs. Requests to any other host are refused and logged.
```

with

```markdown
- Its allowlist contains, for each profile whose borrowed sign-in is not switched off: the host of the profile's base URL; the provider's hosts with the profile's region filled in, unless the profile or its sign-in choice sets its own base URL; the refresh hosts of its sign-in choice; and the hosts that its sign-in and model discovery accepted, such as Copilot's API host. Kestrel updates the allowlist at start and whenever the profiles change, so a host leaves it when the last profile that needs it is deleted or its sign-in is switched off, and hosts of providers without a profile are never on it. A provider's other sign-in hosts, and a new profile's hosts while the owner checks it, are allowed only while that runs. Requests to any other host are refused and logged.
```

- [ ] **Step 10: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:app` A₀ + 4; `:core:providers` 84, `:tools:catalog-sync` 6, `:core:privacy` P₀ + 5, `:core:commands` C₀, `:core:voice` V₀, `:core:screen` S₀ and `:core:safety` F₀, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit. The dependency report `app\build\reports\kestrel-audit\dependencies-release.txt` lists no new library: `:core:providers` brings only OkHttp, Okio, kotlinx.serialization and kotlinx.coroutines, which the app already had.

- [ ] **Step 11: Run `AppGraphTest` and start the app once on the emulator**

Wait for the emulator lock and take it:

Run: `$deadline = (Get-Date).AddMinutes(60); while (Test-Path .superpowers\sdd\emulator.lock) { if ((Get-Date) -gt $deadline) { throw 'BLOCKED: the emulator lock still exists after 60 minutes' }; Start-Sleep -Seconds 120 }; Set-Content .superpowers\sdd\emulator.lock 'plan 4A task 9'`
Expected: no output. If it throws, report BLOCKED to the controller. Delete the lock at the end of this step, also when a command fails: `Remove-Item .superpowers\sdd\emulator.lock`.

Clear the log, so that it holds every start of Kestrel's process from now on (Android may restart the process right after the install), and install the debug build:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 logcat -c; scripts\install.ps1 -Serial emulator-5554`
Expected: the audit passes and the install ends with "Success".

Check that the APK carries the catalog:

Run: `Add-Type -AssemblyName System.IO.Compression.FileSystem; $apk = [IO.Compression.ZipFile]::OpenRead((Resolve-Path app\build\outputs\apk\debug\app-debug.apk)); try { $apk.Entries | Where-Object FullName -like 'assets/catalog/*' | ForEach-Object FullName } finally { $apk.Dispose() }`
Expected: `assets/catalog/overrides.json` and `assets/catalog/providers.json`.

Run `AppGraphTest`, whose process starts the allowlist job, so that its tests also check that the gateway, the network log and a local-server lease work beside the job:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.AppGraphTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`, and the result file in `app\build\outputs\androidTest-results\connected\debug\` shows the 4 tests of `AppGraphTest` and 0 failures. The count O₀ does not change.

Then start Kestrel, check that its process runs, and read Kestrel's warnings 10 seconds later:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; $adb = Get-Adb; & $adb -s emulator-5554 shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 10; & $adb -s emulator-5554 shell pidof dev.kestrel; & $adb -s emulator-5554 logcat -d -s Kestrel:W | Select-String 'Updating the allowlist failed|Reading the provider setting failed|A background task failed'`
Expected: `Status: ok` from `am start`, a process ID (a number) from `pidof`, and no line from `Select-String`: in every start of the process since the install, the allowlist job read the empty provider setting and the catalog from the assets without a failure.

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add app/build.gradle.kts app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/androidTest/kotlin/dev/kestrel/app/AppGraphTest.kt app/src/main/kotlin/dev/kestrel/app/providers/StoredCredentials.kt app/src/main/kotlin/dev/kestrel/app/providers/ProviderAllowlist.kt app/src/test/kotlin/dev/kestrel/app/providers/ProviderAllowlistTest.kt app/src/test/kotlin/dev/kestrel/app/providers/HostsAgreementTest.kt README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): wire the providers' HTTP seam, catalog and credential vault, and keep the allowlist in step with the profiles" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Counts at the end of this plan

`:core:providers` JVM 84, `:tools:catalog-sync` JVM 6, `:app` JVM A₀ + 4, `:core:privacy` JVM P₀ + 5, `:core:commands` JVM C₀, `:core:voice` JVM V₀, `:core:screen` JVM S₀, `:core:safety` JVM F₀; the device tests of `:core:privacy` Q₀ + 4; the command device tests K₀, the other device tests of `:app` O₀ and the voice device tests W₀, unchanged; Pester T₀ + 6 in each shell.
